package com.educore.api.communication;

import static com.educore.api.communication.CommunicationController.*;
import static com.educore.api.communication.CommunicationSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CommunicationService {
    private static final Set<String> CATEGORIES = Set.of(
            "GENERAL", "ACADEMIC", "ATTENDANCE", "FEES", "TRANSPORT", "EVENT", "EMERGENCY", "OTHER");
    private static final Set<String> TRANSACTIONAL_CATEGORIES = Set.of(
            "ACADEMIC", "ATTENDANCE", "FEES", "TRANSPORT", "EMERGENCY");
    private static final Set<String> PRIORITIES = Set.of("NORMAL", "IMPORTANT", "URGENT", "EMERGENCY");
    private static final Set<String> LANGUAGES = Set.of("ENGLISH", "HINDI", "BILINGUAL");
    private static final Set<String> AUDIENCES = Set.of(
            "PUBLIC", "ALL_STAFF", "ALL_FAMILIES", "ALL_STUDENTS",
            "SECTION_STAFF", "SECTION_FAMILIES", "SECTION_STUDENTS",
            "LEARNER_FAMILIES", "LEARNER_STUDENT");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommunicationService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit,
            ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CommunicationSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId,
                Set.of("communication.send", "communication.configure", "communication.approve"));
        List<ProviderItem> providers = jdbc.query("""
                SELECT channel,environment,provider_code,sender_identity,status
                FROM communication_provider_configs
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND (:broadAccess OR status='ACTIVE')
                ORDER BY channel,environment
                """, scope.params(), (result, row) -> new ProviderItem(
                result.getString("channel"), result.getString("environment"),
                result.getString("provider_code"), result.getString("sender_identity"),
                result.getString("status")));
        List<TemplateItem> templates = jdbc.query("""
                SELECT * FROM communication_templates
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND (:broadAccess OR status='ACTIVE')
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                  display_name,revision_number DESC,id
                """, scope.params(), (result, row) -> new TemplateItem(
                result.getObject("id", UUID.class), result.getString("template_code"),
                result.getInt("revision_number"), result.getObject("supersedes_template_id", UUID.class),
                result.getString("display_name"), result.getString("category"),
                result.getString("language_mode"), result.getString("title_en"), result.getString("title_hi"),
                result.getString("body_en"), result.getString("body_hi"),
                result.getBoolean("default_requires_acknowledgement"),
                channels(result.getBoolean("default_email"), result.getBoolean("default_sms"),
                        result.getBoolean("default_whatsapp"), result.getBoolean("default_push")),
                result.getString("status"), result.getLong("row_version")));
        List<SectionOption> sections = jdbc.query("""
                SELECT section.id,section.display_name,section.grade_code,session.name AS session_name
                FROM class_sections section
                JOIN academic_sessions session ON session.id=section.academic_session_id
                WHERE section.organisation_id=:organisationId AND section.school_id=:schoolId
                  AND section.status='ACTIVE'
                  AND (:broadAccess OR EXISTS (
                    SELECT 1 FROM section_subject_offerings offering
                    JOIN teaching_allocations allocation
                      ON allocation.section_subject_offering_id=offering.id
                    WHERE offering.class_section_id=section.id AND offering.status='ACTIVE'
                      AND allocation.teacher_membership_id=:actorMembershipId
                      AND allocation.status='ACTIVE'))
                ORDER BY session.starts_on DESC,section.grade_code,section.display_name,section.id
                """, scope.params(), (result, row) -> new SectionOption(
                result.getObject("id", UUID.class), result.getString("display_name"),
                result.getString("grade_code"), result.getString("session_name")));
        List<LearnerOption> learners = jdbc.query("""
                SELECT learner.id,learner.legal_name,enrolment.admission_number,enrolment.grade_code,
                       placement.class_section_id,section.display_name AS section_name
                FROM learners learner
                JOIN student_enrolments enrolment ON enrolment.learner_id=learner.id
                 AND enrolment.status='ACTIVE'
                LEFT JOIN student_section_placements placement ON placement.enrolment_id=enrolment.id
                 AND placement.status='ACTIVE'
                LEFT JOIN class_sections section ON section.id=placement.class_section_id
                WHERE learner.organisation_id=:organisationId AND learner.school_id=:schoolId
                  AND learner.status='ACTIVE'
                  AND (:broadAccess OR EXISTS (
                    SELECT 1 FROM section_subject_offerings offering
                    JOIN teaching_allocations allocation
                      ON allocation.section_subject_offering_id=offering.id
                    WHERE offering.class_section_id=placement.class_section_id
                      AND offering.status='ACTIVE'
                      AND allocation.teacher_membership_id=:actorMembershipId
                      AND allocation.status='ACTIVE'))
                ORDER BY learner.legal_name,enrolment.starts_on DESC,learner.id
                """, scope.params(), (result, row) -> new LearnerOption(
                result.getObject("id", UUID.class), result.getString("legal_name"),
                result.getString("admission_number"), result.getString("grade_code"),
                result.getObject("class_section_id", UUID.class), result.getString("section_name")));
        List<AnnouncementItem> announcements = jdbc.query("""
                SELECT announcement.*,creator.display_name AS creator_name,decider.display_name AS decider_name,
                  CASE announcement.audience_kind
                    WHEN 'PUBLIC' THEN 'Public website'
                    WHEN 'ALL_STAFF' THEN 'All active staff'
                    WHEN 'ALL_FAMILIES' THEN 'All active families'
                    WHEN 'ALL_STUDENTS' THEN 'All active student accounts'
                    WHEN 'SECTION_STAFF' THEN concat('Staff · ',section.display_name)
                    WHEN 'SECTION_FAMILIES' THEN concat('Families · ',section.display_name)
                    WHEN 'SECTION_STUDENTS' THEN concat('Students · ',section.display_name)
                    WHEN 'LEARNER_FAMILIES' THEN concat('Family · ',learner.legal_name)
                    WHEN 'LEARNER_STUDENT' THEN concat('Student · ',learner.legal_name)
                  END AS audience_label,
                  (SELECT count(*) FROM communication_recipients recipient
                    WHERE recipient.communication_announcement_id=announcement.id) recipient_count,
                  (SELECT count(*) FROM communication_recipients recipient
                    WHERE recipient.communication_announcement_id=announcement.id
                      AND recipient.contact_eligible) eligible_count,
                  (SELECT count(*) FROM communication_recipients recipient
                    WHERE recipient.communication_announcement_id=announcement.id
                      AND recipient.read_at IS NOT NULL) read_count,
                  (SELECT count(*) FROM communication_recipients recipient
                    WHERE recipient.communication_announcement_id=announcement.id
                      AND recipient.acknowledged_at IS NOT NULL) ack_count
                FROM communication_announcements announcement
                JOIN memberships creator_membership ON creator_membership.id=announcement.created_by_membership_id
                JOIN user_accounts creator ON creator.id=creator_membership.user_id
                LEFT JOIN memberships decider_membership ON decider_membership.id=announcement.decided_by_membership_id
                LEFT JOIN user_accounts decider ON decider.id=decider_membership.user_id
                LEFT JOIN class_sections section ON section.id=announcement.audience_reference_id
                 AND announcement.audience_kind IN ('SECTION_STAFF','SECTION_FAMILIES','SECTION_STUDENTS')
                LEFT JOIN learners learner ON learner.id=announcement.audience_reference_id
                 AND announcement.audience_kind IN ('LEARNER_FAMILIES','LEARNER_STUDENT')
                WHERE announcement.organisation_id=:organisationId AND announcement.school_id=:schoolId
                  AND (:broadAccess OR announcement.created_by_membership_id=:actorMembershipId)
                ORDER BY announcement.created_at DESC,announcement.id
                """, scope.params(), (result, row) -> new AnnouncementItem(
                result.getObject("id", UUID.class), result.getString("category"),
                result.getString("priority"), result.getString("audience_kind"),
                result.getObject("audience_reference_id", UUID.class), result.getString("audience_label"),
                result.getString("language_mode"), result.getString("title_en"), result.getString("title_hi"),
                result.getString("body_en"), result.getString("body_hi"),
                result.getBoolean("requires_acknowledgement"),
                channels(result.getBoolean("request_email"), result.getBoolean("request_sms"),
                        result.getBoolean("request_whatsapp"), result.getBoolean("request_push")),
                instant(result, "scheduled_for"), instantNullable(result, "expires_at"),
                result.getString("status"), result.getString("creator_name"),
                result.getString("decider_name"), result.getString("decision_reason"),
                result.getInt("recipient_count"), result.getInt("eligible_count"),
                result.getInt("read_count"), result.getInt("ack_count"), result.getLong("row_version")));
        return new CommunicationSnapshot(scope.schoolId(), scope.schoolName(), scope.canConfigure(),
                scope.canSend(), scope.canApprove(), providers, templates, sections, learners, announcements);
    }

    @Transactional
    public CommunicationMutationResponse createTemplate(AuthenticatedUser user, TemplateRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "communication.configure");
        validateTemplateRevision(scope, request, null);
        Content content = content(request.languageMode(), request.titleEn(), request.titleHi(),
                request.bodyEn(), request.bodyHi());
        UUID id = UUID.randomUUID();
        safe("COMMUNICATION_TEMPLATE_EXISTS", () -> jdbc.update("""
                INSERT INTO communication_templates(id,organisation_id,school_id,template_code,revision_number,
                  supersedes_template_id,display_name,category,language_mode,title_en,title_hi,body_en,body_hi,
                  default_requires_acknowledgement,default_email,default_sms,default_whatsapp,default_push,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:code,:revision,:supersedesId,:name,:category,:language,
                  :titleEn,:titleHi,:bodyEn,:bodyHi,:ack,:email,:sms,:whatsapp,:push,'DRAFT',
                  :actorMembershipId,:now,:now,0)
                """, templateParams(scope, id, request, content, null)));
        audit(user, scope, "communication.template.created", "COMMUNICATION_TEMPLATE", id, request.reason());
        return new CommunicationMutationResponse(id, 0, 0);
    }

    @Transactional
    public CommunicationMutationResponse updateTemplate(AuthenticatedUser user, UUID id, TemplateRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "communication.configure");
        long version = requiredVersion(request.expectedVersion());
        validateTemplateRevision(scope, request, id);
        Content content = content(request.languageMode(), request.titleEn(), request.titleHi(),
                request.bodyEn(), request.bodyHi());
        int changed = safe("COMMUNICATION_TEMPLATE_EXISTS", () -> jdbc.update("""
                UPDATE communication_templates SET display_name=:name,category=:category,language_mode=:language,
                  title_en=:titleEn,title_hi=:titleHi,body_en=:bodyEn,body_hi=:bodyHi,
                  default_requires_acknowledgement=:ack,default_email=:email,default_sms=:sms,
                  default_whatsapp=:whatsapp,default_push=:push,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND template_code=:code AND revision_number=:revision
                  AND supersedes_template_id IS NOT DISTINCT FROM :supersedesId
                  AND status='DRAFT' AND row_version=:version
                """, templateParams(scope, id, request, content, version)));
        changed(changed);
        audit(user, scope, "communication.template.updated", "COMMUNICATION_TEMPLATE", id, reason(request.reason()));
        return new CommunicationMutationResponse(id, version + 1, 0);
    }

    @Transactional
    public CommunicationMutationResponse activateTemplate(AuthenticatedUser user, UUID id, VersionedReason request) {
        Scope scope = requireScope(user, request.schoolId(), "communication.configure");
        List<StoredTemplate> rows = jdbc.query("""
                SELECT template_code,supersedes_template_id,status,row_version
                FROM communication_templates
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                FOR UPDATE
                """, scope.params().addValue("id", id), (result, row) -> new StoredTemplate(
                result.getString("template_code"), result.getObject("supersedes_template_id", UUID.class),
                result.getString("status"), result.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("COMMUNICATION_TEMPLATE_NOT_FOUND", "The communication template was not found.");
        StoredTemplate template = rows.getFirst();
        if (!"DRAFT".equals(template.status()) || template.version() != request.expectedVersion())
            throw conflict("STALE_VERSION", "The draft template changed. Refresh and try again.");
        Instant now = Instant.now(clock);
        if (template.supersedesId() != null) {
            int retired = jdbc.update("""
                    UPDATE communication_templates SET status='RETIRED',updated_at=:now,row_version=row_version+1
                    WHERE id=:supersedesId AND organisation_id=:organisationId AND school_id=:schoolId
                      AND template_code=:code AND status='ACTIVE'
                    """, scope.params().addValue("supersedesId", template.supersedesId())
                    .addValue("code", template.code()).addValue("now", now));
            if (retired != 1) throw conflict("TEMPLATE_REVISION_SOURCE_INVALID",
                    "The superseded template must be the active revision.");
        }
        int changed = jdbc.update("""
                UPDATE communication_templates SET status='ACTIVE',activated_by_membership_id=:actorMembershipId,
                  activated_at=:now,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("now", now)
                .addValue("version", request.expectedVersion()));
        changed(changed);
        audit(user, scope, "communication.template.activated", "COMMUNICATION_TEMPLATE", id, request.reason());
        return new CommunicationMutationResponse(id, request.expectedVersion() + 1, 0);
    }

    @Transactional
    public CommunicationMutationResponse createAnnouncement(AuthenticatedUser user, AnnouncementRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "communication.send");
        AnnouncementValues values = announcementValues(scope, request);
        validateAudienceScope(scope, values.audienceKind(), values.audienceReferenceId());
        UUID id = UUID.randomUUID();
        safe("COMMUNICATION_ANNOUNCEMENT_EXISTS", () -> jdbc.update("""
                INSERT INTO communication_announcements(id,organisation_id,school_id,communication_template_id,
                  category,priority,audience_kind,audience_reference_id,language_mode,title_en,title_hi,body_en,body_hi,
                  requires_acknowledgement,request_email,request_sms,request_whatsapp,request_push,scheduled_for,
                  expires_at,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:templateId,:category,:priority,:audienceKind,:audienceReferenceId,
                  :language,:titleEn,:titleHi,:bodyEn,:bodyHi,:ack,:email,:sms,:whatsapp,:push,:scheduledFor,
                  :expiresAt,'DRAFT',:actorMembershipId,:now,:now,0)
                """, announcementParams(scope, id, values, null)));
        history(scope, id, "CREATED", null, "DRAFT", clean(request.reason(), "Draft created"));
        audit(user, scope, "communication.announcement.created", "COMMUNICATION_ANNOUNCEMENT", id, request.reason());
        return new CommunicationMutationResponse(id, 0, 0);
    }

    @Transactional
    public CommunicationMutationResponse updateAnnouncement(AuthenticatedUser user, UUID id,
            AnnouncementRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "communication.send");
        long version = requiredVersion(request.expectedVersion());
        AnnouncementValues values = announcementValues(scope, request);
        validateAudienceScope(scope, values.audienceKind(), values.audienceReferenceId());
        int changed = safe("COMMUNICATION_ANNOUNCEMENT_EXISTS", () -> jdbc.update("""
                UPDATE communication_announcements SET communication_template_id=:templateId,category=:category,
                  priority=:priority,audience_kind=:audienceKind,audience_reference_id=:audienceReferenceId,
                  language_mode=:language,title_en=:titleEn,title_hi=:titleHi,body_en=:bodyEn,body_hi=:bodyHi,
                  requires_acknowledgement=:ack,request_email=:email,request_sms=:sms,
                  request_whatsapp=:whatsapp,request_push=:push,scheduled_for=:scheduledFor,expires_at=:expiresAt,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                  AND (created_by_membership_id=:actorMembershipId OR :canConfigure)
                """, announcementParams(scope, id, values, version)));
        changed(changed);
        history(scope, id, "UPDATED", "DRAFT", "DRAFT", reason(request.reason()));
        audit(user, scope, "communication.announcement.updated", "COMMUNICATION_ANNOUNCEMENT", id, request.reason());
        return new CommunicationMutationResponse(id, version + 1, 0);
    }

    @Transactional
    public CommunicationMutationResponse submitAnnouncement(AuthenticatedUser user, UUID id, VersionedReason request) {
        Scope scope = requireScope(user, request.schoolId(), "communication.send");
        StoredAnnouncement announcement = storedAnnouncement(scope, id, false);
        if (!"DRAFT".equals(announcement.status()) || announcement.version() != request.expectedVersion())
            throw conflict("STALE_VERSION", "The draft announcement changed. Refresh and try again.");
        if (announcement.expiresAt() != null && !announcement.expiresAt().isAfter(Instant.now(clock)))
            throw bad("ANNOUNCEMENT_ALREADY_EXPIRED", "Move the expiry time into the future before submitting.");
        validateAudienceScope(scope, announcement.audienceKind(), announcement.audienceReferenceId());
        int changed = jdbc.update("""
                UPDATE communication_announcements SET status='PENDING_APPROVAL',
                  submitted_by_membership_id=:actorMembershipId,submitted_at=:now,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                  AND (created_by_membership_id=:actorMembershipId OR :canConfigure)
                """, scope.params().addValue("id", id).addValue("version", request.expectedVersion())
                .addValue("canConfigure", scope.canConfigure()).addValue("now", Instant.now(clock)));
        changed(changed);
        history(scope, id, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", request.reason());
        audit(user, scope, "communication.announcement.submitted", "COMMUNICATION_ANNOUNCEMENT", id, request.reason());
        return new CommunicationMutationResponse(id, request.expectedVersion() + 1, 0);
    }

    @Transactional
    public CommunicationMutationResponse decideAnnouncement(AuthenticatedUser user, UUID id, DecisionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "communication.approve");
        StoredAnnouncement announcement = storedAnnouncement(scope, id, true);
        if (!"PENDING_APPROVAL".equals(announcement.status()) || announcement.version() != request.expectedVersion())
            throw conflict("STALE_VERSION", "The pending announcement changed. Refresh and try again.");
        if (announcement.creatorMembershipId().equals(scope.actorMembershipId()))
            throw conflict("COMMUNICATION_MAKER_CHECKER_REQUIRED",
                    "A different authorized membership must decide this announcement.");
        String decision = enumValue(request.decision(), Set.of("APPROVED", "REJECTED"), "decision");
        int recipientCount = 0;
        if ("APPROVED".equals(decision)) {
            if (announcement.expiresAt() != null && !announcement.expiresAt().isAfter(Instant.now(clock)))
                throw bad("ANNOUNCEMENT_ALREADY_EXPIRED", "An expired announcement cannot be approved.");
            recipientCount = freezeRecipients(scope, announcement);
            int eligibleCount = "PUBLIC".equals(announcement.audienceKind()) ? 0 : jdbc.queryForObject("""
                    SELECT count(*) FROM communication_recipients
                    WHERE communication_announcement_id=:id AND organisation_id=:organisationId
                      AND school_id=:schoolId AND contact_eligible
                    """, scope.params().addValue("id", id), Integer.class);
            if (!"PUBLIC".equals(announcement.audienceKind()) && eligibleCount == 0)
                throw conflict("COMMUNICATION_AUDIENCE_EMPTY",
                        "The approved audience currently resolves to no eligible portal identities.");
        }
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE communication_announcements SET status=:decision,decided_by_membership_id=:actorMembershipId,
                  decided_at=:now,decision_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING_APPROVAL' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("decision", decision)
                .addValue("reason", reason(request.reason())).addValue("now", now)
                .addValue("version", request.expectedVersion()));
        changed(changed);
        if ("APPROVED".equals(decision)) {
            history(scope, id, "RECIPIENTS_FROZEN", "PENDING_APPROVAL", "PENDING_APPROVAL",
                    recipientCount + " recipient records frozen");
            enqueueApproval(scope, announcement, id, recipientCount);
        }
        history(scope, id, decision, "PENDING_APPROVAL", decision, request.reason());
        audit(user, scope, "communication.announcement." + decision.toLowerCase(Locale.ROOT),
                "COMMUNICATION_ANNOUNCEMENT", id, request.reason());
        return new CommunicationMutationResponse(id, request.expectedVersion() + 1, recipientCount);
    }

    /**
     * Materializes one audience for a previously approved school-calendar revision. The calendar
     * domain owns the maker-checker decision and due-time job; this communication-domain facade
     * owns recipient freezing, delivery evidence, and the provider outbox.
     */
    @Transactional
    public CalendarNoticePublicationResult publishApprovedCalendarNotice(
            CalendarNoticePublication publication) {
        Objects.requireNonNull(publication, "publication");
        if (publication.organisationId() == null || publication.schoolId() == null
                || publication.noticeRevisionId() == null
                || publication.proposerMembershipId() == null
                || publication.approverMembershipId() == null) {
            throw bad("CALENDAR_NOTICE_PUBLICATION_INVALID", "Calendar publication scope is incomplete.");
        }
        if (publication.proposerMembershipId().equals(publication.approverMembershipId())) {
            throw conflict("COMMUNICATION_MAKER_CHECKER_REQUIRED",
                    "Calendar publication requires different proposer and approver memberships.");
        }
        String audience = enumValue(publication.audienceKind(),
                Set.of("ALL_FAMILIES", "ALL_STUDENTS"), "calendar audience");
        if ("ALL_STUDENTS".equals(audience) && publication.push()) {
            throw bad("STUDENT_EXTERNAL_DELIVERY_DISABLED",
                    "Student calendar notices currently support the consented in-app inbox only.");
        }
        int authorityCount = jdbc.queryForObject("""
                SELECT count(*) FROM memberships
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND id IN (:proposerId,:approverId)
                """, new MapSqlParameterSource()
                .addValue("organisationId", publication.organisationId())
                .addValue("schoolId", publication.schoolId())
                .addValue("proposerId", publication.proposerMembershipId())
                .addValue("approverId", publication.approverMembershipId()), Integer.class);
        if (authorityCount != 2) {
            throw conflict("CALENDAR_NOTICE_AUTHORITY_INVALID",
                    "The frozen calendar authority evidence no longer matches this school.");
        }
        String schoolName = jdbc.queryForObject("""
                SELECT display_name FROM schools
                WHERE id=:schoolId AND organisation_id=:organisationId
                """, new MapSqlParameterSource()
                .addValue("organisationId", publication.organisationId())
                .addValue("schoolId", publication.schoolId()), String.class);
        Scope scope = new Scope(publication.organisationId(), publication.schoolId(), schoolName,
                publication.approverMembershipId(), false, false, true);
        List<CalendarNoticePublicationResult> existing = jdbc.query("""
                SELECT announcement.id,
                  (SELECT count(*) FROM communication_recipients recipient
                    WHERE recipient.communication_announcement_id=announcement.id) recipient_count
                FROM communication_announcements announcement
                WHERE announcement.organisation_id=:organisationId AND announcement.school_id=:schoolId
                  AND announcement.source_type='SCHOOL_CALENDAR_NOTICE'
                  AND announcement.source_id=:sourceId AND announcement.audience_kind=:audience
                """, scope.params().addValue("sourceId", publication.noticeRevisionId())
                .addValue("audience", audience), (result, row) -> new CalendarNoticePublicationResult(
                result.getObject("id", UUID.class), result.getInt("recipient_count")));
        if (!existing.isEmpty()) return existing.getFirst();

        String category = Set.of("EMERGENCY_CLOSURE", "GOVERNMENT_ORDER")
                .contains(publication.noticeKind()) ? "EMERGENCY" : "GENERAL";
        String priority = "EMERGENCY".equals(category) ? "EMERGENCY" : "IMPORTANT";
        Instant now = Instant.now(clock);
        UUID id = UUID.randomUUID();
        safe("CALENDAR_NOTICE_COMMUNICATION_EXISTS", () -> jdbc.update("""
                INSERT INTO communication_announcements(
                  id,organisation_id,school_id,communication_template_id,category,priority,
                  audience_kind,audience_reference_id,language_mode,title_en,title_hi,body_en,body_hi,
                  requires_acknowledgement,request_email,request_sms,request_whatsapp,request_push,
                  scheduled_for,expires_at,status,created_by_membership_id,submitted_by_membership_id,
                  submitted_at,decided_by_membership_id,decided_at,decision_reason,created_at,updated_at,
                  row_version,source_type,source_id)
                VALUES(:id,:organisationId,:schoolId,NULL,:category,:priority,:audience,NULL,'BILINGUAL',
                  :titleEn,:titleHi,:bodyEn,:bodyHi,:ack,FALSE,FALSE,FALSE,:push,:scheduledFor,
                  :expiresAt,'APPROVED',:proposerId,:proposerId,:approvedAt,:approverId,:approvedAt,
                  :decisionReason,:now,:now,0,'SCHOOL_CALENDAR_NOTICE',:sourceId)
                """, scope.params().addValue("id", id).addValue("category", category)
                .addValue("priority", priority).addValue("audience", audience)
                .addValue("titleEn", required(publication.titleEn()))
                .addValue("titleHi", required(publication.titleHi()))
                .addValue("bodyEn", required(publication.bodyEn()))
                .addValue("bodyHi", required(publication.bodyHi()))
                .addValue("ack", publication.requiresAcknowledgement())
                .addValue("push", publication.push()).addValue("scheduledFor", publication.availableAt())
                .addValue("expiresAt", publication.expiresAt())
                .addValue("proposerId", publication.proposerMembershipId())
                .addValue("approverId", publication.approverMembershipId())
                .addValue("approvedAt", publication.approvedAt())
                .addValue("decisionReason", reason(publication.decisionReason()))
                .addValue("sourceId", publication.noticeRevisionId()).addValue("now", now)));
        StoredAnnouncement announcement = new StoredAnnouncement(
                id, null, category, audience, null, "APPROVED",
                publication.proposerMembershipId(), publication.availableAt(), publication.expiresAt(),
                false, false, false, publication.push(), 0);
        int recipientCount = freezeRecipients(scope, announcement);
        history(scope, id, "RECIPIENTS_FROZEN", "APPROVED", "APPROVED",
                recipientCount + " recipient records frozen from approved calendar authority");
        history(scope, id, "APPROVED", null, "APPROVED",
                "Approved calendar revision materialized for " + audience);
        enqueueApproval(scope, announcement, id, recipientCount);
        return new CalendarNoticePublicationResult(id, recipientCount);
    }

    @Transactional
    public CommunicationMutationResponse cancelAnnouncement(AuthenticatedUser user, UUID id, VersionedReason request) {
        Scope scope = requireScope(user, request.schoolId(), "communication.read");
        StoredAnnouncement announcement = storedAnnouncement(scope, id, true);
        if (announcement.version() != request.expectedVersion())
            throw conflict("STALE_VERSION", "The announcement changed. Refresh and try again.");
        if ("REJECTED".equals(announcement.status()) || "CANCELLED".equals(announcement.status()))
            throw conflict("ANNOUNCEMENT_TERMINAL", "This announcement is already in a terminal state.");
        if ("APPROVED".equals(announcement.status()) && !scope.canApprove())
            throw forbidden("COMMUNICATION_CANCEL_DENIED", "Only an approver can cancel an approved announcement.");
        if (!"APPROVED".equals(announcement.status()) && !scope.canSend())
            throw forbidden("COMMUNICATION_CANCEL_DENIED", "Communication sending access is required.");
        if (!scope.canConfigure() && !scope.canApprove()
                && !announcement.creatorMembershipId().equals(scope.actorMembershipId()))
            throw forbidden("COMMUNICATION_CANCEL_DENIED", "Only the creator or an authorized approver can cancel this draft.");
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE communication_announcements SET status='CANCELLED',
                  cancelled_by_membership_id=:actorMembershipId,cancelled_at=:now,cancellation_reason=:reason,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status=:status AND row_version=:version
                """, scope.params().addValue("id", id).addValue("status", announcement.status())
                .addValue("version", request.expectedVersion()).addValue("now", now)
                .addValue("reason", reason(request.reason())));
        changed(changed);
        jdbc.update("""
                UPDATE communication_deliveries SET status='CANCELLED',updated_at=:now,row_version=row_version+1
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND communication_recipient_id IN (
                    SELECT id FROM communication_recipients WHERE communication_announcement_id=:id)
                  AND status IN ('READY','AWAITING_PROVIDER','QUEUED','FAILED')
                """, scope.params().addValue("id", id).addValue("now", now));
        history(scope, id, "CANCELLED", announcement.status(), "CANCELLED", request.reason());
        audit(user, scope, "communication.announcement.cancelled", "COMMUNICATION_ANNOUNCEMENT", id, request.reason());
        return new CommunicationMutationResponse(id, request.expectedVersion() + 1, 0);
    }

    private int freezeRecipients(Scope scope, StoredAnnouncement announcement) {
        if ("PUBLIC".equals(announcement.audienceKind())) return 0;
        MapSqlParameterSource parameters = scope.params()
                .addValue("audienceReferenceId", announcement.audienceReferenceId());
        List<RecipientCandidate> candidates;
        if (announcement.audienceKind().endsWith("STAFF")) {
            String sectionClause = "SECTION_STAFF".equals(announcement.audienceKind()) ? """
                    AND EXISTS (
                      SELECT 1 FROM teaching_allocations allocation
                      JOIN section_subject_offerings offering ON offering.id=allocation.section_subject_offering_id
                      WHERE allocation.teacher_membership_id=membership.id AND allocation.status='ACTIVE'
                        AND offering.class_section_id=:audienceReferenceId AND offering.status='ACTIVE')
                    """ : "";
            candidates = jdbc.query("""
                    SELECT DISTINCT membership.id AS membership_id,membership.user_id,
                      COALESCE(preference.preferred_language,'EN') preferred_language,
                      COALESCE(preference.safe_contact_status,'ALLOWED') safe_contact_status,
                      COALESCE(preference.allow_email,true) allow_email,
                      COALESCE(preference.allow_sms,true) allow_sms,
                      COALESCE(preference.allow_whatsapp,false) allow_whatsapp,
                      COALESCE(preference.allow_push,false) allow_push
                    FROM memberships membership
                    JOIN user_accounts account ON account.id=membership.user_id AND account.status='ACTIVE'
                    LEFT JOIN communication_contact_preferences preference
                      ON preference.school_id=membership.school_id AND preference.user_account_id=membership.user_id
                    WHERE membership.organisation_id=:organisationId AND membership.school_id=:schoolId
                      AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                      AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                      AND EXISTS (SELECT 1 FROM membership_roles mr
                        JOIN role_capabilities rc ON rc.role_id=mr.role_id
                        WHERE mr.membership_id=membership.id AND rc.capability_code='communication.read')
                    """ + sectionClause + " ORDER BY membership.id",
                    parameters, (result, row) -> RecipientCandidate.staff(
                    result.getObject("membership_id", UUID.class), result.getObject("user_id", UUID.class),
                    result.getString("preferred_language"), result.getString("safe_contact_status"),
                    result.getBoolean("allow_email"), result.getBoolean("allow_sms"),
                    result.getBoolean("allow_whatsapp"), result.getBoolean("allow_push")));
        } else if (announcement.audienceKind().endsWith("STUDENTS")
                || "LEARNER_STUDENT".equals(announcement.audienceKind())) {
            String filter = switch (announcement.audienceKind()) {
                case "SECTION_STUDENTS" -> " AND placement.class_section_id=:audienceReferenceId ";
                case "LEARNER_STUDENT" -> " AND learner.id=:audienceReferenceId ";
                default -> "";
            };
            candidates = jdbc.query("""
                    SELECT DISTINCT membership.id AS membership_id,learner.id AS learner_id,
                      portal.user_account_id,COALESCE(preference.preferred_language,'EN') preferred_language
                    FROM learner_portal_accounts portal
                    JOIN student_portal_consent_requests consent
                      ON consent.id=portal.current_consent_request_id
                     AND consent.organisation_id=portal.organisation_id
                     AND consent.school_id=portal.school_id
                     AND consent.status='APPROVED'
                     AND consent.approved_user_account_id=portal.user_account_id
                    JOIN learners learner ON learner.id=portal.learner_id AND learner.status='ACTIVE'
                    JOIN student_enrolments enrolment ON enrolment.learner_id=learner.id
                     AND enrolment.organisation_id=portal.organisation_id
                     AND enrolment.school_id=portal.school_id AND enrolment.status='ACTIVE'
                    LEFT JOIN student_section_placements placement ON placement.enrolment_id=enrolment.id
                     AND placement.organisation_id=portal.organisation_id
                     AND placement.school_id=portal.school_id AND placement.status='ACTIVE'
                    JOIN user_accounts account ON account.id=portal.user_account_id AND account.status='ACTIVE'
                    JOIN memberships membership ON membership.user_id=portal.user_account_id
                     AND membership.organisation_id=portal.organisation_id
                     AND membership.school_id=portal.school_id AND membership.status='ACTIVE'
                     AND membership.valid_from<=CURRENT_TIMESTAMP
                     AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                    LEFT JOIN communication_contact_preferences preference
                      ON preference.school_id=portal.school_id
                     AND preference.user_account_id=portal.user_account_id
                    WHERE portal.organisation_id=:organisationId AND portal.school_id=:schoolId
                      AND portal.status='ACTIVE'
                      AND EXISTS (SELECT 1 FROM membership_roles mr
                        JOIN role_capabilities rc ON rc.role_id=mr.role_id
                        WHERE mr.membership_id=membership.id AND rc.capability_code='portal.student')
                    """ + filter + " ORDER BY membership.id,learner.id",
                    parameters, (result, row) -> RecipientCandidate.student(
                    result.getObject("membership_id", UUID.class),
                    result.getObject("learner_id", UUID.class),
                    result.getObject("user_account_id", UUID.class),
                    result.getString("preferred_language")));
        } else {
            String filter = switch (announcement.audienceKind()) {
                case "SECTION_FAMILIES" -> " AND placement.class_section_id=:audienceReferenceId ";
                case "LEARNER_FAMILIES" -> " AND learner.id=:audienceReferenceId ";
                default -> "";
            };
            candidates = jdbc.query("""
                    SELECT DISTINCT guardian.id AS guardian_id,learner.id AS learner_id,guardian.user_account_id,
                      COALESCE(preference.preferred_language,'EN') preferred_language,
                      COALESCE(preference.safe_contact_status,'ALLOWED') safe_contact_status,
                      COALESCE(preference.allow_email,true) allow_email,
                      COALESCE(preference.allow_sms,true) allow_sms,
                      COALESCE(preference.allow_whatsapp,false) allow_whatsapp,
                      COALESCE(preference.allow_push,false) allow_push
                    FROM learner_guardian_relationships relationship
                    JOIN guardians guardian ON guardian.id=relationship.guardian_id
                     AND guardian.user_account_id IS NOT NULL AND guardian.status='ACTIVE'
                    JOIN learners learner ON learner.id=relationship.learner_id AND learner.status='ACTIVE'
                    JOIN student_enrolments enrolment ON enrolment.learner_id=learner.id AND enrolment.status='ACTIVE'
                    LEFT JOIN student_section_placements placement ON placement.enrolment_id=enrolment.id
                     AND placement.status='ACTIVE'
                    JOIN user_accounts account ON account.id=guardian.user_account_id AND account.status='ACTIVE'
                    LEFT JOIN communication_contact_preferences preference
                      ON preference.school_id=guardian.school_id AND preference.user_account_id=guardian.user_account_id
                    WHERE relationship.organisation_id=:organisationId AND relationship.school_id=:schoolId
                      AND relationship.portal_access_status='ACTIVE'
                      AND EXISTS (SELECT 1 FROM memberships membership
                        JOIN membership_roles mr ON mr.membership_id=membership.id
                        JOIN role_capabilities rc ON rc.role_id=mr.role_id
                        WHERE membership.user_id=guardian.user_account_id
                          AND membership.organisation_id=:organisationId AND membership.school_id=:schoolId
                          AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                          AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                          AND rc.capability_code='portal.guardian')
                    """ + filter + " ORDER BY guardian.id,learner.id",
                    parameters, (result, row) -> RecipientCandidate.guardian(
                    result.getObject("guardian_id", UUID.class), result.getObject("learner_id", UUID.class),
                    result.getObject("user_account_id", UUID.class), result.getString("preferred_language"),
                    result.getString("safe_contact_status"), result.getBoolean("allow_email"),
                    result.getBoolean("allow_sms"), result.getBoolean("allow_whatsapp"),
                    result.getBoolean("allow_push")));
        }
        Instant now = Instant.now(clock);
        for (RecipientCandidate candidate : candidates) {
            UUID recipientId = UUID.randomUUID();
            boolean eligible = !"BLOCKED".equals(candidate.safeContactStatus());
            jdbc.update("""
                    INSERT INTO communication_recipients(id,organisation_id,school_id,
                      communication_announcement_id,recipient_kind,membership_id,guardian_id,learner_id,
                      user_account_id,preferred_language,contact_eligible,suppression_reason,available_at,
                      created_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:announcementId,:kind,:membershipId,:guardianId,:learnerId,
                      :userId,:language,:eligible,:suppression,:availableAt,:now,0)
                    """, scope.params().addValue("id", recipientId).addValue("announcementId", announcement.id())
                    .addValue("kind", candidate.kind()).addValue("membershipId", candidate.membershipId())
                    .addValue("guardianId", candidate.guardianId()).addValue("learnerId", candidate.learnerId())
                    .addValue("userId", candidate.userId()).addValue("language", candidate.language())
                    .addValue("eligible", eligible).addValue("suppression", eligible ? null : "SAFE_CONTACT_BLOCKED")
                    .addValue("availableAt", announcement.scheduledFor()).addValue("now", now));
            createRecipientDeliveries(scope, announcement, candidate, recipientId, eligible, now);
        }
        return candidates.size();
    }

    private void createRecipientDeliveries(Scope scope, StoredAnnouncement announcement,
            RecipientCandidate candidate, UUID recipientId, boolean eligible, Instant now) {
        delivery(scope, recipientId, "IN_APP", eligible ? "READY" : "SUPPRESSED", null, now);
        boolean externalContactAllowed = eligible
                && allowsExternalContact(candidate.safeContactStatus(), announcement.category());
        if (announcement.email()) externalDelivery(scope, announcement, candidate, recipientId,
                "EMAIL", externalContactAllowed && candidate.allowEmail(), now);
        if (announcement.sms()) externalDelivery(scope, announcement, candidate, recipientId,
                "SMS", externalContactAllowed && candidate.allowSms(), now);
        if (announcement.whatsapp()) externalDelivery(scope, announcement, candidate, recipientId,
                "WHATSAPP", externalContactAllowed && candidate.allowWhatsapp(), now);
        if (announcement.push()) externalDelivery(scope, announcement, candidate, recipientId,
                "PUSH", externalContactAllowed && candidate.allowPush(), now);
    }

    static boolean allowsExternalContact(String safeContactStatus, String category) {
        return "ALLOWED".equals(safeContactStatus) || ("TRANSACTIONAL_ONLY".equals(safeContactStatus)
                && category != null && TRANSACTIONAL_CATEGORIES.contains(category));
    }

    private void externalDelivery(Scope scope, StoredAnnouncement announcement, RecipientCandidate candidate,
            UUID recipientId, String channel, boolean preferenceAllows, Instant now) {
        if (!preferenceAllows) {
            delivery(scope, recipientId, channel, "SUPPRESSED", null, now);
            return;
        }
        List<UUID> providers = jdbc.query("""
                SELECT config.id FROM communication_provider_configs config
                WHERE config.organisation_id=:organisationId AND config.school_id=:schoolId
                  AND config.channel=:channel AND config.status='ACTIVE'
                  AND (config.environment='LIVE' OR NOT EXISTS (
                    SELECT 1 FROM communication_provider_configs live
                    WHERE live.school_id=config.school_id AND live.channel=config.channel
                      AND live.environment='LIVE' AND live.status='ACTIVE'))
                  AND (:channel NOT IN ('SMS','WHATSAPP') OR (:templateId IS NOT NULL AND EXISTS (
                    SELECT 1 FROM communication_template_channel_registrations registration
                    WHERE registration.communication_template_id=:templateId
                      AND registration.channel=:channel AND registration.registration_status='APPROVED')))
                ORDER BY CASE config.environment WHEN 'LIVE' THEN 0 ELSE 1 END LIMIT 1
                """, scope.params().addValue("channel", channel)
                .addValue("templateId", announcement.templateId()),
                (result, row) -> result.getObject("id", UUID.class));
        delivery(scope, recipientId, channel, providers.isEmpty() ? "AWAITING_PROVIDER" : "READY",
                providers.isEmpty() ? null : providers.getFirst(), now);
    }

    private void delivery(Scope scope, UUID recipientId, String channel, String status,
            UUID providerId, Instant now) {
        jdbc.update("""
                INSERT INTO communication_deliveries(id,organisation_id,school_id,communication_recipient_id,
                  channel,status,provider_config_id,attempt_count,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:recipientId,:channel,:status,:providerId,0,:now,:now,0)
                """, scope.params().addValue("id", UUID.randomUUID()).addValue("recipientId", recipientId)
                .addValue("channel", channel).addValue("status", status)
                .addValue("providerId", providerId).addValue("now", now));
    }

    private void validateAudienceScope(Scope scope, String audience, UUID referenceId) {
        boolean referenceRequired = Set.of(
                "SECTION_STAFF", "SECTION_FAMILIES", "SECTION_STUDENTS",
                "LEARNER_FAMILIES", "LEARNER_STUDENT").contains(audience);
        if (referenceRequired != (referenceId != null))
            throw bad("COMMUNICATION_AUDIENCE_REFERENCE_INVALID", "Choose the record required by this audience.");
        if (Set.of("PUBLIC", "ALL_STAFF", "ALL_FAMILIES", "ALL_STUDENTS").contains(audience)
                && !scope.canApprove() && !scope.canConfigure())
            throw forbidden("COMMUNICATION_AUDIENCE_DENIED",
                    "School-wide and public audiences require leadership communication scope.");
        if (!referenceRequired) return;
        int exists;
        if (audience.startsWith("SECTION_")) {
            exists = jdbc.queryForObject("""
                    SELECT count(*) FROM class_sections section
                    WHERE section.id=:referenceId AND section.organisation_id=:organisationId
                      AND section.school_id=:schoolId AND section.status='ACTIVE'
                      AND (:wideScope OR EXISTS (
                        SELECT 1 FROM section_subject_offerings offering
                        JOIN teaching_allocations allocation ON allocation.section_subject_offering_id=offering.id
                        WHERE offering.class_section_id=section.id AND offering.status='ACTIVE'
                          AND allocation.teacher_membership_id=:actorMembershipId
                          AND allocation.status='ACTIVE'))
                    """, scope.params().addValue("referenceId", referenceId)
                    .addValue("wideScope", scope.canApprove() || scope.canConfigure()), Integer.class);
        } else {
            exists = jdbc.queryForObject("""
                    SELECT count(*) FROM learners learner
                    WHERE learner.id=:referenceId AND learner.organisation_id=:organisationId
                      AND learner.school_id=:schoolId AND learner.status='ACTIVE'
                      AND (:wideScope OR EXISTS (
                        SELECT 1 FROM student_section_placements placement
                        JOIN section_subject_offerings offering ON offering.class_section_id=placement.class_section_id
                        JOIN teaching_allocations allocation ON allocation.section_subject_offering_id=offering.id
                        WHERE placement.learner_id=learner.id AND placement.status='ACTIVE'
                          AND offering.status='ACTIVE' AND allocation.status='ACTIVE'
                          AND allocation.teacher_membership_id=:actorMembershipId))
                    """, scope.params().addValue("referenceId", referenceId)
                    .addValue("wideScope", scope.canApprove() || scope.canConfigure()), Integer.class);
        }
        if (exists != 1) throw forbidden("COMMUNICATION_AUDIENCE_DENIED",
                "The selected audience is unavailable within your assigned school scope.");
    }

    private AnnouncementValues announcementValues(Scope scope, AnnouncementRequest request) {
        String audience = enumValue(request.audienceKind(), AUDIENCES, "audience");
        String priority = enumValue(request.priority(), PRIORITIES, "priority");
        Content content;
        String category;
        boolean ack;
        boolean email;
        boolean sms;
        boolean whatsapp;
        boolean push;
        if (request.templateId() == null) {
            category = enumValue(request.category(), CATEGORIES, "category");
            content = content(request.languageMode(), request.titleEn(), request.titleHi(),
                    request.bodyEn(), request.bodyHi());
            ack = request.requiresAcknowledgement();
            email = request.email();
            sms = request.sms();
            whatsapp = request.whatsapp();
            push = request.push();
        } else {
            List<TemplateContent> rows = jdbc.query("""
                    SELECT category,language_mode,title_en,title_hi,body_en,body_hi,
                      default_requires_acknowledgement,default_email,default_sms,default_whatsapp,default_push
                    FROM communication_templates WHERE id=:templateId AND organisation_id=:organisationId
                      AND school_id=:schoolId AND status='ACTIVE'
                    """, scope.params().addValue("templateId", request.templateId()),
                    (result, row) -> new TemplateContent(result.getString("category"),
                    new Content(result.getString("language_mode"), result.getString("title_en"),
                            result.getString("title_hi"), result.getString("body_en"), result.getString("body_hi")),
                    result.getBoolean("default_requires_acknowledgement"), result.getBoolean("default_email"),
                    result.getBoolean("default_sms"), result.getBoolean("default_whatsapp"),
                    result.getBoolean("default_push")));
            if (rows.isEmpty()) throw bad("COMMUNICATION_TEMPLATE_NOT_ACTIVE",
                    "Choose an active communication template or write a direct draft.");
            TemplateContent template = rows.getFirst();
            category = template.category();
            content = template.content();
            ack = template.ack();
            email = template.email();
            sms = template.sms();
            whatsapp = template.whatsapp();
            push = template.push();
        }
        if ("PUBLIC".equals(audience) && (ack || email || sms || whatsapp || push))
            throw bad("PUBLIC_COMMUNICATION_CHANNEL_INVALID",
                    "Public website notices cannot request acknowledgements or external delivery.");
        if (request.expiresAt() != null && !request.expiresAt().isAfter(request.scheduledFor()))
            throw bad("COMMUNICATION_SCHEDULE_INVALID", "Expiry must be after the scheduled publication time.");
        return new AnnouncementValues(request.templateId(), category, priority, audience,
                request.audienceReferenceId(), content, ack, email, sms, whatsapp, push,
                request.scheduledFor(), request.expiresAt());
    }

    private StoredAnnouncement storedAnnouncement(Scope scope, UUID id, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<StoredAnnouncement> rows = jdbc.query("""
                SELECT id,communication_template_id,category,audience_kind,audience_reference_id,status,
                  created_by_membership_id,scheduled_for,expires_at,request_email,request_sms,
                  request_whatsapp,request_push,row_version
                FROM communication_announcements
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND authority_kind='HUMAN_REVIEW'
                """ + suffix, scope.params().addValue("id", id), (result, row) -> new StoredAnnouncement(
                result.getObject("id", UUID.class), result.getObject("communication_template_id", UUID.class),
                result.getString("category"), result.getString("audience_kind"),
                result.getObject("audience_reference_id", UUID.class),
                result.getString("status"), result.getObject("created_by_membership_id", UUID.class),
                instant(result, "scheduled_for"), instantNullable(result, "expires_at"),
                result.getBoolean("request_email"), result.getBoolean("request_sms"),
                result.getBoolean("request_whatsapp"), result.getBoolean("request_push"),
                result.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("COMMUNICATION_ANNOUNCEMENT_NOT_FOUND", "The announcement was not found.");
        return rows.getFirst();
    }

    private Scope requireScope(AuthenticatedUser user, UUID schoolId, String capability) {
        return requireScope(user, schoolId, Set.of(capability));
    }

    private Scope requireScope(AuthenticatedUser user, UUID schoolId, Set<String> capabilities) {
        if (user.activeOrganisationId() == null) throw conflict("ORGANISATION_SELECTION_REQUIRED",
                "Select a school organisation first.");
        List<Scope> rows = jdbc.query("""
                SELECT school.id AS school_id,school.display_name,membership.id AS membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='communication.configure') can_configure,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='communication.send') can_send,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='communication.approve') can_approve
                FROM memberships membership JOIN schools school ON school.id=membership.school_id
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN role_capabilities rc ON rc.role_id=mr.role_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds) AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND rc.capability_code IN (:capabilities)
                ORDER BY can_approve DESC,can_configure DESC,membership.created_at LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId()).addValue("membershipIds", user.membershipIds())
                .addValue("schoolId", schoolId).addValue("capabilities", capabilities), (result, row) -> new Scope(
                user.activeOrganisationId(), result.getObject("school_id", UUID.class), result.getString("display_name"),
                result.getObject("membership_id", UUID.class), result.getBoolean("can_configure"),
                result.getBoolean("can_send"), result.getBoolean("can_approve")));
        if (rows.isEmpty()) throw forbidden("COMMUNICATION_SCOPE_DENIED",
                "Communication access is unavailable for this school.");
        return rows.getFirst();
    }

    private void validateTemplateRevision(Scope scope, TemplateRequest request, UUID editingId) {
        String code = code(request.code());
        if (request.supersedesTemplateId() == null) {
            if (request.revisionNumber() != 1) throw bad("COMMUNICATION_TEMPLATE_REVISION_INVALID",
                    "A new template code starts at revision 1.");
            return;
        }
        List<TemplateRevision> rows = jdbc.query("""
                SELECT template_code,revision_number,status FROM communication_templates
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", request.supersedesTemplateId()), (result, row) -> new TemplateRevision(
                result.getString("template_code"), result.getInt("revision_number"), result.getString("status")));
        if (rows.isEmpty()) throw bad("COMMUNICATION_TEMPLATE_REVISION_INVALID", "The superseded template was not found.");
        TemplateRevision source = rows.getFirst();
        if (!source.code().equals(code) || request.revisionNumber() != source.revision() + 1
                || !"ACTIVE".equals(source.status())
                || (editingId != null && editingId.equals(request.supersedesTemplateId())))
            throw bad("COMMUNICATION_TEMPLATE_REVISION_INVALID",
                    "A new revision must supersede the active template with the same code and next number.");
    }

    private MapSqlParameterSource templateParams(Scope scope, UUID id, TemplateRequest request,
            Content content, Long version) {
        return scope.params().addValue("id", id).addValue("code", code(request.code()))
                .addValue("revision", request.revisionNumber()).addValue("supersedesId", request.supersedesTemplateId())
                .addValue("name", required(request.name())).addValue("category", enumValue(request.category(), CATEGORIES, "category"))
                .addValue("language", content.language()).addValue("titleEn", content.titleEn())
                .addValue("titleHi", content.titleHi()).addValue("bodyEn", content.bodyEn())
                .addValue("bodyHi", content.bodyHi()).addValue("ack", request.requiresAcknowledgement())
                .addValue("email", request.email()).addValue("sms", request.sms())
                .addValue("whatsapp", request.whatsapp()).addValue("push", request.push())
                .addValue("version", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource announcementParams(Scope scope, UUID id, AnnouncementValues values, Long version) {
        return scope.params().addValue("id", id).addValue("templateId", values.templateId())
                .addValue("category", values.category()).addValue("priority", values.priority())
                .addValue("audienceKind", values.audienceKind())
                .addValue("audienceReferenceId", values.audienceReferenceId())
                .addValue("language", values.content().language()).addValue("titleEn", values.content().titleEn())
                .addValue("titleHi", values.content().titleHi()).addValue("bodyEn", values.content().bodyEn())
                .addValue("bodyHi", values.content().bodyHi()).addValue("ack", values.ack())
                .addValue("email", values.email()).addValue("sms", values.sms())
                .addValue("whatsapp", values.whatsapp()).addValue("push", values.push())
                .addValue("scheduledFor", values.scheduledFor()).addValue("expiresAt", values.expiresAt())
                .addValue("canConfigure", scope.canConfigure()).addValue("version", version)
                .addValue("now", Instant.now(clock));
    }

    private void history(Scope scope, UUID announcementId, String event, String previous,
            String next, String why) {
        jdbc.update("""
                INSERT INTO communication_announcement_history(id,organisation_id,school_id,
                  communication_announcement_id,event_type,previous_status,new_status,reason,
                  actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:announcementId,:event,:previous,:next,:reason,
                  :actorMembershipId,:now)
                """, scope.params().addValue("id", UUID.randomUUID()).addValue("announcementId", announcementId)
                .addValue("event", event).addValue("previous", previous).addValue("next", next)
                .addValue("reason", clean(why, "Workflow transition")).addValue("now", Instant.now(clock)));
    }

    private void enqueueApproval(Scope scope, StoredAnnouncement announcement, UUID id, int count) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "organisationId", scope.organisationId(), "schoolId", scope.schoolId(),
                    "announcementId", id, "recipientCount", count));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Communication outbox payload must be serializable", exception);
        }
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,headers,
                  occurred_at,available_at,attempt_count,created_at)
                VALUES(:id,'COMMUNICATION_ANNOUNCEMENT',:aggregateId,'communication.announcement.approved',
                  CAST(:payload AS jsonb),'{}'::jsonb,:now,:availableAt,0,:now)
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                .addValue("aggregateId", id.toString()).addValue("payload", payload)
                .addValue("now", now).addValue("availableAt", announcement.scheduledFor()));
    }

    private void audit(AuthenticatedUser user, Scope scope, String action, String type, UUID id, String why) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type, id.toString(),
                AuditOutcome.SUCCEEDED, clean(why), Map.of());
    }

    private static Content content(String languageValue, String titleEnValue, String titleHiValue,
            String bodyEnValue, String bodyHiValue) {
        String language = enumValue(languageValue, LANGUAGES, "language mode");
        String titleEn = clean(titleEnValue);
        String titleHi = clean(titleHiValue);
        String bodyEn = clean(bodyEnValue);
        String bodyHi = clean(bodyHiValue);
        if (("ENGLISH".equals(language) || "BILINGUAL".equals(language)) && (titleEn == null || bodyEn == null))
            throw bad("COMMUNICATION_ENGLISH_CONTENT_REQUIRED", "Enter both the English title and message.");
        if (("HINDI".equals(language) || "BILINGUAL".equals(language)) && (titleHi == null || bodyHi == null))
            throw bad("COMMUNICATION_HINDI_CONTENT_REQUIRED", "Enter both the Hindi title and message.");
        if ("ENGLISH".equals(language)) { titleHi = null; bodyHi = null; }
        if ("HINDI".equals(language)) { titleEn = null; bodyEn = null; }
        return new Content(language, titleEn, titleHi, bodyEn, bodyHi);
    }

    private static List<String> channels(boolean email, boolean sms, boolean whatsapp, boolean push) {
        List<String> values = new ArrayList<>();
        values.add("IN_APP");
        if (email) values.add("EMAIL");
        if (sms) values.add("SMS");
        if (whatsapp) values.add("WHATSAPP");
        if (push) values.add("PUSH");
        return List.copyOf(values);
    }

    private static Instant instant(java.sql.ResultSet result, String name) throws java.sql.SQLException {
        return result.getTimestamp(name).toInstant();
    }

    private static Instant instantNullable(java.sql.ResultSet result, String name) throws java.sql.SQLException {
        var value = result.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static <T> T safe(String code, Work<T> work) {
        try { return work.run(); }
        catch (DataIntegrityViolationException exception) {
            throw conflict(code, "The same communication record already exists or violates its frozen workflow.");
        }
    }

    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION", "The record changed. Refresh and try again.");
    }

    private static long requiredVersion(Long value) {
        if (value == null) throw bad("EXPECTED_VERSION_REQUIRED", "Include the current record version before editing.");
        return value;
    }

    private static String reason(String value) {
        String result = clean(value);
        if (result == null) throw bad("CHANGE_REASON_REQUIRED", "Enter a reason for this communication change.");
        return result;
    }

    private static String required(String value) {
        String result = clean(value);
        if (result == null) throw bad("REQUIRED_VALUE_MISSING", "Enter the required value.");
        return result;
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String result = required(value).toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(result)) throw bad("INVALID_VALUE", "Choose a supported " + label + ".");
        return result;
    }

    private static String code(String value) { return required(value).toUpperCase(Locale.ROOT).replace(' ', '_'); }
    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").strip();
    }
    private static String clean(String value, String fallback) { String result = clean(value); return result == null ? fallback : result; }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
    private static ApiException forbidden(String code, String message) { return new ApiException(HttpStatus.FORBIDDEN, code, message); }

    record Scope(UUID organisationId, UUID schoolId, String schoolName, UUID actorMembershipId,
            boolean canConfigure, boolean canSend, boolean canApprove) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId)
                    .addValue("broadAccess", canConfigure || canApprove);
        }
    }
    private record Content(String language, String titleEn, String titleHi, String bodyEn, String bodyHi) {}
    private record TemplateContent(String category, Content content, boolean ack, boolean email,
            boolean sms, boolean whatsapp, boolean push) {}
    private record AnnouncementValues(UUID templateId, String category, String priority, String audienceKind,
            UUID audienceReferenceId, Content content, boolean ack, boolean email, boolean sms,
            boolean whatsapp, boolean push, Instant scheduledFor, Instant expiresAt) {}
    private record StoredTemplate(String code, UUID supersedesId, String status, long version) {}
    private record TemplateRevision(String code, int revision, String status) {}
    private record StoredAnnouncement(UUID id, UUID templateId, String category, String audienceKind,
            UUID audienceReferenceId, String status, UUID creatorMembershipId, Instant scheduledFor, Instant expiresAt,
            boolean email, boolean sms, boolean whatsapp, boolean push, long version) {}
    private record RecipientCandidate(String kind, UUID membershipId, UUID guardianId, UUID learnerId,
            UUID userId, String language, String safeContactStatus, boolean allowEmail, boolean allowSms,
            boolean allowWhatsapp, boolean allowPush) {
        static RecipientCandidate staff(UUID membershipId, UUID userId, String language, String safe,
                boolean email, boolean sms, boolean whatsapp, boolean push) {
            return new RecipientCandidate("STAFF", membershipId, null, null, userId, language, safe,
                    email, sms, whatsapp, push);
        }
        static RecipientCandidate guardian(UUID guardianId, UUID learnerId, UUID userId, String language,
                String safe, boolean email, boolean sms, boolean whatsapp, boolean push) {
            return new RecipientCandidate("GUARDIAN", null, guardianId, learnerId, userId, language, safe,
                    email, sms, whatsapp, push);
        }
        static RecipientCandidate student(
                UUID membershipId, UUID learnerId, UUID userId, String language) {
            return new RecipientCandidate("STUDENT", membershipId, null, learnerId, userId, language,
                    "ALLOWED", false, false, false, false);
        }
    }
    public record CalendarNoticePublication(
            UUID organisationId,
            UUID schoolId,
            UUID noticeRevisionId,
            String audienceKind,
            String noticeKind,
            String titleEn,
            String titleHi,
            String bodyEn,
            String bodyHi,
            boolean push,
            boolean requiresAcknowledgement,
            Instant availableAt,
            Instant expiresAt,
            UUID proposerMembershipId,
            UUID approverMembershipId,
            Instant approvedAt,
            String decisionReason) {}
    public record CalendarNoticePublicationResult(UUID announcementId, int recipientCount) {}
    @FunctionalInterface private interface Work<T> { T run(); }
}

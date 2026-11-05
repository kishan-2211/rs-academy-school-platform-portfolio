package com.educore.api.studentportal;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.identity.UserAccount;
import com.educore.api.identity.UserAccountRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentPortalAccessService {

    private static final String MANAGE = "student.portal.manage";
    private static final String STUDENT_ROLE_ID = "00000000-0000-4000-8000-000000000010";
    private static final Set<String> VERIFICATION_METHODS =
            Set.of("SCHOOL_RECORD", "AUTHORIZED_TOKEN", "OTHER_REVIEWED");
    private static final Map<String, Pattern> VERIFICATION_REFERENCES = Map.of(
            "SCHOOL_RECORD", Pattern.compile("school-record:[A-Za-z0-9._/-]{3,140}"),
            "AUTHORIZED_TOKEN", Pattern.compile("authorized-token:[A-Za-z0-9._/-]{3,136}"),
            "OTHER_REVIEWED", Pattern.compile("review-case:[A-Za-z0-9._/-]{3,144}"));

    private final NamedParameterJdbcTemplate jdbc;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SecurityAuditService audit;
    private final Clock clock;

    public StudentPortalAccessService(
            NamedParameterJdbcTemplate jdbc,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            SecurityAuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StudentPortalAccessSnapshot snapshot(AuthenticatedUser user, UUID learnerId) {
        StaffLearner learner = requireStaffLearner(user, learnerId, false);
        return snapshot(learner);
    }

    @Transactional
    public StudentPortalMutationResponse prepare(
            AuthenticatedUser user,
            UUID learnerId,
            PrepareStudentPortalAccessRequest request) {
        StaffLearner learner = requireStaffLearner(user, learnerId, true);
        if (learner.rowVersion() != request.expectedLearnerVersion()) {
            throw stale("The learner record changed before student access was prepared.");
        }
        if (!"ACTIVE".equals(learner.status()) || !learner.activeEnrolment()) {
            throw conflict("STUDENT_PORTAL_LEARNER_NOT_ACTIVE",
                    "Student access requires an active learner and active enrolment.");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(learner.timeZone())));
        if (!learner.birthDate().plusYears(18).isAfter(today)) {
            throw conflict("STUDENT_PORTAL_ADULT_FLOW_REQUIRED",
                    "This learner is eighteen or older. Use an adult learner approval flow rather than guardian consent.");
        }
        GuardianApprovalScope guardian = requireGuardianForPreparation(learner, request.guardianId());
        if (guardian.relationshipVersion() != request.expectedRelationshipVersion()) {
            throw stale("The guardian relationship changed before student access was prepared.");
        }
        if (!"ACTIVE".equals(guardian.guardianStatus())
                || !"ACTIVE".equals(guardian.portalAccessStatus())
                || guardian.guardianUserId() == null
                || !"ACTIVE".equals(guardian.guardianAccountStatus())) {
            throw conflict("STUDENT_PORTAL_GUARDIAN_NOT_READY",
                    "Choose an active guardian relationship with verified family portal access.");
        }

        String loginIdentifier = normalizeLogin(request.loginIdentifier());
        String method = upper(request.adultVerificationMethod());
        String verificationReference = required(request.adultVerificationReference(), 160);
        validateVerificationReference(method, verificationReference);
        String noticeVersion = required(request.privacyNoticeVersion(), 60);
        String purpose = required(request.purposeSummary(), 1000);
        String reason = required(request.reason(), 500);
        if (purpose.length() < 20) {
            throw bad("STUDENT_PORTAL_PURPOSE_TOO_SHORT",
                    "Describe the child-account purpose in at least 20 characters.");
        }

        expirePending(learner);
        Integer pending = jdbc.queryForObject("""
                SELECT count(*) FROM student_portal_consent_requests
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_id=:learnerId AND status='PENDING'
                """, learner.parameters(), Integer.class);
        if (pending != null && pending > 0) {
            throw conflict("STUDENT_PORTAL_REQUEST_ALREADY_PENDING",
                    "Revoke or complete the current guardian request before preparing another one.");
        }

        PortalAccount account = account(learner.organisationId(), learner.schoolId(), learner.learnerId(), true);
        if (account != null && !"ENDED".equals(account.status())) {
            throw conflict("STUDENT_PORTAL_ACCOUNT_ALREADY_OPEN",
                    "Suspend, resume, or end the existing student account instead of requesting another consent.");
        }
        if (account == null) {
            if (users.findByLoginIdentifierNormalized(loginIdentifier).isPresent()) {
                throw conflict("STUDENT_PORTAL_LOGIN_UNAVAILABLE",
                        "Choose another school sign-in ID; this one is already in use.");
            }
        } else if (!account.loginIdentifier().equals(loginIdentifier)) {
            throw conflict("STUDENT_PORTAL_LOGIN_IMMUTABLE",
                    "A reactivation must keep the learner's existing school sign-in ID.");
        }

        UUID supersedes = jdbc.query("""
                SELECT id FROM student_portal_consent_requests
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND learner_id=:learnerId
                ORDER BY created_at DESC,id DESC LIMIT 1
                """, learner.parameters(), result -> result.next() ? result.getObject("id", UUID.class) : null);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plusSeconds(14L * 24 * 60 * 60);
        try {
            jdbc.update("""
                    INSERT INTO student_portal_consent_requests(
                      id,organisation_id,school_id,learner_id,guardian_id,supersedes_request_id,
                      proposed_login_identifier_normalized,privacy_notice_version,purpose_summary,
                      adult_verification_method,adult_verification_reference,adult_verified_at,
                      adult_verified_by_membership_id,preparation_reason,status,expires_at,
                      created_by_membership_id,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:learnerId,:guardianId,:supersedes,
                      :loginIdentifier,:noticeVersion,:purpose,:verificationMethod,:verificationReference,
                      :now,:actorMembershipId,:reason,'PENDING',:expiresAt,:actorMembershipId,:now,:now,0)
                    """, learner.parameters()
                    .addValue("id", id).addValue("guardianId", guardian.guardianId())
                    .addValue("supersedes", supersedes).addValue("loginIdentifier", loginIdentifier)
                    .addValue("noticeVersion", noticeVersion).addValue("purpose", purpose)
                    .addValue("verificationMethod", method)
                    .addValue("verificationReference", verificationReference)
                    .addValue("reason", reason).addValue("now", now).addValue("expiresAt", expiresAt));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("STUDENT_PORTAL_REQUEST_CONFLICT",
                    "Student access changed while the guardian request was being prepared. Refresh and try again.");
        }
        audit.recordScoped(user, learner.actorMembershipId(), learner.schoolId(),
                "student.portal.consent.prepared", "STUDENT_PORTAL_CONSENT", id.toString(),
                AuditOutcome.SUCCEEDED, reason,
                Map.of("learnerId", learnerId, "guardianId", guardian.guardianId(),
                        "expiresAt", expiresAt.toString()));
        return new StudentPortalMutationResponse(id, 0, "PENDING");
    }

    @Transactional
    public StudentPortalMutationResponse revokePending(
            AuthenticatedUser user, UUID learnerId, UUID requestId, StudentPortalVersionedReason request) {
        StaffLearner learner = requireStaffLearner(user, learnerId, true);
        Consent consent = requireConsent(learner, requestId, true);
        if (consent.rowVersion() != request.expectedVersion()) throw stale("The consent request changed.");
        if (!"PENDING".equals(consent.status())) {
            throw conflict("STUDENT_PORTAL_REQUEST_NOT_PENDING",
                    "Only a pending guardian request can be revoked here.");
        }
        String reason = required(request.reason(), 500);
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE student_portal_consent_requests
                SET status='REVOKED',revoked_by_membership_id=:actorMembershipId,
                    revoked_at=:now,revoked_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_id=:learnerId AND status='PENDING' AND row_version=:expectedVersion
                """, learner.parameters().addValue("id", requestId).addValue("now", now)
                .addValue("reason", reason).addValue("expectedVersion", request.expectedVersion()));
        if (changed != 1) throw stale("The consent request changed.");
        audit.recordScoped(user, learner.actorMembershipId(), learner.schoolId(),
                "student.portal.consent.revoked", "STUDENT_PORTAL_CONSENT", requestId.toString(),
                AuditOutcome.SUCCEEDED, reason, Map.of("learnerId", learnerId));
        return new StudentPortalMutationResponse(requestId, request.expectedVersion() + 1, "REVOKED");
    }

    @Transactional(readOnly = true)
    public FamilyStudentPortalAccessResponse familyAccess(AuthenticatedUser user, UUID learnerId) {
        GuardianContext context = requireGuardianContext(user, learnerId, false);
        PortalAccount account = account(context.organisationId(), context.schoolId(), learnerId, false);
        return familyResponse(context, account);
    }

    @Transactional
    public StudentPortalMutationResponse decide(
            AuthenticatedUser user,
            UUID learnerId,
            UUID requestId,
            StudentPortalDecisionRequest request) {
        GuardianContext context = requireGuardianContext(user, learnerId, true);
        Consent consent = requireGuardianConsent(context, requestId, true);
        if (consent.rowVersion() != request.expectedVersion()) throw stale("The student access request changed.");
        if (!"PENDING".equals(consent.status())) {
            throw conflict("STUDENT_PORTAL_REQUEST_NOT_PENDING",
                    "This guardian decision is already complete or no longer available.");
        }
        Instant now = Instant.now(clock);
        if (!consent.expiresAt().isAfter(now)) {
            jdbc.update("""
                    UPDATE student_portal_consent_requests
                    SET status='EXPIRED',expired_at=:now,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='PENDING' AND row_version=:expectedVersion
                    """, context.parameters().addValue("id", requestId).addValue("now", now)
                    .addValue("expectedVersion", request.expectedVersion()));
            return new StudentPortalMutationResponse(requestId, request.expectedVersion() + 1, "EXPIRED");
        }
        String reason = required(request.reason(), 500);
        String decision = upper(request.decision());
        if ("DECLINE".equals(decision)) {
            int changed = jdbc.update("""
                    UPDATE student_portal_consent_requests
                    SET status='DECLINED',guardian_decided_by_user_id=:actorUserId,
                        guardian_decided_by_membership_id=:actorMembershipId,guardian_decided_at=:now,
                        guardian_decision_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND learner_id=:learnerId AND guardian_id=:guardianId
                      AND status='PENDING' AND row_version=:expectedVersion
                    """, context.parameters().addValue("id", requestId).addValue("now", now)
                    .addValue("reason", reason).addValue("expectedVersion", request.expectedVersion()));
            if (changed != 1) throw stale("The student access request changed.");
            audit.recordScoped(user, context.actorMembershipId(), context.schoolId(),
                    "student.portal.consent.declined", "STUDENT_PORTAL_CONSENT", requestId.toString(),
                    AuditOutcome.SUCCEEDED, reason, Map.of("learnerId", learnerId));
            return new StudentPortalMutationResponse(requestId, request.expectedVersion() + 1, "DECLINED");
        }
        if (!"APPROVE".equals(decision)) {
            throw bad("STUDENT_PORTAL_DECISION_INVALID", "Choose approve or decline.");
        }
        if (request.password() == null || request.password().length() < 12 || request.password().length() > 200) {
            throw bad("STUDENT_PORTAL_PASSWORD_INVALID",
                    "Create an initial student password between 12 and 200 characters.");
        }

        PortalAccount existing = account(context.organisationId(), context.schoolId(), learnerId, true);
        UUID userAccountId;
        String passwordHash = passwordEncoder.encode(request.password());
        if (existing == null) {
            if (users.findByLoginIdentifierNormalized(consent.loginIdentifier()).isPresent()) {
                throw conflict("STUDENT_PORTAL_LOGIN_UNAVAILABLE",
                        "The proposed school sign-in ID is no longer available. Ask the school to prepare a new request.");
            }
            UserAccount created = users.saveAndFlush(UserAccount.activeWithLoginIdentifier(
                    consent.loginIdentifier(), context.learnerName(), passwordHash));
            userAccountId = created.getId();
        } else {
            if (!"ENDED".equals(existing.status())
                    || !existing.loginIdentifier().equals(consent.loginIdentifier())) {
                throw conflict("STUDENT_PORTAL_ACCOUNT_CONFLICT",
                        "The learner account changed. Ask the school to review access before approval.");
            }
            userAccountId = existing.userAccountId();
            jdbc.update("""
                    UPDATE user_accounts SET password_hash=:passwordHash,password_changed_at=:now,
                        status='ACTIVE',failed_login_count=0,locked_until=NULL,updated_at=:now,
                        row_version=row_version+1
                    WHERE id=:userAccountId AND login_identifier_normalized=:loginIdentifier
                    """, context.parameters().addValue("passwordHash", passwordHash).addValue("now", now)
                    .addValue("userAccountId", userAccountId)
                    .addValue("loginIdentifier", consent.loginIdentifier()));
        }

        int approved = jdbc.update("""
                UPDATE student_portal_consent_requests
                SET status='APPROVED',guardian_decided_by_user_id=:actorUserId,
                    guardian_decided_by_membership_id=:actorMembershipId,guardian_decided_at=:now,
                    guardian_decision_reason=:reason,approved_user_account_id=:userAccountId,
                    updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_id=:learnerId AND guardian_id=:guardianId
                  AND status='PENDING' AND row_version=:expectedVersion
                """, context.parameters().addValue("id", requestId).addValue("now", now)
                .addValue("reason", reason).addValue("userAccountId", userAccountId)
                .addValue("expectedVersion", request.expectedVersion()));
        if (approved != 1) throw stale("The student access request changed.");

        UUID portalAccountId;
        String historyEvent;
        String previousStatus;
        long portalVersion;
        if (existing == null) {
            portalAccountId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO learner_portal_accounts(
                      id,organisation_id,school_id,learner_id,user_account_id,current_consent_request_id,
                      status,activated_at,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:learnerId,:userAccountId,:consentId,
                      'ACTIVE',:now,:now,:now,0)
                    """, context.parameters().addValue("id", portalAccountId)
                    .addValue("userAccountId", userAccountId).addValue("consentId", requestId)
                    .addValue("now", now));
            historyEvent = "ACTIVATED";
            previousStatus = null;
            portalVersion = 0;
        } else {
            portalAccountId = existing.id();
            int changed = jdbc.update("""
                    UPDATE learner_portal_accounts
                    SET current_consent_request_id=:consentId,status='ACTIVE',activated_at=:now,
                        suspended_at=NULL,suspended_by_membership_id=NULL,suspension_reason=NULL,
                        ended_at=NULL,ended_by_membership_id=NULL,ending_reason=NULL,
                        updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND learner_id=:learnerId AND status='ENDED' AND row_version=:expectedVersion
                    """, context.parameters().addValue("id", existing.id()).addValue("consentId", requestId)
                    .addValue("now", now).addValue("expectedVersion", existing.rowVersion()));
            if (changed != 1) throw stale("The learner account changed before it was reactivated.");
            historyEvent = "REACTIVATED";
            previousStatus = "ENDED";
            portalVersion = existing.rowVersion() + 1;
        }
        ensureStudentMembership(context, userAccountId, now);
        appendHistory(context.organisationId(), context.schoolId(), portalAccountId, historyEvent,
                previousStatus, "ACTIVE", requestId, reason, user.userId(),
                context.actorMembershipId(), now);
        audit.recordScoped(user, context.actorMembershipId(), context.schoolId(),
                "student.portal.consent.approved", "STUDENT_PORTAL_CONSENT", requestId.toString(),
                AuditOutcome.SUCCEEDED, reason,
                Map.of("learnerId", learnerId, "portalAccountId", portalAccountId));
        return new StudentPortalMutationResponse(portalAccountId, portalVersion, "ACTIVE");
    }

    @Transactional
    public StudentPortalMutationResponse suspend(
            AuthenticatedUser user, UUID learnerId, StudentPortalVersionedReason request) {
        return staffAccountTransition(user, learnerId, request, "SUSPEND");
    }

    @Transactional
    public StudentPortalMutationResponse resume(
            AuthenticatedUser user, UUID learnerId, StudentPortalVersionedReason request) {
        return staffAccountTransition(user, learnerId, request, "RESUME");
    }

    @Transactional
    public StudentPortalMutationResponse end(
            AuthenticatedUser user, UUID learnerId, StudentPortalVersionedReason request) {
        return staffAccountTransition(user, learnerId, request, "END");
    }

    @Transactional
    public StudentPortalMutationResponse withdraw(
            AuthenticatedUser user, UUID learnerId, StudentPortalVersionedReason request) {
        GuardianContext context = requireGuardianContext(user, learnerId, true);
        PortalAccount account = account(context.organisationId(), context.schoolId(), learnerId, true);
        if (account == null || !Set.of("ACTIVE", "SUSPENDED").contains(account.status())) {
            throw conflict("STUDENT_PORTAL_ACCOUNT_NOT_OPEN",
                    "There is no active or suspended learner account to withdraw.");
        }
        Consent consent = requireGuardianConsent(context, account.consentRequestId(), true);
        if (!"APPROVED".equals(consent.status())) {
            throw conflict("STUDENT_PORTAL_CONSENT_NOT_CURRENT",
                    "The current guardian approval is no longer available.");
        }
        if (consent.rowVersion() != request.expectedVersion()) {
            throw stale("The guardian approval changed before withdrawal.");
        }
        String reason = required(request.reason(), 500);
        Instant now = Instant.now(clock);
        endAccount(account, context.actorMembershipId(), reason, now);
        closeStudentIdentity(account.userAccountId(), context.organisationId(), context.schoolId(), now, "ENDED");
        appendHistory(context.organisationId(), context.schoolId(), account.id(), "WITHDRAWN",
                account.status(), "ENDED", consent.id(), reason, user.userId(),
                context.actorMembershipId(), now);
        int withdrawn = jdbc.update("""
                UPDATE student_portal_consent_requests
                SET status='WITHDRAWN',withdrawn_by_user_id=:actorUserId,
                    withdrawn_by_membership_id=:actorMembershipId,withdrawn_at=:now,
                    withdrawal_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_id=:learnerId AND guardian_id=:guardianId
                  AND status='APPROVED' AND row_version=:expectedVersion
                """, context.parameters().addValue("id", consent.id()).addValue("now", now)
                .addValue("reason", reason).addValue("expectedVersion", request.expectedVersion()));
        if (withdrawn != 1) throw stale("The guardian approval changed before withdrawal.");
        audit.recordScoped(user, context.actorMembershipId(), context.schoolId(),
                "student.portal.consent.withdrawn", "STUDENT_PORTAL_CONSENT", consent.id().toString(),
                AuditOutcome.SUCCEEDED, reason, Map.of("learnerId", learnerId));
        return new StudentPortalMutationResponse(account.id(), account.rowVersion() + 1, "ENDED");
    }

    private StudentPortalMutationResponse staffAccountTransition(
            AuthenticatedUser user,
            UUID learnerId,
            StudentPortalVersionedReason request,
            String action) {
        StaffLearner learner = requireStaffLearner(user, learnerId, true);
        PortalAccount account = account(learner.organisationId(), learner.schoolId(), learnerId, true);
        if (account == null) throw notFound("STUDENT_PORTAL_ACCOUNT_NOT_FOUND", "The learner account was not found.");
        if (account.rowVersion() != request.expectedVersion()) throw stale("The learner account changed.");
        String reason = required(request.reason(), 500);
        Instant now = Instant.now(clock);
        String nextStatus;
        String event;
        if ("SUSPEND".equals(action)) {
            if (!"ACTIVE".equals(account.status())) {
                throw conflict("STUDENT_PORTAL_NOT_ACTIVE", "Only active learner access can be suspended.");
            }
            int changed = jdbc.update("""
                    UPDATE learner_portal_accounts
                    SET status='SUSPENDED',suspended_at=:now,suspended_by_membership_id=:actorMembershipId,
                        suspension_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND learner_id=:learnerId AND status='ACTIVE' AND row_version=:expectedVersion
                    """, learner.parameters().addValue("id", account.id()).addValue("now", now)
                    .addValue("reason", reason).addValue("expectedVersion", request.expectedVersion()));
            if (changed != 1) throw stale("The learner account changed.");
            closeStudentIdentity(account.userAccountId(), learner.organisationId(), learner.schoolId(), now, "SUSPENDED");
            nextStatus = "SUSPENDED";
            event = "SUSPENDED";
        } else if ("RESUME".equals(action)) {
            if (!"SUSPENDED".equals(account.status())) {
                throw conflict("STUDENT_PORTAL_NOT_SUSPENDED", "Only suspended learner access can be resumed.");
            }
            int changed = jdbc.update("""
                    UPDATE learner_portal_accounts
                    SET status='ACTIVE',suspended_at=NULL,suspended_by_membership_id=NULL,
                        suspension_reason=NULL,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND learner_id=:learnerId AND status='SUSPENDED' AND row_version=:expectedVersion
                    """, learner.parameters().addValue("id", account.id()).addValue("now", now)
                    .addValue("expectedVersion", request.expectedVersion()));
            if (changed != 1) throw stale("The learner account changed.");
            activateStudentIdentity(account.userAccountId(), learner.organisationId(), learner.schoolId(), now);
            nextStatus = "ACTIVE";
            event = "RESUMED";
        } else {
            if (!Set.of("ACTIVE", "SUSPENDED").contains(account.status())) {
                throw conflict("STUDENT_PORTAL_ALREADY_ENDED", "This learner access has already ended.");
            }
            endAccount(account, learner.actorMembershipId(), reason, now);
            closeStudentIdentity(account.userAccountId(), learner.organisationId(), learner.schoolId(), now, "ENDED");
            int consentRevoked = jdbc.update("""
                    UPDATE student_portal_consent_requests
                    SET status='REVOKED',revoked_by_membership_id=:actorMembershipId,
                        revoked_at=:now,revoked_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='APPROVED'
                    """, learner.parameters().addValue("id", account.consentRequestId())
                    .addValue("now", now).addValue("reason", reason));
            if (consentRevoked != 1) {
                throw conflict("STUDENT_PORTAL_CONSENT_NOT_CURRENT",
                        "The approved guardian decision changed before access ended.");
            }
            nextStatus = "ENDED";
            event = "ENDED";
        }
        appendHistory(learner.organisationId(), learner.schoolId(), account.id(), event,
                account.status(), nextStatus, account.consentRequestId(), reason, user.userId(),
                learner.actorMembershipId(), now);
        audit.recordScoped(user, learner.actorMembershipId(), learner.schoolId(),
                "student.portal.account." + action.toLowerCase(Locale.ROOT), "LEARNER_PORTAL_ACCOUNT",
                account.id().toString(), AuditOutcome.SUCCEEDED, reason, Map.of("learnerId", learnerId));
        return new StudentPortalMutationResponse(account.id(), request.expectedVersion() + 1, nextStatus);
    }

    private StudentPortalAccessSnapshot snapshot(StaffLearner learner) {
        List<StudentPortalAccessSnapshot.GuardianOption> guardians = jdbc.query("""
                SELECT guardian.id,guardian.display_name,relationship.relationship_code,
                       guardian.status,relationship.portal_access_status,guardian.user_account_id,
                       relationship.row_version
                FROM learner_guardian_relationships relationship
                JOIN guardians guardian ON guardian.id=relationship.guardian_id
                  AND guardian.organisation_id=relationship.organisation_id
                  AND guardian.school_id=relationship.school_id
                WHERE relationship.organisation_id=:organisationId AND relationship.school_id=:schoolId
                  AND relationship.learner_id=:learnerId
                ORDER BY relationship.primary_contact DESC,guardian.display_name,guardian.id
                """, learner.parameters(), (r, n) -> new StudentPortalAccessSnapshot.GuardianOption(
                r.getObject("id", UUID.class), r.getString("display_name"), r.getString("relationship_code"),
                r.getString("status"), r.getString("portal_access_status"),
                r.getObject("user_account_id", UUID.class) != null, r.getLong("row_version")));
        PortalAccount storedAccount = account(learner.organisationId(), learner.schoolId(), learner.learnerId(), false);
        StudentPortalAccessSnapshot.PortalAccount responseAccount = storedAccount == null ? null
                : new StudentPortalAccessSnapshot.PortalAccount(
                        storedAccount.id(), storedAccount.loginIdentifier(), storedAccount.status(),
                        storedAccount.activatedAt(), storedAccount.suspendedAt(), storedAccount.suspensionReason(),
                        storedAccount.endedAt(), storedAccount.endingReason(), storedAccount.rowVersion());
        List<StudentPortalAccessSnapshot.ConsentRequest> requests = jdbc.query("""
                SELECT request.id,request.guardian_id,guardian.display_name,request.supersedes_request_id,
                       request.proposed_login_identifier_normalized,request.privacy_notice_version,
                       request.purpose_summary,request.adult_verification_method,
                       request.adult_verification_reference,request.adult_verified_at,request.status,
                       request.expires_at,request.created_at,request.guardian_decided_at,
                       request.guardian_decision_reason,request.revoked_at,request.revoked_reason,
                       request.withdrawn_at,request.withdrawal_reason,request.row_version
                FROM student_portal_consent_requests request
                JOIN guardians guardian ON guardian.id=request.guardian_id
                  AND guardian.organisation_id=request.organisation_id AND guardian.school_id=request.school_id
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND request.learner_id=:learnerId
                ORDER BY request.created_at DESC,request.id DESC LIMIT 30
                """, learner.parameters(), (r, n) -> new StudentPortalAccessSnapshot.ConsentRequest(
                r.getObject("id", UUID.class), r.getObject("guardian_id", UUID.class),
                r.getString("display_name"), r.getObject("supersedes_request_id", UUID.class),
                r.getString("proposed_login_identifier_normalized"), r.getString("privacy_notice_version"),
                r.getString("purpose_summary"), r.getString("adult_verification_method"),
                r.getString("adult_verification_reference"), instant(r, "adult_verified_at"),
                r.getString("status"), instant(r, "expires_at"), instant(r, "created_at"),
                instant(r, "guardian_decided_at"), r.getString("guardian_decision_reason"),
                instant(r, "revoked_at"), r.getString("revoked_reason"),
                instant(r, "withdrawn_at"), r.getString("withdrawal_reason"), r.getLong("row_version")));
        List<StudentPortalAccessSnapshot.HistoryEvent> history = storedAccount == null ? List.of() : jdbc.query("""
                SELECT history.id,history.event_type,history.previous_status,history.new_status,
                       history.consent_request_id,history.reason,account.display_name,history.occurred_at
                FROM learner_portal_account_history history
                JOIN user_accounts account ON account.id=history.actor_user_id
                WHERE history.organisation_id=:organisationId AND history.school_id=:schoolId
                  AND history.learner_portal_account_id=:accountId
                ORDER BY history.occurred_at DESC,history.id DESC LIMIT 50
                """, learner.parameters().addValue("accountId", storedAccount.id()),
                (r, n) -> new StudentPortalAccessSnapshot.HistoryEvent(
                        r.getObject("id", UUID.class), r.getString("event_type"),
                        r.getString("previous_status"), r.getString("new_status"),
                        r.getObject("consent_request_id", UUID.class), r.getString("reason"),
                        r.getString("display_name"), instant(r, "occurred_at")));
        return new StudentPortalAccessSnapshot(
                learner.learnerId(), learner.legalName(), learner.birthDate(), learner.status(),
                learner.rowVersion(), true, guardians, responseAccount, requests, history);
    }

    private FamilyStudentPortalAccessResponse familyResponse(
            GuardianContext context, PortalAccount account) {
        FamilyStudentPortalAccessResponse.PortalAccount responseAccount = account == null ? null
                : new FamilyStudentPortalAccessResponse.PortalAccount(
                        account.loginIdentifier(), account.status(), account.activatedAt(),
                        account.suspendedAt(), account.suspensionReason(), account.endedAt(),
                        account.endingReason(), account.rowVersion());
        List<FamilyStudentPortalAccessResponse.ConsentRequest> requests = jdbc.query("""
                SELECT id,proposed_login_identifier_normalized,privacy_notice_version,purpose_summary,
                       status,expires_at,created_at,guardian_decided_at,guardian_decision_reason,
                       withdrawn_at,withdrawal_reason,row_version
                FROM student_portal_consent_requests
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_id=:learnerId AND guardian_id=:guardianId
                ORDER BY created_at DESC,id DESC LIMIT 20
                """, context.parameters(), (r, n) -> new FamilyStudentPortalAccessResponse.ConsentRequest(
                r.getObject("id", UUID.class), r.getString("proposed_login_identifier_normalized"),
                r.getString("privacy_notice_version"), r.getString("purpose_summary"), r.getString("status"),
                instant(r, "expires_at"), instant(r, "created_at"), instant(r, "guardian_decided_at"),
                r.getString("guardian_decision_reason"), instant(r, "withdrawn_at"),
                r.getString("withdrawal_reason"), r.getLong("row_version")));
        return new FamilyStudentPortalAccessResponse(
                context.learnerId(), context.learnerName(), responseAccount, requests);
    }

    private StaffLearner requireStaffLearner(AuthenticatedUser user, UUID learnerId, boolean lock) {
        requireOrganisationAndMembership(user);
        List<StaffLearner> rows = jdbc.query("""
                SELECT learner.id,learner.organisation_id,learner.school_id,learner.legal_name,
                       learner.birth_date,learner.status,learner.row_version,school.time_zone,
                       membership.id AS actor_membership_id,
                       EXISTS(SELECT 1 FROM student_enrolments enrolment
                         WHERE enrolment.learner_id=learner.id
                           AND enrolment.organisation_id=learner.organisation_id
                           AND enrolment.school_id=learner.school_id AND enrolment.status='ACTIVE') AS active_enrolment
                FROM learners learner
                JOIN schools school ON school.id=learner.school_id AND school.organisation_id=learner.organisation_id
                JOIN memberships membership ON membership.user_id=:actorUserId
                  AND membership.organisation_id=learner.organisation_id
                  AND membership.school_id=learner.school_id AND membership.id IN (:membershipIds)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                WHERE learner.id=:learnerId AND learner.organisation_id=:organisationId
                  AND EXISTS(SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code=:capability)
                ORDER BY membership.created_at,membership.id LIMIT 1
                """ + (lock ? " FOR UPDATE OF learner" : ""), base(user).addValue("learnerId", learnerId)
                .addValue("capability", MANAGE), (r, n) -> new StaffLearner(
                r.getObject("organisation_id", UUID.class), r.getObject("school_id", UUID.class),
                r.getObject("id", UUID.class), r.getString("legal_name"),
                r.getObject("birth_date", LocalDate.class), r.getString("status"), r.getLong("row_version"),
                r.getString("time_zone"), r.getObject("actor_membership_id", UUID.class),
                r.getBoolean("active_enrolment")));
        if (rows.isEmpty()) throw notFound("STUDENT_PORTAL_LEARNER_NOT_FOUND", "The learner was not found.");
        return rows.getFirst();
    }

    private GuardianApprovalScope requireGuardianForPreparation(StaffLearner learner, UUID guardianId) {
        List<GuardianApprovalScope> rows = jdbc.query("""
                SELECT guardian.id,guardian.status,guardian.user_account_id,
                       account.status AS account_status,relationship.portal_access_status,
                       relationship.row_version
                FROM learner_guardian_relationships relationship
                JOIN guardians guardian ON guardian.id=relationship.guardian_id
                  AND guardian.organisation_id=relationship.organisation_id
                  AND guardian.school_id=relationship.school_id
                LEFT JOIN user_accounts account ON account.id=guardian.user_account_id
                WHERE relationship.organisation_id=:organisationId AND relationship.school_id=:schoolId
                  AND relationship.learner_id=:learnerId AND relationship.guardian_id=:guardianId
                FOR UPDATE OF relationship,guardian
                """, learner.parameters().addValue("guardianId", guardianId), (r, n) -> new GuardianApprovalScope(
                r.getObject("id", UUID.class), r.getString("status"),
                r.getObject("user_account_id", UUID.class), r.getString("account_status"),
                r.getString("portal_access_status"), r.getLong("row_version")));
        if (rows.isEmpty()) throw bad("STUDENT_PORTAL_GUARDIAN_INVALID",
                "Choose a guardian relationship for this learner.");
        return rows.getFirst();
    }

    private GuardianContext requireGuardianContext(AuthenticatedUser user, UUID learnerId, boolean lock) {
        requireOrganisationAndMembership(user);
        List<GuardianContext> rows = jdbc.query("""
                SELECT guardian.id AS guardian_id,learner.id AS learner_id,learner.legal_name,
                       learner.organisation_id,learner.school_id,membership.id AS actor_membership_id
                FROM guardians guardian
                JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
                  AND relationship.organisation_id=guardian.organisation_id
                  AND relationship.school_id=guardian.school_id AND relationship.portal_access_status='ACTIVE'
                JOIN learners learner ON learner.id=relationship.learner_id
                  AND learner.organisation_id=relationship.organisation_id
                  AND learner.school_id=relationship.school_id AND learner.status='ACTIVE'
                JOIN memberships membership ON membership.user_id=:actorUserId
                  AND membership.organisation_id=guardian.organisation_id
                  AND membership.school_id=guardian.school_id AND membership.id IN (:membershipIds)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                WHERE guardian.user_account_id=:actorUserId AND guardian.organisation_id=:organisationId
                  AND guardian.status='ACTIVE' AND learner.id=:learnerId
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='portal.guardian')
                ORDER BY membership.created_at,membership.id LIMIT 1
                """ + (lock ? " FOR UPDATE OF relationship" : ""),
                base(user).addValue("learnerId", learnerId), (r, n) -> new GuardianContext(
                r.getObject("organisation_id", UUID.class), r.getObject("school_id", UUID.class),
                r.getObject("learner_id", UUID.class), r.getString("legal_name"),
                r.getObject("guardian_id", UUID.class), r.getObject("actor_membership_id", UUID.class),
                user.userId()));
        if (rows.isEmpty()) throw notFound("FAMILY_CHILD_NOT_FOUND",
                "This learner is not available to your active guardian relationship.");
        return rows.getFirst();
    }

    private Consent requireConsent(StaffLearner learner, UUID requestId, boolean lock) {
        return consent("""
                SELECT id,learner_id,guardian_id,proposed_login_identifier_normalized,status,
                       expires_at,approved_user_account_id,row_version
                FROM student_portal_consent_requests
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_id=:learnerId
                """ + (lock ? " FOR UPDATE" : ""),
                learner.parameters().addValue("id", requestId));
    }

    private Consent requireGuardianConsent(GuardianContext context, UUID requestId, boolean lock) {
        return consent("""
                SELECT id,learner_id,guardian_id,proposed_login_identifier_normalized,status,
                       expires_at,approved_user_account_id,row_version
                FROM student_portal_consent_requests
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_id=:learnerId AND guardian_id=:guardianId
                """ + (lock ? " FOR UPDATE" : ""),
                context.parameters().addValue("id", requestId));
    }

    private Consent consent(String sql, MapSqlParameterSource parameters) {
        List<Consent> rows = jdbc.query(sql, parameters, (r, n) -> new Consent(
                r.getObject("id", UUID.class), r.getObject("learner_id", UUID.class),
                r.getObject("guardian_id", UUID.class), r.getString("proposed_login_identifier_normalized"),
                r.getString("status"), instant(r, "expires_at"),
                r.getObject("approved_user_account_id", UUID.class), r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("STUDENT_PORTAL_REQUEST_NOT_FOUND",
                "The student portal request was not found.");
        return rows.getFirst();
    }

    private PortalAccount account(UUID organisationId, UUID schoolId, UUID learnerId, boolean lock) {
        List<PortalAccount> rows = jdbc.query("""
                SELECT portal.id,portal.organisation_id,portal.school_id,
                       portal.user_account_id,portal.current_consent_request_id,
                       account.login_identifier_normalized,portal.status,portal.activated_at,
                       portal.suspended_at,portal.suspension_reason,portal.ended_at,
                       portal.ending_reason,portal.row_version
                FROM learner_portal_accounts portal
                JOIN user_accounts account ON account.id=portal.user_account_id
                WHERE portal.organisation_id=:organisationId AND portal.school_id=:schoolId
                  AND portal.learner_id=:learnerId
                """ + (lock ? " FOR UPDATE OF portal" : ""), new MapSqlParameterSource()
                .addValue("organisationId", organisationId).addValue("schoolId", schoolId)
                .addValue("learnerId", learnerId), (r, n) -> new PortalAccount(
                r.getObject("id", UUID.class), r.getObject("organisation_id", UUID.class),
                r.getObject("school_id", UUID.class), r.getObject("user_account_id", UUID.class),
                r.getObject("current_consent_request_id", UUID.class),
                r.getString("login_identifier_normalized"), r.getString("status"),
                instant(r, "activated_at"), instant(r, "suspended_at"), r.getString("suspension_reason"),
                instant(r, "ended_at"), r.getString("ending_reason"), r.getLong("row_version")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private UUID ensureStudentMembership(GuardianContext context, UUID userAccountId, Instant now) {
        List<Membership> memberships = jdbc.query("""
                SELECT id,status FROM memberships
                WHERE user_id=:userAccountId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND campus_id IS NULL
                FOR UPDATE
                """, context.parameters().addValue("userAccountId", userAccountId),
                (r, n) -> new Membership(r.getObject("id", UUID.class), r.getString("status")));
        UUID membershipId;
        if (memberships.isEmpty()) {
            membershipId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,
                      valid_from,valid_until,created_at,updated_at,row_version)
                    VALUES(:id,:userAccountId,:organisationId,:schoolId,NULL,'ACTIVE',:now,NULL,:now,:now,0)
                    """, context.parameters().addValue("id", membershipId)
                    .addValue("userAccountId", userAccountId).addValue("now", now));
        } else {
            membershipId = memberships.getFirst().id();
            jdbc.update("""
                    UPDATE memberships SET status='ACTIVE',valid_from=:now,valid_until=NULL,
                      updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                    """, context.parameters().addValue("id", membershipId).addValue("now", now));
        }
        Integer foreignRoles = jdbc.queryForObject("""
                SELECT count(*) FROM membership_roles
                WHERE membership_id=:membershipId AND role_id<>CAST(:studentRoleId AS uuid)
                """, context.parameters().addValue("membershipId", membershipId)
                .addValue("studentRoleId", STUDENT_ROLE_ID), Integer.class);
        if (foreignRoles != null && foreignRoles > 0) {
            throw conflict("STUDENT_PORTAL_MIXED_ROLE_ACCOUNT",
                    "A learner-owned account cannot share staff, guardian, or administrative roles.");
        }
        jdbc.update("""
                INSERT INTO membership_roles(membership_id,role_id)
                VALUES(:membershipId,CAST(:studentRoleId AS uuid)) ON CONFLICT DO NOTHING
                """, context.parameters().addValue("membershipId", membershipId)
                .addValue("studentRoleId", STUDENT_ROLE_ID));
        return membershipId;
    }

    private void endAccount(PortalAccount account, UUID actorMembershipId, String reason, Instant now) {
        int changed = jdbc.update("""
                UPDATE learner_portal_accounts
                SET status='ENDED',ended_at=:now,ended_by_membership_id=:actorMembershipId,
                    ending_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status IN ('ACTIVE','SUSPENDED') AND row_version=:expectedVersion
                """, new MapSqlParameterSource().addValue("id", account.id())
                .addValue("organisationId", account.organisationId())
                .addValue("schoolId", account.schoolId()).addValue("actorMembershipId", actorMembershipId)
                .addValue("reason", reason).addValue("now", now)
                .addValue("expectedVersion", account.rowVersion()));
        if (changed != 1) throw stale("The learner account changed before it ended.");
    }

    private void closeStudentIdentity(
            UUID userAccountId, UUID organisationId, UUID schoolId, Instant now, String target) {
        String accountStatus = "SUSPENDED".equals(target) ? "SUSPENDED" : "DISABLED";
        String membershipStatus = "SUSPENDED".equals(target) ? "SUSPENDED" : "ENDED";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userAccountId", userAccountId).addValue("organisationId", organisationId)
                .addValue("schoolId", schoolId).addValue("now", now)
                .addValue("accountStatus", accountStatus).addValue("membershipStatus", membershipStatus);
        jdbc.update("""
                UPDATE user_accounts SET status=:accountStatus,updated_at=:now,row_version=row_version+1
                WHERE id=:userAccountId
                """, parameters);
        jdbc.update("""
                UPDATE memberships SET status=:membershipStatus,
                  valid_until=CASE WHEN :membershipStatus='ENDED' THEN :now ELSE valid_until END,
                  updated_at=:now,row_version=row_version+1
                WHERE user_id=:userAccountId AND organisation_id=:organisationId AND school_id=:schoolId
                """, parameters);
        jdbc.update("""
                UPDATE user_sessions SET revoked_at=:now
                WHERE user_id=:userAccountId AND revoked_at IS NULL
                """, parameters);
    }

    private void activateStudentIdentity(
            UUID userAccountId, UUID organisationId, UUID schoolId, Instant now) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("userAccountId", userAccountId).addValue("organisationId", organisationId)
                .addValue("schoolId", schoolId).addValue("now", now);
        jdbc.update("""
                UPDATE user_accounts SET status='ACTIVE',failed_login_count=0,locked_until=NULL,
                  updated_at=:now,row_version=row_version+1 WHERE id=:userAccountId
                """, parameters);
        jdbc.update("""
                UPDATE memberships SET status='ACTIVE',valid_from=:now,valid_until=NULL,
                  updated_at=:now,row_version=row_version+1
                WHERE user_id=:userAccountId AND organisation_id=:organisationId AND school_id=:schoolId
                """, parameters);
    }

    private void appendHistory(
            UUID organisationId,
            UUID schoolId,
            UUID portalAccountId,
            String event,
            String previousStatus,
            String newStatus,
            UUID consentRequestId,
            String reason,
            UUID actorUserId,
            UUID actorMembershipId,
            Instant now) {
        jdbc.update("""
                INSERT INTO learner_portal_account_history(
                  id,organisation_id,school_id,learner_portal_account_id,event_type,
                  previous_status,new_status,consent_request_id,reason,actor_user_id,
                  actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:accountId,:event,:previousStatus,
                  :newStatus,:consentId,:reason,:actorUserId,:actorMembershipId,:now)
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                .addValue("organisationId", organisationId).addValue("schoolId", schoolId)
                .addValue("accountId", portalAccountId).addValue("event", event)
                .addValue("previousStatus", previousStatus).addValue("newStatus", newStatus)
                .addValue("consentId", consentRequestId).addValue("reason", reason)
                .addValue("actorUserId", actorUserId).addValue("actorMembershipId", actorMembershipId)
                .addValue("now", now));
    }

    private void expirePending(StaffLearner learner) {
        Instant now = Instant.now(clock);
        jdbc.update("""
                UPDATE student_portal_consent_requests
                SET status='EXPIRED',expired_at=:now,updated_at=:now,row_version=row_version+1
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_id=:learnerId AND status='PENDING' AND expires_at<=:now
                """, learner.parameters().addValue("now", now));
    }

    private void validateVerificationReference(String method, String reference) {
        if (!VERIFICATION_METHODS.contains(method)
                || !VERIFICATION_REFERENCES.get(method).matcher(reference).matches()) {
            throw bad("STUDENT_PORTAL_ADULT_VERIFICATION_REFERENCE_INVALID",
                    "Use an opaque school-record, authorized-token, or review-case reference; never enter an identity number.");
        }
    }

    private MapSqlParameterSource base(AuthenticatedUser user) {
        return new MapSqlParameterSource().addValue("actorUserId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds());
    }

    private void requireOrganisationAndMembership(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        }
        if (user.membershipIds().isEmpty()) {
            throw notFound("STUDENT_PORTAL_SCOPE_NOT_FOUND", "No active school access is available.");
        }
    }

    private static String normalizeLogin(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,79}")) {
            throw bad("STUDENT_PORTAL_LOGIN_INVALID",
                    "Use 3–80 letters, numbers, dots, underscores, or hyphens for the school sign-in ID.");
        }
        return normalized;
    }

    private static String required(String value, int max) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (clean.length() < 1 || clean.length() > max) {
            throw bad("STUDENT_PORTAL_VALUE_INVALID", "Enter a value between 1 and " + max + " characters.");
        }
        return clean;
    }

    private static String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column) == null ? null : result.getTimestamp(column).toInstant();
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException stale(String message) {
        return conflict("STUDENT_PORTAL_STALE", message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private record StaffLearner(
            UUID organisationId,
            UUID schoolId,
            UUID learnerId,
            String legalName,
            LocalDate birthDate,
            String status,
            long rowVersion,
            String timeZone,
            UUID actorMembershipId,
            boolean activeEnrolment) {
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("learnerId", learnerId)
                    .addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record GuardianApprovalScope(
            UUID guardianId,
            String guardianStatus,
            UUID guardianUserId,
            String guardianAccountStatus,
            String portalAccessStatus,
            long relationshipVersion) {}

    private record GuardianContext(
            UUID organisationId,
            UUID schoolId,
            UUID learnerId,
            String learnerName,
            UUID guardianId,
            UUID actorMembershipId,
            UUID actorUserId) {
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("learnerId", learnerId)
                    .addValue("guardianId", guardianId).addValue("actorMembershipId", actorMembershipId)
                    .addValue("actorUserId", actorUserId);
        }
    }

    private record Consent(
            UUID id,
            UUID learnerId,
            UUID guardianId,
            String loginIdentifier,
            String status,
            Instant expiresAt,
            UUID approvedUserAccountId,
            long rowVersion) {}

    private record PortalAccount(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            UUID userAccountId,
            UUID consentRequestId,
            String loginIdentifier,
            String status,
            Instant activatedAt,
            Instant suspendedAt,
            String suspensionReason,
            Instant endedAt,
            String endingReason,
            long rowVersion) {}

    private record Membership(UUID id, String status) {}
}

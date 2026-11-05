package com.educore.api.parentrequests;

import static com.educore.api.parentrequests.FamilyParentRequestController.*;
import static com.educore.api.parentrequests.ParentRequestController.*;
import static com.educore.api.parentrequests.ParentRequestModels.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ParentRequestService {
    private static final Set<String> CATEGORIES = Set.of(
            "FEES", "ATTENDANCE", "ACADEMICS", "HOMEWORK", "TRANSPORT",
            "ADMISSION", "CERTIFICATES", "GENERAL");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> STAFF_CAPABILITIES = Set.of(
            "parent_requests.read", "parent_requests.respond", "parent_requests.manage");
    private static final DateTimeFormatter NUMBER_MONTH = DateTimeFormatter.ofPattern("yyyyMM")
            .withZone(ZoneOffset.UTC);

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ParentRequestService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit,
            ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FamilyList familyList(AuthenticatedUser user, UUID learnerId) {
        FamilyScope scope = familyScope(user, learnerId);
        return new FamilyList(learnerId, summaries(scope.params(),
                "request.learner_id=:learnerId AND request.opened_by_guardian_id=:guardianId", 100));
    }

    @Transactional(readOnly = true)
    public Detail familyDetail(AuthenticatedUser user, UUID learnerId, UUID requestId) {
        FamilyScope scope = familyScope(user, learnerId);
        Stored request = stored(scope.params(), requestId, false,
                "request.learner_id=:learnerId AND request.opened_by_guardian_id=:guardianId");
        return detail(scope.params(), request, false);
    }

    @Transactional
    public Mutation open(AuthenticatedUser user, UUID learnerId, OpenRequest request) {
        FamilyScope scope = familyScope(user, learnerId);
        String category = enumValue(request.category(), CATEGORIES, "category");
        String subject = clean(request.subject(), 180, "subject");
        String body = clean(request.message(), 8000, "message");
        String key = clean(request.idempotencyKey(), 100, "idempotency key");
        String fingerprint = hash(String.join("|", category, subject, body));
        List<Retry> retries = jdbc.query("""
                SELECT id,request_fingerprint,row_version,status FROM parent_requests
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND opened_by_guardian_id=:guardianId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (r, n) -> new Retry(
                r.getObject("id", UUID.class), r.getString("request_fingerprint"),
                r.getLong("row_version"), r.getString("status")));
        if (!retries.isEmpty()) {
            Retry retry = retries.getFirst();
            if (!retry.fingerprint().equals(fingerprint)) throw conflict("PARENT_REQUEST_RETRY_MISMATCH",
                    "That retry key was already used for different request details.");
            return new Mutation(retry.id(), retry.version(), retry.status());
        }
        Instant now = Instant.now(clock);
        UUID id = UUID.randomUUID();
        String number = "PR-" + NUMBER_MONTH.format(now) + "-"
                + id.toString().substring(0, 12).toUpperCase(Locale.ROOT);
        MapSqlParameterSource params = scope.params().addValue("id", id).addValue("number", number)
                .addValue("category", category).addValue("subject", subject).addValue("key", key)
                .addValue("fingerprint", fingerprint).addValue("body", body).addValue("now", now)
                .addValue("messageId", UUID.randomUUID());
        jdbc.update("""
                INSERT INTO parent_requests(id,organisation_id,school_id,request_number,learner_id,
                  opened_by_guardian_id,learner_name_snapshot,guardian_name_snapshot,category,subject,
                  idempotency_key,request_fingerprint,priority,status,opened_at,last_activity_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:number,:learnerId,:guardianId,:learnerName,
                  :guardianName,:category,:subject,:key,:fingerprint,'NORMAL','OPEN',:now,:now,:now,0)
                """, params);
        UUID messageId = insertMessage(params, id, scope.actorMembershipId(), "GUARDIAN", scope.guardianId(), null,
                scope.guardianName(), body, false, key, hash("GUARDIAN|false|" + body), now);
        UUID historyId = history(params, id, "OPENED", null, "OPEN", null, "NORMAL", null, null,
                "Request opened by verified guardian", scope.actorMembershipId(), now);
        outbox(id, "parent_request.opened", scope.organisationId(), scope.schoolId(),
                Map.of("requestId", id, "schoolId", scope.schoolId(), "category", category,
                        "historyId", historyId, "messageId", messageId), now);
        audit(user, scope.actorMembershipId(), scope.schoolId(), "parent_request.opened", id,
                Map.of("category", category));
        return new Mutation(id, 0, "OPEN");
    }

    @Transactional
    public Mutation familyReply(AuthenticatedUser user, UUID learnerId, UUID requestId, ReplyRequest request) {
        FamilyScope scope = familyScope(user, learnerId);
        Stored current = stored(scope.params(), requestId, true,
                "request.learner_id=:learnerId AND request.opened_by_guardian_id=:guardianId");
        String body = clean(request.message(), 8000, "message");
        String key = clean(request.idempotencyKey(), 100, "idempotency key");
        String fingerprint = hash("GUARDIAN|false|" + body);
        Mutation retry = messageRetry(scope.params(), current, scope.actorMembershipId(), key, fingerprint);
        if (retry != null) return retry;
        if ("CLOSED".equals(current.status())) throw conflict("PARENT_REQUEST_CLOSED",
                "This request is closed. Open a new request if more help is needed.");
        requireVersion(current, request.expectedVersion());
        Instant now = Instant.now(clock);
        String next = "RESOLVED".equals(current.status()) ? "ASSIGNED" : current.status();
        int changed = jdbc.update("""
                UPDATE parent_requests SET status=:next,last_activity_at=:now,updated_at=:now,
                  resolved_by_membership_id=CASE WHEN :next='ASSIGNED' THEN NULL ELSE resolved_by_membership_id END,
                  resolved_at=CASE WHEN :next='ASSIGNED' THEN NULL ELSE resolved_at END,
                  resolution_summary=CASE WHEN :next='ASSIGNED' THEN NULL ELSE resolution_summary END,
                  row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND row_version=:version
                """, scope.params().addValue("id", requestId).addValue("next", next)
                .addValue("now", now).addValue("version", current.version()));
        changed(changed);
        UUID messageId = insertMessage(scope.params(), requestId, scope.actorMembershipId(), "GUARDIAN", scope.guardianId(),
                null, scope.guardianName(), body, false, key, fingerprint, now);
        UUID historyId = history(scope.params(), requestId, "PARENT_REPLIED", current.status(), next, current.priority(),
                current.priority(), current.assigneeId(), current.assigneeId(),
                "Guardian added information", scope.actorMembershipId(), now);
        outbox(requestId, "parent_request.guardian_replied", scope.organisationId(), scope.schoolId(),
                Map.of("requestId", requestId, "schoolId", scope.schoolId(), "status", next,
                        "historyId", historyId, "messageId", messageId), now);
        audit(user, scope.actorMembershipId(), scope.schoolId(), "parent_request.guardian_replied",
                requestId, Map.of("status", next));
        return new Mutation(requestId, current.version() + 1, next);
    }

    @Transactional(readOnly = true)
    public Workspace workspace(AuthenticatedUser user, UUID schoolId, String status, String category, String search) {
        StaffScope scope = staffScope(user, schoolId, STAFF_CAPABILITIES);
        String normalizedStatus = optionalEnum(status,
                Set.of("OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED"), "status");
        String normalizedCategory = optionalEnum(category, CATEGORIES, "category");
        String term = cleanOptional(search, 120);
        MapSqlParameterSource params = scope.params().addValue("status", normalizedStatus)
                .addValue("category", normalizedCategory).addValue("search", term == null ? null : "%" + term + "%")
                .addValue("canReadAll", scope.canReadAll()).addValue("selfStaffId", scope.selfStaffId());
        String where = "(:canReadAll OR request.assigned_staff_profile_id=:selfStaffId)"
                + " AND (CAST(:status AS varchar) IS NULL OR request.status=:status)"
                + " AND (CAST(:category AS varchar) IS NULL OR request.category=:category)"
                + " AND (CAST(:search AS varchar) IS NULL OR request.request_number ILIKE :search"
                + " OR request.subject ILIKE :search OR request.learner_name_snapshot ILIKE :search)";
        return new Workspace(scope.schoolId(), scope.schoolName(), scope.canManage(), scope.selfStaffId(),
                summaries(params, where, 200), assignees(scope));
    }

    @Transactional(readOnly = true)
    public Detail staffDetail(AuthenticatedUser user, UUID schoolId, UUID requestId) {
        StaffScope scope = staffScope(user, schoolId, STAFF_CAPABILITIES);
        String access = "(:canReadAll OR request.assigned_staff_profile_id=:selfStaffId)";
        Stored request = stored(scope.params().addValue("canReadAll", scope.canReadAll())
                .addValue("selfStaffId", scope.selfStaffId()), requestId, false, access);
        return detail(scope.params(), request, true);
    }

    @Transactional
    public Mutation assign(AuthenticatedUser user, UUID requestId, AssignmentRequest request) {
        StaffScope scope = staffScope(user, request.schoolId(), Set.of("parent_requests.manage"));
        Stored current = stored(scope.params(), requestId, true, "TRUE");
        requireVersion(current, request.expectedVersion());
        if ("IN_PROGRESS".equals(current.status()) && !request.staffProfileId().equals(current.assigneeId()))
            throw conflict("PARENT_REQUEST_IN_PROGRESS", "Resolve the active work before reassigning this request.");
        String priority = enumValue(request.priority(), PRIORITIES, "priority");
        ensureAssignee(scope, request.staffProfileId());
        String next = Set.of("OPEN", "RESOLVED", "CLOSED").contains(current.status())
                ? "ASSIGNED" : current.status();
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE parent_requests SET status=:next,priority=:priority,
                  assigned_staff_profile_id=:assigneeId,assigned_by_membership_id=:actorMembershipId,
                  assigned_at=:now,resolved_by_membership_id=NULL,resolved_at=NULL,resolution_summary=NULL,
                  closed_by_membership_id=NULL,closed_at=NULL,close_reason=NULL,
                  last_activity_at=:now,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND row_version=:version
                """, scope.params().addValue("id", requestId).addValue("next", next)
                .addValue("priority", priority).addValue("assigneeId", request.staffProfileId())
                .addValue("now", now).addValue("version", current.version()));
        changed(changed);
        String event = current.assigneeId() == null ? "ASSIGNED"
                : current.assigneeId().equals(request.staffProfileId()) ? "PRIORITY_CHANGED" : "REASSIGNED";
        UUID historyId = history(scope.params(), requestId, event, current.status(), next, current.priority(), priority,
                current.assigneeId(), request.staffProfileId(), clean(request.reason(), 500, "reason"),
                scope.actorMembershipId(), now);
        outbox(requestId, "parent_request.assigned", scope.organisationId(), scope.schoolId(),
                Map.of("requestId", requestId, "schoolId", scope.schoolId(),
                        "assignedStaffProfileId", request.staffProfileId(), "priority", priority, "historyId", historyId), now);
        audit(user, scope.actorMembershipId(), scope.schoolId(), "parent_request.assigned", requestId,
                Map.of("priority", priority));
        return new Mutation(requestId, current.version() + 1, next);
    }

    @Transactional
    public Mutation staffReply(AuthenticatedUser user, UUID requestId, StaffReplyRequest request) {
        StaffScope scope = staffScope(user, request.schoolId(), Set.of("parent_requests.respond"));
        requireLinkedStaff(scope);
        Stored current = stored(scope.params(), requestId, true,
                "request.assigned_staff_profile_id=:selfStaffId");
        if (Set.of("RESOLVED", "CLOSED").contains(current.status())) throw conflict("PARENT_REQUEST_NOT_ACTIVE",
                "Reopen this request before adding another staff response.");
        String body = clean(request.message(), 8000, "message");
        String key = clean(request.idempotencyKey(), 100, "idempotency key");
        String fingerprint = hash("STAFF|" + request.internalNote() + "|" + body);
        Mutation retry = messageRetry(scope.params(), current, scope.actorMembershipId(), key, fingerprint);
        if (retry != null) return retry;
        requireVersion(current, request.expectedVersion());
        Instant now = Instant.now(clock);
        String next = "ASSIGNED".equals(current.status()) ? "IN_PROGRESS" : current.status();
        int changed = jdbc.update("""
                UPDATE parent_requests SET status=:next,last_activity_at=:now,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND row_version=:version
                """, scope.params().addValue("id", requestId).addValue("next", next)
                .addValue("now", now).addValue("version", current.version()));
        changed(changed);
        UUID messageId = insertMessage(scope.params(), requestId, scope.actorMembershipId(), "STAFF", null, scope.selfStaffId(),
                scope.actorName(), body, request.internalNote(), key, fingerprint, now);
        UUID historyId = history(scope.params(), requestId, "STAFF_REPLIED", current.status(), next, current.priority(),
                current.priority(), current.assigneeId(), current.assigneeId(),
                request.internalNote() ? "Internal staff note added" : "Response sent to family",
                scope.actorMembershipId(), now);
        if (!request.internalNote()) outbox(requestId, "parent_request.staff_replied", scope.organisationId(),
                scope.schoolId(), Map.of("requestId", requestId, "schoolId", scope.schoolId(),
                        "historyId", historyId, "messageId", messageId), now);
        audit(user, scope.actorMembershipId(), scope.schoolId(), "parent_request.staff_replied", requestId,
                Map.of("internalNote", request.internalNote()));
        return new Mutation(requestId, current.version() + 1, next);
    }

    @Transactional
    public Mutation transition(AuthenticatedUser user, UUID requestId, TransitionRequest request) {
        String action = enumValue(request.action(), Set.of("START", "RESOLVE", "CLOSE", "REOPEN"), "action");
        Set<String> required = Set.of("CLOSE", "REOPEN").contains(action)
                ? Set.of("parent_requests.manage") : Set.of("parent_requests.respond");
        StaffScope scope = staffScope(user, request.schoolId(), required);
        Stored current = stored(scope.params(), requestId, true, "TRUE");
        requireVersion(current, request.expectedVersion());
        String reason = clean(request.reason(), 1000, "reason");
        Instant now = Instant.now(clock);
        String next;
        if ("START".equals(action)) {
            requireAssigned(scope, current);
            if (!"ASSIGNED".equals(current.status())) throw conflict("PARENT_REQUEST_TRANSITION_INVALID",
                    "Only an assigned request can be started.");
            next = "IN_PROGRESS";
        } else if ("RESOLVE".equals(action)) {
            requireAssigned(scope, current);
            if (!Set.of("ASSIGNED", "IN_PROGRESS").contains(current.status()))
                throw conflict("PARENT_REQUEST_TRANSITION_INVALID", "Only active work can be resolved.");
            next = "RESOLVED";
        } else if ("CLOSE".equals(action)) {
            if (!scope.canManage() || !"RESOLVED".equals(current.status()))
                throw conflict("PARENT_REQUEST_TRANSITION_INVALID", "Only a resolved request can be closed.");
            next = "CLOSED";
        } else {
            if (!scope.canManage() || !"CLOSED".equals(current.status()))
                throw conflict("PARENT_REQUEST_TRANSITION_INVALID", "Only a closed request can be reopened.");
            next = "ASSIGNED";
        }
        int changed = jdbc.update("""
                UPDATE parent_requests SET status=:next,
                  resolved_by_membership_id=CASE WHEN :next='RESOLVED' THEN :actorMembershipId
                    WHEN :next='ASSIGNED' THEN NULL ELSE resolved_by_membership_id END,
                  resolved_at=CASE WHEN :next='RESOLVED' THEN :now WHEN :next='ASSIGNED' THEN NULL ELSE resolved_at END,
                  resolution_summary=CASE WHEN :next='RESOLVED' THEN :reason WHEN :next='ASSIGNED' THEN NULL ELSE resolution_summary END,
                  closed_by_membership_id=CASE WHEN :next='CLOSED' THEN :actorMembershipId ELSE NULL END,
                  closed_at=CASE WHEN :next='CLOSED' THEN :now ELSE NULL END,
                  close_reason=CASE WHEN :next='CLOSED' THEN :reason ELSE NULL END,
                  last_activity_at=:now,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND row_version=:version
                """, scope.params().addValue("id", requestId).addValue("next", next)
                .addValue("reason", reason).addValue("now", now).addValue("version", current.version()));
        changed(changed);
        String event = Map.of("START", "STARTED", "RESOLVE", "RESOLVED", "CLOSE", "CLOSED", "REOPEN", "REOPENED").get(action);
        UUID historyId = history(scope.params(), requestId, event, current.status(), next, current.priority(), current.priority(),
                current.assigneeId(), current.assigneeId(), reason, scope.actorMembershipId(), now);
        outbox(requestId, "parent_request." + action.toLowerCase(Locale.ROOT), scope.organisationId(),
                scope.schoolId(), Map.of("requestId", requestId, "schoolId", scope.schoolId(), "status", next, "historyId", historyId), now);
        audit(user, scope.actorMembershipId(), scope.schoolId(), "parent_request." + action.toLowerCase(Locale.ROOT),
                requestId, Map.of("status", next));
        return new Mutation(requestId, current.version() + 1, next);
    }

    private FamilyScope familyScope(AuthenticatedUser user, UUID learnerId) {
        if (user.activeOrganisationId() == null) throw conflict("ORGANISATION_SELECTION_REQUIRED",
                "Select a school organisation first.");
        List<FamilyScope> rows = jdbc.query("""
                SELECT guardian.id guardian_id,guardian.display_name guardian_name,
                  learner.school_id,learner.legal_name learner_name,membership.id membership_id
                FROM guardians guardian
                JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
                  AND relationship.organisation_id=guardian.organisation_id
                  AND relationship.school_id=guardian.school_id AND relationship.portal_access_status='ACTIVE'
                JOIN learners learner ON learner.id=relationship.learner_id
                  AND learner.organisation_id=relationship.organisation_id
                  AND learner.school_id=relationship.school_id AND learner.status='ACTIVE'
                JOIN memberships membership ON membership.user_id=guardian.user_account_id
                  AND membership.organisation_id=guardian.organisation_id AND membership.school_id=guardian.school_id
                WHERE guardian.user_account_id=:userId AND guardian.organisation_id=:organisationId
                  AND guardian.status='ACTIVE' AND learner.id=:learnerId AND membership.id IN(:membershipIds)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='portal.guardian')
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId()).addValue("learnerId", learnerId)
                .addValue("membershipIds", user.membershipIds()), (r, n) -> new FamilyScope(
                user.activeOrganisationId(), r.getObject("school_id", UUID.class), learnerId,
                r.getObject("guardian_id", UUID.class), r.getString("learner_name"),
                r.getString("guardian_name"), r.getObject("membership_id", UUID.class)));
        if (rows.isEmpty()) throw notFound("FAMILY_CHILD_NOT_FOUND",
                "This learner is not available to your verified relationship.");
        return rows.getFirst();
    }

    private StaffScope staffScope(AuthenticatedUser user, UUID schoolId, Set<String> requiredCapabilities) {
        if (user.activeOrganisationId() == null) throw conflict("ORGANISATION_SELECTION_REQUIRED",
                "Select a school organisation first.");
        List<StaffScope> rows = jdbc.query("""
                SELECT school.id school_id,school.display_name school_name,membership.id membership_id,
                  profile.id self_staff_id,COALESCE(profile.display_name,account.display_name) actor_name,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='parent_requests.read') can_read,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='parent_requests.respond') can_respond,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='parent_requests.manage') can_manage
                FROM memberships membership JOIN schools school ON school.id=membership.school_id
                  AND school.organisation_id=membership.organisation_id
                JOIN user_accounts account ON account.id=membership.user_id
                LEFT JOIN staff_profiles profile ON profile.school_id=membership.school_id
                  AND profile.organisation_id=membership.organisation_id
                  AND profile.user_account_id=membership.user_id AND profile.status='ACTIVE'
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds) AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code IN(:capabilities))
                ORDER BY can_manage DESC,can_read DESC,membership.created_at,membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId()).addValue("membershipIds", user.membershipIds())
                .addValue("schoolId", schoolId).addValue("capabilities", requiredCapabilities), (r, n) -> new StaffScope(
                user.activeOrganisationId(), r.getObject("school_id", UUID.class), r.getString("school_name"),
                r.getObject("membership_id", UUID.class), r.getObject("self_staff_id", UUID.class),
                r.getString("actor_name"), r.getBoolean("can_read"), r.getBoolean("can_respond"),
                r.getBoolean("can_manage")));
        if (rows.isEmpty()) throw forbidden("PARENT_REQUEST_SCOPE_DENIED",
                "Parent request access is unavailable for this school.");
        StaffScope scope = rows.getFirst();
        if (!scope.canReadAll() && scope.selfStaffId() == null) requireLinkedStaff(scope);
        return scope;
    }

    private List<Summary> summaries(MapSqlParameterSource params, String predicate, int limit) {
        return jdbc.query("""
                SELECT request.*,assignee.display_name assigned_staff_name
                FROM parent_requests request LEFT JOIN staff_profiles assignee
                  ON assignee.id=request.assigned_staff_profile_id
                  AND assignee.organisation_id=request.organisation_id AND assignee.school_id=request.school_id
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId AND
                """ + predicate + " ORDER BY request.last_activity_at DESC,request.id LIMIT " + limit,
                params, (r, n) -> summary(r));
    }

    private Stored stored(MapSqlParameterSource params, UUID id, boolean lock, String predicate) {
        List<Stored> rows = jdbc.query("""
                SELECT request.*,assignee.display_name assigned_staff_name
                FROM parent_requests request LEFT JOIN staff_profiles assignee
                  ON assignee.id=request.assigned_staff_profile_id
                  AND assignee.organisation_id=request.organisation_id AND assignee.school_id=request.school_id
                WHERE request.id=:id AND request.organisation_id=:organisationId
                  AND request.school_id=:schoolId AND
                """ + predicate + (lock ? " FOR UPDATE OF request" : ""), params.addValue("id", id),
                (r, n) -> new Stored(summary(r), r.getString("resolution_summary"),
                        instantNullable(r, "resolved_at"), r.getString("close_reason"),
                        instantNullable(r, "closed_at")));
        if (rows.isEmpty()) throw notFound("PARENT_REQUEST_NOT_FOUND", "The parent request was not found.");
        return rows.getFirst();
    }

    private Detail detail(MapSqlParameterSource params, Stored stored, boolean includeInternal) {
        List<Message> messages = jdbc.query("""
                SELECT id,author_type,author_name_snapshot,body,internal_note,created_at
                FROM parent_request_messages WHERE parent_request_id=:id
                  AND organisation_id=:organisationId AND school_id=:schoolId
                  AND (:includeInternal OR internal_note=FALSE) ORDER BY created_at,id
                """, params.addValue("id", stored.id()).addValue("includeInternal", includeInternal), (r, n) -> new Message(
                r.getObject("id", UUID.class), r.getString("author_type"), r.getString("author_name_snapshot"),
                r.getString("body"), r.getBoolean("internal_note"), instant(r, "created_at")));
        List<History> history = includeInternal ? jdbc.query("""
                SELECT history.*,previous_staff.display_name previous_assignee_name,
                  next_staff.display_name new_assignee_name,actor.display_name actor_name
                FROM parent_request_history history
                JOIN memberships membership ON membership.id=history.actor_membership_id
                JOIN user_accounts actor ON actor.id=membership.user_id
                LEFT JOIN staff_profiles previous_staff ON previous_staff.id=history.previous_assignee_id
                LEFT JOIN staff_profiles next_staff ON next_staff.id=history.new_assignee_id
                WHERE history.parent_request_id=:id AND history.organisation_id=:organisationId
                  AND history.school_id=:schoolId ORDER BY history.occurred_at,history.id
                """, params, (r, n) -> new History(r.getObject("id", UUID.class), r.getString("event_type"),
                r.getString("previous_status"), r.getString("new_status"), r.getString("previous_priority"),
                r.getString("new_priority"), r.getString("previous_assignee_name"),
                r.getString("new_assignee_name"), r.getString("reason"), r.getString("actor_name"),
                instant(r, "occurred_at"))) : List.of();
        return new Detail(stored.summary(), stored.resolutionSummary(), stored.resolvedAt(),
                stored.closeReason(), stored.closedAt(), messages, history);
    }

    private List<Assignee> assignees(StaffScope scope) {
        if (!scope.canManage()) return List.of();
        return jdbc.query("""
                SELECT DISTINCT profile.id,profile.employee_code,profile.display_name,
                  designation.display_name designation
                FROM staff_profiles profile JOIN memberships membership
                  ON membership.user_id=profile.user_account_id
                  AND membership.organisation_id=profile.organisation_id AND membership.school_id=profile.school_id
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN role_capabilities rc ON rc.role_id=mr.role_id AND rc.capability_code='parent_requests.respond'
                LEFT JOIN staff_assignments assignment ON assignment.staff_profile_id=profile.id
                  AND assignment.organisation_id=profile.organisation_id AND assignment.school_id=profile.school_id
                  AND assignment.status='ACTIVE' AND assignment.primary_assignment
                LEFT JOIN staff_designations designation ON designation.id=assignment.staff_designation_id
                WHERE profile.organisation_id=:organisationId AND profile.school_id=:schoolId
                  AND profile.status='ACTIVE' AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                ORDER BY profile.display_name,profile.id
                """, scope.params(), (r, n) -> new Assignee(r.getObject("id", UUID.class),
                r.getString("employee_code"), r.getString("display_name"), r.getString("designation")));
    }

    private void ensureAssignee(StaffScope scope, UUID staffProfileId) {
        boolean valid = assignees(scope).stream().anyMatch(item -> item.staffProfileId().equals(staffProfileId));
        if (!valid) throw new ApiException(HttpStatus.BAD_REQUEST, "PARENT_REQUEST_ASSIGNEE_INVALID",
                "Choose an active staff account with parent-request response access.");
    }

    private void requireAssigned(StaffScope scope, Stored request) {
        requireLinkedStaff(scope);
        if (!scope.selfStaffId().equals(request.assigneeId())) throw forbidden("PARENT_REQUEST_ASSIGNMENT_REQUIRED",
                "This request is assigned to another staff member.");
    }

    private static void requireLinkedStaff(StaffScope scope) {
        if (scope.selfStaffId() == null) throw forbidden("PARENT_REQUEST_STAFF_LINK_REQUIRED",
                "Your login must be linked to an active staff profile for assigned request work.");
    }

    private Mutation messageRetry(MapSqlParameterSource params, Stored current, UUID membershipId,
            String key, String fingerprint) {
        List<String> rows = jdbc.query("""
                SELECT content_fingerprint FROM parent_request_messages
                WHERE parent_request_id=:id AND author_membership_id=:membershipId
                  AND idempotency_key=:key AND organisation_id=:organisationId AND school_id=:schoolId
                """, params.addValue("id", current.id()).addValue("membershipId", membershipId)
                .addValue("key", key), (r, n) -> r.getString(1));
        if (rows.isEmpty()) return null;
        if (!rows.getFirst().equals(fingerprint)) throw conflict("PARENT_REQUEST_RETRY_MISMATCH",
                "That retry key was already used for a different message.");
        return new Mutation(current.id(), current.version(), current.status());
    }

    private UUID insertMessage(MapSqlParameterSource base, UUID requestId, UUID membershipId, String authorType,
            UUID guardianId, UUID staffId, String authorName, String body, boolean internal, String key,
            String fingerprint, Instant now) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parent_request_messages(id,organisation_id,school_id,parent_request_id,author_type,
                  author_guardian_id,author_staff_profile_id,author_membership_id,author_name_snapshot,body,
                  internal_note,idempotency_key,content_fingerprint,created_at)
                VALUES(:messageId,:organisationId,:schoolId,:requestId,:authorType,:guardianId,:staffId,
                  :membershipId,:authorName,:body,:internal,:key,:fingerprint,:now)
                """, new MapSqlParameterSource(base.getValues()).addValue("messageId", messageId)
                .addValue("requestId", requestId).addValue("authorType", authorType)
                .addValue("guardianId", guardianId).addValue("staffId", staffId)
                .addValue("membershipId", membershipId).addValue("authorName", authorName)
                .addValue("body", body).addValue("internal", internal).addValue("key", key)
                .addValue("fingerprint", fingerprint).addValue("now", now));
        return messageId;
    }

    private UUID history(MapSqlParameterSource base, UUID requestId, String event, String previousStatus,
            String newStatus, String previousPriority, String newPriority, UUID previousAssignee,
            UUID newAssignee, String reason, UUID membershipId, Instant now) {
        UUID historyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO parent_request_history(id,organisation_id,school_id,parent_request_id,event_type,
                  previous_status,new_status,previous_priority,new_priority,previous_assignee_id,new_assignee_id,
                  reason,actor_membership_id,occurred_at)
                VALUES(:historyId,:organisationId,:schoolId,:requestId,:event,:previousStatus,:newStatus,
                  :previousPriority,:newPriority,:previousAssignee,:newAssignee,:reason,:membershipId,:now)
                """, new MapSqlParameterSource(base.getValues()).addValue("historyId", historyId)
                .addValue("requestId", requestId).addValue("event", event)
                .addValue("previousStatus", previousStatus).addValue("newStatus", newStatus)
                .addValue("previousPriority", previousPriority).addValue("newPriority", newPriority)
                .addValue("previousAssignee", previousAssignee).addValue("newAssignee", newAssignee)
                .addValue("reason", reason).addValue("membershipId", membershipId).addValue("now", now));
        return historyId;
    }

    private void outbox(UUID requestId, String eventType, UUID organisationId, UUID schoolId,
            Map<String, ?> payload, Instant now) {
        Map<String, Object> scopedPayload = new HashMap<>(payload);
        scopedPayload.put("organisationId", organisationId);
        scopedPayload.put("schoolId", schoolId);
        try {
            jdbc.update("""
                    INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,headers,
                      occurred_at,available_at,attempt_count,created_at)
                    VALUES(:id,'PARENT_REQUEST',:aggregateId,:eventType,CAST(:payload AS jsonb),'{}'::jsonb,
                      :now,:now,0,:now)
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("aggregateId", requestId.toString()).addValue("eventType", eventType)
                    .addValue("payload", objectMapper.writeValueAsString(scopedPayload)).addValue("now", now));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Parent request outbox payload must be serializable", exception);
        }
    }

    private void audit(AuthenticatedUser user, UUID membershipId, UUID schoolId, String action,
            UUID requestId, Map<String, ?> metadata) {
        audit.recordScoped(user, membershipId, schoolId, action, "PARENT_REQUEST", requestId.toString(),
                AuditOutcome.SUCCEEDED, action.replace('.', ' '), metadata);
    }

    private static Summary summary(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Summary(r.getObject("id", UUID.class), r.getString("request_number"),
                r.getObject("learner_id", UUID.class), r.getString("learner_name_snapshot"),
                r.getString("guardian_name_snapshot"), r.getString("category"), r.getString("subject"),
                r.getString("priority"), r.getString("status"),
                r.getObject("assigned_staff_profile_id", UUID.class), r.getString("assigned_staff_name"),
                instant(r, "opened_at"), instant(r, "last_activity_at"), r.getLong("row_version"));
    }

    private static Instant instant(java.sql.ResultSet r, String column) throws java.sql.SQLException {
        return r.getTimestamp(column).toInstant();
    }

    private static Instant instantNullable(java.sql.ResultSet r, String column) throws java.sql.SQLException {
        var value = r.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static void requireVersion(Stored current, long expected) {
        if (current.version() != expected) throw conflict("STALE_VERSION",
                "This request changed. Refresh before trying again.");
    }

    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION", "This request changed. Refresh before trying again.");
    }

    private static String enumValue(String value, Set<String> allowed, String field) {
        String clean = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(clean)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "PARENT_REQUEST_VALUE_INVALID", "Choose a valid " + field + ".");
        return clean;
    }

    private static String optionalEnum(String value, Set<String> allowed, String field) {
        return value == null || value.isBlank() ? null : enumValue(value, allowed, field);
    }

    private static String clean(String value, int max, String field) {
        String result = value == null ? "" : value.strip();
        if (result.isEmpty() || result.length() > max) throw new ApiException(HttpStatus.BAD_REQUEST,
                "PARENT_REQUEST_TEXT_INVALID", "Enter a valid " + field + ".");
        return result;
    }

    private static String cleanOptional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip();
        if (result.length() > max) throw new ApiException(HttpStatus.BAD_REQUEST,
                "PARENT_REQUEST_SEARCH_INVALID", "Search text is too long.");
        return result;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private record Retry(UUID id, String fingerprint, long version, String status) {}

    private record Stored(Summary summary, String resolutionSummary, Instant resolvedAt,
            String closeReason, Instant closedAt) {
        UUID id() { return summary.id(); }
        String status() { return summary.status(); }
        String priority() { return summary.priority(); }
        UUID assigneeId() { return summary.assignedStaffProfileId(); }
        long version() { return summary.rowVersion(); }
    }

    private record FamilyScope(UUID organisationId, UUID schoolId, UUID learnerId, UUID guardianId,
            String learnerName, String guardianName, UUID actorMembershipId) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("learnerId", learnerId)
                    .addValue("guardianId", guardianId).addValue("learnerName", learnerName)
                    .addValue("guardianName", guardianName).addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record StaffScope(UUID organisationId, UUID schoolId, String schoolName, UUID actorMembershipId,
            UUID selfStaffId, String actorName, boolean canReadAll, boolean canRespond, boolean canManage) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId)
                    .addValue("selfStaffId", selfStaffId);
        }
    }
}

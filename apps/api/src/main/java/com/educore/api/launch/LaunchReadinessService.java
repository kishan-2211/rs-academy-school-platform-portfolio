package com.educore.api.launch;

import static com.educore.api.launch.LaunchReadinessController.CreateItemRequest;
import static com.educore.api.launch.LaunchReadinessController.ReviewDecisionRequest;
import static com.educore.api.launch.LaunchReadinessController.ReviseItemRequest;
import static com.educore.api.launch.LaunchReadinessController.SubmitReviewRequest;
import static com.educore.api.launch.LaunchReadinessSnapshot.LaunchReview;
import static com.educore.api.launch.LaunchReadinessSnapshot.ReadinessItem;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LaunchReadinessService {
    private static final Set<String> CATEGORIES = Set.of(
            "SCHOOL_GOVERNANCE", "PRIVACY_SAFEGUARDING", "IDENTITY_ACCESS",
            "ACADEMICS", "FINANCE", "DATA_MIGRATION", "PROVIDERS",
            "DEPLOYMENT_SECURITY", "RELIABILITY", "ACCESSIBILITY_EXPERIENCE",
            "TRAINING_SUPPORT", "OTHER");
    private static final Set<String> STATUSES = Set.of(
            "NOT_STARTED", "IN_PROGRESS", "READY", "BLOCKED", "NOT_APPLICABLE");
    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LaunchReadinessService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LaunchReadinessSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireAnyScope(user, requestedSchoolId);
        List<StoredItem> storedItems = currentItems(scope);
        FrozenSnapshot frozen = freeze(scope, storedItems);
        List<LaunchReview> reviews = reviews(scope, frozen.sha256());
        String state = launchState(scope, frozen, reviews);
        return new LaunchReadinessSnapshot(
                scope.schoolId(), scope.schoolName(), scope.canConfigure(), scope.canApprove(),
                state, frozen.itemCount(), frozen.requiredCount(), frozen.readyCount(),
                frozen.blockedCount(), frozen.sha256(),
                storedItems.stream().map(StoredItem::publicItem).toList(), reviews);
    }

    @Transactional
    public LaunchReadinessMutationResponse createItem(
            AuthenticatedUser user,
            CreateItemRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "launch.configure");
        lock(scope);
        String controlCode = controlCode(request.controlCode());
        Integer duplicates = jdbc.queryForObject("""
                SELECT count(*) FROM school_launch_readiness_items
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND control_code=:controlCode
                """, scope.params().addValue("controlCode", controlCode), Integer.class);
        if (duplicates != null && duplicates > 0) {
            throw conflict("LAUNCH_CONTROL_EXISTS",
                    "The same launch configuration or review already exists.");
        }
        Values values = values(
                false, request.category(), request.title(), request.guidance(), request.required(),
                request.status(), request.evidenceReference(), request.ownerRoleLabel(),
                request.reviewNote());
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("LAUNCH_CONTROL_EXISTS", () -> jdbc.update("""
                INSERT INTO school_launch_readiness_items(
                  id,organisation_id,school_id,control_code,revision_number,supersedes_item_id,
                  system_control,category,title,guidance,required,status,evidence_reference,
                  owner_role_label,review_note,revised_by_membership_id,revised_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:controlCode,1,NULL,FALSE,:category,
                  :title,:guidance,:required,:status,:evidenceReference,:ownerRoleLabel,
                  :reviewNote,:actorMembershipId,:now,0)
                """, itemParams(scope, id, controlCode, 1, null, values, now)));
        audit(user, scope, "launch.readiness.item.created", "LAUNCH_READINESS_ITEM", id,
                reason(request.reason()), Map.of("controlCode", controlCode, "required", values.required()));
        return new LaunchReadinessMutationResponse(id, 0);
    }

    @Transactional
    public LaunchReadinessMutationResponse reviseItem(
            AuthenticatedUser user,
            UUID itemId,
            ReviseItemRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "launch.configure");
        lock(scope);
        StoredItem stored = latestItem(scope, itemId);
        if (stored.revisionNumber() != request.expectedRevision()) {
            throw conflict("STALE_LAUNCH_CONTROL", "This launch control has changed. Refresh and try again.");
        }
        Values values = values(
                stored.systemControl(), request.category(), request.title(), request.guidance(),
                request.required(), request.status(), request.evidenceReference(),
                request.ownerRoleLabel(), request.reviewNote());
        if (stored.systemControl()
                && (!stored.category().equals(values.category()) || !values.required())) {
            throw bad("SYSTEM_LAUNCH_CONTROL_REQUIRED",
                    "A baseline safety control must keep its category and required status.");
        }
        int revision = stored.revisionNumber() + 1;
        if (revision > 1000) {
            throw conflict("LAUNCH_CONTROL_REVISION_LIMIT",
                    "This launch control has reached its revision limit.");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("LAUNCH_CONTROL_REVISION_CONFLICT", () -> jdbc.update("""
                INSERT INTO school_launch_readiness_items(
                  id,organisation_id,school_id,control_code,revision_number,supersedes_item_id,
                  system_control,category,title,guidance,required,status,evidence_reference,
                  owner_role_label,review_note,revised_by_membership_id,revised_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:controlCode,:revision,:supersedesId,
                  :systemControl,:category,:title,:guidance,:required,:status,:evidenceReference,
                  :ownerRoleLabel,:reviewNote,:actorMembershipId,:now,0)
                """, itemParams(scope, id, stored.controlCode(), revision, stored.id(), values, now)
                .addValue("systemControl", stored.systemControl())));
        audit(user, scope, "launch.readiness.item.revised", "LAUNCH_READINESS_ITEM", id,
                reason(request.reason()), Map.of(
                        "controlCode", stored.controlCode(), "revision", revision,
                        "status", values.status()));
        return new LaunchReadinessMutationResponse(id, 0);
    }

    @Transactional
    public LaunchReadinessMutationResponse submitReview(
            AuthenticatedUser user,
            SubmitReviewRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "launch.configure");
        if (!request.evidenceConfirmed()) {
            throw bad("LAUNCH_EVIDENCE_CONFIRMATION_REQUIRED",
                    "Confirm that every readiness reference was independently checked.");
        }
        lock(scope);
        FrozenSnapshot frozen = freeze(scope, currentItems(scope));
        assertReadyForReview(frozen);
        List<PreviousReview> previous = jdbc.query("""
                SELECT id,review_number,status,snapshot_sha256 FROM school_launch_reviews
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY review_number DESC LIMIT 1 FOR UPDATE
                """, scope.params(), (result, row) -> new PreviousReview(
                result.getObject("id", UUID.class), result.getInt("review_number"),
                result.getString("status"), result.getString("snapshot_sha256")));
        if (!previous.isEmpty() && "PENDING".equals(previous.getFirst().status())) {
            throw conflict("LAUNCH_REVIEW_PENDING",
                    "A launch review is already waiting for an independent decision.");
        }
        if (!previous.isEmpty() && "APPROVED".equals(previous.getFirst().status())
                && frozen.sha256().equals(previous.getFirst().sha256())
                && independentlyDecided(scope, previous.getFirst().id())) {
            throw conflict("LAUNCH_ALREADY_APPROVED",
                    "The current readiness evidence already has an approved launch review.");
        }
        int reviewNumber = previous.isEmpty() ? 1 : previous.getFirst().reviewNumber() + 1;
        if (reviewNumber > 1000) {
            throw conflict("LAUNCH_REVIEW_LIMIT", "The launch review history has reached its limit.");
        }
        UUID supersedesId = previous.isEmpty() ? null : previous.getFirst().id();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String statement = required(request.submissionStatement(), "Enter the authority statement.");
        String why = reason(request.reason());
        safe("LAUNCH_REVIEW_CONFLICT", () -> jdbc.update("""
                INSERT INTO school_launch_reviews(
                  id,organisation_id,school_id,review_number,supersedes_review_id,status,
                  item_count,required_count,ready_count,blocked_count,readiness_snapshot,
                  snapshot_sha256,submission_statement,submission_reason,
                  submitted_by_membership_id,submitted_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:reviewNumber,:supersedesId,'PENDING',
                  :itemCount,:requiredCount,:readyCount,:blockedCount,CAST(:snapshot AS jsonb),
                  :snapshotSha,:statement,:reason,:actorMembershipId,:now,0)
                """, scope.params().addValue("id", id).addValue("reviewNumber", reviewNumber)
                .addValue("supersedesId", supersedesId).addValue("itemCount", frozen.itemCount())
                .addValue("requiredCount", frozen.requiredCount()).addValue("readyCount", frozen.readyCount())
                .addValue("blockedCount", frozen.blockedCount()).addValue("snapshot", frozen.json())
                .addValue("snapshotSha", frozen.sha256()).addValue("statement", statement)
                .addValue("reason", why).addValue("now", now)));
        audit(user, scope, "launch.review.submitted", "SCHOOL_LAUNCH_REVIEW", id, why,
                Map.of("reviewNumber", reviewNumber, "requiredCount", frozen.requiredCount()));
        return new LaunchReadinessMutationResponse(id, 0);
    }

    @Transactional
    public LaunchReadinessMutationResponse decideReview(
            AuthenticatedUser user,
            UUID reviewId,
            ReviewDecisionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "launch.approve");
        lock(scope);
        List<StoredReview> rows = jdbc.query("""
                SELECT review.status,review.snapshot_sha256,submitter.user_id AS submitter_user_id,review.row_version
                FROM school_launch_reviews review
                JOIN memberships submitter ON submitter.id=review.submitted_by_membership_id
                  AND submitter.organisation_id=review.organisation_id AND submitter.school_id=review.school_id
                WHERE review.id=:id AND review.organisation_id=:organisationId AND review.school_id=:schoolId
                FOR UPDATE OF review
                """, scope.params().addValue("id", reviewId), (result, row) -> new StoredReview(
                result.getString("status"), result.getString("snapshot_sha256"),
                result.getObject("submitter_user_id", UUID.class),
                result.getLong("row_version")));
        if (rows.isEmpty()) {
            throw notFound("LAUNCH_REVIEW_NOT_FOUND", "The launch review was not found.");
        }
        StoredReview stored = rows.getFirst();
        if (!"PENDING".equals(stored.status()) || stored.rowVersion() != request.expectedVersion()) {
            throw conflict("STALE_LAUNCH_REVIEW", "This launch review has already changed.");
        }
        if (stored.submitterUserId().equals(user.userId())) {
            throw conflict("MAKER_CHECKER_REQUIRED",
                    "A different authorized person must decide this launch review.");
        }
        String decision = enumValue(request.decision(), DECISIONS, "launch decision");
        if ("APPROVED".equals(decision)) {
            FrozenSnapshot current = freeze(scope, currentItems(scope));
            if (!stored.snapshotSha256().equals(current.sha256())) {
                throw conflict("LAUNCH_REVIEW_EVIDENCE_STALE",
                        "Readiness evidence changed after submission. Reject this review and submit a new one.");
            }
            assertReadyForReview(current);
        }
        String why = required(request.reason(), "Enter the independent decision reason.");
        jdbc.queryForObject("SELECT set_config('app.launch_review_decision','approved',true)",
                new MapSqlParameterSource(), String.class);
        int changed = jdbc.update("""
                UPDATE school_launch_reviews SET status=:decision,
                  decided_by_membership_id=:actorMembershipId,decided_at=:now,
                  decision_reason=:reason,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING' AND row_version=:version
                """, scope.params().addValue("id", reviewId).addValue("decision", decision)
                .addValue("reason", why).addValue("now", Instant.now(clock))
                .addValue("version", stored.rowVersion()));
        changed(changed);
        audit(user, scope, "launch.review.decided", "SCHOOL_LAUNCH_REVIEW", reviewId, why,
                Map.of("decision", decision));
        return new LaunchReadinessMutationResponse(reviewId, stored.rowVersion() + 1);
    }

    private List<StoredItem> currentItems(Scope scope) {
        return jdbc.query("""
                SELECT DISTINCT ON (item.control_code) item.*,
                  coalesce(reviser.display_name,'System baseline') AS revised_by
                FROM school_launch_readiness_items item
                LEFT JOIN memberships reviser_membership
                  ON reviser_membership.id=item.revised_by_membership_id
                LEFT JOIN user_accounts reviser ON reviser.id=reviser_membership.user_id
                WHERE item.organisation_id=:organisationId AND item.school_id=:schoolId
                ORDER BY item.control_code,item.revision_number DESC,item.id
                """, scope.params(), (result, row) -> new StoredItem(
                result.getObject("id", UUID.class), result.getString("control_code"),
                result.getInt("revision_number"), result.getBoolean("system_control"),
                result.getString("category"), result.getString("title"),
                result.getString("guidance"), result.getBoolean("required"),
                result.getString("status"), result.getString("evidence_reference"),
                result.getString("owner_role_label"), result.getString("review_note"),
                result.getObject("revised_by_membership_id", UUID.class),
                result.getString("revised_by"), result.getTimestamp("revised_at").toInstant()));
    }

    private StoredItem latestItem(Scope scope, UUID itemId) {
        List<StoredItem> rows = jdbc.query("""
                SELECT item.*,coalesce(reviser.display_name,'System baseline') AS revised_by
                FROM school_launch_readiness_items item
                LEFT JOIN memberships reviser_membership
                  ON reviser_membership.id=item.revised_by_membership_id
                LEFT JOIN user_accounts reviser ON reviser.id=reviser_membership.user_id
                WHERE item.id=:id AND item.organisation_id=:organisationId AND item.school_id=:schoolId
                  AND NOT EXISTS (
                    SELECT 1 FROM school_launch_readiness_items newer
                    WHERE newer.organisation_id=item.organisation_id
                      AND newer.school_id=item.school_id
                      AND newer.control_code=item.control_code
                      AND newer.revision_number>item.revision_number)
                FOR UPDATE OF item
                """, scope.params().addValue("id", itemId), (result, row) -> new StoredItem(
                result.getObject("id", UUID.class), result.getString("control_code"),
                result.getInt("revision_number"), result.getBoolean("system_control"),
                result.getString("category"), result.getString("title"),
                result.getString("guidance"), result.getBoolean("required"),
                result.getString("status"), result.getString("evidence_reference"),
                result.getString("owner_role_label"), result.getString("review_note"),
                result.getObject("revised_by_membership_id", UUID.class),
                result.getString("revised_by"), result.getTimestamp("revised_at").toInstant()));
        if (rows.isEmpty()) {
            throw conflict("STALE_LAUNCH_CONTROL",
                    "This launch control is missing or has a newer revision. Refresh and try again.");
        }
        return rows.getFirst();
    }

    private FrozenSnapshot freeze(Scope scope, List<StoredItem> items) {
        List<Map<String, Object>> frozenItems = items.stream().map(item -> {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.id());
            value.put("controlCode", item.controlCode());
            value.put("revisionNumber", item.revisionNumber());
            value.put("systemControl", item.systemControl());
            value.put("category", item.category());
            value.put("title", item.title());
            value.put("guidance", item.guidance());
            value.put("required", item.required());
            value.put("status", item.status());
            value.put("evidenceReference", item.evidenceReference());
            value.put("ownerRoleLabel", item.ownerRoleLabel());
            value.put("reviewNote", item.reviewNote());
            value.put("revisedByMembershipId", item.reviserMembershipId());
            value.put("revisedAt", item.revisedAt());
            return Collections.unmodifiableMap(value);
        }).toList();
        int requiredCount = (int) items.stream().filter(StoredItem::required).count();
        int readyCount = (int) items.stream().filter(item -> "READY".equals(item.status())).count();
        int blockedCount = (int) items.stream().filter(item -> "BLOCKED".equals(item.status())).count();
        boolean everyRequiredReady = items.stream()
                .filter(StoredItem::required)
                .allMatch(item -> "READY".equals(item.status()));
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schoolId", scope.schoolId());
        snapshot.put("items", frozenItems);
        String json = json(snapshot);
        return new FrozenSnapshot(json, sha256(json), items.size(), requiredCount,
                readyCount, blockedCount, everyRequiredReady);
    }

    private List<LaunchReview> reviews(Scope scope, String currentSha) {
        return jdbc.query("""
                SELECT review.*,submitter.display_name AS submitted_by,
                  decider.display_name AS decided_by
                FROM school_launch_reviews review
                JOIN memberships submitter_membership
                  ON submitter_membership.id=review.submitted_by_membership_id
                JOIN user_accounts submitter ON submitter.id=submitter_membership.user_id
                LEFT JOIN memberships decider_membership
                  ON decider_membership.id=review.decided_by_membership_id
                LEFT JOIN user_accounts decider ON decider.id=decider_membership.user_id
                WHERE review.organisation_id=:organisationId AND review.school_id=:schoolId
                ORDER BY review.review_number DESC LIMIT 30
                """, scope.params(), (result, row) -> new LaunchReview(
                result.getObject("id", UUID.class), result.getInt("review_number"),
                result.getObject("supersedes_review_id", UUID.class), result.getString("status"),
                result.getInt("item_count"), result.getInt("required_count"),
                result.getInt("ready_count"), result.getInt("blocked_count"),
                result.getString("snapshot_sha256"),
                currentSha.equals(result.getString("snapshot_sha256")),
                result.getString("submission_statement"), result.getString("submission_reason"),
                result.getString("submitted_by"), result.getTimestamp("submitted_at").toInstant(),
                result.getString("decided_by"), result.getTimestamp("decided_at") == null
                        ? null : result.getTimestamp("decided_at").toInstant(),
                result.getString("decision_reason"), result.getLong("row_version")));
    }

    private String launchState(Scope scope, FrozenSnapshot frozen, List<LaunchReview> reviews) {
        if (!reviews.isEmpty()) {
            LaunchReview latest = reviews.getFirst();
            if ("PENDING".equals(latest.status())) return "REVIEW_PENDING";
            if ("APPROVED".equals(latest.status())) {
                return latest.matchesCurrentSnapshot() && independentlyDecided(scope, latest.id())
                        ? "GO_LIVE_APPROVED" : "APPROVAL_STALE";
            }
        }
        return eligible(frozen) ? "READY_FOR_REVIEW" : "NOT_READY";
    }

    private boolean independentlyDecided(Scope scope, UUID reviewId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM school_launch_reviews review
                  JOIN memberships submitter ON submitter.id=review.submitted_by_membership_id
                    AND submitter.organisation_id=review.organisation_id AND submitter.school_id=review.school_id
                  JOIN memberships decider ON decider.id=review.decided_by_membership_id
                    AND decider.organisation_id=review.organisation_id AND decider.school_id=review.school_id
                  WHERE review.id=:id AND review.organisation_id=:organisationId AND review.school_id=:schoolId
                    AND submitter.user_id<>decider.user_id)
                """, scope.params().addValue("id", reviewId), Boolean.class));
    }

    private static boolean eligible(FrozenSnapshot frozen) {
        return frozen.itemCount() > 0 && frozen.requiredCount() > 0
                && frozen.everyRequiredReady() && frozen.blockedCount() == 0;
    }

    private static void assertReadyForReview(FrozenSnapshot frozen) {
        if (!eligible(frozen)) {
            throw conflict("LAUNCH_REQUIREMENTS_INCOMPLETE",
                    "Every required launch control must be ready and no control may be blocked.");
        }
    }

    private Values values(
            boolean systemControl,
            String category,
            String title,
            String guidance,
            boolean required,
            String status,
            String evidenceReference,
            String ownerRoleLabel,
            String reviewNote) {
        String normalizedStatus = enumValue(status, STATUSES, "readiness status");
        String evidence = clean(evidenceReference);
        String note = clean(reviewNote);
        if ("READY".equals(normalizedStatus) && evidence == null) {
            throw bad("LAUNCH_EVIDENCE_REQUIRED",
                    "A ready launch control requires a safe evidence reference.");
        }
        if ("BLOCKED".equals(normalizedStatus) && note == null) {
            throw bad("LAUNCH_BLOCK_NOTE_REQUIRED", "Explain what is blocking this launch control.");
        }
        if ("NOT_APPLICABLE".equals(normalizedStatus) && (systemControl || required)) {
            throw bad("LAUNCH_CONTROL_APPLICABLE",
                    "Required and baseline safety controls cannot be marked not applicable.");
        }
        return new Values(
                enumValue(category, CATEGORIES, "launch category"),
                required(title, "Enter a launch control title."),
                required(guidance, "Enter launch control guidance."), required,
                normalizedStatus, evidence, clean(ownerRoleLabel), note);
    }

    private MapSqlParameterSource itemParams(
            Scope scope,
            UUID id,
            String controlCode,
            int revision,
            UUID supersedesId,
            Values values,
            Instant now) {
        return scope.params().addValue("id", id).addValue("controlCode", controlCode)
                .addValue("revision", revision).addValue("supersedesId", supersedesId)
                .addValue("category", values.category()).addValue("title", values.title())
                .addValue("guidance", values.guidance()).addValue("required", values.required())
                .addValue("status", values.status()).addValue("evidenceReference", values.evidenceReference())
                .addValue("ownerRoleLabel", values.ownerRoleLabel()).addValue("reviewNote", values.reviewNote())
                .addValue("now", now);
    }

    private Scope requireAnyScope(AuthenticatedUser user, UUID schoolId) {
        return requireScope(user, schoolId, null);
    }

    private Scope requireScope(AuthenticatedUser user, UUID schoolId, String requiredCapability) {
        if (user == null || user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        }
        if (user.membershipIds() == null || user.membershipIds().isEmpty()) {
            throw forbidden("LAUNCH_READINESS_SCOPE_DENIED",
                    "Launch readiness is unavailable for this school.");
        }
        List<Scope> scopes = jdbc.query("""
                SELECT school.id,school.display_name,membership.id AS membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mrc
                    JOIN roles role_config ON role_config.id=mrc.role_id
                      AND (role_config.organisation_id IS NULL OR role_config.organisation_id=membership.organisation_id)
                    JOIN role_capabilities rcc ON rcc.role_id=mrc.role_id
                    WHERE mrc.membership_id=membership.id
                      AND rcc.capability_code='launch.configure') can_configure,
                  EXISTS(SELECT 1 FROM membership_roles mra
                    JOIN roles role_approve ON role_approve.id=mra.role_id
                      AND (role_approve.organisation_id IS NULL OR role_approve.organisation_id=membership.organisation_id)
                    JOIN role_capabilities rca ON rca.role_id=mra.role_id
                    WHERE mra.membership_id=membership.id
                      AND rca.capability_code='launch.approve') can_approve
                FROM memberships membership
                JOIN schools school ON school.organisation_id=membership.organisation_id
                  AND school.id=membership.school_id AND school.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=membership.organisation_id AND organisation.status='ACTIVE'
                JOIN user_accounts account ON account.id=membership.user_id AND account.status='ACTIVE'
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN roles owned_role ON owned_role.id=mr.role_id
                  AND (owned_role.organisation_id IS NULL OR owned_role.organisation_id=membership.organisation_id)
                JOIN role_capabilities rc ON rc.role_id=mr.role_id
                WHERE membership.user_id=:userId
                  AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds)
                  AND (CAST(:schoolId AS uuid) IS NULL OR school.id=:schoolId)
                  AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND rc.capability_code IN ('launch.configure','launch.approve')
                  AND (CAST(:requiredCapability AS varchar) IS NULL OR rc.capability_code=:requiredCapability)
                ORDER BY can_approve DESC,membership.created_at LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds()).addValue("schoolId", schoolId)
                .addValue("requiredCapability", requiredCapability), (result, row) -> new Scope(
                user.activeOrganisationId(), result.getObject("id", UUID.class),
                result.getString("display_name"), result.getObject("membership_id", UUID.class),
                result.getBoolean("can_configure"), result.getBoolean("can_approve")));
        if (scopes.isEmpty()) {
            throw forbidden("LAUNCH_READINESS_SCOPE_DENIED",
                    "Launch readiness is unavailable for this school.");
        }
        return scopes.getFirst();
    }

    private void lock(Scope scope) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                new MapSqlParameterSource().addValue("lockKey", scope.schoolId() + ":launch-readiness"),
                (result, row) -> Boolean.TRUE);
    }

    private void audit(
            AuthenticatedUser user,
            Scope scope,
            String action,
            String subjectType,
            UUID subjectId,
            String why,
            Map<String, ?> metadata) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action,
                subjectType, subjectId.toString(), AuditOutcome.SUCCEEDED, why, metadata);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to freeze launch readiness evidence", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static <T> T safe(String code, Work<T> work) {
        try {
            return work.run();
        } catch (DataIntegrityViolationException exception) {
            throw conflict(code, "The same launch configuration or review already exists.");
        }
    }

    private static void changed(int count) {
        if (count != 1) {
            throw conflict("STALE_VERSION", "The record changed. Refresh and try again.");
        }
    }

    private static String controlCode(String value) {
        return required(value, "Enter a launch control code.").toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", "_");
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value, "Choose a " + label + ".")
                .toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(normalized)) {
            throw bad("INVALID_VALUE", "Choose a supported " + label + ".");
        }
        return normalized;
    }

    private static String reason(String value) {
        return required(value, "Enter the reason for this launch-readiness change.");
    }

    private static String required(String value, String message) {
        String cleaned = clean(value);
        if (cleaned == null) throw bad("REQUIRED_VALUE_MISSING", message);
        return cleaned;
    }

    private static String clean(String value) {
        return value == null || value.isBlank()
                ? null : value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
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

    private record Scope(
            UUID organisationId,
            UUID schoolId,
            String schoolName,
            UUID actorMembershipId,
            boolean canConfigure,
            boolean canApprove) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId)
                    .addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record Values(
            String category,
            String title,
            String guidance,
            boolean required,
            String status,
            String evidenceReference,
            String ownerRoleLabel,
            String reviewNote) {}

    private record StoredItem(
            UUID id,
            String controlCode,
            int revisionNumber,
            boolean systemControl,
            String category,
            String title,
            String guidance,
            boolean required,
            String status,
            String evidenceReference,
            String ownerRoleLabel,
            String reviewNote,
            UUID reviserMembershipId,
            String revisedBy,
            Instant revisedAt) {
        ReadinessItem publicItem() {
            return new ReadinessItem(id, controlCode, revisionNumber, systemControl, category,
                    title, guidance, required, status, evidenceReference, ownerRoleLabel,
                    reviewNote, revisedBy, revisedAt);
        }
    }

    private record FrozenSnapshot(
            String json,
            String sha256,
            int itemCount,
            int requiredCount,
            int readyCount,
            int blockedCount,
            boolean everyRequiredReady) {}

    private record PreviousReview(UUID id, int reviewNumber, String status, String sha256) {}

    private record StoredReview(
            String status,
            String snapshotSha256,
            UUID submitterUserId,
            long rowVersion) {}

    @FunctionalInterface
    private interface Work<T> {
        T run();
    }
}

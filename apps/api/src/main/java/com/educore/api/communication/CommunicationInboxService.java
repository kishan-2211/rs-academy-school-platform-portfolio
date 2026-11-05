package com.educore.api.communication;

import com.educore.api.common.api.ApiException;
import com.educore.api.communication.CommunicationInboxResponse.DeliveryItem;
import com.educore.api.communication.CommunicationInboxResponse.InboxItem;
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

@Service
public class CommunicationInboxService {
    private static final String CURRENT_REVIEWER_NOTICE = """
                  AND (announcement.source_type IS DISTINCT FROM 'STUDENT_LEAVE_SUBMISSION' OR EXISTS (
                    SELECT 1 FROM student_leave_submission_notification_reviewers reviewer
                    WHERE reviewer.event_id=announcement.domain_event_id
                      AND reviewer.organisation_id=:organisationId AND reviewer.school_id=:schoolId
                      AND reviewer.membership_id=recipient.membership_id AND reviewer.user_account_id=:actorUserId
                      AND reviewer.occurred_at<=:now AND reviewer.expires_at>:now))
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public CommunicationInboxService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CommunicationInboxResponse staffInbox(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = staffScope(user, requestedSchoolId);
        return new CommunicationInboxResponse(scope.schoolId(), null,
                inbox(scope, null, "STAFF"));
    }

    @Transactional(readOnly = true)
    public CommunicationInboxResponse familyInbox(AuthenticatedUser user, UUID learnerId) {
        Scope scope = familyScope(user, learnerId);
        return new CommunicationInboxResponse(scope.schoolId(), learnerId,
                inbox(scope, learnerId, "GUARDIAN"));
    }

    @Transactional(readOnly = true)
    public CommunicationInboxResponse studentInbox(AuthenticatedUser user) {
        Scope scope = studentScope(user);
        return new CommunicationInboxResponse(scope.schoolId(), scope.learnerId(),
                inbox(scope, scope.learnerId(), "STUDENT"));
    }

    @Transactional
    public CommunicationMutationResponse readStaff(AuthenticatedUser user, UUID recipientId, UUID schoolId) {
        Scope scope = staffScope(user, schoolId);
        return recordInteraction(user, scope, recipientId, null, false);
    }

    @Transactional
    public CommunicationMutationResponse acknowledgeStaff(AuthenticatedUser user, UUID recipientId, UUID schoolId) {
        Scope scope = staffScope(user, schoolId);
        return recordInteraction(user, scope, recipientId, null, true);
    }

    @Transactional
    public CommunicationMutationResponse readFamily(AuthenticatedUser user, UUID learnerId, UUID recipientId) {
        Scope scope = familyScope(user, learnerId);
        return recordInteraction(user, scope, recipientId, learnerId, false);
    }

    @Transactional
    public CommunicationMutationResponse acknowledgeFamily(AuthenticatedUser user, UUID learnerId, UUID recipientId) {
        Scope scope = familyScope(user, learnerId);
        return recordInteraction(user, scope, recipientId, learnerId, true);
    }

    @Transactional
    public CommunicationMutationResponse readStudent(AuthenticatedUser user, UUID recipientId) {
        Scope scope = studentScope(user);
        return recordInteraction(user, scope, recipientId, scope.learnerId(), false);
    }

    @Transactional
    public CommunicationMutationResponse acknowledgeStudent(AuthenticatedUser user, UUID recipientId) {
        Scope scope = studentScope(user);
        return recordInteraction(user, scope, recipientId, scope.learnerId(), true);
    }

    private CommunicationMutationResponse recordInteraction(AuthenticatedUser user, Scope scope,
            UUID recipientId, UUID learnerId, boolean acknowledge) {
        Instant now = Instant.now(clock);
        MapSqlParameterSource parameters = scope.params().addValue("recipientId", recipientId)
                .addValue("learnerId", learnerId).addValue("now", now);
        List<RecipientState> rows = jdbc.query("""
                SELECT recipient.id,recipient.recipient_kind,recipient.learner_id,recipient.read_at,
                  recipient.acknowledged_at,recipient.row_version,announcement.requires_acknowledgement
                FROM communication_recipients recipient
                JOIN communication_announcements announcement
                  ON announcement.id=recipient.communication_announcement_id
                WHERE recipient.id=:recipientId AND recipient.organisation_id=:organisationId
                  AND recipient.school_id=:schoolId AND recipient.user_account_id=:actorUserId
                  AND recipient.contact_eligible AND recipient.available_at<=:now
                  AND announcement.status='APPROVED'
                """ + CURRENT_REVIEWER_NOTICE + """
                  AND ((CAST(:learnerId AS uuid) IS NULL AND recipient.recipient_kind='STAFF'
                        AND recipient.membership_id IN(:staffMembershipIds))
                    OR (CAST(:learnerId AS uuid) IS NOT NULL AND recipient.recipient_kind='STUDENT'
                        AND recipient.membership_id=:actorMembershipId
                        AND recipient.learner_id=:learnerId)
                    OR (CAST(:learnerId AS uuid) IS NOT NULL AND recipient.recipient_kind='GUARDIAN'
                        AND recipient.learner_id=:learnerId
                        AND EXISTS (SELECT 1 FROM learner_guardian_relationships relationship
                          JOIN guardians guardian ON guardian.id=relationship.guardian_id
                          WHERE relationship.learner_id=:learnerId
                            AND relationship.guardian_id=recipient.guardian_id
                            AND relationship.organisation_id=:organisationId
                            AND relationship.school_id=:schoolId
                            AND relationship.portal_access_status='ACTIVE'
                            AND guardian.user_account_id=:actorUserId AND guardian.status='ACTIVE')))
                FOR UPDATE
                """, parameters, (result, row) -> new RecipientState(
                result.getObject("id", UUID.class), result.getString("recipient_kind"),
                result.getObject("learner_id", UUID.class), timestamp(result, "read_at"),
                timestamp(result, "acknowledged_at"), result.getLong("row_version"),
                result.getBoolean("requires_acknowledgement")));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "COMMUNICATION_RECIPIENT_NOT_FOUND",
                "This announcement is unavailable to the verified inbox.");
        RecipientState state = rows.getFirst();
        if (acknowledge && !state.requiresAcknowledgement())
            throw new ApiException(HttpStatus.CONFLICT, "COMMUNICATION_ACK_NOT_REQUIRED",
                    "This announcement does not request an acknowledgement.");
        boolean addRead = state.readAt() == null;
        boolean addAcknowledgement = acknowledge && state.acknowledgedAt() == null;
        if (addRead || addAcknowledgement) {
            jdbc.update("""
                    UPDATE communication_recipients
                    SET read_at=COALESCE(read_at,:now),
                      acknowledged_at=CASE WHEN :acknowledge THEN COALESCE(acknowledged_at,:now)
                        ELSE acknowledged_at END,
                      row_version=row_version+1
                    WHERE id=:recipientId AND organisation_id=:organisationId AND school_id=:schoolId
                      AND row_version=:version
                    """, parameters.addValue("now", now).addValue("acknowledge", acknowledge)
                    .addValue("version", state.version()));
            if (addRead) event(scope, recipientId, "READ", now);
            if (addAcknowledgement) event(scope, recipientId, "ACKNOWLEDGED", now);
            jdbc.update("""
                    UPDATE communication_deliveries SET status='DELIVERED',delivered_at=COALESCE(delivered_at,:now),
                      updated_at=:now,row_version=row_version+1
                    WHERE communication_recipient_id=:recipientId AND organisation_id=:organisationId
                      AND school_id=:schoolId AND channel='IN_APP' AND status='READY'
                    """, parameters);
        }
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(),
                acknowledge ? "communication.inbox.acknowledged" : "communication.inbox.read",
                "COMMUNICATION_RECIPIENT", recipientId.toString(), AuditOutcome.SUCCEEDED, null,
                Map.of("recipientKind", state.kind()));
        long version = state.version() + ((addRead || addAcknowledgement) ? 1 : 0);
        return new CommunicationMutationResponse(recipientId, version, 0);
    }

    private List<InboxItem> inbox(Scope scope, UUID learnerId, String kind) {
        MapSqlParameterSource parameters = scope.params().addValue("learnerId", learnerId).addValue("kind", kind)
                .addValue("now",Instant.now(clock));
        return jdbc.query("""
                SELECT recipient.*,announcement.category,announcement.priority,announcement.audience_kind,
                  announcement.language_mode,announcement.title_en,announcement.title_hi,
                  announcement.body_en,announcement.body_hi,announcement.requires_acknowledgement,
                  announcement.scheduled_for,announcement.expires_at
                FROM communication_recipients recipient
                JOIN communication_announcements announcement
                  ON announcement.id=recipient.communication_announcement_id
                WHERE recipient.organisation_id=:organisationId AND recipient.school_id=:schoolId
                  AND recipient.user_account_id=:actorUserId AND recipient.recipient_kind=:kind
                  AND recipient.contact_eligible AND recipient.available_at<=:now
                  AND announcement.status='APPROVED'
                """ + CURRENT_REVIEWER_NOTICE + """
                  AND ((:kind='STAFF' AND recipient.membership_id IN(:staffMembershipIds))
                    OR (:kind='STUDENT' AND recipient.membership_id=:actorMembershipId
                      AND recipient.learner_id=:learnerId)
                    OR (:kind='GUARDIAN' AND recipient.learner_id=:learnerId
                      AND EXISTS (SELECT 1 FROM learner_guardian_relationships relationship
                        JOIN guardians guardian ON guardian.id=relationship.guardian_id
                        WHERE relationship.learner_id=:learnerId
                          AND relationship.guardian_id=recipient.guardian_id
                          AND relationship.organisation_id=:organisationId
                          AND relationship.school_id=:schoolId
                          AND relationship.portal_access_status='ACTIVE'
                          AND guardian.user_account_id=:actorUserId AND guardian.status='ACTIVE')))
                ORDER BY announcement.scheduled_for DESC,announcement.id DESC
                """, parameters, (result, row) -> {
            UUID recipientId = result.getObject("id", UUID.class);
            List<DeliveryItem> deliveries = jdbc.query("""
                    SELECT channel,status FROM communication_deliveries
                    WHERE communication_recipient_id=:recipientId AND organisation_id=:organisationId
                      AND school_id=:schoolId ORDER BY channel
                    """, scope.params().addValue("recipientId", recipientId),
                    (deliveryResult, deliveryRow) -> new DeliveryItem(
                            deliveryResult.getString("channel"), deliveryResult.getString("status")));
            Instant expiresAt = timestamp(result, "expires_at");
            return new InboxItem(recipientId, result.getObject("communication_announcement_id", UUID.class),
                    result.getObject("learner_id", UUID.class), result.getString("category"),
                    result.getString("priority"), result.getString("audience_kind"),
                    result.getString("language_mode"), result.getString("preferred_language"),
                    result.getString("title_en"), result.getString("title_hi"), result.getString("body_en"),
                    result.getString("body_hi"), result.getBoolean("requires_acknowledgement"),
                    timestamp(result, "scheduled_for"), expiresAt,
                    expiresAt != null && !expiresAt.isAfter(Instant.now(clock)), timestamp(result, "read_at"),
                    timestamp(result, "acknowledged_at"), deliveries, result.getLong("row_version"));
        });
    }

    private Scope staffScope(AuthenticatedUser user, UUID schoolId) {
        if (user.activeOrganisationId() == null) throw new ApiException(HttpStatus.CONFLICT,
                "ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        if (user.membershipIds().isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN,
                "COMMUNICATION_SCOPE_DENIED", "The staff communication inbox is unavailable for this school.");
        List<Scope> rows = jdbc.query("""
                SELECT membership.school_id,membership.id AS membership_id
                FROM memberships membership
                JOIN user_accounts account ON account.id=membership.user_id AND account.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=membership.organisation_id
                  AND organisation.status='ACTIVE'
                JOIN schools school ON school.id=membership.school_id
                  AND school.organisation_id=membership.organisation_id AND school.status='ACTIVE'
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN roles role ON role.id=mr.role_id
                  AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                JOIN role_capabilities rc ON rc.role_id=role.id
                WHERE membership.user_id=:actorUserId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds) AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND rc.capability_code='communication.read'
                ORDER BY membership.created_at,membership.id
                """, base(user).addValue("schoolId", schoolId), (result, row) -> new Scope(
                user.activeOrganisationId(), result.getObject("school_id", UUID.class),
                result.getObject("membership_id", UUID.class), user.userId()));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN, "COMMUNICATION_SCOPE_DENIED",
                "The staff communication inbox is unavailable for this school.");
        Scope selected=rows.getFirst();
        return selected.withStaffMemberships(rows.stream().filter(row -> row.schoolId().equals(selected.schoolId()))
                .map(Scope::actorMembershipId).distinct().toList());
    }

    private Scope familyScope(AuthenticatedUser user, UUID learnerId) {
        if (user.activeOrganisationId() == null) throw new ApiException(HttpStatus.CONFLICT,
                "ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        if (user.membershipIds().isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,
                "FAMILY_CHILD_NOT_FOUND", "This learner is not available to your verified relationship.");
        List<Scope> rows = jdbc.query("""
                SELECT guardian.school_id,membership.id AS membership_id
                FROM guardians guardian
                JOIN user_accounts account ON account.id=guardian.user_account_id AND account.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=guardian.organisation_id
                  AND organisation.status='ACTIVE'
                JOIN schools school ON school.id=guardian.school_id
                  AND school.organisation_id=guardian.organisation_id AND school.status='ACTIVE'
                JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
                 AND relationship.organisation_id=guardian.organisation_id
                 AND relationship.school_id=guardian.school_id
                JOIN memberships membership ON membership.user_id=guardian.user_account_id
                 AND membership.organisation_id=guardian.organisation_id
                 AND membership.school_id=guardian.school_id
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN roles role ON role.id=mr.role_id
                  AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                JOIN role_capabilities rc ON rc.role_id=role.id
                WHERE guardian.user_account_id=:actorUserId AND guardian.organisation_id=:organisationId
                  AND guardian.status='ACTIVE' AND relationship.learner_id=:learnerId
                  AND relationship.portal_access_status='ACTIVE'
                  AND membership.id IN(:membershipIds) AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND rc.capability_code='portal.guardian'
                ORDER BY membership.created_at LIMIT 1
                """, base(user).addValue("learnerId", learnerId), (result, row) -> new Scope(
                user.activeOrganisationId(), result.getObject("school_id", UUID.class),
                result.getObject("membership_id", UUID.class), user.userId()));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "FAMILY_CHILD_NOT_FOUND",
                "This learner is not available to your verified relationship.");
        return rows.getFirst();
    }

    private Scope studentScope(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null || user.membershipIds().isEmpty())
            throw new ApiException(HttpStatus.CONFLICT, "ORGANISATION_SELECTION_REQUIRED",
                    "Select a school organisation first.");
        List<Scope> rows = jdbc.query("""
                SELECT portal.school_id,portal.learner_id,membership.id AS membership_id
                FROM learner_portal_accounts portal
                JOIN user_accounts account ON account.id=portal.user_account_id AND account.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=portal.organisation_id
                  AND organisation.status='ACTIVE'
                JOIN schools school ON school.id=portal.school_id
                  AND school.organisation_id=portal.organisation_id AND school.status='ACTIVE'
                JOIN student_portal_consent_requests consent
                  ON consent.id=portal.current_consent_request_id
                 AND consent.organisation_id=portal.organisation_id AND consent.school_id=portal.school_id
                 AND consent.status='APPROVED' AND consent.approved_user_account_id=portal.user_account_id
                JOIN learners learner ON learner.id=portal.learner_id
                 AND learner.organisation_id=portal.organisation_id AND learner.school_id=portal.school_id
                 AND learner.status='ACTIVE'
                JOIN memberships membership ON membership.user_id=portal.user_account_id
                 AND membership.organisation_id=portal.organisation_id AND membership.school_id=portal.school_id
                 AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                 AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                WHERE portal.user_account_id=:actorUserId AND portal.organisation_id=:organisationId
                  AND portal.status='ACTIVE' AND membership.id IN(:membershipIds)
                  AND EXISTS (SELECT 1 FROM membership_roles mr
                    JOIN roles role ON role.id=mr.role_id
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                    JOIN role_capabilities rc ON rc.role_id=role.id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='portal.student')
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, base(user), (result, row) -> new Scope(
                user.activeOrganisationId(), result.getObject("school_id", UUID.class),
                result.getObject("membership_id", UUID.class), user.userId(),
                result.getObject("learner_id", UUID.class)));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "STUDENT_PORTAL_UNAVAILABLE",
                "The student inbox is unavailable for this account.");
        return rows.getFirst();
    }

    private void event(Scope scope, UUID recipientId, String type, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO communication_recipient_events(id,organisation_id,school_id,
                      communication_recipient_id,event_type,actor_user_id,occurred_at)
                    VALUES(:id,:organisationId,:schoolId,:recipientId,:type,:actorUserId,:now)
                    """, scope.params().addValue("id", UUID.randomUUID()).addValue("recipientId", recipientId)
                    .addValue("type", type).addValue("now", now));
        } catch (DataIntegrityViolationException ignored) {
            // The unique event key makes retried read/ack requests idempotent.
        }
    }

    private static MapSqlParameterSource base(AuthenticatedUser user) {
        return new MapSqlParameterSource().addValue("actorUserId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds());
    }

    private static Instant timestamp(java.sql.ResultSet result, String name) throws java.sql.SQLException {
        var value = result.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private record Scope(
            UUID organisationId, UUID schoolId, UUID actorMembershipId,
            UUID actorUserId, UUID learnerId, List<UUID> staffMembershipIds) {
        Scope(UUID organisationId, UUID schoolId, UUID actorMembershipId, UUID actorUserId) {
            this(organisationId, schoolId, actorMembershipId, actorUserId, null, List.of(actorMembershipId));
        }
        Scope(UUID organisationId, UUID schoolId, UUID actorMembershipId, UUID actorUserId, UUID learnerId) {
            this(organisationId, schoolId, actorMembershipId, actorUserId, learnerId, List.of(actorMembershipId));
        }
        Scope withStaffMemberships(List<UUID> memberships) {
            return new Scope(organisationId, schoolId, actorMembershipId, actorUserId, learnerId, memberships);
        }
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId)
                    .addValue("actorUserId", actorUserId).addValue("staffMembershipIds", staffMembershipIds);
        }
    }
    private record RecipientState(UUID id, String kind, UUID learnerId, Instant readAt,
            Instant acknowledgedAt, long version, boolean requiresAcknowledgement) {}
}

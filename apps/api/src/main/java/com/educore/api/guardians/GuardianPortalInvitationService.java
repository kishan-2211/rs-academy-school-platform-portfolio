package com.educore.api.guardians;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.InvitationTokenProtector;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.identity.SessionTokenCodec;
import com.educore.api.identity.UserAccount;
import com.educore.api.identity.UserAccountRepository;
import com.educore.api.identity.UserStatus;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class GuardianPortalInvitationService {

    private static final String INVITE = "guardian.portal.invite";
    private static final String GUARDIAN_MANAGE = "guardian.manage";
    private static final String PORTAL = "portal.guardian";
    private static final List<String> STAFF_CAPABILITIES = List.of(INVITE, GUARDIAN_MANAGE);
    private static final UUID PARENT_ROLE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000006");
    private static final String INVALID_INVITATION =
            "This invitation is invalid, expired, or no longer available.";

    private final NamedParameterJdbcTemplate jdbc;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokenCodec tokens;
    private final InvitationTokenProtector tokenProtector;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final ApplicationProperties properties;
    private final Clock clock;

    public GuardianPortalInvitationService(
            NamedParameterJdbcTemplate jdbc,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            SessionTokenCodec tokens,
            InvitationTokenProtector tokenProtector,
            SecurityAuditService audit,
            ObjectMapper objectMapper,
            ApplicationProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.tokenProtector = tokenProtector;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Internal housekeeping only; this is not an operator or public-token action. */
    @Transactional
    public UUID expireNext(UUID organisationId) {
        var context = TenantRequestContext.current().orElse(null);
        if (organisationId == null || context == null || context.userId() != null
                || !organisationId.equals(context.organisationId())) {
            throw new IllegalArgumentException("Invitation expiry requires the matching system tenant context");
        }
        Instant now = Instant.now(clock);
        var parameters = new MapSqlParameterSource("organisationId", organisationId).addValue("now", now);
        // Keep the existing relationship/guardian -> invitation lock order. Busy relationships
        // are left for another poll rather than blocking acceptance, reissue or revocation.
        List<ExpiryCandidate> candidates = jdbc.query("""
                SELECT invitation.id, invitation.school_id
                FROM guardian_portal_invitations invitation
                JOIN organisations organisation ON organisation.id = invitation.organisation_id
                JOIN learner_guardian_relationships relationship
                  ON relationship.learner_id = invitation.learner_id
                 AND relationship.guardian_id = invitation.guardian_id
                 AND relationship.organisation_id = invitation.organisation_id
                 AND relationship.school_id = invitation.school_id
                JOIN guardians guardian ON guardian.id = invitation.guardian_id
                  AND guardian.organisation_id = invitation.organisation_id
                  AND guardian.school_id = invitation.school_id
                WHERE invitation.organisation_id = :organisationId
                  AND organisation.status = 'ACTIVE'
                  AND invitation.status = 'ISSUED' AND invitation.expires_at <= :now
                ORDER BY invitation.expires_at, invitation.id
                FOR UPDATE OF relationship, guardian SKIP LOCKED LIMIT 1
                """, parameters, (result, row) -> new ExpiryCandidate(
                result.getObject("id", UUID.class), result.getObject("school_id", UUID.class)));
        if (candidates.isEmpty()) return null;
        List<TokenInvitation> invitations = jdbc.query("""
                SELECT id, organisation_id, school_id, learner_id, guardian_id,
                       target_email_normalized, expires_at, acceptance_attempt_count, row_version
                FROM guardian_portal_invitations
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'ISSUED' AND expires_at <= :now
                FOR UPDATE SKIP LOCKED
                """, parameters.addValue("id", candidates.getFirst().id())
                .addValue("schoolId", candidates.getFirst().schoolId()),
                GuardianPortalInvitationService::mapTokenInvitation);
        if (invitations.isEmpty()) return null;
        TokenInvitation invitation = invitations.getFirst();
        return expireInvitation(invitation, now) ? invitation.id() : null;
    }

    @Transactional(readOnly = true)
    public GuardianPortalInvitationPageResponse list(
            AuthenticatedUser user,
            UUID learnerId,
            UUID guardianId) {
        RelationshipScope scope = authorizeRelationship(user, learnerId, guardianId);
        return new GuardianPortalInvitationPageResponse(jdbc.query("""
                SELECT id, learner_id, guardian_id, target_email_normalized, status,
                       delivery_status, expires_at, accepted_at, revoked_at, created_at, row_version
                FROM guardian_portal_invitations
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND learner_id = :learnerId AND guardian_id = :guardianId
                ORDER BY created_at DESC, id DESC
                LIMIT 20
                """, scopeParameters(scope), GuardianPortalInvitationService::mapResponse));
    }

    @Transactional
    public GuardianPortalInvitationResponse issue(
            AuthenticatedUser user,
            UUID learnerId,
            UUID guardianId,
            IssueGuardianPortalInvitationRequest request) {
        Instant now = Instant.now(clock);
        RelationshipScope authorized = authorizeRelationship(user, learnerId, guardianId);
        RelationshipScope scope = lockRelationship(authorized);
        if (scope.relationshipVersion() != request.expectedRelationshipVersion()) {
            throw stale("The guardian relationship changed before the invitation was prepared.");
        }
        if (!"ACTIVE".equals(scope.guardianStatus())) {
            throw conflict("GUARDIAN_NOT_ACTIVE", "Portal access cannot be issued for an inactive guardian.");
        }
        if (Set.of("DENIED", "SUSPENDED").contains(scope.portalAccessStatus())) {
            throw conflict(
                    "GUARDIAN_PORTAL_ACCESS_BLOCKED",
                    "This relationship is blocked. Resolve its access restriction before issuing an invitation.");
        }
        if ("ACTIVE".equals(scope.portalAccessStatus())) {
            throw conflict("GUARDIAN_PORTAL_ALREADY_ACTIVE", "This learner relationship already has active portal access.");
        }
        String email = normalizeEmail(scope.email());
        if (email == null) {
            throw badRequest(
                    "GUARDIAN_EMAIL_REQUIRED",
                    "Add a valid guardian email address before preparing portal access.");
        }

        jdbc.update("""
                UPDATE guardian_portal_invitations
                SET status = 'REVOKED', delivery_status = CASE
                        WHEN delivery_status IN ('SENT', 'DELIVERED') THEN delivery_status
                        ELSE 'CANCELLED'
                    END,
                    token_ciphertext = NULL, token_nonce = NULL,
                    revoked_at = :now, revoked_by_membership_id = :actorMembershipId,
                    revoked_reason = :reason, updated_at = :now, row_version = row_version + 1
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND learner_id = :learnerId AND guardian_id = :guardianId
                  AND status = 'ISSUED'
                """, scopeParameters(scope)
                .addValue("now", now)
                .addValue("reason", "Superseded: " + cleanReason(request.reason(), 228)));

        UUID invitationId = UUID.randomUUID();
        String rawToken = tokens.newRawToken();
        InvitationTokenProtector.ProtectedToken protectedToken =
                tokenProtector.protect(rawToken, invitationId.toString());
        Instant expiresAt = now.plus(properties.security().guardianInvitationTtl());
        jdbc.update("""
                INSERT INTO guardian_portal_invitations (
                    id, organisation_id, school_id, learner_id, guardian_id,
                    target_email_normalized, token_hash, token_ciphertext, token_nonce,
                    status, delivery_status, expires_at, acceptance_attempt_count,
                    created_by_membership_id, created_at, updated_at, row_version
                ) VALUES (
                    :id, :organisationId, :schoolId, :learnerId, :guardianId,
                    :email, :tokenHash, :tokenCiphertext, :tokenNonce,
                    'ISSUED', 'AWAITING_PROVIDER', :expiresAt, 0,
                    :actorMembershipId, :now, :now, 0
                )
                """, scopeParameters(scope)
                .addValue("id", invitationId)
                .addValue("email", email)
                .addValue("tokenHash", tokens.hash(rawToken))
                .addValue("tokenCiphertext", protectedToken.ciphertext())
                .addValue("tokenNonce", protectedToken.nonce())
                .addValue("expiresAt", expiresAt)
                .addValue("now", now));
        int relationshipUpdated = jdbc.update("""
                UPDATE learner_guardian_relationships
                SET portal_access_status = 'INVITED', updated_at = :now, row_version = row_version + 1
                WHERE learner_id = :learnerId AND guardian_id = :guardianId
                  AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                  AND portal_access_status IN ('NOT_INVITED', 'INVITED')
                """, scopeParameters(scope)
                .addValue("now", now)
                .addValue("expectedVersion", request.expectedRelationshipVersion()));
        if (relationshipUpdated != 1) {
            throw stale("The guardian relationship changed before the invitation was prepared.");
        }
        appendOutbox(
                invitationId,
                "guardian.portal.invitation.requested",
                Map.of(
                        "invitationId", invitationId,
                        "organisationId", scope.organisationId(),
                        "schoolId", scope.schoolId(),
                        "channel", "EMAIL",
                        "template", "guardian-portal-invitation"),
                now);
        audit.recordScoped(
                user,
                scope.actorMembershipId(),
                scope.schoolId(),
                "guardian.portal.invitation.issued",
                "GUARDIAN_PORTAL_INVITATION",
                invitationId.toString(),
                AuditOutcome.SUCCEEDED,
                cleanReason(request.reason(), 240),
                Map.of("learnerId", learnerId, "guardianId", guardianId, "deliveryStatus", "AWAITING_PROVIDER"));
        return findResponse(scope.organisationId(), scope.schoolId(), invitationId);
    }

    @Transactional
    public GuardianPortalInvitationResponse revoke(
            AuthenticatedUser user,
            UUID invitationId,
            RevokeGuardianPortalInvitationRequest request) {
        StoredInvitation authorized = findAccessible(user, invitationId, false);
        lockStoredRelationship(authorized);
        StoredInvitation invitation = findAccessible(user, invitationId, true);
        if (invitation.version() != request.expectedVersion()) {
            throw stale("The invitation changed before it was revoked.");
        }
        if (!"ISSUED".equals(invitation.status())) {
            throw conflict("GUARDIAN_INVITATION_NOT_OPEN", "Only an open invitation can be revoked.");
        }
        Instant now = Instant.now(clock);
        jdbc.update("""
                UPDATE guardian_portal_invitations
                SET status = 'REVOKED', delivery_status = CASE
                        WHEN delivery_status IN ('SENT', 'DELIVERED') THEN delivery_status
                        ELSE 'CANCELLED'
                    END,
                    token_ciphertext = NULL, token_nonce = NULL,
                    revoked_at = :now, revoked_by_membership_id = :actorMembershipId,
                    revoked_reason = :reason, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'ISSUED' AND row_version = :expectedVersion
                """, invitationParameters(invitation)
                .addValue("now", now)
                .addValue("reason", cleanReason(request.reason(), 240))
                .addValue("expectedVersion", request.expectedVersion()));
        jdbc.update("""
                UPDATE learner_guardian_relationships relationship
                SET portal_access_status = 'NOT_INVITED', updated_at = :now,
                    row_version = row_version + 1
                WHERE relationship.learner_id = :learnerId
                  AND relationship.guardian_id = :guardianId
                  AND relationship.organisation_id = :organisationId
                  AND relationship.school_id = :schoolId
                  AND relationship.portal_access_status = 'INVITED'
                  AND NOT EXISTS (
                    SELECT 1 FROM guardian_portal_invitations open_invitation
                    WHERE open_invitation.learner_id = relationship.learner_id
                      AND open_invitation.guardian_id = relationship.guardian_id
                      AND open_invitation.status = 'ISSUED'
                  )
                """, invitationParameters(invitation).addValue("now", now));
        appendOutbox(
                invitationId,
                "guardian.portal.invitation.revoked",
                Map.of("invitationId", invitationId, "schoolId", invitation.schoolId()),
                now);
        audit.recordScoped(
                user,
                invitation.actorMembershipId(),
                invitation.schoolId(),
                "guardian.portal.invitation.revoked",
                "GUARDIAN_PORTAL_INVITATION",
                invitationId.toString(),
                AuditOutcome.SUCCEEDED,
                cleanReason(request.reason(), 240),
                Map.of("learnerId", invitation.learnerId(), "guardianId", invitation.guardianId()));
        return findResponse(invitation.organisationId(), invitation.schoolId(), invitationId);
    }

    @Transactional(noRollbackFor = InvitationExpiredException.class)
    public GuardianInvitationPreviewResponse preview(GuardianInvitationTokenRequest request) {
        String tokenHash = bindToken(request.token());
        TokenInvitation token = findByToken(tokenHash, false);
        if (token == null) throw invalidInvitation();
        bindOrganisation(token.organisationId());
        if (!token.expiresAt().isAfter(Instant.now(clock)) || token.attemptCount() >= 10) {
            lockAcceptanceRelationship(token);
            TokenInvitation locked = findByToken(tokenHash, true);
            if (locked == null || !locked.id().equals(token.id())) throw invalidInvitation();
            expireInvitation(locked, Instant.now(clock));
            throw new InvitationExpiredException();
        }
        PreviewData preview = jdbc.queryForObject("""
                SELECT school.display_name AS school_name,
                       invitation.target_email_normalized,
                       invitation.expires_at,
                       EXISTS (
                         SELECT 1 FROM user_accounts account
                         WHERE account.email_normalized = invitation.target_email_normalized
                       ) AS existing_account
                FROM guardian_portal_invitations invitation
                JOIN schools school ON school.id = invitation.school_id
                  AND school.organisation_id = invitation.organisation_id
                WHERE invitation.id = :id AND invitation.organisation_id = :organisationId
                  AND invitation.school_id = :schoolId
                """, tokenParameters(token), (result, row) -> new PreviewData(
                result.getString("school_name"),
                result.getString("target_email_normalized"),
                result.getTimestamp("expires_at").toInstant(),
                result.getBoolean("existing_account")));
        if (preview == null) throw invalidInvitation();
        return new GuardianInvitationPreviewResponse(
                preview.schoolName(),
                maskEmail(preview.email()),
                preview.expiresAt(),
                preview.existingAccount(),
                "ready");
    }

    @Transactional(noRollbackFor = {AcceptanceDeniedException.class, InvitationExpiredException.class})
    public GuardianInvitationAcceptanceResponse accept(AcceptGuardianPortalInvitationRequest request) {
        Instant now = Instant.now(clock);
        String tokenHash = bindToken(request.token());
        TokenInvitation discovered = findByToken(tokenHash, false);
        if (discovered == null) throw invalidInvitation();
        bindOrganisation(discovered.organisationId());
        AcceptanceRelationship relationship = lockAcceptanceRelationship(discovered);
        TokenInvitation invitation = findByToken(tokenHash, true);
        if (invitation == null || !invitation.id().equals(discovered.id())) throw invalidInvitation();
        if (!invitation.expiresAt().isAfter(now) || invitation.attemptCount() >= 10) {
            expireInvitation(invitation, now);
            throw new InvitationExpiredException();
        }
        if (!"INVITED".equals(relationship.portalAccessStatus())
                || !"ACTIVE".equals(relationship.guardianStatus())
                || !invitation.email().equals(relationship.guardianEmail())) {
            throw conflict(
                    "GUARDIAN_INVITATION_CONTEXT_CHANGED",
                    "The guardian relationship or delivery address changed. Ask the school to issue a new invitation.");
        }

        UserAccount account = users.findByEmailNormalized(invitation.email()).orElse(null);
        if (account != null) {
            if (account.getStatus() != UserStatus.ACTIVE
                    || !passwordEncoder.matches(request.password(), account.getPasswordHash())) {
                recordDeniedAcceptance(invitation, now);
                throw new AcceptanceDeniedException();
            }
        } else {
            account = users.saveAndFlush(UserAccount.active(
                    invitation.email(),
                    relationship.guardianName(),
                    passwordEncoder.encode(request.password())));
        }
        if (relationship.linkedUserId() != null && !relationship.linkedUserId().equals(account.getId())) {
            throw conflict(
                    "GUARDIAN_ACCOUNT_LINK_CONFLICT",
                    "This guardian is linked to a different verified account. Ask the school to review the record.");
        }

        try {
            int guardianUpdated = jdbc.update("""
                    UPDATE guardians
                    SET user_account_id = :userId, updated_at = :now, row_version = row_version + 1
                    WHERE id = :guardianId AND organisation_id = :organisationId
                      AND school_id = :schoolId
                      AND (user_account_id IS NULL OR user_account_id = :userId)
                    """, tokenParameters(invitation)
                    .addValue("userId", account.getId())
                    .addValue("now", now));
            if (guardianUpdated != 1) {
                throw conflict(
                        "GUARDIAN_ACCOUNT_LINK_CONFLICT",
                        "This guardian is linked to a different verified account. Ask the school to review the record.");
            }
            UUID membershipId = ensureParentMembership(account.getId(), invitation, now);
            int relationshipUpdated = jdbc.update("""
                    UPDATE learner_guardian_relationships
                    SET portal_access_status = 'ACTIVE', updated_at = :now,
                        row_version = row_version + 1
                    WHERE learner_id = :learnerId AND guardian_id = :guardianId
                      AND organisation_id = :organisationId AND school_id = :schoolId
                      AND portal_access_status = 'INVITED'
                    """, tokenParameters(invitation).addValue("now", now));
            if (relationshipUpdated != 1) {
                throw conflict(
                        "GUARDIAN_INVITATION_CONTEXT_CHANGED",
                        "The guardian relationship changed. Ask the school to issue a new invitation.");
            }
            int accepted = jdbc.update("""
                    UPDATE guardian_portal_invitations
                    SET status = 'ACCEPTED', token_ciphertext = NULL, token_nonce = NULL,
                        accepted_at = :now, accepted_user_id = :userId,
                        updated_at = :now, row_version = row_version + 1
                    WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                      AND status = 'ISSUED' AND row_version = :version
                    """, tokenParameters(invitation)
                    .addValue("userId", account.getId())
                    .addValue("now", now)
                    .addValue("version", invitation.version()));
            if (accepted != 1) throw stale("The invitation changed before it was accepted.");

            appendOutbox(
                    invitation.id(),
                    "guardian.portal.invitation.accepted",
                    Map.of(
                            "invitationId", invitation.id(),
                            "schoolId", invitation.schoolId(),
                            "userId", account.getId()),
                    now);
            AuthenticatedUser guardianActor = new AuthenticatedUser(
                    account.getId(),
                    null,
                    account.getLoginIdentifierNormalized(),
                    account.getEmailNormalized(),
                    account.getDisplayName(),
                    false,
                    invitation.organisationId(),
                    Set.of(membershipId),
                    Set.of(PORTAL),
                    List.of());
            audit.recordScoped(
                    guardianActor,
                    membershipId,
                    invitation.schoolId(),
                    "guardian.portal.invitation.accepted",
                    "GUARDIAN_PORTAL_INVITATION",
                    invitation.id().toString(),
                    AuditOutcome.SUCCEEDED,
                    null,
                    Map.of("learnerId", invitation.learnerId(), "guardianId", invitation.guardianId()));
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "GUARDIAN_ACCOUNT_LINK_CONFLICT",
                    "The account or guardian link now conflicts with another acceptance. Ask the school to review it.");
        }
        return new GuardianInvitationAcceptanceResponse("accepted", "sign_in");
    }

    private RelationshipScope authorizeRelationship(AuthenticatedUser user, UUID learnerId, UUID guardianId) {
        List<RelationshipScope> scopes = jdbc.query("""
                SELECT relationship.organisation_id, relationship.school_id,
                       relationship.learner_id, relationship.guardian_id,
                       relationship.portal_access_status, relationship.row_version,
                       guardian.email_normalized, guardian.status AS guardian_status,
                       guardian.user_account_id, membership.id AS actor_membership_id
                FROM learner_guardian_relationships relationship
                JOIN guardians guardian ON guardian.id = relationship.guardian_id
                  AND guardian.organisation_id = relationship.organisation_id
                  AND guardian.school_id = relationship.school_id
                JOIN memberships membership ON membership.user_id = :actorUserId
                  AND membership.organisation_id = relationship.organisation_id
                  AND membership.school_id = relationship.school_id
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= :now
                  AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN roles role ON role.id = membership_role.role_id
                JOIN role_capabilities capability ON capability.role_id = role.id
                WHERE relationship.learner_id = :learnerId
                  AND relationship.guardian_id = :guardianId
                  AND relationship.organisation_id = :organisationId
                  AND capability.capability_code IN (:requiredCapabilities)
                  AND (role.organisation_id IS NULL OR role.organisation_id = relationship.organisation_id)
                GROUP BY relationship.learner_id, relationship.guardian_id,
                         guardian.id, membership.id
                HAVING count(DISTINCT capability.capability_code) = :capabilityCount
                ORDER BY membership.created_at, membership.id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", requireOrganisation(user))
                .addValue("learnerId", learnerId)
                .addValue("guardianId", guardianId)
                .addValue("requiredCapabilities", STAFF_CAPABILITIES)
                .addValue("capabilityCount", STAFF_CAPABILITIES.size())
                .addValue("now", Instant.now(clock)), GuardianPortalInvitationService::mapScope);
        if (scopes.isEmpty()) throw notFound();
        return scopes.getFirst();
    }

    private RelationshipScope lockRelationship(RelationshipScope scope) {
        List<RelationshipScope> rows = jdbc.query("""
                SELECT relationship.organisation_id, relationship.school_id,
                       relationship.learner_id, relationship.guardian_id,
                       relationship.portal_access_status, relationship.row_version,
                       guardian.email_normalized, guardian.status AS guardian_status,
                       guardian.user_account_id, CAST(:actorMembershipId AS uuid) AS actor_membership_id
                FROM learner_guardian_relationships relationship
                JOIN guardians guardian ON guardian.id = relationship.guardian_id
                  AND guardian.organisation_id = relationship.organisation_id
                  AND guardian.school_id = relationship.school_id
                WHERE relationship.learner_id = :learnerId
                  AND relationship.guardian_id = :guardianId
                  AND relationship.organisation_id = :organisationId
                  AND relationship.school_id = :schoolId
                FOR UPDATE OF relationship, guardian
                """, scopeParameters(scope), GuardianPortalInvitationService::mapScope);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private StoredInvitation findAccessible(AuthenticatedUser user, UUID invitationId, boolean lock) {
        List<StoredInvitation> invitations = jdbc.query("""
                SELECT invitation.id, invitation.organisation_id, invitation.school_id,
                       invitation.learner_id, invitation.guardian_id, invitation.status,
                       invitation.row_version, membership.id AS actor_membership_id
                FROM guardian_portal_invitations invitation
                JOIN memberships membership ON membership.user_id = :actorUserId
                  AND membership.organisation_id = invitation.organisation_id
                  AND membership.school_id = invitation.school_id
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= :now
                  AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN roles role ON role.id = membership_role.role_id
                JOIN role_capabilities capability ON capability.role_id = role.id
                WHERE invitation.id = :id AND invitation.organisation_id = :organisationId
                  AND capability.capability_code IN (:requiredCapabilities)
                  AND (role.organisation_id IS NULL OR role.organisation_id = invitation.organisation_id)
                GROUP BY invitation.id, membership.id
                HAVING count(DISTINCT capability.capability_code) = :capabilityCount
                ORDER BY membership.created_at, membership.id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("id", invitationId)
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", requireOrganisation(user))
                .addValue("requiredCapabilities", STAFF_CAPABILITIES)
                .addValue("capabilityCount", STAFF_CAPABILITIES.size())
                .addValue("now", Instant.now(clock)), GuardianPortalInvitationService::mapStoredInvitation);
        if (invitations.isEmpty()) throw invitationNotFound();
        StoredInvitation invitation = invitations.getFirst();
        if (!lock) return invitation;
        List<StoredInvitation> locked = jdbc.query("""
                SELECT id, organisation_id, school_id, learner_id, guardian_id,
                       status, row_version, CAST(:actorMembershipId AS uuid) AS actor_membership_id
                FROM guardian_portal_invitations
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                FOR UPDATE
                """, invitationParameters(invitation), GuardianPortalInvitationService::mapStoredInvitation);
        if (locked.isEmpty()) throw invitationNotFound();
        return locked.getFirst();
    }

    private void lockStoredRelationship(StoredInvitation invitation) {
        List<Integer> rows = jdbc.query("""
                SELECT 1
                FROM learner_guardian_relationships relationship
                WHERE relationship.learner_id = :learnerId
                  AND relationship.guardian_id = :guardianId
                  AND relationship.organisation_id = :organisationId
                  AND relationship.school_id = :schoolId
                FOR UPDATE OF relationship
                """, invitationParameters(invitation), (result, row) -> result.getInt(1));
        if (rows.isEmpty()) throw invitationNotFound();
    }

    private TokenInvitation findByToken(String tokenHash, boolean lock) {
        String sql = """
                SELECT id, organisation_id, school_id, learner_id, guardian_id,
                       target_email_normalized, expires_at, acceptance_attempt_count, row_version
                FROM guardian_portal_invitations
                WHERE token_hash = :tokenHash AND status = 'ISSUED'
                """ + (lock ? " FOR UPDATE" : "");
        List<TokenInvitation> invitations = jdbc.query(
                sql,
                new MapSqlParameterSource("tokenHash", tokenHash),
                GuardianPortalInvitationService::mapTokenInvitation);
        return invitations.isEmpty() ? null : invitations.getFirst();
    }

    private static TokenInvitation mapTokenInvitation(java.sql.ResultSet result, int row)
            throws java.sql.SQLException {
        return new TokenInvitation(
                result.getObject("id", UUID.class), result.getObject("organisation_id", UUID.class),
                result.getObject("school_id", UUID.class), result.getObject("learner_id", UUID.class),
                result.getObject("guardian_id", UUID.class), result.getString("target_email_normalized"),
                result.getTimestamp("expires_at").toInstant(), result.getInt("acceptance_attempt_count"),
                result.getLong("row_version"));
    }

    private AcceptanceRelationship lockAcceptanceRelationship(TokenInvitation invitation) {
        List<AcceptanceRelationship> relationships = jdbc.query("""
                SELECT guardian.display_name, guardian.email_normalized,
                       guardian.status AS guardian_status, guardian.user_account_id,
                       relationship.portal_access_status
                FROM guardians guardian
                JOIN learner_guardian_relationships relationship
                  ON relationship.guardian_id = guardian.id
                 AND relationship.organisation_id = guardian.organisation_id
                 AND relationship.school_id = guardian.school_id
                WHERE guardian.id = :guardianId
                  AND relationship.learner_id = :learnerId
                  AND guardian.organisation_id = :organisationId
                  AND guardian.school_id = :schoolId
                FOR UPDATE OF guardian, relationship
                """, tokenParameters(invitation), (result, row) -> new AcceptanceRelationship(
                result.getString("display_name"),
                result.getString("email_normalized"),
                result.getString("guardian_status"),
                result.getObject("user_account_id", UUID.class),
                result.getString("portal_access_status")));
        if (relationships.isEmpty()) throw invalidInvitation();
        return relationships.getFirst();
    }

    private UUID ensureParentMembership(UUID userId, TokenInvitation invitation, Instant now) {
        List<StoredMembership> memberships = jdbc.query("""
                SELECT id, status, valid_from, valid_until
                FROM memberships
                WHERE user_id = :userId AND organisation_id = :organisationId
                  AND school_id = :schoolId AND campus_id IS NULL
                FOR UPDATE
                """, tokenParameters(invitation).addValue("userId", userId), (result, row) -> new StoredMembership(
                result.getObject("id", UUID.class),
                result.getString("status"),
                result.getTimestamp("valid_from").toInstant(),
                instant(result, "valid_until")));
        UUID membershipId;
        if (memberships.isEmpty()) {
            membershipId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO memberships (
                        id, user_id, organisation_id, school_id, campus_id, status,
                        valid_from, created_at, updated_at, row_version
                    ) VALUES (
                        :id, :userId, :organisationId, :schoolId, NULL, 'ACTIVE',
                        :now, :now, :now, 0
                    )
                    """, tokenParameters(invitation)
                    .addValue("id", membershipId)
                    .addValue("userId", userId)
                    .addValue("now", now));
        } else {
            StoredMembership membership = memberships.getFirst();
            if (!"ACTIVE".equals(membership.status())
                    || membership.validFrom().isAfter(now)
                    || (membership.validUntil() != null && !membership.validUntil().isAfter(now))) {
                throw conflict(
                        "GUARDIAN_MEMBERSHIP_UNAVAILABLE",
                        "The existing school membership is not currently active. Ask the school to review it.");
            }
            membershipId = membership.id();
        }
        jdbc.update("""
                INSERT INTO membership_roles (membership_id, role_id)
                VALUES (:membershipId, :roleId)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("membershipId", membershipId)
                .addValue("roleId", PARENT_ROLE_ID));
        return membershipId;
    }

    private void recordDeniedAcceptance(TokenInvitation invitation, Instant now) {
        int nextAttempts = Math.min(10, invitation.attemptCount() + 1);
        if (nextAttempts >= 10) {
            jdbc.update("""
                    UPDATE guardian_portal_invitations
                    SET status = 'EXPIRED', delivery_status = CASE
                            WHEN delivery_status IN ('SENT', 'DELIVERED') THEN delivery_status
                            ELSE 'CANCELLED'
                        END,
                        token_ciphertext = NULL, token_nonce = NULL,
                        acceptance_attempt_count = 10, updated_at = :now,
                        row_version = row_version + 1
                    WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                      AND status = 'ISSUED'
                    """, tokenParameters(invitation).addValue("now", now));
            appendOutbox(
                    invitation.id(),
                    "guardian.portal.invitation.expired",
                    Map.of("invitationId", invitation.id(), "schoolId", invitation.schoolId()),
                    now);
            jdbc.update("""
                    UPDATE learner_guardian_relationships
                    SET portal_access_status = 'NOT_INVITED', updated_at = :now,
                        row_version = row_version + 1
                    WHERE learner_id = :learnerId AND guardian_id = :guardianId
                      AND organisation_id = :organisationId AND school_id = :schoolId
                      AND portal_access_status = 'INVITED'
                    """, tokenParameters(invitation).addValue("now", now));
        } else {
            jdbc.update("""
                    UPDATE guardian_portal_invitations
                    SET acceptance_attempt_count = :attempts, updated_at = :now,
                        row_version = row_version + 1
                    WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                      AND status = 'ISSUED'
                    """, tokenParameters(invitation)
                    .addValue("attempts", nextAttempts)
                    .addValue("now", now));
        }
    }

    private boolean expireInvitation(TokenInvitation invitation, Instant now) {
        int expired = jdbc.update("""
                UPDATE guardian_portal_invitations
                SET status = 'EXPIRED', delivery_status = CASE
                        WHEN delivery_status IN ('SENT', 'DELIVERED') THEN delivery_status
                        ELSE 'CANCELLED'
                    END,
                    token_ciphertext = NULL, token_nonce = NULL,
                    updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'ISSUED' AND row_version = :version
                """, tokenParameters(invitation)
                .addValue("now", now)
                .addValue("version", invitation.version()));
        if (expired == 0) return false;
        jdbc.update("""
                UPDATE learner_guardian_relationships relationship
                SET portal_access_status = 'NOT_INVITED', updated_at = :now,
                    row_version = row_version + 1
                WHERE relationship.learner_id = :learnerId
                  AND relationship.guardian_id = :guardianId
                  AND relationship.organisation_id = :organisationId
                  AND relationship.school_id = :schoolId
                  AND relationship.portal_access_status = 'INVITED'
                  AND NOT EXISTS (
                    SELECT 1 FROM guardian_portal_invitations open_invitation
                    WHERE open_invitation.learner_id = relationship.learner_id
                      AND open_invitation.guardian_id = relationship.guardian_id
                      AND open_invitation.status = 'ISSUED'
                  )
                """, tokenParameters(invitation).addValue("now", now));
        appendOutbox(
                invitation.id(),
                "guardian.portal.invitation.expired",
                Map.of("invitationId", invitation.id(), "schoolId", invitation.schoolId()),
                now);
        return true;
    }

    private GuardianPortalInvitationResponse findResponse(UUID organisationId, UUID schoolId, UUID invitationId) {
        List<GuardianPortalInvitationResponse> invitations = jdbc.query("""
                SELECT id, learner_id, guardian_id, target_email_normalized, status,
                       delivery_status, expires_at, accepted_at, revoked_at, created_at, row_version
                FROM guardian_portal_invitations
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                """, new MapSqlParameterSource()
                .addValue("id", invitationId)
                .addValue("organisationId", organisationId)
                .addValue("schoolId", schoolId), GuardianPortalInvitationService::mapResponse);
        if (invitations.isEmpty()) throw invitationNotFound();
        return invitations.getFirst();
    }

    private void appendOutbox(UUID invitationId, String eventType, Map<String, ?> payload, Instant now) {
        jdbc.update("""
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, payload, occurred_at,
                    available_at, attempt_count, created_at
                ) VALUES (
                    :id, 'GuardianPortalInvitation', :aggregateId, :eventType,
                    CAST(:payload AS jsonb), :now, :now, 0, :now
                )
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("aggregateId", invitationId.toString())
                .addValue("eventType", eventType)
                .addValue("payload", json(payload))
                .addValue("now", now));
    }

    private String json(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invitation outbox payload must be JSON serializable", exception);
        }
    }

    private String bindToken(String rawToken) {
        String clean = rawToken == null ? "" : rawToken.strip();
        String tokenHash = tokens.hash(clean);
        jdbc.getJdbcTemplate().queryForObject(
                "SELECT set_config('app.current_invitation_token_hash', ?, true)",
                String.class,
                tokenHash);
        return tokenHash;
    }

    private void bindOrganisation(UUID organisationId) {
        jdbc.getJdbcTemplate().queryForObject(
                "SELECT set_config('app.current_organisation_id', ?, true)",
                String.class,
                organisationId.toString());
    }

    private static RelationshipScope mapScope(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        return new RelationshipScope(
                result.getObject("organisation_id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getObject("learner_id", UUID.class),
                result.getObject("guardian_id", UUID.class),
                result.getString("portal_access_status"),
                result.getLong("row_version"),
                result.getString("email_normalized"),
                result.getString("guardian_status"),
                result.getObject("user_account_id", UUID.class),
                result.getObject("actor_membership_id", UUID.class));
    }

    private static StoredInvitation mapStoredInvitation(java.sql.ResultSet result, int row)
            throws java.sql.SQLException {
        return new StoredInvitation(
                result.getObject("id", UUID.class),
                result.getObject("organisation_id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getObject("learner_id", UUID.class),
                result.getObject("guardian_id", UUID.class),
                result.getString("status"),
                result.getLong("row_version"),
                result.getObject("actor_membership_id", UUID.class));
    }

    private static GuardianPortalInvitationResponse mapResponse(java.sql.ResultSet result, int row)
            throws java.sql.SQLException {
        return new GuardianPortalInvitationResponse(
                result.getObject("id", UUID.class),
                result.getObject("learner_id", UUID.class),
                result.getObject("guardian_id", UUID.class),
                maskEmail(result.getString("target_email_normalized")),
                result.getString("status"),
                result.getString("delivery_status"),
                result.getTimestamp("expires_at").toInstant(),
                instant(result, "accepted_at"),
                instant(result, "revoked_at"),
                result.getTimestamp("created_at").toInstant(),
                result.getLong("row_version"));
    }

    private static Instant instant(java.sql.ResultSet result, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static MapSqlParameterSource scopeParameters(RelationshipScope scope) {
        return new MapSqlParameterSource()
                .addValue("organisationId", scope.organisationId())
                .addValue("schoolId", scope.schoolId())
                .addValue("learnerId", scope.learnerId())
                .addValue("guardianId", scope.guardianId())
                .addValue("actorMembershipId", scope.actorMembershipId());
    }

    private static MapSqlParameterSource invitationParameters(StoredInvitation invitation) {
        return new MapSqlParameterSource()
                .addValue("id", invitation.id())
                .addValue("organisationId", invitation.organisationId())
                .addValue("schoolId", invitation.schoolId())
                .addValue("learnerId", invitation.learnerId())
                .addValue("guardianId", invitation.guardianId())
                .addValue("actorMembershipId", invitation.actorMembershipId());
    }

    private static MapSqlParameterSource tokenParameters(TokenInvitation invitation) {
        return new MapSqlParameterSource()
                .addValue("id", invitation.id())
                .addValue("organisationId", invitation.organisationId())
                .addValue("schoolId", invitation.schoolId())
                .addValue("learnerId", invitation.learnerId())
                .addValue("guardianId", invitation.guardianId());
    }

    private static String normalizeEmail(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip().toLowerCase(Locale.ROOT);
        return clean.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") ? clean : null;
    }

    private static String maskEmail(String email) {
        if (email == null) return "Unavailable";
        int at = email.indexOf('@');
        return at <= 0 ? "***" : email.substring(0, 1) + "***" + email.substring(at);
    }

    private static String cleanReason(String value, int maximum) {
        if (value == null || value.isBlank()) return "Portal access review";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return clean.substring(0, Math.min(maximum, clean.length()));
    }

    private static UUID requireOrganisation(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation before managing portal access.");
        }
        return user.activeOrganisationId();
    }

    private static ApiException invalidInvitation() {
        return new ApiException(HttpStatus.GONE, "GUARDIAN_INVITATION_UNAVAILABLE", INVALID_INVITATION);
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "GUARDIAN_RELATIONSHIP_NOT_FOUND",
                "The learner and guardian relationship was not found in an authorized school.");
    }

    private static ApiException invitationNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "GUARDIAN_INVITATION_NOT_FOUND",
                "The guardian portal invitation was not found in an authorized school.");
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException stale(String message) {
        return conflict("STALE_VERSION", message);
    }

    private static final class AcceptanceDeniedException extends ApiException {
        private static final long serialVersionUID = 1L;

        private AcceptanceDeniedException() {
            super(HttpStatus.UNAUTHORIZED, "GUARDIAN_INVITATION_ACCEPTANCE_DENIED", INVALID_INVITATION);
        }
    }

    private static final class InvitationExpiredException extends ApiException {
        private static final long serialVersionUID = 1L;

        private InvitationExpiredException() {
            super(HttpStatus.GONE, "GUARDIAN_INVITATION_UNAVAILABLE", INVALID_INVITATION);
        }
    }

    private record RelationshipScope(
            UUID organisationId,
            UUID schoolId,
            UUID learnerId,
            UUID guardianId,
            String portalAccessStatus,
            long relationshipVersion,
            String email,
            String guardianStatus,
            UUID linkedUserId,
            UUID actorMembershipId) {
    }

    private record StoredInvitation(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            UUID learnerId,
            UUID guardianId,
            String status,
            long version,
            UUID actorMembershipId) {
    }

    private record TokenInvitation(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            UUID learnerId,
            UUID guardianId,
            String email,
            Instant expiresAt,
            int attemptCount,
            long version) {
    }

    private record ExpiryCandidate(UUID id, UUID schoolId) {}

    private record AcceptanceRelationship(
            String guardianName,
            String guardianEmail,
            String guardianStatus,
            UUID linkedUserId,
            String portalAccessStatus) {
    }

    private record PreviewData(
            String schoolName,
            String email,
            Instant expiresAt,
            boolean existingAccount) {
    }

    private record StoredMembership(UUID id, String status, Instant validFrom, Instant validUntil) {
    }
}

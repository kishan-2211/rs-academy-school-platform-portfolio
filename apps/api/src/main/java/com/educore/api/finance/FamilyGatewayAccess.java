package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.tenancy.TenantRequestContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Live own-child access, including earlier session accounts; never background impersonation. */
@Service
public class FamilyGatewayAccess {
    private final NamedParameterJdbcTemplate jdbc;
    public FamilyGatewayAccess(NamedParameterJdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    public record Scope(UUID organisationId, UUID schoolId, UUID learnerId, UUID membershipId) {
        public Scope { Objects.requireNonNull(organisationId); Objects.requireNonNull(schoolId); Objects.requireNonNull(learnerId); Objects.requireNonNull(membershipId); }
        @Override public String toString() { return "Scope[private verified family relationship]"; }
    }
    public record AccountScope(Scope scope, UUID accountId, String status, String currencyCode, String sessionName) {
        @Override public String toString() { return "AccountScope[private historical family account]"; }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Scope requireChild(AuthenticatedUser user, UUID learnerId) {
        requireActor(user);
        if (learnerId == null) throw missing();
        var p = new MapSqlParameterSource("organisationId", user.activeOrganisationId())
                .addValue("userId", user.userId()).addValue("membershipIds", user.membershipIds()).addValue("learnerId", learnerId);
        var scopes = jdbc.query("""
                SELECT membership.school_id,membership.id AS membership_id
                FROM memberships membership JOIN user_accounts actor ON actor.id=membership.user_id AND actor.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=membership.organisation_id AND organisation.status='ACTIVE'
                JOIN schools school ON school.id=membership.school_id AND school.organisation_id=membership.organisation_id AND school.status='ACTIVE'
                JOIN guardians guardian ON guardian.user_account_id=actor.id AND guardian.organisation_id=membership.organisation_id
                  AND guardian.school_id=membership.school_id AND guardian.status='ACTIVE'
                JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
                  AND relationship.organisation_id=guardian.organisation_id AND relationship.school_id=guardian.school_id
                  AND relationship.portal_access_status='ACTIVE' AND relationship.learner_id=:learnerId
                JOIN learners learner ON learner.id=relationship.learner_id AND learner.organisation_id=relationship.organisation_id
                  AND learner.school_id=relationship.school_id AND learner.status='ACTIVE'
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId AND membership.id IN(:membershipIds)
                  AND membership.status='ACTIVE' AND membership.valid_from<=clock_timestamp()
                  AND (membership.valid_until IS NULL OR membership.valid_until>clock_timestamp())
                  AND current_app_user_id()=:userId AND current_app_organisation_id()=:organisationId
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities capability ON capability.role_id=role.id
                    WHERE mr.membership_id=membership.id AND capability.capability_code='portal.guardian'
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id))
                ORDER BY membership.id LIMIT 1
                """, p, (r, n) -> new Scope(user.activeOrganisationId(), r.getObject("school_id", UUID.class), learnerId,
                        r.getObject("membership_id", UUID.class)));
        if (scopes.size() != 1) throw missing();
        return scopes.getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AccountScope requireAccount(AuthenticatedUser user, UUID learnerId, UUID accountId) {
        Scope scope = requireChild(user, learnerId);
        if (accountId == null) throw missing();
        var rows = jdbc.query("""
                SELECT account.id,account.status,account.currency_code,session.name AS session_name
                FROM learner_fee_accounts account JOIN academic_sessions session ON session.id=account.academic_session_id
                  AND session.organisation_id=account.organisation_id AND session.school_id=account.school_id
                WHERE account.id=:accountId AND account.learner_id=:learnerId
                  AND account.organisation_id=:organisationId AND account.school_id=:schoolId
                """, new MapSqlParameterSource("organisationId", scope.organisationId()).addValue("schoolId", scope.schoolId())
                .addValue("learnerId", learnerId).addValue("accountId", accountId),
                (r, n) -> new AccountScope(scope, r.getObject("id", UUID.class), r.getString("status"),
                        r.getString("currency_code"), r.getString("session_name")));
        if (rows.size() != 1) throw missing();
        return rows.getFirst();
    }

    static void requireActor(AuthenticatedUser user) {
        if (user == null || user.userId() == null || user.activeOrganisationId() == null || user.membershipIds().isEmpty()
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TenantRequestContext.current().map(context -> user.userId().equals(context.userId())
                        && user.activeOrganisationId().equals(context.organisationId())).orElse(false)) throw missing();
    }

    static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "FAMILY_PAYMENT_NOT_FOUND",
                "This learner or payment is not available through your active family relationship.");
    }
}

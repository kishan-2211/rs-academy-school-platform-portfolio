package com.educore.api.tenancy;

import com.educore.api.common.api.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessContextService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AccessContextService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public AccessContext resolve(UUID userId, UUID preferredOrganisationId) {
        applyUserScope(userId);
        List<OrganisationOption> organisations = queryOrganisations(userId);
        UUID activeOrganisationId = chooseActiveOrganisation(organisations, preferredOrganisationId);
        if (activeOrganisationId == null) {
            applyOrganisationScope(null);
            return new AccessContext(null, Set.of(), Set.of(), organisations);
        }

        applyOrganisationScope(activeOrganisationId);
        Set<UUID> membershipIds = new LinkedHashSet<>(jdbc.query(
                """
                SELECT id
                FROM memberships
                WHERE user_id = ?
                  AND organisation_id = ?
                  AND status = 'ACTIVE'
                  AND valid_from <= ?
                  AND (valid_until IS NULL OR valid_until > ?)
                ORDER BY created_at
                """,
                (result, row) -> result.getObject("id", UUID.class),
                userId,
                activeOrganisationId,
                Instant.now(clock),
                Instant.now(clock)));
        Set<String> capabilities = new LinkedHashSet<>(jdbc.query(
                """
                SELECT DISTINCT role_capabilities.capability_code
                FROM memberships
                JOIN membership_roles ON membership_roles.membership_id = memberships.id
                JOIN roles ON roles.id = membership_roles.role_id
                JOIN role_capabilities ON role_capabilities.role_id = roles.id
                WHERE memberships.user_id = ?
                  AND memberships.organisation_id = ?
                  AND memberships.status = 'ACTIVE'
                  AND memberships.valid_from <= ?
                  AND (memberships.valid_until IS NULL OR memberships.valid_until > ?)
                  AND (roles.organisation_id IS NULL OR roles.organisation_id = ?)
                ORDER BY role_capabilities.capability_code
                """,
                (result, row) -> result.getString("capability_code"),
                userId,
                activeOrganisationId,
                Instant.now(clock),
                Instant.now(clock),
                activeOrganisationId));
        return new AccessContext(activeOrganisationId, Set.copyOf(membershipIds), Set.copyOf(capabilities), organisations);
    }

    @Transactional
    public AccessContext select(UUID userId, UUID organisationId) {
        AccessContext context = resolve(userId, organisationId);
        if (!Objects.equals(context.activeOrganisationId(), organisationId)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "ORGANISATION_ACCESS_DENIED",
                    "The selected school organisation is not available to this account.");
        }
        return context;
    }

    private List<OrganisationOption> queryOrganisations(UUID userId) {
        Instant now = Instant.now(clock);
        return jdbc.query(
                """
                SELECT organisations.id,
                       organisations.display_name,
                       organisations.slug,
                       count(DISTINCT memberships.id) AS membership_count
                FROM memberships
                JOIN organisations ON organisations.id = memberships.organisation_id
                WHERE memberships.user_id = ?
                  AND memberships.status = 'ACTIVE'
                  AND memberships.valid_from <= ?
                  AND (memberships.valid_until IS NULL OR memberships.valid_until > ?)
                  AND organisations.status = 'ACTIVE'
                GROUP BY organisations.id, organisations.display_name, organisations.slug
                ORDER BY organisations.display_name, organisations.id
                """,
                (result, row) -> new OrganisationOption(
                        result.getObject("id", UUID.class),
                        result.getString("display_name"),
                        result.getString("slug"),
                        result.getInt("membership_count")),
                userId,
                now,
                now);
    }

    private UUID chooseActiveOrganisation(
            List<OrganisationOption> organisations,
            UUID preferredOrganisationId) {
        if (preferredOrganisationId != null && organisations.stream()
                .anyMatch(option -> option.id().equals(preferredOrganisationId))) {
            return preferredOrganisationId;
        }
        return organisations.size() == 1 ? organisations.getFirst().id() : null;
    }

    private void applyUserScope(UUID userId) {
        jdbc.queryForObject(
                "SELECT set_config('app.current_user_id', ?, true)",
                String.class,
                userId.toString());
    }

    private void applyOrganisationScope(UUID organisationId) {
        jdbc.queryForObject(
                "SELECT set_config('app.current_organisation_id', ?, true)",
                String.class,
                organisationId == null ? "" : organisationId.toString());
    }

    public record AccessContext(
            UUID activeOrganisationId,
            Set<UUID> membershipIds,
            Set<String> capabilities,
            List<OrganisationOption> organisations) {

        public AccessContext {
            membershipIds = Set.copyOf(membershipIds);
            capabilities = Set.copyOf(capabilities);
            organisations = List.copyOf(organisations);
        }
    }

    public record OrganisationOption(UUID id, String displayName, String slug, int membershipCount) {
    }
}

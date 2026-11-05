package com.educore.api.identity;

import java.util.UUID;
import java.util.List;
import java.util.Set;
import com.educore.api.tenancy.AccessContextService.OrganisationOption;

public record AuthenticatedUser(
        UUID userId,
        UUID sessionId,
        String loginIdentifier,
        String email,
        String displayName,
        boolean mfaVerified,
        UUID activeOrganisationId,
        Set<UUID> membershipIds,
        Set<String> capabilities,
        List<OrganisationOption> organisations) {

    public AuthenticatedUser {
        membershipIds = Set.copyOf(membershipIds);
        capabilities = Set.copyOf(capabilities);
        organisations = List.copyOf(organisations);
    }
}

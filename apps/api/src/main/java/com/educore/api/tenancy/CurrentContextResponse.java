package com.educore.api.tenancy;

import com.educore.api.identity.AuthenticatedUser;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record CurrentContextResponse(
        UUID activeOrganisationId,
        List<Organisation> organisations,
        Set<String> capabilities,
        boolean selectionRequired,
        boolean mfaRequired) {

    public CurrentContextResponse {
        organisations = List.copyOf(organisations);
        capabilities = Set.copyOf(capabilities);
    }

    static CurrentContextResponse from(AuthenticatedUser user, boolean mfaRequired) {
        List<Organisation> options = user.organisations().stream()
                .map(option -> new Organisation(
                        option.id(),
                        option.displayName(),
                        option.slug(),
                        option.membershipCount(),
                        option.id().equals(user.activeOrganisationId())))
                .toList();
        return new CurrentContextResponse(
                user.activeOrganisationId(),
                options,
                user.capabilities(),
                user.activeOrganisationId() == null && options.size() > 1,
                mfaRequired);
    }

    public record Organisation(
            UUID id,
            String displayName,
            String slug,
            int membershipCount,
            boolean active) {
    }
}

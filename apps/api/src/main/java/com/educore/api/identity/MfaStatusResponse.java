package com.educore.api.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MfaStatusResponse(boolean enabled, List<Factor> factors, long unusedRecoveryCodes, boolean sessionVerified) {

    public MfaStatusResponse {
        factors = List.copyOf(factors);
    }

    public record Factor(UUID id, String type, String label, Instant verifiedAt) {
        static Factor from(MfaTotpFactor factor) {
            return new Factor(factor.getId(), "totp", factor.getLabel(), factor.getVerifiedAt());
        }
    }
}

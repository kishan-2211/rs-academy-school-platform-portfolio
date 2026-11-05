package com.educore.api.identity;

import java.time.Instant;

public record LoginResponse(
        String status,
        AuthSessionResponse session,
        String challengeToken,
        Instant challengeExpiresAt) {

    static LoginResponse authenticated(UserSession session) {
        return new LoginResponse("authenticated", AuthSessionResponse.from(session), null, null);
    }

    static LoginResponse mfaRequired(String challengeToken, Instant challengeExpiresAt) {
        return new LoginResponse("mfa_required", null, challengeToken, challengeExpiresAt);
    }
}

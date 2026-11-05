package com.educore.api.identity;

import java.time.Instant;

record LoginResult(
        String rawSessionToken,
        UserSession session,
        String rawChallengeToken,
        Instant challengeExpiresAt) {

    static LoginResult authenticated(String rawSessionToken, UserSession session) {
        return new LoginResult(rawSessionToken, session, null, null);
    }

    static LoginResult mfaRequired(String rawChallengeToken, Instant challengeExpiresAt) {
        return new LoginResult(null, null, rawChallengeToken, challengeExpiresAt);
    }

    boolean isAuthenticated() {
        return session != null;
    }
}

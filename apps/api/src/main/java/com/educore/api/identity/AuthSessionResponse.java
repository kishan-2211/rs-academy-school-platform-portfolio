package com.educore.api.identity;

import java.time.Instant;
import java.util.UUID;

public record AuthSessionResponse(
        UUID userId,
        String loginIdentifier,
        String email,
        String displayName,
        Instant expiresAt,
        boolean mfaVerified) {

    static AuthSessionResponse from(UserSession session) {
        UserAccount user = session.getUser();
        return new AuthSessionResponse(
                user.getId(),
                user.getLoginIdentifierNormalized(),
                user.getEmailNormalized(),
                user.getDisplayName(),
                session.getExpiresAt(),
                session.isMfaVerified());
    }

    static AuthSessionResponse from(AuthenticatedUser user, Instant expiresAt) {
        return new AuthSessionResponse(
                user.userId(), user.loginIdentifier(), user.email(), user.displayName(), expiresAt, user.mfaVerified());
    }
}

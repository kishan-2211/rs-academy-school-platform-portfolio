package com.educore.api.identity;

import java.time.Instant;
import java.util.UUID;

public record SessionSummaryResponse(
        UUID id,
        String deviceName,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        boolean current,
        boolean mfaVerified) {

    static SessionSummaryResponse from(UserSession session, boolean current) {
        return new SessionSummaryResponse(
                session.getId(),
                session.getDeviceName(),
                session.getCreatedAt(),
                session.getLastSeenAt(),
                session.getExpiresAt(),
                current,
                session.isMfaVerified());
    }
}

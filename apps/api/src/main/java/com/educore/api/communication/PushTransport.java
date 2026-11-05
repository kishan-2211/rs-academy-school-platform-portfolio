package com.educore.api.communication;

import java.time.Instant;

/** Provider-neutral boundary; acceptance never implies phone display or human reading. */
public interface PushTransport {
    Result send(Message message);

    record Message(String endpoint, String p256dh, String auth, String payload,
            String topic, boolean urgent, Instant expiresAt) {
        @Override public String toString() { return "PushMessage[redacted]"; }
    }

    enum Outcome { ACCEPTED, SUBSCRIPTION_EXPIRED, RETRYABLE_FAILURE, PERMANENT_FAILURE,
        CONFIGURATION_FAILURE, DISABLED }

    record Result(Outcome outcome, Integer httpStatus, String safeCode, long retryAfterSeconds) {}
}

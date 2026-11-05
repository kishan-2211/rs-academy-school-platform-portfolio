package com.educore.api.communication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Operational counts only: no subscription capability material or private message contents. */
public record PushOperationsResponse(UUID schoolId, boolean canApprove, boolean transportEnabled,
        boolean workerEnabled, String providerStatus, Long providerVersion, boolean providerCompatible,
        Instant windowStartsAt, List<StateCount> parentStates, List<StateCount> deviceStates,
        List<StateCount> failureCodes) {
    public record StateCount(String code, long count) {}
}

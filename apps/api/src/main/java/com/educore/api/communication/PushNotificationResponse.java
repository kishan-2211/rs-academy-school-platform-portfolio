package com.educore.api.communication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PushNotificationResponse(
        UUID schoolId,
        boolean registrationEnabled,
        String applicationServerKey,
        boolean allowPush,
        int activeDeviceCount,
        List<Device> devices) {

    public record Device(
            UUID id,
            String label,
            Instant createdAt,
            Instant lastConfirmedAt) {}
}

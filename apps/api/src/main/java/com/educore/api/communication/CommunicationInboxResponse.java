package com.educore.api.communication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommunicationInboxResponse(
        UUID schoolId,
        UUID learnerId,
        List<InboxItem> items) {

    public record InboxItem(
            UUID recipientId,
            UUID announcementId,
            UUID learnerId,
            String category,
            String priority,
            String audienceKind,
            String languageMode,
            String preferredLanguage,
            String titleEn,
            String titleHi,
            String bodyEn,
            String bodyHi,
            boolean requiresAcknowledgement,
            Instant scheduledFor,
            Instant expiresAt,
            boolean expired,
            Instant readAt,
            Instant acknowledgedAt,
            List<DeliveryItem> deliveries,
            long version) {}

    public record DeliveryItem(String channel, String status) {}
}

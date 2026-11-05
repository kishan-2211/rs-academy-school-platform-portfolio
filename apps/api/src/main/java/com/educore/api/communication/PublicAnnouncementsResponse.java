package com.educore.api.communication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublicAnnouncementsResponse(
        String schoolName,
        List<PublicAnnouncementItem> announcements) {

    public record PublicAnnouncementItem(
            UUID id,
            String category,
            String priority,
            String languageMode,
            String titleEn,
            String titleHi,
            String bodyEn,
            String bodyHi,
            Instant publishedAt,
            Instant expiresAt) {}
}

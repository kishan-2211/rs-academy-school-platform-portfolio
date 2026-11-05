package com.educore.api.people;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublicStaffResponse(String schoolName, List<PublicStaffItem> staff) {
    public PublicStaffResponse { staff = List.copyOf(staff); }

    public record PublicStaffItem(UUID id, String displayName, String designation,
            String qualificationSummary, String shortBio, String cbseStaffCategory,
            int displayOrder, Instant publishedAt) {}
}

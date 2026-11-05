package com.educore.api.launch;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LaunchReadinessSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canConfigure,
        boolean canApprove,
        String launchState,
        int itemCount,
        int requiredCount,
        int readyCount,
        int blockedCount,
        String currentSnapshotSha256,
        List<ReadinessItem> items,
        List<LaunchReview> reviews) {

    public LaunchReadinessSnapshot {
        items = List.copyOf(items);
        reviews = List.copyOf(reviews);
    }

    public record ReadinessItem(
            UUID id,
            String controlCode,
            int revisionNumber,
            boolean systemControl,
            String category,
            String title,
            String guidance,
            boolean required,
            String status,
            String evidenceReference,
            String ownerRoleLabel,
            String reviewNote,
            String revisedBy,
            Instant revisedAt) {}

    public record LaunchReview(
            UUID id,
            int reviewNumber,
            UUID supersedesReviewId,
            String status,
            int itemCount,
            int requiredCount,
            int readyCount,
            int blockedCount,
            String snapshotSha256,
            boolean matchesCurrentSnapshot,
            String submissionStatement,
            String submissionReason,
            String submittedBy,
            Instant submittedAt,
            String decidedBy,
            Instant decidedAt,
            String decisionReason,
            long rowVersion) {}
}

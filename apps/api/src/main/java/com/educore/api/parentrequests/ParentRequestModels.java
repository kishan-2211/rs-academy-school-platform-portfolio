package com.educore.api.parentrequests;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ParentRequestModels {
    private ParentRequestModels() {}

    public record Summary(
            UUID id, String requestNumber, UUID learnerId, String learnerName,
            String guardianName, String category, String subject, String priority,
            String status, UUID assignedStaffProfileId, String assignedStaffName,
            Instant openedAt, Instant lastActivityAt, long rowVersion) {}

    public record Message(
            UUID id, String authorType, String authorName, String body,
            boolean internalNote, Instant createdAt) {}

    public record History(
            UUID id, String eventType, String previousStatus, String newStatus,
            String previousPriority, String newPriority, String previousAssigneeName,
            String newAssigneeName, String reason, String actorName, Instant occurredAt) {}

    public record Detail(
            Summary request, String resolutionSummary, Instant resolvedAt,
            String closeReason, Instant closedAt, List<Message> messages, List<History> history) {}

    public record FamilyList(UUID learnerId, List<Summary> requests) {}

    public record Assignee(UUID staffProfileId, String employeeCode, String displayName, String designation) {}

    public record Workspace(
            UUID schoolId, String schoolName, boolean canManage, UUID selfStaffProfileId,
            List<Summary> requests, List<Assignee> assignees) {}

    public record Mutation(UUID id, long rowVersion, String status) {}
}

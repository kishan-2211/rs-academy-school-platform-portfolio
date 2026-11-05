package com.educore.api.family;

import com.educore.api.files.PrivateFileSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FamilyAssignmentsResponse(UUID learnerId, List<Assignment> assignments) {
    public FamilyAssignmentsResponse {
        assignments = List.copyOf(assignments);
    }

    public record Assignment(
            UUID assignmentId, String title, String instructions, String subjectName,
            String categoryName, String resourceLabel, String resourceUrl,
            Instant availableFrom, Instant dueAt, String submissionMode,
            BigDecimal maximumPoints, boolean allowAttachments, int maxAttachmentCount,
            boolean attachmentRequired, boolean allowLate, Instant lateUntil,
            String status, int publicationRevision, List<PrivateFileSummary> attachments,
            Submission submission) {
        public Assignment { attachments = List.copyOf(attachments); }
    }

    public record Submission(
            UUID submissionId, String state, String responseText, String responseLink,
            Instant submittedAt, String teacherFeedback, BigDecimal pointsAwarded,
            Instant reviewedAt, long rowVersion, List<PrivateFileSummary> attachments) {
        public Submission { attachments = List.copyOf(attachments); }
    }
}

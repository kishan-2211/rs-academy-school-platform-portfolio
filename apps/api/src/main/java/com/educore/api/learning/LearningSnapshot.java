package com.educore.api.learning;

import com.educore.api.files.PrivateFileSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LearningSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canModerate,
        List<SessionItem> sessions,
        List<TermItem> terms,
        List<SectionItem> sections,
        List<OfferingItem> offerings,
        List<CategoryItem> categories,
        List<AssignmentItem> assignments) {

    public LearningSnapshot {
        sessions = List.copyOf(sessions);
        terms = List.copyOf(terms);
        sections = List.copyOf(sections);
        offerings = List.copyOf(offerings);
        categories = List.copyOf(categories);
        assignments = List.copyOf(assignments);
    }

    public record SessionItem(UUID id, String name, LocalDate startsOn, LocalDate endsOn, String status) {}
    public record TermItem(
            UUID id, UUID sessionId, String code, String name,
            LocalDate startsOn, LocalDate endsOn, String status) {}
    public record SectionItem(UUID id, UUID sessionId, String gradeCode, String name) {}
    public record OfferingItem(
            UUID id, UUID sectionId, UUID subjectId, String subjectCode, String subjectName) {}
    public record CategoryItem(
            UUID id, UUID sessionId, String code, String name, String accentColor,
            int displayOrder, String status, long rowVersion) {}
    public record AssignmentItem(
            UUID id, UUID termId, UUID sectionId, UUID offeringId, UUID categoryId,
            String title, String instructions, String subjectName, String categoryName,
            String resourceLabel, String resourceUrl, Instant availableFrom, Instant dueAt,
            String submissionMode, BigDecimal maximumPoints,
            boolean allowAttachments, int maxAttachmentCount, boolean attachmentRequired,
            boolean allowLate, Instant lateUntil,
            String status, int publicationRevision, int expectedLearnerCount,
            Map<String, Integer> submissionCounts, Instant publishedAt, Instant closedAt,
            long rowVersion, List<PrivateFileSummary> attachments, List<SubmissionItem> submissions) {
        public AssignmentItem {
            submissionCounts = Map.copyOf(submissionCounts);
            attachments = List.copyOf(attachments);
            submissions = List.copyOf(submissions);
        }
    }
    public record SubmissionItem(
            UUID id, UUID learnerId, String learnerName, String rollNumber, String state,
            String responseText, String responseLink, Instant submittedAt,
            String teacherFeedback, BigDecimal pointsAwarded, Instant reviewedAt,
            long rowVersion, List<PrivateFileSummary> attachments) {
        public SubmissionItem { attachments = List.copyOf(attachments); }
    }
}

package com.educore.api.assessment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AssessmentSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canConfigure,
        boolean canPublish,
        List<SessionItem> sessions,
        List<TermItem> terms,
        List<SectionItem> sections,
        List<OfferingItem> offerings,
        List<ScaleItem> gradingScales,
        List<BandItem> gradeBands,
        List<SchemeItem> schemes,
        List<ComponentItem> components,
        List<AssessmentItem> assessments,
        List<CorrectionItem> corrections,
        List<PublicationItem> publications) {

    public AssessmentSnapshot {
        sessions = List.copyOf(sessions);
        terms = List.copyOf(terms);
        sections = List.copyOf(sections);
        offerings = List.copyOf(offerings);
        gradingScales = List.copyOf(gradingScales);
        gradeBands = List.copyOf(gradeBands);
        schemes = List.copyOf(schemes);
        components = List.copyOf(components);
        assessments = List.copyOf(assessments);
        corrections = List.copyOf(corrections);
        publications = List.copyOf(publications);
    }

    public record SessionItem(UUID id, String name, LocalDate startsOn, LocalDate endsOn, String status) {}
    public record TermItem(UUID id, UUID sessionId, String code, String name, LocalDate startsOn, LocalDate endsOn, String status) {}
    public record SectionItem(UUID id, UUID sessionId, String gradeCode, String name) {}
    public record OfferingItem(UUID id, UUID sectionId, UUID subjectId, String subjectCode, String subjectName) {}
    public record ScaleItem(UUID id, UUID sessionId, String name, String status, long rowVersion) {}
    public record BandItem(
            UUID id, UUID gradingScaleId, String gradeCode, String name,
            BigDecimal minimumPercentage, BigDecimal maximumPercentage, BigDecimal gradePoint,
            String outcome, String descriptor, int displayOrder, long rowVersion) {}
    public record SchemeItem(
            UUID id, UUID sessionId, UUID gradingScaleId, String gradeCode, String name,
            String aggregationMode, String roundingMode, int decimalPlaces, String passRule,
            BigDecimal overallPassPercentage, String status, Instant activatedAt, long rowVersion) {}
    public record ComponentItem(
            UUID id, UUID schemeId, String code, String name, String scoringMode,
            BigDecimal weightPercentage, BigDecimal defaultMaxMarks, BigDecimal passPercentage,
            int displayOrder, String status, long rowVersion) {}
    public record AssessmentItem(
            UUID id, UUID termId, UUID sectionId, UUID offeringId, UUID schemeId, UUID componentId,
            String name, LocalDate assessmentDate, String scoringMode, BigDecimal maximumMarks,
            String subjectName, String componentName, String status, int expectedLearnerCount,
            int recordedCount, Instant submittedAt, Instant validatedAt, Instant publishedAt,
            long rowVersion, List<MarkItem> marks) {
        public AssessmentItem { marks = List.copyOf(marks); }
    }
    public record MarkItem(
            UUID id, UUID learnerId, String learnerName, String rollNumber, String evidenceState,
            BigDecimal marksAwarded, String gradeCode, String narrative, String teacherRemark,
            long rowVersion) {}
    public record CorrectionItem(
            UUID id, UUID markId, UUID assessmentId, String learnerName, String assessmentName,
            String requestedState, BigDecimal requestedMarks, String requestedGrade,
            String requestedNarrative, String requestReason, String status,
            String requestedBy, Instant createdAt, long rowVersion) {}
    public record PublicationItem(
            UUID id, UUID termId, UUID sectionId, UUID schemeId, int version, String name,
            String status, UUID supersedesPublicationId, int reportCardCount,
            Instant publishedAt, Instant supersededAt, Instant revokedAt) {}
}

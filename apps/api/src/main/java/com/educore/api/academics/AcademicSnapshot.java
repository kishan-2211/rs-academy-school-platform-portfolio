package com.educore.api.academics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record AcademicSnapshot(
        UUID schoolId,
        String schoolName,
        List<SessionOption> sessions,
        List<CampusOption> campuses,
        List<SectionOption> sections,
        List<TeacherOption> teachers,
        List<TermItem> terms,
        List<SubjectItem> subjects,
        List<ResourceItem> resources,
        List<CurriculumPublicationItem> curriculumPublications,
        List<CurriculumSnapshotItem> curriculumItems,
        List<OfferingItem> offerings,
        List<AllocationItem> allocations,
        List<PeriodItem> periods,
        List<PlanItem> plans,
        List<EntryItem> entries) {

    public AcademicSnapshot {
        sessions = List.copyOf(sessions);
        campuses = List.copyOf(campuses);
        sections = List.copyOf(sections);
        teachers = List.copyOf(teachers);
        terms = List.copyOf(terms);
        subjects = List.copyOf(subjects);
        resources = List.copyOf(resources);
        curriculumPublications = List.copyOf(curriculumPublications);
        curriculumItems = List.copyOf(curriculumItems);
        offerings = List.copyOf(offerings);
        allocations = List.copyOf(allocations);
        periods = List.copyOf(periods);
        plans = List.copyOf(plans);
        entries = List.copyOf(entries);
    }

    public record SessionOption(
            UUID id, String name, LocalDate startsOn, LocalDate endsOn, String status, long rowVersion,
            int rteSeatLimit, int rteMinAgeYears, int rteMaxAgeYears) {}

    public record CampusOption(UUID id, String name, String timeZone) {}

    public record SectionOption(
            UUID id, UUID campusId, UUID academicSessionId, String gradeCode, String code, String name, String status) {}

    public record TeacherOption(UUID membershipId, String displayName, String email) {}

    public record TermItem(
            UUID id, UUID academicSessionId, String code, String name, LocalDate startsOn,
            LocalDate endsOn, String status, long rowVersion) {}

    public record SubjectItem(
            UUID id, String code, String name, String shortName, String category,
            String languageCode, String boardSubjectCode, String status, long rowVersion) {}

    public record ResourceItem(
            UUID id, UUID academicSessionId, UUID subjectId, String gradeCode, String resourceType,
            String title, String description, String author, String publisher, String edition,
            String isbn, String sourceUrl, int displayOrder, String status, long rowVersion) {}

    public record CurriculumPublicationItem(
            UUID id, UUID academicSessionId, String gradeCode, int publicationVersion,
            String status, UUID supersedesPublicationId, Instant createdAt,
            Instant publishedAt, int itemCount, long rowVersion) {}

    public record CurriculumSnapshotItem(
            UUID id, UUID publicationId, UUID sourceResourceId, String subjectCode,
            String subjectName, String subjectShortName, String resourceType, String title,
            String description, String author, String publisher, String edition, String isbn,
            String sourceUrl, int displayOrder) {}

    public record OfferingItem(
            UUID id, UUID sectionId, UUID subjectId, int weeklyTargetPeriods,
            String status, long rowVersion) {}

    public record AllocationItem(
            UUID id, UUID offeringId, UUID teacherMembershipId, String allocationRole,
            LocalDate startsOn, LocalDate endsOn, String status, long rowVersion) {}

    public record PeriodItem(
            UUID id, UUID campusId, UUID academicSessionId, int dayOfWeek, int sequenceNumber,
            String code, String name, LocalTime startsAt, LocalTime endsAt, String kind,
            String status, long rowVersion) {}

    public record PlanItem(
            UUID id, UUID campusId, UUID academicSessionId, UUID academicTermId, String name,
            String status, UUID supersedesPlanId, Instant publishedAt, long rowVersion) {}

    public record EntryItem(
            UUID id, UUID planId, UUID sectionId, UUID offeringId, UUID periodId,
            UUID teacherMembershipId, String roomLabel, String note, long rowVersion) {}
}

package com.educore.api.ptm;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PtmModels {
    private PtmModels() {}

    public record Mutation(UUID id, long rowVersion, String status) {}
    public record SessionOption(UUID id, String name, LocalDate startsOn, LocalDate endsOn, String status) {}
    public record SectionOption(UUID id, UUID sessionId, String gradeCode, String name, String timeZone) {}
    public record TeacherOption(UUID membershipId, UUID staffProfileId, String employeeCode, String name) {}
    public record FamilySlot(UUID id, Instant startsAt, Instant endsAt, boolean available, boolean selected) {}
    public record FamilyBooking(UUID id, UUID slotId, String status, String topic, long rowVersion) {}
    public record FamilyEvent(UUID id, String titleEn, String titleHi, String instructionsEn,
            String instructionsHi, String venue, String teacherName, String className, LocalDate eventDate,
            Instant bookingOpensAt, Instant bookingClosesAt, Instant changesCloseAt,
            List<FamilySlot> slots, FamilyBooking booking, String timeZone, String status) {}
    public record FamilySchedule(UUID learnerId, String learnerName, List<FamilyEvent> events) {}

    public record StaffBooking(UUID id, UUID learnerId, String learnerName, String guardianName,
            String topic, String status, long rowVersion) {}
    public record StaffSlot(UUID id, Instant startsAt, Instant endsAt, String status, StaffBooking booking) {}
    public record StaffEvent(UUID id, UUID sessionId, UUID sectionId, UUID teacherMembershipId,
            String titleEn, String titleHi, String venue, String teacherName, String className,
            LocalDate eventDate, Instant bookingOpensAt, Instant bookingClosesAt,
            Instant changesCloseAt, int reminderMinutesBefore, String status,
            List<StaffSlot> slots, long rowVersion, String timeZone) {}
    public record Workspace(UUID schoolId, String schoolName, boolean canManage,
            UUID selfTeacherMembershipId, List<SessionOption> sessions, List<SectionOption> sections,
            List<TeacherOption> teachers, List<StaffEvent> events) {}
}

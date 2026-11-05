package com.educore.api.schoolcalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record SchoolCalendarSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canManage,
        boolean canApprove,
        UUID selectedCampusId,
        UUID selectedAcademicSessionId,
        List<CampusOption> campuses,
        List<SessionOption> sessions,
        PolicyItem policy,
        List<NoticeItem> notices,
        List<ReviewItem> reviews) {

    public record CampusOption(UUID id, String code, String name, String timeZone) {}

    public record SessionOption(
            UUID id, String name, LocalDate startsOn, LocalDate endsOn, String status) {}

    public record PolicyItem(
            UUID id,
            int revisionNumber,
            UUID supersedesPolicyId,
            LocalTime schoolStartTime,
            int sameDayCutoffMinutes,
            LocalTime sameDayCutoffTime,
            LocalTime reminderTime,
            String timeZone,
            boolean guardianPushEnabled,
            boolean studentInAppEnabled,
            boolean emergencyAcknowledgementRequired,
            String changeReason,
            String revisedBy,
            Instant revisedAt) {}

    public record NotificationJobItem(
            UUID id,
            String audienceKind,
            List<String> requestedChannels,
            boolean requiresAcknowledgement,
            Instant scheduledFor,
            Instant nextAttemptAt,
            String status,
            UUID announcementId,
            Integer recipientCount,
            int attemptCount,
            String lastErrorCode,
            int inAppReadyCount,
            int pushAwaitingProviderCount,
            int pushReadyCount,
            int pushSentCount,
            int pushDeliveredCount,
            int pushFailedCount,
            int pushSuppressedCount) {}

    public record NoticeItem(
            UUID noticeSeriesId,
            UUID revisionId,
            int revisionNumber,
            UUID supersedesRevisionId,
            LocalDate calendarDate,
            String dayType,
            String noticeKind,
            String titleEn,
            String titleHi,
            String bodyEn,
            String bodyHi,
            String sourceReference,
            String changeReason,
            String revisedBy,
            Instant revisedAt,
            String reviewStatus,
            List<NotificationJobItem> notificationJobs) {}

    public record ReviewItem(
            UUID id,
            UUID noticeSeriesId,
            UUID noticeRevisionId,
            int revisionNumber,
            LocalDate calendarDate,
            String titleEn,
            String titleHi,
            String status,
            String submissionReason,
            String submittedBy,
            Instant submittedAt,
            String decidedBy,
            Instant decidedAt,
            String decisionReason,
            long rowVersion) {}
}

package com.educore.api.schoolcalendar;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/school-calendar")
public class SchoolCalendarController {
    private static final String READ =
            "hasAnyAuthority('CAPABILITY_calendar.manage','CAPABILITY_calendar.approve')";

    private final SchoolCalendarService calendars;

    public SchoolCalendarController(SchoolCalendarService calendars) {
        this.calendars = calendars;
    }

    @GetMapping
    @PreAuthorize(READ)
    SchoolCalendarSnapshot snapshot(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam(required = false) UUID campusId,
            @RequestParam(required = false) UUID academicSessionId) {
        return calendars.snapshot(user, schoolId, campusId, academicSessionId);
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_calendar.manage')")
    SchoolCalendarMutationResponse createPolicy(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreatePolicyRequest request) {
        return calendars.createPolicy(user, request);
    }

    @PutMapping("/policies/{policyId}")
    @PreAuthorize("hasAuthority('CAPABILITY_calendar.manage')")
    SchoolCalendarMutationResponse revisePolicy(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID policyId,
            @Valid @RequestBody RevisePolicyRequest request) {
        return calendars.revisePolicy(user, policyId, request);
    }

    @PostMapping("/notices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_calendar.manage')")
    SchoolCalendarMutationResponse createNotice(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateNoticeRequest request) {
        return calendars.createNotice(user, request);
    }

    @PostMapping("/notices/{noticeSeriesId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_calendar.manage')")
    SchoolCalendarMutationResponse reviseNotice(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID noticeSeriesId,
            @Valid @RequestBody ReviseNoticeRequest request) {
        return calendars.reviseNotice(user, noticeSeriesId, request);
    }

    @PostMapping("/notices/{noticeSeriesId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_calendar.manage')")
    SchoolCalendarMutationResponse submitReview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID noticeSeriesId,
            @Valid @RequestBody SubmitReviewRequest request) {
        return calendars.submitReview(user, noticeSeriesId, request);
    }

    @PostMapping("/reviews/{reviewId}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_calendar.approve')")
    SchoolCalendarMutationResponse decideReview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID reviewId,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return calendars.decideReview(user, reviewId, request);
    }

    public record CreatePolicyRequest(
            @NotNull UUID schoolId,
            @NotNull UUID campusId,
            @NotNull UUID academicSessionId,
            @NotNull LocalTime schoolStartTime,
            @Min(0) @Max(240) int sameDayCutoffMinutes,
            @NotNull LocalTime reminderTime,
            @NotBlank @Size(max = 60) String timeZone,
            boolean guardianPushEnabled,
            boolean studentInAppEnabled,
            boolean emergencyAcknowledgementRequired,
            @AssertTrue boolean authorityConfirmed,
            @NotBlank @Size(max = 500) String reason) {}

    public record RevisePolicyRequest(
            @NotNull UUID schoolId,
            @NotNull LocalTime schoolStartTime,
            @Min(0) @Max(240) int sameDayCutoffMinutes,
            @NotNull LocalTime reminderTime,
            @NotBlank @Size(max = 60) String timeZone,
            boolean guardianPushEnabled,
            boolean studentInAppEnabled,
            boolean emergencyAcknowledgementRequired,
            @Min(1) int expectedRevision,
            @AssertTrue boolean authorityConfirmed,
            @NotBlank @Size(max = 500) String reason) {}

    public record CreateNoticeRequest(
            @NotNull UUID schoolId,
            @NotNull UUID campusId,
            @NotNull UUID academicSessionId,
            @NotNull LocalDate calendarDate,
            @NotBlank @Pattern(regexp = "^(?i:HOLIDAY|CLOSURE|INSTRUCTIONAL)$") String dayType,
            @NotBlank String noticeKind,
            @NotBlank @Size(max = 180) String titleEn,
            @NotBlank @Size(max = 180) String titleHi,
            @NotBlank @Size(max = 4000) String bodyEn,
            @NotBlank @Size(max = 4000) String bodyHi,
            @Size(max = 500) String sourceReference,
            @AssertTrue boolean bilingualContentConfirmed,
            @NotBlank @Size(max = 500) String reason) {}

    public record ReviseNoticeRequest(
            @NotNull UUID schoolId,
            @NotBlank @Pattern(regexp = "^(?i:HOLIDAY|CLOSURE|INSTRUCTIONAL)$") String dayType,
            @NotBlank String noticeKind,
            @NotBlank @Size(max = 180) String titleEn,
            @NotBlank @Size(max = 180) String titleHi,
            @NotBlank @Size(max = 4000) String bodyEn,
            @NotBlank @Size(max = 4000) String bodyHi,
            @Size(max = 500) String sourceReference,
            @Min(1) int expectedRevision,
            @AssertTrue boolean bilingualContentConfirmed,
            @NotBlank @Size(max = 500) String reason) {}

    public record SubmitReviewRequest(
            @NotNull UUID schoolId,
            @Min(1) int expectedRevision,
            @AssertTrue boolean evidenceConfirmed,
            @NotBlank @Size(max = 500) String reason) {}

    public record ReviewDecisionRequest(
            @NotNull UUID schoolId,
            @NotBlank @Pattern(regexp = "^(?i:APPROVED|REJECTED)$") String decision,
            @Min(0) long expectedVersion,
            @NotBlank @Size(max = 1000) String reason) {}
}

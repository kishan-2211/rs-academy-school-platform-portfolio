package com.educore.api.attendance;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {
    private final AttendanceService attendance;
    public AttendanceController(AttendanceService attendance) { this.attendance = attendance; }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.read')")
    AttendanceSnapshot snapshot(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam(required = false) LocalDate date) {
        return attendance.snapshot(user, schoolId, date);
    }

    @PostMapping("/policies") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.configure')")
    AttendanceMutationResponse createPolicy(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PolicyRequest request) { return attendance.createPolicy(user, request); }
    @PutMapping("/policies/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.configure')")
    AttendanceMutationResponse updatePolicy(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody PolicyRequest request) { return attendance.updatePolicy(user, id, request); }

    @PostMapping("/calendar-days") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.configure')")
    AttendanceMutationResponse createCalendarDay(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CalendarDayRequest request) { return attendance.createCalendarDay(user, request); }
    @PutMapping("/calendar-days/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.configure')")
    AttendanceMutationResponse updateCalendarDay(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody CalendarDayRequest request) { return attendance.updateCalendarDay(user, id, request); }

    @PostMapping("/registers") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.record')")
    AttendanceMutationResponse openRegister(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody OpenRegisterRequest request) { return attendance.openRegister(user, request); }
    @PutMapping("/registers/{id}/marks")
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.record')")
    AttendanceMutationResponse recordMarks(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RecordMarksRequest request) { return attendance.recordMarks(user, id, request); }
    @PostMapping("/registers/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.record')")
    AttendanceMutationResponse submit(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody SubmitRegisterRequest request) { return attendance.submit(user, id, request); }

    @PostMapping("/registers/{id}/leave-review")
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.record')")
    AttendanceMutationResponse reviewLeave(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DraftLeaveReviewRequest request) {
        return attendance.reviewDraftLeave(user,id,request);
    }

    @PostMapping("/corrections") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.record')")
    AttendanceMutationResponse requestCorrection(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CorrectionRequest request) { return attendance.requestCorrection(user, request); }
    @PostMapping("/corrections/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.correct')")
    AttendanceMutationResponse decideCorrection(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody CorrectionDecisionRequest request) {
        return attendance.decideCorrection(user, id, request);
    }

    @PostMapping("/explanations/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_attendance.correct')")
    AttendanceMutationResponse decideExplanation(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ExplanationDecisionRequest request) {
        return attendance.decideExplanation(user, id, request);
    }

    public record PolicyRequest(@NotNull UUID schoolId, @NotNull UUID sessionId,
            @NotBlank @Size(max=120) String name, @NotBlank String registerMode,
            @NotEmpty @Size(max=12) List<@NotBlank String> enabledStates, LocalTime submissionCutoff,
            @NotEmpty @Size(max=12) Map<@NotBlank String, @DecimalMin("0") @DecimalMax("1") BigDecimal> stateWeights,
            @Min(0) @Max(720) int lockAfterHours, @Min(0) @Max(90) int guardianExplanationDays,
            @DecimalMin("0") @DecimalMax("100") BigDecimal warningThresholdPercent,
            @NotBlank String status, @Min(0) Long expectedVersion, @Size(max=240) String reason) {}
    public record CalendarDayRequest(@NotNull UUID schoolId, @NotNull UUID campusId,
            @NotNull UUID sessionId, @NotNull LocalDate date, @NotBlank String dayType,
            @NotBlank @Size(max=120) String name, boolean attendanceRequired, @NotBlank String status,
            @Min(0) Long expectedVersion, @Size(max=240) String reason) {}
    public record OpenRegisterRequest(@NotNull UUID schoolId, @NotNull UUID sectionId,
            @NotNull UUID calendarDayId, UUID periodId, @NotBlank String mode) {}
    public record MarkInput(@NotNull UUID learnerId, @NotBlank String stateCode, @Size(max=240) String note,
            @Min(0) Long expectedVersion) {}
    public record RecordMarksRequest(@NotNull UUID schoolId, @Min(0) long expectedRegisterVersion,
            @NotEmpty @Size(max=500) List<@Valid MarkInput> marks, @Size(max=240) String reason) {}
    public record SubmitRegisterRequest(@NotNull UUID schoolId, @Min(0) long expectedVersion,
            @NotBlank @Size(max=240) String reason) {}
    public record CorrectionRequest(@NotNull UUID schoolId, @NotNull UUID markId,
            @NotBlank String requestedState, @NotBlank @Size(max=240) String reason,
            @Valid LeaveCorrectionSource leaveSource) {
        public CorrectionRequest(UUID schoolId, UUID markId, String requestedState, String reason) {
            this(schoolId,markId,requestedState,reason,null);
        }
    }
    public record LeaveCorrectionSource(@NotNull UUID requestId, @NotNull @Min(1) Long requestVersion,
            @NotNull @Min(0) Long expectedMarkVersion) {}
    public record DraftLeaveReviewRequest(@NotNull UUID schoolId, @NotNull UUID operationId,
            @NotNull UUID markId, @NotNull UUID leaveRequestId, @Min(1) long leaveRequestVersion,
            @Min(0) long expectedRegisterVersion, @Min(0) long expectedMarkVersion,
            @NotBlank @Pattern(regexp="KEEP_RECORDED|APPLY_APPROVED") String decision,
            @NotBlank @Size(min=3,max=240) String reason) {}
    public record CorrectionDecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @NotBlank @Size(max=240) String reason, @Min(0) long expectedVersion) {}
    public record ExplanationDecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @NotBlank @Size(max=240) String reason, @Min(0) long expectedVersion) {}
}

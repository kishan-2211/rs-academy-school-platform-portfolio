package com.educore.api.workforce;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/v1/workforce")
public class WorkforceController {
    private final WorkforceService workforce;

    public WorkforceController(WorkforceService workforce) {
        this.workforce = workforce;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('CAPABILITY_workforce.self','CAPABILITY_workforce.read')")
    WorkforceSnapshot snapshot(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam(required = false) LocalDate date) {
        return workforce.snapshot(user, schoolId, date);
    }

    @PostMapping("/attendance-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.configure')")
    WorkforceMutationResponse createPolicy(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PolicyRequest request) {
        return workforce.createPolicy(user, request);
    }

    @PutMapping("/attendance-policies/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.configure')")
    WorkforceMutationResponse updatePolicy(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody PolicyRequest request) {
        return workforce.updatePolicy(user, id, request);
    }

    @PostMapping("/leave-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.configure')")
    WorkforceMutationResponse createLeaveType(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody LeaveTypeRequest request) {
        return workforce.createLeaveType(user, request);
    }

    @PutMapping("/leave-types/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.configure')")
    WorkforceMutationResponse updateLeaveType(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody LeaveTypeRequest request) {
        return workforce.updateLeaveType(user, id, request);
    }

    @PostMapping("/leave-balances")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.configure')")
    WorkforceMutationResponse adjustLeaveBalance(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody LeaveBalanceRequest request) {
        return workforce.adjustLeaveBalance(user, request);
    }

    @PostMapping("/attendance-registers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.attendance.record')")
    WorkforceMutationResponse openRegister(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody OpenRegisterRequest request) {
        return workforce.openRegister(user, request);
    }

    @PutMapping("/attendance-registers/{id}/marks")
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.attendance.record')")
    WorkforceMutationResponse recordMarks(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RecordMarksRequest request) {
        return workforce.recordMarks(user, id, request);
    }

    @PostMapping("/attendance-registers/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.attendance.record')")
    WorkforceMutationResponse submitRegister(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReasonRequest request) {
        return workforce.submitRegister(user, id, request);
    }

    @PostMapping("/attendance-corrections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('CAPABILITY_workforce.self','CAPABILITY_workforce.attendance.record')")
    WorkforceMutationResponse requestCorrection(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CorrectionRequest request) {
        return workforce.requestCorrection(user, request);
    }

    @PostMapping("/attendance-corrections/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.attendance.correct')")
    WorkforceMutationResponse decideCorrection(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return workforce.decideCorrection(user, id, request);
    }

    @PostMapping("/leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.self')")
    WorkforceMutationResponse requestLeave(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody LeaveRequest request) {
        return workforce.requestLeave(user, request);
    }

    @PostMapping("/leave-requests/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_workforce.leave.approve')")
    WorkforceMutationResponse decideLeave(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return workforce.decideLeave(user, id, request);
    }

    public record PolicyRequest(@NotNull UUID schoolId, @NotNull UUID sessionId,
            @NotNull UUID campusId, @NotBlank @Size(max = 120) String name,
            @NotEmpty @Size(max = 8) List<@NotBlank String> enabledStates,
            @NotEmpty @Size(max = 8) Map<@NotBlank String,
                    @DecimalMin("0") @DecimalMax("1") BigDecimal> stateWeights,
            @Min(0) @Max(720) int lockAfterHours,
            @DecimalMin("0") @DecimalMax("100") BigDecimal warningThresholdPercent,
            @Min(1) @Max(30) int consecutiveAbsenceThreshold,
            @NotBlank String status, @Min(0) Long expectedVersion,
            @Size(max = 500) String reason) {}

    public record LeaveTypeRequest(@NotNull UUID schoolId, @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name, boolean paid, boolean balanceRequired,
            @NotNull @DecimalMin("0.5") @DecimalMax("366") BigDecimal maxDaysPerRequest,
            @NotBlank String status, @Min(0) Long expectedVersion,
            @Size(max = 500) String reason) {}

    public record LeaveBalanceRequest(@NotNull UUID schoolId, @NotNull UUID sessionId,
            @NotNull UUID staffProfileId, @NotNull UUID leaveTypeId,
            @NotBlank String entryKind, @NotNull @DecimalMin("-366") @DecimalMax("366") BigDecimal quantity,
            @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
            @NotBlank @Size(max = 500) String reason) {}

    public record OpenRegisterRequest(@NotNull UUID schoolId, @NotNull UUID calendarDayId) {}

    public record MarkInput(@NotNull UUID staffProfileId, @NotBlank String stateCode,
            LocalTime checkInTime, LocalTime checkOutTime, @Size(max = 240) String note,
            @Min(0) Long expectedVersion) {}

    public record RecordMarksRequest(@NotNull UUID schoolId, @Min(0) long expectedRegisterVersion,
            @NotEmpty @Size(max = 500) List<@Valid MarkInput> marks,
            @NotBlank @Size(max = 500) String reason) {}

    public record VersionedReasonRequest(@NotNull UUID schoolId, @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record CorrectionRequest(@NotNull UUID schoolId, @NotNull UUID markId,
            @NotBlank String requestedState, LocalTime requestedCheckInTime,
            LocalTime requestedCheckOutTime, @NotBlank @Size(max = 500) String reason) {}

    public record LeaveRequest(@NotNull UUID schoolId, @NotNull UUID sessionId,
            @NotNull UUID leaveTypeId, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn,
            @NotBlank String duration, @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
            @NotBlank @Size(max = 1000) String reason) {}

    public record DecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @NotBlank @Size(max = 500) String reason, @Min(0) long expectedVersion) {}
}

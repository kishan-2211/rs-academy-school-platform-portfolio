package com.educore.api.academics;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/academic-rollovers")
public class AcademicRolloverController {

    private final AcademicRolloverService rollovers;

    public AcademicRolloverController(AcademicRolloverService rollovers) {
        this.rollovers = rollovers;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_academic.rollover.manage')")
    RolloverWorkspace workspace(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return rollovers.workspace(user, schoolId);
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('CAPABILITY_academic.rollover.manage')")
    RolloverPreview preview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RolloverPreviewRequest request) {
        return rollovers.preview(user, request);
    }

    @PostMapping("/execute")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academic.rollover.manage')")
    RolloverExecution execute(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RolloverExecutionRequest request) {
        return rollovers.execute(user, request);
    }

    public record RolloverPreviewRequest(
            @NotNull UUID schoolId,
            @NotNull UUID sourceSessionId,
            @NotBlank @Size(max = 80) String targetSessionName,
            @NotNull LocalDate targetStartsOn,
            @NotNull LocalDate targetEndsOn,
            boolean copyTeachingAllocations,
            boolean copyPublishedTimetable,
            boolean copyFeePlans,
            boolean copyCalendarPolicy,
            @NotEmpty @Size(max = 2000) List<@Valid StudentRolloverDecisionRequest> studentDecisions) {}

    public record StudentRolloverDecisionRequest(
            @NotNull UUID sourceEnrolmentId,
            @NotBlank @Size(max = 20) String outcome,
            @Size(max = 40) String targetGradeCode,
            @Size(max = 30) String targetSectionCode,
            @Size(max = 30) String targetRollNumber,
            @Size(max = 20) String leaveStatus,
            @NotBlank @Size(min = 3, max = 240) String reason) {}

    public record RolloverExecutionRequest(
            @NotNull @Valid RolloverPreviewRequest preview,
            @NotBlank @Size(min = 16, max = 100) String idempotencyKey,
            @NotBlank @Size(min = 64, max = 64) String snapshotHash,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record RolloverWorkspace(
            UUID schoolId,
            String schoolName,
            List<RolloverSessionOption> sessions,
            List<RolloverSectionOption> sections,
            List<RolloverStudentOption> students,
            List<RolloverBatchItem> history) {}

    public record RolloverSessionOption(
            UUID id, String name, LocalDate startsOn, LocalDate endsOn, String status, long version) {}

    public record RolloverSectionOption(
            UUID id, UUID campusId, UUID sessionId, String gradeCode, String code,
            String name, Integer capacity, String status) {}

    public record RolloverStudentOption(
            UUID sourceEnrolmentId,
            UUID learnerId,
            String learnerName,
            UUID academicSessionId,
            UUID campusId,
            String admissionNumber,
            String gradeCode,
            UUID sourcePlacementId,
            UUID sourceSectionId,
            String sourceSectionCode,
            String rollNumber,
            long enrolmentVersion,
            Long placementVersion) {}

    public record RolloverBatchItem(
            UUID id,
            UUID sourceSessionId,
            String sourceSessionName,
            UUID targetSessionId,
            String targetSessionName,
            int promotedCount,
            int retainedCount,
            int leavingCount,
            Instant executedAt,
            String status,
            long version) {}

    public record RolloverCopyCounts(
            int students,
            int promoted,
            int retained,
            int leaving,
            int sections,
            int terms,
            int resources,
            int offerings,
            int allocations,
            int periods,
            int timetablePlans,
            int timetableEntries,
            int feePlans,
            int feeItems,
            int calendarPolicies) {}

    public record RolloverStudentPreview(
            UUID sourceEnrolmentId,
            UUID learnerId,
            String learnerName,
            String admissionNumber,
            String sourceGradeCode,
            String sourceSectionCode,
            String outcome,
            String targetGradeCode,
            String targetSectionCode,
            String targetRollNumber,
            String leaveStatus,
            String reason) {}

    public record RolloverPreview(
            UUID schoolId,
            UUID sourceSessionId,
            String sourceSessionName,
            String targetSessionName,
            LocalDate targetStartsOn,
            LocalDate targetEndsOn,
            String snapshotHash,
            RolloverCopyCounts counts,
            List<String> warnings,
            List<RolloverStudentPreview> students) {}

    public record RolloverExecution(
            UUID batchId,
            UUID sourceSessionId,
            UUID targetSessionId,
            String targetSessionName,
            String status,
            String snapshotHash,
            RolloverCopyCounts counts,
            List<String> warnings,
            Instant executedAt,
            long version) {}
}

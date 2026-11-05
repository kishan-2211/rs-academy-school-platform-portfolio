package com.educore.api.assessment;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assessment")
public class AssessmentController {

    private final AssessmentService assessment;

    public AssessmentController(AssessmentService assessment) {
        this.assessment = assessment;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.read')")
    AssessmentSnapshot snapshot(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return assessment.snapshot(user, schoolId);
    }

    @PostMapping("/grading-scales")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.configure')")
    AssessmentMutationResponse createScale(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ScaleRequest request) {
        return assessment.createScale(user, request);
    }

    @PutMapping("/grading-scales/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.configure')")
    AssessmentMutationResponse updateScale(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ScaleRequest request) {
        return assessment.updateScale(user, id, request);
    }

    @PostMapping("/grade-bands")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.configure')")
    AssessmentMutationResponse createBand(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody BandRequest request) {
        return assessment.createBand(user, request);
    }

    @PutMapping("/grade-bands/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.configure')")
    AssessmentMutationResponse updateBand(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody BandRequest request) {
        return assessment.updateBand(user, id, request);
    }

    @PostMapping("/schemes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.configure')")
    AssessmentMutationResponse createScheme(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SchemeRequest request) {
        return assessment.createScheme(user, request);
    }

    @PutMapping("/schemes/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.configure')")
    AssessmentMutationResponse updateScheme(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody SchemeRequest request) {
        return assessment.updateScheme(user, id, request);
    }

    @PostMapping("/schemes/{id}/activate")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.configure')")
    AssessmentMutationResponse activateScheme(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return assessment.activateScheme(user, id, request);
    }

    @PostMapping("/components")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.configure')")
    AssessmentMutationResponse createComponent(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ComponentRequest request) {
        return assessment.createComponent(user, request);
    }

    @PutMapping("/components/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.configure')")
    AssessmentMutationResponse updateComponent(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ComponentRequest request) {
        return assessment.updateComponent(user, id, request);
    }

    @PostMapping("/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.record')")
    AssessmentMutationResponse createAssessment(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateAssessmentRequest request) {
        return assessment.createAssessment(user, request);
    }

    @PostMapping("/assessments/{id}/open")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.record')")
    AssessmentMutationResponse openAssessment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return assessment.openAssessment(user, id, request);
    }

    @PutMapping("/assessments/{id}/marks")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.record')")
    AssessmentMutationResponse recordMarks(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RecordMarksRequest request) {
        return assessment.recordMarks(user, id, request);
    }

    @PostMapping("/assessments/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.record')")
    AssessmentMutationResponse submitAssessment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return assessment.submitAssessment(user, id, request);
    }

    @PostMapping("/assessments/{id}/validate")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.publish')")
    AssessmentMutationResponse validateAssessment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return assessment.validateAssessment(user, id, request);
    }

    @PostMapping("/assessments/{id}/publish")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.publish')")
    AssessmentMutationResponse publishAssessment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return assessment.publishAssessment(user, id, request);
    }

    @PostMapping("/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.record')")
    AssessmentMutationResponse requestCorrection(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CorrectionRequest request) {
        return assessment.requestCorrection(user, request);
    }

    @PostMapping("/corrections/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.publish')")
    AssessmentMutationResponse decideCorrection(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return assessment.decideCorrection(user, id, request);
    }

    @PostMapping("/publications")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.publish')")
    AssessmentMutationResponse publishReportCards(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PublicationRequest request) {
        return assessment.publishReportCards(user, request);
    }

    @PostMapping("/publications/{id}/revoke")
    @PreAuthorize("hasAuthority('CAPABILITY_assessment.publish')")
    AssessmentMutationResponse revokePublication(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RevokePublicationRequest request) {
        return assessment.revokePublication(user, id, request);
    }

    public record ScaleRequest(
            @NotNull UUID schoolId, @NotNull UUID academicSessionId,
            @NotBlank @Size(max = 120) String name, @NotBlank String status,
            @Min(0) Long expectedVersion, @Size(max = 240) String reason) {}

    public record BandRequest(
            @NotNull UUID schoolId, @NotNull UUID gradingScaleId,
            @NotBlank @Size(max = 20) String gradeCode, @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal minimumPercentage,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal maximumPercentage,
            @DecimalMin("0") @DecimalMax("100") BigDecimal gradePoint,
            @NotBlank String outcome, @Size(max = 240) String descriptor,
            @Min(0) @Max(1000) int displayOrder, @Min(0) Long expectedVersion,
            @Size(max = 240) String reason) {}

    public record SchemeRequest(
            @NotNull UUID schoolId, @NotNull UUID academicSessionId, UUID gradingScaleId,
            @NotBlank @Size(max = 40) String gradeCode, @NotBlank @Size(max = 120) String name,
            @NotBlank String aggregationMode, @NotBlank String roundingMode,
            @Min(0) @Max(2) int decimalPlaces, @NotBlank String passRule,
            @DecimalMin("0") @DecimalMax("100") BigDecimal overallPassPercentage,
            @Min(0) Long expectedVersion, @Size(max = 240) String reason) {}

    public record ComponentRequest(
            @NotNull UUID schoolId, @NotNull UUID schemeId,
            @NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 120) String name,
            @NotBlank String scoringMode,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal weightPercentage,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000") BigDecimal defaultMaxMarks,
            @DecimalMin("0") @DecimalMax("100") BigDecimal passPercentage,
            @Min(0) @Max(1000) int displayOrder, @NotBlank String status,
            @Min(0) Long expectedVersion, @Size(max = 240) String reason) {}

    public record CreateAssessmentRequest(
            @NotNull UUID schoolId, @NotNull UUID academicTermId,
            @NotNull UUID offeringId, @NotNull UUID componentId,
            @NotBlank @Size(max = 160) String name, @NotNull LocalDate assessmentDate,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000") BigDecimal maximumMarks) {}

    public record MarkInput(
            @NotNull UUID learnerId, @NotBlank String evidenceState,
            @DecimalMin("0") @DecimalMax("10000") BigDecimal marksAwarded,
            @Size(max = 20) String gradeCode, @Size(max = 1000) String narrative,
            @Size(max = 500) String teacherRemark, @Min(0) Long expectedVersion) {}

    public record RecordMarksRequest(
            @NotNull UUID schoolId, @Min(0) long expectedAssessmentVersion,
            @NotEmpty @Size(max = 500) List<@Valid MarkInput> marks,
            @NotBlank @Size(max = 240) String reason) {}

    public record TransitionRequest(
            @NotNull UUID schoolId, @Min(0) long expectedVersion,
            @NotBlank @Size(max = 240) String reason) {}

    public record CorrectionRequest(
            @NotNull UUID schoolId, @NotNull UUID markId, @NotBlank String evidenceState,
            @DecimalMin("0") @DecimalMax("10000") BigDecimal marksAwarded,
            @Size(max = 20) String gradeCode, @Size(max = 1000) String narrative,
            @Size(max = 500) String teacherRemark,
            @NotBlank @Size(max = 240) String reason) {}

    public record DecisionRequest(
            @NotNull UUID schoolId, @NotBlank String decision,
            @NotBlank @Size(max = 240) String reason, @Min(0) long expectedVersion) {}

    public record PublicationRequest(
            @NotNull UUID schoolId, @NotNull UUID academicTermId,
            @NotNull UUID sectionId, @NotNull UUID schemeId,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 240) String reason) {}

    public record RevokePublicationRequest(
            @NotNull UUID schoolId, @NotBlank @Size(max = 240) String reason) {}
}

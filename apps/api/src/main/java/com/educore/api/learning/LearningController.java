package com.educore.api.learning;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {

    private final LearningService learning;

    public LearningController(LearningService learning) {
        this.learning = learning;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_learning.read')")
    LearningSnapshot snapshot(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return learning.snapshot(user, schoolId);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_learning.moderate')")
    LearningMutationResponse createCategory(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CategoryRequest request) {
        return learning.createCategory(user, request);
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_learning.moderate')")
    LearningMutationResponse updateCategory(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody CategoryRequest request) {
        return learning.updateCategory(user, id, request);
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_learning.manage')")
    LearningMutationResponse createAssignment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AssignmentRequest request) {
        return learning.createAssignment(user, request);
    }

    @PutMapping("/assignments/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_learning.manage')")
    LearningMutationResponse updateAssignment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody AssignmentRequest request) {
        return learning.updateAssignment(user, id, request);
    }

    @PostMapping("/assignments/{id}/publish")
    @PreAuthorize("hasAuthority('CAPABILITY_learning.manage')")
    LearningMutationResponse publishAssignment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody TransitionRequest request) {
        return learning.publishAssignment(user, id, request);
    }

    @PostMapping("/assignments/{id}/close")
    @PreAuthorize("hasAuthority('CAPABILITY_learning.manage')")
    LearningMutationResponse closeAssignment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody TransitionRequest request) {
        return learning.closeAssignment(user, id, request);
    }

    @PostMapping("/submissions/{id}/review")
    @PreAuthorize("hasAuthority('CAPABILITY_learning.manage')")
    LearningMutationResponse reviewSubmission(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody ReviewRequest request) {
        return learning.reviewSubmission(user, id, request);
    }

    public record CategoryRequest(
            @NotNull UUID schoolId,
            @NotNull UUID academicSessionId,
            @NotBlank @Size(min = 2, max = 30)
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,29}$",
                    message = "Use 2–30 letters, numbers or underscores, starting with a letter") String code,
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "^#[A-Fa-f0-9]{6}$") String accentColor,
            @Min(0) @Max(1000) int displayOrder,
            @NotBlank String status,
            @Min(0) Long expectedVersion,
            @Size(max = 240) String reason) {}

    public record AssignmentRequest(
            @NotNull UUID schoolId,
            @NotNull UUID academicTermId,
            @NotNull UUID offeringId,
            UUID categoryId,
            @NotBlank @Size(max = 180) String title,
            @NotBlank @Size(max = 6000) String instructions,
            @Size(max = 120) String resourceLabel,
            @Size(max = 1000) String resourceUrl,
            @NotNull Instant availableFrom,
            @NotNull Instant dueAt,
            @NotBlank String submissionMode,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000") BigDecimal maximumPoints,
            boolean allowAttachments,
            @Min(0) @Max(10) int maxAttachmentCount,
            boolean attachmentRequired,
            boolean allowLate,
            Instant lateUntil,
            @Min(0) Long expectedVersion,
            @Size(max = 240) String reason) {}

    public record TransitionRequest(
            @NotNull UUID schoolId,
            @Min(0) long expectedVersion,
            @NotBlank @Size(max = 240) String reason) {}

    public record ReviewRequest(
            @NotNull UUID schoolId,
            @NotBlank String decision,
            @Size(max = 3000) String feedback,
            @DecimalMin("0") @DecimalMax("10000") BigDecimal pointsAwarded,
            @Min(0) long expectedVersion,
            @NotBlank @Size(max = 240) String reason) {}
}

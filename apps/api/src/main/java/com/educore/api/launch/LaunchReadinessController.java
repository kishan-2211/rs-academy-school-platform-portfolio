package com.educore.api.launch;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/v1/launch-readiness")
public class LaunchReadinessController {
    private static final String READ =
            "hasAnyAuthority('CAPABILITY_launch.configure','CAPABILITY_launch.approve')";

    private final LaunchReadinessService readiness;

    public LaunchReadinessController(LaunchReadinessService readiness) {
        this.readiness = readiness;
    }

    @GetMapping
    @PreAuthorize(READ)
    LaunchReadinessSnapshot snapshot(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return readiness.snapshot(user, schoolId);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_launch.configure')")
    LaunchReadinessMutationResponse createItem(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateItemRequest request) {
        return readiness.createItem(user, request);
    }

    @PutMapping("/items/{itemId}")
    @PreAuthorize("hasAuthority('CAPABILITY_launch.configure')")
    LaunchReadinessMutationResponse reviseItem(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID itemId,
            @Valid @RequestBody ReviseItemRequest request) {
        return readiness.reviseItem(user, itemId, request);
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_launch.configure')")
    LaunchReadinessMutationResponse submitReview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SubmitReviewRequest request) {
        return readiness.submitReview(user, request);
    }

    @PostMapping("/reviews/{reviewId}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_launch.approve')")
    LaunchReadinessMutationResponse decideReview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID reviewId,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return readiness.decideReview(user, reviewId, request);
    }

    public record CreateItemRequest(
            @NotNull UUID schoolId,
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_ ]{2,59}$") String controlCode,
            @NotBlank String category,
            @NotBlank @Size(max = 180) String title,
            @NotBlank @Size(max = 1000) String guidance,
            boolean required,
            @NotBlank String status,
            @Size(max = 500) String evidenceReference,
            @Size(max = 120) String ownerRoleLabel,
            @Size(max = 1000) String reviewNote,
            @NotBlank @Size(max = 500) String reason) {}

    public record ReviseItemRequest(
            @NotNull UUID schoolId,
            @NotBlank String category,
            @NotBlank @Size(max = 180) String title,
            @NotBlank @Size(max = 1000) String guidance,
            boolean required,
            @NotBlank String status,
            @Size(max = 500) String evidenceReference,
            @Size(max = 120) String ownerRoleLabel,
            @Size(max = 1000) String reviewNote,
            @Min(1) int expectedRevision,
            @NotBlank @Size(max = 500) String reason) {}

    public record SubmitReviewRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 1000) String submissionStatement,
            @AssertTrue boolean evidenceConfirmed,
            @NotBlank @Size(max = 500) String reason) {}

    public record ReviewDecisionRequest(
            @NotNull UUID schoolId,
            @NotBlank @Pattern(regexp = "^(?i:APPROVED|REJECTED)$") String decision,
            @NotBlank @Size(max = 1000) String reason,
            @Min(0) long expectedVersion) {}
}

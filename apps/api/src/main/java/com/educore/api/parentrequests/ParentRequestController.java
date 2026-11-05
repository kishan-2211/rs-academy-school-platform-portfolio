package com.educore.api.parentrequests;

import static com.educore.api.parentrequests.ParentRequestModels.*;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/parent-requests")
@PreAuthorize("hasAnyAuthority('CAPABILITY_parent_requests.read','CAPABILITY_parent_requests.respond','CAPABILITY_parent_requests.manage')")
public class ParentRequestController {
    private final ParentRequestService requests;

    public ParentRequestController(ParentRequestService requests) {
        this.requests = requests;
    }

    @GetMapping
    Workspace workspace(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search) {
        return requests.workspace(user, schoolId, status, category, search);
    }

    @GetMapping("/{requestId}")
    Detail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID requestId,
            @RequestParam(required = false) UUID schoolId) {
        return requests.staffDetail(user, schoolId, requestId);
    }

    @PostMapping("/{requestId}/assignment")
    @PreAuthorize("hasAuthority('CAPABILITY_parent_requests.manage')")
    Mutation assign(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID requestId,
            @Valid @RequestBody AssignmentRequest request) {
        return requests.assign(user, requestId, request);
    }

    @PostMapping("/{requestId}/messages")
    Mutation reply(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID requestId,
            @Valid @RequestBody StaffReplyRequest request) {
        return requests.staffReply(user, requestId, request);
    }

    @PostMapping("/{requestId}/transitions")
    Mutation transition(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID requestId,
            @Valid @RequestBody TransitionRequest request) {
        return requests.transition(user, requestId, request);
    }

    public record AssignmentRequest(
            @NotNull UUID schoolId,
            @NotNull UUID staffProfileId,
            @NotBlank String priority,
            @Min(0) long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record StaffReplyRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 8000) String message,
            boolean internalNote,
            @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
            @Min(0) long expectedVersion) {}

    public record TransitionRequest(
            @NotNull UUID schoolId,
            @NotBlank String action,
            @Min(0) long expectedVersion,
            @NotBlank @Size(min = 3, max = 1000) String reason) {}
}

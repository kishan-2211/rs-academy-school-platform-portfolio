package com.educore.api.parentrequests;

import static com.educore.api.parentrequests.ParentRequestModels.*;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/family/children/{learnerId}/requests")
@PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
public class FamilyParentRequestController {
    private final ParentRequestService requests;

    public FamilyParentRequestController(ParentRequestService requests) {
        this.requests = requests;
    }

    @GetMapping
    FamilyList list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId) {
        return requests.familyList(user, learnerId);
    }

    @GetMapping("/{requestId}")
    Detail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId,
            @PathVariable UUID requestId) {
        return requests.familyDetail(user, learnerId, requestId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Mutation open(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId,
            @Valid @RequestBody OpenRequest request) {
        return requests.open(user, learnerId, request);
    }

    @PostMapping("/{requestId}/messages")
    Mutation reply(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId,
            @PathVariable UUID requestId, @Valid @RequestBody ReplyRequest request) {
        return requests.familyReply(user, learnerId, requestId, request);
    }

    public record OpenRequest(
            @NotBlank String category,
            @NotBlank @Size(min = 3, max = 180) String subject,
            @NotBlank @Size(max = 8000) String message,
            @NotBlank @Size(min = 8, max = 100) String idempotencyKey) {}

    public record ReplyRequest(
            @NotBlank @Size(max = 8000) String message,
            @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
            @Min(0) long expectedVersion) {}
}

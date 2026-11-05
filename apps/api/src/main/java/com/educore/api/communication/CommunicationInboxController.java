package com.educore.api.communication;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/communications/inbox")
public class CommunicationInboxController {
    private final CommunicationInboxService inbox;

    public CommunicationInboxController(CommunicationInboxService inbox) { this.inbox = inbox; }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_communication.read')")
    CommunicationInboxResponse inbox(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return inbox.staffInbox(user, schoolId);
    }

    @PostMapping("/{recipientId}/read")
    @PreAuthorize("hasAuthority('CAPABILITY_communication.read')")
    CommunicationMutationResponse read(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID recipientId, @Valid @RequestBody InboxAction request) {
        return inbox.readStaff(user, recipientId, request.schoolId());
    }

    @PostMapping("/{recipientId}/acknowledge")
    @PreAuthorize("hasAuthority('CAPABILITY_communication.read')")
    CommunicationMutationResponse acknowledge(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID recipientId, @Valid @RequestBody InboxAction request) {
        return inbox.acknowledgeStaff(user, recipientId, request.schoolId());
    }

    public record InboxAction(@NotNull UUID schoolId) {}
}

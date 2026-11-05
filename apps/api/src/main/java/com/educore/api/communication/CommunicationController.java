package com.educore.api.communication;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/communications")
public class CommunicationController {
    private final CommunicationService communications;

    public CommunicationController(CommunicationService communications) {
        this.communications = communications;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('CAPABILITY_communication.send','CAPABILITY_communication.configure','CAPABILITY_communication.approve')")
    CommunicationSnapshot snapshot(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return communications.snapshot(user, schoolId);
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_communication.configure')")
    CommunicationMutationResponse createTemplate(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody TemplateRequest request) {
        return communications.createTemplate(user, request);
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_communication.configure')")
    CommunicationMutationResponse updateTemplate(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody TemplateRequest request) {
        return communications.updateTemplate(user, id, request);
    }

    @PostMapping("/templates/{id}/activate")
    @PreAuthorize("hasAuthority('CAPABILITY_communication.configure')")
    CommunicationMutationResponse activateTemplate(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReason request) {
        return communications.activateTemplate(user, id, request);
    }

    @PostMapping("/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_communication.send')")
    CommunicationMutationResponse createAnnouncement(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AnnouncementRequest request) {
        return communications.createAnnouncement(user, request);
    }

    @PutMapping("/announcements/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_communication.send')")
    CommunicationMutationResponse updateAnnouncement(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody AnnouncementRequest request) {
        return communications.updateAnnouncement(user, id, request);
    }

    @PostMapping("/announcements/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_communication.send')")
    CommunicationMutationResponse submitAnnouncement(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReason request) {
        return communications.submitAnnouncement(user, id, request);
    }

    @PostMapping("/announcements/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_communication.approve')")
    CommunicationMutationResponse decideAnnouncement(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return communications.decideAnnouncement(user, id, request);
    }

    @PostMapping("/announcements/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('CAPABILITY_communication.send','CAPABILITY_communication.approve')")
    CommunicationMutationResponse cancelAnnouncement(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReason request) {
        return communications.cancelAnnouncement(user, id, request);
    }

    public record TemplateRequest(
            @NotNull UUID schoolId,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_ ]{1,39}") String code,
            @Min(1) @Max(1000) int revisionNumber,
            UUID supersedesTemplateId,
            @NotBlank @Size(max = 140) String name,
            @NotBlank String category,
            @NotBlank String languageMode,
            @Size(max = 180) String titleEn,
            @Size(max = 180) String titleHi,
            @Size(max = 8000) String bodyEn,
            @Size(max = 8000) String bodyHi,
            boolean requiresAcknowledgement,
            boolean email,
            boolean sms,
            boolean whatsapp,
            boolean push,
            @Min(0) Long expectedVersion,
            @Size(max = 500) String reason) {}

    public record AnnouncementRequest(
            @NotNull UUID schoolId,
            UUID templateId,
            @NotBlank String category,
            @NotBlank String priority,
            @NotBlank String audienceKind,
            UUID audienceReferenceId,
            @NotBlank String languageMode,
            @Size(max = 180) String titleEn,
            @Size(max = 180) String titleHi,
            @Size(max = 8000) String bodyEn,
            @Size(max = 8000) String bodyHi,
            boolean requiresAcknowledgement,
            boolean email,
            boolean sms,
            boolean whatsapp,
            boolean push,
            @NotNull Instant scheduledFor,
            Instant expiresAt,
            @Min(0) Long expectedVersion,
            @Size(max = 500) String reason) {}

    public record VersionedReason(
            @NotNull UUID schoolId,
            @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record DecisionRequest(
            @NotNull UUID schoolId,
            @NotBlank String decision,
            @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}
}

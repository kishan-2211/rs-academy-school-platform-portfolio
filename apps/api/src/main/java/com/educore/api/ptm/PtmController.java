package com.educore.api.ptm;

import static com.educore.api.ptm.PtmModels.*;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ptm")
@PreAuthorize("hasAnyAuthority('CAPABILITY_ptm.read','CAPABILITY_ptm.manage')")
public class PtmController {
    private final PtmService ptm;

    public PtmController(PtmService ptm) { this.ptm = ptm; }

    @GetMapping
    Workspace workspace(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required=false) UUID schoolId) {
        return ptm.workspace(user, schoolId);
    }

    @PostMapping("/events")
    @PreAuthorize("hasAuthority('CAPABILITY_ptm.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    Mutation create(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateEventRequest request) {
        return ptm.create(user, request);
    }

    @PostMapping("/events/{eventId}/publish")
    @PreAuthorize("hasAuthority('CAPABILITY_ptm.manage')")
    Mutation publish(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID eventId,
            @Valid @RequestBody EventDecisionRequest request) {
        return ptm.publish(user, eventId, request);
    }

    @PostMapping("/events/{eventId}/cancel")
    @PreAuthorize("hasAuthority('CAPABILITY_ptm.manage')")
    Mutation cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID eventId,
            @Valid @RequestBody EventDecisionRequest request) {
        return ptm.cancelEvent(user, eventId, request);
    }

    public record CreateEventRequest(
            @NotNull UUID schoolId,
            @NotNull UUID academicSessionId,
            @NotNull UUID classSectionId,
            @NotNull UUID teacherMembershipId,
            @NotBlank @Size(min=3,max=160) String titleEn,
            @Size(max=160) String titleHi,
            @Size(max=1200) String instructionsEn,
            @Size(max=1200) String instructionsHi,
            @NotBlank @Size(min=2,max=160) String venue,
            @NotNull LocalDate eventDate,
            @NotNull Instant bookingOpensAt,
            @NotNull Instant bookingClosesAt,
            @NotNull Instant changesCloseAt,
            @Min(0) @Max(10080) int reminderMinutesBefore,
            @NotNull Instant firstSlotStartsAt,
            @Min(5) @Max(120) int slotDurationMinutes,
            @Min(0) @Max(60) int gapMinutes,
            @Min(1) @Max(100) int slotCount,
            @NotBlank @Size(min=8,max=100) String idempotencyKey) {}
    public record EventDecisionRequest(
            @NotNull UUID schoolId,
            @Min(0) long expectedVersion,
            @NotBlank @Size(min=3,max=500) String reason) {}
}

package com.educore.api.ptm;

import static com.educore.api.ptm.PtmModels.*;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/family/children/{learnerId}/ptm")
@PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
public class FamilyPtmController {
    private final PtmService ptm;

    public FamilyPtmController(PtmService ptm) { this.ptm = ptm; }

    @GetMapping
    FamilySchedule schedule(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId) {
        return ptm.familySchedule(user, learnerId);
    }

    @PostMapping("/slots/{slotId}/book")
    @ResponseStatus(HttpStatus.CREATED)
    Mutation book(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId,
            @PathVariable UUID slotId, @Valid @RequestBody BookRequest request) {
        return ptm.book(user, learnerId, slotId, request);
    }

    @PostMapping("/bookings/{bookingId}/reschedule")
    Mutation reschedule(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId,
            @PathVariable UUID bookingId, @Valid @RequestBody RescheduleRequest request) {
        return ptm.reschedule(user, learnerId, bookingId, request);
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    Mutation cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId,
            @PathVariable UUID bookingId, @Valid @RequestBody CancelBookingRequest request) {
        return ptm.cancelBooking(user, learnerId, bookingId, request);
    }

    public record BookRequest(
            @Size(max=500) String topic,
            @NotBlank @Size(min=8,max=100) String idempotencyKey) {}
    public record RescheduleRequest(
            @NotNull UUID slotId,
            @Min(0) long expectedVersion,
            @NotBlank @Size(min=3,max=500) String reason) {}
    public record CancelBookingRequest(
            @Min(0) long expectedVersion,
            @NotBlank @Size(min=3,max=500) String reason) {}
}

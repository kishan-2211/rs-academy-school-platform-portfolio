package com.educore.api.transport;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transport")
public class TransportController {
    private final TransportService transport;

    public TransportController(TransportService transport) { this.transport = transport; }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_transport.read')")
    TransportSnapshot snapshot(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return transport.snapshot(user, schoolId);
    }

    @PostMapping("/stops")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse createStop(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody StopRequest request) { return transport.createStop(user, request); }

    @PutMapping("/stops/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse updateStop(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody StopRequest request) {
        return transport.updateStop(user, id, request);
    }

    @PostMapping("/routes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse createRoute(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RouteRequest request) { return transport.createRoute(user, request); }

    @PutMapping("/routes/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse updateRoute(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RouteRequest request) {
        return transport.updateRoute(user, id, request);
    }

    @PostMapping("/route-revisions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse createRouteRevision(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RouteRevisionRequest request) {
        return transport.createRouteRevision(user, request);
    }

    @PutMapping("/route-revisions/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse updateRouteRevision(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RouteRevisionRequest request) {
        return transport.updateRouteRevision(user, id, request);
    }

    @PostMapping("/route-stops")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse createRouteStop(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RouteStopRequest request) {
        return transport.createRouteStop(user, request);
    }

    @PutMapping("/route-stops/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse updateRouteStop(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RouteStopRequest request) {
        return transport.updateRouteStop(user, id, request);
    }

    @PostMapping("/route-stops/{id}/remove")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse removeRouteStop(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReason request) {
        return transport.removeRouteStop(user, id, request);
    }

    @PostMapping("/route-revisions/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse submitRoute(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReason request) {
        return transport.submitRoute(user, id, request);
    }

    @PostMapping("/route-revisions/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.publish')")
    TransportMutationResponse decideRoute(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return transport.decideRoute(user, id, request);
    }

    @PostMapping("/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse createVehicle(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody VehicleRequest request) { return transport.createVehicle(user, request); }

    @PutMapping("/vehicles/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse updateVehicle(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VehicleRequest request) {
        return transport.updateVehicle(user, id, request);
    }

    @PostMapping("/compliance-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse createComplianceType(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ComplianceTypeRequest request) {
        return transport.createComplianceType(user, request);
    }

    @PutMapping("/compliance-types/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse updateComplianceType(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ComplianceTypeRequest request) {
        return transport.updateComplianceType(user, id, request);
    }

    @PostMapping("/compliance-checks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.safety')")
    TransportMutationResponse createComplianceCheck(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ComplianceCheckRequest request) {
        return transport.createComplianceCheck(user, request);
    }

    @PostMapping("/compliance-checks/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.safety')")
    TransportMutationResponse decideComplianceCheck(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ComplianceDecisionRequest request) {
        return transport.decideComplianceCheck(user, id, request);
    }

    @PostMapping("/duties")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse createDuty(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody DutyRequest request) { return transport.createDuty(user, request); }

    @PostMapping("/duties/{id}/activate")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse activateDuty(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReason request) {
        return transport.activateDuty(user, id, request);
    }

    @PostMapping("/duties/{id}/end")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse endDuty(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody EndRequest request) {
        return transport.endDuty(user, id, request);
    }

    @PostMapping("/learner-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse createLearnerAssignment(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody LearnerAssignmentRequest request) {
        return transport.createLearnerAssignment(user, request);
    }

    @PostMapping("/learner-assignments/{id}/activate")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse activateLearnerAssignment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReason request) {
        return transport.activateLearnerAssignment(user, id, request);
    }

    @PostMapping("/learner-assignments/{id}/end")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.configure')")
    TransportMutationResponse endLearnerAssignment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody EndRequest request) {
        return transport.endLearnerAssignment(user, id, request);
    }

    @PostMapping("/trips")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.operate')")
    TransportMutationResponse createTrip(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody TripRequest request) { return transport.createTrip(user, request); }

    @PostMapping("/trips/{id}/state")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.operate')")
    TransportMutationResponse changeTripState(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody TripStateRequest request) {
        return transport.changeTripState(user, id, request);
    }

    @PostMapping("/trip-events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.operate')")
    TransportMutationResponse recordTripEvent(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody TripEventRequest request) {
        return transport.recordTripEvent(user, request);
    }

    @PostMapping("/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_transport.safety')")
    TransportMutationResponse createIncident(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody IncidentRequest request) { return transport.createIncident(user, request); }

    @PostMapping("/incidents/{id}/resolve")
    @PreAuthorize("hasAuthority('CAPABILITY_transport.safety')")
    TransportMutationResponse resolveIncident(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ResolveIncidentRequest request) {
        return transport.resolveIncident(user, id, request);
    }

    public record StopRequest(@NotNull UUID schoolId, @NotNull UUID campusId,
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_ ]{1,39}$") String code,
            @NotBlank @Size(max = 140) String displayName,
            @NotBlank @Size(max = 140) String familyLabel, @Size(max = 180) String locality,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Size(max = 500) String accessibilityNote, @NotBlank String status,
            @Min(0) Long expectedVersion, @Size(max = 500) String reason) {}

    public record RouteRequest(@NotNull UUID schoolId, @NotNull UUID campusId,
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_ ]{1,39}$") String code,
            @NotBlank @Size(max = 140) String displayName, @Size(max = 500) String description,
            @NotBlank String status, @Min(0) Long expectedVersion, @Size(max = 500) String reason) {}

    public record RouteRevisionRequest(@NotNull UUID schoolId, @NotNull UUID routeId,
            @Min(1) @Max(1000) int revisionNumber, UUID supersedesRevisionId,
            @NotBlank String journeyKind, @NotBlank @Size(max = 140) String serviceLabel,
            @NotBlank @Size(max = 40) String operatingDays, @NotNull LocalDate effectiveFrom,
            LocalDate effectiveUntil, @Min(0) Long expectedVersion,
            @Size(max = 500) String reason) {}

    public record RouteStopRequest(@NotNull UUID schoolId, @NotNull UUID routeRevisionId,
            @NotNull UUID stopId, @Min(1) @Max(200) int sequenceNumber,
            @NotNull LocalTime scheduledTime, @Min(0) @Max(60) int boardingWindowMinutes,
            @Min(0) Long expectedVersion, @Size(max = 500) String reason) {}

    public record VehicleRequest(@NotNull UUID schoolId, @NotNull UUID campusId,
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_ ]{1,39}$") String vehicleCode,
            @NotBlank @Size(max = 120) String displayLabel,
            @NotBlank @Size(max = 30) String registrationNumber, @NotBlank String ownershipKind,
            @Size(max = 160) String vendorName, @Min(1) @Max(100) int learnerCapacity,
            boolean wheelchairAccessible, @Size(max = 160) String gpsProviderReference,
            boolean clearGpsProviderReference, @NotBlank String status,
            @Min(0) Long expectedVersion, @Size(max = 500) String reason) {}

    public record ComplianceTypeRequest(@NotNull UUID schoolId,
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_ ]{1,49}$") String code,
            @NotBlank @Size(max = 140) String name, @Size(max = 500) String description,
            @Min(1) @Max(240) Integer validityMonths, boolean requiredBeforeService,
            @NotBlank String status, @Min(0) Long expectedVersion, @Size(max = 500) String reason) {}

    public record ComplianceCheckRequest(@NotNull UUID schoolId, @NotNull UUID vehicleId,
            @NotNull UUID complianceTypeId, @Size(max = 160) String safeReference,
            @Size(max = 1000) String restrictedNote,
            @NotBlank @Size(max = 500) String reason) {}

    public record ComplianceDecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            LocalDate completedOn, LocalDate validUntil, @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record DutyRequest(@NotNull UUID schoolId, @NotNull UUID routeRevisionId,
            @NotNull UUID vehicleId, @NotNull UUID driverStaffId, @NotNull UUID attendantStaffId,
            @NotNull LocalDate startsOn, @NotBlank String status,
            @NotBlank @Size(max = 500) String reason) {}

    public record LearnerAssignmentRequest(@NotNull UUID schoolId, @NotNull UUID learnerId,
            @NotNull UUID routeRevisionId, @NotNull UUID routeStopId,
            @NotBlank String journeyKind, @NotNull LocalDate startsOn,
            @NotBlank String status, @NotBlank @Size(max = 500) String reason) {}

    public record EndRequest(@NotNull UUID schoolId, @NotNull LocalDate endDate,
            @Min(0) long expectedVersion, @NotBlank @Size(max = 500) String reason) {}

    public record TripRequest(@NotNull UUID schoolId, @NotNull UUID routeRevisionId,
            @NotNull UUID dutyId, @NotNull LocalDate serviceDate,
            @NotBlank @Size(max = 500) String reason) {}

    public record TripStateRequest(@NotNull UUID schoolId, @NotBlank String action,
            @Min(0) long expectedVersion, @NotBlank @Size(max = 500) String reason) {}

    public record TripEventRequest(@NotNull UUID schoolId, @NotNull UUID eventId,
            @NotNull UUID tripId, @NotNull UUID rosterEntryId, @NotBlank String eventType,
            Instant occurredAt, @Size(max = 500) String safeNote) {}

    public record IncidentRequest(@NotNull UUID schoolId, UUID tripId, UUID learnerId,
            @NotBlank String category, @NotBlank String severity,
            @NotBlank @Size(max = 300) String summary,
            @NotBlank @Size(max = 4000) String restrictedDetails,
            @Size(max = 2000) String immediateAction) {}

    public record ResolveIncidentRequest(@NotNull UUID schoolId, @Min(0) long expectedVersion,
            @NotBlank @Size(max = 2000) String resolutionSummary) {}

    public record VersionedReason(@NotNull UUID schoolId, @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record DecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @Min(0) long expectedVersion, @NotBlank @Size(max = 500) String reason) {}
}

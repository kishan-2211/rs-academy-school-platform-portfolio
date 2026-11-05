package com.educore.api.transport;

import static com.educore.api.transport.TransportController.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Validation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class TransportRequestTest {
    @Test
    void acceptsConfigurableNetworkReadinessRosterAndIncidentRequests() {
        UUID school = UUID.randomUUID();
        UUID campus = UUID.randomUUID();
        UUID route = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID stop = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        UUID duty = UUID.randomUUID();
        UUID learner = UUID.randomUUID();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new StopRequest(school, campus, "STOP_01", "Main market",
                    "Main market pickup point", "Synthetic locality", 25.32, 82.98,
                    "Synthetic accessible boarding note", "ACTIVE", null, "Synthetic setup"))).isEmpty();
            assertThat(validator.validate(new RouteRequest(school, campus, "ROUTE_01", "Morning route",
                    "Synthetic route", "ACTIVE", null, "Synthetic setup"))).isEmpty();
            assertThat(validator.validate(new RouteRevisionRequest(school, route, 1, null, "PICKUP",
                    "Morning pickup", "MON,TUE,WED,THU,FRI,SAT", LocalDate.of(2026, 8, 24),
                    null, null, "Synthetic revision"))).isEmpty();
            assertThat(validator.validate(new RouteStopRequest(school, revision, stop, 1,
                    LocalTime.of(7, 30), 5, null, "Synthetic timing"))).isEmpty();
            assertThat(validator.validate(new VehicleRequest(school, campus, "BUS_01", "School bus 1",
                    "UP65 XX 0001", "SCHOOL", null, 32, false, "provider-device-test",
                    false, "ACTIVE", null, "Synthetic vehicle"))).isEmpty();
            assertThat(validator.validate(new ComplianceTypeRequest(school, "FITNESS_CERTIFICATE",
                    "Fitness certificate", "School-configured readiness requirement", 12,
                    true, "ACTIVE", null, "Synthetic readiness rule"))).isEmpty();
            assertThat(validator.validate(new ComplianceCheckRequest(school, vehicle, UUID.randomUUID(),
                    "SYNTHETIC-REFERENCE", "Synthetic restricted reviewer note",
                    "Synthetic evidence"))).isEmpty();
            assertThat(validator.validate(new ComplianceDecisionRequest(school, "VERIFIED",
                    LocalDate.of(2026, 8, 20), LocalDate.of(2027, 8, 19), 0,
                    "Synthetic independent review"))).isEmpty();
            assertThat(validator.validate(new DutyRequest(school, revision, vehicle, UUID.randomUUID(),
                    UUID.randomUUID(), LocalDate.of(2026, 8, 24), "PLANNED",
                    "Synthetic responsible-adult assignment"))).isEmpty();
            assertThat(validator.validate(new LearnerAssignmentRequest(school, learner, revision, stop,
                    "PICKUP", LocalDate.of(2026, 8, 24), "PLANNED",
                    "Synthetic guardian-confirmed placement"))).isEmpty();
            assertThat(validator.validate(new EndRequest(school, LocalDate.of(2026, 8, 31), 0,
                    "Synthetic operational handoff"))).isEmpty();
            assertThat(validator.validate(new TripRequest(school, revision, duty,
                    LocalDate.of(2026, 8, 24), "Synthetic service day"))).isEmpty();
            assertThat(validator.validate(new TripStateRequest(school, "OPEN_BOARDING", 0,
                    "Synthetic operator action"))).isEmpty();
            assertThat(validator.validate(new TripEventRequest(school, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), "BOARDED", Instant.parse("2026-08-24T02:00:00Z"), null))).isEmpty();
            assertThat(validator.validate(new IncidentRequest(school, null, learner, "DELAY", "LOW",
                    "Synthetic delay", "Synthetic restricted incident evidence", "Family informed"))).isEmpty();
            assertThat(validator.validate(new ResolveIncidentRequest(school, 0,
                    "Synthetic accountable resolution"))).isEmpty();
            assertThat(validator.validate(new VersionedReason(school, 0, "Synthetic transition"))).isEmpty();
            assertThat(validator.validate(new DecisionRequest(school, "PUBLISHED", 0,
                    "Synthetic independent decision"))).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeBlankEvidenceAndUnsafeBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var stop = new StopRequest(null, null, "1", "", "", "x".repeat(181),
                    -91d, 181d, "x".repeat(501), "", -1L, null);
            var revision = new RouteRevisionRequest(null, null, 0, null, "", "",
                    "x".repeat(41), null, null, -1L, null);
            var vehicle = new VehicleRequest(null, null, "1", "", "", "",
                    "x".repeat(161), 0, false, "x".repeat(161), false, "", -1L, null);
            var routeStop = new RouteStopRequest(null, null, null, 0, null, 61, -1L, null);
            var event = new TripEventRequest(null, null, null, null, "", null,
                    "x".repeat(501));
            var incident = new IncidentRequest(null, null, null, "", "", "",
                    "", "x".repeat(2001));

            assertThat(validator.validate(stop)).extracting(v -> v.getPropertyPath().toString())
                    .contains("schoolId", "campusId", "code", "displayName", "familyLabel",
                            "locality", "latitude", "longitude", "accessibilityNote", "status",
                            "expectedVersion");
            assertThat(validator.validate(revision)).extracting(v -> v.getPropertyPath().toString())
                    .contains("schoolId", "routeId", "revisionNumber", "journeyKind", "serviceLabel",
                            "operatingDays", "effectiveFrom", "expectedVersion");
            assertThat(validator.validate(vehicle)).extracting(v -> v.getPropertyPath().toString())
                    .contains("schoolId", "campusId", "vehicleCode", "displayLabel",
                            "registrationNumber", "ownershipKind", "vendorName", "learnerCapacity",
                            "gpsProviderReference", "status", "expectedVersion");
            assertThat(validator.validate(routeStop)).extracting(v -> v.getPropertyPath().toString())
                    .contains("schoolId", "routeRevisionId", "stopId", "sequenceNumber",
                            "scheduledTime", "boardingWindowMinutes", "expectedVersion");
            assertThat(validator.validate(event)).extracting(v -> v.getPropertyPath().toString())
                    .contains("schoolId", "eventId", "tripId", "rosterEntryId", "eventType", "safeNote");
            assertThat(validator.validate(incident)).extracting(v -> v.getPropertyPath().toString())
                    .contains("schoolId", "category", "severity", "summary", "restrictedDetails",
                            "immediateAction");
        }
    }

    @Test
    void sensitiveEndpointsDeclareDenyByDefaultCapabilities() throws Exception {
        var snapshot = TransportController.class.getDeclaredMethod("snapshot",
                AuthenticatedUser.class, UUID.class).getAnnotation(PreAuthorize.class);
        var createTrip = TransportController.class.getDeclaredMethod("createTrip",
                AuthenticatedUser.class, TripRequest.class).getAnnotation(PreAuthorize.class);
        var incident = TransportController.class.getDeclaredMethod("createIncident",
                AuthenticatedUser.class, IncidentRequest.class).getAnnotation(PreAuthorize.class);
        var family = FamilyTransportController.class.getDeclaredMethod("transport",
                AuthenticatedUser.class, UUID.class).getAnnotation(PreAuthorize.class);

        assertThat(snapshot.value()).isEqualTo("hasAuthority('CAPABILITY_transport.read')");
        assertThat(createTrip.value()).isEqualTo("hasAuthority('CAPABILITY_transport.operate')");
        assertThat(incident.value()).isEqualTo("hasAuthority('CAPABILITY_transport.safety')");
        assertThat(family.value()).isEqualTo("hasAuthority('CAPABILITY_portal.guardian')");
    }
}

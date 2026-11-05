package com.educore.api.transport;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record TransportSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canConfigure,
        boolean canOperate,
        boolean canReviewSafety,
        boolean canPublish,
        List<CampusItem> campuses,
        List<StopItem> stops,
        List<RouteItem> routes,
        List<RouteRevisionItem> routeRevisions,
        List<RouteStopItem> routeStops,
        List<VehicleItem> vehicles,
        List<ComplianceTypeItem> complianceTypes,
        List<ComplianceCheckItem> complianceChecks,
        List<StaffItem> staff,
        List<LearnerItem> learners,
        List<DutyItem> duties,
        List<LearnerAssignmentItem> learnerAssignments,
        List<TripItem> trips,
        List<RosterItem> roster,
        List<TripEventItem> tripEvents,
        List<IncidentItem> incidents) {

    public TransportSnapshot {
        campuses = List.copyOf(campuses);
        stops = List.copyOf(stops);
        routes = List.copyOf(routes);
        routeRevisions = List.copyOf(routeRevisions);
        routeStops = List.copyOf(routeStops);
        vehicles = List.copyOf(vehicles);
        complianceTypes = List.copyOf(complianceTypes);
        complianceChecks = List.copyOf(complianceChecks);
        staff = List.copyOf(staff);
        learners = List.copyOf(learners);
        duties = List.copyOf(duties);
        learnerAssignments = List.copyOf(learnerAssignments);
        trips = List.copyOf(trips);
        roster = List.copyOf(roster);
        tripEvents = List.copyOf(tripEvents);
        incidents = List.copyOf(incidents);
    }

    public record CampusItem(UUID id, String name, String status) {}
    public record StopItem(UUID id, UUID campusId, String code, String displayName,
            String familyLabel, String locality, Double latitude, Double longitude,
            String accessibilityNote, String status, long rowVersion) {}
    public record RouteItem(UUID id, UUID campusId, String code, String displayName,
            String description, String status, long rowVersion) {}
    public record RouteRevisionItem(UUID id, UUID routeId, int revisionNumber,
            UUID supersedesRevisionId, String journeyKind, String serviceLabel,
            String operatingDays, LocalDate effectiveFrom, LocalDate effectiveUntil,
            String status, String creatorName, String deciderName, String decisionReason,
            Instant publishedAt, long rowVersion) {}
    public record RouteStopItem(UUID id, UUID routeRevisionId, UUID stopId,
            int sequenceNumber, LocalTime scheduledTime, int boardingWindowMinutes,
            String stopCode, String stopName, String familyLabel, long rowVersion) {}
    public record VehicleItem(UUID id, UUID campusId, String vehicleCode, String displayLabel,
            String registrationNumber, String ownershipKind, String vendorName,
            int learnerCapacity, boolean wheelchairAccessible, boolean gpsConfigured,
            String status, long rowVersion) {}
    public record ComplianceTypeItem(UUID id, String code, String name, String description,
            Integer validityMonths, boolean requiredBeforeService, String status, long rowVersion) {}
    public record ComplianceCheckItem(UUID id, UUID vehicleId, UUID complianceTypeId,
            String status, LocalDate completedOn, LocalDate validUntil, String safeReference,
            String restrictedNote, String decisionReason, long rowVersion) {}
    public record StaffItem(UUID id, String employeeCode, String displayName,
            String staffCategory, String designation, boolean eligibleAsDriver,
            boolean eligibleAsAttendant) {}
    public record LearnerItem(UUID id, String learnerReference, String displayName,
            String gradeCode, String sectionName) {}
    public record DutyItem(UUID id, UUID routeRevisionId, UUID vehicleId, UUID driverStaffId,
            UUID attendantStaffId, String driverName, String attendantName,
            LocalDate startsOn, LocalDate endsOn, String status, String endingReason,
            long rowVersion) {}
    public record LearnerAssignmentItem(UUID id, UUID learnerId, UUID routeRevisionId,
            UUID routeStopId, String journeyKind, LocalDate startsOn, LocalDate endsOn,
            String status, String endingReason, long rowVersion) {}
    public record TripItem(UUID id, UUID routeRevisionId, UUID dutyId, LocalDate serviceDate,
            String journeyKind, String routeCode, String routeName, String serviceLabel,
            String vehicleLabel, String registrationNumber, String driverName,
            String attendantName, String status, Instant boardingOpenedAt,
            Instant startedAt, Instant completedAt,
            String cancellationReason, int expectedLearners, int eventCount, long rowVersion) {}
    public record RosterItem(UUID id, UUID tripId, UUID learnerId, UUID assignmentId,
            UUID routeStopId, String learnerName, String learnerReference,
            int stopSequence, String stopFamilyLabel, LocalTime scheduledTime,
            boolean parentNotTravelling, Instant parentPlanUpdatedAt, boolean parentPlanAssignmentChanged) {}
    public record TripEventItem(UUID id, UUID tripId, UUID rosterEntryId, String eventType,
            Instant occurredAt, String safeNote, String actorName) {}
    public record IncidentItem(UUID id, UUID tripId, UUID learnerId, String category,
            String severity, String summary, String restrictedDetails, String immediateAction,
            String status, String reporterName, Instant reportedAt, String resolverName,
            Instant resolvedAt, String resolutionSummary, long rowVersion) {}
}

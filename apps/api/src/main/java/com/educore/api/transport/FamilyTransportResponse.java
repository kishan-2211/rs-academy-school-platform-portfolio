package com.educore.api.transport;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record FamilyTransportResponse(UUID learnerId, List<JourneyItem> journeys) {
    public FamilyTransportResponse { journeys = List.copyOf(journeys); }

    public record JourneyItem(
            String journeyKind,
            String routeName,
            String serviceLabel,
            String operatingDays,
            String stopLabel,
            LocalTime scheduledTime,
            int boardingWindowMinutes,
            String vehicleLabel,
            String vehicleRegistration,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            LatestTrip latestTrip) {}

    public record LatestTrip(
            UUID tripId,
            LocalDate serviceDate,
            String status,
            Instant lastUpdatedAt,
            String learnerStatus,
            Instant learnerStatusAt) {}
}

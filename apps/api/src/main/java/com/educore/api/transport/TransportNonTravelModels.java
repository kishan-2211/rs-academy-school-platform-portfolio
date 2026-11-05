package com.educore.api.transport;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class TransportNonTravelModels {
    private TransportNonTravelModels() {}

    public record PolicyRequest(@NotNull UUID schoolId,@NotNull UUID campusId,@NotNull Boolean enabled,
            LocalTime pickupClosesAt,LocalTime dropClosesAt,@PositiveOrZero Long expectedVersion,
            @NotBlank @Size(min=3,max=500) String reason) {}
    public record ChangeRequest(@NotNull UUID clientRequestId,@NotNull LocalDate serviceDate,
            @NotBlank @Pattern(regexp="PICKUP|DROP|FULL_DAY") String journeys,@NotNull Boolean notTravelling,
            UUID pickupAssignmentId,UUID dropAssignmentId,UUID expectedPickupRevisionId,UUID expectedDropRevisionId) {}
    public record Policy(UUID id,UUID campusId,String campusName,String timeZone,long version,
            boolean enabled,LocalTime pickupClosesAt,LocalTime dropClosesAt) {}
    public record PolicyWorkspace(UUID schoolId,String schoolName,boolean canConfigure,List<Policy> policies) {
        public PolicyWorkspace { policies=List.copyOf(policies); }
    }
    public record FamilyDay(UUID learnerId,LocalDate serviceDate,String timeZone,boolean enabled,
            Instant observedAt,List<Journey> journeys,List<Change> history) {
        public FamilyDay { journeys=List.copyOf(journeys);history=List.copyOf(history); }
    }
    public record Journey(String journeyKind,UUID assignmentId,String serviceLabel,String stopLabel,
            LocalTime scheduledTime,Instant changesCloseAt,boolean changesAllowed,boolean notTravelling,
            UUID revisionId,boolean assignmentChanged) {}
    public record Change(UUID id,LocalDate serviceDate,String journeys,boolean notTravelling,Instant occurredAt) {}
}

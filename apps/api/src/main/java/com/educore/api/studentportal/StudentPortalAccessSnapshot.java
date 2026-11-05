package com.educore.api.studentportal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StudentPortalAccessSnapshot(
        UUID learnerId,
        String legalName,
        LocalDate birthDate,
        String learnerStatus,
        long learnerVersion,
        boolean canManage,
        List<GuardianOption> guardians,
        PortalAccount account,
        List<ConsentRequest> requests,
        List<HistoryEvent> history) {

    public StudentPortalAccessSnapshot {
        guardians = List.copyOf(guardians);
        requests = List.copyOf(requests);
        history = List.copyOf(history);
    }

    public record GuardianOption(
            UUID guardianId,
            String displayName,
            String relationshipCode,
            String guardianStatus,
            String relationshipPortalStatus,
            boolean linkedPortalAccount,
            long relationshipVersion) {}

    public record PortalAccount(
            UUID id,
            String loginIdentifier,
            String status,
            Instant activatedAt,
            Instant suspendedAt,
            String suspensionReason,
            Instant endedAt,
            String endingReason,
            long rowVersion) {}

    public record ConsentRequest(
            UUID id,
            UUID guardianId,
            String guardianName,
            UUID supersedesRequestId,
            String loginIdentifier,
            String privacyNoticeVersion,
            String purposeSummary,
            String adultVerificationMethod,
            String adultVerificationReference,
            Instant adultVerifiedAt,
            String status,
            Instant expiresAt,
            Instant createdAt,
            Instant guardianDecidedAt,
            String guardianDecisionReason,
            Instant revokedAt,
            String revokedReason,
            Instant withdrawnAt,
            String withdrawalReason,
            long rowVersion) {}

    public record HistoryEvent(
            UUID id,
            String eventType,
            String previousStatus,
            String newStatus,
            UUID consentRequestId,
            String reason,
            String actorName,
            Instant occurredAt) {}
}

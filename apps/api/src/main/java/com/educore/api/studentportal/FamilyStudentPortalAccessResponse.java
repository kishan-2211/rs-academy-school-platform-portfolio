package com.educore.api.studentportal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FamilyStudentPortalAccessResponse(
        UUID learnerId,
        String learnerName,
        PortalAccount account,
        List<ConsentRequest> requests) {

    public FamilyStudentPortalAccessResponse {
        requests = List.copyOf(requests);
    }

    public record PortalAccount(
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
            String loginIdentifier,
            String privacyNoticeVersion,
            String purposeSummary,
            String status,
            Instant expiresAt,
            Instant createdAt,
            Instant decidedAt,
            String decisionReason,
            Instant withdrawnAt,
            String withdrawalReason,
            long rowVersion) {}
}

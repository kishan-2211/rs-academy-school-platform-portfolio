package com.educore.api.people;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Narrow, internal employment boundary for controlled staff-account invitations.
 * The identity caller authorizes issuance/acceptance and binds the verified school
 * organisation before calling this bridge in its existing transaction.
 */
public interface PeopleStaffAccountBridge {
    StaffAccountCandidate lockCandidate(UUID organisationId, UUID schoolId, UUID staffId);

    void linkAcceptedAccount(StaffAccountCandidate expected, UUID userId,
            UUID actorMembershipId, String reason);

    record StaffAccountCandidate(UUID id, UUID organisationId, UUID schoolId,
            String schoolName, String displayName, String email, String language,
            long version, LocalDate joinedOn, LocalDate endedOn, String status,
            UUID userAccountId) {}
}

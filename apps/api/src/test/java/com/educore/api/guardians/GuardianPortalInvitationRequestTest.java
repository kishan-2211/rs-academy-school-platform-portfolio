package com.educore.api.guardians;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class GuardianPortalInvitationRequestTest {

    @Test
    void acceptsVersionedAuditedInvitationActionsAndStrongAcceptanceCredentials() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var issue = new IssueGuardianPortalInvitationRequest(
                    0L, "Guardian requested access after the contact record was checked");
            var revoke = new RevokeGuardianPortalInvitationRequest(
                    1L, "Guardian asked the school to cancel this invitation");
            var token = new GuardianInvitationTokenRequest("a".repeat(43));
            var acceptance = new AcceptGuardianPortalInvitationRequest(
                    "a".repeat(43), "a-strong-family-password");

            assertThat(factory.getValidator().validate(issue)).isEmpty();
            assertThat(factory.getValidator().validate(revoke)).isEmpty();
            assertThat(factory.getValidator().validate(token)).isEmpty();
            assertThat(factory.getValidator().validate(acceptance)).isEmpty();
        }
    }

    @Test
    void rejectsUnversionedUnauditedOrWeakInvitationActions() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var issue = new IssueGuardianPortalInvitationRequest(-1L, "");
            var revoke = new RevokeGuardianPortalInvitationRequest(-1L, "");
            var token = new GuardianInvitationTokenRequest("short");
            var acceptance = new AcceptGuardianPortalInvitationRequest("short", "weak");

            assertThat(factory.getValidator().validate(issue))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("expectedRelationshipVersion", "reason");
            assertThat(factory.getValidator().validate(revoke))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("expectedVersion", "reason");
            assertThat(factory.getValidator().validate(token))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("token");
            assertThat(factory.getValidator().validate(acceptance))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("token", "password");
        }
    }
}

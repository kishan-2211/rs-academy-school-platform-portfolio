package com.educore.api.admissions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApplicationStatusTest {

    @Test
    void allowsOnlyWorkflowTransitionsThatDoNotBypassDecisionsOrEnrolment() {
        assertThat(ApplicationStatus.DRAFT.canTransitionTo(ApplicationStatus.SUBMITTED)).isTrue();
        assertThat(ApplicationStatus.SUBMITTED.canTransitionTo(ApplicationStatus.UNDER_REVIEW)).isTrue();
        assertThat(ApplicationStatus.OFFERED.canTransitionTo(ApplicationStatus.ACCEPTED)).isTrue();
        assertThat(ApplicationStatus.REJECTED.canTransitionTo(ApplicationStatus.UNDER_REVIEW)).isTrue();

        assertThat(ApplicationStatus.DRAFT.canTransitionTo(ApplicationStatus.OFFERED)).isFalse();
        assertThat(ApplicationStatus.UNDER_REVIEW.canTransitionTo(ApplicationStatus.ACCEPTED)).isFalse();
        assertThat(ApplicationStatus.ACCEPTED.canTransitionTo(ApplicationStatus.ENROLLED)).isFalse();
        assertThat(ApplicationStatus.ENROLLED.canTransitionTo(ApplicationStatus.UNDER_REVIEW)).isFalse();
    }

    @Test
    void mapsEveryDecisionToItsControlledApplicationState() {
        assertThat(AdmissionDecision.OFFER.applicationStatus()).isEqualTo(ApplicationStatus.OFFERED);
        assertThat(AdmissionDecision.WAITLIST.applicationStatus()).isEqualTo(ApplicationStatus.WAITLISTED);
        assertThat(AdmissionDecision.REJECT.applicationStatus()).isEqualTo(ApplicationStatus.REJECTED);
    }
}

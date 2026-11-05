package com.educore.api.admissions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class EnquiryStatusTest {

    @Test
    void permitsOnlyExplicitWorkflowTransitions() {
        assertThat(allowedFrom(EnquiryStatus.NEW))
                .containsExactlyInAnyOrder(EnquiryStatus.NEW, EnquiryStatus.OPEN, EnquiryStatus.CLOSED, EnquiryStatus.SPAM);
        assertThat(allowedFrom(EnquiryStatus.OPEN))
                .containsExactlyInAnyOrder(EnquiryStatus.OPEN, EnquiryStatus.QUALIFIED, EnquiryStatus.CLOSED, EnquiryStatus.SPAM);
        assertThat(allowedFrom(EnquiryStatus.QUALIFIED))
                .containsExactlyInAnyOrder(EnquiryStatus.QUALIFIED, EnquiryStatus.OPEN, EnquiryStatus.CLOSED);
        assertThat(allowedFrom(EnquiryStatus.CLOSED))
                .containsExactlyInAnyOrder(EnquiryStatus.CLOSED, EnquiryStatus.OPEN);
        assertThat(allowedFrom(EnquiryStatus.SPAM))
                .containsExactlyInAnyOrder(EnquiryStatus.SPAM, EnquiryStatus.NEW);
    }

    private static EnumSet<EnquiryStatus> allowedFrom(EnquiryStatus current) {
        EnumSet<EnquiryStatus> allowed = EnumSet.noneOf(EnquiryStatus.class);
        for (EnquiryStatus candidate : EnquiryStatus.values()) {
            if (current.canTransitionTo(candidate)) allowed.add(candidate);
        }
        return allowed;
    }
}

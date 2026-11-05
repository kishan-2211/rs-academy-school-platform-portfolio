package com.educore.api.admissions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EnquiryIntentTest {

    @Test
    void acceptsBrowserFriendlyLowercaseValues() {
        assertThat(EnquiryIntent.fromJson("visit")).isEqualTo(EnquiryIntent.VISIT);
        assertThat(EnquiryIntent.fromJson(" Application ")).isEqualTo(EnquiryIntent.APPLICATION);
        assertThat(EnquiryIntent.fromJson(null)).isEqualTo(EnquiryIntent.GENERAL);
    }
}

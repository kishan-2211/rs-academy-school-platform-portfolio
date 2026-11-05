package com.educore.api.admissions;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

public enum EnquiryIntent {
    GENERAL,
    VISIT,
    APPLICATION;

    @JsonCreator
    public static EnquiryIntent fromJson(String value) {
        return value == null ? GENERAL : EnquiryIntent.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}

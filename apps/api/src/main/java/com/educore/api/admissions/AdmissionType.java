package com.educore.api.admissions;

public enum AdmissionType {
    REGULAR, RTE;

    public static AdmissionType orRegular(AdmissionType value) {
        return value == null ? REGULAR : value;
    }
}

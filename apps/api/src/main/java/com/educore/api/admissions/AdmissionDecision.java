package com.educore.api.admissions;

public enum AdmissionDecision {
    OFFER(ApplicationStatus.OFFERED),
    WAITLIST(ApplicationStatus.WAITLISTED),
    REJECT(ApplicationStatus.REJECTED);

    private final ApplicationStatus applicationStatus;

    AdmissionDecision(ApplicationStatus applicationStatus) {
        this.applicationStatus = applicationStatus;
    }

    public ApplicationStatus applicationStatus() {
        return applicationStatus;
    }
}

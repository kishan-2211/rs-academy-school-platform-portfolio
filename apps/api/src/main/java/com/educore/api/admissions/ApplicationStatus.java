package com.educore.api.admissions;

import java.util.EnumSet;

public enum ApplicationStatus {
    DRAFT,
    SUBMITTED,
    UNDER_REVIEW,
    WAITLISTED,
    OFFERED,
    ACCEPTED,
    REJECTED,
    WITHDRAWN,
    ENROLLED;

    public boolean canTransitionTo(ApplicationStatus target) {
        if (target == this) return true;
        return switch (this) {
            case DRAFT -> EnumSet.of(SUBMITTED, WITHDRAWN).contains(target);
            case SUBMITTED -> EnumSet.of(UNDER_REVIEW, WITHDRAWN).contains(target);
            case UNDER_REVIEW -> target == WITHDRAWN;
            case WAITLISTED -> EnumSet.of(UNDER_REVIEW, WITHDRAWN).contains(target);
            case OFFERED -> EnumSet.of(ACCEPTED, UNDER_REVIEW, WITHDRAWN).contains(target);
            case ACCEPTED -> EnumSet.of(UNDER_REVIEW, WITHDRAWN).contains(target);
            case REJECTED -> target == UNDER_REVIEW;
            case WITHDRAWN -> target == UNDER_REVIEW;
            case ENROLLED -> false;
        };
    }

    public boolean canReceiveDecision() {
        return EnumSet.of(SUBMITTED, UNDER_REVIEW, WAITLISTED, OFFERED, ACCEPTED, REJECTED).contains(this);
    }
}

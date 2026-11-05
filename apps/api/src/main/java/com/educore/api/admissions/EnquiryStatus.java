package com.educore.api.admissions;

import java.util.EnumSet;

public enum EnquiryStatus {
    NEW,
    OPEN,
    QUALIFIED,
    CLOSED,
    SPAM;

    public boolean canTransitionTo(EnquiryStatus target) {
        if (target == this) return true;
        return switch (this) {
            case NEW -> EnumSet.of(OPEN, CLOSED, SPAM).contains(target);
            case OPEN -> EnumSet.of(QUALIFIED, CLOSED, SPAM).contains(target);
            case QUALIFIED -> EnumSet.of(OPEN, CLOSED).contains(target);
            case CLOSED -> target == OPEN;
            case SPAM -> target == NEW;
        };
    }
}

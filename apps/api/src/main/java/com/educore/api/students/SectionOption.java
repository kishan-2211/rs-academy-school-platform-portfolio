package com.educore.api.students;

import java.util.UUID;

public record SectionOption(
        UUID id,
        String code,
        String displayName,
        Integer capacity,
        long activeStudentCount,
        long version) {
}

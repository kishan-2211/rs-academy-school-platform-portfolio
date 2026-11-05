package com.educore.api.students;

import java.util.List;

public record StudentOptionsResponse(List<SectionOption> sections) {
    public StudentOptionsResponse {
        sections = List.copyOf(sections);
    }
}

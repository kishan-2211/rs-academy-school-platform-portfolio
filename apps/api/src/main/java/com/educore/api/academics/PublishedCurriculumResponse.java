package com.educore.api.academics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PublishedCurriculumResponse(
        UUID learnerId,
        UUID publicationId,
        Integer publicationVersion,
        String sessionName,
        String gradeCode,
        Instant publishedAt,
        List<Item> items) {

    public PublishedCurriculumResponse {
        items = List.copyOf(items);
    }

    public record Item(
            UUID id,
            String subjectCode,
            String subjectName,
            String subjectShortName,
            String resourceType,
            String title,
            String description,
            String author,
            String publisher,
            String edition,
            String isbn,
            String sourceUrl,
            int displayOrder) {}
}

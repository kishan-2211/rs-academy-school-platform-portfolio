package com.educore.api.admissions;

import java.time.Instant;
import java.util.UUID;

public record EnquiryNoteResponse(UUID id, String body, String authorName, Instant createdAt) {
}

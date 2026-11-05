package com.educore.api.files;

import java.time.Instant;
import java.util.UUID;

public record PrivateFileSummary(
        UUID fileId,
        String filename,
        String mediaType,
        long sizeBytes,
        String sha256,
        String purpose,
        Instant availableAt,
        UUID replacesFileId,
        long rowVersion) {}

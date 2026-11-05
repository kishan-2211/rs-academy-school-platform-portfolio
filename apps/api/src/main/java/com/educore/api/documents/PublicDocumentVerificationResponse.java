package com.educore.api.documents;

import java.time.Instant;

public record PublicDocumentVerificationResponse(
        String verificationStatus,
        String schoolName,
        String documentKind,
        String documentNumber,
        String lifecycleStatus,
        String sourceStatus,
        Instant issuedAt,
        String pdfSha256) {}

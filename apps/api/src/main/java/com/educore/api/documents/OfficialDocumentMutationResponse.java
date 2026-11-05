package com.educore.api.documents;

import java.util.UUID;

public record OfficialDocumentMutationResponse(
        UUID id,
        long rowVersion,
        String status,
        String documentNumber) {}

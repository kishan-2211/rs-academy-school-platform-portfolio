package com.educore.api.family;

import java.util.UUID;

public record FamilyExplanationResponse(UUID explanationId, long rowVersion) {}

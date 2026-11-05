package com.educore.api.assessment;

import java.util.UUID;

public record AssessmentMutationResponse(UUID id, long rowVersion) {}

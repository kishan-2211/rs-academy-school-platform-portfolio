package com.educore.api.studentportal;

import java.util.UUID;

public record StudentPortalMutationResponse(UUID id, long rowVersion, String status) {}

package com.educore.api.people;

import java.util.UUID;

public record PeopleMutationResponse(UUID id, long rowVersion) {}

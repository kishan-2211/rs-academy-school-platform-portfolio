package com.educore.api.transport;

import java.util.UUID;

public record TransportMutationResponse(UUID id, long rowVersion) {}

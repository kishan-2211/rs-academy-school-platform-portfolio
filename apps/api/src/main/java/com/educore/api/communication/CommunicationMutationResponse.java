package com.educore.api.communication;

import java.util.UUID;

public record CommunicationMutationResponse(UUID id, long version, int recipientCount) {}

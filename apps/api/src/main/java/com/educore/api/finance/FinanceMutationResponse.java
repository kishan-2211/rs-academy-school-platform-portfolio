package com.educore.api.finance;

import java.util.UUID;

public record FinanceMutationResponse(UUID id, long rowVersion, String receiptNumber) {
    public FinanceMutationResponse(UUID id, long rowVersion) { this(id, rowVersion, null); }
}

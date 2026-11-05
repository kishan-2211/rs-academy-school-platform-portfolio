package com.educore.api.accounting;

import java.util.UUID;

public record AccountingMutationResponse(UUID id, long rowVersion, String voucherNumber) {
    public AccountingMutationResponse(UUID id, long rowVersion) {
        this(id, rowVersion, null);
    }
}

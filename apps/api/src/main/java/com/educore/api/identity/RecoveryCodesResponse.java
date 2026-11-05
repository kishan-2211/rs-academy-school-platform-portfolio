package com.educore.api.identity;

import java.util.List;

public record RecoveryCodesResponse(List<String> recoveryCodes) {

    public RecoveryCodesResponse {
        recoveryCodes = List.copyOf(recoveryCodes);
    }
}

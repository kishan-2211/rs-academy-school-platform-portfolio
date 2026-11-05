package com.educore.api.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Family-safe actual balances only; no refund HOLD, review or provider provenance. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record FamilyFeeAccountsResponse(UUID learnerId, UUID currentAccountId, int page, int pageSize,
        boolean hasMore, List<Account> accounts) {
    public FamilyFeeAccountsResponse { accounts = List.copyOf(accounts); }
    @Override public String toString() { return "FamilyFeeAccountsResponse[private own-child fee accounts]"; }

    /** Current-session membership is a selection hint, never permission to pay. */
    public record Account(UUID id, UUID sessionId, String sessionName, String status, String currencyCode,
            BigDecimal balance, boolean currentSession) {
        @Override public String toString() { return "Account[private family fee-account summary]"; }
    }
}

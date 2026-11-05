package com.educore.api.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Synthetic pure evidence, never PostgreSQL or provider proof. */
final class GatewaySettlementSourceReviewFixtures {
    static final Instant TIME = Instant.parse("2026-10-21T12:00:00.123456789Z");
    final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), request = UUID.randomUUID(), summary = UUID.randomUUID();
    final GatewayWorkQueue.ProviderBinding binding = new GatewayWorkQueue.ProviderBinding(org, school, UUID.randomUUID(),
            "CASHFREE", "TEST", "synthetic_merchant", "synthetic:private-label");

    GatewaySettlementSourceReviewEvidence.Chain chain(List<List<GatewaySettlementProvider.SettlementEvent>> rows) {
        return chain(binding, rows);
    }
    GatewaySettlementSourceReviewEvidence.Chain chain(GatewayWorkQueue.ProviderBinding selected,
            List<List<GatewaySettlementProvider.SettlementEvent>> rows) {
        var pages = new ArrayList<GatewaySettlementSourceReviewEvidence.Page>();
        UUID previous = null;
        for (int index = 0; index < rows.size(); index++) {
            UUID read = UUID.randomUUID();
            var query = new GatewaySettlementReadStore.Prepared(index == 0 ? request : UUID.randomUUID(), selected,
                    GatewaySettlementReadStore.Kind.EVENTS, request, index + 1, previous, summary, null, null,
                    "synthetic_statement", null, TIME.plusSeconds(index * 2L));
            pages.add(new GatewaySettlementSourceReviewEvidence.Page(read, query, index == 0 ? null : hash(index),
                    index == rows.size() - 1 ? null : hash(index + 1), TIME.plusSeconds(index * 2L + 1), rows.get(index)));
            previous = read;
        }
        return new GatewaySettlementSourceReviewEvidence.Chain(org, school, request(rows.size()), selected, pages);
    }
    GatewaySettlementModels.Request request(int pages) {
        return new GatewaySettlementModels.Request(request, "EVENTS", "Asia/Kolkata", "COMPLETE", null, null,
                summary, "synthetic_statement", TIME.minusSeconds(1), "Synthetic actor", pages, 0, 0, TIME, null);
    }
    static String hash(int index) { return String.format("%064x", index); }
    static GatewaySettlementProvider.SettlementEvent payment(String event, String reference) {
        return event(event, "PAYMENT", "CREDIT", "SUCCESS", "INR", "100.00000000", "synthetic_order", reference);
    }
    static GatewaySettlementProvider.SettlementEvent event(String id, String type, String direction, String status,
            String currency, String amount, String order, String payment) {
        return new GatewaySettlementProvider.SettlementEvent("synthetic_statement", id, type, direction, status, currency,
                new BigDecimal(amount), new BigDecimal("1.12345678"), null, new BigDecimal("-9.87654321"), TIME,
                order, payment, List.of(), null);
    }
    static GatewaySettlementSourceReviewModels.Source source() {
        return new GatewaySettlementSourceReviewModels.Source(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SYNTHETIC-R-001", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "OTHER", UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2025, 4, 1),
                "ACCRUAL", "INR", "100.00");
    }
}

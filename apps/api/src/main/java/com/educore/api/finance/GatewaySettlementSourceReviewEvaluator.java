package com.educore.api.finance;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Pure classification; source resolver is used only for supported, unique, exact-paise payment rows. */
final class GatewaySettlementSourceReviewEvaluator {
    static final java.util.Set<String> REASONS = java.util.Set.of(
            "UNSUPPORTED_PROVIDER", "REFUND_ASSOCIATION_REVIEW", "UNSUPPORTED_EVENT_TYPE",
            "UNSUPPORTED_EVENT_DIRECTION", "UNSUPPORTED_EVENT_STATUS", "UNSUPPORTED_CURRENCY",
            "FRACTIONAL_PAISE", "NON_POSITIVE_GROSS", "MISSING_PAYMENT_REFERENCES", "DUPLICATE_PAYMENT_SOURCE",
            "SOURCE_NOT_FOUND_OR_OUT_OF_SCOPE", "AMBIGUOUS_PAYMENT_SOURCE", "UNRESOLVED_NUMBERED_RECEIPT",
            "GROSS_MISMATCH", "ORIGINAL_POSTING_INVALID", "CLEARING_EVIDENCE_UNAVAILABLE",
            "LEGACY_BANK_CLEARING_REVIEW", "INVALID_CLEARING_IDENTITY");
    private GatewaySettlementSourceReviewEvaluator() {}

    record Identification(GatewaySettlementSourceReviewModels.Source source, String reason) {
        Identification {
            if ((source == null) == (reason == null) || reason != null && !REASONS.contains(reason))
                throw new IllegalArgumentException("Exactly one source identity or explicit review reason required");
        }
        @Override public String toString() { return "Identification[private historical source review]"; }
    }

    static GatewaySettlementSourceReviewModels.Review evaluate(GatewaySettlementSourceReviewEvidence.Chain chain,
            int page, int size, Function<GatewaySettlementProvider.SettlementEvent, Identification> resolver) {
        Objects.requireNonNull(chain); Objects.requireNonNull(resolver); GatewaySettlementTerms.page(page, size);
        var all = allRows(chain,resolver);
        long identified = all.stream().filter(row -> "SOURCE_IDENTIFIED".equals(row.status())).count();
        int start = (int) Math.min((long) page * size, all.size()), end = Math.min(start + size, all.size());
        return new GatewaySettlementSourceReviewModels.Review(chain.schoolId(), chain.request().id(), chain.request().settlementReference(),
                GatewaySettlementSourceReviewModels.STAGE, chain.pages().size(), all.size(), identified, all.size() - identified,
                page, size, end < all.size(), all.subList(start, end));
    }

    static List<GatewaySettlementSourceReviewModels.Row> allRows(GatewaySettlementSourceReviewEvidence.Chain chain,
            Function<GatewaySettlementProvider.SettlementEvent, Identification> resolver) {
        Objects.requireNonNull(chain); Objects.requireNonNull(resolver);
        // Payment identity, not event ID, is the economic key. Count the entire unfiltered chain before pagination.
        var occurrences = new HashMap<String, Integer>();
        for (var retained : chain.pages()) for (var event : retained.events())
            if ("PAYMENT".equals(event.reportedType()) && event.paymentReference() != null)
                occurrences.merge(event.paymentReference(), 1, Integer::sum);
        var all = new ArrayList<GatewaySettlementSourceReviewModels.Row>();
        for (var retained : chain.pages()) for (int index = 0; index < retained.events().size(); index++) {
            var event = retained.events().get(index); var reasons = new ArrayList<String>();
            if (!"CASHFREE".equals(chain.binding().providerCode())) reasons.add("UNSUPPORTED_PROVIDER");
            boolean payment = "PAYMENT".equals(event.reportedType()), refund = "REFUND".equals(event.reportedType());
            if (refund) reasons.add("REFUND_ASSOCIATION_REVIEW");
            else if (!payment) reasons.add("UNSUPPORTED_EVENT_TYPE");
            if (!(payment && "CREDIT".equals(event.reportedDirection()) || refund && "DEBIT".equals(event.reportedDirection())
                    || !payment && !refund && List.of("CREDIT", "DEBIT").contains(event.reportedDirection()))) reasons.add("UNSUPPORTED_EVENT_DIRECTION");
            if (!"SUCCESS".equals(event.reportedStatus())) reasons.add("UNSUPPORTED_EVENT_STATUS");
            if (!"INR".equals(event.currencyCode())) reasons.add("UNSUPPORTED_CURRENCY");
            try { event.reportedAmount().setScale(2, RoundingMode.UNNECESSARY); }
            catch (ArithmeticException fractional) { reasons.add("FRACTIONAL_PAISE"); }
            if (event.reportedAmount().signum() <= 0) reasons.add("NON_POSITIVE_GROSS");
            if (payment && (event.orderReference() == null || event.paymentReference() == null)) reasons.add("MISSING_PAYMENT_REFERENCES");
            if (payment && event.paymentReference() != null && occurrences.get(event.paymentReference()) > 1) reasons.add("DUPLICATE_PAYMENT_SOURCE");
            GatewaySettlementSourceReviewModels.Source source = null;
            if (reasons.isEmpty()) {
                Identification identification = Objects.requireNonNull(resolver.apply(event));
                source = identification.source(); if (identification.reason() != null) reasons.add(identification.reason());
            }
            all.add(new GatewaySettlementSourceReviewModels.Row(retained.readId(), index, retained.observedAt(), event(event),
                    reasons.isEmpty() ? "SOURCE_IDENTIFIED" : "REVIEW_REQUIRED", reasons, source));
        }
        return List.copyOf(all);
    }

    private static GatewaySettlementModels.Event event(GatewaySettlementProvider.SettlementEvent value) {
        return new GatewaySettlementModels.Event(value.settlementReference(), value.eventReference(), value.reportedType(),
                value.reportedDirection(), value.reportedStatus(), value.currencyCode(), value.reportedAmount().toPlainString(),
                money(value.reportedServiceCharge()), money(value.reportedServiceTax()), money(value.reportedSettlementAmount()),
                value.occurredAt(), value.orderReference(), value.paymentReference(), value.refundReferences(), value.chargesCurrencyCode());
    }
    private static String money(java.math.BigDecimal value) { return value == null ? null : value.toPlainString(); }
}

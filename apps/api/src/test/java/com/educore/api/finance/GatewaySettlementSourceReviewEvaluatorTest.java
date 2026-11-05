package com.educore.api.finance;

import static com.educore.api.finance.GatewaySettlementSourceReviewFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GatewaySettlementSourceReviewEvaluatorTest {
    @Test void exactSupportedPaymentIdentifiesOnlyHistoricalSourceWithoutAssumingReportedNetOrTaxFormula() {
        var f = new GatewaySettlementSourceReviewFixtures(); var source = source();
        var result = GatewaySettlementSourceReviewEvaluator.evaluate(f.chain(List.of(List.of(payment("synthetic_event", "synthetic_payment")))),
                0, 25, event -> new GatewaySettlementSourceReviewEvaluator.Identification(source, null));
        assertThat(result.reviewStage()).isEqualTo("SOURCE_IDENTIFICATION_ONLY");
        assertThat(result.sourceIdentifiedRows()).isEqualTo(1); assertThat(result.reviewRequiredRows()).isZero();
        var row = result.items().getFirst(); assertThat(row.status()).isEqualTo("SOURCE_IDENTIFIED");
        assertThat(row.source()).isSameAs(source); assertThat(row.reasons()).isEmpty();
        assertThat(row.event().reportedAmount()).isEqualTo("100.00000000");
        assertThat(row.event().reportedServiceCharge()).isEqualTo("1.12345678");
        assertThat(row.event().reportedSettlementAmount()).isEqualTo("-9.87654321");
        assertThat(row.event().occurredAt()).isEqualTo(TIME);
    }

    @Test void allDuplicateEconomicOccurrencesAcrossPagesAreReviewEvenOutsideReturnedPage() {
        var f = new GatewaySettlementSourceReviewFixtures(); var resolver = resolver();
        var chain = f.chain(List.of(List.of(payment("synthetic_first", "synthetic_payment")),
                List.of(payment("synthetic_later", "synthetic_payment"))));
        for (int page = 0; page < 2; page++) {
            var result = GatewaySettlementSourceReviewEvaluator.evaluate(chain, page, 1, resolver);
            assertThat(result.totalRows()).isEqualTo(2); assertThat(result.reviewRequiredRows()).isEqualTo(2);
            assertThat(result.sourceIdentifiedRows()).isZero();
            assertThat(result.items().getFirst().reasons()).containsExactly("DUPLICATE_PAYMENT_SOURCE");
            assertThat(result.items().getFirst().source()).isNull();
        }
        verifyNoInteractions(resolver);
    }

    @Test void sameEventIdAcrossKindsDoesNotInventAnEconomicDuplicateOrRefundAssociation() {
        var f = new GatewaySettlementSourceReviewFixtures(); var source = source();
        var refund = event("synthetic_same", "REFUND", "DEBIT", "SUCCESS", "INR", "100.00", "synthetic_order", "synthetic_payment");
        var result = GatewaySettlementSourceReviewEvaluator.evaluate(f.chain(List.of(List.of(payment("synthetic_same", "synthetic_payment"), refund))),
                0, 25, event -> new GatewaySettlementSourceReviewEvaluator.Identification(source, null));
        assertThat(result.items().getFirst().status()).isEqualTo("SOURCE_IDENTIFIED");
        assertThat(result.items().get(1).reasons()).containsExactly("REFUND_ASSOCIATION_REVIEW");
        assertThat(result.items().get(1).source()).isNull();
    }

    @Test void unknownProviderNeverUsesLocalSourceResolver() {
        var f = new GatewaySettlementSourceReviewFixtures(); var resolver = resolver(); var b = f.binding;
        var foreign = new GatewayWorkQueue.ProviderBinding(b.organisationId(), b.schoolId(), b.providerConfigId(), "OTHER_PROVIDER", b.environment(), b.merchantReference(), b.credentialsReference());
        var result = GatewaySettlementSourceReviewEvaluator.evaluate(f.chain(foreign, List.of(List.of(payment("synthetic_event", "synthetic_payment")))), 0, 25, resolver);
        assertThat(result.items().getFirst().reasons()).containsExactly("UNSUPPORTED_PROVIDER"); verifyNoInteractions(resolver);
    }

    @Test void unsupportedTypeDirectionStatusCurrencyAndFractionalPaiseRemainExplicitReview() {
        var f = new GatewaySettlementSourceReviewFixtures(); var resolver = resolver();
        var unknown = event("synthetic_event", "UNKNOWN_KIND", "UNKNOWN_DIRECTION", "UNKNOWN_STATUS", "USD", "100.00000001", null, null);
        var result = GatewaySettlementSourceReviewEvaluator.evaluate(f.chain(List.of(List.of(unknown))), 0, 25, resolver);
        assertThat(result.items().getFirst().reasons()).containsExactly("UNSUPPORTED_EVENT_TYPE", "UNSUPPORTED_EVENT_DIRECTION",
                "UNSUPPORTED_EVENT_STATUS", "UNSUPPORTED_CURRENCY", "FRACTIONAL_PAISE"); verifyNoInteractions(resolver);
    }

    @Test void wrongPaymentDirectionMissingReferencesAndZeroOrNegativeGrossAreNeverIdentified() {
        var f = new GatewaySettlementSourceReviewFixtures(); var resolver = resolver();
        for (String amount : List.of("0.00", "-10.00")) {
            var invalid = event("synthetic_event", "PAYMENT", "DEBIT", "SUCCESS", "INR", amount, null, null);
            var result = GatewaySettlementSourceReviewEvaluator.evaluate(f.chain(List.of(List.of(invalid))), 0, 25, resolver);
            assertThat(result.items().getFirst().reasons()).containsExactly("UNSUPPORTED_EVENT_DIRECTION", "NON_POSITIVE_GROSS", "MISSING_PAYMENT_REFERENCES");
        }
        verifyNoInteractions(resolver);
    }

    @Test void explicitCompleteEmptyChainIsNotUnknownAndHasNoFabricatedRows() {
        var f = new GatewaySettlementSourceReviewFixtures(); var resolver = resolver();
        var result = GatewaySettlementSourceReviewEvaluator.evaluate(f.chain(List.of(List.of())), 0, 25, resolver);
        assertThat(result.totalRows()).isZero(); assertThat(result.items()).isEmpty(); assertThat(result.pagesRead()).isEqualTo(1);
        assertThat(result.hasMore()).isFalse(); verifyNoInteractions(resolver);
    }

    @Test void sourceFailureReasonsAreRetainedWithoutAnyPostingControl() {
        var f = new GatewaySettlementSourceReviewFixtures();
        for (String reason : List.of("SOURCE_NOT_FOUND_OR_OUT_OF_SCOPE", "AMBIGUOUS_PAYMENT_SOURCE", "UNRESOLVED_NUMBERED_RECEIPT",
                "GROSS_MISMATCH", "ORIGINAL_POSTING_INVALID", "CLEARING_EVIDENCE_UNAVAILABLE", "LEGACY_BANK_CLEARING_REVIEW", "INVALID_CLEARING_IDENTITY")) {
            var result = GatewaySettlementSourceReviewEvaluator.evaluate(f.chain(List.of(List.of(payment("synthetic_event", "synthetic_payment")))),
                    0, 25, event -> new GatewaySettlementSourceReviewEvaluator.Identification(null, reason));
            assertThat(result.items().getFirst().reasons()).containsExactly(reason); assertThat(result.items().getFirst().status()).isEqualTo("REVIEW_REQUIRED");
            assertThat(JsonMapper.builder().build().writeValueAsString(result)).doesNotContain("canPost", "bankConfirmed", "credentials", "merchant", "cursor", "sourceSha256");
        }
    }

    @Test void outOfRangePaginationIsRejectedBeforeResolverAndOutOfBoundsPageIsGenuinelyEmpty() {
        var f = new GatewaySettlementSourceReviewFixtures(); var resolver = resolver(); var chain = f.chain(List.of(List.of()));
        for (int[] invalid : List.of(new int[]{-1, 25}, new int[]{10001, 25}, new int[]{0, 0}, new int[]{0, 51}))
            assertThatThrownBy(() -> GatewaySettlementSourceReviewEvaluator.evaluate(chain, invalid[0], invalid[1], resolver)).isInstanceOf(com.educore.api.common.api.ApiException.class);
        assertThat(GatewaySettlementSourceReviewEvaluator.evaluate(chain, 10000, 50, resolver).items()).isEmpty(); verifyNoInteractions(resolver);
    }

    @Test void outputStateCannotClaimSourceIdentifiedWithoutProofOrInventReadinessStages() {
        var f = new GatewaySettlementSourceReviewFixtures(); var valid = GatewaySettlementSourceReviewEvaluator.evaluate(f.chain(List.of(List.of(payment("synthetic_event", "synthetic_payment")))),
                0, 25, event -> new GatewaySettlementSourceReviewEvaluator.Identification(source(), null)); var row = valid.items().getFirst();
        assertThatThrownBy(() -> new GatewaySettlementSourceReviewModels.Row(row.sourceReadId(), 0, row.observedAt(), row.event(), "SOURCE_IDENTIFIED", List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewaySettlementSourceReviewModels.Row(row.sourceReadId(), 0, row.observedAt(), row.event(), "REVIEW_REQUIRED", List.of("UNTRUSTED_CODE"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewaySettlementSourceReviewModels.Review(f.school, f.request, "synthetic_statement", "READY_TO_POST", 1, 0, 0, 0, 0, 25, false, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewaySettlementSourceReviewEvaluator.Identification(source(), "GROSS_MISMATCH")).isInstanceOf(IllegalArgumentException.class);
        assertThat(row.toString()).doesNotContain("synthetic_payment"); assertThat(row.source().toString()).doesNotContain("SYNTHETIC-R-001");
        assertThatThrownBy(() -> valid.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @SuppressWarnings("unchecked") private static Function<GatewaySettlementProvider.SettlementEvent, GatewaySettlementSourceReviewEvaluator.Identification> resolver() {
        return mock(Function.class);
    }
}

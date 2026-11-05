package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GatewayWorkQueueTest {
    private static final Instant NOW = Instant.parse("2026-10-10T06:00:00Z");
    private final GatewayWorkQueue.ProviderBinding binding = binding(UUID.randomUUID());

    @AfterEach void clearOnlyTestTransactionMarkers() { TransactionSynchronizationManager.clear(); }

    @Test void providerBindingsRequireCanonicalEnvironmentAndNonEchoingReferenceShapes() {
        assertThat(binding.toString()).doesNotContain(binding.merchantReference(), binding.credentialsReference());
        assertThatThrownBy(() -> new GatewayWorkQueue.ProviderBinding(binding.organisationId(), binding.schoolId(), binding.providerConfigId(),
                "CASHFREE", "PRODUCTION", "synthetic_merchant", "synthetic:not-enabled")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayWorkQueue.ProviderBinding(binding.organisationId(), binding.schoolId(), binding.providerConfigId(),
                "CASHFREE", "TEST", "https://payment.example.test?customer=x", "synthetic:not-enabled")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayWorkQueue.ProviderBinding(binding.organisationId(), binding.schoolId(), binding.providerConfigId(),
                "CASHFREE", "TEST", "synthetic_merchant", "Bearer value with spaces")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void claimsRequireBoundedCurrentLeasesWithoutManufacturedWebhookCreateDispatch() {
        var claim = claim(GatewayWorkQueue.WorkKind.ATTEMPT, false);
        assertThat(claim.toString()).doesNotContain(claim.token().toString());
        assertThatThrownBy(() -> new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, UUID.randomUUID(), binding,
                UUID.randomUUID(), NOW, NOW, 1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, UUID.randomUUID(), binding,
                UUID.randomUUID(), NOW, NOW.plusSeconds(121), 1, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.WEBHOOK, UUID.randomUUID(), binding,
                UUID.randomUUID(), NOW, NOW.plusSeconds(90), 1, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void supportedMinimalWebhookEvidenceHasCanonicalHashProviderAndCheckoutIdentity() {
        GatewayWorkQueue.validateWebhook(binding, event("PAYMENT_SUCCESS_WEBHOOK", UUID.randomUUID().toString(), "synthetic_order", null));
        GatewayWorkQueue.validateWebhook(binding, event("PAYMENT_FAILED_WEBHOOK", null, "synthetic_order", null));
        assertThatThrownBy(() -> GatewayWorkQueue.validateWebhook(binding, event("PAYMENT_SUCCESS_WEBHOOK", "1-1-1-1-1", "synthetic_order", null)))
                .isInstanceOf(IllegalArgumentException.class);
        var wrongProvider = new PaymentProviderAdapter.VerifiedWebhook("OTHER", "a".repeat(64), "PAYMENT_SUCCESS_WEBHOOK", null,
                "synthetic_order", "synthetic_payment", null);
        assertThatThrownBy(() -> GatewayWorkQueue.validateWebhook(binding, wrongProvider)).isInstanceOf(IllegalArgumentException.class);
        var wrongHash = new PaymentProviderAdapter.VerifiedWebhook("CASHFREE", "A".repeat(64), "PAYMENT_SUCCESS_WEBHOOK", null,
                "synthetic_order", "synthetic_payment", null);
        assertThatThrownBy(() -> GatewayWorkQueue.validateWebhook(binding, wrongHash)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unsupportedOrMalformedWebhookShapesAreNotRetainedAsHarmlessEvents() {
        assertThatThrownBy(() -> GatewayWorkQueue.validateWebhook(binding, event("PAYMENT_LINK_EVENT", null, "synthetic_order", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatewayWorkQueue.validateWebhook(binding, event("PAYMENT_SUCCESS_WEBHOOK", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatewayWorkQueue.validateWebhook(binding, event("PAYMENT_SUCCESS_WEBHOOK", null, "synthetic_order", "synthetic_refund")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatewayWorkQueue.validateWebhook(binding, event("REFUND_STATUS_WEBHOOK", UUID.randomUUID().toString(), "synthetic_order", "synthetic_refund")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void minimalRefundRoutingAllowsOfficialAutoRefundMissingOrderButNotMissingOrdinaryRefund() {
        GatewayWorkQueue.validateWebhook(binding, event("REFUND_STATUS_WEBHOOK", null, "synthetic_order", "synthetic_refund"));
        GatewayWorkQueue.validateWebhook(binding, event("AUTO_REFUND_STATUS_WEBHOOK", null, null, null));
        assertThatThrownBy(() -> GatewayWorkQueue.validateWebhook(binding, event("REFUND_STATUS_WEBHOOK", null, null, "synthetic_refund")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void independentlyReadSuccessRequiresOriginalCompletionTimeAndExactValidMoney() {
        GatewayWorkQueue.validatePayment(payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, NOW.minusSeconds(1), "500.00"), NOW);
        assertThatThrownBy(() -> GatewayWorkQueue.validatePayment(payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, null, "500.00"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatewayWorkQueue.validatePayment(payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, NOW.plusSeconds(1), "500.00"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatewayWorkQueue.validatePayment(payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, NOW, "500.001"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        GatewayWorkQueue.validatePayment(payment(PaymentProviderAdapter.PaymentStatus.PENDING, false, null, "500"), NOW);
    }

    @Test void paymentEvidenceHashDeduplicatesEquivalentMoneyButPreservesStrongerAndScopedFacts() {
        UUID attempt = UUID.randomUUID();
        var pending = payment(PaymentProviderAdapter.PaymentStatus.PENDING, false, null, "500");
        String pendingHash = GatewayWorkQueue.paymentEvidenceHash(binding, attempt, pending);
        assertThat(pendingHash).matches("[a-f0-9]{64}").isEqualTo(GatewayWorkQueue.paymentEvidenceHash(binding, attempt,
                payment(PaymentProviderAdapter.PaymentStatus.PENDING, false, null, "500.00")));
        assertThat(GatewayWorkQueue.paymentEvidenceHash(binding, attempt,
                payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, NOW, "500"))).isNotEqualTo(pendingHash);
        assertThat(GatewayWorkQueue.paymentEvidenceHash(binding, null, pending)).isNotEqualTo(pendingHash);
        assertThat(GatewayWorkQueue.paymentEvidenceHash(binding(UUID.randomUUID()), attempt, pending)).isNotEqualTo(pendingHash);
    }

    @Test void fullNanosecondFactsHaveDifferentV2HashesEvenWhenTheirMicrosecondProjectionsMatch() {
        UUID attempt = UUID.randomUUID();
        Instant first = Instant.parse("2026-10-10T05:59:59.123456499Z"), second = first.plusNanos(1);
        var one = payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, first, "500.00");
        var two = payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, second, "500.00");
        assertThat(GatewayExactInstant.floor(first)).isEqualTo(GatewayExactInstant.floor(second));
        assertThat(GatewayWorkQueue.paymentEvidenceHash(binding, attempt, one))
                .isNotEqualTo(GatewayWorkQueue.paymentEvidenceHash(binding, attempt, two))
                .isEqualTo(GatewayWorkQueue.paymentEvidenceHash(binding, attempt, one));
    }

    @Test void v2HashCannotDeduplicateOntoImmutableLegacyFactWithoutOriginalSource() throws Exception {
        UUID attempt = UUID.randomUUID();
        var observation = payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, NOW, "500.00");
        String oldCanonical = String.join("|", binding.organisationId().toString(), binding.schoolId().toString(), binding.providerConfigId().toString(),
                binding.providerCode(), binding.environment(), binding.merchantReference(), binding.credentialsReference(), attempt.toString(),
                observation.orderReference(), observation.paymentReference(), observation.amount().setScale(2).toPlainString(), observation.currencyCode(),
                observation.status().name(), Boolean.toString(observation.captured()), observation.providerPaidAt().toString(), observation.paymentMethod());
        var digest = MessageDigest.getInstance("SHA-256");
        String legacyHash = HexFormat.of().formatHex(digest.digest(oldCanonical.getBytes(StandardCharsets.UTF_8)));
        String exactHash = HexFormat.of().formatHex(digest.digest(("PAYMENT_GET_EXACT_V2|" + oldCanonical).getBytes(StandardCharsets.UTF_8)));
        assertThat(GatewayWorkQueue.paymentEvidenceHash(binding, attempt, observation)).isEqualTo(exactHash).isNotEqualTo(legacyHash);
    }

    @Test void fullSourceFutureByOneNanosecondCannotPassValidationThroughSharedProjection() {
        Instant observed = Instant.parse("2026-10-10T05:59:59.123456500Z");
        var source = payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, observed.plusNanos(1), "500.00");
        assertThat(GatewayExactInstant.floor(source.providerPaidAt())).isEqualTo(GatewayExactInstant.floor(observed));
        assertThatThrownBy(() -> GatewayWorkQueue.validatePayment(source, observed))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Minimal independently observed payment facts required").hasNoCause();
    }

    @Test void retentionWritesUnchangedExactSourceAlongsideExplicitFloorNotDriverRoundedTime() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var queue = new GatewayWorkQueue(jdbc, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        UUID retained = UUID.randomUUID();
        Instant source = Instant.parse("2026-10-09T23:59:59.999999500Z");
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.eq(Boolean.class))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Instant>>any())).thenReturn(NOW);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<UUID>>any())).thenAnswer(call -> {
            assertThat(call.getArgument(0, String.class)).contains("provider_paid_at,provider_paid_at_exact", ":paidAt,:paidAtExact");
            var parameters = call.getArgument(1, SqlParameterSource.class);
            assertThat(parameters.getValue("paidAtExact")).isEqualTo(source.toString());
            assertThat(parameters.getValue("paidAt")).isEqualTo(Instant.parse("2026-10-09T23:59:59.999999Z").atOffset(java.time.ZoneOffset.UTC));
            assertThat(parameters.getSqlType("paidAt")).isEqualTo(java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            return List.of(retained);
        });
        try (var ignored = TenantRequestContext.open(null, binding.organisationId())) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThat(queue.retainPaymentObservation(binding, null,
                    payment(PaymentProviderAdapter.PaymentStatus.SUCCESS, true, source, "500.00"))).isEqualTo(retained);
        }
    }

    @Test void retryDelaysAreBoundedPositiveAndDoNotOverflowForLongLivedRecovery() {
        assertThat(GatewayWorkQueue.retryDelay(1, new UUID(0, 59))).isEqualTo(119);
        assertThat(GatewayWorkQueue.retryDelay(2, new UUID(0, 59))).isEqualTo(179);
        assertThat(GatewayWorkQueue.retryDelay(1000000, new UUID(0, Long.MIN_VALUE))).isBetween(60L, 3600L);
        assertThat(GatewayWorkQueue.requireRetryDelay(Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(1));
        assertThatThrownBy(() -> GatewayWorkQueue.requireRetryDelay(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatewayWorkQueue.requireRetryDelay(Duration.ofDays(2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatewayWorkQueue.requireSafeCode("Gateway response contains customer data"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void absentWrongOrReadOnlyTenantTransactionFailsBeforeAnyDatabaseOperation() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var queue = new GatewayWorkQueue(jdbc, Clock.systemUTC());
        assertThatThrownBy(() -> queue.enqueueAttempt(binding, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        try (var ignored = TenantRequestContext.open(null, UUID.randomUUID())) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> queue.claimNextAttempt(binding)).isInstanceOf(IllegalStateException.class);
        }
        try (var ignored = TenantRequestContext.open(null, binding.organisationId())) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            assertThatThrownBy(() -> queue.enqueueAttempt(binding, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThatThrownBy(() -> queue.claimNextWebhook(binding)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(jdbc);
    }

    @Test void aHumanSessionCannotManufactureBackgroundProviderEvidenceOrDispatch() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var queue = new GatewayWorkQueue(jdbc, Clock.systemUTC());
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), binding.organisationId())) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> queue.claimNextAttempt(binding)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> queue.retainVerifiedWebhook(binding, event("PAYMENT_SUCCESS_WEBHOOK", null, "synthetic_order", null)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> queue.retainPaymentObservation(binding, null,
                    payment(PaymentProviderAdapter.PaymentStatus.PENDING, false, null, "500"))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> queue.markCreateDispatched(claim(GatewayWorkQueue.WorkKind.ATTEMPT, false))).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(jdbc);
    }

    @Test void webhookLeaseCannotBeUsedAsPermissionForProviderCreate() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var queue = new GatewayWorkQueue(jdbc, Clock.systemUTC());
        assertThatThrownBy(() -> queue.markCreateDispatched(claim(GatewayWorkQueue.WorkKind.WEBHOOK, false)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void settlementClaimsAndExpiredRecoveryUseOnlyTheirFixedMetadataTableAndNeverCreateDispatch(){
        var jdbc=mock(NamedParameterJdbcTemplate.class);var queue=new GatewayWorkQueue(jdbc,Clock.systemUTC());
        var statements=new java.util.ArrayList<String>();
        when(jdbc.queryForObject(anyString(),any(SqlParameterSource.class),org.mockito.ArgumentMatchers.eq(Boolean.class))).thenReturn(true);
        when(jdbc.queryForObject(anyString(),any(SqlParameterSource.class),ArgumentMatchers.<RowMapper<Instant>>any())).thenReturn(NOW);
        when(jdbc.query(anyString(),any(SqlParameterSource.class),ArgumentMatchers.<ResultSetExtractor<GatewayWorkQueue.Claim>>any())).thenAnswer(call->{
            statements.add(call.getArgument(0,String.class));return null;
        });
        try(var ignored=TenantRequestContext.open(null,binding.organisationId())){
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThat(queue.claimNextSettlement(binding)).isNull();assertThat(queue.recoverExpiredSettlementLease(binding)).isNull();
        }
        assertThat(statements).hasSize(2);
        assertThat(statements.getFirst()).contains("SELECT id FROM fee_gateway_settlement_work","UPDATE fee_gateway_settlement_work",
                "work.id=due.id","RETURNING work.id","false AS dispatched");
        assertThat(statements.getLast()).contains("SELECT id,lease_token","false AS dispatched FROM fee_gateway_settlement_work","status='LEASED'");
        statements.forEach(sql->assertThat(sql).doesNotContain("fee_gateway_attempt_work","fee_gateway_webhook_inbox","fee_gateway_refund_work",
                "refund_request_id","create_dispatched_at"));
    }

    @Test void nonmutatingSettlementAndWebhookClaimsCannotManufactureDispatchedFlagsOrCreatePermission(){
        var jdbc=mock(NamedParameterJdbcTemplate.class);var queue=new GatewayWorkQueue(jdbc,Clock.systemUTC());
        for(var kind:List.of(GatewayWorkQueue.WorkKind.SETTLEMENT,GatewayWorkQueue.WorkKind.WEBHOOK)){
            assertThatThrownBy(()->claim(kind,true)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->queue.markCreateDispatched(claim(kind,false))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(claim(GatewayWorkQueue.WorkKind.ATTEMPT,true).createDispatched()).isTrue();
        assertThat(claim(GatewayWorkQueue.WorkKind.REFUND,true).createDispatched()).isTrue();verifyNoInteractions(jdbc);
    }

    @Test void settlementClaimAndLeaseRecoveryRejectHumanWrongTenantAndReadOnlyScopesBeforeSql(){
        var jdbc=mock(NamedParameterJdbcTemplate.class);var queue=new GatewayWorkQueue(jdbc,Clock.systemUTC());
        assertThatThrownBy(()->queue.claimNextSettlement(binding)).isInstanceOf(IllegalStateException.class);
        try(var ignored=TenantRequestContext.open(UUID.randomUUID(),binding.organisationId())){
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(()->queue.claimNextSettlement(binding)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->queue.recoverExpiredSettlementLease(binding)).isInstanceOf(IllegalStateException.class);
        }
        try(var ignored=TenantRequestContext.open(null,UUID.randomUUID())){
            assertThatThrownBy(()->queue.claimNextSettlement(binding)).isInstanceOf(IllegalStateException.class);
        }
        try(var ignored=TenantRequestContext.open(null,binding.organisationId())){
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThatThrownBy(()->queue.claimNextSettlement(binding)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->queue.recoverExpiredSettlementLease(binding)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(jdbc);
    }

    private GatewayWorkQueue.Claim claim(GatewayWorkQueue.WorkKind kind, boolean dispatched) {
        return new GatewayWorkQueue.Claim(kind, UUID.randomUUID(), binding, UUID.randomUUID(), NOW, NOW.plusSeconds(90), 1, dispatched);
    }
    private static GatewayWorkQueue.ProviderBinding binding(UUID org) {
        return new GatewayWorkQueue.ProviderBinding(org, UUID.randomUUID(), UUID.randomUUID(), "CASHFREE", "TEST", "synthetic_merchant", "synthetic:not-enabled");
    }
    private static PaymentProviderAdapter.VerifiedWebhook event(String type, String checkout, String order, String refund) {
        return new PaymentProviderAdapter.VerifiedWebhook("CASHFREE", "a".repeat(64), type, checkout, order, "synthetic_payment", refund);
    }
    private static PaymentProviderAdapter.PaymentObservation payment(PaymentProviderAdapter.PaymentStatus status, boolean captured, Instant paid, String amount) {
        return new PaymentProviderAdapter.PaymentObservation("synthetic_order", "synthetic_payment", new BigDecimal(amount), "INR", status, captured, paid, "upi");
    }
}

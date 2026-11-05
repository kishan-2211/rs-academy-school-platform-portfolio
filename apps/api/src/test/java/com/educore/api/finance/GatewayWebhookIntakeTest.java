package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.tenancy.TenantRequestContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GatewayWebhookIntakeTest {
    private static final String ROUTE = "a".repeat(64), SECRET = "synthetic-webhook-secret-not-live", TIME = "1600000000000";
    private final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), provider = UUID.randomUUID(), inbox = UUID.randomUUID();
    private final GatewayProviderRegistry registry = mock(GatewayProviderRegistry.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GatewayWorkQueue queue = mock(GatewayWorkQueue.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final HttpClient client = mock(HttpClient.class);
    private final GatewayProviderRegistry.Route route = new GatewayProviderRegistry.Route(ROUTE, org, school, provider,
            "CASHFREE", CashfreePaymentProviderAdapter.Environment.TEST, "synthetic_merchant", "synthetic:not-enabled",
            "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
    private final GatewayWebhookIntake service = new GatewayWebhookIntake(registry, jdbc, queue, manager);

    @AfterEach void cleanOnlyTestMarkers() {
        TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void authenticRawUtf8BodyBecomesOnlyMinimalEvidenceAfterCommitWithoutHttpOrFinancialWrites() throws Exception {
        ready(); byte[] body = body(); byte[] original = body.clone();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(null, org));
            var binding = call.getArgument(0, GatewayWorkQueue.ProviderBinding.class);
            var event = call.getArgument(1, PaymentProviderAdapter.VerifiedWebhook.class);
            assertThat(binding.providerConfigId()).isEqualTo(provider);
            assertThat(binding.credentialsReference()).isEqualTo("synthetic:not-enabled");
            assertThat(event.bodySha256()).isEqualTo(hash(original));
            assertThat(event.paymentReference()).isEqualTo("synthetic_payment");
            assertThat(event.toString()).doesNotContain("synthetic-private-नाम", SECRET);
            return inbox;
        }).when(queue).retainVerifiedWebhook(any(), any());
        var receipt = service.accept(ROUTE, body, sign(body), TIME);
        assertThat(receipt.inboxId()).isEqualTo(inbox);
        assertThat(receipt.toString()).doesNotContain(inbox.toString(), org.toString());
        assertThat(body).isEqualTo(original);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(manager).commit(any());
        verify(client, never()).send(any(), any());
    }

    @Test void forgedOrMutatedRawBytesAndTimestampFailBeforeAnyDatabaseTransaction() throws Exception {
        ready(); byte[] body = body(); String signed = sign(body);
        rejects(body, "A".repeat(43) + "=", TIME);
        rejects((new String(body, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8), signed, TIME);
        rejects(body, signed, "1600000000001");
        rejects(new String(body, StandardCharsets.UTF_8).replace("नाम", "name").getBytes(StandardCharsets.UTF_8), signed, TIME);
        verifyNoInteractions(jdbc, queue, manager);
        verify(client, never()).send(any(), any());
    }

    @Test void authenticUnsupportedDuplicateAndInconsistentEventsFailBeforeDatabaseWork() throws Exception {
        ready();
        for (String malformed : java.util.List.of("{\"type\":\"SETTLEMENT_SUCCESS\",\"data\":{}}",
                new String(body(), StandardCharsets.UTF_8).replace("\"type\":", "\"type\":\"PAYMENT_FAILED_WEBHOOK\",\"type\":"),
                new String(body(), StandardCharsets.UTF_8).replace("\"payment_status\":\"SUCCESS\"", "\"payment_status\":\"FAILED\""))) {
            byte[] wire = malformed.getBytes(StandardCharsets.UTF_8); rejects(wire, sign(wire), TIME);
        }
        verifyNoInteractions(jdbc, queue, manager);
    }

    @Test void disabledUnknownCreationOnlyAndLiveNotOptedInRoutesCannotResolveCredentials() {
        when(registry.workerEnabled()).thenReturn(false);
        rejects(body(), "ignored", TIME);
        when(registry.workerEnabled()).thenReturn(true); when(registry.findRoute(ROUTE)).thenReturn(Optional.empty());
        rejects(body(), "ignored", TIME);
        var live = new GatewayProviderRegistry.Route(ROUTE, org, school, provider, "CASHFREE", CashfreePaymentProviderAdapter.Environment.LIVE,
                "synthetic_merchant", "synthetic:not-enabled", "env:APP_FINANCE_GATEWAY_CASHFREE_LIVE_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_LIVE_CLIENT_SECRET");
        when(registry.findRoute(ROUTE)).thenReturn(Optional.of(live)); rejects(body(), "ignored", TIME);
        verify(registry, never()).provider(any()); verifyNoInteractions(jdbc, queue, manager);
    }

    @Test void nullEmptyAndOversizeBodiesAreRejectedBeforeRouteResolutionOrCopy() {
        when(registry.workerEnabled()).thenReturn(true);
        for (byte[] wire : new byte[][] {null, new byte[0], new byte[GatewayWebhookIntake.MAX_RAW_BODY_BYTES + 1]}) rejects(wire, "ignored", TIME);
        verify(registry, never()).findRoute(any()); verifyNoInteractions(jdbc, queue, manager);
    }

    @Test void ambientHumanSystemOrTransactionCannotBeReplacedByCallbackAuthority() {
        when(registry.workerEnabled()).thenReturn(true);
        for (UUID user : new UUID[] {null, UUID.randomUUID()}) {
            try (var ignored = TenantRequestContext.open(user, UUID.randomUUID())) {
                var previous = TenantRequestContext.current(); rejects(body(), "ignored", TIME);
                assertThat(TenantRequestContext.current()).isEqualTo(previous);
            }
        }
        TransactionSynchronizationManager.setActualTransactionActive(true); rejects(body(), "ignored", TIME);
        TransactionSynchronizationManager.clear();
        verify(registry, never()).findRoute(any()); verifyNoInteractions(jdbc, queue, manager);
    }

    @Test void replacementAdapterCannotChangeProviderHashOrUnsupportedMinimalFields() {
        when(registry.workerEnabled()).thenReturn(true); when(registry.findRoute(ROUTE)).thenReturn(Optional.of(route));
        var adapter = mock(PaymentProviderAdapter.class); when(registry.provider(route)).thenReturn(adapter);
        for (var event : java.util.List.of(new PaymentProviderAdapter.VerifiedWebhook("OTHER", hash(body()), "PAYMENT_SUCCESS_WEBHOOK", null, "synthetic_order", "synthetic_payment", null),
                new PaymentProviderAdapter.VerifiedWebhook("CASHFREE", "a".repeat(64), "PAYMENT_SUCCESS_WEBHOOK", null, "synthetic_order", "synthetic_payment", null),
                new PaymentProviderAdapter.VerifiedWebhook("CASHFREE", hash(body()), "PAYMENT_SUCCESS_WEBHOOK", null, "synthetic_order", "synthetic_payment", "synthetic_refund"))) {
            when(adapter.verifyWebhook(any(), anyString(), anyString())).thenReturn(event); rejects(body(), "ignored", TIME);
        }
        verifyNoInteractions(jdbc, queue, manager);
    }

    @Test void ambientSynchronizationAndBoundResourcesArePreservedAndNeverBecomeCallbackAuthority() {
        when(registry.workerEnabled()).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            rejects(body(), "ignored", TIME);
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        Object key = new Object(), resource = new Object();
        TransactionSynchronizationManager.bindResource(key, resource);
        try {
            rejects(body(), "ignored", TIME);
            assertThat(TransactionSynchronizationManager.getResource(key)).isSameAs(resource);
        } finally { TransactionSynchronizationManager.unbindResource(key); }
        verify(registry, never()).findRoute(any()); verifyNoInteractions(jdbc, queue, manager);
    }

    @Test void authenticAndForgedRetriesReuseOneTrustedAdapterWithoutHttpOrAnotherCredentialClient() throws Exception {
        ready(); byte[] wire = body();
        rejects(wire, "A".repeat(43) + "=", TIME);
        var first = service.accept(ROUTE, wire, sign(wire), TIME);
        var second = service.accept(ROUTE, wire, sign(wire), TIME);
        assertThat(first.inboxId()).isEqualTo(second.inboxId());
        verify(registry, times(1)).provider(route);
        verify(client, never()).send(any(), any());
        assertThat(wire).isEqualTo(body());
    }

    @Test void alteredAdapterInputCannotRedefineTheOriginalRawBodyHash() {
        when(registry.workerEnabled()).thenReturn(true); when(registry.findRoute(ROUTE)).thenReturn(Optional.of(route));
        var adapter = mock(PaymentProviderAdapter.class); when(registry.provider(route)).thenReturn(adapter);
        when(adapter.verifyWebhook(any(), anyString(), anyString())).thenAnswer(call -> {
            byte[] copy = call.getArgument(0); copy[0] = (byte) ' ';
            return new PaymentProviderAdapter.VerifiedWebhook("CASHFREE", hash(copy), "PAYMENT_SUCCESS_WEBHOOK", null, "synthetic_order", "synthetic_payment", null);
        });
        byte[] original = body(); rejects(original, "ignored", TIME); assertThat(original).isEqualTo(body());
        verifyNoInteractions(jdbc, queue, manager);
    }

    @Test void databaseScopeOrRotatedFrozenBindingFailsClosedAndRollsBackWithoutAcknowledgement() throws Exception {
        ready(); when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(false);
        rejects(body(), sign(body()), TIME); verify(manager).rollback(any()); verifyNoInteractions(queue);
        ready(); when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<ResultSetExtractor<UUID>>any())).thenReturn(null);
        rejects(body(), sign(body()), TIME); verify(manager, times(2)).rollback(any()); verifyNoInteractions(queue);
    }

    @Test void inboxFailureOrCommitFailureNeverAcknowledgesAndDoesNotLeakRawDataOrSecrets() throws Exception {
        ready(); doThrow(new IllegalStateException(SECRET + " synthetic-private-नाम")).when(queue).retainVerifiedWebhook(any(), any());
        rejects(body(), sign(body()), TIME); verify(manager).rollback(any());
        ready(); doReturn(inbox).when(queue).retainVerifiedWebhook(any(), any());
        doThrow(new IllegalStateException(SECRET)).when(manager).commit(any());
        rejects(body(), sign(body()), TIME);
        verify(manager).commit(any());
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    private void ready() {
        when(registry.workerEnabled()).thenReturn(true); when(registry.findRoute(ROUTE)).thenReturn(Optional.of(route));
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        var adapter = new CashfreePaymentProviderAdapter(route.environment(), "synthetic-client-not-live", SECRET,
                URI.create("https://synthetic-school.example.invalid"), Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC), client);
        when(registry.provider(route)).thenReturn(adapter);
        doAnswer(call -> {
            var definition = call.getArgument(0, TransactionDefinition.class);
            assertThat(definition.isReadOnly()).isFalse();
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            TransactionSynchronizationManager.setActualTransactionActive(true); return new SimpleTransactionStatus();
        }).when(manager).getTransaction(any());
        doAnswer(call -> { TransactionSynchronizationManager.setActualTransactionActive(false); return null; }).when(manager).commit(any());
        doAnswer(call -> { TransactionSynchronizationManager.setActualTransactionActive(false); return null; }).when(manager).rollback(any());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(true);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<ResultSetExtractor<UUID>>any())).thenReturn(provider);
        doReturn(inbox).when(queue).retainVerifiedWebhook(any(), any());
    }
    private void rejects(byte[] body, String signature, String timestamp) {
        assertThatThrownBy(() -> service.accept(ROUTE, body, signature, timestamp)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Gateway webhook intake requires its trusted route, authentic bounded body and committed scoped inbox")
                .hasNoCause();
    }
    private static byte[] body() {
        return "{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":{\"order_id\":\"synthetic_order\"},\"payment\":{\"cf_payment_id\":\"synthetic_payment\",\"payment_status\":\"SUCCESS\"},\"customer_details\":{\"customer_name\":\"synthetic-private-नाम\"}}}".getBytes(StandardCharsets.UTF_8);
    }
    private static String sign(byte[] body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(TIME.getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
    private static String hash(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

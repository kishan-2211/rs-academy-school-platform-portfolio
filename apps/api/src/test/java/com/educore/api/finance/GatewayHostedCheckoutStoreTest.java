package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.finance.PaymentProviderAdapter.CheckoutObservation;
import com.educore.api.finance.PaymentProviderAdapter.CheckoutStatus;
import com.educore.api.finance.PaymentProviderAdapter.HostedCheckout;
import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Focused mocked private URL storage only; not real PostgreSQL, merchant, or family acceptance. */
class GatewayHostedCheckoutStoreTest {
    private static final String ROUTE = "c".repeat(64);
    private static final UUID ORGANISATION = UUID.fromString("00000000-0000-4000-8000-000000000901");
    private static final UUID SCHOOL = UUID.fromString("00000000-0000-4000-8000-000000000902");
    private static final UUID PROVIDER = UUID.fromString("00000000-0000-4000-8000-000000000903");
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000904");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000905");
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00.123456789Z");
    private static final URI TEST_URI = URI.create("https://payments-test.cashfree.com/links/synthetic_private_checkout");
    private static final URI LIVE_URI = URI.create("https://payments.cashfree.com/links/synthetic_private_checkout");
    private final GatewayProviderRegistry registry = mock(GatewayProviderRegistry.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final SensitiveValueCipher cipher = mock(SensitiveValueCipher.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final GatewayHostedCheckoutStore store = new GatewayHostedCheckoutStore(registry, jdbc, cipher, manager);

    @AfterEach void clearOnlyTestState() {
        TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test void acceptsOnlyTheExactEnvironmentHttpsHostedPathIncludingOneAndHundredCharacterIds() {
        for (String identifier : List.of("a", "A_0-9", "a".repeat(100))) {
            assertThatCode(() -> GatewayHostedCheckoutStore.requireHostedUri(binding("TEST"),
                    URI.create("https://payments-test.cashfree.com/links/" + identifier))).doesNotThrowAnyException();
            assertThatCode(() -> GatewayHostedCheckoutStore.requireHostedUri(binding("LIVE"),
                    URI.create("https://payments.cashfree.com/links/" + identifier))).doesNotThrowAnyException();
        }
        verifyNoInteractions(registry, jdbc, cipher, manager);
    }

    @Test void rejectsCrossEnvironmentAndLookalikeHostsWithoutPermittingProviderRedirects() {
        for (URI uri : List.of(LIVE_URI, URI.create("https://cashfree.com/links/synthetic"),
                URI.create("https://payments-test.cashfree.com.evil.example/links/synthetic"),
                URI.create("https://evilpayments-test.cashfree.com/links/synthetic"),
                URI.create("https://payments-test.cashfree.com./links/synthetic"))) {
            assertThatThrownBy(() -> GatewayHostedCheckoutStore.requireHostedUri(binding("TEST"), uri))
                    .isInstanceOf(RuntimeException.class).hasNoCause();
        }
        assertThatThrownBy(() -> GatewayHostedCheckoutStore.requireHostedUri(binding("LIVE"), TEST_URI))
                .isInstanceOf(RuntimeException.class).hasNoCause();
        verifyNoInteractions(registry, jdbc, cipher, manager);
    }

    @Test void rejectsPortsUserInfoQueryFragmentsAndNonHttpsHostedAddresses() {
        for (String address : List.of("http://payments-test.cashfree.com/links/synthetic",
                "ftp://payments-test.cashfree.com/links/synthetic",
                "https://payments-test.cashfree.com:443/links/synthetic",
                "https://payments-test.cashfree.com:8443/links/synthetic",
                "https://synthetic-user:synthetic-secret@payments-test.cashfree.com/links/synthetic",
                "https://payments-test.cashfree.com/links/synthetic?token=synthetic-private-token",
                "https://payments-test.cashfree.com/links/synthetic?",
                "https://payments-test.cashfree.com/links/synthetic#synthetic-private-fragment",
                "https://payments-test.cashfree.com/links/synthetic#")) {
            assertThatThrownBy(() -> GatewayHostedCheckoutStore.requireHostedUri(binding("TEST"), URI.create(address)))
                    .isInstanceOf(RuntimeException.class).hasNoCause()
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(address,
                            "synthetic-secret", "synthetic-private-token", "synthetic-private-fragment"));
        }
        verifyNoInteractions(registry, jdbc, cipher, manager);
    }

    @Test void rejectsEmptyEncodedUnicodeOrOverlongPathsInsteadOfNormalizingThemIntoAuthority() {
        for (String path : List.of("", "/", "/links/", "/links/" + "a".repeat(101),
                "/links/synthetic/", "/links/synthetic/next", "/links/../synthetic", "/Links/synthetic",
                "/links/synthetic%2Fnext", "/links/%73ynthetic", "/links/परीक्षण", "//links/synthetic")) {
            URI uri = URI.create("https://payments-test.cashfree.com" + path);
            assertThatThrownBy(() -> GatewayHostedCheckoutStore.requireHostedUri(binding("TEST"), uri))
                    .isInstanceOf(RuntimeException.class).hasNoCause();
        }
        assertThatThrownBy(() -> GatewayHostedCheckoutStore.requireHostedUri(binding("TEST"), null))
                .isInstanceOf(RuntimeException.class).hasNoCause();
        verifyNoInteractions(registry, jdbc, cipher, manager);
    }

    @Test void associatedDataSeparatesSchoolOrganisationProviderAndAttemptIdentity() {
        String original = GatewayHostedCheckoutStore.associatedData(binding("TEST"), ATTEMPT);
        assertThat(original).contains(ORGANISATION.toString(), SCHOOL.toString(), ATTEMPT.toString());
        for (var changed : List.of(
                new GatewayWorkQueue.ProviderBinding(OTHER, SCHOOL, PROVIDER, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:hosted-credentials"),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, OTHER, PROVIDER, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:hosted-credentials"),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, OTHER, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:hosted-credentials"))) {
            assertThat(GatewayHostedCheckoutStore.associatedData(changed, ATTEMPT)).isNotEqualTo(original);
        }
        assertThat(GatewayHostedCheckoutStore.associatedData(binding("TEST"), OTHER)).isNotEqualTo(original);
        verifyNoInteractions(registry, jdbc, cipher, manager);
    }

    @Test void disabledWorkersCannotPersistEitherCreateOrReadUrlsEvenWithCreationEnabled() {
        configure("TEST");
        when(registry.creationEnabled()).thenReturn(true);
        when(registry.workerEnabled()).thenReturn(false);
        rejectCreate(claim(binding("TEST")), hosted(TEST_URI), NOW);
        assertThatThrownBy(() -> store.retainGet(ROUTE, claim(binding("TEST")), read(TEST_URI)))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
        verifyNoInteractions(jdbc, cipher, manager);
        verify(registry, never()).provider(any());
    }

    @Test void liveWorkerNeedsIndependentLiveEnablementAndCannotUseTestUri() {
        configure("LIVE");
        when(registry.liveEnabled()).thenReturn(false);
        rejectCreate(claim(binding("LIVE")), hosted(LIVE_URI), NOW);
        verifyNoInteractions(jdbc, cipher, manager);
        verify(registry, never()).provider(any());
    }

    @Test void unknownRouteAndChangedFrozenBindingFailBeforeDatabaseOrEncryption() {
        configure("TEST");
        when(registry.findRoute(ROUTE)).thenReturn(Optional.empty());
        rejectCreate(claim(binding("TEST")), hosted(TEST_URI), NOW);
        configure("TEST");
        for (var changed : List.of(
                new GatewayWorkQueue.ProviderBinding(OTHER, SCHOOL, PROVIDER, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:hosted-credentials"),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, OTHER, PROVIDER, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:hosted-credentials"),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, OTHER, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:hosted-credentials"),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER, "OTHER", "TEST", "synthetic_merchant", "synthetic:hosted-credentials"),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER, "CASHFREE", "LIVE", "synthetic_merchant", "synthetic:hosted-credentials"),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER, "CASHFREE", "TEST", "changed_merchant", "synthetic:hosted-credentials"),
                new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:changed-credentials"))) {
            rejectCreate(claim(changed), hosted(TEST_URI), NOW);
        }
        verifyNoInteractions(jdbc, cipher, manager);
        verify(registry, never()).provider(any());
    }

    @Test void webhookClaimsAndMissingCreateInputCannotBecomeHostedCheckoutAuthority() {
        configure("TEST");
        var webhook = new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.WEBHOOK, ATTEMPT, binding("TEST"),
                OTHER, NOW.minusSeconds(1), NOW.plusSeconds(89), 1, false);
        rejectCreate(webhook, hosted(TEST_URI), NOW);
        rejectCreate(null, hosted(TEST_URI), NOW);
        rejectCreate(claim(binding("TEST")), null, NOW);
        rejectCreate(claim(binding("TEST")), new HostedCheckout(OTHER.toString(), TEST_URI), NOW);
        rejectCreate(claim(binding("TEST")), new HostedCheckout(null, TEST_URI), NOW);
        rejectCreate(claim(binding("TEST")), new HostedCheckout(ATTEMPT.toString(), null), NOW);
        rejectCreate(claim(binding("TEST")), hosted(TEST_URI), null);
        verifyNoInteractions(jdbc, cipher, manager);
        verify(registry, never()).provider(any());
    }

    @Test void malformedCreateUrlIsRejectedWithoutOpeningPersistenceOrExposingIt() {
        configure("TEST");
        URI privateAddress = URI.create("https://synthetic-user:synthetic-secret@payments-test.cashfree.com/links/synthetic?token=synthetic-private-token");
        assertThatThrownBy(() -> store.retainCreate(ROUTE, claim(binding("TEST")), hosted(privateAddress), NOW))
                .isInstanceOf(IllegalStateException.class).hasNoCause()
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(privateAddress.toString(),
                        "synthetic-secret", "synthetic-private-token"));
        verifyNoInteractions(jdbc, cipher, manager);
        verify(registry, never()).provider(any());
    }

    @Test void ambientHumanAndSystemContextsAreRejectedWithoutReplacementOrDatabaseAccess() {
        configure("TEST");
        for (UUID user : Arrays.asList(OTHER, null)) {
            try (var ignored = TenantRequestContext.open(user, ORGANISATION)) {
                var before = TenantRequestContext.current();
                rejectCreate(claim(binding("TEST")), hosted(TEST_URI), NOW);
                assertThatThrownBy(() -> store.retainGet(ROUTE, claim(binding("TEST")), read(TEST_URI)))
                        .isInstanceOf(IllegalStateException.class).hasNoCause();
                assertThat(TenantRequestContext.current()).isEqualTo(before);
            }
        }
        verifyNoInteractions(jdbc, cipher, manager);
        verify(registry, never()).provider(any());
    }

    @Test void ambientTransactionAndSynchronizationAreRejectedWithoutClearingCallerState() {
        configure("TEST");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        rejectCreate(claim(binding("TEST")), hosted(TEST_URI), NOW);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.initSynchronization();
        try {
            rejectCreate(claim(binding("TEST")), hosted(TEST_URI), NOW);
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        verifyNoInteractions(jdbc, cipher, manager);
        verify(registry, never()).provider(any());
    }

    @Test void ambientBoundResourceIsRejectedAndRemainsOwnedByCaller() {
        configure("TEST");
        Object key = new Object(), value = new Object();
        TransactionSynchronizationManager.bindResource(key, value);
        try {
            rejectCreate(claim(binding("TEST")), hosted(TEST_URI), NOW);
            assertThat(TransactionSynchronizationManager.getResource(key)).isSameAs(value);
        } finally { TransactionSynchronizationManager.unbindResource(key); }
        verifyNoInteractions(jdbc, cipher, manager);
        verify(registry, never()).provider(any());
    }

    private void configure(String environment) {
        when(registry.workerEnabled()).thenReturn(true);
        when(registry.liveEnabled()).thenReturn(true);
        var route = new GatewayProviderRegistry.Route(ROUTE, ORGANISATION, SCHOOL, PROVIDER, "CASHFREE",
                CashfreePaymentProviderAdapter.Environment.valueOf(environment), "synthetic_merchant", "synthetic:hosted-credentials",
                "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_ID",
                "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_SECRET");
        when(registry.findRoute(ROUTE)).thenReturn(Optional.of(route));
    }

    private void rejectCreate(GatewayWorkQueue.Claim claim, HostedCheckout checkout, Instant observedAt) {
        assertThatThrownBy(() -> store.retainCreate(ROUTE, claim, checkout, observedAt))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    private static GatewayWorkQueue.ProviderBinding binding(String environment) {
        return new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER, "CASHFREE", environment,
                "synthetic_merchant", "synthetic:hosted-credentials");
    }

    private static GatewayWorkQueue.Claim claim(GatewayWorkQueue.ProviderBinding binding) {
        return new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, ATTEMPT, binding, OTHER,
                NOW.minusSeconds(1), NOW.plusSeconds(89), 1, false);
    }

    private static HostedCheckout hosted(URI uri) { return new HostedCheckout(ATTEMPT.toString(), uri); }

    private static GatewayProviderDiscovery.ReadResult read(URI uri) {
        var checkout = new CheckoutObservation(ATTEMPT.toString(), ATTEMPT.toString(), new BigDecimal("1000.00"),
                "INR", BigDecimal.ZERO, CheckoutStatus.CREATED, NOW.plusSeconds(1800), List.of("synthetic_order"));
        return new GatewayProviderDiscovery.ReadResult(new GatewayDiscoveryEvidence.Discovery(binding("TEST"), checkout,
                List.of(GatewayDiscoveryEvidence.OrderRead.unknown("synthetic_order")), NOW), Optional.of(hosted(uri)));
    }
}

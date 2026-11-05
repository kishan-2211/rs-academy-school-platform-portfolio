package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class GatewayProviderRegistryTest {
    private static final String KEY = "79f819428fb6492996a750b3e43b30e20ffb9408ad1569be8d19f5a307ca2f86";
    private static final URI ORIGIN = URI.create("https://school.example.invalid/");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID ORGANISATION = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID SCHOOL = UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final UUID PROVIDER = UUID.fromString("00000000-0000-4000-8000-000000000103");

    @Test void disabledEmptyConfigurationDoesNotResolveSecretsOrCreateProviders() {
        var factory = mock(GatewayProviderRegistry.ProviderFactory.class);
        var registry = new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false, false, false, false, null),
                URI.create("http://localhost:3000"), CLOCK, ignored -> { throw new AssertionError("No startup lookup"); }, factory);
        assertThat(registry.creationEnabled()).isFalse();
        assertThat(registry.workerEnabled()).isFalse();
        assertThat(registry.liveEnabled()).isFalse();
        assertThat(registry.refundsEnabled()).isFalse();
        assertThat(registry.settlementsEnabled()).isFalse();
        assertThat(registry.settlementsAllowed(null)).isFalse();
        assertThat(registry.configuredRoutes()).isEmpty();
        assertThat(registry.findRoute(null)).isEmpty();
        assertThat(registry.findRoute(KEY)).isEmpty();
        verifyNoInteractions(factory);
    }

    @Test void bindsTypedRouteListWithIndependentFlagsAndNonSecretReferences() {
        var properties = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("app.finance.gateway.worker-enabled", "true"),
                Map.entry("app.finance.gateway.routes[0].route-key", KEY),
                Map.entry("app.finance.gateway.routes[0].organisation-id", ORGANISATION.toString()),
                Map.entry("app.finance.gateway.routes[0].school-id", SCHOOL.toString()),
                Map.entry("app.finance.gateway.routes[0].provider-config-id", PROVIDER.toString()),
                Map.entry("app.finance.gateway.routes[0].provider-code", "CASHFREE"),
                Map.entry("app.finance.gateway.routes[0].environment", "TEST"),
                Map.entry("app.finance.gateway.routes[0].merchant-account-reference", "synthetic-merchant"),
                Map.entry("app.finance.gateway.routes[0].credentials-secret-reference", "secretstore:finance/synthetic-cashfree"),
                Map.entry("app.finance.gateway.routes[0].client-id-environment-reference", reference("TEST", "CLIENT_ID")),
                Map.entry("app.finance.gateway.routes[0].client-secret-environment-reference", reference("TEST", "CLIENT_SECRET"))));
        var settings = new Binder(properties).bind("app.finance.gateway", Bindable.of(GatewayProviderRegistry.Settings.class)).get();
        assertThat(settings.creationEnabled()).isFalse();
        assertThat(settings.workerEnabled()).isTrue();
        assertThat(settings.liveEnabled()).isFalse();
        assertThat(settings.refundsEnabled()).isFalse();
        assertThat(settings.settlementsEnabled()).isFalse();
        assertThat(settings.testAllowLoopback()).isFalse();
        assertThat(settings.routes()).containsExactly(route("TEST"));
        assertThat(settings.routes().getFirst().exclusiveSchoolMerchantConfirmed()).isFalse();
        assertThat(settings.toString()).doesNotContain(KEY, "secretstore:");
        assertThat(settings.routes().getFirst().toString()).doesNotContain(KEY, "secretstore:");
    }

    @Test void refundsRequireTheirOwnExplicitConfigurationWithoutActivatingCheckoutOrLiveMode() {
        var settings=new Binder(new MapConfigurationPropertySource(Map.of("app.finance.gateway.refunds-enabled","true")))
                .bind("app.finance.gateway",Bindable.of(GatewayProviderRegistry.Settings.class)).get();
        assertThat(settings.refundsEnabled()).isTrue();assertThat(settings.creationEnabled()).isFalse();
        assertThat(settings.workerEnabled()).isFalse();assertThat(settings.liveEnabled()).isFalse();
        assertThat(new GatewayProviderRegistry.Settings(true,true,true,false,List.of()).refundsEnabled()).isFalse();
    }

    @Test void settlementsAndExclusiveConfirmationDefaultFalseForEveryExistingConstructor(){
        var oldCheckout=new GatewayProviderRegistry.Settings(true,true,true,false,List.of(route("TEST")));
        var oldRefunds=new GatewayProviderRegistry.Settings(true,true,true,false,List.of(route("TEST")),true);
        assertThat(oldCheckout.settlementsEnabled()).isFalse();assertThat(oldRefunds.settlementsEnabled()).isFalse();
        assertThat(oldRefunds.refundsEnabled()).isTrue();assertThat(route("TEST").exclusiveSchoolMerchantConfirmed()).isFalse();
        var onlyReports=new Binder(new MapConfigurationPropertySource(Map.of("app.finance.gateway.settlements-enabled","true")))
                .bind("app.finance.gateway",Bindable.of(GatewayProviderRegistry.Settings.class)).get();
        assertThat(onlyReports.settlementsEnabled()).isTrue();assertThat(onlyReports.workerEnabled()).isFalse();
        assertThat(onlyReports.creationEnabled()).isFalse();assertThat(onlyReports.refundsEnabled()).isFalse();assertThat(onlyReports.liveEnabled()).isFalse();
    }

    @Test void typedExplicitSettlementAndExclusiveSchoolMerchantSettingsDoNotActivateOtherPaymentPermissions(){
        var properties=new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("app.finance.gateway.worker-enabled","true"),Map.entry("app.finance.gateway.settlements-enabled","true"),
                Map.entry("app.finance.gateway.routes[0].route-key",KEY),
                Map.entry("app.finance.gateway.routes[0].organisation-id",ORGANISATION.toString()),
                Map.entry("app.finance.gateway.routes[0].school-id",SCHOOL.toString()),
                Map.entry("app.finance.gateway.routes[0].provider-config-id",PROVIDER.toString()),
                Map.entry("app.finance.gateway.routes[0].provider-code","CASHFREE"),Map.entry("app.finance.gateway.routes[0].environment","TEST"),
                Map.entry("app.finance.gateway.routes[0].merchant-account-reference","synthetic-merchant"),
                Map.entry("app.finance.gateway.routes[0].credentials-secret-reference","secretstore:finance/synthetic-cashfree"),
                Map.entry("app.finance.gateway.routes[0].client-id-environment-reference",reference("TEST","CLIENT_ID")),
                Map.entry("app.finance.gateway.routes[0].client-secret-environment-reference",reference("TEST","CLIENT_SECRET")),
                Map.entry("app.finance.gateway.routes[0].exclusive-school-merchant-confirmed","true")));
        var settings=new Binder(properties).bind("app.finance.gateway",Bindable.of(GatewayProviderRegistry.Settings.class)).get();
        var factory=mock(GatewayProviderRegistry.ProviderFactory.class);
        var registry=new GatewayProviderRegistry(settings,ORIGIN,CLOCK,ignored->{throw new AssertionError("Policy must not resolve credentials");},factory);
        assertThat(settings.routes()).containsExactly(exclusive(route("TEST"),true));
        assertThat(registry.settlementsAllowed(settings.routes().getFirst())).isTrue();
        assertThat(registry.creationEnabled()).isFalse();assertThat(registry.refundsEnabled()).isFalse();assertThat(registry.liveEnabled()).isFalse();
        verifyNoInteractions(factory);
    }

    @Test void settlementPolicyRequiresWorkerIndependentFlagAndExclusiveConfirmationWithoutAnyLookup(){
        var confirmed=exclusive(route("TEST"),true);var factory=mock(GatewayProviderRegistry.ProviderFactory.class);
        for(var settings:List.of(new GatewayProviderRegistry.Settings(false,false,false,false,List.of(confirmed),false,true),
                new GatewayProviderRegistry.Settings(true,true,true,false,List.of(confirmed),true,false),
                new GatewayProviderRegistry.Settings(true,true,true,false,List.of(route("TEST")),true,true))){
            var registry=new GatewayProviderRegistry(settings,ORIGIN,CLOCK,ignored->{throw new AssertionError("No policy credential lookup");},factory);
            assertThat(registry.settlementsAllowed(settings.routes().getFirst())).isFalse();
        }
        var enabled=new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,true,false,false,List.of(confirmed),false,true),
                ORIGIN,CLOCK,ignored->{throw new AssertionError("No policy credential lookup");},factory);
        assertThat(enabled.settlementsAllowed(confirmed)).isTrue();
        assertThat(enabled.creationEnabled()).isFalse();assertThat(enabled.refundsEnabled()).isFalse();verifyNoInteractions(factory);
    }

    @Test void settlementPolicyRejectsNullUnconfiguredAndForgedRoutesEvenWithAConfirmedLookingFlag(){
        var confirmed=exclusive(route("TEST"),true);var factory=mock(GatewayProviderRegistry.ProviderFactory.class);
        var registry=new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,true,false,false,List.of(confirmed),false,true),
                ORIGIN,CLOCK,ignored->{throw new AssertionError("No policy credential lookup");},factory);
        assertThat(registry.settlementsAllowed(null)).isFalse();assertThat(registry.settlementsAllowed(route("TEST"))).isFalse();
        var forgedSchool=new GatewayProviderRegistry.Route(KEY,ORGANISATION,UUID.randomUUID(),PROVIDER,"CASHFREE",confirmed.environment(),
                confirmed.merchantAccountReference(),confirmed.credentialsSecretReference(),confirmed.clientIdEnvironmentReference(),confirmed.clientSecretEnvironmentReference(),true);
        var forgedMerchant=new GatewayProviderRegistry.Route(KEY,ORGANISATION,SCHOOL,PROVIDER,"CASHFREE",confirmed.environment(),
                "synthetic-foreign-merchant",confirmed.credentialsSecretReference(),confirmed.clientIdEnvironmentReference(),confirmed.clientSecretEnvironmentReference(),true);
        var unknownKey=new GatewayProviderRegistry.Route("1".repeat(64),ORGANISATION,SCHOOL,PROVIDER,"CASHFREE",confirmed.environment(),
                confirmed.merchantAccountReference(),confirmed.credentialsSecretReference(),confirmed.clientIdEnvironmentReference(),confirmed.clientSecretEnvironmentReference(),true);
        assertThat(registry.settlementsAllowed(forgedSchool)).isFalse();assertThat(registry.settlementsAllowed(forgedMerchant)).isFalse();
        assertThat(registry.settlementsAllowed(unknownKey)).isFalse();
        assertThat(registry.settlementsAllowed(exclusive(route("TEST"),true))).isTrue();verifyNoInteractions(factory);
    }

    @Test void liveSettlementReportsNeedTheirOwnLiveApprovalAndNeverResolveCredentialsForPolicy(){
        var confirmed=exclusive(route("LIVE"),true);var factory=mock(GatewayProviderRegistry.ProviderFactory.class);
        for(boolean live:List.of(false,true)){
            var registry=new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,true,live,false,List.of(confirmed),false,true),
                    ORIGIN,CLOCK,ignored->{throw new AssertionError("No policy credential lookup");},factory);
            assertThat(registry.settlementsAllowed(confirmed)).isEqualTo(live);
        }
        verifyNoInteractions(factory);
    }

    @Test void exclusiveMerchantCannotBeSharedByAnotherSchoolOrConfigurationInEitherListOrder(){
        var original=route("TEST");var factory=mock(GatewayProviderRegistry.ProviderFactory.class);
        for(boolean originalConfirmed:List.of(false,true)){
            var first=exclusive(original,originalConfirmed);
            var second=new GatewayProviderRegistry.Route("1".repeat(64),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"CASHFREE",original.environment(),
                    original.merchantAccountReference(),"secretstore:finance/synthetic-other","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_OTHER_CLIENT_ID",
                    "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_OTHER_CLIENT_SECRET",!originalConfirmed);
            for(var routes:List.of(List.of(first,second),List.of(second,first))){
                assertThatThrownBy(()->new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,false,false,false,routes,false,false),
                        ORIGIN,CLOCK,ignored->{throw new AssertionError("No invalid configuration lookup");},factory))
                        .isInstanceOf(IllegalStateException.class).hasMessage("Gateway configuration requires valid server-owned routes and public origin").hasNoCause();
            }
        }
        verifyNoInteractions(factory);
    }

    @Test void exclusiveClientIdReferenceCannotBeSharedUnderAnotherMerchantLabel(){
        var original=route("TEST");var factory=mock(GatewayProviderRegistry.ProviderFactory.class);
        for(boolean originalConfirmed:List.of(false,true)){
            var first=exclusive(original,originalConfirmed);
            var second=new GatewayProviderRegistry.Route("1".repeat(64),ORGANISATION,UUID.randomUUID(),UUID.randomUUID(),"CASHFREE",original.environment(),
                    "synthetic-other-merchant","secretstore:finance/synthetic-other",original.clientIdEnvironmentReference(),original.clientSecretEnvironmentReference(),!originalConfirmed);
            for(var routes:List.of(List.of(first,second),List.of(second,first))){
                assertThatThrownBy(()->new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,true,false,false,routes,false,true),
                        ORIGIN,CLOCK,ignored->{throw new AssertionError("No invalid configuration lookup");},factory)).isInstanceOf(IllegalStateException.class);
            }
        }
        verifyNoInteractions(factory);
    }

    @Test void priorNonexclusiveCheckoutSharingAndSeparateTestLiveMerchantsRemainConfigurationCompatible(){
        var original=route("TEST");var other=new GatewayProviderRegistry.Route("1".repeat(64),ORGANISATION,UUID.randomUUID(),UUID.randomUUID(),"CASHFREE",original.environment(),
                original.merchantAccountReference(),"secretstore:finance/synthetic-other",original.clientIdEnvironmentReference(),original.clientSecretEnvironmentReference());
        var factory=mock(GatewayProviderRegistry.ProviderFactory.class);
        var oldRegistry=new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,true,false,false,List.of(original,other)),
                ORIGIN,CLOCK,ignored->{throw new AssertionError("No startup lookup");},factory);
        assertThat(oldRegistry.configuredRoutes()).containsExactly(original,other);
        assertThat(oldRegistry.settlementsAllowed(original)).isFalse();assertThat(oldRegistry.settlementsAllowed(other)).isFalse();
        var test=exclusive(original,true);var live=new GatewayProviderRegistry.Route("2".repeat(64),ORGANISATION,SCHOOL,UUID.randomUUID(),"CASHFREE",
                CashfreePaymentProviderAdapter.Environment.LIVE,original.merchantAccountReference(),"secretstore:finance/synthetic-live",
                reference("LIVE","CLIENT_ID"),reference("LIVE","CLIENT_SECRET"),true);
        var separate=new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(false,true,false,false,List.of(test,live),false,true),
                ORIGIN,CLOCK,ignored->{throw new AssertionError("No startup lookup");},factory);
        assertThat(separate.settlementsAllowed(test)).isTrue();assertThat(separate.settlementsAllowed(live)).isFalse();verifyNoInteractions(factory);
    }

    @Test void configuredStartupStillDoesNotResolveEnvironmentOrCreateAnAdapter() {
        var factory = mock(GatewayProviderRegistry.ProviderFactory.class);
        var registry = new GatewayProviderRegistry(settings(true, false, false, false, route("TEST")), ORIGIN, CLOCK,
                ignored -> { throw new AssertionError("No startup lookup"); }, factory);
        assertThat(registry.findRoute(KEY)).contains(route("TEST"));
        assertThatThrownBy(() -> registry.configuredRoutes().clear()).isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(factory);
    }

    @Test void providerResolvesOnlyTheConfiguredCredentialNamesAtTheReplaceableFactory() {
        var lookedUp = new ArrayList<String>();
        var adapter = mock(PaymentProviderAdapter.class);
        when(adapter.providerCode()).thenReturn("CASHFREE");
        var calls = new AtomicInteger();
        var registry = new GatewayProviderRegistry(settings(false, true, false, false, route("TEST")), ORIGIN, CLOCK,
                name -> { lookedUp.add(name); return "synthetic-credential"; },
                (route, clientId, clientSecret, origin, clock) -> {
                    calls.incrementAndGet();
                    assertThat(route).isEqualTo(route("TEST"));
                    assertThat(clientId).isEqualTo("synthetic-credential");
                    assertThat(clientSecret).isEqualTo("synthetic-credential");
                    assertThat(origin).isEqualTo(URI.create("https://school.example.invalid"));
                    assertThat(clock).isSameAs(CLOCK);
                    return adapter;
                });
        assertThat(lookedUp).isEmpty();
        assertThat(registry.provider(registry.findRoute(KEY).orElseThrow())).isSameAs(adapter);
        assertThat(lookedUp).containsExactly("APP_FINANCE_GATEWAY_CASHFREE_TEST_SYNTHETIC_CLIENT_ID",
                "APP_FINANCE_GATEWAY_CASHFREE_TEST_SYNTHETIC_CLIENT_SECRET");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void rejectsUnconfiguredRouteChangesBeforeAnyCredentialLookup() {
        var factory = mock(GatewayProviderRegistry.ProviderFactory.class);
        var registry = new GatewayProviderRegistry(settings(true, false, false, false, route("TEST")), ORIGIN, CLOCK,
                ignored -> { throw new AssertionError("No untrusted lookup"); }, factory);
        var forged = new GatewayProviderRegistry.Route(KEY, ORGANISATION, UUID.randomUUID(), PROVIDER, "CASHFREE",
                CashfreePaymentProviderAdapter.Environment.TEST, "synthetic-merchant", "env:DATABASE_PASSWORD",
                "env:DATABASE_USER", "env:DATABASE_PASSWORD");
        assertThatThrownBy(() -> registry.provider(forged)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(factory);
    }

    @Test void rejectsArbitraryReferencesWeakKeysAndDuplicateProviderBindings() {
        var valid = route("TEST");
        var badReference = new GatewayProviderRegistry.Route(KEY, ORGANISATION, SCHOOL, PROVIDER, "CASHFREE",
                CashfreePaymentProviderAdapter.Environment.TEST, valid.merchantAccountReference(), valid.credentialsSecretReference(),
                "env:DATABASE_USER", "env:DATABASE_PASSWORD");
        var badKey = new GatewayProviderRegistry.Route("short-key", ORGANISATION, SCHOOL, PROVIDER, "CASHFREE",
                CashfreePaymentProviderAdapter.Environment.TEST, valid.merchantAccountReference(), valid.credentialsSecretReference(),
                valid.clientIdEnvironmentReference(), valid.clientSecretEnvironmentReference());
        var wrongEnvironment = new GatewayProviderRegistry.Route(KEY, ORGANISATION, SCHOOL, PROVIDER, "CASHFREE",
                CashfreePaymentProviderAdapter.Environment.TEST, valid.merchantAccountReference(), valid.credentialsSecretReference(),
                reference("LIVE", "CLIENT_ID"), reference("LIVE", "CLIENT_SECRET"));
        var mismatchedCredentials = new GatewayProviderRegistry.Route(KEY, ORGANISATION, SCHOOL, PROVIDER, "CASHFREE",
                CashfreePaymentProviderAdapter.Environment.TEST, valid.merchantAccountReference(), valid.credentialsSecretReference(),
                valid.clientIdEnvironmentReference(), "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_OTHER_CLIENT_SECRET");
        var factory = mock(GatewayProviderRegistry.ProviderFactory.class);
        Function<String, String> lookup = ignored -> { throw new AssertionError("No configuration lookup"); };
        for (var invalid : List.of(badReference, badKey, wrongEnvironment, mismatchedCredentials)) {
            assertThatThrownBy(() -> new GatewayProviderRegistry(settings(true, false, false, false, invalid), ORIGIN, CLOCK,
                    lookup, factory)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(true, false, false, false,
                List.of(valid, valid)), ORIGIN, CLOCK, lookup, factory)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(factory);
    }

    @Test void requiresExplicitLiveOptInBeforeResolvingCredentials() {
        var factory = mock(GatewayProviderRegistry.ProviderFactory.class);
        var registry = new GatewayProviderRegistry(settings(false, true, false, false, route("LIVE")), ORIGIN, CLOCK,
                ignored -> { throw new AssertionError("No disabled LIVE lookup"); }, factory);
        assertThatThrownBy(() -> registry.provider(route("LIVE"))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(factory);
    }

    @Test void onlyExplicitTestConfigurationCanUseLoopback() {
        var factory = mock(GatewayProviderRegistry.ProviderFactory.class);
        Function<String, String> lookup = ignored -> { throw new AssertionError("No startup lookup"); };
        URI loopback = URI.create("http://localhost:3000");
        assertThatThrownBy(() -> new GatewayProviderRegistry(settings(false, true, false, false, route("TEST")), loopback,
                CLOCK, lookup, factory)).isInstanceOf(IllegalStateException.class);
        assertThat(new GatewayProviderRegistry(settings(false, true, false, true, route("TEST")), loopback,
                CLOCK, lookup, factory).configuredRoutes()).containsExactly(route("TEST"));
        assertThatThrownBy(() -> new GatewayProviderRegistry(settings(false, true, true, true, route("LIVE")), loopback,
                CLOCK, lookup, factory)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(factory);
    }

    @Test void missingCredentialsAndResolverFailuresExposeNoValuesOrCauses() {
        var factory = mock(GatewayProviderRegistry.ProviderFactory.class);
        for (Function<String, String> lookup : List.<Function<String, String>>of(ignored -> null,
                ignored -> { throw new IllegalArgumentException("synthetic-secret-value"); })) {
            var registry = new GatewayProviderRegistry(settings(false, true, false, false, route("TEST")), ORIGIN, CLOCK,
                    lookup, factory);
            assertThatThrownBy(() -> registry.provider(route("TEST"))).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Configured gateway provider is disabled or unavailable").hasNoCause();
        }
        var registry = new GatewayProviderRegistry(settings(false, true, false, false, route("TEST")), ORIGIN, CLOCK,
                ignored -> "synthetic-secret-value",
                (route, id, secret, origin, clock) -> { throw new IllegalArgumentException(secret); });
        assertThatThrownBy(() -> registry.provider(route("TEST"))).isInstanceOf(IllegalStateException.class)
                .hasMessage("Configured gateway provider is disabled or unavailable").hasNoCause();
        verifyNoInteractions(factory);
    }

    @Test void alternateLoopbackFormsNeverBypassLiveAndTestOriginApproval() {
        var factory = mock(GatewayProviderRegistry.ProviderFactory.class);
        Function<String, String> lookup = ignored -> { throw new AssertionError("No startup lookup"); };
        for (String host : List.of("127.0.0.2", "127.1", "2130706433", "localhost.", "preview.localhost",
                "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]")) {
            URI origin = URI.create("https://" + host);
            assertThatThrownBy(() -> new GatewayProviderRegistry(settings(false, true, true, true, route("LIVE")),
                    origin, CLOCK, lookup, factory)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new GatewayProviderRegistry(settings(false, true, false, false, route("TEST")),
                    origin, CLOCK, lookup, factory)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(factory);
    }

    private static GatewayProviderRegistry.Settings settings(boolean creation, boolean worker, boolean live,
            boolean testLoopback, GatewayProviderRegistry.Route route) {
        return new GatewayProviderRegistry.Settings(creation, worker, live, testLoopback, List.of(route));
    }

    private static GatewayProviderRegistry.Route route(String environment) {
        return new GatewayProviderRegistry.Route(KEY, ORGANISATION, SCHOOL, PROVIDER, "CASHFREE",
                CashfreePaymentProviderAdapter.Environment.valueOf(environment), "synthetic-merchant",
                "secretstore:finance/synthetic-cashfree", reference(environment, "CLIENT_ID"), reference(environment, "CLIENT_SECRET"));
    }

    private static String reference(String environment, String credential) {
        return "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_SYNTHETIC_" + credential;
    }

    private static GatewayProviderRegistry.Route exclusive(GatewayProviderRegistry.Route route,boolean confirmed){
        return new GatewayProviderRegistry.Route(route.routeKey(),route.organisationId(),route.schoolId(),route.providerConfigId(),route.providerCode(),route.environment(),
                route.merchantAccountReference(),route.credentialsSecretReference(),route.clientIdEnvironmentReference(),route.clientSecretEnvironmentReference(),confirmed);
    }
}

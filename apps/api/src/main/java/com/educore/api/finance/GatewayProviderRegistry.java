package com.educore.api.finance;

import com.educore.api.config.ApplicationProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Server-owned route bindings only. Configuration does not activate a school provider. */
@Component
@EnableConfigurationProperties(GatewayProviderRegistry.Settings.class)
public final class GatewayProviderRegistry {
    private static final String ENVIRONMENT_PREFIX = "env:APP_FINANCE_GATEWAY_CASHFREE_";
    private final Settings settings;
    private final URI publicOrigin;
    private final Clock clock;
    private final Function<String, String> processEnvironment;
    private final ProviderFactory providerFactory;
    private final Map<String, Route> routesByKey;

    @Autowired
    public GatewayProviderRegistry(Settings settings, ApplicationProperties application, Clock clock,
            ObjectProvider<ProviderFactory> providerFactories) {
        this(settings, configuredOrigin(application), clock, System::getenv,
                providerFactories.getIfAvailable(() -> GatewayProviderRegistry::cashfree));
    }

    /** Injectable process lookup and adapter factory keep validation free of external I/O. */
    public GatewayProviderRegistry(Settings settings, URI publicOrigin, Clock clock,
            Function<String, String> processEnvironment, ProviderFactory providerFactory) {
        if (settings == null || clock == null || processEnvironment == null || providerFactory == null) {
            throw invalidConfiguration();
        }
        this.settings = settings;
        this.publicOrigin = canonicalOrigin(publicOrigin);
        this.clock = clock;
        this.processEnvironment = processEnvironment;
        this.providerFactory = providerFactory;
        var routes = new LinkedHashMap<String, Route>();
        var providerConfigurations = new HashSet<UUID>();
        for (Route route : settings.routes()) {
            validateRoute(route, this.publicOrigin, settings.testAllowLoopback());
            if (routes.putIfAbsent(route.routeKey(), route) != null
                    || !providerConfigurations.add(route.providerConfigId())) {
                throw invalidConfiguration();
            }
        }
        validateExclusiveMerchants(settings.routes());
        this.routesByKey = Map.copyOf(routes);
    }

    public boolean creationEnabled() { return settings.creationEnabled(); }
    public boolean workerEnabled() { return settings.workerEnabled(); }
    public boolean liveEnabled() { return settings.liveEnabled(); }
    public boolean refundsEnabled() { return settings.refundsEnabled(); }
    public boolean settlementsEnabled() { return settings.settlementsEnabled(); }

    /** Pure policy check: a merchant-wide report requires separately confirmed exclusive school scope. */
    public boolean settlementsAllowed(Route route) {
        return workerEnabled() && settlementsEnabled() && route != null && route.routeKey() != null
                && route.exclusiveSchoolMerchantConfirmed() && route.equals(routesByKey.get(route.routeKey()))
                && (route.environment() != CashfreePaymentProviderAdapter.Environment.LIVE || liveEnabled());
    }

    public Optional<Route> findRoute(String routeKey) {
        return routeKey == null ? Optional.empty() : Optional.ofNullable(routesByKey.get(routeKey));
    }

    public List<Route> configuredRoutes() { return settings.routes(); }

    /** Resolves only the complete, unchanged configured route; it never resolves database input. */
    public PaymentProviderAdapter provider(Route route) {
        if ((!creationEnabled() && !workerEnabled()) || route == null || route.routeKey() == null
                || !route.equals(routesByKey.get(route.routeKey()))
                || (route.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !liveEnabled())) {
            throw unavailable();
        }
        try {
            Credentials credentials = new Credentials(
                    processEnvironment.apply(environmentName(route.clientIdEnvironmentReference())),
                    processEnvironment.apply(environmentName(route.clientSecretEnvironmentReference())));
            PaymentProviderAdapter adapter = providerFactory.create(route, credentials.clientId(), credentials.clientSecret(),
                    publicOrigin, clock);
            if (adapter == null || !route.providerCode().equals(adapter.providerCode())) throw unavailable();
            return adapter;
        } catch (RuntimeException exception) {
            // A process resolver or replacement factory may include credentials in its exception.
            throw unavailable();
        }
    }

    @FunctionalInterface
    public interface ProviderFactory {
        PaymentProviderAdapter create(Route route, String clientId, String clientSecret, URI publicOrigin, Clock clock);
    }

    @ConfigurationProperties(prefix = "app.finance.gateway")
    public record Settings(boolean creationEnabled, boolean workerEnabled, boolean liveEnabled,
            boolean testAllowLoopback, List<Route> routes, boolean refundsEnabled, boolean settlementsEnabled) {
        /** Existing checkout configurations never implicitly activate refunds or merchant-wide reports. */
        public Settings(boolean creationEnabled,boolean workerEnabled,boolean liveEnabled,boolean testAllowLoopback,List<Route> routes) {
            this(creationEnabled,workerEnabled,liveEnabled,testAllowLoopback,routes,false,false);
        }
        /** Existing explicit refund configurations still leave merchant-wide report reads disabled. */
        public Settings(boolean creationEnabled,boolean workerEnabled,boolean liveEnabled,boolean testAllowLoopback,List<Route> routes,boolean refundsEnabled) {
            this(creationEnabled,workerEnabled,liveEnabled,testAllowLoopback,routes,refundsEnabled,false);
        }
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Settings {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }

        @Override public String toString() { return "Settings[server gateway configuration]"; }
    }

    /** All fields are non-secret deployment bindings; route keys are opaque routing identifiers. */
    public record Route(String routeKey, UUID organisationId, UUID schoolId, UUID providerConfigId,
            String providerCode, CashfreePaymentProviderAdapter.Environment environment,
            String merchantAccountReference, String credentialsSecretReference,
            String clientIdEnvironmentReference, String clientSecretEnvironmentReference, boolean exclusiveSchoolMerchantConfirmed) {
        /** A prior checkout/refund route is not evidence of an exclusively school-owned merchant. */
        public Route(String routeKey,UUID organisationId,UUID schoolId,UUID providerConfigId,String providerCode,
                CashfreePaymentProviderAdapter.Environment environment,String merchantAccountReference,String credentialsSecretReference,
                String clientIdEnvironmentReference,String clientSecretEnvironmentReference) {
            this(routeKey,organisationId,schoolId,providerConfigId,providerCode,environment,merchantAccountReference,credentialsSecretReference,
                    clientIdEnvironmentReference,clientSecretEnvironmentReference,false);
        }
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Route {}
        @Override public String toString() { return "Route[server gateway configuration]"; }
    }

    private record Credentials(String clientId, String clientSecret) {
        private Credentials {
            if (!credential(clientId) || !credential(clientSecret)) throw unavailable();
        }

        @Override public String toString() { return "Credentials[redacted]"; }
    }

    private static PaymentProviderAdapter cashfree(Route route, String clientId, String clientSecret,
            URI publicOrigin, Clock clock) {
        return new CashfreePaymentProviderAdapter(route.environment(), clientId, clientSecret, publicOrigin, clock);
    }

    private static void validateRoute(Route route, URI origin, boolean testAllowLoopback) {
        if (route == null || !canonicalRouteKey(route.routeKey()) || route.organisationId() == null
                || route.schoolId() == null || route.providerConfigId() == null
                || !"CASHFREE".equals(route.providerCode()) || route.environment() == null
                || route.merchantAccountReference() == null
                || !route.merchantAccountReference().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,119}")
                || route.credentialsSecretReference() == null
                || !route.credentialsSecretReference().matches("[A-Za-z][A-Za-z0-9_./:-]{2,239}")) {
            throw invalidConfiguration();
        }
        String prefix = ENVIRONMENT_PREFIX + route.environment().name() + "_";
        String clientIdReference = route.clientIdEnvironmentReference();
        if (clientIdReference == null || !clientIdReference.startsWith(prefix)
                || !clientIdReference.substring(prefix.length()).matches("(?:[A-Z][A-Z0-9_]{0,47}_)?CLIENT_ID")
                || !clientIdReference.substring(0, clientIdReference.length() - "CLIENT_ID".length())
                        .concat("CLIENT_SECRET").equals(route.clientSecretEnvironmentReference())) {
            throw invalidConfiguration();
        }
        boolean loopback = loopbackOrigin(origin);
        boolean allowedLoopback = loopback && route.environment() == CashfreePaymentProviderAdapter.Environment.TEST
                && testAllowLoopback;
        if ((loopback && !allowedLoopback) || (!"https".equals(origin.getScheme()) && !allowedLoopback)) {
            throw invalidConfiguration();
        }
    }

    private static void validateExclusiveMerchants(List<Route> routes) {
        for (Route exclusive : routes) {
            if (!exclusive.exclusiveSchoolMerchantConfirmed()) continue;
            for (Route other : routes) {
                if (exclusive.routeKey().equals(other.routeKey())) continue;
                boolean sameMerchant = exclusive.providerCode().equals(other.providerCode()) && exclusive.environment() == other.environment()
                        && exclusive.merchantAccountReference().equals(other.merchantAccountReference());
                if (sameMerchant || exclusive.clientIdEnvironmentReference().equals(other.clientIdEnvironmentReference())) {
                    throw invalidConfiguration();
                }
            }
        }
    }

    /** Numeric-literal parsing only: never resolve a deployment hostname through DNS. */
    private static boolean loopbackOrigin(URI origin) {
        String host = origin.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.equals("localhost") || host.endsWith(".localhost")) return true;
        if (host.matches("[0-9.]+") || (host.contains(":") && host.matches("[0-9a-f:.]+"))) {
            try { return InetAddress.getByName(host).isLoopbackAddress(); }
            catch (UnknownHostException exception) { throw invalidConfiguration(); }
        }
        return false;
    }

    private static boolean canonicalRouteKey(String routeKey) {
        if (routeKey == null) return false;
        // Support 32-byte keys encoded as canonical hex or unpadded base64url.
        if (routeKey.matches("[a-f0-9]{64}")) return true;
        if (!routeKey.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(routeKey);
            return bytes.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(routeKey);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static URI configuredOrigin(ApplicationProperties application) {
        try {
            if (application == null || application.publicUrl() == null) throw invalidConfiguration();
            return URI.create(application.publicUrl());
        } catch (RuntimeException exception) {
            throw invalidConfiguration();
        }
    }

    private static URI canonicalOrigin(URI origin) {
        if (origin == null || origin.getHost() == null || origin.getRawUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || origin.getPort() < -1 || origin.getPort() == 0 || origin.getPort() > 65535
                || !("https".equals(origin.getScheme()) || "http".equals(origin.getScheme()))
                || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
                || origin.toASCIIString().length() > 240) {
            throw invalidConfiguration();
        }
        return URI.create(origin.getScheme() + "://" + origin.getRawAuthority());
    }

    private static String environmentName(String configuredReference) {
        return configuredReference.substring("env:".length());
    }

    private static boolean credential(String value) {
        return value != null && !value.isBlank() && value.length() <= 512
                && value.chars().noneMatch(character -> character < 32 || character == 127);
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("Gateway configuration requires valid server-owned routes and public origin");
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Configured gateway provider is disabled or unavailable");
    }
}

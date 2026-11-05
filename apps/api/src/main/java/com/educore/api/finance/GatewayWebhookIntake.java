package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal signature-to-durable-inbox boundary, not an HTTP endpoint, processor,
 * payment confirmation, merchant activation or authority to create/post/refund.
 * Authenticate an immutable bounded body before any database work; acknowledge
 * only after the existing minimal, scoped, idempotent inbox transaction commits.
 */
@Service
public class GatewayWebhookIntake {
    static final int MAX_RAW_BODY_BYTES = 256 * 1024;
    private final GatewayProviderRegistry registry;
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayWorkQueue queue;
    private final TransactionTemplate transactions;
    // Bounded by immutable configured routes, not caller-supplied keys. Repeated
    // forged/retried callbacks must not create an HTTP client/selector per body.
    // Credential rotation follows the existing operator restart procedure.
    private final ConcurrentHashMap<GatewayProviderRegistry.Route, PaymentProviderAdapter> providers = new ConcurrentHashMap<>();

    public GatewayWebhookIntake(GatewayProviderRegistry registry, NamedParameterJdbcTemplate jdbc,
            GatewayWorkQueue queue, PlatformTransactionManager transactionManager) {
        this.registry = Objects.requireNonNull(registry);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.queue = Objects.requireNonNull(queue);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setReadOnly(false);
        this.transactions.setTimeout(15);
    }

    /** Internal opaque receipt; a future HTTP callback should return no school/payment details. */
    public record IntakeReceipt(UUID inboxId) {
        public IntakeReceipt { Objects.requireNonNull(inboxId); }
        @Override public String toString() { return "IntakeReceipt[durably retained authenticated provider event]"; }
    }

    public IntakeReceipt accept(String routeKey, byte[] rawBody, String signature, String timestamp) {
        // Never replace a browser/human/foreign SYSTEM context or join its transaction.
        if (!registry.workerEnabled() || TenantRequestContext.current().isPresent()
                || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()
                || rawBody == null || rawBody.length == 0 || rawBody.length > MAX_RAW_BODY_BYTES) throw unavailable();
        var route = registry.findRoute(routeKey).orElseThrow(GatewayWebhookIntake::unavailable);
        if (route.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) throw unavailable();
        var binding = new GatewayWorkQueue.ProviderBinding(route.organisationId(), route.schoolId(), route.providerConfigId(),
                route.providerCode(), route.environment().name(), route.merchantAccountReference(), route.credentialsSecretReference());
        byte[] snapshot = rawBody.clone();
        PaymentProviderAdapter.VerifiedWebhook verified;
        try {
            byte[] originalHash = sha256(snapshot);
            // Provider construction/authentication performs no network I/O. The
            // registry resolves credentials only through this exact trusted route.
            verified = providers.computeIfAbsent(route, registry::provider).verifyWebhook(snapshot, signature, timestamp);
            GatewayWorkQueue.validateWebhook(binding, verified);
            if (!MessageDigest.isEqual(originalHash,
                    java.util.HexFormat.of().parseHex(verified.bodySha256()))) throw unavailable();
        } catch (RuntimeException failure) {
            // Resolver/adapter failures may contain credentials or raw payloads.
            // Retain no cause, signature, payload, provider response or reference.
            throw unavailable();
        } finally {
            Arrays.fill(snapshot, (byte) 0);
        }
        if (TenantRequestContext.current().isPresent() || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
        try (var ignored = TenantRequestContext.open(null, binding.organisationId())) {
            UUID inboxId = transactions.execute(status -> {
                var p = new MapSqlParameterSource().addValue("organisationId", binding.organisationId())
                        .addValue("schoolId", binding.schoolId()).addValue("providerId", binding.providerConfigId())
                        .addValue("providerCode", binding.providerCode()).addValue("environment", binding.environment())
                        .addValue("merchant", binding.merchantReference()).addValue("credentialsReference", binding.credentialsReference());
                if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL
                        """, p, Boolean.class))) throw unavailable();
                var provider = jdbc.query("""
                        SELECT id FROM payment_provider_configs WHERE id=:providerId
                          AND organisation_id=:organisationId AND school_id=:schoolId
                          AND provider_code=:providerCode AND environment=:environment
                          AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentialsReference
                        FOR SHARE
                        """, p, row -> row.next() ? row.getObject("id", UUID.class) : null);
                if (!binding.providerConfigId().equals(provider)) throw unavailable();
                // Exact historical INACTIVE bindings may retain genuine callbacks:
                // disabling NEW checkout must not discard already received money.
                // No attempt mutation or financial posting is permitted here.
                return queue.retainVerifiedWebhook(binding, verified);
            });
            // TransactionTemplate returns only after commit. Failure is never acknowledgement.
            return new IntakeReceipt(Objects.requireNonNull(inboxId));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static byte[] sha256(byte[] payload) {
        try { return MessageDigest.getInstance("SHA-256").digest(payload); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 required"); }
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Gateway webhook intake requires its trusted route, authentic bounded body and committed scoped inbox");
    }
}

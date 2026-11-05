package com.educore.api.finance;

import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.tenancy.TenantRequestContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Private append-only hosted-link evidence, never payment or family disclosure
 * authority. The worker must always send independently authenticated GET money
 * facts to the existing finalizer even if this separate URL transaction fails.
 * A lost lease cannot retain a URL; same-ID recovery can obtain it later without
 * another CREATE. No network, credential lookup, receipt or intent mutation here.
 */
@Service
public final class GatewayHostedCheckoutStore {
    private final GatewayProviderRegistry registry;
    private final NamedParameterJdbcTemplate jdbc;
    private final SensitiveValueCipher cipher;
    private final TransactionTemplate transactions;

    public GatewayHostedCheckoutStore(GatewayProviderRegistry registry, NamedParameterJdbcTemplate jdbc,
            SensitiveValueCipher cipher, PlatformTransactionManager transactionManager) {
        this.registry = Objects.requireNonNull(registry);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.cipher = Objects.requireNonNull(cipher);
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(15);
        transactions.setReadOnly(false);
    }

    /** Opaque internal retention result. Any conflict forbids later checkout continuation. */
    public record Retained(UUID id, boolean conflicting) {
        public Retained { Objects.requireNonNull(id); }
        @Override public String toString() { return "Retained[private immutable gateway link evidence]"; }
    }

    public Retained retainCreate(String routeKey, GatewayWorkQueue.Claim claim,
            PaymentProviderAdapter.HostedCheckout hostedCheckout, Instant observedAt) {
        return retain(routeKey, claim, hostedCheckout, observedAt, "CREATE", null);
    }

    public Optional<Retained> retainGet(String routeKey, GatewayWorkQueue.Claim claim,
            GatewayProviderDiscovery.ReadResult read) {
        if (read == null) throw unavailable();
        // Even no-link outcomes cannot be attributed to an unrelated route.
        requireOutsidePersistence();
        var binding = trustedBinding(routeKey, claim);
        if (!binding.equals(read.evidence().binding())) throw unavailable();
        if (read.hostedCheckout().isEmpty()) return Optional.empty();
        return Optional.of(retain(routeKey, claim, read.hostedCheckout().orElseThrow(),
                read.evidence().observedAt(), "GET", read.evidence()));
    }

    private Retained retain(String routeKey, GatewayWorkQueue.Claim claim,
            PaymentProviderAdapter.HostedCheckout hosted, Instant observedAt, String source,
            GatewayDiscoveryEvidence.Discovery discovery) {
        requireOutsidePersistence();
        var binding = trustedBinding(routeKey, claim);
        if (hosted == null || observedAt == null || !claim.id().toString().equals(hosted.providerOrderReference())) throw unavailable();
        URI url = requireHostedUri(binding, hosted.redirectUri());
        try (var ignored = TenantRequestContext.open(null, binding.organisationId())) {
            return Objects.requireNonNull(transactions.execute(status -> {
                var p = parameters(claim);
                if (!TransactionSynchronizationManager.isActualTransactionActive()
                        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        || !Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL
                            """, p, Boolean.class))) throw unavailable();
                // Same fence and lock order as reservation, dispatch and finalization.
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                        p.addValue("lockKey", binding.schoolId() + ":finance"), (r, n) -> true);
                var frozen = jdbc.query("""
                        SELECT attempt.id,attempt.learner_fee_account_id,attempt.amount,attempt.currency_code,
                          attempt.created_at,attempt.expires_at,attempt.school_time_zone,
                          work.create_dispatched_at,work.create_dispatched_by_lease_token
                        FROM fee_gateway_attempt_work work JOIN fee_gateway_attempts attempt
                          ON attempt.id=work.attempt_id AND attempt.organisation_id=work.organisation_id AND attempt.school_id=work.school_id
                          AND attempt.payment_provider_config_id=work.payment_provider_config_id AND attempt.provider_code=work.provider_code
                          AND attempt.provider_environment=work.provider_environment AND attempt.merchant_account_reference=work.merchant_account_reference
                        JOIN payment_provider_configs provider ON provider.id=work.payment_provider_config_id
                          AND provider.organisation_id=work.organisation_id AND provider.school_id=work.school_id
                          AND provider.provider_code=work.provider_code AND provider.environment=work.provider_environment
                          AND provider.merchant_account_reference=work.merchant_account_reference AND provider.credentials_secret_reference=work.credentials_secret_reference
                        WHERE work.attempt_id=:attemptId AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                          AND work.payment_provider_config_id=:providerId AND work.provider_code=:providerCode
                          AND work.provider_environment=:environment AND work.merchant_account_reference=:merchant
                          AND work.credentials_secret_reference=:credentialsReference AND work.status='LEASED'
                          AND work.lease_token=:leaseToken AND work.lease_started_at=:leaseStart AND work.lease_until=:leaseUntil
                          AND work.attempt_count=:attemptNumber AND work.create_dispatched_at IS NOT NULL
                        FOR SHARE OF work,attempt,provider
                        """, p, r -> r.next() ? new Frozen(new GatewayDiscoveryEvidence.ExpectedAttempt(
                                r.getObject("id", UUID.class), r.getObject("learner_fee_account_id", UUID.class), binding,
                                r.getBigDecimal("amount"), r.getString("currency_code"), r.getTimestamp("created_at").toInstant(),
                                r.getTimestamp("expires_at").toInstant(), r.getString("school_time_zone")),
                                r.getTimestamp("create_dispatched_at").toInstant(), r.getObject("create_dispatched_by_lease_token", UUID.class)) : null);
                // Actual DB time after lock waits, not application-clock lease claims.
                Instant now = jdbc.queryForObject("SELECT clock_timestamp()", p,
                        (r, n) -> r.getObject(1, java.time.OffsetDateTime.class).toInstant());
                if (frozen == null || now == null || !now.isBefore(claim.leaseUntil())
                        || observedAt.isBefore(claim.startedAt()) || observedAt.isBefore(frozen.dispatchedAt()) || observedAt.isAfter(now)
                        || "CREATE".equals(source) && !claim.token().equals(frozen.dispatchedBy())) throw unavailable();
                if (discovery != null && (!binding.equals(discovery.binding())
                        || GatewayDiscoveryEvidence.classify(frozen.expected(), discovery).checkout().isEmpty()
                        || !hosted.providerOrderReference().equals(discovery.checkout().checkoutReference()))) throw unavailable();

                String aad = associatedData(binding, claim.id());
                String hash = urlHash(aad, url);
                p.addValue("urlHash", hash);
                var existing = existing(p);
                UUID id;
                if (existing != null) {
                    verifyExisting(existing, aad, url, hash);
                    id = existing.id(); // Preserve first source/time and original randomized encryption.
                } else {
                    byte[] plaintext = url.toASCIIString().getBytes(StandardCharsets.UTF_8);
                    SensitiveValueCipher.EncryptedValue encrypted;
                    try { encrypted = cipher.encrypt(plaintext, aad); }
                    finally { Arrays.fill(plaintext, (byte) 0); }
                    if (encrypted == null || encrypted.ciphertext() == null || encrypted.nonce() == null
                            || !encrypted.ciphertext().matches("[A-Za-z0-9_-]{24,512}") || !encrypted.nonce().matches("[A-Za-z0-9_-]{16}")) throw unavailable();
                    id = UUID.randomUUID();
                    p.addValue("id", id).addValue("accountId", frozen.expected().accountId())
                            .addValue("ciphertext", encrypted.ciphertext()).addValue("nonce", encrypted.nonce()).addValue("source", source)
                            .addValue("observedAt", GatewayExactInstant.parameter(observedAt), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                            .addValue("observedAtExact", GatewayExactInstant.canonical(observedAt));
                    int changed = jdbc.update("""
                            INSERT INTO fee_gateway_hosted_checkouts(id,organisation_id,school_id,attempt_id,learner_fee_account_id,
                              payment_provider_config_id,provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,
                              provider_checkout_reference,url_ciphertext,url_nonce,url_sha256,source_kind,source_lease_token,
                              source_lease_started_at,source_lease_until,source_attempt_count,observed_at,observed_at_exact)
                            VALUES(:id,:organisationId,:schoolId,:attemptId,:accountId,:providerId,:providerCode,:environment,:merchant,
                              :credentialsReference,:attemptId,:ciphertext,:nonce,:urlHash,:source,:leaseToken,:leaseStart,:leaseUntil,:attemptNumber,:observedAt,:observedAtExact)
                            """, p);
                    if (changed != 1) throw unavailable();
                }
                Integer count = jdbc.queryForObject("""
                        SELECT count(*) FROM fee_gateway_hosted_checkouts
                        WHERE attempt_id=:attemptId AND organisation_id=:organisationId AND school_id=:schoolId
                        """, p, Integer.class);
                if (count == null || count < 1) throw unavailable();
                return new Retained(id, count > 1);
            }));
        } catch (RuntimeException failure) {
            // Cipher/SQL/parser failures can contain bearer URLs or key material.
            // Never preserve their causes, plaintext or supplied references.
            throw unavailable();
        }
    }

    private Existing existing(MapSqlParameterSource p) {
        // The already-held school finance fence serializes insert/retry writers.
        // Evidence is append-only: a locking read would also apply UPDATE RLS
        // policies, which correctly do not exist for these immutable rows.
        return jdbc.query("""
                SELECT id,url_ciphertext,url_nonce,url_sha256 FROM fee_gateway_hosted_checkouts
                WHERE attempt_id=:attemptId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND payment_provider_config_id=:providerId AND provider_code=:providerCode AND provider_environment=:environment
                  AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentialsReference AND url_sha256=:urlHash
                """, p, r -> r.next() ? new Existing(r.getObject("id", UUID.class), r.getString("url_ciphertext"),
                        r.getString("url_nonce"), r.getString("url_sha256")) : null);
    }

    private void verifyExisting(Existing existing, String aad, URI url, String hash) {
        byte[] decoded = null;
        try {
            decoded = cipher.decrypt(new SensitiveValueCipher.EncryptedValue(existing.ciphertext(), existing.nonce()), aad);
            if (decoded == null || !hash.equals(existing.hash()) || !MessageDigest.isEqual(decoded,
                    url.toASCIIString().getBytes(StandardCharsets.UTF_8))) throw unavailable();
        } finally { if (decoded != null) Arrays.fill(decoded, (byte) 0); }
    }

    private GatewayWorkQueue.ProviderBinding trustedBinding(String routeKey, GatewayWorkQueue.Claim claim) {
        if (claim == null || claim.kind() != GatewayWorkQueue.WorkKind.ATTEMPT || !registry.workerEnabled()) throw unavailable();
        var route = registry.findRoute(routeKey).orElseThrow(GatewayHostedCheckoutStore::unavailable);
        if (route.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) throw unavailable();
        var binding = new GatewayWorkQueue.ProviderBinding(route.organisationId(), route.schoolId(), route.providerConfigId(),
                route.providerCode(), route.environment().name(), route.merchantAccountReference(), route.credentialsSecretReference());
        if (!binding.equals(claim.binding())) throw unavailable();
        return binding;
    }

    static URI requireHostedUri(GatewayWorkQueue.ProviderBinding binding, URI url) {
        if (binding == null) throw unavailable();
        String host = "TEST".equals(binding.environment()) ? "payments-test.cashfree.com" : "payments.cashfree.com";
        if (!"CASHFREE".equals(binding.providerCode()) || url == null || !"https".equals(url.getScheme())
                || !host.equals(url.getHost()) || url.getPort() != -1 || url.getRawUserInfo() != null
                || url.getRawQuery() != null || url.getRawFragment() != null || url.getRawPath() == null
                || !url.getRawPath().matches("/links/[A-Za-z0-9_-]{1,100}")
                || !url.toASCIIString().equals("https://" + host + url.getRawPath())) throw unavailable();
        return url;
    }

    static String associatedData(GatewayWorkQueue.ProviderBinding b, UUID attempt) {
        return "finance:gateway:hosted-url:v1\n" + b.organisationId() + "\n" + b.schoolId() + "\n" + attempt + "\n"
                + b.providerConfigId() + "\n" + b.providerCode() + "\n" + b.environment() + "\n" + b.merchantReference() + "\n" + b.credentialsReference();
    }

    private static String urlHash(String aad, URI url) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((aad + "\n" + url.toASCIIString()).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw unavailable(); }
    }

    private static MapSqlParameterSource parameters(GatewayWorkQueue.Claim claim) {
        var b = claim.binding();
        return new MapSqlParameterSource().addValue("organisationId", b.organisationId()).addValue("schoolId", b.schoolId())
                .addValue("attemptId", claim.id()).addValue("providerId", b.providerConfigId()).addValue("providerCode", b.providerCode())
                .addValue("environment", b.environment()).addValue("merchant", b.merchantReference()).addValue("credentialsReference", b.credentialsReference())
                .addValue("leaseToken", claim.token()).addValue("leaseStart", GatewayExactInstant.parameter(claim.startedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("leaseUntil", GatewayExactInstant.parameter(claim.leaseUntil()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE).addValue("attemptNumber", claim.attemptNumber());
    }

    private static void requireOutsidePersistence() {
        if (TenantRequestContext.current().isPresent() || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Private gateway link requires trusted scoped immutable provider evidence");
    }
    private record Frozen(GatewayDiscoveryEvidence.ExpectedAttempt expected, Instant dispatchedAt, UUID dispatchedBy) {
        @Override public String toString() { return "Frozen[internal private gateway admission]"; }
    }
    private record Existing(UUID id, String ciphertext, String nonce, String hash) {
        @Override public String toString() { return "Existing[encrypted private gateway evidence]"; }
    }
}

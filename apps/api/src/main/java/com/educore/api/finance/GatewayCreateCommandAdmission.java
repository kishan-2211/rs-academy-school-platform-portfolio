package com.educore.api.finance;

import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.tenancy.TenantRequestContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Internal one-use CREATE command, never an endpoint or provider client. The
 * existing dispatch admission remains the sole financial/family/book authority.
 * Its marker and the private payer read/decryption share one short transaction;
 * an unusable payer rolls back the marker. Only a successfully committed command
 * may reach a future worker's single provider POST outside persistence. An
 * unknown commit/POST outcome must recover by same-ID GET, never another CREATE.
 */
@Service
public final class GatewayCreateCommandAdmission {
    private final GatewayProviderRegistry registry;
    private final GatewayAttemptDispatch dispatch;
    private final NamedParameterJdbcTemplate jdbc;
    private final SensitiveValueCipher cipher;
    private final TransactionTemplate transactions;

    public GatewayCreateCommandAdmission(GatewayProviderRegistry registry, GatewayAttemptDispatch dispatch,
            NamedParameterJdbcTemplate jdbc, SensitiveValueCipher cipher, PlatformTransactionManager transactionManager) {
        this.registry = Objects.requireNonNull(registry);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.cipher = Objects.requireNonNull(cipher);
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(15);
        transactions.setReadOnly(false);
    }

    /** Sensitive transient server command; not a browser DTO, queue payload or log value. */
    public record Command(GatewayAttemptDispatch.CreateAdmission admission, PaymentProviderAdapter.CheckoutRequest request) {
        public Command { Objects.requireNonNull(admission); Objects.requireNonNull(request); }
        @Override public String toString() { return "Command[private one-use gateway CREATE]"; }
    }

    public Command admit(String routeKey, GatewayWorkQueue.Claim claim) {
        requireOutsidePersistence();
        try {
            var binding = trustedBinding(routeKey, claim);
            try (var ignored = TenantRequestContext.open(null, binding.organisationId())) {
                // TransactionTemplate returns only AFTER successful commit. The
                // provider must not be invoked from this callback or a TX hook.
                return Objects.requireNonNull(transactions.execute(status -> prepare(routeKey, claim)));
            }
        } catch (RuntimeException failure) {
            // Cipher, SQL, delegated admission and commit failures may carry
            // private values. Return no command and preserve no diagnostic cause.
            throw unavailable();
        }
    }

    private Command prepare(String routeKey, GatewayWorkQueue.Claim claim) {
        var binding = claim.binding();
        var context = TenantRequestContext.current().orElseThrow(GatewayCreateCommandAdmission::unavailable);
        var p = new MapSqlParameterSource().addValue("organisationId", binding.organisationId())
                .addValue("schoolId", binding.schoolId()).addValue("providerId", binding.providerConfigId())
                .addValue("providerCode", binding.providerCode()).addValue("environment", binding.environment())
                .addValue("merchant", binding.merchantReference()).addValue("credentialsReference", binding.credentialsReference())
                .addValue("attemptId", claim.id()).addValue("leaseToken", claim.token())
                .addValue("leaseStart", GatewayExactInstant.parameter(claim.startedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("leaseUntil", GatewayExactInstant.parameter(claim.leaseUntil()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("attemptNumber", claim.attemptNumber());
        if (context.userId() != null || !binding.organisationId().equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL
                    """, p, Boolean.class))) throw unavailable();

        // Reuse all existing locked live-parent, payable-fee, independently
        // approved accounting and exact full-claim checks; do not duplicate them.
        var admission = dispatch.admitCreate(routeKey, claim);
        requireAdmission(admission, claim);
        var attempt = admission.attempt();
        p.addValue("accountId", attempt.accountId()).addValue("amount", attempt.amount())
                .addValue("currency", attempt.currencyCode()).addValue("returnUri", attempt.returnUri().toASCIIString())
                .addValue("createdAt", GatewayExactInstant.parameter(attempt.createdAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("expiresAt", GatewayExactInstant.parameter(attempt.expiresAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("attemptVersion", attempt.attemptVersion())
                .addValue("dispatchedAt", GatewayExactInstant.parameter(admission.dispatchedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        var payer = jdbc.query("""
                SELECT attempt.payer_phone_ciphertext,attempt.payer_phone_nonce
                FROM fee_gateway_attempt_work work JOIN fee_gateway_attempts attempt
                  ON attempt.id=work.attempt_id AND attempt.organisation_id=work.organisation_id AND attempt.school_id=work.school_id
                  AND attempt.payment_provider_config_id=work.payment_provider_config_id AND attempt.provider_code=work.provider_code
                  AND attempt.provider_environment=work.provider_environment AND attempt.merchant_account_reference=work.merchant_account_reference
                WHERE work.attempt_id=:attemptId AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                  AND work.payment_provider_config_id=:providerId AND work.provider_code=:providerCode
                  AND work.provider_environment=:environment AND work.merchant_account_reference=:merchant
                  AND work.credentials_secret_reference=:credentialsReference AND work.status='LEASED'
                  AND work.lease_token=:leaseToken AND work.lease_started_at=:leaseStart AND work.lease_until=:leaseUntil
                  AND work.attempt_count=:attemptNumber AND work.create_dispatched_by_lease_token=:leaseToken
                  AND work.create_dispatched_at=:dispatchedAt AND attempt.status='RESERVED'
                  AND attempt.learner_fee_account_id=:accountId AND attempt.checkout_reference=:attemptId
                  AND attempt.amount=:amount AND attempt.currency_code=:currency AND attempt.return_uri=:returnUri
                  AND attempt.created_at=:createdAt AND attempt.expires_at=:expiresAt AND attempt.row_version=:attemptVersion
                FOR SHARE OF work,attempt
                """, p, r -> {
                    if (!r.next()) return null;
                    var result = new PrivatePayer(r.getString("payer_phone_ciphertext"), r.getString("payer_phone_nonce"));
                    if (r.next()) throw unavailable();
                    return result;
                });
        if (payer == null || payer.ciphertext() == null || !payer.ciphertext().matches("[A-Za-z0-9_-]{24,160}")
                || payer.nonce() == null || !payer.nonce().matches("[A-Za-z0-9_-]{16}")) throw unavailable();

        byte[] plaintext = null;
        try {
            plaintext = cipher.decrypt(new SensitiveValueCipher.EncryptedValue(payer.ciphertext(), payer.nonce()),
                    GatewayReservationService.associatedData(binding.organisationId(), binding.schoolId(), attempt.accountId(), claim.id()));
            if (plaintext == null || plaintext.length != 10) throw unavailable();
            for (byte digit : plaintext) if (digit < '0' || digit > '9') throw unavailable();
            // Actual DB time after every lock/decryption, not the caller's clock.
            // A lapse aborts this transaction and its not-yet-committed marker.
            Instant now = jdbc.queryForObject("SELECT clock_timestamp()", p,
                    (r, n) -> r.getObject(1, OffsetDateTime.class).toInstant());
            if (now == null || now.isBefore(admission.dispatchedAt()) || !now.isBefore(claim.leaseUntil())
                    || !now.isBefore(attempt.expiresAt())) throw unavailable();
            return new Command(admission, new PaymentProviderAdapter.CheckoutRequest(attempt.attemptId(), attempt.accountId(),
                    attempt.amount(), attempt.currencyCode(), attempt.expiresAt(), attempt.returnUri(),
                    new String(plaintext, StandardCharsets.US_ASCII)));
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static void requireAdmission(GatewayAttemptDispatch.CreateAdmission admission, GatewayWorkQueue.Claim claim) {
        if (admission == null || admission.attempt() == null || admission.book() == null || admission.dispatchedAt() == null) throw unavailable();
        var attempt = admission.attempt();
        if (!claim.id().equals(attempt.attemptId()) || !claim.binding().equals(attempt.binding())
                || !claim.leaseUntil().equals(attempt.leaseUntil()) || attempt.createPreviouslyDispatched()
                || !"RESERVED".equals(attempt.attemptStatus()) || !"ACTIVE".equals(attempt.accountStatus())
                || admission.dispatchedAt().isBefore(claim.startedAt()) || !admission.dispatchedAt().isBefore(claim.leaseUntil())
                || !admission.dispatchedAt().isBefore(attempt.expiresAt())) throw unavailable();
    }

    private GatewayWorkQueue.ProviderBinding trustedBinding(String routeKey, GatewayWorkQueue.Claim claim) {
        if (!registry.creationEnabled() || !registry.workerEnabled() || claim == null
                || claim.kind() != GatewayWorkQueue.WorkKind.ATTEMPT || claim.createDispatched()) throw unavailable();
        var route = registry.findRoute(routeKey).orElseThrow(GatewayCreateCommandAdmission::unavailable);
        if (route.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) throw unavailable();
        var binding = new GatewayWorkQueue.ProviderBinding(route.organisationId(), route.schoolId(), route.providerConfigId(),
                route.providerCode(), route.environment().name(), route.merchantAccountReference(), route.credentialsSecretReference());
        if (!binding.equals(claim.binding())) throw unavailable();
        return binding;
    }

    private static void requireOutsidePersistence() {
        if (TenantRequestContext.current().isPresent() || TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw unavailable();
    }

    private record PrivatePayer(String ciphertext, String nonce) {
        @Override public String toString() { return "PrivatePayer[encrypted private gateway contact]"; }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Gateway CREATE command requires committed trusted one-use admission and a valid scoped private payer");
    }
}

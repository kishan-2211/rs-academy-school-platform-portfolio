package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal persistence only: no scheduler, endpoint, credential lookup or provider I/O.
 * Enqueue joins the caller's reservation transaction. A future processor must commit the
 * one-use dispatch marker before CREATE and use only same-ID GET after an unknown dispatch.
 * VerifiedWebhook is an internal adapter result, not a request DTO or a payment-success claim.
 */
@Service
public class GatewayWorkQueue {
    private static final Duration LEASE = Duration.ofSeconds(90);
    private static final String UNKNOWN_LEASE = "GATEWAY_LEASE_OUTCOME_UNKNOWN";
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final Duration leaseDuration;

    @Autowired
    public GatewayWorkQueue(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this(jdbc, clock, LEASE);
    }

    GatewayWorkQueue(NamedParameterJdbcTemplate jdbc, Clock clock, Duration leaseDuration) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        if (leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(1)) < 0
                || leaseDuration.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("A one-to-120-second gateway lease required");
        }
        this.leaseDuration = leaseDuration;
    }

    /** Non-secret frozen identity; never use this label to resolve an environment variable. */
    public record ProviderBinding(UUID organisationId, UUID schoolId, UUID providerConfigId,
            String providerCode, String environment, String merchantReference, String credentialsReference) {
        public ProviderBinding {
            Objects.requireNonNull(organisationId, "Organisation required");
            Objects.requireNonNull(schoolId, "School required");
            Objects.requireNonNull(providerConfigId, "Provider configuration required");
            if (providerCode == null || !providerCode.matches("[A-Z][A-Z0-9_]{1,39}")
                    || !List.of("TEST", "LIVE").contains(environment == null ? "" : environment)
                    || merchantReference == null || !merchantReference.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,119}")
                    || credentialsReference == null || !credentialsReference.matches("[A-Za-z][A-Za-z0-9_./:-]{2,239}")) {
                throw new IllegalArgumentException("Canonical frozen provider binding required");
            }
        }
        @Override public String toString() { return "ProviderBinding[scoped server identity]"; }
    }

    public enum WorkKind {
        ATTEMPT("fee_gateway_attempt_work", "attempt_id"), WEBHOOK("fee_gateway_webhook_inbox", "id"),
        REFUND("fee_gateway_refund_work", "refund_request_id"), SETTLEMENT("fee_gateway_settlement_work", "id");
        private final String table, key;
        WorkKind(String table, String key) { this.table = table; this.key = key; }
    }

    public enum Outcome { RETRY, COMPLETE, REVIEW_REQUIRED }

    /** A lease is not authority to create, access a child, or issue a receipt. */
    public record Claim(WorkKind kind, UUID id, ProviderBinding binding, UUID token,
            Instant startedAt, Instant leaseUntil, int attemptNumber, boolean createDispatched) {
        public Claim {
            Objects.requireNonNull(kind); Objects.requireNonNull(id); Objects.requireNonNull(binding);
            Objects.requireNonNull(token); Objects.requireNonNull(startedAt); Objects.requireNonNull(leaseUntil);
            if (!leaseUntil.isAfter(startedAt) || leaseUntil.isAfter(startedAt.plusSeconds(120))
                    || attemptNumber < 1 || attemptNumber > 1000000
                    || createDispatched && kind != WorkKind.ATTEMPT && kind != WorkKind.REFUND) {
                throw new IllegalArgumentException("Bounded current gateway lease required");
            }
        }
        @Override public String toString() { return "Claim[internal scoped gateway lease]"; }
    }

    @Transactional
    public UUID enqueueAttempt(ProviderBinding binding, UUID attemptId) {
        Objects.requireNonNull(attemptId, "Persisted attempt required");
        MapSqlParameterSource p = scope(binding, false).addValue("id", attemptId).addValue("now", now());
        List<UUID> inserted = jdbc.query("""
                INSERT INTO fee_gateway_attempt_work(attempt_id,organisation_id,school_id,payment_provider_config_id,
                  provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,
                  next_attempt_at,created_at,updated_at)
                SELECT attempt.id,attempt.organisation_id,attempt.school_id,attempt.payment_provider_config_id,
                  attempt.provider_code,attempt.provider_environment,attempt.merchant_account_reference,
                  provider.credentials_secret_reference,:now,:now,:now
                FROM fee_gateway_attempts attempt JOIN payment_provider_configs provider
                  ON provider.id=attempt.payment_provider_config_id AND provider.organisation_id=attempt.organisation_id
                  AND provider.school_id=attempt.school_id
                WHERE attempt.id=:id AND attempt.organisation_id=:organisationId AND attempt.school_id=:schoolId
                  AND attempt.payment_provider_config_id=:providerId AND attempt.provider_code=:providerCode
                  AND attempt.provider_environment=:environment AND attempt.merchant_account_reference=:merchant
                  AND provider.credentials_secret_reference=:credentialsReference
                  AND (CAST(:userId AS uuid) IS NULL OR attempt.initiated_by_user_id=:userId)
                ON CONFLICT(attempt_id) DO NOTHING RETURNING attempt_id
                """, p, (r, n) -> r.getObject(1, UUID.class));
        if (!inserted.isEmpty()) return inserted.getFirst();
        UUID existing = jdbc.query("""
                SELECT work.attempt_id FROM fee_gateway_attempt_work work JOIN fee_gateway_attempts attempt
                  ON attempt.id=work.attempt_id AND attempt.organisation_id=work.organisation_id AND attempt.school_id=work.school_id
                WHERE work.attempt_id=:id AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                  AND work.payment_provider_config_id=:providerId AND work.provider_code=:providerCode
                  AND work.provider_environment=:environment AND work.merchant_account_reference=:merchant
                  AND work.credentials_secret_reference=:credentialsReference
                  AND (CAST(:userId AS uuid) IS NULL OR attempt.initiated_by_user_id=:userId)
                """, p, r -> r.next() ? r.getObject(1, UUID.class) : null);
        if (existing == null) throw new IllegalStateException("Gateway work requires its existing scoped attempt and provider binding");
        return existing;
    }

    /** Caller must authenticate original bytes through the configured adapter before calling. */
    @Transactional
    public UUID retainVerifiedWebhook(ProviderBinding binding, PaymentProviderAdapter.VerifiedWebhook event) {
        validateWebhook(binding, event);
        MapSqlParameterSource p = scope(binding, true);
        Instant now = now();
        p.addValue("id", UUID.randomUUID()).addValue("now", now)
                .addValue("hash", event.bodySha256()).addValue("type", event.eventType())
                .addValue("checkout", event.checkoutReference() == null ? null : canonicalUuid(event.checkoutReference()))
                .addValue("order", event.orderReference()).addValue("payment", event.paymentReference())
                .addValue("refund", event.refundReference());
        List<UUID> inserted = jdbc.query("""
                INSERT INTO fee_gateway_webhook_inbox(id,organisation_id,school_id,payment_provider_config_id,
                  provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,body_sha256,
                  event_type,checkout_reference,order_reference,payment_reference,refund_reference,received_at,
                  next_attempt_at,created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:providerId,:providerCode,:environment,:merchant,:credentialsReference,
                  :hash,:type,:checkout,:order,:payment,:refund,:now,:now,:now,:now)
                ON CONFLICT(payment_provider_config_id,body_sha256) DO NOTHING RETURNING id
                """, p, (r, n) -> r.getObject(1, UUID.class));
        if (!inserted.isEmpty()) return inserted.getFirst();
        UUID existing = jdbc.query("""
                SELECT id FROM fee_gateway_webhook_inbox WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND payment_provider_config_id=:providerId AND provider_code=:providerCode AND provider_environment=:environment
                  AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentialsReference
                  AND body_sha256=:hash AND event_type=:type AND checkout_reference IS NOT DISTINCT FROM :checkout
                  AND order_reference IS NOT DISTINCT FROM :order AND payment_reference=:payment
                  AND refund_reference IS NOT DISTINCT FROM :refund
                """, p, r -> r.next() ? r.getObject(1, UUID.class) : null);
        if (existing == null) throw new IllegalStateException("Authenticated gateway retry does not match retained minimal evidence");
        return existing;
    }

    /** Append independent provider GET facts. These do not satisfy capture or receipt proof. */
    @Transactional
    public UUID retainPaymentObservation(ProviderBinding binding, UUID attemptId,
            PaymentProviderAdapter.PaymentObservation observation) {
        validatePayment(observation, clock.instant());
        MapSqlParameterSource p = scope(binding, true);
        Instant now = now();
        validatePayment(observation, now);
        String hash = paymentEvidenceHash(binding, attemptId, observation);
        p.addValue("id", UUID.randomUUID()).addValue("attemptId", attemptId)
                .addValue("order", observation.orderReference()).addValue("payment", observation.paymentReference())
                .addValue("amount", observation.amount()).addValue("currency", observation.currencyCode())
                .addValue("status", observation.status().name()).addValue("captured", observation.captured())
                .addValue("paidAt", observation.providerPaidAt() == null ? null : GatewayExactInstant.parameter(observation.providerPaidAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("paidAtExact", observation.providerPaidAt() == null ? null : GatewayExactInstant.canonical(observation.providerPaidAt()))
                .addValue("method", observation.paymentMethod())
                .addValue("now", now).addValue("hash", hash);
        List<UUID> inserted = jdbc.query("""
                INSERT INTO fee_gateway_payment_observations(id,organisation_id,school_id,payment_provider_config_id,
                  provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,attempt_id,
                  order_reference,payment_reference,amount,currency_code,payment_status,captured,provider_paid_at,provider_paid_at_exact,
                  payment_method,observed_at,evidence_sha256)
                VALUES(:id,:organisationId,:schoolId,:providerId,:providerCode,:environment,:merchant,:credentialsReference,
                  :attemptId,:order,:payment,:amount,:currency,:status,:captured,:paidAt,:paidAtExact,:method,:now,:hash)
                ON CONFLICT(payment_provider_config_id,evidence_sha256) DO NOTHING RETURNING id
                """, p, (r, n) -> r.getObject(1, UUID.class));
        if (!inserted.isEmpty()) return inserted.getFirst();
        return jdbc.query("""
                SELECT id FROM fee_gateway_payment_observations WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND payment_provider_config_id=:providerId AND evidence_sha256=:hash
                """, p, r -> {
            if (!r.next()) throw new IllegalStateException("Gateway observation retry is unavailable in the scoped provider");
            return r.getObject(1, UUID.class);
        });
    }

    @Transactional public Claim claimNextAttempt(ProviderBinding binding) { return claim(WorkKind.ATTEMPT, binding); }
    @Transactional public Claim claimNextWebhook(ProviderBinding binding) { return claim(WorkKind.WEBHOOK, binding); }
    @Transactional public Claim claimNextRefund(ProviderBinding binding) { return claim(WorkKind.REFUND, binding); }
    @Transactional public Claim claimNextSettlement(ProviderBinding binding) { return claim(WorkKind.SETTLEMENT, binding); }

    /** Explicit recovery of pre-V080 approved intent; never rewrites or requeues terminal work. */
    @Transactional
    public UUID enqueueRefund(ProviderBinding binding, UUID refundId) {
        Objects.requireNonNull(refundId, "Persisted approved refund required");
        var p=scope(binding,true).addValue("id",refundId).addValue("now",now());
        jdbc.update("""
                INSERT INTO fee_gateway_refund_work(refund_request_id,organisation_id,school_id,approval_decision_id,
                  payment_provider_config_id,provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,
                  next_attempt_at,created_at,updated_at)
                SELECT request.id,request.organisation_id,request.school_id,decision.id,request.payment_provider_config_id,
                  request.provider_code,request.provider_environment,request.merchant_account_reference,request.credentials_secret_reference,:now,:now,:now
                FROM fee_gateway_refund_requests request JOIN fee_gateway_refund_decisions decision ON decision.refund_request_id=request.id
                  AND decision.organisation_id=request.organisation_id AND decision.school_id=request.school_id AND decision.decision='APPROVE'
                WHERE request.id=:id AND request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND request.payment_provider_config_id=:providerId AND request.provider_code=:providerCode AND request.provider_environment=:environment
                  AND request.merchant_account_reference=:merchant AND request.credentials_secret_reference=:credentialsReference
                ON CONFLICT(refund_request_id) DO NOTHING
                """,p);
        UUID found=jdbc.query("""
                SELECT refund_request_id FROM fee_gateway_refund_work WHERE refund_request_id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND payment_provider_config_id=:providerId AND provider_code=:providerCode AND provider_environment=:environment
                  AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentialsReference
                """,p,row->row.next()?row.getObject(1,UUID.class):null);
        if(found==null)throw new IllegalStateException("Refund work requires exact approved source and frozen provider");
        return found;
    }

    private Claim claim(WorkKind kind, ProviderBinding binding) {
        MapSqlParameterSource p = scope(binding, true);
        Instant now = now();
        p.addValue("now", now).addValue("token", UUID.randomUUID()).addValue("until", now.plus(leaseDuration));
        String dispatch = kind == WorkKind.ATTEMPT ? "work.create_dispatched_at IS NOT NULL" :
                kind == WorkKind.REFUND ? refundDispatched("work") : "false";
        return jdbc.query("""
                WITH due AS (SELECT %s FROM %s WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND payment_provider_config_id=:providerId AND provider_code=:providerCode AND provider_environment=:environment
                  AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentialsReference
                  AND status IN ('READY','RETRY') AND next_attempt_at<=:now AND attempt_count<1000000
                  ORDER BY next_attempt_at,%s FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE %s work SET status='LEASED',lease_token=:token,lease_started_at=:now,lease_until=:until,
                  attempt_count=attempt_count+1,updated_at=:now,row_version=row_version+1
                FROM due WHERE work.%s=due.%s
                RETURNING work.%s,work.lease_token,work.lease_started_at,work.lease_until,work.attempt_count,%s AS dispatched
                """.formatted(kind.key, kind.table, kind.key, kind.table, kind.key, kind.key, kind.key, dispatch), p,
                r -> r.next() ? new Claim(kind, r.getObject(1, UUID.class), binding, r.getObject(2, UUID.class),
                        r.getTimestamp(3).toInstant(), r.getTimestamp(4).toInstant(), r.getInt(5), r.getBoolean(6)) : null);
    }

    /**
     * True exactly once. Commit this transaction before a single CREATE. False never permits
     * CREATE; timeout/restart/repeated calls must use GET for the existing attempt identity.
     */
    @Transactional
    public boolean markCreateDispatched(Claim claim) {
        Objects.requireNonNull(claim);
        if (claim.kind() != WorkKind.ATTEMPT) throw new IllegalArgumentException("Only persisted attempt work may dispatch CREATE");
        MapSqlParameterSource p = scope(claim.binding(), true).addValue("id", claim.id()).addValue("token", claim.token());
        // Same finance fence/order as reservations, releases and receipt posting; no network in this transaction.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                p.addValue("lockKey", claim.binding().schoolId() + ":finance"), (r, n) -> true);
        p.addValue("now", now()); // Re-evaluate lease/expiry after waiting for competing finance work.
        return jdbc.update("""
                UPDATE fee_gateway_attempt_work work SET create_dispatched_at=:now,create_dispatched_by_lease_token=:token,
                  updated_at=:now,row_version=row_version+1
                WHERE work.attempt_id=:id AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                  AND work.payment_provider_config_id=:providerId AND work.provider_code=:providerCode AND work.provider_environment=:environment
                  AND work.merchant_account_reference=:merchant AND work.credentials_secret_reference=:credentialsReference
                  AND work.status='LEASED' AND work.lease_token=:token AND work.lease_until>:now AND work.lease_started_at<=:now
                  AND work.create_dispatched_at IS NULL AND EXISTS (SELECT 1 FROM fee_gateway_attempts attempt
                    WHERE attempt.id=work.attempt_id AND attempt.organisation_id=work.organisation_id AND attempt.school_id=work.school_id
                      AND attempt.status='RESERVED' AND attempt.expires_at>:now)
                """, p) == 1;
    }

    @Transactional
    public boolean complete(Claim claim, Outcome outcome, String safeCode, Duration retryAfter) {
        Objects.requireNonNull(claim); Objects.requireNonNull(outcome);
        requireSafeCode(safeCode);
        scope(claim.binding(), true);
        Instant now = now();
        Instant next = outcome == Outcome.RETRY ? now.plus(requireRetryDelay(retryAfter)) : now;
        return finish(claim, outcome, safeCode, now, next, false);
    }

    @Transactional public UUID recoverExpiredAttemptLease(ProviderBinding binding) { return recover(WorkKind.ATTEMPT, binding); }
    @Transactional public UUID recoverExpiredWebhookLease(ProviderBinding binding) { return recover(WorkKind.WEBHOOK, binding); }
    @Transactional public UUID recoverExpiredRefundLease(ProviderBinding binding) { return recover(WorkKind.REFUND, binding); }
    @Transactional public UUID recoverExpiredSettlementLease(ProviderBinding binding) { return recover(WorkKind.SETTLEMENT, binding); }

    private UUID recover(WorkKind kind, ProviderBinding binding) {
        MapSqlParameterSource p = scope(binding, true);
        Instant now = now();
        String dispatch = kind == WorkKind.ATTEMPT ? "create_dispatched_at IS NOT NULL" :
                kind == WorkKind.REFUND ? refundDispatched("fee_gateway_refund_work") : "false";
        Claim claim = jdbc.query("""
                SELECT %s,lease_token,lease_started_at,lease_until,attempt_count,%s AS dispatched FROM %s
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND payment_provider_config_id=:providerId
                  AND provider_code=:providerCode AND provider_environment=:environment AND merchant_account_reference=:merchant
                  AND credentials_secret_reference=:credentialsReference AND status='LEASED' AND lease_until<=:now
                ORDER BY lease_until,%s FOR UPDATE SKIP LOCKED LIMIT 1
                """.formatted(kind.key, dispatch, kind.table, kind.key), p.addValue("now", now),
                r -> r.next() ? new Claim(kind, r.getObject(1, UUID.class), binding, r.getObject(2, UUID.class),
                        r.getTimestamp(3).toInstant(), r.getTimestamp(4).toInstant(), r.getInt(5), r.getBoolean(6)) : null);
        if (claim == null) return null;
        return finish(claim, Outcome.RETRY, UNKNOWN_LEASE, now,
                now.plusSeconds(retryDelay(claim.attemptNumber(), claim.token())), true) ? claim.id() : null;
    }

    private boolean finish(Claim claim, Outcome outcome, String safeCode, Instant now, Instant next, boolean recovery) {
        MapSqlParameterSource p = scope(claim.binding(), true).addValue("id", claim.id()).addValue("token", claim.token())
                .addValue("now", now).addValue("next", next).addValue("status", outcome.name()).addValue("code", safeCode)
                .addValue("completed", outcome == Outcome.RETRY ? null : now);
        return jdbc.update("""
                UPDATE %s SET status=:status,next_attempt_at=:next,completed_at=:completed,last_error_code=:code,
                  lease_token=NULL,lease_started_at=NULL,lease_until=NULL,updated_at=:now,row_version=row_version+1
                WHERE %s=:id AND organisation_id=:organisationId AND school_id=:schoolId AND payment_provider_config_id=:providerId
                  AND provider_code=:providerCode AND provider_environment=:environment AND merchant_account_reference=:merchant
                  AND credentials_secret_reference=:credentialsReference AND status='LEASED' AND lease_token=:token
                  AND lease_started_at<=:now AND lease_until%s:now
                """.formatted(claim.kind().table, claim.kind().key, recovery ? "<=" : ">"), p) == 1;
    }

    private static String refundDispatched(String alias) {
        // Alias is a private fixed SQL identifier, never request/provider input.
        return "EXISTS(SELECT 1 FROM fee_gateway_refund_dispatches dispatch WHERE dispatch.refund_request_id="+alias+".refund_request_id"
                +" AND dispatch.organisation_id="+alias+".organisation_id AND dispatch.school_id="+alias+".school_id)";
    }

    private MapSqlParameterSource scope(ProviderBinding binding, boolean backgroundOnly) {
        Objects.requireNonNull(binding, "Frozen provider binding required");
        var context = TenantRequestContext.current().orElseThrow(() -> new IllegalStateException("Gateway work requires explicit tenant scope"));
        if (!binding.organisationId().equals(context.organisationId()) || backgroundOnly && context.userId() != null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Gateway work requires its matching writable tenant transaction and actor");
        }
        var p = new MapSqlParameterSource().addValue("organisationId", binding.organisationId()).addValue("schoolId", binding.schoolId())
                .addValue("providerId", binding.providerConfigId()).addValue("providerCode", binding.providerCode())
                .addValue("environment", binding.environment()).addValue("merchant", binding.merchantReference())
                .addValue("credentialsReference", binding.credentialsReference()).addValue("userId", context.userId(), java.sql.Types.OTHER);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NOT DISTINCT FROM :userId
                """, p, Boolean.class))) {
            throw new IllegalStateException("Gateway work database scope does not match its caller");
        }
        return p;
    }

    static void validateWebhook(ProviderBinding binding, PaymentProviderAdapter.VerifiedWebhook event) {
        Objects.requireNonNull(binding); Objects.requireNonNull(event);
        if (!binding.providerCode().equals(event.providerCode()) || event.eventType() == null || event.bodySha256() == null
                || !event.bodySha256().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Minimal authenticated provider event required");
        PaymentProviderAdapter.requireReference(event.paymentReference());
        if (event.orderReference() != null) PaymentProviderAdapter.requireReference(event.orderReference());
        if (event.refundReference() != null) PaymentProviderAdapter.requireReference(event.refundReference());
        boolean payment = List.of("PAYMENT_SUCCESS_WEBHOOK", "PAYMENT_FAILED_WEBHOOK", "PAYMENT_USER_DROPPED_WEBHOOK").contains(event.eventType());
        boolean refund = "REFUND_STATUS_WEBHOOK".equals(event.eventType());
        boolean autoRefund = "AUTO_REFUND_STATUS_WEBHOOK".equals(event.eventType());
        if ((!payment && !refund && !autoRefund) || payment && (event.orderReference() == null || event.refundReference() != null)
                || refund && (event.orderReference() == null || event.refundReference() == null)
                || !payment && event.checkoutReference() != null) throw new IllegalArgumentException("Canonical supported provider routing evidence required");
        if (event.checkoutReference() != null) canonicalUuid(event.checkoutReference());
    }

    static void validatePayment(PaymentProviderAdapter.PaymentObservation observation, Instant now) {
        Objects.requireNonNull(observation); Objects.requireNonNull(now);
        PaymentProviderAdapter.requireReference(observation.orderReference()); PaymentProviderAdapter.requireReference(observation.paymentReference());
        PaymentProviderAdapter.requireAmount(observation.amount()); Objects.requireNonNull(observation.status());
        if (observation.currencyCode() == null || !observation.currencyCode().matches("[A-Z]{3}")
                || observation.providerPaidAt() != null && observation.providerPaidAt().isAfter(now)
                || observation.status() == PaymentProviderAdapter.PaymentStatus.SUCCESS && observation.captured() && observation.providerPaidAt() == null
                || observation.paymentMethod() != null && !observation.paymentMethod().matches("[a-z][a-z0-9_]{0,40}")) {
            throw new IllegalArgumentException("Minimal independently observed payment facts required");
        }
        if (observation.providerPaidAt() != null) GatewayExactInstant.canonical(observation.providerPaidAt());
    }

    static String paymentEvidenceHash(ProviderBinding binding, UUID attemptId, PaymentProviderAdapter.PaymentObservation observation) {
        Objects.requireNonNull(binding); validatePayment(observation, Instant.MAX);
        // New namespace cannot acknowledge an immutable legacy row that lacks
        // the original exact source, even when its timestamp looks equivalent.
        String canonical = String.join("|", "PAYMENT_GET_EXACT_V2", binding.organisationId().toString(), binding.schoolId().toString(), binding.providerConfigId().toString(),
                binding.providerCode(), binding.environment(), binding.merchantReference(), binding.credentialsReference(), String.valueOf(attemptId),
                observation.orderReference(), observation.paymentReference(), observation.amount().setScale(2).toPlainString(), observation.currencyCode(),
                observation.status().name(), Boolean.toString(observation.captured()), String.valueOf(observation.providerPaidAt()),
                observation.paymentMethod() == null ? "-" : observation.paymentMethod());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 required"); }
    }

    static long retryDelay(int attemptNumber, UUID token) {
        Objects.requireNonNull(token);
        long backoff = 60L << Math.min(Math.max(attemptNumber - 1, 0), 6);
        return Math.min(3600, backoff + Math.floorMod(token.getLeastSignificantBits(), 60));
    }

    static Duration requireRetryDelay(Duration value) {
        if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0 || value.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("A bounded positive gateway retry delay required");
        }
        return value;
    }

    static String requireSafeCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{2,99}")) throw new IllegalArgumentException("A payload-free gateway result code required");
        return value;
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Canonical persisted checkout identity required"); }
    }
    /** Authoritative lease timestamps; application-clock advancement cannot fake database expiry. */
    private Instant now() {
        return jdbc.queryForObject("SELECT clock_timestamp()", new MapSqlParameterSource(),
                (r, n) -> r.getTimestamp(1).toInstant().truncatedTo(ChronoUnit.MICROS));
    }
}

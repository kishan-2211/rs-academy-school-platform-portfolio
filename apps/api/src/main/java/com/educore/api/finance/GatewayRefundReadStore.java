package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Immutable witnesses of independent provider GETs, not permission to dispatch a
 * refund or change money. No provider I/O or credentials are accepted here.
 * Allocate the read identity before GET; an uncertain persistence retry must keep
 * that identity and the same facts. A genuinely new GET requires a new identity.
 */
@Service
public class GatewayRefundReadStore {
    private final NamedParameterJdbcTemplate jdbc;

    public GatewayRefundReadStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public record Prepared(GatewayRefundVerification.Expected expected, UUID approvalDecisionId) {
        public Prepared { Objects.requireNonNull(expected); Objects.requireNonNull(approvalDecisionId); }
        @Override public String toString() { return "Prepared[private approved refund read provenance]"; }
    }

    public record Read(UUID id, GatewayRefundVerification.Expected expected,
            GatewayRefundVerification.Observation observation, GatewayRefundVerification.Result verification) {
        public Read {
            Objects.requireNonNull(id); Objects.requireNonNull(expected);
            Objects.requireNonNull(observation); Objects.requireNonNull(verification);
        }
        @Override public String toString() { return "Read[private immutable refund GET witness]"; }
    }

    /** Prepare only server-owned approved terms, then leave this transaction before GET. */
    @Transactional
    public Prepared prepare(GatewayWorkQueue.ProviderBinding binding, UUID refundId) {
        var p = scope(binding);
        fence(p, binding);
        return approved(p, binding, refundId);
    }

    /**
     * Retain the read even if a future worker has lost its lease. A read witness
     * cannot post a financial refund, release a budget or imply another POST is safe.
     */
    @Transactional
    public Read retain(GatewayWorkQueue.ProviderBinding binding, UUID refundId, UUID readId,
            PaymentProviderAdapter.RefundObservation facts) {
        Objects.requireNonNull(refundId, "Persisted refund identity required");
        Objects.requireNonNull(readId, "Stable independent read identity required");
        requireRetainableFacts(facts);
        var p = scope(binding).addValue("refundId", refundId).addValue("readId", readId);
        fence(p, binding);
        Prepared prepared = approved(p, binding, refundId);
        Read previous = existing(p, prepared);
        if (previous != null) return replay(previous, facts);

        Instant observedAt = jdbc.queryForObject("SELECT clock_timestamp()", p,
                (r, n) -> r.getObject(1, OffsetDateTime.class).toInstant());
        var observation = new GatewayRefundVerification.Observation(binding, facts, observedAt);
        var verified = GatewayRefundVerification.verify(prepared.expected(), observation);
        var proof = verified.success().orElse(null);
        p.addValue("approvalId", prepared.approvalDecisionId()).addValue("observedAt", GatewayExactInstant.parameter(observedAt))
                .addValue("hash", verified.evidenceSha256()).addValue("outcome", verified.outcome().name())
                .addValue("refundRef", facts == null ? null : facts.refundReference())
                .addValue("orderRef", facts == null ? null : facts.orderReference())
                .addValue("paymentRef", facts == null ? null : facts.paymentReference())
                .addValue("amount", facts == null ? null : facts.amount())
                .addValue("currency", facts == null ? null : facts.currencyCode())
                .addValue("status", facts == null ? null : facts.status().name())
                .addValue("processedAt", facts == null || facts.processedAt() == null ? null : GatewayExactInstant.parameter(facts.processedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("processedExact", facts == null || facts.processedAt() == null ? null : GatewayExactInstant.canonical(facts.processedAt()))
                .addValue("processedOn", proof == null ? null : proof.processedOn(), java.sql.Types.DATE);
        int inserted = jdbc.update("""
                INSERT INTO fee_gateway_refund_reads(id,organisation_id,school_id,refund_request_id,approval_decision_id,
                  payment_provider_config_id,provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,
                  observed_at,evidence_sha256,refund_reference,order_reference,payment_reference,amount,currency_code,
                  refund_status,processed_at,processed_at_exact,outcome,processed_on)
                VALUES(:readId,:organisationId,:schoolId,:refundId,:approvalId,:providerId,:providerCode,:environment,:merchant,
                  :credentials,:observedAt,:hash,:refundRef,:orderRef,:paymentRef,:amount,:currency,:status,
                  :processedAt,:processedExact,:outcome,:processedOn)
                ON CONFLICT(id) DO NOTHING
                """, p);
        if (inserted == 1) return new Read(readId, prepared.expected(), observation, verified);
        // A read ID is globally unique, but only its own exact tenant/refund may
        // recover it. Never reveal whether a foreign record claimed that identity.
        previous = existing(p, prepared);
        if (previous == null) throw unavailable();
        return replay(previous, facts);
    }

    private Prepared approved(MapSqlParameterSource p, GatewayWorkQueue.ProviderBinding binding, UUID refundId) {
        Objects.requireNonNull(refundId, "Persisted refund identity required");
        Prepared prepared = jdbc.query("""
                SELECT request.id,request.fee_payment_id,request.capture_id,request.amount AS refund_amount,
                  request.order_reference,request.payment_reference,request.school_time_zone,request.requested_at,
                  decision.id AS approval_id,decision.decided_at,capture.amount AS captured_amount,capture.paid_at,capture.paid_at_exact
                FROM fee_gateway_refund_requests request
                JOIN fee_gateway_refund_decisions decision ON decision.refund_request_id=request.id
                  AND decision.organisation_id=request.organisation_id AND decision.school_id=request.school_id AND decision.decision='APPROVE'
                JOIN fee_gateway_captures capture ON capture.id=request.capture_id
                  AND capture.organisation_id=request.organisation_id AND capture.school_id=request.school_id
                JOIN fee_payments payment ON payment.id=request.fee_payment_id
                  AND payment.organisation_id=request.organisation_id AND payment.school_id=request.school_id
                  AND payment.gateway_capture_id=capture.id AND payment.learner_fee_account_id=request.learner_fee_account_id
                  AND payment.payment_method='ONLINE_PROVIDER' AND payment.amount=capture.amount
                JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
                  AND attempt.organisation_id=capture.organisation_id AND attempt.school_id=capture.school_id
                  AND attempt.learner_fee_account_id=request.learner_fee_account_id AND attempt.school_time_zone=request.school_time_zone
                JOIN fee_gateway_capture_resolutions resolution ON resolution.capture_id=capture.id
                  AND resolution.organisation_id=capture.organisation_id AND resolution.school_id=capture.school_id
                  AND resolution.resolution_type='POSTED' AND resolution.fee_payment_id=payment.id AND resolution.open_date=payment.received_on
                WHERE request.id=:refundId AND request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND request.payment_provider_config_id=:providerId AND request.provider_code=:providerCode
                  AND request.provider_environment=:environment AND request.merchant_account_reference=:merchant
                  AND request.credentials_secret_reference=:credentials
                  AND capture.payment_provider_config_id=request.payment_provider_config_id AND capture.provider_code=request.provider_code
                  AND capture.provider_environment=request.provider_environment AND capture.merchant_account_reference=request.merchant_account_reference
                  AND capture.order_reference=request.order_reference AND capture.payment_reference=request.payment_reference
                  AND capture.currency_code='INR' AND request.currency_code='INR' AND request.amount<=capture.amount
                """, p.addValue("refundId", refundId), r -> r.next() ? new Prepared(
                        new GatewayRefundVerification.Expected(r.getObject("id", UUID.class), binding,
                                r.getObject("fee_payment_id", UUID.class), r.getObject("capture_id", UUID.class),
                                r.getString("order_reference"), r.getString("payment_reference"), r.getBigDecimal("captured_amount"),
                                r.getBigDecimal("refund_amount"), GatewayExactInstant.read(r, "paid_at_exact", "paid_at"),
                                instant(r, "requested_at"), instant(r, "decided_at"), r.getString("school_time_zone")),
                        r.getObject("approval_id", UUID.class)) : null);
        if (prepared == null) throw unavailable();
        return prepared;
    }

    private Read existing(MapSqlParameterSource p, Prepared prepared) {
        return jdbc.query("""
                SELECT * FROM fee_gateway_refund_reads WHERE id=:readId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND refund_request_id=:refundId AND approval_decision_id=:approvalId
                  AND payment_provider_config_id=:providerId AND provider_code=:providerCode AND provider_environment=:environment
                  AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentials
                """, p.addValue("approvalId", prepared.approvalDecisionId()), r -> {
            if (!r.next()) return null;
            var facts = r.getString("refund_reference") == null ? null : new PaymentProviderAdapter.RefundObservation(
                    r.getString("refund_reference"), r.getString("order_reference"), r.getString("payment_reference"),
                    r.getBigDecimal("amount"), r.getString("currency_code"), PaymentProviderAdapter.RefundStatus.valueOf(r.getString("refund_status")),
                    GatewayExactInstant.readNullable(r, "processed_at_exact", "processed_at"));
            var observed = new GatewayRefundVerification.Observation(prepared.expected().binding(), facts, instant(r, "observed_at"));
            var verified = GatewayRefundVerification.verify(prepared.expected(), observed);
            if (!verified.evidenceSha256().equals(r.getString("evidence_sha256"))
                    || !verified.outcome().name().equals(r.getString("outcome"))
                    || !Objects.equals(verified.success().map(GatewayRefundVerification.VerifiedSuccess::processedOn).orElse(null),
                            r.getObject("processed_on", java.time.LocalDate.class))) throw unavailable();
            return new Read(r.getObject("id", UUID.class), prepared.expected(), observed, verified);
        });
    }

    private static Read replay(Read previous, PaymentProviderAdapter.RefundObservation facts) {
        var replay = GatewayRefundVerification.verify(previous.expected(), new GatewayRefundVerification.Observation(
                previous.expected().binding(), facts, previous.observation().observedAt()));
        if (!previous.verification().evidenceSha256().equals(replay.evidenceSha256())) {
            throw new IllegalStateException("An independent refund read identity cannot be reused for different facts");
        }
        return previous;
    }

    private MapSqlParameterSource scope(GatewayWorkQueue.ProviderBinding binding) {
        Objects.requireNonNull(binding, "Frozen provider binding required");
        var context = TenantRequestContext.current().orElseThrow(GatewayRefundReadStore::unavailable);
        if (!binding.organisationId().equals(context.organisationId()) || context.userId() != null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
        var p = new MapSqlParameterSource().addValue("organisationId", binding.organisationId()).addValue("schoolId", binding.schoolId())
                .addValue("providerId", binding.providerConfigId()).addValue("providerCode", binding.providerCode())
                .addValue("environment", binding.environment()).addValue("merchant", binding.merchantReference()).addValue("credentials", binding.credentialsReference());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL", p, Boolean.class))) {
            throw unavailable();
        }
        return p;
    }

    private void fence(MapSqlParameterSource p, GatewayWorkQueue.ProviderBinding binding) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:fence,0))",
                p.addValue("fence", binding.schoolId() + ":finance"), (r, n) -> true);
    }

    private static void requireRetainableFacts(PaymentProviderAdapter.RefundObservation facts) {
        if (facts == null) return;
        try {
            PaymentProviderAdapter.requireReference(facts.refundReference());
            PaymentProviderAdapter.requireReference(facts.orderReference());
            PaymentProviderAdapter.requireReference(facts.paymentReference());
            PaymentProviderAdapter.requireAmount(facts.amount());
            if (facts.status() == null || facts.currencyCode() == null || !facts.currencyCode().matches("[A-Z]{3}")) throw unavailable();
            if (facts.processedAt() != null) GatewayExactInstant.canonical(facts.processedAt());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Minimal canonical independently read refund facts required");
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Refund GET evidence requires its approved frozen source and matching writable background scope");
    }
}

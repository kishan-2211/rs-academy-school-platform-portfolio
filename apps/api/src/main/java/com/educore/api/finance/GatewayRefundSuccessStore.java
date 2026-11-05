package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Recognizes an already committed, independently verified refund GET as an
 * immutable HOLD source. No provider I/O, work admission, money write or budget
 * release exists here. Call after the read-retention transaction has committed;
 * reconciliation failures must not roll back the independent read witness.
 */
@Service
public class GatewayRefundSuccessStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayRefundReadStore reads;

    public GatewayRefundSuccessStore(NamedParameterJdbcTemplate jdbc, GatewayRefundReadStore reads) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.reads = Objects.requireNonNull(reads);
    }

    public record Success(UUID refundId, UUID verifiedReadId, UUID approvalDecisionId,
            UUID paymentId, UUID captureId, UUID accountId, BigDecimal amount, String currencyCode,
            String processedAtExact, Instant processedAtProjection, LocalDate processedOn,
            String schoolTimeZone, Instant recognizedAt, UUID closedDayCloseId,
            String evidenceSha256, int sourceVersion, String status, String holdReason) {
        @Override public String toString() { return "Success[private immutable refund recognition HOLD]"; }
    }

    /** Distinguishes contradictory retained money facts from missing server provenance. */
    public static final class ConflictingSuccessEvidenceException extends IllegalStateException {
        private ConflictingSuccessEvidenceException() {
            super("Refund success facts require reconciliation; retained evidence is unchanged");
        }
    }

    private record Source(GatewayRefundReadStore.Prepared approved, UUID readId, UUID accountId,
            GatewayRefundVerification.Result verification) {
        @Override public String toString() { return "Source[private retained approved refund success facts]"; }
    }

    /**
     * Only persisted identities are accepted. Repeated identical successful GETs
     * return the original anchor, including its first read, DB time and close.
     * A different successful fact set requires review; it never replaces money
     * evidence or makes a second anchor/financial completion possible.
     * New inserts require PostgreSQL READ COMMITTED: the database rejects an
     * older RR/SERIALIZABLE close-history snapshot with retryable SQLSTATE 40001.
     * Existing immutable-anchor replay still uses the shared capture epoch.
     */
    @Transactional
    public Success retain(GatewayWorkQueue.ProviderBinding binding, UUID refundId, UUID readId) {
        Objects.requireNonNull(refundId, "Persisted refund identity required");
        Objects.requireNonNull(readId, "Persisted independent read identity required");
        var p = scope(binding).addValue("refundId", refundId).addValue("readId", readId);
        fence(p);
        var approved = reads.prepare(binding, refundId);
        var source = source(p, approved, readId);
        p.addValue("hash", source.verification().evidenceSha256());
        if (conflictingSuccess(p)) throw new ConflictingSuccessEvidenceException();
        Success previous = existing(p);
        if (previous != null) return replay(previous, source);

        var expected = approved.expected();
        var proof = source.verification().success().orElseThrow(GatewayRefundSuccessStore::unavailable);
        UUID closedDayId = jdbc.queryForObject("""
                SELECT (SELECT id FROM fee_day_closes WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='APPROVED' AND business_date>=:processedOn ORDER BY business_date DESC,id LIMIT 1)
                """, p.addValue("processedOn", proof.processedOn()), UUID.class);
        p.addValue("approvalId", approved.approvalDecisionId()).addValue("paymentId", expected.paymentId())
                .addValue("captureId", expected.captureId()).addValue("accountId", source.accountId())
                .addValue("refundReference", expected.refundId().toString()).addValue("orderReference", expected.orderReference())
                .addValue("paymentReference", expected.paymentReference()).addValue("amount", expected.refundAmount())
                .addValue("timeZone", expected.schoolTimeZone()).addValue("processedAt", GatewayExactInstant.parameter(
                        GatewayExactInstant.parseCanonical(proof.processedAtExact())))
                .addValue("processedExact", proof.processedAtExact()).addValue("closedDayId", closedDayId, java.sql.Types.OTHER)
                .addValue("holdReason", closedDayId == null ? "AWAITING_REVIEWED_POSTING" : "ORIGINAL_FINANCIAL_DAY_CLOSED");
        var inserted = jdbc.query("""
                INSERT INTO fee_gateway_refund_successes(refund_request_id,organisation_id,school_id,verified_read_id,
                  approval_decision_id,fee_payment_id,capture_id,learner_fee_account_id,payment_provider_config_id,
                  provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,
                  refund_reference,order_reference,payment_reference,amount,currency_code,school_time_zone,
                  processed_at,processed_at_exact,processed_on,source_version,evidence_sha256,closed_day_close_id,status,hold_reason)
                VALUES(:refundId,:organisationId,:schoolId,:readId,:approvalId,:paymentId,:captureId,:accountId,:providerId,
                  :providerCode,:environment,:merchant,:credentials,:refundReference,:orderReference,:paymentReference,
                  :amount,'INR',:timeZone,:processedAt,:processedExact,:processedOn,1,:hash,:closedDayId,'HOLD',:holdReason)
                ON CONFLICT(refund_request_id) DO NOTHING RETURNING *
                """, p, GatewayRefundSuccessStore::success);
        if (!inserted.isEmpty()) return inserted.getFirst();
        // A globally claimed refund identity must never disclose another scope.
        // Old transaction snapshots also fail closed instead of inventing an anchor.
        previous = existing(p);
        if (previous == null) throw unavailable();
        return replay(previous, source);
    }

    private Source source(MapSqlParameterSource p, GatewayRefundReadStore.Prepared approved, UUID readId) {
        return jdbc.query("""
                SELECT witness.*,request.learner_fee_account_id FROM fee_gateway_refund_reads witness
                JOIN fee_gateway_refund_requests request ON request.id=witness.refund_request_id
                  AND request.organisation_id=witness.organisation_id AND request.school_id=witness.school_id
                WHERE witness.id=:readId AND witness.refund_request_id=:refundId
                  AND witness.organisation_id=:organisationId AND witness.school_id=:schoolId
                  AND witness.approval_decision_id=:approvalId AND witness.outcome='VERIFIED_SUCCESS'
                  AND witness.payment_provider_config_id=:providerId AND witness.provider_code=:providerCode
                  AND witness.provider_environment=:environment AND witness.merchant_account_reference=:merchant
                  AND witness.credentials_secret_reference=:credentials
                """, p.addValue("approvalId", approved.approvalDecisionId()), r -> {
            if (!r.next()) throw unavailable();
            var facts = new PaymentProviderAdapter.RefundObservation(r.getString("refund_reference"), r.getString("order_reference"),
                    r.getString("payment_reference"), r.getBigDecimal("amount"), r.getString("currency_code"),
                    PaymentProviderAdapter.RefundStatus.valueOf(r.getString("refund_status")),
                    GatewayExactInstant.read(r, "processed_at_exact", "processed_at"));
            var observed = new GatewayRefundVerification.Observation(approved.expected().binding(), facts, instant(r, "observed_at"));
            var verified = GatewayRefundVerification.verify(approved.expected(), observed);
            if (verified.outcome() != GatewayRefundVerification.Outcome.VERIFIED_SUCCESS
                    || !verified.evidenceSha256().equals(r.getString("evidence_sha256"))
                    || !verified.success().orElseThrow(GatewayRefundSuccessStore::unavailable).processedOn()
                            .equals(r.getObject("processed_on", LocalDate.class))) throw unavailable();
            return new Source(approved, readId, r.getObject("learner_fee_account_id", UUID.class), verified);
        });
    }

    private boolean conflictingSuccess(MapSqlParameterSource p) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_gateway_refund_reads WHERE refund_request_id=:refundId
                  AND organisation_id=:organisationId AND school_id=:schoolId
                  AND outcome='VERIFIED_SUCCESS' AND evidence_sha256<>:hash)
                """, p, Boolean.class));
    }

    private Success existing(MapSqlParameterSource p) {
        var values = jdbc.query("""
                SELECT * FROM fee_gateway_refund_successes WHERE refund_request_id=:refundId
                  AND organisation_id=:organisationId AND school_id=:schoolId
                  AND payment_provider_config_id=:providerId AND provider_code=:providerCode AND provider_environment=:environment
                  AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentials
                """, p, GatewayRefundSuccessStore::success);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static Success replay(Success previous, Source source) {
        var expected = source.approved().expected();
        var proof = source.verification().success().orElseThrow(GatewayRefundSuccessStore::unavailable);
        if (!previous.evidenceSha256().equals(source.verification().evidenceSha256())) {
            throw new ConflictingSuccessEvidenceException();
        }
        if (!previous.refundId().equals(expected.refundId()) || !previous.approvalDecisionId().equals(source.approved().approvalDecisionId())
                || !previous.paymentId().equals(expected.paymentId()) || !previous.captureId().equals(expected.captureId())
                || !previous.accountId().equals(source.accountId()) || previous.amount().compareTo(expected.refundAmount()) != 0
                || !"INR".equals(previous.currencyCode()) || !previous.schoolTimeZone().equals(expected.schoolTimeZone())
                || !previous.processedAtExact().equals(proof.processedAtExact())
                || !previous.processedAtProjection().equals(proof.processedAtProjection())
                || !previous.processedOn().equals(proof.processedOn()) || previous.sourceVersion() != 1
                || !"HOLD".equals(previous.status())) throw unavailable();
        return previous;
    }

    private static Success success(ResultSet row, int index) throws SQLException {
        Instant exact = GatewayExactInstant.read(row, "processed_at_exact", "processed_at");
        return new Success(row.getObject("refund_request_id", UUID.class), row.getObject("verified_read_id", UUID.class),
                row.getObject("approval_decision_id", UUID.class), row.getObject("fee_payment_id", UUID.class),
                row.getObject("capture_id", UUID.class), row.getObject("learner_fee_account_id", UUID.class),
                row.getBigDecimal("amount"), row.getString("currency_code"), GatewayExactInstant.canonical(exact),
                GatewayExactInstant.floor(exact), row.getObject("processed_on", LocalDate.class), row.getString("school_time_zone"),
                instant(row, "recognized_at"), row.getObject("closed_day_close_id", UUID.class), row.getString("evidence_sha256"),
                row.getInt("source_version"), row.getString("status"), row.getString("hold_reason"));
    }

    private MapSqlParameterSource scope(GatewayWorkQueue.ProviderBinding binding) {
        Objects.requireNonNull(binding, "Frozen provider binding required");
        var context = TenantRequestContext.current().orElseThrow(GatewayRefundSuccessStore::unavailable);
        if (!binding.organisationId().equals(context.organisationId()) || context.userId() != null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
        var p = new MapSqlParameterSource().addValue("organisationId", binding.organisationId()).addValue("schoolId", binding.schoolId())
                .addValue("providerId", binding.providerConfigId()).addValue("providerCode", binding.providerCode())
                .addValue("environment", binding.environment()).addValue("merchant", binding.merchantReference()).addValue("credentials", binding.credentialsReference());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL",
                p, Boolean.class))) throw unavailable();
        return p;
    }

    private void fence(MapSqlParameterSource p) {
        // The same technical capture epoch is written by V075's direct-SQL
        // new-read guard. Even an exact anchor replay must not miss conflicting
        // evidence committed after a REPEATABLE READ/SERIALIZABLE snapshot.
        jdbc.queryForObject("SELECT touch_gateway_refund_evidence_fence(:refundId,:organisationId,:schoolId)",
                p, (r, n) -> true);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Refund success recognition requires a retained exact approved read and matching writable background scope");
    }
}

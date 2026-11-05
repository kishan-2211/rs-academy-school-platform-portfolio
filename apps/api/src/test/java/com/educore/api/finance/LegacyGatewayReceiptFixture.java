package com.educore.api.finance;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bounded prior-schema population for genuine V072..V077 upgrade rehearsals.
 * This is not current FinanceService acceptance. It uses the actual non-owner
 * SYSTEM context, V068/V071 evidence/admission/deferred guards, normal numbered
 * receipt/ledger/allocation writes and the real system audit service. It cannot
 * run after V078 or against another database, identity, provider or real school.
 */
final class LegacyGatewayReceiptFixture {
    private LegacyGatewayReceiptFixture() {}

    static FinanceService.GatewayPostingResult seed(JdbcTemplate jdbc, ApplicationProperties properties,
            SecurityAuditService audit, UUID organisationId, UUID schoolId, UUID captureId) {
        requireLegacySystem(jdbc, properties, organisationId);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) locked",
                Integer.class, schoolId + ":finance");
        var sources = jdbc.query("""
                SELECT capture.attempt_id,intent.learner_fee_account_id,account.academic_session_id,capture.amount,
                  capture.payment_reference,capture.original_business_date,intent.status AS attempt_status
                FROM fee_gateway_captures capture JOIN fee_gateway_attempts intent ON intent.id=capture.attempt_id
                  AND intent.organisation_id=capture.organisation_id AND intent.school_id=capture.school_id
                JOIN learner_fee_accounts account ON account.id=intent.learner_fee_account_id
                  AND account.organisation_id=intent.organisation_id AND account.school_id=intent.school_id
                  AND account.status='ACTIVE' AND account.currency_code='INR'
                JOIN learners learner ON learner.id=account.learner_id AND learner.organisation_id=account.organisation_id
                  AND learner.school_id=account.school_id AND learner.legal_name='Synthetic learner'
                JOIN schools school ON school.id=capture.school_id AND school.organisation_id=capture.organisation_id
                  AND school.legal_name='Synthetic school' AND school.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=capture.organisation_id
                  AND organisation.legal_name='Synthetic school' AND organisation.status='ACTIVE'
                JOIN payment_provider_configs provider ON provider.id=capture.payment_provider_config_id
                  AND provider.organisation_id=capture.organisation_id AND provider.school_id=capture.school_id
                  AND provider.provider_code='CASHFREE' AND provider.environment='TEST' AND provider.status='ACTIVE'
                  AND provider.credentials_secret_reference='synthetic:not-enabled'
                  AND provider.merchant_account_reference='synthetic_merchant_' || provider.id::text
                  AND capture.provider_code=provider.provider_code AND capture.provider_environment=provider.environment
                  AND capture.merchant_account_reference=provider.merchant_account_reference
                WHERE capture.id=? AND capture.organisation_id=? AND capture.school_id=?
                  AND capture.payment_reference LIKE 'synthetic%'
                FOR UPDATE OF intent
                """, (row, number) -> new Source(row.getObject("attempt_id", UUID.class),
                        row.getObject("learner_fee_account_id", UUID.class), row.getObject("academic_session_id", UUID.class),
                        row.getBigDecimal("amount"), row.getString("payment_reference"), row.getObject("original_business_date", LocalDate.class),
                        row.getString("attempt_status")), captureId, organisationId, schoolId);
        if (sources.size() != 1) throw denied();
        Source source = sources.getFirst();
        var existing = jdbc.query("""
                SELECT payment.id,payment.receipt_number FROM fee_payments payment
                JOIN fee_gateway_capture_resolutions disposition ON disposition.fee_payment_id=payment.id
                  AND disposition.organisation_id=payment.organisation_id AND disposition.school_id=payment.school_id
                  AND disposition.capture_id=payment.gateway_capture_id AND disposition.resolution_type='POSTED'
                  AND disposition.actor_kind='SYSTEM'
                WHERE payment.gateway_capture_id=? AND payment.organisation_id=? AND payment.school_id=?
                  AND payment.actor_kind='SYSTEM' AND payment.status='POSTED'
                """, (row, number) -> new FinanceService.GatewayPostingResult("POSTED", row.getObject("id", UUID.class),
                        row.getString("receipt_number")), captureId, organisationId, schoolId);
        if (!existing.isEmpty()) {
            if (existing.size() != 1) throw denied();
            return existing.getFirst();
        }
        if (!List.of("RESERVED", "CHECKOUT_PENDING").contains(source.status())
                || !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT gateway_receipt_evidence_matches(?,?,?) AND gateway_system_posting_allowed(?,?,?)
                      AND EXISTS(SELECT 1 FROM fee_gateway_attempts intent WHERE intent.id=?
                        AND intent.organisation_id=? AND intent.school_id=? AND intent.fee_payment_id IS NULL
                        AND ? <= (clock_timestamp() AT TIME ZONE intent.school_time_zone)::date)
                    """, Boolean.class, captureId, organisationId, schoolId, captureId, organisationId, schoolId,
                    source.attemptId(), organisationId, schoolId, source.day()))) throw denied();
        var allocations = jdbc.query("""
                SELECT allocation.learner_fee_charge_id,allocation.amount,charge.amount AS charge_amount,charge.allow_partial,
                  COALESCE((SELECT sum(concession.amount) FROM fee_ledger_entries concession
                    WHERE concession.learner_fee_charge_id=charge.id AND concession.organisation_id=charge.organisation_id
                      AND concession.school_id=charge.school_id AND concession.entry_type='CONCESSION' AND concession.direction='CREDIT'),0) concession,
                  COALESCE((SELECT sum(paid.amount) FROM fee_payment_allocations paid JOIN fee_payments payment ON payment.id=paid.fee_payment_id
                    AND payment.organisation_id=paid.organisation_id AND payment.school_id=paid.school_id AND payment.status='POSTED'
                    WHERE paid.learner_fee_charge_id=charge.id AND paid.organisation_id=charge.organisation_id AND paid.school_id=charge.school_id),0) paid,
                  COALESCE((SELECT sum(reserved.amount) FROM fee_gateway_allocations reserved WHERE reserved.learner_fee_charge_id=charge.id
                    AND reserved.organisation_id=charge.organisation_id AND reserved.school_id=charge.school_id AND reserved.status='ACTIVE'),0) reserved
                FROM fee_gateway_allocations allocation JOIN learner_fee_charges charge ON charge.id=allocation.learner_fee_charge_id
                  AND charge.organisation_id=allocation.organisation_id AND charge.school_id=allocation.school_id
                  AND charge.learner_fee_account_id=allocation.learner_fee_account_id
                WHERE allocation.attempt_id=? AND allocation.organisation_id=? AND allocation.school_id=?
                  AND allocation.learner_fee_account_id=? AND allocation.status='ACTIVE'
                ORDER BY allocation.learner_fee_charge_id LIMIT 101 FOR UPDATE OF allocation
                """, (row, number) -> new Allocation(row.getObject("learner_fee_charge_id", UUID.class), row.getBigDecimal("amount"),
                        row.getBigDecimal("charge_amount").subtract(row.getBigDecimal("concession")).subtract(row.getBigDecimal("paid")),
                        row.getBigDecimal("reserved"), row.getBoolean("allow_partial")), source.attemptId(), organisationId, schoolId, source.accountId());
        if (allocations.isEmpty() || allocations.size() > 100 || allocations.stream().map(Allocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(source.amount()) != 0
                || allocations.stream().anyMatch(allocation -> allocation.amount().signum() <= 0
                    || allocation.amount().compareTo(allocation.outstanding()) > 0
                    || allocation.amount().compareTo(allocation.outstanding().subtract(allocation.reserved()).add(allocation.amount())) > 0
                    || (!allocation.partial() && allocation.amount().compareTo(allocation.outstanding()) != 0))) throw denied();
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
        if (source.status().equals("RESERVED")) requireOne(jdbc.update("""
                UPDATE fee_gateway_attempts SET status='CHECKOUT_PENDING',updated_at=?,row_version=row_version+1
                WHERE id=? AND organisation_id=? AND school_id=? AND status='RESERVED'
                """, GatewayExactInstant.parameter(now), source.attemptId(), organisationId, schoolId));
        var sequence = jdbc.query("""
                UPDATE fee_receipt_sequences SET next_value=next_value+1,updated_at=?
                WHERE organisation_id=? AND school_id=? AND academic_session_id=? AND next_value<999999999
                RETURNING receipt_prefix,next_value-1 AS issued_number
                """, (row, number) -> new Sequence(row.getString("receipt_prefix"), row.getLong("issued_number")),
                GatewayExactInstant.parameter(now), organisationId, schoolId, source.sessionId());
        if (sequence.size() != 1) throw denied();
        String prefix = sequence.getFirst().prefix();
        String receipt = prefix + (prefix.endsWith("/") || prefix.endsWith("-") ? "" : "-")
                + String.format(Locale.ROOT, "%06d", sequence.getFirst().number());
        UUID paymentId = UUID.randomUUID(), ledgerId = UUID.randomUUID();
        requireOne(jdbc.update("""
                INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,learner_fee_charge_id,
                  entry_type,direction,amount,effective_on,description,source_type,source_id,reverses_entry_id,
                  created_by_membership_id,created_at,actor_kind,gateway_capture_id)
                VALUES(?,?,?,?,NULL,'PAYMENT','CREDIT',?, ?,?,'FEE_PAYMENT',?,NULL,NULL,?,'SYSTEM',?)
                """, ledgerId, organisationId, schoolId, source.accountId(), source.amount(), source.day(), "Fee payment " + receipt,
                paymentId, GatewayExactInstant.parameter(now), captureId));
        requireOne(jdbc.update("""
                INSERT INTO fee_payments(id,organisation_id,school_id,learner_fee_account_id,receipt_number,idempotency_key,
                  request_fingerprint,payment_method,external_reference,amount,received_on,notes,status,ledger_entry_id,
                  received_by_membership_id,created_at,updated_at,row_version,actor_kind,gateway_capture_id)
                VALUES(?,?,?,?,?,?,?,'ONLINE_PROVIDER',?,?,?,NULL,'POSTED',?,NULL,?,?,0,'SYSTEM',?)
                """, paymentId, organisationId, schoolId, source.accountId(), receipt, "gateway:" + captureId,
                digest(captureId + "|" + source.paymentReference() + "|" + source.amount().toPlainString() + "|" + source.day()),
                source.paymentReference(), source.amount(), source.day(), ledgerId, GatewayExactInstant.parameter(now), GatewayExactInstant.parameter(now), captureId));
        for (Allocation allocation : allocations) requireOne(jdbc.update("""
                INSERT INTO fee_payment_allocations(id,organisation_id,school_id,fee_payment_id,learner_fee_charge_id,amount,created_at)
                VALUES(?,?,?,?,?,?,?)
                """, UUID.randomUUID(), organisationId, schoolId, paymentId, allocation.chargeId(), allocation.amount(), GatewayExactInstant.parameter(now)));
        requireOne(jdbc.update("""
                INSERT INTO fee_gateway_capture_resolutions(id,organisation_id,school_id,capture_id,resolution_type,reason,
                  open_date,fee_payment_id,reviewed_by_user_id,reviewed_by_membership_id,reviewed_at,actor_kind,system_component,closed_day_close_id)
                VALUES(?,?,?,?,'POSTED','Verified gateway capture posted on its original open financial date',?,?,NULL,NULL,?,'SYSTEM','GATEWAY_VERIFICATION',NULL)
                """, UUID.randomUUID(), organisationId, schoolId, captureId, source.day(), paymentId, GatewayExactInstant.parameter(now)));
        if (jdbc.update("""
                UPDATE fee_gateway_allocations SET status='CONSUMED',updated_at=?,row_version=row_version+1
                WHERE attempt_id=? AND organisation_id=? AND school_id=? AND status='ACTIVE'
                """, GatewayExactInstant.parameter(now), source.attemptId(), organisationId, schoolId) != allocations.size()) throw denied();
        requireOne(jdbc.update("""
                UPDATE fee_gateway_attempts SET status='POSTED',fee_payment_id=?,updated_at=?,row_version=row_version+1
                WHERE id=? AND organisation_id=? AND school_id=? AND status='CHECKOUT_PENDING' AND fee_payment_id IS NULL
                """, paymentId, GatewayExactInstant.parameter(now), source.attemptId(), organisationId, schoolId));
        audit.recordGatewaySystemScoped(organisationId, schoolId, captureId, paymentId, "POSTED");
        return new FinanceService.GatewayPostingResult("POSTED", paymentId, receipt);
    }

    private static void requireLegacySystem(JdbcTemplate jdbc, ApplicationProperties properties, UUID organisationId) {
        var tenant = TenantRequestContext.current().orElse(null);
        if (organisationId == null || tenant == null || tenant.userId() != null || !organisationId.equals(tenant.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || properties == null || !"https://synthetic-school.example.test".equals(properties.publicUrl())
                || !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT current_database() ~ '^educore_online_[a-f0-9]{32}$'
                      AND current_app_user_id() IS NULL AND current_app_organisation_id()=?
                      AND NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user)
                      AND current_user<>(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database())
                      AND current_setting('row_security')='on'
                      AND to_regprocedure('public.fee_charge_net_paid(uuid,uuid,uuid,date)') IS NULL
                      AND to_regclass('public.fee_gateway_refund_postings') IS NULL
                    """, Boolean.class, organisationId))) throw denied();
    }

    private static String digest(String source) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static void requireOne(int count) { if (count != 1) throw denied(); }
    private static IllegalStateException denied() { return new IllegalStateException("Guarded isolated synthetic pre-V078 receipt population required"); }
    private record Source(UUID attemptId, UUID accountId, UUID sessionId, BigDecimal amount, String paymentReference, LocalDate day, String status) {}
    private record Allocation(UUID chargeId, BigDecimal amount, BigDecimal outstanding, BigDecimal reserved, boolean partial) {}
    private record Sequence(String prefix, long number) {}
}

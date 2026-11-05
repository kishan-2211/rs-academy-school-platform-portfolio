package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Human-attested source evidence for the existing common receipt, not a second ledger.
 * Both eligibility and recording join the caller's writable transaction. No provider
 * request, screenshot, reference alone or browser assertion is proof of bank settlement.
 */
@Service
public class ManualCollectionEvidence {
    private static final Set<String> METHODS = Set.of("CASH", "BANK_TRANSFER", "SCHOOL_UPI");
    private static final Set<String> CREDIT_SOURCES = Set.of("BANK_APP_CREDIT", "BANK_STATEMENT_CREDIT");
    private final NamedParameterJdbcTemplate jdbc;

    public ManualCollectionEvidence(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public record ValueScope(UUID organisationId, UUID schoolId, UUID membershipId, String schoolTimeZone) {
        public ValueScope {
            Objects.requireNonNull(organisationId, "Organisation required");
            Objects.requireNonNull(schoolId, "School required");
            Objects.requireNonNull(membershipId, "Actual human membership required");
            if (schoolTimeZone == null || schoolTimeZone.length() > 100) {
                throw new IllegalArgumentException("Configured school time zone required");
            }
            ZoneId.of(schoolTimeZone);
        }
        @Override public String toString() { return "ValueScope[scoped human financial actor]"; }
    }

    public record BankCreditVerification(String sourceKind, boolean confirmedReceived, String creditedEntryReference) {
        @Override public String toString() { return "BankCreditVerification[private received-funds attestation]"; }
    }

    public record EvidenceInput(Instant actualReceivedAt, LocalDate originalReceivedOn, LocalDate postingOn,
            BankCreditVerification credit, String reviewReason) {
        @Override public String toString() { return "EvidenceInput[private manual collection evidence]"; }
    }

    public record Prepared(String paymentMethod, String receiptReference, Instant actualReceivedAt,
            LocalDate originalReceivedOn, LocalDate postingOn, String receivedTimePrecision,
            String verificationKind, String bankCreditReference, String normalizedBankReference, String reviewReason) {
        @Override public String toString() { return "Prepared[private canonical manual collection evidence]"; }
    }

    /** Pure canonicalization. Missing INSTANT cash evidence is honestly DATE_ONLY, never invented midnight. */
    public Prepared prepare(ValueScope scope, String method, String reference, EvidenceInput input) {
        Objects.requireNonNull(scope, "Validated school scope required");
        Objects.requireNonNull(input, "Received-funds evidence required");
        if (!METHODS.contains(method == null ? "" : method)) {
            throw new IllegalArgumentException("Only supported manual collection methods have received-funds evidence");
        }
        LocalDate original = Objects.requireNonNull(input.originalReceivedOn(), "Original received day required");
        LocalDate posting = input.postingOn() == null ? original : input.postingOn();
        if (original.isBefore(LocalDate.of(2000, 1, 1)) || posting.isBefore(original)) {
            throw new IllegalArgumentException("Posting cannot precede the original received day");
        }
        Instant actual = input.actualReceivedAt();
        if (actual != null && !actual.atZone(ZoneId.of(scope.schoolTimeZone())).toLocalDate().equals(original)) {
            throw new IllegalArgumentException("Actual received time must match its original school-local day");
        }
        String reason = clean(input.reviewReason());
        if (reason != null && (reason.length() < 3 || reason.length() > 240 || unsafeControl(reason))) {
            throw new IllegalArgumentException("Use a three-to-240-character safe reviewed-posting reason");
        }
        if (!posting.equals(original) && reason == null) {
            throw new IllegalArgumentException("A different posting day requires an explicit review reason");
        }
        if ("CASH".equals(method)) {
            if (input.credit() != null) throw new IllegalArgumentException("Cash must not claim bank-credit verification");
            String cashReference = clean(reference);
            if (cashReference != null && (cashReference.length() > 120 || unsafeControl(cashReference))) {
                throw new IllegalArgumentException("Keep a cash reference bounded and free of control characters");
            }
            return new Prepared(method, cashReference, actual, original, posting,
                    actual == null ? "DATE_ONLY" : "INSTANT", "CASH_COUNTED", null, null, reason);
        }
        BankCreditVerification credit = input.credit();
        if (actual == null || credit == null || !credit.confirmedReceived()
                || !CREDIT_SOURCES.contains(credit.sourceKind() == null ? "" : credit.sourceKind())) {
            throw new IllegalArgumentException("A bank or UPI collection requires actual received time and explicit credited-bank verification");
        }
        String suppliedReference = bankReference(reference);
        String creditedReference = bankReference(credit.creditedEntryReference());
        if (creditedReference == null) creditedReference = suppliedReference;
        if (creditedReference == null) {
            throw new IllegalArgumentException("Identify the actual credited bank entry; a screenshot alone is not settlement evidence");
        }
        String normalized = normalizeBankReference(creditedReference);
        if (suppliedReference != null && !normalized.equals(normalizeBankReference(suppliedReference))) {
            throw new IllegalArgumentException("Payment and verified bank references must identify the same credited entry");
        }
        return new Prepared(method, suppliedReference == null ? creditedReference : suppliedReference,
                actual, original, posting, "INSTANT", credit.sourceKind(), creditedReference, normalized, reason);
    }

    /** Exact ASCII case/whitespace normalization; meaningful punctuation remains distinct. */
    public static String normalizeBankReference(String reference) {
        if (reference == null) return null;
        StringBuilder value = new StringBuilder(reference.length());
        for (int i = 0; i < reference.length(); i++) {
            char character = reference.charAt(i);
            if (character == ' ' || character >= '\t' && character <= '\r') continue;
            value.append(character >= 'a' && character <= 'z' ? (char) (character - ('a' - 'A')) : character);
        }
        return value.isEmpty() ? null : value.toString();
    }

    /** Stable input only. Never include recording time/close selection in the retry fingerprint. */
    public static String fingerprintMaterial(Prepared prepared) {
        Objects.requireNonNull(prepared);
        StringBuilder result = new StringBuilder("MANUAL_RECEIVED_FUNDS_V1");
        for (Object value : new Object[] { prepared.paymentMethod(), prepared.receiptReference(), prepared.actualReceivedAt(),
                prepared.originalReceivedOn(), prepared.postingOn(), prepared.receivedTimePrecision(), prepared.verificationKind(),
                prepared.bankCreditReference(), prepared.normalizedBankReference(), prepared.reviewReason() }) {
            String text = value == null ? "" : value.toString();
            result.append('|').append(value == null ? -1 : text.length()).append(':').append(text);
        }
        return result.toString();
    }

    /** Call after the caller's exact idempotency checks and before the common writer. */
    public void assertEligible(ValueScope scope, Prepared prepared) {
        eligible(scope, prepared, null);
    }

    /** Call immediately after the common receipt writer in the same transaction. */
    public void record(ValueScope scope, UUID paymentId, Prepared prepared) {
        Objects.requireNonNull(paymentId, "Persisted common receipt required");
        Eligibility eligibility = eligible(scope, prepared, paymentId);
        var parameters = parameters(scope).addValue("paymentId", paymentId)
                .addValue("originalDate", prepared.originalReceivedOn()).addValue("actualAt", prepared.actualReceivedAt())
                .addValue("precision", prepared.receivedTimePrecision()).addValue("postingDate", prepared.postingOn())
                .addValue("kind", prepared.verificationKind()).addValue("bankReference", prepared.bankCreditReference())
                .addValue("normalizedReference", prepared.normalizedBankReference())
                .addValue("confirmed", !"CASH".equals(prepared.paymentMethod()))
                .addValue("verifiedAt", eligibility.now()).addValue("closedDayId", eligibility.closedDayId())
                .addValue("reason", prepared.reviewReason());
        try {
            jdbc.update("""
                    INSERT INTO fee_manual_collection_evidence(fee_payment_id,organisation_id,school_id,
                      original_received_on,actual_received_at,received_time_precision,school_time_zone,posting_on,
                      verification_kind,bank_credit_reference,normalized_bank_reference,bank_credit_confirmed,
                      verified_by_membership_id,verified_at,closed_day_close_id,review_reason)
                    VALUES(:paymentId,:organisationId,:schoolId,:originalDate,:actualAt,:precision,:timeZone,:postingDate,
                      :kind,:bankReference,:normalizedReference,:confirmed,:membershipId,:verifiedAt,:closedDayId,:reason)
                    """, parameters);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("MANUAL_COLLECTION_EVIDENCE_CONFLICT",
                    "The received-funds evidence changed or conflicts with an existing collection. Review the receipt history before retrying.");
        }
    }

    private Eligibility eligible(ValueScope scope, Prepared prepared, UUID currentPaymentId) {
        Objects.requireNonNull(scope); Objects.requireNonNull(prepared);
        // Rebuild canonical input to reject fabricated internal Prepared values before issuing SQL.
        Prepared canonical = prepare(scope, prepared.paymentMethod(), prepared.receiptReference(),
                new EvidenceInput(prepared.actualReceivedAt(), prepared.originalReceivedOn(), prepared.postingOn(),
                        "CASH".equals(prepared.paymentMethod()) ? null
                                : new BankCreditVerification(prepared.verificationKind(), true, prepared.bankCreditReference()),
                        prepared.reviewReason()));
        if (!canonical.equals(prepared)) throw new IllegalArgumentException("Canonical prepared received-funds evidence required");
        UUID userId = requireTransaction(scope);
        var parameters = parameters(scope).addValue("userId", userId).addValue("currentPaymentId", currentPaymentId)
                .addValue("originalDate", prepared.originalReceivedOn()).addValue("postingDate", prepared.postingOn())
                .addValue("reference", prepared.normalizedBankReference());
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                new MapSqlParameterSource("lockKey", scope.schoolId() + ":finance"), (result, row) -> Boolean.TRUE);
        List<UUID> authority = jdbc.query("""
                SELECT membership.id FROM memberships membership
                JOIN user_accounts actor ON actor.id=membership.user_id AND actor.status='ACTIVE'
                JOIN schools school ON school.id=membership.school_id AND school.organisation_id=membership.organisation_id
                  AND school.status='ACTIVE' AND school.time_zone=:timeZone
                JOIN organisations organisation ON organisation.id=membership.organisation_id AND organisation.status='ACTIVE'
                JOIN membership_roles assignment ON assignment.membership_id=membership.id
                JOIN roles role ON role.id=assignment.role_id
                  AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                JOIN role_capabilities grant_record ON grant_record.role_id=role.id AND grant_record.capability_code='fees.collect'
                WHERE membership.id=:membershipId AND membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.school_id=:schoolId AND membership.status='ACTIVE'
                  AND membership.valid_from<=clock_timestamp()
                  AND (membership.valid_until IS NULL OR membership.valid_until>clock_timestamp())
                FOR SHARE OF membership,actor,school,assignment
                """, parameters, (result, row) -> result.getObject("id", UUID.class));
        if (authority.isEmpty()) throw denied();
        Instant now = Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", parameters,
                (result, row) -> result.getTimestamp(1).toInstant()));
        LocalDate schoolToday = now.atZone(ZoneId.of(scope.schoolTimeZone())).toLocalDate();
        if (prepared.originalReceivedOn().isAfter(schoolToday) || prepared.postingOn().isAfter(schoolToday)
                || prepared.actualReceivedAt() != null && prepared.actualReceivedAt().isAfter(now)) {
            throw conflict("MANUAL_COLLECTION_FUTURE_DATE", "Received funds and their posting date cannot be in the future.");
        }
        UUID closedDayId = jdbc.queryForObject("""
                SELECT (SELECT id FROM fee_day_closes WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='APPROVED' AND business_date>=:originalDate ORDER BY business_date DESC,id LIMIT 1)
                """, parameters, UUID.class);
        Boolean postingClosed = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_day_closes WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='APPROVED' AND business_date>=:postingDate)
                """, parameters, Boolean.class);
        if (Boolean.TRUE.equals(postingClosed)) throw conflict("MANUAL_COLLECTION_POSTING_DATE_CLOSED",
                "Choose an eligible open posting date; the original received day and closed history must remain unchanged.");
        if (closedDayId != null && prepared.reviewReason() == null) throw conflict("MANUAL_COLLECTION_REVIEW_REQUIRED",
                "Received funds from a closed financial day require an authorized review reason and an open posting date.");
        if (prepared.normalizedBankReference() != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_payments payment WHERE payment.organisation_id=:organisationId
                  AND payment.school_id=:schoolId AND payment.gateway_capture_id IS NULL
                  AND payment.payment_method IN ('BANK_TRANSFER','SCHOOL_UPI')
                  AND (CAST(:currentPaymentId AS uuid) IS NULL OR payment.id<>CAST(:currentPaymentId AS uuid))
                  AND normalize_manual_bank_reference(payment.external_reference)=:reference)
                """, parameters, Boolean.class))) {
            throw conflict("MANUAL_BANK_CREDIT_ALREADY_RECORDED",
                    "This bank credit already appears in the school's receipt history, including reversed receipts. Request a controlled reconciliation instead of recording it again.");
        }
        if (prepared.normalizedBankReference() != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                /* manual-bank-settlement-reference */
                SELECT EXISTS(SELECT 1 FROM fee_gateway_settlement_bank_evidence evidence
                  WHERE evidence.organisation_id=:organisationId AND evidence.school_id=:schoolId
                    AND evidence.normalized_bank_reference=:reference
                    AND NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_bank_evidence_rejections rejected
                      WHERE rejected.bank_evidence_id=evidence.id AND rejected.organisation_id=evidence.organisation_id
                        AND rejected.school_id=evidence.school_id))
                """, parameters, Boolean.class))) {
            throw conflict("MANUAL_BANK_CREDIT_RESERVED_FOR_SETTLEMENT",
                    "This bank credit is reserved for gateway settlement review. Review its retained evidence; do not issue another fee receipt for the same credit.");
        }
        return new Eligibility(now, closedDayId);
    }

    private UUID requireTransaction(ValueScope scope) {
        var tenant = TenantRequestContext.current().orElseThrow(ManualCollectionEvidence::denied);
        if (tenant.userId() == null || !scope.organisationId().equals(tenant.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw denied();
        Boolean bound = jdbc.queryForObject("""
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id()=:userId
                """, parameters(scope).addValue("userId", tenant.userId()), Boolean.class);
        if (!Boolean.TRUE.equals(bound)) throw denied();
        return tenant.userId();
    }

    private static MapSqlParameterSource parameters(ValueScope scope) {
        return new MapSqlParameterSource("organisationId", scope.organisationId()).addValue("schoolId", scope.schoolId())
                .addValue("membershipId", scope.membershipId()).addValue("timeZone", scope.schoolTimeZone());
    }

    private static String bankReference(String reference) {
        if (reference == null) return null;
        for (int i = 0; i < reference.length(); i++) if (reference.charAt(i) > 127 || reference.charAt(i) == 0) {
            throw new IllegalArgumentException("Use the actual bounded ASCII bank-credit identifier");
        }
        String cleaned = clean(reference);
        if (cleaned == null) return null;
        if (cleaned.length() > 120 || cleaned.matches("[A-Za-z][A-Za-z0-9+.-]*://.*")
                || !cleaned.matches("[A-Za-z0-9][A-Za-z0-9/_.: \\t-]{0,119}")) {
            throw new IllegalArgumentException("Use the actual bounded bank-credit identifier, not screenshot text or credentials");
        }
        String normalized = normalizeBankReference(cleaned);
        if (normalized == null || normalized.matches("[A-Z][A-Z0-9+.-]*://.*")
                || !normalized.matches("[A-Z0-9][A-Z0-9/_.:-]{0,119}")) {
            throw new IllegalArgumentException("An identifiable bank credit is required");
        }
        return cleaned;
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static boolean unsafeControl(String value) { return value.chars().anyMatch(c -> c < 32 || c == 127); }
    private static ApiException denied() {
        return new ApiException(HttpStatus.FORBIDDEN, "MANUAL_COLLECTION_SCOPE_DENIED",
                "Received-funds verification requires the live authorized financial actor and school context.");
    }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private record Eligibility(Instant now, UUID closedDayId) {}
}

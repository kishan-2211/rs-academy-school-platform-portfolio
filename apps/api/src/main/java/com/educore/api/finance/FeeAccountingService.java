package com.educore.api.finance;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static com.educore.api.finance.FeeAccountingController.*;
import static com.educore.api.finance.FeeAccountingSnapshot.*;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.accounting.AccountingMutationResponse;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;

@Service
public class FeeAccountingService {
    private static final Set<String> EVENTS =
            Set.of("CHARGE", "CONCESSION", "PAYMENT", "PAYMENT_REVERSAL");
    private static final List<String> TENDERS = List.of(
            "CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI", "ONLINE_PROVIDER");

    private final NamedParameterJdbcTemplate jdbc;
    private final AccountingModuleBridge accounting;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final ObjectMapper proofObjectMapper;
    private final Clock clock;

    public FeeAccountingService(NamedParameterJdbcTemplate jdbc,
            AccountingModuleBridge accounting, SecurityAuditService audit,
            ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.accounting = accounting;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.proofObjectMapper = objectMapper.rebuild().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
        this.clock = clock;
    }

    /**
     * Human, read-only proof of the complete original PAYMENT posting. This is
     * historical source evidence, not refund admission or posting readiness:
     * it acquires no financial locks, does not rule out a pending reversal
     * request, and does not authorize any provider or accounting mutation.
     */
    @Transactional(readOnly = true, noRollbackFor = HistoricalPostingProofUnavailable.class)
    OriginalPaymentPostingProof verifyOriginalPaymentPosting(AuthenticatedUser user,
            UUID schoolId, UUID paymentId) {
        requireOriginalPaymentContext(user, schoolId);
        Scope scope;
        try {
            scope = requireScope(user, schoolId, "fees.read");
        } catch (ApiException denied) {
            throw originalPaymentAuthorityFailure(denied);
        }
        return originalPaymentPostingProof(new OriginalSourceScope(scope.organisationId(), scope.schoolId()), paymentId, target -> {
            Map<UUID, FeeJournalEvidence> journals;
            try {
                journals = accounting.feeJournalEvidence(user, scope.schoolId(), List.of(target.journalId()));
            } catch (ApiException denied) {
                throw originalPaymentAuthorityFailure(denied);
            }
            return journals == null || journals.size() != 1 ? null : journals.get(target.journalId());
        });
    }

    /** Invocation-local historical proof: every target is gateway-qualified; complete batches are verified once. */
    @Transactional(readOnly = true, noRollbackFor = HistoricalPostingProofUnavailable.class)
    OriginalPaymentProofBatch verifyOriginalPaymentPostings(AuthenticatedUser user, UUID schoolId, List<UUID> paymentIds) {
        requireOriginalPaymentContext(user, schoolId);
        Scope scope;
        try { scope = requireScope(user, schoolId, "fees.read"); }
        catch (ApiException denied) { throw originalPaymentAuthorityFailure(denied); }
        if (paymentIds == null || paymentIds.size() > 1000 || paymentIds.stream().anyMatch(Objects::isNull))
            throw originalPaymentProofInvalid();
        Set<UUID> requested = new LinkedHashSet<>(paymentIds);
        if (requested.isEmpty()) return new OriginalPaymentProofBatch(Map.of(), Set.of());
        var sourceScope = new OriginalSourceScope(scope.organisationId(), scope.schoolId());
        List<OriginalPaymentTarget> targets = originalPaymentTargets(sourceScope, requested);
        Map<UUID,List<OriginalPaymentTarget>> byPayment = new LinkedHashMap<>();
        for (var target : targets) {
            if (target.paymentId() == null || !requested.contains(target.paymentId())) throw originalPaymentProofInvalid();
            byPayment.computeIfAbsent(target.paymentId(), ignored -> new ArrayList<>()).add(target);
        }
        Set<UUID> invalid = new LinkedHashSet<>();
        Map<UUID,List<OriginalPaymentTarget>> byBatch = new LinkedHashMap<>();
        for (UUID paymentId : requested) {
            var found = byPayment.get(paymentId);
            if (found == null || found.size() != 1) { invalid.add(paymentId); continue; }
            var target = found.getFirst();
            try { validateOriginalPaymentTarget(target); }
            catch (HistoricalPostingProofUnavailable unavailable) {
                if (!"FEE_ORIGINAL_PAYMENT_PROOF_INVALID".equals(unavailable.code())) throw unavailable;
                invalid.add(paymentId); continue;
            }
            byBatch.computeIfAbsent(target.batchId(), ignored -> new ArrayList<>()).add(target);
        }
        Map<UUID,OriginalPaymentPostingProof> proofs = new LinkedHashMap<>();
        for (var batchTargets : byBatch.values()) {
            VerifiedOriginalPaymentBatch batch;
            try {
                batch = verifyOriginalPaymentBatch(sourceScope, batchTargets.getFirst(), null, target -> {
                    Map<UUID,FeeJournalEvidence> journals;
                    try { journals = accounting.feeJournalEvidence(user, scope.schoolId(), List.of(target.journalId())); }
                    catch (ApiException denied) { throw originalPaymentAuthorityFailure(denied); }
                    return journals == null || journals.size() != 1 ? null : journals.get(target.journalId());
                });
            } catch (HistoricalPostingProofUnavailable unavailable) {
                if (!"FEE_ORIGINAL_PAYMENT_PROOF_INVALID".equals(unavailable.code())) throw unavailable;
                batchTargets.forEach(target -> invalid.add(target.paymentId())); continue;
            }
            for (var target : batchTargets) {
                try { proofs.put(target.paymentId(), originalProofFromTarget(target.paymentId(), target, batch)); }
                catch (HistoricalPostingProofUnavailable unavailable) {
                    if (!"FEE_ORIGINAL_PAYMENT_PROOF_INVALID".equals(unavailable.code())) throw unavailable;
                    invalid.add(target.paymentId());
                }
            }
        }
        return new OriginalPaymentProofBatch(proofs, invalid);
    }

    /**
     * Historical proof of the already completed REFUND debit and its entire,
     * independently posted common journal. This is not a provider event/refund
     * association, absence of a pending reversal, settlement reconciliation or
     * admission to post money. It deliberately takes no locks and performs no
     * writes; a monetary consumer must reprove live evidence under its fences.
     */
    @Transactional(readOnly = true, noRollbackFor = HistoricalPostingProofUnavailable.class)
    CompletedRefundPostingProof verifyCompletedRefundPosting(AuthenticatedUser user,
            UUID schoolId, UUID refundPostingId) {
        try {
            requireOriginalPaymentContext(user, schoolId);
            Scope scope = requireScope(user, schoolId, "fees.read");
            requireCompletedRefundReadActor(user, scope);
            if (refundPostingId == null) throw completedRefundProofInvalid();
            RefundPostingSource refund = refundPostingSource(scope, refundPostingId);
            SourceEntry source = refund.source();
            if (!refundPostingId.equals(refund.id()) || refund.refundId() == null || refund.paymentId() == null
                    || refund.originalItemId() == null || refund.accountId() == null || refund.postedOn() == null
                    || source.id() == null || !refund.accountId().equals(source.feeAccountId())
                    || !refundPostingId.equals(source.sourceId()) || !refund.postedOn().equals(source.effectiveOn())
                    || !"REFUND".equals(source.event()) || !"DEBIT".equals(source.direction())
                    || !"GATEWAY_REFUND_POSTING".equals(source.sourceType()) || source.chargeId() != null
                    || source.reversesEntryId() != null || source.createdAt() == null || source.feeHeadId() != null
                    || source.feeHeadCode() != null || source.feeHeadName() != null
                    || !"INR".equals(source.currency()) || !"ONLINE_PROVIDER".equals(source.paymentMethod())
                    || source.receiptNumber() == null || source.receiptNumber().isBlank()
                    || proofMoney(source.amount(), false).compareTo(new BigDecimal("100000000.00")) > 0)
                throw completedRefundProofInvalid();
            requireCompletedRefundWitness(scope, refund);
            OriginalPaymentPostingProof original = verifyOriginalPaymentPosting(user, schoolId, refund.paymentId());
            if (!refund.originalItemId().equals(original.postingItemId()) || !source.currency().equals(original.currencyCode()))
                throw completedRefundProofInvalid();
            CompletedRefundBatch batch = completedRefundBatch(scope, refund);
            if (batch.id() == null || batch.itemId() == null || batch.journalId() == null || batch.yearId() == null
                    || batch.periodId() == null || batch.postingOn() == null || batch.postingOn().isBefore(refund.postedOn())
                    || !original.configurationId().equals(batch.configurationId()) || !refund.id().equals(batch.refundPostingId())
                    || !refund.originalItemId().equals(batch.originalItemId()) || !"REFUND".equals(batch.event())
                    || !"JOURNAL".equals(batch.disposition()) || batch.sourceCount() != 1
                    || proofMoney(batch.sourceAmount(), false).compareTo(source.amount()) != 0
                    || !proofDigest(batch.evidenceSha256()) || !proofDigest(batch.sourceSha256())
                    || (batch.previousBatchId() == null) != (batch.previousJournalId() == null)
                    || batch.id().equals(batch.previousBatchId())
                    || batch.previousBatchId() == null && !batch.postingOn().equals(refund.postedOn()))
                throw completedRefundProofInvalid();
            List<CompletedRefundItem> items = jdbc.query("""
                    /* completed-refund-proof-items */
                    SELECT id,fee_ledger_entry_id,sequence_number,status,source_snapshot::text,source_sha256
                    FROM fee_accounting_posting_items WHERE posting_batch_id=:batchId
                      AND organisation_id=:organisationId AND school_id=:schoolId
                    ORDER BY sequence_number,id LIMIT 2
                    """, scope.params().addValue("batchId", batch.id()), (row, index) -> new CompletedRefundItem(
                    row.getObject("id", UUID.class), row.getObject("fee_ledger_entry_id", UUID.class), row.getInt("sequence_number"),
                    row.getString("status"), row.getString("source_snapshot"), row.getString("source_sha256")));
            if (items.size() != 1) throw completedRefundProofInvalid();
            CompletedRefundItem item = items.getFirst();
            if (!batch.itemId().equals(item.id()) || !source.id().equals(item.ledgerId()) || item.sequence() != 1
                    || !"ACTIVE".equals(item.status()) || !batch.sourceSha256().equals(item.sourceSha256()))
                throw completedRefundProofInvalid();
            List<AllocationEvidence> allocations = completedRefundAllocations(scope, refund, original, proofJson(item.snapshot()));
            if (!proofSourceJsonEqual(proofJson(item.snapshot()), proofJson(json(sourceSnapshot(source, allocations)))))
                throw completedRefundProofInvalid();
            EffectiveConfiguration configuration = new EffectiveConfiguration(original.configurationId(), original.originalFinancialYearId(),
                    original.originalPostingDate(), original.accountingBasis(), original.currencyCode(), original.receivableAccountId(), null,
                    original.incomeAccounts(), Map.of("ONLINE_PROVIDER", original.depositAccountId()));
            PostingEvidence expected = postingEvidence(configuration, batch.yearId(), batch.periodId(), List.of(source), allocations);
            Map<String, Object> snapshot = new LinkedHashMap<>(expected.snapshot());
            snapshot.put("refundPostingId", refund.id());
            snapshot.put("originalPaymentPostingItemId", original.postingItemId());
            snapshot.put("originalPaymentPostingBatchId", original.postingBatchId());
            snapshot.put("originalPaymentJournalId", original.accountingJournalId());
            snapshot.put("originalSourceSha256", original.sourceSha256());
            snapshot.put("originalBatchEvidenceSha256", original.batchEvidenceSha256());
            if (batch.previousBatchId() != null) {
                snapshot.put("supersedesRefundBatchId", batch.previousBatchId());
                snapshot.put("supersedesRefundJournalId", batch.previousJournalId());
                snapshot.put("originalRefundPostingOn", refund.postedOn());
            }
            if (!proofBatchJsonEqual(proofJson(batch.snapshot()), proofJson(json(snapshot)))) throw completedRefundProofInvalid();
            Map<UUID, FeeJournalEvidence> journals = accounting.feeJournalEvidence(user, schoolId, List.of(batch.journalId()));
            FeeJournalEvidence journal = journals == null || journals.size() != 1 ? null : journals.get(batch.journalId());
            if (journal == null || !batch.journalId().equals(journal.id()) || !batch.id().equals(journal.sourceId())
                    || !batch.yearId().equals(journal.financialYearId()) || !batch.periodId().equals(journal.accountingPeriodId())
                    || !batch.postingOn().equals(journal.postingDate()) || !original.accountingBasis().equals(journal.accountingBasis())
                    || !original.currencyCode().equals(journal.currencyCode()) || !"POSTED".equals(journal.status())
                    || journal.reversalId() != null || journal.reversalStatus() != null || !journal.reversalLines().isEmpty()
                    || journal.lines().size() < 2 || journal.lines().size() > 500) throw completedRefundProofInvalid();
            Map<OriginalJournalDimension, OriginalJournalAmounts> expectedLines = new LinkedHashMap<>();
            for (FeeJournalLine line : journalLines(configuration, "REFUND", List.of(source), allocations))
                addOriginalJournalLine(expectedLines, new OriginalJournalDimension(line.accountId(), null, line.learnerFeeAccountId()),
                        line.debitAmount(), line.creditAmount());
            Map<OriginalJournalDimension, OriginalJournalAmounts> actualLines = new LinkedHashMap<>();
            for (FeeJournalLineEvidence line : journal.lines()) {
                if (line == null || line.accountId() == null) throw completedRefundProofInvalid();
                addOriginalJournalLine(actualLines, new OriginalJournalDimension(line.accountId(), line.vendorId(), line.learnerFeeAccountId()),
                        line.debitAmount(), line.creditAmount());
            }
            if (!expectedLines.equals(actualLines)) throw completedRefundProofInvalid();
            return new CompletedRefundPostingProof(refund.id(), refund.refundId(), refund.paymentId(), source.id(), item.id(), batch.id(),
                    batch.configurationId(), batch.journalId(), original.depositAccountId(), batch.yearId(), batch.periodId(), batch.postingOn(),
                    original.accountingBasis(), original.currencyCode(), proofMoney(source.amount(), false), original,
                    item.sourceSha256(), batch.evidenceSha256());
        } catch (ApiException failure) {
            if (failure.status() == HttpStatus.FORBIDDEN && Set.of("FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED",
                    "FEE_ACCOUNTING_SCOPE_DENIED", "ACCOUNTING_SCOPE_DENIED").contains(failure.code())) throw completedRefundProofDenied();
            if (failure.status() == HttpStatus.CONFLICT && Set.of("FEE_ORIGINAL_PAYMENT_PROOF_INVALID",
                    "FEE_REFUND_ACCOUNTING_SOURCE_INVALID", "FEE_ACCOUNTING_CURRENCY_MISMATCH", "FEE_PAYMENT_ALLOCATION_MISMATCH",
                    "FEE_ACCOUNT_MAPPING_MISSING", "FEE_POSTING_LINE_LIMIT", "FEE_POSTING_RULE_UNAVAILABLE").contains(failure.code()))
                throw completedRefundProofInvalid();
            throw failure;
        }
    }

    private void requireCompletedRefundReadActor(AuthenticatedUser user, Scope scope) {
        Boolean allowed = jdbc.queryForObject("""
                /* completed-refund-proof-live-actor */
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id()=:userId
                  AND (SELECT count(DISTINCT capability.capability_code) FROM memberships member
                    JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
                    JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
                    JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
                    JOIN membership_roles assignment ON assignment.membership_id=member.id
                    JOIN roles role ON role.id=assignment.role_id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                    JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code IN('fees.read','accounting.read')
                    WHERE member.user_id=:userId AND member.id IN(:membershipIds) AND member.organisation_id=:organisationId
                      AND member.school_id=:schoolId AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
                      AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp()))=2
                """, scope.params().addValue("userId", user.userId()).addValue("membershipIds", user.membershipIds()), Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) throw completedRefundProofDenied();
    }

    private void requireCompletedRefundWitness(Scope scope, RefundPostingSource refund) {
        Boolean valid = jdbc.queryForObject("""
                /* completed-refund-proof-witness */
                SELECT EXISTS(SELECT 1 FROM fee_gateway_refund_postings completion
                  JOIN fee_gateway_refund_successes success ON success.refund_request_id=completion.refund_request_id
                    AND success.organisation_id=completion.organisation_id AND success.school_id=completion.school_id
                    AND success.fee_payment_id=completion.fee_payment_id AND success.learner_fee_account_id=completion.learner_fee_account_id
                    AND success.verified_read_id=completion.verified_read_id AND success.evidence_sha256=completion.success_evidence_sha256
                    AND success.source_version=completion.source_version AND success.amount=completion.amount AND success.currency_code=completion.currency_code
                  JOIN fee_gateway_refund_requests request ON request.id=success.refund_request_id
                    AND request.organisation_id=success.organisation_id AND request.school_id=success.school_id
                    AND request.fee_payment_id=success.fee_payment_id AND request.capture_id=success.capture_id
                    AND request.learner_fee_account_id=success.learner_fee_account_id AND request.amount=success.amount AND request.currency_code=success.currency_code
                    AND request.order_reference=success.order_reference AND request.payment_reference=success.payment_reference
                    AND request.payment_provider_config_id=success.payment_provider_config_id AND request.provider_code=success.provider_code
                    AND request.provider_environment=success.provider_environment AND request.merchant_account_reference=success.merchant_account_reference
                    AND request.credentials_secret_reference=success.credentials_secret_reference
                  JOIN fee_gateway_refund_decisions approval ON approval.id=success.approval_decision_id
                    AND approval.refund_request_id=request.id AND approval.organisation_id=request.organisation_id
                    AND approval.school_id=request.school_id AND approval.decision='APPROVE'
                  JOIN fee_gateway_refund_reads witness ON witness.id=success.verified_read_id AND witness.refund_request_id=request.id
                    AND witness.organisation_id=request.organisation_id AND witness.school_id=request.school_id
                    AND witness.approval_decision_id=approval.id AND witness.outcome='VERIFIED_SUCCESS' AND witness.refund_status='SUCCESS'
                    AND witness.evidence_sha256=success.evidence_sha256 AND witness.amount=success.amount AND witness.currency_code=success.currency_code
                    AND witness.refund_reference=success.refund_reference AND witness.order_reference=success.order_reference
                    AND witness.payment_reference=success.payment_reference AND witness.processed_at_exact=success.processed_at_exact
                    AND witness.processed_at=success.processed_at AND witness.processed_on=success.processed_on
                    AND witness.payment_provider_config_id=success.payment_provider_config_id AND witness.provider_code=success.provider_code
                    AND witness.provider_environment=success.provider_environment AND witness.merchant_account_reference=success.merchant_account_reference
                    AND witness.credentials_secret_reference=success.credentials_secret_reference
                  WHERE completion.id=:refundPostingId AND completion.organisation_id=:organisationId AND completion.school_id=:schoolId
                    AND success.status='HOLD' AND success.source_version=1 AND success.currency_code='INR'
                    AND success.refund_reference=request.id::text
                    AND NOT EXISTS(SELECT 1 FROM fee_gateway_refund_reads contradictory WHERE contradictory.refund_request_id=request.id
                      AND contradictory.organisation_id=request.organisation_id AND contradictory.school_id=request.school_id
                      AND ((contradictory.outcome='VERIFIED_SUCCESS' AND contradictory.evidence_sha256<>success.evidence_sha256)
                        OR (contradictory.outcome='REVIEW_REQUIRED' AND contradictory.refund_status='SUCCESS'))))
                """, scope.params().addValue("refundPostingId", refund.id()), Boolean.class);
        if (!Boolean.TRUE.equals(valid)) throw completedRefundProofInvalid();
    }

    private CompletedRefundBatch completedRefundBatch(Scope scope, RefundPostingSource refund) {
        List<CompletedRefundBatch> batches = jdbc.query("""
                /* completed-refund-proof-batch */
                SELECT batch.id,batch.configuration_id,batch.financial_year_id,batch.accounting_period_id,batch.business_date,
                  batch.event_type,batch.disposition,batch.source_count,batch.source_amount,batch.accounting_journal_id,
                  batch.evidence_snapshot::text,batch.evidence_sha256,batch.refund_posting_id,batch.original_payment_posting_item_id,
                  batch.supersedes_refund_batch_id,batch.supersedes_refund_journal_id,item.id AS item_id,item.source_sha256
                FROM fee_accounting_posting_batches batch JOIN fee_accounting_posting_items item ON item.posting_batch_id=batch.id
                  AND item.organisation_id=batch.organisation_id AND item.school_id=batch.school_id
                WHERE batch.refund_posting_id=:refundPostingId AND batch.organisation_id=:organisationId AND batch.school_id=:schoolId
                  AND item.fee_ledger_entry_id=:ledgerId AND item.status='ACTIVE'
                ORDER BY batch.id,item.id LIMIT 2
                """, scope.params().addValue("refundPostingId", refund.id()).addValue("ledgerId", refund.source().id()),
                (row, index) -> new CompletedRefundBatch(row.getObject("id", UUID.class), row.getObject("item_id", UUID.class),
                row.getObject("configuration_id", UUID.class), row.getObject("financial_year_id", UUID.class), row.getObject("accounting_period_id", UUID.class),
                row.getObject("business_date", LocalDate.class), row.getString("event_type"), row.getString("disposition"), row.getInt("source_count"),
                row.getBigDecimal("source_amount"), row.getObject("accounting_journal_id", UUID.class), row.getString("evidence_snapshot"), row.getString("evidence_sha256"),
                row.getObject("refund_posting_id", UUID.class), row.getObject("original_payment_posting_item_id", UUID.class),
                row.getObject("supersedes_refund_batch_id", UUID.class), row.getObject("supersedes_refund_journal_id", UUID.class), row.getString("source_sha256")));
        if (batches.size() != 1) throw completedRefundProofInvalid();
        return batches.getFirst();
    }

    private List<AllocationEvidence> completedRefundAllocations(Scope scope, RefundPostingSource refund,
            OriginalPaymentPostingProof original, JsonNode frozen) {
        List<CompletedRefundAllocation> rows = jdbc.query("""
                /* completed-refund-proof-allocations: labels come only from the immutable item */
                SELECT original_payment_allocation_id,learner_fee_charge_id,fee_head_id,amount
                FROM fee_gateway_refund_allocations WHERE refund_request_id=:refundId AND fee_payment_id=:paymentId
                  AND learner_fee_account_id=:accountId AND organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY learner_fee_charge_id LIMIT 101
                """, scope.params().addValue("refundId", refund.refundId()).addValue("paymentId", refund.paymentId()).addValue("accountId", refund.accountId()),
                (row, index) -> new CompletedRefundAllocation(row.getObject("original_payment_allocation_id", UUID.class),
                row.getObject("learner_fee_charge_id", UUID.class), row.getObject("fee_head_id", UUID.class), proofMoney(row.getBigDecimal("amount"), false)));
        JsonNode frozenRows = frozen.get("allocations");
        if (rows.isEmpty() || rows.size() > 100 || frozenRows == null || !frozenRows.isArray() || frozenRows.size() != rows.size())
            throw completedRefundProofInvalid();
        Map<UUID, CompletedRefundAllocation> byCharge = new LinkedHashMap<>();
        for (CompletedRefundAllocation row : rows) {
            if (row.originalAllocationId() == null || row.chargeId() == null || row.headId() == null
                    || byCharge.putIfAbsent(row.chargeId(), row) != null) throw completedRefundProofInvalid();
        }
        List<RefundAllocation> allocations = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        for (JsonNode retained : frozenRows) {
            UUID charge = proofUuid(retained, "learnerFeeChargeId");
            CompletedRefundAllocation row = byCharge.get(charge);
            if (row == null || !seen.add(charge) || !row.headId().equals(proofUuid(retained, "feeHeadId"))) throw completedRefundProofInvalid();
            allocations.add(new RefundAllocation(row.originalAllocationId(), new AllocationEvidence(refund.id(), charge, row.headId(),
                    proofText(retained, "feeHeadCode"), proofText(retained, "feeHeadName"), row.amount())));
        }
        return validateRefundAllocations(refund.source().amount(), allocations, original);
    }

    private static ApiException completedRefundProofInvalid() {
        return new HistoricalPostingProofUnavailable("FEE_COMPLETED_REFUND_PROOF_INVALID", "The complete retained refund posting evidence is unavailable or requires reconciliation.");
    }
    private static ApiException completedRefundProofDenied() {
        return forbidden("FEE_COMPLETED_REFUND_PROOF_SCOPE_DENIED", "Completed refund evidence is unavailable for this school and session.");
    }

    /**
     * Complete historical source proof for a scoped background caller, not a
     * human impersonation or durable permission to send a provider refund. The
     * future dispatch admission must separately bind the approved refund and
     * persist its one-use guard before leaving the transaction for provider I/O.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    OriginalPaymentPostingProof verifyGatewayOriginalPaymentPosting(UUID organisationId, UUID schoolId, UUID paymentId) {
        OriginalSourceScope scope = new OriginalSourceScope(organisationId, schoolId);
        requireGatewayOriginalPaymentContext(scope);
        if (paymentId == null) throw originalPaymentProofInvalid();
        // Match reviewed-refund ordering: gateway finance, fee configuration,
        // then the accounting bridge's school fence. No network I/O occurs here.
        for (String lock : List.of(schoolId + ":finance", "finance:school:" + schoolId)) {
            jdbc.queryForObject("/* gateway-original-source-fence */ SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked",
                    scope.params().addValue("lockKey", lock), Integer.class);
        }
        requireGatewayOriginalPaymentContext(scope);
        return originalPaymentPostingProof(scope, paymentId, target -> accounting.gatewayOriginalPaymentJournalEvidence(
                organisationId, schoolId, paymentId, target.batchId(), target.journalId()));
    }

    private void requireGatewayOriginalPaymentContext(OriginalSourceScope scope) {
        var context = TenantRequestContext.current().orElse(null);
        if (scope.organisationId() == null || scope.schoolId() == null || context == null || context.userId() != null
                || !scope.organisationId().equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw originalPaymentProofDenied();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                /* gateway-original-source-context */
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL
                  AND current_setting('transaction_isolation')='read committed'
                  AND current_setting('transaction_read_only')='off'
                  AND EXISTS(SELECT 1 FROM schools school JOIN organisations organisation
                    ON organisation.id=school.organisation_id AND organisation.status='ACTIVE'
                    WHERE school.id=:schoolId AND school.organisation_id=:organisationId AND school.status='ACTIVE')
                """, scope.params(), Boolean.class))) throw originalPaymentProofDenied();
    }

    private OriginalPaymentPostingProof originalPaymentPostingProof(OriginalSourceScope scope, UUID paymentId,
            Function<OriginalPaymentTarget, FeeJournalEvidence> journalReader) {
        if (paymentId == null) throw originalPaymentProofInvalid();
        OriginalPaymentTarget target = originalPaymentTarget(scope, paymentId);
        validateOriginalPaymentTarget(target);
        return originalProofFromTarget(paymentId, target, verifyOriginalPaymentBatch(scope, target, paymentId, journalReader));
    }

    private void validateOriginalPaymentTarget(OriginalPaymentTarget target) {
        if (!"PAYMENT".equals(target.event()) || !"JOURNAL".equals(target.disposition())
                || target.sourceCount() < 1 || target.sourceCount() > 200
                || target.journalId() == null || target.configurationId() == null
                || target.batchId() == null || target.itemId() == null || target.ledgerId() == null
                || target.yearId() == null || target.periodId() == null || target.businessDate() == null
                || !"POSTED".equals(target.receiptStatus()) || !"ONLINE_PROVIDER".equals(target.paymentMethod())
                || !"INR".equals(target.currency()) || target.accountId() == null
                || !proofDigest(target.sourceSha256()) || !proofDigest(target.evidenceSha256())
                || proofMoney(target.receiptAmount(), false).compareTo(new BigDecimal("100000000.00")) > 0) {
            throw originalPaymentProofInvalid();
        }
    }

    private VerifiedOriginalPaymentBatch verifyOriginalPaymentBatch(OriginalSourceScope scope, OriginalPaymentTarget target,
            UUID paymentId, Function<OriginalPaymentTarget, FeeJournalEvidence> journalReader) {
        List<OriginalPostingItem> items = originalPaymentItems(scope, target.batchId());
        if (items.size() != target.sourceCount()) throw originalPaymentProofInvalid();
        Set<UUID> ledgerIds = new LinkedHashSet<>();
        Set<UUID> paymentIds = new LinkedHashSet<>();
        Set<UUID> itemIds = new LinkedHashSet<>();
        OriginalPostingItem selected = null;
        for (int index = 0; index < items.size(); index++) {
            OriginalPostingItem item = items.get(index);
            SourceEntry source = item.source();
            if (item.id() == null || !itemIds.add(item.id()) || item.sequence() != index + 1
                    || !"ACTIVE".equals(item.status()) || !proofDigest(item.sourceSha256())
                    || source.id() == null || !ledgerIds.add(source.id())
                    || source.sourceId() == null || !paymentIds.add(source.sourceId())
                    || source.feeAccountId() == null || source.chargeId() != null
                    || !"PAYMENT".equals(source.event()) || !"CREDIT".equals(source.direction())
                    || !"FEE_PAYMENT".equals(source.sourceType()) || source.reversesEntryId() != null
                    || !target.businessDate().equals(source.effectiveOn()) || source.createdAt() == null
                    || !target.currency().equals(source.currency()) || source.feeHeadId() != null
                    || source.feeHeadCode() != null || source.feeHeadName() != null
                    || source.paymentMethod() == null || !TENDERS.contains(source.paymentMethod()) || source.receiptNumber() == null
                    || source.receiptNumber().isBlank()) throw originalPaymentProofInvalid();
            proofMoney(source.amount(), false);
            if (item.id().equals(target.itemId())) selected = item;
        }
        if (paymentId != null) validateOriginalSelectedTarget(paymentId, target, selected);
        List<OriginalAllocationRow> allocationRows = originalPaymentAllocations(scope, paymentIds);
        // Existing manual receipts support 1,000 allocations each; do not make
        // their legitimate 200-source historical batches unprovable.
        if (allocationRows.isEmpty() || allocationRows.size() > 200000) throw originalPaymentProofInvalid();
        Map<UUID, Map<UUID, OriginalAllocationRow>> allocationsByPayment = new LinkedHashMap<>();
        Set<UUID> allocationIds = new LinkedHashSet<>();
        Map<UUID, SourceEntry> sourcesByPayment = items.stream().map(OriginalPostingItem::source)
                .collect(Collectors.toMap(SourceEntry::sourceId, source -> source));
        for (OriginalAllocationRow allocation : allocationRows) {
            SourceEntry source = sourcesByPayment.get(allocation.paymentId());
            if (allocation.id() == null || !allocationIds.add(allocation.id()) || source == null
                    || allocation.chargeId() == null || allocation.feeHeadId() == null
                    || !source.feeAccountId().equals(allocation.accountId())) throw originalPaymentProofInvalid();
            proofMoney(allocation.amount(), false);
            if (allocationsByPayment.computeIfAbsent(allocation.paymentId(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(allocation.chargeId(), allocation) != null) throw originalPaymentProofInvalid();
        }
        List<AllocationEvidence> allocations = new ArrayList<>();
        Map<UUID,List<OriginalPaymentAllocation>> originalAllocations = new LinkedHashMap<>();
        for (OriginalPostingItem item : items) {
            SourceEntry source = item.source();
            Map<UUID, OriginalAllocationRow> rows = allocationsByPayment.get(source.sourceId());
            JsonNode frozen = proofJson(item.sourceSnapshot());
            JsonNode frozenAllocations = frozen.get("allocations");
            if (rows == null || frozenAllocations == null || !frozenAllocations.isArray()
                    || frozenAllocations.size() != rows.size()
                    || (paymentId != null && paymentId.equals(source.sourceId()) && rows.size() > 100)) throw originalPaymentProofInvalid();
            List<AllocationEvidence> sourceAllocations = new ArrayList<>();
            List<OriginalPaymentAllocation> preservedAllocations = new ArrayList<>();
            Set<UUID> seenCharges = new LinkedHashSet<>();
            for (JsonNode frozenAllocation : frozenAllocations) {
                UUID chargeId = proofUuid(frozenAllocation, "learnerFeeChargeId");
                OriginalAllocationRow row = rows.get(chargeId);
                if (row == null || !seenCharges.add(chargeId)
                        || !row.feeHeadId().equals(proofUuid(frozenAllocation, "feeHeadId"))) throw originalPaymentProofInvalid();
                // Display labels belong to the frozen source, not today's renamed fee head.
                AllocationEvidence allocation = new AllocationEvidence(source.sourceId(), chargeId, row.feeHeadId(),
                        proofText(frozenAllocation, "feeHeadCode"), proofText(frozenAllocation, "feeHeadName"), row.amount());
                sourceAllocations.add(allocation);
                preservedAllocations.add(new OriginalPaymentAllocation(row.id(), chargeId, row.feeHeadId(), row.amount()));
            }
            if (sourceAllocations.stream().map(AllocationEvidence::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .compareTo(source.amount()) != 0
                    || !proofSourceJsonEqual(frozen, proofJson(json(sourceSnapshot(source, sourceAllocations))))) {
                throw originalPaymentProofInvalid();
            }
            allocations.addAll(sourceAllocations);
            originalAllocations.put(source.sourceId(), List.copyOf(preservedAllocations));
        }
        EffectiveConfiguration configuration = originalPaymentConfiguration(scope, target);
        List<SourceEntry> sources = items.stream().map(OriginalPostingItem::source).toList();
        if (sources.stream().anyMatch(source -> configuration.tenderAccounts().get(source.paymentMethod()) == null)
                || ("CASH".equals(configuration.basis()) && allocations.stream()
                    .anyMatch(allocation -> configuration.incomeAccounts().get(allocation.feeHeadId()) == null))) {
            throw originalPaymentProofInvalid();
        }
        PostingEvidence expected = postingEvidence(configuration, target.yearId(), target.periodId(), sources, allocations);
        if (proofMoney(target.sourceAmount(), false).compareTo(expected.total()) != 0
                || !proofBatchJsonEqual(proofJson(target.evidenceSnapshot()), proofJson(json(expected.snapshot())))) {
            throw originalPaymentProofInvalid();
        }
        FeeJournalEvidence journal = journalReader.apply(target);
        if (journal == null || !target.journalId().equals(journal.id())
                || !target.batchId().equals(journal.sourceId()) || !target.yearId().equals(journal.financialYearId())
                || !target.periodId().equals(journal.accountingPeriodId()) || !target.businessDate().equals(journal.postingDate())
                || !configuration.basis().equals(journal.accountingBasis()) || !configuration.currency().equals(journal.currencyCode())
                || !"POSTED".equals(journal.status()) || journal.reversalId() != null || journal.reversalStatus() != null
                || !journal.reversalLines().isEmpty()) throw originalPaymentProofInvalid();
        List<FeeJournalLine> expectedLines = journalLines(configuration, "PAYMENT", sources, allocations);
        Map<OriginalJournalDimension, OriginalJournalAmounts> expectedTotals = new LinkedHashMap<>();
        for (FeeJournalLine line : expectedLines) addOriginalJournalLine(expectedTotals,
                new OriginalJournalDimension(line.accountId(), null, line.learnerFeeAccountId()), line.debitAmount(), line.creditAmount());
        Map<OriginalJournalDimension, OriginalJournalAmounts> actualTotals = new LinkedHashMap<>();
        if (journal.lines().size() < 2 || journal.lines().size() > 500) throw originalPaymentProofInvalid();
        for (FeeJournalLineEvidence line : journal.lines()) {
            if (line == null || line.accountId() == null) throw originalPaymentProofInvalid();
            addOriginalJournalLine(actualTotals, new OriginalJournalDimension(line.accountId(), line.vendorId(), line.learnerFeeAccountId()),
                    line.debitAmount(), line.creditAmount());
        }
        if (!expectedTotals.equals(actualTotals)) throw originalPaymentProofInvalid();
        // The retained digests link immutable records. JSONB is compared semantically:
        // reserializing it cannot recreate the original pre-JSONB hash byte stream.
        return new VerifiedOriginalPaymentBatch(target, configuration,
                items.stream().collect(Collectors.toMap(OriginalPostingItem::id, Function.identity())), originalAllocations);
    }

    private void validateOriginalSelectedTarget(UUID paymentId, OriginalPaymentTarget target, OriginalPostingItem selected) {
        if (selected == null || !selected.source().id().equals(target.ledgerId())
                || !selected.source().sourceId().equals(paymentId) || !selected.source().feeAccountId().equals(target.accountId())
                || !selected.sourceSha256().equals(target.sourceSha256()) || selected.source().amount().compareTo(target.receiptAmount()) != 0
                || !selected.source().paymentMethod().equals(target.paymentMethod())) throw originalPaymentProofInvalid();
    }

    private OriginalPaymentPostingProof originalProofFromTarget(UUID paymentId, OriginalPaymentTarget target, VerifiedOriginalPaymentBatch batch) {
        OriginalPaymentTarget header = batch.header();
        if (!paymentId.equals(target.paymentId()) || !header.batchId().equals(target.batchId())
                || !header.configurationId().equals(target.configurationId()) || !header.journalId().equals(target.journalId())
                || !header.yearId().equals(target.yearId()) || !header.periodId().equals(target.periodId())
                || !header.businessDate().equals(target.businessDate()) || !header.event().equals(target.event())
                || !header.disposition().equals(target.disposition()) || header.sourceCount() != target.sourceCount()
                || !header.currency().equals(target.currency()) || !header.evidenceSha256().equals(target.evidenceSha256())
                || proofMoney(header.sourceAmount(), false).compareTo(proofMoney(target.sourceAmount(), false)) != 0
                || !proofBatchJsonEqual(proofJson(header.evidenceSnapshot()), proofJson(target.evidenceSnapshot())))
            throw originalPaymentProofInvalid();
        validateOriginalSelectedTarget(paymentId, target, batch.items().get(target.itemId()));
        var allocations = batch.allocations().get(paymentId);
        if (allocations == null || allocations.isEmpty() || allocations.size() > 100) throw originalPaymentProofInvalid();
        var configuration = batch.configuration();
        return new OriginalPaymentPostingProof(paymentId, target.ledgerId(), target.itemId(), target.batchId(), configuration.id(),
                target.journalId(), target.yearId(), target.periodId(), target.businessDate(), configuration.basis(), configuration.currency(),
                configuration.tenderAccounts().get("ONLINE_PROVIDER"), configuration.receivableId(), configuration.incomeAccounts(),
                allocations, target.sourceSha256(), target.evidenceSha256());
    }

    private void requireOriginalPaymentContext(AuthenticatedUser user, UUID schoolId) {
        var context = TenantRequestContext.current().orElse(null);
        if (user == null || user.userId() == null || user.activeOrganisationId() == null || schoolId == null
                || user.membershipIds().isEmpty() || context == null || !user.userId().equals(context.userId())
                || !user.activeOrganisationId().equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()) throw originalPaymentProofDenied();
        Boolean bound = jdbc.queryForObject("""
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id()=:userId
                """, new MapSqlParameterSource().addValue("organisationId", user.activeOrganisationId())
                .addValue("userId", user.userId()), Boolean.class);
        if (!Boolean.TRUE.equals(bound)) throw originalPaymentProofDenied();
    }

    private static final String ORIGINAL_PAYMENT_TARGET_QUERY = """
                /* original-payment-proof-target */
                SELECT payment.id AS payment_id,ledger.id AS fee_ledger_entry_id,posting_item.id AS posting_item_id,
                  batch.id AS posting_batch_id,batch.configuration_id,batch.accounting_journal_id,
                  batch.financial_year_id,batch.accounting_period_id,batch.business_date,batch.event_type,batch.disposition,
                  batch.source_count,batch.source_amount,batch.evidence_snapshot::text,batch.evidence_sha256,
                  posting_item.source_sha256,payment.status AS receipt_status,payment.payment_method,
                  payment.amount AS receipt_amount,payment.learner_fee_account_id AS receipt_account_id,account.currency_code AS receipt_currency
                FROM fee_payments payment
                JOIN learner_fee_accounts account ON account.id=payment.learner_fee_account_id
                  AND account.organisation_id=payment.organisation_id AND account.school_id=payment.school_id
                JOIN fee_ledger_entries ledger ON ledger.id=payment.ledger_entry_id
                  AND ledger.organisation_id=payment.organisation_id AND ledger.school_id=payment.school_id
                  AND ledger.source_id=payment.id AND ledger.source_type='FEE_PAYMENT'
                  AND ledger.entry_type='PAYMENT' AND ledger.direction='CREDIT'
                  AND ledger.learner_fee_account_id=payment.learner_fee_account_id AND ledger.amount=payment.amount
                  AND ledger.effective_on=payment.received_on AND ledger.learner_fee_charge_id IS NULL AND ledger.reverses_entry_id IS NULL
                  AND ledger.gateway_capture_id=payment.gateway_capture_id
                JOIN fee_gateway_captures capture ON capture.id=payment.gateway_capture_id
                  AND capture.organisation_id=payment.organisation_id AND capture.school_id=payment.school_id
                  AND capture.amount=payment.amount AND capture.currency_code=account.currency_code
                JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
                  AND attempt.organisation_id=capture.organisation_id AND attempt.school_id=capture.school_id
                  AND attempt.learner_fee_account_id=account.id AND attempt.fee_payment_id=payment.id
                JOIN fee_accounting_posting_items posting_item ON posting_item.fee_ledger_entry_id=ledger.id
                  AND posting_item.organisation_id=ledger.organisation_id AND posting_item.school_id=ledger.school_id AND posting_item.status='ACTIVE'
                JOIN fee_accounting_posting_batches batch ON batch.id=posting_item.posting_batch_id
                  AND batch.organisation_id=posting_item.organisation_id AND batch.school_id=posting_item.school_id
                WHERE payment.organisation_id=:organisationId AND payment.school_id=:schoolId
                  AND payment.status='POSTED' AND payment.payment_method='ONLINE_PROVIDER'
                  AND EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions resolution WHERE resolution.capture_id=capture.id
                    AND resolution.organisation_id=payment.organisation_id AND resolution.school_id=payment.school_id
                    AND resolution.resolution_type='POSTED' AND resolution.fee_payment_id=payment.id AND resolution.open_date=payment.received_on)
                """;

    private static OriginalPaymentTarget originalPaymentTargetRow(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new OriginalPaymentTarget(
                row.getObject("payment_id", UUID.class), row.getObject("fee_ledger_entry_id", UUID.class),
                row.getObject("posting_item_id", UUID.class), row.getObject("posting_batch_id", UUID.class),
                row.getObject("configuration_id", UUID.class), row.getObject("accounting_journal_id", UUID.class),
                row.getObject("financial_year_id", UUID.class), row.getObject("accounting_period_id", UUID.class),
                row.getObject("business_date", LocalDate.class), row.getString("event_type"), row.getString("disposition"),
                row.getInt("source_count"), row.getBigDecimal("source_amount"), row.getString("evidence_snapshot"),
                row.getString("evidence_sha256"), row.getString("source_sha256"), row.getString("receipt_status"),
                row.getString("payment_method"), row.getBigDecimal("receipt_amount"), row.getObject("receipt_account_id", UUID.class),
                row.getString("receipt_currency"));
    }

    private OriginalPaymentTarget originalPaymentTarget(OriginalSourceScope scope, UUID paymentId) {
        List<OriginalPaymentTarget> rows = jdbc.query(ORIGINAL_PAYMENT_TARGET_QUERY + " AND payment.id=:paymentId LIMIT 2",
                scope.params().addValue("paymentId", paymentId), FeeAccountingService::originalPaymentTargetRow);
        if (rows.size() != 1 || !paymentId.equals(rows.getFirst().paymentId())) throw originalPaymentProofInvalid();
        return rows.getFirst();
    }

    private List<OriginalPaymentTarget> originalPaymentTargets(OriginalSourceScope scope, Set<UUID> paymentIds) {
        return jdbc.query("""
                /* original-payment-proof-targets */
                WITH candidates AS (
                """ + ORIGINAL_PAYMENT_TARGET_QUERY + """
                  AND payment.id IN (:paymentIds)
                ), ranked AS (
                  SELECT candidates.*,row_number() OVER(PARTITION BY payment_id ORDER BY posting_item_id,posting_batch_id) target_rank
                  FROM candidates
                )
                SELECT * FROM ranked WHERE target_rank<=2 ORDER BY payment_id,target_rank LIMIT :targetLimit
                """, scope.params().addValue("paymentIds", paymentIds).addValue("targetLimit", paymentIds.size()*2+1),
                FeeAccountingService::originalPaymentTargetRow);
    }

    private List<OriginalPostingItem> originalPaymentItems(OriginalSourceScope scope, UUID batchId) {
        return jdbc.query("""
                /* original-payment-proof-items */
                SELECT posting_item.id AS posting_item_id,posting_item.sequence_number,posting_item.status AS item_status,
                  posting_item.source_snapshot::text,posting_item.source_sha256,
                  ledger.id,ledger.learner_fee_account_id,ledger.learner_fee_charge_id,ledger.entry_type,ledger.direction,
                  ledger.amount,ledger.effective_on,ledger.description,ledger.source_type,ledger.source_id,ledger.reverses_entry_id,
                  ledger.created_at,account.currency_code,NULL::uuid AS fee_head_id,NULL::text AS fee_head_code,
                  NULL::text AS fee_head_name,payment.payment_method,payment.receipt_number
                FROM fee_accounting_posting_items posting_item
                LEFT JOIN fee_ledger_entries ledger ON ledger.id=posting_item.fee_ledger_entry_id
                  AND ledger.organisation_id=posting_item.organisation_id AND ledger.school_id=posting_item.school_id
                LEFT JOIN learner_fee_accounts account ON account.id=ledger.learner_fee_account_id
                  AND account.organisation_id=ledger.organisation_id AND account.school_id=ledger.school_id
                LEFT JOIN fee_payments payment ON payment.id=ledger.source_id
                  AND payment.organisation_id=ledger.organisation_id AND payment.school_id=ledger.school_id
                  AND payment.ledger_entry_id=ledger.id AND payment.learner_fee_account_id=ledger.learner_fee_account_id
                  AND payment.amount=ledger.amount AND payment.received_on=ledger.effective_on AND payment.status IN ('POSTED','REVERSED')
                WHERE posting_item.posting_batch_id=:batchId AND posting_item.organisation_id=:organisationId AND posting_item.school_id=:schoolId
                ORDER BY posting_item.sequence_number,posting_item.id LIMIT 201
                """, scope.params().addValue("batchId", batchId), (row, index) -> new OriginalPostingItem(
                row.getObject("posting_item_id", UUID.class), row.getInt("sequence_number"), row.getString("item_status"),
                row.getString("source_snapshot"), row.getString("source_sha256"), sourceEntry(row, true)));
    }

    private List<OriginalAllocationRow> originalPaymentAllocations(OriginalSourceScope scope, Set<UUID> paymentIds) {
        return jdbc.query("""
                /* original-payment-proof-allocations */
                SELECT allocation.id AS allocation_id,allocation.fee_payment_id,allocation.learner_fee_charge_id,item.fee_head_id,
                  charge.learner_fee_account_id,allocation.amount
                FROM fee_payment_allocations allocation
                LEFT JOIN learner_fee_charges charge ON charge.id=allocation.learner_fee_charge_id
                  AND charge.organisation_id=allocation.organisation_id AND charge.school_id=allocation.school_id
                LEFT JOIN fee_plan_items item ON item.id=charge.fee_plan_item_id
                  AND item.organisation_id=charge.organisation_id AND item.school_id=charge.school_id
                WHERE allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId AND allocation.fee_payment_id IN (:paymentIds)
                ORDER BY allocation.fee_payment_id,item.sequence_number,allocation.learner_fee_charge_id LIMIT 200001
                """, scope.params().addValue("paymentIds", paymentIds), (row, index) -> new OriginalAllocationRow(
                row.getObject("allocation_id", UUID.class), row.getObject("fee_payment_id", UUID.class),
                row.getObject("learner_fee_charge_id", UUID.class), row.getObject("fee_head_id", UUID.class),
                row.getObject("learner_fee_account_id", UUID.class), proofMoney(row.getBigDecimal("amount"), false)));
    }

    private EffectiveConfiguration originalPaymentConfiguration(OriginalSourceScope scope, OriginalPaymentTarget target) {
        List<StoredConfiguration> rows = jdbc.query("""
                /* original-payment-proof-configuration */
                SELECT * FROM fee_accounting_configurations WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", target.configurationId()), (row, index) -> storedConfiguration(row));
        if (rows.size() != 1) throw originalPaymentProofInvalid();
        StoredConfiguration original = rows.getFirst();
        if (!target.configurationId().equals(original.id()) || original.status() == null
                || !Set.of("APPROVED", "SUPERSEDED").contains(original.status()) || !target.yearId().equals(original.financialYearId())
                || original.effectiveFrom() == null || original.effectiveFrom().isAfter(target.businessDate())
                || original.basis() == null || !Set.of("CASH", "ACCRUAL").contains(original.basis()) || !target.currency().equals(original.currency())
                || ("ACCRUAL".equals(original.basis()) && (original.receivableId() == null || original.concessionId() == null))
                || ("CASH".equals(original.basis()) && (original.receivableId() != null || original.concessionId() != null))) {
            throw originalPaymentProofInvalid();
        }
        EffectiveConfiguration configuration = new EffectiveConfiguration(original.id(), original.financialYearId(), original.effectiveFrom(),
                original.basis(), original.currency(), original.receivableId(), original.concessionId(),
                feeHeadAccountMap(scope.params(), original.id()), tenderAccountMap(scope.params(), original.id()));
        if (configuration.tenderAccounts().get("ONLINE_PROVIDER") == null) throw originalPaymentProofInvalid();
        return configuration;
    }

    private JsonNode proofJson(String value) {
        if (value == null) throw originalPaymentProofInvalid();
        try {
            JsonNode result = proofObjectMapper.readTree(value);
            if (result == null || !result.isObject()) throw originalPaymentProofInvalid();
            return result;
        } catch (JacksonException invalid) {
            throw originalPaymentProofInvalid();
        }
    }

    private static String proofText(JsonNode value, String field) {
        JsonNode text = value == null ? null : value.get(field);
        if (text == null || !text.isString() || text.stringValue().isBlank()) throw originalPaymentProofInvalid();
        return text.stringValue();
    }

    private static UUID proofUuid(JsonNode value, String field) {
        try { return UUID.fromString(proofText(value, field)); }
        catch (IllegalArgumentException invalid) { throw originalPaymentProofInvalid(); }
    }

    private static boolean proofJsonEqual(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected.isNumber() && actual.isNumber()) return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) return false;
            for (int index = 0; index < expected.size(); index++) if (!proofJsonEqual(expected.get(index), actual.get(index))) return false;
            return true;
        }
        if (expected.isObject() && actual.isObject()) {
            if (expected.size() != actual.size()) return false;
            for (String field : expected.propertyNames()) if (!proofJsonEqual(expected.get(field), actual.get(field))) return false;
            return true;
        }
        return expected.equals(actual);
    }

    private static final Set<String> NULL_PAYMENT_SOURCE_FIELDS = Set.of(
            "learnerFeeChargeId", "reversesEntryId", "feeHeadId", "feeHeadCode", "feeHeadName");

    /**
     * The persisted source schema predates the application's NON_NULL mapper.
     * Only these independently validated, inapplicable source fields may be
     * absent or JSON null. Required fields, allocations and unknown fields
     * retain strict equality; no stored JSON or retained digest is rewritten.
     */
    private static boolean proofSourceJsonEqual(JsonNode expected, JsonNode actual) {
        if (!paymentOrRefundSource(expected) || !paymentOrRefundSource(actual)) return false;
        Set<String> fields = new LinkedHashSet<>(expected.propertyNames());
        fields.addAll(actual.propertyNames());
        for (String field : fields) {
            JsonNode first = expected.get(field), second = actual.get(field);
            if (NULL_PAYMENT_SOURCE_FIELDS.contains(field)) {
                if ((first != null && !first.isNull()) || (second != null && !second.isNull())) return false;
            } else if (!proofJsonEqual(first, second)) return false;
        }
        return true;
    }

    private static boolean paymentOrRefundSource(JsonNode source) {
        if (source == null || !source.isObject()) return false;
        return (proofStringIs(source, "eventType", "PAYMENT") && proofStringIs(source, "sourceType", "FEE_PAYMENT")
                    && proofStringIs(source, "direction", "CREDIT"))
                || (proofStringIs(source, "eventType", "REFUND") && proofStringIs(source, "sourceType", "GATEWAY_REFUND_POSTING")
                    && proofStringIs(source, "direction", "DEBIT"));
    }

    private static boolean proofStringIs(JsonNode value, String field, String expected) {
        JsonNode text = value.get(field);
        return text != null && text.isString() && expected.equals(text.stringValue());
    }

    private static boolean proofBatchJsonEqual(JsonNode expected, JsonNode actual) {
        if (!expected.isObject() || !actual.isObject() || expected.size() != actual.size()
                || !proofJsonEqual(expected.get("schemaVersion"), actual.get("schemaVersion"))
                || expected.get("schemaVersion") == null || !expected.get("schemaVersion").isIntegralNumber()
                || expected.get("schemaVersion").intValue() != 1) return false;
        for (String field : expected.propertyNames()) {
            if (!"sources".equals(field)) {
                if (!proofJsonEqual(expected.get(field), actual.get(field))) return false;
                continue;
            }
            JsonNode first = expected.get(field), second = actual.get(field);
            if (first == null || second == null || !first.isArray() || !second.isArray()
                    || first.size() != second.size()) return false;
            for (int index = 0; index < first.size(); index++)
                if (!proofSourceJsonEqual(first.get(index), second.get(index))) return false;
        }
        return true;
    }

    private static BigDecimal proofMoney(BigDecimal value, boolean zeroAllowed) {
        if (value == null || value.signum() < 0 || (!zeroAllowed && value.signum() == 0)
                || value.compareTo(new BigDecimal("1000000000000.00")) > 0) throw originalPaymentProofInvalid();
        try { return value.setScale(2); }
        catch (ArithmeticException invalid) { throw originalPaymentProofInvalid(); }
    }

    private static void addOriginalJournalLine(Map<OriginalJournalDimension, OriginalJournalAmounts> totals,
            OriginalJournalDimension dimension, BigDecimal debit, BigDecimal credit) {
        BigDecimal dr = proofMoney(debit, true);
        BigDecimal cr = proofMoney(credit, true);
        if (dr.signum() == 0 && cr.signum() == 0) throw originalPaymentProofInvalid();
        totals.merge(dimension, new OriginalJournalAmounts(dr, cr), (first, second) -> new OriginalJournalAmounts(
                first.debit().add(second.debit()), first.credit().add(second.credit())));
    }

    private static boolean proofDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static ApiException originalPaymentProofInvalid() {
        return new HistoricalPostingProofUnavailable("FEE_ORIGINAL_PAYMENT_PROOF_INVALID", "The complete original payment posting evidence is unavailable or requires reconciliation.");
    }
    /** Expected read-only review outcome. Mutating callers still roll back when this escapes their transaction. */
    static final class HistoricalPostingProofUnavailable extends ApiException {
        private static final long serialVersionUID = 1L;
        private HistoricalPostingProofUnavailable(String code, String message) { super(HttpStatus.CONFLICT, code, message); }
    }
    private static ApiException originalPaymentProofDenied() {
        return forbidden("FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED", "Original payment evidence is unavailable for this school and session.");
    }
    private static ApiException originalPaymentAuthorityFailure(ApiException failure) {
        if (failure.status() == HttpStatus.FORBIDDEN && ("FEE_ACCOUNTING_SCOPE_DENIED".equals(failure.code())
                || "ACCOUNTING_SCOPE_DENIED".equals(failure.code()))) {
            return originalPaymentProofDenied();
        }
        return failure;
    }

    @Transactional(readOnly = true)
    public FeeAccountingSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId, "fees.read");
        List<FeeHeadOption> heads = jdbc.query("""
                SELECT id,code,display_name,status FROM fee_heads
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,display_name,id
                """, scope.params(), (result, row) -> new FeeHeadOption(
                result.getObject("id", UUID.class), result.getString("code"),
                result.getString("display_name"), result.getString("status")));

        Map<UUID, List<HeadMappingItem>> headMappings = new LinkedHashMap<>();
        jdbc.query("""
                SELECT mapping.configuration_id,mapping.fee_head_id,head.code,head.display_name,
                       mapping.income_account_id
                FROM fee_accounting_head_mappings mapping
                JOIN fee_heads head ON head.id=mapping.fee_head_id
                  AND head.organisation_id=mapping.organisation_id
                  AND head.school_id=mapping.school_id
                WHERE mapping.organisation_id=:organisationId AND mapping.school_id=:schoolId
                ORDER BY mapping.configuration_id,head.display_name,head.id
                """, scope.params(), result -> {
                    UUID configurationId = result.getObject("configuration_id", UUID.class);
                    headMappings.computeIfAbsent(configurationId, ignored -> new ArrayList<>())
                            .add(new HeadMappingItem(
                                    result.getObject("fee_head_id", UUID.class),
                                    result.getString("code"),
                                    result.getString("display_name"),
                                    result.getObject("income_account_id", UUID.class)));
                });
        Map<UUID, List<TenderMappingItem>> tenderMappings = new LinkedHashMap<>();
        jdbc.query("""
                SELECT configuration_id,payment_method,deposit_account_id
                FROM fee_accounting_tender_mappings
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY configuration_id,payment_method
                """, scope.params(), result -> {
                    UUID configurationId = result.getObject("configuration_id", UUID.class);
                    tenderMappings.computeIfAbsent(configurationId, ignored -> new ArrayList<>())
                            .add(new TenderMappingItem(result.getString("payment_method"),
                                    result.getObject("deposit_account_id", UUID.class)));
                });
        List<ConfigurationItem> configurations = jdbc.query("""
                SELECT configuration.*,creator_user.display_name AS creator_name,
                       submitter_user.display_name AS submitter_name,
                       decider_user.display_name AS decider_name
                FROM fee_accounting_configurations configuration
                JOIN memberships creator ON creator.id=configuration.created_by_membership_id
                JOIN user_accounts creator_user ON creator_user.id=creator.user_id
                LEFT JOIN memberships submitter ON submitter.id=configuration.submitted_by_membership_id
                LEFT JOIN user_accounts submitter_user ON submitter_user.id=submitter.user_id
                LEFT JOIN memberships decider ON decider.id=configuration.decided_by_membership_id
                LEFT JOIN user_accounts decider_user ON decider_user.id=decider.user_id
                WHERE configuration.organisation_id=:organisationId
                  AND configuration.school_id=:schoolId
                ORDER BY configuration.effective_from DESC,configuration.revision_number DESC
                """, scope.params(), (result, row) -> {
                    UUID id = result.getObject("id", UUID.class);
                    return new ConfigurationItem(id,
                            result.getObject("financial_year_id", UUID.class),
                            result.getObject("supersedes_configuration_id", UUID.class),
                            result.getInt("revision_number"),
                            result.getObject("effective_from", LocalDate.class),
                            result.getString("accounting_basis_snapshot"),
                            result.getString("currency_code"),
                            result.getObject("receivable_account_id", UUID.class),
                            result.getObject("concession_account_id", UUID.class),
                            result.getString("status"), result.getString("creator_name"),
                            instant(result, "created_at"), result.getString("submitter_name"),
                            instant(result, "submitted_at"), result.getString("decider_name"),
                            instant(result, "decided_at"), result.getLong("row_version"),
                            headMappings.getOrDefault(id, List.of()),
                            tenderMappings.getOrDefault(id, List.of()));
                });

        List<BatchRow> rows = batchRows(scope);
        List<UUID> journalIds = rows.stream().map(BatchRow::journalId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> journalStatuses =
                accounting.feeJournalStatuses(user, scope.schoolId(), journalIds);
        List<PostingBatchItem> batches = rows.stream()
                .map(row -> batchItem(row, journalStatuses.get(row.journalId()))).toList();
        List<UnpreparedEventSummary> unprepared = unpreparedEvents(scope);
        int sourceCount = unprepared.stream().mapToInt(UnpreparedEventSummary::sourceCount).sum();
        BigDecimal sourceAmount = unprepared.stream().map(UnpreparedEventSummary::sourceAmount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        ReconciliationSummary reconciliation = new ReconciliationSummary(
                countStatus(batches, "RECONCILED"), countStatus(batches, "AWAITING_APPROVAL"),
                countStatus(batches, "NO_GL_EFFECT"),
                countStatus(batches, "REPREPARED"),
                countStatus(batches, "REQUIRES_REPREPARATION"), sourceCount, sourceAmount);
        return new FeeAccountingSnapshot(scope.schoolId(), scope.schoolName(),
                scope.canConfigure(), scope.canPrepare(), scope.canApprove(), heads,
                configurations, batches, unprepared, reconciliation);
    }

    @Transactional
    public FinanceMutationResponse createConfiguration(AuthenticatedUser user,
            ConfigurationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.close.propose");
        lockFinance(scope);
        ValidatedConfiguration validated = validateConfigurationRequest(user, scope, request, false);
        validateRevision(scope, request, null);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("FEE_ACCOUNTING_CONFIGURATION_CONFLICT", () -> jdbc.update("""
                INSERT INTO fee_accounting_configurations(id,organisation_id,school_id,
                  financial_year_id,supersedes_configuration_id,revision_number,effective_from,
                  accounting_basis_snapshot,currency_code,receivable_account_id,
                  concession_account_id,status,creation_reason,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:financialYearId,:supersedesId,
                  :revision,:effectiveFrom,:basis,:currency,:receivableId,:concessionId,
                  'DRAFT',:reason,:actorMembershipId,:now,:now,0)
                """, configurationParams(scope, id, request, validated.book(), now)));
        replaceMappings(scope, id, request, now);
        audit(user, scope, "finance.accounting-configuration.created",
                "FEE_ACCOUNTING_CONFIGURATION", id, request.reason());
        return new FinanceMutationResponse(id, 0);
    }

    @Transactional
    public FinanceMutationResponse updateConfiguration(AuthenticatedUser user, UUID id,
            ConfigurationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.close.propose");
        lockFinance(scope);
        StoredConfiguration stored = lockConfiguration(scope, id);
        long expectedVersion = requiredVersion(request.expectedVersion());
        if (!"DRAFT".equals(stored.status()) || stored.version() != expectedVersion) {
            throw conflict("FEE_ACCOUNTING_CONFIGURATION_NOT_EDITABLE",
                    "Refresh the draft configuration and try again.");
        }
        if (!stored.creatorId().equals(scope.actorMembershipId())) {
            throw forbidden("FEE_ACCOUNTING_CONFIGURATION_CREATOR_REQUIRED",
                    "Only the configuration maker can edit this draft.");
        }
        if (!stored.financialYearId().equals(request.financialYearId())
                || stored.revision() != request.revisionNumber()) {
            throw bad("FEE_ACCOUNTING_CONFIGURATION_IDENTITY_INVALID",
                    "The financial year and revision number cannot be changed.");
        }
        ValidatedConfiguration validated = validateConfigurationRequest(user, scope, request, false);
        validateRevision(scope, request, id);
        Instant now = Instant.now(clock);
        int changed = safe("FEE_ACCOUNTING_CONFIGURATION_CONFLICT", () -> jdbc.update("""
                UPDATE fee_accounting_configurations
                SET supersedes_configuration_id=:supersedesId,effective_from=:effectiveFrom,
                    receivable_account_id=:receivableId,concession_account_id=:concessionId,
                    updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, configurationParams(scope, id, request, validated.book(), now)
                .addValue("version", expectedVersion)));
        changed(changed);
        jdbc.update("""
                DELETE FROM fee_accounting_head_mappings
                WHERE configuration_id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", id));
        jdbc.update("""
                DELETE FROM fee_accounting_tender_mappings
                WHERE configuration_id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", id));
        replaceMappings(scope, id, request, now);
        audit(user, scope, "finance.accounting-configuration.updated",
                "FEE_ACCOUNTING_CONFIGURATION", id, request.reason());
        return new FinanceMutationResponse(id, expectedVersion + 1);
    }

    @Transactional
    public FinanceMutationResponse submitConfiguration(AuthenticatedUser user, UUID id,
            VersionedReasonRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.close.propose");
        lockFinance(scope);
        StoredConfiguration stored = lockConfiguration(scope, id);
        if (!"DRAFT".equals(stored.status()) || stored.version() != request.expectedVersion()) {
            throw conflict("FEE_ACCOUNTING_CONFIGURATION_NOT_SUBMITTABLE",
                    "Refresh the draft configuration and try again.");
        }
        if (!stored.creatorId().equals(scope.actorMembershipId())) {
            throw forbidden("FEE_ACCOUNTING_CONFIGURATION_CREATOR_REQUIRED",
                    "Only the configuration maker can submit this draft.");
        }
        List<FeeAccountUse> uses = configurationAccountUses(scope, stored);
        accounting.validateFeeConfigurationDraft(user, scope.schoolId(),
                stored.financialYearId(), stored.effectiveFrom(), uses);
        Instant now = Instant.now(clock);
        int changed = safe("FEE_ACCOUNTING_CONFIGURATION_CONFLICT", () -> jdbc.update("""
                UPDATE fee_accounting_configurations
                SET status='SUBMITTED',submitted_by_membership_id=:actorMembershipId,
                    submitted_at=:now,submission_reason=:reason,updated_at=:now,
                    row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, scope.params().addValue("id", id)
                .addValue("version", request.expectedVersion())
                .addValue("reason", reason(request.reason())).addValue("now", now)));
        changed(changed);
        audit(user, scope, "finance.accounting-configuration.submitted",
                "FEE_ACCOUNTING_CONFIGURATION", id, request.reason());
        return new FinanceMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public FinanceMutationResponse decideConfiguration(AuthenticatedUser user, UUID id,
            ConfigurationDecisionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.close.approve");
        lockFinance(scope);
        StoredConfiguration stored = lockConfiguration(scope, id);
        if (!"SUBMITTED".equals(stored.status())
                || stored.version() != request.expectedVersion()) {
            throw conflict("FEE_ACCOUNTING_CONFIGURATION_NOT_DECIDABLE",
                    "Refresh the submitted configuration and try again.");
        }
        if (stored.creatorId().equals(scope.actorMembershipId())) {
            throw forbidden("INDEPENDENT_FEE_ACCOUNTING_DECISION_REQUIRED",
                    "A different authorized person must decide this configuration.");
        }
        String decision = enumValue(request.decision(), Set.of("APPROVE", "REJECT"),
                "configuration decision");
        List<FeeAccountUse> uses = "APPROVE".equals(decision)
                ? configurationAccountUses(scope, stored) : List.of();
        accounting.validateFeeConfigurationDecision(user, scope.schoolId(),
                stored.financialYearId(), stored.effectiveFrom(), uses);
        String target = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        Instant now = Instant.now(clock);
        if ("APPROVED".equals(target) && stored.supersedesId() != null) {
            jdbc.queryForObject("""
                    SELECT set_config('app.fee_accounting_supersede','approved',true)
                    """, new MapSqlParameterSource(), String.class);
            int superseded = jdbc.update("""
                    UPDATE fee_accounting_configurations
                    SET status='SUPERSEDED',superseded_by_membership_id=:actorMembershipId,
                        superseded_at=:now,supersede_reason=:reason,updated_at=:now,
                        row_version=row_version+1
                    WHERE id=:supersedesId AND organisation_id=:organisationId
                      AND school_id=:schoolId AND financial_year_id=:financialYearId
                      AND status='APPROVED'
                    """, scope.params().addValue("supersedesId", stored.supersedesId())
                    .addValue("financialYearId", stored.financialYearId())
                    .addValue("reason", reason(request.reason())).addValue("now", now));
            changed(superseded);
        }
        int changed = safe("FEE_ACCOUNTING_CONFIGURATION_CONFLICT", () -> jdbc.update("""
                UPDATE fee_accounting_configurations
                SET status=:target,decided_by_membership_id=:actorMembershipId,
                    decided_at=:now,decision_reason=:reason,updated_at=:now,
                    row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='SUBMITTED' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("target", target)
                .addValue("version", request.expectedVersion())
                .addValue("reason", reason(request.reason())).addValue("now", now)));
        changed(changed);
        audit(user, scope, "finance.accounting-configuration."
                        + target.toLowerCase(Locale.ROOT),
                "FEE_ACCOUNTING_CONFIGURATION", id, request.reason());
        return new FinanceMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public FinanceMutationResponse preparePosting(AuthenticatedUser user,
            String idempotencyKey, PostingRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.close.propose");
        String key = idempotencyKey(idempotencyKey);
        String event = enumValue(request.eventType(), EVENTS, "fee event");
        if (request.businessDate().isAfter(today(scope))) {
            throw bad("FUTURE_FEE_POSTING_DATE",
                    "Fee accounting batches cannot be prepared for a future date.");
        }
        String why = reason(request.reason());
        String requestFingerprint = sha256(scope.schoolId() + "|"
                + request.financialYearId() + "|" + request.businessDate() + "|"
                + event + "|" + request.maxEntries() + "|" + why);
        ExistingBatch existing = existingBatch(scope, key);
        if (existing != null) {
            if (!existing.fingerprint().equals(requestFingerprint)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED",
                        "This retry key was already used for a different fee posting batch.");
            }
            return new FinanceMutationResponse(existing.id(), existing.version());
        }
        lockFinance(scope);
        existing = existingBatch(scope, key);
        if (existing != null) {
            if (!existing.fingerprint().equals(requestFingerprint)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED",
                        "This retry key was already used for a different fee posting batch.");
            }
            return new FinanceMutationResponse(existing.id(), existing.version());
        }
        EffectiveConfiguration configuration = effectiveConfiguration(scope,
                request.financialYearId(), request.businessDate());
        boolean noGlEffect = "CASH".equals(configuration.basis())
                && Set.of("CHARGE", "CONCESSION").contains(event);
        FeeBookContext book = accounting.resolveFeePostingBook(user, scope.schoolId(),
                request.financialYearId(), request.businessDate(), !noGlEffect);
        if (!book.accountingBasis().equals(configuration.basis())
                || !book.currencyCode().equals(configuration.currency())) {
            throw conflict("FEE_ACCOUNTING_CONFIGURATION_STALE",
                    "The effective fee mapping no longer matches the accounting book.");
        }
        releaseRejectedSources(user, scope);
        List<SourceEntry> sources = sourceEntries(scope, request.businessDate(),
                event, request.maxEntries());
        if (sources.isEmpty()) {
            throw conflict("NO_FEE_EVENTS_TO_PREPARE",
                    "There are no unprepared fee events for this date and event type.");
        }
        List<AllocationEvidence> allocations = paymentAllocations(scope, sources);
        PostingEvidence evidence = postingEvidence(configuration, book, sources, allocations);
        String disposition = noGlEffect ? "NO_GL_EFFECT" : "JOURNAL";
        int sequence = nextBatchSequence(scope, configuration.id(),
                request.businessDate(), event);
        UUID batchId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String evidenceJson = json(evidence.snapshot());
        String evidenceHash = sha256(evidenceJson);
        MapSqlParameterSource batchParams = scope.params().addValue("id", batchId)
                .addValue("configurationId", configuration.id())
                .addValue("financialYearId", request.financialYearId())
                .addValue("periodId", book.accountingPeriodId())
                .addValue("businessDate", request.businessDate()).addValue("event", event)
                .addValue("disposition", disposition).addValue("sequence", sequence)
                .addValue("key", key).addValue("fingerprint", requestFingerprint)
                .addValue("sourceCount", sources.size()).addValue("sourceAmount", evidence.total())
                .addValue("evidence", evidenceJson).addValue("evidenceHash", evidenceHash)
                .addValue("reason", why).addValue("now", now);
        safe("FEE_ACCOUNTING_BATCH_CONFLICT", () -> jdbc.update("""
                INSERT INTO fee_accounting_posting_batches(id,organisation_id,school_id,
                  configuration_id,financial_year_id,accounting_period_id,business_date,
                  event_type,disposition,sequence_number,idempotency_key,request_fingerprint,
                  source_count,source_amount,evidence_snapshot,evidence_sha256,
                  preparation_reason,prepared_by_membership_id,prepared_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:configurationId,:financialYearId,
                  :periodId,:businessDate,:event,:disposition,:sequence,:key,:fingerprint,
                  :sourceCount,:sourceAmount,CAST(:evidence AS jsonb),:evidenceHash,
                  :reason,:actorMembershipId,:now,0)
                """, batchParams));
        insertPostingItems(scope, batchId, sources, allocations, now);

        long version = 0;
        if (!noGlEffect) {
            List<FeeJournalLine> lines = journalLines(configuration, event, sources, allocations);
            AccountingMutationResponse journal = accounting.prepareFeeJournal(user,
                    new FeeJournalRequest(scope.schoolId(), request.financialYearId(),
                            book.accountingPeriodId(), request.businessDate(),
                            voucherType(event), "FEE-" + request.businessDate() + "-"
                                    + event + "-" + sequence,
                            "Fee " + event.toLowerCase(Locale.ROOT).replace('_', ' ')
                                    + " batch for " + request.businessDate(),
                            batchId, "fee-gl-" + batchId,
                            "Prepared from immutable fee ledger batch " + batchId, lines));
            jdbc.queryForObject("""
                    SELECT set_config('app.fee_accounting_journal_link','approved',true)
                    """, new MapSqlParameterSource(), String.class);
            int linked = jdbc.update("""
                    UPDATE fee_accounting_posting_batches
                    SET accounting_journal_id=:journalId,journal_linked_at=:now,
                        row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND accounting_journal_id IS NULL AND row_version=0
                    """, batchParams.addValue("journalId", journal.id()));
            changed(linked);
            version = 1;
        }
        audit(user, scope, "finance.accounting-posting.prepared",
                "FEE_ACCOUNTING_POSTING_BATCH", batchId, why);
        return new FinanceMutationResponse(batchId, version);
    }

    /** Called only in the atomic reviewed completion transaction, never by a provider worker. */
    @Transactional(propagation = Propagation.MANDATORY)
    RefundPostingPreparation prepareRefundPosting(AuthenticatedUser user, UUID schoolId, UUID refundPostingId,
            UUID financialYearId, LocalDate postingOn, String reason) {
        return prepareRefundPosting(user, schoolId, refundPostingId, financialYearId, postingOn, null, null, null, reason, false);
    }

    /** A deliberate accounting repair reuses the economic completion; it cannot append another refund debit. */
    @Transactional(propagation = Propagation.MANDATORY)
    RefundPostingPreparation reprepareRefundPosting(AuthenticatedUser user, UUID schoolId, UUID refundPostingId,
            UUID financialYearId, LocalDate postingOn, UUID clientRequestId, UUID expectedBatchId, UUID expectedJournalId, String reason) {
        if (clientRequestId == null || expectedBatchId == null || expectedJournalId == null) throw refundAccountingInvalid();
        return prepareRefundPosting(user, schoolId, refundPostingId, financialYearId, postingOn, clientRequestId, expectedBatchId, expectedJournalId, reason, true);
    }

    private RefundPostingPreparation prepareRefundPosting(AuthenticatedUser user, UUID schoolId, UUID refundPostingId,
            UUID financialYearId, LocalDate postingOn, UUID clientRequestId, UUID expectedBatchId, UUID expectedJournalId, String requestedReason, boolean reprepare) {
        requireOriginalPaymentContext(user, schoolId);
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly() || refundPostingId == null
                || financialYearId == null || postingOn == null) throw refundAccountingInvalid();
        Scope scope = requireScope(user, schoolId, "fees.refund");
        String why = reason(requestedReason);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked",
                scope.params().addValue("lockKey", scope.schoolId() + ":finance"), Integer.class);
        lockFinance(scope);
        // Revalidate the actual fee actor after all finance-lock waits; a cached
        // controller authority or transaction-start clock is not financial authority.
        jdbc.query("SELECT require_gateway_refund_actor(:organisationId,:schoolId,:actorMembershipId)", scope.params(), row -> {});
        // The exact accepted command is evidence, not a second admission into a
        // financial book that may have closed since acceptance. Keep live scope
        // checks above this lookup, but never re-run mutable book/date eligibility.
        String key = reprepare ? "fee-refund-reprepare-" + clientRequestId : "fee-refund-" + refundPostingId;
        String fingerprint = reprepare
                ? sha256("fee-refund-accounting:v2|" + user.userId() + "|" + refundPostingId + "|"
                    + financialYearId + "|" + postingOn + "|true|" + expectedBatchId + "|" + expectedJournalId + "|" + why)
                : sha256("fee-refund-accounting:v1|" + user.userId() + "|" + refundPostingId + "|"
                    + financialYearId + "|" + postingOn + "|false|" + why);
        if (reprepare) {
            jdbc.query("SELECT require_gateway_refund_posting_actor(:organisationId,:schoolId,:actorMembershipId)", scope.params(), row -> {});
            Boolean liveRead = jdbc.query("""
                    /* refund-repair-live-accounting-reader */
                    SELECT 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
                    JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
                    JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
                    JOIN membership_roles assignment ON assignment.membership_id=member.id
                    JOIN roles role ON role.id=assignment.role_id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                    JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code='accounting.read'
                    WHERE member.user_id=:actor AND member.id IN(:memberships) AND member.organisation_id=:organisationId
                      AND member.school_id=:schoolId AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
                      AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
                    LIMIT 1 FOR SHARE OF member,actor,assignment
                    """, scope.params().addValue("actor", user.userId()).addValue("memberships", user.membershipIds()),
                    (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) row -> row.next());
            if (!Boolean.TRUE.equals(liveRead)) throw forbidden("FEE_ACCOUNTING_SCOPE_DENIED", "Accounting review access is unavailable for this school.");
            ExistingBatch replay = existingBatch(scope, key);
            if (replay != null) {
                if (!fingerprint.equals(replay.fingerprint())) throw conflict("IDEMPOTENCY_KEY_REUSED", "This accounting retry identity belongs to different refund terms.");
                return refundBatchResult(scope, replay.id());
            }
        }
        RefundPostingSource refund = refundPostingSource(scope, refundPostingId);
        if (!refund.source().effectiveOn().equals(refund.postedOn())
                || (reprepare ? postingOn.isBefore(refund.postedOn()) : !postingOn.equals(refund.postedOn()))
                || postingOn.isAfter(today(scope))) throw refundAccountingInvalid();

        OriginalPaymentPostingProof original = verifyOriginalPaymentPosting(user, schoolId, refund.paymentId());
        List<AllocationEvidence> allocations = refundPostingAllocations(scope, refund, original);
        List<FeeAccountUse> accounts = refundAccountUses(original, allocations);
        FeeBookContext book = accounting.validateFeeRefundPosting(user, schoolId, original.accountingJournalId(),
                financialYearId, postingOn, accounts);
        // The original proof was first used only to identify the accounting lock
        // and original account uses. Repeat its complete-batch proof under that lock.
        OriginalPaymentPostingProof locked = verifyOriginalPaymentPosting(user, schoolId, refund.paymentId());
        if (!original.equals(locked) || !refund.originalItemId().equals(locked.postingItemId())
                || !refund.source().feeAccountId().equals(refund.accountId())
                || !locked.accountingBasis().equals(book.accountingBasis()) || !locked.currencyCode().equals(book.currencyCode())) {
            throw refundAccountingInvalid();
        }
        ExistingBatch replay = existingBatch(scope, key);
        if (replay != null) {
            if (!fingerprint.equals(replay.fingerprint())) throw conflict("IDEMPOTENCY_KEY_REUSED", "This accounting retry identity belongs to different refund terms.");
            return refundBatchResult(scope, replay.id());
        }
        List<RefundActiveBatch> active = refundActiveBatches(scope, refundPostingId, refund.source().id());
        if (active.size() > 1) throw refundAccountingInvalid();
        if (!active.isEmpty()) {
            RefundActiveBatch old = active.getFirst();
            if (!reprepare) return refundBatchResult(scope, old.id());
            if (!old.id().equals(expectedBatchId) || !Objects.equals(old.journalId(), expectedJournalId)) {
                throw conflict("FEE_REFUND_JOURNAL_STALE", "The selected refund journal has changed. Refresh before preparing a correction.");
            }
            if (postingOn.isBefore(old.postingOn())) throw refundAccountingInvalid();
            if (old.status() == null || !Set.of("REJECTED", "REVERSED").contains(old.status())) {
                throw conflict("FEE_REFUND_JOURNAL_NOT_REPREPARABLE", "Only a rejected or reversed refund voucher can be deliberately re-prepared.");
            }
            jdbc.queryForObject("SELECT set_config('app.fee_accounting_item_release','approved',true)", new MapSqlParameterSource(), String.class);
            int released = jdbc.update("""
                    UPDATE fee_accounting_posting_items SET status='RELEASED',released_by_membership_id=:actorMembershipId,
                      released_at=:now,release_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE posting_batch_id=:batchId AND fee_ledger_entry_id=:ledgerId AND organisation_id=:organisationId
                      AND school_id=:schoolId AND status='ACTIVE'
                    """, scope.params().addValue("batchId", old.id()).addValue("ledgerId", refund.source().id())
                    .addValue("reason", why).addValue("now", clock.instant()));
            if (released != 1) throw refundAccountingInvalid();
        } else if (reprepare) throw refundAccountingInvalid();
        EffectiveConfiguration configuration = new EffectiveConfiguration(locked.configurationId(), locked.originalFinancialYearId(),
                locked.originalPostingDate(), locked.accountingBasis(), locked.currencyCode(), locked.receivableAccountId(), null,
                locked.incomeAccounts(), Map.of("ONLINE_PROVIDER", locked.depositAccountId()));
        PostingEvidence evidence = postingEvidence(configuration, book, List.of(refund.source()), allocations);
        Map<String, Object> frozen = new LinkedHashMap<>(evidence.snapshot());
        frozen.put("refundPostingId", refundPostingId);
        frozen.put("originalPaymentPostingItemId", locked.postingItemId());
        frozen.put("originalPaymentPostingBatchId", locked.postingBatchId());
        frozen.put("originalPaymentJournalId", locked.accountingJournalId());
        frozen.put("originalSourceSha256", locked.sourceSha256());
        frozen.put("originalBatchEvidenceSha256", locked.batchEvidenceSha256());
        if (reprepare) {
            frozen.put("supersedesRefundBatchId", expectedBatchId);
            frozen.put("supersedesRefundJournalId", expectedJournalId);
            frozen.put("originalRefundPostingOn", refund.postedOn());
        }
        String snapshot = json(frozen);
        UUID batchId = UUID.randomUUID();
        Instant now = clock.instant();
        int sequence = nextBatchSequence(scope, locked.configurationId(), postingOn, "REFUND");
        var parameters = scope.params().addValue("id", batchId).addValue("configurationId", locked.configurationId())
                .addValue("yearId", book.financialYearId()).addValue("periodId", book.accountingPeriodId())
                .addValue("postingOn", postingOn).addValue("sequence", sequence).addValue("key", key)
                .addValue("fingerprint", fingerprint).addValue("amount", refund.source().amount())
                .addValue("snapshot", snapshot).addValue("hash", sha256(snapshot)).addValue("reason", why).addValue("now", now)
                .addValue("refundPostingId", refundPostingId).addValue("originalItemId", locked.postingItemId())
                .addValue("previousBatchId", expectedBatchId).addValue("previousJournalId", expectedJournalId);
        jdbc.update("""
                INSERT INTO fee_accounting_posting_batches(id,organisation_id,school_id,configuration_id,financial_year_id,
                  accounting_period_id,business_date,event_type,disposition,sequence_number,idempotency_key,request_fingerprint,
                  source_count,source_amount,evidence_snapshot,evidence_sha256,preparation_reason,prepared_by_membership_id,
                  prepared_at,row_version,refund_posting_id,original_payment_posting_item_id,supersedes_refund_batch_id,supersedes_refund_journal_id)
                VALUES(:id,:organisationId,:schoolId,:configurationId,:yearId,:periodId,:postingOn,'REFUND','JOURNAL',:sequence,
                  :key,:fingerprint,1,:amount,CAST(:snapshot AS jsonb),:hash,:reason,:actorMembershipId,:now,0,:refundPostingId,:originalItemId,:previousBatchId,:previousJournalId)
                """, parameters);
        insertPostingItems(scope, batchId, List.of(refund.source()), allocations, now);
        AccountingMutationResponse journal = accounting.prepareFeeJournal(user, new FeeJournalRequest(schoolId,
                book.financialYearId(), book.accountingPeriodId(), postingOn, "PAYMENT", "FEE-REFUND-" + refundPostingId,
                "Verified fee refund counter-entry", batchId, "fee-gl-" + batchId,
                why, journalLines(configuration, "REFUND", List.of(refund.source()), allocations)));
        jdbc.queryForObject("SELECT set_config('app.fee_accounting_journal_link','approved',true)", new MapSqlParameterSource(), String.class);
        int linked = jdbc.update("""
                UPDATE fee_accounting_posting_batches SET accounting_journal_id=:journalId,journal_linked_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND accounting_journal_id IS NULL AND row_version=0
                """, parameters.addValue("journalId", journal.id()));
        if (linked != 1) throw refundAccountingInvalid();
        audit(user, scope, reprepare ? "finance.gateway.refund.accounting-reprepared" : "finance.gateway.refund.accounting-prepared",
                "FEE_ACCOUNTING_POSTING_BATCH", batchId, why);
        return new RefundPostingPreparation(batchId, journal.id(), "SUBMITTED", 1);
    }

    private RefundPostingSource refundPostingSource(Scope scope, UUID id) {
        var rows = jdbc.query("""
                /* reviewed-refund-accounting-source */
                SELECT completion.id AS refund_posting_id,completion.refund_request_id,completion.fee_payment_id,
                  completion.learner_fee_account_id AS completion_account_id,completion.original_payment_posting_item_id,completion.posted_on,
                  ledger.*,account.currency_code,NULL::uuid AS fee_head_id,NULL::text AS fee_head_code,NULL::text AS fee_head_name,
                  payment.payment_method,payment.receipt_number
                FROM fee_gateway_refund_postings completion JOIN fee_ledger_entries ledger
                  ON ledger.id=completion.fee_ledger_entry_id AND ledger.organisation_id=completion.organisation_id AND ledger.school_id=completion.school_id
                  AND ledger.source_id=completion.id AND ledger.source_type='GATEWAY_REFUND_POSTING' AND ledger.entry_type='REFUND' AND ledger.direction='DEBIT'
                  AND ledger.amount=completion.amount AND ledger.effective_on=completion.posted_on
                  AND ledger.learner_fee_account_id=completion.learner_fee_account_id AND ledger.learner_fee_charge_id IS NULL
                  AND ledger.gateway_capture_id IS NULL AND ledger.reverses_entry_id IS NULL
                JOIN learner_fee_accounts account ON account.id=completion.learner_fee_account_id
                  AND account.organisation_id=completion.organisation_id AND account.school_id=completion.school_id AND account.currency_code=completion.currency_code
                JOIN fee_payments payment ON payment.id=completion.fee_payment_id AND payment.organisation_id=completion.organisation_id
                  AND payment.school_id=completion.school_id AND payment.learner_fee_account_id=account.id
                  AND payment.status='POSTED' AND payment.payment_method='ONLINE_PROVIDER'
                WHERE completion.id=:id AND completion.organisation_id=:organisationId AND completion.school_id=:schoolId
                """, scope.params().addValue("id", id), (row, number) -> new RefundPostingSource(
                        row.getObject("refund_posting_id", UUID.class), row.getObject("refund_request_id", UUID.class),
                        row.getObject("fee_payment_id", UUID.class), row.getObject("completion_account_id", UUID.class),
                        row.getObject("original_payment_posting_item_id", UUID.class), row.getObject("posted_on", LocalDate.class), sourceEntry(row, true)));
        if (rows.size() != 1) throw refundAccountingInvalid();
        return rows.getFirst();
    }

    private List<AllocationEvidence> refundPostingAllocations(Scope scope, RefundPostingSource refund, OriginalPaymentPostingProof proof) {
        var rows = jdbc.query("""
                /* reviewed-refund-accounting-allocations */
                SELECT allocation.original_payment_allocation_id,allocation.learner_fee_charge_id,allocation.fee_head_id,
                  allocation.amount,head.code AS fee_head_code,head.display_name AS fee_head_name
                FROM fee_gateway_refund_allocations allocation JOIN fee_heads head ON head.id=allocation.fee_head_id
                  AND head.organisation_id=allocation.organisation_id AND head.school_id=allocation.school_id
                WHERE allocation.refund_request_id=:refundId AND allocation.fee_payment_id=:paymentId
                  AND allocation.learner_fee_account_id=:accountId AND allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId
                ORDER BY allocation.learner_fee_charge_id LIMIT 101
                """, scope.params().addValue("refundId", refund.refundId()).addValue("paymentId", refund.paymentId())
                .addValue("accountId", refund.accountId()), (row, number) -> new RefundAllocation(
                        row.getObject("original_payment_allocation_id", UUID.class), new AllocationEvidence(refund.id(),
                        row.getObject("learner_fee_charge_id", UUID.class), row.getObject("fee_head_id", UUID.class),
                        row.getString("fee_head_code"), row.getString("fee_head_name"), proofMoney(row.getBigDecimal("amount"), false))));
        return validateRefundAllocations(refund.source().amount(), rows, proof);
    }

    static List<AllocationEvidence> validateRefundAllocations(BigDecimal amount, List<RefundAllocation> rows, OriginalPaymentPostingProof proof) {
        if (rows.isEmpty() || rows.size() > 100) throw refundAccountingInvalid();
        Map<UUID, OriginalPaymentAllocation> originals = proof.allocations().stream()
                .collect(Collectors.toMap(OriginalPaymentAllocation::allocationId, Function.identity()));
        Set<UUID> seen = new LinkedHashSet<>(), charges = new LinkedHashSet<>();
        for (RefundAllocation row : rows) {
            OriginalPaymentAllocation original = originals.get(row.originalAllocationId());
            AllocationEvidence allocation = row.value();
            if (original == null || !seen.add(row.originalAllocationId()) || !charges.add(allocation.chargeId())
                    || !original.chargeId().equals(allocation.chargeId()) || !original.feeHeadId().equals(allocation.feeHeadId())
                    || proofMoney(allocation.amount(), false).compareTo(original.amount()) > 0) throw refundAccountingInvalid();
        }
        List<AllocationEvidence> values = rows.stream().map(RefundAllocation::value).toList();
        if (values.stream().map(AllocationEvidence::amount).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(amount) != 0) throw refundAccountingInvalid();
        return values;
    }

    static List<FeeAccountUse> refundAccountUses(OriginalPaymentPostingProof proof, List<AllocationEvidence> allocations) {
        List<FeeAccountUse> uses = new ArrayList<>();
        uses.add(new FeeAccountUse(proof.depositAccountId(), FeeAccountUseType.ONLINE_CLEARING));
        if ("ACCRUAL".equals(proof.accountingBasis())) {
            if (proof.receivableAccountId() == null) throw refundAccountingInvalid();
            uses.add(new FeeAccountUse(proof.receivableAccountId(), FeeAccountUseType.RECEIVABLE));
        } else if ("CASH".equals(proof.accountingBasis())) {
            allocations.stream().map(AllocationEvidence::feeHeadId).distinct().forEach(head ->
                    uses.add(new FeeAccountUse(requiredMapping(proof.incomeAccounts(), head, "original fee head"), FeeAccountUseType.INCOME)));
        } else throw refundAccountingInvalid();
        return uses.stream().distinct().toList();
    }

    private List<RefundActiveBatch> refundActiveBatches(Scope scope, UUID refundPostingId, UUID ledgerId) {
        return jdbc.query("""
                SELECT batch.id,batch.accounting_journal_id,batch.business_date,journal.status FROM fee_accounting_posting_batches batch
                JOIN fee_accounting_posting_items item ON item.posting_batch_id=batch.id AND item.organisation_id=batch.organisation_id
                  AND item.school_id=batch.school_id AND item.status='ACTIVE'
                LEFT JOIN accounting_journals journal ON journal.id=batch.accounting_journal_id
                  AND journal.organisation_id=batch.organisation_id AND journal.school_id=batch.school_id
                WHERE batch.refund_posting_id=:refundPostingId AND batch.event_type='REFUND'
                  AND batch.organisation_id=:organisationId AND batch.school_id=:schoolId AND item.fee_ledger_entry_id=:ledgerId
                """, scope.params().addValue("refundPostingId", refundPostingId).addValue("ledgerId", ledgerId),
                (row, number) -> new RefundActiveBatch(row.getObject("id", UUID.class), row.getObject("accounting_journal_id", UUID.class),
                    row.getObject("business_date", LocalDate.class), row.getString("status")));
    }

    private RefundPostingPreparation refundBatchResult(Scope scope, UUID batchId) {
        var rows = jdbc.query("""
                SELECT batch.id,batch.accounting_journal_id,batch.row_version,journal.status
                FROM fee_accounting_posting_batches batch LEFT JOIN accounting_journals journal ON journal.id=batch.accounting_journal_id
                  AND journal.organisation_id=batch.organisation_id AND journal.school_id=batch.school_id
                WHERE batch.id=:batchId AND batch.organisation_id=:organisationId AND batch.school_id=:schoolId AND batch.event_type='REFUND'
                """, scope.params().addValue("batchId", batchId), (row, number) -> new RefundPostingPreparation(
                        row.getObject("id", UUID.class), row.getObject("accounting_journal_id", UUID.class), row.getString("status"), row.getLong("row_version")));
        if (rows.size() != 1 || rows.getFirst().journalId() == null || rows.getFirst().journalStatus() == null) throw refundAccountingInvalid();
        return rows.getFirst();
    }

    private static ApiException refundAccountingInvalid() {
        return conflict("FEE_REFUND_ACCOUNTING_SOURCE_INVALID", "The completed refund and original accounting source require review.");
    }

    private List<BatchRow> batchRows(Scope scope) {
        return jdbc.query("""
                SELECT batch.*,preparer_user.display_name AS preparer_name,
                       (SELECT count(*) FROM fee_accounting_posting_items item
                        WHERE item.posting_batch_id=batch.id
                          AND item.organisation_id=batch.organisation_id
                          AND item.school_id=batch.school_id
                          AND item.status='ACTIVE') AS active_source_count
                FROM fee_accounting_posting_batches batch
                JOIN memberships preparer ON preparer.id=batch.prepared_by_membership_id
                JOIN user_accounts preparer_user ON preparer_user.id=preparer.user_id
                WHERE batch.organisation_id=:organisationId AND batch.school_id=:schoolId
                ORDER BY batch.business_date DESC,batch.prepared_at DESC,batch.id
                LIMIT 100
                """, scope.params(), (result, row) -> new BatchRow(
                result.getObject("id", UUID.class),
                result.getObject("configuration_id", UUID.class),
                result.getObject("financial_year_id", UUID.class),
                result.getObject("accounting_period_id", UUID.class),
                result.getObject("business_date", LocalDate.class),
                result.getString("event_type"), result.getString("disposition"),
                result.getInt("sequence_number"), result.getInt("source_count"),
                result.getInt("active_source_count"), result.getBigDecimal("source_amount"),
                result.getObject("accounting_journal_id", UUID.class),
                result.getString("preparer_name"), instant(result, "prepared_at"),
                result.getLong("row_version")));
    }

    private static PostingBatchItem batchItem(BatchRow row, String journalStatus) {
        String reconciliation = "NO_GL_EFFECT".equals(row.disposition()) ? "NO_GL_EFFECT"
                : switch (journalStatus == null ? "MISSING" : journalStatus) {
                    case "POSTED" -> "RECONCILED";
                    case "DRAFT", "SUBMITTED" -> "AWAITING_APPROVAL";
                    case "REJECTED", "REVERSED" ->
                            row.activeSourceCount() == 0
                                    ? "REPREPARED" : "REQUIRES_REPREPARATION";
                    default -> "REQUIRES_REPREPARATION";
                };
        return new PostingBatchItem(row.id(), row.configurationId(), row.financialYearId(),
                row.periodId(), row.businessDate(), row.event(), row.disposition(),
                row.sequence(), row.sourceCount(), row.activeSourceCount(),
                row.sourceAmount(), row.journalId(),
                journalStatus, reconciliation, row.preparerName(), row.preparedAt(),
                row.version());
    }

    private List<UnpreparedEventSummary> unpreparedEvents(Scope scope) {
        Map<String, UnpreparedEventSummary> rows = jdbc.query("""
                SELECT ledger.entry_type,count(*) AS source_count,
                       COALESCE(sum(ledger.amount),0) AS source_amount
                FROM fee_ledger_entries ledger
                WHERE ledger.organisation_id=:organisationId AND ledger.school_id=:schoolId
                  AND ledger.entry_type IN ('CHARGE','CONCESSION','PAYMENT','PAYMENT_REVERSAL')
                  AND NOT EXISTS (
                    SELECT 1 FROM fee_accounting_posting_items item
                    WHERE item.organisation_id=ledger.organisation_id
                      AND item.school_id=ledger.school_id
                      AND item.fee_ledger_entry_id=ledger.id AND item.status='ACTIVE')
                GROUP BY ledger.entry_type
                """, scope.params(), result -> {
                    Map<String, UnpreparedEventSummary> values = new LinkedHashMap<>();
                    while (result.next()) {
                        values.put(result.getString("entry_type"),
                                new UnpreparedEventSummary(result.getString("entry_type"),
                                        result.getInt("source_count"),
                                        money(result.getBigDecimal("source_amount"))));
                    }
                    return values;
                });
        return EVENTS.stream().sorted().map(event -> rows.getOrDefault(event,
                new UnpreparedEventSummary(event, 0, new BigDecimal("0.00")))).toList();
    }

    private static int countStatus(List<PostingBatchItem> batches, String status) {
        return Math.toIntExact(batches.stream()
                .filter(batch -> status.equals(batch.reconciliationStatus())).count());
    }

    private ValidatedConfiguration validateConfigurationRequest(AuthenticatedUser user,
            Scope scope, ConfigurationRequest request, boolean decision) {
        validateMappingCoverage(scope, request);
        FeeBookContext book = decision
                ? accounting.validateFeeConfigurationDecision(user, scope.schoolId(),
                        request.financialYearId(), request.effectiveFrom(), List.of())
                : accounting.validateFeeConfigurationDraft(user, scope.schoolId(),
                        request.financialYearId(), request.effectiveFrom(), List.of());
        List<FeeAccountUse> uses = configurationAccountUses(request, book.accountingBasis());
        FeeBookContext validatedBook = decision
                ? accounting.validateFeeConfigurationDecision(user, scope.schoolId(),
                        request.financialYearId(), request.effectiveFrom(), uses)
                : accounting.validateFeeConfigurationDraft(user, scope.schoolId(),
                        request.financialYearId(), request.effectiveFrom(), uses);
        return new ValidatedConfiguration(validatedBook, uses);
    }

    private void validateMappingCoverage(Scope scope, ConfigurationRequest request) {
        Set<UUID> expectedHeads = new LinkedHashSet<>(jdbc.query("""
                SELECT id FROM fee_heads
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY id
                """, scope.params(), (result, row) -> result.getObject("id", UUID.class)));
        Set<UUID> submittedHeads = request.headMappings().stream()
                .map(HeadMappingRequest::feeHeadId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (expectedHeads.isEmpty() || submittedHeads.size() != request.headMappings().size()
                || !expectedHeads.equals(submittedHeads)) {
            throw bad("FEE_ACCOUNTING_HEAD_MAPPING_INCOMPLETE",
                    "Map every configured fee head exactly once.");
        }
        Set<String> submittedTenders = request.tenderMappings().stream()
                .map(item -> upper(item.paymentMethod()).replace(' ', '_'))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (submittedTenders.size() != request.tenderMappings().size()
                || !submittedTenders.equals(new LinkedHashSet<>(TENDERS))) {
            throw bad("FEE_ACCOUNTING_TENDER_MAPPING_INCOMPLETE",
                    "Map cash and all four bank/digital payment methods exactly once.");
        }
    }

    private static List<FeeAccountUse> configurationAccountUses(
            ConfigurationRequest request, String basis) {
        List<FeeAccountUse> uses = new ArrayList<>();
        if ("ACCRUAL".equals(basis)) {
            if (request.receivableAccountId() == null
                    || request.concessionAccountId() == null) {
                throw bad("FEE_ACCOUNTING_ACCRUAL_ACCOUNTS_REQUIRED",
                        "Accrual accounting requires receivable and concession accounts.");
            }
            uses.add(new FeeAccountUse(request.receivableAccountId(),
                    FeeAccountUseType.RECEIVABLE));
            uses.add(new FeeAccountUse(request.concessionAccountId(),
                    FeeAccountUseType.CONCESSION));
        } else if (request.receivableAccountId() != null
                || request.concessionAccountId() != null) {
            throw bad("FEE_ACCOUNTING_CASH_ACCOUNTS_INVALID",
                    "Cash-basis configuration does not use receivable or concession accounts.");
        }
        request.headMappings().forEach(mapping -> uses.add(new FeeAccountUse(
                mapping.incomeAccountId(), FeeAccountUseType.INCOME)));
        request.tenderMappings().forEach(mapping -> uses.add(new FeeAccountUse(
                mapping.depositAccountId(), tenderAccountUse(upper(mapping.paymentMethod())))));
        return List.copyOf(uses);
    }

    private List<FeeAccountUse> configurationAccountUses(Scope scope,
            StoredConfiguration stored) {
        List<FeeAccountUse> uses = new ArrayList<>();
        if ("ACCRUAL".equals(stored.basis())) {
            uses.add(new FeeAccountUse(stored.receivableId(),
                    FeeAccountUseType.RECEIVABLE));
            uses.add(new FeeAccountUse(stored.concessionId(),
                    FeeAccountUseType.CONCESSION));
        }
        jdbc.query("""
                SELECT income_account_id FROM fee_accounting_head_mappings
                WHERE configuration_id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId
                """, scope.params().addValue("id", stored.id()), result -> {
                    while (result.next()) {
                        uses.add(new FeeAccountUse(
                                result.getObject("income_account_id", UUID.class),
                                FeeAccountUseType.INCOME));
                    }
                });
        jdbc.query("""
                SELECT payment_method,deposit_account_id
                FROM fee_accounting_tender_mappings
                WHERE configuration_id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId
                """, scope.params().addValue("id", stored.id()), result -> {
                    while (result.next()) {
                        uses.add(new FeeAccountUse(
                                result.getObject("deposit_account_id", UUID.class),
                                tenderAccountUse(result.getString("payment_method"))));
                    }
                });
        return List.copyOf(uses);
    }

    private static FeeAccountUseType tenderAccountUse(String method) {
        return "CASH".equals(method) ? FeeAccountUseType.CASH
                : "ONLINE_PROVIDER".equals(method) ? FeeAccountUseType.ONLINE_TENDER : FeeAccountUseType.BANK;
    }

    private void validateRevision(Scope scope, ConfigurationRequest request,
            UUID editingId) {
        if (request.supersedesConfigurationId() == null) {
            if (request.revisionNumber() != 1) {
                throw bad("FEE_ACCOUNTING_REVISION_INVALID",
                        "A configuration without a predecessor must be revision 1.");
            }
            if (editingId != null) {
                return;
            }
            Integer approved = jdbc.queryForObject("""
                    SELECT count(*) FROM fee_accounting_configurations
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND financial_year_id=:financialYearId AND status='APPROVED'
                    """, scope.params().addValue("financialYearId",
                    request.financialYearId()), Integer.class);
            if (approved != null && approved > 0) {
                throw bad("FEE_ACCOUNTING_REVISION_INVALID",
                        "The first configuration is revision 1; later revisions must supersede the approved version.");
            }
            return;
        }
        List<RevisionSource> rows = jdbc.query("""
                SELECT revision_number FROM fee_accounting_configurations
                WHERE id=:supersedesId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND financial_year_id=:financialYearId
                  AND status='APPROVED'
                """, scope.params().addValue("supersedesId",
                request.supersedesConfigurationId()).addValue("financialYearId",
                request.financialYearId()), (result, row) ->
                new RevisionSource(result.getInt("revision_number")));
        if (rows.isEmpty()
                || request.revisionNumber() != rows.getFirst().revision() + 1) {
            throw bad("FEE_ACCOUNTING_REVISION_INVALID",
                    "A revision must supersede the current approved configuration and use the next number.");
        }
    }

    private MapSqlParameterSource configurationParams(Scope scope, UUID id,
            ConfigurationRequest request, FeeBookContext book, Instant now) {
        return scope.params().addValue("id", id)
                .addValue("financialYearId", request.financialYearId())
                .addValue("supersedesId", request.supersedesConfigurationId())
                .addValue("revision", request.revisionNumber())
                .addValue("effectiveFrom", request.effectiveFrom())
                .addValue("basis", book.accountingBasis())
                .addValue("currency", book.currencyCode())
                .addValue("receivableId", request.receivableAccountId())
                .addValue("concessionId", request.concessionAccountId())
                .addValue("reason", reason(request.reason())).addValue("now", now);
    }

    private void replaceMappings(Scope scope, UUID configurationId,
            ConfigurationRequest request, Instant now) {
        for (HeadMappingRequest mapping : request.headMappings()) {
            jdbc.update("""
                    INSERT INTO fee_accounting_head_mappings(id,organisation_id,school_id,
                      configuration_id,fee_head_id,income_account_id,created_at,updated_at,row_version)
                    VALUES(:mappingId,:organisationId,:schoolId,:configurationId,:feeHeadId,
                      :accountId,:now,:now,0)
                    """, scope.params().addValue("mappingId", UUID.randomUUID())
                    .addValue("configurationId", configurationId)
                    .addValue("feeHeadId", mapping.feeHeadId())
                    .addValue("accountId", mapping.incomeAccountId()).addValue("now", now));
        }
        for (TenderMappingRequest mapping : request.tenderMappings()) {
            jdbc.update("""
                    INSERT INTO fee_accounting_tender_mappings(id,organisation_id,school_id,
                      configuration_id,payment_method,deposit_account_id,created_at,updated_at,row_version)
                    VALUES(:mappingId,:organisationId,:schoolId,:configurationId,:method,
                      :accountId,:now,:now,0)
                    """, scope.params().addValue("mappingId", UUID.randomUUID())
                    .addValue("configurationId", configurationId)
                    .addValue("method", upper(mapping.paymentMethod()).replace(' ', '_'))
                    .addValue("accountId", mapping.depositAccountId()).addValue("now", now));
        }
    }

    private StoredConfiguration lockConfiguration(Scope scope, UUID id) {
        List<StoredConfiguration> rows = jdbc.query("""
                SELECT * FROM fee_accounting_configurations
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                FOR UPDATE
                """, scope.params().addValue("id", id), (result, row) ->
                storedConfiguration(result));
        if (rows.isEmpty()) {
            throw notFound("FEE_ACCOUNTING_CONFIGURATION_NOT_FOUND",
                    "The fee accounting configuration was not found.");
        }
        return rows.getFirst();
    }

    private EffectiveConfiguration effectiveConfiguration(Scope scope,
            UUID financialYearId, LocalDate businessDate) {
        List<EffectiveConfiguration> rows = jdbc.query("""
                SELECT * FROM fee_accounting_configurations
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND financial_year_id=:financialYearId
                  AND status IN ('APPROVED','SUPERSEDED')
                  AND effective_from<=:businessDate
                ORDER BY effective_from DESC,revision_number DESC LIMIT 1
                """, scope.params().addValue("financialYearId", financialYearId)
                .addValue("businessDate", businessDate), (result, row) -> {
                    UUID id = result.getObject("id", UUID.class);
                    return new EffectiveConfiguration(id,
                            result.getObject("financial_year_id", UUID.class),
                            result.getObject("effective_from", LocalDate.class),
                            result.getString("accounting_basis_snapshot"),
                            result.getString("currency_code"),
                            result.getObject("receivable_account_id", UUID.class),
                            result.getObject("concession_account_id", UUID.class),
                            feeHeadAccountMap(scope, id), tenderAccountMap(scope, id));
                });
        if (rows.isEmpty()) {
            throw conflict("FEE_ACCOUNTING_CONFIGURATION_REQUIRED",
                    "Approve a fee accounting configuration effective for this date first.");
        }
        return rows.getFirst();
    }

    private Map<UUID, UUID> feeHeadAccountMap(Scope scope, UUID configurationId) {
        return feeHeadAccountMap(scope.params(), configurationId);
    }

    private Map<UUID, UUID> feeHeadAccountMap(MapSqlParameterSource parameters, UUID configurationId) {
        return Map.copyOf(jdbc.query("""
                SELECT fee_head_id,income_account_id FROM fee_accounting_head_mappings
                WHERE configuration_id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId
                """, parameters.addValue("id", configurationId), result -> {
                    Map<UUID, UUID> mappings = new LinkedHashMap<>();
                    while (result.next()) {
                        mappings.put(result.getObject("fee_head_id", UUID.class),
                                result.getObject("income_account_id", UUID.class));
                    }
                    return mappings;
                }));
    }

    private Map<String, UUID> tenderAccountMap(Scope scope, UUID configurationId) {
        return tenderAccountMap(scope.params(), configurationId);
    }

    private Map<String, UUID> tenderAccountMap(MapSqlParameterSource parameters, UUID configurationId) {
        return Map.copyOf(jdbc.query("""
                SELECT payment_method,deposit_account_id
                FROM fee_accounting_tender_mappings
                WHERE configuration_id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId
                """, parameters.addValue("id", configurationId), result -> {
                    Map<String, UUID> mappings = new LinkedHashMap<>();
                    while (result.next()) {
                        mappings.put(result.getString("payment_method"),
                                result.getObject("deposit_account_id", UUID.class));
                    }
                    return mappings;
                }));
    }

    private void releaseRejectedSources(AuthenticatedUser user, Scope scope) {
        List<ReleasedBatchCandidate> candidates = jdbc.query("""
                SELECT DISTINCT batch.id,batch.accounting_journal_id
                FROM fee_accounting_posting_batches batch
                JOIN fee_accounting_posting_items item ON item.posting_batch_id=batch.id
                  AND item.organisation_id=batch.organisation_id
                  AND item.school_id=batch.school_id
                WHERE batch.organisation_id=:organisationId AND batch.school_id=:schoolId
                  AND batch.event_type<>'REFUND'
                  AND batch.accounting_journal_id IS NOT NULL AND item.status='ACTIVE'
                ORDER BY batch.id
                """, scope.params(), (result, row) -> new ReleasedBatchCandidate(
                result.getObject("id", UUID.class),
                result.getObject("accounting_journal_id", UUID.class)));
        if (candidates.isEmpty()) {
            return;
        }
        Map<UUID, String> statuses = accounting.feeJournalStatuses(user, scope.schoolId(),
                candidates.stream().map(ReleasedBatchCandidate::journalId).toList());
        List<UUID> releasable = candidates.stream()
                .filter(candidate -> Set.of("REJECTED", "REVERSED")
                        .contains(statuses.get(candidate.journalId())))
                .map(ReleasedBatchCandidate::batchId).toList();
        if (releasable.isEmpty()) {
            return;
        }
        jdbc.queryForObject("""
                SELECT set_config('app.fee_accounting_item_release','approved',true)
                """, new MapSqlParameterSource(), String.class);
        jdbc.update("""
                UPDATE fee_accounting_posting_items
                SET status='RELEASED',released_by_membership_id=:actorMembershipId,
                    released_at=:now,
                    release_reason='Source released after fee journal rejection or reversal',
                    updated_at=:now,row_version=row_version+1
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND posting_batch_id IN (:batchIds) AND status='ACTIVE'
                """, scope.params().addValue("batchIds", releasable)
                .addValue("now", Instant.now(clock)));
    }

    private List<SourceEntry> sourceEntries(Scope scope, LocalDate businessDate,
            String event, int maxEntries) {
        List<SourceEntry> sources = jdbc.query("""
                SELECT ledger.id,ledger.learner_fee_account_id,ledger.learner_fee_charge_id,
                       ledger.entry_type,ledger.direction,ledger.amount,ledger.effective_on,
                       ledger.description,ledger.source_type,ledger.source_id,
                       ledger.reverses_entry_id,ledger.created_at,fee_account.currency_code,
                       item.fee_head_id,head.code AS fee_head_code,
                       head.display_name AS fee_head_name,payment.payment_method,
                       payment.receipt_number
                FROM fee_ledger_entries ledger
                JOIN learner_fee_accounts fee_account
                  ON fee_account.id=ledger.learner_fee_account_id
                 AND fee_account.organisation_id=ledger.organisation_id
                 AND fee_account.school_id=ledger.school_id
                LEFT JOIN learner_fee_charges charge
                  ON charge.id=ledger.learner_fee_charge_id
                 AND charge.organisation_id=ledger.organisation_id
                 AND charge.school_id=ledger.school_id
                LEFT JOIN fee_plan_items item ON item.id=charge.fee_plan_item_id
                  AND item.organisation_id=charge.organisation_id
                  AND item.school_id=charge.school_id
                LEFT JOIN fee_heads head ON head.id=item.fee_head_id
                  AND head.organisation_id=item.organisation_id
                  AND head.school_id=item.school_id
                LEFT JOIN fee_payments payment ON payment.id=ledger.source_id
                  AND payment.organisation_id=ledger.organisation_id
                  AND payment.school_id=ledger.school_id
                  AND ledger.source_type IN ('FEE_PAYMENT','FEE_PAYMENT_REVERSAL')
                WHERE ledger.organisation_id=:organisationId AND ledger.school_id=:schoolId
                  AND ledger.effective_on=:businessDate AND ledger.entry_type=:event
                  AND NOT EXISTS (
                    SELECT 1 FROM fee_accounting_posting_items posting_item
                    WHERE posting_item.organisation_id=ledger.organisation_id
                      AND posting_item.school_id=ledger.school_id
                      AND posting_item.fee_ledger_entry_id=ledger.id
                      AND posting_item.status='ACTIVE')
                ORDER BY ledger.created_at,ledger.id
                LIMIT :maxEntries FOR UPDATE OF ledger SKIP LOCKED
                """, scope.params().addValue("businessDate", businessDate)
                .addValue("event", event).addValue("maxEntries", maxEntries),
                (result, row) -> sourceEntry(result));
        String expectedDirection = Set.of("CHARGE", "PAYMENT_REVERSAL").contains(event)
                ? "DEBIT" : "CREDIT";
        if (sources.stream().anyMatch(source -> !expectedDirection.equals(source.direction()))) {
            throw conflict("FEE_LEDGER_EVIDENCE_INVALID",
                    "A fee ledger event has an unexpected debit or credit direction.");
        }
        return List.copyOf(sources);
    }

    private List<AllocationEvidence> paymentAllocations(Scope scope,
            List<SourceEntry> sources) {
        Set<UUID> paymentIds = sources.stream()
                .filter(source -> Set.of("PAYMENT", "PAYMENT_REVERSAL")
                        .contains(source.event()))
                .map(SourceEntry::sourceId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT allocation.fee_payment_id,allocation.learner_fee_charge_id,
                       allocation.amount,item.fee_head_id,head.code AS fee_head_code,
                       head.display_name AS fee_head_name
                FROM fee_payment_allocations allocation
                JOIN learner_fee_charges charge ON charge.id=allocation.learner_fee_charge_id
                  AND charge.organisation_id=allocation.organisation_id
                  AND charge.school_id=allocation.school_id
                JOIN fee_plan_items item ON item.id=charge.fee_plan_item_id
                  AND item.organisation_id=charge.organisation_id
                  AND item.school_id=charge.school_id
                JOIN fee_heads head ON head.id=item.fee_head_id
                  AND head.organisation_id=item.organisation_id
                  AND head.school_id=item.school_id
                WHERE allocation.organisation_id=:organisationId
                  AND allocation.school_id=:schoolId
                  AND allocation.fee_payment_id IN (:paymentIds)
                ORDER BY allocation.fee_payment_id,item.sequence_number,
                         allocation.learner_fee_charge_id
                """, scope.params().addValue("paymentIds", paymentIds),
                (result, row) -> new AllocationEvidence(
                        result.getObject("fee_payment_id", UUID.class),
                        result.getObject("learner_fee_charge_id", UUID.class),
                        result.getObject("fee_head_id", UUID.class),
                        result.getString("fee_head_code"),
                        result.getString("fee_head_name"),
                        money(result.getBigDecimal("amount"))));
    }

    private PostingEvidence postingEvidence(EffectiveConfiguration configuration,
            FeeBookContext book, List<SourceEntry> sources,
            List<AllocationEvidence> allocations) {
        return postingEvidence(configuration, book.financialYearId(), book.accountingPeriodId(), sources, allocations);
    }

    private PostingEvidence postingEvidence(EffectiveConfiguration configuration,
            UUID financialYearId, UUID accountingPeriodId, List<SourceEntry> sources,
            List<AllocationEvidence> allocations) {
        Map<UUID, List<AllocationEvidence>> byPayment = allocations.stream()
                .collect(Collectors.groupingBy(AllocationEvidence::paymentId,
                        LinkedHashMap::new, Collectors.toList()));
        BigDecimal total = sources.stream().map(SourceEntry::amount)
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
        List<Map<String, Object>> sourceSnapshots = new ArrayList<>();
        for (SourceEntry source : sources) {
            if (!configuration.currency().equals(source.currency())) {
                throw conflict("FEE_ACCOUNTING_CURRENCY_MISMATCH",
                        "Every source fee account must use the accounting book currency.");
            }
            List<AllocationEvidence> sourceAllocations =
                    byPayment.getOrDefault(source.sourceId(), List.of());
            if (Set.of("PAYMENT", "PAYMENT_REVERSAL", "REFUND").contains(source.event())) {
                BigDecimal allocated = sourceAllocations.stream()
                        .map(AllocationEvidence::amount)
                        .reduce(new BigDecimal("0.00"), BigDecimal::add);
                if (allocated.compareTo(source.amount()) != 0) {
                    throw conflict("FEE_PAYMENT_ALLOCATION_MISMATCH",
                            "The payment allocation evidence does not equal the receipt amount.");
                }
            }
            if (Set.of("CHARGE", "CONCESSION").contains(source.event())
                    && source.feeHeadId() == null) {
                throw conflict("FEE_HEAD_EVIDENCE_MISSING",
                        "A charge or concession is missing its preserved fee-head evidence.");
            }
            sourceSnapshots.add(sourceSnapshot(source, sourceAllocations));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("configurationId", configuration.id());
        snapshot.put("financialYearId", financialYearId);
        snapshot.put("accountingPeriodId", accountingPeriodId);
        snapshot.put("accountingBasis", configuration.basis());
        snapshot.put("currencyCode", configuration.currency());
        snapshot.put("businessDate", sources.getFirst().effectiveOn());
        snapshot.put("eventType", sources.getFirst().event());
        snapshot.put("sourceCount", sources.size());
        snapshot.put("sourceAmount", total);
        snapshot.put("sources", sourceSnapshots);
        return new PostingEvidence(Map.copyOf(snapshot), money(total));
    }

    private static Map<String, Object> sourceSnapshot(SourceEntry source,
            List<AllocationEvidence> allocations) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("feeLedgerEntryId", source.id());
        snapshot.put("learnerFeeAccountId", source.feeAccountId());
        snapshot.put("learnerFeeChargeId", source.chargeId());
        snapshot.put("eventType", source.event());
        snapshot.put("direction", source.direction());
        snapshot.put("amount", source.amount());
        snapshot.put("effectiveOn", source.effectiveOn());
        snapshot.put("description", source.description());
        snapshot.put("sourceType", source.sourceType());
        snapshot.put("sourceId", source.sourceId());
        snapshot.put("reversesEntryId", source.reversesEntryId());
        snapshot.put("createdAt", source.createdAt());
        snapshot.put("currencyCode", source.currency());
        snapshot.put("feeHeadId", source.feeHeadId());
        snapshot.put("feeHeadCode", source.feeHeadCode());
        snapshot.put("feeHeadName", source.feeHeadName());
        snapshot.put("paymentMethod", source.paymentMethod());
        snapshot.put("receiptNumber", source.receiptNumber());
        snapshot.put("allocations", allocations.stream().map(allocation -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("learnerFeeChargeId", allocation.chargeId());
            value.put("feeHeadId", allocation.feeHeadId());
            value.put("feeHeadCode", allocation.feeHeadCode());
            value.put("feeHeadName", allocation.feeHeadName());
            value.put("amount", allocation.amount());
            return Map.copyOf(value);
        }).toList());
        return java.util.Collections.unmodifiableMap(snapshot);
    }

    private void insertPostingItems(Scope scope, UUID batchId,
            List<SourceEntry> sources, List<AllocationEvidence> allocations, Instant now) {
        Map<UUID, List<AllocationEvidence>> byPayment = allocations.stream()
                .collect(Collectors.groupingBy(AllocationEvidence::paymentId,
                        LinkedHashMap::new, Collectors.toList()));
        for (int index = 0; index < sources.size(); index++) {
            SourceEntry source = sources.get(index);
            String sourceJson = json(sourceSnapshot(source,
                    byPayment.getOrDefault(source.sourceId(), List.of())));
            jdbc.update("""
                    INSERT INTO fee_accounting_posting_items(id,organisation_id,school_id,
                      posting_batch_id,fee_ledger_entry_id,sequence_number,source_snapshot,
                      source_sha256,status,created_at,updated_at,row_version)
                    VALUES(:itemId,:organisationId,:schoolId,:batchId,:ledgerId,:sequence,
                      CAST(:snapshot AS jsonb),:snapshotHash,'ACTIVE',:now,:now,0)
                    """, scope.params().addValue("itemId", UUID.randomUUID())
                    .addValue("batchId", batchId).addValue("ledgerId", source.id())
                    .addValue("sequence", index + 1).addValue("snapshot", sourceJson)
                    .addValue("snapshotHash", sha256(sourceJson)).addValue("now", now));
        }
    }

    static List<FeeJournalLine> journalLines(EffectiveConfiguration configuration,
            String event, List<SourceEntry> sources,
            List<AllocationEvidence> allocations) {
        Map<UUID, List<AllocationEvidence>> byPayment = allocations.stream()
                .collect(Collectors.groupingBy(AllocationEvidence::paymentId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<LineKey, BigDecimal> totals = new LinkedHashMap<>();
        for (SourceEntry source : sources) {
            UUID tender = source.paymentMethod() == null ? null
                    : requiredMapping(configuration.tenderAccounts(),
                            source.paymentMethod(), "payment method");
            switch (configuration.basis() + ":" + event) {
                case "ACCRUAL:CHARGE" -> {
                    addLine(totals, configuration.receivableId(),
                            source.feeAccountId(), true, source.amount());
                    addLine(totals, requiredMapping(configuration.incomeAccounts(),
                            source.feeHeadId(), "fee head"), null, false, source.amount());
                }
                case "ACCRUAL:CONCESSION" -> {
                    addLine(totals, configuration.concessionId(), null,
                            true, source.amount());
                    addLine(totals, configuration.receivableId(),
                            source.feeAccountId(), false, source.amount());
                }
                case "ACCRUAL:PAYMENT" -> {
                    addLine(totals, tender, null, true, source.amount());
                    addLine(totals, configuration.receivableId(),
                            source.feeAccountId(), false, source.amount());
                }
                case "ACCRUAL:PAYMENT_REVERSAL", "ACCRUAL:REFUND" -> {
                    addLine(totals, configuration.receivableId(),
                            source.feeAccountId(), true, source.amount());
                    addLine(totals, tender, null, false, source.amount());
                }
                case "CASH:PAYMENT" -> {
                    addLine(totals, tender, null, true, source.amount());
                    for (AllocationEvidence allocation :
                            byPayment.getOrDefault(source.sourceId(), List.of())) {
                        addLine(totals, requiredMapping(configuration.incomeAccounts(),
                                allocation.feeHeadId(), "fee head"), null, false,
                                allocation.amount());
                    }
                }
                case "CASH:PAYMENT_REVERSAL", "CASH:REFUND" -> {
                    for (AllocationEvidence allocation :
                            byPayment.getOrDefault(source.sourceId(), List.of())) {
                        addLine(totals, requiredMapping(configuration.incomeAccounts(),
                                allocation.feeHeadId(), "fee head"), null, true,
                                allocation.amount());
                    }
                    addLine(totals, tender, null, false, source.amount());
                }
                default -> throw conflict("FEE_POSTING_RULE_UNAVAILABLE",
                        "The accounting basis does not support this fee event.");
            }
        }
        if (totals.size() < 2 || totals.size() > 500) {
            throw conflict("FEE_POSTING_LINE_LIMIT",
                    "Prepare a smaller batch so the journal contains 2 to 500 lines.");
        }
        String memo = "Fee " + event.toLowerCase(Locale.ROOT).replace('_', ' ');
        return totals.entrySet().stream().map(entry -> new FeeJournalLine(
                entry.getKey().accountId(), entry.getKey().feeAccountId(),
                entry.getKey().debit() ? entry.getValue() : new BigDecimal("0.00"),
                entry.getKey().debit() ? new BigDecimal("0.00") : entry.getValue(),
                memo)).toList();
    }

    private static void addLine(Map<LineKey, BigDecimal> totals, UUID accountId,
            UUID feeAccountId, boolean debit, BigDecimal amount) {
        if (accountId == null) {
            throw conflict("FEE_ACCOUNT_MAPPING_MISSING",
                    "The effective configuration is missing a required account mapping.");
        }
        totals.merge(new LineKey(accountId, feeAccountId, debit), money(amount),
                BigDecimal::add);
    }

    private static <K> UUID requiredMapping(Map<K, UUID> mappings, K key,
            String label) {
        UUID accountId = mappings.get(key);
        if (accountId == null) {
            throw conflict("FEE_ACCOUNT_MAPPING_MISSING",
                    "The effective configuration does not map this " + label + ".");
        }
        return accountId;
    }

    private int nextBatchSequence(Scope scope, UUID configurationId,
            LocalDate businessDate, String event) {
        Integer current = jdbc.queryForObject("""
                SELECT COALESCE(max(sequence_number),0)
                FROM fee_accounting_posting_batches
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND configuration_id=:configurationId
                  AND business_date=:businessDate AND event_type=:event
                """, scope.params().addValue("configurationId", configurationId)
                .addValue("businessDate", businessDate).addValue("event", event),
                Integer.class);
        int next = (current == null ? 0 : current) + 1;
        if (next > 1000) {
            throw conflict("FEE_POSTING_SEQUENCE_EXHAUSTED",
                    "The daily fee posting sequence is exhausted.");
        }
        return next;
    }

    private ExistingBatch existingBatch(Scope scope, String idempotencyKey) {
        List<ExistingBatch> rows = jdbc.query("""
                SELECT id,request_fingerprint,row_version
                FROM fee_accounting_posting_batches
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND idempotency_key=:key
                """, scope.params().addValue("key", idempotencyKey),
                (result, row) -> new ExistingBatch(
                        result.getObject("id", UUID.class),
                        result.getString("request_fingerprint"),
                        result.getLong("row_version")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String voucherType(String event) {
        return switch (event) {
            case "PAYMENT" -> "RECEIPT";
            case "PAYMENT_REVERSAL" -> "PAYMENT";
            default -> "JOURNAL";
        };
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId,
            String capability) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED",
                    "Select a school organisation first.");
        }
        List<Scope> rows = jdbc.query("""
                SELECT school.id AS school_id,school.display_name,school.time_zone,
                       membership.id AS membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id
                      AND rc.capability_code='fees.close.propose')
                  AND EXISTS(SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id
                      AND rc.capability_code='accounting.configure') can_configure,
                  EXISTS(SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id
                      AND rc.capability_code='fees.close.propose')
                  AND EXISTS(SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id
                      AND rc.capability_code='accounting.prepare') can_prepare,
                  EXISTS(SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id
                      AND rc.capability_code='fees.close.approve')
                  AND EXISTS(SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id
                      AND rc.capability_code='accounting.post') can_approve
                FROM memberships membership
                JOIN schools school ON school.id=membership.school_id
                WHERE membership.user_id=:userId
                  AND membership.organisation_id=:organisationId
                  AND membership.id IN (:membershipIds)
                  AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL
                       OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND (CAST(:schoolId AS uuid) IS NULL
                       OR membership.school_id=:schoolId)
                  AND EXISTS(SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id
                      AND rc.capability_code=:capability)
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds())
                .addValue("schoolId", requestedSchoolId)
                .addValue("capability", capability), (result, row) -> new Scope(
                user.activeOrganisationId(), result.getObject("school_id", UUID.class),
                result.getString("display_name"),
                result.getObject("membership_id", UUID.class),
                result.getBoolean("can_configure"), result.getBoolean("can_prepare"),
                result.getBoolean("can_approve"), result.getString("time_zone")));
        if (rows.isEmpty()) {
            throw forbidden("FEE_ACCOUNTING_SCOPE_DENIED",
                    "Fee accounting access is unavailable for this school.");
        }
        return rows.getFirst();
    }

    private LocalDate today(Scope scope) {
        String configuredZone = scope.timeZone();
        if (configuredZone == null || configuredZone.isBlank() || configuredZone.length() > 60) {
            throw conflict("FEE_ACCOUNTING_TIME_ZONE_INVALID",
                    "Configure a valid school time zone before preparing fee accounting.");
        }
        try {
            return clock.instant().atZone(ZoneId.of(configuredZone)).toLocalDate();
        } catch (DateTimeException invalid) {
            throw conflict("FEE_ACCOUNTING_TIME_ZONE_INVALID",
                    "Configure a valid school time zone before preparing fee accounting.");
        }
    }

    private void lockFinance(Scope scope) {
        jdbc.queryForObject("""
                SELECT 1 FROM (
                  SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))
                ) locked
                """, scope.params().addValue("lockKey",
                "finance:school:" + scope.schoolId()), Integer.class);
    }

    private void audit(AuthenticatedUser user, Scope scope, String action,
            String subjectType, UUID subjectId, String reason) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action,
                subjectType, subjectId.toString(), AuditOutcome.SUCCEEDED,
                clean(reason), Map.of());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "FEE_ACCOUNTING_EVIDENCE_FAILED",
                    "The immutable fee accounting evidence could not be prepared.");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static StoredConfiguration storedConfiguration(java.sql.ResultSet result)
            throws java.sql.SQLException {
        return new StoredConfiguration(result.getObject("id", UUID.class),
                result.getObject("financial_year_id", UUID.class),
                result.getObject("supersedes_configuration_id", UUID.class),
                result.getInt("revision_number"),
                result.getObject("effective_from", LocalDate.class),
                result.getString("accounting_basis_snapshot"),
                result.getString("currency_code"),
                result.getObject("receivable_account_id", UUID.class),
                result.getObject("concession_account_id", UUID.class),
                result.getString("status"),
                result.getObject("created_by_membership_id", UUID.class),
                result.getLong("row_version"));
    }

    private static SourceEntry sourceEntry(java.sql.ResultSet result) throws java.sql.SQLException {
        return sourceEntry(result, false);
    }

    private static SourceEntry sourceEntry(java.sql.ResultSet result, boolean proof) throws java.sql.SQLException {
        return new SourceEntry(result.getObject("id", UUID.class),
                result.getObject("learner_fee_account_id", UUID.class), result.getObject("learner_fee_charge_id", UUID.class),
                result.getString("entry_type"), result.getString("direction"),
                proof ? proofMoney(result.getBigDecimal("amount"), false) : money(result.getBigDecimal("amount")),
                result.getObject("effective_on", LocalDate.class), result.getString("description"), result.getString("source_type"),
                result.getObject("source_id", UUID.class), result.getObject("reverses_entry_id", UUID.class), instant(result, "created_at"),
                result.getString("currency_code"), result.getObject("fee_head_id", UUID.class), result.getString("fee_head_code"),
                result.getString("fee_head_name"), result.getString("payment_method"), result.getString("receipt_number"));
    }

    private static Instant instant(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static long requiredVersion(Long value) {
        if (value == null || value < 0) {
            throw bad("EXPECTED_VERSION_REQUIRED",
                    "Refresh the draft and include its current version.");
        }
        return value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? new BigDecimal("0.00") : value.setScale(2);
    }

    private static String idempotencyKey(String value) {
        String key = required(value);
        if (key.length() < 8 || key.length() > 100
                || key.chars().anyMatch(Character::isISOControl)) {
            throw bad("IDEMPOTENCY_KEY_INVALID",
                    "Use a retry key between 8 and 100 visible characters.");
        }
        return key;
    }

    private static String enumValue(String value, Set<String> allowed,
            String label) {
        String normalized = upper(value).replace(' ', '_');
        if (!allowed.contains(normalized)) {
            throw bad("INVALID_VALUE", "Choose a supported " + label + ".");
        }
        return normalized;
    }

    private static String upper(String value) {
        return required(value).toUpperCase(Locale.ROOT);
    }

    private static String reason(String value) {
        String reason = required(value);
        if (reason.length() < 3 || reason.length() > 500) {
            throw bad("REASON_INVALID", "Enter a reason between 3 and 500 characters.");
        }
        return reason;
    }

    private static String required(String value) {
        String result = clean(value);
        if (result == null) {
            throw bad("VALUE_REQUIRED", "Complete all required fields.");
        }
        return result;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null
                : value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    private static void changed(int count) {
        if (count != 1) {
            throw conflict("STALE_VERSION",
                    "The record changed. Refresh and try again.");
        }
    }

    private static int safe(String code, Work<Integer> work) {
        try {
            return work.run();
        } catch (DataIntegrityViolationException exception) {
            throw conflict(code,
                    "The operation conflicts with protected fee accounting history.");
        }
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    /** Read coordinates only: deliberately carries no actor, membership or capabilities. */
    private record OriginalSourceScope(UUID organisationId, UUID schoolId) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId).addValue("schoolId", schoolId);
        }
    }

    private record Scope(UUID organisationId, UUID schoolId, String schoolName,
            UUID actorMembershipId, boolean canConfigure, boolean canPrepare,
            boolean canApprove, String timeZone) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId)
                    .addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record ValidatedConfiguration(FeeBookContext book,
            List<FeeAccountUse> accountUses) {}

    record OriginalPaymentPostingProof(UUID paymentId, UUID feeLedgerEntryId, UUID postingItemId, UUID postingBatchId,
            UUID configurationId, UUID accountingJournalId, UUID originalFinancialYearId, UUID originalAccountingPeriodId,
            LocalDate originalPostingDate, String accountingBasis, String currencyCode, UUID depositAccountId,
            UUID receivableAccountId, Map<UUID, UUID> incomeAccounts, List<OriginalPaymentAllocation> allocations,
            String sourceSha256, String batchEvidenceSha256) {
        OriginalPaymentPostingProof {
            Objects.requireNonNull(depositAccountId);
            incomeAccounts = Map.copyOf(incomeAccounts);
            allocations = List.copyOf(allocations);
        }
        @Override public String toString() { return "OriginalPaymentPostingProof[private immutable historical source proof; not admission]"; }
    }

    record OriginalPaymentAllocation(UUID allocationId, UUID chargeId, UUID feeHeadId, BigDecimal amount) {
        @Override public String toString() { return "OriginalPaymentAllocation[private original allocation]"; }
    }

    record OriginalPaymentProofBatch(Map<UUID,OriginalPaymentPostingProof> proofs, Set<UUID> invalidPaymentIds) {
        OriginalPaymentProofBatch {
            proofs = Map.copyOf(proofs); invalidPaymentIds = Set.copyOf(invalidPaymentIds);
            if (proofs.size()+invalidPaymentIds.size()>1000 || !java.util.Collections.disjoint(proofs.keySet(), invalidPaymentIds))
                throw new IllegalArgumentException("Disjoint bounded original-payment proof results required");
        }
        @Override public String toString() { return "OriginalPaymentProofBatch[private invocation-local historical proof outcomes]"; }
    }

    private record VerifiedOriginalPaymentBatch(OriginalPaymentTarget header, EffectiveConfiguration configuration,
            Map<UUID,OriginalPostingItem> items, Map<UUID,List<OriginalPaymentAllocation>> allocations) {
        VerifiedOriginalPaymentBatch { items = Map.copyOf(items); allocations = Map.copyOf(allocations); }
        @Override public String toString() { return "VerifiedOriginalPaymentBatch[private invocation-local complete batch evidence]"; }
    }

    /**
     * clearingAccountId preserves the original ONLINE_PROVIDER deposit mapping;
     * the name does not grant clearing eligibility. Legacy BANK mappings remain
     * historical evidence. Settlement admission must separately prove ASSET/OTHER
     * identity, live account authority and absence of pending/actual reversal.
     */
    record CompletedRefundPostingProof(UUID refundPostingId, UUID refundId, UUID paymentId, UUID feeLedgerEntryId,
            UUID postingItemId, UUID postingBatchId, UUID configurationId, UUID accountingJournalId, UUID clearingAccountId,
            UUID financialYearId, UUID accountingPeriodId, LocalDate postingOn, String accountingBasis, String currencyCode,
            BigDecimal refundAmount, OriginalPaymentPostingProof originalPaymentProof, String sourceSha256, String batchEvidenceSha256) {
        @Override public String toString() { return "CompletedRefundPostingProof[private historical refund source; not admission or association]"; }
    }

    private record CompletedRefundBatch(UUID id, UUID itemId, UUID configurationId, UUID yearId, UUID periodId,
            LocalDate postingOn, String event, String disposition, int sourceCount, BigDecimal sourceAmount, UUID journalId,
            String snapshot, String evidenceSha256, UUID refundPostingId, UUID originalItemId, UUID previousBatchId,
            UUID previousJournalId, String sourceSha256) {
        @Override public String toString() { return "CompletedRefundBatch[private immutable refund source]"; }
    }
    private record CompletedRefundItem(UUID id, UUID ledgerId, int sequence, String status, String snapshot, String sourceSha256) {
        @Override public String toString() { return "CompletedRefundItem[private immutable refund item]"; }
    }
    private record CompletedRefundAllocation(UUID originalAllocationId, UUID chargeId, UUID headId, BigDecimal amount) {
        @Override public String toString() { return "CompletedRefundAllocation[private immutable allocation]"; }
    }

    private record OriginalPaymentTarget(UUID paymentId, UUID ledgerId, UUID itemId, UUID batchId, UUID configurationId,
            UUID journalId, UUID yearId, UUID periodId, LocalDate businessDate, String event, String disposition,
            int sourceCount, BigDecimal sourceAmount, String evidenceSnapshot, String evidenceSha256, String sourceSha256,
            String receiptStatus, String paymentMethod, BigDecimal receiptAmount, UUID accountId, String currency) {
        @Override public String toString() { return "OriginalPaymentTarget[private original source]"; }
    }
    private record OriginalPostingItem(UUID id, int sequence, String status, String sourceSnapshot, String sourceSha256, SourceEntry source) {
        @Override public String toString() { return "OriginalPostingItem[private immutable source]"; }
    }
    private record OriginalAllocationRow(UUID id, UUID paymentId, UUID chargeId, UUID feeHeadId, UUID accountId, BigDecimal amount) {
        @Override public String toString() { return "OriginalAllocationRow[private original allocation]"; }
    }
    private record OriginalJournalDimension(UUID accountId, UUID vendorId, UUID learnerFeeAccountId) {
        @Override public String toString() { return "OriginalJournalDimension[private accounting dimensions]"; }
    }
    private record OriginalJournalAmounts(BigDecimal debit, BigDecimal credit) {
        @Override public String toString() { return "OriginalJournalAmounts[private accounting amounts]"; }
    }

    private record StoredConfiguration(UUID id, UUID financialYearId,
            UUID supersedesId, int revision, LocalDate effectiveFrom, String basis,
            String currency, UUID receivableId, UUID concessionId, String status,
            UUID creatorId, long version) {}

    record EffectiveConfiguration(UUID id, UUID financialYearId,
            LocalDate effectiveFrom, String basis, String currency,
            UUID receivableId, UUID concessionId, Map<UUID, UUID> incomeAccounts,
            Map<String, UUID> tenderAccounts) {}

    private record RevisionSource(int revision) {}

    private record BatchRow(UUID id, UUID configurationId, UUID financialYearId,
            UUID periodId, LocalDate businessDate, String event, String disposition,
            int sequence, int sourceCount, int activeSourceCount, BigDecimal sourceAmount,
            UUID journalId,
            String preparerName, Instant preparedAt, long version) {}

    private record ReleasedBatchCandidate(UUID batchId, UUID journalId) {}

    record SourceEntry(UUID id, UUID feeAccountId, UUID chargeId,
            String event, String direction, BigDecimal amount, LocalDate effectiveOn,
            String description, String sourceType, UUID sourceId, UUID reversesEntryId,
            Instant createdAt, String currency, UUID feeHeadId, String feeHeadCode,
            String feeHeadName, String paymentMethod, String receiptNumber) {}

    record AllocationEvidence(UUID paymentId, UUID chargeId, UUID feeHeadId,
            String feeHeadCode, String feeHeadName, BigDecimal amount) {}

    record RefundPostingPreparation(UUID postingBatchId, UUID journalId, String journalStatus, long rowVersion) {
        @Override public String toString() { return "RefundPostingPreparation[private common accounting outcome]"; }
    }
    record RefundAllocation(UUID originalAllocationId, AllocationEvidence value) {}
    private record RefundPostingSource(UUID id, UUID refundId, UUID paymentId, UUID accountId,
            UUID originalItemId, LocalDate postedOn, SourceEntry source) {}
    private record RefundActiveBatch(UUID id, UUID journalId, LocalDate postingOn, String status) {}

    private record PostingEvidence(Map<String, Object> snapshot, BigDecimal total) {}

    private record LineKey(UUID accountId, UUID feeAccountId, boolean debit) {}

    private record ExistingBatch(UUID id, String fingerprint, long version) {}

    @FunctionalInterface
    private interface Work<T> {
        T run();
    }
}

package com.educore.api.finance;

import com.educore.api.accounting.AccountingModuleBridge;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Records an already verified return of money; never dispatches a provider command. */
@Service
public class GatewayRefundPostingService {
    private final NamedParameterJdbcTemplate jdbc;
    private final FinanceService finance;
    private final FeeAccountingService feeAccounting;
    private final AccountingModuleBridge accounting;
    private final SecurityAuditService audit;
    private final Clock clock;

    public GatewayRefundPostingService(NamedParameterJdbcTemplate jdbc, FinanceService finance,
            FeeAccountingService feeAccounting, AccountingModuleBridge accounting, SecurityAuditService audit, Clock clock) {
        this.jdbc = jdbc; this.finance = finance; this.feeAccounting = feeAccounting;
        this.accounting = accounting; this.audit = audit; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GatewayRefundPostingModels.Context inspect(AuthenticatedUser user, UUID schoolId, UUID refundId) {
        requireContext(user, false);
        var scope = finance.requireRefundReadScope(user, schoolId);
        Source source = source(scope, refundId);
        var previous = posted(scope, refundId);
        var retained = projection(user, scope, previous);
        boolean canPrepare = scope.canReverse() && canPrepareAccounting(user, scope);
        String state = previous != null ? "POSTED" : source.state();
        return new GatewayRefundPostingModels.Context(schoolId, refundId, source.paymentId(), source.receiptNumber(),
                source.accountId(), source.learnerName(), source.sessionName(), source.accountStatus(), source.currency(),
                source.balance(), source.amount(), state, source.processedExact(), source.processedOn(), source.timeZone(),
                source.closedDayId() != null, previous == null && "READY".equals(state) && "ACTIVE".equals(source.accountStatus())
                    && canPrepare, retained, retained != null && canPrepare
                    && List.of("REJECTED", "REVERSED").contains(retained.journalStatus()));
    }

    /** Accounting-only successor; it cannot call a provider or append another learner debit. */
    @Transactional
    public GatewayRefundPostingModels.JournalRepair repairJournal(AuthenticatedUser user, UUID refundId,
            GatewayRefundPostingModels.JournalRepairCommand command) {
        requireContext(user, true);
        if (refundId == null || command == null || command.schoolId() == null || command.clientRequestId() == null
                || command.expectedBatchId() == null || command.expectedJournalId() == null
                || command.financialYearId() == null || command.postingOn() == null) throw invalid();
        String reason = GatewayRefundTerms.reason(command.reason());
        var scope = finance.requireRefundWriteScope(user, command.schoolId());
        if (!canPrepareAccounting(user, scope)) throw denied();
        var completion = posted(scope, refundId);
        if (completion == null) throw missing();
        var result = feeAccounting.reprepareRefundPosting(user, scope.schoolId(), completion.id(),
                command.financialYearId(), command.postingOn(), command.clientRequestId(),
                command.expectedBatchId(), command.expectedJournalId(), reason);
        return new GatewayRefundPostingModels.JournalRepair(completion.id(), refundId,
                command.expectedBatchId(), command.expectedJournalId(), result.postingBatchId(),
                result.journalId(), result.journalStatus(), command.financialYearId(), command.postingOn());
    }

    @Transactional
    public GatewayRefundPostingModels.Posting post(AuthenticatedUser user, UUID refundId, GatewayRefundPostingModels.Command command) {
        requireContext(user, true);
        if (refundId == null || command == null || command.schoolId() == null || command.clientRequestId() == null
                || command.financialYearId() == null || command.postingOn() == null) throw invalid();
        String reason = GatewayRefundTerms.reason(command.reason());
        var scope = finance.requireRefundWriteScope(user, command.schoolId());
        if (!canPrepareAccounting(user, scope)) throw denied();
        String fingerprint = fingerprint(refundId, command.financialYearId(), command.postingOn(), reason);
        var prior = replay(scope, user.userId(), command.clientRequestId());
        if (prior != null) return exactReplay(user, scope, prior, refundId, fingerprint);

        Source source = source(scope, refundId);
        if (source.captureId() == null) throw unavailable();
        var p = scope.params().addValue("captureId", source.captureId()).addValue("accountId", source.accountId());
        jdbc.query("SELECT touch_gateway_refund_fence(:captureId,:organisationId,:schoolId)", p, row -> {});
        jdbc.query("SELECT touch_refund_account_reopen_fence(:accountId,:organisationId,:schoolId)", p, row -> {});
        // Epoch writes make stale stronger-isolation snapshots fail rather than
        // overlook another completion, account transition or conflicting read.
        source = source(scope, refundId);
        prior = replay(scope, user.userId(), command.clientRequestId());
        if (prior != null) return exactReplay(user, scope, prior, refundId, fingerprint);
        if (posted(scope, refundId) != null) throw conflict("REFUND_ALREADY_POSTED", "This refund has a retained financial posting. Refresh its history.");
        if (!"READY".equals(source.state()) || !"ACTIVE".equals(source.accountStatus()) || source.processedOn() == null
                || command.postingOn().isBefore(source.processedOn()) || command.postingOn().isBefore(source.receiptPostedOn())
                || command.postingOn().isAfter(FinanceService.businessDate(clock, source.timeZone()))) throw unavailable();
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_day_closes WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='APPROVED' AND business_date>=:postingOn)
                """, p.addValue("postingOn", command.postingOn()), Boolean.class))) throw unavailable();

        var original = feeAccounting.verifyOriginalPaymentPosting(user, scope.schoolId(), source.paymentId());
        var uses = originalAccounts(scope, refundId, original);
        accounting.validateFeeRefundPosting(user, scope.schoolId(), original.accountingJournalId(), command.financialYearId(), command.postingOn(), uses);
        // Admission holds the accounting school lock. Reprove the complete source
        // after waiting, never treat the earlier historical read as authority.
        original = feeAccounting.verifyOriginalPaymentPosting(user, scope.schoolId(), source.paymentId());
        UUID id = UUID.randomUUID(), ledgerId = UUID.randomUUID();
        p.addValue("id", id).addValue("refundId", refundId).addValue("paymentId", source.paymentId())
                .addValue("originalItemId", original.postingItemId()).addValue("ledgerId", ledgerId)
                .addValue("readId", source.readId()).addValue("hash", source.hash()).addValue("sourceVersion", source.version())
                .addValue("amount", source.amount()).addValue("currency", source.currency()).addValue("actor", user.userId())
                .addValue("clientId", command.clientRequestId()).addValue("fingerprint", fingerprint).addValue("reason", reason);
        // The matching ledger FK is deferred. Completion exists before the
        // source-backed ledger guard runs; both and the journal commit atomically.
        if (jdbc.update("""
                INSERT INTO fee_gateway_refund_postings(id,organisation_id,school_id,refund_request_id,fee_payment_id,
                  learner_fee_account_id,original_payment_posting_item_id,fee_ledger_entry_id,verified_read_id,
                  success_evidence_sha256,source_version,amount,currency_code,posted_on,posted_by_user_id,
                  posted_by_membership_id,posting_reason,client_request_id,request_fingerprint)
                VALUES(:id,:organisationId,:schoolId,:refundId,:paymentId,:accountId,:originalItemId,:ledgerId,:readId,
                  :hash,:sourceVersion,:amount,:currency,:postingOn,:actor,:actorMembershipId,:reason,:clientId,:fingerprint)
                """, p) != 1) throw unavailable();
        if (jdbc.update("""
                INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,entry_type,direction,
                  amount,effective_on,description,source_type,source_id,created_by_membership_id,created_at,actor_kind)
                VALUES(:ledgerId,:organisationId,:schoolId,:accountId,'REFUND','DEBIT',:amount,:postingOn,
                  'Verified online refund; original fee obligation restored','GATEWAY_REFUND_POSTING',:id,
                  :actorMembershipId,clock_timestamp(),'HUMAN')
                """, p) != 1) throw unavailable();
        feeAccounting.prepareRefundPosting(user, scope.schoolId(), id, command.financialYearId(), command.postingOn(), reason);
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), "finance.gateway.refund.posted",
                "GATEWAY_REFUND_POSTING", id.toString(), AuditOutcome.SUCCEEDED, reason,
                Map.of("refundId", refundId, "paymentId", source.paymentId(), "accountId", source.accountId(), "ledgerEntryId", ledgerId));
        var result = posted(scope, refundId);
        if (result == null) throw unavailable();
        return projection(user, scope, result);
    }

    private List<AccountingModuleBridge.FeeAccountUse> originalAccounts(FinanceService.Scope scope, UUID refundId,
            FeeAccountingService.OriginalPaymentPostingProof proof) {
        var result = new ArrayList<AccountingModuleBridge.FeeAccountUse>();
        result.add(new AccountingModuleBridge.FeeAccountUse(proof.depositAccountId(), AccountingModuleBridge.FeeAccountUseType.ONLINE_CLEARING));
        if ("ACCRUAL".equals(proof.accountingBasis())) {
            result.add(new AccountingModuleBridge.FeeAccountUse(proof.receivableAccountId(), AccountingModuleBridge.FeeAccountUseType.RECEIVABLE));
        } else {
            var selectedHeads = jdbc.query("""
                    SELECT DISTINCT fee_head_id FROM fee_gateway_refund_allocations
                    WHERE refund_request_id=:refundId AND organisation_id=:organisationId AND school_id=:schoolId
                    ORDER BY fee_head_id LIMIT 101
                    """, scope.params().addValue("refundId", refundId), (row, index) -> row.getObject("fee_head_id", UUID.class));
            if (selectedHeads.isEmpty() || selectedHeads.size() > 100) throw unavailable();
            selectedHeads.stream().map(head -> {
                UUID account = proof.incomeAccounts().get(head);
                if (account == null) throw unavailable();
                return account;
            }).distinct().forEach(id -> result.add(new AccountingModuleBridge.FeeAccountUse(id, AccountingModuleBridge.FeeAccountUseType.INCOME)));
        }
        return List.copyOf(result);
    }

    private Source source(FinanceService.Scope scope, UUID refundId) {
        if (refundId == null) throw missing();
        var rows = jdbc.query("""
                SELECT request.fee_payment_id,request.capture_id,account.id AS account_id,account.status AS account_status,
                  payment.receipt_number,payment.received_on AS receipt_posted_on,learner.legal_name,session.name AS session_name,account.currency_code,
                  success.amount,success.verified_read_id,success.evidence_sha256,success.source_version,
                  success.processed_at_exact,success.processed_on,success.school_time_zone,success.closed_day_close_id,
                  CASE WHEN success.refund_request_id IS NULL THEN 'NO_VERIFIED_SUCCESS'
                    WHEN refund_account_reopening_source_ready(request.id,request.organisation_id,request.school_id) THEN 'READY'
                    ELSE 'REVIEW_REQUIRED' END AS source_state,
                  COALESCE((SELECT sum(CASE ledger.direction WHEN 'DEBIT' THEN ledger.amount ELSE -ledger.amount END)
                    FROM fee_ledger_entries ledger WHERE ledger.learner_fee_account_id=account.id
                      AND ledger.organisation_id=account.organisation_id AND ledger.school_id=account.school_id),0) AS signed_balance
                FROM fee_gateway_refund_requests request
                JOIN fee_payments payment ON payment.id=request.fee_payment_id AND payment.organisation_id=request.organisation_id AND payment.school_id=request.school_id
                JOIN learner_fee_accounts account ON account.id=request.learner_fee_account_id AND account.organisation_id=request.organisation_id AND account.school_id=request.school_id
                JOIN learners learner ON learner.id=account.learner_id AND learner.organisation_id=account.organisation_id AND learner.school_id=account.school_id
                JOIN academic_sessions session ON session.id=account.academic_session_id AND session.organisation_id=account.organisation_id AND session.school_id=account.school_id
                LEFT JOIN fee_gateway_refund_successes success ON success.refund_request_id=request.id AND success.organisation_id=request.organisation_id AND success.school_id=request.school_id
                WHERE request.id=:refundId AND request.organisation_id=:organisationId AND request.school_id=:schoolId
                """, scope.params().addValue("refundId", refundId), (row, index) -> new Source(row.getObject("fee_payment_id", UUID.class), row.getObject("capture_id", UUID.class),
                    row.getObject("account_id", UUID.class), row.getString("account_status"), row.getString("receipt_number"), row.getString("legal_name"),
                    row.getString("session_name"), row.getString("currency_code"), row.getBigDecimal("signed_balance"), row.getBigDecimal("amount"),
                    row.getObject("verified_read_id", UUID.class), row.getString("evidence_sha256"), row.getInt("source_version"), row.getString("processed_at_exact"),
                    row.getObject("processed_on", LocalDate.class), row.getString("school_time_zone"), row.getObject("closed_day_close_id", UUID.class), row.getString("source_state"),
                    row.getObject("receipt_posted_on", LocalDate.class)));
        if (rows.size() != 1) throw missing();
        return rows.getFirst();
    }

    private Stored replay(FinanceService.Scope scope, UUID userId, UUID clientId) {
        var rows = jdbc.query("SELECT * FROM fee_gateway_refund_postings WHERE organisation_id=:organisationId AND school_id=:schoolId AND posted_by_user_id=:actor AND client_request_id=:clientId",
                scope.params().addValue("actor", userId).addValue("clientId", clientId), (row, index) -> stored(row));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Stored posted(FinanceService.Scope scope, UUID refundId) {
        var rows = jdbc.query("SELECT * FROM fee_gateway_refund_postings WHERE refund_request_id=:refundId AND organisation_id=:organisationId AND school_id=:schoolId",
                scope.params().addValue("refundId", refundId), (row, index) -> stored(row));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private GatewayRefundPostingModels.Posting exactReplay(AuthenticatedUser user, FinanceService.Scope scope, Stored old, UUID refundId, String fingerprint) {
        if (!old.refundId().equals(refundId) || !old.fingerprint().equals(fingerprint)) throw conflict("REFUND_POSTING_REQUEST_REUSED", "This request identity belongs to different posting terms.");
        return projection(user, scope, old);
    }

    private GatewayRefundPostingModels.Posting projection(AuthenticatedUser user, FinanceService.Scope scope, Stored row) {
        if (row == null) return null;
        var batches = jdbc.query("""
                SELECT batch.id,batch.accounting_journal_id,batch.financial_year_id,batch.business_date FROM fee_accounting_posting_batches batch
                WHERE batch.refund_posting_id=:id AND batch.organisation_id=:organisationId AND batch.school_id=:schoolId
                  AND EXISTS(SELECT 1 FROM fee_accounting_posting_items item WHERE item.posting_batch_id=batch.id
                    AND item.organisation_id=batch.organisation_id AND item.school_id=batch.school_id
                    AND item.fee_ledger_entry_id=:ledgerId AND item.status='ACTIVE')
                ORDER BY batch.prepared_at DESC,batch.sequence_number DESC,batch.id DESC LIMIT 1
                """, scope.params().addValue("id", row.id()).addValue("ledgerId", row.ledgerId()), (r, n) -> new Batch(r.getObject("id", UUID.class), r.getObject("accounting_journal_id", UUID.class),
                    r.getObject("financial_year_id", UUID.class), r.getObject("business_date", LocalDate.class)));
        Batch batch = batches.isEmpty() ? null : batches.getFirst();
        String status = batch == null || batch.journalId() == null ? "REQUIRES_REVIEW"
                : accounting.feeRefundJournalStatuses(user, scope.schoolId(), List.of(batch.journalId())).getOrDefault(batch.journalId(), "REQUIRES_REVIEW");
        return new GatewayRefundPostingModels.Posting(row.id(), row.refundId(), row.paymentId(), row.accountId(), row.amount(), row.currency(), row.postedOn(), row.postedAt(), row.ledgerId(),
                batch == null ? null : batch.id(), batch == null ? null : batch.journalId(), status,
                batch == null ? null : batch.yearId(), batch == null ? null : batch.postingOn());
    }

    private boolean canPrepareAccounting(AuthenticatedUser user, FinanceService.Scope scope) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT (SELECT count(DISTINCT capability.capability_code) FROM memberships member JOIN membership_roles assignment ON assignment.membership_id=member.id
                  JOIN roles role ON role.id=assignment.role_id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                  JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code IN ('accounting.read','accounting.prepare')
                  WHERE member.user_id=:actor AND member.id IN(:memberships) AND member.organisation_id=:organisationId AND member.school_id=:schoolId
                    AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp() AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
                    AND current_app_user_id()=:actor AND current_app_organisation_id()=:organisationId)=2
                """, scope.params().addValue("actor", user.userId()).addValue("memberships", user.membershipIds()), Boolean.class));
    }

    private void requireContext(AuthenticatedUser user, boolean write) {
        var context = TenantRequestContext.current().orElse(null);
        if (user == null || user.userId() == null || user.activeOrganisationId() == null || user.membershipIds().isEmpty() || context == null
                || !Objects.equals(context.userId(), user.userId()) || !Objects.equals(context.organisationId(), user.activeOrganisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive() || (write && TransactionSynchronizationManager.isCurrentTransactionReadOnly())) throw denied();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:org AND current_app_user_id()=:actor",
                new MapSqlParameterSource("org", user.activeOrganisationId()).addValue("actor", user.userId()), Boolean.class))) throw denied();
    }

    static String fingerprint(UUID refundId, UUID yearId, LocalDate postingOn, String reason) {
        String canonical = "v1|" + refundId + "|" + yearId + "|" + postingOn + "|" + reason.length() + ":" + reason;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static Stored stored(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Stored(row.getObject("id", UUID.class), row.getObject("refund_request_id", UUID.class), row.getObject("fee_payment_id", UUID.class),
                row.getObject("learner_fee_account_id", UUID.class), row.getBigDecimal("amount"), row.getString("currency_code"), row.getObject("posted_on", LocalDate.class),
                row.getObject("posted_at", OffsetDateTime.class).toInstant(), row.getObject("fee_ledger_entry_id", UUID.class), row.getString("request_fingerprint"));
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "REFUND_POSTING_INVALID", "Choose a school, stable request identity, financial year, posting date and reason."); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "REFUND_POSTING_NOT_FOUND", "This refund is not available for this school."); }
    private static ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN, "REFUND_POSTING_DENIED", "Reviewed refund posting requires live school-scoped fee and accounting authority."); }
    private static ApiException unavailable() { return conflict("REFUND_POSTING_REVIEW_REQUIRED", "Review the verified refund, original account, open date and original accounting before posting."); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private record Batch(UUID id, UUID journalId, UUID yearId, LocalDate postingOn) {
        @Override public String toString() { return "Batch[private refund accounting identity]"; }
    }
    private record Stored(UUID id, UUID refundId, UUID paymentId, UUID accountId, BigDecimal amount, String currency,
            LocalDate postedOn, Instant postedAt, UUID ledgerId, String fingerprint) {
        @Override public String toString() { return "Stored[private immutable refund financial record]"; }
    }
    private record Source(UUID paymentId, UUID captureId, UUID accountId, String accountStatus, String receiptNumber, String learnerName,
            String sessionName, String currency, BigDecimal balance, BigDecimal amount, UUID readId, String hash, int version,
            String processedExact, LocalDate processedOn, String timeZone, UUID closedDayId, String state, LocalDate receiptPostedOn) {
        @Override public String toString() { return "Source[private retained refund evidence]"; }
    }
}

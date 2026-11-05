package com.educore.api.accounting;

import static com.educore.api.accounting.AccountingController.*;
import static com.educore.api.accounting.AccountingModuleBridge.*;
import static com.educore.api.accounting.AccountingSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

@Service
public class AccountingService implements AccountingModuleBridge {
    private static final Set<String> BASES = Set.of("ACCRUAL", "CASH");
    private static final Set<String> ACCOUNT_TYPES =
            Set.of("ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE");
    private static final Set<String> CATEGORIES =
            Set.of("CASH", "BANK", "RECEIVABLE", "PAYABLE", "FUND", "INCOME", "EXPENSE", "OTHER");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final int MAX_FEE_EVIDENCE_IDS = 1000;
    private static final int MAX_FEE_EVIDENCE_GROUPS = 20000;
    private static final Set<String> VOUCHER_TYPES =
            Set.of("RECEIPT", "PAYMENT", "JOURNAL", "CONTRA", "EXPENSE", "OPENING");
    private static final Map<String, String> VOUCHER_PREFIXES = Map.of(
            "RECEIPT", "RCPT", "PAYMENT", "PAY", "JOURNAL", "JV",
            "CONTRA", "CONTRA", "EXPENSE", "EXP", "OPENING", "OPEN");
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public AccountingService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettlementBankAccount> settlementBankEvidenceAccounts(AuthenticatedUser user, UUID schoolId) {
        return new GatewaySettlementBankEvidenceAccounts(jdbc).read(user, schoolId);
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementAccountingOptions settlementAccountingOptions(AuthenticatedUser user, UUID schoolId) {
        // Reuse the live human/read boundary, not cached principal permissions.
        new GatewaySettlementBankEvidenceAccounts(jdbc).read(user, schoolId);
        Scope scope = requireScope(user, schoolId);
        var years = jdbc.query("""
                SELECT id,code,display_name,starts_on,ends_on,accounting_basis,currency_code
                FROM accounting_financial_years WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='OPEN' AND currency_code='INR' ORDER BY starts_on DESC,id
                """, scope.params(), (row,n) -> new SettlementFinancialYear(row.getObject("id",UUID.class),row.getString("code"),
                        row.getString("display_name"),row.getObject("starts_on",LocalDate.class),row.getObject("ends_on",LocalDate.class),
                        row.getString("accounting_basis"),row.getString("currency_code")));
        var accounts = jdbc.query("""
                SELECT id,code,display_name,account_type,category FROM accounting_accounts
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE' AND allow_direct_posting
                  AND (account_type IN('EXPENSE','INCOME') OR account_type='ASSET' AND category IN('BANK','OTHER')
                    OR account_type='LIABILITY' AND category IN('PAYABLE','OTHER')) ORDER BY account_type,category,code,id
                """, scope.params(), (row,n) -> new SettlementAccountOption(row.getObject("id",UUID.class),row.getString("code"),
                        row.getString("display_name"),row.getString("account_type"),row.getString("category")));
        return new SettlementAccountingOptions(scope.canPrepare(),years,accounts);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FeeBookContext validateSettlementPosting(AuthenticatedUser user, UUID schoolId, UUID targetFinancialYearId,
            LocalDate postingOn, List<SettlementAccountUse> accounts, List<UUID> sourceJournalIds) {
        requireRefundAccountingContext(user,schoolId);
        if(accounts==null||accounts.isEmpty()||accounts.size()>1000||accounts.stream().anyMatch(use->use==null||use.accountId()==null||use.useType()==null)
                ||sourceJournalIds==null||sourceJournalIds.isEmpty()||sourceJournalIds.size()>MAX_FEE_EVIDENCE_IDS||sourceJournalIds.stream().anyMatch(id->id==null))
            throw settlementUnavailable();
        if(accounts.stream().distinct().count()!=accounts.size()
                ||accounts.stream().filter(use->use.useType()==SettlementAccountUseType.BANK).count()!=1
                ||accounts.stream().noneMatch(use->use.useType()==SettlementAccountUseType.CLEARING))throw settlementUnavailable();
        Scope scope=requireCapability(user,schoolId,"accounting.prepare");lockSchool(scope);
        requireRefundReadCommitted(scope);requireLiveRefundAccountingActor(user,scope,"accounting.prepare");
        FeeBookContext book=validateFeeBook(scope,targetFinancialYearId,postingOn,List.of(),true);
        if(!"INR".equals(book.currencyCode()))throw settlementUnavailable();
        var ids=new LinkedHashSet<>(sourceJournalIds);
        var sources=jdbc.query("""
                /* settlement-accounting-source-admission */
                SELECT original.id,original.status,original.reversal_of_journal_id,year.accounting_basis,year.currency_code,
                  EXISTS(SELECT 1 FROM accounting_journals reversal WHERE reversal.reversal_of_journal_id=original.id
                    AND reversal.organisation_id=original.organisation_id AND reversal.school_id=original.school_id) has_reversal,
                  EXISTS(SELECT 1 FROM accounting_reversal_requests pending WHERE pending.accounting_journal_id=original.id
                    AND pending.organisation_id=original.organisation_id AND pending.school_id=original.school_id AND pending.status='PENDING') has_pending
                FROM accounting_journals original JOIN accounting_financial_years year ON year.id=original.financial_year_id
                  AND year.organisation_id=original.organisation_id AND year.school_id=original.school_id
                WHERE original.organisation_id=:organisationId AND original.school_id=:schoolId
                  AND original.source_module='FEES' AND original.id IN(:journalIds) FOR SHARE OF original
                """,scope.params().addValue("journalIds",ids),(row,n)-> {
                    if(!"POSTED".equals(row.getString("status"))||row.getObject("reversal_of_journal_id")!=null
                            ||row.getBoolean("has_reversal")||row.getBoolean("has_pending")
                            ||!book.accountingBasis().equals(row.getString("accounting_basis"))||!book.currencyCode().equals(row.getString("currency_code")))
                        throw settlementUnavailable();
                    return row.getObject("id",UUID.class);
                });
        if(sources.size()!=ids.size())throw settlementUnavailable();
        var accountIds=accounts.stream().map(SettlementAccountUse::accountId).collect(Collectors.toSet());
        var available=postingAccounts(scope,accountIds);
        if(available.size()!=accountIds.size())throw settlementUnavailable();
        var clearing=accounts.stream().filter(use->use.useType()==SettlementAccountUseType.CLEARING).map(SettlementAccountUse::accountId).collect(Collectors.toSet());
        for(var use:accounts) {
            PostingAccount account=available.get(use.accountId());
            if(!"ACTIVE".equals(account.status())||!account.direct()||!settlementAccountMatches(account,use.useType())
                    ||clearing.contains(account.id())&&use.useType()!=SettlementAccountUseType.CLEARING)throw settlementUnavailable();
        }
        return book;
    }

    private static boolean settlementAccountMatches(PostingAccount account,SettlementAccountUseType use) {
        return switch(use) {
            case BANK -> "ASSET".equals(account.type())&&"BANK".equals(account.category());
            case CLEARING, TAX_ASSET -> "ASSET".equals(account.type())&&"OTHER".equals(account.category());
            case EXPENSE -> "EXPENSE".equals(account.type())&&Set.of("EXPENSE","OTHER").contains(account.category());
            case ADJUSTMENT_DEBIT, ADJUSTMENT_CREDIT -> Set.of("EXPENSE","INCOME").contains(account.type())
                    ||"ASSET".equals(account.type())&&"OTHER".equals(account.category())
                    ||"LIABILITY".equals(account.type())&&Set.of("PAYABLE","OTHER").contains(account.category());
        };
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountingMutationResponse prepareSettlementJournal(AuthenticatedUser user,SettlementJournalRequest request) {
        if(request==null||request.preparationId()==null||request.lines().size()<2||request.lines().size()>500)throw settlementUnavailable();
        requireRefundAccountingContext(user,request.schoolId());
        Scope scope=requireCapability(user,request.schoolId(),"accounting.prepare");lockSchool(scope);
        requireRefundReadCommitted(scope);requireLiveRefundAccountingActor(user,scope,"accounting.prepare");
        var lines=request.lines().stream().map(line->new JournalLineRequest(line.accountId(),null,null,
                line.debitAmount(),line.creditAmount(),line.memo())).toList();
        var command=new JournalRequest(request.schoolId(),request.financialYearId(),request.accountingPeriodId(),"JOURNAL",
                request.postingDate(),request.reference(),request.narration(),lines);
        // A same-source replay survives subsequent period/account closure. Only
        // immutable canonical request facts are compared before mutable checks.
        StoredJournal prior=findJournalByKey(scope,idempotencyKey(request.idempotencyKey()));
        if(prior!=null) {
            var canonical=request.lines().stream().map(line->new LineEvidence(
                    new PostingAccount(line.accountId(),null,null,null,null,null,false),null,null,
                    inputMoney(line.debitAmount()),inputMoney(line.creditAmount()),clean(line.memo()))).toList();
            String fingerprint=sha256(journalFingerprint(command,"JOURNAL",canonical)+"|GATEWAY_SETTLEMENT|"+request.preparationId());
            if(!fingerprint.equals(prior.fingerprint())||!"GATEWAY_SETTLEMENT".equals(prior.sourceModule())
                    ||!request.preparationId().equals(prior.sourceId()))throw conflict("IDEMPOTENCY_KEY_REUSED","This journal retry belongs to different settlement terms.");
            return new AccountingMutationResponse(prior.id(),prior.version(),prior.voucherNumber());
        }
        var draft=createJournal(scope,user,request.idempotencyKey(),command,"GATEWAY_SETTLEMENT",request.preparationId());
        return submitJournal(user,draft.id(),new VersionedReasonRequest(request.schoolId(),draft.rowVersion(),request.submissionReason()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID,SettlementJournalStatus> settlementJournalStatuses(AuthenticatedUser user,UUID schoolId,List<UUID> preparationIds) {
        new GatewaySettlementBankEvidenceAccounts(jdbc).read(user,schoolId);
        Scope scope=requireScope(user,schoolId);
        if(preparationIds==null||preparationIds.isEmpty())return Map.of();
        if(preparationIds.size()>1000||preparationIds.stream().anyMatch(id->id==null))throw settlementUnavailable();
        var result=new LinkedHashMap<UUID,SettlementJournalStatus>();
        jdbc.query("""
                SELECT journal.source_id,journal.id,journal.status,journal.row_version,journal.voucher_number,
                  reversal.id reversal_id,reversal.status reversal_status
                FROM accounting_journals journal LEFT JOIN accounting_journals reversal ON reversal.reversal_of_journal_id=journal.id
                  AND reversal.organisation_id=journal.organisation_id AND reversal.school_id=journal.school_id
                WHERE journal.organisation_id=:organisationId AND journal.school_id=:schoolId AND journal.source_module='GATEWAY_SETTLEMENT'
                  AND journal.reversal_of_journal_id IS NULL AND journal.source_id IN(:preparationIds)
                """,scope.params().addValue("preparationIds",Set.copyOf(preparationIds)),row->{
                    UUID preparation=row.getObject("source_id",UUID.class);
                    if(result.putIfAbsent(preparation,new SettlementJournalStatus(row.getObject("id",UUID.class),row.getString("status"),
                            row.getLong("row_version"),row.getString("voucher_number"),row.getObject("reversal_id",UUID.class),row.getString("reversal_status")))!=null)
                        throw settlementUnavailable();
                });
        return Map.copyOf(result);
    }

    private static ApiException settlementUnavailable(){return conflict("GATEWAY_SETTLEMENT_ACCOUNTING_UNAVAILABLE","The settlement sources, reviewed accounts or open accounting book require review.");}

    @Transactional(readOnly = true)
    public AccountingSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId,
            UUID requestedFinancialYearId, LocalDate requestedFrom, LocalDate requestedTo) {
        Scope scope = requireScope(user, requestedSchoolId);
        List<PeriodItem> allPeriods = jdbc.query("""
                SELECT id,financial_year_id,period_number,code,display_name,starts_on,ends_on,status,row_version
                FROM accounting_periods
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY financial_year_id,period_number,id
                """, scope.params(), (r, n) -> new PeriodItem(r.getObject("id", UUID.class),
                r.getInt("period_number"), r.getString("code"), r.getString("display_name"),
                r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class),
                r.getString("status"), r.getLong("row_version")));
        Map<UUID, List<PeriodItem>> periodsByYear = new LinkedHashMap<>();
        List<UUID> periodYearIds = jdbc.query("""
                SELECT financial_year_id FROM accounting_periods
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY financial_year_id,period_number,id
                """, scope.params(), (r, n) -> r.getObject("financial_year_id", UUID.class));
        for (int index = 0; index < allPeriods.size(); index++) {
            periodsByYear.computeIfAbsent(periodYearIds.get(index), ignored -> new ArrayList<>())
                    .add(allPeriods.get(index));
        }
        List<FinancialYearItem> years = jdbc.query("""
                SELECT year.*,creator_user.display_name AS creator_name,
                       opener_user.display_name AS opener_name,closer_user.display_name AS closer_name
                FROM accounting_financial_years year
                JOIN memberships creator ON creator.id=year.created_by_membership_id
                JOIN user_accounts creator_user ON creator_user.id=creator.user_id
                LEFT JOIN memberships opener ON opener.id=year.opened_by_membership_id
                LEFT JOIN user_accounts opener_user ON opener_user.id=opener.user_id
                LEFT JOIN memberships closer ON closer.id=year.closed_by_membership_id
                LEFT JOIN user_accounts closer_user ON closer_user.id=closer.user_id
                WHERE year.organisation_id=:organisationId AND year.school_id=:schoolId
                ORDER BY CASE year.status WHEN 'OPEN' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                         year.starts_on DESC,year.id
                """, scope.params(), (r, n) -> new FinancialYearItem(r.getObject("id", UUID.class),
                r.getString("code"), r.getString("display_name"),
                r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class),
                r.getString("accounting_basis"), r.getString("currency_code"), r.getString("status"),
                r.getString("creator_name"), r.getString("opener_name"), instant(r.getTimestamp("opened_at")),
                r.getString("closer_name"), instant(r.getTimestamp("closed_at")), r.getLong("row_version"),
                periodsByYear.getOrDefault(r.getObject("id", UUID.class), List.of())));
        FinancialYearItem selected = selectYear(years, requestedFinancialYearId);
        LocalDate from = requestedFrom == null
                ? (selected == null ? LocalDate.now(clock).withDayOfYear(1) : selected.startsOn()) : requestedFrom;
        LocalDate to = requestedTo == null
                ? (selected == null ? LocalDate.now(clock) : selected.endsOn()) : requestedTo;
        validateRange(from, to);
        if (selected != null && (from.isBefore(selected.startsOn()) || to.isAfter(selected.endsOn()))) {
            throw bad("ACCOUNTING_REPORT_RANGE_INVALID", "Choose dates inside the selected financial year.");
        }
        UUID yearId = selected == null ? null : selected.id();
        MapSqlParameterSource parameters = scope.params().addValue("financialYearId", yearId)
                .addValue("from", from).addValue("to", to);
        List<AccountItem> accounts = loadAccounts(parameters);
        List<VendorItem> vendors = loadVendors(parameters);
        List<JournalItem> journals = yearId == null ? List.of() : loadJournals(parameters);
        List<ReversalRequestItem> reversals = yearId == null ? List.of() : loadReversalRequests(parameters);
        List<TrialBalanceItem> trialBalance = yearId == null ? List.of() : loadTrialBalance(parameters);
        List<LedgerLineItem> ledger = yearId == null ? List.of() : loadLedger(parameters);
        StatementSummary statements = statementSummary(trialBalance, yearId == null
                ? Map.of("income", BigDecimal.ZERO, "expense", BigDecimal.ZERO)
                : loadPeriodResult(parameters));
        return new AccountingSnapshot(scope.schoolId(), scope.schoolName(), scope.canConfigure(),
                scope.canPrepare(), scope.canPost(), scope.canClose(), scope.canRequestReversal(),
                scope.canApproveReversal(), yearId, from, to, years, accounts, vendors, journals,
                reversals, trialBalance, ledger, statements);
    }

    private List<AccountItem> loadAccounts(MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT id,parent_account_id,code,display_name,description,account_type,normal_balance,
                       category,allow_direct_posting,status,row_version
                FROM accounting_accounts
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY account_type,code,id
                """, parameters, (r, n) -> new AccountItem(r.getObject("id", UUID.class),
                r.getObject("parent_account_id", UUID.class), r.getString("code"),
                r.getString("display_name"), r.getString("description"), r.getString("account_type"),
                r.getString("normal_balance"), r.getString("category"),
                r.getBoolean("allow_direct_posting"), r.getString("status"), r.getLong("row_version")));
    }

    private List<VendorItem> loadVendors(MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT id,code,display_name,contact_name,phone,email,address,status,row_version
                FROM accounting_vendors
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY status,display_name,id
                """, parameters, (r, n) -> new VendorItem(r.getObject("id", UUID.class),
                r.getString("code"), r.getString("display_name"), r.getString("contact_name"),
                r.getString("phone"), r.getString("email"), r.getString("address"),
                r.getString("status"), r.getLong("row_version")));
    }

    private List<JournalItem> loadJournals(MapSqlParameterSource parameters) {
        List<JournalHeader> headers = jdbc.query("""
                SELECT journal.*,creator_user.display_name AS creator_name,
                       submitter_user.display_name AS submitter_name,
                       decider_user.display_name AS decider_name
                FROM accounting_journals journal
                JOIN memberships creator ON creator.id=journal.created_by_membership_id
                JOIN user_accounts creator_user ON creator_user.id=creator.user_id
                LEFT JOIN memberships submitter ON submitter.id=journal.submitted_by_membership_id
                LEFT JOIN user_accounts submitter_user ON submitter_user.id=submitter.user_id
                LEFT JOIN memberships decider ON decider.id=journal.decided_by_membership_id
                LEFT JOIN user_accounts decider_user ON decider_user.id=decider.user_id
                WHERE journal.organisation_id=:organisationId AND journal.school_id=:schoolId
                  AND journal.financial_year_id=:financialYearId
                  AND journal.posting_date BETWEEN :from AND :to
                ORDER BY journal.posting_date DESC,journal.created_at DESC,journal.id
                LIMIT 250
                """, parameters, (r, n) -> new JournalHeader(r.getObject("id", UUID.class),
                r.getObject("financial_year_id", UUID.class), r.getObject("accounting_period_id", UUID.class),
                r.getString("voucher_type"), r.getString("voucher_number"),
                r.getObject("posting_date", LocalDate.class), r.getString("reference"),
                r.getString("narration"), r.getString("source_module"), r.getObject("source_id", UUID.class),
                r.getObject("reversal_of_journal_id", UUID.class), r.getString("status"),
                (Integer) r.getObject("line_count"), r.getBigDecimal("total_amount"),
                r.getString("creator_name"), r.getString("submitter_name"),
                instant(r.getTimestamp("submitted_at")), r.getString("decider_name"),
                instant(r.getTimestamp("decided_at")), r.getLong("row_version")));
        if (headers.isEmpty()) return List.of();
        List<UUID> ids = headers.stream().map(JournalHeader::id).toList();
        Map<UUID, List<JournalLineItem>> lines = jdbc.query("""
                SELECT accounting_journal_id,id,line_number,account_id,account_code_snapshot,
                       account_name_snapshot,account_type_snapshot,vendor_id,learner_fee_account_id,
                       debit_amount,credit_amount,memo
                FROM accounting_journal_lines
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND accounting_journal_id IN (:journalIds)
                ORDER BY accounting_journal_id,line_number,id
                """, parameters.addValue("journalIds", ids), r -> {
                    Map<UUID, List<JournalLineItem>> grouped = new LinkedHashMap<>();
                    while (r.next()) {
                        grouped.computeIfAbsent(r.getObject("accounting_journal_id", UUID.class),
                                ignored -> new ArrayList<>()).add(new JournalLineItem(
                                r.getObject("id", UUID.class), r.getInt("line_number"),
                                r.getObject("account_id", UUID.class), r.getString("account_code_snapshot"),
                                r.getString("account_name_snapshot"), r.getString("account_type_snapshot"),
                                r.getObject("vendor_id", UUID.class),
                                r.getObject("learner_fee_account_id", UUID.class),
                                r.getBigDecimal("debit_amount"), r.getBigDecimal("credit_amount"),
                                r.getString("memo")));
                    }
                    return grouped;
                });
        Map<UUID, List<JournalHistoryItem>> history = jdbc.query("""
                SELECT history.accounting_journal_id,history.from_status,history.to_status,
                       history.reason,actor_user.display_name AS actor_name,history.occurred_at
                FROM accounting_journal_history history
                JOIN memberships actor ON actor.id=history.actor_membership_id
                JOIN user_accounts actor_user ON actor_user.id=actor.user_id
                WHERE history.organisation_id=:organisationId AND history.school_id=:schoolId
                  AND history.accounting_journal_id IN (:journalIds)
                ORDER BY history.accounting_journal_id,history.occurred_at,history.id
                """, parameters, r -> {
                    Map<UUID, List<JournalHistoryItem>> grouped = new LinkedHashMap<>();
                    while (r.next()) {
                        grouped.computeIfAbsent(r.getObject("accounting_journal_id", UUID.class),
                                ignored -> new ArrayList<>()).add(new JournalHistoryItem(
                                r.getString("from_status"), r.getString("to_status"),
                                r.getString("reason"), r.getString("actor_name"),
                                r.getTimestamp("occurred_at").toInstant()));
                    }
                    return grouped;
                });
        return headers.stream().map(header -> header.item(
                lines.getOrDefault(header.id(), List.of()),
                history.getOrDefault(header.id(), List.of()))).toList();
    }

    private List<ReversalRequestItem> loadReversalRequests(MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT request.*,requester_user.display_name AS requester_name,
                       decider_user.display_name AS decider_name
                FROM accounting_reversal_requests request
                JOIN accounting_journals original ON original.id=request.accounting_journal_id
                JOIN memberships requester ON requester.id=request.requested_by_membership_id
                JOIN user_accounts requester_user ON requester_user.id=requester.user_id
                LEFT JOIN memberships decider ON decider.id=request.decided_by_membership_id
                LEFT JOIN user_accounts decider_user ON decider_user.id=decider.user_id
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND original.financial_year_id=:financialYearId
                ORDER BY CASE request.status WHEN 'PENDING' THEN 0 ELSE 1 END,
                         request.created_at DESC,request.id
                LIMIT 250
                """, parameters, (r, n) -> new ReversalRequestItem(r.getObject("id", UUID.class),
                r.getObject("accounting_journal_id", UUID.class),
                r.getObject("requested_accounting_period_id", UUID.class),
                r.getObject("requested_posting_date", LocalDate.class), r.getString("request_reason"),
                r.getString("status"), r.getString("requester_name"), r.getString("decider_name"),
                r.getString("decision_reason"), r.getObject("reversal_journal_id", UUID.class),
                r.getTimestamp("created_at").toInstant(), r.getLong("row_version")));
    }

    private List<TrialBalanceItem> loadTrialBalance(MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT account.id,account.code,account.display_name,account.account_type,account.category,
                       COALESCE(book.debits,0) AS debits,COALESCE(book.credits,0) AS credits
                FROM accounting_accounts account
                LEFT JOIN (
                  SELECT line.account_id,sum(line.debit_amount) AS debits,sum(line.credit_amount) AS credits
                  FROM accounting_journal_lines line
                  JOIN accounting_journals journal ON journal.id=line.accounting_journal_id
                  WHERE journal.organisation_id=:organisationId AND journal.school_id=:schoolId
                    AND journal.financial_year_id=:financialYearId
                    AND journal.status IN ('POSTED','REVERSED') AND journal.posting_date<=:to
                  GROUP BY line.account_id
                ) book ON book.account_id=account.id
                WHERE account.organisation_id=:organisationId AND account.school_id=:schoolId
                ORDER BY account.account_type,account.code,account.id
                """, parameters, (r, n) -> {
                    BigDecimal debits = money(r.getBigDecimal("debits"));
                    BigDecimal credits = money(r.getBigDecimal("credits"));
                    BigDecimal net = debits.subtract(credits);
                    return new TrialBalanceItem(r.getObject("id", UUID.class), r.getString("code"),
                            r.getString("display_name"), r.getString("account_type"),
                            r.getString("category"), debits, credits, net.max(BigDecimal.ZERO),
                            net.negate().max(BigDecimal.ZERO));
                });
    }

    private List<LedgerLineItem> loadLedger(MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT journal.id AS journal_id,journal.voucher_number,journal.voucher_type,
                       journal.posting_date,journal.narration,line.account_id,line.account_code_snapshot,
                       line.account_name_snapshot,line.vendor_id,vendor.display_name AS vendor_name,
                       line.learner_fee_account_id,line.debit_amount,line.credit_amount,line.memo
                FROM accounting_journals journal
                JOIN accounting_journal_lines line ON line.accounting_journal_id=journal.id
                LEFT JOIN accounting_vendors vendor ON vendor.id=line.vendor_id
                WHERE journal.organisation_id=:organisationId AND journal.school_id=:schoolId
                  AND journal.financial_year_id=:financialYearId
                  AND journal.status IN ('POSTED','REVERSED')
                  AND journal.posting_date BETWEEN :from AND :to
                ORDER BY journal.posting_date,journal.voucher_number,line.line_number,line.id
                LIMIT 1000
                """, parameters, (r, n) -> new LedgerLineItem(r.getObject("journal_id", UUID.class),
                r.getString("voucher_number"), r.getString("voucher_type"),
                r.getObject("posting_date", LocalDate.class), r.getObject("account_id", UUID.class),
                r.getString("account_code_snapshot"), r.getString("account_name_snapshot"),
                r.getObject("vendor_id", UUID.class), r.getString("vendor_name"),
                r.getObject("learner_fee_account_id", UUID.class), r.getBigDecimal("debit_amount"),
                r.getBigDecimal("credit_amount"), r.getString("narration"), r.getString("memo")));
    }

    private Map<String, BigDecimal> loadPeriodResult(MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT COALESCE(sum(CASE WHEN line.account_type_snapshot='INCOME'
                    THEN line.credit_amount-line.debit_amount ELSE 0 END),0) AS income,
                  COALESCE(sum(CASE WHEN line.account_type_snapshot='EXPENSE'
                    THEN line.debit_amount-line.credit_amount ELSE 0 END),0) AS expense
                FROM accounting_journals journal
                JOIN accounting_journal_lines line ON line.accounting_journal_id=journal.id
                WHERE journal.organisation_id=:organisationId AND journal.school_id=:schoolId
                  AND journal.financial_year_id=:financialYearId
                  AND journal.status IN ('POSTED','REVERSED')
                  AND journal.posting_date BETWEEN :from AND :to
                """, parameters, r -> r.next() ? Map.of("income", money(r.getBigDecimal("income")),
                "expense", money(r.getBigDecimal("expense"))) : Map.of());
    }

    private StatementSummary statementSummary(List<TrialBalanceItem> trial,
            Map<String, BigDecimal> periodResult) {
        BigDecimal trialDebit = sum(trial, TrialBalanceItem::debitBalance);
        BigDecimal trialCredit = sum(trial, TrialBalanceItem::creditBalance);
        BigDecimal assets = balance(trial, "ASSET", true);
        BigDecimal liabilities = balance(trial, "LIABILITY", false);
        BigDecimal fund = balance(trial, "EQUITY", false);
        BigDecimal income = periodResult.getOrDefault("income", BigDecimal.ZERO);
        BigDecimal expense = periodResult.getOrDefault("expense", BigDecimal.ZERO);
        return new StatementSummary(trialDebit, trialCredit, assets, liabilities, fund,
                income, expense, income.subtract(expense), categoryBalance(trial, "CASH", true),
                categoryBalance(trial, "BANK", true), categoryBalance(trial, "RECEIVABLE", true),
                categoryBalance(trial, "PAYABLE", false));
    }

    @Transactional
    public AccountingMutationResponse createFinancialYear(AuthenticatedUser user,
            FinancialYearRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.configure");
        lockSchool(scope);
        String code = code(request.code(), 30);
        String basis = enumValue(request.accountingBasis(), BASES, "accounting basis");
        String currency = request.currencyCode().trim().toUpperCase(Locale.ROOT);
        if (request.endsOn().isBefore(request.startsOn())
                || request.endsOn().isAfter(request.startsOn().plusDays(730))) {
            throw bad("FINANCIAL_YEAR_DATES_INVALID",
                    "The financial year end must be on or after its start and no more than two years later.");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        MapSqlParameterSource parameters = scope.params().addValue("id", id).addValue("code", code)
                .addValue("name", required(request.name())).addValue("startsOn", request.startsOn())
                .addValue("endsOn", request.endsOn()).addValue("basis", basis)
                .addValue("currency", currency).addValue("now", now);
        safe("FINANCIAL_YEAR_EXISTS", () -> jdbc.update("""
                INSERT INTO accounting_financial_years(id,organisation_id,school_id,code,display_name,
                  starts_on,ends_on,accounting_basis,currency_code,status,created_by_membership_id,
                  created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:code,:name,:startsOn,:endsOn,:basis,:currency,
                  'DRAFT',:actorMembershipId,:now,:now)
                """, parameters));
        LocalDate cursor = request.startsOn();
        int number = 1;
        while (!cursor.isAfter(request.endsOn())) {
            if (number > 24) {
                throw bad("FINANCIAL_YEAR_PERIODS_INVALID",
                        "A financial year can contain no more than 24 monthly accounting periods.");
            }
            LocalDate periodEnd = YearMonth.from(cursor).atEndOfMonth();
            if (periodEnd.isAfter(request.endsOn())) periodEnd = request.endsOn();
            jdbc.update("""
                    INSERT INTO accounting_periods(id,organisation_id,school_id,financial_year_id,
                      period_number,code,display_name,starts_on,ends_on,status,created_at,updated_at)
                    VALUES(:periodId,:organisationId,:schoolId,:id,:number,:periodCode,:periodName,
                      :periodStart,:periodEnd,'OPEN',:now,:now)
                    """, parameters.addValue("periodId", UUID.randomUUID()).addValue("number", number)
                    .addValue("periodCode", "P%02d".formatted(number))
                    .addValue("periodName", cursor.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                            + " " + cursor.getYear())
                    .addValue("periodStart", cursor).addValue("periodEnd", periodEnd));
            cursor = periodEnd.plusDays(1);
            number++;
        }
        String suffix = code.replace('_', '-');
        for (String type : VOUCHER_TYPES) {
            String prefix = truncate(VOUCHER_PREFIXES.get(type) + "/" + suffix, 20);
            jdbc.update("""
                    INSERT INTO accounting_journal_sequences(organisation_id,school_id,financial_year_id,
                      voucher_type,prefix,next_value,updated_at)
                    VALUES(:organisationId,:schoolId,:id,:voucherType,:prefix,1,:now)
                    """, parameters.addValue("voucherType", type).addValue("prefix", prefix));
        }
        audit(user, scope, "accounting.financial-year.created", "ACCOUNTING_FINANCIAL_YEAR", id,
                request.reason());
        return new AccountingMutationResponse(id, 0);
    }

    @Transactional
    public AccountingMutationResponse openFinancialYear(AuthenticatedUser user, UUID id,
            VersionedReasonRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.close");
        lockSchool(scope);
        FinancialYearState year = lockFinancialYear(scope, id);
        if (!"DRAFT".equals(year.status()) || year.version() != request.expectedVersion()) {
            throw conflict("FINANCIAL_YEAR_NOT_OPENABLE", "Refresh the draft financial year and try again.");
        }
        if (year.creatorId().equals(scope.actorMembershipId())) {
            throw forbidden("INDEPENDENT_FINANCIAL_YEAR_OPEN_REQUIRED",
                    "A different authorized person must open the financial year.");
        }
        int postingAccounts = jdbc.queryForObject("""
                SELECT count(*) FROM accounting_accounts
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='ACTIVE' AND allow_direct_posting
                """, scope.params(), Integer.class);
        if (postingAccounts < 2) {
            throw conflict("CHART_OF_ACCOUNTS_REQUIRED",
                    "Configure at least two active posting accounts before opening the financial year.");
        }
        int changed = safe("FINANCIAL_YEAR_OPEN_CONFLICT", () -> jdbc.update("""
                UPDATE accounting_financial_years SET status='OPEN',opened_by_membership_id=:actorMembershipId,
                  opened_at=:now,open_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("version", request.expectedVersion())
                .addValue("reason", reason(request.reason())).addValue("now", Instant.now(clock))));
        changed(changed);
        audit(user, scope, "accounting.financial-year.opened", "ACCOUNTING_FINANCIAL_YEAR", id,
                request.reason());
        return new AccountingMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public AccountingMutationResponse closeFinancialYear(AuthenticatedUser user, UUID id,
            VersionedReasonRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.close");
        lockSchool(scope);
        FinancialYearState year = lockFinancialYear(scope, id);
        if (!"OPEN".equals(year.status()) || year.version() != request.expectedVersion()) {
            throw conflict("FINANCIAL_YEAR_NOT_CLOSABLE", "Refresh the open financial year and try again.");
        }
        int changed = safe("FINANCIAL_YEAR_CLOSE_CONFLICT", () -> jdbc.update("""
                UPDATE accounting_financial_years SET status='CLOSED',closed_by_membership_id=:actorMembershipId,
                  closed_at=:now,close_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='OPEN' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("version", request.expectedVersion())
                .addValue("reason", reason(request.reason())).addValue("now", Instant.now(clock))));
        changed(changed);
        audit(user, scope, "accounting.financial-year.closed", "ACCOUNTING_FINANCIAL_YEAR", id,
                request.reason());
        return new AccountingMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public AccountingMutationResponse transitionPeriod(AuthenticatedUser user, UUID id,
            PeriodTransitionRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.close");
        lockSchool(scope);
        String transition = enumValue(request.transition(), Set.of("LOCK", "CLOSE"), "period transition");
        String target = "LOCK".equals(transition) ? "LOCKED" : "CLOSED";
        String actorColumn = "LOCK".equals(transition) ? "locked_by_membership_id" : "closed_by_membership_id";
        String atColumn = "LOCK".equals(transition) ? "locked_at" : "closed_at";
        String reasonColumn = "LOCK".equals(transition) ? "lock_reason" : "close_reason";
        String sql = "UPDATE accounting_periods SET status=:target," + actorColumn
                + "=:actorMembershipId," + atColumn + "=:now," + reasonColumn
                + "=:reason,updated_at=:now,row_version=row_version+1 WHERE id=:id"
                + " AND organisation_id=:organisationId AND school_id=:schoolId"
                + " AND row_version=:version AND status "
                + ("LOCK".equals(transition) ? "='OPEN'" : "IN ('OPEN','LOCKED')");
        int changed = safe("ACCOUNTING_PERIOD_TRANSITION_CONFLICT", () -> jdbc.update(sql,
                scope.params().addValue("target", target).addValue("id", id)
                .addValue("version", request.expectedVersion()).addValue("reason", reason(request.reason()))
                .addValue("now", Instant.now(clock))));
        changed(changed);
        String auditAction = "LOCK".equals(transition)
                ? "accounting.period.locked" : "accounting.period.closed";
        audit(user, scope, auditAction,
                "ACCOUNTING_PERIOD", id, request.reason());
        return new AccountingMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public AccountingMutationResponse createAccount(AuthenticatedUser user, AccountRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.configure");
        lockSchool(scope);
        AccountEvidence evidence = accountEvidence(request);
        UUID id = UUID.randomUUID();
        safe("ACCOUNT_CODE_EXISTS", () -> jdbc.update("""
                INSERT INTO accounting_accounts(id,organisation_id,school_id,parent_account_id,code,
                  display_name,description,account_type,normal_balance,category,allow_direct_posting,
                  status,created_by_membership_id,created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:parentId,:code,:name,:description,:type,
                  :normalBalance,:category,:direct,:status,:actorMembershipId,:now,:now)
                """, accountParams(scope, id, request, evidence, null)));
        audit(user, scope, "accounting.account.created", "ACCOUNTING_ACCOUNT", id, request.reason());
        return new AccountingMutationResponse(id, 0);
    }

    @Transactional
    public AccountingMutationResponse updateAccount(AuthenticatedUser user, UUID id,
            AccountRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.configure");
        lockSchool(scope);
        AccountEvidence evidence = accountEvidence(request);
        long version = version(request.expectedVersion());
        int changed = safe("ACCOUNT_UPDATE_CONFLICT", () -> jdbc.update("""
                UPDATE accounting_accounts SET display_name=:name,description=:description,
                  allow_direct_posting=:direct,status=:status,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND parent_account_id IS NOT DISTINCT FROM :parentId AND code=:code
                  AND account_type=:type AND normal_balance=:normalBalance AND category=:category
                  AND row_version=:version
                """, accountParams(scope, id, request, evidence, version)));
        changed(changed);
        audit(user, scope, "accounting.account.updated", "ACCOUNTING_ACCOUNT", id,
                reason(request.reason()));
        return new AccountingMutationResponse(id, version + 1);
    }

    @Transactional
    public AccountingMutationResponse createVendor(AuthenticatedUser user, VendorRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.configure");
        lockSchool(scope);
        UUID id = UUID.randomUUID();
        safe("VENDOR_CODE_EXISTS", () -> jdbc.update("""
                INSERT INTO accounting_vendors(id,organisation_id,school_id,code,display_name,
                  contact_name,phone,email,address,status,created_by_membership_id,created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:code,:name,:contact,:phone,:email,:address,
                  :status,:actorMembershipId,:now,:now)
                """, vendorParams(scope, id, request, null)));
        audit(user, scope, "accounting.vendor.created", "ACCOUNTING_VENDOR", id, request.reason());
        return new AccountingMutationResponse(id, 0);
    }

    @Transactional
    public AccountingMutationResponse updateVendor(AuthenticatedUser user, UUID id,
            VendorRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.configure");
        lockSchool(scope);
        long version = version(request.expectedVersion());
        int changed = safe("VENDOR_UPDATE_CONFLICT", () -> jdbc.update("""
                UPDATE accounting_vendors SET display_name=:name,contact_name=:contact,phone=:phone,
                  email=:email,address=:address,status=:status,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND code=:code AND row_version=:version
                """, vendorParams(scope, id, request, version)));
        changed(changed);
        audit(user, scope, "accounting.vendor.updated", "ACCOUNTING_VENDOR", id,
                reason(request.reason()));
        return new AccountingMutationResponse(id, version + 1);
    }

    private AccountEvidence accountEvidence(AccountRequest request) {
        String type = enumValue(request.accountType(), ACCOUNT_TYPES, "account type");
        String category = enumValue(request.category(), CATEGORIES, "account category");
        if (!categoryMatches(type, category)) {
            throw bad("ACCOUNT_CATEGORY_INVALID", "Choose a category that matches the account type.");
        }
        return new AccountEvidence(code(request.code(), 30), type,
                Set.of("ASSET", "EXPENSE").contains(type) ? "DEBIT" : "CREDIT", category,
                enumValue(request.status(), STATUSES, "account status"));
    }

    private MapSqlParameterSource accountParams(Scope scope, UUID id, AccountRequest request,
            AccountEvidence evidence, Long version) {
        return scope.params().addValue("id", id).addValue("parentId", request.parentAccountId())
                .addValue("code", evidence.code()).addValue("name", required(request.name()))
                .addValue("description", clean(request.description())).addValue("type", evidence.type())
                .addValue("normalBalance", evidence.normalBalance())
                .addValue("category", evidence.category()).addValue("direct", request.allowDirectPosting())
                .addValue("status", evidence.status()).addValue("version", version)
                .addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource vendorParams(Scope scope, UUID id, VendorRequest request, Long version) {
        return scope.params().addValue("id", id).addValue("code", code(request.code(), 40))
                .addValue("name", required(request.name())).addValue("contact", clean(request.contactName()))
                .addValue("phone", clean(request.phone())).addValue("email", lower(request.email()))
                .addValue("address", clean(request.address()))
                .addValue("status", enumValue(request.status(), STATUSES, "vendor status"))
                .addValue("version", version).addValue("now", Instant.now(clock));
    }

    @Transactional
    public AccountingMutationResponse createJournal(AuthenticatedUser user, String idempotencyKey,
            JournalRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.prepare");
        lockSchool(scope);
        return createJournal(scope, user, idempotencyKey, request, "MANUAL", null);
    }

    private AccountingMutationResponse createJournal(Scope scope, AuthenticatedUser user,
            String idempotencyKey, JournalRequest request, String sourceModule, UUID sourceId) {
        String key = idempotencyKey(idempotencyKey);
        String voucherType = enumValue(request.voucherType(), VOUCHER_TYPES, "voucher type");
        List<LineEvidence> lines = validateLines(scope, request.lines());
        BigDecimal debit = sumLines(lines, LineEvidence::debit);
        BigDecimal credit = sumLines(lines, LineEvidence::credit);
        if (debit.signum() <= 0 || debit.compareTo(credit) != 0) {
            throw bad("JOURNAL_NOT_BALANCED", "Total debits must equal total credits and be greater than zero.");
        }
        validateJournalContext(scope, request.financialYearId(), request.accountingPeriodId(),
                request.postingDate());
        String fingerprint = journalFingerprint(request, voucherType, lines);
        if (!"MANUAL".equals(sourceModule) || sourceId != null) {
            fingerprint = sha256(fingerprint + "|" + sourceModule + "|" + sourceId);
        }
        StoredJournal retry = findJournalByKey(scope, key);
        if (retry != null) {
            if (!retry.fingerprint().equals(fingerprint)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED",
                        "This retry key was already used for a different accounting voucher.");
            }
            return new AccountingMutationResponse(retry.id(), retry.version(), retry.voucherNumber());
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        MapSqlParameterSource parameters = scope.params().addValue("id", id)
                .addValue("financialYearId", request.financialYearId())
                .addValue("periodId", request.accountingPeriodId()).addValue("voucherType", voucherType)
                .addValue("postingDate", request.postingDate()).addValue("reference", clean(request.reference()))
                .addValue("narration", required(request.narration())).addValue("key", key)
                .addValue("fingerprint", fingerprint).addValue("sourceModule", sourceModule)
                .addValue("sourceId", sourceId).addValue("now", now);
        safe("JOURNAL_RETRY_CONFLICT", () -> jdbc.update("""
                INSERT INTO accounting_journals(id,organisation_id,school_id,financial_year_id,
                  accounting_period_id,voucher_type,posting_date,reference,narration,source_module,
                  source_id,idempotency_key,request_fingerprint,status,created_by_membership_id,
                  created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:financialYearId,:periodId,:voucherType,
                  :postingDate,:reference,:narration,:sourceModule,:sourceId,:key,:fingerprint,'DRAFT',
                  :actorMembershipId,:now,:now)
                """, parameters));
        for (int index = 0; index < lines.size(); index++) {
            LineEvidence line = lines.get(index);
            jdbc.update("""
                    INSERT INTO accounting_journal_lines(id,organisation_id,school_id,
                      accounting_journal_id,line_number,account_id,account_code_snapshot,
                      account_name_snapshot,account_type_snapshot,vendor_id,learner_fee_account_id,
                      debit_amount,credit_amount,memo,created_at,updated_at)
                    VALUES(:lineId,:organisationId,:schoolId,:id,:lineNumber,:accountId,:accountCode,
                      :accountName,:accountType,:vendorId,:feeAccountId,:debit,:credit,:memo,:now,:now)
                    """, parameters.addValue("lineId", UUID.randomUUID()).addValue("lineNumber", index + 1)
                    .addValue("accountId", line.account().id()).addValue("accountCode", line.account().code())
                    .addValue("accountName", line.account().name()).addValue("accountType", line.account().type())
                    .addValue("vendorId", line.vendorId()).addValue("feeAccountId", line.feeAccountId())
                    .addValue("debit", line.debit()).addValue("credit", line.credit())
                    .addValue("memo", line.memo()));
        }
        journalHistory(scope, id, null, "DRAFT", "Balanced draft voucher created");
        audit(user, scope, "accounting.journal.created", "ACCOUNTING_JOURNAL", id,
                request.narration());
        return new AccountingMutationResponse(id, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public FeeBookContext validateFeeConfigurationDraft(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate effectiveFrom, List<FeeAccountUse> accounts) {
        Scope scope = requireCapability(user, schoolId, "accounting.configure");
        lockSchool(scope);
        return validateFeeBook(scope, financialYearId, effectiveFrom, accounts, false);
    }

    @Override
    @Transactional(readOnly = true)
    public FeeBookContext validateFeeConfigurationDecision(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate effectiveFrom, List<FeeAccountUse> accounts) {
        Scope scope = requireCapability(user, schoolId, "accounting.post");
        lockSchool(scope);
        return validateFeeBook(scope, financialYearId, effectiveFrom, accounts, false);
    }

    @Override
    @Transactional(readOnly = true)
    public FeeBookContext resolveFeePostingBook(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate postingDate, boolean requireOpenPeriod) {
        Scope scope = requireCapability(user, schoolId, "accounting.prepare");
        return validateFeeBook(scope, financialYearId, postingDate, List.of(),
                requireOpenPeriod);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FeeBookContext validateGatewayCheckoutBook(UUID organisationId, UUID schoolId,
            LocalDate businessDate, UUID expectedFinancialYearId, List<FeeAccountUse> requestedAccounts) {
        var context = TenantRequestContext.current().orElseThrow(AccountingService::gatewayBookUnavailable);
        if (organisationId == null || schoolId == null || businessDate == null || requestedAccounts == null
                || context.userId() != null || !organisationId.equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw gatewayBookUnavailable();
        }
        var parameters = new MapSqlParameterSource().addValue("organisationId", organisationId)
                .addValue("schoolId", schoolId).addValue("businessDate", businessDate)
                .addValue("expectedYearId", expectedFinancialYearId, java.sql.Types.OTHER);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL
                  AND EXISTS(SELECT 1 FROM schools school JOIN organisations organisation
                    ON organisation.id=school.organisation_id AND organisation.status='ACTIVE'
                    WHERE school.id=:schoolId AND school.organisation_id=:organisationId AND school.status='ACTIVE')
                """, parameters, Boolean.class))) throw gatewayBookUnavailable();
        // Caller takes the fee accounting configuration fence first. Keep the
        // existing accounting lock order, preventing close/account edits during admission.
        jdbc.queryForObject("""
                SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked
                """, parameters.addValue("lockKey", "accounting:school:" + schoolId), Integer.class);
        var books = jdbc.query("""
                SELECT year.id AS financial_year_id,period.id AS accounting_period_id,
                  year.starts_on AS year_starts_on,year.ends_on AS year_ends_on,
                  year.accounting_basis,year.currency_code,
                  year.status AS financial_year_status,period.status AS accounting_period_status
                FROM accounting_financial_years year JOIN accounting_periods period
                  ON period.financial_year_id=year.id AND period.organisation_id=year.organisation_id
                  AND period.school_id=year.school_id
                WHERE year.organisation_id=:organisationId AND year.school_id=:schoolId
                  AND :businessDate BETWEEN year.starts_on AND year.ends_on
                  AND :businessDate BETWEEN period.starts_on AND period.ends_on
                  AND (CAST(:expectedYearId AS uuid) IS NULL OR year.id=:expectedYearId)
                FOR SHARE OF year,period
                """, parameters, (result, row) -> new FeeBookContext(
                result.getObject("financial_year_id", UUID.class), result.getObject("accounting_period_id", UUID.class),
                result.getObject("year_starts_on", LocalDate.class), result.getObject("year_ends_on", LocalDate.class),
                result.getString("accounting_basis"), result.getString("currency_code"),
                result.getString("financial_year_status"), result.getString("accounting_period_status")));
        if (books.size() != 1) throw gatewayBookUnavailable();
        var book = books.getFirst();
        if (!"OPEN".equals(book.financialYearStatus()) || !"OPEN".equals(book.accountingPeriodStatus())
                || !"INR".equals(book.currencyCode()) || !BASES.contains(book.accountingBasis())
                || book.accountingPeriodId() == null) throw gatewayBookUnavailable();
        if (requestedAccounts.size() > 103 || requestedAccounts.stream().distinct().count() != requestedAccounts.size()
                || requestedAccounts.stream().anyMatch(use -> use == null || use.accountId() == null || use.useType() == null)) {
            throw gatewayBookUnavailable();
        }
        if (!requestedAccounts.isEmpty()) {
            var ids = requestedAccounts.stream().map(FeeAccountUse::accountId).collect(Collectors.toSet());
            var accounts = jdbc.query("""
                    SELECT id,code,display_name,account_type,category,status,allow_direct_posting
                    FROM accounting_accounts WHERE organisation_id=:organisationId
                      AND school_id=:schoolId AND id IN(:accountIds) FOR SHARE
                    """, parameters.addValue("accountIds", ids), result -> {
                        Map<UUID, PostingAccount> values = new LinkedHashMap<>();
                        while (result.next()) {
                            var value = new PostingAccount(result.getObject("id", UUID.class), result.getString("code"),
                                    result.getString("display_name"), result.getString("account_type"),
                                    result.getString("category"), result.getString("status"), result.getBoolean("allow_direct_posting"));
                            values.put(value.id(), value);
                        }
                        return values;
                    });
            if (accounts.size() != ids.size() || requestedAccounts.stream().anyMatch(use -> {
                var account = accounts.get(use.accountId());
                return account == null || !"ACTIVE".equals(account.status()) || !account.direct()
                        || !feeAccountUseMatches(account, use.useType());
            })) throw gatewayBookUnavailable();
        }
        return book;
    }

    private static IllegalStateException gatewayBookUnavailable() {
        return new IllegalStateException("Gateway CREATE requires a current scoped open accounting book and approved active mappings");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FeeBookContext validateFeeRefundPosting(AuthenticatedUser user, UUID schoolId,
            UUID originalJournalId, UUID targetFinancialYearId, LocalDate postingOn,
            List<FeeAccountUse> originalAccounts) {
        requireRefundAccountingContext(user, schoolId);
        if (originalJournalId == null || targetFinancialYearId == null || postingOn == null
                || originalAccounts == null || originalAccounts.isEmpty() || originalAccounts.size() > 102
                || originalAccounts.stream().anyMatch(use -> use == null || use.accountId() == null || use.useType() == null)
                || originalAccounts.stream().distinct().count() != originalAccounts.size()) throw refundPostingUnavailable();
        Scope scope = requireCapability(user, schoolId, "accounting.prepare");
        // Finance takes its existing operation/configuration fences before this
        // accounting fence. Reversal requests and book/account edits use it too.
        lockSchool(scope);
        requireRefundReadCommitted(scope);
        requireLiveRefundAccountingActor(user, scope, "accounting.prepare");
        OriginalRefundJournal original = originalRefundJournal(scope, originalJournalId);
        FeeBookContext book = validateFeeBook(scope, targetFinancialYearId, postingOn, originalAccounts, true);
        if (!original.basis().equals(book.accountingBasis()) || !original.currency().equals(book.currencyCode())
                || !"INR".equals(book.currencyCode())) throw refundPostingUnavailable();
        return book;
    }

    private OriginalRefundJournal originalRefundJournal(Scope scope, UUID journalId) {
        return originalRefundJournal(scope.params(), journalId);
    }

    private OriginalRefundJournal originalRefundJournal(MapSqlParameterSource parameters, UUID journalId) {
        var rows = jdbc.query("""
                /* reviewed-refund-original-journal */
                SELECT original.status,original.reversal_of_journal_id,year.accounting_basis,year.currency_code,
                  EXISTS(SELECT 1 FROM accounting_journals reversal
                    WHERE reversal.reversal_of_journal_id=original.id
                      AND reversal.organisation_id=original.organisation_id AND reversal.school_id=original.school_id) has_reversal,
                  EXISTS(SELECT 1 FROM accounting_reversal_requests pending
                    WHERE pending.accounting_journal_id=original.id AND pending.organisation_id=original.organisation_id
                      AND pending.school_id=original.school_id AND pending.status='PENDING') has_pending_reversal
                FROM accounting_journals original JOIN accounting_financial_years year
                  ON year.id=original.financial_year_id AND year.organisation_id=original.organisation_id
                  AND year.school_id=original.school_id
                WHERE original.id=:originalJournalId AND original.organisation_id=:organisationId
                  AND original.school_id=:schoolId AND original.source_module='FEES'
                FOR SHARE OF original
                """, parameters.addValue("originalJournalId", journalId), (row, number) -> {
                    if (!"POSTED".equals(row.getString("status")) || row.getObject("reversal_of_journal_id") != null
                            || row.getBoolean("has_reversal") || row.getBoolean("has_pending_reversal")) throw refundPostingUnavailable();
                    return new OriginalRefundJournal(row.getString("accounting_basis"), row.getString("currency_code"));
                });
        if (rows.size() != 1 || !BASES.contains(rows.getFirst().basis()) || !"INR".equals(rows.getFirst().currency())) {
            throw refundPostingUnavailable();
        }
        return rows.getFirst();
    }

    private void requireRefundAccountingContext(AuthenticatedUser user, UUID schoolId) {
        var context = TenantRequestContext.current().orElse(null);
        if (user == null || user.userId() == null || user.activeOrganisationId() == null || schoolId == null
                || user.membershipIds().isEmpty() || context == null || !user.userId().equals(context.userId())
                || !user.activeOrganisationId().equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw forbidden("FEE_REFUND_ACCOUNTING_SCOPE_DENIED", "Reviewed refund accounting requires this session and a writable transaction.");
        }
    }

    private void requireLiveRefundAccountingActor(AuthenticatedUser user, Scope scope, String capability) {
        requireRefundAccountingContext(user, scope.schoolId());
        Boolean allowed = jdbc.queryForObject("""
                /* reviewed-refund-accounting-actor */
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id()=:userId
                  AND EXISTS(SELECT 1 FROM memberships membership
                    JOIN user_accounts actor ON actor.id=membership.user_id AND actor.status='ACTIVE'
                    JOIN schools school ON school.id=membership.school_id AND school.organisation_id=membership.organisation_id AND school.status='ACTIVE'
                    JOIN organisations organisation ON organisation.id=membership.organisation_id AND organisation.status='ACTIVE'
                    WHERE membership.id=:actorMembershipId AND membership.id IN(:membershipIds)
                      AND membership.user_id=:userId AND membership.organisation_id=:organisationId AND membership.school_id=:schoolId
                      AND membership.status='ACTIVE' AND membership.valid_from<=clock_timestamp()
                      AND (membership.valid_until IS NULL OR membership.valid_until>clock_timestamp())
                      AND EXISTS(SELECT 1 FROM membership_roles grant_role JOIN roles role ON role.id=grant_role.role_id
                        JOIN role_capabilities grant_capability ON grant_capability.role_id=role.id
                        WHERE grant_role.membership_id=membership.id AND grant_capability.capability_code=:capability
                          AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)))
                """, scope.params().addValue("userId", user.userId()).addValue("membershipIds", user.membershipIds())
                .addValue("capability", capability), Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) throw forbidden("FEE_REFUND_ACCOUNTING_SCOPE_DENIED", "Refund accounting access is unavailable for this school and session.");
    }

    private void requireRefundReadCommitted(Scope scope) {
        String isolation = jdbc.queryForObject("SELECT current_setting('transaction_isolation')", scope.params(), String.class);
        if (!"read committed".equals(isolation)) {
            throw conflict("FEE_REFUND_ACCOUNTING_RETRY_REQUIRED", "Retry reviewed refund accounting in a fresh read-committed transaction.");
        }
    }

    private static ApiException refundPostingUnavailable() {
        return conflict("FEE_REFUND_ACCOUNTING_UNAVAILABLE", "The original posted journal or current refund accounting book requires review.");
    }

    @Override
    @Transactional
    public AccountingMutationResponse prepareFeeJournal(AuthenticatedUser user,
            FeeJournalRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.prepare");
        lockSchool(scope);
        List<JournalLineRequest> lines = request.lines().stream()
                .map(line -> new JournalLineRequest(line.accountId(), null,
                        line.learnerFeeAccountId(), line.debitAmount(), line.creditAmount(),
                        line.memo()))
                .toList();
        JournalRequest journal = new JournalRequest(request.schoolId(), request.financialYearId(),
                request.accountingPeriodId(), request.voucherType(), request.postingDate(),
                request.reference(), request.narration(), lines);
        AccountingMutationResponse draft = createJournal(scope, user, request.idempotencyKey(),
                journal, "FEES", request.feePostingBatchId());
        if (draft.rowVersion() > 0) {
            return draft;
        }
        return submitJournal(user, draft.id(), new VersionedReasonRequest(request.schoolId(),
                draft.rowVersion(), request.submissionReason()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> feeJournalStatuses(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds) {
        Scope scope = requireScope(user, schoolId);
        if (journalIds == null || journalIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> unique = Set.copyOf(journalIds);
        return Map.copyOf(jdbc.query("""
                SELECT id,status FROM accounting_journals
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND source_module='FEES' AND id IN (:journalIds)
                """, scope.params().addValue("journalIds", unique), result -> {
                    Map<UUID, String> statuses = new LinkedHashMap<>();
                    while (result.next()) {
                        statuses.put(result.getObject("id", UUID.class),
                                result.getString("status"));
                    }
                    return statuses;
                }));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> feeRefundJournalStatuses(AuthenticatedUser user, UUID schoolId, List<UUID> journalIds) {
        var context = TenantRequestContext.current().orElse(null);
        if (user == null || user.userId() == null || user.activeOrganisationId() == null || schoolId == null
                || user.membershipIds().isEmpty() || context == null || !user.userId().equals(context.userId())
                || !user.activeOrganisationId().equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()) throw refundStatusDenied();
        if (journalIds == null || journalIds.size() > 200 || journalIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw bad("FEE_REFUND_JOURNAL_STATUS_INVALID", "Choose at most 200 refund journal identifiers.");
        }
        var p = new MapSqlParameterSource().addValue("organisationId", user.activeOrganisationId()).addValue("schoolId", schoolId)
                .addValue("userId", user.userId()).addValue("membershipIds", user.membershipIds());
        String liveReader = """
                current_app_organisation_id()=:organisationId AND current_app_user_id()=:userId
                  AND EXISTS(SELECT 1 FROM memberships membership
                    JOIN user_accounts actor ON actor.id=membership.user_id AND actor.status='ACTIVE'
                    JOIN schools school ON school.id=membership.school_id AND school.organisation_id=membership.organisation_id AND school.status='ACTIVE'
                    JOIN organisations organisation ON organisation.id=membership.organisation_id AND organisation.status='ACTIVE'
                    JOIN membership_roles grant_role ON grant_role.membership_id=membership.id
                    JOIN roles role ON role.id=grant_role.role_id AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                    JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code='fees.read'
                    WHERE membership.id IN(:membershipIds) AND membership.user_id=:userId
                      AND membership.organisation_id=:organisationId AND membership.school_id=:schoolId
                      AND membership.status='ACTIVE' AND membership.valid_from<=clock_timestamp()
                      AND (membership.valid_until IS NULL OR membership.valid_until>clock_timestamp()))
                """;
        Boolean allowed = jdbc.queryForObject("/* fee-refund-status-live-reader */ SELECT " + liveReader, p, Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) throw refundStatusDenied();
        if (journalIds.isEmpty()) return Map.of();
        return Map.copyOf(jdbc.query("""
                /* fee-refund-status-only */
                SELECT journal.id,journal.status FROM accounting_journals journal
                JOIN fee_accounting_posting_batches batch ON batch.id=journal.source_id
                  AND batch.accounting_journal_id=journal.id AND batch.organisation_id=journal.organisation_id
                  AND batch.school_id=journal.school_id AND batch.event_type='REFUND' AND batch.refund_posting_id IS NOT NULL
                WHERE journal.organisation_id=:organisationId AND journal.school_id=:schoolId
                  AND journal.source_module='FEES' AND journal.reversal_of_journal_id IS NULL AND journal.id IN(:journalIds)
                """ + " AND (" + liveReader + ")", p.addValue("journalIds", new LinkedHashSet<>(journalIds)), rows -> {
                    Map<UUID, String> statuses = new LinkedHashMap<>();
                    while (rows.next()) statuses.put(rows.getObject("id", UUID.class), rows.getString("status"));
                    return statuses;
                }));
    }

    private static ApiException refundStatusDenied() {
        return forbidden("FEE_REFUND_JOURNAL_STATUS_DENIED", "Refund accounting status is unavailable for this school and session.");
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, FeeJournalEvidence> feeJournalEvidence(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds) {
        Scope scope = requireScope(user, schoolId);
        if (journalIds == null || journalIds.isEmpty()) return Map.of();
        if (journalIds.size() > MAX_FEE_EVIDENCE_IDS) {
            throw bad("FEE_JOURNAL_EVIDENCE_TOO_LARGE", "Narrow the fee journal evidence request.");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (UUID id : journalIds) {
            if (id == null) {
                throw bad("FEE_JOURNAL_EVIDENCE_INVALID", "Fee journal identifiers must not be null.");
            }
            ids.add(id);
        }
        return readFeeJournalEvidence(scope.params(), ids);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FeeJournalEvidence gatewayOriginalPaymentJournalEvidence(UUID organisationId, UUID schoolId,
            UUID paymentId, UUID postingBatchId, UUID journalId) {
        if (paymentId == null || postingBatchId == null || journalId == null) throw gatewayOriginalJournalUnavailable();
        var parameters = new MapSqlParameterSource().addValue("organisationId", organisationId)
                .addValue("schoolId", schoolId).addValue("paymentId", paymentId)
                .addValue("batchId", postingBatchId).addValue("journalId", journalId);
        requireGatewayOriginalJournalContext(organisationId, schoolId, parameters);
        jdbc.queryForObject("/* gateway-original-journal-fence */ SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked",
                parameters.addValue("lockKey", "accounting:school:" + schoolId), Integer.class);
        // A stale repeatable-read snapshot cannot prove absence of a newly pending
        // reversal merely by acquiring the same advisory lock later.
        requireGatewayOriginalJournalContext(organisationId, schoolId, parameters);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                /* gateway-original-payment-source */
                SELECT count(*)=1 FROM fee_payments payment
                JOIN learner_fee_accounts account ON account.id=payment.learner_fee_account_id
                  AND account.organisation_id=payment.organisation_id AND account.school_id=payment.school_id
                JOIN fee_ledger_entries ledger ON ledger.id=payment.ledger_entry_id
                  AND ledger.organisation_id=payment.organisation_id AND ledger.school_id=payment.school_id
                JOIN fee_accounting_posting_items item ON item.fee_ledger_entry_id=ledger.id
                  AND item.organisation_id=ledger.organisation_id AND item.school_id=ledger.school_id
                JOIN fee_accounting_posting_batches batch ON batch.id=item.posting_batch_id
                  AND batch.organisation_id=item.organisation_id AND batch.school_id=item.school_id
                JOIN accounting_journals journal ON journal.id=batch.accounting_journal_id
                  AND journal.organisation_id=batch.organisation_id AND journal.school_id=batch.school_id
                WHERE payment.id=:paymentId AND payment.organisation_id=:organisationId AND payment.school_id=:schoolId
                  AND payment.status='POSTED' AND payment.payment_method='ONLINE_PROVIDER' AND payment.gateway_capture_id IS NOT NULL
                  AND ledger.entry_type='PAYMENT' AND ledger.direction='CREDIT' AND ledger.source_type='FEE_PAYMENT'
                  AND ledger.source_id=payment.id AND ledger.learner_fee_account_id=payment.learner_fee_account_id
                  AND ledger.amount=payment.amount AND account.currency_code='INR'
                  AND item.status='ACTIVE' AND batch.id=:batchId AND batch.event_type='PAYMENT'
                  AND batch.disposition='JOURNAL' AND batch.source_count BETWEEN 1 AND 200
                  AND journal.id=:journalId AND journal.source_module='FEES' AND journal.source_id=batch.id
                  AND journal.financial_year_id=batch.financial_year_id AND journal.accounting_period_id=batch.accounting_period_id
                  AND journal.posting_date=batch.business_date AND ledger.effective_on=batch.business_date
                """, parameters, Boolean.class))) throw gatewayOriginalJournalUnavailable();
        var original = originalRefundJournal(parameters, journalId);
        var journals = readFeeJournalEvidence(parameters, Set.of(journalId));
        var evidence = journals.get(journalId);
        if (journals.size() != 1 || evidence == null || !journalId.equals(evidence.id())
                || !postingBatchId.equals(evidence.sourceId()) || !"POSTED".equals(evidence.status())
                || !original.basis().equals(evidence.accountingBasis()) || !original.currency().equals(evidence.currencyCode())
                || evidence.reversalId() != null || evidence.reversalStatus() != null || !evidence.reversalLines().isEmpty()
                || evidence.lines().size() < 2 || evidence.lines().size() > 500) throw gatewayOriginalJournalUnavailable();
        return evidence;
    }

    private void requireGatewayOriginalJournalContext(UUID organisationId, UUID schoolId, MapSqlParameterSource parameters) {
        var context = TenantRequestContext.current().orElse(null);
        if (organisationId == null || schoolId == null || context == null || context.userId() != null
                || !organisationId.equals(context.organisationId()) || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw gatewayOriginalJournalUnavailable();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                /* gateway-original-journal-context */
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL
                  AND current_setting('transaction_isolation')='read committed'
                  AND current_setting('transaction_read_only')='off'
                  AND EXISTS(SELECT 1 FROM schools school JOIN organisations organisation
                    ON organisation.id=school.organisation_id AND organisation.status='ACTIVE'
                    WHERE school.id=:schoolId AND school.organisation_id=:organisationId AND school.status='ACTIVE')
                """, parameters, Boolean.class))) throw gatewayOriginalJournalUnavailable();
    }

    private static IllegalStateException gatewayOriginalJournalUnavailable() {
        return new IllegalStateException("Exact original payment journal requires scoped background verification");
    }

    private Map<UUID, FeeJournalEvidence> readFeeJournalEvidence(MapSqlParameterSource parameters, Set<UUID> ids) {
        // Headers, complete normalized lines and the overflow check share one SQL snapshot.
        // An oversized result returns headers only so no partial journal evidence can escape.
        return Map.copyOf(jdbc.query("""
                WITH selected AS MATERIALIZED (
                  SELECT j.id,j.source_id,j.financial_year_id,j.accounting_period_id,
                         j.posting_date,j.status,y.accounting_basis,y.currency_code,
                         y.status financial_year_status,p.status accounting_period_status,
                         reversal.id reversal_id,reversal.status reversal_status
                  FROM accounting_journals j
                  JOIN accounting_financial_years y ON y.id=j.financial_year_id
                    AND y.organisation_id=j.organisation_id AND y.school_id=j.school_id
                  JOIN accounting_periods p ON p.id=j.accounting_period_id
                    AND p.financial_year_id=j.financial_year_id
                    AND p.organisation_id=j.organisation_id AND p.school_id=j.school_id
                  LEFT JOIN accounting_journals reversal ON reversal.reversal_of_journal_id=j.id
                    AND reversal.organisation_id=j.organisation_id AND reversal.school_id=j.school_id
                  WHERE j.organisation_id=:organisationId AND j.school_id=:schoolId
                    AND j.source_module='FEES' AND j.reversal_of_journal_id IS NULL
                    AND j.id IN (:ids)
                ), normalized AS MATERIALIZED (
                  SELECT l.accounting_journal_id,l.account_id,l.vendor_id,l.learner_fee_account_id,
                         sum(l.debit_amount) debit_amount,sum(l.credit_amount) credit_amount
                  FROM accounting_journal_lines l
                  WHERE l.organisation_id=:organisationId AND l.school_id=:schoolId
                    AND l.accounting_journal_id IN (
                      SELECT id FROM selected UNION SELECT reversal_id FROM selected
                      WHERE reversal_id IS NOT NULL
                    )
                  GROUP BY l.accounting_journal_id,l.account_id,l.vendor_id,l.learner_fee_account_id
                ), budget AS (
                  SELECT count(*) normalized_group_count FROM normalized
                )
                SELECT selected.*,budget.normalized_group_count,
                       line.accounting_journal_id line_journal_id,line.account_id,
                       line.vendor_id,line.learner_fee_account_id,line.debit_amount,line.credit_amount,
                       account.account_type,account.category account_category,
                       account.status account_status,account.allow_direct_posting
                FROM selected CROSS JOIN budget
                LEFT JOIN normalized line ON line.accounting_journal_id IN (selected.id,selected.reversal_id)
                  AND budget.normalized_group_count<=:maxGroups
                LEFT JOIN accounting_accounts account ON account.id=line.account_id
                  AND account.organisation_id=:organisationId AND account.school_id=:schoolId
                ORDER BY selected.id,(line.accounting_journal_id=selected.id) DESC,
                         line.account_id,line.vendor_id,line.learner_fee_account_id
                """, parameters.addValue("ids", ids).addValue("maxGroups", MAX_FEE_EVIDENCE_GROUPS), result -> {
                    Map<UUID, FeeJournalEvidenceAccumulator> accumulated = new LinkedHashMap<>();
                    while (result.next()) {
                        if (result.getLong("normalized_group_count") > MAX_FEE_EVIDENCE_GROUPS) {
                            throw bad("FEE_JOURNAL_EVIDENCE_TOO_LARGE",
                                    "Narrow the fee journal evidence request.");
                        }
                        UUID id = result.getObject("id", UUID.class);
                        FeeJournalEvidenceAccumulator journal = accumulated.get(id);
                        if (journal == null) {
                            journal = new FeeJournalEvidenceAccumulator(new FeeJournalEvidence(id,
                                    result.getObject("source_id", UUID.class),
                                    result.getObject("financial_year_id", UUID.class),
                                    result.getObject("accounting_period_id", UUID.class),
                                    result.getObject("posting_date", LocalDate.class),
                                    result.getString("accounting_basis"), result.getString("currency_code"),
                                    result.getString("status"), result.getString("financial_year_status"),
                                    result.getString("accounting_period_status"),
                                    result.getObject("reversal_id", UUID.class),
                                    result.getString("reversal_status"), List.of(), List.of()));
                            accumulated.put(id, journal);
                        }
                        UUID lineJournalId = result.getObject("line_journal_id", UUID.class);
                        if (lineJournalId != null) {
                            var line = new FeeJournalLineEvidence(result.getObject("account_id", UUID.class),
                                    result.getObject("vendor_id", UUID.class),
                                    result.getObject("learner_fee_account_id", UUID.class),
                                    result.getBigDecimal("debit_amount"), result.getBigDecimal("credit_amount"),
                                    result.getString("account_type"), result.getString("account_category"),
                                    result.getString("account_status"), result.getBoolean("allow_direct_posting"));
                            if (id.equals(lineJournalId)) journal.lines.add(line);
                            else journal.reversalLines.add(line);
                        }
                    }
                    Map<UUID, FeeJournalEvidence> evidence = new LinkedHashMap<>();
                    accumulated.forEach((id, journal) -> evidence.put(id, journal.complete()));
                    return evidence;
                }));
    }

    private static final class FeeJournalEvidenceAccumulator {
        private final FeeJournalEvidence header;
        private final List<FeeJournalLineEvidence> lines = new ArrayList<>();
        private final List<FeeJournalLineEvidence> reversalLines = new ArrayList<>();

        private FeeJournalEvidenceAccumulator(FeeJournalEvidence header) {
            this.header = header;
        }

        private FeeJournalEvidence complete() {
            return new FeeJournalEvidence(header.id(), header.sourceId(), header.financialYearId(),
                    header.accountingPeriodId(), header.postingDate(), header.accountingBasis(),
                    header.currencyCode(), header.status(), header.financialYearStatus(),
                    header.accountingPeriodStatus(), header.reversalId(), header.reversalStatus(),
                    lines, reversalLines);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollAccountingOptions payrollAccountingOptions(AuthenticatedUser user, UUID schoolId) {
        Scope scope = requireScope(user, schoolId);
        List<PayrollFinancialYearOption> years = jdbc.query("""
                SELECT id,code,display_name,starts_on,ends_on,accounting_basis,currency_code,status
                FROM accounting_financial_years
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND accounting_basis='ACCRUAL'
                ORDER BY starts_on DESC,id
                """, scope.params(), (result, row) -> new PayrollFinancialYearOption(
                result.getObject("id", UUID.class), result.getString("code"),
                result.getString("display_name"), result.getObject("starts_on", LocalDate.class),
                result.getObject("ends_on", LocalDate.class), result.getString("accounting_basis"),
                result.getString("currency_code"), result.getString("status")));
        List<PayrollAccountOption> accounts = jdbc.query("""
                SELECT id,code,display_name,account_type,category,status
                FROM accounting_accounts
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND allow_direct_posting=true
                  AND ((account_type='EXPENSE' AND category='EXPENSE')
                    OR (account_type='LIABILITY' AND category='PAYABLE')
                    OR (account_type='ASSET' AND category IN ('CASH','BANK','RECEIVABLE')))
                ORDER BY account_type,category,code,id
                """, scope.params(), (result, row) -> new PayrollAccountOption(
                result.getObject("id", UUID.class), result.getString("code"),
                result.getString("display_name"), result.getString("account_type"),
                result.getString("category"), result.getString("status")));
        return new PayrollAccountingOptions(scope.canConfigure(), scope.canPrepare(), scope.canPost(),
                years, accounts);
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollBookContext validatePayrollConfigurationDraft(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate effectiveFrom, List<PayrollAccountUse> accounts) {
        Scope scope = requireCapability(user, schoolId, "accounting.configure");
        lockSchool(scope);
        return validatePayrollBook(scope, financialYearId, effectiveFrom, accounts, false);
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollBookContext validatePayrollConfigurationDecision(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate effectiveFrom, List<PayrollAccountUse> accounts) {
        Scope scope = requireCapability(user, schoolId, "accounting.post");
        lockSchool(scope);
        return validatePayrollBook(scope, financialYearId, effectiveFrom, accounts, false);
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollBookContext resolvePayrollPostingBook(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate postingDate) {
        Scope scope = requireCapability(user, schoolId, "accounting.prepare");
        return validatePayrollBook(scope, financialYearId, postingDate, List.of(), true);
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollBookContext validatePayrollAdvanceDraft(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate issueDate, List<PayrollAccountUse> accounts) {
        Scope scope = requireCapability(user, schoolId, "accounting.prepare");
        lockSchool(scope);
        return validatePayrollBook(scope, financialYearId, issueDate, accounts, true);
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollBookContext validatePayrollAdvanceDecision(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate issueDate, List<PayrollAccountUse> accounts) {
        Scope scope = requireCapability(user, schoolId, "accounting.post");
        lockSchool(scope);
        return validatePayrollBook(scope, financialYearId, issueDate, accounts, true);
    }

    @Override
    @Transactional
    public AccountingMutationResponse preparePayrollJournal(AuthenticatedUser user,
            PayrollJournalRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.prepare");
        lockSchool(scope);
        List<JournalLineRequest> lines = request.lines().stream()
                .map(line -> new JournalLineRequest(line.accountId(), null, null,
                        line.debitAmount(), line.creditAmount(), line.memo()))
                .toList();
        JournalRequest journal = new JournalRequest(request.schoolId(), request.financialYearId(),
                request.accountingPeriodId(), request.voucherType(), request.postingDate(),
                request.reference(), request.narration(), lines);
        AccountingMutationResponse draft = createJournal(scope, user, request.idempotencyKey(),
                journal, "PAYROLL", request.payrollPostingBatchId());
        if (draft.rowVersion() > 0) {
            return draft;
        }
        return submitJournal(user, draft.id(), new VersionedReasonRequest(request.schoolId(),
                draft.rowVersion(), request.submissionReason()));
    }

    @Override
    @Transactional
    public AccountingMutationResponse preparePayrollAdvanceJournal(AuthenticatedUser user,
            PayrollAdvanceJournalRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.prepare");
        lockSchool(scope);
        List<JournalLineRequest> lines = request.lines().stream()
                .map(line -> new JournalLineRequest(line.accountId(), null, null,
                        line.debitAmount(), line.creditAmount(), line.memo()))
                .toList();
        JournalRequest journal = new JournalRequest(request.schoolId(), request.financialYearId(),
                request.accountingPeriodId(), request.voucherType(), request.postingDate(),
                request.reference(), request.narration(), lines);
        AccountingMutationResponse draft = createJournal(scope, user, request.idempotencyKey(),
                journal, "PAYROLL_ADVANCE", request.advancePostingId());
        if (draft.rowVersion() > 0) {
            return draft;
        }
        return submitJournal(user, draft.id(), new VersionedReasonRequest(request.schoolId(),
                draft.rowVersion(), request.submissionReason()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> payrollJournalStatuses(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds) {
        Scope scope = requireScope(user, schoolId);
        if (journalIds == null || journalIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> unique = Set.copyOf(journalIds);
        return Map.copyOf(jdbc.query("""
                SELECT id,status FROM accounting_journals
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND source_module='PAYROLL' AND id IN (:journalIds)
                """, scope.params().addValue("journalIds", unique), result -> {
                    Map<UUID, String> statuses = new LinkedHashMap<>();
                    while (result.next()) {
                        statuses.put(result.getObject("id", UUID.class), result.getString("status"));
                    }
                    return statuses;
                }));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> payrollAdvanceJournalStatuses(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds) {
        Scope scope = requireScope(user, schoolId);
        if (journalIds == null || journalIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> unique = Set.copyOf(journalIds);
        return Map.copyOf(jdbc.query("""
                SELECT id,status FROM accounting_journals
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND source_module='PAYROLL_ADVANCE' AND id IN (:journalIds)
                """, scope.params().addValue("journalIds", unique), result -> {
                    Map<UUID, String> statuses = new LinkedHashMap<>();
                    while (result.next()) {
                        statuses.put(result.getObject("id", UUID.class), result.getString("status"));
                    }
                    return statuses;
                }));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PayrollAdvanceRecoveryEvidence> payrollAdvanceRecoveryEvidence(
            AuthenticatedUser user, UUID schoolId, List<UUID> advanceIds) {
        Scope scope = requireScope(user, schoolId);
        if (advanceIds == null || advanceIds.isEmpty()) return Map.of();
        Set<UUID> unique = Set.copyOf(advanceIds);
        if (unique.size() > 10000) throw bad("PAYROLL_RECOVERY_QUERY_TOO_LARGE", "Narrow the employee advance search.");
        return Map.copyOf(jdbc.query("""
                SELECT recovery.employee_advance_id,
                  coalesce(sum(CASE WHEN recovery.reservation_status='ACTIVE' AND EXISTS (
                    SELECT 1 FROM payroll_accounting_posting_items item
                    JOIN payroll_accounting_posting_batches batch ON batch.id=item.posting_batch_id
                      AND batch.organisation_id=item.organisation_id AND batch.school_id=item.school_id
                      AND batch.posting_type='ACCRUAL'
                    JOIN accounting_journals journal ON journal.id=batch.accounting_journal_id
                      AND journal.organisation_id=batch.organisation_id AND journal.school_id=batch.school_id
                      AND journal.source_module='PAYROLL' AND journal.status='POSTED'
                    WHERE item.payroll_run_staff_result_id=recovery.payroll_run_staff_result_id
                      AND item.organisation_id=recovery.organisation_id
                      AND item.school_id=recovery.school_id AND item.posting_type='ACCRUAL'
                      AND item.status='ACTIVE'
                  ) THEN recovery.recovery_amount ELSE 0 END),0) recovered_amount,
                  count(*) FILTER (WHERE recovery.reservation_status='ACTIVE') active_reservations
                FROM payroll_run_advance_recoveries recovery
                WHERE recovery.organisation_id=:organisationId AND recovery.school_id=:schoolId
                  AND recovery.employee_advance_id IN (:advanceIds)
                GROUP BY recovery.employee_advance_id
                """, scope.params().addValue("advanceIds", unique), result -> {
                    Map<UUID, PayrollAdvanceRecoveryEvidence> evidence = new LinkedHashMap<>();
                    while (result.next()) evidence.put(result.getObject("employee_advance_id", UUID.class),
                            new PayrollAdvanceRecoveryEvidence(result.getBigDecimal("recovered_amount"),
                                    result.getInt("active_reservations")));
                    return evidence;
                }));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PayrollJournalEvidence> payrollJournalEvidence(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds) {
        return payrollJournalEvidence(user, schoolId, journalIds, "PAYROLL", "PAYROLL_REPORT_TOO_LARGE");
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PayrollJournalEvidence> payrollAdvanceJournalEvidence(AuthenticatedUser user,
            UUID schoolId, List<UUID> journalIds) {
        return payrollJournalEvidence(user, schoolId, journalIds, "PAYROLL_ADVANCE",
                "PAYROLL_ADVANCE_REPORT_TOO_LARGE");
    }

    private Map<UUID, PayrollJournalEvidence> payrollJournalEvidence(AuthenticatedUser user,
            UUID schoolId, List<UUID> journalIds, String sourceModule, String limitCode) {
        Scope scope = requireScope(user, schoolId);
        if (journalIds == null || journalIds.isEmpty()) return Map.of();
        Set<UUID> ids = Set.copyOf(journalIds);
        if (ids.size() > 10000) throw bad(limitCode, "Narrow the payroll report filters.");
        return Map.copyOf(jdbc.query("""
                SELECT j.id,j.source_id,j.financial_year_id,j.posting_date,y.currency_code,j.status,
                       coalesce(original.debit,0) debit,coalesce(original.credit,0) credit,
                       reversal.id reversal_id,reversal.status reversal_status,
                       coalesce(counter.debit,0) reversal_debit,coalesce(counter.credit,0) reversal_credit,
                       reversal.id IS NOT NULL AND NOT EXISTS (
                         SELECT l.account_id,l.vendor_id,l.learner_fee_account_id
                         FROM accounting_journal_lines l
                         WHERE l.organisation_id=j.organisation_id AND l.school_id=j.school_id
                           AND l.accounting_journal_id IN (j.id,reversal.id)
                         GROUP BY l.account_id,l.vendor_id,l.learner_fee_account_id
                         HAVING sum(l.debit_amount-l.credit_amount)<>0
                       ) reversal_offsets_accounts
                FROM accounting_journals j
                JOIN accounting_financial_years y ON y.id=j.financial_year_id
                  AND y.organisation_id=j.organisation_id AND y.school_id=j.school_id
                LEFT JOIN accounting_journals reversal ON reversal.reversal_of_journal_id=j.id
                  AND reversal.organisation_id=j.organisation_id AND reversal.school_id=j.school_id
                LEFT JOIN LATERAL (
                  SELECT sum(l.debit_amount) debit,sum(l.credit_amount) credit
                  FROM accounting_journal_lines l WHERE l.accounting_journal_id=j.id
                    AND l.organisation_id=j.organisation_id AND l.school_id=j.school_id
                ) original ON true
                LEFT JOIN LATERAL (
                  SELECT sum(l.debit_amount) debit,sum(l.credit_amount) credit
                  FROM accounting_journal_lines l WHERE l.accounting_journal_id=reversal.id
                    AND l.organisation_id=j.organisation_id AND l.school_id=j.school_id
                ) counter ON true
                WHERE j.organisation_id=:organisationId AND j.school_id=:schoolId
                  AND j.source_module=:sourceModule AND j.reversal_of_journal_id IS NULL
                  AND j.id IN (:ids)
                """, scope.params().addValue("ids", ids).addValue("sourceModule", sourceModule), result -> {
                    Map<UUID, PayrollJournalEvidence> evidence = new LinkedHashMap<>();
                    while (result.next()) {
                        UUID id = result.getObject("id", UUID.class);
                        evidence.put(id, new PayrollJournalEvidence(id, result.getObject("source_id", UUID.class),
                                result.getObject("financial_year_id", UUID.class),
                                result.getObject("posting_date", LocalDate.class), result.getString("currency_code"),
                                result.getString("status"), result.getBigDecimal("debit"), result.getBigDecimal("credit"),
                                result.getObject("reversal_id", UUID.class), result.getString("reversal_status"),
                                result.getBigDecimal("reversal_debit"), result.getBigDecimal("reversal_credit"),
                                result.getBoolean("reversal_offsets_accounts")));
                    }
                    return evidence;
                }));
    }

    private PayrollBookContext validatePayrollBook(Scope scope, UUID financialYearId,
            LocalDate effectiveDate, List<PayrollAccountUse> requestedAccounts,
            boolean requireOpenPeriod) {
        if (financialYearId == null || effectiveDate == null) {
            throw bad("PAYROLL_ACCOUNTING_BOOK_REQUIRED",
                    "Choose an accrual financial year and effective date.");
        }
        List<PayrollBookContext> books = jdbc.query("""
                SELECT year.id AS financial_year_id,period.id AS accounting_period_id,
                       year.starts_on AS year_starts_on,year.ends_on AS year_ends_on,
                       year.accounting_basis,year.currency_code,
                       year.status AS financial_year_status,period.status AS accounting_period_status
                FROM accounting_financial_years year
                LEFT JOIN accounting_periods period
                  ON period.financial_year_id=year.id
                 AND period.organisation_id=year.organisation_id
                 AND period.school_id=year.school_id
                 AND :effectiveDate BETWEEN period.starts_on AND period.ends_on
                WHERE year.id=:financialYearId AND year.organisation_id=:organisationId
                  AND year.school_id=:schoolId
                ORDER BY period.period_number
                """, scope.params().addValue("financialYearId", financialYearId)
                .addValue("effectiveDate", effectiveDate), (result, row) -> new PayrollBookContext(
                result.getObject("financial_year_id", UUID.class),
                result.getObject("accounting_period_id", UUID.class),
                result.getObject("year_starts_on", LocalDate.class),
                result.getObject("year_ends_on", LocalDate.class),
                result.getString("accounting_basis"), result.getString("currency_code"),
                result.getString("financial_year_status"),
                result.getString("accounting_period_status")));
        if (books.isEmpty()) {
            throw notFound("FINANCIAL_YEAR_NOT_FOUND", "The financial year was not found.");
        }
        PayrollBookContext book = books.getFirst();
        if (!"ACCRUAL".equals(book.accountingBasis())) {
            throw bad("PAYROLL_ACCRUAL_BOOK_REQUIRED",
                    "Payroll obligations require an accrual accounting financial year.");
        }
        if (effectiveDate.isBefore(book.startsOn()) || effectiveDate.isAfter(book.endsOn())) {
            throw bad("PAYROLL_ACCOUNTING_DATE_OUTSIDE_YEAR",
                    "The payroll posting date must be inside the selected financial year.");
        }
        if (book.accountingPeriodId() == null) {
            throw conflict("ACCOUNTING_PERIOD_REQUIRED",
                    "Generate the accounting periods that cover this payroll date.");
        }
        if (requireOpenPeriod && (!"OPEN".equals(book.financialYearStatus())
                || !"OPEN".equals(book.accountingPeriodStatus()))) {
            throw conflict("ACCOUNTING_PERIOD_NOT_OPEN",
                    "Payroll can be prepared only inside an open accounting period.");
        }
        validatePayrollPostingAccounts(scope,
                requestedAccounts == null ? List.of() : requestedAccounts);
        return book;
    }

    private void validatePayrollPostingAccounts(Scope scope, List<PayrollAccountUse> requested) {
        if (requested.isEmpty()) {
            return;
        }
        if (requested.stream().anyMatch(item -> item == null || item.accountId() == null
                || item.useType() == null)) {
            throw bad("PAYROLL_ACCOUNT_MAPPING_INVALID",
                    "Every payroll mapping requires an account and use.");
        }
        Set<UUID> ids = requested.stream().map(PayrollAccountUse::accountId)
                .collect(Collectors.toSet());
        Map<UUID, PostingAccount> accounts = postingAccounts(scope, ids);
        if (accounts.size() != ids.size()) {
            throw bad("PAYROLL_ACCOUNT_SCOPE_INVALID",
                    "Every payroll mapping must use this school's chart of accounts.");
        }
        for (PayrollAccountUse requestedUse : requested) {
            PostingAccount account = accounts.get(requestedUse.accountId());
            if (!"ACTIVE".equals(account.status()) || !account.direct()
                    || !payrollAccountUseMatches(account, requestedUse.useType())) {
                throw bad("PAYROLL_ACCOUNT_MAPPING_INVALID",
                        "Each payroll mapping must use an active posting account of the required type.");
            }
        }
    }

    private static boolean payrollAccountUseMatches(PostingAccount account,
            PayrollAccountUseType useType) {
        return switch (useType) {
            case SALARY_PAYABLE, LIABILITY -> "LIABILITY".equals(account.type())
                    && "PAYABLE".equals(account.category());
            case EXPENSE -> "EXPENSE".equals(account.type())
                    && "EXPENSE".equals(account.category());
            case RECEIVABLE -> "ASSET".equals(account.type())
                    && "RECEIVABLE".equals(account.category());
            case CASH -> "ASSET".equals(account.type()) && "CASH".equals(account.category());
            case BANK -> "ASSET".equals(account.type()) && "BANK".equals(account.category());
        };
    }

    private FeeBookContext validateFeeBook(Scope scope, UUID financialYearId,
            LocalDate effectiveDate, List<FeeAccountUse> requestedAccounts,
            boolean requireOpenPeriod) {
        if (financialYearId == null || effectiveDate == null) {
            throw bad("FEE_ACCOUNTING_BOOK_REQUIRED",
                    "Choose a financial year and an effective date.");
        }
        List<FeeBookContext> books = jdbc.query("""
                SELECT year.id AS financial_year_id,period.id AS accounting_period_id,
                       year.starts_on AS year_starts_on,year.ends_on AS year_ends_on,
                       year.accounting_basis,year.currency_code,
                       year.status AS financial_year_status,period.status AS accounting_period_status
                FROM accounting_financial_years year
                LEFT JOIN accounting_periods period
                  ON period.financial_year_id=year.id
                 AND period.organisation_id=year.organisation_id
                 AND period.school_id=year.school_id
                 AND :effectiveDate BETWEEN period.starts_on AND period.ends_on
                WHERE year.id=:financialYearId AND year.organisation_id=:organisationId
                  AND year.school_id=:schoolId
                ORDER BY period.period_number
                """, scope.params().addValue("financialYearId", financialYearId)
                .addValue("effectiveDate", effectiveDate), (result, row) -> new FeeBookContext(
                result.getObject("financial_year_id", UUID.class),
                result.getObject("accounting_period_id", UUID.class),
                result.getObject("year_starts_on", LocalDate.class),
                result.getObject("year_ends_on", LocalDate.class),
                result.getString("accounting_basis"), result.getString("currency_code"),
                result.getString("financial_year_status"),
                result.getString("accounting_period_status")));
        if (books.isEmpty()) {
            throw notFound("FINANCIAL_YEAR_NOT_FOUND", "The financial year was not found.");
        }
        FeeBookContext book = books.getFirst();
        if (effectiveDate.isBefore(book.startsOn()) || effectiveDate.isAfter(book.endsOn())) {
            throw bad("FEE_ACCOUNTING_DATE_OUTSIDE_YEAR",
                    "The effective date must be inside the selected financial year.");
        }
        if (book.accountingPeriodId() == null) {
            throw conflict("ACCOUNTING_PERIOD_REQUIRED",
                    "Generate the accounting periods that cover this effective date.");
        }
        if (requireOpenPeriod && (!"OPEN".equals(book.financialYearStatus())
                || !"OPEN".equals(book.accountingPeriodStatus()))) {
            throw conflict("ACCOUNTING_PERIOD_NOT_OPEN",
                    "Fee events can be prepared only inside an open accounting period.");
        }
        validateFeePostingAccounts(scope,
                requestedAccounts == null ? List.of() : requestedAccounts);
        return book;
    }

    private void validateFeePostingAccounts(Scope scope, List<FeeAccountUse> requested) {
        if (requested.isEmpty()) {
            return;
        }
        if (requested.stream().anyMatch(item -> item == null || item.accountId() == null
                || item.useType() == null)) {
            throw bad("FEE_ACCOUNT_MAPPING_INVALID", "Every fee mapping requires an account and use.");
        }
        Set<UUID> ids = requested.stream().map(FeeAccountUse::accountId)
                .collect(Collectors.toSet());
        Map<UUID, PostingAccount> accounts = postingAccounts(scope, ids);
        if (accounts.size() != ids.size()) {
            throw bad("FEE_ACCOUNT_SCOPE_INVALID",
                    "Every fee mapping must use this school's chart of accounts.");
        }
        for (FeeAccountUse requestedUse : requested) {
            PostingAccount account = accounts.get(requestedUse.accountId());
            if (!"ACTIVE".equals(account.status()) || !account.direct()
                    || !feeAccountUseMatches(account, requestedUse.useType())) {
                throw bad("FEE_ACCOUNT_MAPPING_INVALID",
                        "Each fee mapping must use an active posting account of the required type.");
            }
        }
    }

    private static boolean feeAccountUseMatches(PostingAccount account,
            FeeAccountUseType useType) {
        return switch (useType) {
            case RECEIVABLE -> "ASSET".equals(account.type())
                    && "RECEIVABLE".equals(account.category());
            case CONCESSION -> "EXPENSE".equals(account.type())
                    && "EXPENSE".equals(account.category());
            case INCOME -> "INCOME".equals(account.type())
                    && "INCOME".equals(account.category());
            case CASH -> "ASSET".equals(account.type())
                    && "CASH".equals(account.category());
            case BANK -> "ASSET".equals(account.type())
                    && "BANK".equals(account.category());
            case ONLINE_TENDER -> "ASSET".equals(account.type())
                    && Set.of("BANK", "OTHER").contains(account.category());
            case ONLINE_CLEARING -> "ASSET".equals(account.type())
                    && "OTHER".equals(account.category());
        };
    }

    @Transactional
    public AccountingMutationResponse submitJournal(AuthenticatedUser user, UUID id,
            VersionedReasonRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.prepare");
        lockSchool(scope);
        StoredJournal journal = lockJournal(scope, id);
        if (!"DRAFT".equals(journal.status()) || journal.version() != request.expectedVersion()) {
            throw conflict("JOURNAL_NOT_SUBMITTABLE", "Refresh the draft voucher and try again.");
        }
        if (!journal.creatorId().equals(scope.actorMembershipId())) {
            throw forbidden("JOURNAL_CREATOR_REQUIRED", "Only the voucher maker can submit this draft.");
        }
        JournalTotals totals = totals(id, scope);
        if (totals.lineCount() < 2 || totals.debit().signum() <= 0
                || totals.debit().compareTo(totals.credit()) != 0) {
            throw bad("JOURNAL_NOT_BALANCED", "Total debits must equal total credits and be greater than zero.");
        }
        int changed = safe("JOURNAL_SUBMIT_CONFLICT", () -> jdbc.update("""
                UPDATE accounting_journals SET status='SUBMITTED',line_count=:lineCount,
                  total_amount=:total,submitted_by_membership_id=:actorMembershipId,
                  submitted_at=:now,submission_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("lineCount", totals.lineCount())
                .addValue("total", totals.debit()).addValue("version", request.expectedVersion())
                .addValue("reason", reason(request.reason())).addValue("now", Instant.now(clock))));
        changed(changed);
        journalHistory(scope, id, "DRAFT", "SUBMITTED", request.reason());
        audit(user, scope, "accounting.journal.submitted", "ACCOUNTING_JOURNAL", id, request.reason());
        return new AccountingMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public AccountingMutationResponse decideJournal(AuthenticatedUser user, UUID id,
            DecisionRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.post");
        lockSchool(scope);
        StoredJournal journal = lockJournal(scope, id);
        if (!"SUBMITTED".equals(journal.status()) || journal.version() != request.expectedVersion()) {
            throw conflict("JOURNAL_NOT_DECIDABLE", "Refresh the submitted voucher and try again.");
        }
        if (scope.actorMembershipId().equals(journal.submitterId())) {
            throw forbidden("INDEPENDENT_JOURNAL_DECISION_REQUIRED",
                    "A different authorized person must decide this voucher.");
        }
        String decision = enumValue(request.decision(), Set.of("POST", "REJECT"), "journal decision");
        if ("GATEWAY_SETTLEMENT".equals(journal.sourceModule())) {
            requireRefundReadCommitted(scope);
            requireLiveRefundAccountingActor(user,scope,"accounting.post");
            safe("GATEWAY_SETTLEMENT_DECISION_CONFLICT", () -> jdbc.queryForObject("""
                    SELECT 1 FROM (SELECT assert_gateway_settlement_journal_decision(:journalId,:membershipId,:decision)) checked
                    """,scope.params().addValue("journalId",journal.id()).addValue("membershipId",scope.actorMembershipId())
                    .addValue("decision",decision),Integer.class));
        }
        validateRefundJournalDecision(user, scope, journal.id(), decision);
        String target = "POST".equals(decision) ? "POSTED" : "REJECTED";
        VoucherNumber voucher = "POST".equals(decision)
                ? allocateVoucherNumber(scope, journal.financialYearId(), journal.voucherType()) : null;
        int changed = safe("JOURNAL_DECISION_CONFLICT", () -> jdbc.update("""
                UPDATE accounting_journals SET status=:target,voucher_number=:voucherNumber,
                  decided_by_membership_id=:actorMembershipId,decided_at=:now,decision_reason=:reason,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='SUBMITTED' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("target", target)
                .addValue("voucherNumber", voucher == null ? null : voucher.value())
                .addValue("version", request.expectedVersion()).addValue("reason", reason(request.reason()))
                .addValue("now", Instant.now(clock))));
        changed(changed);
        journalHistory(scope, id, "SUBMITTED", target, request.reason());
        audit(user, scope, "accounting.journal." + target.toLowerCase(Locale.ROOT),
                "ACCOUNTING_JOURNAL", id, request.reason());
        return new AccountingMutationResponse(id, request.expectedVersion() + 1,
                voucher == null ? null : voucher.value());
    }

    private void validateRefundJournalDecision(AuthenticatedUser user, Scope scope, UUID journalId, String decision) {
        // Dispatch on the preserved fee-event contract before reading refund-only
        // provenance. Ordinary journals, including populated pre-refund upgrade
        // fixtures, must not depend on columns introduced for a later event.
        Boolean refundJournal = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_accounting_posting_batches batch
                  WHERE batch.accounting_journal_id=:journalId AND batch.organisation_id=:organisationId
                    AND batch.school_id=:schoolId AND batch.event_type='REFUND')
                """, scope.params().addValue("journalId", journalId), Boolean.class);
        if (!Boolean.TRUE.equals(refundJournal)) return;
        var refunds = jdbc.query("""
                /* reviewed-refund-journal-decision */
                SELECT submitter.user_id AS submitter_user_id,original_batch.accounting_journal_id AS original_journal_id,
                  journal.financial_year_id,journal.accounting_period_id,journal.posting_date
                FROM accounting_journals journal JOIN fee_accounting_posting_batches refund_batch
                  ON refund_batch.accounting_journal_id=journal.id AND refund_batch.organisation_id=journal.organisation_id
                  AND refund_batch.school_id=journal.school_id AND refund_batch.event_type='REFUND'
                JOIN fee_accounting_posting_items original_item ON original_item.id=refund_batch.original_payment_posting_item_id
                  AND original_item.organisation_id=refund_batch.organisation_id AND original_item.school_id=refund_batch.school_id
                JOIN fee_accounting_posting_batches original_batch ON original_batch.id=original_item.posting_batch_id
                  AND original_batch.organisation_id=original_item.organisation_id AND original_batch.school_id=original_item.school_id
                JOIN memberships submitter ON submitter.id=journal.submitted_by_membership_id
                  AND submitter.organisation_id=journal.organisation_id AND submitter.school_id=journal.school_id
                WHERE journal.id=:journalId AND journal.organisation_id=:organisationId
                  AND journal.school_id=:schoolId AND journal.source_module='FEES'
                """, scope.params().addValue("journalId", journalId), (row, number) -> new RefundJournalDecisionSource(
                        row.getObject("submitter_user_id", UUID.class), row.getObject("original_journal_id", UUID.class),
                        row.getObject("financial_year_id", UUID.class), row.getObject("accounting_period_id", UUID.class),
                        row.getObject("posting_date", LocalDate.class)));
        if (refunds.size() != 1) throw refundPostingUnavailable();
        requireRefundReadCommitted(scope);
        requireLiveRefundAccountingActor(user, scope, "accounting.post");
        var refund = refunds.getFirst();
        if (user.userId().equals(refund.submitterUserId())) {
            throw forbidden("INDEPENDENT_REFUND_JOURNAL_DECISION_REQUIRED", "A different authorized person must decide this refund voucher.");
        }
        if ("POST".equals(decision)) {
            OriginalRefundJournal original = originalRefundJournal(scope, refund.originalJournalId());
            FeeBookContext target = validateFeeBook(scope, refund.yearId(), refund.postingOn(), List.of(), true);
            if (!refund.periodId().equals(target.accountingPeriodId()) || !original.basis().equals(target.accountingBasis())
                    || !original.currency().equals(target.currencyCode())) throw refundPostingUnavailable();
            Boolean validAccounts = jdbc.queryForObject("""
                    SELECT NOT EXISTS(SELECT 1 FROM accounting_journal_lines line
                      LEFT JOIN accounting_accounts account ON account.id=line.account_id
                        AND account.organisation_id=line.organisation_id AND account.school_id=line.school_id
                      WHERE line.accounting_journal_id=:journalId AND line.organisation_id=:organisationId AND line.school_id=:schoolId
                        AND (account.id IS NULL OR account.status<>'ACTIVE' OR NOT account.allow_direct_posting))
                    """, scope.params().addValue("journalId", journalId), Boolean.class);
            if (!Boolean.TRUE.equals(validAccounts)) throw refundPostingUnavailable();
        }
    }

    @Transactional
    public AccountingMutationResponse requestReversal(AuthenticatedUser user, UUID journalId,
            ReversalRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.reverse.request");
        lockSchool(scope);
        StoredJournal journal = lockJournal(scope, journalId);
        if (!"POSTED".equals(journal.status()) || journal.reversalOfId() != null) {
            throw conflict("JOURNAL_NOT_REVERSIBLE", "Only an unreversed posted source voucher can be reversed.");
        }
        validateOpenPeriod(scope, request.requestedAccountingPeriodId(), request.requestedPostingDate());
        UUID id = UUID.randomUUID();
        safe("REVERSAL_REQUEST_EXISTS", () -> jdbc.update("""
                INSERT INTO accounting_reversal_requests(id,organisation_id,school_id,
                  accounting_journal_id,requested_accounting_period_id,requested_posting_date,
                  request_reason,status,requested_by_membership_id,created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:journalId,:periodId,:postingDate,:reason,
                  'PENDING',:actorMembershipId,:now,:now)
                """, scope.params().addValue("id", id).addValue("journalId", journalId)
                .addValue("periodId", request.requestedAccountingPeriodId())
                .addValue("postingDate", request.requestedPostingDate())
                .addValue("reason", reason(request.reason())).addValue("now", Instant.now(clock))));
        audit(user, scope, "accounting.reversal.requested", "ACCOUNTING_REVERSAL_REQUEST", id,
                request.reason());
        return new AccountingMutationResponse(id, 0);
    }

    @Transactional
    public AccountingMutationResponse decideReversal(AuthenticatedUser user, UUID id,
            DecisionRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "accounting.reverse.approve");
        lockSchool(scope);
        StoredReversal reversal = lockReversal(scope, id);
        if (!"PENDING".equals(reversal.status()) || reversal.version() != request.expectedVersion()) {
            throw conflict("REVERSAL_NOT_DECIDABLE", "Refresh the pending reversal and try again.");
        }
        if (scope.actorMembershipId().equals(reversal.requesterId())) {
            throw forbidden("INDEPENDENT_REVERSAL_DECISION_REQUIRED",
                    "A different authorized person must decide this reversal.");
        }
        String decision = enumValue(request.decision(), Set.of("APPROVE", "REJECT"), "reversal decision");
        // Settlement corrections retain the same actual-person separation as
        // settlement posting; a second membership is not another reviewer.
        Boolean settlement = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM accounting_journals journal
                  WHERE journal.id=:journalId AND journal.organisation_id=:organisationId
                    AND journal.school_id=:schoolId AND journal.source_module='GATEWAY_SETTLEMENT')
                """, scope.params().addValue("journalId", reversal.originalJournalId()), Boolean.class);
        if (Boolean.TRUE.equals(settlement)) {
            requireRefundReadCommitted(scope);
            requireLiveRefundAccountingActor(user, scope, "accounting.reverse.approve");
            safe("GATEWAY_SETTLEMENT_REVERSAL_DECISION_CONFLICT", () -> jdbc.queryForObject("""
                    SELECT 1 FROM (SELECT assert_gateway_settlement_reversal_decision(:reversalId,:membershipId,:decision)) checked
                    """, scope.params().addValue("reversalId", reversal.id()).addValue("membershipId", scope.actorMembershipId())
                    .addValue("decision", decision), Integer.class));
        }
        if ("REJECT".equals(decision)) {
            int changed = jdbc.update("""
                    UPDATE accounting_reversal_requests SET status='REJECTED',
                      decided_by_membership_id=:actorMembershipId,decided_at=:now,
                      decision_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='PENDING' AND row_version=:version
                    """, scope.params().addValue("id", id).addValue("version", request.expectedVersion())
                    .addValue("reason", reason(request.reason())).addValue("now", Instant.now(clock)));
            changed(changed);
            audit(user, scope, "accounting.reversal.rejected", "ACCOUNTING_REVERSAL_REQUEST", id,
                    request.reason());
            return new AccountingMutationResponse(id, request.expectedVersion() + 1);
        }

        StoredJournal original = lockJournal(scope, reversal.originalJournalId());
        if (!"POSTED".equals(original.status()) || original.reversalOfId() != null) {
            throw conflict("JOURNAL_NOT_REVERSIBLE", "The source voucher is no longer reversible.");
        }
        TargetPeriod target = validateOpenPeriod(scope, reversal.periodId(), reversal.postingDate());
        JournalTotals totals = totals(original.id(), scope);
        UUID counterId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String key = "reversal:" + id;
        String fingerprint = sha256(String.join("|", key, original.id().toString(),
                target.id().toString(), reversal.postingDate().toString()));
        String sourceNumber = original.voucherNumber() == null ? original.id().toString()
                : original.voucherNumber();
        String narration = truncate("Reversal of " + sourceNumber + ": " + reversal.requestReason(), 500);
        MapSqlParameterSource parameters = scope.params().addValue("counterId", counterId)
                .addValue("financialYearId", target.financialYearId()).addValue("periodId", target.id())
                .addValue("postingDate", reversal.postingDate())
                .addValue("reference", truncate("Reversal of " + sourceNumber, 120))
                .addValue("narration", narration).addValue("key", key).addValue("fingerprint", fingerprint)
                .addValue("originalId", original.id()).addValue("makerId", reversal.requesterId())
                .addValue("lineCount", totals.lineCount()).addValue("total", totals.debit())
                .addValue("requestReason", reversal.requestReason()).addValue("decisionReason", reason(request.reason()))
                .addValue("now", now);
        jdbc.update("""
                INSERT INTO accounting_journals(id,organisation_id,school_id,financial_year_id,
                  accounting_period_id,voucher_type,posting_date,reference,narration,source_module,
                  source_id,idempotency_key,request_fingerprint,reversal_of_journal_id,status,
                  created_by_membership_id,created_at,updated_at)
                VALUES(:counterId,:organisationId,:schoolId,:financialYearId,:periodId,'JOURNAL',
                  :postingDate,:reference,:narration,'REVERSAL',:originalId,:key,:fingerprint,
                  :originalId,'DRAFT',:makerId,:now,:now)
                """, parameters);
        jdbc.update("""
                INSERT INTO accounting_journal_lines(id,organisation_id,school_id,
                  accounting_journal_id,line_number,account_id,account_code_snapshot,
                  account_name_snapshot,account_type_snapshot,vendor_id,learner_fee_account_id,
                  debit_amount,credit_amount,memo,created_at,updated_at)
                SELECT gen_random_uuid(),organisation_id,school_id,:counterId,line_number,account_id,
                  account_code_snapshot,account_name_snapshot,account_type_snapshot,vendor_id,
                  learner_fee_account_id,credit_amount,debit_amount,
                  LEFT('Reversal: ' || COALESCE(memo,''),240),:now,:now
                FROM accounting_journal_lines
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND accounting_journal_id=:originalId
                ORDER BY line_number,id
                """, parameters);
        journalHistory(scope, counterId, null, "DRAFT", "Counter-voucher generated from approved request",
                reversal.requesterId());
        jdbc.update("""
                UPDATE accounting_journals SET status='SUBMITTED',line_count=:lineCount,total_amount=:total,
                  submitted_by_membership_id=:makerId,submitted_at=:now,submission_reason=:requestReason,
                  updated_at=:now,row_version=row_version+1 WHERE id=:counterId AND status='DRAFT'
                """, parameters);
        journalHistory(scope, counterId, "DRAFT", "SUBMITTED", reversal.requestReason(),
                reversal.requesterId());
        VoucherNumber voucher = allocateVoucherNumber(scope, target.financialYearId(), "JOURNAL");
        parameters.addValue("voucherNumber", voucher.value());
        jdbc.update("""
                UPDATE accounting_journals SET status='POSTED',voucher_number=:voucherNumber,
                  decided_by_membership_id=:actorMembershipId,decided_at=:now,
                  decision_reason=:decisionReason,updated_at=:now,row_version=row_version+1
                WHERE id=:counterId AND status='SUBMITTED'
                """, parameters);
        journalHistory(scope, counterId, "SUBMITTED", "POSTED", request.reason());
        int requestChanged = jdbc.update("""
                UPDATE accounting_reversal_requests SET status='APPROVED',
                  decided_by_membership_id=:actorMembershipId,decided_at=:now,
                  decision_reason=:decisionReason,reversal_journal_id=:counterId,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING' AND row_version=:version
                """, parameters.addValue("id", id).addValue("version", request.expectedVersion()));
        changed(requestChanged);
        int sourceChanged = jdbc.update("""
                UPDATE accounting_journals SET status='REVERSED',reversed_by_membership_id=:actorMembershipId,
                  reversed_at=:now,reversal_reason=:decisionReason,updated_at=:now,row_version=row_version+1
                WHERE id=:originalId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='POSTED'
                """, parameters);
        changed(sourceChanged);
        journalHistory(scope, original.id(), "POSTED", "REVERSED", request.reason());
        audit(user, scope, "accounting.reversal.approved", "ACCOUNTING_REVERSAL_REQUEST", id,
                request.reason());
        return new AccountingMutationResponse(id, request.expectedVersion() + 1, voucher.value());
    }

    private List<LineEvidence> validateLines(Scope scope, List<JournalLineRequest> requested) {
        if (requested == null || requested.size() < 2 || requested.size() > 500) {
            throw bad("JOURNAL_LINES_INVALID", "Use between 2 and 500 voucher lines.");
        }
        Set<UUID> accountIds = requested.stream().map(JournalLineRequest::accountId)
                .collect(Collectors.toSet());
        Map<UUID, PostingAccount> accounts = postingAccounts(scope, accountIds);
        if (accounts.size() != accountIds.size()) {
            throw bad("ACCOUNT_SCOPE_INVALID", "Every voucher line must use this school's chart of accounts.");
        }
        Set<UUID> vendorIds = requested.stream().map(JournalLineRequest::vendorId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        validateDimensions(scope, "accounting_vendors", vendorIds, "VENDOR_SCOPE_INVALID");
        Set<UUID> feeAccountIds = requested.stream().map(JournalLineRequest::learnerFeeAccountId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        validateDimensions(scope, "learner_fee_accounts", feeAccountIds, "FEE_ACCOUNT_SCOPE_INVALID");
        List<LineEvidence> result = new ArrayList<>();
        for (JournalLineRequest line : requested) {
            PostingAccount account = accounts.get(line.accountId());
            if (!"ACTIVE".equals(account.status()) || !account.direct()) {
                throw bad("ACCOUNT_NOT_POSTABLE", "Every voucher line must use an active posting account.");
            }
            BigDecimal debit = inputMoney(line.debitAmount());
            BigDecimal credit = inputMoney(line.creditAmount());
            if ((debit.signum() > 0) == (credit.signum() > 0)) {
                throw bad("JOURNAL_LINE_AMOUNT_INVALID",
                        "Each voucher line must contain either one debit or one credit amount.");
            }
            result.add(new LineEvidence(account, line.vendorId(), line.learnerFeeAccountId(),
                    debit, credit, clean(line.memo())));
        }
        return List.copyOf(result);
    }

    private Map<UUID, PostingAccount> postingAccounts(Scope scope, Set<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("""
                SELECT id,code,display_name,account_type,category,status,allow_direct_posting
                FROM accounting_accounts
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND id IN (:accountIds)
                """, scope.params().addValue("accountIds", accountIds), result -> {
                    Map<UUID, PostingAccount> accounts = new LinkedHashMap<>();
                    while (result.next()) {
                        PostingAccount account = new PostingAccount(
                                result.getObject("id", UUID.class), result.getString("code"),
                                result.getString("display_name"), result.getString("account_type"),
                                result.getString("category"), result.getString("status"),
                                result.getBoolean("allow_direct_posting"));
                        accounts.put(account.id(), account);
                    }
                    return accounts;
                });
    }

    private void validateDimensions(Scope scope, String table, Set<UUID> ids, String errorCode) {
        if (ids.isEmpty()) return;
        String sql = "SELECT count(*) FROM " + table
                + " WHERE organisation_id=:organisationId AND school_id=:schoolId AND id IN (:ids)";
        int count = jdbc.queryForObject(sql, scope.params().addValue("ids", ids), Integer.class);
        if (count != ids.size()) {
            throw bad(errorCode, "A voucher dimension is unavailable for this school.");
        }
    }

    private JournalContext validateJournalContext(Scope scope, UUID financialYearId, UUID periodId,
            LocalDate postingDate) {
        List<JournalContext> rows = jdbc.query("""
                SELECT year.id AS year_id,year.status AS year_status,period.id AS period_id,
                       period.status AS period_status,period.starts_on,period.ends_on
                FROM accounting_financial_years year
                JOIN accounting_periods period ON period.financial_year_id=year.id
                WHERE year.id=:financialYearId AND period.id=:periodId
                  AND year.organisation_id=:organisationId AND year.school_id=:schoolId
                """, scope.params().addValue("financialYearId", financialYearId)
                .addValue("periodId", periodId), (r, n) -> new JournalContext(
                r.getObject("year_id", UUID.class), r.getObject("period_id", UUID.class),
                r.getString("year_status"), r.getString("period_status"),
                r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class)));
        if (rows.isEmpty()) {
            throw bad("ACCOUNTING_PERIOD_SCOPE_INVALID", "Choose a period from the selected financial year.");
        }
        JournalContext context = rows.getFirst();
        if (!"OPEN".equals(context.yearStatus()) || !"OPEN".equals(context.periodStatus())
                || postingDate.isBefore(context.startsOn()) || postingDate.isAfter(context.endsOn())) {
            throw conflict("ACCOUNTING_PERIOD_NOT_OPEN",
                    "The voucher date must be inside an open financial year and accounting period.");
        }
        return context;
    }

    private TargetPeriod validateOpenPeriod(Scope scope, UUID periodId, LocalDate postingDate) {
        List<TargetPeriod> rows = jdbc.query("""
                SELECT period.id,period.financial_year_id,period.status AS period_status,
                       year.status AS year_status,period.starts_on,period.ends_on
                FROM accounting_periods period
                JOIN accounting_financial_years year ON year.id=period.financial_year_id
                WHERE period.id=:periodId AND period.organisation_id=:organisationId
                  AND period.school_id=:schoolId
                """, scope.params().addValue("periodId", periodId), (r, n) -> new TargetPeriod(
                r.getObject("id", UUID.class), r.getObject("financial_year_id", UUID.class),
                r.getString("period_status"), r.getString("year_status"),
                r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class)));
        if (rows.isEmpty()) throw bad("ACCOUNTING_PERIOD_SCOPE_INVALID", "Choose this school's accounting period.");
        TargetPeriod target = rows.getFirst();
        if (!"OPEN".equals(target.periodStatus()) || !"OPEN".equals(target.yearStatus())
                || postingDate.isBefore(target.startsOn()) || postingDate.isAfter(target.endsOn())) {
            throw conflict("ACCOUNTING_PERIOD_NOT_OPEN", "Choose a date inside an open accounting period.");
        }
        return target;
    }

    private FinancialYearState lockFinancialYear(Scope scope, UUID id) {
        List<FinancialYearState> rows = jdbc.query("""
                SELECT id,status,created_by_membership_id,row_version
                FROM accounting_financial_years
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), (r, n) -> new FinancialYearState(
                r.getObject("id", UUID.class), r.getString("status"),
                r.getObject("created_by_membership_id", UUID.class), r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("FINANCIAL_YEAR_NOT_FOUND", "The financial year was not found.");
        return rows.getFirst();
    }

    private StoredJournal lockJournal(Scope scope, UUID id) {
        List<StoredJournal> rows = jdbc.query("""
                SELECT id,financial_year_id,accounting_period_id,voucher_type,voucher_number,status,
                       created_by_membership_id,submitted_by_membership_id,reversal_of_journal_id,
                       request_fingerprint,row_version,source_module,source_id
                FROM accounting_journals
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), (r, n) -> storedJournal(r));
        if (rows.isEmpty()) throw notFound("JOURNAL_NOT_FOUND", "The accounting voucher was not found.");
        return rows.getFirst();
    }

    private StoredJournal findJournalByKey(Scope scope, String key) {
        List<StoredJournal> rows = jdbc.query("""
                SELECT id,financial_year_id,accounting_period_id,voucher_type,voucher_number,status,
                       created_by_membership_id,submitted_by_membership_id,reversal_of_journal_id,
                       request_fingerprint,row_version,source_module,source_id
                FROM accounting_journals
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (r, n) -> storedJournal(r));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static StoredJournal storedJournal(java.sql.ResultSet r) throws java.sql.SQLException {
        return new StoredJournal(r.getObject("id", UUID.class), r.getObject("financial_year_id", UUID.class),
                r.getObject("accounting_period_id", UUID.class), r.getString("voucher_type"),
                r.getString("voucher_number"), r.getString("status"),
                r.getObject("created_by_membership_id", UUID.class),
                r.getObject("submitted_by_membership_id", UUID.class),
                r.getObject("reversal_of_journal_id", UUID.class), r.getString("request_fingerprint"),
                r.getLong("row_version"),r.getString("source_module"),r.getObject("source_id",UUID.class));
    }

    private StoredReversal lockReversal(Scope scope, UUID id) {
        List<StoredReversal> rows = jdbc.query("""
                SELECT id,accounting_journal_id,requested_accounting_period_id,requested_posting_date,
                       request_reason,status,requested_by_membership_id,row_version
                FROM accounting_reversal_requests
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), (r, n) -> new StoredReversal(
                r.getObject("id", UUID.class), r.getObject("accounting_journal_id", UUID.class),
                r.getObject("requested_accounting_period_id", UUID.class),
                r.getObject("requested_posting_date", LocalDate.class), r.getString("request_reason"),
                r.getString("status"), r.getObject("requested_by_membership_id", UUID.class),
                r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("REVERSAL_REQUEST_NOT_FOUND", "The reversal request was not found.");
        return rows.getFirst();
    }

    private JournalTotals totals(UUID journalId, Scope scope) {
        return jdbc.query("""
                SELECT count(*)::integer AS line_count,COALESCE(sum(debit_amount),0) AS debit,
                       COALESCE(sum(credit_amount),0) AS credit
                FROM accounting_journal_lines
                WHERE accounting_journal_id=:journalId AND organisation_id=:organisationId
                  AND school_id=:schoolId
                """, scope.params().addValue("journalId", journalId), r -> {
                    if (!r.next()) return new JournalTotals(0, BigDecimal.ZERO, BigDecimal.ZERO);
                    return new JournalTotals(r.getInt("line_count"), money(r.getBigDecimal("debit")),
                            money(r.getBigDecimal("credit")));
                });
    }

    private VoucherNumber allocateVoucherNumber(Scope scope, UUID financialYearId, String voucherType) {
        List<VoucherNumber> rows = jdbc.query("""
                UPDATE accounting_journal_sequences SET next_value=next_value+1,updated_at=:now
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND financial_year_id=:financialYearId AND voucher_type=:voucherType
                  AND next_value<999999999
                RETURNING prefix,next_value-1 AS allocated
                """, scope.params().addValue("financialYearId", financialYearId)
                .addValue("voucherType", voucherType).addValue("now", Instant.now(clock)),
                (r, n) -> new VoucherNumber(r.getString("prefix") + "/"
                        + "%06d".formatted(r.getLong("allocated"))));
        if (rows.isEmpty()) {
            throw conflict("VOUCHER_SEQUENCE_UNAVAILABLE", "The voucher numbering sequence is unavailable.");
        }
        return rows.getFirst();
    }

    private void journalHistory(Scope scope, UUID journalId, String fromStatus, String toStatus,
            String changeReason) {
        journalHistory(scope, journalId, fromStatus, toStatus, changeReason, scope.actorMembershipId());
    }

    private void journalHistory(Scope scope, UUID journalId, String fromStatus, String toStatus,
            String changeReason, UUID actorMembershipId) {
        jdbc.update("""
                INSERT INTO accounting_journal_history(id,organisation_id,school_id,
                  accounting_journal_id,from_status,to_status,reason,actor_membership_id,occurred_at)
                VALUES(:historyId,:organisationId,:schoolId,:journalId,:fromStatus,:toStatus,
                  :reason,:historyActorId,:now)
                """, scope.params().addValue("historyId", UUID.randomUUID()).addValue("journalId", journalId)
                .addValue("fromStatus", fromStatus).addValue("toStatus", toStatus)
                .addValue("reason", reason(changeReason)).addValue("historyActorId", actorMembershipId)
                .addValue("now", Instant.now(clock)));
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        }
        List<Scope> rows = jdbc.query("""
                SELECT school.id AS school_id,school.display_name,membership.id AS membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='accounting.configure') can_configure,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='accounting.prepare') can_prepare,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='accounting.post') can_post,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='accounting.close') can_close,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='accounting.reverse.request') can_request,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='accounting.reverse.approve') can_approve
                FROM memberships membership
                JOIN schools school ON school.id=membership.school_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN (:membershipIds) AND membership.status='ACTIVE'
                  AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='accounting.read')
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds()).addValue("schoolId", requestedSchoolId),
                (r, n) -> new Scope(user.activeOrganisationId(), r.getObject("school_id", UUID.class),
                r.getString("display_name"), r.getObject("membership_id", UUID.class),
                r.getBoolean("can_configure"), r.getBoolean("can_prepare"), r.getBoolean("can_post"),
                r.getBoolean("can_close"), r.getBoolean("can_request"), r.getBoolean("can_approve")));
        if (rows.isEmpty()) {
            throw forbidden("ACCOUNTING_SCOPE_DENIED", "Accounting access is unavailable for this school.");
        }
        return rows.getFirst();
    }

    private Scope requireCapability(AuthenticatedUser user, UUID schoolId, String capability) {
        Scope scope = requireScope(user, schoolId);
        if (!scope.has(capability)) {
            throw forbidden("ACCOUNTING_CAPABILITY_DENIED", "This accounting action is outside your authorized role.");
        }
        return scope;
    }

    private void lockSchool(Scope scope) {
        jdbc.queryForObject("""
                SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked
                """, scope.params().addValue("lockKey", "accounting:school:" + scope.schoolId()), Integer.class);
    }

    private void audit(AuthenticatedUser user, Scope scope, String action, String subjectType,
            UUID subjectId, String reason) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, subjectType,
                subjectId.toString(), AuditOutcome.SUCCEEDED, clean(reason), Map.of());
    }

    private static FinancialYearItem selectYear(List<FinancialYearItem> years, UUID requestedId) {
        if (requestedId == null) return years.isEmpty() ? null : years.getFirst();
        return years.stream().filter(year -> year.id().equals(requestedId)).findFirst()
                .orElseThrow(() -> notFound("FINANCIAL_YEAR_NOT_FOUND", "The financial year was not found."));
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusDays(1830))) {
            throw bad("ACCOUNTING_REPORT_RANGE_INVALID", "Choose a valid report range of no more than five years.");
        }
    }

    private static boolean categoryMatches(String type, String category) {
        return switch (category) {
            case "CASH", "BANK", "RECEIVABLE" -> "ASSET".equals(type);
            case "PAYABLE" -> "LIABILITY".equals(type);
            case "FUND" -> "EQUITY".equals(type);
            case "INCOME" -> "INCOME".equals(type);
            case "EXPENSE" -> "EXPENSE".equals(type);
            case "OTHER" -> true;
            default -> false;
        };
    }

    private static String journalFingerprint(JournalRequest request, String voucherType,
            List<LineEvidence> lines) {
        StringBuilder canonical = new StringBuilder().append(request.schoolId()).append('|')
                .append(request.financialYearId()).append('|').append(request.accountingPeriodId()).append('|')
                .append(voucherType).append('|').append(request.postingDate()).append('|')
                .append(clean(request.reference())).append('|').append(required(request.narration()));
        for (LineEvidence line : lines) {
            canonical.append('|').append(line.account().id()).append(':').append(line.vendorId())
                    .append(':').append(line.feeAccountId()).append(':')
                    .append(line.debit().toPlainString()).append(':').append(line.credit().toPlainString())
                    .append(':').append(line.memo());
        }
        return sha256(canonical.toString());
    }

    private static BigDecimal inputMoney(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("1000000000000")) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw bad("ACCOUNTING_AMOUNT_INVALID", "Use a non-negative monetary amount with at most two decimals.");
        }
        return value.setScale(2);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? new BigDecimal("0.00") : value.setScale(2);
    }

    private static <T> BigDecimal sum(List<T> values, Function<T, BigDecimal> value) {
        return values.stream().map(value).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    private static BigDecimal sumLines(List<LineEvidence> values,
            Function<LineEvidence, BigDecimal> value) {
        return sum(values, value);
    }

    private static BigDecimal balance(List<TrialBalanceItem> trial, String type, boolean debitNormal) {
        return trial.stream().filter(item -> type.equals(item.accountType()))
                .map(item -> debitNormal
                        ? item.debitBalance().subtract(item.creditBalance())
                        : item.creditBalance().subtract(item.debitBalance()))
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    private static BigDecimal categoryBalance(List<TrialBalanceItem> trial, String category,
            boolean debitNormal) {
        return trial.stream().filter(item -> category.equals(item.category()))
                .map(item -> debitNormal
                        ? item.debitBalance().subtract(item.creditBalance())
                        : item.creditBalance().subtract(item.debitBalance()))
                .reduce(new BigDecimal("0.00"), BigDecimal::add);
    }

    private static String code(String value, int maxLength) {
        String normalized = required(value).toUpperCase(Locale.ROOT).replace(' ', '_');
        if (normalized.length() > maxLength || !normalized.matches("^[A-Z][A-Z0-9_.-]+$")) {
            throw bad("ACCOUNTING_CODE_INVALID",
                    "Use uppercase letters, numbers, underscores, dots, or dashes, starting with a letter.");
        }
        return normalized;
    }

    private static String idempotencyKey(String value) {
        String key = required(value);
        if (key.length() < 8 || key.length() > 100) {
            throw bad("IDEMPOTENCY_KEY_INVALID", "Use a retry key between 8 and 100 characters.");
        }
        return key;
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value).toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(normalized)) throw bad("INVALID_VALUE", "Choose a supported " + label + ".");
        return normalized;
    }

    private static String required(String value) {
        String cleaned = clean(value);
        if (cleaned == null) throw bad("REQUIRED_VALUE", "Complete all required accounting fields.");
        return cleaned;
    }

    private static String reason(String value) {
        String why = required(value);
        if (why.length() < 3 || why.length() > 500) {
            throw bad("ACCOUNTING_REASON_INVALID", "Enter a reason between 3 and 500 characters.");
        }
        return why;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String lower(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int maximum) {
        String cleaned = clean(value);
        return cleaned == null || cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum);
    }

    private static long version(Long value) {
        if (value == null || value < 0) throw bad("EXPECTED_VERSION_REQUIRED", "Refresh and try again.");
        return value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static <T> T safe(String code, Work<T> work) {
        try {
            return work.run();
        } catch (DataIntegrityViolationException exception) {
            throw conflict(code, "The accounting operation conflicts with protected financial history.");
        }
    }

    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION", "The record changed. Refresh and try again.");
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

    private record Scope(UUID organisationId, UUID schoolId, String schoolName,
            UUID actorMembershipId, boolean canConfigure, boolean canPrepare, boolean canPost,
            boolean canClose, boolean canRequestReversal, boolean canApproveReversal) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId);
        }

        boolean has(String capability) {
            return switch (capability) {
                case "accounting.configure" -> canConfigure;
                case "accounting.prepare" -> canPrepare;
                case "accounting.post" -> canPost;
                case "accounting.close" -> canClose;
                case "accounting.reverse.request" -> canRequestReversal;
                case "accounting.reverse.approve" -> canApproveReversal;
                default -> false;
            };
        }
    }

    private record JournalHeader(UUID id, UUID financialYearId, UUID accountingPeriodId,
            String voucherType, String voucherNumber, LocalDate postingDate, String reference,
            String narration, String sourceModule, UUID sourceId, UUID reversalOfId, String status,
            Integer lineCount, BigDecimal totalAmount, String creatorName, String submitterName,
            Instant submittedAt, String deciderName, Instant decidedAt, long version) {
        JournalItem item(List<JournalLineItem> lines, List<JournalHistoryItem> history) {
            return new JournalItem(id, financialYearId, accountingPeriodId, voucherType, voucherNumber,
                    postingDate, reference, narration, sourceModule, sourceId, reversalOfId, status,
                    lineCount, totalAmount, creatorName, submitterName, submittedAt, deciderName,
                    decidedAt, version, lines, history);
        }
    }

    private record AccountEvidence(String code, String type, String normalBalance,
            String category, String status) {}
    private record PostingAccount(UUID id, String code, String name, String type,
            String category, String status, boolean direct) {}
    private record LineEvidence(PostingAccount account, UUID vendorId, UUID feeAccountId,
            BigDecimal debit, BigDecimal credit, String memo) {}
    private record FinancialYearState(UUID id, String status, UUID creatorId, long version) {}
    private record JournalContext(UUID financialYearId, UUID periodId, String yearStatus,
            String periodStatus, LocalDate startsOn, LocalDate endsOn) {}
    private record TargetPeriod(UUID id, UUID financialYearId, String periodStatus,
            String yearStatus, LocalDate startsOn, LocalDate endsOn) {}
    private record StoredJournal(UUID id, UUID financialYearId, UUID periodId, String voucherType,
            String voucherNumber, String status, UUID creatorId, UUID submitterId,
            UUID reversalOfId, String fingerprint, long version, String sourceModule, UUID sourceId) {}
    private record StoredReversal(UUID id, UUID originalJournalId, UUID periodId,
            LocalDate postingDate, String requestReason, String status, UUID requesterId, long version) {}
    private record OriginalRefundJournal(String basis, String currency) {}
    private record RefundJournalDecisionSource(UUID submitterUserId, UUID originalJournalId,
            UUID yearId, UUID periodId, LocalDate postingOn) {}
    private record JournalTotals(int lineCount, BigDecimal debit, BigDecimal credit) {}
    private record VoucherNumber(String value) {}

    @FunctionalInterface
    private interface Work<T> {
        T run();
    }
}

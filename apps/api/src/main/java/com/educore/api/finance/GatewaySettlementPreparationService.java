package com.educore.api.finance;

import static com.educore.api.finance.GatewaySettlementPreparationModels.*;
import static com.educore.api.accounting.AccountingModuleBridge.*;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** One atomic reviewed settlement into the common book; never another student payment or receipt. */
@Service
public class GatewaySettlementPreparationService {
    private final GatewaySettlementService settlements;
    private final GatewaySettlementSourceReviewService sourceReview;
    private final GatewaySettlementRefundCandidateService refunds;
    private final GatewaySettlementBankEvidenceService banks;
    private final FeeAccountingService fees;
    private final AccountingModuleBridge accounting;
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().build();

    public GatewaySettlementPreparationService(GatewaySettlementService settlements,GatewaySettlementSourceReviewService sourceReview,
            GatewaySettlementRefundCandidateService refunds,GatewaySettlementBankEvidenceService banks,FeeAccountingService fees,
            AccountingModuleBridge accounting,NamedParameterJdbcTemplate jdbc,SecurityAuditService audit,Clock clock) {
        this.settlements=Objects.requireNonNull(settlements);this.sourceReview=Objects.requireNonNull(sourceReview);
        this.refunds=Objects.requireNonNull(refunds);this.banks=Objects.requireNonNull(banks);this.fees=Objects.requireNonNull(fees);
        this.accounting=Objects.requireNonNull(accounting);this.jdbc=Objects.requireNonNull(jdbc);this.audit=Objects.requireNonNull(audit);this.clock=Objects.requireNonNull(clock);
    }

    @Transactional(readOnly=true)
    public Context context(AuthenticatedUser user,UUID schoolId,UUID requestId,int page,int pageSize) {
        GatewaySettlementTerms.page(page,pageSize);
        var authority=settlements.bankEvidenceAuthority(user,schoolId,null);
        var data=data(user,schoolId,requestId);
        var options=accounting.settlementAccountingOptions(user,schoolId);
        var history=history(user,authority,requestId);
        var availableBankEvidence=new ArrayList<>(data.bankContext().evidence());
        // A repair retains its exact old credit. Bounded bank-history paging
        // must never force the maker to invent a second credit for that repair.
        if(!history.isEmpty()&&!"ABANDONED".equals(history.getFirst().status())){
            UUID retainedBankId=history.getFirst().bankEvidenceId();
            if(availableBankEvidence.stream().noneMatch(value->value.id().equals(retainedBankId))){
                availableBankEvidence.addFirst(banks.evidence(user,schoolId,requestId,retainedBankId));
                if(availableBankEvidence.size()>25)availableBankEvidence.removeLast();
            }
        }
        var blockers=new ArrayList<>(data.blockers());
        if(options.financialYears().isEmpty())blockers.add("NO_OPEN_ACCOUNTING_YEAR");
        if(availableBankEvidence.stream().noneMatch(value->"BANK_EVIDENCE_RECORDED".equals(value.status())))blockers.add("NO_AVAILABLE_BANK_EVIDENCE");
        if(statementConsumed(authority,data.chain(),null)&&(history.isEmpty()
                ||!Set.of("REJECTED","REVERSED").contains(history.getFirst().status())))blockers.add("STATEMENT_ALREADY_PREPARED");
        int start=(int)Math.min((long)page*pageSize,data.rows().size()),end=Math.min(start+pageSize,data.rows().size());
        return new Context(schoolId,requestId,data.chain().request().settlementReference(),authority.timeZone(),data.fingerprint(),
                authority.canRecord()&&options.canPrepare(),options,availableBankEvidence,summary(data.bankSource().summary()),
                data.facts(),data.paymentTotal(),data.refundTotal(),page,pageSize,data.rows().size(),
                data.rows().stream().filter(row->"REFUND".equals(row.event().reportedType())).count(),end<data.rows().size(),data.rows().subList(start,end),blockers,history);
    }

    @Transactional(readOnly=true)
    public PreparationDetail detail(AuthenticatedUser user,UUID schoolId,UUID preparationId) {
        var authority=settlements.bankEvidenceAuthority(user,schoolId,null);var preparation=projection(user,authority,preparationId);
        var frozen=jdbc.query("SELECT evidence_snapshot::text FROM fee_gateway_settlement_preparations WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId",
                authority.params().addValue("id",preparationId),(row,n)->json.readTree(row.getString(1)));
        if(frozen.size()!=1)throw unavailable();var snapshot=frozen.getFirst();
        try {
            if(snapshot.get("schemaVersion").intValue()!=1)throw unavailable();
            var command=json.treeToValue(snapshot.get("command"),Command.class);GatewaySettlementPreparationTerms.normalize(command);
            var rawSources=new ArrayList<SourceIdentity>();
            for(var value:snapshot.get("sources"))rawSources.add(new SourceIdentity(value.get("kind").stringValue(),
                    uuid(value,"paymentId"),nullableUuid(value,"refundId"),uuid(value,"ledgerId"),uuid(value,"itemId"),uuid(value,"batchId"),
                    uuid(value,"journalId"),uuid(value,"clearingId"),value.get("amount").decimalValue().setScale(2).toPlainString(),
                    uuid(value,"sourceReadId"),value.get("rowIndex").intValue(),nullableText(value,"associationReason")));
            var facts=new ArrayList<ComponentFact>();for(var value:snapshot.get("components"))facts.add(json.treeToValue(value.get("fact"),ComponentFact.class));
            return new PreparationDetail(preparation,snapshot.get("schoolTimeZone").stringValue(),json.treeToValue(snapshot.get("summaryProjection"),GatewaySettlementModels.Summary.class),
                    json.treeToValue(snapshot.get("bankEvidence"),GatewaySettlementBankEvidenceModels.Evidence.class),command.componentBasis(),facts,
                    command.componentReviews(),command.refundAssociations(),rawSources);
        } catch(RuntimeException invalid){throw unavailable();}
    }

    @Transactional
    public Preparation prepare(AuthenticatedUser user,UUID requestId,Command command) {
        var terms=GatewaySettlementPreparationTerms.normalize(command);var input=terms.command();
        var authority=preparingActor(user,input.schoolId());writable(authority);
        UUID prior=replay(authority,input.clientRequestId(),terms.fingerprint(),requestId);
        if(prior!=null)return projection(user,authority,prior);
        // Preserve the established finance-operation -> fee-configuration -> accounting order.
        fence(authority,authority.schoolId()+":finance");fence(authority,"finance:school:"+authority.schoolId());
        authority=preparingActor(user,input.schoolId());writable(authority);
        prior=replay(authority,input.clientRequestId(),terms.fingerprint(),requestId);if(prior!=null)return projection(user,authority,prior);
        var data=data(user,input.schoolId(),requestId);sameContext(data,input);var selectedBank=bank(user,authority,data,input.bankEvidenceId());
        var components=GatewaySettlementPreparationTerms.components(terms,data.facts());
        var sources=sources(user,data,input.refundAssociations());
        var uses=accountUses(selectedBank,sources,components,accounting.settlementAccountingOptions(user,input.schoolId()).accounts());
        var book=accounting.validateSettlementPosting(user,input.schoolId(),input.financialYearId(),input.postingOn(),uses,sourceJournals(sources));
        // A wait for the book fence can observe changed source/reversal/actor state.
        // Rebuild complete proof and immutable context after that wait.
        authority=preparingActor(user,input.schoolId());writable(authority);
        data=data(user,input.schoolId(),requestId);sameContext(data,input);selectedBank=bank(user,authority,data,input.bankEvidenceId());
        sources=sources(user,data,input.refundAssociations());components=GatewaySettlementPreparationTerms.components(terms,data.facts());
        uses=accountUses(selectedBank,sources,components,accounting.settlementAccountingOptions(user,input.schoolId()).accounts());
        book=accounting.validateSettlementPosting(user,input.schoolId(),input.financialYearId(),input.postingOn(),uses,sourceJournals(sources));
        validatePostingDate(authority,input.postingOn(),selectedBank,sources);
        BigDecimal payments=total(sources,"PAYMENT"),returned=total(sources,"REFUND"),bankAmount=paise(selectedBank.creditedAmount(),false);
        GatewaySettlementPreparationTerms.balanced(payments,returned,bankAmount,components);
        Root root=rootForPreparation(user,authority,data,input,sources);
        if(root==null) {
            root=new Root(UUID.randomUUID(),requestId,input.bankEvidenceId(),1,null,null);
            insertRoot(authority,data,root);
            insertSources(authority,root.id(),sources);
        } else sources=reuseClaims(authority,root.id(),sources);
        UUID id=UUID.randomUUID();
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("schemaVersion",1);snapshot.put("rootId",root.id());snapshot.put("requestId",requestId);snapshot.put("command",input);
        snapshot.put("summary",data.bankSource().summary());snapshot.put("summaryProjection",summary(data.bankSource().summary()));snapshot.put("sourceSummaryReadId",data.bankSource().summaryReadId());
        snapshot.put("schoolTimeZone",data.bankSource().timeZone());
        snapshot.put("sourceSummaryRowIndex",data.bankSource().summaryRowIndex());snapshot.put("contextFingerprint",data.fingerprint());
        snapshot.put("bankEvidence",selectedBank);snapshot.put("sources",sources);snapshot.put("components",components);
        snapshot.put("financialYearId",book.financialYearId());snapshot.put("accountingPeriodId",book.accountingPeriodId());
        snapshot.put("postingOn",input.postingOn());snapshot.put("currencyCode","INR");
        var journal=accounting.prepareSettlementJournal(user,new SettlementJournalRequest(input.schoolId(),book.financialYearId(),book.accountingPeriodId(),
                input.postingOn(),data.chain().request().settlementReference(),"Reviewed gateway settlement: "+input.reason(),id,
                "gateway-settlement:"+id,input.reason(),journalLines(selectedBank,sources,components)));
        insertPreparation(authority,root,id,journal.id(),book,input,terms.fingerprint(),snapshot,payments,returned,bankAmount,components);
        insertPhysicalRows(authority,id,sources);
        insertComponents(authority,id,components,input.componentBasis());
        audit.recordScoped(user,authority.membershipId(),authority.schoolId(),"finance.gateway.settlement.prepared","GATEWAY_SETTLEMENT_PREPARATION",id.toString(),
                AuditOutcome.SUCCEEDED,input.reason(),Map.of("requestId",requestId,"rootId",root.id(),"journalId",journal.id(),"stage","AWAITING_INDEPENDENT_JOURNAL_DECISION"));
        return projection(user,authority,id);
    }

    @Transactional
    public AbandonmentResult abandon(AuthenticatedUser user,UUID preparationId,AbandonmentCommand command) {
        if(preparationId==null||command==null||command.schoolId()==null||command.clientRequestId()==null||!command.confirmedReleaseRejectedPreparation())throw unavailable();
        String reason=GatewaySettlementPreparationTerms.reason(command.reason());
        var authority=preparingActor(user,command.schoolId());writable(authority);
        var canonical=new StringBuilder("SETTLEMENT_REJECTED_ROOT_RELEASE_V1");GatewaySettlementPreparationTerms.frame(canonical,preparationId,reason);
        String fingerprint=GatewaySettlementPreparationTerms.hash(canonical.toString());
        UUID prior=replayAbandonment(authority,command.clientRequestId(),fingerprint);
        if(prior!=null)return new AbandonmentResult(command.clientRequestId(),projection(user,authority,preparationId));
        fence(authority,authority.schoolId()+":finance");fence(authority,"finance:school:"+authority.schoolId());
        // This is the same shared order as preparation. Accounting decision paths
        // never call back into this finance-locking operation.
        fence(authority,"accounting:school:"+authority.schoolId());
        authority=preparingActor(user,command.schoolId());writable(authority);
        prior=replayAbandonment(authority,command.clientRequestId(),fingerprint);
        if(prior!=null)return new AbandonmentResult(command.clientRequestId(),projection(user,authority,preparationId));
        var selected=projection(user,authority,preparationId);
        var attempts=jdbc.query("""
                SELECT id FROM fee_gateway_settlement_preparations WHERE root_id=:rootId AND organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY revision DESC LIMIT 1001
                """,authority.params().addValue("rootId",selected.rootId()),(row,n)->row.getObject("id",UUID.class));
        if(attempts.isEmpty()||attempts.size()>1000||!preparationId.equals(attempts.getFirst()))throw unavailable();
        var statuses=accounting.settlementJournalStatuses(user,authority.schoolId(),attempts);
        if(statuses.size()!=attempts.size()||statuses.values().stream().anyMatch(value->!"REJECTED".equals(value.status())||value.reversalId()!=null)
                ||!"REJECTED".equals(selected.status()))throw conflict("GATEWAY_SETTLEMENT_RELEASE_UNAVAILABLE","Only an independently rejected root with no posted or reversed journal history can release its reservations.");
        UUID id=UUID.randomUUID();
        if(jdbc.update("""
                INSERT INTO fee_gateway_settlement_abandonments(id,organisation_id,school_id,root_id,abandoned_by_user_id,abandoned_by_membership_id,
                  client_request_id,request_fingerprint,reason)
                VALUES(:id,:organisationId,:schoolId,:rootId,:userId,:membershipId,:clientId,:fingerprint,:reason)
                """,authority.params().addValue("id",id).addValue("rootId",selected.rootId()).addValue("clientId",command.clientRequestId())
                .addValue("fingerprint",fingerprint).addValue("reason",reason))!=1)throw unavailable();
        audit.recordScoped(user,authority.membershipId(),authority.schoolId(),"finance.gateway.settlement.rejected-root-released","GATEWAY_SETTLEMENT_PREPARATION",preparationId.toString(),
                AuditOutcome.SUCCEEDED,reason,Map.of("rootId",selected.rootId(),"abandonmentId",id,"stage","NEVER_POSTED_RESERVATIONS_RELEASED"));
        return new AbandonmentResult(command.clientRequestId(),projection(user,authority,preparationId));
    }

    private UUID replayAbandonment(GatewaySettlementBankEvidenceService.Authority authority,UUID client,String fingerprint) {
        var rows=jdbc.query("""
                SELECT id,request_fingerprint FROM fee_gateway_settlement_abandonments
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND abandoned_by_user_id=:userId AND client_request_id=:clientId
                """,authority.params().addValue("clientId",client),(row,n)->Map.entry(row.getObject("id",UUID.class),row.getString("request_fingerprint")));
        if(rows.size()>1)throw unavailable();if(rows.isEmpty())return null;
        if(!rows.getFirst().getValue().equals(fingerprint))throw conflict("GATEWAY_SETTLEMENT_REQUEST_REUSED","This release retry belongs to different retained terms.");
        return rows.getFirst().getKey();
    }

    private Data data(AuthenticatedUser user,UUID schoolId,UUID requestId) {
        var chain=settlements.completeEvents(user,schoolId,requestId);
        var bankSource=settlements.bankEvidenceSource(user,schoolId,requestId);
        var bankContext=banks.context(user,schoolId,requestId);
        var original=sourceReview.allRows(user,chain);var rows=new ArrayList<Row>();var blockers=new LinkedHashSet<String>();
        BigDecimal payments=BigDecimal.ZERO,returned=BigDecimal.ZERO;boolean totalsValid=true;
        var fingerprint=new StringBuilder("SETTLEMENT_PREPARATION_CONTEXT_V1");
        GatewaySettlementPreparationTerms.frame(fingerprint,chain.organisationId(),schoolId,requestId,chain.binding().providerConfigId(),chain.binding().providerCode(),
                chain.binding().environment(),chain.binding().merchantReference(),bankSource.summaryReadId(),bankSource.summaryRowIndex(),bankSource.timeZone());
        for(var page:chain.pages())GatewaySettlementPreparationTerms.frame(fingerprint,page.readId(),page.query().id(),page.cursorHash(),page.nextCursorHash());
        for(var row:original) {
            boolean refund="REFUND".equals(row.event().reportedType());var reasons=new ArrayList<>(row.reasons());
            if(refund&&(row.event().orderReference()==null||row.event().paymentReference()==null))reasons.add("MISSING_PAYMENT_REFERENCES");
            boolean selection=refund&&reasons.equals(List.of("REFUND_ASSOCIATION_REVIEW"));
            String status=row.source()!=null?"PAYMENT_SOURCE_IDENTIFIED":selection?"REFUND_SELECTION_REQUIRED":"REVIEW_REQUIRED";
            rows.add(new Row(row.sourceReadId(),row.rowIndex(),row.event(),status,reasons,row.source()));
            if("REVIEW_REQUIRED".equals(status)){blockers.add("SOURCE_REVIEW_REQUIRED");totalsValid=false;}
            else if(refund)returned=returned.add(paise(row.event().reportedAmount(),false));else payments=payments.add(paise(row.event().reportedAmount(),false));
            GatewaySettlementPreparationTerms.frame(fingerprint,row.sourceReadId(),row.rowIndex(),status);
            if(row.source()!=null)GatewaySettlementPreparationTerms.frame(fingerprint,row.source().paymentId(),row.source().feeLedgerEntryId(),row.source().postingItemId(),
                    row.source().postingBatchId(),row.source().accountingJournalId(),row.source().configurationId(),row.source().clearingAccountId());
        }
        if(rows.isEmpty())blockers.add("NO_EVENTS");
        if(!"SUCCESS".equals(bankSource.summary().reportedStatus()))blockers.add("SUMMARY_STATUS_REVIEW_REQUIRED");
        if(bankSource.summary().reportedAmountSettled()==null||bankSource.summary().reportedAmountSettled().signum()<=0)blockers.add("SUMMARY_AMOUNT_UNAVAILABLE");
        else try{bankSource.summary().reportedAmountSettled().setScale(2,RoundingMode.UNNECESSARY);}catch(ArithmeticException invalid){blockers.add("SUMMARY_FRACTIONAL_PAISE");}
        return new Data(chain,bankSource,bankContext,List.copyOf(rows),facts(bankSource.summary(),rows),GatewaySettlementPreparationTerms.hash(fingerprint.toString()),
                totalsValid?payments.setScale(2).toPlainString():null,totalsValid?returned.setScale(2).toPlainString():null,List.copyOf(blockers));
    }

    private List<Source> sources(AuthenticatedUser user,Data data,List<RefundAssociation> choices) {
        if(!data.blockers().isEmpty())throw unavailable();
        var associations=new LinkedHashMap<String,RefundAssociation>();for(var choice:choices)associations.put(choice.sourceReadId()+":"+choice.rowIndex(),choice);
        var paymentIds=new LinkedHashSet<UUID>();
        for(var row:data.rows())if("PAYMENT_SOURCE_IDENTIFIED".equals(row.status())) {
            if(row.source()==null||row.source().paymentId()==null)throw unavailable();paymentIds.add(row.source().paymentId());
        }
        var orderedPaymentIds=List.copyOf(paymentIds);var paymentProofs=new LinkedHashMap<UUID,FeeAccountingService.OriginalPaymentPostingProof>();
        for(int start=0;start<orderedPaymentIds.size();start+=1000) {
            var chunk=orderedPaymentIds.subList(start,Math.min(start+1000,orderedPaymentIds.size()));
            var result=fees.verifyOriginalPaymentPostings(user,data.chain().schoolId(),chunk);
            if(result==null||!result.invalidPaymentIds().isEmpty()||!result.proofs().keySet().equals(new LinkedHashSet<>(chunk)))throw unavailable();
            paymentProofs.putAll(result.proofs());
        }
        var result=new ArrayList<Source>();var economics=new LinkedHashSet<String>();
        for(var row:data.rows()) {
            Source source;
            if("PAYMENT_SOURCE_IDENTIFIED".equals(row.status())) {
                var matched=row.source();var proof=paymentProofs.get(matched.paymentId());if(proof==null)throw unavailable();
                if(!proof.postingItemId().equals(matched.postingItemId())||!proof.accountingJournalId().equals(matched.accountingJournalId())
                        ||!proof.depositAccountId().equals(matched.clearingAccountId()))throw unavailable();
                source=new Source(UUID.randomUUID(),"PAYMENT",proof.paymentId(),null,proof.feeLedgerEntryId(),proof.postingItemId(),proof.postingBatchId(),
                        proof.accountingJournalId(),proof.configurationId(),proof.depositAccountId(),paise(row.event().reportedAmount(),false),proof.currencyCode(),
                        proof.sourceSha256(),proof.batchEvidenceSha256(),proof.originalPostingDate(),proof.accountingJournalId(),row.sourceReadId(),row.rowIndex(),null);
            } else if("REFUND_SELECTION_REQUIRED".equals(row.status())) {
                var choice=associations.remove(row.sourceReadId()+":"+row.rowIndex());if(choice==null)throw unavailable();
                var retained=data.chain().pages().stream().filter(page->page.readId().equals(row.sourceReadId())).findFirst().orElseThrow(GatewaySettlementPreparationService::unavailable);
                UUID paymentId=refunds.originalPaymentId(data.chain(),retained.events().get(row.rowIndex()));
                var proof=fees.verifyCompletedRefundPosting(user,data.chain().schoolId(),choice.refundPostingId());
                if(!proof.paymentId().equals(paymentId)||!proof.currencyCode().equals(row.event().currencyCode())
                        ||proof.refundAmount().compareTo(paise(row.event().reportedAmount(),false))!=0)throw unavailable();
                source=new Source(UUID.randomUUID(),"REFUND",paymentId,proof.refundPostingId(),proof.feeLedgerEntryId(),proof.postingItemId(),proof.postingBatchId(),
                        proof.accountingJournalId(),proof.configurationId(),proof.clearingAccountId(),proof.refundAmount(),proof.currencyCode(),proof.sourceSha256(),
                        proof.batchEvidenceSha256(),proof.postingOn(),proof.originalPaymentProof().accountingJournalId(),row.sourceReadId(),row.rowIndex(),choice.reason());
            } else throw unavailable();
            if(!"INR".equals(source.currency())||!economics.add(source.kind()+":"+(source.refundId()==null?source.paymentId():source.refundId())))throw unavailable();
            result.add(source);
        }
        if(!associations.isEmpty()||result.isEmpty())throw unavailable();return List.copyOf(result);
    }

    private GatewaySettlementBankEvidenceService.Authority preparingActor(AuthenticatedUser user,UUID schoolId) {
        var authority=settlements.bankEvidenceAuthority(user,schoolId,"fees.close.propose");
        if(!accounting.settlementAccountingOptions(user,schoolId).canPrepare())
            throw new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_SETTLEMENT_PREPARATION_DENIED","Current finance preparation and accounting preparation authority are required.");
        return authority;
    }
    private void writable(GatewaySettlementBankEvidenceService.Authority authority) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!"read committed".equals(jdbc.queryForObject("SELECT current_setting('transaction_isolation')",authority.params(),String.class)))
            throw conflict("GATEWAY_SETTLEMENT_PREPARATION_RETRY_REQUIRED","Prepare settlement only in a fresh authorized writable read-committed transaction.");
    }
    private void fence(GatewaySettlementBankEvidenceService.Authority authority,String key) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked",authority.params().addValue("lockKey",key),Integer.class);
    }
    private static void sameContext(Data data,Command input) {
        if(!data.fingerprint().equals(input.contextFingerprint())||!data.blockers().isEmpty())throw conflict("GATEWAY_SETTLEMENT_PREPARATION_CONTEXT_CHANGED","Refresh the complete statement and source review before preparing its journal.");
    }
    private GatewaySettlementBankEvidenceModels.Evidence bank(AuthenticatedUser user,GatewaySettlementBankEvidenceService.Authority authority,Data data,UUID id) {
        var selected=banks.evidence(user,authority.schoolId(),data.chain().request().id(),id);
        if(!"BANK_EVIDENCE_RECORDED".equals(selected.status())||!"INR".equals(selected.currencyCode())
                ||data.bankSource().summary().reportedAmountSettled()==null
                ||paise(selected.creditedAmount(),false).compareTo(data.bankSource().summary().reportedAmountSettled())!=0)throw unavailable();
        return selected;
    }
    private void validatePostingDate(GatewaySettlementBankEvidenceService.Authority authority,LocalDate date,
            GatewaySettlementBankEvidenceModels.Evidence bank,List<Source> sources) {
        if(date.isBefore(bank.creditedOn())||date.isAfter(FinanceService.businessDate(clock,authority.timeZone()))
                ||sources.stream().anyMatch(source->date.isBefore(source.postingOn())))throw unavailable();
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_day_closes WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='APPROVED' AND business_date>=:postingOn)
                """,authority.params().addValue("postingOn",date),Boolean.class)))throw conflict("GATEWAY_SETTLEMENT_POSTING_DAY_CLOSED","Choose an eligible open posting date; the actual bank and provider timestamps remain unchanged.");
    }
    private static List<UUID> sourceJournals(List<Source> sources) {
        var ids=new LinkedHashSet<UUID>();for(var source:sources){ids.add(source.journalId());ids.add(source.originalPaymentJournalId());}return List.copyOf(ids);
    }
    private static BigDecimal total(List<Source> sources,String kind){return sources.stream().filter(source->kind.equals(source.kind())).map(Source::amount).reduce(new BigDecimal("0.00"),BigDecimal::add);}
    private static BigDecimal componentTotal(List<GatewaySettlementPreparationTerms.Component> components,String direction) {
        return components.stream().filter(component->direction.equals(component.review().direction())).map(GatewaySettlementPreparationTerms.Component::amount).reduce(new BigDecimal("0.00"),BigDecimal::add);
    }
    private static List<SettlementAccountUse> accountUses(GatewaySettlementBankEvidenceModels.Evidence bank,List<Source> sources,
            List<GatewaySettlementPreparationTerms.Component> components,List<SettlementAccountOption> available) {
        var result=new LinkedHashSet<SettlementAccountUse>();result.add(new SettlementAccountUse(bank.bankAccountId(),SettlementAccountUseType.BANK));
        for(var source:sources)result.add(new SettlementAccountUse(source.clearingId(),SettlementAccountUseType.CLEARING));
        for(var component:components)if(component.amount().signum()>0) {
            var account=available.stream().filter(value->value.id().equals(component.review().accountId())).findFirst().orElseThrow(GatewaySettlementPreparationService::unavailable);
            var use="ADJUSTMENT".equals(component.review().key())
                    ?("DEBIT".equals(component.review().direction())?SettlementAccountUseType.ADJUSTMENT_DEBIT:SettlementAccountUseType.ADJUSTMENT_CREDIT)
                    :component.review().key().endsWith("TAX")&&"ASSET".equals(account.accountType())?SettlementAccountUseType.TAX_ASSET:SettlementAccountUseType.EXPENSE;
            result.add(new SettlementAccountUse(account.id(),use));
        }
        return List.copyOf(result);
    }
    private static List<SettlementJournalLine> journalLines(GatewaySettlementBankEvidenceModels.Evidence bank,List<Source> sources,
            List<GatewaySettlementPreparationTerms.Component> components) {
        var totals=new java.util.TreeMap<UUID,BigDecimal[]>();
        addLine(totals,bank.bankAccountId(),paise(bank.creditedAmount(),false),true);
        for(var source:sources)addLine(totals,source.clearingId(),source.amount(),"REFUND".equals(source.kind()));
        for(var component:components)if(component.amount().signum()>0)addLine(totals,component.review().accountId(),component.amount(),"DEBIT".equals(component.review().direction()));
        var lines=new ArrayList<SettlementJournalLine>();
        totals.forEach((account,amounts)->{if(amounts[0].signum()>0)lines.add(new SettlementJournalLine(account,amounts[0],new BigDecimal("0.00"),"Reviewed settlement debit"));
            if(amounts[1].signum()>0)lines.add(new SettlementJournalLine(account,new BigDecimal("0.00"),amounts[1],"Reviewed settlement credit"));});
        if(lines.size()<2||lines.size()>500)throw unavailable();return List.copyOf(lines);
    }
    private static void addLine(Map<UUID,BigDecimal[]> lines,UUID account,BigDecimal amount,boolean debit) {
        var values=lines.computeIfAbsent(account,ignored->new BigDecimal[]{new BigDecimal("0.00"),new BigDecimal("0.00")});
        int side=debit?0:1;values[side]=values[side].add(amount);
    }
    private static List<ComponentFact> facts(GatewaySettlementProvider.SettlementSummary summary,List<Row> rows) {
        var values=new LinkedHashMap<String,BigDecimal>();values.put("SERVICE_CHARGE",summary.reportedServiceCharge());values.put("SERVICE_TAX",summary.reportedServiceTax());
        values.put("ADJUSTMENT",summary.reportedAdjustment());values.put("SETTLEMENT_CHARGE",summary.reportedSettlementCharge());values.put("SETTLEMENT_TAX",summary.reportedSettlementTax());
        values.put("SPLIT_CHARGE",summary.reportedSplitCharge());values.put("SPLIT_TAX",summary.reportedSplitTax());values.put("VENDOR_COMMISSION",summary.reportedVendorCommission());
        var result=new ArrayList<ComponentFact>();
        for(String key:GatewaySettlementPreparationTerms.KEYS) {
            BigDecimal sum=BigDecimal.ZERO;boolean complete=Set.of("SERVICE_CHARGE","SERVICE_TAX").contains(key)&&!rows.isEmpty();
            if(complete)for(var row:rows){String amount="SERVICE_CHARGE".equals(key)?row.event().reportedServiceCharge():row.event().reportedServiceTax();
                if(amount==null||!"INR".equals(row.event().chargesCurrencyCode())){complete=false;break;}sum=sum.add(new BigDecimal(amount));}
            result.add(new ComponentFact(key,decimal(values.get(key)),complete?sum.toPlainString():null,complete));
        }
        return List.copyOf(result);
    }
    private static GatewaySettlementModels.Summary summary(GatewaySettlementProvider.SettlementSummary value) {
        return new GatewaySettlementModels.Summary(value.settlementReference(),value.reportedStatus(),value.transferReference(),value.paymentsFrom(),value.paymentsThrough(),
                value.initiatedAt(),value.processedAt(),decimal(value.reportedPaymentAmount()),decimal(value.reportedServiceCharge()),decimal(value.reportedServiceTax()),
                decimal(value.reportedAdjustment()),decimal(value.reportedSettlementCharge()),decimal(value.reportedSettlementTax()),decimal(value.reportedSplitCharge()),
                decimal(value.reportedSplitTax()),decimal(value.reportedVendorCommission()),decimal(value.reportedAmountSettled()),value.reportedChargeType(),value.reportedSettlementType());
    }
    private static BigDecimal paise(String value,boolean zero) {
        try {var amount=new BigDecimal(value).setScale(2,RoundingMode.UNNECESSARY);if(amount.signum()<0||!zero&&amount.signum()==0||amount.compareTo(new BigDecimal("100000000.00"))>0)throw unavailable();return amount;}
        catch(NumberFormatException|ArithmeticException invalid){throw unavailable();}
    }
    private static String decimal(BigDecimal value){return value==null?null:value.toPlainString();}
    private static UUID uuid(tools.jackson.databind.JsonNode value,String field){return UUID.fromString(value.get(field).stringValue());}
    private static UUID nullableUuid(tools.jackson.databind.JsonNode value,String field){String text=nullableText(value,field);return text==null?null:UUID.fromString(text);}
    private static String nullableText(tools.jackson.databind.JsonNode value,String field){var found=value.get(field);return found==null||found.isNull()?null:found.stringValue();}
    private static ApiException unavailable(){return conflict("GATEWAY_SETTLEMENT_PREPARATION_UNAVAILABLE","The complete retained statement, bank evidence or immutable accounting source requires review.");}
    private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}

    private UUID replay(GatewaySettlementBankEvidenceService.Authority authority,UUID client,String fingerprint,UUID requestId) {
        var rows=jdbc.query("""
                SELECT preparation.id,preparation.request_fingerprint,root.request_id
                FROM fee_gateway_settlement_preparations preparation JOIN fee_gateway_settlement_roots root ON root.id=preparation.root_id
                  AND root.organisation_id=preparation.organisation_id AND root.school_id=preparation.school_id
                WHERE preparation.organisation_id=:organisationId AND preparation.school_id=:schoolId
                  AND preparation.prepared_by_user_id=:userId AND preparation.client_request_id=:clientId
                """,authority.params().addValue("clientId",client),(row,n)->new Replay(row.getObject("id",UUID.class),row.getObject("request_id",UUID.class),row.getString("request_fingerprint")));
        if(rows.size()>1)throw unavailable();if(rows.isEmpty())return null;var result=rows.getFirst();
        if(!fingerprint.equals(result.fingerprint())||!requestId.equals(result.requestId()))throw conflict("GATEWAY_SETTLEMENT_REQUEST_REUSED","This retry identity belongs to different immutable settlement terms.");
        return result.id();
    }
    private List<Preparation> history(AuthenticatedUser user,GatewaySettlementBankEvidenceService.Authority authority,UUID requestId) {
        var ids=jdbc.query(PREPARATION_ROWS+" WHERE preparation.organisation_id=:organisationId AND preparation.school_id=:schoolId AND root.request_id=:requestId ORDER BY preparation.prepared_at DESC,preparation.id DESC LIMIT 25",
                authority.params().addValue("requestId",requestId),(row,n)->row.getObject("id",UUID.class));
        var result=new ArrayList<Preparation>();for(UUID id:ids)result.add(projection(user,authority,id));return List.copyOf(result);
    }
    private Preparation projection(AuthenticatedUser user,GatewaySettlementBankEvidenceService.Authority authority,UUID id) {
        var rows=jdbc.query(PREPARATION_ROWS+" WHERE preparation.id=:id AND preparation.organisation_id=:organisationId AND preparation.school_id=:schoolId",
                authority.params().addValue("id",id),(row,n)->new StoredPreparation(row.getObject("id",UUID.class),row.getObject("client_request_id",UUID.class),row.getObject("root_id",UUID.class),
                        row.getObject("request_id",UUID.class),row.getObject("bank_evidence_id",UUID.class),row.getInt("revision"),row.getObject("previous_preparation_id",UUID.class),row.getObject("journal_id",UUID.class),
                        row.getObject("posting_on",LocalDate.class),row.getString("currency_code"),row.getBigDecimal("bank_amount"),row.getBigDecimal("payment_total"),row.getBigDecimal("refund_total"),
                        row.getBigDecimal("component_debit_total"),row.getBigDecimal("component_credit_total"),row.getInt("source_count"),row.getObject("prepared_at",OffsetDateTime.class).toInstant(),
                        row.getString("prepared_by"),row.getString("preparation_reason"),row.getBoolean("abandoned")));
        if(rows.size()!=1)throw new ApiException(HttpStatus.NOT_FOUND,"GATEWAY_SETTLEMENT_PREPARATION_NOT_FOUND","The retained settlement preparation is unavailable for this school.");
        var stored=rows.getFirst();var status=accounting.settlementJournalStatuses(user,authority.schoolId(),List.of(id)).get(id);
        if(status==null||!stored.journalId().equals(status.journalId())||!Set.of("SUBMITTED","POSTED","REJECTED","REVERSED").contains(status.status()))throw unavailable();
        return new Preparation(stored.id(),stored.clientId(),stored.rootId(),stored.requestId(),stored.bankId(),stored.revision(),stored.previousId(),stored.journalId(),
                stored.abandoned()?"ABANDONED":status.status(),status.rowVersion(),status.voucherNumber(),stored.postingOn(),stored.currency(),
                stored.bankAmount().setScale(2).toPlainString(),stored.payments().setScale(2).toPlainString(),stored.refunds().setScale(2).toPlainString(),
                stored.debits().setScale(2).toPlainString(),stored.credits().setScale(2).toPlainString(),stored.sourceCount(),stored.at(),stored.by(),stored.reason());
    }
    private boolean statementConsumed(GatewaySettlementBankEvidenceService.Authority authority,GatewaySettlementSourceReviewEvidence.Chain chain,UUID ignoredRoot) {
        var binding=chain.binding();var p=authority.params().addValue("providerId",binding.providerConfigId()).addValue("providerCode",binding.providerCode())
                .addValue("environment",binding.environment()).addValue("merchant",binding.merchantReference()).addValue("settlement",chain.request().settlementReference());
        var ids=jdbc.query("""
                SELECT root.id,preparation.id preparation_id FROM fee_gateway_settlement_roots root
                JOIN LATERAL (SELECT id FROM fee_gateway_settlement_preparations WHERE root_id=root.id
                  AND organisation_id=root.organisation_id AND school_id=root.school_id ORDER BY revision DESC LIMIT 1) preparation ON true
                WHERE root.organisation_id=:organisationId AND root.school_id=:schoolId AND root.provider_config_id=:providerId
                  AND root.provider_code=:providerCode AND root.provider_environment=:environment AND root.merchant_account_reference=:merchant AND root.settlement_reference=:settlement
                  AND NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_abandonments release WHERE release.root_id=root.id
                    AND release.organisation_id=root.organisation_id AND release.school_id=root.school_id)
                """,p,(row,n)->Map.entry(row.getObject("id",UUID.class),row.getObject("preparation_id",UUID.class)));
        // Rejected/reversed attempts remain visible and may be explicitly repaired;
        // a new root still requires a controlled never-posted abandonment.
        return ids.stream().anyMatch(row->!row.getKey().equals(ignoredRoot));
    }
    private Root rootForPreparation(AuthenticatedUser user,GatewaySettlementBankEvidenceService.Authority authority,Data data,Command command,List<Source> sources) {
        if(command.previousPreparationId()==null) {
            if(statementConsumed(authority,data.chain(),null))throw conflict("GATEWAY_SETTLEMENT_ALREADY_PREPARED","Inspect the retained attempt. Repair it or release an independently rejected never-posted preparation; do not create duplicate settlement money.");
            return null;
        }
        var previous=projection(user,authority,command.previousPreparationId());
        if(!previous.requestId().equals(data.chain().request().id())||!previous.bankEvidenceId().equals(command.bankEvidenceId())
                ||!Set.of("REJECTED","REVERSED").contains(previous.status()))throw unavailable();
        UUID latest=jdbc.queryForObject("""
                SELECT id FROM fee_gateway_settlement_preparations WHERE root_id=:rootId
                  AND organisation_id=:organisationId AND school_id=:schoolId ORDER BY revision DESC LIMIT 1
                """,authority.params().addValue("rootId",previous.rootId()),UUID.class);
        if(!previous.id().equals(latest))throw conflict("GATEWAY_SETTLEMENT_REPAIR_STALE","Repair only the latest retained rejected or reversed attempt.");
        reuseClaims(authority,previous.rootId(),sources);
        return new Root(previous.rootId(),previous.requestId(),previous.bankEvidenceId(),previous.revision()+1,previous.id(),previous.journalId());
    }
    private List<Source> reuseClaims(GatewaySettlementBankEvidenceService.Authority authority,UUID rootId,List<Source> current) {
        var retained=jdbc.query("""
                SELECT * FROM fee_gateway_settlement_sources WHERE root_id=:rootId AND organisation_id=:organisationId AND school_id=:schoolId
                """,authority.params().addValue("rootId",rootId),(row,n)->new Claim(row.getObject("id",UUID.class),row.getString("source_kind"),row.getObject("payment_id",UUID.class),
                        row.getObject("refund_posting_id",UUID.class),row.getObject("fee_ledger_entry_id",UUID.class),row.getObject("posting_item_id",UUID.class),row.getObject("posting_batch_id",UUID.class),
                        row.getObject("source_journal_id",UUID.class),row.getObject("configuration_id",UUID.class),row.getObject("clearing_account_id",UUID.class),row.getBigDecimal("amount"),
                        row.getString("currency_code"),row.getString("source_sha256"),row.getString("batch_sha256")));
        if(retained.size()!=current.size())throw unavailable();var remaining=new LinkedHashMap<String,Claim>();
        for(var claim:retained)if(remaining.putIfAbsent(economicKey(claim.kind(),claim.paymentId(),claim.refundId()),claim)!=null)throw unavailable();
        var result=new ArrayList<Source>();
        for(var source:current) {
            var claim=remaining.remove(economicKey(source.kind(),source.paymentId(),source.refundId()));
            if(claim==null||!claim.paymentId().equals(source.paymentId())||!Objects.equals(claim.refundId(),source.refundId())
                    ||!claim.ledgerId().equals(source.ledgerId())||!claim.itemId().equals(source.itemId())||!claim.batchId().equals(source.batchId())
                    ||!claim.journalId().equals(source.journalId())||!claim.configurationId().equals(source.configurationId())||!claim.clearingId().equals(source.clearingId())
                    ||claim.amount().compareTo(source.amount())!=0||!claim.currency().equals(source.currency())||!claim.sourceHash().equals(source.sourceHash())||!claim.batchHash().equals(source.batchHash()))throw unavailable();
            result.add(new Source(claim.id(),source.kind(),source.paymentId(),source.refundId(),source.ledgerId(),source.itemId(),source.batchId(),source.journalId(),source.configurationId(),source.clearingId(),
                    source.amount(),source.currency(),source.sourceHash(),source.batchHash(),source.postingOn(),source.originalPaymentJournalId(),source.sourceReadId(),source.rowIndex(),source.associationReason()));
        }
        if(!remaining.isEmpty())throw unavailable();return List.copyOf(result);
    }
    private static String economicKey(String kind,UUID paymentId,UUID refundId){return kind+":"+("REFUND".equals(kind)?refundId:paymentId);}
    private void insertRoot(GatewaySettlementBankEvidenceService.Authority authority,Data data,Root root) {
        var binding=data.chain().binding();
        int changed=jdbc.update("""
                INSERT INTO fee_gateway_settlement_roots(id,organisation_id,school_id,bank_evidence_id,provider_config_id,provider_code,provider_environment,
                  merchant_account_reference,settlement_reference,request_id,source_summary_read_id,source_summary_row_index,created_by_user_id,created_by_membership_id)
                VALUES(:rootId,:organisationId,:schoolId,:bankId,:providerId,:providerCode,:environment,:merchant,:settlement,:requestId,:summaryId,:summaryRow,:userId,:membershipId)
                """,authority.params().addValue("rootId",root.id()).addValue("bankId",root.bankId()).addValue("providerId",binding.providerConfigId()).addValue("providerCode",binding.providerCode())
                .addValue("environment",binding.environment()).addValue("merchant",binding.merchantReference()).addValue("settlement",data.chain().request().settlementReference())
                .addValue("requestId",root.requestId()).addValue("summaryId",data.bankSource().summaryReadId()).addValue("summaryRow",data.bankSource().summaryRowIndex()));
        if(changed!=1)throw unavailable();
    }
    private void insertSources(GatewaySettlementBankEvidenceService.Authority authority,UUID rootId,List<Source> sources) {
        for(var source:sources)if(jdbc.update("""
                INSERT INTO fee_gateway_settlement_sources(id,organisation_id,school_id,root_id,source_kind,payment_id,refund_posting_id,fee_ledger_entry_id,
                  posting_item_id,posting_batch_id,source_journal_id,configuration_id,clearing_account_id,amount,currency_code,source_sha256,batch_sha256)
                VALUES(:id,:organisationId,:schoolId,:rootId,:kind,:paymentId,:refundId,:ledgerId,:itemId,:batchId,:journalId,:configurationId,:clearingId,:amount,:currency,:sourceHash,:batchHash)
                """,authority.params().addValue("rootId",rootId).addValue("id",source.id()).addValue("kind",source.kind()).addValue("paymentId",source.paymentId()).addValue("refundId",source.refundId())
                .addValue("ledgerId",source.ledgerId()).addValue("itemId",source.itemId()).addValue("batchId",source.batchId()).addValue("journalId",source.journalId()).addValue("configurationId",source.configurationId())
                .addValue("clearingId",source.clearingId()).addValue("amount",source.amount()).addValue("currency",source.currency()).addValue("sourceHash",source.sourceHash()).addValue("batchHash",source.batchHash()))!=1)throw unavailable();
    }
    private void insertPreparation(GatewaySettlementBankEvidenceService.Authority authority,Root root,UUID id,UUID journalId,FeeBookContext book,Command command,String fingerprint,
            Map<String,Object> snapshot,BigDecimal payments,BigDecimal refunds,BigDecimal bank,List<GatewaySettlementPreparationTerms.Component> components) {
        String encoded=json.writeValueAsString(snapshot);
        if(jdbc.update("""
                INSERT INTO fee_gateway_settlement_preparations(id,organisation_id,school_id,root_id,revision,previous_preparation_id,previous_journal_id,
                  financial_year_id,accounting_period_id,posting_on,bank_amount,payment_total,refund_total,component_debit_total,component_credit_total,currency_code,
                  snapshot_version,evidence_snapshot,evidence_sha256,journal_id,prepared_by_user_id,prepared_by_membership_id,client_request_id,request_fingerprint,preparation_reason)
                VALUES(:id,:organisationId,:schoolId,:rootId,:revision,:previousId,:previousJournal,:yearId,:periodId,:postingOn,:bank,:payments,:refunds,:debits,:credits,'INR',
                  1,CAST(:snapshot AS jsonb),encode(sha256(convert_to(CAST(:snapshot AS jsonb)::text,'UTF8')),'hex'),:journalId,:userId,:membershipId,:clientId,:fingerprint,:reason)
                """,authority.params().addValue("id",id).addValue("rootId",root.id()).addValue("revision",root.revision()).addValue("previousId",root.previousId()).addValue("previousJournal",root.previousJournal())
                .addValue("yearId",book.financialYearId()).addValue("periodId",book.accountingPeriodId()).addValue("postingOn",command.postingOn()).addValue("bank",bank).addValue("payments",payments).addValue("refunds",refunds)
                .addValue("debits",componentTotal(components,"DEBIT")).addValue("credits",componentTotal(components,"CREDIT")).addValue("snapshot",encoded).addValue("journalId",journalId)
                .addValue("clientId",command.clientRequestId()).addValue("fingerprint",fingerprint).addValue("reason",command.reason()))!=1)throw unavailable();
    }
    private void insertPhysicalRows(GatewaySettlementBankEvidenceService.Authority authority,UUID preparationId,List<Source> sources) {
        for(var source:sources)if(jdbc.update("""
                INSERT INTO fee_gateway_settlement_source_rows(id,organisation_id,school_id,preparation_id,source_read_id,row_index,source_id,association_reason)
                VALUES(:id,:organisationId,:schoolId,:preparationId,:readId,:rowIndex,:sourceId,:reason)
                """,authority.params().addValue("id",UUID.randomUUID()).addValue("preparationId",preparationId).addValue("readId",source.sourceReadId())
                .addValue("rowIndex",source.rowIndex()).addValue("sourceId",source.id()).addValue("reason",source.associationReason()))!=1)throw unavailable();
    }
    private void insertComponents(GatewaySettlementBankEvidenceService.Authority authority,UUID preparationId,List<GatewaySettlementPreparationTerms.Component> components,String primaryBasis) {
        for(var component:components) {
            var review=component.review();boolean events=Set.of("SERVICE_CHARGE","SERVICE_TAX").contains(review.key())&&"EVENTS".equals(primaryBasis);
            // An explicitly documented unknown value keeps the original unknown
            // field visible; the selected primary basis remains in the command.
            String selected=events?(component.fact().eventsComplete()?component.fact().eventsAmount():null):component.fact().summaryAmount();
            var raw=new LinkedHashMap<String,Object>();raw.put("mode",review.mode());raw.put("summaryAmount",component.fact().summaryAmount());
            raw.put("eventsAmount",component.fact().eventsAmount());raw.put("eventsComplete",component.fact().eventsComplete());raw.put("selectedReportedAmount",selected);
            if(jdbc.update("""
                    INSERT INTO fee_gateway_settlement_components(id,organisation_id,school_id,preparation_id,component_key,component_kind,component_basis,direction,
                      account_id,amount,currency_code,evidence_reference,reason,raw_field_facts)
                    VALUES(:id,:organisationId,:schoolId,:preparationId,:key,:key,:basis,:direction,:accountId,:amount,'INR',:reference,:reason,CAST(:facts AS jsonb))
                    """,authority.params().addValue("id",UUID.randomUUID()).addValue("preparationId",preparationId).addValue("key",review.key()).addValue("basis",component.basis()).addValue("direction",review.direction())
                    .addValue("accountId",review.accountId()).addValue("amount",component.amount()).addValue("reference",review.evidenceReference()).addValue("reason",review.reason()).addValue("facts",json.writeValueAsString(raw)))!=1)throw unavailable();
        }
    }

    private static final String PREPARATION_ROWS="""
            SELECT preparation.*,root.request_id,root.bank_evidence_id,actor.display_name prepared_by,
              (SELECT count(*) FROM fee_gateway_settlement_sources source WHERE source.root_id=root.id
                AND source.organisation_id=root.organisation_id AND source.school_id=root.school_id) source_count,
              EXISTS(SELECT 1 FROM fee_gateway_settlement_abandonments release WHERE release.root_id=root.id
                AND release.organisation_id=root.organisation_id AND release.school_id=root.school_id) abandoned
            FROM fee_gateway_settlement_preparations preparation JOIN fee_gateway_settlement_roots root ON root.id=preparation.root_id
              AND root.organisation_id=preparation.organisation_id AND root.school_id=preparation.school_id
            JOIN user_accounts actor ON actor.id=preparation.prepared_by_user_id
            """;
    private record Replay(UUID id,UUID requestId,String fingerprint) {}
    private record StoredPreparation(UUID id,UUID clientId,UUID rootId,UUID requestId,UUID bankId,int revision,UUID previousId,UUID journalId,LocalDate postingOn,
            String currency,BigDecimal bankAmount,BigDecimal payments,BigDecimal refunds,BigDecimal debits,BigDecimal credits,int sourceCount,Instant at,String by,String reason,boolean abandoned) {}
    private record Claim(UUID id,String kind,UUID paymentId,UUID refundId,UUID ledgerId,UUID itemId,UUID batchId,UUID journalId,UUID configurationId,
            UUID clearingId,BigDecimal amount,String currency,String sourceHash,String batchHash) {}

    private record Data(GatewaySettlementSourceReviewEvidence.Chain chain,GatewaySettlementBankEvidenceService.Source bankSource,
            GatewaySettlementBankEvidenceModels.Context bankContext,List<Row> rows,List<ComponentFact> facts,String fingerprint,
            String paymentTotal,String refundTotal,List<String> blockers) {}
    private record Root(UUID id,UUID requestId,UUID bankId,int revision,UUID previousId,UUID previousJournal) {}
    private record Source(UUID id,String kind,UUID paymentId,UUID refundId,UUID ledgerId,UUID itemId,UUID batchId,UUID journalId,
            UUID configurationId,UUID clearingId,BigDecimal amount,String currency,String sourceHash,String batchHash,
            LocalDate postingOn,UUID originalPaymentJournalId,UUID sourceReadId,int rowIndex,String associationReason) {
        @Override public String toString(){return "Source[private immutable consumed settlement evidence]";}
    }
}

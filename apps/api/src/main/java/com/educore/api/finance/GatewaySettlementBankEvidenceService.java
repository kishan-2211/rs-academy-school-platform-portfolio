package com.educore.api.finance;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.common.api.DatabaseConflict;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable bank attestation and independent rejection. No provider, fee, refund, receipt or journal writes. */
@Service
public class GatewaySettlementBankEvidenceService {
    private final GatewaySettlementService settlements;
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountingModuleBridge accounting;
    private final SecurityAuditService audit;
    public GatewaySettlementBankEvidenceService(GatewaySettlementService settlements,NamedParameterJdbcTemplate jdbc,
            AccountingModuleBridge accounting,SecurityAuditService audit){
        this.settlements=Objects.requireNonNull(settlements);this.jdbc=Objects.requireNonNull(jdbc);
        this.accounting=Objects.requireNonNull(accounting);this.audit=Objects.requireNonNull(audit);
    }

    record Source(UUID organisationId,UUID schoolId,UUID requestId,String timeZone,String settlementReference,
            UUID summaryReadId,int summaryRowIndex,Instant observedAt,GatewaySettlementProvider.SettlementSummary summary){
        @Override public String toString(){return "Source[private exact selected statement witness]";}
    }
    record Authority(UUID organisationId,UUID schoolId,UUID userId,UUID membershipId,String timeZone,boolean canRecord,boolean canReject){
        MapSqlParameterSource params(){return new MapSqlParameterSource().addValue("organisationId",organisationId).addValue("schoolId",schoolId)
                .addValue("userId",userId).addValue("membershipId",membershipId);}
        @Override public String toString(){return "Authority[private live human bank-evidence scope]";}
    }

    @Transactional(readOnly=true)
    public GatewaySettlementBankEvidenceModels.Context context(AuthenticatedUser user,UUID schoolId,UUID requestId){
        var source=settlements.bankEvidenceSource(user,schoolId,requestId);var authority=settlements.bankEvidenceAuthority(user,schoolId,null);
        var banks=bankAccounts(user,schoolId);
        var rows=jdbc.query(evidenceRows(authority)+" WHERE evidence.organisation_id=:organisationId AND evidence.school_id=:schoolId AND evidence.request_id=:requestId ORDER BY evidence.recorded_at DESC,evidence.id DESC LIMIT 26",
                authority.params().addValue("requestId",requestId),(row,n)->stored(row,source,authority));
        boolean more=rows.size()>25;
        return new GatewaySettlementBankEvidenceModels.Context(schoolId,requestId,source.settlementReference(),source.timeZone(),
                authority.canRecord()&&source.timeZone().equals(authority.timeZone())&&!banks.isEmpty(),authority.canReject(),
                new GatewaySettlementBankEvidenceModels.Summary(source.summaryReadId(),source.summaryRowIndex(),source.observedAt(),source.summary().reportedStatus(),
                    source.summary().transferReference(),money(source.summary().reportedAmountSettled())),banks,
                rows.subList(0,Math.min(25,rows.size())).stream().map(Stored::projection).toList(),more);
    }

    @Transactional
    public GatewaySettlementBankEvidenceModels.Evidence record(AuthenticatedUser user,UUID requestId,GatewaySettlementBankEvidenceModels.Command command){
        var terms=GatewaySettlementBankEvidenceTerms.record(requestId,command);
        var source=settlements.bankEvidenceSource(user,terms.schoolId(),requestId);
        var authority=settlements.bankEvidenceAuthority(user,terms.schoolId(),"fees.close.propose");writable(authority);
        var previous=replayRecord(authority,terms.clientId());
        if(previous!=null)return exactRecord(previous,terms,source,authority);
        fence(authority);
        authority=settlements.bankEvidenceAuthority(user,terms.schoolId(),"fees.close.propose");writable(authority);
        previous=replayRecord(authority,terms.clientId());if(previous!=null)return exactRecord(previous,terms,source,authority);
        if(!source.timeZone().equals(authority.timeZone()))throw conflict("GATEWAY_SETTLEMENT_BANK_TIME_ZONE_CHANGED","The school timezone changed. Retain the original statement and refresh its review before recording new bank evidence.");
        if(bankAccounts(user,terms.schoolId()).stream().noneMatch(bank->bank.id().equals(terms.bankId())&&bank.currencyCode().equals(terms.currency())))
            throw conflict("GATEWAY_SETTLEMENT_BANK_ACCOUNT_UNAVAILABLE","Select an active permitted bank account in the current INR bank context.");
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",authority.params(),(row,n)->row.getObject(1,OffsetDateTime.class).toInstant());
        if(now==null)throw unavailable();
        if(terms.actualAt().isAfter(now))throw conflict("GATEWAY_SETTLEMENT_BANK_FUTURE_CREDIT","The actual bank credit time cannot be in the future.");
        LocalDate creditedOn=terms.actualAt().atZone(ZoneId.of(source.timeZone())).toLocalDate();
        var p=authority.params().addValue("reference",terms.normalizedReference());
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                /* settlement-bank-evidence-manual-reference */
                SELECT EXISTS(SELECT 1 FROM fee_payments payment WHERE payment.organisation_id=:organisationId AND payment.school_id=:schoolId
                  AND payment.gateway_capture_id IS NULL AND payment.payment_method IN('BANK_TRANSFER','SCHOOL_UPI')
                  AND normalize_manual_bank_reference(payment.external_reference)=:reference)
                """,p,Boolean.class)))throw conflict("GATEWAY_SETTLEMENT_BANK_REFERENCE_ALREADY_COLLECTED","This bank reference already appears in manual receipt history, including reversed or ambiguous legacy receipts.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                /* settlement-bank-evidence-active-reference */
                SELECT EXISTS(SELECT 1 FROM fee_gateway_settlement_bank_evidence evidence
                  WHERE evidence.organisation_id=:organisationId AND evidence.school_id=:schoolId AND evidence.normalized_bank_reference=:reference
                    AND NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_bank_evidence_rejections rejection
                      WHERE rejection.bank_evidence_id=evidence.id AND rejection.organisation_id=evidence.organisation_id AND rejection.school_id=evidence.school_id))
                """,p,Boolean.class)))throw conflict("GATEWAY_SETTLEMENT_BANK_REFERENCE_RESERVED","This bank reference is already reserved by retained unposted settlement evidence.");
        UUID id=UUID.randomUUID();p.addValue("id",id).addValue("requestId",requestId).addValue("summaryReadId",source.summaryReadId())
                .addValue("summaryRowIndex",source.summaryRowIndex()).addValue("settlement",source.settlementReference())
                .addValue("bankId",terms.bankId()).addValue("currency",terms.currency()).addValue("amount",terms.amount())
                .addValue("actualAt",GatewayExactInstant.parameter(terms.actualAt())).addValue("actualExact",terms.actualExact())
                .addValue("creditedOn",creditedOn).addValue("timeZone",source.timeZone()).addValue("kind",terms.kind())
                .addValue("bankReference",terms.reference()).addValue("reason",terms.reason()).addValue("clientId",terms.clientId()).addValue("fingerprint",terms.fingerprint());
        try{
            int inserted=jdbc.update("""
                    INSERT INTO fee_gateway_settlement_bank_evidence(id,organisation_id,school_id,request_id,source_summary_read_id,source_summary_row_index,
                      settlement_reference,bank_account_id,currency_code,credited_amount,actual_credited_at,actual_credited_at_exact,credited_on,school_time_zone,
                      verification_kind,bank_credit_reference,normalized_bank_reference,confirmed_received,reason,client_request_id,request_fingerprint,
                      recorded_by_user_id,recorded_by_membership_id)
                    VALUES(:id,:organisationId,:schoolId,:requestId,:summaryReadId,:summaryRowIndex,:settlement,:bankId,:currency,:amount,
                      :actualAt,:actualExact,:creditedOn,:timeZone,:kind,:bankReference,:reference,true,:reason,:clientId,:fingerprint,:userId,:membershipId)
                    """,p);
            if(inserted!=1)throw unavailable();
        }catch(DataAccessException failure){throw translated(failure);}
        audit.recordScoped(user,authority.membershipId(),authority.schoolId(),"finance.gateway.settlement.bank-evidence-recorded","GATEWAY_SETTLEMENT_BANK_EVIDENCE",id.toString(),
                AuditOutcome.SUCCEEDED,terms.reason(),Map.of("requestId",requestId,"stage","UNPOSTED_BANK_EVIDENCE"));
        return find(authority,source,id).projection();
    }

    @Transactional
    public GatewaySettlementBankEvidenceModels.Evidence reject(AuthenticatedUser user,UUID requestId,UUID evidenceId,GatewaySettlementBankEvidenceModels.RejectionCommand command){
        var terms=GatewaySettlementBankEvidenceTerms.rejection(requestId,evidenceId,command);
        var source=settlements.bankEvidenceSource(user,terms.schoolId(),requestId);
        var authority=settlements.bankEvidenceAuthority(user,terms.schoolId(),"fees.close.approve");writable(authority);
        var previous=replayRejection(authority,terms.clientId());if(previous!=null)return exactRejection(previous,terms,source,authority);
        fence(authority);
        authority=settlements.bankEvidenceAuthority(user,terms.schoolId(),"fees.close.approve");writable(authority);
        previous=replayRejection(authority,terms.clientId());if(previous!=null)return exactRejection(previous,terms,source,authority);
        var retained=find(authority,source,evidenceId);
        if(authority.userId().equals(retained.makerId()))throw new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_SETTLEMENT_BANK_INDEPENDENT_REJECTION_REQUIRED","A different authorized person must reject mistaken bank evidence.");
        if(retained.projection().rejection()!=null)throw conflict("GATEWAY_SETTLEMENT_BANK_EVIDENCE_ALREADY_REJECTED","This retained evidence already has an independent rejection. Refresh its history.");
        UUID decision=UUID.randomUUID();var p=authority.params().addValue("id",decision).addValue("evidenceId",evidenceId)
                .addValue("clientId",terms.clientId()).addValue("fingerprint",terms.fingerprint()).addValue("reason",terms.reason());
        try{
            int inserted=jdbc.update("""
                    INSERT INTO fee_gateway_settlement_bank_evidence_rejections(id,organisation_id,school_id,bank_evidence_id,client_request_id,
                      request_fingerprint,reason,confirmed_mistaken_evidence,rejected_by_user_id,rejected_by_membership_id)
                    VALUES(:id,:organisationId,:schoolId,:evidenceId,:clientId,:fingerprint,:reason,true,:userId,:membershipId)
                    """,p);if(inserted!=1)throw unavailable();
        }catch(DataAccessException failure){throw translated(failure);}
        audit.recordScoped(user,authority.membershipId(),authority.schoolId(),"finance.gateway.settlement.bank-evidence-rejected","GATEWAY_SETTLEMENT_BANK_EVIDENCE",evidenceId.toString(),
                AuditOutcome.SUCCEEDED,terms.reason(),Map.of("requestId",requestId,"decisionId",decision,"stage","UNPOSTED_EVIDENCE_CORRECTION"));
        return find(authority,source,evidenceId).projection();
    }

    private List<GatewaySettlementBankEvidenceModels.BankAccount> bankAccounts(AuthenticatedUser user,UUID schoolId){
        var accounts=accounting.settlementBankEvidenceAccounts(user,schoolId);if(accounts==null||accounts.size()>1000)throw unavailable();
        return accounts.stream().map(bank->new GatewaySettlementBankEvidenceModels.BankAccount(bank.id(),bank.code(),bank.name(),bank.currencyCode())).toList();
    }
    private void writable(Authority authority){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_SETTLEMENT_SCOPE_DENIED","Bank evidence requires the actual authorized human and writable transaction.");
        if(!"read committed".equals(jdbc.queryForObject("SELECT current_setting('transaction_isolation')",authority.params(),String.class)))
            throw conflict("GATEWAY_SETTLEMENT_BANK_RETRY_REQUIRED","Retry bank evidence in a fresh read-committed transaction.");
    }
    private void fence(Authority authority){jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked",
            authority.params().addValue("lockKey",authority.schoolId()+":finance"),Integer.class);}
    private Replay replayRecord(Authority authority,UUID client){return replay("""
            SELECT id,request_id,request_fingerprint FROM fee_gateway_settlement_bank_evidence
            WHERE organisation_id=:organisationId AND school_id=:schoolId AND recorded_by_user_id=:userId AND client_request_id=:clientId
            """,authority,client,false);}
    private Replay replayRejection(Authority authority,UUID client){return replay("""
            SELECT id,bank_evidence_id,request_fingerprint FROM fee_gateway_settlement_bank_evidence_rejections
            WHERE organisation_id=:organisationId AND school_id=:schoolId AND rejected_by_user_id=:userId AND client_request_id=:clientId
            """,authority,client,true);}
    private Replay replay(String sql,Authority authority,UUID client,boolean decision){
        var rows=jdbc.query(sql,authority.params().addValue("clientId",client),(row,n)->new Replay(row.getObject("id",UUID.class),
                row.getObject(decision?"bank_evidence_id":"request_id",UUID.class),row.getString("request_fingerprint")));
        if(rows.size()>1)throw unavailable();return rows.isEmpty()?null:rows.getFirst();
    }
    private GatewaySettlementBankEvidenceModels.Evidence exactRecord(Replay replay,GatewaySettlementBankEvidenceTerms.RecordTerms terms,Source source,Authority authority){
        if(!terms.requestId().equals(replay.target())||!terms.fingerprint().equals(replay.fingerprint()))throw reused();
        return find(authority,source,replay.id()).projection();
    }
    private GatewaySettlementBankEvidenceModels.Evidence exactRejection(Replay replay,GatewaySettlementBankEvidenceTerms.RejectionTerms terms,Source source,Authority authority){
        if(!terms.evidenceId().equals(replay.target())||!terms.fingerprint().equals(replay.fingerprint()))throw reused();
        var result=find(authority,source,terms.evidenceId()).projection();
        if(result.rejection()==null||!replay.id().equals(result.rejection().id()))throw unavailable();return result;
    }
    private Stored find(Authority authority,Source source,UUID id){
        var rows=jdbc.query(evidenceRows(authority)+" WHERE evidence.organisation_id=:organisationId AND evidence.school_id=:schoolId AND evidence.request_id=:requestId AND evidence.id=:id",
                authority.params().addValue("requestId",source.requestId()).addValue("id",id),(row,n)->stored(row,source,authority));
        if(rows.size()!=1)throw new ApiException(HttpStatus.NOT_FOUND,"GATEWAY_SETTLEMENT_BANK_EVIDENCE_NOT_FOUND","The bank evidence is unavailable for this school and report.");return rows.getFirst();
    }
    /** Exact retained identity for the connected preparation, including older history beyond the first page. */
    GatewaySettlementBankEvidenceModels.Evidence evidence(AuthenticatedUser user,UUID schoolId,UUID requestId,UUID evidenceId) {
        return find(settlements.bankEvidenceAuthority(user,schoolId,null),settlements.bankEvidenceSource(user,schoolId,requestId),evidenceId).projection();
    }
    private static Stored stored(ResultSet row,Source source,Authority authority)throws SQLException{
        try{
            if(!source.organisationId().equals(row.getObject("organisation_id",UUID.class))||!source.schoolId().equals(row.getObject("school_id",UUID.class))
                    ||!source.requestId().equals(row.getObject("request_id",UUID.class))||!source.summaryReadId().equals(row.getObject("source_summary_read_id",UUID.class))
                    ||source.summaryRowIndex()!=row.getInt("source_summary_row_index")||!source.settlementReference().equals(row.getString("settlement_reference"))
                    ||!source.timeZone().equals(row.getString("school_time_zone"))||!row.getBoolean("confirmed_received"))throw unavailable();
            String exact=row.getString("actual_credited_at_exact");if(exact==null)throw unavailable();
            Instant actual=GatewayExactInstant.read(row,"actual_credited_at_exact","actual_credited_at");
            LocalDate creditedOn=row.getObject("credited_on",LocalDate.class);
            if(!actual.atZone(ZoneId.of(source.timeZone())).toLocalDate().equals(creditedOn))throw unavailable();
            BigDecimal amount=row.getBigDecimal("credited_amount");if(amount==null)throw unavailable();
            String canonicalAmount=GatewaySettlementBankEvidenceTerms.amount(amount.toPlainString()).toPlainString();
            String reference=GatewaySettlementBankEvidenceTerms.reference(row.getString("bank_credit_reference"));
            if(!ManualCollectionEvidence.normalizeBankReference(reference).equals(row.getString("normalized_bank_reference")))throw unavailable();
            UUID maker=row.getObject("recorded_by_user_id",UUID.class),rejectionId=row.getObject("rejection_id",UUID.class);
            if(maker==null)throw unavailable();
            GatewaySettlementBankEvidenceModels.Rejection rejection=null;
            if(rejectionId!=null){
                UUID rejector=row.getObject("rejected_by_user_id",UUID.class);
                if(rejector==null||rejector.equals(maker)||!row.getBoolean("confirmed_mistaken_evidence"))throw unavailable();
                rejection=new GatewaySettlementBankEvidenceModels.Rejection(rejectionId,GatewaySettlementBankEvidenceTerms.reason(row.getString("rejection_reason")),
                        time(row,"rejected_at"),row.getString("rejected_by"));
            }
            var projection=new GatewaySettlementBankEvidenceModels.Evidence(row.getObject("id",UUID.class),row.getObject("client_request_id",UUID.class),
                    row.getObject("bank_account_id",UUID.class),reference,row.getString("currency_code"),canonicalAmount,exact,creditedOn,row.getString("verification_kind"),
                    time(row,"recorded_at"),row.getString("recorded_by"),GatewaySettlementBankEvidenceTerms.reason(row.getString("reason")),
                    rejection==null?"BANK_EVIDENCE_RECORDED":"REJECTED",reviewReasons(source.summary(),amount,reference),rejection,
                    rejection==null&&!row.getBoolean("settlement_consumed")&&authority.canReject()&&!authority.userId().equals(maker));
            return new Stored(projection,maker);
        }catch(ApiException|IllegalArgumentException|NullPointerException malformed){throw unavailable();}
    }
    static List<String> reviewReasons(GatewaySettlementProvider.SettlementSummary summary,BigDecimal amount,String reference){
        var reasons=new ArrayList<>(List.of("NOT_RECONCILED","SUMMARY_CURRENCY_UNCONFIRMED","SOURCE_RECONCILIATION_PENDING"));
        if(summary.reportedAmountSettled()==null)reasons.add("SUMMARY_AMOUNT_UNKNOWN");
        else if(summary.reportedAmountSettled().compareTo(amount)!=0)reasons.add("SUMMARY_AMOUNT_MISMATCH");
        if(summary.transferReference()==null)reasons.add("TRANSFER_REFERENCE_UNKNOWN");
        else if(!Objects.equals(ManualCollectionEvidence.normalizeBankReference(summary.transferReference()),ManualCollectionEvidence.normalizeBankReference(reference)))reasons.add("TRANSFER_REFERENCE_MISMATCH");
        if(!"SUCCESS".equals(summary.reportedStatus()))reasons.add("SUMMARY_STATUS_REVIEW");return List.copyOf(reasons);
    }
    private static RuntimeException translated(DataAccessException failure){
        String constraint=constraint(failure);
        if("gateway_bank_reference_reserved".equals(constraint))return conflict("GATEWAY_SETTLEMENT_BANK_REFERENCE_RESERVED","This bank reference is already reserved by retained unposted settlement evidence.");
        if("gateway_bank_reference_already_collected".equals(constraint))return conflict("GATEWAY_SETTLEMENT_BANK_REFERENCE_ALREADY_COLLECTED","This bank reference already appears in manual receipt history.");
        if("gateway_bank_independent_rejection_required".equals(constraint))return new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_SETTLEMENT_BANK_INDEPENDENT_REJECTION_REQUIRED","A different authorized person must reject mistaken bank evidence.");
        if(DatabaseConflict.isIntegrityRejection(failure))return conflict("PROTECTED_RECORD_CONFLICT","The evidence conflicts with protected records. Refresh and review the retained history.");
        return failure;
    }
    private static String constraint(Throwable failure){
        // PostgreSQL is a runtime-scoped dependency: read ONLY the structured constraint field, never SQL/message text.
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql&&"23514".equals(sql.getSQLState())
                &&"org.postgresql.util.PSQLException".equals(cause.getClass().getName()))try{
            Object server=cause.getClass().getMethod("getServerErrorMessage").invoke(cause);
            return server==null?null:(String)server.getClass().getMethod("getConstraint").invoke(server);
        }catch(ReflectiveOperationException ignored){return null;}
        return null;
    }
    private static Instant time(ResultSet row,String name)throws SQLException{var value=row.getObject(name,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static String money(BigDecimal value){return value==null?null:value.toPlainString();}
    private static ApiException reused(){return conflict("GATEWAY_SETTLEMENT_BANK_REQUEST_REUSED","This retry identity belongs to different bank-evidence terms.");}
    private static ApiException unavailable(){return conflict("GATEWAY_SETTLEMENT_BANK_EVIDENCE_UNAVAILABLE","Retained bank evidence requires review; no reconciliation or posting is implied.");}
    private static ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
    private record Replay(UUID id,UUID target,String fingerprint){}
    private record Stored(GatewaySettlementBankEvidenceModels.Evidence projection,UUID makerId){}

    private String evidenceRows(Authority authority){
        // Older migration-stage verification legitimately reads V083 evidence.
        // Do not parse a reference to V084 relations before that migration exists;
        // resolve this per invocation, never cache a schema/tenant outcome.
        boolean preparations=Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('public.fee_gateway_settlement_roots') IS NOT NULL",authority.params(),Boolean.class));
        String consumed=preparations?"""
                EXISTS(SELECT 1 FROM fee_gateway_settlement_roots consumed_root
                  WHERE consumed_root.organisation_id=evidence.organisation_id AND consumed_root.school_id=evidence.school_id
                    AND consumed_root.bank_evidence_id=evidence.id
                    AND NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_abandonments released WHERE released.root_id=consumed_root.id))
                """:"false";
        return ROWS.replace("SELECT evidence.*", "SELECT "+consumed+" settlement_consumed,evidence.*");
    }
    private static final String ROWS="""
            /* settlement-bank-evidence-history */
            SELECT evidence.*,maker.display_name recorded_by,rejection.id rejection_id,rejection.reason rejection_reason,
              rejection.rejected_at,rejection.rejected_by_user_id,rejection.confirmed_mistaken_evidence,rejector.display_name rejected_by
            FROM fee_gateway_settlement_bank_evidence evidence JOIN user_accounts maker ON maker.id=evidence.recorded_by_user_id
            LEFT JOIN fee_gateway_settlement_bank_evidence_rejections rejection ON rejection.bank_evidence_id=evidence.id
              AND rejection.organisation_id=evidence.organisation_id AND rejection.school_id=evidence.school_id
            LEFT JOIN user_accounts rejector ON rejector.id=rejection.rejected_by_user_id
            """;
}

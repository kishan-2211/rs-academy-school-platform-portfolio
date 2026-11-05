package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked service evidence only, not PostgreSQL concurrency/RLS/trigger or actual bank verification. */
class GatewaySettlementBankEvidenceServiceTest {
    private static final Instant CREDIT=Instant.parse("2026-10-24T18:29:59.999999999Z"),NOW=Instant.parse("2026-10-25T12:00:00Z");
    @AfterEach void clear(){TransactionSynchronizationManager.clear();}

    @Test void actualRecordPreservesOriginalNanosecondsDateAndOnlyAppendsBankEvidenceAndRealAudit(){
        var f=new Fixture();var result=f.run(()->f.service.record(f.user,f.request,f.command()));
        assertThat(result.status()).isEqualTo("BANK_EVIDENCE_RECORDED");assertThat(result.actualCreditedAt()).isEqualTo(CREDIT.toString());
        assertThat(result.creditedOn()).isEqualTo(LocalDate.of(2026,10,24));assertThat(result.bankReference()).isEqualTo("utr / 001");
        assertThat(result.canReject()).isFalse();assertThat(result.reviewReasons()).contains("NOT_RECONCILED","SUMMARY_CURRENCY_UNCONFIRMED","SOURCE_RECONCILIATION_PENDING");
        assertThat(f.writes).hasSize(1);assertThat(f.writes.getFirst().sql()).contains("INSERT INTO fee_gateway_settlement_bank_evidence(")
                .doesNotContain("INSERT INTO fee_payments","fee_ledger_entries","accounting_journals","UPDATE ","DELETE ");
        assertThat(f.writes.getFirst().values()).containsEntry("actualExact",CREDIT.toString()).containsEntry("actualAt",GatewayExactInstant.parameter(CREDIT))
                .containsEntry("creditedOn",LocalDate.of(2026,10,24)).containsEntry("reference","UTR/001");
        assertThat(f.reads).anySatisfy(sql->assertThat(sql).contains("payment.gateway_capture_id IS NULL","IN('BANK_TRANSFER','SCHOOL_UPI')").doesNotContain("payment.status='POSTED'"));
        verify(f.audit).recordScoped(same(f.user),eq(f.member),eq(f.school),eq("finance.gateway.settlement.bank-evidence-recorded"),eq("GATEWAY_SETTLEMENT_BANK_EVIDENCE"),eq(result.id().toString()),any(),eq("Checked school bank credit"),anyMap());
    }
    @Test void getHistoryRemainsReadableWithInactiveBankAndChangedSchoolTimezoneButCannotRecordNewEvidence(){
        var f=new Fixture();f.run(()->f.service.record(f.user,f.request,f.command()));f.bankActive=false;f.currentZone="UTC";
        var context=f.service.context(f.user,f.school,f.request);
        assertThat(context.schoolTimeZone()).isEqualTo("Asia/Kolkata");assertThat(context.canRecord()).isFalse();assertThat(context.bankAccounts()).isEmpty();
        assertThat(context.evidence()).hasSize(1);assertThat(context.evidence().getFirst().actualCreditedAt()).isEqualTo(CREDIT.toString());
        assertThat(f.reads).anySatisfy(sql->assertThat(sql).contains("ORDER BY evidence.recorded_at DESC,evidence.id DESC LIMIT 26"));
    }
    @Test void latestHistoryIsBoundedWithoutHidingWhetherMoreEvidenceExists(){
        var f=new Fixture();for(int index=0;index<26;index++)f.history.add(f.row(UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),f.user.userId()));
        var context=f.service.context(f.user,f.school,f.request);assertThat(context.evidence()).hasSize(25);assertThat(context.hasMoreEvidence()).isTrue();
        assertThatThrownBy(()->context.evidence().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void consumedEvidenceCannotOfferMistakenEvidenceRejectionToAnotherAuthorizedHuman(){
        var f=new Fixture();var row=f.row(UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),UUID.randomUUID());
        row.put("settlement_consumed",true);f.history.add(row);
        assertThat(f.service.context(f.user,f.school,f.request).evidence().getFirst().canReject()).isFalse();
        assertThat(f.writes).isEmpty();verifyNoInteractions(f.audit);
    }
    @Test void sourceOrLiveScopeFailureCannotBecomeAnEmptySuccessfulContextOrReachBankEvidence(){
        var f=new Fixture();var missing=new ApiException(HttpStatus.CONFLICT,"GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE","Synthetic incomplete chain");
        doThrow(missing).when(f.settlements).bankEvidenceSource(f.user,f.school,f.request);
        assertThatThrownBy(()->f.service.context(f.user,f.school,f.request)).isSameAs(missing);verifyNoInteractions(f.jdbc,f.accounting,f.audit);
    }
    @Test void exactReplayKeepsOriginalEvidenceAfterBankDeactivationTimezoneChangeAndDoesNotWriteAgain(){
        var f=new Fixture();var original=f.run(()->f.service.record(f.user,f.request,f.command()));
        f.bankActive=false;f.currentZone="UTC";clearInvocations(f.accounting,f.audit);int fences=f.fences;
        var replay=f.run(()->f.service.record(f.user,f.request,f.command()));
        assertThat(replay).isEqualTo(original);assertThat(f.writes).hasSize(1);assertThat(f.fences).isEqualTo(fences);verifyNoInteractions(f.accounting,f.audit);
    }
    @Test void changedCommandCannotReuseTheOriginalSameUserClientIdentity(){
        var f=new Fixture();f.run(()->f.service.record(f.user,f.request,f.command()));
        var changed=new GatewaySettlementBankEvidenceModels.Command(f.school,f.client,f.bank,"INR","101.00",CREDIT,"BANK_APP_CREDIT","utr / 001",true,"Checked school bank credit");
        f.run(()->{code(()->f.service.record(f.user,f.request,changed),"GATEWAY_SETTLEMENT_BANK_REQUEST_REUSED");return null;});assertThat(f.writes).hasSize(1);
    }
    @Test void newEvidenceRequiresActivePermittedBankMatchingFrozenTimezoneAndNonFutureActualTime(){
        var f=new Fixture();f.bankActive=false;f.run(()->{code(()->f.service.record(f.user,f.request,f.command()),"GATEWAY_SETTLEMENT_BANK_ACCOUNT_UNAVAILABLE");return null;});
        f.bankActive=true;f.currentZone="UTC";f.run(()->{code(()->f.service.record(f.user,f.request,f.command()),"GATEWAY_SETTLEMENT_BANK_TIME_ZONE_CHANGED");return null;});
        f.currentZone="Asia/Kolkata";var future=new GatewaySettlementBankEvidenceModels.Command(f.school,f.client,f.bank,"INR","100.00",NOW.plusNanos(1),"BANK_APP_CREDIT","utr / 001",true,"Checked school bank credit");
        f.run(()->{code(()->f.service.record(f.user,f.request,future),"GATEWAY_SETTLEMENT_BANK_FUTURE_CREDIT");return null;});assertThat(f.writes).isEmpty();verifyNoInteractions(f.audit);
    }
    @Test void closedHistoricalFinancialDayIsNotReopenedOrUsedAsANewPostingDate(){
        var f=new Fixture();var old=new GatewaySettlementBankEvidenceModels.Command(f.school,f.client,f.bank,"INR","100.00",Instant.parse("2025-03-31T18:29:59.999999999Z"),"BANK_STATEMENT_CREDIT","utr / 001",true,"Historical bank entry checked");
        var result=f.run(()->f.service.record(f.user,f.request,old));assertThat(result.creditedOn()).isEqualTo(LocalDate.of(2025,3,31));
        assertThat(f.reads).noneMatch(sql->sql.contains("fee_day_closes")||sql.contains("accounting_periods"));
        assertThat(f.writes.getFirst().values()).doesNotContainKey("postingOn");
    }
    @Test void allManualHistoryAndActiveUnpostedReservationsBlockDuplicateCreditAcrossPaymentKinds(){
        var f=new Fixture();f.manualCollected=true;f.run(()->{code(()->f.service.record(f.user,f.request,f.command()),"GATEWAY_SETTLEMENT_BANK_REFERENCE_ALREADY_COLLECTED");return null;});
        f.manualCollected=false;f.forceReserved=true;f.run(()->{code(()->f.service.record(f.user,f.request,f.command()),"GATEWAY_SETTLEMENT_BANK_REFERENCE_RESERVED");return null;});
        assertThat(f.writes).isEmpty();verifyNoInteractions(f.audit);
    }
    @Test void liveAuthorityIsRecheckedAfterTheFinanceFenceNotTrustedFromEarlierClaims(){
        var f=new Fixture();f.afterFence=()->f.recordAllowed=false;
        f.run(()->{code(()->f.service.record(f.user,f.request,f.command()),"GATEWAY_SETTLEMENT_SCOPE_DENIED");return null;});
        assertThat(f.fences).isEqualTo(1);assertThat(f.writes).isEmpty();verifyNoInteractions(f.accounting,f.audit);
    }
    @Test void writableReadCommittedTransactionIsRequiredBeforeAnyReservationWrite(){
        var f=new Fixture();code(()->f.service.record(f.user,f.request,f.command()),"GATEWAY_SETTLEMENT_SCOPE_DENIED");
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        code(()->f.service.record(f.user,f.request,f.command()),"GATEWAY_SETTLEMENT_SCOPE_DENIED");TransactionSynchronizationManager.clear();
        f.isolation="repeatable read";f.run(()->{code(()->f.service.record(f.user,f.request,f.command()),"GATEWAY_SETTLEMENT_BANK_RETRY_REQUIRED");return null;});
        assertThat(f.fences).isZero();assertThat(f.writes).isEmpty();
    }
    @Test void aDistinctAuthorizedHumanCanRejectOnlyUnpostedEvidenceWithAppendOnlyDecision(){
        var f=new Fixture();UUID evidence=UUID.randomUUID();f.history.add(f.row(evidence,UUID.randomUUID(),"a".repeat(64),UUID.randomUUID()));
        var result=f.run(()->f.service.reject(f.user,f.request,evidence,f.rejection()));
        assertThat(result.status()).isEqualTo("REJECTED");assertThat(result.rejection()).isNotNull();assertThat(result.canReject()).isFalse();
        assertThat(f.history).hasSize(1);assertThat(f.writes).hasSize(1);assertThat(f.writes.getFirst().sql()).contains("INSERT INTO fee_gateway_settlement_bank_evidence_rejections",
                "confirmed_mistaken_evidence").doesNotContain("UPDATE ","DELETE ","fee_payments","fee_ledger_entries","accounting_journals");
        verifyNoInteractions(f.accounting);verify(f.audit).recordScoped(same(f.user),eq(f.member),eq(f.school),eq("finance.gateway.settlement.bank-evidence-rejected"),anyString(),eq(evidence.toString()),any(),anyString(),anyMap());
    }
    @Test void anotherMembershipOfTheMakerCannotSatisfyIndependentRejection(){
        var f=new Fixture();UUID evidence=UUID.randomUUID();f.history.add(f.row(evidence,UUID.randomUUID(),"a".repeat(64),f.user.userId()));f.member=UUID.randomUUID();
        f.run(()->{code(()->f.service.reject(f.user,f.request,evidence,f.rejection()),"GATEWAY_SETTLEMENT_BANK_INDEPENDENT_REJECTION_REQUIRED");return null;});
        assertThat(f.writes).isEmpty();verifyNoInteractions(f.audit);
    }
    @Test void rejectionRetriesKeepTheSameDecisionAndAlteredRetryTermsConflict(){
        var f=new Fixture();UUID evidence=UUID.randomUUID();f.history.add(f.row(evidence,UUID.randomUUID(),"a".repeat(64),UUID.randomUUID()));
        var first=f.run(()->f.service.reject(f.user,f.request,evidence,f.rejection()));int fences=f.fences;f.bankActive=false;f.currentZone="UTC";
        assertThat(f.run(()->f.service.reject(f.user,f.request,evidence,f.rejection()))).isEqualTo(first);assertThat(f.fences).isEqualTo(fences);assertThat(f.writes).hasSize(1);
        var changed=new GatewaySettlementBankEvidenceModels.RejectionCommand(f.school,f.rejectClient,"Different reviewed mistake",true);
        f.run(()->{code(()->f.service.reject(f.user,f.request,evidence,changed),"GATEWAY_SETTLEMENT_BANK_REQUEST_REUSED");return null;});
        var differentClient=new GatewaySettlementBankEvidenceModels.RejectionCommand(f.school,UUID.randomUUID(),"Mistaken reference checked",true);
        f.run(()->{code(()->f.service.reject(f.user,f.request,evidence,differentClient),"GATEWAY_SETTLEMENT_BANK_EVIDENCE_ALREADY_REJECTED");return null;});
    }
    @Test void originalRecordRetryAfterIndependentRejectionReturnsRejectedHistoryWithoutReactivatingIt(){
        var f=new Fixture();var original=f.run(()->f.service.record(f.user,f.request,f.command()));var row=f.history.getFirst();f.markRejected(row,UUID.randomUUID());
        var replay=f.run(()->f.service.record(f.user,f.request,f.command()));assertThat(replay.id()).isEqualTo(original.id());assertThat(replay.status()).isEqualTo("REJECTED");
        assertThat(replay.rejection()).isNotNull();assertThat(f.writes).hasSize(1);
    }
    @Test void logicalReservationIsReleasedOnlyByRetainedRejectionAndOriginalEvidenceIsNotDeleted(){
        var f=new Fixture();f.run(()->f.service.record(f.user,f.request,f.command()));f.markRejected(f.history.getFirst(),UUID.randomUUID());
        var fresh=new GatewaySettlementBankEvidenceModels.Command(f.school,UUID.randomUUID(),f.bank,"INR","100.00",CREDIT,"BANK_APP_CREDIT","utr / 001",true,"New checked bank attestation");
        var result=f.run(()->f.service.record(f.user,f.request,fresh));assertThat(result.status()).isEqualTo("BANK_EVIDENCE_RECORDED");assertThat(f.history).hasSize(2);
        assertThat(f.history.getFirst().get("rejection_id")).isNotNull();
    }
    @Test void onlyKnownStructuredConstraintIdentitiesReceiveScopedTranslationAndOutagesRemainReal(){
        var f=new Fixture();for(var pair:List.of(new String[]{"gateway_bank_reference_reserved","GATEWAY_SETTLEMENT_BANK_REFERENCE_RESERVED"},
                new String[]{"gateway_bank_reference_already_collected","GATEWAY_SETTLEMENT_BANK_REFERENCE_ALREADY_COLLECTED"},
                new String[]{"gateway_bank_independent_rejection_required","GATEWAY_SETTLEMENT_BANK_INDEPENDENT_REJECTION_REQUIRED"},
                new String[]{"unknown_guard","PROTECTED_RECORD_CONFLICT"})){
            f.writeFailure=new DataIntegrityViolationException("synthetic",new PSQLException(new ServerErrorMessage("SERROR\0C23514\0n"+pair[0]+"\0Msynthetic_private_sql\0\0")));
            f.run(()->{code(()->f.service.record(f.user,f.request,f.command()),pair[1]);return null;});
        }
        var outage=new DataAccessResourceFailureException("Synthetic database outage");f.writeFailure=outage;
        f.run(()->{assertThatThrownBy(()->f.service.record(f.user,f.request,f.command())).isSameAs(outage);return null;});verifyNoInteractions(f.audit);
    }
    @Test void corruptExactTimeDateOrNonPaiseAmountFailsClosedInsteadOfNormalizingRetainedEvidence(){
        for(String field:List.of("actual_credited_at_exact","credited_on","credited_amount")){
            var f=new Fixture();var row=f.row(UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),UUID.randomUUID());f.history.add(row);
            row.put(field,switch(field){case "actual_credited_at_exact"->"2026-10-24T18:29:59.999998999Z";case "credited_on"->LocalDate.of(2026,10,25);default->new BigDecimal("100.001");});
            code(()->f.service.context(f.user,f.school,f.request),"GATEWAY_SETTLEMENT_BANK_EVIDENCE_UNAVAILABLE");
        }
    }
    private static void code(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->{
        assertThat(error.code()).isEqualTo(code);assertThat(error.getMessage()).doesNotContain("synthetic_private_sql");});}
    private record Write(String sql,Map<String,Object> values){}
    private static final class Fixture{
        final UUID org=UUID.randomUUID(),school=UUID.randomUUID(),request=UUID.randomUUID(),summary=UUID.randomUUID(),bank=UUID.randomUUID(),client=UUID.randomUUID(),rejectClient=UUID.randomUUID();
        UUID member=UUID.randomUUID();final AuthenticatedUser user=new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic@example.test",null,"Synthetic actor",false,org,Set.of(member),Set.of(),List.of());
        final GatewaySettlementService settlements=mock(GatewaySettlementService.class);final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
        final AccountingModuleBridge accounting=mock(AccountingModuleBridge.class);final SecurityAuditService audit=mock(SecurityAuditService.class);
        final GatewaySettlementBankEvidenceService service=new GatewaySettlementBankEvidenceService(settlements,jdbc,accounting,audit);
        final List<Map<String,Object>> history=new ArrayList<>(),decisions=new ArrayList<>();final List<Write>writes=new ArrayList<>();final List<String>reads=new ArrayList<>();
        final GatewaySettlementBankEvidenceService.Source source=new GatewaySettlementBankEvidenceService.Source(org,school,request,"Asia/Kolkata","synthetic_statement",summary,0,NOW,
                new GatewaySettlementProvider.SettlementSummary("synthetic_statement","SUCCESS","UTR/001",null,null,null,null,null,null,null,null,null,null,null,null,null,new BigDecimal("100.00000000"),null,null));
        boolean bankActive=true,recordAllowed=true,rejectAllowed=true,manualCollected,forceReserved;String currentZone="Asia/Kolkata",isolation="read committed";
        int fences;Runnable afterFence=()->{};DataAccessException writeFailure;
        @SuppressWarnings({"rawtypes","unchecked"})Fixture(){
            when(settlements.bankEvidenceSource(user,school,request)).thenReturn(source);
            when(settlements.bankEvidenceAuthority(eq(user),eq(school),nullable(String.class))).thenAnswer(call->{
                String required=call.getArgument(2);if("fees.close.propose".equals(required)&&!recordAllowed||"fees.close.approve".equals(required)&&!rejectAllowed)
                    throw new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_SETTLEMENT_SCOPE_DENIED","Synthetic live revocation");return authority();});
            when(accounting.settlementBankEvidenceAccounts(user,school)).thenAnswer(call->bankActive?List.of(new AccountingModuleBridge.SettlementBankAccount(bank,"SYNTHETIC_BANK","Synthetic bank","INR")):List.of());
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(String.class))).thenReturn(isolation);
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(String.class))).thenAnswer(call->isolation);
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Integer.class))).thenAnswer(call->{fences++;assertThat(((MapSqlParameterSource)call.getArgument(1)).getValue("lockKey")).isEqualTo(school+":finance");afterFence.run();return 1;});
            when(jdbc.queryForObject(eq("SELECT clock_timestamp()"),any(MapSqlParameterSource.class),any(RowMapper.class))).thenReturn(NOW);
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->{String sql=call.getArgument(0);reads.add(sql);
                if(sql.contains("to_regclass"))return true;
                return sql.contains("manual-reference")?manualCollected:forceReserved||history.stream().anyMatch(row->row.get("rejection_id")==null&&row.get("normalized_bank_reference").equals(((MapSqlParameterSource)call.getArgument(1)).getValue("reference")));});
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(RowMapper.class))).thenAnswer(call->{
                String sql=call.getArgument(0);reads.add(sql);var p=(MapSqlParameterSource)call.getArgument(1);List<Map<String,Object>> selected;
                if(sql.contains("SELECT id,request_id,request_fingerprint"))selected=history.stream().filter(row->row.get("recorded_by_user_id").equals(user.userId())&&row.get("client_request_id").equals(p.getValue("clientId"))).toList();
                else if(sql.contains("SELECT id,bank_evidence_id,request_fingerprint"))selected=decisions.stream().filter(row->row.get("client_request_id").equals(p.getValue("clientId"))).toList();
                else selected=p.hasValue("id")?history.stream().filter(row->row.get("id").equals(p.getValue("id"))).toList():history;
                var result=new ArrayList<>();RowMapper mapper=call.getArgument(2);for(int index=0;index<selected.size();index++)result.add(mapper.mapRow(rs(selected.get(index)),index));return result;
            });
            when(jdbc.update(anyString(),any(MapSqlParameterSource.class))).thenAnswer(call->{if(writeFailure!=null)throw writeFailure;
                String sql=call.getArgument(0);var p=(MapSqlParameterSource)call.getArgument(1);writes.add(new Write(sql,new HashMap<>(p.getValues())));
                if(sql.contains("bank_evidence_rejections")){
                    var row=history.stream().filter(value->value.get("id").equals(p.getValue("evidenceId"))).findFirst().orElseThrow();markRejected(row,user.userId());
                    row.put("rejection_id",p.getValue("id"));row.put("rejection_reason",p.getValue("reason"));
                    decisions.add(Map.of("id",p.getValue("id"),"bank_evidence_id",p.getValue("evidenceId"),"request_fingerprint",p.getValue("fingerprint"),"client_request_id",p.getValue("clientId")));
                }else{
                    var row=row((UUID)p.getValue("id"),(UUID)p.getValue("clientId"),(String)p.getValue("fingerprint"),user.userId());
                    row.put("credited_amount",p.getValue("amount"));row.put("actual_credited_at",p.getValue("actualAt"));row.put("actual_credited_at_exact",p.getValue("actualExact"));
                    row.put("credited_on",p.getValue("creditedOn"));row.put("reason",p.getValue("reason"));row.put("verification_kind",p.getValue("kind"));
                    row.put("bank_credit_reference",p.getValue("bankReference"));row.put("normalized_bank_reference",p.getValue("reference"));history.add(row);
                }return 1;
            });
        }
        GatewaySettlementBankEvidenceService.Authority authority(){return new GatewaySettlementBankEvidenceService.Authority(org,school,user.userId(),member,currentZone,recordAllowed,rejectAllowed);}
        GatewaySettlementBankEvidenceModels.Command command(){return new GatewaySettlementBankEvidenceModels.Command(school,client,bank,"INR","100.00",CREDIT,"BANK_APP_CREDIT","utr / 001",true,"Checked school bank credit");}
        GatewaySettlementBankEvidenceModels.RejectionCommand rejection(){return new GatewaySettlementBankEvidenceModels.RejectionCommand(school,rejectClient,"Mistaken reference checked",true);}
        Map<String,Object> row(UUID id,UUID clientId,String fingerprint,UUID maker){
            var row=new HashMap<String,Object>();row.put("id",id);row.put("organisation_id",org);row.put("school_id",school);row.put("request_id",request);
            row.put("source_summary_read_id",summary);row.put("source_summary_row_index",0);row.put("settlement_reference","synthetic_statement");row.put("school_time_zone","Asia/Kolkata");
            row.put("bank_account_id",bank);row.put("currency_code","INR");row.put("credited_amount",new BigDecimal("100.00"));row.put("actual_credited_at",GatewayExactInstant.parameter(CREDIT));
            row.put("actual_credited_at_exact",CREDIT.toString());row.put("credited_on",CREDIT.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate());row.put("verification_kind","BANK_APP_CREDIT");
            row.put("bank_credit_reference","utr / 001");row.put("normalized_bank_reference","UTR/001");row.put("confirmed_received",true);row.put("reason","Checked school bank credit");
            row.put("client_request_id",clientId);row.put("request_fingerprint",fingerprint);row.put("recorded_by_user_id",maker);row.put("recorded_by","Synthetic maker");row.put("recorded_at",NOW.atOffset(ZoneOffset.UTC));return row;
        }
        void markRejected(Map<String,Object> row,UUID userId){row.put("rejection_id",UUID.randomUUID());row.put("rejection_reason","Mistaken reference checked");
            row.put("rejected_at",NOW.atOffset(ZoneOffset.UTC));row.put("rejected_by_user_id",userId);row.put("rejected_by","Synthetic independent reviewer");row.put("confirmed_mistaken_evidence",true);}
        <T>T run(Supplier<T> action){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            try{return action.get();}finally{TransactionSynchronizationManager.clear();}}
        private static ResultSet rs(Map<String,Object> row)throws Exception{
            var result=mock(ResultSet.class);when(result.getObject(anyString(),any(Class.class))).thenAnswer(call->row.get(call.getArgument(0,String.class)));
            when(result.getString(anyString())).thenAnswer(call->row.get(call.getArgument(0,String.class)));
            when(result.getInt(anyString())).thenAnswer(call->((Number)row.getOrDefault(call.getArgument(0,String.class),0)).intValue());
            when(result.getBoolean(anyString())).thenAnswer(call->Boolean.TRUE.equals(row.get(call.getArgument(0,String.class))));
            when(result.getBigDecimal(anyString())).thenAnswer(call->row.get(call.getArgument(0,String.class)));return result;
        }
    }
}

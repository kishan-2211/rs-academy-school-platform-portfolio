package com.educore.api.accounting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Guard/query/complete-result units only; real PostgreSQL and race evidence is separate. */
class GatewayOriginalPaymentJournalTest {
    @AfterEach void clearOnlySyntheticMarkers(){TransactionSynchronizationManager.clear();assertThat(TenantRequestContext.current()).isEmpty();}

    @Test void missingHumanOrForeignContextCannotReadAnOriginalJournal(){
        var f=new Fixture();writable();assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
        try(var ignored=TenantRequestContext.open(UUID.randomUUID(),f.org)){assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);}
        try(var ignored=TenantRequestContext.open(null,UUID.randomUUID())){assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);}
        verifyNoInteractions(f.jdbc,f.audit);
    }

    @Test void absentOrReadOnlyTransactionDeniesBeforeDatabaseAccess(){
        var f=new Fixture();try(var ignored=TenantRequestContext.open(null,f.org)){
            assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
            writable();TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
        }verifyNoInteractions(f.jdbc,f.audit);
    }

    @Test void everyExplicitSourceIdentityIsRequiredBeforeDatabaseAccess(){
        var f=new Fixture();try(var ignored=background(f)){
            for(int missing=0;missing<5;missing++){
                UUID[] ids={f.org,f.school,f.payment,f.batch,f.journal};ids[missing]=null;
                assertThatThrownBy(()->f.service.gatewayOriginalPaymentJournalEvidence(ids[0],ids[1],ids[2],ids[3],ids[4])).isInstanceOf(IllegalStateException.class);
            }
        }verifyNoInteractions(f.jdbc,f.audit);
    }

    @Test void actualDatabaseScopeIsolationAndWritableStateMustPassBeforeAndAfterTheFence() throws Exception{
        for(int failedCheck:new int[]{1,2}){
            var f=new Fixture();f.failedContextCheck=failedCheck;f.install();
            try(var ignored=background(f)){assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);}
            assertThat(f.events).isEqualTo(failedCheck==1?List.of("context"):List.of("context","fence","context"));
            assertThat(f.contextSql).contains("current_app_organisation_id()=:organisationId","current_app_user_id() IS NULL",
                    "transaction_isolation')='read committed'","transaction_read_only')='off'","school.status='ACTIVE'","organisation.status='ACTIVE'");
            f.noMutation();
        }
    }

    @Test void closedHistoricalBooksReturnCompleteSeparateGrossSidesWithoutNewPostingPermission() throws Exception{
        var f=new Fixture();f.install();try(var ignored=background(f)){
            var evidence=f.read();assertThat(evidence.id()).isEqualTo(f.journal);assertThat(evidence.sourceId()).isEqualTo(f.batch);
            assertThat(evidence.financialYearStatus()).isEqualTo("CLOSED");assertThat(evidence.accountingPeriodStatus()).isEqualTo("CLOSED");
            assertThat(evidence.lines()).hasSize(2);assertThat(evidence.lines().getFirst().debitAmount()).isEqualByComparingTo("200");
            assertThat(evidence.lines().getLast().creditAmount()).isEqualByComparingTo("200");assertThat(evidence.toString()).isEqualTo("FeeJournalEvidence[redacted]");
        }
        assertThat(f.events).containsExactly("context","fence","context","source","original","complete-evidence");
        assertThat(f.lockParameters).containsEntry("lockKey","accounting:school:"+f.school);
        assertThat(f.evidenceSql).contains("sum(l.debit_amount) debit_amount,sum(l.credit_amount) credit_amount","GROUP BY l.accounting_journal_id,l.account_id,l.vendor_id,l.learner_fee_account_id");
        assertThat(f.evidenceSql).doesNotContain("status='OPEN'","LIMIT");f.noMutation();
    }

    @Test void sourceQueryBindsOnlyTheExactOnlineReceiptLedgerBatchAndCommonJournal() throws Exception{
        var f=new Fixture();f.install();try(var ignored=background(f)){f.read();}
        assertThat(f.sourceParameters).containsEntry("organisationId",f.org).containsEntry("schoolId",f.school).containsEntry("paymentId",f.payment).containsEntry("batchId",f.batch).containsEntry("journalId",f.journal);
        assertThat(f.sourceSql).contains("payment.status='POSTED'","payment.payment_method='ONLINE_PROVIDER'","payment.gateway_capture_id IS NOT NULL",
                "ledger.source_id=payment.id","ledger.amount=payment.amount","ledger.learner_fee_account_id=payment.learner_fee_account_id","account.currency_code='INR'",
                "item.status='ACTIVE'","batch.event_type='PAYMENT'","batch.disposition='JOURNAL'","journal.source_module='FEES'",
                "journal.source_id=batch.id","journal.accounting_period_id=batch.accounting_period_id","ledger.effective_on=batch.business_date");
        assertThat(f.sourceParameters).doesNotContainKeys("userId","actorMembershipId");f.noMutation();
    }

    @Test void missingForeignManualOrUnassociatedPaymentIsDeniedWithoutLoadingJournalLines() throws Exception{
        var f=new Fixture();f.sourceMatches=false;f.install();try(var ignored=background(f)){assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);}
        assertThat(f.events).containsExactly("context","fence","context","source");f.noMutation();
    }

    @Test void pendingActualAndNonpostedOriginalJournalStatesAreDenied() throws Exception{
        for(String failure:List.of("pending","reversal","draft","reversed","missing")){
            var f=new Fixture();
            if(failure.equals("pending"))f.original.put("has_pending_reversal",true);
            else if(failure.equals("reversal"))f.original.put("has_reversal",true);
            else if(failure.equals("missing"))f.originalMissing=true;
            else f.original.put("status",failure.toUpperCase(Locale.ROOT));
            f.install();try(var ignored=background(f)){assertThatThrownBy(f::read).isInstanceOf(RuntimeException.class);}
            assertThat(f.originalSql).contains("FOR SHARE OF original","pending.status='PENDING'","reversal.reversal_of_journal_id=original.id");
            assertThat(f.events).doesNotContain("complete-evidence");f.noMutation();
        }
    }

    @Test void missingOrChangedCompleteEvidenceCannotBecomeSuccessfulSourceProof() throws Exception{
        for(String failure:List.of("missing","batch","currency","status","reversal")){
            var f=new Fixture();
            if(failure.equals("missing"))f.rows.clear();
            else for(var row:f.rows){
                if(failure.equals("batch"))row.put("source_id",UUID.randomUUID());
                if(failure.equals("currency"))row.put("currency_code","USD");
                if(failure.equals("status"))row.put("status","SUBMITTED");
                if(failure.equals("reversal")){row.put("reversal_id",UUID.randomUUID());row.put("reversal_status","SUBMITTED");}
            }
            f.install();try(var ignored=background(f)){assertThatThrownBy(f::read).isInstanceOf(RuntimeException.class);}f.noMutation();
        }
    }

    @Test void emptyPartialAndExcessiveLineSetsNeverReturnTruncatedEvidence() throws Exception{
        for(int count:new int[]{0,1,501}){
            var f=new Fixture();f.rows.clear();for(int i=0;i<count;i++)f.rows.add(f.line(UUID.randomUUID(),"1","0"));
            f.install();try(var ignored=background(f)){assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);}f.noMutation();
        }
    }

    @Test void sharedReaderOverflowStillRejectsWholeEvidenceInsteadOfAnApparentZero() throws Exception{
        var f=new Fixture();f.rows.forEach(row->row.put("normalized_group_count",20001L));f.install();
        try(var ignored=background(f)){assertThatThrownBy(f::read).isInstanceOf(com.educore.api.common.api.ApiException.class);}f.noMutation();
    }

    @Test void backgroundEntryRequiresAnExistingWritableTransactionAndDoesNotChangeHumanReaderContract() throws Exception{
        var method=AccountingService.class.getDeclaredMethod("gatewayOriginalPaymentJournalEvidence",UUID.class,UUID.class,UUID.class,UUID.class,UUID.class);
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isFalse();
        assertThat(AccountingService.class.getDeclaredMethod("feeJournalEvidence",com.educore.api.identity.AuthenticatedUser.class,UUID.class,List.class).getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    private static void writable(){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);}
    private static TenantRequestContext.Scope background(Fixture f){writable();return TenantRequestContext.open(null,f.org);}

    private static final class Fixture{
        final UUID org=UUID.randomUUID(),school=UUID.randomUUID(),payment=UUID.randomUUID(),batch=UUID.randomUUID(),journal=UUID.randomUUID(),year=UUID.randomUUID(),period=UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);final SecurityAuditService audit=mock(SecurityAuditService.class);
        final AccountingService service=new AccountingService(jdbc,audit,Clock.systemUTC());
        final List<String> events=new ArrayList<>();final List<Map<String,Object>> rows=new ArrayList<>();final Map<String,Object> original=new HashMap<>();
        Map<String,Object> sourceParameters,lockParameters;String sourceSql,contextSql,originalSql,evidenceSql;int contextChecks,failedContextCheck;boolean sourceMatches=true,originalMissing;
        Fixture(){original.put("status","POSTED");original.put("accounting_basis","CASH");original.put("currency_code","INR");rows.add(line(UUID.randomUUID(),"200","0"));rows.add(line(UUID.randomUUID(),"0","200"));}
        AccountingModuleBridge.FeeJournalEvidence read(){return service.gatewayOriginalPaymentJournalEvidence(org,school,payment,batch,journal);}
        Map<String,Object> line(UUID account,String debit,String credit){
            var row=new HashMap<String,Object>();row.put("id",journal);row.put("source_id",batch);row.put("financial_year_id",year);row.put("accounting_period_id",period);
            row.put("posting_date",LocalDate.of(2026,10,19));row.put("accounting_basis","CASH");row.put("currency_code","INR");row.put("status","POSTED");
            row.put("financial_year_status","CLOSED");row.put("accounting_period_status","CLOSED");row.put("normalized_group_count",2L);row.put("line_journal_id",journal);
            row.put("account_id",account);row.put("debit_amount",new BigDecimal(debit));row.put("credit_amount",new BigDecimal(credit));row.put("account_type","ASSET");row.put("account_category","OTHER");row.put("account_status","INACTIVE");row.put("allow_direct_posting",false);return row;
        }
        @SuppressWarnings({"rawtypes","unchecked"}) void install() throws Exception{
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->{String sql=call.getArgument(0);if(sql.contains("gateway-original-journal-context")){events.add("context");contextSql=sql;return ++contextChecks!=failedContextCheck;}if(sql.contains("gateway-original-payment-source")){events.add("source");sourceSql=sql;sourceParameters=new LinkedHashMap<>(call.<MapSqlParameterSource>getArgument(1).getValues());return sourceMatches;}throw new AssertionError("Unexpected scalar query");});
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Integer.class))).thenAnswer(call->{events.add("fence");lockParameters=new LinkedHashMap<>(call.<MapSqlParameterSource>getArgument(1).getValues());return 1;});
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(RowMapper.class))).thenAnswer(call->{events.add("original");originalSql=call.getArgument(0);if(originalMissing)return List.of();return List.of(((RowMapper)call.getArgument(2)).mapRow(result(original),0));});
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(ResultSetExtractor.class))).thenAnswer(call->{events.add("complete-evidence");evidenceSql=call.getArgument(0);int[] index={-1};var result=mock(ResultSet.class);when(result.next()).thenAnswer(next->++index[0]<rows.size());when(result.getObject(anyString(),any(Class.class))).thenAnswer(read->rows.get(index[0]).get(read.<String>getArgument(0)));when(result.getString(anyString())).thenAnswer(read->(String)rows.get(index[0]).get(read.<String>getArgument(0)));when(result.getLong(anyString())).thenAnswer(read->((Number)rows.get(index[0]).get(read.<String>getArgument(0))).longValue());when(result.getBigDecimal(anyString())).thenAnswer(read->(BigDecimal)rows.get(index[0]).get(read.<String>getArgument(0)));when(result.getBoolean(anyString())).thenAnswer(read->Boolean.TRUE.equals(rows.get(index[0]).get(read.<String>getArgument(0))));return ((ResultSetExtractor)call.getArgument(2)).extractData(result);});
        }
        private static ResultSet result(Map<String,Object> row) throws Exception{var result=mock(ResultSet.class);when(result.getString(anyString())).thenAnswer(call->(String)row.get(call.<String>getArgument(0)));when(result.getObject(anyString())).thenAnswer(call->row.get(call.<String>getArgument(0)));when(result.getBoolean(anyString())).thenAnswer(call->Boolean.TRUE.equals(row.get(call.<String>getArgument(0))));return result;}
        void noMutation(){verify(jdbc,never()).update(anyString(),any(SqlParameterSource.class));verifyNoInteractions(audit);}
    }
}

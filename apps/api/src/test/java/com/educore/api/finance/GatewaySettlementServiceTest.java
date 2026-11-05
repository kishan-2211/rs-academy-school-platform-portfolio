package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Focused service mocks only. Real RLS, trigger, concurrency and queue atomicity require PostgreSQL gates. */
class GatewaySettlementServiceTest {
    private static final Instant NOW=Instant.parse("2026-10-22T12:00:00Z"),FROM=Instant.parse("2026-10-01T00:00:00Z"),UNTIL=Instant.parse("2026-10-20T00:00:00Z");
    @AfterEach void clearSyntheticThreadMarkers(){TransactionSynchronizationManager.clear();assertThat(TenantRequestContext.current()).isEmpty();}
    @Test void noAmbientHumanOrMismatchingTenantCanReadOrCreateARequest(){
        var f=new Fixture();assertCode(()->f.service.queue(f.user,f.school,"ALL","ALL",null,0,25),"GATEWAY_SETTLEMENT_SCOPE_DENIED");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try(var ignored=TenantRequestContext.open(null,f.org)){assertCode(()->f.service.submit(f.user,f.command()),"GATEWAY_SETTLEMENT_SCOPE_DENIED");}
        try(var ignored=TenantRequestContext.open(f.user.userId(),UUID.randomUUID())){assertCode(()->f.service.queue(f.user,f.school,"ALL","ALL",null,0,25),"GATEWAY_SETTLEMENT_SCOPE_DENIED");}
        verifyNoInteractions(f.jdbc,f.registry,f.audit);
    }
    @Test void readOnlyTransactionCannotSubmitAndRevokedDatabaseAuthorityIsDenied(){
        var f=new Fixture();f.run(true,()->{assertCode(()->f.service.submit(f.user,f.command()),"GATEWAY_SETTLEMENT_SCOPE_DENIED");return null;});
        f.dbContext=false;f.run(false,()->{assertCode(()->f.service.queue(f.user,f.school,"ALL","ALL",null,0,25),"GATEWAY_SETTLEMENT_SCOPE_DENIED");return null;});
        f.dbContext=true;f.authorized=false;f.run(false,()->{assertCode(()->f.service.submit(f.user,f.command()),"GATEWAY_SETTLEMENT_SCOPE_DENIED");return null;});
        verify(f.jdbc,never()).update(anyString(),any(MapSqlParameterSource.class));verifyNoInteractions(f.audit);
    }
    @Test void readCapabilityAloneCannotStartSync(){var f=new Fixture();f.canRequest=false;f.run(false,()->{assertCode(()->f.service.submit(f.user,f.command()),"GATEWAY_SETTLEMENT_SCOPE_DENIED");return null;});verifyNoInteractions(f.registry,f.audit);}
    @Test void exactReplayUsesFrozenTimezoneAndNeverEnqueuesAuditsOrLooksUpProvider(){
        var f=new Fixture();f.replayed=f.replay(f.command());f.zone="UTC";
        var result=f.run(false,()->f.service.submit(f.user,f.command()));assertThat(result.id()).isEqualTo(f.request);
        verify(f.jdbc,never()).update(anyString(),any(MapSqlParameterSource.class));verifyNoInteractions(f.registry,f.audit);
    }
    @Test void changedTermsCannotReuseARequestIdentity(){
        var f=new Fixture();f.replayed=f.replay(f.command());var changed=new GatewaySettlementModels.Submit(f.school,f.client,"Asia/Kolkata","SUMMARY",FROM.plusNanos(1),UNTIL,null,null);
        f.run(false,()->{assertCode(()->f.service.submit(f.user,changed),"GATEWAY_SETTLEMENT_REQUEST_REUSED");return null;});verifyNoInteractions(f.registry,f.audit);
    }
    @Test void staleSchoolTimezoneIsRejectedOnlyForANewRequest(){var f=new Fixture();f.zone="UTC";f.run(false,()->{assertCode(()->f.service.submit(f.user,f.command()),"GATEWAY_SETTLEMENT_TIME_ZONE_CHANGED");return null;});verifyNoInteractions(f.registry,f.audit);}
    @Test void missingAmbiguousDisabledAndMismatchedRoutesCannotTriggerSyncOrCredentials(){
        var f=new Fixture();when(f.registry.configuredRoutes()).thenReturn(List.of());f.run(false,()->{assertCode(()->f.service.submit(f.user,f.command()),"GATEWAY_SETTLEMENT_SYNC_UNAVAILABLE");return null;});
        when(f.registry.configuredRoutes()).thenReturn(List.of(f.route,f.route));f.run(false,()->{assertCode(()->f.service.submit(f.user,f.command()),"GATEWAY_SETTLEMENT_SYNC_UNAVAILABLE");return null;});
        when(f.registry.configuredRoutes()).thenReturn(List.of(f.route));f.activeProvider=false;f.run(false,()->{assertCode(()->f.service.submit(f.user,f.command()),"GATEWAY_SETTLEMENT_SYNC_UNAVAILABLE");return null;});
        verify(f.registry,never()).provider(any());verify(f.jdbc,never()).update(anyString(),any(MapSqlParameterSource.class));verifyNoInteractions(f.audit);
    }
    @Test void newRequestUsesActualDatabaseClockRealAuditAndOnlyRequestInsert(){
        var f=new Fixture();var result=f.run(false,()->f.service.submit(f.user,f.command()));
        assertThat(result.schoolId()).isEqualTo(f.school);assertThat(result.kind()).isEqualTo("SUMMARY");
        assertThat(f.writes).hasSize(1);assertThat(f.writes.getFirst().sql()).contains("INSERT INTO fee_gateway_settlement_requests").doesNotContain("fee_ledger_entries","accounting_journals");
        assertThat(f.writes.getFirst().params()).containsEntry("zone","Asia/Kolkata").containsEntry("from",FROM.toString()).containsEntry("until",UNTIL.toString());
        verify(f.audit).recordScoped(same(f.user),eq(f.member),eq(f.school),eq("finance.gateway.settlement.requested"),eq("GATEWAY_SETTLEMENT_REQUEST"),anyString(),any(),anyString(),anyMap());
        verify(f.registry,never()).provider(any());
        var future=new GatewaySettlementModels.Submit(f.school,UUID.randomUUID(),"Asia/Kolkata","SUMMARY",NOW.minusSeconds(1),NOW.plusSeconds(1),null,null);
        f.run(false,()->{assertCode(()->f.service.submit(f.user,future),"GATEWAY_SETTLEMENT_FUTURE_INTERVAL");return null;});assertThat(f.writes).hasSize(1);
    }
    @Test void onlyRecognizedGuardConflictsBecome409WhileOutagesAndAuthorityErrorsRemainReal(){
        var f=new Fixture();for(String prefix:new String[]{"settlement request active overlap","ERROR: settlement request rate limit"}){
            f.writeFailure=new DataIntegrityViolationException("synthetic",new SQLException(prefix,"P0001"));
            f.run(false,()->{assertCode(()->f.service.submit(f.user,f.command()),"GATEWAY_SETTLEMENT_REQUEST_CONFLICT");return null;});
        }
        for(String state:new String[]{"08006","P0001"}){f.writeFailure=new DataAccessResourceFailureException("synthetic",new SQLException("unrelated database fault",state));var expected=f.writeFailure;
            f.run(false,()->{assertThatThrownBy(()->f.service.submit(f.user,f.command())).isSameAs(expected);return null;});}
        verifyNoInteractions(f.audit);
    }
    @Test void eventsRequireASelectedOwnSchoolSummaryAndExactFrozenProviderBinding(){
        var f=new Fixture();var command=new GatewaySettlementModels.Submit(f.school,f.client,"Asia/Kolkata","EVENTS",null,null,f.read,"synthetic_statement");
        f.run(false,()->{assertCode(()->f.service.submit(f.user,command),"GATEWAY_SETTLEMENT_NOT_FOUND");return null;});
        f.selected.add(f.evidence());var result=f.run(false,()->f.service.submit(f.user,command));assertThat(result.kind()).isEqualTo("EVENTS");
        f.selected.getFirst().put("merchant_account_reference","synthetic_foreign_merchant");
        f.run(false,()->{assertCode(()->f.service.submit(f.user,command),"GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE");return null;});assertThat(f.writes).hasSize(1);
    }
    @Test void queueUsesLiteralBoundedFilteringAndDoesNotExposeRegistrySecrets(){
        var f=new Fixture();var queue=f.run(true,()->f.service.queue(f.user,f.school,"ALL","SUMMARY","%_\\",2,25));
        assertThat(queue.items()).isEmpty();assertThat(queue.page()).isEqualTo(2);assertThat(queue.schoolTimeZone()).isEqualTo("Asia/Kolkata");
        assertThat(f.reads).anySatisfy(read->{assertThat(read.params()).containsEntry("search","%\\%\\_\\\\%").containsEntry("offset",50L);});
        assertThat(JsonMapper.builder().build().writeValueAsString(queue)).doesNotContain("credentials","merchant","cursor","env:");verifyNoInteractions(f.audit);
    }
    @Test void queueCanSyncOnlyWithLiveRequestAuthorityAndExactlyOneApprovedRoute(){
        var f=new Fixture();var queue=f.run(true,()->f.service.queue(f.user,f.school,"ALL","ALL",null,0,25));
        assertThat(queue.syncEnabled()).isTrue();assertThat(queue.canSync()).isTrue();assertThat(queue.query()).isEmpty();
        f.canRequest=false;queue=f.run(true,()->f.service.queue(f.user,f.school,"ALL","ALL",null,0,25));
        assertThat(queue.syncEnabled()).isTrue();assertThat(queue.canSync()).isFalse();
        f.canRequest=true;for(var routes:List.of(List.<GatewayProviderRegistry.Route>of(),List.of(f.route,f.route))){
            when(f.registry.configuredRoutes()).thenReturn(routes);queue=f.run(true,()->f.service.queue(f.user,f.school,"ALL","ALL",null,0,25));
            assertThat(queue.syncEnabled()).isFalse();assertThat(queue.canSync()).isFalse();
        }
        when(f.registry.configuredRoutes()).thenReturn(List.of(f.route));f.activeProvider=false;
        queue=f.run(true,()->f.service.queue(f.user,f.school,"ALL","ALL",null,0,25));assertThat(queue.syncEnabled()).isFalse();assertThat(queue.canSync()).isFalse();
        verifyNoInteractions(f.audit);
    }
    @Test void queueIncludesLatestRetainedAttemptForRetryEvenWhenNothingWasSelected(){
        var f=new Fixture();var row=f.requestRow();row.put("derived_status","RETRYING");row.put("pages_completed",0);row.put("pages_pending",1);f.requestRows.add(row);
        var queue=f.run(true,()->f.service.queue(f.user,f.school,"ALL","ALL",null,0,25));
        assertThat(queue.items().getFirst().status()).isEqualTo("RETRYING");assertThat(queue.items().getFirst().lastCheckedAt()).isEqualTo(NOW);
        assertThat(f.reads).anySatisfy(read->assertThat(read.sql()).contains("max(latest_attempt.observed_at) last_checked_at",
                "attempt.query_id=query.id AND attempt.organisation_id=request.organisation_id AND attempt.school_id=request.school_id",
                "attempt.outcome IN('UNKNOWN','COMPLETE')","ORDER BY attempt.observed_at DESC,attempt.id LIMIT 1",
                "query.root_query_id=request.id").doesNotContain("max(selection.selected_at)"));
        verifyNoInteractions(f.audit);
    }
    @Test void reportVerifiesFullSelectedPageWithoutDecryptingOrAssumingCurrencyAndPreservesReportedPrecision(){
        var f=new Fixture();f.requestRows.add(f.requestRow());f.reportRows.add(f.evidence());
        var report=f.run(true,()->f.service.report(f.user,f.school,f.request,null,0,25));
        assertThat(report.items()).hasSize(1);var row=report.items().getFirst();assertThat(row.rowIndex()).isZero();
        assertThat(row.summary().reportedPaymentAmount()).isEqualTo("123.45678901");assertThat(row.summary().reportedServiceTax()).isNull();assertThat(row.event()).isNull();
        assertThat(JsonMapper.builder().build().writeValueAsString(report)).doesNotContain("INR","credentials","cursor","merchant","evidenceSha256");
        assertThat(f.reads).anySatisfy(read->assertThat(read.sql()).contains("WITH ORDINALITY","selection.request_id=request.id","query.root_query_id=request.id"));
        verifyNoInteractions(f.audit);verify(f.registry,never()).provider(any());
    }
    @Test void corruptHashNoncanonicalFactsAndUnavailableRequestFailClosed(){
        var f=new Fixture();f.run(true,()->{assertCode(()->f.service.report(f.user,f.school,UUID.randomUUID(),null,0,25),"GATEWAY_SETTLEMENT_NOT_FOUND");return null;});
        f.requestRows.add(f.requestRow());var row=f.evidence();row.put("evidence_sha256","0".repeat(64));f.reportRows.add(row);
        f.run(true,()->{assertCode(()->f.service.report(f.user,f.school,f.request,null,0,25),"GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE");return null;});
        row=f.evidence();String noncanonical=" "+row.get("facts_canonical");row.put("facts_canonical",noncanonical);
        row.put("evidence_sha256",GatewaySettlementReadStore.fingerprintFromHashes(f.prepared(),f.read,noncanonical,null,null));
        f.reportRows.clear();f.reportRows.add(row);
        f.run(true,()->{assertCode(()->f.service.report(f.user,f.school,f.request,null,0,25),"GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE");return null;});
    }
    private static void assertCode(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(code));}
    private record Read(String sql,Map<String,Object> params){}
    private static final class Fixture{
        final UUID org=UUID.randomUUID(),school=UUID.randomUUID(),member=UUID.randomUUID(),request=UUID.randomUUID(),client=UUID.randomUUID(),read=UUID.randomUUID(),provider=UUID.randomUUID();
        final AuthenticatedUser user=new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic@example.test",null,"Synthetic",false,org,Set.of(member),Set.of(),List.of());
        final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);final GatewayProviderRegistry registry=mock(GatewayProviderRegistry.class);final SecurityAuditService audit=mock(SecurityAuditService.class);
        final GatewayProviderRegistry.Route route=new GatewayProviderRegistry.Route("a".repeat(64),org,school,provider,"CASHFREE",CashfreePaymentProviderAdapter.Environment.TEST,"synthetic_merchant","synthetic:test-label","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID","env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET",true);
        final GatewaySettlementService service=new GatewaySettlementService(jdbc,registry,audit);final List<Read> reads=new ArrayList<>(),writes=new ArrayList<>();
        final List<Map<String,Object>> requestRows=new ArrayList<>(),reportRows=new ArrayList<>(),selected=new ArrayList<>();
        boolean dbContext=true,authorized=true,canRequest=true,activeProvider=true;String zone="Asia/Kolkata";Map<String,Object> replayed;org.springframework.dao.DataAccessException writeFailure;
        @SuppressWarnings({"rawtypes","unchecked"})Fixture(){
            when(registry.configuredRoutes()).thenReturn(List.of(route));when(registry.settlementsAllowed(route)).thenReturn(true);
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->call.getArgument(0,String.class).contains("current_app_")?dbContext:activeProvider);
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Integer.class))).thenReturn(1);
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class))).thenAnswer(call->(long)(call.getArgument(0,String.class).contains("report_rows")?reportRows.size():requestRows.size()));
            when(jdbc.queryForObject(eq("SELECT clock_timestamp()"),any(MapSqlParameterSource.class),any(RowMapper.class))).thenReturn(NOW);
            when(jdbc.update(anyString(),any(MapSqlParameterSource.class))).thenAnswer(call->{if(writeFailure!=null)throw writeFailure;writes.add(capture(call.getArgument(0),call.getArgument(1)));return 1;});
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(ResultSetExtractor.class))).thenAnswer(call->{reads.add(capture(call.getArgument(0),call.getArgument(1)));return ((ResultSetExtractor)call.getArgument(2)).extractData(result(replayed==null?List.of():List.of(replayed)));});
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(RowMapper.class))).thenAnswer(call->{
                String sql=call.getArgument(0);reads.add(capture(sql,call.getArgument(1)));List<Map<String,Object>> source;
                if(sql.contains("human-settlement-live-scope"))source=authorized?List.of(new HashMap<>(Map.of("membership_id",member,"time_zone",zone,"can_request",canRequest))):List.of();
                else if(sql.contains("report_rows"))source=reportRows;else if(sql.contains("selected_pages"))source=selected;else source=requestRows;
                var values=new ArrayList<>();RowMapper mapper=call.getArgument(2);for(int i=0;i<source.size();i++)values.add(mapper.mapRow(result(List.of(source.get(i)),true),i));return values;
            });
        }
        GatewaySettlementModels.Submit command(){return new GatewaySettlementModels.Submit(school,client,"Asia/Kolkata","SUMMARY",FROM,UNTIL,null,null);}
        Map<String,Object> replay(GatewaySettlementModels.Submit command){return Map.of("id",request,"query_kind",command.kind(),"school_time_zone",command.schoolTimeZone(),"request_fingerprint",GatewaySettlementTerms.prepare(command).fingerprint());}
        <T>T run(boolean readOnly,Supplier<T> action){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);try(var ignored=TenantRequestContext.open(user.userId(),org)){return action.get();}finally{TransactionSynchronizationManager.clear();}}
        Map<String,Object> requestRow(){var row=new HashMap<String,Object>();row.put("id",request);row.put("query_kind","SUMMARY");row.put("school_time_zone","Asia/Kolkata");row.put("derived_status","COMPLETE");row.put("from_exact",FROM.toString());row.put("until_exact",UNTIL.toString());row.put("requested_at",NOW.minusSeconds(60).atOffset(ZoneOffset.UTC));row.put("requested_by","Synthetic");row.put("pages_completed",1);row.put("pages_pending",0);row.put("pages_review",0);row.put("last_checked_at",NOW.atOffset(ZoneOffset.UTC));return row;}
        Map<String,Object> evidence(){
            var facts=new GatewaySettlementProvider.SettlementSummary("synthetic_statement","SUCCESS",null,null,null,NOW.minusSeconds(30),NOW.minusSeconds(20),new BigDecimal("123.45678901"),null,null,null,null,null,null,null,null,null,null,null);
            String canonical=GatewaySettlementFacts.summaries(List.of(facts));var query=prepared();
            var row=new HashMap<String,Object>();row.put("query_id",request);row.put("organisation_id",org);row.put("school_id",school);row.put("payment_provider_config_id",provider);row.put("provider_code","CASHFREE");row.put("provider_environment","TEST");row.put("merchant_account_reference","synthetic_merchant");row.put("credentials_secret_reference","synthetic:test-label");row.put("query_kind","SUMMARY");row.put("root_query_id",request);row.put("page_number",1);row.put("from_exact",FROM.toString());row.put("until_exact",UNTIL.toString());row.put("prepared_at",NOW.minusSeconds(60).atOffset(ZoneOffset.UTC));row.put("read_id",read);row.put("read_outcome","COMPLETE");row.put("facts_canonical",canonical);row.put("evidence_sha256",GatewaySettlementReadStore.fingerprintFromHashes(query,read,canonical,null,null));row.put("observed_at",NOW.atOffset(ZoneOffset.UTC));row.put("row_index",1);return row;
        }
        GatewaySettlementReadStore.Prepared prepared(){return new GatewaySettlementReadStore.Prepared(request,new GatewayWorkQueue.ProviderBinding(org,school,provider,"CASHFREE","TEST","synthetic_merchant","synthetic:test-label"),GatewaySettlementReadStore.Kind.SUMMARY,request,1,null,null,FROM,UNTIL,null,null,NOW.minusSeconds(60));}
        private static Read capture(String sql,MapSqlParameterSource p){return new Read(sql,new HashMap<>(p.getValues()));}
        private static ResultSet result(List<Map<String,Object>> rows)throws SQLException{return result(rows,false);}
        private static ResultSet result(List<Map<String,Object>> rows,boolean positioned)throws SQLException{
            int[] index={positioned?0:-1};var result=mock(ResultSet.class);when(result.next()).thenAnswer(call->++index[0]<rows.size());
            when(result.getObject(anyString(),any(Class.class))).thenAnswer(call->rows.get(index[0]).get(call.getArgument(0,String.class)));
            when(result.getString(anyString())).thenAnswer(call->(String)rows.get(index[0]).get(call.getArgument(0,String.class)));
            when(result.getBoolean(anyString())).thenAnswer(call->Boolean.TRUE.equals(rows.get(index[0]).get(call.getArgument(0,String.class))));
            when(result.getInt(anyString())).thenAnswer(call->{Object value=rows.get(index[0]).get(call.getArgument(0,String.class));return value==null?0:((Number)value).intValue();});return result;
        }
    }
}

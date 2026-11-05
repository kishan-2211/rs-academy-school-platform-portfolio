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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete EVENTS-chain validation is reused, not replaced; mocks isolate the additional summary/actor boundary. */
class GatewaySettlementBankEvidenceAccessTest {
    @AfterEach void clear(){TransactionSynchronizationManager.clear();assertThat(TenantRequestContext.current()).isEmpty();}
    @Test void exactSelectedSummaryAndZeroBasedRowAreBoundToTheAlreadyCompleteEventChain(){
        var f=new Fixture();var source=f.run(()->f.service.bankEvidenceSource(f.user,f.pure.school,f.pure.request));
        assertThat(source.summaryReadId()).isEqualTo(f.pure.summary);assertThat(source.summaryRowIndex()).isZero();
        assertThat(source.summary().reportedAmountSettled()).isEqualByComparingTo("100.00000000");
        verify(f.service).completeEvents(f.user,f.pure.school,f.pure.request);verifyNoInteractions(f.registry,f.audit);
    }
    @Test void missingDuplicateOrForeignStatementSummaryCannotBecomeBankEvidenceSource(){
        var f=new Fixture();f.selected.clear();f.run(()->{code(()->f.service.bankEvidenceSource(f.user,f.pure.school,f.pure.request),"GATEWAY_SETTLEMENT_NOT_FOUND");return null;});
        f.retain(List.of(f.summary("synthetic_statement"),f.summary("synthetic_statement")),f.pure.binding);
        f.run(()->{unavailable(()->f.service.bankEvidenceSource(f.user,f.pure.school,f.pure.request));return null;});
        f.retain(List.of(f.summary("synthetic_other_statement")),f.pure.binding);
        f.run(()->{unavailable(()->f.service.bankEvidenceSource(f.user,f.pure.school,f.pure.request));return null;});
    }
    @Test void canonicallyValidSummaryFromAnotherFrozenMerchantBindingIsStillDenied(){
        var f=new Fixture();var b=f.pure.binding;var other=new GatewayWorkQueue.ProviderBinding(b.organisationId(),b.schoolId(),b.providerConfigId(),b.providerCode(),b.environment(),"synthetic_other_merchant",b.credentialsReference());
        f.retain(List.of(f.summary("synthetic_statement")),other);
        f.run(()->{unavailable(()->f.service.bankEvidenceSource(f.user,f.pure.school,f.pure.request));return null;});
    }
    @Test void actualEligibleMembershipIsSelectedPerActionAndReadFlagsAggregateWithoutConferringWriteAuthority(){
        var f=new Fixture();UUID maker=UUID.randomUUID(),reviewer=UUID.randomUUID();
        f.authorities=List.of(Map.of("membership_id",maker,"time_zone","Asia/Kolkata","can_record",true,"can_reject",false),
                Map.of("membership_id",reviewer,"time_zone","Asia/Kolkata","can_record",false,"can_reject",true));
        f.run(()->{
            var read=f.service.bankEvidenceAuthority(f.user,f.pure.school,null);assertThat(read.canRecord()).isTrue();assertThat(read.canReject()).isTrue();
            assertThat(f.service.bankEvidenceAuthority(f.user,f.pure.school,"fees.close.propose").membershipId()).isEqualTo(maker);
            assertThat(f.service.bankEvidenceAuthority(f.user,f.pure.school,"fees.close.approve").membershipId()).isEqualTo(reviewer);return null;
        });
    }
    @Test void noCurrentActionAuthorityOrReadOnlyTransactionCanWriteAndSystemScopeCannotInspect(){
        var f=new Fixture();f.authorities=List.of(Map.of("membership_id",f.member,"time_zone","Asia/Kolkata","can_record",false,"can_reject",false));
        f.run(()->{code(()->f.service.bankEvidenceAuthority(f.user,f.pure.school,"fees.close.propose"),"GATEWAY_SETTLEMENT_SCOPE_DENIED");
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);code(()->f.service.bankEvidenceAuthority(f.user,f.pure.school,"fees.close.approve"),"GATEWAY_SETTLEMENT_SCOPE_DENIED");return null;});
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try(var ignored=TenantRequestContext.open(null,f.pure.org)){code(()->f.service.bankEvidenceAuthority(f.user,f.pure.school,null),"GATEWAY_SETTLEMENT_SCOPE_DENIED");}
    }
    private static void unavailable(Runnable action){code(action,"GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE");}
    private static void code(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(code));}
    private static final class Fixture{
        final GatewaySettlementSourceReviewFixtures pure=new GatewaySettlementSourceReviewFixtures();final UUID member=UUID.randomUUID();
        final AuthenticatedUser user=new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic@example.test",null,"Synthetic",false,pure.org,Set.of(member),Set.of(),List.of());
        final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);final GatewayProviderRegistry registry=mock(GatewayProviderRegistry.class);final SecurityAuditService audit=mock(SecurityAuditService.class);
        final GatewaySettlementService service=spy(new GatewaySettlementService(jdbc,registry,audit));final List<Map<String,Object>> selected=new ArrayList<>();List<Map<String,Object>> authorities;
        @SuppressWarnings({"rawtypes","unchecked"})Fixture(){
            doReturn(pure.chain(List.of(List.of()))).when(service).completeEvents(user,pure.school,pure.request);
            authorities=List.of(Map.of("membership_id",member,"time_zone","Asia/Kolkata","can_record",true,"can_reject",true));
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Boolean.class))).thenReturn(true);
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(RowMapper.class))).thenAnswer(call->{
                String sql=call.getArgument(0);List<Map<String,Object>> rows=sql.contains("human-settlement-bank-authority")?authorities:
                        sql.contains("human-settlement-live-scope")?List.of(Map.of("membership_id",member,"time_zone","Asia/Kolkata","can_request",true)):selected;
                var values=new ArrayList<>();RowMapper mapper=call.getArgument(2);for(int index=0;index<rows.size();index++){
                    var row=rows.get(index);var rs=mock(ResultSet.class);when(rs.getObject(anyString(),any(Class.class))).thenAnswer(value->row.get(value.getArgument(0,String.class)));
                    when(rs.getString(anyString())).thenAnswer(value->row.get(value.getArgument(0,String.class)));
                    when(rs.getBoolean(anyString())).thenAnswer(value->Boolean.TRUE.equals(row.get(value.getArgument(0,String.class))));
                    when(rs.getInt(anyString())).thenAnswer(value->((Number)row.getOrDefault(value.getArgument(0,String.class),0)).intValue());values.add(mapper.mapRow(rs,index));}return values;
            });retain(List.of(summary("synthetic_statement")),pure.binding);
        }
        GatewaySettlementProvider.SettlementSummary summary(String reference){return new GatewaySettlementProvider.SettlementSummary(reference,"SUCCESS","UTR/1",null,null,null,null,null,null,null,null,null,null,null,null,null,new BigDecimal("100.00000000"),null,null);}
        void retain(List<GatewaySettlementProvider.SettlementSummary> facts,GatewayWorkQueue.ProviderBinding b){
            selected.clear();UUID queryId=UUID.randomUUID();var time=GatewaySettlementSourceReviewFixtures.TIME;
            var query=new GatewaySettlementReadStore.Prepared(queryId,b,GatewaySettlementReadStore.Kind.SUMMARY,queryId,1,null,null,time.minusSeconds(10),time.minusSeconds(1),null,null,time);
            String canonical=GatewaySettlementFacts.summaries(facts);var row=new HashMap<String,Object>();
            row.put("query_id",queryId);row.put("organisation_id",b.organisationId());row.put("school_id",b.schoolId());row.put("payment_provider_config_id",b.providerConfigId());
            row.put("provider_code",b.providerCode());row.put("provider_environment",b.environment());row.put("merchant_account_reference",b.merchantReference());row.put("credentials_secret_reference",b.credentialsReference());
            row.put("query_kind","SUMMARY");row.put("root_query_id",queryId);row.put("page_number",1);row.put("from_exact",query.from().toString());row.put("until_exact",query.until().toString());
            row.put("prepared_at",time.atOffset(ZoneOffset.UTC));row.put("read_id",pure.summary);row.put("read_outcome","COMPLETE");row.put("facts_canonical",canonical);
            row.put("observed_at",time.plusSeconds(1).atOffset(ZoneOffset.UTC));row.put("evidence_sha256",GatewaySettlementReadStore.fingerprintFromHashes(query,pure.summary,canonical,null,null));selected.add(row);
        }
        <T>T run(Supplier<T> action){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            try(var ignored=TenantRequestContext.open(user.userId(),pure.org)){return action.get();}finally{TransactionSynchronizationManager.clear();}}
    }
}

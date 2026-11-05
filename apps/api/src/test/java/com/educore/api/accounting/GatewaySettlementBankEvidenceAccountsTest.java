package com.educore.api.accounting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.tenancy.TenantRequestContext;
import java.sql.ResultSet;
import java.util.ArrayList;
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

/** Mocked accounting reader only; SQL/RLS and actual bank context are separately verified against PostgreSQL. */
class GatewaySettlementBankEvidenceAccountsTest {
    @AfterEach void clear(){TransactionSynchronizationManager.clear();assertThat(TenantRequestContext.current()).isEmpty();}
    @Test void noTransactionAnonymousSystemOrForeignTenantCannotReadBankOptions(){
        var f=new Fixture();denied(()->f.reader.read(f.user,f.school));TransactionSynchronizationManager.setActualTransactionActive(true);
        try(var ignored=TenantRequestContext.open(null,f.org)){denied(()->f.reader.read(f.user,f.school));}
        try(var ignored=TenantRequestContext.open(f.user.userId(),UUID.randomUUID())){denied(()->f.reader.read(f.user,f.school));}
        try(var ignored=TenantRequestContext.open(f.user.userId(),f.org)){denied(()->f.reader.read(null,f.school));}
        verifyNoInteractions(f.jdbc);
    }
    @Test void revokedOrExpiredAccountingAuthorityFailsClosedBeforeChartLookup(){
        var f=new Fixture();f.authorized=false;f.run(()->{denied(()->f.reader.read(f.user,f.school));return null;});
        assertThat(f.sql).isNull();
    }
    @Test void optionsAreBoundedExactActiveBankIdentitiesInOneCurrentInrBookWithoutPostingPeriodClaims(){
        var f=new Fixture();var result=f.run(()->f.reader.read(f.user,f.school));
        assertThat(result).hasSize(1);assertThat(result.getFirst().id()).isEqualTo(f.bank);assertThat(result.getFirst().currencyCode()).isEqualTo("INR");
        assertThat(f.sql).contains("account.account_type='ASSET'","account.category='BANK'","account.status='ACTIVE'","account.allow_direct_posting",
                "year.status='OPEN'","book.currency_code='INR'","(SELECT count(*) FROM current_book)=1","LIMIT 1001",
                "gateway_refund_school_date(to_char(clock_timestamp() AT TIME ZONE 'UTC'")
                .doesNotContain("accounting_periods","FOR UPDATE","pg_advisory","INSERT","UPDATE","DELETE");
        assertThat(f.parameters).containsEntry("organisationId",f.org).containsEntry("schoolId",f.school);
        verify(f.jdbc,never()).update(anyString(),any(MapSqlParameterSource.class));
    }
    @Test void noUniqueCurrentInrContextIsRepresentedByNoOptionsNotInventedCurrency(){
        var f=new Fixture();f.rows=0;assertThat(f.run(()->f.reader.read(f.user,f.school))).isEmpty();
    }
    @Test void exceedingTheOptionBoundFailsRatherThanReturningAHiddenPartialSet(){
        var f=new Fixture();f.rows=1001;f.run(()->{assertThatThrownBy(()->f.reader.read(f.user,f.school)).isInstanceOfSatisfying(ApiException.class,
                error->assertThat(error.code()).isEqualTo("GATEWAY_SETTLEMENT_BANK_ACCOUNT_UNAVAILABLE"));return null;});
    }
    @Test void optionCollectionsAndDiagnosticsDoNotLeakOrPermitMutation(){
        var f=new Fixture();var options=f.run(()->f.reader.read(f.user,f.school));
        assertThatThrownBy(options::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(options.getFirst().toString()).doesNotContain("SYNTHETIC_BANK","Synthetic bank");
    }
    private static void denied(Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo("ACCOUNTING_SCOPE_DENIED"));}
    private static final class Fixture{
        final UUID org=UUID.randomUUID(),school=UUID.randomUUID(),bank=UUID.randomUUID();
        final AuthenticatedUser user=new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic@example.test",null,"Synthetic",false,org,Set.of(UUID.randomUUID()),Set.of(),List.of());
        final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);final GatewaySettlementBankEvidenceAccounts reader=new GatewaySettlementBankEvidenceAccounts(jdbc);
        boolean authorized=true;int rows=1;String sql;Map<String,Object> parameters;
        @SuppressWarnings({"rawtypes","unchecked"})Fixture(){
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->{
                assertThat(call.getArgument(0,String.class)).contains("member.valid_from<=clock_timestamp()","member.valid_until>clock_timestamp()","capability.capability_code='accounting.read'");return authorized;});
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(RowMapper.class))).thenAnswer(call->{
                sql=call.getArgument(0);parameters=((MapSqlParameterSource)call.getArgument(1)).getValues();var result=new ArrayList<>();RowMapper mapper=call.getArgument(2);
                for(int n=0;n<rows;n++){var rs=mock(ResultSet.class);when(rs.getObject("id",UUID.class)).thenReturn(bank);
                    when(rs.getString("code")).thenReturn("SYNTHETIC_BANK");when(rs.getString("display_name")).thenReturn("Synthetic bank");when(rs.getString("currency_code")).thenReturn("INR");result.add(mapper.mapRow(rs,n));}return result;
            });
        }
        <T>T run(Supplier<T> action){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            try(var ignored=TenantRequestContext.open(user.userId(),org)){return action.get();}finally{TransactionSynchronizationManager.clear();}}
    }
}

package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.educore.api.common.api.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class CollectionRegisterQueryTest {
    private static final LocalDate START=LocalDate.of(2026,1,1);
    private CollectionRegisterQuery.Filter filter(LocalDate from, LocalDate to, String currency, String method, String query, int page, int size) {
        return new CollectionRegisterQuery.Filter(from,to,currency,method,query,page,size);
    }
    @Test void acceptsAtMost366InclusivePostingDatesIncludingALeapYear() {
        assertThat(filter(START,START.plusDays(365),"INR",null,null,0,25).to()).isEqualTo(START.plusDays(365));
        assertThat(filter(LocalDate.of(2024,1,1),LocalDate.of(2024,12,31),"INR",null,null,0,25).from()).isEqualTo(LocalDate.of(2024,1,1));
        assertInvalid(()->filter(START,START.plusDays(366),"INR",null,null,0,25));
        assertInvalid(()->filter(START,START.minusDays(1),"INR",null,null,0,25));
    }
    @Test void missingAndOutOfIsoCalendarDatesAreRejected() {
        assertInvalid(()->filter(null,START,"INR",null,null,0,25));
        assertInvalid(()->filter(START,null,"INR",null,null,0,25));
        assertInvalid(()->filter(LocalDate.of(0,1,1),LocalDate.of(0,1,1),"INR",null,null,0,25));
        assertInvalid(()->filter(LocalDate.of(10000,1,1),LocalDate.of(10000,1,1),"INR",null,null,0,25));
    }
    @Test void exactTenderAndCurrencyFiltersCannotIntroduceQueriesOrMixedCurrencyTotals() {
        for(String method:CollectionRegisterQuery.METHODS) assertThat(filter(START,START,"INR",method,null,0,25).paymentMethod()).isEqualTo(method);
        for(String method:List.of("ALL","cash","CASH' OR true--","")) assertInvalid(()->filter(START,START,"INR",method,null,0,25));
        for(String currency:List.of("inr","INR,USD","INR'","")) assertInvalid(()->filter(START,START,currency,null,null,0,25));
        assertInvalid(()->filter(START,START,null,null,null,0,25));
    }
    @Test void literalSearchEscapesWildcardsAndBackslashesButKeepsAuthoredCharacters() {
        assertThat(filter(START,START,"INR",null,"  Synthetic_%\\' परीक्षण  ",0,25).searchPattern())
                .isEqualTo("%Synthetic\\_\\%\\\\' परीक्षण%");
        assertThat(filter(START,START,"INR",null,"   ",0,25).query()).isNull();
        assertThat(filter(START,START,"INR",null,null,0,25).searchPattern()).isNull();
        assertInvalid(()->filter(START,START,"INR",null,"x".repeat(101),0,25));
        assertInvalid(()->filter(START,START,"INR",null,"Synthetic\nrecord",0,25));
    }
    @Test void pageAndPageSizeHaveIndependentBounds() {
        assertThat(filter(START,START,"INR",null,null,10000,100).page()).isEqualTo(10000);
        for(int page:List.of(-1,10001,Integer.MAX_VALUE)) assertInvalid(()->filter(START,START,"INR",null,null,page,25));
        for(int size:List.of(0,101,Integer.MAX_VALUE)) assertInvalid(()->filter(START,START,"INR",null,null,0,size));
    }
    @Test void serviceDeniesAnAnonymousActorBeforeAnyFinancialRead() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        var service=new FinanceService(jdbc,mock(com.educore.api.identity.SecurityAuditService.class),JsonMapper.builder().build(),java.time.Clock.systemUTC());
        assertThatThrownBy(()->service.collections(null,UUID.randomUUID(),START,START,null,null,null,0,25))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(jdbc);
    }
    @Test void exportRequiresTheSameLiveAuthorityAndNeverReadsForAnAnonymousActor() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        var service=new FinanceService(jdbc,mock(com.educore.api.identity.SecurityAuditService.class),JsonMapper.builder().build(),java.time.Clock.systemUTC());
        assertThatThrownBy(()->service.exportCollections(null,UUID.randomUUID(),START,START,null,null,null))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(jdbc);
    }
    @Test void wholeRangeExportAcceptsTheExactBoundButNeverSilentlyTruncates() {
        CollectionRegisterQuery.requireExportBound(0,true);
        CollectionRegisterQuery.requireExportBound(10000,true);
        CollectionRegisterQuery.requireExportBound(10001,false);
        assertThatThrownBy(()->CollectionRegisterQuery.requireExportBound(10001,true))
                .isInstanceOfSatisfying(ApiException.class,error->{
                    assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.code()).isEqualTo("COLLECTION_REGISTER_EXPORT_TOO_LARGE");
                });
    }
    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->{
            assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(error.code()).isEqualTo("COLLECTION_REGISTER_FILTER_INVALID");
        });
    }
}

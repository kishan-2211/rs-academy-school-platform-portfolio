package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;
import com.educore.api.common.api.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewaySettlementTermsTest {
    private static final UUID SCHOOL=UUID.fromString("85000000-0000-4000-8000-000000000001"),CLIENT=UUID.fromString("85000000-0000-4000-8000-000000000002");
    private static final Instant FROM=Instant.parse("2026-10-01T00:00:00.123456789Z"),UNTIL=FROM.plusSeconds(86400);
    @Test void summaryHasExactUtcTimezoneBoundedTermsAndStableReplayFingerprint(){
        var command=summary(FROM,UNTIL);var first=GatewaySettlementTerms.prepare(command);
        assertThat(first.from()).isEqualTo(FROM);assertThat(first.schoolTimeZone()).isEqualTo("Asia/Kolkata");
        assertThat(first.fingerprint()).matches("[a-f0-9]{64}").isEqualTo(GatewaySettlementTerms.prepare(command).fingerprint());
        assertThat(GatewaySettlementTerms.prepare(summary(FROM.plusNanos(1),UNTIL)).fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(first.toString()).doesNotContain(SCHOOL.toString(),"Kolkata");
    }
    @Test void moreThan31DaysReversedEqualOutOfRangeAndMixedSummaryTermsAreRejected(){
        for(var command:new GatewaySettlementModels.Submit[]{summary(FROM,FROM),summary(UNTIL,FROM),summary(FROM,FROM.plusSeconds(31L*86400+1)),
                summary(Instant.parse("1999-12-31T23:59:59Z"),Instant.parse("2000-01-01T00:00:00Z")),
                new GatewaySettlementModels.Submit(SCHOOL,CLIENT,"Asia/Kolkata","SUMMARY",FROM,UNTIL,UUID.randomUUID(),null)})
            assertThatThrownBy(()->GatewaySettlementTerms.prepare(command)).isInstanceOf(ApiException.class);
    }
    @Test void eventsRequireAnObservedIdentityWithoutInventedDateWindowOrFreeFormReference(){
        UUID read=UUID.randomUUID();var valid=new GatewaySettlementModels.Submit(SCHOOL,CLIENT,"Asia/Kolkata","EVENTS",null,null,read,"synthetic_statement_1");
        assertThat(GatewaySettlementTerms.prepare(valid).sourceSummaryReadId()).isEqualTo(read);
        for(var invalid:new GatewaySettlementModels.Submit[]{
                new GatewaySettlementModels.Submit(SCHOOL,CLIENT,"Asia/Kolkata","EVENTS",FROM,null,read,"synthetic_statement_1"),
                new GatewaySettlementModels.Submit(SCHOOL,CLIENT,"Asia/Kolkata","EVENTS",null,null,null,"synthetic_statement_1"),
                new GatewaySettlementModels.Submit(SCHOOL,CLIENT,"Asia/Kolkata","EVENTS",null,null,read,"unsafe/reference")})
            assertThatThrownBy(()->GatewaySettlementTerms.prepare(invalid)).isInstanceOf(ApiException.class);
    }
    @Test void timezoneAndKindsAreNotLooselyNormalizedOrGuessed(){
        for(String zone:new String[]{null,"not-a-zone","Asia/Kolkata ",""})assertThatThrownBy(()->GatewaySettlementTerms.prepare(new GatewaySettlementModels.Submit(SCHOOL,CLIENT,zone,"SUMMARY",FROM,UNTIL,null,null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->GatewaySettlementTerms.prepare(new GatewaySettlementModels.Submit(SCHOOL,CLIENT,"UTC","summary",FROM,UNTIL,null,null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->GatewaySettlementTerms.prepare(null)).isInstanceOf(ApiException.class);
    }
    @Test void literalBilingualSearchEscapesLikeOperatorsAndBoundsPageWork(){
        assertThat(GatewaySettlementTerms.search(" विद्यालय %_\\ ")).isEqualTo("विद्यालय %_\\");
        assertThat(GatewaySettlementTerms.like("%_\\")).isEqualTo("%\\%\\_\\\\%");
        assertThat(GatewaySettlementTerms.search(null)).isEmpty();
        for(String q:new String[]{"a".repeat(101),"unsafe\nquery"})assertThatThrownBy(()->GatewaySettlementTerms.search(q)).isInstanceOf(ApiException.class);
        for(int[] value:new int[][]{{-1,25},{10001,25},{0,0},{0,51}})assertThatThrownBy(()->GatewaySettlementTerms.page(value[0],value[1])).isInstanceOf(ApiException.class);
        GatewaySettlementTerms.page(10000,50);
    }
    private static GatewaySettlementModels.Submit summary(Instant from,Instant until){return new GatewaySettlementModels.Submit(SCHOOL,CLIENT,"Asia/Kolkata","SUMMARY",from,until,null,null);}
}

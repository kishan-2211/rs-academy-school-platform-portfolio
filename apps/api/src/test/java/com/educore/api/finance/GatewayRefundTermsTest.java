package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;
import com.educore.api.common.api.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GatewayRefundTermsTest {
    private final UUID payment=UUID.randomUUID(),request=UUID.randomUUID(),first=UUID.randomUUID(),second=UUID.randomUUID();
    private GatewayRefundTerms.Selection item(UUID charge,String amount){return new GatewayRefundTerms.Selection(charge,amount==null?null:new BigDecimal(amount));}
    private GatewayRefundTerms.Terms terms(List<GatewayRefundTerms.Selection> items,String why){return GatewayRefundTerms.prepare(payment,request,items,why);}
    @Test void exactDecimalSumAndCanonicalOrderAreStableAcrossRetry() {
        var a=terms(List.of(item(first,"29.29"),item(second,"0.71")),"  Synthetic partial refund परीक्षण  ");
        var b=terms(List.of(item(second,"0.71"),item(first,"29.29")),"Synthetic partial refund परीक्षण");
        assertThat(a).isEqualTo(b);assertThat(a.amount()).isEqualByComparingTo("30.00");assertThat(a.fingerprint()).matches("[a-f0-9]{64}");
    }
    @Test void originalPaymentReasonAllocationAndAmountArePartOfTheFrozenIdentity() {
        var a=terms(List.of(item(first,"29.29")),"Synthetic refund");
        assertThat(terms(List.of(item(first,"29.30")),"Synthetic refund").fingerprint()).isNotEqualTo(a.fingerprint());
        assertThat(terms(List.of(item(second,"29.29")),"Synthetic refund").fingerprint()).isNotEqualTo(a.fingerprint());
        assertThat(terms(List.of(item(first,"29.29")),"Synthetic other refund").fingerprint()).isNotEqualTo(a.fingerprint());
        assertThat(GatewayRefundTerms.prepare(UUID.randomUUID(),request,a.allocations(),a.reason()).fingerprint()).isNotEqualTo(a.fingerprint());
    }
    @Test void amountsNeverRoundOrAcceptZeroNegativeOversizedOrUnsupportedScale() {
        for(String amount:List.of("0","-1","100000000.01","1.001","1.250"))reject(()->terms(List.of(item(first,amount)),"Synthetic refund"));
        reject(()->terms(List.of(item(first,null)),"Synthetic refund"));
        reject(()->terms(List.of(item(first,"100000000"),item(second,"0.01")),"Synthetic refund"));
        assertThat(terms(List.of(item(first,"100000000")),"Synthetic refund").amount()).isEqualByComparingTo("100000000.00");
    }
    @Test void uniqueOriginalAllocationsAndExplicitRequestIdentityAreRequired() {
        reject(()->terms(null,"Synthetic refund"));reject(()->terms(List.of(),"Synthetic refund"));
        reject(()->terms(List.of(item(first,"1"),item(first,"2")),"Synthetic refund"));
        reject(()->terms(List.of(item(null,"1")),"Synthetic refund"));
        var items=new ArrayList<GatewayRefundTerms.Selection>();items.add(null);reject(()->terms(items,"Synthetic refund"));
        var tooMany=new ArrayList<GatewayRefundTerms.Selection>();for(int i=0;i<101;i++)tooMany.add(item(UUID.randomUUID(),"1"));reject(()->terms(tooMany,"Synthetic refund"));
        reject(()->GatewayRefundTerms.prepare(null,request,List.of(item(first,"1")),"Synthetic refund"));
        reject(()->GatewayRefundTerms.prepare(payment,null,List.of(item(first,"1")),"Synthetic refund"));
    }
    @Test void reasonSupportsHindiButRejectsMissingOversizedAndControlCharacters() {
        assertThat(terms(List.of(item(first,"1")),"  काल्पनिक वापसी कारण  ").reason()).isEqualTo("काल्पनिक वापसी कारण");
        for(String why:List.of("","  ","ab","x".repeat(501),"Synthetic\nrefund","Synthetic\u0085refund"))reject(()->terms(List.of(item(first,"1")),why));
        reject(()->terms(List.of(item(first,"1")),null));
    }
    @Test void callerCannotChangeTermsAfterPreparation() {
        var items=new ArrayList<>(List.of(item(first,"1.25")));var frozen=terms(items,"Synthetic refund");items.clear();
        assertThat(frozen.allocations()).hasSize(1);assertThatThrownBy(()->frozen.allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    private static void reject(Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->{
        assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(error.code()).isEqualTo("GATEWAY_REFUND_TERMS_INVALID");
    });}
}

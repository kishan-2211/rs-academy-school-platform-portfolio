package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;

import com.educore.api.common.api.ApiException;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RefundCollectionTest {
    private static BigDecimal amount(String value){return new BigDecimal(value);}

    @Test void partialRefundIsSeparateFromGrossReceiptAndManualReversal(){
        var summary=CollectionRegisterQuery.summary(2,amount("529.29"),1,amount("29.29"),1,amount("50.25"));
        assertThat(summary.grossCollections()).isEqualByComparingTo("529.29");
        assertThat(summary.reversalAmount()).isEqualByComparingTo("29.29");
        assertThat(summary.refundCount()).isEqualTo(1);
        assertThat(summary.refundAmount()).isEqualByComparingTo("50.25");
        assertThat(summary.netCollections()).isEqualByComparingTo("449.75");
    }

    @Test void refundOnlyDayIsTruthfullyNegativeAndRetainsNoInventedCollection(){
        var summary=CollectionRegisterQuery.summary(0,BigDecimal.ZERO,0,BigDecimal.ZERO,2,amount("50.25"));
        assertThat(summary.collectionCount()).isZero();
        assertThat(summary.grossCollections()).isEqualByComparingTo("0.00");
        assertThat(summary.netCollections()).isEqualByComparingTo("-50.25");
    }

    @Test void legacySummaryConstructorDefaultsOnlyTheNewRefundFields(){
        var summary=new CollectionRegisterResponse.Summary(1,amount("25.00"),0,amount("0.00"),amount("25.00"));
        assertThat(summary.refundCount()).isZero();assertThat(summary.refundAmount()).isEqualByComparingTo("0.00");
    }

    @Test void signedDeclarationRequiresActualNegativeOnlineRefundNetAndIsBounded(){
        assertThat(FinanceService.dayCloseDeclaration("ONLINE_PROVIDER",amount("-50.25"),amount("-50.25"),amount("50.25"))).isEqualByComparingTo("-50.25");
        assertThat(FinanceService.dayCloseDeclaration("ONLINE_PROVIDER",amount("-10.00"),amount("-50.25"),amount("50.25"))).isEqualByComparingTo("-10.00");
        for(String method:java.util.List.of("CASH","SCHOOL_UPI","BANK_TRANSFER","CHEQUE")){
            assertThatThrownBy(()->FinanceService.dayCloseDeclaration(method,amount("-1.00"),amount("-50.25"),amount("50.25"))).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(()->FinanceService.dayCloseDeclaration("ONLINE_PROVIDER",amount("-50.26"),amount("-50.25"),amount("50.25"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->FinanceService.dayCloseDeclaration("ONLINE_PROVIDER",amount("-1.00"),amount("0.00"),amount("50.25"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->FinanceService.dayCloseDeclaration("ONLINE_PROVIDER",amount("-1.00"),amount("-50.25"),BigDecimal.ZERO)).isInstanceOf(ApiException.class);
    }

    @Test void ordinaryNonnegativeDeclarationsStaySupportedButScaleAndBoundDoNotWeaken(){
        assertThat(FinanceService.dayCloseDeclaration("CASH",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)).isEqualByComparingTo("0.00");
        assertThat(FinanceService.dayCloseDeclaration("CASH",amount("29.29"),amount("25.00"),BigDecimal.ZERO)).isEqualByComparingTo("29.29");
        assertThatThrownBy(()->FinanceService.dayCloseDeclaration("ONLINE_PROVIDER",amount("-0.001"),amount("-10.00"),amount("10.00"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->FinanceService.dayCloseDeclaration("CASH",amount("100000000.01"),BigDecimal.ZERO,BigDecimal.ZERO)).isInstanceOf(ApiException.class);
    }

    @Test void httpValidationPermitsOnlyOnlineSignedDeclarationAndKeepsDecimalPrecision(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var validator=factory.getValidator();
            assertThat(validator.validate(new FinanceController.TenderDeclaration("ONLINE_PROVIDER",amount("-50.25")))).isEmpty();
            assertThat(validator.validate(new FinanceController.TenderDeclaration("CASH",amount("-50.25")))).isNotEmpty();
            assertThat(validator.validate(new FinanceController.TenderDeclaration("ONLINE_PROVIDER",amount("-0.001")))).isNotEmpty();
            assertThat(validator.validate(new FinanceController.TenderDeclaration("ONLINE_PROVIDER",amount("-100000000.01")))).isNotEmpty();
        }
    }
}

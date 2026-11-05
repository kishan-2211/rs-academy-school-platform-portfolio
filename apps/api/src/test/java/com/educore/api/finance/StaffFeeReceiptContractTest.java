package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Staff response shape only; actual source derivation is checked against PostgreSQL. */
class StaffFeeReceiptContractTest {
    @Test void capturedReceiptExposesOnlyTheBooleanRoutingHintNotItsPrivateCaptureIdentity() {
        var value=JsonMapper.builder().build().valueToTree(receipt("ONLINE_PROVIDER",true));
        assertThat(value.get("gatewayRefundSource").booleanValue()).isTrue();
        assertThat(value.size()).isEqualTo(12);
        assertThat(value.get("paymentMethod").stringValue()).isEqualTo("ONLINE_PROVIDER");
        assertThat(value.toString()).doesNotContain("gatewayCaptureId","captureId","credentials","merchant","payerPhone");
        assertThat(value.get("timing").get("receivedTimePrecision").stringValue()).isEqualTo("INSTANT");
    }

    @Test void manualAndLegacyOnlineReceiptsKeepAnExplicitFalseHintWithOriginalReceiptEvidence() {
        for(String method:List.of("CASH","SCHOOL_UPI","BANK_TRANSFER","CHEQUE","ONLINE_PROVIDER")) {
            var value=JsonMapper.builder().build().valueToTree(receipt(method,false));
            assertThat(value.get("gatewayRefundSource").booleanValue()).isFalse();
            assertThat(value.get("receiptNumber").stringValue()).isEqualTo("SYN-000001");
            assertThat(value.get("status").stringValue()).isEqualTo("POSTED");
            assertThat(value.get("amount").decimalValue()).isEqualByComparingTo("29.29");
            assertThat(value.get("externalReference").isNull()).isTrue();
            assertThat(value.get("notes").isNull()).isTrue();
        }
    }

    private static FinanceSnapshot.PaymentItem receipt(String method,boolean source) {
        return new FinanceSnapshot.PaymentItem(UUID.fromString("76000000-0000-4000-8000-000000000099"),
                "SYN-000001",method,null,new BigDecimal("29.29"),LocalDate.of(2026,10,17),null,"POSTED",
                Instant.parse("2026-10-17T10:01:00Z"),0,new FinanceSnapshot.ReceiptTiming(LocalDate.of(2026,10,17),
                        Instant.parse("2026-10-17T10:00:00.123456789Z"),"INSTANT","Asia/Kolkata"),source);
    }
}

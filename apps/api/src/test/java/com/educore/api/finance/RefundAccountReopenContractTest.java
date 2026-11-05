package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Match application.yml's NON_NULL default, not only the MVC fixture's plain mapper. */
class RefundAccountReopenContractTest {
    private static final JsonMapper JSON=JsonMapper.builder().changeDefaultPropertyInclusion(
            value->value.withValueInclusion(JsonInclude.Include.NON_NULL)).build();
    private final UUID school=UUID.randomUUID(),refund=UUID.randomUUID(),payment=UUID.randomUUID(),account=UUID.randomUUID();

    @Test void activeAccountWithoutAHoldKeepsExplicitNullsWithoutInventingRefundMoney() {
        assertThat(JSON.valueToTree(new Probe("Synthetic",null)).has("absent")).isFalse();
        var value=JSON.valueToTree(new RefundAccountReopenModels.Context(school,refund,payment,account,
                "Synthetic learner","Synthetic session","INR","Asia/Kolkata","NO_HOLD","ACTIVE",0,null,
                new BigDecimal("-1.01"),null,false,false,false,false,List.of()));
        assertThat(value.get("currentClosure").isNull()).isTrue();
        assertThat(value.get("confirmedUnpostedRefundAmount").isNull()).isTrue();
        assertThat(value.get("currentBalance").decimalValue()).isEqualByComparingTo("-1.01");
        assertThat(value.get("refundPosted").booleanValue()).isFalse();
    }
    @Test void unfilteredPendingQueueKeepsExplicitNoDecisionAndNullSearchUnderTheActualInclusionPolicy() {
        var closure=new RefundAccountReopenModels.Closure(Instant.parse("2026-10-18T00:00:00Z"),"Synthetic original closure","Synthetic closer");
        var review=new RefundAccountReopenModels.Review(UUID.randomUUID(),refund,payment,account,"Synthetic learner","Synthetic session",
                "SUBMITTED","Synthetic review reason",Instant.parse("2026-10-18T00:01:00Z"),"Synthetic maker",false,true,true,false,1,closure,null);
        var value=JSON.valueToTree(new RefundAccountReopenModels.Queue(school,"SUBMITTED",null,0,25,1,false,false,List.of(review)));
        assertThat(value.get("query").isNull()).isTrue();
        assertThat(value.get("items").get(0).get("decision").isNull()).isTrue();
        assertThat(value.get("items").get(0).get("originalClosure").get("reason").stringValue()).isEqualTo(closure.reason());
        assertThat(value.get("refundPosted").booleanValue()).isFalse();
        assertThat(value.toString()).doesNotContain("credentials","evidence_sha256","requested_by_user_id");
    }
    @Test void existingRefundEntryQueueAlsoPreservesItsDeclaredNullableFieldsForTheStrictClient() {
        var request=new GatewayRefundModels.Request(UUID.randomUUID(),payment,"SYN-000001","Synthetic learner",new BigDecimal("50.25"),
                "INR","SUBMITTED","Synthetic reviewed refund",Instant.parse("2026-10-18T00:01:00Z"),"Synthetic maker",false,true,false,
                List.of(new GatewayRefundModels.Allocation(UUID.randomUUID(),"Synthetic original allocation",new BigDecimal("50.25"))),null);
        var value=JSON.valueToTree(new GatewayRefundModels.Queue(school,"SUBMITTED",null,0,25,1,false,false,List.of(request)));
        assertThat(value.get("query").isNull()).isTrue();
        assertThat(value.get("items").get(0).get("decision").isNull()).isTrue();
        assertThat(value.get("executionEnabled").booleanValue()).isFalse();
    }
    private record Probe(String visible,String absent) {}
}

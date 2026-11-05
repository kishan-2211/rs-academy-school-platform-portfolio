package com.educore.api.finance;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Projection only; no provider, database write or financial acceptance. */
class GatewayRefundProcessingTest {
    private static final Instant CHECKED=Instant.parse("2026-10-22T10:00:00Z"),NEXT=CHECKED.plusSeconds(60);

    @Test void nonApprovedIntentNeverClaimsProviderOrFinancialCompletion() throws Exception {
        for(String state:Set.of("SUBMITTED","REJECTED","CANCELLED"))assertThat(project(Map.of("state",state,"dues_restored",true,"success_retained",true))).isNull();
    }
    @Test void untouchedQueuedWorkAndDisabledRetriesAreNotProviderConfirmation() throws Exception {
        for(String work:Set.of("READY","RETRY","LEASED")){
            var result=project(Map.of("work_status",work,"next_check_at",NEXT));
            assertThat(result.state()).isEqualTo("AWAITING_PROCESSING");assertThat(result.lastCheckedAt()).isNull();assertThat(result.requiresReview()).isFalse();
        }
    }
    @Test void dispatchMarkerOrIndependentUnknownReadRemainsPendingNotVerifiedMoney() throws Exception {
        assertThat(project(Map.of("work_status","RETRY","dispatch_recorded",true)).state()).isEqualTo("PENDING_CONFIRMATION");
        var result=project(Map.of("work_status","RETRY","observed_at",CHECKED,"next_check_at",NEXT));
        assertThat(result.state()).isEqualTo("PENDING_CONFIRMATION");assertThat(result.lastCheckedAt()).isEqualTo(CHECKED);assertThat(result.nextCheckAt()).isEqualTo(NEXT);
    }
    @Test void terminalProviderFailureRemainsExplicitAndRequiresReviewWithoutReleasingMoney() throws Exception {
        for(String status:Set.of("FAILED","CANCELLED")){
            var result=project(Map.of("work_status","REVIEW_REQUIRED","read_status",status,"read_outcome","NOT_SUCCESSFUL","observed_at",CHECKED));
            assertThat(result.state()).isEqualTo("PROVIDER_FAILED");assertThat(result.requiresReview()).isTrue();assertThat(result.nextCheckAt()).isNull();
        }
    }
    @Test void contradictoryReadCannotBecomeCompletionEvenWhenWorkIsRetryable() throws Exception {
        var result=project(Map.of("work_status","RETRY","read_outcome","REVIEW_REQUIRED","observed_at",CHECKED));
        assertThat(result.state()).isEqualTo("REVIEW_REQUIRED");assertThat(result.requiresReview()).isTrue();
    }
    @Test void immutableVerifiedAnchorIsSeparateFromFinancialPostingAndRetainsNewReviewWarning() throws Exception {
        var result=project(Map.of("success_retained",true,"read_outcome","REVIEW_REQUIRED","observed_at",CHECKED));
        assertThat(result.state()).isEqualTo("VERIFIED_AWAITING_POSTING");assertThat(result.requiresReview()).isTrue();assertThat(result.nextCheckAt()).isNull();
    }
    @Test void restoredDuesRemainVisibleWithoutAssertingThatCommonAccountingIsPosted() throws Exception {
        var result=project(Map.of("dues_restored",true,"work_status","REVIEW_REQUIRED","observed_at",CHECKED));
        assertThat(result.state()).isEqualTo("DUES_RESTORED");assertThat(result.requiresReview()).isTrue();assertThat(result.nextCheckAt()).isNull();
    }
    @Test void historicalUnqueuedApprovalRequiresReviewRatherThanInventingDispatch() throws Exception {
        var result=project(Map.of());assertThat(result.state()).isEqualTo("NOT_QUEUED");assertThat(result.requiresReview()).isTrue();
    }
    @Test void serializedSummaryHasExplicitNullsAndNoPrivateProviderOrLeaseIdentifiers() {
        var result=new GatewayRefundModels.Processing("AWAITING_PROCESSING",false,null,null);
        var json=JsonMapper.builder().build().valueToTree(result);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("state","requiresReview","lastCheckedAt","nextCheckAt");
        assertThat(json.get("lastCheckedAt").isNull()).isTrue();assertThat(json.get("nextCheckAt").isNull()).isTrue();
        assertThat(result.toString()).doesNotContain("AWAITING_PROCESSING");
    }

    private GatewayRefundModels.Processing project(Map<String,Object> values)throws Exception {
        var row=mock(ResultSet.class);
        when(row.getString(anyString())).thenAnswer(call->{String key=call.getArgument(0);return values.getOrDefault(key,key.equals("state")?"APPROVED":null);});
        when(row.getBoolean(anyString())).thenAnswer(call->Boolean.TRUE.equals(values.get(call.getArgument(0))));
        when(row.getTimestamp(anyString())).thenAnswer(call->{var value=(Instant)values.get(call.getArgument(0));return value==null?null:Timestamp.from(value);});
        return GatewayRefundService.processing(row);
    }
}

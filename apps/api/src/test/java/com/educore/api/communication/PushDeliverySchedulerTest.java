package com.educore.api.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.educore.api.tenancy.TenantRequestContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PushDeliverySchedulerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PushDeliveryDispatch dispatch=mock(PushDeliveryDispatch.class);
    private final PushDeliveryProcessor processor=mock(PushDeliveryProcessor.class);
    private final WebPushDeliveryConfiguration config=mock(WebPushDeliveryConfiguration.class);
    private final UUID org=UUID.randomUUID(),school=UUID.randomUUID();

    @Test
    void disabledDeliveryDoesNotDiscoverSchoolsOrCreateJobs() {
        new PushDeliveryScheduler(jdbc,dispatch,processor,config,Runnable::run).dispatchDuePushes();
        verifyNoInteractions(jdbc,dispatch,processor);
    }

    @Test
    void runsBoundedBatchesInExplicitTenantContextAndRestoresIt() {
        enable();
        when(processor.processNext(org,school)).thenAnswer(call -> {
            assertThat(TenantRequestContext.current()).hasValueSatisfying(scope -> assertThat(scope.organisationId()).isEqualTo(org));
            return UUID.randomUUID();
        });
        new PushDeliveryScheduler(jdbc,dispatch,processor,config,Runnable::run).dispatchDuePushes();
        verify(processor,times(100)).processNext(org,school);
        verify(dispatch,times(100)).materializeNext(org,school);
        verify(dispatch,times(100)).reconcileNext(org,school);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test
    void busyWorkersDoNotAccumulateABacklog() {
        enable();
        var scheduler=new PushDeliveryScheduler(jdbc,dispatch,processor,config,task -> { throw new RejectedExecutionException(); });
        scheduler.dispatchDuePushes();
        scheduler.dispatchDuePushes();
        verify(jdbc,times(2)).query(anyString(),ArgumentMatchers.<RowMapper<PushDeliveryScheduler.School>>any(),eq(new UUID(0,0)));
        verifyNoInteractions(dispatch,processor);
    }

    @Test
    void idleAndFailedSchoolsStopPromptlyWithoutLeakingTenantContext() {
        enable();
        var scheduler=new PushDeliveryScheduler(jdbc,dispatch,processor,config,Runnable::run);
        scheduler.dispatchDuePushes();
        verify(processor,times(4)).processNext(org,school);
        when(dispatch.materializeNext(org,school)).thenThrow(new IllegalStateException("Synthetic private error"));
        scheduler.dispatchDuePushes();
        verify(processor,times(4)).processNext(org,school);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    private void enable() {
        when(config.enabled()).thenReturn(true);
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<PushDeliveryScheduler.School>>any(),any(UUID.class)))
                .thenReturn(List.of(new PushDeliveryScheduler.School(org,school)));
    }
}

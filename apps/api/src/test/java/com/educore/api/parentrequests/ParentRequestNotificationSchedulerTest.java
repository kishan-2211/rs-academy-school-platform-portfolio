package com.educore.api.parentrequests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.educore.api.tenancy.TenantRequestContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ParentRequestNotificationSchedulerTest {
    @Test
    void boundsWorkAndClearsTenantContext() {
        var jdbc=mock(JdbcTemplate.class);
        var processor=mock(ParentRequestNotificationProcessor.class);
        UUID organisation=UUID.randomUUID();
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(organisation));
        when(processor.processNext(organisation)).thenAnswer(call -> {
            assertThat(TenantRequestContext.current()).hasValueSatisfying(s -> assertThat(s.organisationId()).isEqualTo(organisation));
            return UUID.randomUUID();
        });
        new ParentRequestNotificationScheduler(jdbc,processor).publishDueUpdates();
        verify(processor,times(25)).processNext(organisation);
        assertThat(TenantRequestContext.current()).isEmpty();
    }
    @Test
    void recordsSafeFailureAndStopsAtAnEmptyQueue() {
        var jdbc=mock(JdbcTemplate.class);
        var processor=mock(ParentRequestNotificationProcessor.class);
        UUID organisation=UUID.randomUUID(),event=UUID.randomUUID();
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(organisation));
        when(processor.processNext(organisation)).thenThrow(new ParentRequestNotificationProcessor.PublicationException(event)).thenReturn(null);
        new ParentRequestNotificationScheduler(jdbc,processor).publishDueUpdates();
        verify(processor).recordFailure(organisation,event);
        verify(processor,times(2)).processNext(organisation);
        assertThat(TenantRequestContext.current()).isEmpty();
    }
}

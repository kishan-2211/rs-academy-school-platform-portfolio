package com.educore.api.studentleave;

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

class StudentLeaveNotificationSchedulerTest {
    @Test
    void boundsWorkAndScopesEachOrganisation() {
        var jdbc=mock(JdbcTemplate.class);
        var processor=mock(StudentLeaveNotificationProcessor.class);
        var organisations=List.of(UUID.randomUUID(),UUID.randomUUID());
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(organisations);
        for(UUID organisation:organisations) when(processor.processNext(organisation)).thenAnswer(call -> {
            assertThat(TenantRequestContext.current()).hasValueSatisfying(s -> assertThat(s.organisationId()).isEqualTo(organisation));
            return UUID.randomUUID();
        });
        for(UUID organisation:organisations) when(processor.processNextSubmission(organisation)).thenAnswer(call -> {
            assertThat(TenantRequestContext.current()).hasValueSatisfying(s -> assertThat(s.organisationId()).isEqualTo(organisation));
            return UUID.randomUUID();
        });
        new StudentLeaveNotificationScheduler(jdbc,processor).publishDueUpdates();
        for(UUID organisation:organisations) verify(processor,times(25)).processNext(organisation);
        for(UUID organisation:organisations) verify(processor,times(25)).processNextSubmission(organisation);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test
    void recordsSafeFailureThenStopsAtEmptyQueue() {
        var jdbc=mock(JdbcTemplate.class);
        var processor=mock(StudentLeaveNotificationProcessor.class);
        UUID organisation=UUID.randomUUID(),event=UUID.randomUUID();
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(organisation));
        when(processor.processNext(organisation)).thenThrow(new StudentLeaveNotificationProcessor.PublicationException(event)).thenReturn(null);
        new StudentLeaveNotificationScheduler(jdbc,processor).publishDueUpdates();
        verify(processor).recordFailure(organisation,event);
        verify(processor,times(2)).processNext(organisation);
        verify(processor).processNextSubmission(organisation);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test
    void recordsSubmissionFailureWithoutChangingTheDecisionQueueContract() {
        var jdbc=mock(JdbcTemplate.class);
        var processor=mock(StudentLeaveNotificationProcessor.class);
        UUID organisation=UUID.randomUUID(),event=UUID.randomUUID();
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(organisation));
        when(processor.processNextSubmission(organisation))
                .thenThrow(new StudentLeaveNotificationProcessor.PublicationException(event)).thenReturn(null);
        new StudentLeaveNotificationScheduler(jdbc,processor).publishDueUpdates();
        verify(processor).processNext(organisation);
        verify(processor).recordFailure(organisation,event);
        verify(processor,times(2)).processNextSubmission(organisation);
        assertThat(TenantRequestContext.current()).isEmpty();
    }
}

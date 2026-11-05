package com.educore.api.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class LearningPublicationNotificationSchedulerTest {
    @Test
    void boundsEachOrganisationAndClearsContext() {
        var jdbc=mock(JdbcTemplate.class);
        var processor=mock(LearningPublicationNotificationProcessor.class);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(first,second));
        when(processor.processNext(any())).thenAnswer(call -> {
            assertThat(TenantRequestContext.current()).hasValueSatisfying(s ->
                    assertThat(s.organisationId()).isEqualTo(call.getArgument(0)));
            return UUID.randomUUID();
        });
        new LearningPublicationNotificationScheduler(jdbc,processor).publishDueUpdates();
        verify(processor,times(25)).processNext(first);
        verify(processor,times(25)).processNext(second);
        assertThat(TenantRequestContext.current()).isEmpty();
    }
    @Test
    void recordsSafeFailureThenStopsAtEmptyQueue() {
        var jdbc=mock(JdbcTemplate.class);
        var processor=mock(LearningPublicationNotificationProcessor.class);
        UUID organisation=UUID.randomUUID(),event=UUID.randomUUID();
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(organisation));
        when(processor.processNext(organisation)).thenThrow(new LearningPublicationNotificationProcessor.PublicationException(event)).thenReturn(null);
        new LearningPublicationNotificationScheduler(jdbc,processor).publishDueUpdates();
        verify(processor).recordFailure(organisation,event);
        verify(processor,times(2)).processNext(organisation);
        assertThat(TenantRequestContext.current()).isEmpty();
    }
    @Test
    void workerAndSourceDenyMissingOrForeignOrganisationBeforeDatabaseAccess() {
        var jdbc=mock(NamedParameterJdbcTemplate.class);
        var processor=new LearningPublicationNotificationProcessor(jdbc,null,Clock.systemUTC());
        var source=new LearningPublicationNotificationSource(jdbc,Clock.systemUTC());
        UUID organisation=UUID.randomUUID(),school=UUID.randomUUID(),event=UUID.randomUUID();
        assertThatThrownBy(()->processor.processNext(organisation)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->processor.recordFailure(organisation,event)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->source.current(organisation,school,event)).isInstanceOf(IllegalStateException.class);
        try(var ignored=TenantRequestContext.open(null,UUID.randomUUID())) {
            assertThatThrownBy(()->source.recipients(organisation,school,event)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->source.isCurrentGuardian(organisation,school,event,null,null,null)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(jdbc);
    }
}

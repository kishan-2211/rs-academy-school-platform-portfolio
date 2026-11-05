package com.educore.api.ptm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.educore.api.tenancy.TenantRequestContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PtmReminderSchedulerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PtmReminderProcessor reminders=mock(PtmReminderProcessor.class);
    private final PtmNotificationProcessor notifications=mock(PtmNotificationProcessor.class);
    private final UUID organisation=UUID.randomUUID();

    @Test
    void boundsBothStagesAndClosesTheirOrganisationContext() {
        discover();
        when(reminders.processNext(organisation)).thenReturn(UUID.randomUUID());
        when(notifications.processNext(organisation)).thenAnswer(call -> {
            assertThat(TenantRequestContext.current()).hasValueSatisfying(s -> assertThat(s.organisationId()).isEqualTo(organisation));
            return UUID.randomUUID();
        });
        new PtmReminderScheduler(jdbc,reminders,notifications).materializeDueReminders();
        verify(reminders,times(25)).processNext(organisation);
        verify(notifications,times(25)).processNext(organisation);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test
    void drainsExistingEventsEvenWhenNoNewRemindersAreDue() {
        discover();
        when(notifications.processNext(organisation)).thenReturn(UUID.randomUUID(),(UUID)null);
        new PtmReminderScheduler(jdbc,reminders,notifications).materializeDueReminders();
        verify(reminders).processNext(organisation);
        verify(notifications,times(2)).processNext(organisation);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test
    void recordsFailedPublicationForRetryWithoutInventingDeliverySuccess() {
        discover(); UUID event=UUID.randomUUID();
        when(notifications.processNext(organisation)).thenThrow(new PtmNotificationProcessor.PublicationException(event)).thenReturn(null);
        new PtmReminderScheduler(jdbc,reminders,notifications).materializeDueReminders();
        verify(notifications).recordFailure(organisation,event);
        assertThat(new PtmNotificationProcessor.PublicationException(event).getCause()).isNull();
        assertThat(TenantRequestContext.current()).isEmpty();
    }
    private void discover() {
        when(jdbc.query(anyString(),ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(organisation));
    }
}

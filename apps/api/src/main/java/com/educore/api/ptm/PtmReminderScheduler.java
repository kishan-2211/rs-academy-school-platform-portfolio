package com.educore.api.ptm;

import com.educore.api.tenancy.TenantRequestContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.ptm.reminders.enabled", havingValue="true", matchIfMissing=true)
public class PtmReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(PtmReminderScheduler.class);
    private final JdbcTemplate jdbc;
    private final PtmReminderProcessor processor;
    private final PtmNotificationProcessor notifications;
    public PtmReminderScheduler(JdbcTemplate jdbc, PtmReminderProcessor processor, PtmNotificationProcessor notifications) {
        this.jdbc = jdbc;
        this.processor = processor;
        this.notifications = notifications;
    }
    @Scheduled(fixedDelayString="${app.ptm.reminders.poll-delay-ms:60000}",
            initialDelayString="${app.ptm.reminders.initial-delay-ms:30000}")
    public void materializeDueReminders() {
        for (UUID organisationId : jdbc.query("SELECT id FROM organisations WHERE status='ACTIVE' ORDER BY id",
                (r, n) -> r.getObject(1, UUID.class))) {
            try (TenantRequestContext.Scope ignored = TenantRequestContext.open(null, organisationId)) {
                for (int index = 0; index < 25; index++) {
                    try {
                        if (processor.processNext(organisationId) == null) break;
                    } catch (PtmReminderProcessor.ReminderProcessingException exception) {
                        processor.recordFailure(organisationId, exception.jobId());
                        log.warn("PTM reminder could not be materialized; retry evidence recorded");
                    }
                }
                for (int index = 0; index < 25; index++) {
                    try {
                        if (notifications.processNext(organisationId) == null) break;
                    } catch (PtmNotificationProcessor.PublicationException exception) {
                        notifications.recordFailure(organisationId, exception.eventId());
                        log.warn("PTM notification could not be published; retry evidence recorded");
                    }
                }
            }
        }
    }
}

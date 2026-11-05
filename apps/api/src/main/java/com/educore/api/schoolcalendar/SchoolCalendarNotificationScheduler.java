package com.educore.api.schoolcalendar;

import com.educore.api.schoolcalendar.SchoolCalendarNotificationProcessor.CalendarNotificationProcessingException;
import com.educore.api.tenancy.TenantRequestContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.calendar.notifications.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SchoolCalendarNotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(SchoolCalendarNotificationScheduler.class);
    private static final int MAX_JOBS_PER_ORGANISATION = 25;

    private final JdbcTemplate jdbc;
    private final SchoolCalendarNotificationProcessor processor;

    public SchoolCalendarNotificationScheduler(
            JdbcTemplate jdbc,
            SchoolCalendarNotificationProcessor processor) {
        this.jdbc = jdbc;
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString = "${app.calendar.notifications.poll-delay-ms:60000}",
            initialDelayString = "${app.calendar.notifications.initial-delay-ms:30000}")
    public void materializeDueNotices() {
        List<UUID> organisations = jdbc.query(
                "SELECT id FROM organisations WHERE status='ACTIVE' ORDER BY id",
                (result, row) -> result.getObject("id", UUID.class));
        for (UUID organisationId : organisations) {
            try (TenantRequestContext.Scope ignored = TenantRequestContext.open(null, organisationId)) {
                for (int index = 0; index < MAX_JOBS_PER_ORGANISATION; index++) {
                    try {
                        if (processor.processNext(organisationId) == null) break;
                    } catch (CalendarNotificationProcessingException exception) {
                        processor.recordFailure(organisationId, exception.jobId());
                        log.warn("Calendar notification job {} could not be materialized; retry evidence recorded",
                                exception.jobId());
                    }
                }
            }
        }
    }
}

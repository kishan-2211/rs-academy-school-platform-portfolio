package com.educore.api.studentleave;

import com.educore.api.tenancy.TenantRequestContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.student-leave.notifications.enabled",havingValue="true",matchIfMissing=true)
public class StudentLeaveNotificationScheduler {
    private static final Logger log=LoggerFactory.getLogger(StudentLeaveNotificationScheduler.class);
    private final JdbcTemplate jdbc;
    private final StudentLeaveNotificationProcessor processor;
    public StudentLeaveNotificationScheduler(JdbcTemplate jdbc,StudentLeaveNotificationProcessor processor) {
        this.jdbc=jdbc; this.processor=processor;
    }
    @Scheduled(fixedDelayString="${app.student-leave.notifications.poll-delay-ms:60000}",
            initialDelayString="${app.student-leave.notifications.initial-delay-ms:30000}")
    public void publishDueUpdates() {
        for(UUID organisationId:jdbc.query("SELECT id FROM organisations WHERE status='ACTIVE' ORDER BY id",
                (row,index)->row.getObject(1,UUID.class))) {
            try(TenantRequestContext.Scope ignored=TenantRequestContext.open(null,organisationId)) {
                for(int index=0;index<25;index++) {
                    try { if(processor.processNext(organisationId)==null) break; }
                    catch(StudentLeaveNotificationProcessor.PublicationException failure) {
                        processor.recordFailure(organisationId,failure.eventId());
                        log.warn("Student leave notification could not be published; retry evidence recorded");
                    }
                }
                for(int index=0;index<25;index++) {
                    try { if(processor.processNextSubmission(organisationId)==null) break; }
                    catch(StudentLeaveNotificationProcessor.PublicationException failure) {
                        processor.recordFailure(organisationId,failure.eventId());
                        log.warn("Student leave reviewer alert could not be published; retry evidence recorded");
                    }
                }
            }
        }
    }
}

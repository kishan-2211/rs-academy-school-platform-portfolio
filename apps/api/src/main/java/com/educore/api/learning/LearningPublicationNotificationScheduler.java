package com.educore.api.learning;

import com.educore.api.tenancy.TenantRequestContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.learning.notifications.enabled",havingValue="true",matchIfMissing=true)
public class LearningPublicationNotificationScheduler {
    private static final Logger log=LoggerFactory.getLogger(LearningPublicationNotificationScheduler.class);
    private final JdbcTemplate jdbc;
    private final LearningPublicationNotificationProcessor processor;
    public LearningPublicationNotificationScheduler(JdbcTemplate jdbc,LearningPublicationNotificationProcessor processor) {
        this.jdbc=jdbc; this.processor=processor;
    }
    @Scheduled(fixedDelayString="${app.learning.notifications.poll-delay-ms:60000}",
            initialDelayString="${app.learning.notifications.initial-delay-ms:30000}")
    public void publishDueUpdates() {
        for(UUID organisationId:jdbc.query("SELECT id FROM organisations WHERE status='ACTIVE' ORDER BY id",
                (r,n) -> r.getObject(1,UUID.class))) {
            try(TenantRequestContext.Scope ignored=TenantRequestContext.open(null,organisationId)) {
                for(int index=0;index<25;index++) {
                    try { if(processor.processNext(organisationId)==null) break; }
                    catch(LearningPublicationNotificationProcessor.PublicationException failure) {
                        processor.recordFailure(organisationId,failure.eventId());
                        log.warn("Learning notification could not be published; retry evidence recorded");
                    }
                }
            }
        }
    }
}

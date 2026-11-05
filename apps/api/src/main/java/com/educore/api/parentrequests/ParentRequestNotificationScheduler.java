package com.educore.api.parentrequests;

import com.educore.api.tenancy.TenantRequestContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.parent-requests.notifications.enabled",havingValue="true",matchIfMissing=true)
public class ParentRequestNotificationScheduler {
    private static final Logger log=LoggerFactory.getLogger(ParentRequestNotificationScheduler.class);
    private final JdbcTemplate jdbc;
    private final ParentRequestNotificationProcessor processor;
    public ParentRequestNotificationScheduler(JdbcTemplate jdbc,ParentRequestNotificationProcessor processor) {
        this.jdbc=jdbc; this.processor=processor;
    }
    @Scheduled(fixedDelayString="${app.parent-requests.notifications.poll-delay-ms:60000}",
            initialDelayString="${app.parent-requests.notifications.initial-delay-ms:30000}")
    public void publishDueUpdates() {
        for(UUID organisationId:jdbc.query("SELECT id FROM organisations WHERE status='ACTIVE' ORDER BY id",
                (r,n) -> r.getObject(1,UUID.class))) {
            try(TenantRequestContext.Scope ignored=TenantRequestContext.open(null,organisationId)) {
                for(int index=0;index<25;index++) {
                    try { if(processor.processNext(organisationId)==null) break; }
                    catch(ParentRequestNotificationProcessor.PublicationException failure) {
                        processor.recordFailure(organisationId,failure.eventId());
                        log.warn("Request notification could not be published; retry evidence recorded");
                    }
                }
            }
        }
    }
}

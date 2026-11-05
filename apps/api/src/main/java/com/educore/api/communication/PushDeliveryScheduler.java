package com.educore.api.communication;

import com.educore.api.tenancy.TenantRequestContext;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.push.delivery.worker-enabled",havingValue="true")
public class PushDeliveryScheduler {
    private static final Logger log=LoggerFactory.getLogger(PushDeliveryScheduler.class);
    private static final UUID START=new UUID(0,0);
    private static final int WORKERS=4;
    private static final int MAX_STEPS=25;
    private final JdbcTemplate jdbc;
    private final PushDeliveryDispatch dispatch;
    private final PushDeliveryProcessor processor;
    private final WebPushDeliveryConfiguration configuration;
    private final Executor executor;
    private UUID cursor=START;

    @Autowired
    public PushDeliveryScheduler(JdbcTemplate jdbc, PushDeliveryDispatch dispatch, PushDeliveryProcessor processor,
            WebPushDeliveryConfiguration configuration) {
        this(jdbc,dispatch,processor,configuration,new ThreadPoolExecutor(WORKERS,WORKERS,0,TimeUnit.SECONDS,
                new SynchronousQueue<>(),task -> {
                    Thread thread=new Thread(task,"school-push-delivery");
                    thread.setDaemon(true);
                    return thread;
                },new ThreadPoolExecutor.AbortPolicy()));
    }

    PushDeliveryScheduler(JdbcTemplate jdbc, PushDeliveryDispatch dispatch, PushDeliveryProcessor processor,
            WebPushDeliveryConfiguration configuration, Executor executor) {
        this.jdbc=jdbc;
        this.dispatch=dispatch;
        this.processor=processor;
        this.configuration=configuration;
        this.executor=executor;
    }

    @Scheduled(fixedDelayString="${app.push.delivery.poll-delay-ms:15000}",
            initialDelayString="${app.push.delivery.initial-delay-ms:30000}")
    public void dispatchDuePushes() {
        if(!configuration.enabled()) return;
        List<School> schools=schoolsAfter(cursor);
        if(schools.isEmpty() && !cursor.equals(START)) schools=schoolsAfter(START);
        if(schools.isEmpty()) { cursor=START; return; }
        // At most four schools per scan; rotate through larger deployments without an unbounded list.
        for(int slot=0;slot<WORKERS;slot++) {
            School school=schools.get(slot%schools.size());
            try {
                executor.execute(() -> processSchool(school));
                cursor=school.schoolId(); // Busy workers must not advance past work they did not accept.
            }
            catch(RejectedExecutionException busy) { break; } // No waiting/unbounded executor queue.
        }
    }

    private List<School> schoolsAfter(UUID after) {
        return jdbc.query("""
                SELECT school.organisation_id,school.id FROM schools school
                JOIN organisations organisation ON organisation.id=school.organisation_id
                WHERE school.status='ACTIVE' AND organisation.status='ACTIVE' AND school.id>?
                ORDER BY school.id LIMIT 4
                """,(r,n) -> new School(r.getObject(1,UUID.class),r.getObject(2,UUID.class)),after);
    }

    private void processSchool(School school) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        try(var ignored=TenantRequestContext.open(null,school.organisationId())) {
            for(int step=0;step<MAX_STEPS && System.nanoTime()<deadline && !Thread.currentThread().isInterrupted();step++) {
                UUID materialized=dispatch.materializeNext(school.organisationId(),school.schoolId());
                UUID processed=processor.processNext(school.organisationId(),school.schoolId());
                UUID reconciled=dispatch.reconcileNext(school.organisationId(),school.schoolId());
                if(materialized==null && processed==null && reconciled==null) break;
            }
        } catch(RuntimeException failure) {
            // Exceptions may contain SQL parameters or encrypted/device values. Persisted leases drive recovery.
            log.warn("Push batch stopped for school {}; pending work and lease recovery remain available",school.schoolId());
        }
    }

    @PreDestroy
    public void close() {
        if(executor instanceof ExecutorService service) service.shutdownNow();
    }
    record School(UUID organisationId,UUID schoolId) {}
}

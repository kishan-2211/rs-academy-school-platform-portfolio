package com.educore.api.communication;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persist the provider outcome and any resulting device erasure atomically, outside the HTTP call. */
@Service
public class PushDeliveryCompletion {
    private final PushDeliveryQueue queue;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public PushDeliveryCompletion(PushDeliveryQueue queue, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.queue = queue;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public boolean complete(PushDeliveryQueue.Claim claim, PushTransport.Result result, long subscriptionVersion) {
        // The queue validates tenant context, result classification and current lease, and locks its job.
        if (!queue.complete(claim, result)) return false;
        if (result.outcome() != PushTransport.Outcome.SUBSCRIPTION_EXPIRED || subscriptionVersion < 0) return true;
        var parameters = new MapSqlParameterSource("organisationId", claim.organisationId())
                .addValue("schoolId", claim.schoolId()).addValue("subscriptionId", claim.subscriptionId())
                .addValue("jobId", claim.jobId()).addValue("attemptId", claim.token())
                .addValue("version", subscriptionVersion).addValue("now", clock.instant().truncatedTo(ChronoUnit.MICROS));
        // A refresh while the provider call was in flight must not erase a newly confirmed subscription.
        int expired = jdbc.update("""
                UPDATE communication_push_subscriptions subscription SET status='EXPIRED',
                  endpoint_ciphertext=NULL,endpoint_nonce=NULL,p256dh_ciphertext=NULL,p256dh_nonce=NULL,
                  auth_ciphertext=NULL,auth_nonce=NULL,revoked_at=:now,
                  revoked_reason='Push provider reported an expired subscription',row_version=row_version+1
                WHERE subscription.id=:subscriptionId AND subscription.organisation_id=:organisationId
                  AND subscription.school_id=:schoolId AND subscription.status='ACTIVE' AND subscription.row_version=:version
                  AND EXISTS (SELECT 1 FROM communication_push_attempts attempt
                    JOIN communication_push_jobs job ON job.id=attempt.communication_push_job_id
                      AND job.organisation_id=attempt.organisation_id AND job.school_id=attempt.school_id
                    WHERE attempt.id=:attemptId AND attempt.organisation_id=:organisationId AND attempt.school_id=:schoolId
                      AND job.id=:jobId AND job.communication_push_subscription_id=subscription.id
                      AND job.status='FAILED' AND attempt.outcome='SUBSCRIPTION_EXPIRED' AND attempt.http_status IN (404,410)
                      AND subscription.last_confirmed_at<=attempt.started_at)
                """, parameters);
        if (expired == 1) {
            jdbc.update("""
                    INSERT INTO communication_push_subscription_events(id,organisation_id,school_id,
                      communication_push_subscription_id,event_type,actor_kind,provider_attempt_id,subscription_version,
                      occurred_at,safe_reason)
                    VALUES(:id,:organisationId,:schoolId,:subscriptionId,'EXPIRED','SYSTEM',:attemptId,:version,:now,
                      'Push provider reported an expired subscription')
                    """, parameters.addValue("id", UUID.randomUUID()));
        }
        return true;
    }
}

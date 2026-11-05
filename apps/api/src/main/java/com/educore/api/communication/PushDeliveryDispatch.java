package com.educore.api.communication;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded parent-ledger work; device results remain authoritative and no network runs in these transactions. */
@Service
public class PushDeliveryDispatch {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public PushDeliveryDispatch(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc=jdbc;
        this.clock=clock;
    }

    @Transactional
    public UUID materializeNext(UUID organisationId, UUID schoolId) {
        var p=scope(organisationId,schoolId);
        UUID activated=activateAwaiting(p);
        var parent=jdbc.query("""
                SELECT delivery.id,recipient.user_account_id,
                  least(coalesce(notice.expires_at,notice.scheduled_for+interval '1 day'),notice.scheduled_for+interval '1 day') expires_at
                FROM communication_deliveries delivery
                JOIN communication_recipients recipient ON recipient.id=delivery.communication_recipient_id
                  AND recipient.organisation_id=delivery.organisation_id AND recipient.school_id=delivery.school_id
                JOIN communication_announcements notice ON notice.id=recipient.communication_announcement_id
                  AND notice.organisation_id=delivery.organisation_id AND notice.school_id=delivery.school_id
                JOIN communication_provider_configs provider ON provider.id=delivery.provider_config_id
                  AND provider.organisation_id=delivery.organisation_id AND provider.school_id=delivery.school_id
                WHERE delivery.organisation_id=:organisationId AND delivery.school_id=:schoolId
                  AND delivery.channel='PUSH' AND delivery.status='READY' AND recipient.contact_eligible
                  AND recipient.available_at<=:now AND notice.status='APPROVED' AND notice.request_push
                  AND notice.scheduled_for<=:now AND notice.scheduled_for+interval '1 day'>:now
                  AND (notice.expires_at IS NULL OR notice.expires_at>:now)
                  AND provider.channel='PUSH' AND provider.status='ACTIVE' AND provider.environment='LIVE'
                  AND provider.provider_code='WEB_PUSH' AND provider.credential_secret_reference='env:APP_PUSH_DELIVERY_PRIVATE_KEY'
                  AND (EXISTS (SELECT 1 FROM communication_push_subscriptions device
                    WHERE device.organisation_id=:organisationId AND device.school_id=:schoolId
                      AND device.user_account_id=recipient.user_account_id AND device.status='ACTIVE')
                    OR EXISTS (SELECT 1 FROM communication_push_jobs job WHERE job.communication_delivery_id=delivery.id
                      AND job.organisation_id=:organisationId AND job.school_id=:schoolId))
                ORDER BY (notice.priority='EMERGENCY') DESC,notice.scheduled_for,delivery.id
                FOR UPDATE OF delivery SKIP LOCKED LIMIT 1
                """,p,r -> r.next()?new Parent(r.getObject("id",UUID.class),r.getObject("user_account_id",UUID.class),
                        r.getTimestamp("expires_at").toInstant()):null);
        if(parent==null) return activated;
        p.addValue("deliveryId",parent.id()).addValue("userId",parent.userId()).addValue("expiresAt",parent.expiresAt());
        // Freeze the current device set in one database statement. Never append devices to an existing batch.
        jdbc.update("""
                INSERT INTO communication_push_jobs(id,organisation_id,school_id,communication_delivery_id,
                  communication_push_subscription_id,next_attempt_at,expires_at,created_at,updated_at)
                SELECT gen_random_uuid(),:organisationId,:schoolId,:deliveryId,device.id,:now,:expiresAt,:now,:now
                FROM communication_push_subscriptions device
                WHERE device.organisation_id=:organisationId AND device.school_id=:schoolId
                  AND device.user_account_id=:userId AND device.status='ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM communication_push_jobs job WHERE job.communication_delivery_id=:deliveryId
                    AND job.organisation_id=:organisationId AND job.school_id=:schoolId)
                ON CONFLICT (communication_delivery_id,communication_push_subscription_id) DO NOTHING
                """,p);
        int changed=jdbc.update("""
                UPDATE communication_deliveries SET status='QUEUED',queued_at=coalesce(queued_at,:now),
                  updated_at=:now,row_version=row_version+1
                WHERE id=:deliveryId AND organisation_id=:organisationId AND school_id=:schoolId AND status='READY'
                  AND EXISTS (SELECT 1 FROM communication_push_jobs job WHERE job.communication_delivery_id=:deliveryId
                    AND job.organisation_id=:organisationId AND job.school_id=:schoolId)
                """,p);
        return changed==1?parent.id():activated;
    }

    /** Bind only never-assigned deliveries. Existing TEST/LIVE provider history is not rewritten. */
    private UUID activateAwaiting(MapSqlParameterSource p) {
        return jdbc.query("""
                WITH eligible AS (
                  SELECT delivery.id,provider.id provider_id FROM communication_deliveries delivery
                  JOIN communication_recipients recipient ON recipient.id=delivery.communication_recipient_id
                    AND recipient.organisation_id=delivery.organisation_id AND recipient.school_id=delivery.school_id
                  JOIN communication_announcements notice ON notice.id=recipient.communication_announcement_id
                    AND notice.organisation_id=delivery.organisation_id AND notice.school_id=delivery.school_id
                  JOIN communication_provider_configs provider ON provider.organisation_id=delivery.organisation_id
                    AND provider.school_id=delivery.school_id AND provider.channel='PUSH' AND provider.environment='LIVE'
                    AND provider.status='ACTIVE' AND provider.provider_code='WEB_PUSH'
                    AND provider.credential_secret_reference='env:APP_PUSH_DELIVERY_PRIVATE_KEY'
                  WHERE delivery.organisation_id=:organisationId AND delivery.school_id=:schoolId
                    AND delivery.channel='PUSH' AND delivery.status='AWAITING_PROVIDER' AND delivery.provider_config_id IS NULL
                    AND recipient.contact_eligible AND recipient.available_at<=:now
                    AND notice.status='APPROVED' AND notice.request_push AND notice.scheduled_for<=:now
                    AND notice.scheduled_for+interval '1 day'>:now AND (notice.expires_at IS NULL OR notice.expires_at>:now)
                  ORDER BY (notice.priority='EMERGENCY') DESC,notice.scheduled_for,delivery.id
                  FOR UPDATE OF delivery SKIP LOCKED LIMIT 1)
                UPDATE communication_deliveries delivery SET provider_config_id=eligible.provider_id,status='READY',
                  updated_at=:now,row_version=row_version+1 FROM eligible WHERE delivery.id=eligible.id RETURNING delivery.id
                """,p,r -> r.next()?r.getObject(1,UUID.class):null);
    }

    @Transactional
    public UUID reconcileNext(UUID organisationId, UUID schoolId) {
        var p=scope(organisationId,schoolId);
        var state=jdbc.query("""
                SELECT delivery.id,computed.next_status,computed.error_code,totals.first_accepted_at
                FROM communication_deliveries delivery
                CROSS JOIN LATERAL (
                  SELECT count(*) FILTER (WHERE job.status='ACCEPTED') accepted,
                    count(*) FILTER (WHERE job.status IN ('READY','RETRY','LEASED')) pending,
                    count(*) FILTER (WHERE job.status='FAILED') failed,count(*) total,
                    min(job.completed_at) FILTER (WHERE job.status='ACCEPTED') first_accepted_at
                  FROM communication_push_jobs job WHERE job.communication_delivery_id=delivery.id
                    AND job.organisation_id=delivery.organisation_id AND job.school_id=delivery.school_id
                ) totals
                CROSS JOIN LATERAL (
                  SELECT CASE WHEN totals.accepted>0 THEN 'SENT' WHEN totals.pending>0 THEN 'QUEUED'
                    WHEN totals.failed>0 THEN 'FAILED' ELSE 'CANCELLED' END next_status,
                    CASE WHEN totals.accepted>0 AND totals.failed>0 THEN 'PUSH_PARTIAL_FAILURE'
                      WHEN totals.pending=0 AND totals.accepted=0 AND totals.failed>0 THEN 'PUSH_ALL_DEVICES_FAILED'
                      WHEN totals.pending=0 AND totals.accepted=0 AND totals.failed=0 THEN 'PUSH_ALL_DEVICES_CANCELLED'
                      ELSE NULL END error_code
                ) computed
                WHERE delivery.organisation_id=:organisationId AND delivery.school_id=:schoolId
                  AND delivery.channel='PUSH' AND delivery.status IN ('QUEUED','SENT') AND totals.total>0
                  AND (delivery.status IS DISTINCT FROM computed.next_status OR delivery.last_error_code IS DISTINCT FROM computed.error_code)
                ORDER BY delivery.updated_at,delivery.id FOR UPDATE OF delivery SKIP LOCKED LIMIT 1
                """,p,r -> r.next()?new State(r.getObject("id",UUID.class),r.getString("next_status"),r.getString("error_code"),
                        r.getTimestamp("first_accepted_at")==null?null:r.getTimestamp("first_accepted_at").toInstant()):null);
        if(state!=null) {
            jdbc.update("""
                    UPDATE communication_deliveries SET status=:status,last_error_code=:error,
                      sent_at=coalesce(sent_at,:acceptedAt),
                      failed_at=CASE WHEN :status='FAILED' THEN coalesce(failed_at,:now) ELSE failed_at END,
                      updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                    """,p.addValue("id",state.id()).addValue("status",state.status()).addValue("error",state.error())
                    .addValue("acceptedAt",state.acceptedAt()));
            return state.id();
        }
        // No phone was contacted for an expired or withdrawn notice that never obtained a device batch.
        return jdbc.query("""
                WITH expired AS (
                  SELECT delivery.id FROM communication_deliveries delivery
                  JOIN communication_recipients recipient ON recipient.id=delivery.communication_recipient_id
                    AND recipient.organisation_id=delivery.organisation_id AND recipient.school_id=delivery.school_id
                  JOIN communication_announcements notice ON notice.id=recipient.communication_announcement_id
                    AND notice.organisation_id=delivery.organisation_id AND notice.school_id=delivery.school_id
                  WHERE delivery.organisation_id=:organisationId AND delivery.school_id=:schoolId
                    AND delivery.channel='PUSH' AND delivery.status IN ('READY','AWAITING_PROVIDER')
                    AND (notice.status<>'APPROVED' OR notice.expires_at<=:now OR notice.scheduled_for+interval '1 day'<=:now)
                    AND NOT EXISTS (SELECT 1 FROM communication_push_jobs job WHERE job.communication_delivery_id=delivery.id
                      AND job.organisation_id=:organisationId AND job.school_id=:schoolId)
                  ORDER BY delivery.updated_at,delivery.id FOR UPDATE OF delivery SKIP LOCKED LIMIT 1)
                UPDATE communication_deliveries delivery SET status='CANCELLED',last_error_code='PUSH_SOURCE_UNAVAILABLE',
                  updated_at=:now,row_version=row_version+1 FROM expired WHERE delivery.id=expired.id RETURNING delivery.id
                """,p,r -> r.next()?r.getObject(1,UUID.class):null);
    }

    private MapSqlParameterSource scope(UUID organisationId, UUID schoolId) {
        if (!TenantRequestContext.current().map(s -> organisationId.equals(s.organisationId())).orElse(false))
            throw new IllegalStateException("Push dispatch requires a matching organisation context");
        return new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId)
                .addValue("now",clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
    private record Parent(UUID id,UUID userId,Instant expiresAt) {}
    private record State(UUID id,String status,String error,Instant acceptedAt) {}
}

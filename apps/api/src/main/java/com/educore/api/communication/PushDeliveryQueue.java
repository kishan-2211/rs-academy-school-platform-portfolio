package com.educore.api.communication;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal persistence boundary. A claim is NOT permission to send to its recipient. */
@Service
public class PushDeliveryQueue {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public PushDeliveryQueue(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public UUID enqueue(UUID organisationId, UUID schoolId, UUID deliveryId, UUID subscriptionId,
            Instant expiresAt) {
        MapSqlParameterSource p = scope(organisationId, schoolId)
                .addValue("deliveryId", Objects.requireNonNull(deliveryId))
                .addValue("subscriptionId", Objects.requireNonNull(subscriptionId))
                .addValue("id", UUID.randomUUID());
        Instant now = now();
        if (expiresAt != null) expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(1, ChronoUnit.DAYS))) {
            throw new IllegalArgumentException("Push expiry must be within the next day");
        }
        p.addValue("now", now).addValue("expiresAt", expiresAt);
        // The source stays the existing independently approved communication. Do not create another inbox.
        List<UUID> inserted = jdbc.query("""
                INSERT INTO communication_push_jobs(id,organisation_id,school_id,communication_delivery_id,
                  communication_push_subscription_id,next_attempt_at,expires_at,created_at,updated_at)
                SELECT :id,:organisationId,:schoolId,delivery.id,subscription.id,
                  greatest(:now,recipient.available_at),least(:expiresAt,coalesce(notice.expires_at,:expiresAt)),:now,:now
                FROM communication_deliveries delivery
                JOIN communication_recipients recipient ON recipient.id=delivery.communication_recipient_id
                  AND recipient.organisation_id=delivery.organisation_id AND recipient.school_id=delivery.school_id
                JOIN communication_announcements notice ON notice.id=recipient.communication_announcement_id
                  AND notice.organisation_id=recipient.organisation_id AND notice.school_id=recipient.school_id
                JOIN communication_provider_configs provider ON provider.id=delivery.provider_config_id
                  AND provider.organisation_id=delivery.organisation_id AND provider.school_id=delivery.school_id
                JOIN communication_push_subscriptions subscription ON subscription.id=:subscriptionId
                  AND subscription.organisation_id=recipient.organisation_id AND subscription.school_id=recipient.school_id
                  AND subscription.user_account_id=recipient.user_account_id
                WHERE delivery.id=:deliveryId AND delivery.organisation_id=:organisationId AND delivery.school_id=:schoolId
                  AND delivery.channel='PUSH' AND delivery.status IN ('READY','QUEUED')
                  AND recipient.contact_eligible AND subscription.status='ACTIVE'
                  AND notice.status='APPROVED' AND notice.request_push AND notice.scheduled_for<=:now
                  AND provider.channel='PUSH' AND provider.status='ACTIVE'
                  AND (notice.expires_at IS NULL OR notice.expires_at>:now)
                  AND recipient.available_at<least(:expiresAt,coalesce(notice.expires_at,:expiresAt))
                ON CONFLICT (communication_delivery_id,communication_push_subscription_id) DO NOTHING RETURNING id
                """, p, (r, n) -> r.getObject(1, UUID.class));
        if (!inserted.isEmpty()) return inserted.getFirst();
        // Replays return the original job even if its parent/device has since become terminal.
        return jdbc.query("""
                SELECT id FROM communication_push_jobs WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND communication_delivery_id=:deliveryId AND communication_push_subscription_id=:subscriptionId
                """, p, r -> r.next() ? r.getObject(1, UUID.class) : null);
    }

    @Transactional
    public Claim claimNext(UUID organisationId, UUID schoolId) {
        Instant now = now();
        MapSqlParameterSource p = scope(organisationId, schoolId).addValue("now", now)
                .addValue("token", UUID.randomUUID()).addValue("leaseUntil", now.plusSeconds(90));
        return jdbc.query("""
                WITH due AS (
                  SELECT id FROM communication_push_jobs WHERE organisation_id=:organisationId AND school_id=:schoolId
                    AND status IN ('READY','RETRY') AND next_attempt_at<=:now AND expires_at>:now AND attempt_count<8
                  ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE communication_push_jobs job SET status='LEASED',lease_token=:token,lease_started_at=:now,
                  lease_until=:leaseUntil,attempt_count=attempt_count+1,updated_at=:now,row_version=row_version+1
                FROM due WHERE job.id=due.id
                RETURNING job.id,job.communication_delivery_id,job.communication_push_subscription_id,
                  job.lease_token,job.lease_started_at,job.lease_until,job.expires_at,job.attempt_count
                """, p, r -> r.next() ? new Claim(organisationId, schoolId, r.getObject("id", UUID.class),
                r.getObject("communication_delivery_id", UUID.class), r.getObject("communication_push_subscription_id", UUID.class),
                r.getObject("lease_token", UUID.class), r.getTimestamp("lease_started_at").toInstant(),
                r.getTimestamp("lease_until").toInstant(), r.getTimestamp("expires_at").toInstant(),
                r.getInt("attempt_count")) : null);
    }

    @Transactional
    public boolean complete(Claim claim, PushTransport.Result result) {
        Objects.requireNonNull(result);
        validateResult(result);
        return finish(claim, result.outcome().name(), result.httpStatus(), result.safeCode(), result.retryAfterSeconds(), false);
    }

    @Transactional
    public boolean cancel(Claim claim, String safeCode) {
        return finish(claim, "CANCELLED", null, safeCode, 0, false);
    }

    @Transactional
    public UUID recoverExpiredLease(UUID organisationId, UUID schoolId) {
        MapSqlParameterSource p = scope(organisationId, schoolId).addValue("now", now());
        Claim claim = jdbc.query("""
                SELECT * FROM communication_push_jobs WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='LEASED' AND lease_until<=:now ORDER BY lease_until,id FOR UPDATE SKIP LOCKED LIMIT 1
                """, p, r -> r.next() ? new Claim(organisationId, schoolId, r.getObject("id", UUID.class),
                r.getObject("communication_delivery_id", UUID.class), r.getObject("communication_push_subscription_id", UUID.class),
                r.getObject("lease_token", UUID.class), r.getTimestamp("lease_started_at").toInstant(),
                r.getTimestamp("lease_until").toInstant(), r.getTimestamp("expires_at").toInstant(),
                r.getInt("attempt_count")) : null);
        if (claim == null) return null;
        finish(claim, "LEASE_EXPIRED", null, "PUSH_LEASE_OUTCOME_UNKNOWN", 60, true);
        return claim.jobId();
    }

    @Transactional
    public UUID expirePending(UUID organisationId, UUID schoolId) {
        return jdbc.query("""
                WITH due AS (SELECT id FROM communication_push_jobs
                  WHERE organisation_id=:organisationId AND school_id=:schoolId
                    AND status IN ('READY','RETRY') AND expires_at<=:now
                  ORDER BY expires_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE communication_push_jobs job SET status='CANCELLED',completed_at=:now,
                  last_error_code='PUSH_MESSAGE_EXPIRED',updated_at=:now,row_version=row_version+1
                FROM due WHERE job.id=due.id RETURNING job.id
                """, scope(organisationId, schoolId).addValue("now", now()),
                r -> r.next() ? r.getObject(1, UUID.class) : null);
    }

    private boolean finish(Claim claim, String outcome, Integer httpStatus, String safeCode, long retryAfter,
            boolean recovery) {
        Objects.requireNonNull(claim);
        if (safeCode == null || !safeCode.matches("[A-Z][A-Z0-9_]{2,99}")) {
            throw new IllegalArgumentException("A destination-free push result code is required");
        }
        Instant now = now();
        MapSqlParameterSource p = scope(claim.organisationId(), claim.schoolId())
                .addValue("id", claim.jobId()).addValue("token", claim.token()).addValue("now", now);
        // Fenced completion: a timed-out sender cannot overwrite a recovered/newer lease.
        StoredLease lease = jdbc.query("""
                SELECT attempt_count,lease_started_at,lease_until,expires_at FROM communication_push_jobs
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='LEASED' AND lease_token=:token FOR UPDATE
                """, p, r -> r.next() ? new StoredLease(r.getInt(1),r.getTimestamp(2).toInstant(),
                r.getTimestamp(3).toInstant(),r.getTimestamp(4).toInstant()) : null);
        if (lease == null || (recovery ? lease.until().isAfter(now) : !lease.until().isAfter(now))) return false;
        Instant next = now.plusSeconds(retryDelay(lease.attempt(), retryAfter, claim.token()));
        boolean retryable = switch (outcome) {
            case "RETRYABLE_FAILURE", "CONFIGURATION_FAILURE", "DISABLED", "LEASE_EXPIRED" -> true;
            default -> false;
        };
        String status = "ACCEPTED".equals(outcome) ? "ACCEPTED" : "CANCELLED".equals(outcome) ? "CANCELLED"
                : retryable && lease.attempt()<8 && next.isBefore(lease.expiresAt()) ? "RETRY" : "FAILED";
        p.addValue("attempt",lease.attempt()).addValue("startedAt",lease.startedAt()).addValue("outcome",outcome)
                .addValue("httpStatus",httpStatus).addValue("safeCode",safeCode).addValue("status",status)
                .addValue("next",next).addValue("completedAt","RETRY".equals(status) ? null : now);
        jdbc.update("""
                INSERT INTO communication_push_attempts(id,organisation_id,school_id,communication_push_job_id,
                  attempt_number,started_at,finished_at,outcome,http_status,safe_error_code)
                VALUES(:token,:organisationId,:schoolId,:id,:attempt,:startedAt,:now,:outcome,:httpStatus,:safeCode)
                """, p);
        jdbc.update("""
                UPDATE communication_push_jobs SET status=:status,next_attempt_at=:next,completed_at=:completedAt,
                  last_error_code=:safeCode,lease_token=NULL,lease_started_at=NULL,lease_until=NULL,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND lease_token=:token
                """, p);
        return true;
    }

    static long retryDelay(int attempt, long requested, UUID token) {
        long backoff = 60L << Math.min(Math.max(attempt-1, 0), 6);
        long jitter = Math.floorMod(token.getLeastSignificantBits(), 60L);
        return Math.min(86400, Math.max(backoff+jitter, Math.max(60, requested)));
    }

    private static void validateResult(PushTransport.Result result) {
        Objects.requireNonNull(result.outcome());
        Integer code = result.httpStatus();
        if ((code != null && (code<100 || code>599))
                || (result.outcome()==PushTransport.Outcome.ACCEPTED && !List.of(200,201,202).contains(code == null ? 0 : code))
                || (result.outcome()==PushTransport.Outcome.SUBSCRIPTION_EXPIRED && !List.of(404,410).contains(code == null ? 0 : code))) {
            throw new IllegalArgumentException("Push result does not match provider evidence");
        }
    }

    private MapSqlParameterSource scope(UUID organisationId, UUID schoolId) {
        Objects.requireNonNull(organisationId);
        Objects.requireNonNull(schoolId);
        if (TenantRequestContext.current().map(s -> organisationId.equals(s.organisationId())).orElse(false)) {
            return new MapSqlParameterSource("organisationId", organisationId).addValue("schoolId", schoolId);
        }
        throw new IllegalStateException("Push queue requires an explicit matching organisation context");
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    public record Claim(UUID organisationId, UUID schoolId, UUID jobId, UUID deliveryId, UUID subscriptionId,
            UUID token, Instant startedAt, Instant leaseUntil, Instant expiresAt, int attemptNumber) {}
    private record StoredLease(int attempt, Instant startedAt, Instant until, Instant expiresAt) {}
}

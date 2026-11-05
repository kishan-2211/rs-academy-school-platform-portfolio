-- Preserve human registration history; provider-driven expiry has explicit system provenance.
ALTER TABLE communication_push_subscription_events
    ALTER COLUMN actor_user_id DROP NOT NULL,
    ALTER COLUMN actor_membership_id DROP NOT NULL,
    ADD COLUMN actor_kind VARCHAR(10) NOT NULL DEFAULT 'HUMAN',
    ADD COLUMN provider_attempt_id UUID,
    ADD COLUMN subscription_version BIGINT,
    ADD CONSTRAINT communication_push_event_provider_attempt_fk
        FOREIGN KEY (provider_attempt_id, organisation_id, school_id)
        REFERENCES communication_push_attempts(id, organisation_id, school_id),
    ADD CONSTRAINT communication_push_event_provenance_valid CHECK (
        (actor_kind='HUMAN' AND actor_user_id IS NOT NULL AND actor_membership_id IS NOT NULL
            AND provider_attempt_id IS NULL AND subscription_version IS NULL)
        OR (actor_kind='SYSTEM' AND actor_user_id IS NULL AND actor_membership_id IS NULL
            AND event_type='EXPIRED' AND provider_attempt_id IS NOT NULL
            AND subscription_version IS NOT NULL AND subscription_version>=0)
    );

CREATE UNIQUE INDEX communication_push_event_provider_attempt_unique
    ON communication_push_subscription_events(provider_attempt_id) WHERE provider_attempt_id IS NOT NULL;

CREATE FUNCTION guard_communication_push_system_expiry() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.actor_kind='SYSTEM' AND NOT EXISTS (
        SELECT 1 FROM communication_push_attempts attempt
        JOIN communication_push_jobs job ON job.id=attempt.communication_push_job_id
            AND job.organisation_id=attempt.organisation_id AND job.school_id=attempt.school_id
        JOIN communication_push_subscriptions subscription ON subscription.id=job.communication_push_subscription_id
            AND subscription.organisation_id=job.organisation_id AND subscription.school_id=job.school_id
        WHERE attempt.id=NEW.provider_attempt_id AND attempt.organisation_id=NEW.organisation_id
            AND attempt.school_id=NEW.school_id AND attempt.outcome='SUBSCRIPTION_EXPIRED'
            AND attempt.http_status IN (404,410) AND job.status='FAILED'
            AND subscription.id=NEW.communication_push_subscription_id AND subscription.status='EXPIRED'
            AND subscription.row_version=NEW.subscription_version+1
            AND subscription.last_confirmed_at<=attempt.started_at
            AND subscription.revoked_at=NEW.occurred_at AND NEW.occurred_at>=attempt.finished_at
    ) THEN RAISE EXCEPTION 'system push expiry requires matching completed provider and device evidence'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER communication_push_system_expiry_guard
    BEFORE INSERT ON communication_push_subscription_events
    FOR EACH ROW EXECUTE FUNCTION guard_communication_push_system_expiry();

CREATE FUNCTION check_communication_push_expiry_recorded() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM communication_push_subscription_events event
        WHERE event.communication_push_subscription_id=NEW.id AND event.organisation_id=NEW.organisation_id
            AND event.school_id=NEW.school_id AND event.event_type='EXPIRED' AND event.occurred_at=NEW.revoked_at
            AND (event.actor_kind='HUMAN' OR event.subscription_version=OLD.row_version)) THEN
        RAISE EXCEPTION 'push subscription expiry must preserve lifecycle evidence in the same transaction';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER communication_push_expiry_recorded
    AFTER UPDATE ON communication_push_subscriptions DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status='ACTIVE' AND NEW.status='EXPIRED')
    EXECUTE FUNCTION check_communication_push_expiry_recorded();

COMMENT ON COLUMN communication_push_subscription_events.actor_kind IS
    'HUMAN retains existing attributed actions. SYSTEM is restricted to expiry backed by an immutable 404/410 attempt for this unchanged device; never impersonates its owner.';

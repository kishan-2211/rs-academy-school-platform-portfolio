-- Per-device retries extend the existing recipient/channel ledger. No delivery is enabled here.
CREATE TABLE communication_push_jobs (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_delivery_id UUID NOT NULL,
    communication_push_subscription_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_started_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (id, organisation_id, school_id),
    UNIQUE (communication_delivery_id, communication_push_subscription_id),
    FOREIGN KEY (communication_delivery_id, organisation_id, school_id)
        REFERENCES communication_deliveries(id, organisation_id, school_id),
    FOREIGN KEY (communication_push_subscription_id, organisation_id, school_id)
        REFERENCES communication_push_subscriptions(id, organisation_id, school_id),
    CHECK (status IN ('READY','LEASED','RETRY','ACCEPTED','FAILED','CANCELLED')),
    CHECK (attempt_count BETWEEN 0 AND 8 AND row_version >= 0),
    CHECK (status <> 'READY' OR attempt_count=0),
    CHECK (status <> 'RETRY' OR attempt_count BETWEEN 1 AND 7),
    CHECK (expires_at > created_at AND next_attempt_at >= created_at
        AND updated_at >= created_at AND (completed_at IS NULL OR completed_at >= created_at)),
    CHECK ((status = 'LEASED' AND lease_token IS NOT NULL AND lease_started_at IS NOT NULL
            AND lease_until IS NOT NULL AND lease_until > lease_started_at AND lease_started_at >= created_at
            AND lease_until <= lease_started_at + INTERVAL '2 minutes' AND attempt_count > 0)
        OR (status <> 'LEASED' AND lease_token IS NULL AND lease_started_at IS NULL AND lease_until IS NULL)),
    CHECK ((status IN ('ACCEPTED','FAILED','CANCELLED')) = (completed_at IS NOT NULL)),
    CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{2,99}$')
);

CREATE INDEX communication_push_jobs_due_idx
    ON communication_push_jobs (organisation_id, next_attempt_at, id) WHERE status IN ('READY','RETRY');
CREATE INDEX communication_push_jobs_lease_idx
    ON communication_push_jobs (organisation_id, lease_until, id) WHERE status = 'LEASED';

CREATE TABLE communication_push_attempts (
    id UUID PRIMARY KEY, -- claim token: one persisted result per lease
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    communication_push_job_id UUID NOT NULL,
    attempt_number SMALLINT NOT NULL CHECK (attempt_number BETWEEN 1 AND 8),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL CHECK (finished_at >= started_at),
    outcome VARCHAR(30) NOT NULL CHECK (outcome IN (
        'ACCEPTED','SUBSCRIPTION_EXPIRED','RETRYABLE_FAILURE','PERMANENT_FAILURE',
        'CONFIGURATION_FAILURE','DISABLED','CANCELLED','LEASE_EXPIRED')),
    http_status SMALLINT CHECK (http_status BETWEEN 100 AND 599),
    safe_error_code VARCHAR(100) NOT NULL CHECK (safe_error_code ~ '^[A-Z][A-Z0-9_]{2,99}$'),
    UNIQUE (id, organisation_id, school_id),
    UNIQUE (communication_push_job_id, attempt_number),
    FOREIGN KEY (communication_push_job_id, organisation_id, school_id)
        REFERENCES communication_push_jobs(id, organisation_id, school_id),
    CHECK ((outcome <> 'ACCEPTED' OR http_status IN (200,201,202))
        AND (outcome <> 'SUBSCRIPTION_EXPIRED' OR http_status IN (404,410))),
    CHECK (outcome NOT IN ('ACCEPTED','SUBSCRIPTION_EXPIRED') OR http_status IS NOT NULL)
);

CREATE FUNCTION guard_communication_push_job() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE result_outcome TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'push job evidence cannot be deleted'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'READY' OR NEW.attempt_count <> 0 OR NEW.row_version <> 0 THEN
            RAISE EXCEPTION 'push jobs must begin ready without attempts';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM communication_deliveries delivery
            JOIN communication_recipients recipient ON recipient.id=delivery.communication_recipient_id
                AND recipient.organisation_id=delivery.organisation_id AND recipient.school_id=delivery.school_id
            JOIN communication_push_subscriptions subscription ON subscription.id=NEW.communication_push_subscription_id
                AND subscription.organisation_id=recipient.organisation_id AND subscription.school_id=recipient.school_id
                AND subscription.user_account_id=recipient.user_account_id AND subscription.status='ACTIVE'
            WHERE delivery.id=NEW.communication_delivery_id AND delivery.organisation_id=NEW.organisation_id
                AND delivery.school_id=NEW.school_id AND delivery.channel='PUSH'
                AND delivery.status IN ('READY','QUEUED') AND recipient.contact_eligible
        ) THEN RAISE EXCEPTION 'push job must match an eligible recipient and active device'; END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id,NEW.organisation_id,NEW.school_id,NEW.communication_delivery_id,
           NEW.communication_push_subscription_id,NEW.created_at,NEW.expires_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.organisation_id,OLD.school_id,OLD.communication_delivery_id,
           OLD.communication_push_subscription_id,OLD.created_at,OLD.expires_at) THEN
        RAISE EXCEPTION 'push job identity and expiry are immutable';
    END IF;
    IF OLD.status IN ('ACCEPTED','FAILED','CANCELLED') THEN
        RAISE EXCEPTION 'terminal push job evidence is immutable';
    END IF;
    IF NEW.row_version <> OLD.row_version+1 OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'push job version must advance once';
    END IF;
    IF OLD.status IN ('READY','RETRY') AND NEW.status='LEASED' THEN
        IF NEW.attempt_count <> OLD.attempt_count+1 OR NEW.lease_started_at < OLD.next_attempt_at
            OR NEW.lease_started_at >= OLD.expires_at THEN
            RAISE EXCEPTION 'push claim must be due, unexpired and advance its attempt';
        END IF;
    ELSIF OLD.status='LEASED' AND NEW.status IN ('RETRY','ACCEPTED','FAILED','CANCELLED') THEN
        SELECT outcome INTO result_outcome FROM communication_push_attempts
            WHERE id=OLD.lease_token AND communication_push_job_id=OLD.id
                AND organisation_id=OLD.organisation_id AND school_id=OLD.school_id;
        IF result_outcome IS NULL OR NEW.attempt_count <> OLD.attempt_count
            OR ((NEW.status='ACCEPTED') <> (result_outcome='ACCEPTED'))
            OR (NEW.status='RETRY' AND (NEW.attempt_count>=8 OR NEW.next_attempt_at>=NEW.expires_at
                OR result_outcome NOT IN ('RETRYABLE_FAILURE','CONFIGURATION_FAILURE','DISABLED','LEASE_EXPIRED')))
            OR (NEW.status='CANCELLED' AND result_outcome<>'CANCELLED') THEN
            RAISE EXCEPTION 'push completion requires matching immutable attempt evidence';
        END IF;
    ELSIF OLD.status IN ('READY','RETRY') AND NEW.status='CANCELLED' THEN
        IF NEW.attempt_count <> OLD.attempt_count OR NEW.last_error_code IS NULL THEN
            RAISE EXCEPTION 'push cancellation must preserve attempts and give a safe reason';
        END IF;
    ELSE RAISE EXCEPTION 'invalid push job transition';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER communication_push_jobs_guard BEFORE INSERT OR UPDATE OR DELETE ON communication_push_jobs
    FOR EACH ROW EXECUTE FUNCTION guard_communication_push_job();

CREATE FUNCTION guard_communication_push_attempt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM communication_push_jobs job
        WHERE job.id=NEW.communication_push_job_id AND job.organisation_id=NEW.organisation_id
            AND job.school_id=NEW.school_id AND job.status='LEASED' AND job.lease_token=NEW.id
            AND job.attempt_count=NEW.attempt_number AND job.lease_started_at=NEW.started_at
            AND (NEW.outcome='LEASE_EXPIRED' OR NEW.finished_at<job.lease_until)
            AND (NEW.outcome<>'LEASE_EXPIRED' OR job.lease_until<=NEW.finished_at)) THEN
        RAISE EXCEPTION 'push attempt must match the current lease';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER communication_push_attempts_guard BEFORE INSERT ON communication_push_attempts
    FOR EACH ROW EXECUTE FUNCTION guard_communication_push_attempt();
CREATE TRIGGER communication_push_attempts_append_only BEFORE UPDATE OR DELETE ON communication_push_attempts
    FOR EACH ROW EXECUTE FUNCTION prevent_communication_evidence_change();

CREATE FUNCTION check_communication_push_attempt_completed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM communication_push_jobs job
        WHERE job.id=NEW.communication_push_job_id AND job.organisation_id=NEW.organisation_id
            AND job.school_id=NEW.school_id AND job.lease_token=NEW.id) THEN
        RAISE EXCEPTION 'push attempt result must complete its job in the same transaction';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER communication_push_attempt_result_completed
    AFTER INSERT ON communication_push_attempts DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_communication_push_attempt_completed();

ALTER TABLE communication_push_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_push_jobs FORCE ROW LEVEL SECURITY;
ALTER TABLE communication_push_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE communication_push_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY communication_push_jobs_tenant_all ON communication_push_jobs
    USING (organisation_id=current_app_organisation_id()) WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY communication_push_attempts_tenant_all ON communication_push_attempts
    USING (organisation_id=current_app_organisation_id()) WITH CHECK (organisation_id=current_app_organisation_id());

COMMENT ON TABLE communication_push_jobs IS
    'Per-device retry/lease evidence, not authorization to send. Worker must revalidate the approved source, recipient relationship, consent, provider and active device before each attempt.';
COMMENT ON TABLE communication_push_attempts IS
    'Append-only destination/payload-free results. ACCEPTED means provider acceptance, not phone display. LEASE_EXPIRED means unknown external outcome, not a proven delivery failure.';

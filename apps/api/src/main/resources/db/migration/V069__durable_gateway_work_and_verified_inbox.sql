-- Internal, disabled-by-default work persistence. No merchant, endpoint or worker is activated.
-- V066--V068 financial intent, capture and receipt evidence remain unchanged.
CREATE TABLE fee_gateway_attempt_work (
    attempt_id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK (provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL,
    credentials_secret_reference VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','LEASED','RETRY','COMPLETE','REVIEW_REQUIRED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 1000000),
    next_attempt_at TIMESTAMPTZ NOT NULL, lease_token UUID, lease_started_at TIMESTAMPTZ, lease_until TIMESTAMPTZ,
    create_dispatched_at TIMESTAMPTZ, create_dispatched_by_lease_token UUID,
    completed_at TIMESTAMPTZ, last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version>=0),
    UNIQUE (attempt_id,organisation_id,school_id),
    FOREIGN KEY (attempt_id,organisation_id,school_id) REFERENCES fee_gateway_attempts(id,organisation_id,school_id),
    FOREIGN KEY (payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    CHECK (merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$'),
    CHECK (length(btrim(credentials_secret_reference)) BETWEEN 1 AND 240),
    CHECK (next_attempt_at>=created_at AND updated_at>=created_at),
    CHECK ((create_dispatched_at IS NULL)=(create_dispatched_by_lease_token IS NULL)),
    CHECK (create_dispatched_at IS NULL OR create_dispatched_at>=created_at),
    CHECK ((status='LEASED' AND lease_token IS NOT NULL AND lease_started_at IS NOT NULL AND lease_until IS NOT NULL
        AND lease_started_at>=created_at AND lease_until>lease_started_at AND lease_until<=lease_started_at+interval '2 minutes'
        AND attempt_count>0) OR (status<>'LEASED' AND lease_token IS NULL AND lease_started_at IS NULL AND lease_until IS NULL)),
    CHECK ((status IN ('COMPLETE','REVIEW_REQUIRED'))=(completed_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR completed_at>=created_at),
    CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{2,99}$')
);

CREATE TABLE fee_gateway_webhook_inbox (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK (provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL, credentials_secret_reference VARCHAR(240) NOT NULL,
    body_sha256 CHAR(64) NOT NULL CHECK (body_sha256 ~ '^[a-f0-9]{64}$'),
    event_type VARCHAR(40) NOT NULL CHECK (event_type IN ('PAYMENT_SUCCESS_WEBHOOK','PAYMENT_FAILED_WEBHOOK',
        'PAYMENT_USER_DROPPED_WEBHOOK','REFUND_STATUS_WEBHOOK','AUTO_REFUND_STATUS_WEBHOOK')),
    checkout_reference UUID, order_reference VARCHAR(100), payment_reference VARCHAR(100) NOT NULL,
    refund_reference VARCHAR(100), received_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY','LEASED','RETRY','COMPLETE','REVIEW_REQUIRED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 1000000),
    next_attempt_at TIMESTAMPTZ NOT NULL, lease_token UUID, lease_started_at TIMESTAMPTZ, lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ, last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version>=0),
    UNIQUE (id,organisation_id,school_id), UNIQUE (payment_provider_config_id,body_sha256),
    FOREIGN KEY (payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    CHECK (merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$'),
    CHECK (length(btrim(credentials_secret_reference)) BETWEEN 1 AND 240),
    CHECK (order_reference IS NULL OR order_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'),
    CHECK (payment_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'),
    CHECK (refund_reference IS NULL OR refund_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'),
    CHECK ((event_type LIKE 'PAYMENT_%' AND order_reference IS NOT NULL AND refund_reference IS NULL)
        OR (event_type='REFUND_STATUS_WEBHOOK' AND order_reference IS NOT NULL AND refund_reference IS NOT NULL AND checkout_reference IS NULL)
        OR (event_type='AUTO_REFUND_STATUS_WEBHOOK' AND checkout_reference IS NULL)),
    CHECK (created_at=received_at AND next_attempt_at>=created_at AND updated_at>=created_at),
    CHECK ((status='LEASED' AND lease_token IS NOT NULL AND lease_started_at IS NOT NULL AND lease_until IS NOT NULL
        AND lease_started_at>=created_at AND lease_until>lease_started_at AND lease_until<=lease_started_at+interval '2 minutes'
        AND attempt_count>0) OR (status<>'LEASED' AND lease_token IS NULL AND lease_started_at IS NULL AND lease_until IS NULL)),
    CHECK ((status IN ('COMPLETE','REVIEW_REQUIRED'))=(completed_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR completed_at>=created_at),
    CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{2,99}$')
);

CREATE TABLE fee_gateway_payment_observations (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK (provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL, credentials_secret_reference VARCHAR(240) NOT NULL,
    attempt_id UUID, order_reference VARCHAR(100) NOT NULL, payment_reference VARCHAR(100) NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount>0 AND amount<=100000000 AND scale(amount)<=2),
    currency_code CHAR(3) NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    payment_status VARCHAR(20) NOT NULL CHECK (payment_status IN ('CREATED','PENDING','SUCCESS','FAILED','USER_DROPPED','CANCELLED','VOID')),
    captured BOOLEAN NOT NULL, provider_paid_at TIMESTAMPTZ, payment_method VARCHAR(41),
    observed_at TIMESTAMPTZ NOT NULL, evidence_sha256 CHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[a-f0-9]{64}$'),
    UNIQUE (id,organisation_id,school_id), UNIQUE (payment_provider_config_id,evidence_sha256),
    FOREIGN KEY (payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    FOREIGN KEY (attempt_id,organisation_id,school_id) REFERENCES fee_gateway_attempts(id,organisation_id,school_id),
    CHECK (merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$'),
    CHECK (length(btrim(credentials_secret_reference)) BETWEEN 1 AND 240),
    CHECK (order_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$' AND payment_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'),
    CHECK (provider_paid_at IS NULL OR provider_paid_at<=observed_at),
    CHECK (payment_method IS NULL OR payment_method ~ '^[a-z][a-z0-9_]{0,40}$'),
    CHECK (payment_status<>'SUCCESS' OR NOT captured OR provider_paid_at IS NOT NULL)
);

CREATE INDEX fee_gateway_attempt_work_due_idx ON fee_gateway_attempt_work(organisation_id,school_id,payment_provider_config_id,next_attempt_at,attempt_id)
    WHERE status IN ('READY','RETRY');
CREATE INDEX fee_gateway_attempt_work_leased_idx ON fee_gateway_attempt_work(organisation_id,school_id,payment_provider_config_id,lease_until,attempt_id)
    WHERE status='LEASED';
CREATE INDEX fee_gateway_webhook_inbox_due_idx ON fee_gateway_webhook_inbox(organisation_id,school_id,payment_provider_config_id,next_attempt_at,id)
    WHERE status IN ('READY','RETRY');
CREATE INDEX fee_gateway_webhook_inbox_leased_idx ON fee_gateway_webhook_inbox(organisation_id,school_id,payment_provider_config_id,lease_until,id)
    WHERE status='LEASED';
CREATE INDEX fee_gateway_payment_observations_attempt_idx ON fee_gateway_payment_observations(organisation_id,school_id,attempt_id,observed_at,id);
CREATE INDEX fee_gateway_payment_observations_payment_idx ON fee_gateway_payment_observations(organisation_id,school_id,payment_provider_config_id,payment_reference,observed_at,id);

CREATE FUNCTION guard_gateway_work_binding() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE attempt_id_value UUID := NULLIF(to_jsonb(NEW)->>'attempt_id','')::uuid;
BEGIN
    IF NEW.organisation_id IS DISTINCT FROM current_app_organisation_id() OR NOT EXISTS (
        SELECT 1 FROM payment_provider_configs provider
        WHERE provider.id=NEW.payment_provider_config_id AND provider.organisation_id=NEW.organisation_id AND provider.school_id=NEW.school_id
          AND provider.provider_code=NEW.provider_code AND provider.environment=NEW.provider_environment
          AND provider.merchant_account_reference=NEW.merchant_account_reference
          AND provider.credentials_secret_reference=NEW.credentials_secret_reference
    ) THEN RAISE EXCEPTION 'gateway work requires the exact scoped frozen provider binding'; END IF;
    IF TG_TABLE_NAME='fee_gateway_attempt_work' OR (TG_TABLE_NAME='fee_gateway_payment_observations' AND attempt_id_value IS NOT NULL) THEN
        IF NOT EXISTS (SELECT 1 FROM fee_gateway_attempts attempt WHERE attempt.id=attempt_id_value
            AND attempt.organisation_id=NEW.organisation_id AND attempt.school_id=NEW.school_id
            AND attempt.payment_provider_config_id=NEW.payment_provider_config_id AND attempt.provider_code=NEW.provider_code
            AND attempt.provider_environment=NEW.provider_environment AND attempt.merchant_account_reference=NEW.merchant_account_reference
            AND (current_app_user_id() IS NULL OR attempt.initiated_by_user_id=current_app_user_id())) THEN
            RAISE EXCEPTION 'gateway work must preserve its scoped immutable attempt identity';
        END IF;
    END IF;
    IF TG_TABLE_NAME<>'fee_gateway_attempt_work' AND current_app_user_id() IS NOT NULL THEN
        RAISE EXCEPTION 'verified gateway intake requires an honest background scope';
    END IF;
    IF TG_TABLE_NAME='fee_gateway_webhook_inbox' THEN
        IF NEW.checkout_reference IS NOT NULL AND EXISTS (
            SELECT 1 FROM fee_gateway_attempts attempt WHERE attempt.checkout_reference=NEW.checkout_reference
              AND (attempt.organisation_id<>NEW.organisation_id OR attempt.school_id<>NEW.school_id
                OR attempt.payment_provider_config_id<>NEW.payment_provider_config_id OR attempt.provider_code<>NEW.provider_code
                OR attempt.provider_environment<>NEW.provider_environment OR attempt.merchant_account_reference<>NEW.merchant_account_reference)
        ) THEN RAISE EXCEPTION 'authenticated webhook cannot be routed to another visible school or merchant'; END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_attempt_work_binding BEFORE INSERT ON fee_gateway_attempt_work FOR EACH ROW EXECUTE FUNCTION guard_gateway_work_binding();
CREATE TRIGGER fee_gateway_webhook_inbox_binding BEFORE INSERT ON fee_gateway_webhook_inbox FOR EACH ROW EXECUTE FUNCTION guard_gateway_work_binding();
CREATE TRIGGER fee_gateway_payment_observations_binding BEFORE INSERT ON fee_gateway_payment_observations FOR EACH ROW EXECUTE FUNCTION guard_gateway_work_binding();

CREATE FUNCTION guard_gateway_work_transition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE mutable_columns TEXT[] := ARRAY['status','attempt_count','next_attempt_at','lease_token','lease_started_at','lease_until',
    'completed_at','last_error_code','updated_at','row_version'];
    attempt fee_gateway_attempts%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'gateway work and authenticated intake evidence cannot be deleted'; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'READY' OR NEW.attempt_count<>0 OR NEW.row_version<>0 OR NEW.updated_at<>NEW.created_at
            OR NEW.last_error_code IS NOT NULL THEN RAISE EXCEPTION 'gateway work must begin ready without manufactured processing history'; END IF;
        IF TG_TABLE_NAME='fee_gateway_attempt_work' THEN
            IF NEW.create_dispatched_at IS NOT NULL OR NEW.create_dispatched_by_lease_token IS NOT NULL THEN
                RAISE EXCEPTION 'new gateway work cannot claim an already dispatched create';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.organisation_id IS DISTINCT FROM current_app_organisation_id() OR current_app_user_id() IS NOT NULL THEN
        RAISE EXCEPTION 'gateway processing requires an honest matching background scope';
    END IF;
    IF TG_TABLE_NAME='fee_gateway_attempt_work' THEN mutable_columns:=mutable_columns||ARRAY['create_dispatched_at','create_dispatched_by_lease_token']; END IF;
    IF (to_jsonb(NEW)-mutable_columns) IS DISTINCT FROM (to_jsonb(OLD)-mutable_columns)
        OR NEW.row_version<>OLD.row_version+1 OR NEW.updated_at<OLD.updated_at
        OR OLD.status IN ('COMPLETE','REVIEW_REQUIRED') THEN
        RAISE EXCEPTION 'gateway work identity, original evidence and completed processing are immutable';
    END IF;
    IF TG_TABLE_NAME='fee_gateway_attempt_work' THEN
        IF OLD.create_dispatched_at IS NOT NULL AND (NEW.create_dispatched_at IS DISTINCT FROM OLD.create_dispatched_at
            OR NEW.create_dispatched_by_lease_token IS DISTINCT FROM OLD.create_dispatched_by_lease_token) THEN
            RAISE EXCEPTION 'a dispatched gateway create can never be reset or repeated';
        END IF;
        IF OLD.create_dispatched_at IS NULL AND NEW.create_dispatched_at IS NOT NULL THEN
            PERFORM pg_advisory_xact_lock(hashtextextended(OLD.school_id::text || ':finance',0));
            SELECT * INTO attempt FROM fee_gateway_attempts WHERE id=OLD.attempt_id AND organisation_id=OLD.organisation_id AND school_id=OLD.school_id FOR UPDATE;
            IF OLD.status<>'LEASED' OR NEW.status<>'LEASED' OR NEW.create_dispatched_by_lease_token IS DISTINCT FROM OLD.lease_token
                OR NEW.create_dispatched_at<OLD.lease_started_at OR NEW.create_dispatched_at>=OLD.lease_until
                OR clock_timestamp()>=OLD.lease_until OR NEW.create_dispatched_at>clock_timestamp()
                OR NEW.updated_at<>NEW.create_dispatched_at OR attempt.status<>'RESERVED'
                OR attempt.expires_at<=NEW.create_dispatched_at OR attempt.expires_at<=clock_timestamp()
                OR (to_jsonb(NEW)-ARRAY['create_dispatched_at','create_dispatched_by_lease_token','updated_at','row_version']) IS DISTINCT FROM
                   (to_jsonb(OLD)-ARRAY['create_dispatched_at','create_dispatched_by_lease_token','updated_at','row_version']) THEN
                RAISE EXCEPTION 'create dispatch requires a current unexpired one-use lease and reserved attempt';
            END IF;
            RETURN NEW;
        END IF;
    END IF;
    IF OLD.status IN ('READY','RETRY') AND NEW.status='LEASED' THEN
        IF NEW.attempt_count<>OLD.attempt_count+1 OR NEW.lease_started_at<OLD.next_attempt_at OR NEW.updated_at<>NEW.lease_started_at
            OR NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at OR NEW.last_error_code IS DISTINCT FROM OLD.last_error_code
            OR NEW.lease_started_at>clock_timestamp() OR NEW.lease_until<=clock_timestamp() THEN
            RAISE EXCEPTION 'gateway claim must be due and advance its processing attempt once';
        END IF;
    ELSIF OLD.status='LEASED' AND NEW.status IN ('RETRY','COMPLETE','REVIEW_REQUIRED') THEN
        IF NEW.attempt_count<>OLD.attempt_count OR NEW.last_error_code IS NULL
            OR (NEW.status='RETRY' AND NEW.next_attempt_at<=NEW.updated_at)
            OR (NEW.status<>'RETRY' AND (NEW.completed_at<>NEW.updated_at OR NEW.updated_at>=OLD.lease_until))
            OR (NEW.updated_at>=OLD.lease_until AND NEW.last_error_code<>'GATEWAY_LEASE_OUTCOME_UNKNOWN')
            OR NEW.updated_at>clock_timestamp()
            OR (clock_timestamp()>=OLD.lease_until AND (NEW.status<>'RETRY'
                OR NEW.last_error_code<>'GATEWAY_LEASE_OUTCOME_UNKNOWN' OR NEW.updated_at<OLD.lease_until)) THEN
            RAISE EXCEPTION 'gateway result must be fenced to its current lease; an expired lease has an unknown outcome';
        END IF;
    ELSE RAISE EXCEPTION 'unsupported gateway work transition'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_attempt_work_transition BEFORE INSERT OR UPDATE OR DELETE ON fee_gateway_attempt_work
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_work_transition();
CREATE TRIGGER fee_gateway_webhook_inbox_transition BEFORE INSERT OR UPDATE OR DELETE ON fee_gateway_webhook_inbox
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_work_transition();
CREATE TRIGGER fee_gateway_payment_observations_append_only BEFORE UPDATE OR DELETE ON fee_gateway_payment_observations
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();

DO $$ DECLARE table_name TEXT; BEGIN
    FOREACH table_name IN ARRAY ARRAY['fee_gateway_attempt_work','fee_gateway_webhook_inbox','fee_gateway_payment_observations'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY %I ON %I USING (organisation_id=current_app_organisation_id()) WITH CHECK (organisation_id=current_app_organisation_id())',table_name||'_tenant',table_name);
    END LOOP;
END $$;

COMMENT ON TABLE fee_gateway_attempt_work IS 'Internal lease/retry metadata only, not authority to create or post payments. A committed dispatch marker permits at most one CREATE; unknown outcomes require same-ID GET recovery.';
COMMENT ON COLUMN fee_gateway_attempt_work.create_dispatched_at IS 'One-use dispatch evidence committed before provider CREATE, preserved across timeout, process restart and every lease recovery. Never permission to retry CREATE.';
COMMENT ON TABLE fee_gateway_webhook_inbox IS 'Minimal verified original-byte HMAC routing evidence. The server boundary authenticates before insertion; durable intake alone does not verify payment or issue a receipt.';
COMMENT ON TABLE fee_gateway_payment_observations IS 'Append-only minimal independent provider read facts retained during incomplete discovery. No raw customer/card data; these are not captures, ledger credits or receipt proof. First payable capture binds only complete ALL-order/payment discovery.';

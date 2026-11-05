-- Durable school intent and minimal authenticated provider evidence. No provider is activated.
-- Receipt posting/provenance and refunds extend this foundation in subsequent forward migrations.
ALTER TABLE payment_provider_configs ADD COLUMN merchant_account_reference VARCHAR(120);
ALTER TABLE payment_provider_configs ADD CONSTRAINT payment_provider_merchant_reference_valid
    CHECK (merchant_account_reference IS NULL OR merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$');
ALTER TABLE learner_fee_charges ADD CONSTRAINT learner_fee_charge_account_scope_unique
    UNIQUE (id, learner_fee_account_id, organisation_id, school_id);

CREATE TABLE fee_gateway_attempts (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    learner_fee_account_id UUID NOT NULL, payment_provider_config_id UUID NOT NULL,
    initiated_by_user_id UUID NOT NULL REFERENCES user_accounts(id), initiated_by_membership_id UUID NOT NULL,
    client_request_id UUID NOT NULL, request_fingerprint CHAR(64) NOT NULL,
    checkout_reference UUID NOT NULL CHECK (checkout_reference=id),
    amount NUMERIC NOT NULL CHECK (amount>0 AND amount<=100000000 AND scale(amount)<=2),
    currency_code CHAR(3) NOT NULL CHECK (currency_code='INR'),
    payer_phone_ciphertext VARCHAR(160) NOT NULL CHECK (payer_phone_ciphertext ~ '^[A-Za-z0-9_-]{24,160}$'),
    payer_phone_nonce VARCHAR(16) NOT NULL CHECK (payer_phone_nonce ~ '^[A-Za-z0-9_-]{16}$'),
    return_uri VARCHAR(250) NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK (provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL CHECK (merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$'),
    school_time_zone VARCHAR(60) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL, original_business_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'RESERVED'
        CHECK (status IN ('RESERVED','CHECKOUT_PENDING','CAPTURED_REVIEW_REQUIRED','POSTED','RELEASED')),
    fee_payment_id UUID, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version>=0),
    UNIQUE (id,organisation_id,school_id), UNIQUE (id,learner_fee_account_id,organisation_id,school_id),
    UNIQUE (school_id,initiated_by_user_id,client_request_id), UNIQUE (fee_payment_id),
    CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    CHECK (expires_at>created_at AND expires_at<=created_at+interval '24 hours'),
    CHECK ((status='POSTED')=(fee_payment_id IS NOT NULL)),
    FOREIGN KEY (learner_fee_account_id,organisation_id,school_id) REFERENCES learner_fee_accounts(id,organisation_id,school_id),
    FOREIGN KEY (payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    FOREIGN KEY (initiated_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    FOREIGN KEY (fee_payment_id,organisation_id,school_id) REFERENCES fee_payments(id,organisation_id,school_id)
);

CREATE TABLE fee_gateway_checkout_observations (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, attempt_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_checkout_reference UUID NOT NULL, attempt_reference UUID NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount>0 AND amount<=100000000 AND scale(amount)<=2),
    currency_code CHAR(3) NOT NULL CHECK (currency_code='INR'),
    amount_paid NUMERIC NOT NULL CHECK (amount_paid>=0 AND amount_paid<=100000000 AND scale(amount_paid)<=2),
    checkout_status VARCHAR(20) NOT NULL CHECK (checkout_status IN ('CREATED','PENDING','PARTIALLY_PAID','PAID','CANCELLED','EXPIRED')),
    provider_expires_at TIMESTAMPTZ NOT NULL, order_references VARCHAR(100)[] NOT NULL,
    pending_payment_count INTEGER NOT NULL CHECK (pending_payment_count BETWEEN 0 AND 10000),
    successful_payment_count INTEGER NOT NULL CHECK (successful_payment_count BETWEEN 0 AND 10000),
    discovery_complete BOOLEAN NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[a-f0-9]{64}$'), observed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id,organisation_id,school_id), UNIQUE (id,attempt_id,organisation_id,school_id),
    FOREIGN KEY (attempt_id,organisation_id,school_id) REFERENCES fee_gateway_attempts(id,organisation_id,school_id),
    FOREIGN KEY (payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    CHECK (cardinality(order_references)<=100)
);

CREATE TABLE fee_gateway_allocations (
    id UUID PRIMARY KEY, attempt_id UUID NOT NULL, learner_fee_account_id UUID NOT NULL,
    learner_fee_charge_id UUID NOT NULL, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount>0 AND amount<=100000000 AND scale(amount)<=2),
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','CONSUMED','RELEASED')),
    release_observation_id UUID, release_reviewed_by_membership_id UUID, release_reason VARCHAR(240),
    released_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version>=0),
    UNIQUE (attempt_id,learner_fee_charge_id),
    FOREIGN KEY (attempt_id,learner_fee_account_id,organisation_id,school_id)
        REFERENCES fee_gateway_attempts(id,learner_fee_account_id,organisation_id,school_id),
    FOREIGN KEY (learner_fee_charge_id,learner_fee_account_id,organisation_id,school_id)
        REFERENCES learner_fee_charges(id,learner_fee_account_id,organisation_id,school_id),
    FOREIGN KEY (release_observation_id,attempt_id,organisation_id,school_id)
        REFERENCES fee_gateway_checkout_observations(id,attempt_id,organisation_id,school_id),
    FOREIGN KEY (release_reviewed_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CHECK ((status='RELEASED' AND release_observation_id IS NOT NULL AND release_reviewed_by_membership_id IS NOT NULL
              AND length(btrim(release_reason)) BETWEEN 1 AND 240 AND released_at IS NOT NULL)
        OR (status<>'RELEASED' AND release_observation_id IS NULL AND release_reviewed_by_membership_id IS NULL
              AND release_reason IS NULL AND released_at IS NULL))
);

CREATE TABLE fee_gateway_captures (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, attempt_id UUID,
    payment_provider_config_id UUID NOT NULL, checkout_observation_id UUID,
    provider_code VARCHAR(40) NOT NULL, provider_environment VARCHAR(10) NOT NULL CHECK (provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL CHECK (merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$'),
    checkout_reference VARCHAR(100), order_reference VARCHAR(100) NOT NULL, payment_reference VARCHAR(100) NOT NULL,
    amount NUMERIC NOT NULL CHECK (amount>0 AND amount<=100000000 AND scale(amount)<=2),
    currency_code CHAR(3) NOT NULL CHECK (currency_code='INR'), paid_at TIMESTAMPTZ NOT NULL, detected_at TIMESTAMPTZ NOT NULL,
    original_business_date DATE, closed_day_close_id UUID,
    classification VARCHAR(20) NOT NULL CHECK (classification IN ('MATCHED','REVIEW_REQUIRED','ORPHAN')),
    evidence_sha256 CHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[a-f0-9]{64}$'),
    UNIQUE (id,organisation_id,school_id),
    UNIQUE (provider_code,provider_environment,merchant_account_reference,payment_reference),
    FOREIGN KEY (attempt_id,organisation_id,school_id) REFERENCES fee_gateway_attempts(id,organisation_id,school_id),
    FOREIGN KEY (payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    FOREIGN KEY (checkout_observation_id,attempt_id,organisation_id,school_id)
        REFERENCES fee_gateway_checkout_observations(id,attempt_id,organisation_id,school_id),
    FOREIGN KEY (closed_day_close_id,organisation_id,school_id) REFERENCES fee_day_closes(id,organisation_id,school_id),
    CHECK (order_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$' AND payment_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'),
    CHECK (checkout_reference IS NULL OR checkout_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'),
    CHECK (paid_at<=detected_at),
    CHECK ((classification='ORPHAN' AND attempt_id IS NULL AND original_business_date IS NULL AND checkout_observation_id IS NULL)
        OR (classification<>'ORPHAN' AND attempt_id IS NOT NULL AND original_business_date IS NOT NULL))
);

CREATE TABLE fee_gateway_capture_resolutions (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, capture_id UUID NOT NULL,
    resolution_type VARCHAR(20) NOT NULL CHECK (resolution_type IN ('HOLD','REFUND_REQUIRED','POSTED')),
    reason VARCHAR(240) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 240),
    open_date DATE, fee_payment_id UUID,
    reviewed_by_user_id UUID NOT NULL REFERENCES user_accounts(id), reviewed_by_membership_id UUID NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL, UNIQUE (id,organisation_id,school_id), UNIQUE (fee_payment_id),
    FOREIGN KEY (capture_id,organisation_id,school_id) REFERENCES fee_gateway_captures(id,organisation_id,school_id),
    FOREIGN KEY (reviewed_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    FOREIGN KEY (fee_payment_id,organisation_id,school_id) REFERENCES fee_payments(id,organisation_id,school_id),
    CHECK ((resolution_type='POSTED' AND open_date IS NOT NULL AND fee_payment_id IS NOT NULL)
        OR (resolution_type<>'POSTED' AND open_date IS NULL AND fee_payment_id IS NULL))
);
CREATE UNIQUE INDEX fee_gateway_capture_posted_once ON fee_gateway_capture_resolutions(capture_id) WHERE resolution_type='POSTED';
CREATE INDEX fee_gateway_attempts_work_idx ON fee_gateway_attempts(organisation_id,school_id,status,updated_at,id);
CREATE INDEX fee_gateway_reservations_charge_idx ON fee_gateway_allocations(learner_fee_charge_id) WHERE status='ACTIVE';
CREATE INDEX fee_gateway_reservations_account_idx ON fee_gateway_allocations(learner_fee_account_id,status);
CREATE INDEX fee_gateway_observations_attempt_idx ON fee_gateway_checkout_observations(attempt_id,observed_at DESC,id);
CREATE INDEX fee_gateway_captures_attempt_idx ON fee_gateway_captures(attempt_id,detected_at,id);

CREATE FUNCTION acquire_gateway_finance_fence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_attempts_fence BEFORE INSERT OR UPDATE ON fee_gateway_attempts FOR EACH ROW EXECUTE FUNCTION acquire_gateway_finance_fence();
CREATE TRIGGER fee_gateway_allocations_fence BEFORE INSERT OR UPDATE ON fee_gateway_allocations FOR EACH ROW EXECUTE FUNCTION acquire_gateway_finance_fence();
CREATE TRIGGER fee_allocations_gateway_fence BEFORE INSERT ON fee_payment_allocations FOR EACH ROW EXECUTE FUNCTION acquire_gateway_finance_fence();
CREATE TRIGGER fee_concessions_gateway_fence BEFORE INSERT ON fee_ledger_entries FOR EACH ROW WHEN (NEW.entry_type='CONCESSION') EXECUTE FUNCTION acquire_gateway_finance_fence();

CREATE FUNCTION guard_gateway_intent() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'RESERVED' OR NEW.row_version<>0 OR NEW.updated_at<>NEW.created_at
           OR NEW.original_business_date<>(NEW.created_at AT TIME ZONE NEW.school_time_zone)::date
           OR NOT EXISTS (
               SELECT 1 FROM learner_fee_accounts account
               JOIN schools school ON school.id=account.school_id AND school.organisation_id=account.organisation_id AND school.status='ACTIVE'
               JOIN organisations organisation ON organisation.id=school.organisation_id AND organisation.status='ACTIVE'
               JOIN payment_provider_configs provider ON provider.id=NEW.payment_provider_config_id
                 AND provider.organisation_id=account.organisation_id AND provider.school_id=account.school_id AND provider.status='ACTIVE'
               JOIN memberships member ON member.id=NEW.initiated_by_membership_id
                 AND member.user_id=NEW.initiated_by_user_id AND member.organisation_id=account.organisation_id AND member.school_id=account.school_id
                 AND member.status='ACTIVE' AND member.valid_from<=CURRENT_TIMESTAMP AND (member.valid_until IS NULL OR member.valid_until>CURRENT_TIMESTAMP)
               JOIN user_accounts issuer ON issuer.id=member.user_id AND issuer.status='ACTIVE'
               WHERE account.id=NEW.learner_fee_account_id AND account.organisation_id=NEW.organisation_id AND account.school_id=NEW.school_id
                 AND account.status='ACTIVE' AND account.currency_code='INR' AND school.time_zone=NEW.school_time_zone
                 AND provider.provider_code=NEW.provider_code AND provider.environment=NEW.provider_environment
                 AND provider.merchant_account_reference=NEW.merchant_account_reference
                 AND EXISTS (SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                   JOIN role_capabilities rc ON rc.role_id=mr.role_id AND rc.capability_code='portal.guardian'
                   WHERE mr.membership_id=member.id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id))
                 AND EXISTS (SELECT 1 FROM guardians guardian JOIN learner_guardian_relationships relationship
                   ON relationship.guardian_id=guardian.id AND relationship.organisation_id=guardian.organisation_id
                     AND relationship.school_id=guardian.school_id AND relationship.portal_access_status='ACTIVE'
                   WHERE guardian.user_account_id=NEW.initiated_by_user_id AND guardian.status='ACTIVE'
                     AND guardian.organisation_id=account.organisation_id AND guardian.school_id=account.school_id
                     AND relationship.learner_id=account.learner_id)
           ) THEN RAISE EXCEPTION 'gateway intent requires matching active family, account and provider context'; END IF;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['status','fee_payment_id','updated_at','row_version']) IS DISTINCT FROM
           (to_jsonb(OLD)-ARRAY['status','fee_payment_id','updated_at','row_version'])
           OR NEW.row_version<>OLD.row_version+1 OR NEW.updated_at<OLD.updated_at
           OR OLD.status='POSTED' OR (OLD.status='RELEASED' AND NEW.status<>'CAPTURED_REVIEW_REQUIRED')
           OR (OLD.status='CAPTURED_REVIEW_REQUIRED' AND NEW.status NOT IN ('CAPTURED_REVIEW_REQUIRED','POSTED')) THEN
            RAISE EXCEPTION 'gateway intent identity and stronger financial evidence are immutable';
        END IF;
        IF (OLD.status='RESERVED' AND NEW.status NOT IN ('CHECKOUT_PENDING','CAPTURED_REVIEW_REQUIRED','RELEASED'))
           OR (OLD.status='CHECKOUT_PENDING' AND NEW.status NOT IN ('CHECKOUT_PENDING','CAPTURED_REVIEW_REQUIRED','POSTED','RELEASED')) THEN
            RAISE EXCEPTION 'invalid gateway intent transition';
        END IF;
        IF NEW.status='CAPTURED_REVIEW_REQUIRED' AND NOT EXISTS (SELECT 1 FROM fee_gateway_captures capture WHERE capture.attempt_id=NEW.id) THEN
            RAISE EXCEPTION 'capture review requires retained provider capture evidence';
        END IF;
        IF NEW.status='POSTED' AND NOT EXISTS (
            SELECT 1 FROM fee_gateway_captures capture JOIN fee_gateway_capture_resolutions resolution ON resolution.capture_id=capture.id
            WHERE capture.attempt_id=NEW.id AND resolution.resolution_type='POSTED' AND resolution.fee_payment_id=NEW.fee_payment_id
        ) THEN RAISE EXCEPTION 'posted gateway state requires matching reviewed financial receipt'; END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_attempts_guard BEFORE INSERT OR UPDATE ON fee_gateway_attempts FOR EACH ROW EXECUTE FUNCTION guard_gateway_intent();

CREATE FUNCTION guard_gateway_allocation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE observed fee_gateway_checkout_observations%ROWTYPE; outstanding NUMERIC; partial_allowed BOOLEAN;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'ACTIVE' OR NEW.row_version<>0 OR NEW.updated_at<>NEW.created_at
           OR NOT EXISTS (SELECT 1 FROM fee_gateway_attempts attempt WHERE attempt.id=NEW.attempt_id AND attempt.status='RESERVED') THEN
            RAISE EXCEPTION 'new gateway allocations require a reserved intent';
        END IF;
        SELECT charge.allow_partial,charge.amount
            -COALESCE((SELECT sum(amount) FROM fee_ledger_entries WHERE learner_fee_charge_id=charge.id AND entry_type='CONCESSION'),0)
            -COALESCE((SELECT sum(allocation.amount) FROM fee_payment_allocations allocation JOIN fee_payments payment
                ON payment.id=allocation.fee_payment_id AND payment.status='POSTED' WHERE allocation.learner_fee_charge_id=charge.id),0)
          INTO partial_allowed,outstanding FROM learner_fee_charges charge WHERE charge.id=NEW.learner_fee_charge_id;
        IF partial_allowed=FALSE AND NEW.amount<>outstanding THEN RAISE EXCEPTION 'non-partial fee obligation must be reserved in full'; END IF;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['status','release_observation_id','release_reviewed_by_membership_id','release_reason','released_at','updated_at','row_version']) IS DISTINCT FROM
           (to_jsonb(OLD)-ARRAY['status','release_observation_id','release_reviewed_by_membership_id','release_reason','released_at','updated_at','row_version'])
           OR OLD.status<>'ACTIVE' OR NEW.status NOT IN ('CONSUMED','RELEASED') OR NEW.row_version<>OLD.row_version+1 OR NEW.updated_at<OLD.updated_at THEN
            RAISE EXCEPTION 'gateway allocations are immutable except supported one-use disposition';
        END IF;
        IF NEW.status='RELEASED' THEN
            SELECT * INTO observed FROM fee_gateway_checkout_observations WHERE id=NEW.release_observation_id;
            IF observed.checkout_status NOT IN ('CANCELLED','EXPIRED') OR observed.amount_paid<>0 OR NOT observed.discovery_complete
               OR observed.pending_payment_count<>0 OR observed.successful_payment_count<>0 OR observed.observed_at>NEW.released_at
               OR EXISTS (SELECT 1 FROM fee_gateway_captures capture WHERE capture.attempt_id=NEW.attempt_id)
               OR NOT EXISTS (SELECT 1 FROM memberships member JOIN membership_roles mr ON mr.membership_id=member.id
                 JOIN roles role ON role.id=mr.role_id JOIN role_capabilities rc ON rc.role_id=mr.role_id AND rc.capability_code='fees.approve'
                 WHERE member.id=NEW.release_reviewed_by_membership_id AND member.organisation_id=NEW.organisation_id AND member.school_id=NEW.school_id
                   AND member.status='ACTIVE' AND member.valid_from<=CURRENT_TIMESTAMP AND (member.valid_until IS NULL OR member.valid_until>CURRENT_TIMESTAMP)
                   AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)) THEN
                RAISE EXCEPTION 'reservation release requires reviewed complete provider-terminal evidence without captures';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_allocations_guard BEFORE INSERT OR UPDATE ON fee_gateway_allocations FOR EACH ROW EXECUTE FUNCTION guard_gateway_allocation();

CREATE FUNCTION guard_gateway_observation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM fee_gateway_attempts attempt WHERE attempt.id=NEW.attempt_id
          AND attempt.payment_provider_config_id=NEW.payment_provider_config_id AND attempt.checkout_reference=NEW.provider_checkout_reference
          AND attempt.id=NEW.attempt_reference AND attempt.amount=NEW.amount AND attempt.currency_code=NEW.currency_code
          AND attempt.expires_at=NEW.provider_expires_at AND NEW.observed_at>=attempt.created_at)
       OR EXISTS (SELECT 1 FROM unnest(NEW.order_references) reference WHERE reference IS NULL OR reference !~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$')
       OR cardinality(NEW.order_references)<>(SELECT count(DISTINCT reference) FROM unnest(NEW.order_references) reference) THEN
        RAISE EXCEPTION 'gateway observation must match immutable checkout and canonical complete order identities';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_observations_guard BEFORE INSERT ON fee_gateway_checkout_observations FOR EACH ROW EXECUTE FUNCTION guard_gateway_observation();

CREATE FUNCTION guard_gateway_capture() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE attempt fee_gateway_attempts%ROWTYPE; close_record fee_day_closes%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    IF NOT EXISTS (SELECT 1 FROM payment_provider_configs provider WHERE provider.id=NEW.payment_provider_config_id
        AND provider.provider_code=NEW.provider_code AND provider.environment=NEW.provider_environment
        AND provider.merchant_account_reference=NEW.merchant_account_reference) THEN RAISE EXCEPTION 'capture requires configured merchant identity'; END IF;
    IF NEW.attempt_id IS NOT NULL THEN
        SELECT * INTO attempt FROM fee_gateway_attempts WHERE id=NEW.attempt_id;
        IF attempt.payment_provider_config_id<>NEW.payment_provider_config_id
           OR NEW.original_business_date<>(NEW.paid_at AT TIME ZONE attempt.school_time_zone)::date THEN
            RAISE EXCEPTION 'capture scope and original gateway financial date must be preserved';
        END IF;
        SELECT * INTO close_record FROM fee_day_closes WHERE organisation_id=NEW.organisation_id AND school_id=NEW.school_id
          AND status='APPROVED' AND business_date>=NEW.original_business_date ORDER BY business_date DESC,id LIMIT 1;
        IF close_record.id IS NOT NULL AND (NEW.classification<>'REVIEW_REQUIRED' OR NEW.closed_day_close_id IS DISTINCT FROM close_record.id) THEN
            RAISE EXCEPTION 'closed-day capture must preserve approved close and require reviewed open-date posting';
        END IF;
        IF NEW.classification='MATCHED' AND (attempt.status IN ('RELEASED','POSTED') OR NEW.amount<>attempt.amount OR NEW.paid_at<attempt.created_at
          OR NEW.checkout_reference IS DISTINCT FROM attempt.checkout_reference::text OR NEW.checkout_observation_id IS NULL
          OR NOT EXISTS (SELECT 1 FROM fee_gateway_checkout_observations observed WHERE observed.id=NEW.checkout_observation_id
              AND observed.checkout_status='PAID' AND observed.amount_paid=NEW.amount AND NEW.order_reference=ANY(observed.order_references))
          OR EXISTS (SELECT 1 FROM fee_gateway_captures capture WHERE capture.attempt_id=NEW.attempt_id)) THEN
            RAISE EXCEPTION 'unmatched or additional successful payments must remain review evidence';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_captures_guard BEFORE INSERT ON fee_gateway_captures FOR EACH ROW EXECUTE FUNCTION guard_gateway_capture();

CREATE FUNCTION guard_gateway_provider_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM fee_gateway_attempts attempt WHERE attempt.payment_provider_config_id=OLD.id)
       AND (OLD.id IS DISTINCT FROM NEW.id OR OLD.organisation_id IS DISTINCT FROM NEW.organisation_id
         OR OLD.school_id IS DISTINCT FROM NEW.school_id OR OLD.provider_code IS DISTINCT FROM NEW.provider_code
         OR OLD.environment IS DISTINCT FROM NEW.environment OR OLD.merchant_account_reference IS DISTINCT FROM NEW.merchant_account_reference) THEN
        RAISE EXCEPTION 'merchant identity used by gateway evidence must be preserved across credential rotation';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_provider_identity BEFORE UPDATE ON payment_provider_configs FOR EACH ROW EXECUTE FUNCTION guard_gateway_provider_identity();

CREATE FUNCTION guard_gateway_resolution() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    IF NOT EXISTS (SELECT 1 FROM memberships member JOIN membership_roles mr ON mr.membership_id=member.id
        JOIN roles role ON role.id=mr.role_id JOIN role_capabilities rc ON rc.role_id=mr.role_id AND rc.capability_code='fees.collect'
        WHERE member.id=NEW.reviewed_by_membership_id AND member.user_id=NEW.reviewed_by_user_id
          AND member.organisation_id=NEW.organisation_id AND member.school_id=NEW.school_id AND member.status='ACTIVE'
          AND member.valid_from<=CURRENT_TIMESTAMP AND (member.valid_until IS NULL OR member.valid_until>CURRENT_TIMESTAMP)
          AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)) THEN RAISE EXCEPTION 'capture resolution requires live financial reviewer'; END IF;
    IF NEW.resolution_type='POSTED' AND NOT EXISTS (
        SELECT 1 FROM fee_gateway_captures capture JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
        JOIN fee_payments payment ON payment.id=NEW.fee_payment_id AND payment.organisation_id=capture.organisation_id AND payment.school_id=capture.school_id
        WHERE capture.id=NEW.capture_id AND payment.learner_fee_account_id=attempt.learner_fee_account_id
          AND payment.payment_method='ONLINE_PROVIDER' AND payment.amount=capture.amount AND payment.status='POSTED'
          AND payment.received_on=NEW.open_date AND NEW.open_date>=(capture.paid_at AT TIME ZONE attempt.school_time_zone)::date
          AND NEW.open_date<=(NEW.reviewed_at AT TIME ZONE attempt.school_time_zone)::date
          AND NOT EXISTS (SELECT 1 FROM fee_day_closes close WHERE close.organisation_id=NEW.organisation_id AND close.school_id=NEW.school_id
              AND close.status='APPROVED' AND close.business_date>=NEW.open_date)
    ) THEN RAISE EXCEPTION 'reviewed posting requires exact receipt on eligible open financial date'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_resolutions_guard BEFORE INSERT ON fee_gateway_capture_resolutions FOR EACH ROW EXECUTE FUNCTION guard_gateway_resolution();

CREATE FUNCTION check_gateway_charge_availability() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE charge_id UUID; charge_amount NUMERIC; used_amount NUMERIC;
BEGIN
    charge_id:=NEW.learner_fee_charge_id;
    IF charge_id IS NULL THEN RETURN NULL; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    SELECT amount INTO charge_amount FROM learner_fee_charges WHERE id=charge_id;
    SELECT COALESCE((SELECT sum(amount) FROM fee_gateway_allocations WHERE learner_fee_charge_id=charge_id AND status='ACTIVE'),0)
        + COALESCE((SELECT sum(allocation.amount) FROM fee_payment_allocations allocation JOIN fee_payments payment
            ON payment.id=allocation.fee_payment_id AND payment.status='POSTED' WHERE allocation.learner_fee_charge_id=charge_id),0)
        + COALESCE((SELECT sum(amount) FROM fee_ledger_entries WHERE learner_fee_charge_id=charge_id AND entry_type='CONCESSION'),0)
        INTO used_amount;
    IF used_amount>charge_amount THEN RAISE EXCEPTION 'fee charge cannot be consumed by payments, concessions and active reservations more than once'; END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fee_gateway_charge_availability AFTER INSERT OR UPDATE ON fee_gateway_allocations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_gateway_charge_availability();
CREATE CONSTRAINT TRIGGER fee_payment_gateway_availability AFTER INSERT ON fee_payment_allocations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_gateway_charge_availability();
CREATE CONSTRAINT TRIGGER fee_concession_gateway_availability AFTER INSERT ON fee_ledger_entries DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.entry_type='CONCESSION') EXECUTE FUNCTION check_gateway_charge_availability();

CREATE FUNCTION check_gateway_intent_allocations() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE attempt_id_value UUID; attempt fee_gateway_attempts%ROWTYPE; total NUMERIC;
BEGIN
    IF TG_TABLE_NAME='fee_gateway_attempts' THEN attempt_id_value:=NEW.id;
    ELSE attempt_id_value:=NEW.attempt_id; END IF;
    SELECT * INTO attempt FROM fee_gateway_attempts WHERE id=attempt_id_value;
    SELECT COALESCE(sum(amount),0) INTO total FROM fee_gateway_allocations WHERE attempt_id=attempt_id_value;
    IF total<>attempt.amount OR NOT EXISTS (SELECT 1 FROM fee_gateway_allocations WHERE attempt_id=attempt_id_value) THEN
        RAISE EXCEPTION 'gateway intent requires exact frozen charge allocations in the same transaction';
    END IF;
    IF (attempt.status IN ('RESERVED','CHECKOUT_PENDING','CAPTURED_REVIEW_REQUIRED') AND EXISTS (
        SELECT 1 FROM fee_gateway_allocations WHERE attempt_id=attempt_id_value AND status<>'ACTIVE'))
       OR (attempt.status='RELEASED' AND EXISTS (SELECT 1 FROM fee_gateway_allocations WHERE attempt_id=attempt_id_value AND status<>'RELEASED'))
       OR (attempt.status='POSTED' AND EXISTS (SELECT 1 FROM fee_gateway_allocations WHERE attempt_id=attempt_id_value AND status<>'CONSUMED')) THEN
        -- Captures after earlier release are reviewed without silently re-reserving obligations.
        IF NOT (attempt.status='CAPTURED_REVIEW_REQUIRED' AND NOT EXISTS (SELECT 1 FROM fee_gateway_allocations WHERE attempt_id=attempt_id_value AND status<>'RELEASED')) THEN
            RAISE EXCEPTION 'gateway intent and one-use reservation disposition must agree';
        END IF;
    END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fee_gateway_intent_allocation_total AFTER INSERT OR UPDATE ON fee_gateway_attempts DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_gateway_intent_allocations();
CREATE CONSTRAINT TRIGGER fee_gateway_allocation_intent_total AFTER INSERT OR UPDATE ON fee_gateway_allocations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_gateway_intent_allocations();

DO $$ DECLARE table_name TEXT; BEGIN
    FOREACH table_name IN ARRAY ARRAY['fee_gateway_attempts','fee_gateway_allocations','fee_gateway_checkout_observations','fee_gateway_captures','fee_gateway_capture_resolutions'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY %I ON %I USING (organisation_id=current_app_organisation_id()) WITH CHECK (organisation_id=current_app_organisation_id())',table_name||'_tenant',table_name);
        EXECUTE format('CREATE TRIGGER %I BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation()',table_name||'_prevent_delete',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['fee_gateway_checkout_observations','fee_gateway_captures','fee_gateway_capture_resolutions'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation()',table_name||'_immutable',table_name);
    END LOOP;
END $$;

COMMENT ON TABLE fee_gateway_attempts IS 'Immutable family fee intent committed before network calls. Pending/review states are not ledger credits or receipts.';
COMMENT ON TABLE fee_gateway_captures IS 'Minimal append-only server-verified capture facts, preserving additional/orphan payments and original gateway times. Never raw customer/card payloads.';
COMMENT ON TABLE fee_gateway_capture_resolutions IS 'Reviewed dispositions append history; a held capture never reopens or changes approved closed-day evidence.';

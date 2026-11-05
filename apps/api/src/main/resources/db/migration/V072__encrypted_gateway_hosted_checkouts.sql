-- Additive private checkout evidence only. No URL backfill, provider activation,
-- intent mutation, receipt issuance or financial reservation release.
CREATE TABLE fee_gateway_hosted_checkouts (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    attempt_id UUID NOT NULL, learner_fee_account_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK (provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL,
    credentials_secret_reference VARCHAR(240) NOT NULL,
    provider_checkout_reference UUID NOT NULL CHECK (provider_checkout_reference=attempt_id),
    -- PostgreSQL's bounded-regex repetition ceiling is 255. Enforce the same
    -- 24..512 byte-safe base64url shape with a length check and unbounded class.
    url_ciphertext VARCHAR(512) NOT NULL CHECK (length(url_ciphertext) BETWEEN 24 AND 512 AND url_ciphertext ~ '^[A-Za-z0-9_-]+$'),
    url_nonce VARCHAR(16) NOT NULL CHECK (url_nonce ~ '^[A-Za-z0-9_-]{16}$'),
    url_sha256 CHAR(64) NOT NULL CHECK (url_sha256 ~ '^[a-f0-9]{64}$'),
    source_kind VARCHAR(10) NOT NULL CHECK (source_kind IN ('CREATE','GET')),
    source_lease_token UUID NOT NULL, source_lease_started_at TIMESTAMPTZ NOT NULL,
    source_lease_until TIMESTAMPTZ NOT NULL, source_attempt_count INTEGER NOT NULL CHECK (source_attempt_count BETWEEN 1 AND 1000000),
    observed_at TIMESTAMPTZ NOT NULL CHECK (isfinite(observed_at)), observed_at_exact VARCHAR(30) NOT NULL,
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (id,organisation_id,school_id), UNIQUE (attempt_id,url_sha256),
    FOREIGN KEY (attempt_id,learner_fee_account_id,organisation_id,school_id)
        REFERENCES fee_gateway_attempts(id,learner_fee_account_id,organisation_id,school_id),
    FOREIGN KEY (payment_provider_config_id,organisation_id,school_id)
        REFERENCES payment_provider_configs(id,organisation_id,school_id),
    CHECK (merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$'),
    CHECK (credentials_secret_reference ~ '^[A-Za-z][A-Za-z0-9_./:-]{2,239}$'),
    CHECK (source_lease_until>source_lease_started_at AND source_lease_until<=source_lease_started_at+interval '2 minutes'),
    CHECK (observed_at=gateway_instant_floor_us(observed_at_exact)),
    CHECK (isfinite(retained_at) AND observed_at>=source_lease_started_at AND observed_at<=retained_at),
    CHECK (gateway_instant_epoch_ns(observed_at_exact)<=extract(epoch FROM retained_at)*1000000000)
);
CREATE INDEX fee_gateway_hosted_checkouts_attempt_idx
    ON fee_gateway_hosted_checkouts(organisation_id,school_id,attempt_id,retained_at,id);

CREATE FUNCTION guard_gateway_hosted_checkout_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE work fee_gateway_attempt_work%ROWTYPE; attempt fee_gateway_attempts%ROWTYPE;
BEGIN
    IF NEW.organisation_id IS DISTINCT FROM current_app_organisation_id() OR current_app_user_id() IS NOT NULL THEN
        RAISE EXCEPTION 'private gateway evidence requires an exact background school scope';
    END IF;
    SELECT * INTO work FROM fee_gateway_attempt_work
        WHERE attempt_id=NEW.attempt_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    SELECT * INTO attempt FROM fee_gateway_attempts
        WHERE id=NEW.attempt_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    IF work.attempt_id IS NULL OR attempt.id IS NULL OR work.create_dispatched_at IS NULL
        OR work.status<>'LEASED' OR work.lease_token IS DISTINCT FROM NEW.source_lease_token
        OR work.lease_started_at IS DISTINCT FROM NEW.source_lease_started_at
        OR work.lease_until IS DISTINCT FROM NEW.source_lease_until OR work.attempt_count<>NEW.source_attempt_count
        OR work.lease_until<=clock_timestamp()
        OR attempt.learner_fee_account_id<>NEW.learner_fee_account_id
        OR work.payment_provider_config_id<>NEW.payment_provider_config_id OR attempt.payment_provider_config_id<>NEW.payment_provider_config_id
        OR work.provider_code<>NEW.provider_code OR attempt.provider_code<>NEW.provider_code
        OR work.provider_environment<>NEW.provider_environment OR attempt.provider_environment<>NEW.provider_environment
        OR work.merchant_account_reference<>NEW.merchant_account_reference OR attempt.merchant_account_reference<>NEW.merchant_account_reference
        OR work.credentials_secret_reference<>NEW.credentials_secret_reference
        OR gateway_instant_epoch_ns(NEW.observed_at_exact)<extract(epoch FROM work.create_dispatched_at)*1000000000
        OR gateway_instant_epoch_ns(NEW.observed_at_exact)>extract(epoch FROM clock_timestamp())*1000000000
        OR (NEW.source_kind='CREATE' AND work.create_dispatched_by_lease_token IS DISTINCT FROM NEW.source_lease_token)
        OR NOT EXISTS (SELECT 1 FROM payment_provider_configs provider
            WHERE provider.id=NEW.payment_provider_config_id AND provider.organisation_id=NEW.organisation_id AND provider.school_id=NEW.school_id
              AND provider.provider_code=NEW.provider_code AND provider.environment=NEW.provider_environment
              AND provider.merchant_account_reference=NEW.merchant_account_reference
              AND provider.credentials_secret_reference=NEW.credentials_secret_reference) THEN
        RAISE EXCEPTION 'private checkout evidence requires its frozen binding and genuinely dispatched current lease';
    END IF;
    -- Audit detection time is always DB-authored after any lock wait. A caller
    -- cannot manufacture infinity, a future time or a rounded earlier instant.
    NEW.retained_at:=clock_timestamp();
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_hosted_checkouts_10_finance_fence BEFORE INSERT ON fee_gateway_hosted_checkouts
    FOR EACH ROW EXECUTE FUNCTION acquire_gateway_finance_fence();
CREATE TRIGGER fee_gateway_hosted_checkouts_20_binding BEFORE INSERT ON fee_gateway_hosted_checkouts
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_hosted_checkout_insert();
CREATE TRIGGER fee_gateway_hosted_checkouts_append_only BEFORE UPDATE OR DELETE ON fee_gateway_hosted_checkouts
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();

ALTER TABLE fee_gateway_hosted_checkouts ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_gateway_hosted_checkouts FORCE ROW LEVEL SECURITY;
CREATE POLICY fee_gateway_hosted_checkouts_insert ON fee_gateway_hosted_checkouts FOR INSERT
    WITH CHECK (organisation_id=current_app_organisation_id() AND current_app_user_id() IS NULL);
CREATE POLICY fee_gateway_hosted_checkouts_select ON fee_gateway_hosted_checkouts FOR SELECT USING (
    organisation_id=current_app_organisation_id() AND (current_app_user_id() IS NULL OR EXISTS (
        SELECT 1 FROM fee_gateway_attempts attempt
        JOIN learner_fee_accounts account ON account.id=attempt.learner_fee_account_id
            AND account.organisation_id=attempt.organisation_id AND account.school_id=attempt.school_id
        JOIN learners learner ON learner.id=account.learner_id
            AND learner.organisation_id=account.organisation_id AND learner.school_id=account.school_id AND learner.status='ACTIVE'
        JOIN schools school ON school.id=account.school_id AND school.organisation_id=account.organisation_id AND school.status='ACTIVE'
        JOIN organisations organisation ON organisation.id=account.organisation_id AND organisation.status='ACTIVE'
        JOIN user_accounts actor ON actor.id=attempt.initiated_by_user_id AND actor.status='ACTIVE'
        JOIN guardians guardian ON guardian.user_account_id=actor.id AND guardian.organisation_id=account.organisation_id
            AND guardian.school_id=account.school_id AND guardian.status='ACTIVE'
        JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id AND relationship.learner_id=learner.id
            AND relationship.organisation_id=account.organisation_id AND relationship.school_id=account.school_id AND relationship.portal_access_status='ACTIVE'
        WHERE attempt.id=fee_gateway_hosted_checkouts.attempt_id AND attempt.organisation_id=fee_gateway_hosted_checkouts.organisation_id
            AND attempt.school_id=fee_gateway_hosted_checkouts.school_id AND actor.id=current_app_user_id()
            AND EXISTS (SELECT 1 FROM memberships membership
                JOIN membership_roles mr ON mr.membership_id=membership.id JOIN roles role ON role.id=mr.role_id
                JOIN role_capabilities rc ON rc.role_id=role.id AND rc.capability_code='portal.guardian'
                WHERE membership.user_id=actor.id AND membership.organisation_id=account.organisation_id AND membership.school_id=account.school_id
                    AND membership.status='ACTIVE' AND membership.valid_from<=clock_timestamp()
                    AND (membership.valid_until IS NULL OR membership.valid_until>clock_timestamp())
                    AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id))
    ))
);
-- No UPDATE/DELETE policy. The trigger also protects historical evidence against
-- privileged maintenance accidentally editing it rather than adding a new fact.
COMMENT ON TABLE fee_gateway_hosted_checkouts IS 'Append-only encrypted private hosted-link evidence. Exact retries preserve the first row; multiple URL hashes for an attempt prohibit continuation. No payment, receipt or release authority. Current family authorization and payable-state checks remain mandatory before disclosure.';
COMMENT ON COLUMN fee_gateway_hosted_checkouts.source_lease_token IS 'Current-lease provenance for private link retention, not economic proof. Stale link writes may be refused; independently verified payment facts must still reach the existing finalizer.';

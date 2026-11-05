-- Human refund intent and independent approval only. No provider call, worker,
-- learner debit, financial journal or invented refund completion is introduced.
ALTER TABLE fee_payment_allocations ADD CONSTRAINT fee_payment_allocation_refund_source_unique
    UNIQUE(id,fee_payment_id,learner_fee_charge_id,organisation_id,school_id);

CREATE TABLE fee_gateway_refund_requests (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    fee_payment_id UUID NOT NULL, learner_fee_account_id UUID NOT NULL, capture_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK(provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL, credentials_secret_reference VARCHAR(240) NOT NULL,
    order_reference VARCHAR(100) NOT NULL, payment_reference VARCHAR(100) NOT NULL,
    currency_code CHAR(3) NOT NULL CHECK(currency_code='INR'), school_time_zone VARCHAR(60) NOT NULL,
    amount NUMERIC NOT NULL CHECK(amount>0 AND amount<=100000000 AND scale(amount)<=2),
    client_request_id UUID NOT NULL, request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
    request_reason VARCHAR(500) NOT NULL CHECK(length(btrim(request_reason)) BETWEEN 3 AND 500 AND request_reason !~ '[[:cntrl:]]'),
    requested_by_user_id UUID NOT NULL REFERENCES user_accounts(id), requested_by_membership_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL CHECK(isfinite(requested_at)),
    UNIQUE(id,organisation_id,school_id),
    UNIQUE(id,fee_payment_id,learner_fee_account_id,organisation_id,school_id),
    UNIQUE(school_id,requested_by_user_id,client_request_id),
    FOREIGN KEY(fee_payment_id,organisation_id,school_id) REFERENCES fee_payments(id,organisation_id,school_id),
    FOREIGN KEY(learner_fee_account_id,organisation_id,school_id) REFERENCES learner_fee_accounts(id,organisation_id,school_id),
    FOREIGN KEY(capture_id,organisation_id,school_id) REFERENCES fee_gateway_captures(id,organisation_id,school_id),
    FOREIGN KEY(payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    FOREIGN KEY(requested_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CHECK(credentials_secret_reference ~ '^[A-Za-z][A-Za-z0-9_./:-]{2,239}$')
);

CREATE TABLE fee_gateway_refund_allocations (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    refund_request_id UUID NOT NULL, fee_payment_id UUID NOT NULL, learner_fee_account_id UUID NOT NULL,
    original_payment_allocation_id UUID NOT NULL, learner_fee_charge_id UUID NOT NULL, fee_head_id UUID NOT NULL,
    amount NUMERIC NOT NULL CHECK(amount>0 AND amount<=100000000 AND scale(amount)<=2),
    policy_refundable BOOLEAN NOT NULL CHECK(policy_refundable),
    UNIQUE(refund_request_id,learner_fee_charge_id), UNIQUE(id,organisation_id,school_id),
    FOREIGN KEY(refund_request_id,fee_payment_id,learner_fee_account_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_requests(id,fee_payment_id,learner_fee_account_id,organisation_id,school_id),
    FOREIGN KEY(original_payment_allocation_id,fee_payment_id,learner_fee_charge_id,organisation_id,school_id)
        REFERENCES fee_payment_allocations(id,fee_payment_id,learner_fee_charge_id,organisation_id,school_id),
    FOREIGN KEY(learner_fee_charge_id,learner_fee_account_id,organisation_id,school_id)
        REFERENCES learner_fee_charges(id,learner_fee_account_id,organisation_id,school_id),
    FOREIGN KEY(fee_head_id,organisation_id,school_id) REFERENCES fee_heads(id,organisation_id,school_id)
);

CREATE TABLE fee_gateway_refund_decisions (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    refund_request_id UUID NOT NULL UNIQUE,
    decision VARCHAR(10) NOT NULL CHECK(decision IN ('APPROVE','REJECT','CANCEL')),
    reason VARCHAR(500) NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 500 AND reason !~ '[[:cntrl:]]'),
    decided_by_user_id UUID NOT NULL REFERENCES user_accounts(id), decided_by_membership_id UUID NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL CHECK(isfinite(decided_at)),
    UNIQUE(id,organisation_id,school_id),
    FOREIGN KEY(refund_request_id,organisation_id,school_id) REFERENCES fee_gateway_refund_requests(id,organisation_id,school_id),
    FOREIGN KEY(decided_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id)
);
CREATE INDEX fee_gateway_refund_request_payment_idx ON fee_gateway_refund_requests(organisation_id,school_id,fee_payment_id,requested_at DESC,id);
CREATE INDEX fee_gateway_refund_request_capture_idx ON fee_gateway_refund_requests(capture_id);
CREATE INDEX fee_gateway_refund_allocation_source_idx ON fee_gateway_refund_allocations(original_payment_allocation_id);

-- Technical write fence, not a balance. Like the existing V040 pattern, an
-- actual row write invalidates a stale REPEATABLE READ/SERIALIZABLE snapshot;
-- advisory locking alone cannot protect aggregate refund budgets at those levels.
CREATE TABLE fee_gateway_refund_fences (
    capture_id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 0 CHECK(epoch>=0),
    FOREIGN KEY(capture_id,organisation_id,school_id) REFERENCES fee_gateway_captures(id,organisation_id,school_id)
);
CREATE FUNCTION touch_gateway_refund_fence(capture_value UUID,organisation_value UUID,school_value UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO fee_gateway_refund_fences(capture_id,organisation_id,school_id,epoch)
    VALUES(capture_value,organisation_value,school_value,0)
    ON CONFLICT(capture_id) DO UPDATE SET epoch=fee_gateway_refund_fences.epoch+1
    WHERE fee_gateway_refund_fences.organisation_id=organisation_value AND fee_gateway_refund_fences.school_id=school_value;
    IF NOT FOUND THEN RAISE EXCEPTION 'refund concurrency scope mismatch'; END IF;
END; $$;

CREATE FUNCTION require_gateway_refund_actor(organisation_value UUID,school_value UUID,membership_value UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM organisation_value OR current_app_user_id() IS NULL THEN
        RAISE EXCEPTION 'refund intent requires the actual human tenant context';
    END IF;
    PERFORM 1 FROM memberships member
    JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
    JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
    JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
    JOIN membership_roles assignment ON assignment.membership_id=member.id
    JOIN roles role ON role.id=assignment.role_id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
    JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code='fees.refund'
    WHERE member.id=membership_value AND member.user_id=current_app_user_id()
        AND member.organisation_id=organisation_value AND member.school_id=school_value AND member.status='ACTIVE'
        AND member.valid_from<=clock_timestamp() AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
    FOR SHARE OF member,actor,school,assignment;
    IF NOT FOUND THEN RAISE EXCEPTION 'refund intent requires live school-scoped refund authority'; END IF;
END; $$;

CREATE FUNCTION guard_gateway_refund_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM require_gateway_refund_actor(NEW.organisation_id,NEW.school_id,NEW.requested_by_membership_id);
    IF NEW.requested_by_user_id IS DISTINCT FROM current_app_user_id() OR NEW.requested_at>clock_timestamp() THEN
        RAISE EXCEPTION 'refund maker and request time must be honest';
    END IF;
    -- A posted immutable capture-backed receipt is historical proof. Do not reuse
    -- the new-payment eligibility predicate: it requires a currently active fee
    -- account and no later extra capture, neither of which invalidates this receipt.
    PERFORM 1 FROM fee_payments payment
    JOIN learner_fee_accounts account ON account.id=payment.learner_fee_account_id
        AND account.organisation_id=payment.organisation_id AND account.school_id=payment.school_id
    JOIN fee_gateway_captures capture ON capture.id=payment.gateway_capture_id
        AND capture.organisation_id=payment.organisation_id AND capture.school_id=payment.school_id
    JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
        AND attempt.organisation_id=capture.organisation_id AND attempt.school_id=capture.school_id
    JOIN payment_provider_configs provider ON provider.id=capture.payment_provider_config_id
        AND provider.organisation_id=capture.organisation_id AND provider.school_id=capture.school_id
    WHERE payment.id=NEW.fee_payment_id AND payment.organisation_id=NEW.organisation_id AND payment.school_id=NEW.school_id
        AND payment.status='POSTED' AND payment.payment_method='ONLINE_PROVIDER' AND payment.amount=capture.amount
        AND payment.learner_fee_account_id=NEW.learner_fee_account_id AND account.currency_code=NEW.currency_code
        AND capture.id=NEW.capture_id AND capture.payment_provider_config_id=NEW.payment_provider_config_id
        AND capture.provider_code=NEW.provider_code AND capture.provider_environment=NEW.provider_environment
        AND capture.merchant_account_reference=NEW.merchant_account_reference AND capture.currency_code=NEW.currency_code
        AND capture.order_reference=NEW.order_reference AND capture.payment_reference=NEW.payment_reference
        AND provider.provider_code=NEW.provider_code AND provider.environment=NEW.provider_environment
        AND provider.merchant_account_reference=NEW.merchant_account_reference
        AND provider.credentials_secret_reference=NEW.credentials_secret_reference
        AND attempt.learner_fee_account_id=account.id AND attempt.school_time_zone=NEW.school_time_zone
        AND NEW.amount<=capture.amount AND NEW.requested_at>=payment.created_at
        AND EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions resolution
            WHERE resolution.capture_id=capture.id AND resolution.organisation_id=NEW.organisation_id
                AND resolution.school_id=NEW.school_id AND resolution.resolution_type='POSTED'
                AND resolution.fee_payment_id=payment.id AND resolution.open_date=payment.received_on)
    FOR SHARE OF payment,account,capture,attempt,provider;
    IF NOT FOUND THEN RAISE EXCEPTION 'refund intent must retain the exact original posted receipt and server-owned provider binding'; END IF;
    PERFORM touch_gateway_refund_fence(NEW.capture_id,NEW.organisation_id,NEW.school_id);
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_request_guard BEFORE INSERT ON fee_gateway_refund_requests
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_refund_request();

CREATE FUNCTION guard_gateway_refund_allocation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE request fee_gateway_refund_requests%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    SELECT * INTO request FROM fee_gateway_refund_requests WHERE id=NEW.refund_request_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF request.id IS NULL OR request.requested_by_user_id IS DISTINCT FROM current_app_user_id() THEN
        RAISE EXCEPTION 'refund allocations require the original maker and school';
    END IF;
    PERFORM require_gateway_refund_actor(NEW.organisation_id,NEW.school_id,request.requested_by_membership_id);
    IF EXISTS(SELECT 1 FROM fee_gateway_refund_decisions WHERE refund_request_id=request.id) THEN
        RAISE EXCEPTION 'reviewed refund terms cannot be extended';
    END IF;
    PERFORM 1 FROM fee_payment_allocations source
    JOIN learner_fee_charges charge ON charge.id=source.learner_fee_charge_id
        AND charge.organisation_id=source.organisation_id AND charge.school_id=source.school_id
    JOIN fee_plan_items item ON item.id=charge.fee_plan_item_id AND item.organisation_id=charge.organisation_id AND item.school_id=charge.school_id
    JOIN fee_heads head ON head.id=item.fee_head_id AND head.organisation_id=item.organisation_id AND head.school_id=item.school_id
    WHERE source.id=NEW.original_payment_allocation_id AND source.fee_payment_id=request.fee_payment_id
        AND source.organisation_id=NEW.organisation_id AND source.school_id=NEW.school_id
        AND charge.id=NEW.learner_fee_charge_id AND charge.learner_fee_account_id=request.learner_fee_account_id
        AND head.id=NEW.fee_head_id AND head.refundable AND NEW.policy_refundable AND NEW.amount<=source.amount
    FOR SHARE OF source,charge,item,head;
    IF NOT FOUND THEN RAISE EXCEPTION 'refund allocations require exact original paid allocations and configured refundable fee heads'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_allocation_guard BEFORE INSERT ON fee_gateway_refund_allocations
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_refund_allocation();

CREATE FUNCTION guard_gateway_refund_decision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE request fee_gateway_refund_requests%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM require_gateway_refund_actor(NEW.organisation_id,NEW.school_id,NEW.decided_by_membership_id);
    SELECT * INTO request FROM fee_gateway_refund_requests WHERE id=NEW.refund_request_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR UPDATE;
    IF request.id IS NULL OR NEW.decided_by_user_id IS DISTINCT FROM current_app_user_id()
        OR NEW.decided_at<request.requested_at OR NEW.decided_at>clock_timestamp()
        OR (NEW.decision='CANCEL' AND NEW.decided_by_user_id<>request.requested_by_user_id)
        OR (NEW.decision IN ('APPROVE','REJECT') AND NEW.decided_by_user_id=request.requested_by_user_id) THEN
        RAISE EXCEPTION 'refund review requires a different authorized person; only the maker may withdraw an unreviewed request';
    END IF;
    PERFORM touch_gateway_refund_fence(request.capture_id,NEW.organisation_id,NEW.school_id);
    IF NEW.decision='APPROVE' THEN
        PERFORM 1 FROM fee_gateway_refund_allocations allocation
        JOIN fee_heads head ON head.id=allocation.fee_head_id
            AND head.organisation_id=allocation.organisation_id AND head.school_id=allocation.school_id
        WHERE allocation.refund_request_id=request.id FOR SHARE OF head;
        IF EXISTS(SELECT 1 FROM fee_gateway_refund_allocations allocation JOIN fee_heads head ON head.id=allocation.fee_head_id
                AND head.organisation_id=allocation.organisation_id AND head.school_id=allocation.school_id
                WHERE allocation.refund_request_id=request.id AND NOT head.refundable)
            OR NOT EXISTS(SELECT 1 FROM payment_provider_configs provider WHERE provider.id=request.payment_provider_config_id
                AND provider.organisation_id=request.organisation_id AND provider.school_id=request.school_id
                AND provider.provider_code=request.provider_code AND provider.environment=request.provider_environment
                AND provider.merchant_account_reference=request.merchant_account_reference
                AND provider.credentials_secret_reference=request.credentials_secret_reference) THEN
            RAISE EXCEPTION 'refund approval requires current policy and the frozen provider binding';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_decision_guard BEFORE INSERT ON fee_gateway_refund_decisions
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_refund_decision();

CREATE FUNCTION check_gateway_refund_budget() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE request_id UUID; request fee_gateway_refund_requests%ROWTYPE; allocated NUMERIC; held NUMERIC; principal NUMERIC;
BEGIN
    -- Separate statements matter: a CASE expression binds both record fields
    -- before evaluation, but request rows do not have refund_request_id.
    IF TG_TABLE_NAME='fee_gateway_refund_requests' THEN
        request_id:=NEW.id;
    ELSE
        request_id:=NEW.refund_request_id;
    END IF;
    SELECT * INTO request FROM fee_gateway_refund_requests WHERE id=request_id;
    IF request.id IS NULL THEN RAISE EXCEPTION 'refund request scope is unavailable'; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(request.school_id::text || ':finance',0));
    SELECT sum(amount) INTO allocated FROM fee_gateway_refund_allocations WHERE refund_request_id=request.id;
    IF allocated IS NULL OR allocated<>request.amount OR (SELECT count(*) FROM fee_gateway_refund_allocations WHERE refund_request_id=request.id)>100 THEN
        RAISE EXCEPTION 'refund amount must equal its complete frozen original allocations' USING ERRCODE='23514';
    END IF;
    SELECT amount INTO principal FROM fee_gateway_captures WHERE id=request.capture_id AND organisation_id=request.organisation_id AND school_id=request.school_id;
    SELECT COALESCE(sum(other.amount),0) INTO held FROM fee_gateway_refund_requests other
        WHERE other.capture_id=request.capture_id AND other.organisation_id=request.organisation_id AND other.school_id=request.school_id
            AND NOT EXISTS(SELECT 1 FROM fee_gateway_refund_decisions decision WHERE decision.refund_request_id=other.id AND decision.decision IN ('REJECT','CANCEL'));
    IF principal IS NULL OR held>principal OR EXISTS(
        SELECT 1 FROM fee_gateway_refund_allocations allocation
        JOIN fee_gateway_refund_requests parent ON parent.id=allocation.refund_request_id
            AND parent.organisation_id=allocation.organisation_id AND parent.school_id=allocation.school_id
        JOIN fee_payment_allocations original ON original.id=allocation.original_payment_allocation_id
            AND original.organisation_id=allocation.organisation_id AND original.school_id=allocation.school_id
        WHERE parent.capture_id=request.capture_id AND parent.organisation_id=request.organisation_id AND parent.school_id=request.school_id
            AND NOT EXISTS(SELECT 1 FROM fee_gateway_refund_decisions decision WHERE decision.refund_request_id=parent.id AND decision.decision IN ('REJECT','CANCEL'))
        GROUP BY original.id,original.amount HAVING sum(allocation.amount)>original.amount
    ) THEN RAISE EXCEPTION 'refunds cannot exceed captured principal or any original paid allocation' USING ERRCODE='23514'; END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fee_gateway_refund_request_budget AFTER INSERT ON fee_gateway_refund_requests
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_gateway_refund_budget();
CREATE CONSTRAINT TRIGGER fee_gateway_refund_allocation_budget AFTER INSERT ON fee_gateway_refund_allocations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_gateway_refund_budget();
CREATE CONSTRAINT TRIGGER fee_gateway_refund_decision_budget AFTER INSERT ON fee_gateway_refund_decisions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_gateway_refund_budget();

DO $$ DECLARE table_name TEXT; BEGIN
    FOREACH table_name IN ARRAY ARRAY['fee_gateway_refund_requests','fee_gateway_refund_allocations','fee_gateway_refund_decisions'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation()',table_name||'_immutable',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['fee_gateway_refund_requests','fee_gateway_refund_allocations','fee_gateway_refund_decisions','fee_gateway_refund_fences'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY %I ON %I USING (organisation_id=current_app_organisation_id()) WITH CHECK (organisation_id=current_app_organisation_id())',table_name||'_tenant',table_name);
    END LOOP;
END $$;

COMMENT ON TABLE fee_gateway_refund_requests IS 'Immutable independently reviewed intent for an existing captured fee receipt. Approved/submitted amounts encumber original captured principal and allocations; no external execution or fee refund is implied.';
COMMENT ON TABLE fee_gateway_refund_decisions IS 'One append-only maker withdrawal or independent approval/rejection. Approval is not provider success, a fee debit, a receipt reversal or waiver.';

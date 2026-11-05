-- One immutable recognized success/HOLD source per approved refund. This is
-- deliberately not a dispatch, learner debit, receipt reversal, budget release,
-- financial completion or journal. V073 allocations remain the frozen terms.
-- Every new GET witness and recognition/replay writes the same V073 technical
-- capture epoch. An advisory lock alone does not refresh REPEATABLE READ or
-- SERIALIZABLE snapshots; an old snapshot must serialize/retry, not miss a
-- newer contradictory witness. This epoch is not an economic balance.
CREATE FUNCTION touch_gateway_refund_evidence_fence(refund_value UUID,organisation_value UUID,school_value UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE capture_value UUID;
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM organisation_value OR current_app_user_id() IS NOT NULL THEN
        RAISE EXCEPTION 'refund evidence fencing requires the matching background organisation scope';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(school_value::text || ':finance',0));
    SELECT capture_id INTO capture_value FROM fee_gateway_refund_requests WHERE id=refund_value
        AND organisation_id=organisation_value AND school_id=school_value;
    IF capture_value IS NULL THEN RAISE EXCEPTION 'refund evidence fencing requires its scoped original source'; END IF;
    PERFORM touch_gateway_refund_fence(capture_value,organisation_value,school_value);
END; $$;
CREATE FUNCTION fence_gateway_refund_read_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM touch_gateway_refund_evidence_fence(NEW.refund_request_id,NEW.organisation_id,NEW.school_id);
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_reads_concurrency_fence BEFORE INSERT ON fee_gateway_refund_reads
    FOR EACH ROW EXECUTE FUNCTION fence_gateway_refund_read_evidence();

CREATE TABLE fee_gateway_refund_successes (
    refund_request_id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    verified_read_id UUID NOT NULL UNIQUE, approval_decision_id UUID NOT NULL,
    fee_payment_id UUID NOT NULL, capture_id UUID NOT NULL, learner_fee_account_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK(provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL, credentials_secret_reference VARCHAR(240) NOT NULL,
    refund_reference VARCHAR(100) NOT NULL, order_reference VARCHAR(100) NOT NULL, payment_reference VARCHAR(100) NOT NULL,
    amount NUMERIC NOT NULL CHECK(amount>0 AND amount<=100000000 AND scale(amount)<=2),
    currency_code CHAR(3) NOT NULL CHECK(currency_code='INR'), school_time_zone VARCHAR(60) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL, processed_at_exact VARCHAR(30) NOT NULL, processed_on DATE NOT NULL,
    -- Version 1 preserves the V074 verifier's versioned source-facts hash. It
    -- never hashes a rounded timestamp or a later transport observation time.
    source_version SMALLINT NOT NULL DEFAULT 1 CHECK(source_version=1),
    evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[a-f0-9]{64}$'),
    recognized_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(recognized_at)),
    closed_day_close_id UUID,
    status VARCHAR(10) NOT NULL DEFAULT 'HOLD' CHECK(status='HOLD'),
    hold_reason VARCHAR(40) NOT NULL CHECK(hold_reason IN ('AWAITING_REVIEWED_POSTING','ORIGINAL_FINANCIAL_DAY_CLOSED')),
    UNIQUE(refund_request_id,organisation_id,school_id),
    FOREIGN KEY(refund_request_id,fee_payment_id,learner_fee_account_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_requests(id,fee_payment_id,learner_fee_account_id,organisation_id,school_id),
    FOREIGN KEY(verified_read_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_reads(id,organisation_id,school_id),
    FOREIGN KEY(approval_decision_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_decisions(id,organisation_id,school_id),
    FOREIGN KEY(capture_id,organisation_id,school_id)
        REFERENCES fee_gateway_captures(id,organisation_id,school_id),
    FOREIGN KEY(payment_provider_config_id,organisation_id,school_id)
        REFERENCES payment_provider_configs(id,organisation_id,school_id),
    FOREIGN KEY(closed_day_close_id,organisation_id,school_id)
        REFERENCES fee_day_closes(id,organisation_id,school_id),
    CONSTRAINT fee_gateway_refund_successes_exact_time CHECK(
        isfinite(processed_at) AND gateway_instant_floor_us(processed_at_exact)=processed_at
        AND processed_on BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    CONSTRAINT fee_gateway_refund_successes_close_shape CHECK(
        (closed_day_close_id IS NULL AND hold_reason='AWAITING_REVIEWED_POSTING')
        OR (closed_day_close_id IS NOT NULL AND hold_reason='ORIGINAL_FINANCIAL_DAY_CLOSED'))
);
CREATE INDEX fee_gateway_refund_successes_school_time_idx
    ON fee_gateway_refund_successes(organisation_id,school_id,recognized_at DESC,refund_request_id);
CREATE INDEX fee_gateway_refund_successes_payment_idx
    ON fee_gateway_refund_successes(organisation_id,school_id,fee_payment_id);
CREATE INDEX fee_gateway_refund_successes_close_idx
    ON fee_gateway_refund_successes(closed_day_close_id,organisation_id,school_id)
    WHERE closed_day_close_id IS NOT NULL;

CREATE FUNCTION guard_gateway_refund_success_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    request fee_gateway_refund_requests%ROWTYPE;
    approval fee_gateway_refund_decisions%ROWTYPE;
    witness fee_gateway_refund_reads%ROWTYPE;
    covering_close UUID;
    source_mismatches TEXT[];
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id OR current_app_user_id() IS NOT NULL THEN
        RAISE EXCEPTION 'refund success evidence requires the matching background organisation scope';
    END IF;
    PERFORM touch_gateway_refund_evidence_fence(NEW.refund_request_id,NEW.organisation_id,NEW.school_id);
    -- The capture epoch detects new refund reads, not school-wide day closes.
    -- A finance advisory lock cannot refresh an older RR/SERIALIZABLE snapshot
    -- of an approved close. New recognition must use READ COMMITTED so the
    -- covering-close lookup below sees history committed before this lock.
    -- Preserve V073/V074 isolation behavior; only this new anchor is restricted.
    IF current_setting('transaction_isolation') IS DISTINCT FROM 'read committed' THEN
        RAISE EXCEPTION 'new refund success recognition requires a fresh read committed transaction'
            USING ERRCODE='40001';
    END IF;
    SELECT * INTO request FROM fee_gateway_refund_requests WHERE id=NEW.refund_request_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    SELECT * INTO approval FROM fee_gateway_refund_decisions WHERE id=NEW.approval_decision_id
        AND refund_request_id=NEW.refund_request_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    -- A row-locking SELECT also needs UPDATE-policy visibility. V074 deliberately
    -- has no UPDATE policy: its witnesses cannot be changed or deleted. Read
    -- this immutable source without a row lock, under the shared evidence fence,
    -- rather than granting mutation policy merely to make FOR SHARE see it.
    SELECT * INTO witness FROM fee_gateway_refund_reads WHERE id=NEW.verified_read_id
        AND refund_request_id=NEW.refund_request_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    -- Constant predicate labels aid diagnosis without exposing any identities,
    -- amounts, times, provider references, credential labels or source values.
    source_mismatches:=array_remove(ARRAY[
        CASE WHEN request.id IS NULL THEN 'request_scope' END,
        CASE WHEN approval.id IS NULL THEN 'approval_scope' END,
        CASE WHEN witness.id IS NULL THEN 'witness_scope' END,
        CASE WHEN approval.decision IS DISTINCT FROM 'APPROVE' THEN 'approval_decision' END,
        CASE WHEN approval.decided_by_user_id IS NOT DISTINCT FROM request.requested_by_user_id THEN 'independent_actor' END,
        CASE WHEN witness.approval_decision_id IS DISTINCT FROM approval.id THEN 'witness_approval' END,
        CASE WHEN witness.outcome IS DISTINCT FROM 'VERIFIED_SUCCESS' THEN 'witness_outcome' END,
        CASE WHEN witness.refund_status IS DISTINCT FROM 'SUCCESS' THEN 'witness_status' END,
        CASE WHEN NEW.source_version IS DISTINCT FROM 1 THEN 'source_version' END,
        CASE WHEN NEW.status IS DISTINCT FROM 'HOLD' THEN 'hold_status' END,
        CASE WHEN NEW.fee_payment_id IS DISTINCT FROM request.fee_payment_id THEN 'original_payment' END,
        CASE WHEN NEW.capture_id IS DISTINCT FROM request.capture_id THEN 'original_capture' END,
        CASE WHEN NEW.learner_fee_account_id IS DISTINCT FROM request.learner_fee_account_id THEN 'original_account' END,
        CASE WHEN NEW.payment_provider_config_id IS DISTINCT FROM request.payment_provider_config_id THEN 'provider_config' END,
        CASE WHEN NEW.provider_code IS DISTINCT FROM request.provider_code THEN 'provider_code' END,
        CASE WHEN NEW.provider_environment IS DISTINCT FROM request.provider_environment THEN 'provider_environment' END,
        CASE WHEN NEW.merchant_account_reference IS DISTINCT FROM request.merchant_account_reference THEN 'merchant_binding' END,
        CASE WHEN NEW.credentials_secret_reference IS DISTINCT FROM request.credentials_secret_reference THEN 'credential_binding' END,
        CASE WHEN witness.payment_provider_config_id IS DISTINCT FROM request.payment_provider_config_id THEN 'witness_provider_config' END,
        CASE WHEN witness.provider_code IS DISTINCT FROM request.provider_code THEN 'witness_provider_code' END,
        CASE WHEN witness.provider_environment IS DISTINCT FROM request.provider_environment THEN 'witness_provider_environment' END,
        CASE WHEN witness.merchant_account_reference IS DISTINCT FROM request.merchant_account_reference THEN 'witness_merchant_binding' END,
        CASE WHEN witness.credentials_secret_reference IS DISTINCT FROM request.credentials_secret_reference THEN 'witness_credential_binding' END,
        CASE WHEN NEW.refund_reference IS DISTINCT FROM request.id::text THEN 'refund_identity' END,
        CASE WHEN NEW.refund_reference IS DISTINCT FROM witness.refund_reference THEN 'witness_refund_identity' END,
        CASE WHEN NEW.order_reference IS DISTINCT FROM request.order_reference THEN 'original_order' END,
        CASE WHEN NEW.order_reference IS DISTINCT FROM witness.order_reference THEN 'witness_order' END,
        CASE WHEN NEW.payment_reference IS DISTINCT FROM request.payment_reference THEN 'original_payment_reference' END,
        CASE WHEN NEW.payment_reference IS DISTINCT FROM witness.payment_reference THEN 'witness_payment_reference' END,
        CASE WHEN NEW.amount IS DISTINCT FROM request.amount THEN 'approved_amount' END,
        CASE WHEN NEW.amount IS DISTINCT FROM witness.amount THEN 'witness_amount' END,
        CASE WHEN NEW.currency_code IS DISTINCT FROM request.currency_code THEN 'approved_currency' END,
        CASE WHEN NEW.currency_code IS DISTINCT FROM witness.currency_code THEN 'witness_currency' END,
        CASE WHEN NEW.currency_code IS DISTINCT FROM 'INR' THEN 'supported_currency' END,
        CASE WHEN NEW.school_time_zone IS DISTINCT FROM request.school_time_zone THEN 'original_school_zone' END,
        CASE WHEN NEW.processed_at IS DISTINCT FROM witness.processed_at THEN 'processed_projection' END,
        CASE WHEN NEW.processed_at_exact IS DISTINCT FROM witness.processed_at_exact THEN 'processed_exact' END,
        CASE WHEN NEW.processed_on IS DISTINCT FROM witness.processed_on THEN 'processed_school_date' END,
        CASE WHEN NEW.evidence_sha256 IS DISTINCT FROM witness.evidence_sha256 THEN 'source_digest' END
    ],NULL);
    IF cardinality(source_mismatches)>0 THEN
        RAISE EXCEPTION 'refund success must retain its exact independently approved original source and verified read'
            USING DETAIL=array_to_string(source_mismatches,',');
    END IF;
    -- Historical source binding is intentional. Provider activation, credential
    -- rotation or a later account/session status must not erase observed money.
    PERFORM 1 FROM fee_payments payment
    JOIN fee_gateway_captures capture ON capture.id=payment.gateway_capture_id
        AND capture.organisation_id=payment.organisation_id AND capture.school_id=payment.school_id
    JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
        AND attempt.organisation_id=capture.organisation_id AND attempt.school_id=capture.school_id
    WHERE payment.id=request.fee_payment_id AND payment.organisation_id=request.organisation_id AND payment.school_id=request.school_id
        AND payment.learner_fee_account_id=request.learner_fee_account_id AND payment.gateway_capture_id=request.capture_id
        AND payment.payment_method='ONLINE_PROVIDER' AND payment.status='POSTED' AND payment.amount=capture.amount
        AND capture.payment_provider_config_id=request.payment_provider_config_id AND capture.provider_code=request.provider_code
        AND capture.provider_environment=request.provider_environment AND capture.merchant_account_reference=request.merchant_account_reference
        AND capture.order_reference=request.order_reference AND capture.payment_reference=request.payment_reference AND capture.currency_code='INR'
        AND request.amount<=capture.amount AND attempt.learner_fee_account_id=request.learner_fee_account_id
        AND attempt.school_time_zone=request.school_time_zone
        AND EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions resolution WHERE resolution.capture_id=capture.id
            AND resolution.organisation_id=capture.organisation_id AND resolution.school_id=capture.school_id
            AND resolution.resolution_type='POSTED' AND resolution.fee_payment_id=payment.id AND resolution.open_date=payment.received_on)
    FOR SHARE OF payment,capture,attempt;
    IF NOT FOUND THEN RAISE EXCEPTION 'refund success requires the preserved original posted gateway receipt'; END IF;
    IF NEW.processed_on IS DISTINCT FROM gateway_refund_school_date(NEW.processed_at_exact,request.school_time_zone) THEN
        RAISE EXCEPTION 'refund success date must preserve its full original instant and school time zone';
    END IF;
    IF EXISTS(SELECT 1 FROM fee_gateway_refund_reads other WHERE other.refund_request_id=request.id
        AND other.organisation_id=request.organisation_id AND other.school_id=request.school_id
        AND other.outcome='VERIFIED_SUCCESS' AND other.evidence_sha256<>witness.evidence_sha256) THEN
        RAISE EXCEPTION 'conflicting refund success facts require reconciliation without replacing evidence' USING ERRCODE='23514';
    END IF;
    SELECT id INTO covering_close FROM fee_day_closes WHERE organisation_id=NEW.organisation_id AND school_id=NEW.school_id
        AND status='APPROVED' AND business_date>=NEW.processed_on ORDER BY business_date DESC,id LIMIT 1;
    IF NEW.closed_day_close_id IS DISTINCT FROM covering_close
        OR NEW.hold_reason IS DISTINCT FROM (CASE WHEN covering_close IS NULL
            THEN 'AWAITING_REVIEWED_POSTING' ELSE 'ORIGINAL_FINANCIAL_DAY_CLOSED' END) THEN
        RAISE EXCEPTION 'refund success must preserve the close covering its original processing day at recognition';
    END IF;
    -- Only the database chooses recognition time. Retrying later never stamps a
    -- new time onto the already immutable first anchor or recomputes its close.
    NEW.recognized_at:=clock_timestamp();
    IF NEW.recognized_at<witness.observed_at THEN
        RAISE EXCEPTION 'refund recognition cannot precede its retained independent read';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_successes_guard BEFORE INSERT ON fee_gateway_refund_successes
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_refund_success_insert();
CREATE TRIGGER fee_gateway_refund_successes_append_only BEFORE UPDATE OR DELETE ON fee_gateway_refund_successes
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
ALTER TABLE fee_gateway_refund_successes ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_gateway_refund_successes FORCE ROW LEVEL SECURITY;
CREATE POLICY fee_gateway_refund_successes_select ON fee_gateway_refund_successes FOR SELECT
    USING(organisation_id=current_app_organisation_id());
CREATE POLICY fee_gateway_refund_successes_insert ON fee_gateway_refund_successes FOR INSERT
    WITH CHECK(organisation_id=current_app_organisation_id() AND current_app_user_id() IS NULL);
COMMENT ON TABLE fee_gateway_refund_successes IS 'One append-only SYSTEM recognition/HOLD anchor per approved refund from an independently verified retained GET. New inserts require READ COMMITTED for fresh school-wide close evidence; other isolation fails with retryable 40001. Never financial completion, restored dues, provider dispatch, budget release, receipt reversal or GL posting. Future reviewed posting must recheck contradictions and current open-date authority.';
COMMENT ON COLUMN fee_gateway_refund_successes.closed_day_close_id IS 'Immutable approved fee close covering the original refund processing date when first recognized. Later closes never replace it or change the original date.';
COMMENT ON COLUMN fee_gateway_refund_successes.source_version IS 'Version 1 preserves the committed V074 versioned exact source-facts hash and original canonical nanosecond instant; later transport retry timestamps are not source facts.';
COMMENT ON COLUMN fee_gateway_refund_successes.credentials_secret_reference IS 'Original approved nonsecret provider credential label only, never the secret value or current rotated binding.';

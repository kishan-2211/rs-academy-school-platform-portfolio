-- Explicit human review of the original CLOSED fee account for a retained
-- verified refund HOLD. This is not a refund, restored due, academic reopening,
-- provider command, financial-date adjustment or accounting posting.
CREATE TABLE fee_refund_account_reopen_requests (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    refund_request_id UUID NOT NULL, fee_payment_id UUID NOT NULL, learner_fee_account_id UUID NOT NULL,
    verified_read_id UUID NOT NULL, success_evidence_sha256 CHAR(64) NOT NULL,
    success_source_version SMALLINT NOT NULL CHECK(success_source_version=1),
    account_academic_session_id UUID NOT NULL, account_learner_id UUID NOT NULL, account_enrolment_id UUID NOT NULL,
    account_currency_code CHAR(3) NOT NULL, account_opened_by_membership_id UUID NOT NULL,
    account_opened_at TIMESTAMPTZ NOT NULL CHECK(isfinite(account_opened_at)),
    account_closed_by_membership_id UUID NOT NULL, account_closed_at TIMESTAMPTZ NOT NULL CHECK(isfinite(account_closed_at)),
    account_close_reason VARCHAR(240) NOT NULL CHECK(length(btrim(account_close_reason)) BETWEEN 1 AND 240),
    closed_by_name_snapshot TEXT NOT NULL CHECK(length(btrim(closed_by_name_snapshot)) BETWEEN 1 AND 200),
    account_row_version BIGINT NOT NULL CHECK(account_row_version>=0),
    client_request_id UUID NOT NULL, request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
    reason VARCHAR(500) NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 500 AND reason !~ '[[:cntrl:]]'),
    requested_by_user_id UUID NOT NULL REFERENCES user_accounts(id), requested_by_membership_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(requested_at)),
    UNIQUE(id,organisation_id,school_id),
    UNIQUE(id,learner_fee_account_id,account_row_version,organisation_id,school_id),
    UNIQUE(school_id,requested_by_user_id,client_request_id),
    FOREIGN KEY(refund_request_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_successes(refund_request_id,organisation_id,school_id),
    FOREIGN KEY(refund_request_id,fee_payment_id,learner_fee_account_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_requests(id,fee_payment_id,learner_fee_account_id,organisation_id,school_id),
    FOREIGN KEY(verified_read_id,organisation_id,school_id) REFERENCES fee_gateway_refund_reads(id,organisation_id,school_id),
    FOREIGN KEY(learner_fee_account_id,organisation_id,school_id) REFERENCES learner_fee_accounts(id,organisation_id,school_id),
    FOREIGN KEY(requested_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CHECK(success_evidence_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE TABLE fee_refund_account_reopen_decisions (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    reopen_request_id UUID NOT NULL UNIQUE, learner_fee_account_id UUID NOT NULL, closed_account_version BIGINT NOT NULL,
    decision VARCHAR(10) NOT NULL CHECK(decision IN ('APPROVE','REJECT','CANCEL')),
    reason VARCHAR(500) NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 500 AND reason !~ '[[:cntrl:]]'),
    decided_by_user_id UUID NOT NULL REFERENCES user_accounts(id), decided_by_membership_id UUID NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(decided_at)),
    applied_account_version BIGINT,
    UNIQUE(id,organisation_id,school_id),
    FOREIGN KEY(reopen_request_id,learner_fee_account_id,closed_account_version,organisation_id,school_id)
        REFERENCES fee_refund_account_reopen_requests(id,learner_fee_account_id,account_row_version,organisation_id,school_id),
    FOREIGN KEY(decided_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CHECK((decision='APPROVE' AND applied_account_version IS NOT NULL AND applied_account_version=closed_account_version+1)
        OR (decision IN ('REJECT','CANCEL') AND applied_account_version IS NULL))
);
CREATE UNIQUE INDEX fee_refund_account_reopen_one_transition_idx
    ON fee_refund_account_reopen_decisions(learner_fee_account_id,closed_account_version) WHERE decision='APPROVE';
CREATE INDEX fee_refund_account_reopen_source_idx ON fee_refund_account_reopen_requests(organisation_id,school_id,refund_request_id,requested_at DESC,id);
CREATE INDEX fee_refund_account_reopen_account_idx ON fee_refund_account_reopen_requests(learner_fee_account_id,requested_at DESC,id);

-- Technical serialization only, never an account balance or meaningful account
-- version. An advisory-lock wait alone does not refresh RR/SERIALIZABLE reads.
CREATE TABLE fee_refund_account_reopen_fences (
    learner_fee_account_id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 0 CHECK(epoch>=0),
    FOREIGN KEY(learner_fee_account_id,organisation_id,school_id) REFERENCES learner_fee_accounts(id,organisation_id,school_id)
);
CREATE FUNCTION touch_refund_account_reopen_fence(account_value UUID,organisation_value UUID,school_value UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO fee_refund_account_reopen_fences(learner_fee_account_id,organisation_id,school_id,epoch)
    VALUES(account_value,organisation_value,school_value,0)
    ON CONFLICT(learner_fee_account_id) DO UPDATE SET epoch=fee_refund_account_reopen_fences.epoch+1
    WHERE fee_refund_account_reopen_fences.organisation_id=organisation_value
        AND fee_refund_account_reopen_fences.school_id=school_value;
    IF NOT FOUND THEN RAISE EXCEPTION 'account reopening concurrency scope mismatch'; END IF;
END; $$;

-- A retained historical success remains evidence, but contradictory SUCCESS
-- facts require reconciliation. Unknown/pending reads do not erase history.
CREATE FUNCTION refund_account_reopening_source_ready(refund_value UUID,organisation_value UUID,school_value UUID)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS(
        SELECT 1 FROM fee_gateway_refund_successes success
        JOIN fee_gateway_refund_decisions approval ON approval.id=success.approval_decision_id
            AND approval.refund_request_id=success.refund_request_id
            AND approval.organisation_id=success.organisation_id AND approval.school_id=success.school_id
        WHERE success.refund_request_id=refund_value AND success.organisation_id=organisation_value AND success.school_id=school_value
            AND success.status='HOLD' AND success.source_version=1 AND approval.decision='APPROVE'
            AND NOT EXISTS(SELECT 1 FROM fee_gateway_refund_reads witness
                WHERE witness.refund_request_id=success.refund_request_id
                    AND witness.organisation_id=success.organisation_id AND witness.school_id=success.school_id
                    AND ((witness.outcome='VERIFIED_SUCCESS' AND witness.evidence_sha256<>success.evidence_sha256)
                        OR (witness.outcome='REVIEW_REQUIRED' AND witness.refund_status='SUCCESS')))
    );
$$;

CREATE FUNCTION guard_refund_account_reopen_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE account learner_fee_accounts%ROWTYPE; success fee_gateway_refund_successes%ROWTYPE; closer_name TEXT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM require_gateway_refund_actor(NEW.organisation_id,NEW.school_id,NEW.requested_by_membership_id);
    IF NEW.requested_by_user_id IS DISTINCT FROM current_app_user_id() THEN
        RAISE EXCEPTION 'account reopening requires the actual maker';
    END IF;
    -- Immutable SELECT/INSERT-policy witnesses must be read without row locks.
    SELECT * INTO success FROM fee_gateway_refund_successes WHERE refund_request_id=NEW.refund_request_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF success.refund_request_id IS NULL OR success.learner_fee_account_id IS DISTINCT FROM NEW.learner_fee_account_id THEN
        RAISE EXCEPTION 'account reopening requires the original verified refund HOLD';
    END IF;
    PERFORM touch_gateway_refund_fence(success.capture_id,NEW.organisation_id,NEW.school_id);
    PERFORM touch_refund_account_reopen_fence(success.learner_fee_account_id,NEW.organisation_id,NEW.school_id);
    SELECT * INTO account FROM learner_fee_accounts WHERE id=success.learner_fee_account_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR UPDATE;
    SELECT actor.display_name INTO closer_name FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id
        WHERE member.id=account.closed_by_membership_id AND member.organisation_id=NEW.organisation_id AND member.school_id=NEW.school_id;
    IF account.id IS NULL OR account.status<>'CLOSED'
        OR NOT refund_account_reopening_source_ready(NEW.refund_request_id,NEW.organisation_id,NEW.school_id)
        OR NEW.fee_payment_id IS DISTINCT FROM success.fee_payment_id
        OR NEW.verified_read_id IS DISTINCT FROM success.verified_read_id
        OR NEW.success_evidence_sha256 IS DISTINCT FROM success.evidence_sha256
        OR NEW.success_source_version IS DISTINCT FROM success.source_version
        OR ROW(NEW.account_academic_session_id,NEW.account_learner_id,NEW.account_enrolment_id,NEW.account_currency_code,
            NEW.account_opened_by_membership_id,NEW.account_opened_at,NEW.account_closed_by_membership_id,
            NEW.account_closed_at,NEW.account_close_reason,NEW.account_row_version,NEW.closed_by_name_snapshot)
           IS DISTINCT FROM ROW(account.academic_session_id,account.learner_id,account.enrolment_id,account.currency_code,
            account.opened_by_membership_id,account.opened_at,account.closed_by_membership_id,
            account.closed_at,account.close_reason,account.row_version,closer_name) THEN
        RAISE EXCEPTION 'account reopening must preserve the exact original CLOSED account and verified source';
    END IF;
    IF EXISTS(SELECT 1 FROM fee_refund_account_reopen_requests other
        WHERE other.learner_fee_account_id=account.id AND other.organisation_id=NEW.organisation_id AND other.school_id=NEW.school_id
            AND NOT EXISTS(SELECT 1 FROM fee_refund_account_reopen_decisions decision WHERE decision.reopen_request_id=other.id)) THEN
        RAISE EXCEPTION 'the original fee account already has a pending reopening review' USING ERRCODE='23514';
    END IF;
    NEW.requested_at:=clock_timestamp();
    IF NEW.requested_at<success.recognized_at OR NEW.requested_at<account.closed_at THEN
        RAISE EXCEPTION 'account reopening request time must follow its retained source and closure';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_refund_account_reopen_requests_guard BEFORE INSERT ON fee_refund_account_reopen_requests
    FOR EACH ROW EXECUTE FUNCTION guard_refund_account_reopen_request();

CREATE FUNCTION guard_refund_account_reopen_decision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE request fee_refund_account_reopen_requests%ROWTYPE; account learner_fee_accounts%ROWTYPE; capture_value UUID;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM require_gateway_refund_actor(NEW.organisation_id,NEW.school_id,NEW.decided_by_membership_id);
    SELECT * INTO request FROM fee_refund_account_reopen_requests WHERE id=NEW.reopen_request_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF request.id IS NULL OR NEW.decided_by_user_id IS DISTINCT FROM current_app_user_id()
        OR NEW.learner_fee_account_id IS DISTINCT FROM request.learner_fee_account_id
        OR NEW.closed_account_version IS DISTINCT FROM request.account_row_version
        OR (NEW.decision='CANCEL' AND NEW.decided_by_user_id<>request.requested_by_user_id)
        OR (NEW.decision IN ('APPROVE','REJECT') AND NEW.decided_by_user_id=request.requested_by_user_id) THEN
        RAISE EXCEPTION 'account reopening requires an independent actual reviewer; only its maker may withdraw';
    END IF;
    SELECT capture_id INTO capture_value FROM fee_gateway_refund_successes WHERE refund_request_id=request.refund_request_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF capture_value IS NULL THEN RAISE EXCEPTION 'account reopening retained source is unavailable'; END IF;
    PERFORM touch_gateway_refund_fence(capture_value,NEW.organisation_id,NEW.school_id);
    PERFORM touch_refund_account_reopen_fence(request.learner_fee_account_id,NEW.organisation_id,NEW.school_id);
    SELECT * INTO account FROM learner_fee_accounts WHERE id=request.learner_fee_account_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR UPDATE;
    IF EXISTS(SELECT 1 FROM fee_refund_account_reopen_decisions decision WHERE decision.reopen_request_id=request.id) THEN
        RAISE EXCEPTION 'account reopening already has an immutable decision' USING ERRCODE='23514';
    END IF;
    NEW.decided_at:=clock_timestamp();
    IF NEW.decided_at<request.requested_at THEN RAISE EXCEPTION 'account reopening review cannot precede its request'; END IF;
    IF NEW.decision='APPROVE' THEN
        IF account.id IS NULL OR account.status<>'CLOSED'
            OR NOT refund_account_reopening_source_ready(request.refund_request_id,NEW.organisation_id,NEW.school_id)
            OR ROW(account.academic_session_id,account.learner_id,account.enrolment_id,account.currency_code,
                account.opened_by_membership_id,account.opened_at,account.closed_by_membership_id,
                account.closed_at,account.close_reason,account.row_version)
               IS DISTINCT FROM ROW(request.account_academic_session_id,request.account_learner_id,request.account_enrolment_id,request.account_currency_code,
                request.account_opened_by_membership_id,request.account_opened_at,request.account_closed_by_membership_id,
                request.account_closed_at,request.account_close_reason,request.account_row_version)
            OR NEW.applied_account_version IS DISTINCT FROM request.account_row_version+1 THEN
            RAISE EXCEPTION 'changed original account or contradictory refund evidence requires a fresh reviewed request';
        END IF;
    ELSIF NEW.applied_account_version IS NOT NULL THEN
        RAISE EXCEPTION 'rejected or withdrawn account reviews cannot claim an applied transition';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_refund_account_reopen_decisions_guard BEFORE INSERT ON fee_refund_account_reopen_decisions
    FOR EACH ROW EXECUTE FUNCTION guard_refund_account_reopen_decision();

CREATE FUNCTION guard_reviewed_original_fee_account_update() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id,NEW.organisation_id,NEW.school_id,NEW.academic_session_id,NEW.learner_id,NEW.enrolment_id,
        NEW.currency_code,NEW.opened_by_membership_id,NEW.opened_at)
       IS DISTINCT FROM ROW(OLD.id,OLD.organisation_id,OLD.school_id,OLD.academic_session_id,OLD.learner_id,OLD.enrolment_id,
        OLD.currency_code,OLD.opened_by_membership_id,OLD.opened_at)
       OR NEW.row_version<OLD.row_version OR (NEW.status IS DISTINCT FROM OLD.status AND NEW.row_version IS DISTINCT FROM OLD.row_version+1) THEN
        RAISE EXCEPTION 'fee account identity, original opening and monotonic versions must be preserved';
    END IF;
    IF OLD.status='CLOSED' AND NEW.status='CLOSED'
        AND ROW(NEW.closed_by_membership_id,NEW.closed_at,NEW.close_reason)
            IS DISTINCT FROM ROW(OLD.closed_by_membership_id,OLD.closed_at,OLD.close_reason) THEN
        RAISE EXCEPTION 'the original CLOSED account evidence cannot be silently edited';
    END IF;
    IF OLD.status='CLOSED' AND NEW.status='ACTIVE' THEN
        IF current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id OR current_app_user_id() IS NULL
            OR NEW.closed_by_membership_id IS NOT NULL OR NEW.closed_at IS NOT NULL OR NEW.close_reason IS NOT NULL
            OR NOT EXISTS(SELECT 1 FROM fee_refund_account_reopen_decisions decision
                JOIN fee_refund_account_reopen_requests request ON request.id=decision.reopen_request_id
                    AND request.organisation_id=decision.organisation_id AND request.school_id=decision.school_id
                WHERE decision.learner_fee_account_id=OLD.id AND decision.organisation_id=OLD.organisation_id AND decision.school_id=OLD.school_id
                    AND decision.decision='APPROVE' AND decision.closed_account_version=OLD.row_version
                    AND decision.applied_account_version=NEW.row_version AND decision.decided_by_user_id=current_app_user_id()
                    AND ROW(request.account_closed_by_membership_id,request.account_closed_at,request.account_close_reason)
                        IS NOT DISTINCT FROM ROW(OLD.closed_by_membership_id,OLD.closed_at,OLD.close_reason)) THEN
            RAISE EXCEPTION 'original fee account reopening requires its exact independently approved transition';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER learner_fee_accounts_reviewed_reopening_guard BEFORE UPDATE ON learner_fee_accounts
    FOR EACH ROW EXECUTE FUNCTION guard_reviewed_original_fee_account_update();

-- The explicit APPROVE is the only operation that applies reopening. Keeping
-- the transition in this trigger also protects direct SQL: no approval can
-- commit alone, and application/audit rollback undoes the entire pair.
CREATE FUNCTION apply_refund_account_reopen_decision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE changed_count INTEGER;
BEGIN
    IF NEW.decision='APPROVE' THEN
        UPDATE learner_fee_accounts SET status='ACTIVE',closed_by_membership_id=NULL,closed_at=NULL,close_reason=NULL,
            row_version=NEW.applied_account_version
        WHERE id=NEW.learner_fee_account_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id
            AND status='CLOSED' AND row_version=NEW.closed_account_version;
        GET DIAGNOSTICS changed_count=ROW_COUNT;
        IF changed_count<>1 THEN RAISE EXCEPTION 'approved original account transition was not applied atomically'; END IF;
    END IF;
    RETURN NULL;
END; $$;
CREATE TRIGGER fee_refund_account_reopen_decisions_apply AFTER INSERT ON fee_refund_account_reopen_decisions
    FOR EACH ROW EXECUTE FUNCTION apply_refund_account_reopen_decision();

DO $$ DECLARE table_name TEXT; BEGIN
    FOREACH table_name IN ARRAY ARRAY['fee_refund_account_reopen_requests','fee_refund_account_reopen_decisions'] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation()',table_name||'_append_only',table_name);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY %I ON %I FOR SELECT USING(organisation_id=current_app_organisation_id())',table_name||'_select',table_name);
        EXECUTE format('CREATE POLICY %I ON %I FOR INSERT WITH CHECK(organisation_id=current_app_organisation_id() AND current_app_user_id() IS NOT NULL)',table_name||'_insert',table_name);
    END LOOP;
END $$;
ALTER TABLE fee_refund_account_reopen_fences ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_refund_account_reopen_fences FORCE ROW LEVEL SECURITY;
CREATE POLICY fee_refund_account_reopen_fences_tenant ON fee_refund_account_reopen_fences
    USING(organisation_id=current_app_organisation_id()) WITH CHECK(organisation_id=current_app_organisation_id());
CREATE TRIGGER fee_refund_account_reopen_fences_no_delete BEFORE DELETE ON fee_refund_account_reopen_fences
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
COMMENT ON TABLE fee_refund_account_reopen_requests IS 'Human request preserving the exact original CLOSED fee account for one retained verified refund HOLD. Submission never reopens accounts or posts a refund. No financial day or academic state is changed.';
COMMENT ON TABLE fee_refund_account_reopen_decisions IS 'One immutable maker cancellation or independent review; explicit APPROVE atomically applies its original CLOSED-to-ACTIVE transition only once. Later reclosure does not undo or replay this history.';
COMMENT ON TABLE fee_refund_account_reopen_fences IS 'Technical account epoch only. Real writes protect pending-review predicates at RR/SERIALIZABLE; account FOR UPDATE separately detects stale closure snapshots.';

-- Durable metadata only; no provider, scheduler, receipt, fee debit or GL posting is activated.
CREATE TABLE fee_gateway_refund_work (
    refund_request_id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    approval_decision_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK(provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL, credentials_secret_reference VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','LEASED','RETRY','COMPLETE','REVIEW_REQUIRED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 1000000),
    next_attempt_at TIMESTAMPTZ NOT NULL, lease_token UUID, lease_started_at TIMESTAMPTZ, lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ, last_error_code VARCHAR(100), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK(row_version>=0),
    UNIQUE(refund_request_id,organisation_id,school_id),
    FOREIGN KEY(refund_request_id,organisation_id,school_id) REFERENCES fee_gateway_refund_requests(id,organisation_id,school_id),
    FOREIGN KEY(approval_decision_id,organisation_id,school_id) REFERENCES fee_gateway_refund_decisions(id,organisation_id,school_id),
    FOREIGN KEY(payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    CHECK(isfinite(created_at) AND isfinite(updated_at) AND isfinite(next_attempt_at)),
    CHECK(next_attempt_at>=created_at AND updated_at>=created_at),
    CHECK((status='LEASED' AND lease_token IS NOT NULL AND lease_started_at IS NOT NULL AND lease_until IS NOT NULL
        AND isfinite(lease_started_at) AND isfinite(lease_until) AND lease_started_at>=created_at
        AND lease_until>lease_started_at AND lease_until<=lease_started_at+interval '2 minutes' AND attempt_count>0)
        OR (status<>'LEASED' AND lease_token IS NULL AND lease_started_at IS NULL AND lease_until IS NULL)),
    CHECK((status IN ('COMPLETE','REVIEW_REQUIRED'))=(completed_at IS NOT NULL)),
    CHECK(completed_at IS NULL OR (isfinite(completed_at) AND completed_at>=created_at)),
    CHECK(last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{2,99}$')
);
CREATE INDEX fee_gateway_refund_work_due ON fee_gateway_refund_work(payment_provider_config_id,next_attempt_at,refund_request_id)
    WHERE status IN ('READY','RETRY');
CREATE INDEX fee_gateway_refund_work_expired ON fee_gateway_refund_work(payment_provider_config_id,lease_until,refund_request_id)
    WHERE status='LEASED';

CREATE FUNCTION guard_gateway_refund_work_binding() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE decision fee_gateway_refund_decisions%ROWTYPE;
BEGIN
    IF NEW.organisation_id IS DISTINCT FROM current_app_organisation_id() THEN RAISE EXCEPTION 'refund work requires its actual tenant'; END IF;
    SELECT approved.* INTO decision FROM fee_gateway_refund_decisions approved
        JOIN fee_gateway_refund_requests request ON request.id=approved.refund_request_id
            AND request.organisation_id=approved.organisation_id AND request.school_id=approved.school_id
        WHERE request.id=NEW.refund_request_id AND request.organisation_id=NEW.organisation_id AND request.school_id=NEW.school_id
            AND approved.id=NEW.approval_decision_id AND approved.decision='APPROVE'
            AND request.payment_provider_config_id=NEW.payment_provider_config_id AND request.provider_code=NEW.provider_code
            AND request.provider_environment=NEW.provider_environment AND request.merchant_account_reference=NEW.merchant_account_reference
            AND request.credentials_secret_reference=NEW.credentials_secret_reference;
    IF decision.id IS NULL OR NEW.created_at<decision.decided_at OR NEW.created_at>clock_timestamp() THEN
        RAISE EXCEPTION 'refund work requires the exact independent approval and frozen provider';
    END IF;
    IF current_app_user_id() IS NOT NULL THEN
        IF current_app_user_id() IS DISTINCT FROM decision.decided_by_user_id THEN RAISE EXCEPTION 'human enqueue requires the actual approving actor'; END IF;
        PERFORM require_gateway_refund_actor(NEW.organisation_id,NEW.school_id,decision.decided_by_membership_id);
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_work_binding BEFORE INSERT ON fee_gateway_refund_work
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_refund_work_binding();
-- The shared bounded claim/retry/expiry/terminal-state implementation is unchanged.
CREATE TRIGGER fee_gateway_refund_work_transition BEFORE INSERT OR UPDATE OR DELETE ON fee_gateway_refund_work
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_work_transition();

CREATE FUNCTION enqueue_approved_gateway_refund() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE stamp TIMESTAMPTZ:=clock_timestamp();
BEGIN
    IF NEW.decision='APPROVE' THEN
        INSERT INTO fee_gateway_refund_work(refund_request_id,organisation_id,school_id,approval_decision_id,
            payment_provider_config_id,provider_code,provider_environment,merchant_account_reference,credentials_secret_reference,
            next_attempt_at,created_at,updated_at)
        SELECT request.id,request.organisation_id,request.school_id,NEW.id,request.payment_provider_config_id,
            request.provider_code,request.provider_environment,request.merchant_account_reference,request.credentials_secret_reference,stamp,stamp,stamp
        FROM fee_gateway_refund_requests request WHERE request.id=NEW.refund_request_id
            AND request.organisation_id=NEW.organisation_id AND request.school_id=NEW.school_id;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_approval_outbox AFTER INSERT ON fee_gateway_refund_decisions
    FOR EACH ROW EXECUTE FUNCTION enqueue_approved_gateway_refund();

CREATE TABLE fee_gateway_refund_dispatches (
    refund_request_id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    approval_decision_id UUID NOT NULL, lease_token UUID NOT NULL, lease_started_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL, attempt_number INTEGER NOT NULL CHECK(attempt_number BETWEEN 1 AND 1000000),
    original_payment_posting_item_id UUID NOT NULL, original_payment_posting_batch_id UUID NOT NULL,
    original_payment_journal_id UUID NOT NULL, original_configuration_id UUID NOT NULL,
    source_sha256 CHAR(64) NOT NULL CHECK(source_sha256 ~ '^[a-f0-9]{64}$'),
    batch_evidence_sha256 CHAR(64) NOT NULL CHECK(batch_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(refund_request_id,organisation_id,school_id),
    FOREIGN KEY(refund_request_id,organisation_id,school_id) REFERENCES fee_gateway_refund_work(refund_request_id,organisation_id,school_id),
    FOREIGN KEY(approval_decision_id,organisation_id,school_id) REFERENCES fee_gateway_refund_decisions(id,organisation_id,school_id),
    FOREIGN KEY(original_payment_posting_item_id,organisation_id,school_id) REFERENCES fee_accounting_posting_items(id,organisation_id,school_id),
    FOREIGN KEY(original_payment_posting_batch_id,organisation_id,school_id) REFERENCES fee_accounting_posting_batches(id,organisation_id,school_id),
    FOREIGN KEY(original_payment_journal_id,organisation_id,school_id) REFERENCES accounting_journals(id,organisation_id,school_id),
    FOREIGN KEY(original_configuration_id,organisation_id,school_id) REFERENCES fee_accounting_configurations(id,organisation_id,school_id),
    CHECK(isfinite(admitted_at) AND isfinite(lease_started_at) AND isfinite(lease_until)
        AND admitted_at>=lease_started_at AND admitted_at<lease_until)
);
CREATE INDEX fee_gateway_refund_dispatch_original_journal ON fee_gateway_refund_dispatches(organisation_id,school_id,original_payment_journal_id);

CREATE FUNCTION guard_gateway_refund_dispatch() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE work fee_gateway_refund_work%ROWTYPE;
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id OR current_app_user_id() IS NOT NULL
        OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
        RAISE EXCEPTION 'refund dispatch requires fresh writable background scope' USING ERRCODE='40001';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM pg_advisory_xact_lock(hashtextextended('finance:school:' || NEW.school_id::text,0));
    PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || NEW.school_id::text,0));
    SELECT * INTO work FROM fee_gateway_refund_work WHERE refund_request_id=NEW.refund_request_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR UPDATE;
    NEW.admitted_at:=clock_timestamp();
    IF work.refund_request_id IS NULL OR work.status<>'LEASED' OR work.approval_decision_id IS DISTINCT FROM NEW.approval_decision_id
        OR work.lease_token IS DISTINCT FROM NEW.lease_token OR work.lease_started_at IS DISTINCT FROM NEW.lease_started_at
        OR work.lease_until IS DISTINCT FROM NEW.lease_until OR work.attempt_count IS DISTINCT FROM NEW.attempt_number
        OR NEW.admitted_at<work.lease_started_at OR NEW.admitted_at>=work.lease_until
        OR EXISTS(SELECT 1 FROM fee_gateway_refund_reads WHERE refund_request_id=NEW.refund_request_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id)
        OR EXISTS(SELECT 1 FROM fee_gateway_refund_successes WHERE refund_request_id=NEW.refund_request_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id) THEN
        RAISE EXCEPTION 'refund dispatch requires unused exact current claim without earlier provider evidence';
    END IF;
    PERFORM 1 FROM fee_gateway_refund_requests request
        JOIN fee_gateway_refund_decisions decision ON decision.id=work.approval_decision_id AND decision.refund_request_id=request.id
            AND decision.organisation_id=request.organisation_id AND decision.school_id=request.school_id AND decision.decision='APPROVE'
        JOIN payment_provider_configs provider ON provider.id=request.payment_provider_config_id AND provider.organisation_id=request.organisation_id
            AND provider.school_id=request.school_id AND provider.status='ACTIVE' AND provider.provider_code=request.provider_code
            AND provider.environment=request.provider_environment AND provider.merchant_account_reference=request.merchant_account_reference
            AND provider.credentials_secret_reference=request.credentials_secret_reference
        JOIN schools school ON school.id=request.school_id AND school.organisation_id=request.organisation_id AND school.status='ACTIVE'
        JOIN organisations organisation ON organisation.id=request.organisation_id AND organisation.status='ACTIVE'
        JOIN fee_payments payment ON payment.id=request.fee_payment_id AND payment.organisation_id=request.organisation_id AND payment.school_id=request.school_id
            AND payment.status='POSTED' AND payment.payment_method='ONLINE_PROVIDER' AND payment.gateway_capture_id=request.capture_id
        JOIN fee_accounting_posting_items item ON item.id=NEW.original_payment_posting_item_id AND item.organisation_id=request.organisation_id
            AND item.school_id=request.school_id AND item.fee_ledger_entry_id=payment.ledger_entry_id AND item.status='ACTIVE'
        JOIN fee_accounting_posting_batches batch ON batch.id=item.posting_batch_id AND batch.organisation_id=request.organisation_id AND batch.school_id=request.school_id
            AND batch.id=NEW.original_payment_posting_batch_id AND batch.event_type='PAYMENT' AND batch.disposition='JOURNAL'
            AND batch.configuration_id=NEW.original_configuration_id AND batch.source_count BETWEEN 1 AND 200
            AND batch.source_amount=(SELECT sum(entry.amount) FROM fee_accounting_posting_items source_item JOIN fee_ledger_entries entry
                ON entry.id=source_item.fee_ledger_entry_id AND entry.organisation_id=source_item.organisation_id AND entry.school_id=source_item.school_id
                WHERE source_item.posting_batch_id=batch.id AND source_item.organisation_id=batch.organisation_id AND source_item.school_id=batch.school_id
                    AND source_item.status='ACTIVE' AND entry.entry_type='PAYMENT' AND entry.direction='CREDIT')
        JOIN accounting_journals journal ON journal.id=NEW.original_payment_journal_id AND journal.id=batch.accounting_journal_id
            AND journal.organisation_id=request.organisation_id AND journal.school_id=request.school_id AND journal.source_module='FEES'
            AND journal.source_id=batch.id AND journal.status='POSTED' AND journal.reversal_of_journal_id IS NULL
            AND journal.financial_year_id=batch.financial_year_id AND journal.accounting_period_id=batch.accounting_period_id
            AND journal.posting_date=batch.business_date AND journal.total_amount=batch.source_amount
        WHERE request.id=NEW.refund_request_id AND request.organisation_id=NEW.organisation_id AND request.school_id=NEW.school_id
            AND request.payment_provider_config_id=work.payment_provider_config_id AND request.provider_code=work.provider_code
            AND request.provider_environment=work.provider_environment AND request.merchant_account_reference=work.merchant_account_reference
            AND request.credentials_secret_reference=work.credentials_secret_reference
            AND item.source_sha256=NEW.source_sha256 AND batch.evidence_sha256=NEW.batch_evidence_sha256
            AND (SELECT count(*) FROM fee_accounting_posting_items all_items WHERE all_items.posting_batch_id=batch.id
                AND all_items.organisation_id=batch.organisation_id AND all_items.school_id=batch.school_id AND all_items.status='ACTIVE')=batch.source_count
            AND NOT EXISTS(SELECT 1 FROM accounting_reversal_requests reversal WHERE reversal.accounting_journal_id=journal.id
                AND reversal.organisation_id=request.organisation_id AND reversal.school_id=request.school_id AND reversal.status IN ('PENDING','APPROVED'))
            AND NOT EXISTS(SELECT 1 FROM accounting_journals reversal WHERE reversal.reversal_of_journal_id=journal.id
                AND reversal.organisation_id=request.organisation_id AND reversal.school_id=request.school_id)
        FOR SHARE OF provider,school,payment,item,batch,journal;
    IF NOT FOUND THEN RAISE EXCEPTION 'refund dispatch requires exact approved source, live provider and original unreversed accounting proof'; END IF;
    -- The provider/school/source FOR SHARE locks above can themselves wait.
    -- A timestamp sampled before those waits cannot keep an expired lease alive.
    NEW.admitted_at:=clock_timestamp();
    IF NEW.admitted_at<work.lease_started_at OR NEW.admitted_at>=work.lease_until THEN
        RAISE EXCEPTION 'refund dispatch lease elapsed while locking the approved provider/source' USING ERRCODE='40001';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_dispatch_guard BEFORE INSERT ON fee_gateway_refund_dispatches
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_refund_dispatch();
CREATE TRIGGER fee_gateway_refund_dispatch_append_only BEFORE UPDATE OR DELETE ON fee_gateway_refund_dispatches
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();

CREATE FUNCTION guard_dispatched_refund_original_journal() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE source_id UUID;
BEGIN
    IF TG_TABLE_NAME='accounting_journals' THEN
        IF NEW.status<>'REVERSED' THEN RETURN NEW; END IF;
        source_id:=NEW.id;
    ELSE
        IF NEW.status NOT IN ('PENDING','APPROVED') THEN RETURN NEW; END IF;
        source_id:=NEW.accounting_journal_id;
    END IF;
    -- Do not change unrelated/manual accounting workflows or their isolation rules.
    -- This immutable source association is visible even to an older snapshot;
    -- dispatch absence itself must only be decided under fresh READ COMMITTED.
    IF NOT EXISTS(SELECT 1 FROM fee_accounting_posting_batches batch
        JOIN fee_accounting_posting_items item ON item.posting_batch_id=batch.id AND item.organisation_id=batch.organisation_id AND item.school_id=batch.school_id
        JOIN fee_payments payment ON payment.ledger_entry_id=item.fee_ledger_entry_id AND payment.organisation_id=item.organisation_id AND payment.school_id=item.school_id
        WHERE batch.accounting_journal_id=source_id AND batch.organisation_id=NEW.organisation_id AND batch.school_id=NEW.school_id
            AND batch.event_type='PAYMENT' AND payment.gateway_capture_id IS NOT NULL) THEN RETURN NEW; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || NEW.school_id::text,0));
    IF current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'refund source correction requires fresh dispatch evidence' USING ERRCODE='40001';
    END IF;
    IF EXISTS(SELECT 1 FROM fee_gateway_refund_dispatches dispatch WHERE dispatch.original_payment_journal_id=source_id
        AND dispatch.organisation_id=NEW.organisation_id AND dispatch.school_id=NEW.school_id) THEN
        RAISE EXCEPTION 'a dispatched refund anchors its original journal across uncertain provider outcomes; use reviewed adjustment';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_dispatched_original_request BEFORE INSERT OR UPDATE ON accounting_reversal_requests
    FOR EACH ROW EXECUTE FUNCTION guard_dispatched_refund_original_journal();
CREATE TRIGGER fee_gateway_refund_dispatched_original_journal BEFORE UPDATE ON accounting_journals
    FOR EACH ROW EXECUTE FUNCTION guard_dispatched_refund_original_journal();

ALTER TABLE fee_gateway_refund_work ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_gateway_refund_work FORCE ROW LEVEL SECURITY;
CREATE POLICY fee_gateway_refund_work_tenant ON fee_gateway_refund_work
    USING(organisation_id=current_app_organisation_id()) WITH CHECK(organisation_id=current_app_organisation_id());
ALTER TABLE fee_gateway_refund_dispatches ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_gateway_refund_dispatches FORCE ROW LEVEL SECURITY;
CREATE POLICY fee_gateway_refund_dispatch_select ON fee_gateway_refund_dispatches FOR SELECT USING(organisation_id=current_app_organisation_id());
CREATE POLICY fee_gateway_refund_dispatch_insert ON fee_gateway_refund_dispatches FOR INSERT
    WITH CHECK(organisation_id=current_app_organisation_id() AND current_app_user_id() IS NULL);

COMMENT ON TABLE fee_gateway_refund_work IS 'One durable approved-refund lane using existing bounded lease/retry rules. Approval queues work, not a refund. No historical work is fabricated by this migration.';
COMMENT ON TABLE fee_gateway_refund_dispatches IS 'Immutable one-use POST admission committed before provider I/O, not proof a POST was sent or succeeded. Original source remains anchored across timeout/crash; recover only the same refund UUID by GET. Never delete/reset to repeat POST.';

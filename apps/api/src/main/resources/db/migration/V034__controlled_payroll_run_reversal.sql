INSERT INTO capabilities (code, description, risk_level) VALUES
    ('payroll.run.reverse.request', 'Request controlled reversal of a finalized school payroll run', 'RESTRICTED'),
    ('payroll.run.reverse.approve', 'Independently approve or reject a payroll run reversal request', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'payroll.run.reverse.request'),
    ('00000000-0000-4000-8000-000000000001', 'payroll.run.reverse.approve'),
    ('00000000-0000-4000-8000-000000000004', 'payroll.run.reverse.request'),
    ('00000000-0000-4000-8000-000000000007', 'payroll.run.reverse.approve')
ON CONFLICT DO NOTHING;

ALTER TABLE payroll_runs
    ADD COLUMN reversal_request_id UUID,
    ADD COLUMN reversed_by_membership_id UUID,
    ADD COLUMN reversed_at TIMESTAMPTZ,
    ADD COLUMN reversal_reason VARCHAR(500),
    ADD CONSTRAINT payroll_runs_reverser_fk
        FOREIGN KEY (reversed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id);

CREATE TABLE payroll_run_reversal_requests (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    payroll_run_id UUID NOT NULL,
    requested_run_version BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    request_reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by_membership_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payroll_run_reversal_requests_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_run_reversal_requests_run_scope_unique
        UNIQUE (id, payroll_run_id, organisation_id, school_id),
    CONSTRAINT payroll_run_reversal_requests_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT payroll_run_reversal_requests_run_fk
        FOREIGN KEY (payroll_run_id, organisation_id, school_id)
        REFERENCES payroll_runs(id, organisation_id, school_id),
    CONSTRAINT payroll_run_reversal_requests_requester_fk
        FOREIGN KEY (requested_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_run_reversal_requests_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_run_reversal_requests_idempotency_unique
        UNIQUE (school_id, idempotency_key),
    CONSTRAINT payroll_run_reversal_requests_version_valid CHECK (requested_run_version >= 0),
    CONSTRAINT payroll_run_reversal_requests_idempotency_valid
        CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 100),
    CONSTRAINT payroll_run_reversal_requests_fingerprint_valid
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_run_reversal_requests_reason_valid
        CHECK (length(btrim(request_reason)) BETWEEN 3 AND 500),
    CONSTRAINT payroll_run_reversal_requests_status_valid
        CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT payroll_run_reversal_requests_lifecycle_valid CHECK (
        (status='PENDING' AND decided_by_membership_id IS NULL
            AND decided_at IS NULL AND decision_reason IS NULL)
        OR (status IN ('APPROVED','REJECTED') AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> requested_by_membership_id
            AND decided_at IS NOT NULL
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500))
);

CREATE UNIQUE INDEX payroll_run_reversal_requests_one_pending
    ON payroll_run_reversal_requests(payroll_run_id) WHERE status='PENDING';
CREATE UNIQUE INDEX payroll_run_reversal_requests_one_approved
    ON payroll_run_reversal_requests(payroll_run_id) WHERE status='APPROVED';
CREATE INDEX payroll_run_reversal_requests_scope_time
    ON payroll_run_reversal_requests(
        organisation_id, school_id, requested_at DESC, payroll_run_id, id);

CREATE TABLE payroll_run_reversal_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    payroll_run_reversal_request_id UUID NOT NULL,
    payroll_run_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payroll_run_reversal_history_request_fk
        FOREIGN KEY (payroll_run_reversal_request_id, payroll_run_id, organisation_id, school_id)
        REFERENCES payroll_run_reversal_requests(id, payroll_run_id, organisation_id, school_id),
    CONSTRAINT payroll_run_reversal_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_run_reversal_history_event_valid
        CHECK (event_type IN ('REQUESTED','APPROVED','REJECTED')),
    CONSTRAINT payroll_run_reversal_history_status_valid
        CHECK (new_status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT payroll_run_reversal_history_reason_valid
        CHECK (length(btrim(reason)) BETWEEN 3 AND 500)
);

CREATE INDEX payroll_run_reversal_history_request_time
    ON payroll_run_reversal_history(
        payroll_run_reversal_request_id, occurred_at, id);

ALTER TABLE payroll_runs
    ADD CONSTRAINT payroll_runs_reversal_request_fk
        FOREIGN KEY (reversal_request_id, id, organisation_id, school_id)
        REFERENCES payroll_run_reversal_requests(
            id, payroll_run_id, organisation_id, school_id);

ALTER TABLE payroll_runs
    DROP CONSTRAINT payroll_runs_status_valid,
    DROP CONSTRAINT payroll_runs_lifecycle_valid,
    ADD CONSTRAINT payroll_runs_status_valid CHECK (
        status IN ('DRAFT','SUBMITTED','FINALIZED','REJECTED','CANCELLED','REVERSED')),
    ADD CONSTRAINT payroll_runs_lifecycle_valid CHECK (
        (status <> 'REVERSED' AND reversal_request_id IS NULL
            AND reversed_by_membership_id IS NULL AND reversed_at IS NULL
            AND reversal_reason IS NULL AND (
                (status = 'DRAFT' AND submitted_by_membership_id IS NULL
                    AND submitted_at IS NULL AND submission_reason IS NULL
                    AND decided_by_membership_id IS NULL AND decided_at IS NULL
                    AND decision_reason IS NULL)
                OR (status = 'SUBMITTED' AND submitted_by_membership_id IS NOT NULL
                    AND submitted_at IS NOT NULL
                    AND length(btrim(submission_reason)) BETWEEN 3 AND 500
                    AND decided_by_membership_id IS NULL AND decided_at IS NULL
                    AND decision_reason IS NULL)
                OR (status IN ('FINALIZED','REJECTED')
                    AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
                    AND length(btrim(submission_reason)) BETWEEN 3 AND 500
                    AND decided_by_membership_id IS NOT NULL
                    AND decided_by_membership_id <> created_by_membership_id
                    AND decided_at IS NOT NULL
                    AND length(btrim(decision_reason)) BETWEEN 3 AND 500)
                OR (status = 'CANCELLED' AND submitted_by_membership_id IS NULL
                    AND submitted_at IS NULL AND submission_reason IS NULL
                    AND decided_by_membership_id = created_by_membership_id
                    AND decided_at IS NOT NULL
                    AND length(btrim(decision_reason)) BETWEEN 3 AND 500)))
        OR (status = 'REVERSED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL
            AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> created_by_membership_id
            AND decided_at IS NOT NULL
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND reversal_request_id IS NOT NULL
            AND reversed_by_membership_id IS NOT NULL
            AND reversed_at IS NOT NULL
            AND length(btrim(reversal_reason)) BETWEEN 3 AND 500));

ALTER TABLE payroll_run_history
    DROP CONSTRAINT payroll_run_history_event_valid,
    DROP CONSTRAINT payroll_run_history_status_valid,
    ADD CONSTRAINT payroll_run_history_event_valid CHECK (
        event_type IN ('PREPARED','SUBMITTED','FINALIZED','REJECTED','CANCELLED','REVERSED')),
    ADD CONSTRAINT payroll_run_history_status_valid CHECK (
        new_status IN ('DRAFT','SUBMITTED','FINALIZED','REJECTED','CANCELLED','REVERSED'));

CREATE OR REPLACE FUNCTION guard_payroll_run_reversal_request()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE run_record payroll_runs%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF current_setting('app.payroll_run_reverse_request', true) <> 'approved'
           OR NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'payroll reversal requests require the controlled request workflow';
        END IF;
        SELECT * INTO run_record FROM payroll_runs
        WHERE id=NEW.payroll_run_id AND organisation_id=NEW.organisation_id
          AND school_id=NEW.school_id;
        IF run_record.id IS NULL OR run_record.status <> 'FINALIZED'
           OR run_record.row_version <> NEW.requested_run_version THEN
            RAISE EXCEPTION 'payroll reversal requires the current finalized run version';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payroll reversal evidence is append-only';
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.payroll_run_id <> NEW.payroll_run_id
       OR OLD.requested_run_version <> NEW.requested_run_version
       OR OLD.idempotency_key <> NEW.idempotency_key
       OR OLD.request_fingerprint <> NEW.request_fingerprint
       OR OLD.request_reason <> NEW.request_reason
       OR OLD.requested_by_membership_id <> NEW.requested_by_membership_id
       OR OLD.requested_at <> NEW.requested_at THEN
        RAISE EXCEPTION 'payroll reversal request identity and source evidence is immutable';
    END IF;
    IF current_setting('app.payroll_run_reverse_decide', true) <> 'approved'
       OR OLD.status <> 'PENDING' OR NEW.status NOT IN ('APPROVED','REJECTED')
       OR NEW.row_version <> OLD.row_version + 1
       OR NEW.decided_by_membership_id = OLD.requested_by_membership_id THEN
        RAISE EXCEPTION 'invalid payroll reversal request transition';
    END IF;
    IF NEW.status='APPROVED' THEN
        SELECT * INTO run_record FROM payroll_runs
        WHERE id=NEW.payroll_run_id AND organisation_id=NEW.organisation_id
          AND school_id=NEW.school_id;
        IF run_record.id IS NULL OR run_record.status <> 'FINALIZED'
           OR run_record.row_version <> NEW.requested_run_version THEN
            RAISE EXCEPTION 'payroll reversal approval requires the unchanged finalized run';
        END IF;
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER payroll_run_reversal_requests_guard
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_run_reversal_requests
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_run_reversal_request();

CREATE OR REPLACE FUNCTION guard_payroll_run_reversal_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT'
       AND (current_setting('app.payroll_run_reverse_request', true)='approved'
         OR current_setting('app.payroll_run_reverse_decide', true)='approved') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'payroll reversal history is append-only';
END; $$;

CREATE TRIGGER payroll_run_reversal_history_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_run_reversal_history
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_run_reversal_history();

CREATE OR REPLACE FUNCTION guard_payroll_run()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE reversal_record payroll_run_reversal_requests%ROWTYPE;
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id
       OR OLD.payroll_run_policy_id <> NEW.payroll_run_policy_id
       OR OLD.policy_snapshot <> NEW.policy_snapshot
       OR OLD.payroll_month <> NEW.payroll_month OR OLD.period_start <> NEW.period_start
       OR OLD.period_end <> NEW.period_end OR OLD.attempt_number <> NEW.attempt_number
       OR OLD.run_code <> NEW.run_code OR OLD.idempotency_key <> NEW.idempotency_key
       OR OLD.input_fingerprint <> NEW.input_fingerprint OR OLD.currency_code <> NEW.currency_code
       OR OLD.staff_count <> NEW.staff_count
       OR OLD.scheduled_working_days_total <> NEW.scheduled_working_days_total
       OR OLD.recorded_days_total <> NEW.recorded_days_total
       OR OLD.payable_days_total <> NEW.payable_days_total
       OR OLD.full_gross_total <> NEW.full_gross_total
       OR OLD.payable_gross_total <> NEW.payable_gross_total
       OR OLD.deduction_total <> NEW.deduction_total
       OR OLD.employer_contribution_total <> NEW.employer_contribution_total
       OR OLD.net_total <> NEW.net_total OR OLD.creation_reason <> NEW.creation_reason
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'payroll run identity, source, and calculation evidence is immutable';
    END IF;
    IF OLD.status='DRAFT' AND NEW.status='SUBMITTED' THEN
        PERFORM validate_complete_payroll_run(NEW.id,NEW.organisation_id,NEW.school_id);
        RETURN NEW;
    END IF;
    IF OLD.status='DRAFT' AND NEW.status='CANCELLED' THEN RETURN NEW; END IF;
    IF OLD.status='SUBMITTED' AND NEW.status IN ('FINALIZED','REJECTED') THEN
        IF NEW.decided_by_membership_id=NEW.created_by_membership_id THEN
            RAISE EXCEPTION 'payroll run finalization requires an independent actor';
        END IF;
        IF NEW.status='FINALIZED' THEN
            PERFORM validate_complete_payroll_run(NEW.id,NEW.organisation_id,NEW.school_id);
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status='FINALIZED' AND NEW.status='REVERSED' THEN
        IF current_setting('app.payroll_run_reverse', true) <> 'approved'
           OR NEW.reversal_request_id IS NULL THEN
            RAISE EXCEPTION 'finalized payroll reversal requires the controlled approval workflow';
        END IF;
        SELECT * INTO reversal_record FROM payroll_run_reversal_requests
        WHERE id=NEW.reversal_request_id AND payroll_run_id=NEW.id
          AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF reversal_record.id IS NULL OR reversal_record.status <> 'APPROVED'
           OR reversal_record.decided_by_membership_id <> NEW.reversed_by_membership_id
           OR reversal_record.decision_reason <> NEW.reversal_reason THEN
            RAISE EXCEPTION 'payroll run reversal must match its approved request';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid payroll run transition';
END; $$;

ALTER TABLE payroll_run_reversal_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_run_reversal_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY payroll_run_reversal_requests_tenant_all
    ON payroll_run_reversal_requests
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY payroll_run_reversal_history_tenant_all
    ON payroll_run_reversal_history
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE payroll_run_reversal_requests IS
    'Maker-checker requests that reverse, but never delete or recalculate, one unchanged finalized payroll run. Approval releases the month for a new protected attempt.';
COMMENT ON TABLE payroll_run_reversal_history IS
    'Append-only request, approval, and rejection evidence for controlled payroll run reversal.';
COMMENT ON COLUMN payroll_runs.reversal_request_id IS
    'Approved request that moved the immutable finalized run to REVERSED; source calculations remain unchanged.';

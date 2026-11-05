INSERT INTO capabilities (code, description, risk_level) VALUES
    ('payroll.run.prepare', 'Preview, prepare, submit, and cancel school-scoped monthly payroll runs', 'RESTRICTED'),
    ('payroll.run.approve', 'Independently finalize or reject submitted monthly payroll runs', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'payroll.run.prepare'),
    ('00000000-0000-4000-8000-000000000001', 'payroll.run.approve'),
    ('00000000-0000-4000-8000-000000000004', 'payroll.run.prepare'),
    ('00000000-0000-4000-8000-000000000007', 'payroll.run.approve')
ON CONFLICT DO NOTHING;

CREATE TABLE payroll_run_policies (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'INR',
    zero_scheduled_days_action VARCHAR(20) NOT NULL DEFAULT 'FULL_PAY',
    proration_scale SMALLINT NOT NULL DEFAULT 6,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payroll_run_policies_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_run_policies_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT payroll_run_policies_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_run_policies_name_valid CHECK (length(btrim(display_name)) BETWEEN 2 AND 120),
    CONSTRAINT payroll_run_policies_currency_valid CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT payroll_run_policies_zero_days_valid CHECK (
        zero_scheduled_days_action IN ('FULL_PAY','BLOCK')),
    CONSTRAINT payroll_run_policies_scale_valid CHECK (proration_scale BETWEEN 2 AND 6),
    CONSTRAINT payroll_run_policies_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE UNIQUE INDEX payroll_run_policies_one_active
    ON payroll_run_policies(school_id) WHERE status = 'ACTIVE';
CREATE INDEX payroll_run_policies_school_status
    ON payroll_run_policies(organisation_id, school_id, status, updated_at DESC, id);

CREATE TABLE payroll_runs (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    payroll_run_policy_id UUID NOT NULL,
    policy_snapshot JSONB NOT NULL,
    payroll_month DATE NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    attempt_number SMALLINT NOT NULL,
    run_code VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    staff_count SMALLINT NOT NULL,
    scheduled_working_days_total INTEGER NOT NULL,
    recorded_days_total INTEGER NOT NULL,
    payable_days_total NUMERIC(12,4) NOT NULL,
    full_gross_total NUMERIC(18,2) NOT NULL,
    payable_gross_total NUMERIC(18,2) NOT NULL,
    deduction_total NUMERIC(18,2) NOT NULL,
    employer_contribution_total NUMERIC(18,2) NOT NULL,
    net_total NUMERIC(18,2) NOT NULL,
    creation_reason VARCHAR(500) NOT NULL,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    submission_reason VARCHAR(500),
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payroll_runs_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_runs_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT payroll_runs_policy_fk
        FOREIGN KEY (payroll_run_policy_id, organisation_id, school_id)
        REFERENCES payroll_run_policies(id, organisation_id, school_id),
    CONSTRAINT payroll_runs_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_runs_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_runs_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_runs_attempt_unique UNIQUE (school_id, payroll_month, attempt_number),
    CONSTRAINT payroll_runs_code_unique UNIQUE (school_id, run_code),
    CONSTRAINT payroll_runs_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT payroll_runs_month_valid CHECK (
        extract(day FROM payroll_month) = 1 AND period_start = payroll_month
        AND period_end = (payroll_month + INTERVAL '1 month - 1 day')::date),
    CONSTRAINT payroll_runs_attempt_valid CHECK (attempt_number BETWEEN 1 AND 999),
    CONSTRAINT payroll_runs_code_valid CHECK (run_code ~ '^PAY-[0-9]{6}-[0-9]{3}$'),
    CONSTRAINT payroll_runs_idempotency_valid CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 100),
    CONSTRAINT payroll_runs_fingerprint_valid CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_runs_currency_valid CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT payroll_runs_status_valid CHECK (
        status IN ('DRAFT','SUBMITTED','FINALIZED','REJECTED','CANCELLED')),
    CONSTRAINT payroll_runs_counts_valid CHECK (
        staff_count BETWEEN 1 AND 500 AND scheduled_working_days_total >= 0
        AND recorded_days_total BETWEEN 0 AND scheduled_working_days_total
        AND payable_days_total BETWEEN 0 AND scheduled_working_days_total),
    CONSTRAINT payroll_runs_amounts_valid CHECK (
        full_gross_total >= 0 AND payable_gross_total >= 0 AND deduction_total >= 0
        AND employer_contribution_total >= 0 AND net_total >= 0
        AND deduction_total <= payable_gross_total
        AND net_total = payable_gross_total - deduction_total),
    CONSTRAINT payroll_runs_reason_valid CHECK (length(btrim(creation_reason)) BETWEEN 3 AND 500),
    CONSTRAINT payroll_runs_snapshot_valid CHECK (jsonb_typeof(policy_snapshot) = 'object'),
    CONSTRAINT payroll_runs_lifecycle_valid CHECK (
        (status = 'DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND submission_reason IS NULL AND decided_by_membership_id IS NULL
            AND decided_at IS NULL AND decision_reason IS NULL)
        OR (status = 'SUBMITTED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL)
        OR (status IN ('FINALIZED','REJECTED') AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> created_by_membership_id
            AND decided_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500)
        OR (status = 'CANCELLED' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND submission_reason IS NULL AND decided_by_membership_id = created_by_membership_id
            AND decided_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500))
);

CREATE UNIQUE INDEX payroll_runs_one_live_month
    ON payroll_runs(school_id, payroll_month)
    WHERE status IN ('DRAFT','SUBMITTED','FINALIZED');
CREATE INDEX payroll_runs_school_month
    ON payroll_runs(organisation_id, school_id, payroll_month DESC, attempt_number DESC, id);

CREATE TABLE payroll_run_staff_results (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    payroll_run_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    salary_profile_id UUID NOT NULL,
    employee_code_snapshot VARCHAR(50) NOT NULL,
    staff_name_snapshot VARCHAR(160) NOT NULL,
    salary_revision_snapshot INTEGER NOT NULL,
    scheduled_working_days SMALLINT NOT NULL,
    recorded_days SMALLINT NOT NULL,
    payable_days NUMERIC(7,4) NOT NULL,
    paid_leave_days NUMERIC(7,4) NOT NULL,
    unpaid_leave_days NUMERIC(7,4) NOT NULL,
    missing_attendance_days SMALLINT NOT NULL,
    proration_factor NUMERIC(9,6) NOT NULL,
    full_gross_amount NUMERIC(18,2) NOT NULL,
    payable_gross_amount NUMERIC(18,2) NOT NULL,
    deduction_amount NUMERIC(18,2) NOT NULL,
    employer_contribution_amount NUMERIC(18,2) NOT NULL,
    net_amount NUMERIC(18,2) NOT NULL,
    warning_codes TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payroll_run_staff_results_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_run_staff_results_run_fk
        FOREIGN KEY (payroll_run_id, organisation_id, school_id)
        REFERENCES payroll_runs(id, organisation_id, school_id),
    CONSTRAINT payroll_run_staff_results_staff_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT payroll_run_staff_results_salary_fk
        FOREIGN KEY (salary_profile_id, organisation_id, school_id)
        REFERENCES staff_salary_profiles(id, organisation_id, school_id),
    CONSTRAINT payroll_run_staff_results_staff_unique UNIQUE (payroll_run_id, staff_profile_id),
    CONSTRAINT payroll_run_staff_results_days_valid CHECK (
        scheduled_working_days BETWEEN 0 AND 366
        AND recorded_days BETWEEN 0 AND scheduled_working_days
        AND missing_attendance_days = scheduled_working_days - recorded_days
        AND payable_days BETWEEN 0 AND scheduled_working_days
        AND paid_leave_days BETWEEN 0 AND scheduled_working_days
        AND unpaid_leave_days BETWEEN 0 AND scheduled_working_days
        AND proration_factor BETWEEN 0 AND 1),
    CONSTRAINT payroll_run_staff_results_amounts_valid CHECK (
        full_gross_amount >= 0 AND payable_gross_amount >= 0 AND deduction_amount >= 0
        AND employer_contribution_amount >= 0 AND net_amount >= 0
        AND deduction_amount <= payable_gross_amount
        AND net_amount = payable_gross_amount - deduction_amount)
);

CREATE INDEX payroll_run_staff_results_run_order
    ON payroll_run_staff_results(payroll_run_id, staff_name_snapshot, staff_profile_id);
CREATE INDEX payroll_run_staff_results_staff_month
    ON payroll_run_staff_results(organisation_id, school_id, staff_profile_id, payroll_run_id);

CREATE TABLE payroll_run_staff_lines (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    payroll_run_staff_result_id UUID NOT NULL,
    payroll_component_id UUID NOT NULL,
    component_code_snapshot VARCHAR(40) NOT NULL,
    component_name_snapshot VARCHAR(120) NOT NULL,
    component_kind_snapshot VARCHAR(30) NOT NULL,
    calculation_method_snapshot VARCHAR(30) NOT NULL,
    attendance_prorated_snapshot BOOLEAN NOT NULL,
    statutory_snapshot BOOLEAN NOT NULL,
    full_amount NUMERIC(18,2) NOT NULL,
    payable_amount NUMERIC(18,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payroll_run_staff_lines_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_run_staff_lines_result_fk
        FOREIGN KEY (payroll_run_staff_result_id, organisation_id, school_id)
        REFERENCES payroll_run_staff_results(id, organisation_id, school_id),
    CONSTRAINT payroll_run_staff_lines_component_fk
        FOREIGN KEY (payroll_component_id, organisation_id, school_id)
        REFERENCES payroll_components(id, organisation_id, school_id),
    CONSTRAINT payroll_run_staff_lines_component_unique
        UNIQUE (payroll_run_staff_result_id, payroll_component_id),
    CONSTRAINT payroll_run_staff_lines_kind_valid CHECK (
        component_kind_snapshot IN ('EARNING','DEDUCTION','EMPLOYER_CONTRIBUTION')),
    CONSTRAINT payroll_run_staff_lines_method_valid CHECK (
        calculation_method_snapshot IN ('FIXED','PERCENT_OF_BASIC')),
    CONSTRAINT payroll_run_staff_lines_amount_valid CHECK (
        full_amount >= 0 AND payable_amount >= 0
        AND (component_kind_snapshot = 'EARNING' OR payable_amount = full_amount)
        AND (NOT attendance_prorated_snapshot OR component_kind_snapshot = 'EARNING'))
);

CREATE INDEX payroll_run_staff_lines_result_order
    ON payroll_run_staff_lines(payroll_run_staff_result_id, component_kind_snapshot,
        component_code_snapshot, id);

CREATE TABLE payroll_run_attendance_sources (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    payroll_run_staff_result_id UUID NOT NULL,
    calendar_day_id UUID NOT NULL,
    staff_attendance_register_id UUID NOT NULL,
    staff_attendance_mark_id UUID NOT NULL,
    attendance_date DATE NOT NULL,
    state_code_snapshot VARCHAR(20) NOT NULL,
    payroll_credit NUMERIC(5,4) NOT NULL,
    staff_leave_request_id UUID,
    leave_type_code_snapshot VARCHAR(40),
    paid_leave_snapshot BOOLEAN,
    leave_fraction NUMERIC(3,2),
    register_row_version BIGINT NOT NULL,
    mark_row_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payroll_run_attendance_sources_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_run_attendance_sources_result_fk
        FOREIGN KEY (payroll_run_staff_result_id, organisation_id, school_id)
        REFERENCES payroll_run_staff_results(id, organisation_id, school_id),
    CONSTRAINT payroll_run_attendance_sources_day_fk
        FOREIGN KEY (calendar_day_id, organisation_id, school_id)
        REFERENCES school_calendar_days(id, organisation_id, school_id),
    CONSTRAINT payroll_run_attendance_sources_register_fk
        FOREIGN KEY (staff_attendance_register_id, organisation_id, school_id)
        REFERENCES staff_attendance_registers(id, organisation_id, school_id),
    CONSTRAINT payroll_run_attendance_sources_mark_fk
        FOREIGN KEY (staff_attendance_mark_id, organisation_id, school_id)
        REFERENCES staff_attendance_marks(id, organisation_id, school_id),
    CONSTRAINT payroll_run_attendance_sources_leave_fk
        FOREIGN KEY (staff_leave_request_id, organisation_id, school_id)
        REFERENCES staff_leave_requests(id, organisation_id, school_id),
    CONSTRAINT payroll_run_attendance_sources_day_unique
        UNIQUE (payroll_run_staff_result_id, attendance_date),
    CONSTRAINT payroll_run_attendance_sources_state_valid CHECK (
        state_code_snapshot IN ('PRESENT','ABSENT','LATE','LEAVE','HALF_DAY','OFFICIAL_DUTY')),
    CONSTRAINT payroll_run_attendance_sources_credit_valid CHECK (payroll_credit BETWEEN 0 AND 1),
    CONSTRAINT payroll_run_attendance_sources_leave_valid CHECK (
        (staff_leave_request_id IS NULL AND leave_type_code_snapshot IS NULL
            AND paid_leave_snapshot IS NULL AND leave_fraction IS NULL)
        OR (staff_leave_request_id IS NOT NULL AND state_code_snapshot = 'LEAVE'
            AND leave_type_code_snapshot IS NOT NULL AND paid_leave_snapshot IS NOT NULL
            AND leave_fraction IN (0.5,1.0)))
);

CREATE INDEX payroll_run_attendance_sources_result_date
    ON payroll_run_attendance_sources(payroll_run_staff_result_id, attendance_date, id);

CREATE TABLE payroll_run_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    payroll_run_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payroll_run_history_run_fk
        FOREIGN KEY (payroll_run_id, organisation_id, school_id)
        REFERENCES payroll_runs(id, organisation_id, school_id),
    CONSTRAINT payroll_run_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_run_history_event_valid CHECK (
        event_type IN ('PREPARED','SUBMITTED','FINALIZED','REJECTED','CANCELLED')),
    CONSTRAINT payroll_run_history_status_valid CHECK (
        new_status IN ('DRAFT','SUBMITTED','FINALIZED','REJECTED','CANCELLED')),
    CONSTRAINT payroll_run_history_reason_valid CHECK (length(btrim(reason)) BETWEEN 3 AND 500)
);

CREATE INDEX payroll_run_history_run_time
    ON payroll_run_history(payroll_run_id, occurred_at, id);

CREATE OR REPLACE FUNCTION guard_payroll_run_child_evidence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' AND current_setting('app.payroll_run_prepare', true) = 'approved' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'payroll run source and calculation evidence is immutable';
END; $$;

CREATE TRIGGER payroll_run_staff_results_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_run_staff_results
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_run_child_evidence();
CREATE TRIGGER payroll_run_staff_lines_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_run_staff_lines
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_run_child_evidence();
CREATE TRIGGER payroll_run_attendance_sources_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_run_attendance_sources
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_run_child_evidence();

CREATE OR REPLACE FUNCTION validate_complete_payroll_run(
    target_run_id UUID, target_organisation_id UUID, target_school_id UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE run_record payroll_runs%ROWTYPE;
DECLARE result_count INTEGER;
DECLARE scheduled_total INTEGER;
DECLARE recorded_total INTEGER;
DECLARE payable_total NUMERIC(12,4);
DECLARE full_gross_total NUMERIC(18,2);
DECLARE payable_gross_total NUMERIC(18,2);
DECLARE deduction_total NUMERIC(18,2);
DECLARE employer_total NUMERIC(18,2);
DECLARE net_total NUMERIC(18,2);
DECLARE invalid_results INTEGER;
BEGIN
    SELECT * INTO run_record FROM payroll_runs
    WHERE id=target_run_id AND organisation_id=target_organisation_id
      AND school_id=target_school_id;
    IF run_record.id IS NULL THEN RAISE EXCEPTION 'payroll run was not found'; END IF;

    SELECT count(*),COALESCE(sum(scheduled_working_days),0),COALESCE(sum(recorded_days),0),
           COALESCE(sum(payable_days),0),COALESCE(sum(full_gross_amount),0),
           COALESCE(sum(payable_gross_amount),0),COALESCE(sum(deduction_amount),0),
           COALESCE(sum(employer_contribution_amount),0),COALESCE(sum(net_amount),0)
      INTO result_count,scheduled_total,recorded_total,payable_total,full_gross_total,
           payable_gross_total,deduction_total,employer_total,net_total
    FROM payroll_run_staff_results
    WHERE payroll_run_id=target_run_id AND organisation_id=target_organisation_id
      AND school_id=target_school_id;

    IF result_count <> run_record.staff_count
       OR scheduled_total <> run_record.scheduled_working_days_total
       OR recorded_total <> run_record.recorded_days_total
       OR payable_total <> run_record.payable_days_total
       OR full_gross_total <> run_record.full_gross_total
       OR payable_gross_total <> run_record.payable_gross_total
       OR deduction_total <> run_record.deduction_total
       OR employer_total <> run_record.employer_contribution_total
       OR net_total <> run_record.net_total THEN
        RAISE EXCEPTION 'payroll run header totals do not match staff results';
    END IF;

    SELECT count(*) INTO invalid_results
    FROM payroll_run_staff_results result
    WHERE result.payroll_run_id=target_run_id
      AND result.organisation_id=target_organisation_id AND result.school_id=target_school_id
      AND (
        (SELECT count(*) FROM payroll_run_attendance_sources source
         WHERE source.payroll_run_staff_result_id=result.id
           AND source.organisation_id=result.organisation_id
           AND source.school_id=result.school_id) <> result.scheduled_working_days
        OR (SELECT COALESCE(sum(source.payroll_credit),0)
            FROM payroll_run_attendance_sources source
            WHERE source.payroll_run_staff_result_id=result.id
              AND source.organisation_id=result.organisation_id
              AND source.school_id=result.school_id) <> result.payable_days
        OR (SELECT COALESCE(sum(line.full_amount),0)
            FROM payroll_run_staff_lines line
            WHERE line.payroll_run_staff_result_id=result.id
              AND line.organisation_id=result.organisation_id AND line.school_id=result.school_id
              AND line.component_kind_snapshot='EARNING') <> result.full_gross_amount
        OR (SELECT COALESCE(sum(line.payable_amount),0)
            FROM payroll_run_staff_lines line
            WHERE line.payroll_run_staff_result_id=result.id
              AND line.organisation_id=result.organisation_id AND line.school_id=result.school_id
              AND line.component_kind_snapshot='EARNING') <> result.payable_gross_amount
        OR (SELECT COALESCE(sum(line.payable_amount),0)
            FROM payroll_run_staff_lines line
            WHERE line.payroll_run_staff_result_id=result.id
              AND line.organisation_id=result.organisation_id AND line.school_id=result.school_id
              AND line.component_kind_snapshot='DEDUCTION') <> result.deduction_amount
        OR (SELECT COALESCE(sum(line.payable_amount),0)
            FROM payroll_run_staff_lines line
            WHERE line.payroll_run_staff_result_id=result.id
              AND line.organisation_id=result.organisation_id AND line.school_id=result.school_id
              AND line.component_kind_snapshot='EMPLOYER_CONTRIBUTION')
              <> result.employer_contribution_amount
      );
    IF invalid_results <> 0 THEN
        RAISE EXCEPTION 'payroll staff results do not match frozen source and component evidence';
    END IF;
END; $$;

CREATE OR REPLACE FUNCTION guard_payroll_run()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id
       OR OLD.payroll_run_policy_id <> NEW.payroll_run_policy_id
       OR OLD.policy_snapshot <> NEW.policy_snapshot
       OR OLD.payroll_month <> NEW.payroll_month OR OLD.period_start <> NEW.period_start
       OR OLD.period_end <> NEW.period_end OR OLD.attempt_number <> NEW.attempt_number
       OR OLD.run_code <> NEW.run_code OR OLD.idempotency_key <> NEW.idempotency_key
       OR OLD.input_fingerprint <> NEW.input_fingerprint
       OR OLD.currency_code <> NEW.currency_code OR OLD.staff_count <> NEW.staff_count
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
    RAISE EXCEPTION 'invalid payroll run transition';
END; $$;

CREATE TRIGGER payroll_runs_guard_history
    BEFORE UPDATE ON payroll_runs
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_run();

CREATE OR REPLACE FUNCTION prevent_payroll_run_record_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'payroll run history is append-only'; END; $$;

CREATE TRIGGER payroll_runs_no_delete
    BEFORE DELETE ON payroll_runs
    FOR EACH ROW EXECUTE FUNCTION prevent_payroll_run_record_mutation();
CREATE TRIGGER payroll_run_history_immutable
    BEFORE UPDATE OR DELETE ON payroll_run_history
    FOR EACH ROW EXECUTE FUNCTION prevent_payroll_run_record_mutation();
CREATE TRIGGER payroll_run_policies_no_delete
    BEFORE DELETE ON payroll_run_policies
    FOR EACH ROW EXECUTE FUNCTION prevent_payroll_run_record_mutation();

ALTER TABLE payroll_run_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_run_staff_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_run_staff_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_run_attendance_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_run_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY payroll_run_policies_tenant_all ON payroll_run_policies
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_runs_tenant_all ON payroll_runs
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_run_staff_results_tenant_all ON payroll_run_staff_results
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_run_staff_lines_tenant_all ON payroll_run_staff_lines
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_run_attendance_sources_tenant_all ON payroll_run_attendance_sources
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_run_history_tenant_all ON payroll_run_history
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());

COMMENT ON TABLE payroll_run_policies IS
    'School-approved monthly payroll calculation controls. Legal and statutory decisions remain explicit configuration.';
COMMENT ON TABLE payroll_runs IS
    'Retry-safe monthly payroll headers with frozen input fingerprints, totals, and maker-checker lifecycle evidence.';
COMMENT ON TABLE payroll_run_staff_results IS
    'Immutable staff payroll results derived from approved salary terms and frozen attendance/leave evidence.';
COMMENT ON TABLE payroll_run_staff_lines IS
    'Frozen component-level full and payable values for one staff result.';
COMMENT ON TABLE payroll_run_attendance_sources IS
    'Daily submitted workforce marks and approved leave facts used by a payroll result.';
COMMENT ON TABLE payroll_run_history IS
    'Append-only monthly payroll lifecycle evidence.';

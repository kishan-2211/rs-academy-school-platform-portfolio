INSERT INTO capabilities (code, description, risk_level) VALUES
    ('workforce.self', 'Read own staff attendance and manage own leave requests', 'SENSITIVE'),
    ('workforce.read', 'Read staff attendance, leave, and balance records within school scope', 'SENSITIVE'),
    ('workforce.attendance.record', 'Open and submit school-scoped staff attendance registers', 'RESTRICTED'),
    ('workforce.attendance.correct', 'Approve submitted staff attendance corrections', 'RESTRICTED'),
    ('workforce.leave.approve', 'Approve or reject staff leave with balance and attendance synchronization', 'RESTRICTED'),
    ('workforce.configure', 'Configure staff attendance policies, leave types, and balance adjustments', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'workforce.self'),
    ('00000000-0000-4000-8000-000000000001', 'workforce.read'),
    ('00000000-0000-4000-8000-000000000001', 'workforce.attendance.record'),
    ('00000000-0000-4000-8000-000000000001', 'workforce.attendance.correct'),
    ('00000000-0000-4000-8000-000000000001', 'workforce.leave.approve'),
    ('00000000-0000-4000-8000-000000000001', 'workforce.configure'),
    ('00000000-0000-4000-8000-000000000007', 'workforce.self'),
    ('00000000-0000-4000-8000-000000000007', 'workforce.read'),
    ('00000000-0000-4000-8000-000000000007', 'workforce.attendance.record'),
    ('00000000-0000-4000-8000-000000000007', 'workforce.attendance.correct'),
    ('00000000-0000-4000-8000-000000000007', 'workforce.leave.approve'),
    ('00000000-0000-4000-8000-000000000007', 'workforce.configure'),
    ('00000000-0000-4000-8000-000000000002', 'workforce.self'),
    ('00000000-0000-4000-8000-000000000003', 'workforce.self'),
    ('00000000-0000-4000-8000-000000000004', 'workforce.self'),
    ('00000000-0000-4000-8000-000000000008', 'workforce.self'),
    ('00000000-0000-4000-8000-000000000009', 'workforce.self'),
    ('00000000-0000-4000-8000-000000000011', 'workforce.self')
ON CONFLICT DO NOTHING;

CREATE TABLE workforce_attendance_policies (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    policy_name VARCHAR(120) NOT NULL,
    enabled_state_codes TEXT[] NOT NULL,
    state_weights JSONB NOT NULL,
    lock_after_hours SMALLINT NOT NULL DEFAULT 24,
    warning_threshold_percent NUMERIC(5,2) NOT NULL DEFAULT 75,
    consecutive_absence_threshold SMALLINT NOT NULL DEFAULT 3,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT workforce_attendance_policies_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT workforce_attendance_policies_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT workforce_attendance_policies_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT workforce_attendance_policies_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT workforce_attendance_policies_states_nonempty CHECK (cardinality(enabled_state_codes) BETWEEN 3 AND 8),
    CONSTRAINT workforce_attendance_policies_states_valid CHECK (
        enabled_state_codes <@ ARRAY['PRESENT','ABSENT','LATE','LEAVE','HALF_DAY','OFFICIAL_DUTY']::TEXT[]
        AND enabled_state_codes @> ARRAY['PRESENT','ABSENT','LEAVE']::TEXT[]),
    CONSTRAINT workforce_attendance_policies_weights_object CHECK (jsonb_typeof(state_weights) = 'object'),
    CONSTRAINT workforce_attendance_policies_lock_valid CHECK (lock_after_hours BETWEEN 0 AND 720),
    CONSTRAINT workforce_attendance_policies_warning_valid CHECK (warning_threshold_percent BETWEEN 0 AND 100),
    CONSTRAINT workforce_attendance_policies_absence_valid CHECK (consecutive_absence_threshold BETWEEN 1 AND 30),
    CONSTRAINT workforce_attendance_policies_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE UNIQUE INDEX workforce_attendance_policies_one_active_idx
    ON workforce_attendance_policies (school_id, campus_id, academic_session_id)
    WHERE status = 'ACTIVE';

CREATE TABLE staff_leave_types (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    code VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    paid BOOLEAN NOT NULL DEFAULT TRUE,
    balance_required BOOLEAN NOT NULL DEFAULT TRUE,
    max_days_per_request NUMERIC(6,2) NOT NULL DEFAULT 30,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_leave_types_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_leave_types_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT staff_leave_types_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_leave_types_natural_key_unique UNIQUE (school_id, code),
    CONSTRAINT staff_leave_types_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT staff_leave_types_days_valid CHECK (max_days_per_request BETWEEN 0.5 AND 366),
    CONSTRAINT staff_leave_types_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE staff_leave_requests (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    leave_type_id UUID NOT NULL,
    leave_type_snapshot JSONB NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    duration VARCHAR(20) NOT NULL DEFAULT 'FULL_DAY',
    requested_day_count NUMERIC(6,2) NOT NULL,
    request_reason VARCHAR(1000) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    requested_by_membership_id UUID NOT NULL,
    decided_by_membership_id UUID,
    decision_reason VARCHAR(500),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_leave_requests_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_leave_requests_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT staff_leave_requests_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT staff_leave_requests_staff_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_leave_requests_type_fk
        FOREIGN KEY (leave_type_id, organisation_id, school_id)
        REFERENCES staff_leave_types(id, organisation_id, school_id),
    CONSTRAINT staff_leave_requests_requester_fk
        FOREIGN KEY (requested_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_leave_requests_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_leave_requests_dates_valid CHECK (ends_on >= starts_on),
    CONSTRAINT staff_leave_requests_duration_valid CHECK (duration IN ('FULL_DAY','FIRST_HALF','SECOND_HALF')),
    CONSTRAINT staff_leave_requests_half_day_valid CHECK (duration = 'FULL_DAY' OR starts_on = ends_on),
    CONSTRAINT staff_leave_requests_count_valid CHECK (requested_day_count BETWEEN 0.5 AND 366),
    CONSTRAINT staff_leave_requests_reason_valid CHECK (length(btrim(request_reason)) BETWEEN 3 AND 1000),
    CONSTRAINT staff_leave_requests_idempotency_valid CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 100),
    CONSTRAINT staff_leave_requests_snapshot_object CHECK (jsonb_typeof(leave_type_snapshot) = 'object'),
    CONSTRAINT staff_leave_requests_status_valid CHECK (status IN ('SUBMITTED','APPROVED','REJECTED')),
    CONSTRAINT staff_leave_requests_decision_valid CHECK (
        (status = 'SUBMITTED' AND decided_by_membership_id IS NULL AND decision_reason IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED','REJECTED') AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> requested_by_membership_id
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500 AND decided_at IS NOT NULL))
);

CREATE INDEX staff_leave_requests_staff_dates_idx
    ON staff_leave_requests (organisation_id, school_id, staff_profile_id, starts_on, ends_on, status);
CREATE INDEX staff_leave_requests_status_idx
    ON staff_leave_requests (organisation_id, school_id, status, created_at);

CREATE TABLE staff_leave_request_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_leave_request_id UUID NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT staff_leave_request_history_request_fk
        FOREIGN KEY (staff_leave_request_id, organisation_id, school_id)
        REFERENCES staff_leave_requests(id, organisation_id, school_id),
    CONSTRAINT staff_leave_request_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_leave_request_history_status_valid CHECK (
        new_status IN ('SUBMITTED','APPROVED','REJECTED')
        AND (previous_status IS NULL OR previous_status = 'SUBMITTED')),
    CONSTRAINT staff_leave_request_history_reason_valid CHECK (length(btrim(reason)) BETWEEN 3 AND 500)
);

CREATE TABLE staff_leave_balance_entries (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    leave_type_id UUID NOT NULL,
    source_leave_request_id UUID,
    entry_kind VARCHAR(30) NOT NULL,
    quantity NUMERIC(7,2) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT staff_leave_balance_entries_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT staff_leave_balance_entries_staff_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_leave_balance_entries_type_fk
        FOREIGN KEY (leave_type_id, organisation_id, school_id)
        REFERENCES staff_leave_types(id, organisation_id, school_id),
    CONSTRAINT staff_leave_balance_entries_source_fk
        FOREIGN KEY (source_leave_request_id, organisation_id, school_id)
        REFERENCES staff_leave_requests(id, organisation_id, school_id),
    CONSTRAINT staff_leave_balance_entries_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_leave_balance_entries_kind_valid CHECK (
        entry_kind IN ('OPENING','ACCRUAL','ADJUSTMENT','APPROVED_LEAVE','REVERSAL')),
    CONSTRAINT staff_leave_balance_entries_quantity_valid CHECK (quantity <> 0 AND abs(quantity) <= 366),
    CONSTRAINT staff_leave_balance_entries_reason_valid CHECK (length(btrim(reason)) BETWEEN 3 AND 500),
    CONSTRAINT staff_leave_balance_entries_leave_sign_valid CHECK (
        entry_kind <> 'APPROVED_LEAVE' OR quantity < 0),
    CONSTRAINT staff_leave_balance_entries_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT staff_leave_balance_entries_idempotency_valid CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 100)
);

CREATE UNIQUE INDEX staff_leave_balance_entries_approved_once_idx
    ON staff_leave_balance_entries (source_leave_request_id)
    WHERE entry_kind = 'APPROVED_LEAVE';
CREATE INDEX staff_leave_balance_entries_balance_idx
    ON staff_leave_balance_entries (organisation_id, school_id, academic_session_id, staff_profile_id, leave_type_id, created_at);

CREATE TABLE staff_attendance_registers (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    calendar_day_id UUID NOT NULL,
    workforce_attendance_policy_id UUID NOT NULL,
    policy_snapshot JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    expected_staff_count SMALLINT NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_attendance_registers_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_attendance_registers_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_registers_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_registers_day_fk
        FOREIGN KEY (calendar_day_id, organisation_id, school_id)
        REFERENCES school_calendar_days(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_registers_policy_fk
        FOREIGN KEY (workforce_attendance_policy_id, organisation_id, school_id)
        REFERENCES workforce_attendance_policies(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_registers_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_registers_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_registers_natural_key_unique UNIQUE (school_id, campus_id, calendar_day_id),
    CONSTRAINT staff_attendance_registers_snapshot_object CHECK (jsonb_typeof(policy_snapshot) = 'object'),
    CONSTRAINT staff_attendance_registers_status_valid CHECK (status IN ('DRAFT','SUBMITTED','LOCKED')),
    CONSTRAINT staff_attendance_registers_count_valid CHECK (expected_staff_count BETWEEN 0 AND 500),
    CONSTRAINT staff_attendance_registers_submission_valid CHECK (
        (status = 'DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL)
        OR (status IN ('SUBMITTED','LOCKED') AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL))
);

CREATE TABLE staff_attendance_marks (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_attendance_register_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    state_code VARCHAR(20) NOT NULL,
    check_in_time TIME,
    check_out_time TIME,
    note VARCHAR(240),
    recorded_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_attendance_marks_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_attendance_marks_register_fk
        FOREIGN KEY (staff_attendance_register_id, organisation_id, school_id)
        REFERENCES staff_attendance_registers(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_marks_staff_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_marks_recorder_fk
        FOREIGN KEY (recorded_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_marks_register_staff_unique UNIQUE (staff_attendance_register_id, staff_profile_id),
    CONSTRAINT staff_attendance_marks_state_valid CHECK (
        state_code IN ('PRESENT','ABSENT','LATE','LEAVE','HALF_DAY','OFFICIAL_DUTY')),
    CONSTRAINT staff_attendance_marks_time_valid CHECK (
        check_out_time IS NULL OR check_in_time IS NULL OR check_out_time >= check_in_time)
);

CREATE TABLE staff_attendance_mark_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_attendance_mark_id UUID NOT NULL,
    previous_state_code VARCHAR(20),
    new_state_code VARCHAR(20) NOT NULL,
    previous_check_in_time TIME,
    new_check_in_time TIME,
    previous_check_out_time TIME,
    new_check_out_time TIME,
    change_kind VARCHAR(20) NOT NULL,
    reason VARCHAR(500),
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT staff_attendance_mark_history_mark_fk
        FOREIGN KEY (staff_attendance_mark_id, organisation_id, school_id)
        REFERENCES staff_attendance_marks(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_mark_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_mark_history_state_valid CHECK (
        new_state_code IN ('PRESENT','ABSENT','LATE','LEAVE','HALF_DAY','OFFICIAL_DUTY')
        AND (previous_state_code IS NULL OR previous_state_code IN ('PRESENT','ABSENT','LATE','LEAVE','HALF_DAY','OFFICIAL_DUTY'))),
    CONSTRAINT staff_attendance_mark_history_kind_valid CHECK (
        change_kind IN ('INITIAL','DRAFT_EDIT','CORRECTION','LEAVE_SYNC')),
    CONSTRAINT staff_attendance_mark_history_reason_valid CHECK (
        (change_kind = 'INITIAL' AND reason IS NULL)
        OR (change_kind <> 'INITIAL' AND length(btrim(reason)) BETWEEN 3 AND 500))
);

CREATE TABLE staff_attendance_correction_requests (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_attendance_mark_id UUID NOT NULL,
    requested_state_code VARCHAR(20) NOT NULL,
    requested_check_in_time TIME,
    requested_check_out_time TIME,
    request_reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by_membership_id UUID NOT NULL,
    decided_by_membership_id UUID,
    decision_reason VARCHAR(500),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_attendance_corrections_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_attendance_corrections_mark_fk
        FOREIGN KEY (staff_attendance_mark_id, organisation_id, school_id)
        REFERENCES staff_attendance_marks(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_corrections_requester_fk
        FOREIGN KEY (requested_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_corrections_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_attendance_corrections_state_valid CHECK (
        requested_state_code IN ('PRESENT','ABSENT','LATE','LEAVE','HALF_DAY','OFFICIAL_DUTY')),
    CONSTRAINT staff_attendance_corrections_time_valid CHECK (
        requested_check_out_time IS NULL OR requested_check_in_time IS NULL OR requested_check_out_time >= requested_check_in_time),
    CONSTRAINT staff_attendance_corrections_reason_valid CHECK (length(btrim(request_reason)) BETWEEN 3 AND 500),
    CONSTRAINT staff_attendance_corrections_status_valid CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT staff_attendance_corrections_decision_valid CHECK (
        (status = 'PENDING' AND decided_by_membership_id IS NULL AND decision_reason IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED','REJECTED') AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> requested_by_membership_id
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500 AND decided_at IS NOT NULL))
);

CREATE UNIQUE INDEX staff_attendance_corrections_one_pending_idx
    ON staff_attendance_correction_requests (staff_attendance_mark_id) WHERE status = 'PENDING';
CREATE INDEX staff_attendance_registers_day_idx
    ON staff_attendance_registers (organisation_id, school_id, calendar_day_id, status);
CREATE INDEX staff_attendance_marks_staff_idx
    ON staff_attendance_marks (organisation_id, school_id, staff_profile_id, staff_attendance_register_id);

CREATE OR REPLACE FUNCTION guard_workforce_attendance_register()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.academic_session_id <> NEW.academic_session_id OR OLD.campus_id <> NEW.campus_id
       OR OLD.calendar_day_id <> NEW.calendar_day_id
       OR OLD.workforce_attendance_policy_id <> NEW.workforce_attendance_policy_id
       OR OLD.policy_snapshot <> NEW.policy_snapshot OR OLD.expected_staff_count <> NEW.expected_staff_count
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'staff attendance register identity and snapshots are immutable';
    END IF;
    IF OLD.status <> 'DRAFT'
       AND (OLD.submitted_by_membership_id IS DISTINCT FROM NEW.submitted_by_membership_id
            OR OLD.submitted_at IS DISTINCT FROM NEW.submitted_at) THEN
        RAISE EXCEPTION 'staff attendance submission evidence is immutable';
    END IF;
    IF OLD.status = 'LOCKED' THEN RAISE EXCEPTION 'locked staff attendance registers are immutable'; END IF;
    IF OLD.status = 'SUBMITTED' AND NEW.status NOT IN ('SUBMITTED','LOCKED') THEN
        RAISE EXCEPTION 'submitted staff attendance registers cannot return to draft';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT','SUBMITTED') THEN
        RAISE EXCEPTION 'invalid staff attendance register transition';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER staff_attendance_registers_guard_history
    BEFORE UPDATE ON staff_attendance_registers
    FOR EACH ROW EXECUTE FUNCTION guard_workforce_attendance_register();

CREATE OR REPLACE FUNCTION guard_submitted_staff_attendance_mark()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE register_status VARCHAR(20);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'staff attendance marks cannot be deleted; use a controlled correction';
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.staff_attendance_register_id <> NEW.staff_attendance_register_id
       OR OLD.staff_profile_id <> NEW.staff_profile_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'staff attendance mark identity is immutable';
    END IF;
    SELECT status INTO register_status FROM staff_attendance_registers
    WHERE id = NEW.staff_attendance_register_id;
    IF register_status <> 'DRAFT'
       AND current_setting('app.staff_attendance_correction', TRUE) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'submitted staff attendance marks require an approved correction';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER staff_attendance_marks_guard_submitted
    BEFORE UPDATE OR DELETE ON staff_attendance_marks
    FOR EACH ROW EXECUTE FUNCTION guard_submitted_staff_attendance_mark();

CREATE OR REPLACE FUNCTION guard_staff_leave_request()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.academic_session_id <> NEW.academic_session_id OR OLD.staff_profile_id <> NEW.staff_profile_id
       OR OLD.leave_type_id <> NEW.leave_type_id OR OLD.leave_type_snapshot <> NEW.leave_type_snapshot
       OR OLD.starts_on <> NEW.starts_on OR OLD.ends_on <> NEW.ends_on OR OLD.duration <> NEW.duration
       OR OLD.requested_day_count <> NEW.requested_day_count OR OLD.request_reason <> NEW.request_reason
       OR OLD.idempotency_key <> NEW.idempotency_key
       OR OLD.requested_by_membership_id <> NEW.requested_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'submitted staff leave request evidence is immutable';
    END IF;
    IF OLD.status <> 'SUBMITTED' OR NEW.status NOT IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'invalid staff leave decision transition';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER staff_leave_requests_guard_history
    BEFORE UPDATE ON staff_leave_requests
    FOR EACH ROW EXECUTE FUNCTION guard_staff_leave_request();

CREATE OR REPLACE FUNCTION prevent_workforce_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'workforce history and balance evidence is append-only'; END; $$;

CREATE TRIGGER staff_attendance_mark_history_immutable
    BEFORE UPDATE OR DELETE ON staff_attendance_mark_history
    FOR EACH ROW EXECUTE FUNCTION prevent_workforce_evidence_mutation();
CREATE TRIGGER staff_leave_request_history_immutable
    BEFORE UPDATE OR DELETE ON staff_leave_request_history
    FOR EACH ROW EXECUTE FUNCTION prevent_workforce_evidence_mutation();
CREATE TRIGGER staff_leave_balance_entries_immutable
    BEFORE UPDATE OR DELETE ON staff_leave_balance_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_workforce_evidence_mutation();

CREATE OR REPLACE FUNCTION guard_staff_attendance_correction()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'invalid staff attendance correction transition';
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.created_at <> NEW.created_at
       OR OLD.staff_attendance_mark_id <> NEW.staff_attendance_mark_id
       OR OLD.requested_state_code <> NEW.requested_state_code
       OR OLD.requested_check_in_time IS DISTINCT FROM NEW.requested_check_in_time
       OR OLD.requested_check_out_time IS DISTINCT FROM NEW.requested_check_out_time
       OR OLD.request_reason <> NEW.request_reason
       OR OLD.requested_by_membership_id <> NEW.requested_by_membership_id THEN
        RAISE EXCEPTION 'staff attendance correction evidence is immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER staff_attendance_corrections_guard_history
    BEFORE UPDATE ON staff_attendance_correction_requests
    FOR EACH ROW EXECUTE FUNCTION guard_staff_attendance_correction();

ALTER TABLE workforce_attendance_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_leave_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_leave_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_leave_request_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_leave_balance_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_attendance_registers ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_attendance_marks ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_attendance_mark_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_attendance_correction_requests ENABLE ROW LEVEL SECURITY;

CREATE POLICY workforce_attendance_policies_tenant_all ON workforce_attendance_policies
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_leave_types_tenant_all ON staff_leave_types
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_leave_requests_tenant_all ON staff_leave_requests
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_leave_request_history_tenant_all ON staff_leave_request_history
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_leave_balance_entries_tenant_all ON staff_leave_balance_entries
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_attendance_registers_tenant_all ON staff_attendance_registers
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_attendance_marks_tenant_all ON staff_attendance_marks
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_attendance_mark_history_tenant_all ON staff_attendance_mark_history
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_attendance_corrections_tenant_all ON staff_attendance_correction_requests
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE workforce_attendance_policies IS
    'Authority-configured staff attendance meaning. Each register freezes a policy snapshot so later changes cannot rewrite history.';
COMMENT ON TABLE staff_leave_balance_entries IS
    'Append-only leave balance ledger. Approved leave is a debit and corrections require an explicit compensating entry.';
COMMENT ON TABLE staff_leave_requests IS
    'Immutable submitted leave evidence with independent approval and a frozen leave-type snapshot.';
COMMENT ON TABLE staff_attendance_mark_history IS
    'Append-only staff attendance evidence for initial marks, draft edits, approved corrections, and approved leave synchronization.';

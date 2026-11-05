INSERT INTO capabilities (code, description, risk_level) VALUES
    ('attendance.configure', 'Configure school attendance policy, calendar days, states, cut-offs, and thresholds', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'attendance.configure'),
    ('00000000-0000-4000-8000-000000000001', 'attendance.record')
ON CONFLICT DO NOTHING;

CREATE TABLE attendance_policies (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    policy_name VARCHAR(120) NOT NULL,
    register_mode VARCHAR(20) NOT NULL,
    enabled_state_codes VARCHAR(20)[] NOT NULL,
    state_weights JSONB NOT NULL,
    submission_cutoff TIME,
    lock_after_hours SMALLINT NOT NULL DEFAULT 0,
    guardian_explanation_days SMALLINT NOT NULL DEFAULT 7,
    warning_threshold_percent NUMERIC(5,2),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT attendance_policies_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT attendance_policies_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT attendance_policies_session_unique UNIQUE (school_id, academic_session_id),
    CONSTRAINT attendance_policies_mode_valid CHECK (register_mode IN ('DAILY', 'PERIOD', 'BOTH')),
    CONSTRAINT attendance_policies_states_nonempty CHECK (cardinality(enabled_state_codes) BETWEEN 2 AND 12),
    CONSTRAINT attendance_policies_states_valid CHECK (
        enabled_state_codes <@ ARRAY['PRESENT','ABSENT','LATE','EXCUSED','HALF_DAY','SCHOOL_ACTIVITY']::VARCHAR(20)[]
        AND enabled_state_codes @> ARRAY['PRESENT','ABSENT']::VARCHAR(20)[]
    ),
    CONSTRAINT attendance_policies_weights_object CHECK (jsonb_typeof(state_weights) = 'object'),
    CONSTRAINT attendance_policies_lock_valid CHECK (lock_after_hours BETWEEN 0 AND 720),
    CONSTRAINT attendance_policies_explanation_valid CHECK (guardian_explanation_days BETWEEN 0 AND 90),
    CONSTRAINT attendance_policies_threshold_valid CHECK (
        warning_threshold_percent IS NULL OR warning_threshold_percent BETWEEN 0 AND 100
    ),
    CONSTRAINT attendance_policies_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE school_calendar_days (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    calendar_date DATE NOT NULL,
    day_type VARCHAR(20) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    attendance_required BOOLEAN NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT school_calendar_days_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT school_calendar_days_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT school_calendar_days_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT school_calendar_days_natural_key_unique
        UNIQUE (campus_id, academic_session_id, calendar_date),
    CONSTRAINT school_calendar_days_type_valid CHECK (
        day_type IN ('INSTRUCTIONAL', 'EXAM', 'EVENT', 'HOLIDAY', 'CLOSURE')
    ),
    CONSTRAINT school_calendar_days_attendance_valid CHECK (
        (attendance_required AND day_type IN ('INSTRUCTIONAL', 'EXAM', 'EVENT'))
        OR (NOT attendance_required)
    ),
    CONSTRAINT school_calendar_days_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE attendance_registers (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    class_section_id UUID NOT NULL,
    calendar_day_id UUID NOT NULL,
    attendance_policy_id UUID NOT NULL,
    policy_snapshot JSONB NOT NULL,
    bell_schedule_period_id UUID,
    period_name_snapshot VARCHAR(80),
    period_starts_at_snapshot TIME,
    period_ends_at_snapshot TIME,
    register_mode VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    expected_learner_count INTEGER NOT NULL DEFAULT 0,
    created_by_membership_id UUID NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT attendance_registers_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT attendance_registers_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT attendance_registers_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT attendance_registers_section_fk
        FOREIGN KEY (class_section_id, organisation_id, school_id)
        REFERENCES class_sections(id, organisation_id, school_id),
    CONSTRAINT attendance_registers_calendar_fk
        FOREIGN KEY (calendar_day_id, organisation_id, school_id)
        REFERENCES school_calendar_days(id, organisation_id, school_id),
    CONSTRAINT attendance_registers_policy_fk
        FOREIGN KEY (attendance_policy_id, organisation_id, school_id)
        REFERENCES attendance_policies(id, organisation_id, school_id),
    CONSTRAINT attendance_registers_period_fk
        FOREIGN KEY (bell_schedule_period_id, organisation_id, school_id)
        REFERENCES bell_schedule_periods(id, organisation_id, school_id),
    CONSTRAINT attendance_registers_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT attendance_registers_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT attendance_registers_natural_key_unique
        UNIQUE NULLS NOT DISTINCT (class_section_id, calendar_day_id, bell_schedule_period_id),
    CONSTRAINT attendance_registers_mode_valid CHECK (register_mode IN ('DAILY', 'PERIOD')),
    CONSTRAINT attendance_registers_period_mode_valid CHECK (
        (register_mode = 'DAILY' AND bell_schedule_period_id IS NULL
            AND period_name_snapshot IS NULL AND period_starts_at_snapshot IS NULL AND period_ends_at_snapshot IS NULL)
        OR (register_mode = 'PERIOD' AND bell_schedule_period_id IS NOT NULL
            AND period_name_snapshot IS NOT NULL AND period_starts_at_snapshot IS NOT NULL
            AND period_ends_at_snapshot > period_starts_at_snapshot)
    ),
    CONSTRAINT attendance_registers_status_valid CHECK (status IN ('DRAFT', 'SUBMITTED', 'LOCKED')),
    CONSTRAINT attendance_registers_policy_snapshot_object CHECK (jsonb_typeof(policy_snapshot) = 'object'),
    CONSTRAINT attendance_registers_count_valid CHECK (expected_learner_count BETWEEN 0 AND 500),
    CONSTRAINT attendance_registers_submission_valid CHECK (
        (status = 'DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL)
        OR (status IN ('SUBMITTED', 'LOCKED')
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL)
    )
);

CREATE TABLE attendance_marks (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    attendance_register_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    enrolment_id UUID NOT NULL,
    state_code VARCHAR(20) NOT NULL,
    note VARCHAR(240),
    recorded_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT attendance_marks_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT attendance_marks_register_fk
        FOREIGN KEY (attendance_register_id, organisation_id, school_id)
        REFERENCES attendance_registers(id, organisation_id, school_id),
    CONSTRAINT attendance_marks_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT attendance_marks_enrolment_fk
        FOREIGN KEY (enrolment_id, organisation_id, school_id)
        REFERENCES student_enrolments(id, organisation_id, school_id),
    CONSTRAINT attendance_marks_recorder_fk
        FOREIGN KEY (recorded_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT attendance_marks_register_learner_unique UNIQUE (attendance_register_id, learner_id),
    CONSTRAINT attendance_marks_state_valid CHECK (
        state_code IN ('PRESENT','ABSENT','LATE','EXCUSED','HALF_DAY','SCHOOL_ACTIVITY')
    )
);

CREATE TABLE attendance_mark_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    attendance_mark_id UUID NOT NULL,
    previous_state_code VARCHAR(20),
    new_state_code VARCHAR(20) NOT NULL,
    change_kind VARCHAR(20) NOT NULL,
    reason VARCHAR(240),
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT attendance_mark_history_mark_fk
        FOREIGN KEY (attendance_mark_id, organisation_id, school_id)
        REFERENCES attendance_marks(id, organisation_id, school_id),
    CONSTRAINT attendance_mark_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT attendance_mark_history_state_valid CHECK (
        new_state_code IN ('PRESENT','ABSENT','LATE','EXCUSED','HALF_DAY','SCHOOL_ACTIVITY')
        AND (previous_state_code IS NULL OR previous_state_code IN ('PRESENT','ABSENT','LATE','EXCUSED','HALF_DAY','SCHOOL_ACTIVITY'))
    ),
    CONSTRAINT attendance_mark_history_kind_valid CHECK (change_kind IN ('INITIAL', 'DRAFT_EDIT', 'CORRECTION')),
    CONSTRAINT attendance_mark_history_reason_valid CHECK (
        (change_kind = 'INITIAL' AND reason IS NULL) OR (change_kind <> 'INITIAL' AND length(btrim(reason)) > 0)
    )
);

CREATE TABLE attendance_correction_requests (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    attendance_mark_id UUID NOT NULL,
    requested_state_code VARCHAR(20) NOT NULL,
    request_reason VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by_membership_id UUID NOT NULL,
    decided_by_membership_id UUID,
    decision_reason VARCHAR(240),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT attendance_corrections_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT attendance_corrections_mark_fk
        FOREIGN KEY (attendance_mark_id, organisation_id, school_id)
        REFERENCES attendance_marks(id, organisation_id, school_id),
    CONSTRAINT attendance_corrections_requester_fk
        FOREIGN KEY (requested_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT attendance_corrections_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT attendance_corrections_state_valid CHECK (
        requested_state_code IN ('PRESENT','ABSENT','LATE','EXCUSED','HALF_DAY','SCHOOL_ACTIVITY')
    ),
    CONSTRAINT attendance_corrections_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT attendance_corrections_decision_valid CHECK (
        (status = 'PENDING' AND decided_by_membership_id IS NULL AND decision_reason IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by_membership_id IS NOT NULL
            AND length(btrim(decision_reason)) > 0 AND decided_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX attendance_corrections_one_pending_mark_idx
    ON attendance_correction_requests (attendance_mark_id) WHERE status = 'PENDING';

CREATE TABLE attendance_explanations (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    attendance_mark_id UUID NOT NULL,
    guardian_id UUID NOT NULL,
    explanation VARCHAR(1000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    reviewed_by_membership_id UUID,
    review_reason VARCHAR(240),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT attendance_explanations_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT attendance_explanations_mark_fk
        FOREIGN KEY (attendance_mark_id, organisation_id, school_id)
        REFERENCES attendance_marks(id, organisation_id, school_id),
    CONSTRAINT attendance_explanations_guardian_fk
        FOREIGN KEY (guardian_id, organisation_id, school_id)
        REFERENCES guardians(id, organisation_id, school_id),
    CONSTRAINT attendance_explanations_reviewer_fk
        FOREIGN KEY (reviewed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT attendance_explanations_one_guardian_mark_unique UNIQUE (attendance_mark_id, guardian_id),
    CONSTRAINT attendance_explanations_status_valid CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'DECLINED')),
    CONSTRAINT attendance_explanations_review_valid CHECK (
        (status = 'SUBMITTED' AND reviewed_by_membership_id IS NULL AND review_reason IS NULL AND reviewed_at IS NULL)
        OR (status IN ('ACCEPTED', 'DECLINED') AND reviewed_by_membership_id IS NOT NULL
            AND length(btrim(review_reason)) > 0 AND reviewed_at IS NOT NULL)
    )
);

CREATE INDEX attendance_registers_scope_date_idx
    ON attendance_registers (organisation_id, school_id, calendar_day_id, status);
CREATE INDEX attendance_marks_learner_idx
    ON attendance_marks (organisation_id, school_id, learner_id, attendance_register_id);
CREATE INDEX attendance_explanations_status_idx
    ON attendance_explanations (organisation_id, school_id, status, created_at);

CREATE OR REPLACE FUNCTION guard_attendance_register_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id
       OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id
       OR OLD.academic_session_id <> NEW.academic_session_id
       OR OLD.campus_id <> NEW.campus_id
       OR OLD.class_section_id <> NEW.class_section_id
       OR OLD.calendar_day_id <> NEW.calendar_day_id
       OR OLD.attendance_policy_id <> NEW.attendance_policy_id
       OR OLD.policy_snapshot <> NEW.policy_snapshot
       OR OLD.bell_schedule_period_id IS DISTINCT FROM NEW.bell_schedule_period_id
       OR OLD.period_name_snapshot IS DISTINCT FROM NEW.period_name_snapshot
       OR OLD.period_starts_at_snapshot IS DISTINCT FROM NEW.period_starts_at_snapshot
       OR OLD.period_ends_at_snapshot IS DISTINCT FROM NEW.period_ends_at_snapshot
       OR OLD.register_mode <> NEW.register_mode
       OR OLD.expected_learner_count <> NEW.expected_learner_count
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'attendance register identity and snapshots are immutable';
    END IF;
    IF OLD.status = 'LOCKED' THEN RAISE EXCEPTION 'locked attendance registers are immutable'; END IF;
    IF OLD.status = 'SUBMITTED' AND NEW.status NOT IN ('SUBMITTED', 'LOCKED') THEN
        RAISE EXCEPTION 'submitted attendance registers cannot return to draft';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'SUBMITTED') THEN
        RAISE EXCEPTION 'invalid attendance register transition';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER attendance_registers_guard_history
    BEFORE UPDATE ON attendance_registers
    FOR EACH ROW EXECUTE FUNCTION guard_attendance_register_history();

CREATE OR REPLACE FUNCTION guard_submitted_attendance_mark()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE register_status VARCHAR(20);
BEGIN
    SELECT status INTO register_status FROM attendance_registers
    WHERE id = COALESCE(NEW.attendance_register_id, OLD.attendance_register_id);
    IF register_status <> 'DRAFT'
       AND current_setting('app.attendance_correction', TRUE) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'submitted attendance marks require an approved correction';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER attendance_marks_guard_submitted
    BEFORE UPDATE ON attendance_marks
    FOR EACH ROW EXECUTE FUNCTION guard_submitted_attendance_mark();

CREATE OR REPLACE FUNCTION prevent_attendance_history_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'attendance history is append-only'; END; $$;

CREATE TRIGGER attendance_mark_history_prevent_update_or_delete
    BEFORE UPDATE OR DELETE ON attendance_mark_history
    FOR EACH ROW EXECUTE FUNCTION prevent_attendance_history_mutation();

CREATE OR REPLACE FUNCTION guard_attendance_correction_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING' THEN RAISE EXCEPTION 'decided attendance corrections are immutable'; END IF;
    IF NEW.status NOT IN ('APPROVED', 'REJECTED') THEN RAISE EXCEPTION 'invalid attendance correction transition'; END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER attendance_corrections_guard_history
    BEFORE UPDATE ON attendance_correction_requests
    FOR EACH ROW EXECUTE FUNCTION guard_attendance_correction_history();

CREATE OR REPLACE FUNCTION guard_attendance_explanation_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.attendance_mark_id <> NEW.attendance_mark_id
       OR OLD.guardian_id <> NEW.guardian_id
       OR OLD.explanation <> NEW.explanation
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'submitted attendance explanation evidence is immutable';
    END IF;
    IF OLD.status <> 'SUBMITTED' THEN
        RAISE EXCEPTION 'reviewed attendance explanations are immutable';
    END IF;
    IF NEW.status NOT IN ('ACCEPTED', 'DECLINED') THEN
        RAISE EXCEPTION 'invalid attendance explanation transition';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER attendance_explanations_guard_history
    BEFORE UPDATE ON attendance_explanations
    FOR EACH ROW EXECUTE FUNCTION guard_attendance_explanation_history();

ALTER TABLE attendance_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE school_calendar_days ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_registers ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_marks ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_mark_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_correction_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_explanations ENABLE ROW LEVEL SECURITY;

CREATE POLICY attendance_policies_tenant_all ON attendance_policies
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY school_calendar_days_tenant_all ON school_calendar_days
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY attendance_registers_tenant_all ON attendance_registers
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY attendance_marks_tenant_all ON attendance_marks
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY attendance_mark_history_tenant_all ON attendance_mark_history
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY attendance_corrections_tenant_all ON attendance_correction_requests
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY attendance_explanations_tenant_all ON attendance_explanations
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE attendance_policies IS
    'School-configured attendance modes, allowed states and weights, cut-offs, explanation windows, and warning threshold. No regulatory percentage or state credit is hard-coded.';
COMMENT ON TABLE school_calendar_days IS
    'Explicit authority-managed school calendar. Weekends, holidays, closures, and attendance-required days are not inferred by application code.';
COMMENT ON COLUMN attendance_registers.policy_snapshot IS
    'Frozen policy evidence used by this register so later authority configuration changes do not rewrite historical attendance meaning.';
COMMENT ON TABLE attendance_mark_history IS
    'Append-only attendance evidence including draft changes and approved post-submission corrections.';

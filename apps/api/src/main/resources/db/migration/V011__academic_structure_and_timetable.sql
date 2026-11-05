INSERT INTO capabilities (code, description, risk_level) VALUES
    ('academics.read', 'Read school academic structure and published timetable information within assigned scope', 'SENSITIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'academics.read'),
    ('00000000-0000-4000-8000-000000000003', 'academics.read')
ON CONFLICT DO NOTHING;

CREATE UNIQUE INDEX academic_sessions_one_active_school_idx
    ON academic_sessions (school_id)
    WHERE status = 'ACTIVE';

CREATE TABLE academic_terms (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    code VARCHAR(30) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT academic_terms_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT academic_terms_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT academic_terms_natural_key_unique
        UNIQUE (school_id, academic_session_id, code),
    CONSTRAINT academic_terms_dates_valid CHECK (ends_on >= starts_on),
    CONSTRAINT academic_terms_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE school_subjects (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    code VARCHAR(30) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    short_name VARCHAR(40),
    subject_category VARCHAR(20) NOT NULL,
    language_code VARCHAR(20),
    board_subject_code VARCHAR(30),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT school_subjects_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT school_subjects_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT school_subjects_natural_key_unique UNIQUE (school_id, code),
    CONSTRAINT school_subjects_category_valid CHECK (
        subject_category IN ('LANGUAGE', 'CORE', 'SKILL', 'ACTIVITY', 'INTERNAL', 'OTHER')
    ),
    CONSTRAINT school_subjects_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE academic_subject_resources (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    subject_id UUID NOT NULL,
    grade_code VARCHAR(40) NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(1000),
    author VARCHAR(160),
    publisher VARCHAR(160),
    edition VARCHAR(80),
    isbn VARCHAR(32),
    source_url VARCHAR(1000),
    display_order SMALLINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT academic_subject_resources_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT academic_subject_resources_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT academic_subject_resources_subject_fk
        FOREIGN KEY (subject_id, organisation_id, school_id)
        REFERENCES school_subjects(id, organisation_id, school_id),
    CONSTRAINT academic_subject_resources_type_valid CHECK (
        resource_type IN ('SYLLABUS', 'TEXTBOOK', 'WORKBOOK', 'REFERENCE', 'DIGITAL')
    ),
    CONSTRAINT academic_subject_resources_order_valid CHECK (display_order BETWEEN 0 AND 1000),
    CONSTRAINT academic_subject_resources_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX academic_subject_resources_scope_idx
    ON academic_subject_resources (organisation_id, school_id, academic_session_id, grade_code, subject_id, status);

CREATE TABLE section_subject_offerings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    class_section_id UUID NOT NULL,
    subject_id UUID NOT NULL,
    weekly_target_periods SMALLINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT section_subject_offerings_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT section_subject_offerings_section_fk
        FOREIGN KEY (class_section_id, organisation_id, school_id)
        REFERENCES class_sections(id, organisation_id, school_id),
    CONSTRAINT section_subject_offerings_subject_fk
        FOREIGN KEY (subject_id, organisation_id, school_id)
        REFERENCES school_subjects(id, organisation_id, school_id),
    CONSTRAINT section_subject_offerings_natural_key_unique UNIQUE (class_section_id, subject_id),
    CONSTRAINT section_subject_offerings_target_valid CHECK (weekly_target_periods BETWEEN 0 AND 60),
    CONSTRAINT section_subject_offerings_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE teaching_allocations (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    section_subject_offering_id UUID NOT NULL,
    teacher_membership_id UUID NOT NULL,
    allocation_role VARCHAR(20) NOT NULL DEFAULT 'PRIMARY',
    starts_on DATE NOT NULL,
    ends_on DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT teaching_allocations_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT teaching_allocations_offering_fk
        FOREIGN KEY (section_subject_offering_id, organisation_id, school_id)
        REFERENCES section_subject_offerings(id, organisation_id, school_id),
    CONSTRAINT teaching_allocations_teacher_fk
        FOREIGN KEY (teacher_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT teaching_allocations_natural_key_unique
        UNIQUE (section_subject_offering_id, teacher_membership_id, starts_on),
    CONSTRAINT teaching_allocations_dates_valid CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT teaching_allocations_role_valid CHECK (allocation_role IN ('PRIMARY', 'CO_TEACHER')),
    CONSTRAINT teaching_allocations_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE bell_schedule_periods (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    day_of_week SMALLINT NOT NULL,
    sequence_number SMALLINT NOT NULL,
    code VARCHAR(30) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    starts_at TIME NOT NULL,
    ends_at TIME NOT NULL,
    period_kind VARCHAR(20) NOT NULL DEFAULT 'TEACHING',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT bell_schedule_periods_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT bell_schedule_periods_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT bell_schedule_periods_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT bell_schedule_periods_sequence_unique
        UNIQUE (campus_id, academic_session_id, day_of_week, sequence_number),
    CONSTRAINT bell_schedule_periods_code_unique
        UNIQUE (campus_id, academic_session_id, day_of_week, code),
    CONSTRAINT bell_schedule_periods_day_valid CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT bell_schedule_periods_sequence_valid CHECK (sequence_number BETWEEN 1 AND 40),
    CONSTRAINT bell_schedule_periods_time_valid CHECK (ends_at > starts_at),
    CONSTRAINT bell_schedule_periods_kind_valid CHECK (
        period_kind IN ('TEACHING', 'ASSEMBLY', 'BREAK', 'ACTIVITY', 'DISMISSAL')
    ),
    CONSTRAINT bell_schedule_periods_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE timetable_plans (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    academic_term_id UUID,
    plan_name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by_membership_id UUID NOT NULL,
    published_by_membership_id UUID,
    published_at TIMESTAMPTZ,
    supersedes_plan_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT timetable_plans_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT timetable_plans_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT timetable_plans_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT timetable_plans_term_fk
        FOREIGN KEY (academic_term_id, organisation_id, school_id)
        REFERENCES academic_terms(id, organisation_id, school_id),
    CONSTRAINT timetable_plans_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT timetable_plans_publisher_fk
        FOREIGN KEY (published_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT timetable_plans_supersedes_fk
        FOREIGN KEY (supersedes_plan_id, organisation_id, school_id)
        REFERENCES timetable_plans(id, organisation_id, school_id),
    CONSTRAINT timetable_plans_status_valid CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT timetable_plans_publication_state_valid CHECK (
        (status = 'DRAFT' AND published_by_membership_id IS NULL AND published_at IS NULL)
        OR (status IN ('PUBLISHED', 'ARCHIVED')
            AND published_by_membership_id IS NOT NULL AND published_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX timetable_plans_one_draft_idx
    ON timetable_plans (campus_id, academic_session_id)
    WHERE status = 'DRAFT';
CREATE UNIQUE INDEX timetable_plans_one_published_idx
    ON timetable_plans (campus_id, academic_session_id)
    WHERE status = 'PUBLISHED';

CREATE TABLE timetable_entries (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    timetable_plan_id UUID NOT NULL,
    class_section_id UUID NOT NULL,
    section_subject_offering_id UUID NOT NULL,
    bell_schedule_period_id UUID NOT NULL,
    teacher_membership_id UUID NOT NULL,
    subject_name_snapshot VARCHAR(120) NOT NULL,
    subject_short_name_snapshot VARCHAR(40),
    period_name_snapshot VARCHAR(80) NOT NULL,
    day_of_week_snapshot SMALLINT NOT NULL,
    sequence_number_snapshot SMALLINT NOT NULL,
    starts_at_snapshot TIME NOT NULL,
    ends_at_snapshot TIME NOT NULL,
    room_label VARCHAR(80),
    note VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT timetable_entries_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT timetable_entries_plan_fk
        FOREIGN KEY (timetable_plan_id, organisation_id, school_id)
        REFERENCES timetable_plans(id, organisation_id, school_id),
    CONSTRAINT timetable_entries_section_fk
        FOREIGN KEY (class_section_id, organisation_id, school_id)
        REFERENCES class_sections(id, organisation_id, school_id),
    CONSTRAINT timetable_entries_offering_fk
        FOREIGN KEY (section_subject_offering_id, organisation_id, school_id)
        REFERENCES section_subject_offerings(id, organisation_id, school_id),
    CONSTRAINT timetable_entries_period_fk
        FOREIGN KEY (bell_schedule_period_id, organisation_id, school_id)
        REFERENCES bell_schedule_periods(id, organisation_id, school_id),
    CONSTRAINT timetable_entries_teacher_fk
        FOREIGN KEY (teacher_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT timetable_entries_section_slot_unique
        UNIQUE (timetable_plan_id, class_section_id, bell_schedule_period_id),
    CONSTRAINT timetable_entries_teacher_slot_unique
        UNIQUE (timetable_plan_id, teacher_membership_id, bell_schedule_period_id),
    CONSTRAINT timetable_entries_snapshot_day_valid CHECK (day_of_week_snapshot BETWEEN 1 AND 7),
    CONSTRAINT timetable_entries_snapshot_sequence_valid CHECK (sequence_number_snapshot BETWEEN 1 AND 40),
    CONSTRAINT timetable_entries_snapshot_time_valid CHECK (ends_at_snapshot > starts_at_snapshot)
);

CREATE INDEX timetable_entries_plan_section_idx
    ON timetable_entries (timetable_plan_id, class_section_id, bell_schedule_period_id);

CREATE OR REPLACE FUNCTION guard_timetable_plan_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ARCHIVED' THEN
        RAISE EXCEPTION 'archived timetable plans are immutable';
    END IF;
    IF OLD.status = 'PUBLISHED' AND NEW.status <> 'ARCHIVED' THEN
        RAISE EXCEPTION 'published timetable plans may only be archived';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'PUBLISHED') THEN
        RAISE EXCEPTION 'invalid timetable plan transition';
    END IF;
    IF OLD.status <> 'DRAFT' AND (
        OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
        OR OLD.school_id <> NEW.school_id OR OLD.campus_id <> NEW.campus_id
        OR OLD.academic_session_id <> NEW.academic_session_id
        OR OLD.academic_term_id IS DISTINCT FROM NEW.academic_term_id
        OR OLD.plan_name <> NEW.plan_name OR OLD.created_by_membership_id <> NEW.created_by_membership_id
        OR OLD.supersedes_plan_id IS DISTINCT FROM NEW.supersedes_plan_id
        OR OLD.created_at <> NEW.created_at OR OLD.published_at <> NEW.published_at
        OR OLD.published_by_membership_id <> NEW.published_by_membership_id
    ) THEN
        RAISE EXCEPTION 'published timetable plan history cannot be rewritten';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER timetable_plans_guard_history
    BEFORE UPDATE ON timetable_plans
    FOR EACH ROW EXECUTE FUNCTION guard_timetable_plan_history();

CREATE OR REPLACE FUNCTION guard_published_timetable_entries()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    plan_status VARCHAR(20);
BEGIN
    SELECT status INTO plan_status
    FROM timetable_plans
    WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.timetable_plan_id ELSE NEW.timetable_plan_id END;
    IF plan_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'published or archived timetable entries are immutable';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER timetable_entries_guard_history
    BEFORE INSERT OR UPDATE OR DELETE ON timetable_entries
    FOR EACH ROW EXECUTE FUNCTION guard_published_timetable_entries();

ALTER TABLE academic_terms ENABLE ROW LEVEL SECURITY;
ALTER TABLE school_subjects ENABLE ROW LEVEL SECURITY;
ALTER TABLE academic_subject_resources ENABLE ROW LEVEL SECURITY;
ALTER TABLE section_subject_offerings ENABLE ROW LEVEL SECURITY;
ALTER TABLE teaching_allocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE bell_schedule_periods ENABLE ROW LEVEL SECURITY;
ALTER TABLE timetable_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE timetable_entries ENABLE ROW LEVEL SECURITY;

CREATE POLICY academic_terms_tenant_all ON academic_terms
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY school_subjects_tenant_all ON school_subjects
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY academic_subject_resources_tenant_all ON academic_subject_resources
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY section_subject_offerings_tenant_all ON section_subject_offerings
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY teaching_allocations_tenant_all ON teaching_allocations
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY bell_schedule_periods_tenant_all ON bell_schedule_periods
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY timetable_plans_tenant_all ON timetable_plans
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY timetable_entries_tenant_all ON timetable_entries
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE academic_subject_resources IS
    'Authority-managed, session-and-grade-specific syllabus, book, workbook, reference, and digital resource catalogue.';
COMMENT ON TABLE bell_schedule_periods IS
    'Configurable campus bell schedule. No working day, period count, or timing is fixed in application code.';
COMMENT ON TABLE timetable_plans IS
    'Draft timetable versions become immutable when published and are superseded instead of overwritten.';

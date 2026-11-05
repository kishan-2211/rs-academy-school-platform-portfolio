CREATE TABLE class_sections (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    grade_code VARCHAR(40) NOT NULL,
    code VARCHAR(30) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    capacity INTEGER,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT class_sections_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT class_sections_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT class_sections_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT class_sections_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT class_sections_natural_key_unique
        UNIQUE (school_id, campus_id, academic_session_id, grade_code, code),
    CONSTRAINT class_sections_capacity_valid CHECK (capacity IS NULL OR capacity BETWEEN 1 AND 500),
    CONSTRAINT class_sections_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX class_sections_scope_lookup_idx
    ON class_sections (organisation_id, school_id, academic_session_id, campus_id, grade_code, status);

CREATE TABLE student_section_placements (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    enrolment_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    class_section_id UUID NOT NULL,
    roll_number VARCHAR(30),
    starts_on DATE NOT NULL,
    ends_on DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    placed_by_membership_id UUID NOT NULL,
    ended_by_membership_id UUID,
    placement_reason VARCHAR(240) NOT NULL,
    end_reason VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT student_section_placements_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT student_section_placements_enrolment_fk
        FOREIGN KEY (enrolment_id, organisation_id, school_id)
        REFERENCES student_enrolments(id, organisation_id, school_id),
    CONSTRAINT student_section_placements_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT student_section_placements_section_fk
        FOREIGN KEY (class_section_id, organisation_id, school_id)
        REFERENCES class_sections(id, organisation_id, school_id),
    CONSTRAINT student_section_placements_placed_by_fk
        FOREIGN KEY (placed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT student_section_placements_ended_by_fk
        FOREIGN KEY (ended_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT student_section_placements_dates_valid CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT student_section_placements_status_valid CHECK (status IN ('ACTIVE', 'ENDED', 'CANCELLED')),
    CONSTRAINT student_section_placements_end_state_valid CHECK (
        (status = 'ACTIVE' AND ends_on IS NULL AND ended_by_membership_id IS NULL AND end_reason IS NULL)
        OR (status IN ('ENDED', 'CANCELLED') AND ends_on IS NOT NULL
            AND ended_by_membership_id IS NOT NULL AND end_reason IS NOT NULL)
    )
);

CREATE UNIQUE INDEX student_section_placements_one_active_idx
    ON student_section_placements (enrolment_id)
    WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX student_section_placements_roll_number_active_idx
    ON student_section_placements (class_section_id, roll_number)
    WHERE status = 'ACTIVE' AND roll_number IS NOT NULL;
CREATE INDEX student_section_placements_learner_history_idx
    ON student_section_placements (learner_id, starts_on DESC, created_at DESC);

CREATE OR REPLACE FUNCTION guard_student_section_placement_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id <> NEW.id
       OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id
       OR OLD.enrolment_id <> NEW.enrolment_id
       OR OLD.learner_id <> NEW.learner_id
       OR OLD.class_section_id <> NEW.class_section_id
       OR OLD.starts_on <> NEW.starts_on
       OR OLD.placed_by_membership_id <> NEW.placed_by_membership_id
       OR OLD.placement_reason <> NEW.placement_reason
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'historical placement identity cannot be changed';
    END IF;
    IF OLD.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'ended or cancelled placements are immutable';
    END IF;
    IF NEW.status NOT IN ('ACTIVE', 'ENDED', 'CANCELLED') THEN
        RAISE EXCEPTION 'invalid placement transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER student_section_placements_guard_history
    BEFORE UPDATE ON student_section_placements
    FOR EACH ROW EXECUTE FUNCTION guard_student_section_placement_history();

ALTER TABLE class_sections ENABLE ROW LEVEL SECURITY;
ALTER TABLE student_section_placements ENABLE ROW LEVEL SECURITY;

CREATE POLICY class_sections_tenant_select ON class_sections
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY class_sections_tenant_insert ON class_sections
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY class_sections_tenant_update ON class_sections
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY student_section_placements_tenant_select ON student_section_placements
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY student_section_placements_tenant_insert ON student_section_placements
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY student_section_placements_tenant_update ON student_section_placements
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE class_sections IS
    'School-scoped, session-specific sections. Capacity is configuration, not an inferred limit.';
COMMENT ON TABLE student_section_placements IS
    'Effective-dated section placement history. Reassignment ends the active row and appends a new row.';

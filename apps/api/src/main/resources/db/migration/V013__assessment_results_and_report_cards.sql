INSERT INTO capabilities (code, description, risk_level) VALUES
    ('assessment.configure', 'Configure assessment schemes, components, grading scales, rounding, and pass rules', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'assessment.configure'),
    ('00000000-0000-4000-8000-000000000001', 'assessment.record')
ON CONFLICT DO NOTHING;

CREATE TABLE assessment_grading_scales (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    scale_name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT assessment_grading_scales_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT assessment_grading_scales_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT assessment_grading_scales_name_unique
        UNIQUE (school_id, academic_session_id, scale_name),
    CONSTRAINT assessment_grading_scales_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE assessment_grade_bands (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    grading_scale_id UUID NOT NULL,
    grade_code VARCHAR(20) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    minimum_percentage NUMERIC(5,2) NOT NULL,
    maximum_percentage NUMERIC(5,2) NOT NULL,
    grade_point NUMERIC(5,2),
    outcome VARCHAR(20) NOT NULL DEFAULT 'PASS',
    descriptor VARCHAR(240),
    display_order SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT assessment_grade_bands_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT assessment_grade_bands_scale_fk
        FOREIGN KEY (grading_scale_id, organisation_id, school_id)
        REFERENCES assessment_grading_scales(id, organisation_id, school_id),
    CONSTRAINT assessment_grade_bands_code_unique UNIQUE (grading_scale_id, grade_code),
    CONSTRAINT assessment_grade_bands_range_valid CHECK (
        minimum_percentage BETWEEN 0 AND 100
        AND maximum_percentage BETWEEN 0 AND 100
        AND maximum_percentage >= minimum_percentage
    ),
    CONSTRAINT assessment_grade_bands_point_valid CHECK (grade_point IS NULL OR grade_point BETWEEN 0 AND 100),
    CONSTRAINT assessment_grade_bands_outcome_valid CHECK (outcome IN ('PASS', 'FAIL', 'NOT_APPLICABLE')),
    CONSTRAINT assessment_grade_bands_order_valid CHECK (display_order BETWEEN 0 AND 1000)
);

CREATE TABLE assessment_schemes (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    grading_scale_id UUID,
    grade_code VARCHAR(40) NOT NULL,
    scheme_name VARCHAR(120) NOT NULL,
    aggregation_mode VARCHAR(20) NOT NULL,
    rounding_mode VARCHAR(20) NOT NULL DEFAULT 'HALF_UP',
    decimal_places SMALLINT NOT NULL DEFAULT 2,
    pass_rule VARCHAR(30) NOT NULL DEFAULT 'NO_PASS_RULE',
    overall_pass_percentage NUMERIC(5,2),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    activated_by_membership_id UUID,
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT assessment_schemes_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT assessment_schemes_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT assessment_schemes_scale_fk
        FOREIGN KEY (grading_scale_id, organisation_id, school_id)
        REFERENCES assessment_grading_scales(id, organisation_id, school_id),
    CONSTRAINT assessment_schemes_activator_fk
        FOREIGN KEY (activated_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assessment_schemes_name_unique
        UNIQUE (school_id, academic_session_id, grade_code, scheme_name),
    CONSTRAINT assessment_schemes_aggregation_valid CHECK (aggregation_mode IN ('WEIGHTED', 'DESCRIPTIVE')),
    CONSTRAINT assessment_schemes_rounding_valid CHECK (rounding_mode IN ('HALF_UP', 'HALF_EVEN', 'DOWN')),
    CONSTRAINT assessment_schemes_decimal_valid CHECK (decimal_places BETWEEN 0 AND 2),
    CONSTRAINT assessment_schemes_pass_rule_valid CHECK (
        pass_rule IN ('NO_PASS_RULE', 'OVERALL', 'EACH_COMPONENT', 'BOTH')
    ),
    CONSTRAINT assessment_schemes_pass_percentage_valid CHECK (
        overall_pass_percentage IS NULL OR overall_pass_percentage BETWEEN 0 AND 100
    ),
    CONSTRAINT assessment_schemes_pass_configuration_valid CHECK (
        (pass_rule IN ('NO_PASS_RULE', 'EACH_COMPONENT') AND overall_pass_percentage IS NULL)
        OR (pass_rule IN ('OVERALL', 'BOTH') AND overall_pass_percentage IS NOT NULL)
    ),
    CONSTRAINT assessment_schemes_status_valid CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT assessment_schemes_activation_valid CHECK (
        (status = 'DRAFT' AND activated_by_membership_id IS NULL AND activated_at IS NULL)
        OR (status IN ('ACTIVE', 'RETIRED') AND activated_by_membership_id IS NOT NULL AND activated_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX assessment_schemes_one_active_grade_idx
    ON assessment_schemes (school_id, academic_session_id, grade_code)
    WHERE status = 'ACTIVE';

CREATE TABLE assessment_components (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    assessment_scheme_id UUID NOT NULL,
    component_code VARCHAR(30) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    scoring_mode VARCHAR(20) NOT NULL,
    weight_percentage NUMERIC(5,2) NOT NULL DEFAULT 0,
    default_max_marks NUMERIC(8,2),
    pass_percentage NUMERIC(5,2),
    display_order SMALLINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT assessment_components_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT assessment_components_scheme_fk
        FOREIGN KEY (assessment_scheme_id, organisation_id, school_id)
        REFERENCES assessment_schemes(id, organisation_id, school_id),
    CONSTRAINT assessment_components_code_unique UNIQUE (assessment_scheme_id, component_code),
    CONSTRAINT assessment_components_scoring_valid CHECK (scoring_mode IN ('MARKS', 'GRADE', 'NARRATIVE')),
    CONSTRAINT assessment_components_weight_valid CHECK (weight_percentage BETWEEN 0 AND 100),
    CONSTRAINT assessment_components_max_valid CHECK (
        (scoring_mode = 'MARKS' AND default_max_marks IS NOT NULL AND default_max_marks > 0 AND default_max_marks <= 10000)
        OR (scoring_mode <> 'MARKS' AND default_max_marks IS NULL AND weight_percentage = 0)
    ),
    CONSTRAINT assessment_components_pass_valid CHECK (
        pass_percentage IS NULL OR (scoring_mode = 'MARKS' AND pass_percentage BETWEEN 0 AND 100)
    ),
    CONSTRAINT assessment_components_order_valid CHECK (display_order BETWEEN 0 AND 1000),
    CONSTRAINT assessment_components_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE assessments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    academic_term_id UUID NOT NULL,
    class_section_id UUID NOT NULL,
    section_subject_offering_id UUID NOT NULL,
    assessment_scheme_id UUID NOT NULL,
    assessment_component_id UUID NOT NULL,
    assessment_name VARCHAR(160) NOT NULL,
    assessment_date DATE NOT NULL,
    scoring_mode VARCHAR(20) NOT NULL,
    maximum_marks NUMERIC(8,2),
    scheme_snapshot JSONB NOT NULL,
    component_snapshot JSONB NOT NULL,
    subject_snapshot JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    expected_learner_count INTEGER NOT NULL DEFAULT 0,
    created_by_membership_id UUID NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    validated_by_membership_id UUID,
    validated_at TIMESTAMPTZ,
    published_by_membership_id UUID,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT assessments_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT assessments_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT assessments_term_fk
        FOREIGN KEY (academic_term_id, organisation_id, school_id)
        REFERENCES academic_terms(id, organisation_id, school_id),
    CONSTRAINT assessments_section_fk
        FOREIGN KEY (class_section_id, organisation_id, school_id)
        REFERENCES class_sections(id, organisation_id, school_id),
    CONSTRAINT assessments_offering_fk
        FOREIGN KEY (section_subject_offering_id, organisation_id, school_id)
        REFERENCES section_subject_offerings(id, organisation_id, school_id),
    CONSTRAINT assessments_scheme_fk
        FOREIGN KEY (assessment_scheme_id, organisation_id, school_id)
        REFERENCES assessment_schemes(id, organisation_id, school_id),
    CONSTRAINT assessments_component_fk
        FOREIGN KEY (assessment_component_id, organisation_id, school_id)
        REFERENCES assessment_components(id, organisation_id, school_id),
    CONSTRAINT assessments_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assessments_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assessments_validator_fk
        FOREIGN KEY (validated_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assessments_publisher_fk
        FOREIGN KEY (published_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assessments_natural_key_unique
        UNIQUE (class_section_id, academic_term_id, section_subject_offering_id, assessment_component_id, assessment_name),
    CONSTRAINT assessments_scoring_valid CHECK (scoring_mode IN ('MARKS', 'GRADE', 'NARRATIVE')),
    CONSTRAINT assessments_max_valid CHECK (
        (scoring_mode = 'MARKS' AND maximum_marks IS NOT NULL AND maximum_marks > 0 AND maximum_marks <= 10000)
        OR (scoring_mode <> 'MARKS' AND maximum_marks IS NULL)
    ),
    CONSTRAINT assessments_snapshots_valid CHECK (
        jsonb_typeof(scheme_snapshot) = 'object'
        AND jsonb_typeof(component_snapshot) = 'object'
        AND jsonb_typeof(subject_snapshot) = 'object'
    ),
    CONSTRAINT assessments_status_valid CHECK (
        status IN ('DRAFT', 'OPEN', 'SUBMITTED', 'VALIDATED', 'PUBLISHED', 'ARCHIVED')
    ),
    CONSTRAINT assessments_count_valid CHECK (expected_learner_count BETWEEN 0 AND 500),
    CONSTRAINT assessments_lifecycle_valid CHECK (
        (status IN ('DRAFT', 'OPEN')
            AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND validated_by_membership_id IS NULL AND validated_at IS NULL
            AND published_by_membership_id IS NULL AND published_at IS NULL)
        OR (status = 'SUBMITTED'
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND validated_by_membership_id IS NULL AND validated_at IS NULL
            AND published_by_membership_id IS NULL AND published_at IS NULL)
        OR (status = 'VALIDATED'
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND validated_by_membership_id IS NOT NULL AND validated_at IS NOT NULL
            AND published_by_membership_id IS NULL AND published_at IS NULL)
        OR (status IN ('PUBLISHED', 'ARCHIVED')
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND validated_by_membership_id IS NOT NULL AND validated_at IS NOT NULL
            AND published_by_membership_id IS NOT NULL AND published_at IS NOT NULL)
    )
);

CREATE TABLE assessment_marks (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    assessment_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    enrolment_id UUID NOT NULL,
    evidence_state VARCHAR(20) NOT NULL DEFAULT 'MISSING',
    marks_awarded NUMERIC(8,2),
    grade_code VARCHAR(20),
    narrative VARCHAR(1000),
    teacher_remark VARCHAR(500),
    recorded_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT assessment_marks_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT assessment_marks_assessment_fk
        FOREIGN KEY (assessment_id, organisation_id, school_id)
        REFERENCES assessments(id, organisation_id, school_id),
    CONSTRAINT assessment_marks_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT assessment_marks_enrolment_fk
        FOREIGN KEY (enrolment_id, organisation_id, school_id)
        REFERENCES student_enrolments(id, organisation_id, school_id),
    CONSTRAINT assessment_marks_recorder_fk
        FOREIGN KEY (recorded_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assessment_marks_learner_unique UNIQUE (assessment_id, learner_id),
    CONSTRAINT assessment_marks_state_valid CHECK (evidence_state IN ('MISSING', 'RECORDED', 'ABSENT', 'EXEMPT')),
    CONSTRAINT assessment_marks_value_state_valid CHECK (
        (evidence_state = 'MISSING' AND marks_awarded IS NULL AND grade_code IS NULL AND narrative IS NULL)
        OR (evidence_state = 'RECORDED' AND num_nonnulls(marks_awarded, grade_code, narrative) >= 1)
        OR (evidence_state IN ('ABSENT', 'EXEMPT') AND marks_awarded IS NULL AND grade_code IS NULL)
    ),
    CONSTRAINT assessment_marks_nonnegative CHECK (marks_awarded IS NULL OR marks_awarded >= 0)
);

CREATE TABLE assessment_mark_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    assessment_mark_id UUID NOT NULL,
    previous_evidence JSONB,
    new_evidence JSONB NOT NULL,
    change_kind VARCHAR(20) NOT NULL,
    reason VARCHAR(240),
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT assessment_mark_history_mark_fk
        FOREIGN KEY (assessment_mark_id, organisation_id, school_id)
        REFERENCES assessment_marks(id, organisation_id, school_id),
    CONSTRAINT assessment_mark_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assessment_mark_history_evidence_valid CHECK (
        (previous_evidence IS NULL OR jsonb_typeof(previous_evidence) = 'object')
        AND jsonb_typeof(new_evidence) = 'object'
    ),
    CONSTRAINT assessment_mark_history_kind_valid CHECK (change_kind IN ('INITIAL', 'ENTRY', 'CORRECTION')),
    CONSTRAINT assessment_mark_history_reason_valid CHECK (
        (change_kind = 'INITIAL' AND reason IS NULL)
        OR (change_kind <> 'INITIAL' AND length(btrim(reason)) BETWEEN 1 AND 240)
    )
);

CREATE TABLE assessment_correction_requests (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    assessment_mark_id UUID NOT NULL,
    requested_evidence JSONB NOT NULL,
    request_reason VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by_membership_id UUID NOT NULL,
    decided_by_membership_id UUID,
    decision_reason VARCHAR(240),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT assessment_corrections_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT assessment_corrections_mark_fk
        FOREIGN KEY (assessment_mark_id, organisation_id, school_id)
        REFERENCES assessment_marks(id, organisation_id, school_id),
    CONSTRAINT assessment_corrections_requester_fk
        FOREIGN KEY (requested_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assessment_corrections_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assessment_corrections_evidence_valid CHECK (jsonb_typeof(requested_evidence) = 'object'),
    CONSTRAINT assessment_corrections_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT assessment_corrections_decision_valid CHECK (
        (status = 'PENDING' AND decided_by_membership_id IS NULL AND decision_reason IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by_membership_id IS NOT NULL
            AND length(btrim(decision_reason)) BETWEEN 1 AND 240 AND decided_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX assessment_corrections_one_pending_mark_idx
    ON assessment_correction_requests (assessment_mark_id) WHERE status = 'PENDING';

CREATE TABLE result_publications (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    academic_term_id UUID NOT NULL,
    class_section_id UUID NOT NULL,
    assessment_scheme_id UUID NOT NULL,
    publication_version INTEGER NOT NULL,
    publication_name VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    supersedes_publication_id UUID,
    published_by_membership_id UUID NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    superseded_at TIMESTAMPTZ,
    revoked_by_membership_id UUID,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT result_publications_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT result_publications_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT result_publications_term_fk
        FOREIGN KEY (academic_term_id, organisation_id, school_id)
        REFERENCES academic_terms(id, organisation_id, school_id),
    CONSTRAINT result_publications_section_fk
        FOREIGN KEY (class_section_id, organisation_id, school_id)
        REFERENCES class_sections(id, organisation_id, school_id),
    CONSTRAINT result_publications_scheme_fk
        FOREIGN KEY (assessment_scheme_id, organisation_id, school_id)
        REFERENCES assessment_schemes(id, organisation_id, school_id),
    CONSTRAINT result_publications_supersedes_fk
        FOREIGN KEY (supersedes_publication_id, organisation_id, school_id)
        REFERENCES result_publications(id, organisation_id, school_id),
    CONSTRAINT result_publications_publisher_fk
        FOREIGN KEY (published_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT result_publications_revoker_fk
        FOREIGN KEY (revoked_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT result_publications_version_unique
        UNIQUE (class_section_id, academic_term_id, publication_version),
    CONSTRAINT result_publications_version_valid CHECK (publication_version > 0),
    CONSTRAINT result_publications_status_valid CHECK (status IN ('PUBLISHED', 'SUPERSEDED', 'REVOKED')),
    CONSTRAINT result_publications_state_valid CHECK (
        (status = 'PUBLISHED' AND superseded_at IS NULL AND revoked_by_membership_id IS NULL
            AND revoked_at IS NULL AND revocation_reason IS NULL)
        OR (status = 'SUPERSEDED' AND superseded_at IS NOT NULL AND revoked_by_membership_id IS NULL
            AND revoked_at IS NULL AND revocation_reason IS NULL)
        OR (status = 'REVOKED' AND revoked_by_membership_id IS NOT NULL AND revoked_at IS NOT NULL
            AND length(btrim(revocation_reason)) BETWEEN 1 AND 240)
    )
);

CREATE UNIQUE INDEX result_publications_one_current_idx
    ON result_publications (class_section_id, academic_term_id) WHERE status = 'PUBLISHED';

CREATE TABLE learner_report_cards (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    result_publication_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    enrolment_id UUID NOT NULL,
    report_number VARCHAR(80) NOT NULL,
    learner_name_snapshot VARCHAR(120) NOT NULL,
    section_name_snapshot VARCHAR(100) NOT NULL,
    term_name_snapshot VARCHAR(100) NOT NULL,
    report_snapshot JSONB NOT NULL,
    overall_percentage NUMERIC(5,2),
    overall_grade_code VARCHAR(20),
    overall_outcome VARCHAR(30),
    snapshot_sha256 CHAR(64) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT learner_report_cards_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT learner_report_cards_publication_fk
        FOREIGN KEY (result_publication_id, organisation_id, school_id)
        REFERENCES result_publications(id, organisation_id, school_id),
    CONSTRAINT learner_report_cards_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT learner_report_cards_enrolment_fk
        FOREIGN KEY (enrolment_id, organisation_id, school_id)
        REFERENCES student_enrolments(id, organisation_id, school_id),
    CONSTRAINT learner_report_cards_learner_unique UNIQUE (result_publication_id, learner_id),
    CONSTRAINT learner_report_cards_number_unique UNIQUE (school_id, report_number),
    CONSTRAINT learner_report_cards_snapshot_valid CHECK (jsonb_typeof(report_snapshot) = 'object'),
    CONSTRAINT learner_report_cards_percentage_valid CHECK (
        overall_percentage IS NULL OR overall_percentage BETWEEN 0 AND 100
    ),
    CONSTRAINT learner_report_cards_hash_valid CHECK (snapshot_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE INDEX assessments_scope_status_idx
    ON assessments (organisation_id, school_id, academic_term_id, class_section_id, status);
CREATE INDEX assessment_marks_assessment_idx ON assessment_marks (assessment_id, learner_id);
CREATE INDEX assessment_marks_learner_idx ON assessment_marks (organisation_id, school_id, learner_id, assessment_id);
CREATE INDEX result_publications_scope_idx
    ON result_publications (organisation_id, school_id, academic_session_id, academic_term_id, class_section_id, status);
CREATE INDEX learner_report_cards_learner_idx
    ON learner_report_cards (organisation_id, school_id, learner_id, published_at DESC);

CREATE OR REPLACE FUNCTION guard_assessment_scheme_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'RETIRED' THEN RAISE EXCEPTION 'retired assessment schemes are immutable'; END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status <> 'RETIRED' THEN
        RAISE EXCEPTION 'active assessment schemes may only be retired';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'ACTIVE') THEN
        RAISE EXCEPTION 'invalid assessment scheme transition';
    END IF;
    IF OLD.status <> 'DRAFT' AND (
        OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
        OR OLD.academic_session_id <> NEW.academic_session_id OR OLD.grading_scale_id IS DISTINCT FROM NEW.grading_scale_id
        OR OLD.grade_code <> NEW.grade_code OR OLD.scheme_name <> NEW.scheme_name
        OR OLD.aggregation_mode <> NEW.aggregation_mode OR OLD.rounding_mode <> NEW.rounding_mode
        OR OLD.decimal_places <> NEW.decimal_places OR OLD.pass_rule <> NEW.pass_rule
        OR OLD.overall_pass_percentage IS DISTINCT FROM NEW.overall_pass_percentage
        OR OLD.activated_by_membership_id <> NEW.activated_by_membership_id OR OLD.activated_at <> NEW.activated_at
        OR OLD.created_at <> NEW.created_at
    ) THEN RAISE EXCEPTION 'activated assessment scheme evidence cannot be rewritten'; END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER assessment_schemes_guard_history
    BEFORE UPDATE ON assessment_schemes
    FOR EACH ROW EXECUTE FUNCTION guard_assessment_scheme_history();

CREATE OR REPLACE FUNCTION guard_assessment_definition_children()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_scheme_id UUID;
    parent_scale_id UUID;
    scheme_status VARCHAR(20);
BEGIN
    IF TG_TABLE_NAME = 'assessment_components' THEN
        parent_scheme_id := CASE WHEN TG_OP = 'DELETE'
            THEN OLD.assessment_scheme_id ELSE NEW.assessment_scheme_id END;
        SELECT status INTO scheme_status FROM assessment_schemes WHERE id = parent_scheme_id;
        IF scheme_status IS DISTINCT FROM 'DRAFT' THEN
            RAISE EXCEPTION 'components of an activated assessment scheme are immutable';
        END IF;
    ELSE
        parent_scale_id := CASE WHEN TG_OP = 'DELETE'
            THEN OLD.grading_scale_id ELSE NEW.grading_scale_id END;
        IF EXISTS (
            SELECT 1 FROM assessment_schemes
            WHERE grading_scale_id = parent_scale_id AND status <> 'DRAFT'
        ) THEN
            RAISE EXCEPTION 'grade bands used by an activated assessment scheme are immutable';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER assessment_components_guard_parent
    BEFORE INSERT OR UPDATE OR DELETE ON assessment_components
    FOR EACH ROW EXECUTE FUNCTION guard_assessment_definition_children();
CREATE TRIGGER assessment_grade_bands_guard_parent
    BEFORE INSERT OR UPDATE OR DELETE ON assessment_grade_bands
    FOR EACH ROW EXECUTE FUNCTION guard_assessment_definition_children();

CREATE OR REPLACE FUNCTION guard_assessment_lifecycle()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.academic_session_id <> NEW.academic_session_id OR OLD.academic_term_id <> NEW.academic_term_id
       OR OLD.class_section_id <> NEW.class_section_id OR OLD.section_subject_offering_id <> NEW.section_subject_offering_id
       OR OLD.assessment_scheme_id <> NEW.assessment_scheme_id OR OLD.assessment_component_id <> NEW.assessment_component_id
       OR OLD.assessment_name <> NEW.assessment_name OR OLD.assessment_date <> NEW.assessment_date
       OR OLD.scoring_mode <> NEW.scoring_mode OR OLD.maximum_marks IS DISTINCT FROM NEW.maximum_marks
       OR OLD.scheme_snapshot <> NEW.scheme_snapshot OR OLD.component_snapshot <> NEW.component_snapshot
       OR OLD.subject_snapshot <> NEW.subject_snapshot
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'assessment identity and snapshots are immutable';
    END IF;
    IF OLD.status <> 'DRAFT' AND OLD.expected_learner_count <> NEW.expected_learner_count THEN
        RAISE EXCEPTION 'the frozen assessment roster count is immutable';
    END IF;
    IF OLD.status = 'ARCHIVED' THEN RAISE EXCEPTION 'archived assessments are immutable'; END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'OPEN') THEN RAISE EXCEPTION 'invalid assessment transition'; END IF;
    IF OLD.status = 'OPEN' AND NEW.status NOT IN ('OPEN', 'SUBMITTED') THEN RAISE EXCEPTION 'invalid assessment transition'; END IF;
    IF OLD.status = 'SUBMITTED' AND NEW.status NOT IN ('SUBMITTED', 'VALIDATED') THEN RAISE EXCEPTION 'invalid assessment transition'; END IF;
    IF OLD.status = 'VALIDATED' AND NEW.status NOT IN ('VALIDATED', 'PUBLISHED') THEN RAISE EXCEPTION 'invalid assessment transition'; END IF;
    IF OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED', 'ARCHIVED') THEN RAISE EXCEPTION 'invalid assessment transition'; END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER assessments_guard_lifecycle
    BEFORE UPDATE ON assessments
    FOR EACH ROW EXECUTE FUNCTION guard_assessment_lifecycle();

CREATE OR REPLACE FUNCTION guard_assessment_mark_edit()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_assessment_id UUID;
    assessment_status VARCHAR(20);
BEGIN
    parent_assessment_id := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.assessment_id ELSE NEW.assessment_id END;
    SELECT status INTO assessment_status FROM assessments
    WHERE id = parent_assessment_id;
    IF assessment_status IS DISTINCT FROM 'OPEN'
       AND current_setting('app.assessment_correction', TRUE) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'marks outside an open assessment require an approved correction';
    END IF;
    IF TG_OP = 'UPDATE' AND (
        OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
        OR OLD.assessment_id <> NEW.assessment_id OR OLD.learner_id <> NEW.learner_id
        OR OLD.enrolment_id <> NEW.enrolment_id OR OLD.created_at <> NEW.created_at
    ) THEN
        RAISE EXCEPTION 'assessment mark identity is immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER assessment_marks_guard_edit
    BEFORE UPDATE OR DELETE ON assessment_marks
    FOR EACH ROW EXECUTE FUNCTION guard_assessment_mark_edit();

CREATE OR REPLACE FUNCTION prevent_assessment_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'published assessment evidence is append-only'; END; $$;

CREATE TRIGGER assessment_mark_history_prevent_update_or_delete
    BEFORE UPDATE OR DELETE ON assessment_mark_history
    FOR EACH ROW EXECUTE FUNCTION prevent_assessment_evidence_mutation();
CREATE TRIGGER learner_report_cards_prevent_update_or_delete
    BEFORE UPDATE OR DELETE ON learner_report_cards
    FOR EACH ROW EXECUTE FUNCTION prevent_assessment_evidence_mutation();

CREATE OR REPLACE FUNCTION guard_assessment_correction_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING' THEN RAISE EXCEPTION 'decided assessment corrections are immutable'; END IF;
    IF NEW.status NOT IN ('APPROVED', 'REJECTED') THEN RAISE EXCEPTION 'invalid assessment correction transition'; END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER assessment_corrections_guard_history
    BEFORE UPDATE ON assessment_correction_requests
    FOR EACH ROW EXECUTE FUNCTION guard_assessment_correction_history();

CREATE OR REPLACE FUNCTION guard_result_publication_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PUBLISHED' THEN RAISE EXCEPTION 'terminal result publications are immutable'; END IF;
    IF NEW.status NOT IN ('SUPERSEDED', 'REVOKED') THEN RAISE EXCEPTION 'invalid result publication transition'; END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.academic_session_id <> NEW.academic_session_id OR OLD.academic_term_id <> NEW.academic_term_id
       OR OLD.class_section_id <> NEW.class_section_id OR OLD.assessment_scheme_id <> NEW.assessment_scheme_id
       OR OLD.publication_version <> NEW.publication_version OR OLD.publication_name <> NEW.publication_name
       OR OLD.supersedes_publication_id IS DISTINCT FROM NEW.supersedes_publication_id
       OR OLD.published_by_membership_id <> NEW.published_by_membership_id
       OR OLD.published_at <> NEW.published_at OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'published result identity cannot be rewritten';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER result_publications_guard_history
    BEFORE UPDATE ON result_publications
    FOR EACH ROW EXECUTE FUNCTION guard_result_publication_history();

ALTER TABLE assessment_grading_scales ENABLE ROW LEVEL SECURITY;
ALTER TABLE assessment_grade_bands ENABLE ROW LEVEL SECURITY;
ALTER TABLE assessment_schemes ENABLE ROW LEVEL SECURITY;
ALTER TABLE assessment_components ENABLE ROW LEVEL SECURITY;
ALTER TABLE assessments ENABLE ROW LEVEL SECURITY;
ALTER TABLE assessment_marks ENABLE ROW LEVEL SECURITY;
ALTER TABLE assessment_mark_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE assessment_correction_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE result_publications ENABLE ROW LEVEL SECURITY;
ALTER TABLE learner_report_cards ENABLE ROW LEVEL SECURITY;

CREATE POLICY assessment_grading_scales_tenant_all ON assessment_grading_scales
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY assessment_grade_bands_tenant_all ON assessment_grade_bands
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY assessment_schemes_tenant_all ON assessment_schemes
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY assessment_components_tenant_all ON assessment_components
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY assessments_tenant_all ON assessments
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY assessment_marks_tenant_all ON assessment_marks
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY assessment_mark_history_tenant_all ON assessment_mark_history
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY assessment_corrections_tenant_all ON assessment_correction_requests
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY result_publications_tenant_all ON result_publications
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY learner_report_cards_tenant_all ON learner_report_cards
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE assessment_schemes IS
    'Authority-managed, grade-specific assessment policy. Weighting, rounding, grading, and pass rules are configuration rather than application constants.';
COMMENT ON COLUMN assessments.scheme_snapshot IS
    'Frozen scheme evidence used for this assessment so later policy changes cannot alter its historical meaning.';
COMMENT ON TABLE assessment_mark_history IS
    'Append-only mark evidence including initial roster rows, teacher entry, and approved post-submission corrections.';
COMMENT ON TABLE learner_report_cards IS
    'Immutable learner-specific result snapshot. Corrections produce a superseding publication and never rewrite an issued report card.';

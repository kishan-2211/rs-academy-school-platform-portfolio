INSERT INTO capabilities (code, description, risk_level) VALUES
    ('learning.read', 'Read assignments and permitted learner submission evidence', 'SENSITIVE'),
    ('learning.manage', 'Create, publish, revise, and review assignments for assigned teaching groups', 'SENSITIVE'),
    ('learning.moderate', 'Moderate assignment policy and any school assignment', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'learning.read'),
    ('00000000-0000-4000-8000-000000000001', 'learning.manage'),
    ('00000000-0000-4000-8000-000000000001', 'learning.moderate'),
    ('00000000-0000-4000-8000-000000000003', 'learning.read'),
    ('00000000-0000-4000-8000-000000000003', 'learning.manage')
ON CONFLICT DO NOTHING;

CREATE TABLE assignment_categories (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    category_code VARCHAR(30) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    accent_color CHAR(7),
    display_order SMALLINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT assignment_categories_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT assignment_categories_id_session_scope_unique
        UNIQUE (id, academic_session_id, organisation_id, school_id),
    CONSTRAINT assignment_categories_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT assignment_categories_code_unique
        UNIQUE (school_id, academic_session_id, category_code),
    CONSTRAINT assignment_categories_code_valid CHECK (category_code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT assignment_categories_color_valid CHECK (
        accent_color IS NULL OR accent_color ~ '^#[A-Fa-f0-9]{6}$'
    ),
    CONSTRAINT assignment_categories_order_valid CHECK (display_order BETWEEN 0 AND 1000),
    CONSTRAINT assignment_categories_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE assignments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    academic_term_id UUID NOT NULL,
    class_section_id UUID NOT NULL,
    section_subject_offering_id UUID NOT NULL,
    assignment_category_id UUID,
    title VARCHAR(180) NOT NULL,
    instructions VARCHAR(6000) NOT NULL,
    resource_label VARCHAR(120),
    resource_url VARCHAR(1000),
    available_from TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    submission_mode VARCHAR(30) NOT NULL,
    maximum_points NUMERIC(8,2),
    allow_late BOOLEAN NOT NULL DEFAULT FALSE,
    late_until TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    publication_revision INTEGER NOT NULL DEFAULT 0,
    expected_learner_count INTEGER NOT NULL DEFAULT 0,
    created_by_membership_id UUID NOT NULL,
    published_by_membership_id UUID,
    published_at TIMESTAMPTZ,
    closed_by_membership_id UUID,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT assignments_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT assignments_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT assignments_term_fk
        FOREIGN KEY (academic_term_id, organisation_id, school_id)
        REFERENCES academic_terms(id, organisation_id, school_id),
    CONSTRAINT assignments_section_fk
        FOREIGN KEY (class_section_id, organisation_id, school_id)
        REFERENCES class_sections(id, organisation_id, school_id),
    CONSTRAINT assignments_offering_fk
        FOREIGN KEY (section_subject_offering_id, organisation_id, school_id)
        REFERENCES section_subject_offerings(id, organisation_id, school_id),
    CONSTRAINT assignments_category_fk
        FOREIGN KEY (assignment_category_id, academic_session_id, organisation_id, school_id)
        REFERENCES assignment_categories(id, academic_session_id, organisation_id, school_id),
    CONSTRAINT assignments_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assignments_publisher_fk
        FOREIGN KEY (published_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assignments_closer_fk
        FOREIGN KEY (closed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assignments_dates_valid CHECK (due_at > available_from),
    CONSTRAINT assignments_resource_valid CHECK (
        (resource_label IS NULL AND resource_url IS NULL)
        OR (length(btrim(resource_label)) BETWEEN 1 AND 120
            AND length(btrim(resource_url)) BETWEEN 1 AND 1000)
    ),
    CONSTRAINT assignments_submission_mode_valid CHECK (
        submission_mode IN ('TEXT', 'LINK', 'TEXT_OR_LINK', 'OFFLINE')
    ),
    CONSTRAINT assignments_points_valid CHECK (
        maximum_points IS NULL OR (maximum_points > 0 AND maximum_points <= 10000)
    ),
    CONSTRAINT assignments_late_valid CHECK (
        (allow_late = FALSE AND late_until IS NULL)
        OR (allow_late = TRUE AND (late_until IS NULL OR late_until > due_at))
    ),
    CONSTRAINT assignments_status_valid CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    CONSTRAINT assignments_revision_valid CHECK (publication_revision >= 0),
    CONSTRAINT assignments_count_valid CHECK (expected_learner_count BETWEEN 0 AND 500),
    CONSTRAINT assignments_lifecycle_valid CHECK (
        (status = 'DRAFT' AND publication_revision = 0 AND expected_learner_count = 0
            AND published_by_membership_id IS NULL AND published_at IS NULL
            AND closed_by_membership_id IS NULL AND closed_at IS NULL)
        OR (status = 'PUBLISHED' AND publication_revision > 0 AND expected_learner_count > 0
            AND published_by_membership_id IS NOT NULL AND published_at IS NOT NULL
            AND closed_by_membership_id IS NULL AND closed_at IS NULL)
        OR (status = 'CLOSED' AND publication_revision > 0 AND expected_learner_count > 0
            AND published_by_membership_id IS NOT NULL AND published_at IS NOT NULL
            AND closed_by_membership_id IS NOT NULL AND closed_at IS NOT NULL)
    )
);

CREATE TABLE assignment_revision_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    publication_revision INTEGER NOT NULL,
    assignment_snapshot JSONB NOT NULL,
    change_kind VARCHAR(20) NOT NULL,
    reason VARCHAR(240) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT assignment_revision_history_assignment_fk
        FOREIGN KEY (assignment_id, organisation_id, school_id)
        REFERENCES assignments(id, organisation_id, school_id),
    CONSTRAINT assignment_revision_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT assignment_revision_history_unique UNIQUE (assignment_id, publication_revision),
    CONSTRAINT assignment_revision_history_revision_valid CHECK (publication_revision > 0),
    CONSTRAINT assignment_revision_history_snapshot_valid CHECK (jsonb_typeof(assignment_snapshot) = 'object'),
    CONSTRAINT assignment_revision_history_kind_valid CHECK (change_kind IN ('PUBLISHED', 'REVISED')),
    CONSTRAINT assignment_revision_history_reason_valid CHECK (length(btrim(reason)) BETWEEN 1 AND 240)
);

CREATE TABLE assignment_recipients (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    enrolment_id UUID NOT NULL,
    learner_name_snapshot VARCHAR(120) NOT NULL,
    roll_number_snapshot VARCHAR(40),
    assigned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT assignment_recipients_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT assignment_recipients_assignment_fk
        FOREIGN KEY (assignment_id, organisation_id, school_id)
        REFERENCES assignments(id, organisation_id, school_id),
    CONSTRAINT assignment_recipients_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT assignment_recipients_enrolment_fk
        FOREIGN KEY (enrolment_id, organisation_id, school_id)
        REFERENCES student_enrolments(id, organisation_id, school_id),
    CONSTRAINT assignment_recipients_learner_unique UNIQUE (assignment_id, learner_id)
);

CREATE TABLE learner_assignment_submissions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    assignment_recipient_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    response_text VARCHAR(8000),
    response_link VARCHAR(1000),
    submitted_by_user_id UUID,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    teacher_feedback VARCHAR(3000),
    points_awarded NUMERIC(8,2),
    reviewed_by_membership_id UUID,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT learner_assignment_submissions_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT learner_assignment_submissions_assignment_fk
        FOREIGN KEY (assignment_id, organisation_id, school_id)
        REFERENCES assignments(id, organisation_id, school_id),
    CONSTRAINT learner_assignment_submissions_recipient_fk
        FOREIGN KEY (assignment_recipient_id, organisation_id, school_id)
        REFERENCES assignment_recipients(id, organisation_id, school_id),
    CONSTRAINT learner_assignment_submissions_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT learner_assignment_submissions_submitter_user_fk
        FOREIGN KEY (submitted_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT learner_assignment_submissions_submitter_membership_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT learner_assignment_submissions_reviewer_fk
        FOREIGN KEY (reviewed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT learner_assignment_submissions_assignment_unique UNIQUE (assignment_id, learner_id),
    CONSTRAINT learner_assignment_submissions_recipient_unique UNIQUE (assignment_recipient_id),
    CONSTRAINT learner_assignment_submissions_state_valid CHECK (
        state IN ('NOT_STARTED', 'DRAFT', 'SUBMITTED', 'LATE', 'RETURNED', 'ACCEPTED', 'EXEMPT')
    ),
    CONSTRAINT learner_assignment_submissions_points_valid CHECK (
        points_awarded IS NULL OR (points_awarded >= 0 AND points_awarded <= 10000)
    ),
    CONSTRAINT learner_assignment_submissions_evidence_valid CHECK (
        (state = 'NOT_STARTED' AND response_text IS NULL AND response_link IS NULL
            AND submitted_by_user_id IS NULL AND submitted_by_membership_id IS NULL
            AND submitted_at IS NULL AND teacher_feedback IS NULL AND points_awarded IS NULL
            AND reviewed_by_membership_id IS NULL AND reviewed_at IS NULL)
        OR (state = 'DRAFT' AND num_nonnulls(response_text, response_link) >= 1
            AND submitted_at IS NULL AND teacher_feedback IS NULL AND points_awarded IS NULL
            AND reviewed_by_membership_id IS NULL AND reviewed_at IS NULL)
        OR (state IN ('SUBMITTED', 'LATE') AND num_nonnulls(response_text, response_link) >= 1
            AND submitted_by_user_id IS NOT NULL AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND teacher_feedback IS NULL AND points_awarded IS NULL
            AND reviewed_by_membership_id IS NULL AND reviewed_at IS NULL)
        OR (state = 'RETURNED' AND num_nonnulls(response_text, response_link) >= 1
            AND submitted_by_user_id IS NOT NULL AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND length(btrim(teacher_feedback)) BETWEEN 1 AND 3000
            AND reviewed_by_membership_id IS NOT NULL AND reviewed_at IS NOT NULL)
        OR (state = 'ACCEPTED' AND submitted_by_user_id IS NOT NULL
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND reviewed_by_membership_id IS NOT NULL AND reviewed_at IS NOT NULL)
        OR (state = 'EXEMPT' AND response_text IS NULL AND response_link IS NULL
            AND submitted_by_user_id IS NULL AND submitted_at IS NULL
            AND reviewed_by_membership_id IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

CREATE TABLE learner_submission_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_assignment_submission_id UUID NOT NULL,
    previous_evidence JSONB,
    new_evidence JSONB NOT NULL,
    change_kind VARCHAR(30) NOT NULL,
    reason VARCHAR(240),
    actor_user_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT learner_submission_history_submission_fk
        FOREIGN KEY (learner_assignment_submission_id, organisation_id, school_id)
        REFERENCES learner_assignment_submissions(id, organisation_id, school_id),
    CONSTRAINT learner_submission_history_user_fk
        FOREIGN KEY (actor_user_id) REFERENCES user_accounts(id),
    CONSTRAINT learner_submission_history_membership_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT learner_submission_history_evidence_valid CHECK (
        (previous_evidence IS NULL OR jsonb_typeof(previous_evidence) = 'object')
        AND jsonb_typeof(new_evidence) = 'object'
    ),
    CONSTRAINT learner_submission_history_kind_valid CHECK (
        change_kind IN ('INITIAL', 'DRAFT_SAVE', 'SUBMIT', 'RESUBMIT', 'STAFF_RECORD', 'REVIEW')
    ),
    CONSTRAINT learner_submission_history_reason_valid CHECK (
        (change_kind IN ('INITIAL', 'DRAFT_SAVE', 'SUBMIT', 'RESUBMIT') AND reason IS NULL)
        OR (change_kind IN ('STAFF_RECORD', 'REVIEW') AND length(btrim(reason)) BETWEEN 1 AND 240)
    )
);

CREATE INDEX assignments_scope_due_idx
    ON assignments (organisation_id, school_id, academic_term_id, class_section_id, status, due_at);
CREATE INDEX assignment_recipients_learner_idx
    ON assignment_recipients (organisation_id, school_id, learner_id, assignment_id);
CREATE INDEX learner_assignment_submissions_state_idx
    ON learner_assignment_submissions (organisation_id, school_id, assignment_id, state);
CREATE INDEX learner_submission_history_submission_idx
    ON learner_submission_history (learner_assignment_submission_id, occurred_at, id);

CREATE OR REPLACE FUNCTION guard_assignment_category_identity()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.academic_session_id <> NEW.academic_session_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'assignment category identity and session are immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER assignment_categories_guard_identity
    BEFORE UPDATE ON assignment_categories
    FOR EACH ROW EXECUTE FUNCTION guard_assignment_category_identity();

CREATE OR REPLACE FUNCTION guard_assignment_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE content_changed BOOLEAN;
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.academic_session_id <> NEW.academic_session_id OR OLD.academic_term_id <> NEW.academic_term_id
       OR OLD.class_section_id <> NEW.class_section_id
       OR OLD.section_subject_offering_id <> NEW.section_subject_offering_id
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'assignment identity is immutable';
    END IF;
    content_changed := OLD.assignment_category_id IS DISTINCT FROM NEW.assignment_category_id
        OR OLD.title <> NEW.title OR OLD.instructions <> NEW.instructions
        OR OLD.resource_label IS DISTINCT FROM NEW.resource_label
        OR OLD.resource_url IS DISTINCT FROM NEW.resource_url
        OR OLD.available_from <> NEW.available_from OR OLD.due_at <> NEW.due_at
        OR OLD.submission_mode <> NEW.submission_mode
        OR OLD.maximum_points IS DISTINCT FROM NEW.maximum_points
        OR OLD.allow_late <> NEW.allow_late OR OLD.late_until IS DISTINCT FROM NEW.late_until;
    IF OLD.status = 'CLOSED' THEN RAISE EXCEPTION 'closed assignments are immutable'; END IF;
    IF OLD.status <> 'DRAFT' AND (
        OLD.published_by_membership_id <> NEW.published_by_membership_id
        OR OLD.published_at <> NEW.published_at
    ) THEN
        RAISE EXCEPTION 'assignment publication identity is immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'PUBLISHED') THEN
        RAISE EXCEPTION 'invalid assignment transition';
    END IF;
    IF OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED', 'CLOSED') THEN
        RAISE EXCEPTION 'invalid assignment transition';
    END IF;
    IF OLD.status <> 'DRAFT' AND content_changed THEN
        IF current_setting('app.assignment_revision', TRUE) IS DISTINCT FROM 'approved'
           OR NEW.publication_revision <> OLD.publication_revision + 1 THEN
            RAISE EXCEPTION 'published assignment changes require a recorded revision';
        END IF;
    ELSIF OLD.status <> 'DRAFT' AND NEW.publication_revision <> OLD.publication_revision THEN
        RAISE EXCEPTION 'assignment revision changed without content changes';
    END IF;
    IF OLD.status <> 'DRAFT' AND OLD.expected_learner_count <> NEW.expected_learner_count THEN
        RAISE EXCEPTION 'the published assignment audience is immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER assignments_guard_history
    BEFORE UPDATE ON assignments
    FOR EACH ROW EXECUTE FUNCTION guard_assignment_history();

CREATE OR REPLACE FUNCTION prevent_learning_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'learning history and assignment audience are append-only'; END; $$;

CREATE TRIGGER assignment_revision_history_prevent_update_or_delete
    BEFORE UPDATE OR DELETE ON assignment_revision_history
    FOR EACH ROW EXECUTE FUNCTION prevent_learning_evidence_mutation();
CREATE TRIGGER assignment_recipients_prevent_update_or_delete
    BEFORE UPDATE OR DELETE ON assignment_recipients
    FOR EACH ROW EXECUTE FUNCTION prevent_learning_evidence_mutation();
CREATE TRIGGER learner_submission_history_prevent_update_or_delete
    BEFORE UPDATE OR DELETE ON learner_submission_history
    FOR EACH ROW EXECUTE FUNCTION prevent_learning_evidence_mutation();

CREATE OR REPLACE FUNCTION guard_assignment_recipient_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE assignment_status VARCHAR(20);
BEGIN
    SELECT status INTO assignment_status FROM assignments WHERE id = NEW.assignment_id;
    IF assignment_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'assignment recipients can only be frozen during initial publication';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER assignment_recipients_guard_insert
    BEFORE INSERT ON assignment_recipients
    FOR EACH ROW EXECUTE FUNCTION guard_assignment_recipient_insert();

CREATE OR REPLACE FUNCTION guard_learner_submission_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    assignment_status VARCHAR(20);
    recipient_matches BOOLEAN;
BEGIN
    SELECT status INTO assignment_status FROM assignments WHERE id = NEW.assignment_id;
    SELECT EXISTS (
        SELECT 1 FROM assignment_recipients recipient
        WHERE recipient.id = NEW.assignment_recipient_id
          AND recipient.assignment_id = NEW.assignment_id
          AND recipient.learner_id = NEW.learner_id
          AND recipient.organisation_id = NEW.organisation_id
          AND recipient.school_id = NEW.school_id
    ) INTO recipient_matches;
    IF assignment_status IS DISTINCT FROM 'DRAFT' OR recipient_matches IS NOT TRUE THEN
        RAISE EXCEPTION 'learner submission must match the frozen assignment recipient';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER learner_assignment_submissions_guard_insert
    BEFORE INSERT ON learner_assignment_submissions
    FOR EACH ROW EXECUTE FUNCTION guard_learner_submission_insert();

CREATE OR REPLACE FUNCTION guard_learner_submission_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.assignment_id <> NEW.assignment_id
       OR OLD.assignment_recipient_id <> NEW.assignment_recipient_id
       OR OLD.learner_id <> NEW.learner_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'learner submission identity is immutable';
    END IF;
    IF OLD.state IN ('ACCEPTED', 'EXEMPT') THEN
        RAISE EXCEPTION 'accepted or exempt submission evidence is immutable';
    END IF;
    IF OLD.state = 'NOT_STARTED' AND NEW.state NOT IN ('NOT_STARTED', 'DRAFT', 'SUBMITTED', 'LATE', 'ACCEPTED', 'EXEMPT') THEN
        RAISE EXCEPTION 'invalid learner submission transition';
    END IF;
    IF OLD.state = 'DRAFT' AND NEW.state NOT IN ('DRAFT', 'SUBMITTED', 'LATE') THEN
        RAISE EXCEPTION 'invalid learner submission transition';
    END IF;
    IF OLD.state IN ('SUBMITTED', 'LATE') AND NEW.state NOT IN ('RETURNED', 'ACCEPTED', 'EXEMPT') THEN
        RAISE EXCEPTION 'invalid learner submission transition';
    END IF;
    IF OLD.state = 'RETURNED' AND NEW.state NOT IN ('DRAFT', 'SUBMITTED', 'LATE') THEN
        RAISE EXCEPTION 'invalid learner submission transition';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER learner_assignment_submissions_guard_history
    BEFORE UPDATE ON learner_assignment_submissions
    FOR EACH ROW EXECUTE FUNCTION guard_learner_submission_history();

ALTER TABLE assignment_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE assignment_revision_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE assignment_recipients ENABLE ROW LEVEL SECURITY;
ALTER TABLE learner_assignment_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE learner_submission_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY assignment_categories_tenant_all ON assignment_categories
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY assignments_tenant_all ON assignments
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY assignment_revision_history_tenant_all ON assignment_revision_history
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY assignment_recipients_tenant_all ON assignment_recipients
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY learner_assignment_submissions_tenant_all ON learner_assignment_submissions
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY learner_submission_history_tenant_all ON learner_submission_history
    USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE assignments IS
    'Teacher-assigned learning work. Timing, late rules, scoring, category, and response mode are per-record configuration rather than application constants.';
COMMENT ON TABLE assignment_revision_history IS
    'Append-only snapshots of every published assignment revision so families can understand material changes.';
COMMENT ON TABLE assignment_recipients IS
    'Frozen learner audience captured when an assignment is first published; later enrolment changes do not rewrite history.';
COMMENT ON TABLE learner_assignment_submissions IS
    'Current learner response and teacher review state. Every change is also written to append-only learner_submission_history.';

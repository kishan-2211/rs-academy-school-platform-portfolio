ALTER TABLE assignments
    ADD COLUMN allow_attachments BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN max_attachment_count SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN attachment_required BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000003', 'files.manage')
ON CONFLICT DO NOTHING;

ALTER TABLE assignments DROP CONSTRAINT assignments_submission_mode_valid;
ALTER TABLE assignments ADD CONSTRAINT assignments_submission_mode_valid CHECK (
    submission_mode IN ('TEXT', 'LINK', 'TEXT_OR_LINK', 'FILE', 'FLEXIBLE', 'OFFLINE')
);
ALTER TABLE assignments ADD CONSTRAINT assignments_attachment_policy_valid CHECK (
    (allow_attachments = FALSE AND max_attachment_count = 0 AND attachment_required = FALSE)
    OR (allow_attachments = TRUE AND max_attachment_count BETWEEN 1 AND 10)
);
ALTER TABLE assignments ADD CONSTRAINT assignments_file_mode_valid CHECK (
    (submission_mode NOT IN ('FILE', 'FLEXIBLE') OR allow_attachments = TRUE)
    AND (submission_mode <> 'OFFLINE' OR allow_attachments = FALSE)
);

ALTER TABLE learner_assignment_submissions
    ADD COLUMN has_attachment_evidence BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE learner_assignment_submissions
    DROP CONSTRAINT learner_assignment_submissions_evidence_valid;
ALTER TABLE learner_assignment_submissions
    ADD CONSTRAINT learner_assignment_submissions_evidence_valid CHECK (
        (state = 'NOT_STARTED' AND response_text IS NULL AND response_link IS NULL
            AND has_attachment_evidence = FALSE
            AND submitted_by_user_id IS NULL AND submitted_by_membership_id IS NULL
            AND submitted_at IS NULL AND teacher_feedback IS NULL AND points_awarded IS NULL
            AND reviewed_by_membership_id IS NULL AND reviewed_at IS NULL)
        OR (state = 'DRAFT' AND (num_nonnulls(response_text, response_link) >= 1 OR has_attachment_evidence)
            AND submitted_at IS NULL AND teacher_feedback IS NULL AND points_awarded IS NULL
            AND reviewed_by_membership_id IS NULL AND reviewed_at IS NULL)
        OR (state IN ('SUBMITTED', 'LATE')
            AND (num_nonnulls(response_text, response_link) >= 1 OR has_attachment_evidence)
            AND submitted_by_user_id IS NOT NULL AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND teacher_feedback IS NULL AND points_awarded IS NULL
            AND reviewed_by_membership_id IS NULL AND reviewed_at IS NULL)
        OR (state = 'RETURNED'
            AND (num_nonnulls(response_text, response_link) >= 1 OR has_attachment_evidence)
            AND submitted_by_user_id IS NOT NULL AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND length(btrim(teacher_feedback)) BETWEEN 1 AND 3000
            AND reviewed_by_membership_id IS NOT NULL AND reviewed_at IS NOT NULL)
        OR (state = 'ACCEPTED' AND submitted_by_user_id IS NOT NULL
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND reviewed_by_membership_id IS NOT NULL AND reviewed_at IS NOT NULL)
        OR (state = 'EXEMPT' AND response_text IS NULL AND response_link IS NULL
            AND submitted_by_user_id IS NULL AND submitted_at IS NULL
            AND reviewed_by_membership_id IS NOT NULL AND reviewed_at IS NOT NULL)
    );

ALTER TABLE learner_submission_history
    DROP CONSTRAINT learner_submission_history_kind_valid;
ALTER TABLE learner_submission_history
    ADD CONSTRAINT learner_submission_history_kind_valid CHECK (
        change_kind IN ('INITIAL', 'DRAFT_SAVE', 'SUBMIT', 'RESUBMIT',
                        'STAFF_RECORD', 'REVIEW', 'ATTACHMENT_CHANGE')
    );
ALTER TABLE learner_submission_history
    DROP CONSTRAINT learner_submission_history_reason_valid;
ALTER TABLE learner_submission_history
    ADD CONSTRAINT learner_submission_history_reason_valid CHECK (
        (change_kind IN ('INITIAL', 'DRAFT_SAVE', 'SUBMIT', 'RESUBMIT') AND reason IS NULL)
        OR (change_kind IN ('STAFF_RECORD', 'REVIEW', 'ATTACHMENT_CHANGE')
            AND length(btrim(reason)) BETWEEN 1 AND 240)
    );

CREATE TABLE private_files (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    assignment_id UUID,
    learner_assignment_submission_id UUID,
    purpose VARCHAR(40) NOT NULL,
    access_scope VARCHAR(30) NOT NULL,
    original_filename VARCHAR(240) NOT NULL,
    download_filename VARCHAR(180) NOT NULL,
    detected_media_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256_hex CHAR(64) NOT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    scan_engine VARCHAR(60) NOT NULL,
    scan_engine_version VARCHAR(180) NOT NULL,
    scan_status VARCHAR(20) NOT NULL,
    lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    replaces_file_id UUID,
    created_by_user_id UUID NOT NULL,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    expired_by_membership_id UUID,
    expired_at TIMESTAMPTZ,
    expiry_reason VARCHAR(240),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT private_files_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT private_files_storage_key_unique UNIQUE (storage_bucket, storage_key),
    CONSTRAINT private_files_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT private_files_assignment_fk
        FOREIGN KEY (assignment_id, organisation_id, school_id)
        REFERENCES assignments(id, organisation_id, school_id),
    CONSTRAINT private_files_submission_fk
        FOREIGN KEY (learner_assignment_submission_id, organisation_id, school_id)
        REFERENCES learner_assignment_submissions(id, organisation_id, school_id),
    CONSTRAINT private_files_replacement_fk
        FOREIGN KEY (replaces_file_id, organisation_id, school_id)
        REFERENCES private_files(id, organisation_id, school_id),
    CONSTRAINT private_files_creator_user_fk
        FOREIGN KEY (created_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT private_files_creator_membership_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT private_files_expirer_membership_fk
        FOREIGN KEY (expired_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT private_files_context_valid CHECK (
        (purpose = 'ASSIGNMENT_RESOURCE' AND assignment_id IS NOT NULL
            AND learner_assignment_submission_id IS NULL)
        OR (purpose = 'SUBMISSION_EVIDENCE' AND assignment_id IS NULL
            AND learner_assignment_submission_id IS NOT NULL)
    ),
    CONSTRAINT private_files_access_valid CHECK (access_scope IN ('CONTEXT_FAMILY', 'STAFF_ONLY')),
    CONSTRAINT private_files_filename_valid CHECK (
        length(btrim(original_filename)) BETWEEN 1 AND 240
        AND length(btrim(download_filename)) BETWEEN 1 AND 180
    ),
    CONSTRAINT private_files_size_valid CHECK (size_bytes BETWEEN 1 AND 52428800),
    CONSTRAINT private_files_sha_valid CHECK (sha256_hex ~ '^[a-f0-9]{64}$'),
    CONSTRAINT private_files_scan_valid CHECK (scan_status = 'CLEAN'),
    CONSTRAINT private_files_status_valid CHECK (lifecycle_status IN ('AVAILABLE', 'EXPIRED')),
    CONSTRAINT private_files_lifecycle_valid CHECK (
        (lifecycle_status = 'AVAILABLE' AND expired_by_membership_id IS NULL
            AND expired_at IS NULL AND expiry_reason IS NULL)
        OR (lifecycle_status = 'EXPIRED' AND expired_by_membership_id IS NOT NULL
            AND expired_at IS NOT NULL AND length(btrim(expiry_reason)) BETWEEN 1 AND 240)
    ),
    CONSTRAINT private_files_timestamps_valid CHECK (available_at >= created_at)
);

CREATE TABLE private_file_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    private_file_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    reason VARCHAR(240) NOT NULL,
    actor_user_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT private_file_history_file_fk
        FOREIGN KEY (private_file_id, organisation_id, school_id)
        REFERENCES private_files(id, organisation_id, school_id),
    CONSTRAINT private_file_history_user_fk
        FOREIGN KEY (actor_user_id) REFERENCES user_accounts(id),
    CONSTRAINT private_file_history_membership_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT private_file_history_event_valid CHECK (event_type IN ('UPLOADED', 'REPLACED', 'EXPIRED')),
    CONSTRAINT private_file_history_status_valid CHECK (
        (event_type IN ('UPLOADED', 'REPLACED') AND previous_status IS NULL AND new_status = 'AVAILABLE')
        OR (event_type = 'EXPIRED' AND previous_status = 'AVAILABLE' AND new_status = 'EXPIRED')
    ),
    CONSTRAINT private_file_history_reason_valid CHECK (length(btrim(reason)) BETWEEN 1 AND 240)
);

CREATE INDEX private_files_assignment_idx
    ON private_files (organisation_id, school_id, assignment_id, lifecycle_status, created_at, id)
    WHERE assignment_id IS NOT NULL;
CREATE INDEX private_files_submission_idx
    ON private_files (organisation_id, school_id, learner_assignment_submission_id, lifecycle_status, created_at, id)
    WHERE learner_assignment_submission_id IS NOT NULL;
CREATE UNIQUE INDEX private_files_replaces_unique_idx
    ON private_files (replaces_file_id) WHERE replaces_file_id IS NOT NULL;
CREATE INDEX private_file_history_file_idx ON private_file_history (private_file_id, occurred_at, id);

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
        OR OLD.allow_late <> NEW.allow_late OR OLD.late_until IS DISTINCT FROM NEW.late_until
        OR OLD.allow_attachments <> NEW.allow_attachments
        OR OLD.max_attachment_count <> NEW.max_attachment_count
        OR OLD.attachment_required <> NEW.attachment_required;
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
    IF OLD.status <> 'DRAFT' AND EXISTS (
        SELECT 1
        FROM learner_assignment_submissions submission
        JOIN private_files file ON file.learner_assignment_submission_id = submission.id
            AND file.lifecycle_status = 'AVAILABLE'
        WHERE submission.assignment_id = OLD.id
        GROUP BY submission.id
        HAVING count(*) > CASE WHEN NEW.allow_attachments THEN NEW.max_attachment_count ELSE 0 END
    ) THEN
        RAISE EXCEPTION 'published attachment policy cannot invalidate existing learner evidence';
    END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION guard_private_file_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    assignment_status VARCHAR(20);
    submission_state VARCHAR(20);
    attachments_allowed BOOLEAN;
    attachment_limit SMALLINT;
    available_from TIMESTAMPTZ;
    due_at TIMESTAMPTZ;
    allow_late BOOLEAN;
    late_until TIMESTAMPTZ;
    available_count INTEGER;
    replacement_matches BOOLEAN;
BEGIN
    IF NEW.replaces_file_id IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM private_files old_file
            WHERE old_file.id = NEW.replaces_file_id
              AND old_file.organisation_id = NEW.organisation_id
              AND old_file.school_id = NEW.school_id
              AND old_file.lifecycle_status = 'AVAILABLE'
              AND old_file.purpose = NEW.purpose
              AND old_file.assignment_id IS NOT DISTINCT FROM NEW.assignment_id
              AND old_file.learner_assignment_submission_id IS NOT DISTINCT FROM NEW.learner_assignment_submission_id
        ) INTO replacement_matches;
        IF replacement_matches IS NOT TRUE THEN
            RAISE EXCEPTION 'replacement file must be an available file in the same context';
        END IF;
    END IF;

    IF NEW.purpose = 'ASSIGNMENT_RESOURCE' THEN
        SELECT status INTO assignment_status FROM assignments WHERE id = NEW.assignment_id;
        IF assignment_status IS DISTINCT FROM 'DRAFT' THEN
            RAISE EXCEPTION 'assignment resources can only change while the assignment is a draft';
        END IF;
        SELECT count(*) INTO available_count FROM private_files
        WHERE assignment_id = NEW.assignment_id AND lifecycle_status = 'AVAILABLE';
        IF available_count >= 10 AND NEW.replaces_file_id IS NULL THEN
            RAISE EXCEPTION 'assignment resource limit reached';
        END IF;
    ELSE
        SELECT submission.state, assignment.status, assignment.allow_attachments,
               assignment.max_attachment_count, assignment.available_from,
               assignment.due_at, assignment.allow_late, assignment.late_until
          INTO submission_state, assignment_status, attachments_allowed, attachment_limit,
               available_from, due_at, allow_late, late_until
        FROM learner_assignment_submissions submission
        JOIN assignments assignment ON assignment.id = submission.assignment_id
        WHERE submission.id = NEW.learner_assignment_submission_id;
        IF assignment_status IS DISTINCT FROM 'PUBLISHED'
           OR submission_state NOT IN ('NOT_STARTED', 'DRAFT', 'RETURNED')
           OR attachments_allowed IS NOT TRUE THEN
            RAISE EXCEPTION 'submission attachments are not editable in this assignment state';
        END IF;
        IF CURRENT_TIMESTAMP < available_from
           OR (CURRENT_TIMESTAMP > due_at AND allow_late IS NOT TRUE)
           OR (CURRENT_TIMESTAMP > due_at AND late_until IS NOT NULL AND CURRENT_TIMESTAMP > late_until) THEN
            RAISE EXCEPTION 'submission attachment window is closed';
        END IF;
        SELECT count(*) INTO available_count FROM private_files
        WHERE learner_assignment_submission_id = NEW.learner_assignment_submission_id
          AND lifecycle_status = 'AVAILABLE';
        IF available_count >= attachment_limit AND NEW.replaces_file_id IS NULL THEN
            RAISE EXCEPTION 'submission attachment limit reached';
        END IF;
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER private_files_guard_insert
    BEFORE INSERT ON private_files FOR EACH ROW EXECUTE FUNCTION guard_private_file_insert();

CREATE OR REPLACE FUNCTION guard_private_file_update()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    context_editable BOOLEAN;
    other_evidence BOOLEAN;
    attachment_window_open BOOLEAN;
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.assignment_id IS DISTINCT FROM NEW.assignment_id
       OR OLD.learner_assignment_submission_id IS DISTINCT FROM NEW.learner_assignment_submission_id
       OR OLD.purpose <> NEW.purpose OR OLD.access_scope <> NEW.access_scope
       OR OLD.original_filename <> NEW.original_filename OR OLD.download_filename <> NEW.download_filename
       OR OLD.detected_media_type <> NEW.detected_media_type OR OLD.size_bytes <> NEW.size_bytes
       OR OLD.sha256_hex <> NEW.sha256_hex OR OLD.storage_bucket <> NEW.storage_bucket
       OR OLD.storage_key <> NEW.storage_key OR OLD.scan_engine <> NEW.scan_engine
       OR OLD.scan_engine_version <> NEW.scan_engine_version OR OLD.scan_status <> NEW.scan_status
       OR OLD.replaces_file_id IS DISTINCT FROM NEW.replaces_file_id
       OR OLD.created_by_user_id <> NEW.created_by_user_id
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at OR OLD.available_at <> NEW.available_at THEN
        RAISE EXCEPTION 'private file evidence is immutable';
    END IF;
    IF OLD.lifecycle_status <> 'AVAILABLE' OR NEW.lifecycle_status <> 'EXPIRED'
       OR NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION 'invalid private file lifecycle transition';
    END IF;
    IF OLD.assignment_id IS NOT NULL THEN
        SELECT status = 'DRAFT' INTO context_editable FROM assignments WHERE id = OLD.assignment_id;
    ELSE
        SELECT assignment.status = 'PUBLISHED'
               AND submission.state IN ('NOT_STARTED', 'DRAFT', 'RETURNED')
          INTO context_editable
        FROM learner_assignment_submissions submission
        JOIN assignments assignment ON assignment.id = submission.assignment_id
        WHERE submission.id = OLD.learner_assignment_submission_id;
        SELECT CURRENT_TIMESTAMP >= assignment.available_from
               AND (CURRENT_TIMESTAMP <= assignment.due_at OR assignment.allow_late)
               AND (assignment.late_until IS NULL OR CURRENT_TIMESTAMP <= assignment.late_until)
          INTO attachment_window_open
        FROM learner_assignment_submissions submission
        JOIN assignments assignment ON assignment.id = submission.assignment_id
        WHERE submission.id = OLD.learner_assignment_submission_id;
        context_editable := context_editable AND attachment_window_open;
        SELECT EXISTS (
            SELECT 1 FROM private_files other_file
            WHERE other_file.learner_assignment_submission_id = OLD.learner_assignment_submission_id
              AND other_file.lifecycle_status = 'AVAILABLE' AND other_file.id <> OLD.id
        ) INTO other_evidence;
        IF other_evidence IS NOT TRUE AND EXISTS (
            SELECT 1 FROM learner_assignment_submissions submission
            WHERE submission.id = OLD.learner_assignment_submission_id
              AND submission.state = 'RETURNED'
              AND submission.response_text IS NULL AND submission.response_link IS NULL
        ) THEN
            RAISE EXCEPTION 'replace or add response evidence before expiring the last returned attachment';
        END IF;
    END IF;
    IF context_editable IS NOT TRUE THEN
        RAISE EXCEPTION 'file context is no longer editable';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER private_files_guard_update
    BEFORE UPDATE ON private_files FOR EACH ROW EXECUTE FUNCTION guard_private_file_update();

CREATE OR REPLACE FUNCTION refresh_submission_attachment_evidence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    submission_id UUID;
    has_files BOOLEAN;
BEGIN
    submission_id := NEW.learner_assignment_submission_id;
    IF submission_id IS NULL THEN RETURN NEW; END IF;
    SELECT EXISTS (
        SELECT 1 FROM private_files file
        WHERE file.learner_assignment_submission_id = submission_id
          AND file.lifecycle_status = 'AVAILABLE'
    ) INTO has_files;
    UPDATE learner_assignment_submissions submission SET
        has_attachment_evidence = has_files,
        state = CASE
            WHEN has_files AND submission.state IN ('NOT_STARTED', 'RETURNED') THEN 'DRAFT'
            WHEN NOT has_files AND submission.state = 'DRAFT'
                 AND submission.response_text IS NULL AND submission.response_link IS NULL THEN 'NOT_STARTED'
            ELSE submission.state END,
        submitted_by_user_id = CASE
            WHEN NOT has_files AND submission.state = 'DRAFT'
                 AND submission.response_text IS NULL AND submission.response_link IS NULL THEN NULL
            ELSE submission.submitted_by_user_id END,
        submitted_by_membership_id = CASE
            WHEN NOT has_files AND submission.state = 'DRAFT'
                 AND submission.response_text IS NULL AND submission.response_link IS NULL THEN NULL
            ELSE submission.submitted_by_membership_id END,
        submitted_at = CASE WHEN has_files AND submission.state = 'RETURNED' THEN NULL ELSE submission.submitted_at END,
        teacher_feedback = CASE WHEN has_files AND submission.state = 'RETURNED' THEN NULL ELSE submission.teacher_feedback END,
        points_awarded = CASE WHEN has_files AND submission.state = 'RETURNED' THEN NULL ELSE submission.points_awarded END,
        reviewed_by_membership_id = CASE WHEN has_files AND submission.state = 'RETURNED' THEN NULL ELSE submission.reviewed_by_membership_id END,
        reviewed_at = CASE WHEN has_files AND submission.state = 'RETURNED' THEN NULL ELSE submission.reviewed_at END,
        updated_at = CURRENT_TIMESTAMP,
        row_version = submission.row_version + 1
    WHERE submission.id = submission_id;
    RETURN NEW;
END; $$;

CREATE TRIGGER private_files_refresh_submission_after_insert
    AFTER INSERT ON private_files FOR EACH ROW EXECUTE FUNCTION refresh_submission_attachment_evidence();
CREATE TRIGGER private_files_refresh_submission_after_update
    AFTER UPDATE OF lifecycle_status ON private_files FOR EACH ROW EXECUTE FUNCTION refresh_submission_attachment_evidence();

CREATE OR REPLACE FUNCTION prevent_private_file_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'private file evidence and history cannot be deleted or rewritten'; END; $$;

CREATE TRIGGER private_files_prevent_delete
    BEFORE DELETE ON private_files FOR EACH ROW EXECUTE FUNCTION prevent_private_file_evidence_mutation();
CREATE TRIGGER private_file_history_prevent_update_or_delete
    BEFORE UPDATE OR DELETE ON private_file_history
    FOR EACH ROW EXECUTE FUNCTION prevent_private_file_evidence_mutation();

ALTER TABLE private_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE private_file_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY private_files_tenant_all ON private_files
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY private_file_history_tenant_all ON private_file_history
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE private_files IS
    'Immutable metadata for malware-scanned private objects. Object keys are never exposed to browsers and context authorization is re-evaluated for every download.';
COMMENT ON TABLE private_file_history IS
    'Append-only lifecycle evidence for upload, replacement, and expiry of private files.';

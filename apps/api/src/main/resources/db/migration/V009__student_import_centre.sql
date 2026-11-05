INSERT INTO capabilities (code, description, risk_level) VALUES
    ('student.import', 'Preview, resolve, and commit controlled student imports', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'student.import')
ON CONFLICT DO NOTHING;

CREATE TABLE student_import_batches (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    starts_on DATE NOT NULL,
    source_file_name VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_rows INTEGER NOT NULL,
    ready_rows INTEGER NOT NULL DEFAULT 0,
    review_rows INTEGER NOT NULL DEFAULT 0,
    invalid_rows INTEGER NOT NULL DEFAULT 0,
    skipped_rows INTEGER NOT NULL DEFAULT 0,
    committed_rows INTEGER NOT NULL DEFAULT 0,
    created_by_membership_id UUID NOT NULL,
    committed_by_membership_id UUID,
    committed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT student_import_batches_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT student_import_batches_fingerprint_unique
        UNIQUE (school_id, campus_id, academic_session_id, file_sha256),
    CONSTRAINT student_import_batches_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT student_import_batches_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT student_import_batches_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT student_import_batches_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT student_import_batches_committer_fk
        FOREIGN KEY (committed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT student_import_batches_sha_valid CHECK (file_sha256 ~ '^[a-f0-9]{64}$'),
    CONSTRAINT student_import_batches_status_valid CHECK (
        status IN ('REVIEW_REQUIRED', 'READY', 'COMMITTED', 'CANCELLED')
    ),
    CONSTRAINT student_import_batches_counts_valid CHECK (
        total_rows BETWEEN 1 AND 2000
        AND ready_rows >= 0 AND review_rows >= 0 AND invalid_rows >= 0
        AND skipped_rows >= 0 AND committed_rows >= 0
        AND ready_rows + review_rows + invalid_rows + skipped_rows + committed_rows = total_rows
    ),
    CONSTRAINT student_import_batches_commit_state_valid CHECK (
        (status = 'COMMITTED' AND committed_at IS NOT NULL AND committed_by_membership_id IS NOT NULL
            AND ready_rows = 0 AND review_rows = 0 AND invalid_rows = 0)
        OR (status <> 'COMMITTED' AND committed_at IS NULL AND committed_by_membership_id IS NULL)
    )
);

CREATE TABLE student_import_rows (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    source_row_number INTEGER NOT NULL,
    legal_name VARCHAR(120),
    preferred_name VARCHAR(120),
    birth_date DATE,
    gender_code VARCHAR(20),
    gender_description VARCHAR(80),
    grade_code VARCHAR(40),
    admission_number VARCHAR(80),
    guardian_name VARCHAR(120),
    guardian_external_key VARCHAR(80),
    guardian_relationship_code VARCHAR(40),
    guardian_email_normalized VARCHAR(254),
    guardian_phone VARCHAR(30),
    primary_contact BOOLEAN NOT NULL DEFAULT TRUE,
    pickup_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL,
    validation_errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    candidate_learner_id UUID,
    candidate_guardian_id UUID,
    learner_resolution VARCHAR(20),
    guardian_resolution VARCHAR(20),
    resolution_reason VARCHAR(240),
    resolved_guardian_id UUID,
    created_learner_id UUID,
    created_enrolment_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT student_import_rows_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT student_import_rows_batch_row_unique UNIQUE (batch_id, source_row_number),
    CONSTRAINT student_import_rows_source_row_valid CHECK (source_row_number >= 2),
    CONSTRAINT student_import_rows_batch_fk
        FOREIGN KEY (batch_id, organisation_id, school_id)
        REFERENCES student_import_batches(id, organisation_id, school_id),
    CONSTRAINT student_import_rows_candidate_learner_fk
        FOREIGN KEY (candidate_learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT student_import_rows_candidate_guardian_fk
        FOREIGN KEY (candidate_guardian_id, organisation_id, school_id)
        REFERENCES guardians(id, organisation_id, school_id),
    CONSTRAINT student_import_rows_resolved_guardian_fk
        FOREIGN KEY (resolved_guardian_id, organisation_id, school_id)
        REFERENCES guardians(id, organisation_id, school_id),
    CONSTRAINT student_import_rows_status_valid CHECK (
        status IN ('INVALID', 'REVIEW', 'READY', 'SKIPPED', 'COMMITTED')
    ),
    CONSTRAINT student_import_rows_gender_valid CHECK (
        status = 'INVALID'
        OR gender_code IN ('FEMALE', 'MALE', 'NON_BINARY', 'SELF_DESCRIBED', 'NOT_STATED')
    ),
    CONSTRAINT student_import_rows_gender_description_valid CHECK (
        status = 'INVALID'
        OR (gender_code = 'SELF_DESCRIBED' AND length(btrim(gender_description)) BETWEEN 1 AND 80)
        OR (gender_code <> 'SELF_DESCRIBED' AND gender_description IS NULL)
    ),
    CONSTRAINT student_import_rows_guardian_contact_present CHECK (
        status = 'INVALID' OR guardian_email_normalized IS NOT NULL OR guardian_phone IS NOT NULL
    ),
    CONSTRAINT student_import_rows_guardian_email_normalized CHECK (
        guardian_email_normalized IS NULL OR guardian_email_normalized = lower(guardian_email_normalized)
    ),
    CONSTRAINT student_import_rows_validation_errors_array CHECK (
        jsonb_typeof(validation_errors) = 'array'
    ),
    CONSTRAINT student_import_rows_learner_resolution_valid CHECK (
        learner_resolution IS NULL OR learner_resolution IN ('CREATE_NEW', 'SKIP')
    ),
    CONSTRAINT student_import_rows_guardian_resolution_valid CHECK (
        guardian_resolution IS NULL OR guardian_resolution IN ('CREATE_NEW', 'LINK_EXISTING')
    ),
    CONSTRAINT student_import_rows_resolution_state_valid CHECK (
        (status IN ('INVALID', 'REVIEW') AND resolved_guardian_id IS NULL
            AND created_learner_id IS NULL AND created_enrolment_id IS NULL)
        OR (status = 'READY' AND learner_resolution = 'CREATE_NEW'
            AND guardian_resolution IN ('CREATE_NEW', 'LINK_EXISTING')
            AND resolved_guardian_id IS NULL
            AND created_learner_id IS NULL AND created_enrolment_id IS NULL)
        OR (status = 'SKIPPED' AND learner_resolution = 'SKIP'
            AND resolved_guardian_id IS NULL
            AND created_learner_id IS NULL AND created_enrolment_id IS NULL)
        OR (status = 'COMMITTED' AND learner_resolution = 'CREATE_NEW'
            AND guardian_resolution IN ('CREATE_NEW', 'LINK_EXISTING')
            AND resolved_guardian_id IS NOT NULL
            AND created_learner_id IS NOT NULL AND created_enrolment_id IS NOT NULL)
    ),
    CONSTRAINT student_import_rows_link_candidate_required CHECK (
        guardian_resolution <> 'LINK_EXISTING' OR candidate_guardian_id IS NOT NULL
    ),
    CONSTRAINT student_import_rows_review_reason_required CHECK (
        status IN ('INVALID', 'REVIEW')
        OR (
            (status <> 'SKIPPED' OR (
                resolution_reason IS NOT NULL
                AND length(btrim(resolution_reason)) BETWEEN 1 AND 240
            ))
            AND (
                (candidate_learner_id IS NULL AND candidate_guardian_id IS NULL)
                OR (
                    resolution_reason IS NOT NULL
                    AND length(btrim(resolution_reason)) BETWEEN 1 AND 240
                )
            )
        )
    ),
    CONSTRAINT student_import_rows_ready_fields_present CHECK (
        status = 'INVALID'
        OR (legal_name IS NOT NULL AND birth_date IS NOT NULL AND gender_code IS NOT NULL
            AND grade_code IS NOT NULL AND guardian_name IS NOT NULL
            AND guardian_relationship_code IS NOT NULL)
    )
);

ALTER TABLE learners
    ALTER COLUMN origin_application_id DROP NOT NULL,
    ADD COLUMN origin_import_row_id UUID,
    ADD CONSTRAINT learners_origin_import_row_unique UNIQUE (origin_import_row_id),
    ADD CONSTRAINT learners_origin_import_row_fk
        FOREIGN KEY (origin_import_row_id, organisation_id, school_id)
        REFERENCES student_import_rows(id, organisation_id, school_id),
    ADD CONSTRAINT learners_exactly_one_origin CHECK (
        num_nonnulls(origin_application_id, origin_import_row_id) = 1
    );

ALTER TABLE student_enrolments
    ALTER COLUMN origin_application_id DROP NOT NULL,
    ADD COLUMN origin_import_row_id UUID,
    ADD CONSTRAINT student_enrolments_origin_import_row_unique UNIQUE (origin_import_row_id),
    ADD CONSTRAINT student_enrolments_origin_import_row_fk
        FOREIGN KEY (origin_import_row_id, organisation_id, school_id)
        REFERENCES student_import_rows(id, organisation_id, school_id),
    ADD CONSTRAINT student_enrolments_exactly_one_origin CHECK (
        num_nonnulls(origin_application_id, origin_import_row_id) = 1
    );

DROP INDEX student_enrolments_school_admission_number_unique;
CREATE UNIQUE INDEX student_enrolments_school_admission_number_unique
    ON student_enrolments (school_id, lower(admission_number))
    WHERE admission_number IS NOT NULL;

ALTER TABLE student_import_rows
    ADD CONSTRAINT student_import_rows_created_learner_fk
        FOREIGN KEY (created_learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    ADD CONSTRAINT student_import_rows_created_enrolment_fk
        FOREIGN KEY (created_enrolment_id, organisation_id, school_id)
        REFERENCES student_enrolments(id, organisation_id, school_id);

CREATE INDEX student_import_batches_school_status_idx
    ON student_import_batches (organisation_id, school_id, status, created_at DESC);
CREATE INDEX student_import_rows_batch_status_idx
    ON student_import_rows (batch_id, status, source_row_number);

ALTER TABLE student_import_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE student_import_rows ENABLE ROW LEVEL SECURITY;

CREATE POLICY student_import_batches_tenant_select ON student_import_batches
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY student_import_batches_tenant_insert ON student_import_batches
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY student_import_batches_tenant_update ON student_import_batches
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY student_import_rows_tenant_select ON student_import_rows
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY student_import_rows_tenant_insert ON student_import_rows
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY student_import_rows_tenant_update ON student_import_rows
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE student_import_batches IS
    'Controlled student import batches. Raw file bytes are never stored; a digest and sanitized display name provide provenance.';
COMMENT ON TABLE student_import_rows IS
    'Validated staging rows. Canonical learner and enrolment writes occur only through an authorized batch commit.';

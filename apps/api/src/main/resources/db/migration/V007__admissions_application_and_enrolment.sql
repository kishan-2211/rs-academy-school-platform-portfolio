ALTER TABLE campuses
    ADD CONSTRAINT campuses_id_organisation_school_unique UNIQUE (id, organisation_id, school_id);

ALTER TABLE academic_sessions
    ADD CONSTRAINT academic_sessions_id_organisation_school_unique UNIQUE (id, organisation_id, school_id);

INSERT INTO capabilities (code, description, risk_level) VALUES
    ('admissions.application.read', 'Read school-scoped admission applications and checklists', 'SENSITIVE'),
    ('admissions.application.manage', 'Create and progress admission applications and document review', 'SENSITIVE'),
    ('admissions.application.decide', 'Record offer, waitlist, or rejection decisions', 'RESTRICTED'),
    ('admissions.application.enrol', 'Handoff an accepted application into learner records', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'admissions.application.read'),
    ('00000000-0000-4000-8000-000000000001', 'admissions.application.manage'),
    ('00000000-0000-4000-8000-000000000001', 'admissions.application.decide'),
    ('00000000-0000-4000-8000-000000000001', 'admissions.application.enrol'),
    ('00000000-0000-4000-8000-000000000002', 'admissions.application.read'),
    ('00000000-0000-4000-8000-000000000002', 'admissions.application.manage'),
    ('00000000-0000-4000-8000-000000000002', 'admissions.application.decide')
ON CONFLICT DO NOTHING;

CREATE TABLE admission_applications (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    enquiry_id UUID,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    target_campus_id UUID,
    target_academic_session_id UUID,
    intake_session_label VARCHAR(80) NOT NULL,
    target_grade_code VARCHAR(40) NOT NULL,
    applicant_legal_name VARCHAR(120) NOT NULL,
    applicant_preferred_name VARCHAR(120),
    birth_date DATE NOT NULL,
    gender_code VARCHAR(20) NOT NULL DEFAULT 'NOT_STATED',
    gender_description VARCHAR(80),
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    assigned_membership_id UUID,
    created_by_membership_id UUID NOT NULL,
    current_decision_id UUID,
    submitted_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    withdrawn_at TIMESTAMPTZ,
    enrolled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT admission_applications_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT admission_applications_school_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT admission_applications_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT admission_applications_enquiry_fk
        FOREIGN KEY (enquiry_id, organisation_id, school_id)
        REFERENCES public_enquiries(id, organisation_id, school_id),
    CONSTRAINT admission_applications_campus_fk
        FOREIGN KEY (target_campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT admission_applications_session_fk
        FOREIGN KEY (target_academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT admission_applications_assignee_fk
        FOREIGN KEY (assigned_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT admission_applications_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT admission_applications_status_valid CHECK (
        status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'WAITLISTED', 'OFFERED',
                   'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'ENROLLED')
    ),
    CONSTRAINT admission_applications_grade_valid CHECK (
        length(btrim(target_grade_code)) BETWEEN 1 AND 40
    ),
    CONSTRAINT admission_applications_gender_valid CHECK (
        gender_code IN ('FEMALE', 'MALE', 'NON_BINARY', 'SELF_DESCRIBED', 'NOT_STATED')
    ),
    CONSTRAINT admission_applications_gender_description_valid CHECK (
        (gender_code = 'SELF_DESCRIBED' AND length(btrim(gender_description)) BETWEEN 1 AND 80)
        OR (gender_code <> 'SELF_DESCRIBED' AND gender_description IS NULL)
    ),
    CONSTRAINT admission_applications_withdrawn_state_valid CHECK (
        (status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL)
    ),
    CONSTRAINT admission_applications_enrolled_state_valid CHECK (
        (status = 'ENROLLED') = (enrolled_at IS NOT NULL)
    )
);

CREATE INDEX admission_applications_school_status_time_idx
    ON admission_applications (organisation_id, school_id, status, created_at DESC);
CREATE INDEX admission_applications_enquiry_idx
    ON admission_applications (enquiry_id, created_at DESC)
    WHERE enquiry_id IS NOT NULL;
CREATE INDEX admission_applications_assignee_idx
    ON admission_applications (assigned_membership_id, status, updated_at DESC)
    WHERE assigned_membership_id IS NOT NULL;

CREATE TABLE admission_application_guardians (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    application_id UUID NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    relationship_code VARCHAR(40) NOT NULL,
    email_normalized VARCHAR(254),
    phone VARCHAR(30),
    primary_contact BOOLEAN NOT NULL DEFAULT FALSE,
    pickup_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    matched_guardian_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT admission_application_guardians_id_scope_unique
        UNIQUE (id, organisation_id, school_id, application_id),
    CONSTRAINT admission_application_guardians_application_fk
        FOREIGN KEY (application_id, organisation_id, school_id)
        REFERENCES admission_applications(id, organisation_id, school_id),
    CONSTRAINT admission_application_guardians_contact_present CHECK (
        email_normalized IS NOT NULL OR phone IS NOT NULL
    ),
    CONSTRAINT admission_application_guardians_email_normalized CHECK (
        email_normalized IS NULL OR email_normalized = lower(email_normalized)
    ),
    CONSTRAINT admission_application_guardians_relationship_valid CHECK (
        length(btrim(relationship_code)) BETWEEN 1 AND 40
    )
);

CREATE UNIQUE INDEX admission_application_guardians_one_primary_idx
    ON admission_application_guardians (application_id)
    WHERE primary_contact;

CREATE TABLE admission_application_documents (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    application_id UUID NOT NULL,
    document_code VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'MISSING',
    received_method VARCHAR(20),
    received_at TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    verified_by_membership_id UUID,
    review_note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT admission_application_documents_application_unique
        UNIQUE (application_id, document_code),
    CONSTRAINT admission_application_documents_id_scope_unique
        UNIQUE (id, organisation_id, school_id, application_id),
    CONSTRAINT admission_application_documents_application_fk
        FOREIGN KEY (application_id, organisation_id, school_id)
        REFERENCES admission_applications(id, organisation_id, school_id),
    CONSTRAINT admission_application_documents_verifier_fk
        FOREIGN KEY (verified_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT admission_application_documents_code_valid CHECK (
        document_code ~ '^[A-Z][A-Z0-9_]{1,39}$'
    ),
    CONSTRAINT admission_application_documents_status_valid CHECK (
        status IN ('MISSING', 'RECEIVED', 'VERIFIED', 'REJECTED', 'WAIVED')
    ),
    CONSTRAINT admission_application_documents_method_valid CHECK (
        received_method IS NULL OR received_method IN ('PRIVATE_UPLOAD', 'OFFLINE_ORIGINAL', 'OFFLINE_COPY')
    ),
    CONSTRAINT admission_application_documents_received_state_valid CHECK (
        (status IN ('RECEIVED', 'VERIFIED', 'REJECTED')) = (received_at IS NOT NULL)
    ),
    CONSTRAINT admission_application_documents_verified_state_valid CHECK (
        (status = 'VERIFIED') = (verified_at IS NOT NULL AND verified_by_membership_id IS NOT NULL)
    )
);

CREATE INDEX admission_application_documents_review_idx
    ON admission_application_documents (school_id, status, updated_at DESC);

CREATE TABLE admission_decisions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    application_id UUID NOT NULL,
    decision VARCHAR(20) NOT NULL,
    reason_code VARCHAR(60) NOT NULL,
    internal_summary VARCHAR(1000),
    offer_expires_on DATE,
    decided_by_membership_id UUID NOT NULL,
    supersedes_decision_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT admission_decisions_id_scope_unique
        UNIQUE (id, organisation_id, school_id, application_id),
    CONSTRAINT admission_decisions_application_fk
        FOREIGN KEY (application_id, organisation_id, school_id)
        REFERENCES admission_applications(id, organisation_id, school_id),
    CONSTRAINT admission_decisions_actor_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT admission_decisions_superseded_fk
        FOREIGN KEY (supersedes_decision_id, organisation_id, school_id, application_id)
        REFERENCES admission_decisions(id, organisation_id, school_id, application_id),
    CONSTRAINT admission_decisions_value_valid CHECK (
        decision IN ('OFFER', 'WAITLIST', 'REJECT')
    ),
    CONSTRAINT admission_decisions_offer_expiry_valid CHECK (
        (decision = 'OFFER' AND offer_expires_on IS NOT NULL)
        OR (decision <> 'OFFER' AND offer_expires_on IS NULL)
    )
);

ALTER TABLE admission_applications
    ADD CONSTRAINT admission_applications_current_decision_fk
        FOREIGN KEY (current_decision_id, organisation_id, school_id, id)
        REFERENCES admission_decisions(id, organisation_id, school_id, application_id);

CREATE OR REPLACE FUNCTION prevent_admission_decision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'admission_decisions is append-only';
END;
$$;

CREATE TRIGGER admission_decisions_prevent_update_or_delete
    BEFORE UPDATE OR DELETE ON admission_decisions
    FOR EACH ROW EXECUTE FUNCTION prevent_admission_decision_mutation();

CREATE TABLE learners (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    origin_application_id UUID NOT NULL,
    legal_name VARCHAR(120) NOT NULL,
    preferred_name VARCHAR(120),
    birth_date DATE NOT NULL,
    gender_code VARCHAR(20) NOT NULL,
    gender_description VARCHAR(80),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT learners_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT learners_origin_application_unique UNIQUE (origin_application_id),
    CONSTRAINT learners_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT learners_origin_application_fk
        FOREIGN KEY (origin_application_id, organisation_id, school_id)
        REFERENCES admission_applications(id, organisation_id, school_id),
    CONSTRAINT learners_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE', 'ALUMNI')),
    CONSTRAINT learners_gender_valid CHECK (
        gender_code IN ('FEMALE', 'MALE', 'NON_BINARY', 'SELF_DESCRIBED', 'NOT_STATED')
    ),
    CONSTRAINT learners_gender_description_valid CHECK (
        (gender_code = 'SELF_DESCRIBED' AND length(btrim(gender_description)) BETWEEN 1 AND 80)
        OR (gender_code <> 'SELF_DESCRIBED' AND gender_description IS NULL)
    )
);

CREATE TABLE guardians (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    email_normalized VARCHAR(254),
    phone VARCHAR(30),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT guardians_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT guardians_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT guardians_contact_present CHECK (email_normalized IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT guardians_email_normalized CHECK (
        email_normalized IS NULL OR email_normalized = lower(email_normalized)
    ),
    CONSTRAINT guardians_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

ALTER TABLE admission_application_guardians
    ADD CONSTRAINT admission_application_guardians_matched_guardian_fk
        FOREIGN KEY (matched_guardian_id, organisation_id, school_id)
        REFERENCES guardians(id, organisation_id, school_id);

CREATE TABLE learner_guardian_relationships (
    learner_id UUID NOT NULL,
    guardian_id UUID NOT NULL,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    relationship_code VARCHAR(40) NOT NULL,
    primary_contact BOOLEAN NOT NULL DEFAULT FALSE,
    pickup_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    portal_access_status VARCHAR(20) NOT NULL DEFAULT 'NOT_INVITED',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (learner_id, guardian_id),
    CONSTRAINT learner_guardian_relationships_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT learner_guardian_relationships_guardian_fk
        FOREIGN KEY (guardian_id, organisation_id, school_id)
        REFERENCES guardians(id, organisation_id, school_id),
    CONSTRAINT learner_guardian_relationships_portal_valid CHECK (
        portal_access_status IN ('NOT_INVITED', 'INVITED', 'ACTIVE', 'SUSPENDED', 'DENIED')
    )
);

CREATE UNIQUE INDEX learner_guardian_relationships_one_primary_idx
    ON learner_guardian_relationships (learner_id)
    WHERE primary_contact;

CREATE TABLE student_enrolments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    campus_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    origin_application_id UUID NOT NULL,
    grade_code VARCHAR(40) NOT NULL,
    admission_number VARCHAR(80),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    placement_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    starts_on DATE NOT NULL,
    ends_on DATE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT student_enrolments_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT student_enrolments_origin_application_unique UNIQUE (origin_application_id),
    CONSTRAINT student_enrolments_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT student_enrolments_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT student_enrolments_session_fk
        FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT student_enrolments_learner_fk
        FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT student_enrolments_origin_application_fk
        FOREIGN KEY (origin_application_id, organisation_id, school_id)
        REFERENCES admission_applications(id, organisation_id, school_id),
    CONSTRAINT student_enrolments_status_valid CHECK (status IN ('ACTIVE', 'ENDED', 'CANCELLED')),
    CONSTRAINT student_enrolments_placement_valid CHECK (placement_status IN ('PENDING', 'PLACED')),
    CONSTRAINT student_enrolments_dates_valid CHECK (ends_on IS NULL OR ends_on >= starts_on)
);

CREATE UNIQUE INDEX student_enrolments_school_admission_number_unique
    ON student_enrolments (school_id, admission_number)
    WHERE admission_number IS NOT NULL;
CREATE INDEX student_enrolments_learner_session_idx
    ON student_enrolments (learner_id, academic_session_id, status);

ALTER TABLE admission_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE admission_application_guardians ENABLE ROW LEVEL SECURITY;
ALTER TABLE admission_application_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE admission_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE learners ENABLE ROW LEVEL SECURITY;
ALTER TABLE guardians ENABLE ROW LEVEL SECURITY;
ALTER TABLE learner_guardian_relationships ENABLE ROW LEVEL SECURITY;
ALTER TABLE student_enrolments ENABLE ROW LEVEL SECURITY;

CREATE POLICY admission_applications_tenant_select ON admission_applications
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY admission_applications_tenant_insert ON admission_applications
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY admission_applications_tenant_update ON admission_applications
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY admission_application_guardians_tenant_select ON admission_application_guardians
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY admission_application_guardians_tenant_insert ON admission_application_guardians
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY admission_application_guardians_tenant_update ON admission_application_guardians
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY admission_application_documents_tenant_select ON admission_application_documents
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY admission_application_documents_tenant_insert ON admission_application_documents
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY admission_application_documents_tenant_update ON admission_application_documents
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY admission_decisions_tenant_select ON admission_decisions
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY admission_decisions_tenant_insert ON admission_decisions
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY learners_tenant_select ON learners
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY learners_tenant_insert ON learners
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY learners_tenant_update ON learners
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY guardians_tenant_select ON guardians
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY guardians_tenant_insert ON guardians
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY guardians_tenant_update ON guardians
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY learner_guardian_relationships_tenant_select ON learner_guardian_relationships
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY learner_guardian_relationships_tenant_insert ON learner_guardian_relationships
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY learner_guardian_relationships_tenant_update ON learner_guardian_relationships
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY student_enrolments_tenant_select ON student_enrolments
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY student_enrolments_tenant_insert ON student_enrolments
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY student_enrolments_tenant_update ON student_enrolments
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE admission_applications IS
    'School-scoped applicant record that preserves the admissions journey and becomes the source for enrolment handoff.';
COMMENT ON TABLE admission_decisions IS
    'Append-only admission decisions. Corrections are recorded as a new decision that supersedes the previous one.';
COMMENT ON TABLE admission_application_documents IS
    'Document checklist metadata only. Private file bytes and object keys must remain in the dedicated private file service.';
COMMENT ON TABLE student_enrolments IS
    'Enrolment created transactionally from one accepted admission application; section placement follows separately.';

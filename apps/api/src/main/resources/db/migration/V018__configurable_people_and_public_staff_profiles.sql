INSERT INTO capabilities (code, description, risk_level) VALUES
    ('people.read', 'Read ordinary school employment records within authorized scope', 'SENSITIVE'),
    ('people.manage', 'Configure departments/designations and manage staff employment lifecycle', 'RESTRICTED'),
    ('people.compliance', 'Review restricted qualification and safeguarding compliance evidence', 'RESTRICTED'),
    ('people.publish', 'Approve staff information for deliberate public disclosure', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'people.read'),
    ('00000000-0000-4000-8000-000000000001', 'people.manage'),
    ('00000000-0000-4000-8000-000000000001', 'people.compliance'),
    ('00000000-0000-4000-8000-000000000001', 'people.publish'),
    ('00000000-0000-4000-8000-000000000007', 'people.read'),
    ('00000000-0000-4000-8000-000000000007', 'people.manage'),
    ('00000000-0000-4000-8000-000000000007', 'people.compliance'),
    ('00000000-0000-4000-8000-000000000007', 'people.publish')
ON CONFLICT DO NOTHING;

CREATE TABLE staff_departments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    code VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_departments_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_departments_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT staff_departments_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_departments_natural_key_unique UNIQUE (school_id, code),
    CONSTRAINT staff_departments_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT staff_departments_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE staff_designations (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    code VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    staff_category VARCHAR(30) NOT NULL,
    cbse_staff_category VARCHAR(30),
    teaching_role BOOLEAN NOT NULL DEFAULT FALSE,
    leadership_role BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(500),
    display_order SMALLINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_designations_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_designations_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT staff_designations_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_designations_natural_key_unique UNIQUE (school_id, code),
    CONSTRAINT staff_designations_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT staff_designations_category_valid CHECK (staff_category IN (
        'LEADERSHIP','TEACHING','ADMINISTRATION','FINANCE','TRANSPORT','OPERATIONS',
        'SUPPORT','HEALTH','OTHER')),
    CONSTRAINT staff_designations_cbse_category_valid CHECK (cbse_staff_category IS NULL OR cbse_staff_category IN (
        'PRINCIPAL','VICE_PRINCIPAL','HEAD','PGT','TGT','PRT','SPECIAL_EDUCATOR',
        'COUNSELLOR_WELLNESS','OTHER_TEACHING','NON_TEACHING')),
    CONSTRAINT staff_designations_teaching_consistent CHECK (
        cbse_staff_category IS NULL OR cbse_staff_category = 'NON_TEACHING' OR teaching_role),
    CONSTRAINT staff_designations_order_valid CHECK (display_order BETWEEN 0 AND 500),
    CONSTRAINT staff_designations_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE staff_profiles (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    employee_code VARCHAR(50) NOT NULL,
    legal_name VARCHAR(160) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    primary_language VARCHAR(10) NOT NULL DEFAULT 'EN',
    work_email_normalized VARCHAR(254),
    work_phone VARCHAR(30),
    user_account_id UUID REFERENCES user_accounts(id),
    joined_on DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ended_on DATE,
    ending_reason VARCHAR(500),
    created_by_membership_id UUID NOT NULL,
    ended_by_membership_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_profiles_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_profiles_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT staff_profiles_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_profiles_ender_fk
        FOREIGN KEY (ended_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_profiles_employee_code_unique UNIQUE (school_id, employee_code),
    CONSTRAINT staff_profiles_code_valid CHECK (employee_code ~ '^[A-Z0-9][A-Z0-9_/-]{1,49}$'),
    CONSTRAINT staff_profiles_email_valid CHECK (
        work_email_normalized IS NULL OR (
            work_email_normalized = lower(work_email_normalized)
            AND length(work_email_normalized) BETWEEN 3 AND 254)),
    CONSTRAINT staff_profiles_language_valid CHECK (primary_language IN ('EN','HI')),
    CONSTRAINT staff_profiles_status_valid CHECK (status IN ('ACTIVE','INACTIVE','ENDED')),
    CONSTRAINT staff_profiles_lifecycle_valid CHECK (
        (status IN ('ACTIVE','INACTIVE') AND ended_on IS NULL AND ending_reason IS NULL
            AND ended_by_membership_id IS NULL)
        OR (status = 'ENDED' AND ended_on IS NOT NULL AND ended_on >= joined_on
            AND length(btrim(ending_reason)) BETWEEN 3 AND 500 AND ended_by_membership_id IS NOT NULL))
);

CREATE UNIQUE INDEX staff_profiles_account_unique
    ON staff_profiles (school_id, user_account_id) WHERE user_account_id IS NOT NULL;
CREATE INDEX staff_profiles_school_status_idx
    ON staff_profiles (organisation_id, school_id, status, display_name, id);

CREATE TABLE staff_assignments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    staff_designation_id UUID NOT NULL,
    staff_department_id UUID,
    campus_id UUID NOT NULL,
    employment_type VARCHAR(30) NOT NULL,
    employer_kind VARCHAR(20) NOT NULL DEFAULT 'SCHOOL',
    agency_name VARCHAR(160),
    primary_assignment BOOLEAN NOT NULL DEFAULT TRUE,
    starts_on DATE NOT NULL,
    ends_on DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ending_reason VARCHAR(500),
    created_by_membership_id UUID NOT NULL,
    ended_by_membership_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_assignments_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_assignments_profile_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_assignments_designation_fk
        FOREIGN KEY (staff_designation_id, organisation_id, school_id)
        REFERENCES staff_designations(id, organisation_id, school_id),
    CONSTRAINT staff_assignments_department_fk
        FOREIGN KEY (staff_department_id, organisation_id, school_id)
        REFERENCES staff_departments(id, organisation_id, school_id),
    CONSTRAINT staff_assignments_campus_fk
        FOREIGN KEY (campus_id, organisation_id, school_id)
        REFERENCES campuses(id, organisation_id, school_id),
    CONSTRAINT staff_assignments_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_assignments_ender_fk
        FOREIGN KEY (ended_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_assignments_type_valid CHECK (employment_type IN (
        'FULL_TIME','PART_TIME','FIXED_TERM','CONTRACT','OUTSOURCED','VOLUNTEER')),
    CONSTRAINT staff_assignments_employer_valid CHECK (
        (employer_kind = 'SCHOOL' AND agency_name IS NULL)
        OR (employer_kind = 'AGENCY' AND length(btrim(agency_name)) BETWEEN 2 AND 160)),
    CONSTRAINT staff_assignments_dates_valid CHECK (ends_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT staff_assignments_status_valid CHECK (status IN ('PLANNED','ACTIVE','ENDED','CANCELLED')),
    CONSTRAINT staff_assignments_lifecycle_valid CHECK (
        (status IN ('PLANNED','ACTIVE') AND ending_reason IS NULL AND ended_by_membership_id IS NULL)
        OR (status IN ('ENDED','CANCELLED') AND ends_on IS NOT NULL
            AND length(btrim(ending_reason)) BETWEEN 3 AND 500 AND ended_by_membership_id IS NOT NULL))
);

CREATE UNIQUE INDEX staff_assignments_one_active_primary_idx
    ON staff_assignments (staff_profile_id) WHERE status = 'ACTIVE' AND primary_assignment;
CREATE INDEX staff_assignments_profile_time_idx
    ON staff_assignments (organisation_id, school_id, staff_profile_id, starts_on DESC, id);

CREATE TABLE staff_qualifications (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    qualification_name VARCHAR(180) NOT NULL,
    qualification_level VARCHAR(30) NOT NULL,
    subject_specialisation VARCHAR(160),
    institution_name VARCHAR(200),
    awarding_body VARCHAR(200),
    completed_year SMALLINT,
    verification_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    verified_by_membership_id UUID,
    verified_at TIMESTAMPTZ,
    verification_reason VARCHAR(500),
    public_summary_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_qualifications_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_qualifications_profile_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_qualifications_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_qualifications_verifier_fk
        FOREIGN KEY (verified_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_qualifications_level_valid CHECK (qualification_level IN (
        'CERTIFICATE','DIPLOMA','GRADUATE','POSTGRADUATE','DOCTORATE','PROFESSIONAL','OTHER')),
    CONSTRAINT staff_qualifications_year_valid CHECK (
        completed_year IS NULL OR completed_year BETWEEN 1950 AND 2100),
    CONSTRAINT staff_qualifications_status_valid CHECK (
        verification_status IN ('PENDING','VERIFIED','REJECTED')),
    CONSTRAINT staff_qualifications_verification_valid CHECK (
        (verification_status = 'PENDING' AND verified_by_membership_id IS NULL
            AND verified_at IS NULL AND verification_reason IS NULL)
        OR (verification_status IN ('VERIFIED','REJECTED') AND verified_by_membership_id IS NOT NULL
            AND verified_at IS NOT NULL AND length(btrim(verification_reason)) BETWEEN 3 AND 500
            AND verified_by_membership_id <> created_by_membership_id)),
    CONSTRAINT staff_qualifications_public_valid CHECK (
        public_summary_eligible = FALSE OR verification_status = 'VERIFIED')
);

CREATE TABLE staff_compliance_types (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    code VARCHAR(50) NOT NULL,
    display_name VARCHAR(140) NOT NULL,
    description VARCHAR(500),
    applicable_staff_category VARCHAR(30),
    validity_months SMALLINT,
    required_for_active_service BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_compliance_types_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_compliance_types_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT staff_compliance_types_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_compliance_types_natural_key_unique UNIQUE (school_id, code),
    CONSTRAINT staff_compliance_types_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT staff_compliance_types_category_valid CHECK (
        applicable_staff_category IS NULL OR applicable_staff_category IN (
            'LEADERSHIP','TEACHING','ADMINISTRATION','FINANCE','TRANSPORT','OPERATIONS',
            'SUPPORT','HEALTH','OTHER')),
    CONSTRAINT staff_compliance_types_validity_valid CHECK (
        validity_months IS NULL OR validity_months BETWEEN 1 AND 240),
    CONSTRAINT staff_compliance_types_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE staff_compliance_checks (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    staff_compliance_type_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    completed_on DATE,
    valid_until DATE,
    safe_reference VARCHAR(160),
    restricted_note VARCHAR(1000),
    created_by_membership_id UUID NOT NULL,
    verified_by_membership_id UUID,
    verified_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_compliance_checks_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_compliance_checks_profile_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_compliance_checks_type_fk
        FOREIGN KEY (staff_compliance_type_id, organisation_id, school_id)
        REFERENCES staff_compliance_types(id, organisation_id, school_id),
    CONSTRAINT staff_compliance_checks_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_compliance_checks_verifier_fk
        FOREIGN KEY (verified_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_compliance_checks_status_valid CHECK (
        status IN ('PENDING','VERIFIED','REJECTED','WAIVED','EXPIRED')),
    CONSTRAINT staff_compliance_checks_dates_valid CHECK (
        valid_until IS NULL OR (completed_on IS NOT NULL AND valid_until >= completed_on)),
    CONSTRAINT staff_compliance_checks_decision_valid CHECK (
        (status = 'PENDING' AND verified_by_membership_id IS NULL
            AND verified_at IS NULL AND decision_reason IS NULL)
        OR (status IN ('VERIFIED','REJECTED','WAIVED') AND verified_by_membership_id IS NOT NULL
            AND verified_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND verified_by_membership_id <> created_by_membership_id)
        OR (status = 'EXPIRED' AND completed_on IS NOT NULL AND valid_until IS NOT NULL))
);

CREATE INDEX staff_compliance_checks_profile_idx
    ON staff_compliance_checks (organisation_id, school_id, staff_profile_id, created_at DESC, id);

CREATE TABLE staff_public_profile_revisions (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    revision_number INTEGER NOT NULL,
    supersedes_revision_id UUID,
    public_display_name VARCHAR(120) NOT NULL,
    designation_snapshot VARCHAR(120) NOT NULL,
    qualification_summary VARCHAR(500),
    short_bio VARCHAR(1000),
    cbse_staff_category_snapshot VARCHAR(30),
    display_order SMALLINT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_by_membership_id UUID NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_public_profiles_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_public_profiles_staff_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_public_profiles_supersedes_fk
        FOREIGN KEY (supersedes_revision_id, organisation_id, school_id)
        REFERENCES staff_public_profile_revisions(id, organisation_id, school_id),
    CONSTRAINT staff_public_profiles_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_public_profiles_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_public_profiles_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_public_profiles_revision_unique UNIQUE (staff_profile_id, revision_number),
    CONSTRAINT staff_public_profiles_revision_valid CHECK (revision_number BETWEEN 1 AND 1000),
    CONSTRAINT staff_public_profiles_category_valid CHECK (
        cbse_staff_category_snapshot IS NULL OR cbse_staff_category_snapshot IN (
            'PRINCIPAL','VICE_PRINCIPAL','HEAD','PGT','TGT','PRT','SPECIAL_EDUCATOR',
            'COUNSELLOR_WELLNESS','OTHER_TEACHING','NON_TEACHING')),
    CONSTRAINT staff_public_profiles_order_valid CHECK (display_order BETWEEN 0 AND 500),
    CONSTRAINT staff_public_profiles_status_valid CHECK (
        status IN ('DRAFT','PENDING_APPROVAL','PUBLISHED','REJECTED','RETIRED')),
    CONSTRAINT staff_public_profiles_state_valid CHECK (
        (status = 'DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND decided_by_membership_id IS NULL AND decided_at IS NULL
            AND decision_reason IS NULL AND published_at IS NULL)
        OR (status = 'PENDING_APPROVAL' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND decided_by_membership_id IS NULL
            AND decided_at IS NULL AND decision_reason IS NULL AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND decided_by_membership_id IS NOT NULL
            AND decided_at IS NOT NULL AND published_at IS NOT NULL
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id <> created_by_membership_id)
        OR (status = 'REJECTED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND decided_by_membership_id IS NOT NULL
            AND decided_at IS NOT NULL AND published_at IS NULL
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id <> created_by_membership_id)
        OR (status = 'RETIRED' AND decided_by_membership_id IS NOT NULL
            AND decided_at IS NOT NULL AND published_at IS NOT NULL
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500))
);

CREATE UNIQUE INDEX staff_public_profiles_one_published_idx
    ON staff_public_profile_revisions (staff_profile_id) WHERE status = 'PUBLISHED';
CREATE INDEX staff_public_profiles_public_order_idx
    ON staff_public_profile_revisions (school_id, display_order, public_display_name, id)
    WHERE status = 'PUBLISHED';

CREATE TABLE staff_profile_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    subject_id UUID NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT staff_profile_history_profile_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_profile_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_profile_history_event_valid CHECK (event_type IN (
        'PROFILE_CREATED','PROFILE_UPDATED','PROFILE_ENDED','ACCESS_OFFBOARDED','ASSIGNMENT_ADDED','ASSIGNMENT_ENDED',
        'QUALIFICATION_ADDED','QUALIFICATION_DECIDED','COMPLIANCE_ADDED','COMPLIANCE_DECIDED',
        'PUBLIC_DRAFT_CREATED','PUBLIC_DRAFT_UPDATED','PUBLIC_SUBMITTED','PUBLIC_PUBLISHED',
        'PUBLIC_REJECTED','PUBLIC_RETIRED')),
    CONSTRAINT staff_profile_history_reason_valid CHECK (length(btrim(reason)) BETWEEN 3 AND 500)
);

CREATE OR REPLACE FUNCTION guard_staff_configuration()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'staff configuration cannot be deleted'; END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.code <> NEW.code
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'staff configuration identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER staff_departments_guard
    BEFORE UPDATE OR DELETE ON staff_departments
    FOR EACH ROW EXECUTE FUNCTION guard_staff_configuration();
CREATE TRIGGER staff_designations_guard
    BEFORE UPDATE OR DELETE ON staff_designations
    FOR EACH ROW EXECUTE FUNCTION guard_staff_configuration();
CREATE TRIGGER staff_compliance_types_guard
    BEFORE UPDATE OR DELETE ON staff_compliance_types
    FOR EACH ROW EXECUTE FUNCTION guard_staff_configuration();

CREATE OR REPLACE FUNCTION guard_staff_department_usage()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'ACTIVE' AND NEW.status = 'INACTIVE'
       AND EXISTS (SELECT 1 FROM staff_assignments assignment
                   WHERE assignment.staff_department_id = OLD.id
                     AND assignment.status IN ('PLANNED','ACTIVE')) THEN
        RAISE EXCEPTION 'department is still used by an open staff assignment';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER staff_departments_usage_guard
    BEFORE UPDATE ON staff_departments
    FOR EACH ROW EXECUTE FUNCTION guard_staff_department_usage();

CREATE OR REPLACE FUNCTION guard_staff_designation_usage()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.status IS DISTINCT FROM NEW.status
        OR OLD.staff_category IS DISTINCT FROM NEW.staff_category
        OR OLD.cbse_staff_category IS DISTINCT FROM NEW.cbse_staff_category
        OR OLD.teaching_role IS DISTINCT FROM NEW.teaching_role)
       AND EXISTS (SELECT 1 FROM staff_assignments assignment
                   WHERE assignment.staff_designation_id = OLD.id
                     AND assignment.status IN ('PLANNED','ACTIVE')) THEN
        RAISE EXCEPTION 'designation meaning is still used by an open staff assignment';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER staff_designations_usage_guard
    BEFORE UPDATE ON staff_designations
    FOR EACH ROW EXECUTE FUNCTION guard_staff_designation_usage();

CREATE OR REPLACE FUNCTION guard_staff_profile()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'staff profiles cannot be deleted'; END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.employee_code <> NEW.employee_code OR OLD.joined_on <> NEW.joined_on
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'staff profile identity is immutable';
    END IF;
    IF OLD.status = 'ENDED' THEN RAISE EXCEPTION 'ended staff profiles are immutable'; END IF;
    IF NEW.status IN ('INACTIVE','ENDED') AND EXISTS (
        SELECT 1 FROM staff_assignments assignment
        WHERE assignment.staff_profile_id = OLD.id AND assignment.status IN ('PLANNED','ACTIVE')) THEN
        RAISE EXCEPTION 'end active or planned staff assignments before closing the profile';
    END IF;
    IF NEW.status IN ('INACTIVE','ENDED') AND EXISTS (
        SELECT 1 FROM staff_public_profile_revisions public_profile
        WHERE public_profile.staff_profile_id = OLD.id AND public_profile.status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'retire the published staff profile before closing the employment profile';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER staff_profiles_guard
    BEFORE UPDATE OR DELETE ON staff_profiles
    FOR EACH ROW EXECUTE FUNCTION guard_staff_profile();

CREATE OR REPLACE FUNCTION guard_staff_profile_account()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.user_account_id IS NOT NULL AND NEW.status = 'ACTIVE' AND NOT EXISTS (
        SELECT 1 FROM memberships membership
        JOIN user_accounts account ON account.id = membership.user_id
        WHERE membership.user_id = NEW.user_account_id
          AND membership.organisation_id = NEW.organisation_id
          AND membership.school_id = NEW.school_id
          AND membership.status = 'ACTIVE' AND account.status IN ('ACTIVE','INVITED')
          AND EXISTS (SELECT 1 FROM membership_roles membership_role
              JOIN roles role ON role.id = membership_role.role_id
              WHERE membership_role.membership_id = membership.id
                AND role.code <> 'parent_guardian'
                AND (role.organisation_id IS NULL OR role.organisation_id = membership.organisation_id))) THEN
        RAISE EXCEPTION 'linked staff account requires an active same-school non-parent membership';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER staff_profiles_account_guard
    BEFORE INSERT OR UPDATE ON staff_profiles
    FOR EACH ROW EXECUTE FUNCTION guard_staff_profile_account();

CREATE OR REPLACE FUNCTION guard_staff_assignment()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE profile_status VARCHAR(20); designation_status VARCHAR(20); department_status VARCHAR(20); campus_status VARCHAR(20);
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'staff assignments cannot be deleted'; END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
           OR OLD.staff_profile_id <> NEW.staff_profile_id OR OLD.staff_designation_id <> NEW.staff_designation_id
           OR OLD.staff_department_id IS DISTINCT FROM NEW.staff_department_id OR OLD.campus_id <> NEW.campus_id
           OR OLD.employment_type <> NEW.employment_type OR OLD.employer_kind <> NEW.employer_kind
           OR OLD.agency_name IS DISTINCT FROM NEW.agency_name OR OLD.primary_assignment <> NEW.primary_assignment
           OR OLD.starts_on <> NEW.starts_on OR OLD.created_by_membership_id <> NEW.created_by_membership_id
           OR OLD.created_at <> NEW.created_at THEN
            RAISE EXCEPTION 'staff assignment identity is immutable';
        END IF;
        IF OLD.status = 'PLANNED' AND NEW.status NOT IN ('PLANNED','ACTIVE','CANCELLED') THEN
            RAISE EXCEPTION 'invalid planned staff assignment transition';
        END IF;
        IF OLD.status = 'ACTIVE' AND NEW.status NOT IN ('ACTIVE','ENDED') THEN
            RAISE EXCEPTION 'invalid active staff assignment transition';
        END IF;
        IF OLD.status IN ('ENDED','CANCELLED') THEN RAISE EXCEPTION 'terminal staff assignments are immutable'; END IF;
        IF OLD.status = 'ACTIVE' AND NEW.status = 'ENDED' AND OLD.primary_assignment
           AND EXISTS (SELECT 1 FROM staff_public_profile_revisions public_profile
                       WHERE public_profile.staff_profile_id = OLD.staff_profile_id
                         AND public_profile.status = 'PUBLISHED') THEN
            RAISE EXCEPTION 'retire the published staff profile before ending its primary assignment';
        END IF;
    END IF;
    SELECT status INTO profile_status FROM staff_profiles
        WHERE id = NEW.staff_profile_id AND organisation_id = NEW.organisation_id AND school_id = NEW.school_id;
    SELECT status INTO designation_status FROM staff_designations
        WHERE id = NEW.staff_designation_id AND organisation_id = NEW.organisation_id AND school_id = NEW.school_id;
    IF NEW.staff_department_id IS NOT NULL THEN
        SELECT status INTO department_status FROM staff_departments
            WHERE id = NEW.staff_department_id AND organisation_id = NEW.organisation_id AND school_id = NEW.school_id;
    END IF;
    SELECT status INTO campus_status FROM campuses
        WHERE id = NEW.campus_id AND organisation_id = NEW.organisation_id AND school_id = NEW.school_id;
    IF NEW.status IN ('PLANNED','ACTIVE') AND (profile_status <> 'ACTIVE' OR designation_status <> 'ACTIVE'
       OR campus_status <> 'ACTIVE' OR (NEW.staff_department_id IS NOT NULL AND department_status <> 'ACTIVE')) THEN
        RAISE EXCEPTION 'open staff assignments require active scoped configuration';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER staff_assignments_guard
    BEFORE INSERT OR UPDATE OR DELETE ON staff_assignments
    FOR EACH ROW EXECUTE FUNCTION guard_staff_assignment();

CREATE OR REPLACE FUNCTION guard_staff_qualification()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'staff qualifications cannot be deleted'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.verification_status <> 'PENDING' THEN
            RAISE EXCEPTION 'staff qualifications must begin pending independent review';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.staff_profile_id <> NEW.staff_profile_id OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN RAISE EXCEPTION 'staff qualification identity is immutable'; END IF;
    IF OLD.verification_status <> 'PENDING' THEN RAISE EXCEPTION 'decided staff qualifications are immutable'; END IF;
    IF NEW.verification_status NOT IN ('PENDING','VERIFIED','REJECTED') THEN
        RAISE EXCEPTION 'invalid staff qualification transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER staff_qualifications_guard
    BEFORE INSERT OR UPDATE OR DELETE ON staff_qualifications
    FOR EACH ROW EXECUTE FUNCTION guard_staff_qualification();

CREATE OR REPLACE FUNCTION guard_staff_compliance_check()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'staff compliance evidence cannot be deleted'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'staff compliance evidence must begin pending independent review';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.staff_profile_id <> NEW.staff_profile_id OR OLD.staff_compliance_type_id <> NEW.staff_compliance_type_id
       OR OLD.safe_reference IS DISTINCT FROM NEW.safe_reference
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'staff compliance evidence identity is immutable';
    END IF;
    IF OLD.status = 'PENDING' AND NEW.status NOT IN ('PENDING','VERIFIED','REJECTED','WAIVED') THEN
        RAISE EXCEPTION 'invalid pending staff compliance transition';
    END IF;
    IF OLD.status = 'VERIFIED' AND NEW.status NOT IN ('VERIFIED','EXPIRED') THEN
        RAISE EXCEPTION 'verified staff compliance may only expire';
    END IF;
    IF OLD.status IN ('REJECTED','WAIVED','EXPIRED') THEN
        RAISE EXCEPTION 'terminal staff compliance evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER staff_compliance_checks_guard
    BEFORE INSERT OR UPDATE OR DELETE ON staff_compliance_checks
    FOR EACH ROW EXECUTE FUNCTION guard_staff_compliance_check();

CREATE OR REPLACE FUNCTION guard_staff_public_profile()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'staff public profile revisions cannot be deleted'; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'staff public profile revisions must begin as private drafts';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.staff_profile_id <> NEW.staff_profile_id OR OLD.revision_number <> NEW.revision_number
       OR OLD.supersedes_revision_id IS DISTINCT FROM NEW.supersedes_revision_id
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'staff public profile revision identity is immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT','PENDING_APPROVAL') THEN
        RAISE EXCEPTION 'invalid public staff draft transition';
    END IF;
    IF OLD.status = 'PENDING_APPROVAL' AND NEW.status NOT IN ('PENDING_APPROVAL','PUBLISHED','REJECTED') THEN
        RAISE EXCEPTION 'invalid public staff approval transition';
    END IF;
    IF OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('PUBLISHED','RETIRED') THEN
        RAISE EXCEPTION 'invalid published staff profile transition';
    END IF;
    IF OLD.status IN ('REJECTED','RETIRED') THEN RAISE EXCEPTION 'terminal public staff revisions are immutable'; END IF;
    IF OLD.status <> 'DRAFT' AND (
       OLD.public_display_name <> NEW.public_display_name
       OR OLD.designation_snapshot <> NEW.designation_snapshot
       OR OLD.qualification_summary IS DISTINCT FROM NEW.qualification_summary
       OR OLD.short_bio IS DISTINCT FROM NEW.short_bio
       OR OLD.cbse_staff_category_snapshot IS DISTINCT FROM NEW.cbse_staff_category_snapshot
       OR OLD.display_order <> NEW.display_order) THEN
        RAISE EXCEPTION 'submitted public staff content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER staff_public_profiles_guard
    BEFORE INSERT OR UPDATE OR DELETE ON staff_public_profile_revisions
    FOR EACH ROW EXECUTE FUNCTION guard_staff_public_profile();

CREATE OR REPLACE FUNCTION prevent_staff_history_change()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'staff profile history is append-only'; END;
$$;

CREATE TRIGGER staff_profile_history_append_only
    BEFORE UPDATE OR DELETE ON staff_profile_history
    FOR EACH ROW EXECUTE FUNCTION prevent_staff_history_change();

ALTER TABLE staff_departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_designations ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_qualifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_compliance_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_compliance_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_public_profile_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_profile_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY staff_departments_tenant_select ON staff_departments
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY staff_departments_tenant_insert ON staff_departments
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_departments_tenant_update ON staff_departments
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY staff_designations_tenant_select ON staff_designations
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY staff_designations_tenant_insert ON staff_designations
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_designations_tenant_update ON staff_designations
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY staff_profiles_tenant_select ON staff_profiles
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY staff_profiles_tenant_insert ON staff_profiles
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_profiles_tenant_update ON staff_profiles
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY staff_assignments_tenant_select ON staff_assignments
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY staff_assignments_tenant_insert ON staff_assignments
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_assignments_tenant_update ON staff_assignments
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY staff_qualifications_tenant_select ON staff_qualifications
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY staff_qualifications_tenant_insert ON staff_qualifications
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_qualifications_tenant_update ON staff_qualifications
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY staff_compliance_types_tenant_select ON staff_compliance_types
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY staff_compliance_types_tenant_insert ON staff_compliance_types
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_compliance_types_tenant_update ON staff_compliance_types
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY staff_compliance_checks_tenant_select ON staff_compliance_checks
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY staff_compliance_checks_tenant_insert ON staff_compliance_checks
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_compliance_checks_tenant_update ON staff_compliance_checks
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

CREATE POLICY staff_public_profiles_tenant_select ON staff_public_profile_revisions
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY staff_public_profiles_tenant_insert ON staff_public_profile_revisions
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_public_profiles_tenant_update ON staff_public_profile_revisions
    FOR UPDATE USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_public_profiles_public_select ON staff_public_profile_revisions
    FOR SELECT USING (
        status = 'PUBLISHED' AND EXISTS (
            SELECT 1 FROM schools school
            WHERE school.id = staff_public_profile_revisions.school_id AND school.status = 'ACTIVE'));

CREATE POLICY staff_profile_history_tenant_select ON staff_profile_history
    FOR SELECT USING (organisation_id = current_app_organisation_id());
CREATE POLICY staff_profile_history_tenant_insert ON staff_profile_history
    FOR INSERT WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE staff_profiles IS
    'School-owned ordinary employment identity, separate from optional portal account access and from restricted compliance evidence.';
COMMENT ON TABLE staff_compliance_checks IS
    'Restricted safeguarding/qualification compliance evidence with safe references only; raw documents require a future private HR file context.';
COMMENT ON TABLE staff_public_profile_revisions IS
    'Maker-checker approved public snapshots. No private employment contact, account, compliance note, or source document is exposed.';

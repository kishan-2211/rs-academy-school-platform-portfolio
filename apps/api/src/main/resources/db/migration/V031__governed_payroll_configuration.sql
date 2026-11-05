INSERT INTO capabilities (code, description, risk_level) VALUES
    ('payroll.read', 'Read school-scoped payroll configuration and approved salary profiles', 'RESTRICTED'),
    ('payroll.configure', 'Configure salary components and prepare salary profile revisions', 'RESTRICTED'),
    ('payroll.approve', 'Independently approve or reject submitted salary profile revisions', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'payroll.read'),
    ('00000000-0000-4000-8000-000000000001', 'payroll.configure'),
    ('00000000-0000-4000-8000-000000000001', 'payroll.approve'),
    ('00000000-0000-4000-8000-000000000004', 'payroll.read'),
    ('00000000-0000-4000-8000-000000000004', 'payroll.configure'),
    ('00000000-0000-4000-8000-000000000007', 'payroll.read'),
    ('00000000-0000-4000-8000-000000000007', 'payroll.approve')
ON CONFLICT DO NOTHING;

CREATE TABLE payroll_components (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    code VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    component_kind VARCHAR(30) NOT NULL,
    calculation_method VARCHAR(30) NOT NULL,
    is_basic BOOLEAN NOT NULL DEFAULT FALSE,
    attendance_prorated BOOLEAN NOT NULL DEFAULT FALSE,
    statutory BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(500),
    display_order SMALLINT NOT NULL DEFAULT 100,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payroll_components_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_components_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT payroll_components_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_components_code_unique UNIQUE (school_id, code),
    CONSTRAINT payroll_components_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT payroll_components_name_valid CHECK (length(btrim(display_name)) BETWEEN 2 AND 120),
    CONSTRAINT payroll_components_kind_valid CHECK (
        component_kind IN ('EARNING','DEDUCTION','EMPLOYER_CONTRIBUTION')),
    CONSTRAINT payroll_components_method_valid CHECK (
        calculation_method IN ('FIXED','PERCENT_OF_BASIC')),
    CONSTRAINT payroll_components_basic_valid CHECK (
        NOT is_basic OR (component_kind = 'EARNING' AND calculation_method = 'FIXED'
            AND statutory = FALSE)),
    CONSTRAINT payroll_components_proration_valid CHECK (
        NOT attendance_prorated OR component_kind = 'EARNING'),
    CONSTRAINT payroll_components_percentage_valid CHECK (
        calculation_method <> 'PERCENT_OF_BASIC' OR is_basic = FALSE),
    CONSTRAINT payroll_components_order_valid CHECK (display_order BETWEEN 0 AND 1000),
    CONSTRAINT payroll_components_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE UNIQUE INDEX payroll_components_one_active_basic
    ON payroll_components(school_id) WHERE is_basic AND status = 'ACTIVE';
CREATE INDEX payroll_components_school_order
    ON payroll_components(school_id, status, display_order, display_name, id);

CREATE TABLE staff_salary_profiles (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    supersedes_profile_id UUID,
    revision_number INTEGER NOT NULL,
    effective_from DATE NOT NULL,
    currency_code CHAR(3) NOT NULL DEFAULT 'INR',
    pay_frequency VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    creation_reason VARCHAR(500) NOT NULL,
    created_by_membership_id UUID NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    submission_reason VARCHAR(500),
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT staff_salary_profiles_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_salary_profiles_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT staff_salary_profiles_staff_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_salary_profiles_supersedes_fk
        FOREIGN KEY (supersedes_profile_id, organisation_id, school_id)
        REFERENCES staff_salary_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_salary_profiles_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_salary_profiles_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_salary_profiles_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_salary_profiles_revision_valid CHECK (revision_number BETWEEN 1 AND 10000),
    CONSTRAINT staff_salary_profiles_supersedes_valid CHECK (
        (revision_number = 1 AND supersedes_profile_id IS NULL)
        OR (revision_number > 1 AND supersedes_profile_id IS NOT NULL AND supersedes_profile_id <> id)),
    CONSTRAINT staff_salary_profiles_currency_valid CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT staff_salary_profiles_frequency_valid CHECK (pay_frequency = 'MONTHLY'),
    CONSTRAINT staff_salary_profiles_status_valid CHECK (
        status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','SUPERSEDED')),
    CONSTRAINT staff_salary_profiles_creation_reason_valid CHECK (
        length(btrim(creation_reason)) BETWEEN 3 AND 500),
    CONSTRAINT staff_salary_profiles_lifecycle_valid CHECK (
        (status = 'DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND submission_reason IS NULL AND decided_by_membership_id IS NULL
            AND decided_at IS NULL AND decision_reason IS NULL)
        OR (status = 'SUBMITTED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL)
        OR (status IN ('APPROVED','REJECTED') AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> created_by_membership_id
            AND decided_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500)
        OR (status = 'SUPERSEDED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> created_by_membership_id
            AND decided_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500))
);

CREATE UNIQUE INDEX staff_salary_profiles_one_editable
    ON staff_salary_profiles(staff_profile_id) WHERE status IN ('DRAFT','SUBMITTED');
CREATE UNIQUE INDEX staff_salary_profiles_one_current
    ON staff_salary_profiles(staff_profile_id) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX staff_salary_profiles_revision_unique
    ON staff_salary_profiles(staff_profile_id, revision_number) WHERE status <> 'REJECTED';
CREATE INDEX staff_salary_profiles_school_effective
    ON staff_salary_profiles(school_id, effective_from DESC, status, staff_profile_id, id);

CREATE TABLE staff_salary_profile_lines (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    salary_profile_id UUID NOT NULL,
    payroll_component_id UUID NOT NULL,
    component_code_snapshot VARCHAR(40) NOT NULL,
    component_name_snapshot VARCHAR(120) NOT NULL,
    component_kind_snapshot VARCHAR(30) NOT NULL,
    calculation_method_snapshot VARCHAR(30) NOT NULL,
    is_basic_snapshot BOOLEAN NOT NULL,
    attendance_prorated_snapshot BOOLEAN NOT NULL,
    statutory_snapshot BOOLEAN NOT NULL,
    fixed_amount NUMERIC(14,2),
    percentage_rate NUMERIC(7,4),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT staff_salary_profile_lines_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT staff_salary_profile_lines_profile_fk
        FOREIGN KEY (salary_profile_id, organisation_id, school_id)
        REFERENCES staff_salary_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_salary_profile_lines_component_fk
        FOREIGN KEY (payroll_component_id, organisation_id, school_id)
        REFERENCES payroll_components(id, organisation_id, school_id),
    CONSTRAINT staff_salary_profile_lines_component_unique
        UNIQUE (salary_profile_id, payroll_component_id),
    CONSTRAINT staff_salary_profile_lines_kind_valid CHECK (
        component_kind_snapshot IN ('EARNING','DEDUCTION','EMPLOYER_CONTRIBUTION')),
    CONSTRAINT staff_salary_profile_lines_method_valid CHECK (
        calculation_method_snapshot IN ('FIXED','PERCENT_OF_BASIC')),
    CONSTRAINT staff_salary_profile_lines_amount_valid CHECK (
        (calculation_method_snapshot = 'FIXED' AND fixed_amount > 0
            AND fixed_amount <= 1000000000000 AND percentage_rate IS NULL)
        OR (calculation_method_snapshot = 'PERCENT_OF_BASIC' AND fixed_amount IS NULL
            AND percentage_rate > 0 AND percentage_rate <= 1000)),
    CONSTRAINT staff_salary_profile_lines_snapshot_basic_valid CHECK (
        NOT is_basic_snapshot OR (component_kind_snapshot = 'EARNING'
            AND calculation_method_snapshot = 'FIXED' AND statutory_snapshot = FALSE)),
    CONSTRAINT staff_salary_profile_lines_snapshot_proration_valid CHECK (
        NOT attendance_prorated_snapshot OR component_kind_snapshot = 'EARNING')
);

CREATE INDEX staff_salary_profile_lines_profile_order
    ON staff_salary_profile_lines(salary_profile_id, component_kind_snapshot,
        component_code_snapshot, id);

CREATE TABLE staff_salary_profile_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    salary_profile_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT staff_salary_profile_history_profile_fk
        FOREIGN KEY (salary_profile_id, organisation_id, school_id)
        REFERENCES staff_salary_profiles(id, organisation_id, school_id),
    CONSTRAINT staff_salary_profile_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT staff_salary_profile_history_reason_valid CHECK (
        length(btrim(reason)) BETWEEN 3 AND 500)
);

CREATE INDEX staff_salary_profile_history_profile_time
    ON staff_salary_profile_history(salary_profile_id, occurred_at, id);

CREATE OR REPLACE FUNCTION validate_staff_salary_profile_context()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE staff_record staff_profiles%ROWTYPE;
DECLARE previous_record staff_salary_profiles%ROWTYPE;
BEGIN
    SELECT * INTO staff_record FROM staff_profiles
    WHERE id = NEW.staff_profile_id AND organisation_id = NEW.organisation_id
      AND school_id = NEW.school_id;
    IF staff_record.id IS NULL OR staff_record.status = 'ENDED'
       OR NEW.effective_from < staff_record.joined_on
       OR (staff_record.ended_on IS NOT NULL AND NEW.effective_from > staff_record.ended_on) THEN
        RAISE EXCEPTION 'salary profile requires eligible same-school staff and an employment date';
    END IF;
    IF NEW.supersedes_profile_id IS NOT NULL THEN
        SELECT * INTO previous_record FROM staff_salary_profiles
        WHERE id = NEW.supersedes_profile_id AND organisation_id = NEW.organisation_id
          AND school_id = NEW.school_id AND staff_profile_id = NEW.staff_profile_id;
        IF previous_record.id IS NULL OR previous_record.status NOT IN ('APPROVED','SUPERSEDED')
           OR NEW.revision_number <> previous_record.revision_number + 1
           OR NEW.effective_from <= previous_record.effective_from THEN
            RAISE EXCEPTION 'salary revision must supersede the prior approved profile in order';
        END IF;
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER staff_salary_profiles_validate_context
    BEFORE INSERT OR UPDATE ON staff_salary_profiles
    FOR EACH ROW EXECUTE FUNCTION validate_staff_salary_profile_context();

CREATE OR REPLACE FUNCTION guard_staff_salary_profile_line()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE profile_state VARCHAR(20);
DECLARE component_record payroll_components%ROWTYPE;
DECLARE target_profile UUID;
DECLARE target_organisation UUID;
DECLARE target_school UUID;
BEGIN
    target_profile := CASE WHEN TG_OP = 'DELETE' THEN OLD.salary_profile_id ELSE NEW.salary_profile_id END;
    target_organisation := CASE WHEN TG_OP = 'DELETE' THEN OLD.organisation_id ELSE NEW.organisation_id END;
    target_school := CASE WHEN TG_OP = 'DELETE' THEN OLD.school_id ELSE NEW.school_id END;
    SELECT status INTO profile_state FROM staff_salary_profiles
    WHERE id = target_profile AND organisation_id = target_organisation
      AND school_id = target_school;
    IF profile_state <> 'DRAFT' THEN
        RAISE EXCEPTION 'salary profile lines are editable only while the profile is a draft';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        SELECT * INTO component_record FROM payroll_components
        WHERE id = NEW.payroll_component_id AND organisation_id = NEW.organisation_id
          AND school_id = NEW.school_id AND status = 'ACTIVE';
        IF component_record.id IS NULL
           OR NEW.component_code_snapshot <> component_record.code
           OR NEW.component_name_snapshot <> component_record.display_name
           OR NEW.component_kind_snapshot <> component_record.component_kind
           OR NEW.calculation_method_snapshot <> component_record.calculation_method
           OR NEW.is_basic_snapshot <> component_record.is_basic
           OR NEW.attendance_prorated_snapshot <> component_record.attendance_prorated
           OR NEW.statutory_snapshot <> component_record.statutory THEN
            RAISE EXCEPTION 'salary line requires the current active component snapshot';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END; $$;

CREATE TRIGGER staff_salary_profile_lines_guard
    BEFORE INSERT OR UPDATE OR DELETE ON staff_salary_profile_lines
    FOR EACH ROW EXECUTE FUNCTION guard_staff_salary_profile_line();

CREATE OR REPLACE FUNCTION validate_complete_salary_profile(
    profile_id UUID, profile_organisation_id UUID, profile_school_id UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE line_count INTEGER;
DECLARE basic_count INTEGER;
DECLARE earning_count INTEGER;
DECLARE basic_amount NUMERIC(14,2);
DECLARE gross_amount NUMERIC(18,2);
DECLARE deduction_amount NUMERIC(18,2);
BEGIN
    SELECT count(*), count(*) FILTER (WHERE is_basic_snapshot),
           count(*) FILTER (WHERE component_kind_snapshot = 'EARNING'),
           max(fixed_amount) FILTER (WHERE is_basic_snapshot)
      INTO line_count, basic_count, earning_count, basic_amount
    FROM staff_salary_profile_lines WHERE salary_profile_id = profile_id
      AND organisation_id = profile_organisation_id AND school_id = profile_school_id;
    IF line_count < 1 OR line_count > 100 OR basic_count <> 1 OR earning_count < 1 THEN
        RAISE EXCEPTION 'salary profile requires one basic earning and at least one earning line';
    END IF;
    SELECT COALESCE(sum(CASE WHEN calculation_method_snapshot = 'FIXED' THEN fixed_amount
                    ELSE basic_amount * percentage_rate / 100 END)
               FILTER (WHERE component_kind_snapshot = 'EARNING'), 0),
           COALESCE(sum(CASE WHEN calculation_method_snapshot = 'FIXED' THEN fixed_amount
                    ELSE basic_amount * percentage_rate / 100 END)
               FILTER (WHERE component_kind_snapshot = 'DEDUCTION'), 0)
      INTO gross_amount, deduction_amount
    FROM staff_salary_profile_lines WHERE salary_profile_id = profile_id
      AND organisation_id = profile_organisation_id AND school_id = profile_school_id;
    IF basic_amount IS NULL OR gross_amount <= 0 OR deduction_amount > gross_amount THEN
        RAISE EXCEPTION 'salary profile deductions cannot exceed configured earnings';
    END IF;
END; $$;

CREATE OR REPLACE FUNCTION guard_staff_salary_profile()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE successor_count INTEGER;
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.staff_profile_id <> NEW.staff_profile_id
       OR OLD.supersedes_profile_id IS DISTINCT FROM NEW.supersedes_profile_id
       OR OLD.revision_number <> NEW.revision_number
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'salary profile identity and effective evidence are immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'DRAFT' THEN RETURN NEW; END IF;
    IF OLD.effective_from <> NEW.effective_from OR OLD.currency_code <> NEW.currency_code
       OR OLD.pay_frequency <> NEW.pay_frequency OR OLD.creation_reason <> NEW.creation_reason THEN
        RAISE EXCEPTION 'submitted salary profile terms are immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED' THEN
        PERFORM validate_complete_salary_profile(NEW.id, NEW.organisation_id, NEW.school_id);
        RETURN NEW;
    END IF;
    IF OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED','REJECTED') THEN
        PERFORM validate_complete_salary_profile(NEW.id, NEW.organisation_id, NEW.school_id);
        IF NEW.decided_by_membership_id = NEW.created_by_membership_id THEN
            RAISE EXCEPTION 'salary profile approval requires an independent actor';
        END IF;
        IF NEW.status = 'APPROVED' AND NEW.supersedes_profile_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM staff_salary_profiles previous
               WHERE previous.id = NEW.supersedes_profile_id AND previous.status = 'SUPERSEDED') THEN
            RAISE EXCEPTION 'salary profile approval must supersede its prior approved revision';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'APPROVED' AND NEW.status = 'SUPERSEDED' THEN
        SELECT count(*) INTO successor_count FROM staff_salary_profiles successor
        WHERE successor.supersedes_profile_id = OLD.id
          AND successor.organisation_id = OLD.organisation_id
          AND successor.school_id = OLD.school_id
          AND successor.status = 'SUBMITTED';
        IF successor_count <> 1 THEN
            RAISE EXCEPTION 'approved salary profiles are superseded only by their submitted successor';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid salary profile transition';
END; $$;

CREATE TRIGGER staff_salary_profiles_guard_history
    BEFORE UPDATE ON staff_salary_profiles
    FOR EACH ROW EXECUTE FUNCTION guard_staff_salary_profile();

CREATE OR REPLACE FUNCTION prevent_payroll_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'payroll configuration history is append-only'; END; $$;

CREATE TRIGGER staff_salary_profile_history_immutable
    BEFORE UPDATE OR DELETE ON staff_salary_profile_history
    FOR EACH ROW EXECUTE FUNCTION prevent_payroll_evidence_mutation();
CREATE TRIGGER staff_salary_profiles_no_delete
    BEFORE DELETE ON staff_salary_profiles
    FOR EACH ROW EXECUTE FUNCTION prevent_payroll_evidence_mutation();
CREATE TRIGGER payroll_components_no_delete
    BEFORE DELETE ON payroll_components
    FOR EACH ROW EXECUTE FUNCTION prevent_payroll_evidence_mutation();

ALTER TABLE payroll_components ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_salary_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_salary_profile_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_salary_profile_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY payroll_components_tenant_all ON payroll_components
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_salary_profiles_tenant_all ON staff_salary_profiles
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_salary_profile_lines_tenant_all ON staff_salary_profile_lines
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY staff_salary_profile_history_tenant_all ON staff_salary_profile_history
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE payroll_components IS
    'School-configurable earning, deduction, and employer contribution rules. Statutory rates are configuration, never hard-coded law.';
COMMENT ON TABLE staff_salary_profiles IS
    'Effective-dated, maker-checker salary revisions. Approved history is superseded rather than rewritten.';
COMMENT ON TABLE staff_salary_profile_lines IS
    'Frozen component snapshots and input values that make later payroll calculations historically reproducible.';
COMMENT ON TABLE staff_salary_profile_history IS
    'Append-only salary profile lifecycle evidence.';

-- School-owned workflow settings, not a certification of statutory RTE compliance.
ALTER TABLE academic_sessions
    ADD COLUMN rte_class_one_seat_limit INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN rte_min_age_years INTEGER NOT NULL DEFAULT 6,
    ADD COLUMN rte_max_age_years INTEGER NOT NULL DEFAULT 14,
    ADD CONSTRAINT academic_sessions_rte_policy_valid CHECK (
        rte_class_one_seat_limit BETWEEN 0 AND 1000
        AND rte_min_age_years BETWEEN 0 AND 18
        AND rte_max_age_years BETWEEN rte_min_age_years AND 18);

ALTER TABLE admission_applications
    ADD COLUMN admission_type VARCHAR(10) NOT NULL DEFAULT 'REGULAR',
    ADD COLUMN rte_reference VARCHAR(100),
    ADD CONSTRAINT admission_applications_rte_valid CHECK (
        (admission_type = 'REGULAR' AND rte_reference IS NULL)
        OR (admission_type = 'RTE' AND target_grade_code = 'I'
            AND target_academic_session_id IS NOT NULL
            AND (rte_reference IS NULL OR length(btrim(rte_reference)) BETWEEN 1 AND 100)));

ALTER TABLE learners
    ADD COLUMN admission_type VARCHAR(10) NOT NULL DEFAULT 'REGULAR',
    ADD COLUMN rte_admission_session_id UUID,
    ADD COLUMN rte_reference VARCHAR(100),
    ADD CONSTRAINT learners_rte_session_fk FOREIGN KEY (rte_admission_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    ADD CONSTRAINT learners_rte_valid CHECK (
        (admission_type = 'REGULAR' AND rte_admission_session_id IS NULL AND rte_reference IS NULL)
        OR (admission_type = 'RTE' AND rte_admission_session_id IS NOT NULL
            AND (rte_reference IS NULL OR length(btrim(rte_reference)) BETWEEN 1 AND 100)));
CREATE INDEX learners_rte_session_idx ON learners (organisation_id, school_id, rte_admission_session_id)
    WHERE admission_type = 'RTE';
CREATE UNIQUE INDEX learners_rte_reference_unique ON learners (school_id, lower(btrim(rte_reference)))
    WHERE rte_reference IS NOT NULL;

CREATE FUNCTION guard_rte_application() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE session_row academic_sessions%ROWTYPE; age_years INTEGER;
BEGIN
    IF TG_OP = 'UPDATE' AND EXISTS (SELECT 1 FROM learners WHERE origin_application_id = OLD.id)
       AND (NEW.admission_type IS DISTINCT FROM OLD.admission_type
            OR NEW.rte_reference IS DISTINCT FROM OLD.rte_reference
            OR (OLD.admission_type = 'RTE'
                AND (NEW.target_academic_session_id IS DISTINCT FROM OLD.target_academic_session_id
                     OR NEW.target_grade_code IS DISTINCT FROM OLD.target_grade_code))) THEN
        RAISE EXCEPTION 'original admission classification is preserved after enrolment';
    END IF;
    IF NEW.admission_type = 'RTE' THEN
        SELECT * INTO session_row FROM academic_sessions
        WHERE id = NEW.target_academic_session_id AND organisation_id = NEW.organisation_id
          AND school_id = NEW.school_id;
        age_years := extract(year FROM age(session_row.starts_on, NEW.birth_date));
        IF session_row.id IS NULL OR NEW.target_grade_code IS DISTINCT FROM 'I'
           OR age_years NOT BETWEEN session_row.rte_min_age_years AND session_row.rte_max_age_years THEN
            RAISE EXCEPTION 'RTE requires Class I, a scoped session and the configured age at session start';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER admission_applications_rte_guard BEFORE INSERT OR UPDATE ON admission_applications
    FOR EACH ROW EXECUTE FUNCTION guard_rte_application();

CREATE FUNCTION guard_rte_learner() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE application admission_applications%ROWTYPE; session_row academic_sessions%ROWTYPE; used_seats BIGINT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.admission_type, NEW.rte_admission_session_id, NEW.rte_reference)
            IS DISTINCT FROM (OLD.admission_type, OLD.rte_admission_session_id, OLD.rte_reference) THEN
            RAISE EXCEPTION 'original RTE admission evidence cannot be silently changed';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO application FROM admission_applications WHERE id = NEW.origin_application_id
        AND organisation_id = NEW.organisation_id AND school_id = NEW.school_id;
    IF application.admission_type = 'RTE' THEN
        SELECT * INTO session_row FROM academic_sessions WHERE id = application.target_academic_session_id
            AND organisation_id = NEW.organisation_id AND school_id = NEW.school_id FOR UPDATE;
        SELECT count(*) INTO used_seats FROM learners WHERE organisation_id = NEW.organisation_id
            AND school_id = NEW.school_id AND admission_type = 'RTE'
            AND rte_admission_session_id = session_row.id;
        IF session_row.id IS NULL OR used_seats >= session_row.rte_class_one_seat_limit THEN
            RAISE EXCEPTION 'configured RTE admission seats are exhausted';
        END IF;
        NEW.admission_type := 'RTE';
        NEW.rte_admission_session_id := session_row.id;
        NEW.rte_reference := application.rte_reference;
    ELSIF NEW.admission_type IS DISTINCT FROM 'REGULAR' THEN
        RAISE EXCEPTION 'RTE classification requires its original application';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER learners_rte_guard BEFORE INSERT OR UPDATE ON learners
    FOR EACH ROW EXECUTE FUNCTION guard_rte_learner();

CREATE FUNCTION guard_rte_session_policy() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.rte_class_one_seat_limit < (SELECT count(*) FROM learners
        WHERE organisation_id = OLD.organisation_id AND school_id = OLD.school_id
          AND rte_admission_session_id = OLD.id AND admission_type = 'RTE') THEN
        RAISE EXCEPTION 'RTE seat limit cannot be lower than completed original admissions';
    END IF;
    IF (NEW.starts_on, NEW.rte_min_age_years, NEW.rte_max_age_years)
        IS DISTINCT FROM (OLD.starts_on, OLD.rte_min_age_years, OLD.rte_max_age_years)
        AND EXISTS (SELECT 1 FROM learners WHERE rte_admission_session_id = OLD.id) THEN
        RAISE EXCEPTION 'RTE admission age policy is frozen once original admissions exist';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER academic_sessions_rte_policy_guard BEFORE UPDATE ON academic_sessions
    FOR EACH ROW EXECUTE FUNCTION guard_rte_session_policy();

ALTER TABLE learner_fee_charges
    ADD COLUMN fee_head_category_snapshot VARCHAR(30),
    ADD COLUMN rte_exemption_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT learner_fee_charges_rte_amount_valid CHECK (
        rte_exemption_amount = 0 OR rte_exemption_amount = amount);

CREATE FUNCTION snapshot_rte_fee_exemption() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE category_code VARCHAR(30); eligible BOOLEAN;
BEGIN
    SELECT head.category, learner.admission_type = 'RTE'
        AND enrolment.grade_code IN ('I','II','III','IV','V','VI','VII','VIII')
    INTO category_code, eligible
    FROM learner_fee_accounts account
    JOIN learners learner ON learner.id = account.learner_id
        AND learner.organisation_id = account.organisation_id AND learner.school_id = account.school_id
    JOIN student_enrolments enrolment ON enrolment.id = account.enrolment_id
        AND enrolment.organisation_id = account.organisation_id AND enrolment.school_id = account.school_id
        AND enrolment.learner_id = learner.id AND enrolment.academic_session_id = account.academic_session_id
    JOIN fee_plan_items item ON item.id = NEW.fee_plan_item_id
        AND item.organisation_id = account.organisation_id AND item.school_id = account.school_id
    JOIN fee_heads head ON head.id = item.fee_head_id
        AND head.organisation_id = account.organisation_id AND head.school_id = account.school_id
    WHERE account.id = NEW.learner_fee_account_id AND account.organisation_id = NEW.organisation_id
        AND account.school_id = NEW.school_id;
    IF category_code IS NULL THEN RAISE EXCEPTION 'fee charge must have scoped category evidence'; END IF;
    NEW.fee_head_category_snapshot := category_code;
    NEW.rte_exemption_amount := CASE WHEN eligible AND category_code IN ('ADMISSION','TUITION') THEN NEW.amount ELSE 0 END;
    RETURN NEW;
END; $$;
CREATE TRIGGER learner_fee_charges_rte_snapshot BEFORE INSERT ON learner_fee_charges
    FOR EACH ROW EXECUTE FUNCTION snapshot_rte_fee_exemption();

CREATE UNIQUE INDEX fee_ledger_rte_exemption_unique ON fee_ledger_entries (learner_fee_charge_id)
    WHERE source_type = 'RTE_EXEMPTION';
CREATE FUNCTION guard_rte_fee_ledger() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE charge learner_fee_charges%ROWTYPE;
BEGIN
    IF NEW.source_type = 'RTE_EXEMPTION' THEN
        SELECT * INTO charge FROM learner_fee_charges WHERE id = NEW.learner_fee_charge_id
            AND organisation_id = NEW.organisation_id AND school_id = NEW.school_id
            AND learner_fee_account_id = NEW.learner_fee_account_id;
        IF charge.id IS NULL OR charge.rte_exemption_amount <= 0
            OR NEW.amount IS DISTINCT FROM charge.rte_exemption_amount
            OR NEW.entry_type IS DISTINCT FROM 'CONCESSION' OR NEW.direction IS DISTINCT FROM 'CREDIT'
            OR NEW.source_id IS DISTINCT FROM charge.id OR NEW.reverses_entry_id IS NOT NULL
            OR NEW.effective_on IS DISTINCT FROM charge.due_on THEN
            RAISE EXCEPTION 'RTE exemption must match its immutable eligible charge';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_ledger_entries_rte_guard BEFORE INSERT ON fee_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION guard_rte_fee_ledger();

CREATE FUNCTION require_rte_fee_exemption() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.rte_exemption_amount > 0 AND NOT EXISTS (
        SELECT 1 FROM fee_ledger_entries WHERE learner_fee_charge_id = NEW.id
            AND organisation_id = NEW.organisation_id AND school_id = NEW.school_id
            AND learner_fee_account_id = NEW.learner_fee_account_id
            AND source_type = 'RTE_EXEMPTION' AND amount = NEW.rte_exemption_amount) THEN
        RAISE EXCEPTION 'eligible RTE charge and exemption must commit atomically';
    END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER learner_fee_charges_rte_complete AFTER INSERT ON learner_fee_charges
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_rte_fee_exemption();

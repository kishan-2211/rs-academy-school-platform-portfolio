INSERT INTO capabilities (code, description, risk_level) VALUES
    ('payroll.advance.prepare', 'Prepare, revise, submit, cancel, and disburse school-scoped employee advances or loans', 'RESTRICTED'),
    ('payroll.advance.approve', 'Independently approve or reject submitted employee advances or loans', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'payroll.advance.prepare'),
    ('00000000-0000-4000-8000-000000000001', 'payroll.advance.approve'),
    ('00000000-0000-4000-8000-000000000004', 'payroll.advance.prepare'),
    ('00000000-0000-4000-8000-000000000007', 'payroll.advance.approve')
ON CONFLICT DO NOTHING;

ALTER TABLE accounting_journals DROP CONSTRAINT accounting_journals_source_valid;
ALTER TABLE accounting_journals ADD CONSTRAINT accounting_journals_source_valid CHECK (
    source_module IN ('MANUAL','FEES','PAYROLL','PAYROLL_ADVANCE','EXPENSE','OPENING','REVERSAL'));

CREATE TABLE payroll_employee_advances (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    advance_number VARCHAR(50) NOT NULL,
    advance_type VARCHAR(20) NOT NULL,
    purpose VARCHAR(500) NOT NULL,
    financial_year_id UUID NOT NULL,
    accounting_period_id UUID NOT NULL,
    issue_date DATE NOT NULL,
    currency_code CHAR(3) NOT NULL,
    principal_amount NUMERIC(16,2) NOT NULL,
    recovery_start_month DATE NOT NULL,
    installment_count SMALLINT NOT NULL,
    receivable_account_id UUID NOT NULL,
    disbursement_account_id UUID NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    payment_reference VARCHAR(120),
    employee_code_snapshot VARCHAR(50) NOT NULL,
    staff_name_snapshot VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    creation_reason VARCHAR(500) NOT NULL,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    submission_reason VARCHAR(500),
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    cancelled_by_membership_id UUID,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payroll_employee_advances_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advances_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT payroll_employee_advances_staff_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advances_year_fk
        FOREIGN KEY (financial_year_id, organisation_id, school_id)
        REFERENCES accounting_financial_years(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advances_period_fk
        FOREIGN KEY (accounting_period_id, organisation_id, school_id)
        REFERENCES accounting_periods(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advances_receivable_fk
        FOREIGN KEY (receivable_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advances_disbursement_fk
        FOREIGN KEY (disbursement_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advances_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advances_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advances_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advances_canceller_fk
        FOREIGN KEY (cancelled_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advances_number_unique UNIQUE (school_id, advance_number),
    CONSTRAINT payroll_employee_advances_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT payroll_employee_advances_number_valid CHECK (advance_number ~ '^EPA-[0-9]{6}-[0-9]{3}$'),
    CONSTRAINT payroll_employee_advances_type_valid CHECK (advance_type IN ('ADVANCE','LOAN')),
    CONSTRAINT payroll_employee_advances_purpose_valid CHECK (length(btrim(purpose)) BETWEEN 3 AND 500),
    CONSTRAINT payroll_employee_advances_currency_valid CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT payroll_employee_advances_amount_valid CHECK (
        principal_amount > 0 AND principal_amount <= 1000000000000),
    CONSTRAINT payroll_employee_advances_recovery_month_valid CHECK (
        extract(day FROM recovery_start_month) = 1
        AND recovery_start_month >= date_trunc('month', issue_date)::date),
    CONSTRAINT payroll_employee_advances_installments_valid CHECK (
        installment_count BETWEEN 1 AND 120 AND principal_amount >= installment_count * 0.01),
    CONSTRAINT payroll_employee_advances_accounts_distinct CHECK (
        receivable_account_id <> disbursement_account_id),
    CONSTRAINT payroll_employee_advances_method_valid CHECK (
        payment_method IN ('CASH','BANK_TRANSFER','CHEQUE','SCHOOL_UPI')),
    CONSTRAINT payroll_employee_advances_reference_valid CHECK (
        (payment_method = 'CASH' AND (payment_reference IS NULL OR length(btrim(payment_reference)) BETWEEN 2 AND 120))
        OR (payment_method <> 'CASH' AND payment_reference IS NOT NULL
            AND length(btrim(payment_reference)) BETWEEN 2 AND 120)),
    CONSTRAINT payroll_employee_advances_status_valid CHECK (
        status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT payroll_employee_advances_idempotency_valid CHECK (
        length(btrim(idempotency_key)) BETWEEN 8 AND 100),
    CONSTRAINT payroll_employee_advances_fingerprint_valid CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_employee_advances_creation_reason_valid CHECK (
        length(btrim(creation_reason)) BETWEEN 3 AND 500),
    CONSTRAINT payroll_employee_advances_lifecycle_valid CHECK (
        (status = 'DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND submission_reason IS NULL AND decided_by_membership_id IS NULL
            AND decided_at IS NULL AND decision_reason IS NULL
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL
            AND cancellation_reason IS NULL)
        OR (status = 'SUBMITTED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND submission_reason IS NOT NULL
            AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL
            AND cancellation_reason IS NULL)
        OR (status IN ('APPROVED','REJECTED') AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND submission_reason IS NOT NULL
            AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> created_by_membership_id
            AND decided_by_membership_id <> submitted_by_membership_id
            AND decided_at IS NOT NULL AND decision_reason IS NOT NULL
            AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL
            AND cancellation_reason IS NULL)
        OR (status = 'CANCELLED' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND submission_reason IS NULL AND decided_by_membership_id IS NULL
            AND decided_at IS NULL AND decision_reason IS NULL
            AND cancelled_by_membership_id IS NOT NULL AND cancelled_at IS NOT NULL
            AND cancellation_reason IS NOT NULL
            AND length(btrim(cancellation_reason)) BETWEEN 3 AND 500))
);

CREATE INDEX payroll_employee_advances_school_status_idx
    ON payroll_employee_advances(organisation_id, school_id, status, issue_date DESC, id);
CREATE INDEX payroll_employee_advances_staff_idx
    ON payroll_employee_advances(organisation_id, school_id, staff_profile_id, issue_date DESC, id);

CREATE TABLE payroll_employee_advance_installments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    employee_advance_id UUID NOT NULL,
    installment_number SMALLINT NOT NULL,
    due_month DATE NOT NULL,
    planned_amount NUMERIC(16,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payroll_employee_advance_installments_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advance_installments_advance_fk
        FOREIGN KEY (employee_advance_id, organisation_id, school_id)
        REFERENCES payroll_employee_advances(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advance_installments_sequence_unique
        UNIQUE (employee_advance_id, installment_number),
    CONSTRAINT payroll_employee_advance_installments_month_unique UNIQUE (employee_advance_id, due_month),
    CONSTRAINT payroll_employee_advance_installments_number_valid CHECK (installment_number BETWEEN 1 AND 120),
    CONSTRAINT payroll_employee_advance_installments_month_valid CHECK (extract(day FROM due_month) = 1),
    CONSTRAINT payroll_employee_advance_installments_amount_valid CHECK (
        planned_amount > 0 AND planned_amount <= 1000000000000)
);

CREATE INDEX payroll_employee_advance_installments_due_idx
    ON payroll_employee_advance_installments(organisation_id, school_id, due_month, employee_advance_id);

CREATE TABLE payroll_employee_advance_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    employee_advance_id UUID NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    evidence_snapshot JSONB NOT NULL,
    CONSTRAINT payroll_employee_advance_history_advance_fk
        FOREIGN KEY (employee_advance_id, organisation_id, school_id)
        REFERENCES payroll_employee_advances(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advance_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advance_history_event_valid CHECK (
        event_type IN ('CREATED','UPDATED','SUBMITTED','APPROVED','REJECTED','CANCELLED','DISBURSEMENT_PREPARED')),
    CONSTRAINT payroll_employee_advance_history_status_valid CHECK (
        (previous_status IS NULL OR previous_status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED'))
        AND new_status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED')),
    CONSTRAINT payroll_employee_advance_history_reason_valid CHECK (length(btrim(reason)) BETWEEN 3 AND 500),
    CONSTRAINT payroll_employee_advance_history_evidence_valid CHECK (jsonb_typeof(evidence_snapshot) = 'object')
);

CREATE INDEX payroll_employee_advance_history_time_idx
    ON payroll_employee_advance_history(employee_advance_id, occurred_at, id);

CREATE TABLE payroll_employee_advance_postings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    employee_advance_id UUID NOT NULL,
    financial_year_id UUID NOT NULL,
    accounting_period_id UUID NOT NULL,
    posting_date DATE NOT NULL,
    sequence_number SMALLINT NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    payment_reference VARCHAR(120),
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    source_amount NUMERIC(16,2) NOT NULL,
    evidence_snapshot JSONB NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    accounting_journal_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    preparation_reason VARCHAR(500) NOT NULL,
    prepared_by_membership_id UUID NOT NULL,
    prepared_at TIMESTAMPTZ NOT NULL,
    journal_linked_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payroll_employee_advance_postings_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advance_postings_advance_fk
        FOREIGN KEY (employee_advance_id, organisation_id, school_id)
        REFERENCES payroll_employee_advances(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advance_postings_year_fk
        FOREIGN KEY (financial_year_id, organisation_id, school_id)
        REFERENCES accounting_financial_years(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advance_postings_period_fk
        FOREIGN KEY (accounting_period_id, organisation_id, school_id)
        REFERENCES accounting_periods(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advance_postings_journal_fk
        FOREIGN KEY (accounting_journal_id, organisation_id, school_id)
        REFERENCES accounting_journals(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advance_postings_preparer_fk
        FOREIGN KEY (prepared_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_employee_advance_postings_sequence_unique UNIQUE (employee_advance_id, sequence_number),
    CONSTRAINT payroll_employee_advance_postings_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT payroll_employee_advance_postings_sequence_valid CHECK (sequence_number BETWEEN 1 AND 100),
    CONSTRAINT payroll_employee_advance_postings_method_valid CHECK (
        payment_method IN ('CASH','BANK_TRANSFER','CHEQUE','SCHOOL_UPI')),
    CONSTRAINT payroll_employee_advance_postings_reference_valid CHECK (
        (payment_method = 'CASH' AND (payment_reference IS NULL OR length(btrim(payment_reference)) BETWEEN 2 AND 120))
        OR (payment_method <> 'CASH' AND payment_reference IS NOT NULL
            AND length(btrim(payment_reference)) BETWEEN 2 AND 120)),
    CONSTRAINT payroll_employee_advance_postings_idempotency_valid CHECK (
        length(btrim(idempotency_key)) BETWEEN 8 AND 100),
    CONSTRAINT payroll_employee_advance_postings_fingerprint_valid CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_employee_advance_postings_amount_valid CHECK (
        source_amount > 0 AND source_amount <= 1000000000000),
    CONSTRAINT payroll_employee_advance_postings_evidence_valid CHECK (
        jsonb_typeof(evidence_snapshot) = 'object' AND evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_employee_advance_postings_status_valid CHECK (status IN ('ACTIVE','RELEASED')),
    CONSTRAINT payroll_employee_advance_postings_reason_valid CHECK (
        length(btrim(preparation_reason)) BETWEEN 3 AND 500),
    CONSTRAINT payroll_employee_advance_postings_link_valid CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status = 'RELEASED' AND accounting_journal_id IS NOT NULL AND released_at IS NOT NULL))
);

CREATE UNIQUE INDEX payroll_employee_advance_postings_one_active_idx
    ON payroll_employee_advance_postings(employee_advance_id) WHERE status = 'ACTIVE';
CREATE INDEX payroll_employee_advance_postings_school_date_idx
    ON payroll_employee_advance_postings(organisation_id, school_id, posting_date DESC, id);

CREATE OR REPLACE FUNCTION validate_payroll_employee_advance_context()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    staff_row staff_profiles%ROWTYPE;
    year_row accounting_financial_years%ROWTYPE;
    period_row accounting_periods%ROWTYPE;
    receivable_row accounting_accounts%ROWTYPE;
    payment_row accounting_accounts%ROWTYPE;
    needs_live_context BOOLEAN;
BEGIN
    needs_live_context := TG_OP='INSERT' OR NEW.status NOT IN ('REJECTED','CANCELLED');
    SELECT * INTO staff_row FROM staff_profiles
      WHERE id=NEW.staff_profile_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF staff_row.id IS NULL OR (needs_live_context AND (staff_row.joined_on > NEW.issue_date
       OR (staff_row.ended_on IS NOT NULL AND staff_row.ended_on < NEW.issue_date))) THEN
        RAISE EXCEPTION 'Employee advance requires staff employed by this school on the issue date';
    END IF;
    SELECT * INTO year_row FROM accounting_financial_years
      WHERE id=NEW.financial_year_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO period_row FROM accounting_periods
      WHERE id=NEW.accounting_period_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF year_row.id IS NULL OR year_row.accounting_basis <> 'ACCRUAL'
       OR NEW.issue_date NOT BETWEEN year_row.starts_on AND year_row.ends_on
       OR NEW.currency_code <> year_row.currency_code THEN
        RAISE EXCEPTION 'Employee advance requires the matching accrual accounting year';
    END IF;
    IF period_row.id IS NULL OR period_row.financial_year_id <> year_row.id
       OR NEW.issue_date NOT BETWEEN period_row.starts_on AND period_row.ends_on THEN
        RAISE EXCEPTION 'Employee advance requires the accounting period covering its issue date';
    END IF;
    IF needs_live_context AND (year_row.status <> 'OPEN' OR period_row.status <> 'OPEN') THEN
        RAISE EXCEPTION 'Employee advance requires an open accounting year and period';
    END IF;
    SELECT * INTO receivable_row FROM accounting_accounts
      WHERE id=NEW.receivable_account_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO payment_row FROM accounting_accounts
      WHERE id=NEW.disbursement_account_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF receivable_row.id IS NULL OR receivable_row.account_type <> 'ASSET'
       OR receivable_row.category <> 'RECEIVABLE'
       OR (needs_live_context AND (receivable_row.status <> 'ACTIVE'
           OR receivable_row.allow_direct_posting = FALSE)) THEN
        RAISE EXCEPTION 'Employee advance requires an active direct-posting receivable account';
    END IF;
    IF payment_row.id IS NULL OR payment_row.account_type <> 'ASSET'
       OR payment_row.category NOT IN ('CASH','BANK')
       OR (needs_live_context AND (payment_row.status <> 'ACTIVE'
           OR payment_row.allow_direct_posting = FALSE))
       OR (NEW.payment_method='CASH' AND payment_row.category <> 'CASH')
       OR (NEW.payment_method<>'CASH' AND payment_row.category <> 'BANK') THEN
        RAISE EXCEPTION 'Employee advance requires a matching active cash or bank posting account';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER payroll_employee_advances_context_guard
    BEFORE INSERT OR UPDATE ON payroll_employee_advances
    FOR EACH ROW EXECUTE FUNCTION validate_payroll_employee_advance_context();

CREATE OR REPLACE FUNCTION guard_payroll_employee_advance()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    decision_user UUID;
BEGIN
    IF TG_OP='INSERT' THEN
        IF current_setting('app.payroll_advance_write',true) IS DISTINCT FROM 'approved'
           OR NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Employee advances require controlled draft preparation';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at OR OLD.idempotency_key <> NEW.idempotency_key
       OR OLD.request_fingerprint <> NEW.request_fingerprint OR NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION 'Employee advance identity and version evidence are immutable';
    END IF;
    IF OLD.status='DRAFT' AND NEW.status='DRAFT' THEN
        IF current_setting('app.payroll_advance_write',true) IS DISTINCT FROM 'approved' THEN
            RAISE EXCEPTION 'Draft employee advance changes require the controlled workflow';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status='DRAFT' AND NEW.status IN ('SUBMITTED','CANCELLED') THEN
        IF current_setting('app.payroll_advance_transition',true) IS DISTINCT FROM 'approved' THEN
            RAISE EXCEPTION 'Employee advance transition requires the controlled workflow';
        END IF;
    ELSIF OLD.status='SUBMITTED' AND NEW.status IN ('APPROVED','REJECTED') THEN
        IF current_setting('app.payroll_advance_transition',true) IS DISTINCT FROM 'approved' THEN
            RAISE EXCEPTION 'Employee advance decision requires the controlled workflow';
        END IF;
        IF ROW(OLD.submitted_by_membership_id,OLD.submitted_at,OLD.submission_reason)
           IS DISTINCT FROM ROW(NEW.submitted_by_membership_id,NEW.submitted_at,NEW.submission_reason) THEN
            RAISE EXCEPTION 'Employee advance submission evidence is immutable';
        END IF;
        SELECT user_id INTO decision_user FROM memberships
          WHERE id=NEW.decided_by_membership_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF decision_user IS NULL OR EXISTS (
            SELECT 1 FROM memberships m
              WHERE m.organisation_id=NEW.organisation_id AND m.school_id=NEW.school_id
                AND m.user_id=decision_user
                AND (m.id IN (OLD.created_by_membership_id,OLD.submitted_by_membership_id)
                    OR EXISTS (SELECT 1 FROM payroll_employee_advance_history h
                      WHERE h.employee_advance_id=OLD.id AND h.organisation_id=OLD.organisation_id
                        AND h.school_id=OLD.school_id AND h.actor_membership_id=m.id
                        AND h.event_type IN ('CREATED','UPDATED','SUBMITTED')))) THEN
            RAISE EXCEPTION 'Employee advance decision requires an independent person';
        END IF;
    ELSE
        RAISE EXCEPTION 'Employee advance history is append-only';
    END IF;
    IF ROW(OLD.staff_profile_id,OLD.advance_number,OLD.advance_type,OLD.purpose,
           OLD.financial_year_id,OLD.accounting_period_id,OLD.issue_date,OLD.currency_code,
           OLD.principal_amount,OLD.recovery_start_month,OLD.installment_count,
           OLD.receivable_account_id,OLD.disbursement_account_id,OLD.payment_method,
           OLD.payment_reference,OLD.employee_code_snapshot,OLD.staff_name_snapshot,OLD.creation_reason)
       IS DISTINCT FROM
       ROW(NEW.staff_profile_id,NEW.advance_number,NEW.advance_type,NEW.purpose,
           NEW.financial_year_id,NEW.accounting_period_id,NEW.issue_date,NEW.currency_code,
           NEW.principal_amount,NEW.recovery_start_month,NEW.installment_count,
           NEW.receivable_account_id,NEW.disbursement_account_id,NEW.payment_method,
           NEW.payment_reference,NEW.employee_code_snapshot,NEW.staff_name_snapshot,NEW.creation_reason) THEN
        RAISE EXCEPTION 'Submitted employee advance evidence is immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER payroll_employee_advances_lifecycle_guard
    BEFORE INSERT OR UPDATE ON payroll_employee_advances
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_employee_advance();

CREATE OR REPLACE FUNCTION guard_payroll_employee_advance_installment()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    advance_status VARCHAR(20);
BEGIN
    IF TG_OP='UPDATE' AND ROW(OLD.id,OLD.employee_advance_id,OLD.organisation_id,OLD.school_id,OLD.created_at)
       IS DISTINCT FROM ROW(NEW.id,NEW.employee_advance_id,NEW.organisation_id,NEW.school_id,NEW.created_at) THEN
        RAISE EXCEPTION 'Employee advance installment identity is immutable';
    END IF;
    SELECT status INTO advance_status FROM payroll_employee_advances
      WHERE id=COALESCE(NEW.employee_advance_id,OLD.employee_advance_id)
        AND organisation_id=COALESCE(NEW.organisation_id,OLD.organisation_id)
        AND school_id=COALESCE(NEW.school_id,OLD.school_id) FOR UPDATE;
    IF advance_status IS DISTINCT FROM 'DRAFT'
       OR current_setting('app.payroll_advance_schedule_write',true) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'Employee advance installments are editable only with their controlled draft';
    END IF;
    RETURN COALESCE(NEW,OLD);
END; $$;

CREATE TRIGGER payroll_employee_advance_installments_guard
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_employee_advance_installments
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_employee_advance_installment();

CREATE OR REPLACE FUNCTION validate_complete_payroll_employee_advance(
    requested_id UUID, requested_organisation UUID, requested_school UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    advance_row payroll_employee_advances%ROWTYPE;
    actual_count INTEGER;
    actual_total NUMERIC(16,2);
    invalid_count INTEGER;
BEGIN
    SELECT * INTO advance_row FROM payroll_employee_advances
      WHERE id=requested_id AND organisation_id=requested_organisation AND school_id=requested_school;
    IF advance_row.id IS NULL THEN RAISE EXCEPTION 'Employee advance was not found'; END IF;
    SELECT count(*),coalesce(sum(planned_amount),0),count(*) FILTER (
        WHERE installment_number > advance_row.installment_count
           OR due_month <> (advance_row.recovery_start_month
              + (installment_number - 1) * INTERVAL '1 month')::date)
      INTO actual_count,actual_total,invalid_count
      FROM payroll_employee_advance_installments
      WHERE employee_advance_id=requested_id AND organisation_id=requested_organisation
        AND school_id=requested_school;
    IF actual_count <> advance_row.installment_count OR actual_total <> advance_row.principal_amount
       OR invalid_count <> 0 THEN
        RAISE EXCEPTION 'Employee advance installment evidence is incomplete or unbalanced';
    END IF;
END; $$;

CREATE OR REPLACE FUNCTION validate_committed_payroll_employee_advance()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status <> 'DRAFT' THEN
        PERFORM validate_complete_payroll_employee_advance(NEW.id,NEW.organisation_id,NEW.school_id);
    END IF;
    RETURN NULL;
END; $$;

CREATE CONSTRAINT TRIGGER payroll_employee_advances_complete
    AFTER INSERT OR UPDATE ON payroll_employee_advances
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_committed_payroll_employee_advance();

CREATE OR REPLACE FUNCTION guard_payroll_employee_advance_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    advance_row payroll_employee_advances%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'Employee advance history is immutable'; END IF;
    IF current_setting('app.payroll_advance_history_write',true) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'Employee advance history requires the controlled workflow';
    END IF;
    SELECT * INTO advance_row FROM payroll_employee_advances
      WHERE id=NEW.employee_advance_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF advance_row.id IS NULL OR NEW.new_status <> advance_row.status
       OR NEW.evidence_snapshot IS DISTINCT FROM to_jsonb(advance_row) THEN
        RAISE EXCEPTION 'Employee advance history must snapshot its exact current evidence';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER payroll_employee_advance_history_guard
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_employee_advance_history
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_employee_advance_history();

CREATE OR REPLACE FUNCTION guard_payroll_employee_advance_posting()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    advance_row payroll_employee_advances%ROWTYPE;
    journal_state VARCHAR(20);
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Employee advance posting evidence is append-only'; END IF;
    SELECT * INTO advance_row FROM payroll_employee_advances
      WHERE id=NEW.employee_advance_id AND organisation_id=NEW.organisation_id
        AND school_id=NEW.school_id FOR UPDATE;
    IF advance_row.id IS NULL OR advance_row.status <> 'APPROVED' THEN
        RAISE EXCEPTION 'Only an approved employee advance can be prepared for disbursement';
    END IF;
    IF NEW.financial_year_id <> advance_row.financial_year_id
       OR NEW.accounting_period_id <> advance_row.accounting_period_id
       OR NEW.posting_date <> advance_row.issue_date
       OR NEW.payment_method <> advance_row.payment_method
       OR NEW.payment_reference IS DISTINCT FROM advance_row.payment_reference
       OR NEW.source_amount <> advance_row.principal_amount THEN
        RAISE EXCEPTION 'Employee advance posting must match its approved frozen evidence';
    END IF;
    IF TG_OP='INSERT' THEN
        IF current_setting('app.payroll_advance_posting_prepare',true) IS DISTINCT FROM 'approved'
           OR NEW.status <> 'ACTIVE' OR NEW.accounting_journal_id IS NOT NULL
           OR NEW.journal_linked_at IS NOT NULL OR NEW.released_at IS NOT NULL THEN
            RAISE EXCEPTION 'Employee advance posting requires controlled preparation';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(OLD.id,OLD.organisation_id,OLD.school_id,OLD.employee_advance_id,
           OLD.financial_year_id,OLD.accounting_period_id,OLD.posting_date,
           OLD.sequence_number,OLD.payment_method,OLD.payment_reference,
           OLD.idempotency_key,OLD.request_fingerprint,OLD.source_amount,
           OLD.evidence_snapshot,OLD.evidence_sha256,OLD.preparation_reason,
           OLD.prepared_by_membership_id,OLD.prepared_at)
       IS DISTINCT FROM
       ROW(NEW.id,NEW.organisation_id,NEW.school_id,NEW.employee_advance_id,
           NEW.financial_year_id,NEW.accounting_period_id,NEW.posting_date,
           NEW.sequence_number,NEW.payment_method,NEW.payment_reference,
           NEW.idempotency_key,NEW.request_fingerprint,NEW.source_amount,
           NEW.evidence_snapshot,NEW.evidence_sha256,NEW.preparation_reason,
           NEW.prepared_by_membership_id,NEW.prepared_at)
       OR NEW.row_version <> OLD.row_version + 1 THEN
        RAISE EXCEPTION 'Employee advance posting evidence is immutable';
    END IF;
    IF OLD.accounting_journal_id IS NULL AND NEW.accounting_journal_id IS NOT NULL
       AND OLD.status='ACTIVE' AND NEW.status='ACTIVE'
       AND current_setting('app.payroll_advance_journal_link',true)='approved' THEN
        IF NEW.journal_linked_at IS NULL OR NEW.released_at IS NOT NULL THEN
            RAISE EXCEPTION 'Employee advance journal link evidence is incomplete';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status='ACTIVE' AND NEW.status='RELEASED'
       AND OLD.accounting_journal_id=NEW.accounting_journal_id
       AND current_setting('app.payroll_advance_posting_release',true)='approved' THEN
        SELECT status INTO journal_state FROM accounting_journals
          WHERE id=NEW.accounting_journal_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF journal_state IS DISTINCT FROM 'REJECTED' OR NEW.released_at IS NULL
           OR NEW.journal_linked_at IS DISTINCT FROM OLD.journal_linked_at THEN
            RAISE EXCEPTION 'Only a rejected employee advance journal can release its preparation';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Employee advance posting evidence is append-only';
END; $$;

CREATE TRIGGER payroll_employee_advance_postings_guard
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_employee_advance_postings
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_employee_advance_posting();

CREATE OR REPLACE FUNCTION validate_complete_payroll_employee_advance_posting(
    requested_id UUID, requested_organisation UUID, requested_school UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    posting_row payroll_employee_advance_postings%ROWTYPE;
    advance_row payroll_employee_advances%ROWTYPE;
    journal_row accounting_journals%ROWTYPE;
    valid_debit INTEGER;
    valid_credit INTEGER;
    line_count INTEGER;
BEGIN
    SELECT * INTO posting_row FROM payroll_employee_advance_postings
      WHERE id=requested_id AND organisation_id=requested_organisation AND school_id=requested_school;
    IF posting_row.id IS NULL
       OR posting_row.accounting_journal_id IS NULL THEN
        RAISE EXCEPTION 'Employee advance posting journal link is incomplete';
    END IF;
    SELECT * INTO advance_row FROM payroll_employee_advances
      WHERE id=posting_row.employee_advance_id AND organisation_id=requested_organisation
        AND school_id=requested_school;
    SELECT * INTO journal_row FROM accounting_journals
      WHERE id=posting_row.accounting_journal_id AND organisation_id=requested_organisation
        AND school_id=requested_school;
    IF journal_row.id IS NULL OR journal_row.source_module <> 'PAYROLL_ADVANCE'
       OR journal_row.source_id <> posting_row.id OR journal_row.voucher_type <> 'PAYMENT'
       OR journal_row.financial_year_id <> posting_row.financial_year_id
       OR journal_row.accounting_period_id <> posting_row.accounting_period_id
       OR journal_row.posting_date <> posting_row.posting_date
       OR (posting_row.status='ACTIVE' AND journal_row.status NOT IN ('SUBMITTED','POSTED','REVERSED','REJECTED'))
       OR (posting_row.status='RELEASED' AND journal_row.status <> 'REJECTED')
       OR journal_row.created_by_membership_id <> posting_row.prepared_by_membership_id
       OR journal_row.line_count <> 2 OR journal_row.total_amount <> posting_row.source_amount THEN
        RAISE EXCEPTION 'Employee advance accounting journal does not match its source evidence';
    END IF;
    SELECT count(*),
           count(*) FILTER (WHERE account_id=advance_row.receivable_account_id
             AND debit_amount=posting_row.source_amount AND credit_amount=0
             AND learner_fee_account_id IS NULL AND vendor_id IS NULL),
           count(*) FILTER (WHERE account_id=advance_row.disbursement_account_id
             AND debit_amount=0 AND credit_amount=posting_row.source_amount
             AND learner_fee_account_id IS NULL AND vendor_id IS NULL)
      INTO line_count,valid_debit,valid_credit
      FROM accounting_journal_lines
      WHERE accounting_journal_id=journal_row.id AND organisation_id=requested_organisation
        AND school_id=requested_school;
    IF line_count <> 2 OR valid_debit <> 1 OR valid_credit <> 1 THEN
        RAISE EXCEPTION 'Employee advance journal lines must debit receivable and credit cash or bank';
    END IF;
END; $$;

CREATE OR REPLACE FUNCTION validate_committed_payroll_employee_advance_posting()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='ACTIVE' THEN
        PERFORM validate_complete_payroll_employee_advance_posting(NEW.id,NEW.organisation_id,NEW.school_id);
    END IF;
    RETURN NULL;
END; $$;

CREATE CONSTRAINT TRIGGER payroll_employee_advance_postings_complete
    AFTER INSERT ON payroll_employee_advance_postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_committed_payroll_employee_advance_posting();

CREATE OR REPLACE FUNCTION guard_payroll_employee_advance_journal_lifecycle()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    posting_row payroll_employee_advance_postings%ROWTYPE;
    advance_state VARCHAR(20);
    decision_user UUID;
BEGIN
    IF NEW.source_module <> 'PAYROLL_ADVANCE' THEN RETURN NEW; END IF;
    SELECT * INTO posting_row FROM payroll_employee_advance_postings
      WHERE id=NEW.source_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF posting_row.id IS NULL THEN
        RAISE EXCEPTION 'Employee advance journals require immutable source evidence';
    END IF;
    IF TG_OP='INSERT' THEN
        IF current_setting('app.payroll_advance_posting_prepare',true) IS DISTINCT FROM 'approved'
           OR NEW.status <> 'DRAFT' OR posting_row.accounting_journal_id IS NOT NULL THEN
            RAISE EXCEPTION 'Employee advance journals require controlled preparation';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status='SUBMITTED' AND NEW.status='POSTED' THEN
        SELECT user_id INTO decision_user FROM memberships WHERE id=NEW.decided_by_membership_id
          AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF decision_user IS NULL OR EXISTS (
            SELECT 1 FROM memberships m WHERE m.organisation_id=NEW.organisation_id
              AND m.school_id=NEW.school_id AND m.user_id=decision_user
              AND m.id IN (NEW.created_by_membership_id,NEW.submitted_by_membership_id,
                           posting_row.prepared_by_membership_id)) THEN
            RAISE EXCEPTION 'Employee advance journal posting requires an independent person';
        END IF;
        SELECT status INTO advance_state FROM payroll_employee_advances
          WHERE id=posting_row.employee_advance_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id FOR UPDATE;
        IF advance_state IS DISTINCT FROM 'APPROVED'
           OR posting_row.status <> 'ACTIVE'
           OR posting_row.accounting_journal_id IS DISTINCT FROM NEW.id THEN
            RAISE EXCEPTION 'Employee advance posting requires unchanged approved source evidence';
        END IF;
        PERFORM validate_complete_payroll_employee_advance_posting(
            posting_row.id,NEW.organisation_id,NEW.school_id);
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER payroll_employee_advance_journal_lifecycle_guard
    BEFORE INSERT OR UPDATE ON accounting_journals
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_employee_advance_journal_lifecycle();

-- A line cannot escape a posted parent's guard by being reparented to a draft.
CREATE OR REPLACE FUNCTION guard_accounting_journal_line_identity()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(OLD.id,OLD.accounting_journal_id,OLD.organisation_id,OLD.school_id)
       IS DISTINCT FROM ROW(NEW.id,NEW.accounting_journal_id,NEW.organisation_id,NEW.school_id) THEN
        RAISE EXCEPTION 'Accounting journal line identity is immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER accounting_journal_lines_identity_guard
    BEFORE UPDATE ON accounting_journal_lines
    FOR EACH ROW EXECUTE FUNCTION guard_accounting_journal_line_identity();

CREATE TRIGGER payroll_employee_advances_no_delete
    BEFORE DELETE ON payroll_employee_advances
    FOR EACH ROW EXECUTE FUNCTION prevent_payroll_evidence_mutation();

ALTER TABLE payroll_employee_advances ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_employee_advance_installments ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_employee_advance_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_employee_advance_postings ENABLE ROW LEVEL SECURITY;

CREATE POLICY payroll_employee_advances_tenant_all ON payroll_employee_advances
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_employee_advance_installments_tenant_all ON payroll_employee_advance_installments
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_employee_advance_history_tenant_all ON payroll_employee_advance_history
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_employee_advance_postings_tenant_all ON payroll_employee_advance_postings
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());

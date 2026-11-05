CREATE TABLE payroll_accounting_configurations (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    financial_year_id UUID NOT NULL,
    supersedes_configuration_id UUID,
    revision_number SMALLINT NOT NULL,
    effective_from DATE NOT NULL,
    accounting_basis_snapshot VARCHAR(20) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    salary_payable_account_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    creation_reason VARCHAR(500) NOT NULL,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    submission_reason VARCHAR(500),
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    superseded_by_membership_id UUID,
    superseded_at TIMESTAMPTZ,
    supersede_reason VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payroll_accounting_configurations_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_configurations_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT payroll_accounting_configurations_year_fk
        FOREIGN KEY (financial_year_id, organisation_id, school_id)
        REFERENCES accounting_financial_years(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_configurations_supersedes_fk
        FOREIGN KEY (supersedes_configuration_id, organisation_id, school_id)
        REFERENCES payroll_accounting_configurations(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_configurations_salary_payable_fk
        FOREIGN KEY (salary_payable_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_configurations_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_configurations_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_configurations_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_configurations_superseder_fk
        FOREIGN KEY (superseded_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_configurations_revision_unique
        UNIQUE (school_id, financial_year_id, revision_number),
    CONSTRAINT payroll_accounting_configurations_revision_valid
        CHECK (revision_number BETWEEN 1 AND 1000),
    CONSTRAINT payroll_accounting_configurations_basis_valid
        CHECK (accounting_basis_snapshot = 'ACCRUAL'),
    CONSTRAINT payroll_accounting_configurations_currency_valid
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT payroll_accounting_configurations_status_valid
        CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','SUPERSEDED')),
    CONSTRAINT payroll_accounting_configurations_reasons_valid CHECK (
        length(btrim(creation_reason)) BETWEEN 3 AND 500
        AND (submission_reason IS NULL OR length(btrim(submission_reason)) BETWEEN 3 AND 500)
        AND (decision_reason IS NULL OR length(btrim(decision_reason)) BETWEEN 3 AND 500)
        AND (supersede_reason IS NULL OR length(btrim(supersede_reason)) BETWEEN 3 AND 500)),
    CONSTRAINT payroll_accounting_configurations_lifecycle_valid CHECK (
        (status = 'DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND submission_reason IS NULL AND decided_by_membership_id IS NULL
            AND decided_at IS NULL AND decision_reason IS NULL
            AND superseded_by_membership_id IS NULL AND superseded_at IS NULL
            AND supersede_reason IS NULL)
        OR (status = 'SUBMITTED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_by_membership_id = created_by_membership_id
            AND submitted_at IS NOT NULL AND submission_reason IS NOT NULL
            AND decided_by_membership_id IS NULL AND decided_at IS NULL
            AND decision_reason IS NULL AND superseded_by_membership_id IS NULL
            AND superseded_at IS NULL AND supersede_reason IS NULL)
        OR (status IN ('APPROVED','REJECTED') AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND submission_reason IS NOT NULL
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> submitted_by_membership_id
            AND decided_at IS NOT NULL AND decision_reason IS NOT NULL
            AND superseded_by_membership_id IS NULL AND superseded_at IS NULL
            AND supersede_reason IS NULL)
        OR (status = 'SUPERSEDED' AND submitted_by_membership_id IS NOT NULL
            AND submitted_at IS NOT NULL AND submission_reason IS NOT NULL
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> submitted_by_membership_id
            AND decided_at IS NOT NULL AND decision_reason IS NOT NULL
            AND superseded_by_membership_id IS NOT NULL AND superseded_at IS NOT NULL
            AND supersede_reason IS NOT NULL)),
    CONSTRAINT payroll_accounting_configurations_not_self_superseding
        CHECK (supersedes_configuration_id IS NULL OR supersedes_configuration_id <> id)
);

CREATE UNIQUE INDEX payroll_accounting_configurations_one_working_idx
    ON payroll_accounting_configurations (school_id, financial_year_id)
    WHERE status IN ('DRAFT','SUBMITTED');
CREATE UNIQUE INDEX payroll_accounting_configurations_one_current_idx
    ON payroll_accounting_configurations (school_id, financial_year_id)
    WHERE status = 'APPROVED';
CREATE INDEX payroll_accounting_configurations_effective_idx
    ON payroll_accounting_configurations (
        organisation_id, school_id, financial_year_id, effective_from DESC, status);

CREATE TABLE payroll_accounting_component_mappings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    configuration_id UUID NOT NULL,
    payroll_component_id UUID NOT NULL,
    component_kind_snapshot VARCHAR(30) NOT NULL,
    expense_account_id UUID,
    payable_account_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payroll_accounting_component_mappings_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_component_mappings_configuration_fk
        FOREIGN KEY (configuration_id, organisation_id, school_id)
        REFERENCES payroll_accounting_configurations(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_component_mappings_component_fk
        FOREIGN KEY (payroll_component_id, organisation_id, school_id)
        REFERENCES payroll_components(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_component_mappings_expense_fk
        FOREIGN KEY (expense_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_component_mappings_payable_fk
        FOREIGN KEY (payable_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_component_mappings_unique
        UNIQUE (configuration_id, payroll_component_id),
    CONSTRAINT payroll_accounting_component_mappings_kind_valid CHECK (
        component_kind_snapshot IN ('EARNING','DEDUCTION','EMPLOYER_CONTRIBUTION')),
    CONSTRAINT payroll_accounting_component_mappings_accounts_valid CHECK (
        (component_kind_snapshot='EARNING' AND expense_account_id IS NOT NULL
            AND payable_account_id IS NULL)
        OR (component_kind_snapshot='DEDUCTION' AND expense_account_id IS NULL
            AND payable_account_id IS NOT NULL)
        OR (component_kind_snapshot='EMPLOYER_CONTRIBUTION'
            AND expense_account_id IS NOT NULL AND payable_account_id IS NOT NULL))
);

CREATE TABLE payroll_accounting_tender_mappings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    configuration_id UUID NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    payment_account_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payroll_accounting_tender_mappings_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_tender_mappings_configuration_fk
        FOREIGN KEY (configuration_id, organisation_id, school_id)
        REFERENCES payroll_accounting_configurations(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_tender_mappings_account_fk
        FOREIGN KEY (payment_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_tender_mappings_method_valid CHECK (
        payment_method IN ('CASH','BANK_TRANSFER','CHEQUE','SCHOOL_UPI')),
    CONSTRAINT payroll_accounting_tender_mappings_unique
        UNIQUE (configuration_id, payment_method)
);

CREATE TABLE payroll_accounting_posting_batches (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    configuration_id UUID NOT NULL,
    payroll_run_id UUID NOT NULL,
    accrual_batch_id UUID,
    financial_year_id UUID NOT NULL,
    accounting_period_id UUID NOT NULL,
    posting_type VARCHAR(20) NOT NULL,
    posting_date DATE NOT NULL,
    sequence_number SMALLINT NOT NULL,
    payment_method VARCHAR(30),
    payment_reference VARCHAR(120),
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    source_count INTEGER NOT NULL,
    source_amount NUMERIC(16,2) NOT NULL,
    evidence_snapshot JSONB NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    accounting_journal_id UUID,
    journal_linked_at TIMESTAMPTZ,
    preparation_reason VARCHAR(500) NOT NULL,
    prepared_by_membership_id UUID NOT NULL,
    prepared_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payroll_accounting_posting_batches_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_batches_configuration_fk
        FOREIGN KEY (configuration_id, organisation_id, school_id)
        REFERENCES payroll_accounting_configurations(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_batches_run_fk
        FOREIGN KEY (payroll_run_id, organisation_id, school_id)
        REFERENCES payroll_runs(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_batches_accrual_fk
        FOREIGN KEY (accrual_batch_id, organisation_id, school_id)
        REFERENCES payroll_accounting_posting_batches(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_batches_year_fk
        FOREIGN KEY (financial_year_id, organisation_id, school_id)
        REFERENCES accounting_financial_years(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_batches_period_fk
        FOREIGN KEY (accounting_period_id, organisation_id, school_id)
        REFERENCES accounting_periods(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_batches_journal_fk
        FOREIGN KEY (accounting_journal_id, organisation_id, school_id)
        REFERENCES accounting_journals(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_batches_preparer_fk
        FOREIGN KEY (prepared_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_batches_idempotency_unique
        UNIQUE (school_id, idempotency_key),
    CONSTRAINT payroll_accounting_posting_batches_sequence_unique
        UNIQUE (school_id, payroll_run_id, posting_type, sequence_number),
    CONSTRAINT payroll_accounting_posting_batches_journal_unique UNIQUE (accounting_journal_id),
    CONSTRAINT payroll_accounting_posting_batches_type_valid
        CHECK (posting_type IN ('ACCRUAL','PAYMENT')),
    CONSTRAINT payroll_accounting_posting_batches_sequence_valid
        CHECK (sequence_number BETWEEN 1 AND 1000),
    CONSTRAINT payroll_accounting_posting_batches_payment_valid CHECK (
        (posting_type='ACCRUAL' AND accrual_batch_id IS NULL
            AND payment_method IS NULL AND payment_reference IS NULL)
        OR (posting_type='PAYMENT' AND accrual_batch_id IS NOT NULL
            AND payment_method IN ('CASH','BANK_TRANSFER','CHEQUE','SCHOOL_UPI')
            AND (payment_reference IS NULL OR length(btrim(payment_reference)) BETWEEN 2 AND 120)
            AND (payment_method='CASH' OR payment_reference IS NOT NULL))),
    CONSTRAINT payroll_accounting_posting_batches_idempotency_valid
        CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 100),
    CONSTRAINT payroll_accounting_posting_batches_fingerprint_valid
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_accounting_posting_batches_counts_valid
        CHECK (source_count BETWEEN 1 AND 500 AND source_amount > 0
            AND source_amount <= 1000000000000),
    CONSTRAINT payroll_accounting_posting_batches_evidence_valid CHECK (
        jsonb_typeof(evidence_snapshot)='object' AND evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_accounting_posting_batches_reason_valid
        CHECK (length(btrim(preparation_reason)) BETWEEN 3 AND 500),
    CONSTRAINT payroll_accounting_posting_batches_link_valid CHECK (
        (accounting_journal_id IS NULL AND journal_linked_at IS NULL)
        OR (accounting_journal_id IS NOT NULL AND journal_linked_at IS NOT NULL))
);

CREATE INDEX payroll_accounting_posting_batches_run_idx
    ON payroll_accounting_posting_batches (
        organisation_id, school_id, payroll_run_id, posting_type, prepared_at DESC);

CREATE TABLE payroll_accounting_posting_items (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    posting_batch_id UUID NOT NULL,
    posting_type VARCHAR(20) NOT NULL,
    payroll_run_staff_result_id UUID NOT NULL,
    sequence_number SMALLINT NOT NULL,
    source_amount NUMERIC(16,2) NOT NULL,
    source_snapshot JSONB NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    released_by_membership_id UUID,
    released_at TIMESTAMPTZ,
    release_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payroll_accounting_posting_items_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_items_batch_fk
        FOREIGN KEY (posting_batch_id, organisation_id, school_id)
        REFERENCES payroll_accounting_posting_batches(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_items_result_fk
        FOREIGN KEY (payroll_run_staff_result_id, organisation_id, school_id)
        REFERENCES payroll_run_staff_results(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_items_releaser_fk
        FOREIGN KEY (released_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_accounting_posting_items_batch_sequence_unique
        UNIQUE (posting_batch_id, sequence_number),
    CONSTRAINT payroll_accounting_posting_items_batch_result_unique
        UNIQUE (posting_batch_id, payroll_run_staff_result_id),
    CONSTRAINT payroll_accounting_posting_items_type_valid
        CHECK (posting_type IN ('ACCRUAL','PAYMENT')),
    CONSTRAINT payroll_accounting_posting_items_sequence_valid
        CHECK (sequence_number BETWEEN 1 AND 500),
    CONSTRAINT payroll_accounting_posting_items_amount_valid
        CHECK (source_amount >= 0 AND source_amount <= 1000000000000),
    CONSTRAINT payroll_accounting_posting_items_snapshot_valid CHECK (
        jsonb_typeof(source_snapshot)='object' AND source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_accounting_posting_items_status_valid
        CHECK (status IN ('ACTIVE','RELEASED')),
    CONSTRAINT payroll_accounting_posting_items_release_valid CHECK (
        (status='ACTIVE' AND released_by_membership_id IS NULL
            AND released_at IS NULL AND release_reason IS NULL)
        OR (status='RELEASED' AND released_by_membership_id IS NOT NULL
            AND released_at IS NOT NULL AND release_reason IS NOT NULL
            AND length(btrim(release_reason)) BETWEEN 3 AND 500))
);

CREATE UNIQUE INDEX payroll_accounting_posting_items_one_active_idx
    ON payroll_accounting_posting_items (
        school_id, payroll_run_staff_result_id, posting_type) WHERE status='ACTIVE';
CREATE INDEX payroll_accounting_posting_items_batch_idx
    ON payroll_accounting_posting_items (
        organisation_id, school_id, posting_batch_id, sequence_number);

CREATE OR REPLACE FUNCTION guard_payroll_accounting_configuration()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    year_row accounting_financial_years%ROWTYPE;
    salary_payable accounting_accounts%ROWTYPE;
    source_row payroll_accounting_configurations%ROWTYPE;
    active_components INTEGER;
    mapped_components INTEGER;
    tender_count INTEGER;
BEGIN
    SELECT * INTO year_row FROM accounting_financial_years
      WHERE id=NEW.financial_year_id AND organisation_id=NEW.organisation_id
        AND school_id=NEW.school_id;
    IF NOT FOUND OR year_row.accounting_basis <> 'ACCRUAL'
       OR NEW.accounting_basis_snapshot <> year_row.accounting_basis
       OR NEW.currency_code <> year_row.currency_code
       OR NEW.effective_from < year_row.starts_on OR NEW.effective_from > year_row.ends_on THEN
        RAISE EXCEPTION 'Payroll accounting requires a matching accrual financial year, currency and date';
    END IF;
    IF TG_OP='INSERT' OR NEW.status IN ('DRAFT','SUBMITTED','APPROVED') THEN
    SELECT * INTO salary_payable FROM accounting_accounts
      WHERE id=NEW.salary_payable_account_id AND organisation_id=NEW.organisation_id
        AND school_id=NEW.school_id;
    IF NOT FOUND OR salary_payable.account_type <> 'LIABILITY'
       OR salary_payable.category <> 'PAYABLE' OR salary_payable.status <> 'ACTIVE'
       OR NOT salary_payable.allow_direct_posting THEN
        RAISE EXCEPTION 'Payroll accounting requires an active posting salary payable account';
    END IF;
    END IF;
    IF NEW.supersedes_configuration_id IS NOT NULL
       AND (TG_OP='INSERT' OR NEW.status IN ('DRAFT','SUBMITTED')) THEN
        SELECT * INTO source_row FROM payroll_accounting_configurations
          WHERE id=NEW.supersedes_configuration_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id AND financial_year_id=NEW.financial_year_id;
        IF NOT FOUND OR source_row.status <> 'APPROVED'
           OR NEW.effective_from <= source_row.effective_from THEN
            RAISE EXCEPTION 'A payroll accounting revision must prospectively supersede the approved configuration';
        END IF;
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Payroll accounting configurations must begin as drafts';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.financial_year_id <> NEW.financial_year_id
       OR OLD.revision_number <> NEW.revision_number
       OR OLD.accounting_basis_snapshot <> NEW.accounting_basis_snapshot
       OR OLD.currency_code <> NEW.currency_code
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at OR OLD.creation_reason <> NEW.creation_reason THEN
        RAISE EXCEPTION 'Payroll accounting configuration identity and origin are immutable';
    END IF;
    IF NEW.row_version <> OLD.row_version+1 THEN
        RAISE EXCEPTION 'Payroll accounting configuration requires the next row version';
    END IF;
    IF OLD.status <> 'DRAFT' AND (
       OLD.salary_payable_account_id <> NEW.salary_payable_account_id
       OR OLD.effective_from <> NEW.effective_from
       OR OLD.supersedes_configuration_id IS DISTINCT FROM NEW.supersedes_configuration_id
       OR OLD.submitted_by_membership_id IS DISTINCT FROM NEW.submitted_by_membership_id
       OR OLD.submitted_at IS DISTINCT FROM NEW.submitted_at
       OR OLD.submission_reason IS DISTINCT FROM NEW.submission_reason) THEN
        RAISE EXCEPTION 'Submitted payroll accounting mapping details are immutable';
    END IF;
    IF OLD.status='APPROVED' AND (
       OLD.decided_by_membership_id IS DISTINCT FROM NEW.decided_by_membership_id
       OR OLD.decided_at IS DISTINCT FROM NEW.decided_at
       OR OLD.decision_reason IS DISTINCT FROM NEW.decision_reason) THEN
        RAISE EXCEPTION 'Payroll accounting approval evidence is immutable';
    END IF;
    IF OLD.status='DRAFT' AND NEW.status='DRAFT' THEN RETURN NEW; END IF;
    IF (OLD.status='DRAFT' AND NEW.status='SUBMITTED')
       OR (OLD.status='SUBMITTED' AND NEW.status='APPROVED') THEN
        SELECT count(*) INTO active_components FROM payroll_components
          WHERE organisation_id=NEW.organisation_id AND school_id=NEW.school_id
            AND status='ACTIVE';
        SELECT count(*) INTO mapped_components FROM payroll_accounting_component_mappings mapping
          JOIN payroll_components component ON component.id=mapping.payroll_component_id
            AND component.organisation_id=mapping.organisation_id AND component.school_id=mapping.school_id
            AND component.status='ACTIVE' AND component.component_kind=mapping.component_kind_snapshot
          WHERE mapping.organisation_id=NEW.organisation_id AND mapping.school_id=NEW.school_id
            AND mapping.configuration_id=NEW.id;
        SELECT count(*) INTO tender_count FROM payroll_accounting_tender_mappings
          WHERE organisation_id=NEW.organisation_id AND school_id=NEW.school_id
            AND configuration_id=NEW.id;
        IF active_components=0 OR mapped_components <> active_components OR tender_count=0 THEN
            RAISE EXCEPTION 'Payroll accounting submission requires every active component and at least one payment method to be mapped';
        END IF;
    END IF;
    IF OLD.status='DRAFT' AND NEW.status='SUBMITTED' THEN RETURN NEW; END IF;
    IF OLD.status='SUBMITTED' AND NEW.status IN ('APPROVED','REJECTED') THEN
        IF NEW.decided_by_membership_id=NEW.submitted_by_membership_id THEN
            RAISE EXCEPTION 'Payroll accounting approval requires an independent actor';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status='APPROVED' AND NEW.status='SUPERSEDED' THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'Invalid payroll accounting configuration transition';
END; $$;

CREATE OR REPLACE FUNCTION guard_payroll_accounting_component_mapping()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_status VARCHAR(20);
    component_row payroll_components%ROWTYPE;
    expense_row accounting_accounts%ROWTYPE;
    payable_row accounting_accounts%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        SELECT status INTO parent_status FROM payroll_accounting_configurations
          WHERE id=OLD.configuration_id AND organisation_id=OLD.organisation_id
            AND school_id=OLD.school_id;
        IF parent_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Payroll accounting mappings are editable only while the configuration is a draft';
        END IF;
        RETURN OLD;
    END IF;
    SELECT status INTO parent_status FROM payroll_accounting_configurations
      WHERE id=NEW.configuration_id AND organisation_id=NEW.organisation_id
        AND school_id=NEW.school_id;
    IF parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Payroll accounting mappings are editable only while the configuration is a draft';
    END IF;
    SELECT * INTO component_row FROM payroll_components
      WHERE id=NEW.payroll_component_id AND organisation_id=NEW.organisation_id
        AND school_id=NEW.school_id;
    IF NOT FOUND OR component_row.component_kind <> NEW.component_kind_snapshot THEN
        RAISE EXCEPTION 'Payroll component mapping must preserve the component kind';
    END IF;
    IF NEW.expense_account_id IS NOT NULL THEN
        SELECT * INTO expense_row FROM accounting_accounts
          WHERE id=NEW.expense_account_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF NOT FOUND OR expense_row.account_type <> 'EXPENSE'
           OR expense_row.category <> 'EXPENSE' OR expense_row.status <> 'ACTIVE'
           OR NOT expense_row.allow_direct_posting THEN
            RAISE EXCEPTION 'Payroll expense mappings require an active posting expense account';
        END IF;
    END IF;
    IF NEW.payable_account_id IS NOT NULL THEN
        SELECT * INTO payable_row FROM accounting_accounts
          WHERE id=NEW.payable_account_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF NOT FOUND OR payable_row.account_type <> 'LIABILITY'
           OR payable_row.category <> 'PAYABLE' OR payable_row.status <> 'ACTIVE'
           OR NOT payable_row.allow_direct_posting THEN
            RAISE EXCEPTION 'Payroll liability mappings require an active posting payable account';
        END IF;
    END IF;
    IF TG_OP='UPDATE' AND (OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.configuration_id <> NEW.configuration_id
       OR OLD.payroll_component_id <> NEW.payroll_component_id
       OR OLD.created_at <> NEW.created_at) THEN
        RAISE EXCEPTION 'Payroll accounting mapping identity is immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION guard_payroll_accounting_tender_mapping()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_status VARCHAR(20);
    account_row accounting_accounts%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        SELECT status INTO parent_status FROM payroll_accounting_configurations
          WHERE id=OLD.configuration_id AND organisation_id=OLD.organisation_id
            AND school_id=OLD.school_id;
        IF parent_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Payroll payment mappings are editable only while the configuration is a draft';
        END IF;
        RETURN OLD;
    END IF;
    SELECT status INTO parent_status FROM payroll_accounting_configurations
      WHERE id=NEW.configuration_id AND organisation_id=NEW.organisation_id
        AND school_id=NEW.school_id;
    IF parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Payroll payment mappings are editable only while the configuration is a draft';
    END IF;
    SELECT * INTO account_row FROM accounting_accounts
      WHERE id=NEW.payment_account_id AND organisation_id=NEW.organisation_id
        AND school_id=NEW.school_id;
    IF NOT FOUND OR account_row.account_type <> 'ASSET'
       OR account_row.category NOT IN ('CASH','BANK') OR account_row.status <> 'ACTIVE'
       OR NOT account_row.allow_direct_posting
       OR (NEW.payment_method='CASH' AND account_row.category <> 'CASH')
       OR (NEW.payment_method<>'CASH' AND account_row.category <> 'BANK') THEN
        RAISE EXCEPTION 'Payroll payment methods require an active matching cash or bank posting account';
    END IF;
    IF TG_OP='UPDATE' AND (OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.configuration_id <> NEW.configuration_id
       OR OLD.payment_method <> NEW.payment_method OR OLD.created_at <> NEW.created_at) THEN
        RAISE EXCEPTION 'Payroll payment mapping identity is immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION guard_payroll_accounting_batch()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    config_row payroll_accounting_configurations%ROWTYPE;
    run_row payroll_runs%ROWTYPE;
    year_row accounting_financial_years%ROWTYPE;
    journal_row accounting_journals%ROWTYPE;
    accrual_row payroll_accounting_posting_batches%ROWTYPE;
    accrual_journal_status VARCHAR(20);
    accrual_salary_payable UUID;
    later_effective DATE;
BEGIN
    IF TG_OP='INSERT' THEN
        IF current_setting('app.payroll_accounting_prepare',true) IS DISTINCT FROM 'approved'
           OR NEW.accounting_journal_id IS NOT NULL THEN
            RAISE EXCEPTION 'Payroll accounting batches require the controlled preparation workflow';
        END IF;
        SELECT * INTO config_row FROM payroll_accounting_configurations
          WHERE id=NEW.configuration_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id AND status IN ('APPROVED','SUPERSEDED');
        SELECT * INTO run_row FROM payroll_runs
          WHERE id=NEW.payroll_run_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id FOR UPDATE;
        SELECT * INTO year_row FROM accounting_financial_years
          WHERE id=NEW.financial_year_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF config_row.id IS NULL OR run_row.id IS NULL OR run_row.status <> 'FINALIZED'
           OR year_row.id IS NULL OR year_row.accounting_basis <> 'ACCRUAL'
           OR year_row.status <> 'OPEN' OR NEW.posting_date < year_row.starts_on
           OR NEW.posting_date > year_row.ends_on OR run_row.currency_code <> config_row.currency_code
           OR year_row.currency_code <> run_row.currency_code THEN
            RAISE EXCEPTION 'Payroll posting requires a finalized run and matching open accrual book';
        END IF;
        SELECT min(effective_from) INTO later_effective
          FROM payroll_accounting_configurations
          WHERE organisation_id=NEW.organisation_id AND school_id=NEW.school_id
            AND financial_year_id=NEW.financial_year_id AND status IN ('APPROVED','SUPERSEDED')
            AND effective_from > config_row.effective_from
            AND effective_from <= NEW.posting_date;
        IF config_row.financial_year_id <> NEW.financial_year_id
           OR config_row.effective_from > NEW.posting_date OR later_effective IS NOT NULL THEN
            RAISE EXCEPTION 'Payroll posting must use the effective approved configuration';
        END IF;
        IF EXISTS (SELECT 1 FROM payroll_run_reversal_requests request
          WHERE request.payroll_run_id=NEW.payroll_run_id AND request.organisation_id=NEW.organisation_id
            AND request.school_id=NEW.school_id AND request.status='PENDING') THEN
            RAISE EXCEPTION 'Resolve the pending payroll reversal before preparing accounting entries';
        END IF;
        IF NEW.posting_type='ACCRUAL' THEN
            IF NEW.posting_date <> run_row.period_end OR NEW.source_count <> run_row.staff_count
               OR NEW.source_amount <> run_row.payable_gross_total
                    + run_row.employer_contribution_total THEN
                RAISE EXCEPTION 'Payroll accrual batch must preserve the complete finalized run totals';
            END IF;
        ELSE
            SELECT * INTO accrual_row FROM payroll_accounting_posting_batches
              WHERE id=NEW.accrual_batch_id AND payroll_run_id=NEW.payroll_run_id
                AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id
                AND posting_type='ACCRUAL';
            IF accrual_row.id IS NULL OR accrual_row.accounting_journal_id IS NULL THEN
                RAISE EXCEPTION 'Payroll payment requires the posted accrual source';
            END IF;
            SELECT status INTO accrual_journal_status FROM accounting_journals
              WHERE id=accrual_row.accounting_journal_id
                AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
            SELECT salary_payable_account_id INTO accrual_salary_payable
              FROM payroll_accounting_configurations
              WHERE id=accrual_row.configuration_id AND organisation_id=NEW.organisation_id
                AND school_id=NEW.school_id;
            IF accrual_journal_status IS DISTINCT FROM 'POSTED'
               OR NEW.posting_date < accrual_row.posting_date
               OR accrual_salary_payable <> config_row.salary_payable_account_id THEN
                RAISE EXCEPTION 'Payroll payment requires a posted accrual with the same salary payable control';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Payroll accounting batches are append-only';
    END IF;
    IF current_setting('app.payroll_accounting_journal_link',true) IS DISTINCT FROM 'approved'
       OR OLD.accounting_journal_id IS NOT NULL OR NEW.accounting_journal_id IS NULL
       OR NEW.row_version <> OLD.row_version+1 THEN
        RAISE EXCEPTION 'Payroll accounting journal links require the controlled workflow';
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.configuration_id <> NEW.configuration_id
       OR OLD.payroll_run_id <> NEW.payroll_run_id
       OR OLD.accrual_batch_id IS DISTINCT FROM NEW.accrual_batch_id
       OR OLD.financial_year_id <> NEW.financial_year_id
       OR OLD.accounting_period_id <> NEW.accounting_period_id
       OR OLD.posting_type <> NEW.posting_type OR OLD.posting_date <> NEW.posting_date
       OR OLD.sequence_number <> NEW.sequence_number
       OR OLD.payment_method IS DISTINCT FROM NEW.payment_method
       OR OLD.payment_reference IS DISTINCT FROM NEW.payment_reference
       OR OLD.idempotency_key <> NEW.idempotency_key
       OR OLD.request_fingerprint <> NEW.request_fingerprint
       OR OLD.source_count <> NEW.source_count OR OLD.source_amount <> NEW.source_amount
       OR OLD.evidence_snapshot <> NEW.evidence_snapshot OR OLD.evidence_sha256 <> NEW.evidence_sha256
       OR OLD.preparation_reason <> NEW.preparation_reason
       OR OLD.prepared_by_membership_id <> NEW.prepared_by_membership_id
       OR OLD.prepared_at <> NEW.prepared_at THEN
        RAISE EXCEPTION 'Payroll accounting source evidence is immutable';
    END IF;
    SELECT * INTO journal_row FROM accounting_journals
      WHERE id=NEW.accounting_journal_id AND organisation_id=NEW.organisation_id
        AND school_id=NEW.school_id;
    IF journal_row.id IS NULL OR journal_row.source_module <> 'PAYROLL'
       OR journal_row.source_id IS DISTINCT FROM NEW.id OR journal_row.status <> 'SUBMITTED'
       OR journal_row.financial_year_id <> NEW.financial_year_id
       OR journal_row.accounting_period_id <> NEW.accounting_period_id
       OR journal_row.posting_date <> NEW.posting_date
       OR journal_row.total_amount <> NEW.source_amount
       OR (NEW.posting_type='ACCRUAL' AND journal_row.voucher_type<>'JOURNAL')
       OR (NEW.posting_type='PAYMENT' AND journal_row.voucher_type<>'PAYMENT') THEN
        RAISE EXCEPTION 'Payroll accounting batch must link its submitted payroll journal';
    END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION guard_payroll_accounting_posting_item()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    batch_row payroll_accounting_posting_batches%ROWTYPE;
    result_row payroll_run_staff_results%ROWTYPE;
BEGIN
    IF TG_OP='INSERT' THEN
        IF current_setting('app.payroll_accounting_prepare',true) IS DISTINCT FROM 'approved'
           OR NEW.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'Payroll accounting items require the controlled preparation workflow';
        END IF;
        SELECT * INTO batch_row FROM payroll_accounting_posting_batches
          WHERE id=NEW.posting_batch_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        SELECT * INTO result_row FROM payroll_run_staff_results
          WHERE id=NEW.payroll_run_staff_result_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF batch_row.id IS NULL OR result_row.id IS NULL OR batch_row.accounting_journal_id IS NOT NULL
           OR result_row.payroll_run_id <> batch_row.payroll_run_id
           OR NEW.posting_type <> batch_row.posting_type
           OR (NEW.posting_type='ACCRUAL' AND NEW.source_amount <>
                result_row.payable_gross_amount + result_row.employer_contribution_amount)
           OR (NEW.posting_type='PAYMENT' AND (NEW.source_amount <> result_row.net_amount
                OR NEW.source_amount <= 0)) THEN
            RAISE EXCEPTION 'Payroll posting item must match its finalized staff result';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Payroll accounting items are append-only';
    END IF;
    IF current_setting('app.payroll_accounting_item_release',true) IS DISTINCT FROM 'approved'
       OR OLD.status <> 'ACTIVE' OR NEW.status <> 'RELEASED'
       OR NEW.row_version <> OLD.row_version+1 THEN
        RAISE EXCEPTION 'Payroll accounting source release requires the controlled workflow';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM payroll_accounting_posting_batches batch
      JOIN accounting_journals journal ON journal.id=batch.accounting_journal_id
        AND journal.organisation_id=batch.organisation_id AND journal.school_id=batch.school_id
      WHERE batch.id=OLD.posting_batch_id AND batch.organisation_id=OLD.organisation_id
        AND batch.school_id=OLD.school_id AND journal.status IN ('REJECTED','REVERSED')) THEN
        RAISE EXCEPTION 'Payroll source release requires a rejected or reversed journal';
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.posting_batch_id <> NEW.posting_batch_id
       OR OLD.posting_type <> NEW.posting_type
       OR OLD.payroll_run_staff_result_id <> NEW.payroll_run_staff_result_id
       OR OLD.sequence_number <> NEW.sequence_number OR OLD.source_amount <> NEW.source_amount
       OR OLD.source_snapshot <> NEW.source_snapshot OR OLD.source_sha256 <> NEW.source_sha256
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'Payroll accounting item source evidence is immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION validate_complete_payroll_accounting_batch(
    requested_batch UUID, requested_organisation UUID, requested_school UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    batch_row payroll_accounting_posting_batches%ROWTYPE;
    item_count INTEGER;
    item_total NUMERIC(16,2);
    run_result_count INTEGER;
BEGIN
    SELECT * INTO batch_row FROM payroll_accounting_posting_batches
      WHERE id=requested_batch AND organisation_id=requested_organisation
        AND school_id=requested_school;
    IF NOT FOUND THEN RAISE EXCEPTION 'Payroll accounting batch was not found'; END IF;
    SELECT count(*),coalesce(sum(source_amount),0) INTO item_count,item_total
      FROM payroll_accounting_posting_items WHERE posting_batch_id=batch_row.id
        AND organisation_id=batch_row.organisation_id AND school_id=batch_row.school_id;
    IF item_count <> batch_row.source_count OR item_total <> batch_row.source_amount THEN
        RAISE EXCEPTION 'Payroll accounting batch totals do not match its immutable staff items';
    END IF;
    IF batch_row.posting_type='ACCRUAL' THEN
        SELECT count(*) INTO run_result_count FROM payroll_run_staff_results
          WHERE payroll_run_id=batch_row.payroll_run_id
            AND organisation_id=batch_row.organisation_id AND school_id=batch_row.school_id;
        IF item_count <> run_result_count THEN
            RAISE EXCEPTION 'Payroll accrual must include every finalized staff result';
        END IF;
    END IF;
END; $$;

CREATE OR REPLACE FUNCTION validate_committed_payroll_accounting_batch()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE linked_journal UUID;
BEGIN
    SELECT accounting_journal_id INTO linked_journal FROM payroll_accounting_posting_batches
      WHERE id=NEW.id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF linked_journal IS NULL THEN
        RAISE EXCEPTION 'Payroll accounting batch must commit with its journal link';
    END IF;
    PERFORM validate_complete_payroll_accounting_batch(NEW.id,NEW.organisation_id,NEW.school_id);
    RETURN NULL;
END; $$;

CREATE CONSTRAINT TRIGGER payroll_accounting_posting_batches_complete
    AFTER INSERT ON payroll_accounting_posting_batches
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_committed_payroll_accounting_batch();

CREATE OR REPLACE FUNCTION guard_payroll_accounting_journal_lifecycle()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    batch_row payroll_accounting_posting_batches%ROWTYPE;
    payroll_status VARCHAR(20);
BEGIN
    IF NEW.source_module <> 'PAYROLL' THEN RETURN NEW; END IF;
    SELECT * INTO batch_row FROM payroll_accounting_posting_batches
      WHERE id=NEW.source_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF batch_row.id IS NULL THEN
        RAISE EXCEPTION 'Payroll journals require immutable payroll source evidence';
    END IF;
    IF TG_OP='INSERT' THEN
        IF current_setting('app.payroll_accounting_prepare',true) IS DISTINCT FROM 'approved'
           OR NEW.status <> 'DRAFT' OR batch_row.accounting_journal_id IS NOT NULL THEN
            RAISE EXCEPTION 'Payroll journals require controlled batch preparation';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status='SUBMITTED' AND NEW.status='POSTED' THEN
        SELECT status INTO payroll_status FROM payroll_runs
          WHERE id=batch_row.payroll_run_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id FOR UPDATE;
        IF payroll_status IS DISTINCT FROM 'FINALIZED'
           OR batch_row.accounting_journal_id IS DISTINCT FROM NEW.id THEN
            RAISE EXCEPTION 'Payroll journal posting requires its unchanged finalized run and batch link';
        END IF;
        IF EXISTS (SELECT 1 FROM payroll_run_reversal_requests request
          WHERE request.payroll_run_id=batch_row.payroll_run_id
            AND request.organisation_id=NEW.organisation_id AND request.school_id=NEW.school_id
            AND request.status='PENDING') THEN
            RAISE EXCEPTION 'Payroll journal posting is blocked by a pending payroll reversal';
        END IF;
        IF batch_row.posting_type='PAYMENT' AND NOT EXISTS (
          SELECT 1 FROM payroll_accounting_posting_batches accrual
          JOIN accounting_journals journal ON journal.id=accrual.accounting_journal_id
            AND journal.organisation_id=accrual.organisation_id AND journal.school_id=accrual.school_id
          WHERE accrual.id=batch_row.accrual_batch_id AND accrual.payroll_run_id=batch_row.payroll_run_id
            AND accrual.organisation_id=NEW.organisation_id AND accrual.school_id=NEW.school_id
            AND journal.status='POSTED') THEN
            RAISE EXCEPTION 'Salary payment posting requires its posted payroll accrual';
        END IF;
        PERFORM validate_complete_payroll_accounting_batch(batch_row.id,NEW.organisation_id,NEW.school_id);
    END IF;
    IF OLD.status='POSTED' AND NEW.status='REVERSED' AND batch_row.posting_type='ACCRUAL'
       AND EXISTS (SELECT 1 FROM payroll_accounting_posting_batches payment
          JOIN accounting_journals journal ON journal.id=payment.accounting_journal_id
            AND journal.organisation_id=payment.organisation_id AND journal.school_id=payment.school_id
          WHERE payment.accrual_batch_id=batch_row.id AND payment.organisation_id=NEW.organisation_id
            AND payment.school_id=NEW.school_id AND journal.status IN ('SUBMITTED','POSTED')) THEN
        RAISE EXCEPTION 'Reverse or reject active salary payment journals before reversing the payroll accrual';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER payroll_accounting_journal_lifecycle_guard
    BEFORE INSERT OR UPDATE ON accounting_journals
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_accounting_journal_lifecycle();

CREATE TRIGGER payroll_accounting_configurations_guard
    BEFORE INSERT OR UPDATE ON payroll_accounting_configurations
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_accounting_configuration();
CREATE TRIGGER payroll_accounting_configurations_no_delete
    BEFORE DELETE ON payroll_accounting_configurations
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();
CREATE TRIGGER payroll_accounting_component_mappings_guard
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_accounting_component_mappings
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_accounting_component_mapping();
CREATE TRIGGER payroll_accounting_tender_mappings_guard
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_accounting_tender_mappings
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_accounting_tender_mapping();
CREATE TRIGGER payroll_accounting_posting_batches_guard
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_accounting_posting_batches
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_accounting_batch();
CREATE TRIGGER payroll_accounting_posting_items_guard
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_accounting_posting_items
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_accounting_posting_item();

CREATE OR REPLACE FUNCTION guard_payroll_run_reversal_request()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    run_record payroll_runs%ROWTYPE;
    active_financial_consequences INTEGER;
BEGIN
    IF TG_OP='INSERT' THEN
        IF current_setting('app.payroll_run_reverse_request', true) IS DISTINCT FROM 'approved'
           OR NEW.status <> 'PENDING' THEN
            RAISE EXCEPTION 'payroll reversal requests require the controlled request workflow';
        END IF;
        SELECT * INTO run_record FROM payroll_runs
        WHERE id=NEW.payroll_run_id AND organisation_id=NEW.organisation_id
          AND school_id=NEW.school_id;
        IF run_record.id IS NULL OR run_record.status <> 'FINALIZED'
           OR run_record.row_version <> NEW.requested_run_version THEN
            RAISE EXCEPTION 'payroll reversal requires the current finalized run version';
        END IF;
        SELECT count(*) INTO active_financial_consequences
          FROM payroll_accounting_posting_batches batch
          JOIN accounting_journals journal ON journal.id=batch.accounting_journal_id
            AND journal.organisation_id=batch.organisation_id
            AND journal.school_id=batch.school_id
          WHERE batch.payroll_run_id=NEW.payroll_run_id
            AND batch.organisation_id=NEW.organisation_id AND batch.school_id=NEW.school_id
            AND journal.status IN ('SUBMITTED','POSTED');
        IF active_financial_consequences > 0 THEN
            RAISE EXCEPTION 'reverse submitted or posted payroll journals before reversing the payroll run';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'payroll reversal evidence is append-only';
    END IF;
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.payroll_run_id <> NEW.payroll_run_id
       OR OLD.requested_run_version <> NEW.requested_run_version
       OR OLD.idempotency_key <> NEW.idempotency_key
       OR OLD.request_fingerprint <> NEW.request_fingerprint
       OR OLD.request_reason <> NEW.request_reason
       OR OLD.requested_by_membership_id <> NEW.requested_by_membership_id
       OR OLD.requested_at <> NEW.requested_at THEN
        RAISE EXCEPTION 'payroll reversal request identity and source evidence is immutable';
    END IF;
    IF current_setting('app.payroll_run_reverse_decide', true) IS DISTINCT FROM 'approved'
       OR OLD.status <> 'PENDING' OR NEW.status NOT IN ('APPROVED','REJECTED')
       OR NEW.row_version <> OLD.row_version + 1
       OR NEW.decided_by_membership_id = OLD.requested_by_membership_id THEN
        RAISE EXCEPTION 'invalid payroll reversal request transition';
    END IF;
    IF NEW.status='APPROVED' THEN
        SELECT * INTO run_record FROM payroll_runs
        WHERE id=NEW.payroll_run_id AND organisation_id=NEW.organisation_id
          AND school_id=NEW.school_id;
        SELECT count(*) INTO active_financial_consequences
          FROM payroll_accounting_posting_batches batch
          JOIN accounting_journals journal ON journal.id=batch.accounting_journal_id
            AND journal.organisation_id=batch.organisation_id
            AND journal.school_id=batch.school_id
          WHERE batch.payroll_run_id=NEW.payroll_run_id
            AND batch.organisation_id=NEW.organisation_id AND batch.school_id=NEW.school_id
            AND journal.status IN ('SUBMITTED','POSTED');
        IF run_record.id IS NULL OR run_record.status <> 'FINALIZED'
           OR run_record.row_version <> NEW.requested_run_version
           OR active_financial_consequences > 0 THEN
            RAISE EXCEPTION 'payroll reversal approval requires the unchanged finalized run with all journals reversed';
        END IF;
    END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION guard_payroll_run()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    reversal_record payroll_run_reversal_requests%ROWTYPE;
    active_financial_consequences INTEGER;
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id
       OR OLD.payroll_run_policy_id <> NEW.payroll_run_policy_id
       OR OLD.policy_snapshot <> NEW.policy_snapshot
       OR OLD.payroll_month <> NEW.payroll_month OR OLD.period_start <> NEW.period_start
       OR OLD.period_end <> NEW.period_end OR OLD.attempt_number <> NEW.attempt_number
       OR OLD.run_code <> NEW.run_code OR OLD.idempotency_key <> NEW.idempotency_key
       OR OLD.input_fingerprint <> NEW.input_fingerprint OR OLD.currency_code <> NEW.currency_code
       OR OLD.staff_count <> NEW.staff_count
       OR OLD.scheduled_working_days_total <> NEW.scheduled_working_days_total
       OR OLD.recorded_days_total <> NEW.recorded_days_total
       OR OLD.payable_days_total <> NEW.payable_days_total
       OR OLD.full_gross_total <> NEW.full_gross_total
       OR OLD.payable_gross_total <> NEW.payable_gross_total
       OR OLD.deduction_total <> NEW.deduction_total
       OR OLD.employer_contribution_total <> NEW.employer_contribution_total
       OR OLD.net_total <> NEW.net_total OR OLD.creation_reason <> NEW.creation_reason
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'payroll run identity, source, and calculation evidence is immutable';
    END IF;
    IF OLD.status='DRAFT' AND NEW.status='SUBMITTED' THEN
        PERFORM validate_complete_payroll_run(NEW.id,NEW.organisation_id,NEW.school_id);
        RETURN NEW;
    END IF;
    IF OLD.status='DRAFT' AND NEW.status='CANCELLED' THEN RETURN NEW; END IF;
    IF OLD.status='SUBMITTED' AND NEW.status IN ('FINALIZED','REJECTED') THEN
        IF NEW.decided_by_membership_id=NEW.created_by_membership_id THEN
            RAISE EXCEPTION 'payroll run finalization requires an independent actor';
        END IF;
        IF NEW.status='FINALIZED' THEN
            PERFORM validate_complete_payroll_run(NEW.id,NEW.organisation_id,NEW.school_id);
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status='FINALIZED' AND NEW.status='REVERSED' THEN
        IF current_setting('app.payroll_run_reverse', true) IS DISTINCT FROM 'approved'
           OR NEW.reversal_request_id IS NULL THEN
            RAISE EXCEPTION 'finalized payroll reversal requires the controlled approval workflow';
        END IF;
        SELECT count(*) INTO active_financial_consequences
          FROM payroll_accounting_posting_batches batch
          JOIN accounting_journals journal ON journal.id=batch.accounting_journal_id
            AND journal.organisation_id=batch.organisation_id
            AND journal.school_id=batch.school_id
          WHERE batch.payroll_run_id=NEW.id AND batch.organisation_id=NEW.organisation_id
            AND batch.school_id=NEW.school_id AND journal.status IN ('SUBMITTED','POSTED');
        IF active_financial_consequences > 0 THEN
            RAISE EXCEPTION 'reverse submitted or posted payroll journals before reversing the payroll run';
        END IF;
        SELECT * INTO reversal_record FROM payroll_run_reversal_requests
        WHERE id=NEW.reversal_request_id AND payroll_run_id=NEW.id
          AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF reversal_record.id IS NULL OR reversal_record.status <> 'APPROVED'
           OR reversal_record.decided_by_membership_id <> NEW.reversed_by_membership_id
           OR reversal_record.decision_reason <> NEW.reversal_reason THEN
            RAISE EXCEPTION 'payroll run reversal must match its approved request';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid payroll run transition';
END; $$;

ALTER TABLE payroll_accounting_configurations ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_accounting_component_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_accounting_tender_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_accounting_posting_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_accounting_posting_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY payroll_accounting_configurations_tenant_all
    ON payroll_accounting_configurations USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_accounting_component_mappings_tenant_all
    ON payroll_accounting_component_mappings USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_accounting_tender_mappings_tenant_all
    ON payroll_accounting_tender_mappings USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_accounting_posting_batches_tenant_all
    ON payroll_accounting_posting_batches USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY payroll_accounting_posting_items_tenant_all
    ON payroll_accounting_posting_items USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());

COMMENT ON TABLE payroll_accounting_configurations IS
    'Maker-checker payroll-to-ledger mappings for accrual expense, salary and statutory payables, and cash or bank settlement accounts.';
COMMENT ON TABLE payroll_accounting_posting_batches IS
    'Immutable retry-safe payroll accrual and staff payment evidence linked to common double-entry journals.';
COMMENT ON TABLE payroll_accounting_posting_items IS
    'Per-staff immutable source snapshots preventing duplicate active accrual or payment preparation.';

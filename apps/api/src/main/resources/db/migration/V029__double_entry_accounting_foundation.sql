INSERT INTO capabilities (code, description, risk_level) VALUES
    ('accounting.read', 'Read school-scoped accounting books and financial statements', 'RESTRICTED'),
    ('accounting.configure', 'Configure financial years, periods, chart of accounts, and vendors', 'RESTRICTED'),
    ('accounting.prepare', 'Prepare and submit balanced accounting vouchers', 'RESTRICTED'),
    ('accounting.post', 'Independently approve and post submitted accounting vouchers', 'RESTRICTED'),
    ('accounting.close', 'Open financial years and irreversibly lock or close accounting periods', 'RESTRICTED'),
    ('accounting.reverse.request', 'Request reversal of a posted accounting voucher', 'RESTRICTED'),
    ('accounting.reverse.approve', 'Independently approve a deterministic accounting reversal', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'accounting.read'),
    ('00000000-0000-4000-8000-000000000001', 'accounting.configure'),
    ('00000000-0000-4000-8000-000000000001', 'accounting.prepare'),
    ('00000000-0000-4000-8000-000000000001', 'accounting.post'),
    ('00000000-0000-4000-8000-000000000001', 'accounting.close'),
    ('00000000-0000-4000-8000-000000000001', 'accounting.reverse.request'),
    ('00000000-0000-4000-8000-000000000001', 'accounting.reverse.approve'),
    ('00000000-0000-4000-8000-000000000004', 'accounting.read'),
    ('00000000-0000-4000-8000-000000000004', 'accounting.configure'),
    ('00000000-0000-4000-8000-000000000004', 'accounting.prepare'),
    ('00000000-0000-4000-8000-000000000004', 'accounting.reverse.request'),
    ('00000000-0000-4000-8000-000000000005', 'accounting.read'),
    ('00000000-0000-4000-8000-000000000007', 'accounting.read'),
    ('00000000-0000-4000-8000-000000000007', 'accounting.post'),
    ('00000000-0000-4000-8000-000000000007', 'accounting.close'),
    ('00000000-0000-4000-8000-000000000007', 'accounting.reverse.approve')
ON CONFLICT DO NOTHING;

CREATE TABLE accounting_financial_years (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    code VARCHAR(30) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    accounting_basis VARCHAR(20) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by_membership_id UUID NOT NULL,
    opened_by_membership_id UUID,
    opened_at TIMESTAMPTZ,
    open_reason VARCHAR(500),
    closed_by_membership_id UUID,
    closed_at TIMESTAMPTZ,
    close_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT accounting_financial_years_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT accounting_financial_years_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT accounting_financial_years_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_financial_years_opener_fk
        FOREIGN KEY (opened_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_financial_years_closer_fk
        FOREIGN KEY (closed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_financial_years_code_unique UNIQUE (school_id, code),
    CONSTRAINT accounting_financial_years_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_-]{1,29}$'),
    CONSTRAINT accounting_financial_years_dates_valid CHECK (
        ends_on >= starts_on AND ends_on <= starts_on + 730),
    CONSTRAINT accounting_financial_years_basis_valid CHECK (
        accounting_basis IN ('ACCRUAL','CASH')),
    CONSTRAINT accounting_financial_years_currency_valid CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT accounting_financial_years_status_valid CHECK (
        status IN ('DRAFT','OPEN','CLOSED')),
    CONSTRAINT accounting_financial_years_lifecycle_valid CHECK (
        (status = 'DRAFT' AND opened_by_membership_id IS NULL AND opened_at IS NULL
            AND open_reason IS NULL AND closed_by_membership_id IS NULL AND closed_at IS NULL
            AND close_reason IS NULL)
        OR (status = 'OPEN' AND opened_by_membership_id IS NOT NULL
            AND opened_by_membership_id <> created_by_membership_id
            AND opened_at IS NOT NULL AND length(btrim(open_reason)) BETWEEN 3 AND 500
            AND closed_by_membership_id IS NULL AND closed_at IS NULL AND close_reason IS NULL)
        OR (status = 'CLOSED' AND opened_by_membership_id IS NOT NULL
            AND opened_by_membership_id <> created_by_membership_id
            AND opened_at IS NOT NULL AND length(btrim(open_reason)) BETWEEN 3 AND 500
            AND closed_by_membership_id IS NOT NULL AND closed_at IS NOT NULL
            AND length(btrim(close_reason)) BETWEEN 3 AND 500))
);

CREATE TABLE accounting_periods (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    financial_year_id UUID NOT NULL,
    period_number SMALLINT NOT NULL,
    code VARCHAR(30) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    locked_by_membership_id UUID,
    locked_at TIMESTAMPTZ,
    lock_reason VARCHAR(500),
    closed_by_membership_id UUID,
    closed_at TIMESTAMPTZ,
    close_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT accounting_periods_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT accounting_periods_year_fk
        FOREIGN KEY (financial_year_id, organisation_id, school_id)
        REFERENCES accounting_financial_years(id, organisation_id, school_id),
    CONSTRAINT accounting_periods_locker_fk
        FOREIGN KEY (locked_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_periods_closer_fk
        FOREIGN KEY (closed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_periods_number_unique UNIQUE (financial_year_id, period_number),
    CONSTRAINT accounting_periods_code_unique UNIQUE (financial_year_id, code),
    CONSTRAINT accounting_periods_number_valid CHECK (period_number BETWEEN 1 AND 24),
    CONSTRAINT accounting_periods_code_valid CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,29}$'),
    CONSTRAINT accounting_periods_dates_valid CHECK (ends_on >= starts_on),
    CONSTRAINT accounting_periods_status_valid CHECK (status IN ('OPEN','LOCKED','CLOSED')),
    CONSTRAINT accounting_periods_lifecycle_valid CHECK (
        (status = 'OPEN' AND locked_by_membership_id IS NULL AND locked_at IS NULL
            AND lock_reason IS NULL AND closed_by_membership_id IS NULL
            AND closed_at IS NULL AND close_reason IS NULL)
        OR (status = 'LOCKED' AND locked_by_membership_id IS NOT NULL
            AND locked_at IS NOT NULL AND length(btrim(lock_reason)) BETWEEN 3 AND 500
            AND closed_by_membership_id IS NULL AND closed_at IS NULL AND close_reason IS NULL)
        OR (status = 'CLOSED' AND closed_by_membership_id IS NOT NULL
            AND closed_at IS NOT NULL AND length(btrim(close_reason)) BETWEEN 3 AND 500))
);

CREATE TABLE accounting_accounts (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    parent_account_id UUID,
    code VARCHAR(30) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    account_type VARCHAR(20) NOT NULL,
    normal_balance VARCHAR(10) NOT NULL,
    category VARCHAR(20) NOT NULL,
    allow_direct_posting BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT accounting_accounts_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT accounting_accounts_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT accounting_accounts_parent_fk
        FOREIGN KEY (parent_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT accounting_accounts_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_accounts_code_unique UNIQUE (school_id, code),
    CONSTRAINT accounting_accounts_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_.-]{1,29}$'),
    CONSTRAINT accounting_accounts_type_valid CHECK (
        account_type IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    CONSTRAINT accounting_accounts_balance_valid CHECK (
        (account_type IN ('ASSET','EXPENSE') AND normal_balance = 'DEBIT')
        OR (account_type IN ('LIABILITY','EQUITY','INCOME') AND normal_balance = 'CREDIT')),
    CONSTRAINT accounting_accounts_category_valid CHECK (
        category IN ('CASH','BANK','RECEIVABLE','PAYABLE','FUND','INCOME','EXPENSE','OTHER')),
    CONSTRAINT accounting_accounts_category_type_valid CHECK (
        (category IN ('CASH','BANK','RECEIVABLE') AND account_type = 'ASSET')
        OR (category = 'PAYABLE' AND account_type = 'LIABILITY')
        OR (category = 'FUND' AND account_type = 'EQUITY')
        OR (category = 'INCOME' AND account_type = 'INCOME')
        OR (category = 'EXPENSE' AND account_type = 'EXPENSE')
        OR category = 'OTHER'),
    CONSTRAINT accounting_accounts_status_valid CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT accounting_accounts_parent_not_self CHECK (parent_account_id IS NULL OR parent_account_id <> id)
);

CREATE TABLE accounting_vendors (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    code VARCHAR(40) NOT NULL,
    display_name VARCHAR(180) NOT NULL,
    contact_name VARCHAR(120),
    phone VARCHAR(30),
    email VARCHAR(254),
    address VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT accounting_vendors_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT accounting_vendors_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT accounting_vendors_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_vendors_code_unique UNIQUE (school_id, code),
    CONSTRAINT accounting_vendors_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_-]{1,39}$'),
    CONSTRAINT accounting_vendors_email_valid CHECK (
        email IS NULL OR email ~* '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'),
    CONSTRAINT accounting_vendors_status_valid CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE TABLE accounting_journal_sequences (
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    financial_year_id UUID NOT NULL,
    voucher_type VARCHAR(20) NOT NULL,
    prefix VARCHAR(20) NOT NULL,
    next_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (school_id, financial_year_id, voucher_type),
    CONSTRAINT accounting_journal_sequences_year_fk
        FOREIGN KEY (financial_year_id, organisation_id, school_id)
        REFERENCES accounting_financial_years(id, organisation_id, school_id),
    CONSTRAINT accounting_journal_sequences_voucher_valid CHECK (
        voucher_type IN ('RECEIPT','PAYMENT','JOURNAL','CONTRA','EXPENSE','OPENING')),
    CONSTRAINT accounting_journal_sequences_prefix_valid CHECK (
        prefix ~ '^[A-Z][A-Z0-9/-]{1,19}$'),
    CONSTRAINT accounting_journal_sequences_value_valid CHECK (
        next_value BETWEEN 1 AND 999999999)
);

CREATE TABLE accounting_journals (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    financial_year_id UUID NOT NULL,
    accounting_period_id UUID NOT NULL,
    voucher_type VARCHAR(20) NOT NULL,
    voucher_number VARCHAR(50),
    posting_date DATE NOT NULL,
    reference VARCHAR(120),
    narration VARCHAR(500) NOT NULL,
    source_module VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    source_id UUID,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    reversal_of_journal_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    line_count SMALLINT,
    total_amount NUMERIC(16,2),
    created_by_membership_id UUID NOT NULL,
    submitted_by_membership_id UUID,
    submitted_at TIMESTAMPTZ,
    submission_reason VARCHAR(500),
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    reversed_by_membership_id UUID,
    reversed_at TIMESTAMPTZ,
    reversal_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT accounting_journals_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT accounting_journals_year_fk
        FOREIGN KEY (financial_year_id, organisation_id, school_id)
        REFERENCES accounting_financial_years(id, organisation_id, school_id),
    CONSTRAINT accounting_journals_period_fk
        FOREIGN KEY (accounting_period_id, organisation_id, school_id)
        REFERENCES accounting_periods(id, organisation_id, school_id),
    CONSTRAINT accounting_journals_reversal_fk
        FOREIGN KEY (reversal_of_journal_id, organisation_id, school_id)
        REFERENCES accounting_journals(id, organisation_id, school_id),
    CONSTRAINT accounting_journals_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_journals_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_journals_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_journals_reverser_fk
        FOREIGN KEY (reversed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_journals_number_unique UNIQUE (school_id, financial_year_id, voucher_number),
    CONSTRAINT accounting_journals_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT accounting_journals_voucher_valid CHECK (
        voucher_type IN ('RECEIPT','PAYMENT','JOURNAL','CONTRA','EXPENSE','OPENING')),
    CONSTRAINT accounting_journals_source_valid CHECK (
        source_module IN ('MANUAL','FEES','PAYROLL','EXPENSE','OPENING','REVERSAL')),
    CONSTRAINT accounting_journals_status_valid CHECK (
        status IN ('DRAFT','SUBMITTED','POSTED','REJECTED','REVERSED')),
    CONSTRAINT accounting_journals_fingerprint_valid CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT accounting_journals_idempotency_valid CHECK (
        length(btrim(idempotency_key)) BETWEEN 8 AND 100),
    CONSTRAINT accounting_journals_amount_valid CHECK (
        total_amount IS NULL OR (total_amount > 0 AND total_amount <= 1000000000000)),
    CONSTRAINT accounting_journals_line_count_valid CHECK (
        line_count IS NULL OR line_count BETWEEN 2 AND 500),
    CONSTRAINT accounting_journals_lifecycle_valid CHECK (
        (status = 'DRAFT' AND voucher_number IS NULL AND line_count IS NULL AND total_amount IS NULL
            AND submitted_by_membership_id IS NULL AND submitted_at IS NULL AND submission_reason IS NULL
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
            AND reversed_by_membership_id IS NULL AND reversed_at IS NULL AND reversal_reason IS NULL)
        OR (status = 'SUBMITTED' AND voucher_number IS NULL AND line_count >= 2 AND total_amount > 0
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
            AND reversed_by_membership_id IS NULL AND reversed_at IS NULL AND reversal_reason IS NULL)
        OR (status = 'REJECTED' AND voucher_number IS NULL AND line_count >= 2 AND total_amount > 0
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> submitted_by_membership_id
            AND decided_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND reversed_by_membership_id IS NULL AND reversed_at IS NULL AND reversal_reason IS NULL)
        OR (status = 'POSTED' AND voucher_number IS NOT NULL AND line_count >= 2 AND total_amount > 0
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> submitted_by_membership_id
            AND decided_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND reversed_by_membership_id IS NULL AND reversed_at IS NULL AND reversal_reason IS NULL)
        OR (status = 'REVERSED' AND voucher_number IS NOT NULL AND line_count >= 2 AND total_amount > 0
            AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND length(btrim(submission_reason)) BETWEEN 3 AND 500
            AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> submitted_by_membership_id
            AND decided_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND reversed_by_membership_id IS NOT NULL AND reversed_at IS NOT NULL
            AND length(btrim(reversal_reason)) BETWEEN 3 AND 500))
);

CREATE UNIQUE INDEX accounting_journals_one_reversal_idx
    ON accounting_journals (reversal_of_journal_id)
    WHERE reversal_of_journal_id IS NOT NULL;
CREATE INDEX accounting_journals_book_idx
    ON accounting_journals (organisation_id, school_id, financial_year_id, posting_date, status);
CREATE INDEX accounting_journals_period_status_idx
    ON accounting_journals (accounting_period_id, status);

CREATE TABLE accounting_journal_lines (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    accounting_journal_id UUID NOT NULL,
    line_number SMALLINT NOT NULL,
    account_id UUID NOT NULL,
    account_code_snapshot VARCHAR(30) NOT NULL,
    account_name_snapshot VARCHAR(160) NOT NULL,
    account_type_snapshot VARCHAR(20) NOT NULL,
    vendor_id UUID,
    learner_fee_account_id UUID,
    debit_amount NUMERIC(16,2) NOT NULL DEFAULT 0,
    credit_amount NUMERIC(16,2) NOT NULL DEFAULT 0,
    memo VARCHAR(240),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT accounting_journal_lines_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT accounting_journal_lines_journal_fk
        FOREIGN KEY (accounting_journal_id, organisation_id, school_id)
        REFERENCES accounting_journals(id, organisation_id, school_id),
    CONSTRAINT accounting_journal_lines_account_fk
        FOREIGN KEY (account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT accounting_journal_lines_vendor_fk
        FOREIGN KEY (vendor_id, organisation_id, school_id)
        REFERENCES accounting_vendors(id, organisation_id, school_id),
    CONSTRAINT accounting_journal_lines_fee_account_fk
        FOREIGN KEY (learner_fee_account_id, organisation_id, school_id)
        REFERENCES learner_fee_accounts(id, organisation_id, school_id),
    CONSTRAINT accounting_journal_lines_number_unique UNIQUE (accounting_journal_id, line_number),
    CONSTRAINT accounting_journal_lines_number_valid CHECK (line_number BETWEEN 1 AND 500),
    CONSTRAINT accounting_journal_lines_type_valid CHECK (
        account_type_snapshot IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE')),
    CONSTRAINT accounting_journal_lines_amount_valid CHECK (
        debit_amount BETWEEN 0 AND 1000000000000
        AND credit_amount BETWEEN 0 AND 1000000000000
        AND ((debit_amount > 0 AND credit_amount = 0)
            OR (credit_amount > 0 AND debit_amount = 0)))
);

CREATE INDEX accounting_journal_lines_account_book_idx
    ON accounting_journal_lines (organisation_id, school_id, account_id, accounting_journal_id);
CREATE INDEX accounting_journal_lines_vendor_idx
    ON accounting_journal_lines (organisation_id, school_id, vendor_id)
    WHERE vendor_id IS NOT NULL;
CREATE INDEX accounting_journal_lines_fee_account_idx
    ON accounting_journal_lines (organisation_id, school_id, learner_fee_account_id)
    WHERE learner_fee_account_id IS NOT NULL;

CREATE TABLE accounting_journal_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    accounting_journal_id UUID NOT NULL,
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT accounting_journal_history_journal_fk
        FOREIGN KEY (accounting_journal_id, organisation_id, school_id)
        REFERENCES accounting_journals(id, organisation_id, school_id),
    CONSTRAINT accounting_journal_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_journal_history_status_valid CHECK (
        (from_status IS NULL OR from_status IN ('DRAFT','SUBMITTED','POSTED','REJECTED','REVERSED'))
        AND to_status IN ('DRAFT','SUBMITTED','POSTED','REJECTED','REVERSED')),
    CONSTRAINT accounting_journal_history_reason_valid CHECK (
        length(btrim(reason)) BETWEEN 3 AND 500)
);

CREATE INDEX accounting_journal_history_journal_idx
    ON accounting_journal_history (accounting_journal_id, occurred_at);

CREATE TABLE accounting_reversal_requests (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    accounting_journal_id UUID NOT NULL,
    requested_accounting_period_id UUID NOT NULL,
    requested_posting_date DATE NOT NULL,
    request_reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by_membership_id UUID NOT NULL,
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    reversal_journal_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT accounting_reversal_requests_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT accounting_reversal_requests_journal_fk
        FOREIGN KEY (accounting_journal_id, organisation_id, school_id)
        REFERENCES accounting_journals(id, organisation_id, school_id),
    CONSTRAINT accounting_reversal_requests_period_fk
        FOREIGN KEY (requested_accounting_period_id, organisation_id, school_id)
        REFERENCES accounting_periods(id, organisation_id, school_id),
    CONSTRAINT accounting_reversal_requests_reversal_fk
        FOREIGN KEY (reversal_journal_id, organisation_id, school_id)
        REFERENCES accounting_journals(id, organisation_id, school_id),
    CONSTRAINT accounting_reversal_requests_requester_fk
        FOREIGN KEY (requested_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_reversal_requests_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT accounting_reversal_requests_reason_valid CHECK (
        length(btrim(request_reason)) BETWEEN 3 AND 500),
    CONSTRAINT accounting_reversal_requests_status_valid CHECK (
        status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT accounting_reversal_requests_lifecycle_valid CHECK (
        (status = 'PENDING' AND decided_by_membership_id IS NULL AND decided_at IS NULL
            AND decision_reason IS NULL AND reversal_journal_id IS NULL)
        OR (status = 'APPROVED' AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> requested_by_membership_id
            AND decided_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND reversal_journal_id IS NOT NULL)
        OR (status = 'REJECTED' AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> requested_by_membership_id
            AND decided_at IS NOT NULL AND length(btrim(decision_reason)) BETWEEN 3 AND 500
            AND reversal_journal_id IS NULL))
);

CREATE UNIQUE INDEX accounting_reversal_requests_one_pending_idx
    ON accounting_reversal_requests (accounting_journal_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX accounting_reversal_requests_one_approved_idx
    ON accounting_reversal_requests (accounting_journal_id) WHERE status = 'APPROVED';

CREATE OR REPLACE FUNCTION validate_accounting_financial_year()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM accounting_financial_years other
        WHERE other.school_id = NEW.school_id AND other.id <> NEW.id
          AND daterange(other.starts_on, other.ends_on, '[]')
              && daterange(NEW.starts_on, NEW.ends_on, '[]')
    ) THEN
        RAISE EXCEPTION 'accounting financial years cannot overlap';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER accounting_financial_years_validate
    BEFORE INSERT OR UPDATE ON accounting_financial_years
    FOR EACH ROW EXECUTE FUNCTION validate_accounting_financial_year();

CREATE OR REPLACE FUNCTION guard_accounting_financial_year()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE open_periods INTEGER;
DECLARE total_periods INTEGER;
DECLARE sequence_count INTEGER;
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.code <> NEW.code
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'accounting financial year identity is immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'DRAFT' THEN RETURN NEW; END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'OPEN' THEN
        IF EXISTS (
            SELECT 1 FROM accounting_financial_years other
            WHERE other.school_id = NEW.school_id AND other.id <> NEW.id AND other.status = 'OPEN'
        ) THEN RAISE EXCEPTION 'another accounting financial year is already open'; END IF;
        SELECT count(*),count(*) FILTER (WHERE status='OPEN')
          INTO total_periods,open_periods FROM accounting_periods
        WHERE financial_year_id = NEW.id;
        SELECT count(*) INTO sequence_count FROM accounting_journal_sequences
        WHERE financial_year_id = NEW.id;
        IF total_periods = 0 OR open_periods <> total_periods OR sequence_count <> 6 THEN
            RAISE EXCEPTION 'financial year requires open periods and all voucher sequences';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'OPEN' AND NEW.status = 'CLOSED' THEN
        SELECT count(*) INTO open_periods FROM accounting_periods
        WHERE financial_year_id = NEW.id AND status <> 'CLOSED';
        IF open_periods <> 0 THEN
            RAISE EXCEPTION 'all accounting periods must be closed first';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid accounting financial year transition';
END; $$;

CREATE TRIGGER accounting_financial_years_guard_history
    BEFORE UPDATE ON accounting_financial_years
    FOR EACH ROW EXECUTE FUNCTION guard_accounting_financial_year();

CREATE OR REPLACE FUNCTION validate_accounting_period()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE year_start DATE;
DECLARE year_end DATE;
BEGIN
    SELECT starts_on, ends_on INTO year_start, year_end
    FROM accounting_financial_years
    WHERE id = NEW.financial_year_id AND organisation_id = NEW.organisation_id
      AND school_id = NEW.school_id;
    IF year_start IS NULL OR NEW.starts_on < year_start OR NEW.ends_on > year_end THEN
        RAISE EXCEPTION 'accounting period must stay inside its financial year';
    END IF;
    IF EXISTS (
        SELECT 1 FROM accounting_periods other
        WHERE other.financial_year_id = NEW.financial_year_id AND other.id <> NEW.id
          AND daterange(other.starts_on, other.ends_on, '[]')
              && daterange(NEW.starts_on, NEW.ends_on, '[]')
    ) THEN RAISE EXCEPTION 'accounting periods cannot overlap'; END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER accounting_periods_validate
    BEFORE INSERT OR UPDATE ON accounting_periods
    FOR EACH ROW EXECUTE FUNCTION validate_accounting_period();

CREATE OR REPLACE FUNCTION guard_accounting_period()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE unfinished INTEGER;
DECLARE year_state VARCHAR(20);
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.financial_year_id <> NEW.financial_year_id
       OR OLD.period_number <> NEW.period_number OR OLD.code <> NEW.code
       OR OLD.starts_on <> NEW.starts_on OR OLD.ends_on <> NEW.ends_on
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'accounting period identity is immutable';
    END IF;
    IF (OLD.status = 'OPEN' AND NEW.status IN ('LOCKED','CLOSED'))
       OR (OLD.status = 'LOCKED' AND NEW.status = 'CLOSED') THEN
        SELECT status INTO year_state FROM accounting_financial_years
        WHERE id = NEW.financial_year_id;
        IF year_state <> 'OPEN' THEN
            RAISE EXCEPTION 'accounting periods can change only while the financial year is open';
        END IF;
        IF NEW.status = 'CLOSED' THEN
            SELECT count(*) INTO unfinished FROM accounting_journals
            WHERE accounting_period_id = NEW.id AND status IN ('DRAFT','SUBMITTED');
            IF unfinished <> 0 THEN
                RAISE EXCEPTION 'draft or submitted journals must be resolved before period close';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid accounting period transition';
END; $$;

CREATE TRIGGER accounting_periods_guard_history
    BEFORE UPDATE ON accounting_periods
    FOR EACH ROW EXECUTE FUNCTION guard_accounting_period();

CREATE OR REPLACE FUNCTION guard_accounting_account()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.code <> NEW.code
       OR OLD.account_type <> NEW.account_type OR OLD.normal_balance <> NEW.normal_balance
       OR OLD.category <> NEW.category OR OLD.parent_account_id IS DISTINCT FROM NEW.parent_account_id
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'accounting account identity and classification are immutable';
    END IF;
    IF NEW.allow_direct_posting AND EXISTS (
        SELECT 1 FROM accounting_accounts child
        WHERE child.parent_account_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'parent accounting accounts cannot allow direct posting';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER accounting_accounts_guard_history
    BEFORE UPDATE ON accounting_accounts
    FOR EACH ROW EXECUTE FUNCTION guard_accounting_account();

CREATE OR REPLACE FUNCTION validate_accounting_account_parent()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE parent_type VARCHAR(20);
DECLARE parent_posting BOOLEAN;
BEGIN
    IF NEW.parent_account_id IS NULL THEN RETURN NEW; END IF;
    SELECT account_type, allow_direct_posting INTO parent_type, parent_posting
    FROM accounting_accounts
    WHERE id = NEW.parent_account_id AND organisation_id = NEW.organisation_id
      AND school_id = NEW.school_id;
    IF parent_type IS NULL OR parent_type <> NEW.account_type THEN
        RAISE EXCEPTION 'parent and child accounting accounts must have the same type';
    END IF;
    IF parent_posting THEN
        RAISE EXCEPTION 'parent accounting accounts cannot allow direct posting';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER accounting_accounts_validate_parent
    BEFORE INSERT OR UPDATE ON accounting_accounts
    FOR EACH ROW EXECUTE FUNCTION validate_accounting_account_parent();

CREATE OR REPLACE FUNCTION guard_accounting_vendor()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.code <> NEW.code
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'accounting vendor identity is immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER accounting_vendors_guard_history
    BEFORE UPDATE ON accounting_vendors
    FOR EACH ROW EXECUTE FUNCTION guard_accounting_vendor();

CREATE OR REPLACE FUNCTION assert_accounting_journal_balanced(journal_id UUID)
RETURNS TABLE(line_count INTEGER, total_debit NUMERIC, total_credit NUMERIC)
LANGUAGE sql STABLE AS $$
    SELECT count(*)::INTEGER, COALESCE(sum(debit_amount), 0), COALESCE(sum(credit_amount), 0)
    FROM accounting_journal_lines
    WHERE accounting_journal_id = journal_id
$$;

CREATE OR REPLACE FUNCTION guard_accounting_journal()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE actual_count INTEGER;
DECLARE actual_debit NUMERIC;
DECLARE actual_credit NUMERIC;
DECLARE period_state VARCHAR(20);
DECLARE year_state VARCHAR(20);
DECLARE approved_reversal INTEGER;
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.financial_year_id <> NEW.financial_year_id
       OR OLD.accounting_period_id <> NEW.accounting_period_id
       OR OLD.voucher_type <> NEW.voucher_type OR OLD.posting_date <> NEW.posting_date
       OR OLD.reference IS DISTINCT FROM NEW.reference OR OLD.narration <> NEW.narration
       OR OLD.source_module <> NEW.source_module OR OLD.source_id IS DISTINCT FROM NEW.source_id
       OR OLD.idempotency_key <> NEW.idempotency_key
       OR OLD.request_fingerprint <> NEW.request_fingerprint
       OR OLD.reversal_of_journal_id IS DISTINCT FROM NEW.reversal_of_journal_id
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'submitted accounting journal identity and source evidence are immutable';
    END IF;

    IF OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED' THEN
        SELECT result.line_count, result.total_debit, result.total_credit
          INTO actual_count, actual_debit, actual_credit
        FROM assert_accounting_journal_balanced(NEW.id) result;
        IF actual_count < 2 OR actual_debit <= 0 OR actual_debit <> actual_credit
           OR NEW.line_count <> actual_count OR NEW.total_amount <> actual_debit THEN
            RAISE EXCEPTION 'accounting journal must contain balanced debit and credit lines';
        END IF;
        SELECT period.status, year.status INTO period_state, year_state
        FROM accounting_periods period
        JOIN accounting_financial_years year ON year.id = period.financial_year_id
        WHERE period.id = NEW.accounting_period_id
          AND period.financial_year_id = NEW.financial_year_id
          AND NEW.posting_date BETWEEN period.starts_on AND period.ends_on;
        IF period_state <> 'OPEN' OR year_state <> 'OPEN' THEN
            RAISE EXCEPTION 'accounting journal date must be in an open period and financial year';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'SUBMITTED' AND NEW.status IN ('POSTED','REJECTED') THEN
        IF NEW.status = 'POSTED' THEN
            SELECT period.status, year.status INTO period_state, year_state
            FROM accounting_periods period
            JOIN accounting_financial_years year ON year.id = period.financial_year_id
            WHERE period.id = NEW.accounting_period_id;
            IF period_state <> 'OPEN' OR year_state <> 'OPEN' THEN
                RAISE EXCEPTION 'accounting journal cannot post into a locked or closed period';
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'POSTED' AND NEW.status = 'REVERSED' THEN
        SELECT count(*) INTO approved_reversal
        FROM accounting_reversal_requests request
        JOIN accounting_journals reversal ON reversal.id = request.reversal_journal_id
        WHERE request.accounting_journal_id = NEW.id AND request.status = 'APPROVED'
          AND reversal.reversal_of_journal_id = NEW.id AND reversal.status = 'POSTED';
        IF approved_reversal <> 1 THEN
            RAISE EXCEPTION 'posted accounting journals require an approved reversal journal';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid accounting journal transition';
END; $$;

CREATE TRIGGER accounting_journals_guard_history
    BEFORE UPDATE ON accounting_journals
    FOR EACH ROW EXECUTE FUNCTION guard_accounting_journal();

CREATE OR REPLACE FUNCTION guard_accounting_journal_line()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE journal_state VARCHAR(20);
DECLARE journal_source VARCHAR(20);
DECLARE account_record accounting_accounts%ROWTYPE;
DECLARE target_journal UUID;
BEGIN
    target_journal := CASE WHEN TG_OP = 'DELETE'
        THEN OLD.accounting_journal_id ELSE NEW.accounting_journal_id END;
    SELECT status, source_module INTO journal_state, journal_source
    FROM accounting_journals WHERE id = target_journal;
    IF journal_state <> 'DRAFT' THEN
        RAISE EXCEPTION 'posted or submitted accounting lines are immutable';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        SELECT * INTO account_record FROM accounting_accounts
        WHERE id = NEW.account_id AND organisation_id = NEW.organisation_id
          AND school_id = NEW.school_id;
        IF account_record.id IS NULL
           OR (journal_source <> 'REVERSAL'
               AND (account_record.status <> 'ACTIVE' OR account_record.allow_direct_posting = FALSE)) THEN
            RAISE EXCEPTION 'accounting line requires an active posting account';
        END IF;
        IF NEW.account_code_snapshot <> account_record.code
           OR NEW.account_name_snapshot <> account_record.display_name
           OR NEW.account_type_snapshot <> account_record.account_type THEN
            RAISE EXCEPTION 'accounting line snapshot must match its posting account';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END; $$;

CREATE TRIGGER accounting_journal_lines_guard_history
    BEFORE INSERT OR UPDATE OR DELETE ON accounting_journal_lines
    FOR EACH ROW EXECUTE FUNCTION guard_accounting_journal_line();

CREATE OR REPLACE FUNCTION guard_accounting_reversal_request()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE reversal_record accounting_journals%ROWTYPE;
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.accounting_journal_id <> NEW.accounting_journal_id
       OR OLD.requested_accounting_period_id <> NEW.requested_accounting_period_id
       OR OLD.requested_posting_date <> NEW.requested_posting_date
       OR OLD.request_reason <> NEW.request_reason
       OR OLD.requested_by_membership_id <> NEW.requested_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'accounting reversal request evidence is immutable';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'invalid accounting reversal decision transition';
    END IF;
    IF NEW.status = 'APPROVED' THEN
        SELECT * INTO reversal_record FROM accounting_journals
        WHERE id = NEW.reversal_journal_id AND organisation_id = NEW.organisation_id
          AND school_id = NEW.school_id;
        IF reversal_record.id IS NULL OR reversal_record.status <> 'POSTED'
           OR reversal_record.source_module <> 'REVERSAL'
           OR reversal_record.reversal_of_journal_id <> NEW.accounting_journal_id
           OR reversal_record.accounting_period_id <> NEW.requested_accounting_period_id
           OR reversal_record.posting_date <> NEW.requested_posting_date THEN
            RAISE EXCEPTION 'approved reversal request requires its matching posted counter-voucher';
        END IF;
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER accounting_reversal_requests_guard_history
    BEFORE UPDATE ON accounting_reversal_requests
    FOR EACH ROW EXECUTE FUNCTION guard_accounting_reversal_request();

CREATE OR REPLACE FUNCTION validate_accounting_reversal_request()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original_status VARCHAR(20);
DECLARE original_reversal UUID;
DECLARE period_state VARCHAR(20);
DECLARE year_state VARCHAR(20);
BEGIN
    SELECT status, reversal_of_journal_id INTO original_status, original_reversal
    FROM accounting_journals
    WHERE id = NEW.accounting_journal_id AND organisation_id = NEW.organisation_id
      AND school_id = NEW.school_id;
    IF original_status <> 'POSTED' OR original_reversal IS NOT NULL THEN
        RAISE EXCEPTION 'only an unreversed posted source journal can be reversed';
    END IF;
    SELECT period.status, year.status INTO period_state, year_state
    FROM accounting_periods period
    JOIN accounting_financial_years year ON year.id = period.financial_year_id
    WHERE period.id = NEW.requested_accounting_period_id
      AND period.organisation_id = NEW.organisation_id AND period.school_id = NEW.school_id
      AND NEW.requested_posting_date BETWEEN period.starts_on AND period.ends_on;
    IF period_state <> 'OPEN' OR year_state <> 'OPEN' THEN
        RAISE EXCEPTION 'reversal date must be in an open accounting period and financial year';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER accounting_reversal_requests_validate
    BEFORE INSERT ON accounting_reversal_requests
    FOR EACH ROW EXECUTE FUNCTION validate_accounting_reversal_request();

CREATE OR REPLACE FUNCTION prevent_accounting_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'posted accounting history is append-only'; END; $$;

CREATE TRIGGER accounting_journal_history_immutable
    BEFORE UPDATE OR DELETE ON accounting_journal_history
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();
CREATE TRIGGER accounting_reversal_requests_no_delete
    BEFORE DELETE ON accounting_reversal_requests
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();
CREATE TRIGGER accounting_journals_no_delete
    BEFORE DELETE ON accounting_journals
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();
CREATE TRIGGER accounting_financial_years_no_delete
    BEFORE DELETE ON accounting_financial_years
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();
CREATE TRIGGER accounting_periods_no_delete
    BEFORE DELETE ON accounting_periods
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();
CREATE TRIGGER accounting_accounts_no_delete
    BEFORE DELETE ON accounting_accounts
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();
CREATE TRIGGER accounting_vendors_no_delete
    BEFORE DELETE ON accounting_vendors
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();
CREATE TRIGGER accounting_journal_sequences_no_delete
    BEFORE DELETE ON accounting_journal_sequences
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();

ALTER TABLE accounting_financial_years ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_periods ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_vendors ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_journal_sequences ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_journals ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_journal_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_journal_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_reversal_requests ENABLE ROW LEVEL SECURITY;

CREATE POLICY accounting_financial_years_tenant_all ON accounting_financial_years
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY accounting_periods_tenant_all ON accounting_periods
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY accounting_accounts_tenant_all ON accounting_accounts
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY accounting_vendors_tenant_all ON accounting_vendors
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY accounting_journal_sequences_tenant_all ON accounting_journal_sequences
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY accounting_journals_tenant_all ON accounting_journals
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY accounting_journal_lines_tenant_all ON accounting_journal_lines
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY accounting_journal_history_tenant_all ON accounting_journal_history
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY accounting_reversal_requests_tenant_all ON accounting_reversal_requests
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE accounting_financial_years IS
    'School-scoped financial years. Accounting basis is explicit configuration and opening requires an independent actor.';
COMMENT ON TABLE accounting_accounts IS
    'School-scoped hierarchical chart of accounts with immutable classifications and configurable active posting leaves.';
COMMENT ON TABLE accounting_journals IS
    'Controlled voucher headers. Posted entries are immutable and corrected only by an independently approved counter-voucher.';
COMMENT ON TABLE accounting_journal_lines IS
    'Double-entry lines with frozen account snapshots and optional vendor or learner-fee subledger dimensions.';
COMMENT ON TABLE accounting_journal_history IS
    'Append-only voucher lifecycle evidence.';
COMMENT ON TABLE accounting_reversal_requests IS
    'Maker-checker request that fixes the open target period and date for a deterministic posted reversal.';

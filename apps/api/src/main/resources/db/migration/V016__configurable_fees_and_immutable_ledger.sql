INSERT INTO capabilities (code, description, risk_level) VALUES
    ('fees.configure', 'Configure fee heads and draft fee plans', 'RESTRICTED'),
    ('fees.collect', 'Record verified offline fee collections and issue receipts', 'RESTRICTED'),
    ('fees.approve', 'Activate fee plans and approve concessions', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'fees.configure'),
    ('00000000-0000-4000-8000-000000000001', 'fees.collect'),
    ('00000000-0000-4000-8000-000000000001', 'fees.approve'),
    ('00000000-0000-4000-8000-000000000001', 'fees.refund'),
    ('00000000-0000-4000-8000-000000000004', 'fees.configure'),
    ('00000000-0000-4000-8000-000000000004', 'fees.collect')
ON CONFLICT DO NOTHING;

CREATE TABLE fee_heads (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    code VARCHAR(30) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    category VARCHAR(30) NOT NULL,
    refundable BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fee_heads_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_heads_school_fk FOREIGN KEY (school_id, organisation_id)
        REFERENCES schools(id, organisation_id),
    CONSTRAINT fee_heads_code_unique UNIQUE (school_id, code),
    CONSTRAINT fee_heads_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT fee_heads_category_valid CHECK (
        category IN ('TUITION', 'ADMISSION', 'ANNUAL', 'MATERIAL', 'TRANSPORT', 'ACTIVITY', 'EXAM', 'OTHER')
    ),
    CONSTRAINT fee_heads_status_valid CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE fee_plans (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    supersedes_plan_id UUID,
    plan_code VARCHAR(40) NOT NULL,
    revision_number INTEGER NOT NULL DEFAULT 1,
    display_name VARCHAR(140) NOT NULL,
    grade_code VARCHAR(40),
    currency_code CHAR(3) NOT NULL,
    public_disclosure BOOLEAN NOT NULL DEFAULT FALSE,
    receipt_prefix VARCHAR(24) NOT NULL,
    notes VARCHAR(1000),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    activated_by_membership_id UUID,
    activated_at TIMESTAMPTZ,
    retired_by_membership_id UUID,
    retired_at TIMESTAMPTZ,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fee_plans_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_plans_session_fk FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT fee_plans_supersedes_fk FOREIGN KEY (supersedes_plan_id, organisation_id, school_id)
        REFERENCES fee_plans(id, organisation_id, school_id),
    CONSTRAINT fee_plans_creator_fk FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_plans_activator_fk FOREIGN KEY (activated_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_plans_retirer_fk FOREIGN KEY (retired_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_plans_revision_unique UNIQUE (school_id, academic_session_id, plan_code, revision_number),
    CONSTRAINT fee_plans_code_valid CHECK (plan_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT fee_plans_revision_valid CHECK (revision_number BETWEEN 1 AND 1000),
    CONSTRAINT fee_plans_currency_valid CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT fee_plans_prefix_valid CHECK (receipt_prefix ~ '^[A-Z0-9][A-Z0-9/-]{1,23}$'),
    CONSTRAINT fee_plans_status_valid CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT fee_plans_lifecycle_valid CHECK (
        (status = 'DRAFT' AND activated_by_membership_id IS NULL AND activated_at IS NULL
            AND retired_by_membership_id IS NULL AND retired_at IS NULL AND public_disclosure = FALSE)
        OR (status = 'ACTIVE' AND activated_by_membership_id IS NOT NULL AND activated_at IS NOT NULL
            AND retired_by_membership_id IS NULL AND retired_at IS NULL)
        OR (status = 'RETIRED' AND activated_by_membership_id IS NOT NULL AND activated_at IS NOT NULL
            AND retired_by_membership_id IS NOT NULL AND retired_at IS NOT NULL)
    )
);

CREATE TABLE fee_plan_items (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    fee_plan_id UUID NOT NULL,
    fee_head_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    label VARCHAR(140) NOT NULL,
    due_on DATE NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    allow_partial BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fee_plan_items_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_plan_items_plan_fk FOREIGN KEY (fee_plan_id, organisation_id, school_id)
        REFERENCES fee_plans(id, organisation_id, school_id),
    CONSTRAINT fee_plan_items_head_fk FOREIGN KEY (fee_head_id, organisation_id, school_id)
        REFERENCES fee_heads(id, organisation_id, school_id),
    CONSTRAINT fee_plan_items_sequence_unique UNIQUE (fee_plan_id, sequence_number),
    CONSTRAINT fee_plan_items_sequence_valid CHECK (sequence_number BETWEEN 1 AND 1000),
    CONSTRAINT fee_plan_items_amount_valid CHECK (amount > 0 AND amount <= 100000000)
);

CREATE TABLE learner_fee_accounts (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    enrolment_id UUID NOT NULL,
    currency_code CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    opened_by_membership_id UUID NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_by_membership_id UUID,
    closed_at TIMESTAMPTZ,
    close_reason VARCHAR(240),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT learner_fee_accounts_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT learner_fee_accounts_session_fk FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT learner_fee_accounts_learner_fk FOREIGN KEY (learner_id, organisation_id, school_id)
        REFERENCES learners(id, organisation_id, school_id),
    CONSTRAINT learner_fee_accounts_enrolment_fk FOREIGN KEY (enrolment_id, organisation_id, school_id)
        REFERENCES student_enrolments(id, organisation_id, school_id),
    CONSTRAINT learner_fee_accounts_opener_fk FOREIGN KEY (opened_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT learner_fee_accounts_closer_fk FOREIGN KEY (closed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT learner_fee_accounts_unique UNIQUE (school_id, academic_session_id, learner_id),
    CONSTRAINT learner_fee_accounts_currency_valid CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT learner_fee_accounts_status_valid CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT learner_fee_accounts_lifecycle_valid CHECK (
        (status = 'ACTIVE' AND closed_by_membership_id IS NULL AND closed_at IS NULL AND close_reason IS NULL)
        OR (status = 'CLOSED' AND closed_by_membership_id IS NOT NULL AND closed_at IS NOT NULL
            AND length(btrim(close_reason)) BETWEEN 1 AND 240)
    )
);

CREATE TABLE fee_plan_assignments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_fee_account_id UUID NOT NULL,
    fee_plan_id UUID NOT NULL,
    plan_snapshot JSONB NOT NULL,
    assigned_by_membership_id UUID NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(240) NOT NULL,
    CONSTRAINT fee_plan_assignments_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_plan_assignments_account_fk FOREIGN KEY (learner_fee_account_id, organisation_id, school_id)
        REFERENCES learner_fee_accounts(id, organisation_id, school_id),
    CONSTRAINT fee_plan_assignments_plan_fk FOREIGN KEY (fee_plan_id, organisation_id, school_id)
        REFERENCES fee_plans(id, organisation_id, school_id),
    CONSTRAINT fee_plan_assignments_actor_fk FOREIGN KEY (assigned_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_plan_assignments_unique UNIQUE (learner_fee_account_id, fee_plan_id),
    CONSTRAINT fee_plan_assignments_snapshot_valid CHECK (jsonb_typeof(plan_snapshot) = 'object')
);

CREATE TABLE learner_fee_charges (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_fee_account_id UUID NOT NULL,
    fee_plan_assignment_id UUID NOT NULL,
    fee_plan_item_id UUID NOT NULL,
    fee_head_code_snapshot VARCHAR(30) NOT NULL,
    fee_head_name_snapshot VARCHAR(120) NOT NULL,
    label_snapshot VARCHAR(140) NOT NULL,
    due_on DATE NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    allow_partial BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT learner_fee_charges_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT learner_fee_charges_account_fk FOREIGN KEY (learner_fee_account_id, organisation_id, school_id)
        REFERENCES learner_fee_accounts(id, organisation_id, school_id),
    CONSTRAINT learner_fee_charges_assignment_fk FOREIGN KEY (fee_plan_assignment_id, organisation_id, school_id)
        REFERENCES fee_plan_assignments(id, organisation_id, school_id),
    CONSTRAINT learner_fee_charges_item_fk FOREIGN KEY (fee_plan_item_id, organisation_id, school_id)
        REFERENCES fee_plan_items(id, organisation_id, school_id),
    CONSTRAINT learner_fee_charges_item_unique UNIQUE (fee_plan_assignment_id, fee_plan_item_id),
    CONSTRAINT learner_fee_charges_amount_valid CHECK (amount > 0 AND amount <= 100000000)
);

CREATE TABLE fee_ledger_entries (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_fee_account_id UUID NOT NULL,
    learner_fee_charge_id UUID,
    entry_type VARCHAR(30) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    effective_on DATE NOT NULL,
    description VARCHAR(240) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id UUID NOT NULL,
    reverses_entry_id UUID,
    created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fee_ledger_entries_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_ledger_entries_account_fk FOREIGN KEY (learner_fee_account_id, organisation_id, school_id)
        REFERENCES learner_fee_accounts(id, organisation_id, school_id),
    CONSTRAINT fee_ledger_entries_charge_fk FOREIGN KEY (learner_fee_charge_id, organisation_id, school_id)
        REFERENCES learner_fee_charges(id, organisation_id, school_id),
    CONSTRAINT fee_ledger_entries_reversal_fk FOREIGN KEY (reverses_entry_id, organisation_id, school_id)
        REFERENCES fee_ledger_entries(id, organisation_id, school_id),
    CONSTRAINT fee_ledger_entries_actor_fk FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_ledger_entries_amount_valid CHECK (amount > 0 AND amount <= 100000000),
    CONSTRAINT fee_ledger_entries_type_valid CHECK (
        entry_type IN ('CHARGE', 'PAYMENT', 'CONCESSION', 'PAYMENT_REVERSAL', 'ADJUSTMENT', 'REFUND')
    ),
    CONSTRAINT fee_ledger_entries_direction_valid CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT fee_ledger_entries_reversal_valid CHECK (
        (entry_type = 'PAYMENT_REVERSAL' AND direction = 'DEBIT' AND reverses_entry_id IS NOT NULL)
        OR (entry_type <> 'PAYMENT_REVERSAL' AND reverses_entry_id IS NULL)
    ),
    CONSTRAINT fee_ledger_entries_source_unique UNIQUE (learner_fee_account_id, source_type, source_id, entry_type)
);

CREATE TABLE fee_receipt_sequences (
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    receipt_prefix VARCHAR(24) NOT NULL,
    next_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (school_id, academic_session_id, receipt_prefix),
    CONSTRAINT fee_receipt_sequences_session_unique UNIQUE (school_id, academic_session_id),
    CONSTRAINT fee_receipt_sequences_session_fk FOREIGN KEY (academic_session_id, organisation_id, school_id)
        REFERENCES academic_sessions(id, organisation_id, school_id),
    CONSTRAINT fee_receipt_sequences_value_valid CHECK (next_value BETWEEN 1 AND 999999999),
    CONSTRAINT fee_receipt_sequences_prefix_valid CHECK (receipt_prefix ~ '^[A-Z0-9][A-Z0-9/-]{1,23}$')
);

CREATE TABLE fee_payments (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_fee_account_id UUID NOT NULL,
    receipt_number VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    external_reference VARCHAR(120),
    amount NUMERIC(14,2) NOT NULL,
    received_on DATE NOT NULL,
    notes VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'POSTED',
    ledger_entry_id UUID NOT NULL,
    received_by_membership_id UUID NOT NULL,
    reversed_by_membership_id UUID,
    reversed_at TIMESTAMPTZ,
    reversal_reason VARCHAR(240),
    reversal_ledger_entry_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fee_payments_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_payments_account_fk FOREIGN KEY (learner_fee_account_id, organisation_id, school_id)
        REFERENCES learner_fee_accounts(id, organisation_id, school_id),
    CONSTRAINT fee_payments_ledger_fk FOREIGN KEY (ledger_entry_id, organisation_id, school_id)
        REFERENCES fee_ledger_entries(id, organisation_id, school_id),
    CONSTRAINT fee_payments_reversal_ledger_fk FOREIGN KEY (reversal_ledger_entry_id, organisation_id, school_id)
        REFERENCES fee_ledger_entries(id, organisation_id, school_id),
    CONSTRAINT fee_payments_receiver_fk FOREIGN KEY (received_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_payments_reverser_fk FOREIGN KEY (reversed_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_payments_receipt_unique UNIQUE (school_id, receipt_number),
    CONSTRAINT fee_payments_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT fee_payments_amount_valid CHECK (amount > 0 AND amount <= 100000000),
    CONSTRAINT fee_payments_method_valid CHECK (
        payment_method IN ('CASH', 'BANK_TRANSFER', 'CHEQUE', 'SCHOOL_UPI', 'ONLINE_PROVIDER')
    ),
    CONSTRAINT fee_payments_status_valid CHECK (status IN ('POSTED', 'REVERSED')),
    CONSTRAINT fee_payments_lifecycle_valid CHECK (
        (status = 'POSTED' AND reversed_by_membership_id IS NULL AND reversed_at IS NULL
            AND reversal_reason IS NULL AND reversal_ledger_entry_id IS NULL)
        OR (status = 'REVERSED' AND reversed_by_membership_id IS NOT NULL AND reversed_at IS NOT NULL
            AND length(btrim(reversal_reason)) BETWEEN 1 AND 240 AND reversal_ledger_entry_id IS NOT NULL)
    )
);

CREATE TABLE fee_payment_allocations (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    fee_payment_id UUID NOT NULL,
    learner_fee_charge_id UUID NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fee_payment_allocations_payment_fk FOREIGN KEY (fee_payment_id, organisation_id, school_id)
        REFERENCES fee_payments(id, organisation_id, school_id),
    CONSTRAINT fee_payment_allocations_charge_fk FOREIGN KEY (learner_fee_charge_id, organisation_id, school_id)
        REFERENCES learner_fee_charges(id, organisation_id, school_id),
    CONSTRAINT fee_payment_allocations_unique UNIQUE (fee_payment_id, learner_fee_charge_id),
    CONSTRAINT fee_payment_allocations_amount_valid CHECK (amount > 0 AND amount <= 100000000)
);

CREATE TABLE fee_concession_requests (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    learner_fee_account_id UUID NOT NULL,
    learner_fee_charge_id UUID NOT NULL,
    concession_type VARCHAR(30) NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    request_reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    requested_by_membership_id UUID NOT NULL,
    decided_by_membership_id UUID,
    decision_reason VARCHAR(500),
    decided_at TIMESTAMPTZ,
    ledger_entry_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fee_concessions_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_concessions_account_fk FOREIGN KEY (learner_fee_account_id, organisation_id, school_id)
        REFERENCES learner_fee_accounts(id, organisation_id, school_id),
    CONSTRAINT fee_concessions_charge_fk FOREIGN KEY (learner_fee_charge_id, organisation_id, school_id)
        REFERENCES learner_fee_charges(id, organisation_id, school_id),
    CONSTRAINT fee_concessions_requester_fk FOREIGN KEY (requested_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_concessions_decider_fk FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_concessions_ledger_fk FOREIGN KEY (ledger_entry_id, organisation_id, school_id)
        REFERENCES fee_ledger_entries(id, organisation_id, school_id),
    CONSTRAINT fee_concessions_type_valid CHECK (concession_type IN ('SCHOLARSHIP', 'SIBLING', 'STAFF', 'HARDSHIP', 'OTHER')),
    CONSTRAINT fee_concessions_amount_valid CHECK (amount > 0 AND amount <= 100000000),
    CONSTRAINT fee_concessions_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT fee_concessions_lifecycle_valid CHECK (
        (status = 'PENDING' AND decided_by_membership_id IS NULL AND decision_reason IS NULL
            AND decided_at IS NULL AND ledger_entry_id IS NULL)
        OR (status = 'APPROVED' AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> requested_by_membership_id
            AND length(btrim(decision_reason)) BETWEEN 1 AND 500 AND decided_at IS NOT NULL
            AND ledger_entry_id IS NOT NULL)
        OR (status = 'REJECTED' AND decided_by_membership_id IS NOT NULL
            AND decided_by_membership_id <> requested_by_membership_id
            AND length(btrim(decision_reason)) BETWEEN 1 AND 500 AND decided_at IS NOT NULL
            AND ledger_entry_id IS NULL)
    )
);

CREATE TABLE payment_provider_configs (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    provider_code VARCHAR(40) NOT NULL,
    checkout_mode VARCHAR(20) NOT NULL DEFAULT 'REDIRECT',
    environment VARCHAR(20) NOT NULL DEFAULT 'TEST',
    credentials_secret_reference VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    configured_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payment_provider_configs_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payment_provider_configs_actor_fk FOREIGN KEY (configured_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payment_provider_configs_unique UNIQUE (school_id, provider_code, environment),
    CONSTRAINT payment_provider_configs_provider_valid CHECK (provider_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT payment_provider_configs_redirect_only CHECK (checkout_mode = 'REDIRECT'),
    CONSTRAINT payment_provider_configs_environment_valid CHECK (environment IN ('TEST', 'LIVE')),
    CONSTRAINT payment_provider_configs_status_valid CHECK (status IN ('INACTIVE', 'ACTIVE'))
);

CREATE INDEX fee_plans_scope_idx ON fee_plans (organisation_id, school_id, academic_session_id, status, grade_code);
CREATE INDEX fee_plan_items_plan_idx ON fee_plan_items (fee_plan_id, due_on, sequence_number);
CREATE INDEX learner_fee_accounts_learner_idx ON learner_fee_accounts (organisation_id, school_id, learner_id, academic_session_id);
CREATE INDEX learner_fee_charges_due_idx ON learner_fee_charges (learner_fee_account_id, due_on, created_at);
CREATE INDEX fee_ledger_entries_account_idx ON fee_ledger_entries (learner_fee_account_id, effective_on, created_at, id);
CREATE INDEX fee_payments_account_idx ON fee_payments (learner_fee_account_id, received_on, created_at);
CREATE INDEX fee_concessions_status_idx ON fee_concession_requests (organisation_id, school_id, status, created_at);

CREATE OR REPLACE FUNCTION guard_fee_plan()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.academic_session_id <> NEW.academic_session_id OR OLD.plan_code <> NEW.plan_code
       OR OLD.revision_number <> NEW.revision_number OR OLD.supersedes_plan_id IS DISTINCT FROM NEW.supersedes_plan_id
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'fee plan identity and revision are immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'ACTIVE') THEN
        RAISE EXCEPTION 'invalid fee plan transition';
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status NOT IN ('ACTIVE', 'RETIRED') THEN
        RAISE EXCEPTION 'invalid fee plan transition';
    END IF;
    IF OLD.status = 'RETIRED' THEN RAISE EXCEPTION 'retired fee plans are immutable'; END IF;
    IF OLD.status <> 'DRAFT' AND (
        OLD.display_name <> NEW.display_name OR OLD.grade_code IS DISTINCT FROM NEW.grade_code
        OR OLD.currency_code <> NEW.currency_code OR OLD.receipt_prefix <> NEW.receipt_prefix
        OR OLD.notes IS DISTINCT FROM NEW.notes OR OLD.public_disclosure <> NEW.public_disclosure
    ) THEN RAISE EXCEPTION 'active fee plan content is immutable'; END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER fee_plans_guard BEFORE UPDATE ON fee_plans
    FOR EACH ROW EXECUTE FUNCTION guard_fee_plan();

CREATE OR REPLACE FUNCTION guard_fee_head_identity()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.code <> NEW.code OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'fee head identity is immutable';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER fee_heads_guard_identity BEFORE UPDATE ON fee_heads
    FOR EACH ROW EXECUTE FUNCTION guard_fee_head_identity();

CREATE OR REPLACE FUNCTION guard_fee_plan_item()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE plan_status VARCHAR(20);
BEGIN
    SELECT status INTO plan_status FROM fee_plans WHERE id = COALESCE(NEW.fee_plan_id, OLD.fee_plan_id);
    IF plan_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'fee plan items can only change while the plan is draft';
    END IF;
    IF TG_OP = 'UPDATE' AND (OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.fee_plan_id <> NEW.fee_plan_id
       OR OLD.created_at <> NEW.created_at) THEN RAISE EXCEPTION 'fee plan item identity is immutable'; END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER fee_plan_items_guard BEFORE INSERT OR UPDATE OR DELETE ON fee_plan_items
    FOR EACH ROW EXECUTE FUNCTION guard_fee_plan_item();

CREATE OR REPLACE FUNCTION prevent_finance_evidence_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'posted financial evidence is append-only'; END; $$;

CREATE TRIGGER fee_plan_assignments_immutable BEFORE UPDATE OR DELETE ON fee_plan_assignments
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
CREATE TRIGGER learner_fee_charges_immutable BEFORE UPDATE OR DELETE ON learner_fee_charges
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
CREATE TRIGGER fee_ledger_entries_immutable BEFORE UPDATE OR DELETE ON fee_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
CREATE TRIGGER fee_payment_allocations_immutable BEFORE UPDATE OR DELETE ON fee_payment_allocations
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();

CREATE OR REPLACE FUNCTION guard_fee_payment()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.learner_fee_account_id <> NEW.learner_fee_account_id OR OLD.receipt_number <> NEW.receipt_number
       OR OLD.idempotency_key <> NEW.idempotency_key OR OLD.request_fingerprint <> NEW.request_fingerprint
       OR OLD.payment_method <> NEW.payment_method OR OLD.external_reference IS DISTINCT FROM NEW.external_reference
       OR OLD.amount <> NEW.amount OR OLD.received_on <> NEW.received_on OR OLD.notes IS DISTINCT FROM NEW.notes
       OR OLD.ledger_entry_id <> NEW.ledger_entry_id OR OLD.received_by_membership_id <> NEW.received_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN RAISE EXCEPTION 'issued receipt evidence is immutable'; END IF;
    IF OLD.status <> 'POSTED' OR NEW.status <> 'REVERSED'
       OR current_setting('app.finance_reversal', TRUE) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'payments can only be reversed through the approved reversal workflow';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER fee_payments_guard BEFORE UPDATE ON fee_payments
    FOR EACH ROW EXECUTE FUNCTION guard_fee_payment();
CREATE TRIGGER fee_payments_prevent_delete BEFORE DELETE ON fee_payments
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();

CREATE OR REPLACE FUNCTION guard_fee_concession()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.learner_fee_account_id <> NEW.learner_fee_account_id
       OR OLD.learner_fee_charge_id <> NEW.learner_fee_charge_id OR OLD.concession_type <> NEW.concession_type
       OR OLD.amount <> NEW.amount OR OLD.request_reason <> NEW.request_reason
       OR OLD.requested_by_membership_id <> NEW.requested_by_membership_id OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'concession request evidence is immutable';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'invalid concession transition';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER fee_concessions_guard BEFORE UPDATE ON fee_concession_requests
    FOR EACH ROW EXECUTE FUNCTION guard_fee_concession();
CREATE TRIGGER fee_concessions_prevent_delete BEFORE DELETE ON fee_concession_requests
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();

ALTER TABLE fee_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_plan_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE learner_fee_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_plan_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE learner_fee_charges ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_ledger_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_receipt_sequences ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_payment_allocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_concession_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_provider_configs ENABLE ROW LEVEL SECURITY;

CREATE POLICY fee_heads_tenant_all ON fee_heads USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY fee_plans_tenant_all ON fee_plans USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY fee_plan_items_tenant_all ON fee_plan_items USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY learner_fee_accounts_tenant_all ON learner_fee_accounts USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY fee_plan_assignments_tenant_all ON fee_plan_assignments USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY learner_fee_charges_tenant_all ON learner_fee_charges USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY fee_ledger_entries_tenant_all ON fee_ledger_entries USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY fee_receipt_sequences_tenant_all ON fee_receipt_sequences USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY fee_payments_tenant_all ON fee_payments USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY fee_payment_allocations_tenant_all ON fee_payment_allocations USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY fee_concessions_tenant_all ON fee_concession_requests USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY payment_provider_configs_tenant_all ON payment_provider_configs USING (organisation_id = current_app_organisation_id()) WITH CHECK (organisation_id = current_app_organisation_id());

-- Public database visibility is deliberately limited to active school identity and fee revisions
-- that a school authority explicitly approved for disclosure. Drafts and learner finance remain private.
CREATE POLICY schools_public_active_read ON schools FOR SELECT USING (status = 'ACTIVE');
CREATE POLICY fee_plans_public_disclosure_read ON fee_plans FOR SELECT
    USING (status = 'ACTIVE' AND public_disclosure = TRUE);
CREATE POLICY academic_sessions_public_fee_read ON academic_sessions FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM fee_plans plan
        WHERE plan.academic_session_id = academic_sessions.id
          AND plan.status = 'ACTIVE' AND plan.public_disclosure = TRUE
    ));
CREATE POLICY fee_plan_items_public_disclosure_read ON fee_plan_items FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM fee_plans plan
        WHERE plan.id = fee_plan_items.fee_plan_id
          AND plan.status = 'ACTIVE' AND plan.public_disclosure = TRUE
    ));
CREATE POLICY fee_heads_public_disclosure_read ON fee_heads FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM fee_plan_items item JOIN fee_plans plan ON plan.id = item.fee_plan_id
        WHERE item.fee_head_id = fee_heads.id
          AND plan.status = 'ACTIVE' AND plan.public_disclosure = TRUE
    ));

COMMENT ON TABLE fee_plans IS 'Versioned, authority-activated fee schedules. Drafts are editable; activated content is immutable and must be superseded.';
COMMENT ON TABLE learner_fee_charges IS 'Frozen charges created from an activated plan assignment; operational configuration never rewrites learner history.';
COMMENT ON TABLE fee_ledger_entries IS 'Append-only account movements. Corrections use explicit counter-entries rather than updates or deletion.';
COMMENT ON TABLE fee_payments IS 'Sequential receipt evidence for a verified collection. Reversal preserves the original receipt and appends a counter-entry.';
COMMENT ON TABLE payment_provider_configs IS 'Provider-neutral redirect-only configuration. Secret values belong in a secret manager and card credentials never enter the platform.';

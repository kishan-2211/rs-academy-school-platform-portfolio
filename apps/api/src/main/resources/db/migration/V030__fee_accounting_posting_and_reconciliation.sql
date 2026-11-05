CREATE TABLE fee_accounting_configurations (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    financial_year_id UUID NOT NULL,
    supersedes_configuration_id UUID,
    revision_number SMALLINT NOT NULL,
    effective_from DATE NOT NULL,
    accounting_basis_snapshot VARCHAR(20) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    receivable_account_id UUID,
    concession_account_id UUID,
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
    CONSTRAINT fee_accounting_configurations_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_accounting_configurations_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT fee_accounting_configurations_year_fk
        FOREIGN KEY (financial_year_id, organisation_id, school_id)
        REFERENCES accounting_financial_years(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_configurations_supersedes_fk
        FOREIGN KEY (supersedes_configuration_id, organisation_id, school_id)
        REFERENCES fee_accounting_configurations(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_configurations_receivable_fk
        FOREIGN KEY (receivable_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_configurations_concession_fk
        FOREIGN KEY (concession_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_configurations_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_configurations_submitter_fk
        FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_configurations_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_configurations_superseder_fk
        FOREIGN KEY (superseded_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_configurations_revision_unique
        UNIQUE (school_id, financial_year_id, revision_number),
    CONSTRAINT fee_accounting_configurations_revision_valid
        CHECK (revision_number BETWEEN 1 AND 1000),
    CONSTRAINT fee_accounting_configurations_basis_valid
        CHECK (accounting_basis_snapshot IN ('CASH','ACCRUAL')),
    CONSTRAINT fee_accounting_configurations_currency_valid
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT fee_accounting_configurations_accounts_valid CHECK (
        (accounting_basis_snapshot = 'ACCRUAL'
            AND receivable_account_id IS NOT NULL AND concession_account_id IS NOT NULL)
        OR (accounting_basis_snapshot = 'CASH'
            AND receivable_account_id IS NULL AND concession_account_id IS NULL)),
    CONSTRAINT fee_accounting_configurations_status_valid
        CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','SUPERSEDED')),
    CONSTRAINT fee_accounting_configurations_reasons_valid CHECK (
        length(btrim(creation_reason)) BETWEEN 3 AND 500
        AND (submission_reason IS NULL OR length(btrim(submission_reason)) BETWEEN 3 AND 500)
        AND (decision_reason IS NULL OR length(btrim(decision_reason)) BETWEEN 3 AND 500)
        AND (supersede_reason IS NULL OR length(btrim(supersede_reason)) BETWEEN 3 AND 500)),
    CONSTRAINT fee_accounting_configurations_lifecycle_valid CHECK (
        (status = 'DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND submission_reason IS NULL AND decided_by_membership_id IS NULL AND decided_at IS NULL
            AND decision_reason IS NULL AND superseded_by_membership_id IS NULL
            AND superseded_at IS NULL AND supersede_reason IS NULL)
        OR (status = 'SUBMITTED' AND submitted_by_membership_id = created_by_membership_id
            AND submitted_at IS NOT NULL AND submission_reason IS NOT NULL
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
            AND superseded_by_membership_id IS NULL AND superseded_at IS NULL
            AND supersede_reason IS NULL)
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
    CONSTRAINT fee_accounting_configurations_not_self_superseding
        CHECK (supersedes_configuration_id IS NULL OR supersedes_configuration_id <> id)
);

CREATE UNIQUE INDEX fee_accounting_configurations_one_working_idx
    ON fee_accounting_configurations (school_id, financial_year_id)
    WHERE status IN ('DRAFT','SUBMITTED');
CREATE UNIQUE INDEX fee_accounting_configurations_one_current_idx
    ON fee_accounting_configurations (school_id, financial_year_id)
    WHERE status = 'APPROVED';
CREATE INDEX fee_accounting_configurations_effective_idx
    ON fee_accounting_configurations (
        organisation_id, school_id, financial_year_id, effective_from DESC, status);

CREATE TABLE fee_accounting_head_mappings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    configuration_id UUID NOT NULL,
    fee_head_id UUID NOT NULL,
    income_account_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fee_accounting_head_mappings_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_accounting_head_mappings_configuration_fk
        FOREIGN KEY (configuration_id, organisation_id, school_id)
        REFERENCES fee_accounting_configurations(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_head_mappings_head_fk
        FOREIGN KEY (fee_head_id, organisation_id, school_id)
        REFERENCES fee_heads(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_head_mappings_income_fk
        FOREIGN KEY (income_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_head_mappings_unique UNIQUE (configuration_id, fee_head_id)
);

CREATE TABLE fee_accounting_tender_mappings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    configuration_id UUID NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    deposit_account_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fee_accounting_tender_mappings_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_accounting_tender_mappings_configuration_fk
        FOREIGN KEY (configuration_id, organisation_id, school_id)
        REFERENCES fee_accounting_configurations(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_tender_mappings_deposit_fk
        FOREIGN KEY (deposit_account_id, organisation_id, school_id)
        REFERENCES accounting_accounts(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_tender_mappings_method_valid CHECK (
        payment_method IN ('CASH','BANK_TRANSFER','CHEQUE','SCHOOL_UPI','ONLINE_PROVIDER')),
    CONSTRAINT fee_accounting_tender_mappings_unique
        UNIQUE (configuration_id, payment_method)
);

CREATE TABLE fee_accounting_posting_batches (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    configuration_id UUID NOT NULL,
    financial_year_id UUID NOT NULL,
    accounting_period_id UUID NOT NULL,
    business_date DATE NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    disposition VARCHAR(20) NOT NULL,
    sequence_number SMALLINT NOT NULL,
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
    CONSTRAINT fee_accounting_posting_batches_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_accounting_posting_batches_configuration_fk
        FOREIGN KEY (configuration_id, organisation_id, school_id)
        REFERENCES fee_accounting_configurations(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_posting_batches_year_fk
        FOREIGN KEY (financial_year_id, organisation_id, school_id)
        REFERENCES accounting_financial_years(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_posting_batches_period_fk
        FOREIGN KEY (accounting_period_id, organisation_id, school_id)
        REFERENCES accounting_periods(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_posting_batches_journal_fk
        FOREIGN KEY (accounting_journal_id, organisation_id, school_id)
        REFERENCES accounting_journals(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_posting_batches_preparer_fk
        FOREIGN KEY (prepared_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_posting_batches_idempotency_unique
        UNIQUE (school_id, idempotency_key),
    CONSTRAINT fee_accounting_posting_batches_sequence_unique
        UNIQUE (school_id, configuration_id, business_date, event_type, sequence_number),
    CONSTRAINT fee_accounting_posting_batches_journal_unique UNIQUE (accounting_journal_id),
    CONSTRAINT fee_accounting_posting_batches_event_valid CHECK (
        event_type IN ('CHARGE','CONCESSION','PAYMENT','PAYMENT_REVERSAL')),
    CONSTRAINT fee_accounting_posting_batches_disposition_valid CHECK (
        disposition IN ('JOURNAL','NO_GL_EFFECT')),
    CONSTRAINT fee_accounting_posting_batches_sequence_valid
        CHECK (sequence_number BETWEEN 1 AND 1000),
    CONSTRAINT fee_accounting_posting_batches_idempotency_valid
        CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 100),
    CONSTRAINT fee_accounting_posting_batches_fingerprint_valid
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fee_accounting_posting_batches_counts_valid
        CHECK (source_count BETWEEN 1 AND 200 AND source_amount > 0
            AND source_amount <= 1000000000000),
    CONSTRAINT fee_accounting_posting_batches_evidence_valid CHECK (
        jsonb_typeof(evidence_snapshot) = 'object'
        AND evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fee_accounting_posting_batches_reason_valid
        CHECK (length(btrim(preparation_reason)) BETWEEN 3 AND 500),
    CONSTRAINT fee_accounting_posting_batches_link_valid CHECK (
        (disposition = 'NO_GL_EFFECT' AND accounting_journal_id IS NULL
            AND journal_linked_at IS NULL)
        OR (disposition = 'JOURNAL'
            AND ((accounting_journal_id IS NULL AND journal_linked_at IS NULL)
                OR (accounting_journal_id IS NOT NULL AND journal_linked_at IS NOT NULL))))
);

CREATE INDEX fee_accounting_posting_batches_date_idx
    ON fee_accounting_posting_batches (
        organisation_id, school_id, business_date DESC, event_type, prepared_at DESC);

CREATE TABLE fee_accounting_posting_items (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    posting_batch_id UUID NOT NULL,
    fee_ledger_entry_id UUID NOT NULL,
    sequence_number SMALLINT NOT NULL,
    source_snapshot JSONB NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    released_by_membership_id UUID,
    released_at TIMESTAMPTZ,
    release_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fee_accounting_posting_items_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_accounting_posting_items_batch_fk
        FOREIGN KEY (posting_batch_id, organisation_id, school_id)
        REFERENCES fee_accounting_posting_batches(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_posting_items_entry_fk
        FOREIGN KEY (fee_ledger_entry_id, organisation_id, school_id)
        REFERENCES fee_ledger_entries(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_posting_items_releaser_fk
        FOREIGN KEY (released_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_accounting_posting_items_batch_sequence_unique
        UNIQUE (posting_batch_id, sequence_number),
    CONSTRAINT fee_accounting_posting_items_batch_entry_unique
        UNIQUE (posting_batch_id, fee_ledger_entry_id),
    CONSTRAINT fee_accounting_posting_items_sequence_valid
        CHECK (sequence_number BETWEEN 1 AND 200),
    CONSTRAINT fee_accounting_posting_items_snapshot_valid CHECK (
        jsonb_typeof(source_snapshot) = 'object' AND source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fee_accounting_posting_items_status_valid
        CHECK (status IN ('ACTIVE','RELEASED')),
    CONSTRAINT fee_accounting_posting_items_release_valid CHECK (
        (status = 'ACTIVE' AND released_by_membership_id IS NULL
            AND released_at IS NULL AND release_reason IS NULL)
        OR (status = 'RELEASED' AND released_by_membership_id IS NOT NULL
            AND released_at IS NOT NULL AND length(btrim(release_reason)) BETWEEN 3 AND 500))
);

CREATE UNIQUE INDEX fee_accounting_posting_items_one_active_idx
    ON fee_accounting_posting_items (school_id, fee_ledger_entry_id)
    WHERE status = 'ACTIVE';
CREATE INDEX fee_accounting_posting_items_batch_idx
    ON fee_accounting_posting_items (
        organisation_id, school_id, posting_batch_id, sequence_number);

CREATE OR REPLACE FUNCTION guard_fee_accounting_configuration()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    year_row accounting_financial_years%ROWTYPE;
    receivable_row accounting_accounts%ROWTYPE;
    concession_row accounting_accounts%ROWTYPE;
    fee_head_count INTEGER;
    mapped_head_count INTEGER;
    mapped_tender_count INTEGER;
    prior_row fee_accounting_configurations%ROWTYPE;
BEGIN
    SELECT * INTO year_row FROM accounting_financial_years
      WHERE id=NEW.financial_year_id AND organisation_id=NEW.organisation_id
        AND school_id=NEW.school_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Fee accounting configuration must use a financial year in the same school';
    END IF;
    IF NEW.effective_from < year_row.starts_on OR NEW.effective_from > year_row.ends_on
       OR NEW.accounting_basis_snapshot <> year_row.accounting_basis
       OR NEW.currency_code <> year_row.currency_code THEN
        RAISE EXCEPTION 'Fee accounting configuration must match its financial year basis, currency and dates';
    END IF;
    IF NEW.accounting_basis_snapshot = 'ACCRUAL' THEN
        IF TG_OP = 'INSERT'
           OR (TG_OP = 'UPDATE' AND (OLD.status = 'DRAFT'
               OR (OLD.status = 'SUBMITTED' AND NEW.status = 'APPROVED'))) THEN
            SELECT * INTO receivable_row FROM accounting_accounts
              WHERE id=NEW.receivable_account_id AND organisation_id=NEW.organisation_id
                AND school_id=NEW.school_id;
            SELECT * INTO concession_row FROM accounting_accounts
              WHERE id=NEW.concession_account_id AND organisation_id=NEW.organisation_id
                AND school_id=NEW.school_id;
            IF receivable_row.account_type <> 'ASSET' OR receivable_row.category <> 'RECEIVABLE'
               OR receivable_row.status <> 'ACTIVE' OR NOT receivable_row.allow_direct_posting THEN
                RAISE EXCEPTION 'Accrual fee accounting requires an active posting receivable account';
            END IF;
            IF concession_row.account_type <> 'EXPENSE' OR concession_row.category <> 'EXPENSE'
               OR concession_row.status <> 'ACTIVE' OR NOT concession_row.allow_direct_posting THEN
                RAISE EXCEPTION 'Accrual fee accounting requires an active posting concession expense account';
            END IF;
        END IF;
    END IF;
    IF NEW.supersedes_configuration_id IS NOT NULL THEN
        IF TG_OP = 'INSERT'
           OR (TG_OP = 'UPDATE'
               AND OLD.supersedes_configuration_id IS DISTINCT FROM NEW.supersedes_configuration_id) THEN
            SELECT * INTO prior_row FROM fee_accounting_configurations
              WHERE id=NEW.supersedes_configuration_id AND organisation_id=NEW.organisation_id
                AND school_id=NEW.school_id AND financial_year_id=NEW.financial_year_id;
            IF NOT FOUND OR prior_row.status <> 'APPROVED'
               OR NEW.effective_from <= prior_row.effective_from THEN
                RAISE EXCEPTION 'A fee accounting revision must supersede the current approved configuration prospectively';
            END IF;
        END IF;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Fee accounting configurations must begin as drafts';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.organisation_id <> NEW.organisation_id OR OLD.school_id <> NEW.school_id
       OR OLD.financial_year_id <> NEW.financial_year_id
       OR OLD.revision_number <> NEW.revision_number
       OR OLD.accounting_basis_snapshot <> NEW.accounting_basis_snapshot
       OR OLD.currency_code <> NEW.currency_code
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at OR OLD.creation_reason <> NEW.creation_reason THEN
        RAISE EXCEPTION 'Fee accounting configuration identity and origin are immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'DRAFT' THEN
        RETURN NEW;
    ELSIF OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED' THEN
        SELECT count(*) INTO fee_head_count FROM fee_heads
          WHERE organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        SELECT count(*) INTO mapped_head_count FROM fee_accounting_head_mappings mapping
          JOIN fee_heads head ON head.id=mapping.fee_head_id
            AND head.organisation_id=mapping.organisation_id AND head.school_id=mapping.school_id
          WHERE mapping.configuration_id=NEW.id AND mapping.organisation_id=NEW.organisation_id
            AND mapping.school_id=NEW.school_id;
        SELECT count(*) INTO mapped_tender_count FROM fee_accounting_tender_mappings
          WHERE configuration_id=NEW.id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF fee_head_count < 1 OR mapped_head_count <> fee_head_count
           OR mapped_tender_count <> 5 THEN
            RAISE EXCEPTION 'Map every fee head and all five payment methods before submission';
        END IF;
        RETURN NEW;
    ELSIF OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED','REJECTED') THEN
        IF NEW.status='APPROVED' AND NEW.supersedes_configuration_id IS NOT NULL
           AND EXISTS (
              SELECT 1 FROM fee_accounting_posting_batches
              WHERE configuration_id=NEW.supersedes_configuration_id
                AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id
                AND business_date>=NEW.effective_from) THEN
            RAISE EXCEPTION 'A fee accounting revision cannot change mapping for source evidence already prepared';
        END IF;
        RETURN NEW;
    ELSIF OLD.status = 'APPROVED' AND NEW.status = 'SUPERSEDED'
          AND current_setting('app.fee_accounting_supersede', true) = 'approved' THEN
        IF OLD.effective_from IS DISTINCT FROM NEW.effective_from
           OR OLD.receivable_account_id IS DISTINCT FROM NEW.receivable_account_id
           OR OLD.concession_account_id IS DISTINCT FROM NEW.concession_account_id
           OR OLD.submitted_by_membership_id IS DISTINCT FROM NEW.submitted_by_membership_id
           OR OLD.submitted_at IS DISTINCT FROM NEW.submitted_at
           OR OLD.submission_reason IS DISTINCT FROM NEW.submission_reason
           OR OLD.decided_by_membership_id IS DISTINCT FROM NEW.decided_by_membership_id
           OR OLD.decided_at IS DISTINCT FROM NEW.decided_at
           OR OLD.decision_reason IS DISTINCT FROM NEW.decision_reason THEN
            RAISE EXCEPTION 'Approved fee accounting evidence cannot be rewritten when superseded';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Invalid fee accounting configuration transition';
END $$;

CREATE OR REPLACE FUNCTION guard_fee_accounting_head_mapping()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_status VARCHAR(20);
    income_row accounting_accounts%ROWTYPE;
BEGIN
    SELECT status INTO parent_status FROM fee_accounting_configurations
      WHERE id=COALESCE(NEW.configuration_id,OLD.configuration_id)
        AND organisation_id=COALESCE(NEW.organisation_id,OLD.organisation_id)
        AND school_id=COALESCE(NEW.school_id,OLD.school_id);
    IF parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Fee-head accounting mappings are editable only while configuration is draft';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        SELECT * INTO income_row FROM accounting_accounts
          WHERE id=NEW.income_account_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF income_row.account_type <> 'INCOME' OR income_row.category <> 'INCOME'
           OR income_row.status <> 'ACTIVE' OR NOT income_row.allow_direct_posting THEN
            RAISE EXCEPTION 'Each fee head must map to an active posting income account';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE OR REPLACE FUNCTION guard_fee_accounting_tender_mapping()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_status VARCHAR(20);
    deposit_row accounting_accounts%ROWTYPE;
BEGIN
    SELECT status INTO parent_status FROM fee_accounting_configurations
      WHERE id=COALESCE(NEW.configuration_id,OLD.configuration_id)
        AND organisation_id=COALESCE(NEW.organisation_id,OLD.organisation_id)
        AND school_id=COALESCE(NEW.school_id,OLD.school_id);
    IF parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Tender accounting mappings are editable only while configuration is draft';
    END IF;
    IF TG_OP <> 'DELETE' THEN
        SELECT * INTO deposit_row FROM accounting_accounts
          WHERE id=NEW.deposit_account_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF deposit_row.account_type <> 'ASSET' OR deposit_row.status <> 'ACTIVE'
           OR NOT deposit_row.allow_direct_posting
           OR (NEW.payment_method='CASH' AND deposit_row.category <> 'CASH')
           OR (NEW.payment_method<>'CASH' AND deposit_row.category <> 'BANK') THEN
            RAISE EXCEPTION 'Cash must map to cash and every other tender must map to an active bank posting account';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE OR REPLACE FUNCTION guard_fee_accounting_batch()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    config_row fee_accounting_configurations%ROWTYPE;
    period_row accounting_periods%ROWTYPE;
    journal_row accounting_journals%ROWTYPE;
    later_effective DATE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO config_row FROM fee_accounting_configurations
          WHERE id=NEW.configuration_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id AND status IN ('APPROVED','SUPERSEDED');
        IF NOT FOUND OR config_row.financial_year_id <> NEW.financial_year_id
           OR NEW.business_date < config_row.effective_from THEN
            RAISE EXCEPTION 'Posting batch requires the approved fee accounting configuration for its date';
        END IF;
        SELECT min(effective_from) INTO later_effective FROM fee_accounting_configurations
          WHERE organisation_id=NEW.organisation_id AND school_id=NEW.school_id
            AND financial_year_id=NEW.financial_year_id
            AND status IN ('APPROVED','SUPERSEDED')
            AND effective_from > config_row.effective_from;
        IF later_effective IS NOT NULL AND NEW.business_date >= later_effective THEN
            RAISE EXCEPTION 'Posting batch must use the configuration effective on its business date';
        END IF;
        SELECT * INTO period_row FROM accounting_periods
          WHERE id=NEW.accounting_period_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id AND financial_year_id=NEW.financial_year_id;
        IF NOT FOUND OR (NEW.disposition='JOURNAL' AND period_row.status <> 'OPEN')
           OR NEW.business_date < period_row.starts_on
           OR NEW.business_date > period_row.ends_on THEN
            RAISE EXCEPTION 'Fee accounting posting date must be inside an open accounting period';
        END IF;
        IF (config_row.accounting_basis_snapshot='CASH'
              AND NEW.event_type IN ('CHARGE','CONCESSION')
              AND NEW.disposition<>'NO_GL_EFFECT')
           OR (NOT (config_row.accounting_basis_snapshot='CASH'
              AND NEW.event_type IN ('CHARGE','CONCESSION'))
              AND NEW.disposition<>'JOURNAL') THEN
            RAISE EXCEPTION 'Posting disposition must match the configured accounting basis and fee event';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.accounting_journal_id IS NULL AND NEW.accounting_journal_id IS NOT NULL
       AND current_setting('app.fee_accounting_journal_link', true)='approved' THEN
        IF OLD.organisation_id IS DISTINCT FROM NEW.organisation_id
           OR OLD.school_id IS DISTINCT FROM NEW.school_id
           OR OLD.configuration_id IS DISTINCT FROM NEW.configuration_id
           OR OLD.financial_year_id IS DISTINCT FROM NEW.financial_year_id
           OR OLD.accounting_period_id IS DISTINCT FROM NEW.accounting_period_id
           OR OLD.business_date IS DISTINCT FROM NEW.business_date
           OR OLD.event_type IS DISTINCT FROM NEW.event_type
           OR OLD.disposition IS DISTINCT FROM NEW.disposition
           OR OLD.sequence_number IS DISTINCT FROM NEW.sequence_number
           OR OLD.idempotency_key IS DISTINCT FROM NEW.idempotency_key
           OR OLD.request_fingerprint IS DISTINCT FROM NEW.request_fingerprint
           OR OLD.source_count IS DISTINCT FROM NEW.source_count
           OR OLD.source_amount IS DISTINCT FROM NEW.source_amount
           OR OLD.evidence_snapshot IS DISTINCT FROM NEW.evidence_snapshot
           OR OLD.evidence_sha256 IS DISTINCT FROM NEW.evidence_sha256
           OR OLD.preparation_reason IS DISTINCT FROM NEW.preparation_reason
           OR OLD.prepared_by_membership_id IS DISTINCT FROM NEW.prepared_by_membership_id
           OR OLD.prepared_at IS DISTINCT FROM NEW.prepared_at THEN
            RAISE EXCEPTION 'Fee accounting batch evidence is immutable';
        END IF;
        SELECT * INTO journal_row FROM accounting_journals
          WHERE id=NEW.accounting_journal_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF NOT FOUND OR journal_row.source_module<>'FEES' OR journal_row.source_id<>NEW.id
           OR journal_row.financial_year_id<>NEW.financial_year_id
           OR journal_row.accounting_period_id<>NEW.accounting_period_id
           OR journal_row.posting_date<>NEW.business_date OR journal_row.status<>'SUBMITTED' THEN
            RAISE EXCEPTION 'Fee accounting batch must link its matching submitted fee journal';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Fee accounting posting batches are append-only';
END $$;

CREATE OR REPLACE FUNCTION guard_fee_accounting_posting_item()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    batch_row fee_accounting_posting_batches%ROWTYPE;
    entry_row fee_ledger_entries%ROWTYPE;
    journal_status VARCHAR(20);
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO batch_row FROM fee_accounting_posting_batches
          WHERE id=NEW.posting_batch_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        SELECT * INTO entry_row FROM fee_ledger_entries
          WHERE id=NEW.fee_ledger_entry_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id;
        IF NOT FOUND OR batch_row.id IS NULL OR entry_row.entry_type<>batch_row.event_type
           OR entry_row.effective_on<>batch_row.business_date THEN
            RAISE EXCEPTION 'Fee accounting item must match its batch event, date and school';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status='ACTIVE' AND NEW.status='RELEASED'
       AND current_setting('app.fee_accounting_item_release', true)='approved' THEN
        IF OLD.organisation_id IS DISTINCT FROM NEW.organisation_id
           OR OLD.school_id IS DISTINCT FROM NEW.school_id
           OR OLD.posting_batch_id IS DISTINCT FROM NEW.posting_batch_id
           OR OLD.fee_ledger_entry_id IS DISTINCT FROM NEW.fee_ledger_entry_id
           OR OLD.sequence_number IS DISTINCT FROM NEW.sequence_number
           OR OLD.source_snapshot IS DISTINCT FROM NEW.source_snapshot
           OR OLD.source_sha256 IS DISTINCT FROM NEW.source_sha256
           OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
            RAISE EXCEPTION 'Released fee accounting source evidence cannot be rewritten';
        END IF;
        SELECT journal.status INTO journal_status FROM fee_accounting_posting_batches batch
          JOIN accounting_journals journal ON journal.id=batch.accounting_journal_id
            AND journal.organisation_id=batch.organisation_id AND journal.school_id=batch.school_id
          WHERE batch.id=OLD.posting_batch_id AND batch.organisation_id=OLD.organisation_id
            AND batch.school_id=OLD.school_id;
        IF journal_status NOT IN ('REJECTED','REVERSED') THEN
            RAISE EXCEPTION 'Only rejected or reversed fee journals may release source entries for re-preparation';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Fee accounting posting source evidence is append-only';
END $$;

CREATE TRIGGER fee_accounting_configurations_guard
    BEFORE INSERT OR UPDATE ON fee_accounting_configurations
    FOR EACH ROW EXECUTE FUNCTION guard_fee_accounting_configuration();
CREATE TRIGGER fee_accounting_configurations_no_delete
    BEFORE DELETE ON fee_accounting_configurations
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();
CREATE TRIGGER fee_accounting_head_mappings_guard
    BEFORE INSERT OR UPDATE OR DELETE ON fee_accounting_head_mappings
    FOR EACH ROW EXECUTE FUNCTION guard_fee_accounting_head_mapping();
CREATE TRIGGER fee_accounting_tender_mappings_guard
    BEFORE INSERT OR UPDATE OR DELETE ON fee_accounting_tender_mappings
    FOR EACH ROW EXECUTE FUNCTION guard_fee_accounting_tender_mapping();
CREATE TRIGGER fee_accounting_posting_batches_guard
    BEFORE INSERT OR UPDATE ON fee_accounting_posting_batches
    FOR EACH ROW EXECUTE FUNCTION guard_fee_accounting_batch();
CREATE TRIGGER fee_accounting_posting_batches_no_delete
    BEFORE DELETE ON fee_accounting_posting_batches
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();
CREATE TRIGGER fee_accounting_posting_items_guard
    BEFORE INSERT OR UPDATE ON fee_accounting_posting_items
    FOR EACH ROW EXECUTE FUNCTION guard_fee_accounting_posting_item();
CREATE TRIGGER fee_accounting_posting_items_no_delete
    BEFORE DELETE ON fee_accounting_posting_items
    FOR EACH ROW EXECUTE FUNCTION prevent_accounting_evidence_mutation();

ALTER TABLE fee_accounting_configurations ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_accounting_head_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_accounting_tender_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_accounting_posting_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_accounting_posting_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY fee_accounting_configurations_tenant_all ON fee_accounting_configurations
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY fee_accounting_head_mappings_tenant_all ON fee_accounting_head_mappings
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY fee_accounting_tender_mappings_tenant_all ON fee_accounting_tender_mappings
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY fee_accounting_posting_batches_tenant_all ON fee_accounting_posting_batches
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());
CREATE POLICY fee_accounting_posting_items_tenant_all ON fee_accounting_posting_items
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());

COMMENT ON TABLE fee_accounting_configurations IS
    'Maker-checker, effective-dated mapping from the immutable fee subledger to one financial year and chart of accounts.';
COMMENT ON TABLE fee_accounting_head_mappings IS
    'Frozen fee-head to income-account mappings for an approved fee accounting configuration.';
COMMENT ON TABLE fee_accounting_tender_mappings IS
    'Frozen collection-method to cash/bank posting mappings for an approved fee accounting configuration.';
COMMENT ON TABLE fee_accounting_posting_batches IS
    'Retry-safe daily fee-event batch with frozen source evidence and an optional submitted general-ledger journal.';
COMMENT ON TABLE fee_accounting_posting_items IS
    'Trace from each immutable fee-ledger entry to its posting batch; rejected or reversed journals release rather than erase history.';

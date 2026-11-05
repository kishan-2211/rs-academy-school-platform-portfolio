INSERT INTO capabilities (code, description, risk_level) VALUES
    ('fees.close.propose', 'Prepare an evidenced daily finance close for independent approval', 'RESTRICTED'),
    ('fees.close.approve', 'Approve or reject a daily finance close and lock the financial date', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'fees.close.propose'),
    ('00000000-0000-4000-8000-000000000001', 'fees.close.approve'),
    ('00000000-0000-4000-8000-000000000004', 'fees.close.propose'),
    ('00000000-0000-4000-8000-000000000007', 'fees.close.approve')
ON CONFLICT DO NOTHING;

CREATE TABLE fee_day_closes (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    business_date DATE NOT NULL,
    revision_number INTEGER NOT NULL,
    supersedes_close_id UUID,
    currency_code CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    collection_count INTEGER NOT NULL,
    gross_collections NUMERIC(14,2) NOT NULL,
    reversal_count INTEGER NOT NULL,
    reversal_amount NUMERIC(14,2) NOT NULL,
    net_collections NUMERIC(14,2) NOT NULL,
    declared_total NUMERIC(14,2) NOT NULL,
    variance_total NUMERIC(14,2) NOT NULL,
    system_method_totals JSONB NOT NULL,
    declared_method_totals JSONB NOT NULL,
    system_snapshot JSONB NOT NULL,
    system_sha256 CHAR(64) NOT NULL,
    evidence_snapshot JSONB NOT NULL,
    evidence_sha256 CHAR(64) NOT NULL,
    submission_reason VARCHAR(500) NOT NULL,
    variance_reason VARCHAR(500),
    submitted_by_membership_id UUID NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fee_day_closes_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT fee_day_closes_school_fk FOREIGN KEY (school_id, organisation_id)
        REFERENCES schools(id, organisation_id),
    CONSTRAINT fee_day_closes_supersedes_fk FOREIGN KEY (supersedes_close_id, organisation_id, school_id)
        REFERENCES fee_day_closes(id, organisation_id, school_id),
    CONSTRAINT fee_day_closes_submitter_fk FOREIGN KEY (submitted_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_day_closes_decider_fk FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT fee_day_closes_revision_unique UNIQUE (school_id, business_date, revision_number),
    CONSTRAINT fee_day_closes_date_valid CHECK (business_date >= DATE '2000-01-01'),
    CONSTRAINT fee_day_closes_revision_valid CHECK (
        (revision_number = 1 AND supersedes_close_id IS NULL)
        OR (revision_number BETWEEN 2 AND 1000 AND supersedes_close_id IS NOT NULL)
    ),
    CONSTRAINT fee_day_closes_currency_valid CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT fee_day_closes_counts_valid CHECK (collection_count >= 0 AND reversal_count >= 0),
    CONSTRAINT fee_day_closes_amounts_valid CHECK (
        gross_collections >= 0 AND reversal_amount >= 0
        AND net_collections = gross_collections - reversal_amount
        AND declared_total >= 0
        AND variance_total = declared_total - net_collections
    ),
    CONSTRAINT fee_day_closes_json_valid CHECK (
        jsonb_typeof(system_method_totals) = 'object'
        AND jsonb_typeof(declared_method_totals) = 'object'
        AND jsonb_typeof(system_snapshot) = 'object'
        AND jsonb_typeof(evidence_snapshot) = 'object'
    ),
    CONSTRAINT fee_day_closes_hashes_valid CHECK (
        system_sha256 ~ '^[0-9a-f]{64}$' AND evidence_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT fee_day_closes_reasons_valid CHECK (
        length(btrim(submission_reason)) BETWEEN 1 AND 500
        AND ((variance_total = 0 AND variance_reason IS NULL)
          OR (variance_total <> 0 AND length(btrim(variance_reason)) BETWEEN 1 AND 500))
    ),
    CONSTRAINT fee_day_closes_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT fee_day_closes_lifecycle_valid CHECK (
        (status = 'PENDING' AND decided_by_membership_id IS NULL
          AND decided_at IS NULL AND decision_reason IS NULL)
        OR (status IN ('APPROVED', 'REJECTED')
          AND decided_by_membership_id IS NOT NULL
          AND decided_by_membership_id <> submitted_by_membership_id
          AND decided_at IS NOT NULL
          AND length(btrim(decision_reason)) BETWEEN 1 AND 500)
    )
);

CREATE UNIQUE INDEX fee_day_closes_one_open_or_approved_idx
    ON fee_day_closes (school_id, business_date)
    WHERE status IN ('PENDING', 'APPROVED');
CREATE UNIQUE INDEX fee_day_closes_one_pending_per_school_idx
    ON fee_day_closes (school_id)
    WHERE status = 'PENDING';
CREATE INDEX fee_day_closes_scope_status_idx
    ON fee_day_closes (organisation_id, school_id, business_date DESC, status);

CREATE OR REPLACE FUNCTION guard_fee_day_close()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.business_date <> NEW.business_date
       OR OLD.revision_number <> NEW.revision_number
       OR OLD.supersedes_close_id IS DISTINCT FROM NEW.supersedes_close_id
       OR OLD.currency_code <> NEW.currency_code
       OR OLD.collection_count <> NEW.collection_count
       OR OLD.gross_collections <> NEW.gross_collections
       OR OLD.reversal_count <> NEW.reversal_count
       OR OLD.reversal_amount <> NEW.reversal_amount
       OR OLD.net_collections <> NEW.net_collections
       OR OLD.declared_total <> NEW.declared_total
       OR OLD.variance_total <> NEW.variance_total
       OR OLD.system_method_totals <> NEW.system_method_totals
       OR OLD.declared_method_totals <> NEW.declared_method_totals
       OR OLD.system_snapshot <> NEW.system_snapshot
       OR OLD.system_sha256 <> NEW.system_sha256
       OR OLD.evidence_snapshot <> NEW.evidence_snapshot
       OR OLD.evidence_sha256 <> NEW.evidence_sha256
       OR OLD.submission_reason <> NEW.submission_reason
       OR OLD.variance_reason IS DISTINCT FROM NEW.variance_reason
       OR OLD.submitted_by_membership_id <> NEW.submitted_by_membership_id
       OR OLD.submitted_at <> NEW.submitted_at THEN
        RAISE EXCEPTION 'fee day close evidence is immutable';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('APPROVED', 'REJECTED')
       OR current_setting('app.finance_day_close_decision', TRUE) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'fee day closes can only change through the approved decision workflow';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER fee_day_closes_guard BEFORE UPDATE ON fee_day_closes
    FOR EACH ROW EXECUTE FUNCTION guard_fee_day_close();
CREATE TRIGGER fee_day_closes_prevent_delete BEFORE DELETE ON fee_day_closes
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();

ALTER TABLE fee_day_closes ENABLE ROW LEVEL SECURITY;
CREATE POLICY fee_day_closes_tenant_all ON fee_day_closes
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE fee_day_closes IS
    'Immutable daily collection and reversal evidence. A different authority approves the frozen snapshot; approved dates reject late financial posting.';

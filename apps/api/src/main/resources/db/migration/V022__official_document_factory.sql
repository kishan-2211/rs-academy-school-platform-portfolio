INSERT INTO capabilities (code, description, risk_level) VALUES
    ('documents.read', 'Read school-scoped official document templates, sources, and issuance evidence', 'SENSITIVE'),
    ('documents.configure', 'Configure versioned official document templates', 'RESTRICTED'),
    ('documents.issue', 'Prepare or directly issue official documents from approved source evidence', 'RESTRICTED'),
    ('documents.approve', 'Publish templates and independently approve official document issuance', 'RESTRICTED'),
    ('documents.revoke', 'Cancel issued official documents without deleting evidence', 'RESTRICTED')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'documents.read'),
    ('00000000-0000-4000-8000-000000000001', 'documents.configure'),
    ('00000000-0000-4000-8000-000000000001', 'documents.issue'),
    ('00000000-0000-4000-8000-000000000001', 'documents.approve'),
    ('00000000-0000-4000-8000-000000000001', 'documents.revoke'),
    ('00000000-0000-4000-8000-000000000004', 'documents.read'),
    ('00000000-0000-4000-8000-000000000004', 'documents.issue')
ON CONFLICT DO NOTHING;

CREATE TABLE official_document_templates (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    template_code VARCHAR(40) NOT NULL,
    revision_number INTEGER NOT NULL,
    supersedes_template_id UUID,
    document_kind VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    locale VARCHAR(20) NOT NULL,
    page_size VARCHAR(20) NOT NULL DEFAULT 'A4',
    title VARCHAR(160) NOT NULL,
    subtitle VARCHAR(240),
    footer_text VARCHAR(500),
    signatory_role VARCHAR(120) NOT NULL,
    accent_colour CHAR(7) NOT NULL DEFAULT '#23483F',
    approval_required BOOLEAN NOT NULL DEFAULT TRUE,
    label_overrides JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by_membership_id UUID NOT NULL,
    published_by_membership_id UUID,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT official_document_templates_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT official_document_templates_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT official_document_templates_supersedes_fk
        FOREIGN KEY (supersedes_template_id, organisation_id, school_id)
        REFERENCES official_document_templates(id, organisation_id, school_id),
    CONSTRAINT official_document_templates_creator_fk
        FOREIGN KEY (created_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT official_document_templates_publisher_fk
        FOREIGN KEY (published_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT official_document_templates_revision_unique
        UNIQUE (school_id, template_code, revision_number),
    CONSTRAINT official_document_templates_code_valid
        CHECK (template_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT official_document_templates_revision_valid
        CHECK (revision_number BETWEEN 1 AND 10000),
    CONSTRAINT official_document_templates_supersedes_valid
        CHECK (supersedes_template_id IS NULL OR supersedes_template_id <> id),
    CONSTRAINT official_document_templates_kind_valid
        CHECK (document_kind IN ('REPORT_CARD', 'FEE_RECEIPT')),
    CONSTRAINT official_document_templates_locale_valid
        CHECK (locale IN ('en-IN', 'hi-IN')),
    CONSTRAINT official_document_templates_page_valid
        CHECK (page_size = 'A4'),
    CONSTRAINT official_document_templates_colour_valid
        CHECK (accent_colour ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT official_document_templates_labels_valid
        CHECK (jsonb_typeof(label_overrides) = 'object'),
    CONSTRAINT official_document_templates_status_valid
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT official_document_templates_text_valid CHECK (
        length(btrim(display_name)) BETWEEN 1 AND 120
        AND length(btrim(title)) BETWEEN 1 AND 160
        AND (subtitle IS NULL OR length(btrim(subtitle)) BETWEEN 1 AND 240)
        AND (footer_text IS NULL OR length(btrim(footer_text)) BETWEEN 1 AND 500)
        AND length(btrim(signatory_role)) BETWEEN 1 AND 120
    ),
    CONSTRAINT official_document_templates_publication_valid CHECK (
        (status = 'DRAFT' AND published_by_membership_id IS NULL AND published_at IS NULL)
        OR (status IN ('PUBLISHED', 'RETIRED')
            AND published_by_membership_id IS NOT NULL AND published_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX official_document_templates_one_published_idx
    ON official_document_templates (school_id, document_kind, locale)
    WHERE status = 'PUBLISHED';

CREATE INDEX official_document_templates_scope_idx
    ON official_document_templates (
        organisation_id, school_id, document_kind, locale, status, revision_number DESC
    );

CREATE TABLE official_documents (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    template_id UUID NOT NULL,
    document_kind VARCHAR(40) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id UUID NOT NULL,
    subject_type VARCHAR(30) NOT NULL,
    subject_id UUID NOT NULL,
    document_number VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    supersedes_document_id UUID,
    approval_required_snapshot BOOLEAN NOT NULL,
    template_snapshot JSONB NOT NULL,
    source_snapshot JSONB NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    request_reason VARCHAR(240) NOT NULL,
    status VARCHAR(30) NOT NULL,
    requested_by_membership_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    decided_by_membership_id UUID,
    decided_at TIMESTAMPTZ,
    decision_reason VARCHAR(240),
    issued_by_membership_id UUID,
    issued_at TIMESTAMPTZ,
    verification_token_sha256 CHAR(64),
    pdf_sha256 CHAR(64),
    pdf_size_bytes BIGINT,
    storage_bucket VARCHAR(255),
    storage_key VARCHAR(500),
    cancelled_by_membership_id UUID,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(240),
    superseded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT official_documents_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT official_documents_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT official_documents_template_fk
        FOREIGN KEY (template_id, organisation_id, school_id)
        REFERENCES official_document_templates(id, organisation_id, school_id),
    CONSTRAINT official_documents_supersedes_fk
        FOREIGN KEY (supersedes_document_id, organisation_id, school_id)
        REFERENCES official_documents(id, organisation_id, school_id),
    CONSTRAINT official_documents_requester_fk
        FOREIGN KEY (requested_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT official_documents_decider_fk
        FOREIGN KEY (decided_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT official_documents_issuer_fk
        FOREIGN KEY (issued_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT official_documents_canceller_fk
        FOREIGN KEY (cancelled_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT official_documents_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT official_documents_storage_unique UNIQUE (storage_bucket, storage_key),
    CONSTRAINT official_documents_verification_unique UNIQUE (verification_token_sha256),
    CONSTRAINT official_documents_kind_valid CHECK (document_kind IN ('REPORT_CARD', 'FEE_RECEIPT')),
    CONSTRAINT official_documents_supersedes_valid
        CHECK (supersedes_document_id IS NULL OR supersedes_document_id <> id),
    CONSTRAINT official_documents_source_valid CHECK (
        (document_kind = 'REPORT_CARD' AND source_type = 'LEARNER_REPORT_CARD'
            AND subject_type = 'LEARNER')
        OR (document_kind = 'FEE_RECEIPT' AND source_type = 'FEE_PAYMENT'
            AND subject_type = 'LEARNER')
    ),
    CONSTRAINT official_documents_number_valid
        CHECK (length(btrim(document_number)) BETWEEN 1 AND 100),
    CONSTRAINT official_documents_hashes_valid CHECK (
        request_fingerprint ~ '^[a-f0-9]{64}$'
        AND snapshot_sha256 ~ '^[a-f0-9]{64}$'
        AND (verification_token_sha256 IS NULL OR verification_token_sha256 ~ '^[a-f0-9]{64}$')
        AND (pdf_sha256 IS NULL OR pdf_sha256 ~ '^[a-f0-9]{64}$')
    ),
    CONSTRAINT official_documents_snapshots_valid CHECK (
        jsonb_typeof(template_snapshot) = 'object'
        AND jsonb_typeof(source_snapshot) = 'object'
    ),
    CONSTRAINT official_documents_status_valid
        CHECK (status IN ('PENDING_APPROVAL', 'ISSUED', 'REJECTED', 'CANCELLED', 'SUPERSEDED')),
    CONSTRAINT official_documents_reason_valid CHECK (
        length(btrim(request_reason)) BETWEEN 1 AND 240
        AND (decision_reason IS NULL OR length(btrim(decision_reason)) BETWEEN 1 AND 240)
        AND (cancellation_reason IS NULL OR length(btrim(cancellation_reason)) BETWEEN 1 AND 240)
    ),
    CONSTRAINT official_documents_pdf_valid CHECK (
        (pdf_size_bytes IS NULL AND storage_bucket IS NULL AND storage_key IS NULL AND pdf_sha256 IS NULL)
        OR (pdf_size_bytes BETWEEN 1 AND 10485760
            AND storage_bucket IS NOT NULL AND storage_key IS NOT NULL AND pdf_sha256 IS NOT NULL)
    ),
    CONSTRAINT official_documents_state_valid CHECK (
        (status = 'PENDING_APPROVAL'
            AND approval_required_snapshot = TRUE
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
            AND issued_by_membership_id IS NULL AND issued_at IS NULL
            AND verification_token_sha256 IS NULL AND pdf_sha256 IS NULL
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL
            AND cancellation_reason IS NULL AND superseded_at IS NULL)
        OR (status = 'REJECTED'
            AND approval_required_snapshot = TRUE
            AND decided_by_membership_id IS NOT NULL AND decided_at IS NOT NULL
            AND decision_reason IS NOT NULL
            AND issued_by_membership_id IS NULL AND issued_at IS NULL
            AND verification_token_sha256 IS NULL AND pdf_sha256 IS NULL
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL
            AND cancellation_reason IS NULL AND superseded_at IS NULL)
        OR (status = 'ISSUED'
            AND issued_by_membership_id IS NOT NULL AND issued_at IS NOT NULL
            AND verification_token_sha256 IS NOT NULL AND pdf_sha256 IS NOT NULL
            AND ((approval_required_snapshot = TRUE
                    AND decided_by_membership_id IS NOT NULL AND decided_at IS NOT NULL
                    AND decision_reason IS NOT NULL
                    AND decided_by_membership_id <> requested_by_membership_id)
                OR (approval_required_snapshot = FALSE
                    AND decided_by_membership_id IS NULL AND decided_at IS NULL
                    AND decision_reason IS NULL))
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL
            AND cancellation_reason IS NULL AND superseded_at IS NULL)
        OR (status = 'CANCELLED'
            AND issued_by_membership_id IS NOT NULL AND issued_at IS NOT NULL
            AND verification_token_sha256 IS NOT NULL AND pdf_sha256 IS NOT NULL
            AND ((approval_required_snapshot = TRUE
                    AND decided_by_membership_id IS NOT NULL AND decided_at IS NOT NULL
                    AND decision_reason IS NOT NULL
                    AND decided_by_membership_id <> requested_by_membership_id)
                OR (approval_required_snapshot = FALSE
                    AND decided_by_membership_id IS NULL AND decided_at IS NULL
                    AND decision_reason IS NULL))
            AND cancelled_by_membership_id IS NOT NULL AND cancelled_at IS NOT NULL
            AND cancellation_reason IS NOT NULL AND superseded_at IS NULL)
        OR (status = 'SUPERSEDED'
            AND issued_by_membership_id IS NOT NULL AND issued_at IS NOT NULL
            AND verification_token_sha256 IS NOT NULL AND pdf_sha256 IS NOT NULL
            AND ((approval_required_snapshot = TRUE
                    AND decided_by_membership_id IS NOT NULL AND decided_at IS NOT NULL
                    AND decision_reason IS NOT NULL
                    AND decided_by_membership_id <> requested_by_membership_id)
                OR (approval_required_snapshot = FALSE
                    AND decided_by_membership_id IS NULL AND decided_at IS NULL
                    AND decision_reason IS NULL))
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL
            AND cancellation_reason IS NULL AND superseded_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX official_documents_one_source_template_idx
    ON official_documents (school_id, template_id, source_type, source_id)
    WHERE status <> 'REJECTED';

CREATE UNIQUE INDEX official_documents_one_active_supersession_idx
    ON official_documents (supersedes_document_id)
    WHERE supersedes_document_id IS NOT NULL AND status IN ('PENDING_APPROVAL', 'ISSUED');

CREATE INDEX official_documents_scope_status_idx
    ON official_documents (organisation_id, school_id, status, requested_at DESC, id);

CREATE INDEX official_documents_subject_idx
    ON official_documents (organisation_id, school_id, subject_type, subject_id, issued_at DESC);

CREATE TABLE official_document_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    official_document_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30) NOT NULL,
    reason VARCHAR(240) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT official_document_history_document_fk
        FOREIGN KEY (official_document_id, organisation_id, school_id)
        REFERENCES official_documents(id, organisation_id, school_id),
    CONSTRAINT official_document_history_actor_fk
        FOREIGN KEY (actor_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT official_document_history_event_valid CHECK (
        event_type IN ('REQUESTED', 'ISSUED', 'APPROVED', 'REJECTED', 'CANCELLED', 'SUPERSEDED')
    ),
    CONSTRAINT official_document_history_status_valid CHECK (
        (event_type = 'REQUESTED' AND previous_status IS NULL
            AND new_status IN ('PENDING_APPROVAL', 'ISSUED'))
        OR (event_type = 'ISSUED' AND previous_status IS NULL AND new_status = 'ISSUED')
        OR (event_type = 'APPROVED' AND previous_status = 'PENDING_APPROVAL' AND new_status = 'ISSUED')
        OR (event_type = 'REJECTED' AND previous_status = 'PENDING_APPROVAL' AND new_status = 'REJECTED')
        OR (event_type = 'CANCELLED' AND previous_status = 'ISSUED' AND new_status = 'CANCELLED')
        OR (event_type = 'SUPERSEDED' AND previous_status = 'ISSUED' AND new_status = 'SUPERSEDED')
    ),
    CONSTRAINT official_document_history_reason_valid
        CHECK (length(btrim(reason)) BETWEEN 1 AND 240)
);

CREATE INDEX official_document_history_document_idx
    ON official_document_history (official_document_id, occurred_at, id);

CREATE OR REPLACE FUNCTION guard_official_document_template_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.template_code <> NEW.template_code
       OR OLD.revision_number <> NEW.revision_number
       OR OLD.supersedes_template_id IS DISTINCT FROM NEW.supersedes_template_id
       OR OLD.document_kind <> NEW.document_kind
       OR OLD.created_by_membership_id <> NEW.created_by_membership_id
       OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'official document template identity is immutable';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'PUBLISHED') THEN
        RAISE EXCEPTION 'invalid official document template transition';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'PUBLISHED' AND (
       OLD.display_name <> NEW.display_name OR OLD.locale <> NEW.locale
       OR OLD.page_size <> NEW.page_size OR OLD.title <> NEW.title
       OR OLD.subtitle IS DISTINCT FROM NEW.subtitle OR OLD.footer_text IS DISTINCT FROM NEW.footer_text
       OR OLD.signatory_role <> NEW.signatory_role OR OLD.accent_colour <> NEW.accent_colour
       OR OLD.approval_required <> NEW.approval_required
       OR OLD.label_overrides <> NEW.label_overrides) THEN
        RAISE EXCEPTION 'template content cannot change during publication';
    END IF;
    IF OLD.status = 'PUBLISHED' AND NEW.status <> 'RETIRED' THEN
        RAISE EXCEPTION 'published official document templates may only be retired';
    END IF;
    IF OLD.status = 'RETIRED' THEN
        RAISE EXCEPTION 'retired official document templates are immutable';
    END IF;
    IF OLD.status <> 'DRAFT' AND (
       OLD.display_name <> NEW.display_name OR OLD.locale <> NEW.locale
       OR OLD.page_size <> NEW.page_size OR OLD.title <> NEW.title
       OR OLD.subtitle IS DISTINCT FROM NEW.subtitle OR OLD.footer_text IS DISTINCT FROM NEW.footer_text
       OR OLD.signatory_role <> NEW.signatory_role OR OLD.accent_colour <> NEW.accent_colour
       OR OLD.approval_required <> NEW.approval_required
       OR OLD.label_overrides <> NEW.label_overrides
       OR OLD.published_by_membership_id <> NEW.published_by_membership_id
       OR OLD.published_at <> NEW.published_at) THEN
        RAISE EXCEPTION 'published official document template content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER official_document_templates_guard
BEFORE UPDATE ON official_document_templates
FOR EACH ROW EXECUTE FUNCTION guard_official_document_template_history();

CREATE OR REPLACE FUNCTION guard_official_document_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id <> NEW.id OR OLD.organisation_id <> NEW.organisation_id
       OR OLD.school_id <> NEW.school_id OR OLD.template_id <> NEW.template_id
       OR OLD.document_kind <> NEW.document_kind OR OLD.source_type <> NEW.source_type
       OR OLD.source_id <> NEW.source_id OR OLD.subject_type <> NEW.subject_type
       OR OLD.subject_id <> NEW.subject_id OR OLD.document_number <> NEW.document_number
       OR OLD.idempotency_key <> NEW.idempotency_key OR OLD.request_fingerprint <> NEW.request_fingerprint
       OR OLD.supersedes_document_id IS DISTINCT FROM NEW.supersedes_document_id
       OR OLD.approval_required_snapshot <> NEW.approval_required_snapshot
       OR OLD.template_snapshot <> NEW.template_snapshot OR OLD.source_snapshot <> NEW.source_snapshot
       OR OLD.snapshot_sha256 <> NEW.snapshot_sha256 OR OLD.request_reason <> NEW.request_reason
       OR OLD.requested_by_membership_id <> NEW.requested_by_membership_id
       OR OLD.requested_at <> NEW.requested_at OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'official document source evidence is immutable';
    END IF;
    IF NOT (
        (OLD.status = 'PENDING_APPROVAL' AND NEW.status IN ('ISSUED', 'REJECTED'))
        OR (OLD.status = 'ISSUED' AND NEW.status IN ('CANCELLED', 'SUPERSEDED'))
    ) THEN
        RAISE EXCEPTION 'invalid official document lifecycle transition';
    END IF;
    IF OLD.status = 'ISSUED' AND (
       OLD.decided_by_membership_id IS DISTINCT FROM NEW.decided_by_membership_id
       OR OLD.decided_at IS DISTINCT FROM NEW.decided_at
       OR OLD.decision_reason IS DISTINCT FROM NEW.decision_reason
       OR OLD.issued_by_membership_id IS DISTINCT FROM NEW.issued_by_membership_id
       OR OLD.issued_at IS DISTINCT FROM NEW.issued_at
       OR OLD.verification_token_sha256 IS DISTINCT FROM NEW.verification_token_sha256
       OR OLD.pdf_sha256 IS DISTINCT FROM NEW.pdf_sha256
       OR OLD.pdf_size_bytes IS DISTINCT FROM NEW.pdf_size_bytes
       OR OLD.storage_bucket IS DISTINCT FROM NEW.storage_bucket
       OR OLD.storage_key IS DISTINCT FROM NEW.storage_key) THEN
        RAISE EXCEPTION 'issued official document evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER official_documents_guard
BEFORE UPDATE ON official_documents
FOR EACH ROW EXECUTE FUNCTION guard_official_document_history();

CREATE OR REPLACE FUNCTION prevent_official_document_evidence_change()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'official document evidence cannot be deleted or rewritten';
END;
$$;

CREATE TRIGGER official_document_templates_prevent_delete
BEFORE DELETE ON official_document_templates
FOR EACH ROW EXECUTE FUNCTION prevent_official_document_evidence_change();

CREATE TRIGGER official_documents_prevent_delete
BEFORE DELETE ON official_documents
FOR EACH ROW EXECUTE FUNCTION prevent_official_document_evidence_change();

CREATE TRIGGER official_document_history_prevent_update_or_delete
BEFORE UPDATE OR DELETE ON official_document_history
FOR EACH ROW EXECUTE FUNCTION prevent_official_document_evidence_change();

ALTER TABLE official_document_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE official_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE official_document_history ENABLE ROW LEVEL SECURITY;

CREATE POLICY official_document_templates_tenant_all ON official_document_templates
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY official_documents_tenant_all ON official_documents
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY official_document_history_tenant_all ON official_document_history
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE official_document_templates IS
    'Versioned school-owned rendering rules. Published revisions are immutable and are superseded rather than rewritten.';
COMMENT ON TABLE official_documents IS
    'Immutable source and template snapshots for privately stored, QR-verifiable official PDFs.';
COMMENT ON TABLE official_document_history IS
    'Append-only official document lifecycle evidence; issued, cancelled, and superseded versions are never deleted.';

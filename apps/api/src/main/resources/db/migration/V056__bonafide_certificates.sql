-- Extend the existing factory; preserve all report cards, receipts and history.
ALTER TABLE official_document_templates DROP CONSTRAINT official_document_templates_kind_valid;
ALTER TABLE official_document_templates ADD CONSTRAINT official_document_templates_kind_valid
    CHECK (document_kind IN ('REPORT_CARD','FEE_RECEIPT','BONAFIDE_CERTIFICATE'));
ALTER TABLE official_document_templates ADD CONSTRAINT official_certificate_approval_required
    CHECK (document_kind<>'BONAFIDE_CERTIFICATE' OR approval_required);
ALTER TABLE official_documents DROP CONSTRAINT official_documents_kind_valid;
ALTER TABLE official_documents ADD CONSTRAINT official_documents_kind_valid
    CHECK (document_kind IN ('REPORT_CARD','FEE_RECEIPT','BONAFIDE_CERTIFICATE'));
ALTER TABLE official_documents DROP CONSTRAINT official_documents_source_valid;
ALTER TABLE official_documents ADD CONSTRAINT official_documents_source_valid CHECK (
    subject_type='LEARNER' AND (
      (document_kind='REPORT_CARD' AND source_type='LEARNER_REPORT_CARD')
      OR (document_kind='FEE_RECEIPT' AND source_type='FEE_PAYMENT')
      OR (document_kind='BONAFIDE_CERTIFICATE' AND source_type='STUDENT_ENROLMENT')));
ALTER TABLE official_documents ADD CONSTRAINT official_certificate_evidence_valid CHECK (
    document_kind<>'BONAFIDE_CERTIFICATE' OR (
      approval_required_snapshot AND template_snapshot->>'documentKind'='BONAFIDE_CERTIFICATE'
      AND source_snapshot->>'documentNumber'=document_number
      AND source_snapshot->>'enrolmentId'=source_id::text
      AND source_snapshot->'learner'->>'id'=subject_id::text
      AND source_snapshot->'certificate'->>'asOf' ~ '^\d{4}-\d{2}-\d{2}$'
      AND source_snapshot->'certificate'->>'enrolledSince' ~ '^\d{4}-\d{2}-\d{2}$'
      AND length(source_snapshot->'learner'->>'name')>0
      AND length(source_snapshot->'learner'->>'admissionNumber')>0
    ) IS TRUE);

-- A family may legitimately need a new certificate later. Retry identity and
-- sequential numbers, rather than one document for life, prevent duplicates.
DROP INDEX official_documents_one_source_template_idx;
CREATE UNIQUE INDEX official_documents_one_source_template_idx
    ON official_documents(school_id,template_id,source_type,source_id)
    WHERE status<>'REJECTED' AND document_kind<>'BONAFIDE_CERTIFICATE';
CREATE UNIQUE INDEX official_certificate_number_unique
    ON official_documents(school_id,document_number) WHERE document_kind='BONAFIDE_CERTIFICATE';

CREATE TABLE official_certificate_counters (
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    calendar_year INTEGER NOT NULL CHECK(calendar_year BETWEEN 2000 AND 9999),
    last_number BIGINT NOT NULL CHECK(last_number BETWEEN 1 AND 999999999),
    PRIMARY KEY(school_id,calendar_year),
    FOREIGN KEY(school_id,organisation_id) REFERENCES schools(id,organisation_id)
);
ALTER TABLE official_certificate_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE official_certificate_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY official_certificate_counters_tenant_all ON official_certificate_counters
    USING(organisation_id=current_app_organisation_id())
    WITH CHECK(organisation_id=current_app_organisation_id());

CREATE FUNCTION guard_official_certificate_counter()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'certificate numbering cannot be reset or deleted';
    ELSIF TG_OP='INSERT' THEN
        IF NEW.last_number<>1 THEN RAISE EXCEPTION 'certificate numbering must start at one'; END IF;
    ELSIF NEW.organisation_id<>OLD.organisation_id OR NEW.school_id<>OLD.school_id
       OR NEW.calendar_year<>OLD.calendar_year OR NEW.last_number<>OLD.last_number+1 THEN
        RAISE EXCEPTION 'certificate numbering must advance exactly once';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER official_certificate_counters_guard BEFORE INSERT OR UPDATE OR DELETE
    ON official_certificate_counters FOR EACH ROW EXECUTE FUNCTION guard_official_certificate_counter();

CREATE FUNCTION guard_official_certificate_source()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.document_kind='BONAFIDE_CERTIFICATE' AND NOT EXISTS (
        SELECT 1 FROM student_enrolments e
        JOIN official_document_templates t ON t.id=NEW.template_id
          AND t.organisation_id=e.organisation_id AND t.school_id=e.school_id
          AND t.document_kind='BONAFIDE_CERTIFICATE' AND t.approval_required
        WHERE e.id=NEW.source_id AND e.organisation_id=NEW.organisation_id
          AND e.school_id=NEW.school_id AND e.learner_id=NEW.subject_id
    ) THEN
        RAISE EXCEPTION 'certificate requires matching scoped enrolment and template evidence';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER official_certificates_guard_source BEFORE INSERT ON official_documents
    FOR EACH ROW EXECUTE FUNCTION guard_official_certificate_source();

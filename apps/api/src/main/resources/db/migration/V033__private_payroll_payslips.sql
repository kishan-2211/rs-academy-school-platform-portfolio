INSERT INTO capabilities (code, description, risk_level) VALUES
    ('payroll.payslip.generate', 'Generate immutable private payslips from a finalized school payroll run', 'RESTRICTED'),
    ('payroll.payslip.self', 'Read and download only payslips belonging to the linked staff profile', 'SENSITIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities (role_id, capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001', 'payroll.payslip.generate'),
    ('00000000-0000-4000-8000-000000000004', 'payroll.payslip.generate'),
    ('00000000-0000-4000-8000-000000000001', 'payroll.payslip.self'),
    ('00000000-0000-4000-8000-000000000002', 'payroll.payslip.self'),
    ('00000000-0000-4000-8000-000000000003', 'payroll.payslip.self'),
    ('00000000-0000-4000-8000-000000000004', 'payroll.payslip.self'),
    ('00000000-0000-4000-8000-000000000007', 'payroll.payslip.self'),
    ('00000000-0000-4000-8000-000000000008', 'payroll.payslip.self'),
    ('00000000-0000-4000-8000-000000000009', 'payroll.payslip.self'),
    ('00000000-0000-4000-8000-000000000011', 'payroll.payslip.self')
ON CONFLICT DO NOTHING;

ALTER TABLE payroll_run_staff_results
    ADD CONSTRAINT payroll_run_staff_results_payslip_scope_unique
    UNIQUE (id, payroll_run_id, staff_profile_id, organisation_id, school_id);

CREATE TABLE payroll_payslip_batches (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    payroll_run_id UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    locale VARCHAR(20) NOT NULL,
    generation_reason VARCHAR(500) NOT NULL,
    total_count SMALLINT NOT NULL,
    generated_by_membership_id UUID NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payroll_payslip_batches_id_scope_unique
        UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_payslip_batches_run_scope_unique
        UNIQUE (id, payroll_run_id, organisation_id, school_id),
    CONSTRAINT payroll_payslip_batches_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT payroll_payslip_batches_run_fk
        FOREIGN KEY (payroll_run_id, organisation_id, school_id)
        REFERENCES payroll_runs(id, organisation_id, school_id),
    CONSTRAINT payroll_payslip_batches_generator_fk
        FOREIGN KEY (generated_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_payslip_batches_run_unique UNIQUE (payroll_run_id),
    CONSTRAINT payroll_payslip_batches_idempotency_unique UNIQUE (school_id, idempotency_key),
    CONSTRAINT payroll_payslip_batches_idempotency_valid
        CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 100),
    CONSTRAINT payroll_payslip_batches_fingerprint_valid
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_payslip_batches_locale_valid CHECK (locale IN ('en-IN','hi-IN')),
    CONSTRAINT payroll_payslip_batches_reason_valid
        CHECK (length(btrim(generation_reason)) BETWEEN 3 AND 500),
    CONSTRAINT payroll_payslip_batches_count_valid CHECK (total_count BETWEEN 1 AND 500)
);

CREATE INDEX payroll_payslip_batches_scope_time
    ON payroll_payslip_batches(organisation_id, school_id, generated_at DESC, id);

CREATE TABLE payroll_payslips (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    payroll_payslip_batch_id UUID NOT NULL,
    payroll_run_id UUID NOT NULL,
    payroll_run_staff_result_id UUID NOT NULL,
    staff_profile_id UUID NOT NULL,
    payslip_number VARCHAR(80) NOT NULL,
    locale VARCHAR(20) NOT NULL,
    source_snapshot JSONB NOT NULL,
    snapshot_sha256 CHAR(64) NOT NULL,
    pdf_sha256 CHAR(64) NOT NULL,
    pdf_size_bytes BIGINT NOT NULL,
    storage_bucket VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    generated_by_membership_id UUID NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT payroll_payslips_id_scope_unique UNIQUE (id, organisation_id, school_id),
    CONSTRAINT payroll_payslips_school_fk
        FOREIGN KEY (school_id, organisation_id) REFERENCES schools(id, organisation_id),
    CONSTRAINT payroll_payslips_batch_fk
        FOREIGN KEY (payroll_payslip_batch_id, payroll_run_id, organisation_id, school_id)
        REFERENCES payroll_payslip_batches(id, payroll_run_id, organisation_id, school_id),
    CONSTRAINT payroll_payslips_result_fk
        FOREIGN KEY (payroll_run_staff_result_id, payroll_run_id, staff_profile_id,
            organisation_id, school_id)
        REFERENCES payroll_run_staff_results(id, payroll_run_id, staff_profile_id,
            organisation_id, school_id),
    CONSTRAINT payroll_payslips_staff_fk
        FOREIGN KEY (staff_profile_id, organisation_id, school_id)
        REFERENCES staff_profiles(id, organisation_id, school_id),
    CONSTRAINT payroll_payslips_generator_fk
        FOREIGN KEY (generated_by_membership_id, organisation_id, school_id)
        REFERENCES memberships(id, organisation_id, school_id),
    CONSTRAINT payroll_payslips_result_unique UNIQUE (payroll_run_staff_result_id),
    CONSTRAINT payroll_payslips_number_unique UNIQUE (school_id, payslip_number),
    CONSTRAINT payroll_payslips_storage_unique UNIQUE (storage_bucket, storage_key),
    CONSTRAINT payroll_payslips_number_valid
        CHECK (payslip_number ~ '^PSL-[0-9]{6}-[0-9]{3}$'),
    CONSTRAINT payroll_payslips_locale_valid CHECK (locale IN ('en-IN','hi-IN')),
    CONSTRAINT payroll_payslips_snapshot_valid CHECK (
        jsonb_typeof(source_snapshot) = 'object'
        AND source_snapshot->>'schemaVersion' = '1'
        AND source_snapshot->>'payslipNumber' = payslip_number
        AND source_snapshot->>'payrollRunId' = payroll_run_id::text
        AND source_snapshot->>'staffProfileId' = staff_profile_id::text
    ),
    CONSTRAINT payroll_payslips_hashes_valid CHECK (
        snapshot_sha256 ~ '^[0-9a-f]{64}$' AND pdf_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT payroll_payslips_pdf_valid CHECK (pdf_size_bytes BETWEEN 1000 AND 10485760),
    CONSTRAINT payroll_payslips_storage_valid CHECK (
        length(btrim(storage_bucket)) BETWEEN 1 AND 255
        AND length(btrim(storage_key)) BETWEEN 1 AND 500)
);

CREATE INDEX payroll_payslips_scope_time
    ON payroll_payslips(organisation_id, school_id, generated_at DESC, id);
CREATE INDEX payroll_payslips_staff_month
    ON payroll_payslips(organisation_id, school_id, staff_profile_id, generated_at DESC, id);

CREATE OR REPLACE FUNCTION guard_payroll_payslip_evidence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE run_status VARCHAR(20);
DECLARE run_staff_count SMALLINT;
BEGIN
    IF TG_OP = 'INSERT' AND current_setting('app.payroll_payslip_generate', true) = 'approved' THEN
        IF TG_TABLE_NAME = 'payroll_payslip_batches' THEN
            SELECT status, staff_count INTO run_status, run_staff_count
            FROM payroll_runs
            WHERE id=NEW.payroll_run_id AND organisation_id=NEW.organisation_id
              AND school_id=NEW.school_id;
            IF run_status IS DISTINCT FROM 'FINALIZED' THEN
                RAISE EXCEPTION 'payslips require a finalized payroll run';
            END IF;
            IF run_staff_count IS DISTINCT FROM NEW.total_count THEN
                RAISE EXCEPTION 'payslip batch count must match the finalized payroll run';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'payroll payslip evidence is append-only';
END; $$;

CREATE TRIGGER payroll_payslip_batches_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_payslip_batches
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_payslip_evidence();
CREATE TRIGGER payroll_payslips_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_payslips
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_payslip_evidence();

CREATE OR REPLACE FUNCTION validate_complete_payroll_payslip_batch(
    target_batch_id UUID, target_organisation_id UUID, target_school_id UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE batch_record payroll_payslip_batches%ROWTYPE;
DECLARE payslip_count INTEGER;
DECLARE distinct_result_count INTEGER;
DECLARE missing_result_count INTEGER;
BEGIN
    SELECT * INTO batch_record FROM payroll_payslip_batches
    WHERE id=target_batch_id AND organisation_id=target_organisation_id
      AND school_id=target_school_id;
    IF batch_record.id IS NULL THEN RAISE EXCEPTION 'payslip batch was not found'; END IF;

    SELECT count(*), count(DISTINCT payroll_run_staff_result_id)
      INTO payslip_count, distinct_result_count
    FROM payroll_payslips
    WHERE payroll_payslip_batch_id=target_batch_id
      AND organisation_id=target_organisation_id AND school_id=target_school_id;

    SELECT count(*) INTO missing_result_count
    FROM payroll_run_staff_results result
    WHERE result.payroll_run_id=batch_record.payroll_run_id
      AND result.organisation_id=target_organisation_id AND result.school_id=target_school_id
      AND NOT EXISTS (
        SELECT 1 FROM payroll_payslips payslip
        WHERE payslip.payroll_payslip_batch_id=target_batch_id
          AND payslip.payroll_run_staff_result_id=result.id
          AND payslip.organisation_id=result.organisation_id
          AND payslip.school_id=result.school_id
      );

    IF payslip_count <> batch_record.total_count
       OR distinct_result_count <> batch_record.total_count
       OR missing_result_count <> 0 THEN
        RAISE EXCEPTION 'payslip batch does not cover every finalized payroll result exactly once';
    END IF;
END; $$;

CREATE OR REPLACE FUNCTION enforce_complete_payroll_payslip_batch()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM validate_complete_payroll_payslip_batch(NEW.id, NEW.organisation_id, NEW.school_id);
    RETURN NULL;
END; $$;

CREATE CONSTRAINT TRIGGER payroll_payslip_batches_complete
    AFTER INSERT ON payroll_payslip_batches
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION enforce_complete_payroll_payslip_batch();

ALTER TABLE payroll_payslip_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_payslips ENABLE ROW LEVEL SECURITY;

CREATE POLICY payroll_payslip_batches_tenant_all ON payroll_payslip_batches
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());
CREATE POLICY payroll_payslips_tenant_all ON payroll_payslips
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON TABLE payroll_payslip_batches IS
    'Retry-safe generation evidence for one finalized monthly payroll run. One complete immutable batch covers every frozen staff result.';
COMMENT ON TABLE payroll_payslips IS
    'Private immutable PDF evidence derived only from finalized payroll results. Delivery is school-wide for payroll readers or own-record only for a linked staff account; no public verification token exists.';

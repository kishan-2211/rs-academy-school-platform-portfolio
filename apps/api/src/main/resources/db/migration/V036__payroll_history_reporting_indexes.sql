-- Forward-only additive indexes; no historical payroll or accounting values change.
-- These bounded reporting scans are always constrained to organisation and school.
CREATE INDEX payroll_accounting_batches_school_posting_date_idx
    ON payroll_accounting_posting_batches (organisation_id, school_id, posting_date DESC, id);

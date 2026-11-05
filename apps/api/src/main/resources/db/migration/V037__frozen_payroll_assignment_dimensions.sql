-- Reporting metadata only: no salary/history backfill or financial recalculation.
ALTER TABLE staff_assignments ADD CONSTRAINT staff_assignments_payroll_identity_unique
    UNIQUE (id, organisation_id, school_id, staff_profile_id);

ALTER TABLE payroll_run_staff_results
    ADD COLUMN assignment_snapshot_status VARCHAR(24) NOT NULL DEFAULT 'NOT_CAPTURED',
    ADD COLUMN assignment_snapshot_date DATE,
    ADD COLUMN assignment_id_snapshot UUID,
    ADD COLUMN designation_name_snapshot VARCHAR(120),
    ADD COLUMN department_name_snapshot VARCHAR(120),
    ADD CONSTRAINT payroll_results_assignment_identity_fk
      FOREIGN KEY (assignment_id_snapshot, organisation_id, school_id, staff_profile_id)
      REFERENCES staff_assignments(id, organisation_id, school_id, staff_profile_id),
    ADD CONSTRAINT payroll_results_assignment_snapshot_valid CHECK (
      (assignment_snapshot_status = 'NOT_CAPTURED' AND assignment_snapshot_date IS NULL
        AND assignment_id_snapshot IS NULL AND designation_name_snapshot IS NULL AND department_name_snapshot IS NULL)
      OR (assignment_snapshot_status IN ('NO_ASSIGNMENT','AMBIGUOUS') AND assignment_snapshot_date IS NOT NULL
        AND assignment_id_snapshot IS NULL AND designation_name_snapshot IS NULL AND department_name_snapshot IS NULL)
      OR (assignment_snapshot_status = 'CAPTURED' AND assignment_snapshot_date IS NOT NULL
        AND assignment_id_snapshot IS NOT NULL AND designation_name_snapshot IS NOT NULL
        AND length(btrim(designation_name_snapshot)) BETWEEN 2 AND 120
        AND (department_name_snapshot IS NULL OR length(btrim(department_name_snapshot)) BETWEEN 2 AND 120))
    );

-- Existing append-only child triggers and organisation RLS cover all added columns.
-- NOT_CAPTURED deliberately remains on old runs; today's assignments cannot establish their past.

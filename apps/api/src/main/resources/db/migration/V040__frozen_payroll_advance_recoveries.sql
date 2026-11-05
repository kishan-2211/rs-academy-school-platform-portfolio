-- V038/V039 are shared. Recovery evidence is additive and school-scoped.
ALTER TABLE payroll_runs ADD COLUMN recovery_snapshot_version SMALLINT NOT NULL DEFAULT 0
    CHECK(recovery_snapshot_version IN (0,1));
ALTER TABLE payroll_runs ALTER COLUMN recovery_snapshot_version SET DEFAULT 1;

CREATE FUNCTION guard_payroll_recovery_snapshot_version()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.recovery_snapshot_version IS DISTINCT FROM OLD.recovery_snapshot_version THEN
        RAISE EXCEPTION 'payroll recovery snapshot version is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payroll_runs_recovery_version_immutable
    BEFORE UPDATE ON payroll_runs
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_recovery_snapshot_version();

ALTER TABLE payroll_employee_advance_installments
    ADD CONSTRAINT payroll_advance_installments_recovery_scope_unique
    UNIQUE (id, employee_advance_id, organisation_id, school_id);
ALTER TABLE payroll_employee_advance_postings
    ADD CONSTRAINT payroll_advance_postings_recovery_scope_unique
    UNIQUE (id, employee_advance_id, organisation_id, school_id);

CREATE TABLE payroll_run_advance_recoveries (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    payroll_run_staff_result_id UUID NOT NULL,
    employee_advance_id UUID NOT NULL,
    installment_id UUID NOT NULL,
    disbursement_posting_id UUID NOT NULL,
    receivable_account_id UUID NOT NULL,
    advance_number_snapshot VARCHAR(50) NOT NULL,
    due_month DATE NOT NULL,
    currency_code CHAR(3) NOT NULL,
    recovery_amount NUMERIC(16,2) NOT NULL,
    reservation_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    released_by_membership_id UUID,
    release_reason VARCHAR(500),
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payroll_recoveries_id_scope_unique UNIQUE(id,organisation_id,school_id),
    CONSTRAINT payroll_recoveries_result_fk
        FOREIGN KEY(payroll_run_staff_result_id,organisation_id,school_id)
        REFERENCES payroll_run_staff_results(id,organisation_id,school_id),
    CONSTRAINT payroll_recoveries_advance_fk
        FOREIGN KEY(employee_advance_id,organisation_id,school_id)
        REFERENCES payroll_employee_advances(id,organisation_id,school_id),
    CONSTRAINT payroll_recoveries_installment_fk
        FOREIGN KEY(installment_id,employee_advance_id,organisation_id,school_id)
        REFERENCES payroll_employee_advance_installments(id,employee_advance_id,organisation_id,school_id),
    CONSTRAINT payroll_recoveries_disbursement_fk
        FOREIGN KEY(disbursement_posting_id,employee_advance_id,organisation_id,school_id)
        REFERENCES payroll_employee_advance_postings(id,employee_advance_id,organisation_id,school_id),
    CONSTRAINT payroll_recoveries_receivable_fk
        FOREIGN KEY(receivable_account_id,organisation_id,school_id)
        REFERENCES accounting_accounts(id,organisation_id,school_id),
    CONSTRAINT payroll_recoveries_releaser_fk
        FOREIGN KEY(released_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT payroll_recoveries_one_source_per_result UNIQUE(payroll_run_staff_result_id,installment_id),
    CONSTRAINT payroll_recoveries_amount_valid CHECK(recovery_amount>0 AND recovery_amount<=1000000000000),
    CONSTRAINT payroll_recoveries_month_valid CHECK(extract(day FROM due_month)=1),
    CONSTRAINT payroll_recoveries_lifecycle_valid CHECK (
        (reservation_status='ACTIVE' AND row_version=0 AND released_at IS NULL
          AND released_by_membership_id IS NULL AND release_reason IS NULL)
        OR (reservation_status='RELEASED' AND row_version=1 AND released_at IS NOT NULL
          AND released_by_membership_id IS NOT NULL AND release_reason IS NOT NULL
          AND length(btrim(release_reason)) BETWEEN 3 AND 500))
);

CREATE UNIQUE INDEX payroll_recoveries_one_active_installment
    ON payroll_run_advance_recoveries(installment_id) WHERE reservation_status='ACTIVE';
CREATE INDEX payroll_recoveries_result_scope_idx
    ON payroll_run_advance_recoveries(organisation_id,school_id,payroll_run_staff_result_id,id);
CREATE INDEX payroll_recoveries_advance_scope_idx
    ON payroll_run_advance_recoveries(organisation_id,school_id,employee_advance_id,reservation_status,id);

ALTER TABLE payroll_run_advance_recoveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_run_advance_recoveries FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_recoveries_organisation_isolation ON payroll_run_advance_recoveries
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());

-- Serialize recovery reservation and issue reversal even under snapshot isolation.
-- This is technical concurrency state, never a financial balance or repayment total.
CREATE TABLE payroll_advance_recovery_fences (
    employee_advance_id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 0 CHECK(epoch>=0),
    FOREIGN KEY(employee_advance_id,organisation_id,school_id)
        REFERENCES payroll_employee_advances(id,organisation_id,school_id)
);
ALTER TABLE payroll_advance_recovery_fences ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_advance_recovery_fences FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_recovery_fences_organisation_isolation ON payroll_advance_recovery_fences
    USING (organisation_id=current_app_organisation_id())
    WITH CHECK (organisation_id=current_app_organisation_id());

CREATE FUNCTION touch_payroll_advance_recovery_fence(advance_id UUID,org_id UUID,target_school UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO payroll_advance_recovery_fences(employee_advance_id,organisation_id,school_id,epoch)
    VALUES(advance_id,org_id,target_school,0)
    ON CONFLICT(employee_advance_id) DO UPDATE
    SET epoch=payroll_advance_recovery_fences.epoch+1
    WHERE payroll_advance_recovery_fences.organisation_id=org_id
      AND payroll_advance_recovery_fences.school_id=target_school;
    IF NOT FOUND THEN RAISE EXCEPTION 'payroll recovery concurrency scope mismatch'; END IF;
END;
$$;

CREATE FUNCTION guard_payroll_advance_recovery()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    result_record payroll_run_staff_results%ROWTYPE;
    run_record payroll_runs%ROWTYPE;
    advance_record payroll_employee_advances%ROWTYPE;
    installment_record payroll_employee_advance_installments%ROWTYPE;
    posting_record payroll_employee_advance_postings%ROWTYPE;
    journal_record accounting_journals%ROWTYPE;
    release_actor UUID;
    release_why TEXT;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'payroll recovery evidence is immutable'; END IF;
    SELECT * INTO result_record FROM payroll_run_staff_results
    WHERE id=NEW.payroll_run_staff_result_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO run_record FROM payroll_runs
    WHERE id=result_record.payroll_run_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF result_record.id IS NULL OR run_record.id IS NULL THEN
        RAISE EXCEPTION 'payroll recovery requires a matching scoped staff result';
    END IF;
    IF TG_OP='INSERT' THEN
        IF current_setting('app.payroll_run_prepare',true) IS DISTINCT FROM 'approved'
           OR run_record.status<>'DRAFT' OR run_record.recovery_snapshot_version<>1
           OR NEW.reservation_status<>'ACTIVE' THEN
            RAISE EXCEPTION 'payroll recovery requires controlled draft preparation';
        END IF;
        SELECT * INTO posting_record FROM payroll_employee_advance_postings
        WHERE id=NEW.disbursement_posting_id AND employee_advance_id=NEW.employee_advance_id
          AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        -- Match the accounting workflow's journal-first lock order.
        SELECT * INTO journal_record FROM accounting_journals
        WHERE id=posting_record.accounting_journal_id AND organisation_id=NEW.organisation_id
          AND school_id=NEW.school_id FOR UPDATE;
        PERFORM touch_payroll_advance_recovery_fence(NEW.employee_advance_id,NEW.organisation_id,NEW.school_id);
        SELECT * INTO advance_record FROM payroll_employee_advances
        WHERE id=NEW.employee_advance_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        SELECT * INTO installment_record FROM payroll_employee_advance_installments
        WHERE id=NEW.installment_id AND employee_advance_id=NEW.employee_advance_id
          AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF advance_record.id IS NULL OR installment_record.id IS NULL OR posting_record.id IS NULL
           OR journal_record.id IS NULL OR advance_record.status<>'APPROVED'
           OR posting_record.status<>'ACTIVE' OR journal_record.status<>'POSTED'
           OR journal_record.source_module<>'PAYROLL_ADVANCE'
           OR journal_record.source_id IS DISTINCT FROM posting_record.id
           OR advance_record.staff_profile_id IS DISTINCT FROM result_record.staff_profile_id
           OR advance_record.issue_date>run_record.period_end
           OR posting_record.source_amount IS DISTINCT FROM advance_record.principal_amount
           OR NEW.recovery_amount IS DISTINCT FROM installment_record.planned_amount
           OR NEW.due_month IS DISTINCT FROM installment_record.due_month
           OR NEW.due_month IS DISTINCT FROM run_record.payroll_month
           OR NEW.receivable_account_id IS DISTINCT FROM advance_record.receivable_account_id
           OR NEW.advance_number_snapshot IS DISTINCT FROM advance_record.advance_number
           OR NEW.currency_code IS DISTINCT FROM advance_record.currency_code
           OR NEW.currency_code IS DISTINCT FROM run_record.currency_code THEN
            RAISE EXCEPTION 'payroll recovery does not match approved issued installment evidence';
        END IF;
        PERFORM validate_complete_payroll_employee_advance_posting(
            posting_record.id,NEW.organisation_id,NEW.school_id);
        RETURN NEW;
    END IF;
    IF current_setting('app.payroll_recovery_release',true) IS DISTINCT FROM 'approved'
       OR OLD.reservation_status<>'ACTIVE' OR NEW.reservation_status<>'RELEASED'
       OR NEW.row_version<>OLD.row_version+1
       OR (to_jsonb(OLD)-ARRAY['reservation_status','released_at','released_by_membership_id','release_reason','row_version'])
          IS DISTINCT FROM
          (to_jsonb(NEW)-ARRAY['reservation_status','released_at','released_by_membership_id','release_reason','row_version'])
       OR run_record.status NOT IN ('CANCELLED','REJECTED','REVERSED') THEN
        RAISE EXCEPTION 'payroll recovery evidence is immutable except controlled source release';
    END IF;
    release_actor=CASE WHEN run_record.status='REVERSED' THEN run_record.reversed_by_membership_id
                       ELSE run_record.decided_by_membership_id END;
    release_why=CASE WHEN run_record.status='REVERSED' THEN run_record.reversal_reason
                     ELSE run_record.decision_reason END;
    IF NEW.released_by_membership_id IS DISTINCT FROM release_actor
       OR NEW.release_reason IS DISTINCT FROM release_why
       OR NEW.released_at IS DISTINCT FROM run_record.updated_at THEN
        RAISE EXCEPTION 'payroll recovery release must match its controlled run decision';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payroll_recoveries_guard_evidence
    BEFORE INSERT OR UPDATE OR DELETE ON payroll_run_advance_recoveries
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_advance_recovery();

CREATE FUNCTION release_payroll_run_advance_recoveries()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status AND NEW.status IN ('CANCELLED','REJECTED','REVERSED') THEN
        PERFORM set_config('app.payroll_recovery_release','approved',true);
        UPDATE payroll_run_advance_recoveries recovery
        SET reservation_status='RELEASED',row_version=row_version+1,released_at=NEW.updated_at,
          released_by_membership_id=CASE WHEN NEW.status='REVERSED'
            THEN NEW.reversed_by_membership_id ELSE NEW.decided_by_membership_id END,
          release_reason=CASE WHEN NEW.status='REVERSED' THEN NEW.reversal_reason ELSE NEW.decision_reason END
        FROM payroll_run_staff_results result
        WHERE result.payroll_run_id=NEW.id AND result.organisation_id=NEW.organisation_id
          AND result.school_id=NEW.school_id AND recovery.payroll_run_staff_result_id=result.id
          AND recovery.organisation_id=NEW.organisation_id AND recovery.school_id=NEW.school_id
          AND recovery.reservation_status='ACTIVE';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payroll_runs_release_recoveries
    AFTER UPDATE ON payroll_runs
    FOR EACH ROW EXECUTE FUNCTION release_payroll_run_advance_recoveries();

CREATE FUNCTION guard_advance_issue_reversal_with_recoveries()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE advance_id UUID;
BEGIN
    IF OLD.source_module='PAYROLL_ADVANCE' AND OLD.status='POSTED' AND NEW.status='REVERSED' THEN
        SELECT employee_advance_id INTO advance_id FROM payroll_employee_advance_postings
        WHERE id=OLD.source_id AND accounting_journal_id=OLD.id
          AND organisation_id=OLD.organisation_id AND school_id=OLD.school_id;
        IF advance_id IS NULL THEN RAISE EXCEPTION 'advance reversal source is unverifiable'; END IF;
        PERFORM touch_payroll_advance_recovery_fence(advance_id,OLD.organisation_id,OLD.school_id);
        IF EXISTS(SELECT 1 FROM payroll_run_advance_recoveries
            WHERE employee_advance_id=advance_id AND organisation_id=OLD.organisation_id
              AND school_id=OLD.school_id AND reservation_status='ACTIVE') THEN
            RAISE EXCEPTION 'release dependent payroll recoveries before reversing the advance issue';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER accounting_advance_recovery_reversal_guard
    BEFORE UPDATE ON accounting_journals
    FOR EACH ROW EXECUTE FUNCTION guard_advance_issue_reversal_with_recoveries();

-- Repayment becomes effective only through the matching independently posted accrual.
-- Salary component mappings use expense/liability accounts; advance recovery alone credits assets.
CREATE FUNCTION guard_payroll_recovery_accrual()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE batch_record payroll_accounting_posting_batches%ROWTYPE;
BEGIN
    IF NEW.source_module<>'PAYROLL' OR OLD.status<>'SUBMITTED' OR NEW.status<>'POSTED' THEN RETURN NEW; END IF;
    SELECT * INTO batch_record FROM payroll_accounting_posting_batches
    WHERE id=NEW.source_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF batch_record.posting_type IS DISTINCT FROM 'ACCRUAL' THEN RETURN NEW; END IF;
    IF EXISTS (
        SELECT 1 FROM payroll_run_advance_recoveries recovery
        JOIN payroll_run_staff_results result ON result.id=recovery.payroll_run_staff_result_id
          AND result.organisation_id=recovery.organisation_id AND result.school_id=recovery.school_id
        JOIN payroll_employee_advance_postings issue ON issue.id=recovery.disbursement_posting_id
          AND issue.organisation_id=recovery.organisation_id AND issue.school_id=recovery.school_id
        JOIN accounting_journals journal ON journal.id=issue.accounting_journal_id
          AND journal.organisation_id=issue.organisation_id AND journal.school_id=issue.school_id
        WHERE result.payroll_run_id=batch_record.payroll_run_id
          AND recovery.organisation_id=NEW.organisation_id AND recovery.school_id=NEW.school_id
          AND (recovery.reservation_status<>'ACTIVE' OR issue.status<>'ACTIVE' OR journal.status<>'POSTED')
    ) THEN RAISE EXCEPTION 'payroll accrual requires active issued recovery evidence'; END IF;
    IF EXISTS (
        WITH expected AS (
            SELECT recovery.receivable_account_id account_id,sum(recovery.recovery_amount) amount
            FROM payroll_run_advance_recoveries recovery
            JOIN payroll_run_staff_results result ON result.id=recovery.payroll_run_staff_result_id
              AND result.organisation_id=recovery.organisation_id AND result.school_id=recovery.school_id
            WHERE result.payroll_run_id=batch_record.payroll_run_id
              AND recovery.organisation_id=NEW.organisation_id AND recovery.school_id=NEW.school_id
            GROUP BY recovery.receivable_account_id
        ), actual AS (
            SELECT line.account_id,sum(line.credit_amount) amount,sum(line.debit_amount) debit,
              bool_or(line.learner_fee_account_id IS NOT NULL OR line.vendor_id IS NOT NULL) invalid_subledger
            FROM accounting_journal_lines line
            JOIN accounting_accounts account ON account.id=line.account_id
              AND account.organisation_id=line.organisation_id AND account.school_id=line.school_id
            WHERE line.accounting_journal_id=NEW.id AND line.organisation_id=NEW.organisation_id
              AND line.school_id=NEW.school_id AND account.account_type='ASSET'
            GROUP BY line.account_id
        )
        SELECT 1 FROM expected FULL JOIN actual USING(account_id)
        WHERE expected.amount IS DISTINCT FROM actual.amount OR actual.debit<>0 OR actual.invalid_subledger
    ) THEN RAISE EXCEPTION 'payroll accrual asset credits must match frozen advance recoveries'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER payroll_recovery_accrual_guard
    BEFORE UPDATE ON accounting_journals
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_recovery_accrual();

CREATE OR REPLACE FUNCTION validate_complete_payroll_run(
    target_run_id UUID, target_organisation_id UUID, target_school_id UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE run_record payroll_runs%ROWTYPE;
DECLARE result_count INTEGER;
DECLARE scheduled_total INTEGER;
DECLARE recorded_total INTEGER;
DECLARE payable_total NUMERIC(12,4);
DECLARE full_gross_total NUMERIC(18,2);
DECLARE payable_gross_total NUMERIC(18,2);
DECLARE deduction_total NUMERIC(18,2);
DECLARE employer_total NUMERIC(18,2);
DECLARE net_total NUMERIC(18,2);
DECLARE invalid_results INTEGER;
BEGIN
    SELECT * INTO run_record FROM payroll_runs
    WHERE id=target_run_id AND organisation_id=target_organisation_id
      AND school_id=target_school_id;
    IF run_record.id IS NULL THEN RAISE EXCEPTION 'payroll run was not found'; END IF;

    SELECT count(*),COALESCE(sum(scheduled_working_days),0),COALESCE(sum(recorded_days),0),
           COALESCE(sum(payable_days),0),COALESCE(sum(full_gross_amount),0),
           COALESCE(sum(payable_gross_amount),0),COALESCE(sum(deduction_amount),0),
           COALESCE(sum(employer_contribution_amount),0),COALESCE(sum(net_amount),0)
      INTO result_count,scheduled_total,recorded_total,payable_total,full_gross_total,
           payable_gross_total,deduction_total,employer_total,net_total
    FROM payroll_run_staff_results
    WHERE payroll_run_id=target_run_id AND organisation_id=target_organisation_id
      AND school_id=target_school_id;

    IF result_count <> run_record.staff_count
       OR scheduled_total <> run_record.scheduled_working_days_total
       OR recorded_total <> run_record.recorded_days_total
       OR payable_total <> run_record.payable_days_total
       OR full_gross_total <> run_record.full_gross_total
       OR payable_gross_total <> run_record.payable_gross_total
       OR deduction_total <> run_record.deduction_total
       OR employer_total <> run_record.employer_contribution_total
       OR net_total <> run_record.net_total THEN
        RAISE EXCEPTION 'payroll run header totals do not match staff results';
    END IF;

    SELECT count(*) INTO invalid_results
    FROM payroll_run_staff_results result
    WHERE result.payroll_run_id=target_run_id
      AND result.organisation_id=target_organisation_id AND result.school_id=target_school_id
      AND (
        (SELECT count(*) FROM payroll_run_attendance_sources source
         WHERE source.payroll_run_staff_result_id=result.id
           AND source.organisation_id=result.organisation_id
           AND source.school_id=result.school_id) <> result.scheduled_working_days
        OR (SELECT COALESCE(sum(source.payroll_credit),0)
            FROM payroll_run_attendance_sources source
            WHERE source.payroll_run_staff_result_id=result.id
              AND source.organisation_id=result.organisation_id
              AND source.school_id=result.school_id) <> result.payable_days
        OR (SELECT COALESCE(sum(line.full_amount),0)
            FROM payroll_run_staff_lines line
            WHERE line.payroll_run_staff_result_id=result.id
              AND line.organisation_id=result.organisation_id AND line.school_id=result.school_id
              AND line.component_kind_snapshot='EARNING') <> result.full_gross_amount
        OR (SELECT COALESCE(sum(line.payable_amount),0)
            FROM payroll_run_staff_lines line
            WHERE line.payroll_run_staff_result_id=result.id
              AND line.organisation_id=result.organisation_id AND line.school_id=result.school_id
              AND line.component_kind_snapshot='EARNING') <> result.payable_gross_amount
        OR (SELECT COALESCE(sum(line.payable_amount),0)
            FROM payroll_run_staff_lines line
            WHERE line.payroll_run_staff_result_id=result.id
              AND line.organisation_id=result.organisation_id AND line.school_id=result.school_id
              AND line.component_kind_snapshot='DEDUCTION')
           + (SELECT COALESCE(sum(recovery.recovery_amount),0)
              FROM payroll_run_advance_recoveries recovery
              WHERE recovery.payroll_run_staff_result_id=result.id
                AND recovery.organisation_id=result.organisation_id AND recovery.school_id=result.school_id)
           <> result.deduction_amount
        OR (SELECT COALESCE(sum(line.payable_amount),0)
            FROM payroll_run_staff_lines line
            WHERE line.payroll_run_staff_result_id=result.id
              AND line.organisation_id=result.organisation_id AND line.school_id=result.school_id
              AND line.component_kind_snapshot='EMPLOYER_CONTRIBUTION')
              <> result.employer_contribution_amount
      );
    IF invalid_results <> 0 THEN
        RAISE EXCEPTION 'payroll staff results do not match frozen source and component evidence';
    END IF;
END; $$;

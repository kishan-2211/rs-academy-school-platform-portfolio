-- Accounting-only correction of an already completed gateway refund.
-- No historical migration, economic completion, receipt, fee debit or day close changes.
ALTER TABLE fee_accounting_posting_batches
    ADD COLUMN supersedes_refund_batch_id UUID,
    ADD COLUMN supersedes_refund_journal_id UUID,
    ADD CONSTRAINT fee_refund_batch_successor_shape CHECK(
        (supersedes_refund_batch_id IS NULL AND supersedes_refund_journal_id IS NULL)
        OR (event_type='REFUND' AND supersedes_refund_batch_id IS NOT NULL
            AND supersedes_refund_journal_id IS NOT NULL AND supersedes_refund_batch_id<>id)),
    ADD CONSTRAINT fee_refund_batch_previous_batch_fk
        FOREIGN KEY(supersedes_refund_batch_id,organisation_id,school_id)
        REFERENCES fee_accounting_posting_batches(id,organisation_id,school_id),
    ADD CONSTRAINT fee_refund_batch_previous_journal_fk
        FOREIGN KEY(supersedes_refund_journal_id,organisation_id,school_id)
        REFERENCES accounting_journals(id,organisation_id,school_id);
CREATE UNIQUE INDEX fee_refund_batch_one_successor_idx
    ON fee_accounting_posting_batches(supersedes_refund_batch_id)
    WHERE supersedes_refund_batch_id IS NOT NULL;

-- Security-invoker: never expose another school's history. The existing batch
-- trigger still requires the fresh actual human and both domain locks.
CREATE FUNCTION refund_journal_batch_date_ready(organisation_value UUID,school_value UUID,completion_value UUID,
    batch_value UUID,previous_batch_value UUID,previous_journal_value UUID,date_value DATE)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE completion fee_gateway_refund_postings%ROWTYPE; zone_value TEXT;
BEGIN
    SELECT * INTO completion FROM fee_gateway_refund_postings WHERE id=completion_value
        AND organisation_id=organisation_value AND school_id=school_value;
    SELECT time_zone INTO zone_value FROM schools WHERE id=school_value AND organisation_id=organisation_value AND status='ACTIVE';
    IF completion.id IS NULL OR zone_value IS NULL OR date_value IS NULL
        OR date_value<completion.posted_on OR date_value>(clock_timestamp() AT TIME ZONE zone_value)::date THEN RETURN FALSE; END IF;
    IF previous_batch_value IS NULL THEN
        RETURN previous_journal_value IS NULL AND date_value=completion.posted_on;
    END IF;
    RETURN previous_journal_value IS NOT NULL AND EXISTS(
        SELECT 1 FROM fee_accounting_posting_batches previous
        JOIN accounting_journals journal ON journal.id=previous.accounting_journal_id
            AND journal.organisation_id=previous.organisation_id AND journal.school_id=previous.school_id
        JOIN fee_accounting_posting_items item ON item.posting_batch_id=previous.id
            AND item.organisation_id=previous.organisation_id AND item.school_id=previous.school_id
        WHERE previous.id=previous_batch_value AND previous.organisation_id=organisation_value AND previous.school_id=school_value
            AND previous.id<>batch_value AND previous.event_type='REFUND' AND previous.refund_posting_id=completion.id
            AND previous.original_payment_posting_item_id=completion.original_payment_posting_item_id
            AND previous.accounting_journal_id=previous_journal_value AND journal.source_module='FEES'
            AND journal.source_id=previous.id AND journal.status IN ('REJECTED','REVERSED')
            AND item.fee_ledger_entry_id=completion.fee_ledger_entry_id AND item.status='RELEASED'
            AND date_value>=previous.business_date
            AND NOT EXISTS(SELECT 1 FROM fee_accounting_posting_batches sibling
                WHERE sibling.supersedes_refund_batch_id=previous.id AND sibling.id<>batch_value
                    AND sibling.organisation_id=organisation_value AND sibling.school_id=school_value));
END; $$;

CREATE OR REPLACE FUNCTION guard_fee_accounting_batch()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    config_row fee_accounting_configurations%ROWTYPE;
    period_row accounting_periods%ROWTYPE;
    journal_row accounting_journals%ROWTYPE;
    later_effective DATE;
    completion fee_gateway_refund_postings%ROWTYPE;
    original_item fee_accounting_posting_items%ROWTYPE;
    original_batch fee_accounting_posting_batches%ROWTYPE;
    target_year accounting_financial_years%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO config_row FROM fee_accounting_configurations
          WHERE id=NEW.configuration_id AND organisation_id=NEW.organisation_id
            AND school_id=NEW.school_id AND status IN ('APPROVED','SUPERSEDED');
        IF NEW.event_type='REFUND' THEN
            IF current_setting('transaction_isolation')<>'read committed' THEN
                RAISE EXCEPTION 'refund journal preparation requires fresh read committed evidence' USING ERRCODE='40001';
            END IF;
            PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
            PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || NEW.school_id::text,0));
            PERFORM require_gateway_refund_posting_actor(NEW.organisation_id,NEW.school_id,NEW.prepared_by_membership_id);
            SELECT * INTO completion FROM fee_gateway_refund_postings WHERE id=NEW.refund_posting_id
                AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
            SELECT * INTO original_item FROM fee_accounting_posting_items WHERE id=NEW.original_payment_posting_item_id
                AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
            SELECT * INTO original_batch FROM fee_accounting_posting_batches WHERE id=original_item.posting_batch_id
                AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
            SELECT * INTO journal_row FROM accounting_journals WHERE id=original_batch.accounting_journal_id
                AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
            SELECT * INTO target_year FROM accounting_financial_years WHERE id=NEW.financial_year_id
                AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
            IF completion.id IS NULL OR original_item.id IS NULL OR original_item.status<>'ACTIVE'
                OR completion.original_payment_posting_item_id IS DISTINCT FROM original_item.id
                OR original_batch.id IS NULL OR original_batch.event_type<>'PAYMENT'
                OR original_batch.configuration_id IS DISTINCT FROM NEW.configuration_id
                OR config_row.id IS NULL OR config_row.financial_year_id IS DISTINCT FROM original_batch.financial_year_id
                OR journal_row.id IS NULL OR journal_row.status<>'POSTED' OR journal_row.source_module<>'FEES'
                OR journal_row.source_id IS DISTINCT FROM original_batch.id
                OR EXISTS(SELECT 1 FROM accounting_reversal_requests reversal WHERE reversal.accounting_journal_id=journal_row.id
                    AND reversal.organisation_id=NEW.organisation_id AND reversal.school_id=NEW.school_id AND reversal.status IN ('PENDING','APPROVED'))
                OR EXISTS(SELECT 1 FROM accounting_journals reversal WHERE reversal.reversal_of_journal_id=journal_row.id
                    AND reversal.organisation_id=NEW.organisation_id AND reversal.school_id=NEW.school_id)
                OR NOT refund_journal_batch_date_ready(NEW.organisation_id,NEW.school_id,NEW.refund_posting_id,
                    NEW.id,NEW.supersedes_refund_batch_id,NEW.supersedes_refund_journal_id,NEW.business_date)
                OR NEW.source_amount IS DISTINCT FROM completion.amount OR NEW.source_count<>1 OR NEW.disposition<>'JOURNAL'
                OR target_year.id IS NULL OR target_year.status<>'OPEN'
                OR target_year.accounting_basis IS DISTINCT FROM config_row.accounting_basis_snapshot
                OR target_year.currency_code IS DISTINCT FROM config_row.currency_code
                OR target_year.currency_code IS DISTINCT FROM completion.currency_code
                OR NEW.business_date NOT BETWEEN target_year.starts_on AND target_year.ends_on THEN
                RAISE EXCEPTION 'refund batch requires its exact original mapping and current open compatible financial book';
            END IF;
        ELSE
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
           OR OLD.prepared_at IS DISTINCT FROM NEW.prepared_at
           OR OLD.refund_posting_id IS DISTINCT FROM NEW.refund_posting_id
           OR OLD.original_payment_posting_item_id IS DISTINCT FROM NEW.original_payment_posting_item_id
           OR OLD.supersedes_refund_batch_id IS DISTINCT FROM NEW.supersedes_refund_batch_id
           OR OLD.supersedes_refund_journal_id IS DISTINCT FROM NEW.supersedes_refund_journal_id THEN
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
           OR (entry_row.effective_on<>batch_row.business_date AND NOT (
               batch_row.event_type='REFUND' AND batch_row.supersedes_refund_batch_id IS NOT NULL
               AND entry_row.source_type='GATEWAY_REFUND_POSTING' AND entry_row.source_id=batch_row.refund_posting_id)) THEN
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
        IF journal_status IS NULL OR journal_status NOT IN ('REJECTED','REVERSED') THEN
            RAISE EXCEPTION 'Only rejected or reversed fee journals may release source entries for re-preparation';
        END IF;
        SELECT * INTO batch_row FROM fee_accounting_posting_batches
            WHERE id=OLD.posting_batch_id AND organisation_id=OLD.organisation_id AND school_id=OLD.school_id;
        IF batch_row.event_type='REFUND' THEN
            IF current_setting('transaction_isolation')<>'read committed' THEN
                RAISE EXCEPTION 'refund journal release requires fresh read committed evidence' USING ERRCODE='40001';
            END IF;
            PERFORM pg_advisory_xact_lock(hashtextextended(OLD.school_id::text || ':finance',0));
            PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || OLD.school_id::text,0));
            PERFORM require_gateway_refund_posting_actor(OLD.organisation_id,OLD.school_id,NEW.released_by_membership_id);
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Fee accounting posting source evidence is append-only';
END $$;

CREATE OR REPLACE FUNCTION check_gateway_refund_posting_coupling() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE completion_id UUID; completion fee_gateway_refund_postings%ROWTYPE; linked_count INTEGER;
    linked_journal_id UUID; linked_configuration_id UUID; incorrect_lines BOOLEAN;
BEGIN
    IF TG_TABLE_NAME='fee_gateway_refund_postings' THEN completion_id:=NEW.id;
    ELSIF TG_TABLE_NAME='fee_ledger_entries' THEN completion_id:=NEW.source_id;
    ELSIF TG_TABLE_NAME='fee_accounting_posting_items' THEN
        SELECT refund_posting_id INTO completion_id FROM fee_accounting_posting_batches
            WHERE id=NEW.posting_batch_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF completion_id IS NULL THEN RETURN NULL; END IF;
    ELSE completion_id:=NEW.refund_posting_id; END IF;
    SELECT * INTO completion FROM fee_gateway_refund_postings WHERE id=completion_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF completion.id IS NULL THEN RAISE EXCEPTION 'refund completion scope is unavailable'; END IF;
    -- Do not call reopening_source_ready here: this transaction's own completion
    -- intentionally makes reopening ineligible. Reprove the immutable HOLD and
    -- absence of contradictory witnesses directly instead.
    IF NOT EXISTS(SELECT 1 FROM fee_gateway_refund_successes success
        JOIN fee_gateway_refund_decisions approval ON approval.id=success.approval_decision_id
            AND approval.refund_request_id=success.refund_request_id
            AND approval.organisation_id=success.organisation_id AND approval.school_id=success.school_id
        WHERE success.refund_request_id=completion.refund_request_id
            AND success.organisation_id=completion.organisation_id AND success.school_id=completion.school_id
            AND success.status='HOLD' AND approval.decision='APPROVE'
            AND success.verified_read_id=completion.verified_read_id AND success.evidence_sha256=completion.success_evidence_sha256
            AND success.source_version=completion.source_version AND success.fee_payment_id=completion.fee_payment_id
            AND success.learner_fee_account_id=completion.learner_fee_account_id
            AND success.amount=completion.amount AND success.currency_code=completion.currency_code
            AND NOT EXISTS(SELECT 1 FROM fee_gateway_refund_reads witness WHERE witness.refund_request_id=success.refund_request_id
                AND witness.organisation_id=success.organisation_id AND witness.school_id=success.school_id
                AND ((witness.outcome='VERIFIED_SUCCESS' AND witness.evidence_sha256<>success.evidence_sha256)
                    OR (witness.outcome='REVIEW_REQUIRED' AND witness.refund_status='SUCCESS')))) THEN
        RAISE EXCEPTION 'refund completion requires its exact noncontradictory retained success at commit';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM fee_ledger_entries entry WHERE entry.id=completion.fee_ledger_entry_id
        AND entry.organisation_id=completion.organisation_id AND entry.school_id=completion.school_id
        AND entry.learner_fee_account_id=completion.learner_fee_account_id AND entry.entry_type='REFUND' AND entry.direction='DEBIT'
        AND entry.amount=completion.amount AND entry.effective_on=completion.posted_on
        AND entry.source_type='GATEWAY_REFUND_POSTING' AND entry.source_id=completion.id
        AND entry.learner_fee_charge_id IS NULL AND entry.reverses_entry_id IS NULL AND entry.gateway_capture_id IS NULL
        AND entry.actor_kind='HUMAN' AND entry.created_by_membership_id=completion.posted_by_membership_id) THEN
        RAISE EXCEPTION 'reviewed refund completion cannot commit without its exact immutable learner debit';
    END IF;
    SELECT count(*),(array_agg(journal.id))[1],(array_agg(batch.configuration_id))[1]
        INTO linked_count,linked_journal_id,linked_configuration_id FROM fee_accounting_posting_items item
    JOIN fee_accounting_posting_batches batch ON batch.id=item.posting_batch_id
        AND batch.organisation_id=item.organisation_id AND batch.school_id=item.school_id
    JOIN accounting_journals journal ON journal.id=batch.accounting_journal_id
        AND journal.organisation_id=batch.organisation_id AND journal.school_id=batch.school_id
    WHERE item.fee_ledger_entry_id=completion.fee_ledger_entry_id AND item.status='ACTIVE'
        AND item.organisation_id=completion.organisation_id AND item.school_id=completion.school_id
        AND batch.refund_posting_id=completion.id AND batch.original_payment_posting_item_id=completion.original_payment_posting_item_id
        AND batch.event_type='REFUND' AND batch.disposition='JOURNAL' AND batch.source_count=1 AND batch.source_amount=completion.amount
        AND refund_journal_batch_date_ready(batch.organisation_id,batch.school_id,completion.id,
            batch.id,batch.supersedes_refund_batch_id,batch.supersedes_refund_journal_id,batch.business_date)
        AND journal.source_module='FEES' AND journal.source_id=batch.id
        AND journal.status IN ('SUBMITTED','POSTED') AND journal.posting_date=batch.business_date
        AND journal.financial_year_id=batch.financial_year_id AND journal.accounting_period_id=batch.accounting_period_id
        AND journal.total_amount=completion.amount AND journal.voucher_type='PAYMENT' AND journal.reversal_of_journal_id IS NULL
        AND (SELECT count(*) FROM fee_accounting_posting_items other_item WHERE other_item.posting_batch_id=batch.id
            AND other_item.organisation_id=batch.organisation_id AND other_item.school_id=batch.school_id AND other_item.status='ACTIVE')=1;
    IF linked_count<>1 THEN
        RAISE EXCEPTION 'new refund completion and exact learner debit require one coupled submitted common accounting journal';
    END IF;
    -- Balanced arbitrary accounts are not sufficient. Match the actual common
    -- journal to the original mapping and frozen refund allocations, including
    -- receivable learner dimension and separate debit/credit sides.
    WITH expected(account_id,learner_fee_account_id,vendor_id,side,amount) AS (
        SELECT mapping.income_account_id,NULL::UUID,NULL::UUID,'DEBIT'::TEXT,sum(allocation.amount)
        FROM fee_gateway_refund_allocations allocation
        JOIN fee_accounting_configurations config ON config.id=linked_configuration_id
            AND config.organisation_id=allocation.organisation_id AND config.school_id=allocation.school_id
            AND config.accounting_basis_snapshot='CASH'
        JOIN fee_accounting_head_mappings mapping ON mapping.configuration_id=config.id
            AND mapping.organisation_id=config.organisation_id AND mapping.school_id=config.school_id
            AND mapping.fee_head_id=allocation.fee_head_id
        WHERE allocation.refund_request_id=completion.refund_request_id
            AND allocation.organisation_id=completion.organisation_id AND allocation.school_id=completion.school_id
        GROUP BY mapping.income_account_id
        UNION ALL
        SELECT config.receivable_account_id,completion.learner_fee_account_id,NULL::UUID,'DEBIT'::TEXT,completion.amount
        FROM fee_accounting_configurations config WHERE config.id=linked_configuration_id
            AND config.organisation_id=completion.organisation_id AND config.school_id=completion.school_id
            AND config.accounting_basis_snapshot='ACCRUAL'
        UNION ALL
        SELECT tender.deposit_account_id,NULL::UUID,NULL::UUID,'CREDIT'::TEXT,completion.amount
        FROM fee_accounting_tender_mappings tender WHERE tender.configuration_id=linked_configuration_id
            AND tender.organisation_id=completion.organisation_id AND tender.school_id=completion.school_id
            AND tender.payment_method='ONLINE_PROVIDER'
    ), actual AS (
        SELECT line.account_id,line.learner_fee_account_id,line.vendor_id,
            CASE WHEN line.debit_amount>0 THEN 'DEBIT' ELSE 'CREDIT' END AS side,
            sum(line.debit_amount+line.credit_amount) AS amount
        FROM accounting_journal_lines line WHERE line.accounting_journal_id=linked_journal_id
            AND line.organisation_id=completion.organisation_id AND line.school_id=completion.school_id
        GROUP BY line.account_id,line.learner_fee_account_id,line.vendor_id,(CASE WHEN line.debit_amount>0 THEN 'DEBIT' ELSE 'CREDIT' END)
    ) SELECT EXISTS((SELECT * FROM expected EXCEPT SELECT * FROM actual)
        UNION ALL (SELECT * FROM actual EXCEPT SELECT * FROM expected)) INTO incorrect_lines;
    IF incorrect_lines THEN
        RAISE EXCEPTION 'refund common journal must use exact original mapped accounts, sides, dimensions and frozen partial allocations';
    END IF;
    RETURN NULL;
END; $$;

-- Releasing a rejected/reversed source alone must never strand the economic
-- completion. The replacement and its exact common journal are required at commit.
CREATE CONSTRAINT TRIGGER fee_refund_item_repair_coupled AFTER UPDATE ON fee_accounting_posting_items
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(OLD.status='ACTIVE' AND NEW.status='RELEASED')
    EXECUTE FUNCTION check_gateway_refund_posting_coupling();

COMMENT ON COLUMN fee_accounting_posting_batches.supersedes_refund_batch_id IS 'Immutable exact rejected/reversed predecessor for an accounting-only refund correction. Unique successor prevents forks; original completion/debit/date and fee-day snapshots remain unchanged.';
COMMENT ON COLUMN fee_accounting_posting_batches.supersedes_refund_journal_id IS 'Exact original rejected/reversed common journal selected by the authorized preparer. Replacement remains SUBMITTED for independent approval; this cannot issue another refund or waive dues.';


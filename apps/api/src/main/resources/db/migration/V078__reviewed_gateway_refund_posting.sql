-- A reviewed financial completion of one independently verified gateway refund.
-- The V075 HOLD and V073 allocations stay immutable evidence. Only this new
-- completion restores dues; pending intents/reads/HOLDs never reduce net paid.
CREATE TABLE fee_gateway_refund_postings (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    refund_request_id UUID NOT NULL UNIQUE, fee_payment_id UUID NOT NULL, learner_fee_account_id UUID NOT NULL,
    original_payment_posting_item_id UUID NOT NULL, fee_ledger_entry_id UUID NOT NULL UNIQUE,
    verified_read_id UUID NOT NULL, success_evidence_sha256 CHAR(64) NOT NULL,
    source_version SMALLINT NOT NULL CHECK(source_version=1),
    amount NUMERIC NOT NULL CHECK(amount>0 AND amount<=100000000 AND scale(amount)<=2),
    currency_code CHAR(3) NOT NULL CHECK(currency_code='INR'),
    posted_on DATE NOT NULL CHECK(posted_on BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    posted_by_user_id UUID NOT NULL REFERENCES user_accounts(id), posted_by_membership_id UUID NOT NULL,
    posting_reason VARCHAR(500) NOT NULL CHECK(length(btrim(posting_reason)) BETWEEN 3 AND 500 AND posting_reason !~ '[[:cntrl:]]'),
    client_request_id UUID NOT NULL, request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
    posted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(posted_at)),
    UNIQUE(id,organisation_id,school_id),
    UNIQUE(school_id,posted_by_user_id,client_request_id),
    FOREIGN KEY(refund_request_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_successes(refund_request_id,organisation_id,school_id),
    FOREIGN KEY(refund_request_id,fee_payment_id,learner_fee_account_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_requests(id,fee_payment_id,learner_fee_account_id,organisation_id,school_id),
    FOREIGN KEY(original_payment_posting_item_id,organisation_id,school_id)
        REFERENCES fee_accounting_posting_items(id,organisation_id,school_id),
    -- Completion is inserted first so the debit guard can prove its source.
    -- Both sides, and the common accounting journal, must exist at commit.
    FOREIGN KEY(fee_ledger_entry_id,organisation_id,school_id)
        REFERENCES fee_ledger_entries(id,organisation_id,school_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(verified_read_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_reads(id,organisation_id,school_id),
    FOREIGN KEY(posted_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CHECK(success_evidence_sha256 ~ '^[a-f0-9]{64}$')
);
CREATE INDEX fee_gateway_refund_postings_account_date_idx
    ON fee_gateway_refund_postings(organisation_id,school_id,learner_fee_account_id,posted_on,id);
CREATE INDEX fee_gateway_refund_postings_payment_idx
    ON fee_gateway_refund_postings(organisation_id,school_id,fee_payment_id);
CREATE INDEX fee_gateway_refund_postings_original_item_idx
    ON fee_gateway_refund_postings(organisation_id,school_id,original_payment_posting_item_id);

ALTER TABLE fee_accounting_posting_batches
    ADD COLUMN refund_posting_id UUID,
    ADD COLUMN original_payment_posting_item_id UUID,
    ADD CONSTRAINT fee_accounting_posting_batches_refund_fk
        FOREIGN KEY(refund_posting_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_postings(id,organisation_id,school_id),
    ADD CONSTRAINT fee_accounting_posting_batches_original_payment_item_fk
        FOREIGN KEY(original_payment_posting_item_id,organisation_id,school_id)
        REFERENCES fee_accounting_posting_items(id,organisation_id,school_id);
ALTER TABLE fee_accounting_posting_batches DROP CONSTRAINT fee_accounting_posting_batches_event_valid;
ALTER TABLE fee_accounting_posting_batches ADD CONSTRAINT fee_accounting_posting_batches_event_valid
    CHECK(event_type IN ('CHARGE','CONCESSION','PAYMENT','PAYMENT_REVERSAL','REFUND'));
ALTER TABLE fee_accounting_posting_batches ADD CONSTRAINT fee_accounting_posting_batches_refund_shape CHECK(
    (event_type='REFUND' AND refund_posting_id IS NOT NULL AND original_payment_posting_item_id IS NOT NULL
        AND source_count=1 AND disposition='JOURNAL')
    OR (event_type<>'REFUND' AND refund_posting_id IS NULL AND original_payment_posting_item_id IS NULL));
-- Many historical batches may represent correction/re-preparation of the SAME
-- immutable completion/debit. Existing one-ACTIVE-item exclusivity still applies.
CREATE INDEX fee_accounting_posting_batches_refund_idx
    ON fee_accounting_posting_batches(organisation_id,school_id,refund_posting_id) WHERE refund_posting_id IS NOT NULL;

CREATE FUNCTION fee_charge_net_paid(organisation_value UUID,school_value UUID,charge_value UUID,as_of_value DATE DEFAULT NULL)
RETURNS NUMERIC LANGUAGE sql STABLE SECURITY INVOKER AS $$
    SELECT COALESCE((SELECT sum(allocation.amount)
        FROM fee_payment_allocations allocation
        JOIN fee_payments payment ON payment.id=allocation.fee_payment_id
            AND payment.organisation_id=allocation.organisation_id AND payment.school_id=allocation.school_id
        LEFT JOIN fee_ledger_entries reversal ON reversal.id=payment.reversal_ledger_entry_id
            AND reversal.organisation_id=payment.organisation_id AND reversal.school_id=payment.school_id
        WHERE allocation.organisation_id=organisation_value AND allocation.school_id=school_value
            AND allocation.learner_fee_charge_id=charge_value
            AND ((as_of_value IS NULL AND payment.status='POSTED')
                OR (as_of_value IS NOT NULL AND payment.received_on<=as_of_value
                    AND (payment.status='POSTED' OR (payment.status='REVERSED' AND reversal.effective_on>as_of_value))))),0)
        -COALESCE((SELECT sum(allocation.amount)
        FROM fee_gateway_refund_allocations allocation
        JOIN fee_gateway_refund_postings completion ON completion.refund_request_id=allocation.refund_request_id
            AND completion.organisation_id=allocation.organisation_id AND completion.school_id=allocation.school_id
            AND completion.fee_payment_id=allocation.fee_payment_id
            AND completion.learner_fee_account_id=allocation.learner_fee_account_id
        WHERE allocation.organisation_id=organisation_value AND allocation.school_id=school_value
            AND allocation.learner_fee_charge_id=charge_value
            AND (as_of_value IS NULL OR completion.posted_on<=as_of_value)),0);
$$;
-- The legacy V030 path remains byte-for-byte equivalent for non-refund events.
-- A REFUND alone uses the ORIGINAL frozen mapping with a CURRENT open target
-- financial year/period of the same basis/currency; it never selects a new mapping.
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
                OR NEW.business_date IS DISTINCT FROM completion.posted_on
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
           OR OLD.original_payment_posting_item_id IS DISTINCT FROM NEW.original_payment_posting_item_id THEN
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
-- The existing generic journal constraint compares membership UUIDs. For refund
-- journals only, require the real authorized reviewer and a different HUMAN user
-- so a second membership cannot be used to self-approve the same journal.
CREATE FUNCTION guard_gateway_refund_journal_reviewer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE maker_user UUID; submitter_user UUID;
BEGIN
    IF NEW.source_module='FEES' AND OLD.status='SUBMITTED' AND NEW.status IN ('POSTED','REJECTED')
        AND EXISTS(SELECT 1 FROM fee_accounting_posting_batches batch WHERE batch.id=NEW.source_id
            AND batch.organisation_id=NEW.organisation_id AND batch.school_id=NEW.school_id AND batch.event_type='REFUND') THEN
        IF current_setting('transaction_isolation') IS DISTINCT FROM 'read committed' THEN
            RAISE EXCEPTION 'refund common journal decisions require fresh read committed authority' USING ERRCODE='40001';
        END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || NEW.school_id::text,0));
        SELECT user_id INTO maker_user FROM memberships WHERE id=NEW.created_by_membership_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        SELECT user_id INTO submitter_user FROM memberships WHERE id=NEW.submitted_by_membership_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id OR current_app_user_id() IS NULL
            OR maker_user IS NULL OR submitter_user IS NULL OR current_app_user_id() IN (maker_user,submitter_user) THEN
            RAISE EXCEPTION 'refund common journal decisions require a different actual authorized human';
        END IF;
        PERFORM 1 FROM memberships member
        JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
        JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
        JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
        JOIN membership_roles assignment ON assignment.membership_id=member.id
        JOIN roles role ON role.id=assignment.role_id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
        JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code='accounting.post'
        WHERE member.id=NEW.decided_by_membership_id AND member.user_id=current_app_user_id()
            AND member.organisation_id=NEW.organisation_id AND member.school_id=NEW.school_id AND member.status='ACTIVE'
            AND member.valid_from<=clock_timestamp() AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
        FOR SHARE OF member,actor,school,assignment;
        IF NOT FOUND THEN RAISE EXCEPTION 'refund common journal decisions require live scoped posting authority'; END IF;
    END IF;
    RETURN NEW;
END; $$;

-- A refund is a separate money-out movement, not a rewritten gross receipt or
-- PAYMENT_REVERSAL. Historical closes get additive zero columns only; their
-- frozen evidence JSON/hash and approval state remain unchanged.
ALTER TABLE fee_day_closes
    ADD COLUMN refund_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN refund_amount NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE fee_day_closes DROP CONSTRAINT fee_day_closes_counts_valid;
ALTER TABLE fee_day_closes ADD CONSTRAINT fee_day_closes_counts_valid
    CHECK(collection_count>=0 AND reversal_count>=0 AND refund_count>=0);
ALTER TABLE fee_day_closes DROP CONSTRAINT fee_day_closes_amounts_valid;
ALTER TABLE fee_day_closes ADD CONSTRAINT fee_day_closes_amounts_valid CHECK(
    gross_collections>=0 AND reversal_amount>=0 AND refund_amount>=0
    AND net_collections=gross_collections-reversal_amount-refund_amount
    AND (declared_total>=0 OR (refund_count>0 AND refund_amount>0))
    AND variance_total=declared_total-net_collections);

CREATE FUNCTION guard_refund_day_close_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE actual_refunds INTEGER; actual_amount NUMERIC; actual_online NUMERIC; declaration RECORD; total_declared NUMERIC:=0; declaration_count INTEGER:=0;
    maker_user UUID;
BEGIN
    IF TG_OP='UPDATE' THEN
        IF OLD.refund_count IS DISTINCT FROM NEW.refund_count OR OLD.refund_amount IS DISTINCT FROM NEW.refund_amount THEN
            RAISE EXCEPTION 'fee day close refund evidence is immutable';
        END IF;
        IF OLD.refund_count=0 THEN RETURN NEW; END IF;
        IF OLD.status='PENDING' AND NEW.status IN ('APPROVED','REJECTED') THEN
            SELECT user_id INTO maker_user FROM memberships WHERE id=OLD.submitted_by_membership_id
                AND organisation_id=OLD.organisation_id AND school_id=OLD.school_id;
            IF maker_user IS NULL OR current_app_user_id() IS NULL OR current_app_user_id()=maker_user THEN
                RAISE EXCEPTION 'refund-bearing finance close requires an independent actual human reviewer';
            END IF;
            PERFORM 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
            JOIN membership_roles assignment ON assignment.membership_id=member.id
            JOIN roles role ON role.id=assignment.role_id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
            JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code='fees.close.approve'
            WHERE member.id=NEW.decided_by_membership_id AND member.user_id=current_app_user_id()
                AND member.organisation_id=NEW.organisation_id AND member.school_id=NEW.school_id AND member.status='ACTIVE'
                AND member.valid_from<=clock_timestamp() AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp());
            IF NOT FOUND THEN RAISE EXCEPTION 'refund-bearing finance close requires live scoped approval authority'; END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF current_setting('transaction_isolation') IS DISTINCT FROM 'read committed' THEN
        RAISE EXCEPTION 'refund-bearing finance close requires fresh read committed evidence' USING ERRCODE='40001';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    SELECT count(*),COALESCE(sum(completion.amount),0) INTO actual_refunds,actual_amount
    FROM fee_gateway_refund_postings completion WHERE completion.organisation_id=NEW.organisation_id
        AND completion.school_id=NEW.school_id AND completion.posted_on=NEW.business_date AND completion.currency_code=NEW.currency_code;
    IF NEW.refund_count=0 AND NEW.refund_amount=0 AND NEW.declared_total>=0 AND actual_refunds=0
        AND COALESCE((NEW.declared_method_totals->>'ONLINE_PROVIDER')::NUMERIC,0)>=0 THEN RETURN NEW; END IF;
    IF NEW.refund_count IS DISTINCT FROM actual_refunds OR NEW.refund_amount IS DISTINCT FROM actual_amount THEN
        RAISE EXCEPTION 'finance close must preserve the actual completed refund count and exact partial amount';
    END IF;
    IF current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id OR current_app_user_id() IS NULL THEN
        RAISE EXCEPTION 'refund-bearing finance close requires its actual human tenant context';
    END IF;
    PERFORM 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
    JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
    JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
    JOIN membership_roles assignment ON assignment.membership_id=member.id
    JOIN roles role ON role.id=assignment.role_id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
    JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code='fees.close.propose'
    WHERE member.id=NEW.submitted_by_membership_id AND member.user_id=current_app_user_id()
        AND member.organisation_id=NEW.organisation_id AND member.school_id=NEW.school_id AND member.status='ACTIVE'
        AND member.valid_from<=clock_timestamp() AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp());
    IF NOT FOUND THEN RAISE EXCEPTION 'refund-bearing finance close requires live scoped maker authority'; END IF;
    SELECT COALESCE((SELECT sum(payment.amount) FROM fee_payments payment
        JOIN learner_fee_accounts account ON account.id=payment.learner_fee_account_id
            AND account.organisation_id=payment.organisation_id AND account.school_id=payment.school_id
        WHERE payment.organisation_id=NEW.organisation_id AND payment.school_id=NEW.school_id
            AND payment.payment_method='ONLINE_PROVIDER' AND payment.received_on=NEW.business_date AND account.currency_code=NEW.currency_code),0)
        -COALESCE((SELECT sum(payment.amount) FROM fee_payments payment
            JOIN fee_ledger_entries reversal ON reversal.id=payment.reversal_ledger_entry_id
                AND reversal.organisation_id=payment.organisation_id AND reversal.school_id=payment.school_id
            JOIN learner_fee_accounts account ON account.id=payment.learner_fee_account_id
                AND account.organisation_id=payment.organisation_id AND account.school_id=payment.school_id
            WHERE payment.organisation_id=NEW.organisation_id AND payment.school_id=NEW.school_id
                AND payment.payment_method='ONLINE_PROVIDER' AND reversal.effective_on=NEW.business_date
                AND reversal.entry_type='PAYMENT_REVERSAL' AND reversal.direction='DEBIT' AND account.currency_code=NEW.currency_code),0)
        -actual_amount INTO actual_online;
    IF jsonb_typeof(NEW.declared_method_totals)<>'object' OR (NEW.system_method_totals->>'ONLINE_PROVIDER')::NUMERIC IS DISTINCT FROM actual_online THEN
        RAISE EXCEPTION 'finance close online movement must agree with its real receipt, reversal and refund sources';
    END IF;
    FOR declaration IN SELECT key,value FROM jsonb_each(NEW.declared_method_totals) LOOP
        declaration_count:=declaration_count+1;
        IF jsonb_typeof(declaration.value)<>'number' OR declaration.key NOT IN ('CASH','BANK_TRANSFER','CHEQUE','SCHOOL_UPI','ONLINE_PROVIDER') THEN
            RAISE EXCEPTION 'finance close tender declarations must be exact supported money values';
        END IF;
        IF (declaration.value::TEXT)::NUMERIC<0 AND (declaration.key<>'ONLINE_PROVIDER' OR actual_amount<=0 OR actual_online>=0
            OR (declaration.value::TEXT)::NUMERIC<actual_online) THEN
            RAISE EXCEPTION 'only genuine refund-negative online movements permit a bounded signed tender declaration';
        END IF;
        total_declared:=total_declared+(declaration.value::TEXT)::NUMERIC;
    END LOOP;
    IF declaration_count<>5 OR total_declared IS DISTINCT FROM NEW.declared_total THEN RAISE EXCEPTION 'finance close requires all five signed declarations matching the frozen declared total'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_day_closes_refund_evidence_guard BEFORE INSERT OR UPDATE ON fee_day_closes
    FOR EACH ROW EXECUTE FUNCTION guard_refund_day_close_evidence();
CREATE TRIGGER accounting_journals_gateway_refund_reviewer BEFORE UPDATE ON accounting_journals
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_refund_journal_reviewer();

CREATE FUNCTION guard_completed_refund_original_source() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE source_journal_id UUID;
BEGIN
    IF TG_TABLE_NAME='fee_payments' THEN
        IF OLD.status<>'POSTED' OR NEW.status<>'REVERSED' OR OLD.gateway_capture_id IS NULL THEN RETURN NEW; END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
        IF current_setting('transaction_isolation') IS DISTINCT FROM 'read committed' THEN
            RAISE EXCEPTION 'gateway refund source corrections require fresh read committed evidence' USING ERRCODE='40001';
        END IF;
        IF EXISTS(SELECT 1 FROM fee_gateway_refund_postings completion
            WHERE completion.fee_payment_id=OLD.id AND completion.organisation_id=OLD.organisation_id AND completion.school_id=OLD.school_id) THEN
            RAISE EXCEPTION 'a completed gateway refund preserves its original payment credit; use controlled accounting adjustments';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_TABLE_NAME='accounting_journals' THEN
        IF OLD.status<>'POSTED' OR NEW.status<>'REVERSED' THEN RETURN NEW; END IF;
        source_journal_id:=OLD.id;
    ELSE
        IF NEW.status NOT IN ('PENDING','APPROVED') THEN RETURN NEW; END IF;
        source_journal_id:=NEW.accounting_journal_id;
    END IF;
    -- Only original common PAYMENT journals containing a gateway receipt use
    -- this additive anchor. Refund counter-journals and unrelated accounting
    -- transitions keep their existing controls/lifecycle unchanged.
    IF NOT EXISTS(SELECT 1 FROM fee_accounting_posting_batches original_batch
        JOIN fee_accounting_posting_items original_item ON original_item.posting_batch_id=original_batch.id
            AND original_item.organisation_id=original_batch.organisation_id AND original_item.school_id=original_batch.school_id
        JOIN fee_payments payment ON payment.ledger_entry_id=original_item.fee_ledger_entry_id
            AND payment.organisation_id=original_item.organisation_id AND payment.school_id=original_item.school_id
        WHERE original_batch.accounting_journal_id=source_journal_id AND original_batch.event_type='PAYMENT'
            AND original_batch.organisation_id=NEW.organisation_id AND original_batch.school_id=NEW.school_id
            AND payment.gateway_capture_id IS NOT NULL) THEN RETURN NEW; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || NEW.school_id::text,0));
    IF current_setting('transaction_isolation') IS DISTINCT FROM 'read committed' THEN
        RAISE EXCEPTION 'gateway refund journal source corrections require fresh read committed evidence' USING ERRCODE='40001';
    END IF;
    IF EXISTS(SELECT 1 FROM fee_gateway_refund_postings completion
        JOIN fee_accounting_posting_items original_item ON original_item.id=completion.original_payment_posting_item_id
            AND original_item.organisation_id=completion.organisation_id AND original_item.school_id=completion.school_id
        JOIN fee_accounting_posting_batches original_batch ON original_batch.id=original_item.posting_batch_id
            AND original_batch.organisation_id=original_item.organisation_id AND original_batch.school_id=original_item.school_id
        WHERE completion.organisation_id=NEW.organisation_id AND completion.school_id=NEW.school_id
            AND original_batch.accounting_journal_id=source_journal_id) THEN
        RAISE EXCEPTION 'a completed gateway refund anchors its original common payment journal; correct by reviewed adjustment, not source reversal';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_payments_completed_refund_source_guard BEFORE UPDATE ON fee_payments
    FOR EACH ROW EXECUTE FUNCTION guard_completed_refund_original_source();
CREATE TRIGGER accounting_journals_completed_refund_source_guard BEFORE UPDATE ON accounting_journals
    FOR EACH ROW EXECUTE FUNCTION guard_completed_refund_original_source();
CREATE TRIGGER accounting_reversals_completed_refund_source_guard BEFORE INSERT OR UPDATE ON accounting_reversal_requests
    FOR EACH ROW EXECUTE FUNCTION guard_completed_refund_original_source();

COMMENT ON TABLE fee_gateway_refund_postings IS 'Append-only reviewed completion of one verified gateway refund; restores exact original-account dues with one immutable REFUND debit and coupled common accounting journal. V075 source timestamps/closed-day evidence and V073 approved allocations remain unchanged. Accounting correction may reprepare this SAME debit, never create a second refund.';
COMMENT ON FUNCTION fee_charge_net_paid(UUID,UUID,UUID,DATE) IS 'Security-invoker exact scoped paid allocations minus only financially completed gateway refund allocations. Pending/HOLD has no effect. Historical reads retain original received/reversal and completion date cutoffs; values are not clamped to hide defects.';

CREATE FUNCTION require_gateway_refund_posting_actor(organisation_value UUID,school_value UUID,membership_value UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM require_gateway_refund_actor(organisation_value,school_value,membership_value);
    PERFORM 1 FROM memberships member
    JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
    JOIN membership_roles assignment ON assignment.membership_id=member.id
    JOIN roles role ON role.id=assignment.role_id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
    JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code='accounting.prepare'
    WHERE member.id=membership_value AND member.user_id=current_app_user_id()
        AND member.organisation_id=organisation_value AND member.school_id=school_value AND member.status='ACTIVE'
        AND member.valid_from<=clock_timestamp() AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
    FOR SHARE OF member,actor,assignment;
    IF NOT FOUND THEN RAISE EXCEPTION 'reviewed refund posting requires live school-scoped accounting preparation authority'; END IF;
END; $$;

CREATE FUNCTION guard_gateway_refund_posting() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    success fee_gateway_refund_successes%ROWTYPE;
    payment fee_payments%ROWTYPE;
    account learner_fee_accounts%ROWTYPE;
    original_item fee_accounting_posting_items%ROWTYPE;
    original_batch fee_accounting_posting_batches%ROWTYPE;
    original_journal accounting_journals%ROWTYPE;
    original_configuration fee_accounting_configurations%ROWTYPE;
BEGIN
    IF current_setting('transaction_isolation') IS DISTINCT FROM 'read committed' THEN
        RAISE EXCEPTION 'new reviewed refund posting requires fresh read committed evidence' USING ERRCODE='40001';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM require_gateway_refund_posting_actor(NEW.organisation_id,NEW.school_id,NEW.posted_by_membership_id);
    IF NEW.posted_by_user_id IS DISTINCT FROM current_app_user_id() THEN
        RAISE EXCEPTION 'reviewed refund posting requires its actual human actor';
    END IF;
    SELECT * INTO success FROM fee_gateway_refund_successes WHERE refund_request_id=NEW.refund_request_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF success.refund_request_id IS NULL THEN RAISE EXCEPTION 'reviewed refund posting requires the retained verified success HOLD'; END IF;
    PERFORM touch_gateway_refund_fence(success.capture_id,NEW.organisation_id,NEW.school_id);
    PERFORM touch_refund_account_reopen_fence(success.learner_fee_account_id,NEW.organisation_id,NEW.school_id);
    SELECT * INTO account FROM learner_fee_accounts WHERE id=success.learner_fee_account_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR UPDATE;
    PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || NEW.school_id::text,0));
    SELECT * INTO payment FROM fee_payments WHERE id=success.fee_payment_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    IF account.id IS NULL OR account.status<>'ACTIVE' OR account.currency_code<>'INR'
        OR payment.id IS NULL OR payment.status<>'POSTED' OR payment.payment_method<>'ONLINE_PROVIDER'
        OR payment.gateway_capture_id IS DISTINCT FROM success.capture_id
        OR payment.learner_fee_account_id IS DISTINCT FROM account.id
        OR NEW.fee_payment_id IS DISTINCT FROM success.fee_payment_id
        OR NEW.learner_fee_account_id IS DISTINCT FROM success.learner_fee_account_id
        OR NEW.verified_read_id IS DISTINCT FROM success.verified_read_id
        OR NEW.success_evidence_sha256 IS DISTINCT FROM success.evidence_sha256
        OR NEW.source_version IS DISTINCT FROM success.source_version
        OR NEW.amount IS DISTINCT FROM success.amount OR NEW.currency_code IS DISTINCT FROM success.currency_code
        OR NOT refund_account_reopening_source_ready(NEW.refund_request_id,NEW.organisation_id,NEW.school_id) THEN
        RAISE EXCEPTION 'reviewed refund posting must retain exact noncontradictory success and the ACTIVE original receipt account';
    END IF;
    NEW.posted_at:=clock_timestamp();
    IF NEW.posted_at<success.recognized_at OR NEW.posted_on<success.processed_on OR NEW.posted_on<payment.received_on
        OR NEW.posted_on>(NEW.posted_at AT TIME ZONE success.school_time_zone)::date
        OR EXISTS(SELECT 1 FROM fee_day_closes close_record WHERE close_record.organisation_id=NEW.organisation_id
            AND close_record.school_id=NEW.school_id AND close_record.status='APPROVED' AND close_record.business_date>=NEW.posted_on) THEN
        RAISE EXCEPTION 'reviewed refund posting requires an eligible open school date without changing original processing or closed-day evidence';
    END IF;
    SELECT * INTO original_item FROM fee_accounting_posting_items WHERE id=NEW.original_payment_posting_item_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    SELECT * INTO original_batch FROM fee_accounting_posting_batches WHERE id=original_item.posting_batch_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    SELECT * INTO original_journal FROM accounting_journals WHERE id=original_batch.accounting_journal_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    SELECT * INTO original_configuration FROM fee_accounting_configurations WHERE id=original_batch.configuration_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    IF original_item.id IS NULL OR original_item.status<>'ACTIVE' OR original_item.fee_ledger_entry_id IS DISTINCT FROM payment.ledger_entry_id
        OR original_batch.id IS NULL OR original_batch.event_type<>'PAYMENT' OR original_batch.disposition<>'JOURNAL'
        OR original_batch.business_date IS DISTINCT FROM payment.received_on
        OR original_journal.id IS NULL OR original_journal.status<>'POSTED' OR original_journal.source_module<>'FEES'
        OR original_journal.source_id IS DISTINCT FROM original_batch.id
        OR original_journal.posting_date IS DISTINCT FROM original_batch.business_date
        OR original_journal.financial_year_id IS DISTINCT FROM original_batch.financial_year_id
        OR original_journal.accounting_period_id IS DISTINCT FROM original_batch.accounting_period_id
        OR original_journal.reversal_of_journal_id IS NOT NULL
        OR original_configuration.id IS NULL OR original_configuration.status NOT IN ('APPROVED','SUPERSEDED')
        OR original_configuration.financial_year_id IS DISTINCT FROM original_batch.financial_year_id
        OR original_configuration.currency_code IS DISTINCT FROM NEW.currency_code
        OR EXISTS(SELECT 1 FROM accounting_reversal_requests reversal WHERE reversal.accounting_journal_id=original_journal.id
            AND reversal.organisation_id=NEW.organisation_id AND reversal.school_id=NEW.school_id AND reversal.status IN ('PENDING','APPROVED'))
        OR EXISTS(SELECT 1 FROM accounting_journals reversal WHERE reversal.reversal_of_journal_id=original_journal.id
            AND reversal.organisation_id=NEW.organisation_id AND reversal.school_id=NEW.school_id)
        OR (SELECT count(*) FROM fee_accounting_posting_items item WHERE item.posting_batch_id=original_batch.id
            AND item.organisation_id=NEW.organisation_id AND item.school_id=NEW.school_id AND item.status='ACTIVE')<>original_batch.source_count
        OR (SELECT COALESCE(sum(entry.amount),0) FROM fee_accounting_posting_items item
            JOIN fee_ledger_entries entry ON entry.id=item.fee_ledger_entry_id AND entry.organisation_id=item.organisation_id AND entry.school_id=item.school_id
            WHERE item.posting_batch_id=original_batch.id AND item.organisation_id=NEW.organisation_id AND item.school_id=NEW.school_id
                AND item.status='ACTIVE' AND entry.entry_type='PAYMENT' AND entry.direction='CREDIT')<>original_batch.source_amount
        OR original_journal.total_amount IS DISTINCT FROM original_batch.source_amount THEN
        RAISE EXCEPTION 'reviewed refund posting requires the complete unreversed original common payment journal and frozen mapping';
    END IF;
    IF (SELECT COALESCE(sum(amount),0) FROM fee_gateway_refund_allocations WHERE refund_request_id=NEW.refund_request_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id)<>NEW.amount THEN
        RAISE EXCEPTION 'reviewed refund posting must retain all exact approved original allocations';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_postings_guard BEFORE INSERT ON fee_gateway_refund_postings
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_refund_posting();

CREATE FUNCTION guard_gateway_refund_debit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE completion fee_gateway_refund_postings%ROWTYPE;
BEGIN
    SELECT * INTO completion FROM fee_gateway_refund_postings WHERE id=NEW.source_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF completion.id IS NULL OR NEW.id IS DISTINCT FROM completion.fee_ledger_entry_id
        OR NEW.learner_fee_account_id IS DISTINCT FROM completion.learner_fee_account_id
        OR NEW.entry_type<>'REFUND' OR NEW.direction<>'DEBIT' OR NEW.amount IS DISTINCT FROM completion.amount
        OR NEW.effective_on IS DISTINCT FROM completion.posted_on OR NEW.learner_fee_charge_id IS NOT NULL
        OR NEW.reverses_entry_id IS NOT NULL OR NEW.gateway_capture_id IS NOT NULL OR NEW.actor_kind<>'HUMAN'
        OR NEW.created_by_membership_id IS DISTINCT FROM completion.posted_by_membership_id
        OR current_app_user_id() IS DISTINCT FROM completion.posted_by_user_id THEN
        RAISE EXCEPTION 'gateway refund debit must exactly match its reviewed original-account completion';
    END IF;
    PERFORM require_gateway_refund_posting_actor(NEW.organisation_id,NEW.school_id,completion.posted_by_membership_id);
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_ledger_entries_gateway_refund_guard BEFORE INSERT ON fee_ledger_entries
    FOR EACH ROW WHEN(NEW.source_type='GATEWAY_REFUND_POSTING') EXECUTE FUNCTION guard_gateway_refund_debit();

CREATE FUNCTION check_gateway_refund_posting_coupling() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE completion_id UUID; completion fee_gateway_refund_postings%ROWTYPE; linked_count INTEGER;
    linked_journal_id UUID; linked_configuration_id UUID; incorrect_lines BOOLEAN;
BEGIN
    IF TG_TABLE_NAME='fee_gateway_refund_postings' THEN completion_id:=NEW.id;
    ELSIF TG_TABLE_NAME='fee_ledger_entries' THEN completion_id:=NEW.source_id;
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
        AND batch.business_date=completion.posted_on AND journal.source_module='FEES' AND journal.source_id=batch.id
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
CREATE CONSTRAINT TRIGGER fee_gateway_refund_postings_coupled AFTER INSERT ON fee_gateway_refund_postings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_gateway_refund_posting_coupling();
CREATE CONSTRAINT TRIGGER fee_gateway_refund_debit_coupled AFTER INSERT ON fee_ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.source_type='GATEWAY_REFUND_POSTING')
    EXECUTE FUNCTION check_gateway_refund_posting_coupling();
CREATE CONSTRAINT TRIGGER fee_gateway_refund_batch_coupled AFTER INSERT ON fee_accounting_posting_batches
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.event_type='REFUND')
    EXECUTE FUNCTION check_gateway_refund_posting_coupling();

CREATE TRIGGER fee_gateway_refund_postings_append_only BEFORE UPDATE OR DELETE ON fee_gateway_refund_postings
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
ALTER TABLE fee_gateway_refund_postings ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_gateway_refund_postings FORCE ROW LEVEL SECURITY;
CREATE POLICY fee_gateway_refund_postings_select ON fee_gateway_refund_postings FOR SELECT
    USING(organisation_id=current_app_organisation_id());
CREATE POLICY fee_gateway_refund_postings_insert ON fee_gateway_refund_postings FOR INSERT
    WITH CHECK(organisation_id=current_app_organisation_id() AND current_app_user_id() IS NOT NULL);

-- Completed refunds remain immutable HOLD evidence but can no longer justify
-- another reopening. Preserve the old witness rules without mutating the HOLD.
CREATE OR REPLACE FUNCTION refund_account_reopening_source_ready(refund_value UUID,organisation_value UUID,school_value UUID)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS(
        SELECT 1 FROM fee_gateway_refund_successes success
        JOIN fee_gateway_refund_decisions approval ON approval.id=success.approval_decision_id
            AND approval.refund_request_id=success.refund_request_id
            AND approval.organisation_id=success.organisation_id AND approval.school_id=success.school_id
        WHERE success.refund_request_id=refund_value AND success.organisation_id=organisation_value AND success.school_id=school_value
            AND success.status='HOLD' AND success.source_version=1 AND approval.decision='APPROVE'
            AND NOT EXISTS(SELECT 1 FROM fee_gateway_refund_postings completion WHERE completion.refund_request_id=success.refund_request_id
                AND completion.organisation_id=success.organisation_id AND completion.school_id=success.school_id)
            AND NOT EXISTS(SELECT 1 FROM fee_gateway_refund_reads witness
                WHERE witness.refund_request_id=success.refund_request_id
                    AND witness.organisation_id=success.organisation_id AND witness.school_id=success.school_id
                    AND ((witness.outcome='VERIFIED_SUCCESS' AND witness.evidence_sha256<>success.evidence_sha256)
                        OR (witness.outcome='REVIEW_REQUIRED' AND witness.refund_status='SUCCESS')))
    );
$$;

-- Preserve the V066 lifecycle/release rules. Only the paid amount becomes net
-- of completed refunds; a restored non-partial installment can be paid in full.
CREATE OR REPLACE FUNCTION guard_gateway_allocation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE observed fee_gateway_checkout_observations%ROWTYPE; outstanding NUMERIC; partial_allowed BOOLEAN;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status<>'ACTIVE' OR NEW.row_version<>0 OR NEW.updated_at<>NEW.created_at
           OR NOT EXISTS (SELECT 1 FROM fee_gateway_attempts attempt WHERE attempt.id=NEW.attempt_id AND attempt.status='RESERVED') THEN
            RAISE EXCEPTION 'new gateway allocations require a reserved intent';
        END IF;
        SELECT charge.allow_partial,charge.amount
            -COALESCE((SELECT sum(amount) FROM fee_ledger_entries WHERE learner_fee_charge_id=charge.id AND entry_type='CONCESSION'),0)
            -fee_charge_net_paid(NEW.organisation_id,NEW.school_id,charge.id)
          INTO partial_allowed,outstanding FROM learner_fee_charges charge WHERE charge.id=NEW.learner_fee_charge_id;
        IF partial_allowed=FALSE AND NEW.amount<>outstanding THEN RAISE EXCEPTION 'non-partial fee obligation must be reserved in full'; END IF;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['status','release_observation_id','release_reviewed_by_membership_id','release_reason','released_at','updated_at','row_version']) IS DISTINCT FROM
           (to_jsonb(OLD)-ARRAY['status','release_observation_id','release_reviewed_by_membership_id','release_reason','released_at','updated_at','row_version'])
           OR OLD.status<>'ACTIVE' OR NEW.status NOT IN ('CONSUMED','RELEASED') OR NEW.row_version<>OLD.row_version+1 OR NEW.updated_at<OLD.updated_at THEN
            RAISE EXCEPTION 'gateway allocations are immutable except supported one-use disposition';
        END IF;
        IF NEW.status='RELEASED' THEN
            SELECT * INTO observed FROM fee_gateway_checkout_observations WHERE id=NEW.release_observation_id;
            IF observed.checkout_status NOT IN ('CANCELLED','EXPIRED') OR observed.amount_paid<>0 OR NOT observed.discovery_complete
               OR observed.pending_payment_count<>0 OR observed.successful_payment_count<>0 OR observed.observed_at>NEW.released_at
               OR EXISTS (SELECT 1 FROM fee_gateway_captures capture WHERE capture.attempt_id=NEW.attempt_id)
               OR NOT EXISTS (SELECT 1 FROM memberships member JOIN membership_roles mr ON mr.membership_id=member.id
                 JOIN roles role ON role.id=mr.role_id JOIN role_capabilities rc ON rc.role_id=mr.role_id AND rc.capability_code='fees.approve'
                 WHERE member.id=NEW.release_reviewed_by_membership_id AND member.organisation_id=NEW.organisation_id AND member.school_id=NEW.school_id
                   AND member.status='ACTIVE' AND member.valid_from<=CURRENT_TIMESTAMP AND (member.valid_until IS NULL OR member.valid_until>CURRENT_TIMESTAMP)
                   AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)) THEN
                RAISE EXCEPTION 'reservation release requires reviewed complete provider-terminal evidence without captures';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION check_gateway_charge_availability() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE charge_id UUID; charge_amount NUMERIC; used_amount NUMERIC;
BEGIN
    charge_id:=NEW.learner_fee_charge_id;
    IF charge_id IS NULL THEN RETURN NULL; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    SELECT amount INTO charge_amount FROM learner_fee_charges WHERE id=charge_id;
    SELECT COALESCE((SELECT sum(amount) FROM fee_gateway_allocations WHERE learner_fee_charge_id=charge_id AND status='ACTIVE'),0)
        +fee_charge_net_paid(NEW.organisation_id,NEW.school_id,charge_id)
        +COALESCE((SELECT sum(amount) FROM fee_ledger_entries WHERE learner_fee_charge_id=charge_id AND entry_type='CONCESSION'),0)
        INTO used_amount;
    IF used_amount>charge_amount THEN RAISE EXCEPTION 'fee charge cannot be consumed by payments, concessions and active reservations more than once'; END IF;
    RETURN NULL;
END; $$;

-- V070 provenance, date, live-authority and exact-allocation controls are kept.
-- Both its aggregate and non-partial remainder now use the SAME net-paid reader.
CREATE OR REPLACE FUNCTION check_manual_collection_receipt_provenance() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE payment_id_value UUID; payment fee_payments%ROWTYPE; evidence fee_manual_collection_evidence%ROWTYPE;
    ledger fee_ledger_entries%ROWTYPE; original_close UUID; now_value TIMESTAMPTZ;
BEGIN
    IF TG_TABLE_NAME='fee_payments' THEN payment_id_value:=NEW.id;
    ELSIF TG_TABLE_NAME='fee_manual_collection_evidence' THEN payment_id_value:=NEW.fee_payment_id;
    ELSIF TG_TABLE_NAME='fee_payment_allocations' THEN payment_id_value:=NEW.fee_payment_id;
    ELSE payment_id_value:=NEW.source_id; END IF;
    SELECT * INTO payment FROM fee_payments WHERE id=payment_id_value
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF payment.id IS NULL THEN RAISE EXCEPTION 'manual payment credit must retain its exact common receipt'; END IF;
    IF payment.gateway_capture_id IS NOT NULL OR payment.payment_method NOT IN ('CASH','BANK_TRANSFER','SCHOOL_UPI') THEN RETURN NULL; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(payment.school_id::text || ':finance',0));
    PERFORM require_manual_collection_actor(payment.organisation_id,payment.school_id,payment.received_by_membership_id);
    SELECT * INTO evidence FROM fee_manual_collection_evidence WHERE fee_payment_id=payment.id
        AND organisation_id=payment.organisation_id AND school_id=payment.school_id;
    SELECT * INTO ledger FROM fee_ledger_entries WHERE id=payment.ledger_entry_id
        AND organisation_id=payment.organisation_id AND school_id=payment.school_id;
    now_value:=clock_timestamp();
    SELECT id INTO original_close FROM fee_day_closes WHERE organisation_id=payment.organisation_id AND school_id=payment.school_id
        AND status='APPROVED' AND business_date>=evidence.original_received_on ORDER BY business_date DESC,id LIMIT 1;
    IF evidence.fee_payment_id IS NULL OR ledger.id IS NULL OR payment.actor_kind<>'HUMAN' OR payment.status<>'POSTED'
        OR evidence.verified_by_membership_id IS DISTINCT FROM payment.received_by_membership_id
        OR evidence.posting_on IS DISTINCT FROM payment.received_on OR evidence.verified_at>now_value
        OR evidence.closed_day_close_id IS DISTINCT FROM original_close
        OR evidence.posting_on>(now_value AT TIME ZONE evidence.school_time_zone)::date
        OR EXISTS(SELECT 1 FROM fee_day_closes close_record WHERE close_record.organisation_id=payment.organisation_id
            AND close_record.school_id=payment.school_id AND close_record.status='APPROVED' AND close_record.business_date>=evidence.posting_on)
        OR ledger.actor_kind<>'HUMAN' OR ledger.gateway_capture_id IS NOT NULL
        OR ledger.created_by_membership_id IS DISTINCT FROM payment.received_by_membership_id
        OR ledger.learner_fee_account_id IS DISTINCT FROM payment.learner_fee_account_id
        OR ledger.entry_type<>'PAYMENT' OR ledger.direction<>'CREDIT' OR ledger.amount IS DISTINCT FROM payment.amount
        OR ledger.effective_on IS DISTINCT FROM payment.received_on OR ledger.source_type<>'FEE_PAYMENT'
        OR ledger.source_id IS DISTINCT FROM payment.id OR ledger.learner_fee_charge_id IS NOT NULL OR ledger.reverses_entry_id IS NOT NULL
        OR EXISTS(SELECT 1 FROM fee_payments other WHERE other.ledger_entry_id=ledger.id
            AND other.organisation_id=payment.organisation_id AND other.school_id=payment.school_id AND other.id<>payment.id)
        OR EXISTS(SELECT 1 FROM fee_ledger_entries other WHERE other.source_type='FEE_PAYMENT' AND other.source_id=payment.id
            AND other.organisation_id=payment.organisation_id AND other.school_id=payment.school_id AND other.id<>ledger.id)
        OR (SELECT COALESCE(sum(amount),0) FROM fee_payment_allocations WHERE fee_payment_id=payment.id
            AND organisation_id=payment.organisation_id AND school_id=payment.school_id)<>payment.amount
        OR EXISTS(SELECT 1 FROM fee_payment_allocations allocation
            JOIN learner_fee_charges charge ON charge.id=allocation.learner_fee_charge_id
                AND charge.organisation_id=allocation.organisation_id AND charge.school_id=allocation.school_id
            WHERE allocation.fee_payment_id=payment.id AND allocation.organisation_id=payment.organisation_id
                AND allocation.school_id=payment.school_id AND (
                    charge.learner_fee_account_id<>payment.learner_fee_account_id
                    OR fee_charge_net_paid(payment.organisation_id,payment.school_id,charge.id)
                        +(SELECT COALESCE(sum(reservation.amount),0) FROM fee_gateway_allocations reservation
                            WHERE reservation.learner_fee_charge_id=charge.id AND reservation.organisation_id=payment.organisation_id
                                AND reservation.school_id=payment.school_id AND reservation.status='ACTIVE')
                        >charge.amount-(SELECT COALESCE(sum(concession.amount),0) FROM fee_ledger_entries concession
                            WHERE concession.learner_fee_charge_id=charge.id AND concession.organisation_id=payment.organisation_id
                                AND concession.school_id=payment.school_id AND concession.entry_type='CONCESSION' AND concession.direction='CREDIT')
                    OR (NOT charge.allow_partial AND allocation.amount IS DISTINCT FROM
                        charge.amount-(SELECT COALESCE(sum(concession.amount),0) FROM fee_ledger_entries concession
                            WHERE concession.learner_fee_charge_id=charge.id AND concession.organisation_id=payment.organisation_id
                                AND concession.school_id=payment.school_id AND concession.entry_type='CONCESSION' AND concession.direction='CREDIT')
                        -(fee_charge_net_paid(payment.organisation_id,payment.school_id,charge.id)-allocation.amount))
                )) THEN
        RAISE EXCEPTION 'manual receipt, truthful received-funds evidence, existing ledger and exact scoped allocations must agree';
    END IF;
    RETURN NULL;
END; $$;

-- Immutable school settlement preparation, consumed economic sources and common
-- journals. This never creates a fee payment, receipt, refund or learner ledger entry.
ALTER TABLE accounting_journals DROP CONSTRAINT accounting_journals_source_valid;
ALTER TABLE accounting_journals ADD CONSTRAINT accounting_journals_source_valid CHECK (
    source_module IN ('MANUAL','FEES','PAYROLL','PAYROLL_ADVANCE','EXPENSE','OPENING','REVERSAL','GATEWAY_SETTLEMENT'));

CREATE TABLE fee_gateway_settlement_roots (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    bank_evidence_id UUID NOT NULL, provider_config_id UUID NOT NULL,
    provider_code VARCHAR(40) NOT NULL, provider_environment VARCHAR(10) NOT NULL CHECK(provider_environment IN('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL, settlement_reference VARCHAR(100) NOT NULL,
    request_id UUID NOT NULL, source_summary_read_id UUID NOT NULL,
    source_summary_row_index INTEGER NOT NULL CHECK(source_summary_row_index BETWEEN 0 AND 9),
    created_by_user_id UUID NOT NULL REFERENCES user_accounts(id), created_by_membership_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(created_at)),
    UNIQUE(id,organisation_id,school_id),
    FOREIGN KEY(bank_evidence_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_bank_evidence(id,organisation_id,school_id),
    FOREIGN KEY(provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    FOREIGN KEY(request_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_requests(id,organisation_id,school_id),
    FOREIGN KEY(source_summary_read_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_reads(id,organisation_id,school_id),
    FOREIGN KEY(created_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CHECK(settlement_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'),
    CHECK(merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$')
);
CREATE INDEX gateway_settlement_roots_bank ON fee_gateway_settlement_roots(organisation_id,school_id,bank_evidence_id);
CREATE INDEX gateway_settlement_roots_statement ON fee_gateway_settlement_roots(organisation_id,school_id,provider_config_id,provider_environment,settlement_reference);

CREATE TABLE fee_gateway_settlement_preparations (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, root_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK(revision BETWEEN 1 AND 1000), previous_preparation_id UUID, previous_journal_id UUID,
    financial_year_id UUID NOT NULL, accounting_period_id UUID NOT NULL,
    posting_on DATE NOT NULL CHECK(posting_on BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    bank_amount NUMERIC NOT NULL CHECK(bank_amount>0 AND bank_amount<=100000000 AND bank_amount=trunc(bank_amount,2)),
    payment_total NUMERIC NOT NULL CHECK(payment_total>=0 AND payment_total<=1000000000000 AND payment_total=trunc(payment_total,2)),
    refund_total NUMERIC NOT NULL CHECK(refund_total>=0 AND refund_total<=1000000000000 AND refund_total=trunc(refund_total,2)),
    component_debit_total NUMERIC NOT NULL CHECK(component_debit_total>=0 AND component_debit_total<=1000000000000 AND component_debit_total=trunc(component_debit_total,2)),
    component_credit_total NUMERIC NOT NULL CHECK(component_credit_total>=0 AND component_credit_total<=1000000000000 AND component_credit_total=trunc(component_credit_total,2)),
    currency_code CHAR(3) NOT NULL CHECK(currency_code='INR'), snapshot_version SMALLINT NOT NULL CHECK(snapshot_version=1),
    evidence_snapshot JSONB NOT NULL CHECK(jsonb_typeof(evidence_snapshot)='object' AND octet_length(evidence_snapshot::text)<=8388608),
    evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[a-f0-9]{64}$'),
    journal_id UUID NOT NULL UNIQUE,
    prepared_by_user_id UUID NOT NULL REFERENCES user_accounts(id), prepared_by_membership_id UUID NOT NULL,
    client_request_id UUID NOT NULL, request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
    preparation_reason VARCHAR(240) NOT NULL CHECK(length(btrim(preparation_reason)) BETWEEN 3 AND 240 AND preparation_reason !~ '[[:cntrl:]]'),
    prepared_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(prepared_at)),
    UNIQUE(id,organisation_id,school_id), UNIQUE(root_id,revision), UNIQUE(previous_preparation_id),
    UNIQUE(school_id,prepared_by_user_id,client_request_id),
    CHECK((revision=1 AND previous_preparation_id IS NULL AND previous_journal_id IS NULL)
        OR (revision>1 AND previous_preparation_id IS NOT NULL AND previous_journal_id IS NOT NULL AND previous_preparation_id<>id)),
    CHECK(payment_total-refund_total=bank_amount+component_debit_total-component_credit_total),
    FOREIGN KEY(root_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_roots(id,organisation_id,school_id),
    FOREIGN KEY(previous_preparation_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_preparations(id,organisation_id,school_id),
    FOREIGN KEY(previous_journal_id,organisation_id,school_id) REFERENCES accounting_journals(id,organisation_id,school_id),
    FOREIGN KEY(financial_year_id,organisation_id,school_id) REFERENCES accounting_financial_years(id,organisation_id,school_id),
    FOREIGN KEY(accounting_period_id,organisation_id,school_id) REFERENCES accounting_periods(id,organisation_id,school_id),
    FOREIGN KEY(journal_id,organisation_id,school_id) REFERENCES accounting_journals(id,organisation_id,school_id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(prepared_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id)
);
CREATE INDEX gateway_settlement_preparations_history ON fee_gateway_settlement_preparations(organisation_id,school_id,root_id,revision DESC);

CREATE TABLE fee_gateway_settlement_sources (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, root_id UUID NOT NULL,
    source_kind VARCHAR(10) NOT NULL CHECK(source_kind IN('PAYMENT','REFUND')),
    payment_id UUID NOT NULL, refund_posting_id UUID,
    fee_ledger_entry_id UUID NOT NULL, posting_item_id UUID NOT NULL, posting_batch_id UUID NOT NULL,
    source_journal_id UUID NOT NULL, configuration_id UUID NOT NULL, clearing_account_id UUID NOT NULL,
    amount NUMERIC NOT NULL CHECK(amount>0 AND amount<=100000000 AND amount=trunc(amount,2)),
    currency_code CHAR(3) NOT NULL CHECK(currency_code='INR'),
    source_sha256 CHAR(64) NOT NULL CHECK(source_sha256 ~ '^[a-f0-9]{64}$'),
    batch_sha256 CHAR(64) NOT NULL CHECK(batch_sha256 ~ '^[a-f0-9]{64}$'),
    UNIQUE(id,organisation_id,school_id),
    CHECK((source_kind='PAYMENT' AND refund_posting_id IS NULL) OR(source_kind='REFUND' AND refund_posting_id IS NOT NULL)),
    FOREIGN KEY(root_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_roots(id,organisation_id,school_id),
    FOREIGN KEY(payment_id,organisation_id,school_id) REFERENCES fee_payments(id,organisation_id,school_id),
    FOREIGN KEY(refund_posting_id,organisation_id,school_id) REFERENCES fee_gateway_refund_postings(id,organisation_id,school_id),
    FOREIGN KEY(fee_ledger_entry_id,organisation_id,school_id) REFERENCES fee_ledger_entries(id,organisation_id,school_id),
    FOREIGN KEY(posting_item_id,organisation_id,school_id) REFERENCES fee_accounting_posting_items(id,organisation_id,school_id),
    FOREIGN KEY(posting_batch_id,organisation_id,school_id) REFERENCES fee_accounting_posting_batches(id,organisation_id,school_id),
    FOREIGN KEY(source_journal_id,organisation_id,school_id) REFERENCES accounting_journals(id,organisation_id,school_id),
    FOREIGN KEY(configuration_id,organisation_id,school_id) REFERENCES fee_accounting_configurations(id,organisation_id,school_id),
    FOREIGN KEY(clearing_account_id,organisation_id,school_id) REFERENCES accounting_accounts(id,organisation_id,school_id)
);
CREATE UNIQUE INDEX gateway_settlement_root_payment ON fee_gateway_settlement_sources(root_id,payment_id) WHERE source_kind='PAYMENT';
CREATE UNIQUE INDEX gateway_settlement_root_refund ON fee_gateway_settlement_sources(root_id,refund_posting_id) WHERE source_kind='REFUND';
CREATE INDEX gateway_settlement_economic_payment ON fee_gateway_settlement_sources(organisation_id,school_id,payment_id) WHERE source_kind='PAYMENT';
CREATE INDEX gateway_settlement_economic_refund ON fee_gateway_settlement_sources(organisation_id,school_id,refund_posting_id) WHERE source_kind='REFUND';
CREATE INDEX gateway_settlement_source_journal ON fee_gateway_settlement_sources(organisation_id,school_id,source_journal_id);
CREATE INDEX gateway_settlement_sources_root ON fee_gateway_settlement_sources(root_id,id);

CREATE TABLE fee_gateway_settlement_source_rows (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, preparation_id UUID NOT NULL,
    source_read_id UUID NOT NULL, row_index INTEGER NOT NULL CHECK(row_index BETWEEN 0 AND 9), source_id UUID NOT NULL,
    association_reason VARCHAR(240) CHECK(association_reason IS NULL OR(length(btrim(association_reason)) BETWEEN 3 AND 240 AND association_reason !~ '[[:cntrl:]]')),
    UNIQUE(id,organisation_id,school_id), UNIQUE(preparation_id,source_read_id,row_index), UNIQUE(preparation_id,source_id),
    FOREIGN KEY(preparation_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_preparations(id,organisation_id,school_id),
    FOREIGN KEY(source_read_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_reads(id,organisation_id,school_id),
    FOREIGN KEY(source_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_sources(id,organisation_id,school_id)
);

CREATE TABLE fee_gateway_settlement_components (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, preparation_id UUID NOT NULL,
    component_key VARCHAR(30) NOT NULL, component_kind VARCHAR(30) NOT NULL,
    component_basis VARCHAR(30) NOT NULL CHECK(component_basis IN('SUMMARY_REPORTED','EVENTS_REPORTED','REVIEWED_DOCUMENT')),
    direction VARCHAR(10) NOT NULL CHECK(direction IN('DEBIT','CREDIT')),
    account_id UUID, amount NUMERIC NOT NULL CHECK(amount>=0 AND amount<=100000000 AND amount=trunc(amount,2)),
    currency_code CHAR(3) NOT NULL CHECK(currency_code='INR'), evidence_reference VARCHAR(120),
    reason VARCHAR(240) NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 240 AND reason !~ '[[:cntrl:]]'),
    raw_field_facts JSONB NOT NULL CHECK(jsonb_typeof(raw_field_facts)='object' AND octet_length(raw_field_facts::text)<=16384),
    UNIQUE(id,organisation_id,school_id), UNIQUE(preparation_id,component_key),
    CHECK(component_key=component_kind AND component_key IN('SERVICE_CHARGE','SERVICE_TAX','ADJUSTMENT','SETTLEMENT_CHARGE','SETTLEMENT_TAX','SPLIT_CHARGE','SPLIT_TAX','VENDOR_COMMISSION')),
    CHECK((amount=0 AND account_id IS NULL) OR(amount>0 AND account_id IS NOT NULL AND evidence_reference IS NOT NULL AND length(btrim(evidence_reference)) BETWEEN 1 AND 120)),
    CHECK(evidence_reference IS NULL OR evidence_reference !~ '[[:cntrl:]]'),
    CHECK(raw_field_facts ? 'mode' AND jsonb_typeof(raw_field_facts->'mode')='string' AND raw_field_facts->>'mode' IN('REPORTED','ROUNDING_REVIEW','CONFIRMED_ZERO','EXTERNAL_REVIEW')),
    FOREIGN KEY(preparation_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_preparations(id,organisation_id,school_id),
    FOREIGN KEY(account_id,organisation_id,school_id) REFERENCES accounting_accounts(id,organisation_id,school_id)
);

CREATE TABLE fee_gateway_settlement_abandonments (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, root_id UUID NOT NULL UNIQUE,
    abandoned_by_user_id UUID NOT NULL REFERENCES user_accounts(id), abandoned_by_membership_id UUID NOT NULL,
    client_request_id UUID NOT NULL, request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
    reason VARCHAR(240) NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 240 AND reason !~ '[[:cntrl:]]'),
    abandoned_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(abandoned_at)),
    UNIQUE(id,organisation_id,school_id), UNIQUE(school_id,abandoned_by_user_id,client_request_id),
    FOREIGN KEY(root_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_roots(id,organisation_id,school_id),
    FOREIGN KEY(abandoned_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id)
);

CREATE FUNCTION require_gateway_settlement_prepare_actor(organisation_value UUID,school_value UUID,membership_value UUID,user_value UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM require_gateway_bank_evidence_actor(organisation_value,school_value,membership_value,user_value,false);
    -- Finance and accounting may use different owned memberships of the SAME
    -- actual user. Never equate membership inequality with independent approval.
    PERFORM 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
        JOIN membership_roles assignment ON assignment.membership_id=member.id
        JOIN roles role ON role.id=assignment.role_id AND(role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
        JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code='accounting.prepare'
        WHERE member.user_id=user_value AND member.organisation_id=organisation_value AND member.school_id=school_value
            AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
            AND(member.valid_until IS NULL OR member.valid_until>clock_timestamp())
        FOR SHARE OF member,actor,assignment;
    IF NOT FOUND THEN RAISE EXCEPTION 'settlement preparation requires live accounting prepare authority'; END IF;
END; $$;

CREATE FUNCTION gateway_settlement_finance_fences(school_value UUID) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off'
        OR current_app_user_id() IS NULL THEN RAISE EXCEPTION 'settlement mutation requires a live human writable read committed transaction' USING ERRCODE='40001'; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(school_value::text || ':finance',0));
    PERFORM pg_advisory_xact_lock(hashtextextended('finance:school:' || school_value::text,0));
    PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || school_value::text,0));
END; $$;

CREATE FUNCTION guard_gateway_settlement_root() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE bank fee_gateway_settlement_bank_evidence%ROWTYPE; request fee_gateway_settlement_requests%ROWTYPE;
BEGIN
    PERFORM gateway_settlement_finance_fences(NEW.school_id);
    PERFORM require_gateway_settlement_prepare_actor(NEW.organisation_id,NEW.school_id,NEW.created_by_membership_id,NEW.created_by_user_id);
    SELECT * INTO bank FROM fee_gateway_settlement_bank_evidence WHERE id=NEW.bank_evidence_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO request FROM fee_gateway_settlement_requests WHERE id=NEW.request_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF bank.id IS NULL OR request.id IS NULL OR bank.request_id<>request.id
        OR bank.source_summary_read_id IS DISTINCT FROM NEW.source_summary_read_id OR bank.source_summary_row_index<>NEW.source_summary_row_index
        OR bank.settlement_reference IS DISTINCT FROM NEW.settlement_reference
        OR request.payment_provider_config_id IS DISTINCT FROM NEW.provider_config_id OR request.provider_code IS DISTINCT FROM NEW.provider_code
        OR request.provider_environment IS DISTINCT FROM NEW.provider_environment OR request.merchant_account_reference IS DISTINCT FROM NEW.merchant_account_reference
        OR request.source_summary_read_id IS DISTINCT FROM NEW.source_summary_read_id OR request.settlement_reference IS DISTINCT FROM NEW.settlement_reference
        OR EXISTS(SELECT 1 FROM fee_gateway_settlement_bank_evidence_rejections rejection WHERE rejection.bank_evidence_id=bank.id
            AND rejection.organisation_id=NEW.organisation_id AND rejection.school_id=NEW.school_id) THEN
        RAISE EXCEPTION 'settlement root requires exact unrejected retained bank and statement evidence';
    END IF;
    PERFORM require_gateway_bank_evidence_report(NEW.organisation_id,NEW.school_id,NEW.request_id,NEW.source_summary_read_id,
        NEW.source_summary_row_index,NEW.settlement_reference,request.school_time_zone);
    IF EXISTS(SELECT 1 FROM fee_gateway_settlement_roots other WHERE other.organisation_id=NEW.organisation_id AND other.school_id=NEW.school_id
        AND(other.bank_evidence_id=NEW.bank_evidence_id OR(other.provider_config_id=NEW.provider_config_id AND other.provider_code=NEW.provider_code
            AND other.provider_environment=NEW.provider_environment AND other.merchant_account_reference=NEW.merchant_account_reference AND other.settlement_reference=NEW.settlement_reference))
        AND NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_abandonments abandoned WHERE abandoned.root_id=other.id)) THEN
        RAISE EXCEPTION 'bank evidence or exact provider statement already belongs to a retained settlement root' USING ERRCODE='23514',CONSTRAINT='gateway_settlement_bank_consumed';
    END IF;
    NEW.created_at:=clock_timestamp(); RETURN NEW;
END; $$;
CREATE TRIGGER gateway_settlement_root_insert_guard BEFORE INSERT ON fee_gateway_settlement_roots FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_root();

-- Accounting-owned source checks. This helper intentionally takes NO finance
-- fence or financial row locks: journal decisions already own accounting first.
-- Source mutations below must take that same accounting fence before admission.
CREATE FUNCTION assert_gateway_settlement_source(source_value UUID,live_value BOOLEAN DEFAULT true) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE source fee_gateway_settlement_sources%ROWTYPE; root fee_gateway_settlement_roots%ROWTYPE;
    payment fee_payments%ROWTYPE; capture fee_gateway_captures%ROWTYPE; entry fee_ledger_entries%ROWTYPE;
    item fee_accounting_posting_items%ROWTYPE; batch fee_accounting_posting_batches%ROWTYPE;
    journal accounting_journals%ROWTYPE; clearing accounting_accounts%ROWTYPE; completion fee_gateway_refund_postings%ROWTYPE;
BEGIN
    SELECT * INTO source FROM fee_gateway_settlement_sources WHERE id=source_value AND organisation_id=current_app_organisation_id();
    SELECT * INTO root FROM fee_gateway_settlement_roots WHERE id=source.root_id AND organisation_id=source.organisation_id AND school_id=source.school_id;
    SELECT * INTO payment FROM fee_payments WHERE id=source.payment_id AND organisation_id=source.organisation_id AND school_id=source.school_id;
    SELECT * INTO capture FROM fee_gateway_captures WHERE id=payment.gateway_capture_id AND organisation_id=source.organisation_id AND school_id=source.school_id;
    SELECT * INTO entry FROM fee_ledger_entries WHERE id=source.fee_ledger_entry_id AND organisation_id=source.organisation_id AND school_id=source.school_id;
    SELECT * INTO item FROM fee_accounting_posting_items WHERE id=source.posting_item_id AND organisation_id=source.organisation_id AND school_id=source.school_id;
    SELECT * INTO batch FROM fee_accounting_posting_batches WHERE id=source.posting_batch_id AND organisation_id=source.organisation_id AND school_id=source.school_id;
    SELECT * INTO journal FROM accounting_journals WHERE id=source.source_journal_id AND organisation_id=source.organisation_id AND school_id=source.school_id;
    SELECT * INTO clearing FROM accounting_accounts WHERE id=source.clearing_account_id AND organisation_id=source.organisation_id AND school_id=source.school_id;
    IF source.id IS NULL OR root.id IS NULL OR payment.id IS NULL OR capture.id IS NULL OR entry.id IS NULL OR item.id IS NULL OR batch.id IS NULL OR journal.id IS NULL
        OR payment.status<>'POSTED' OR payment.payment_method<>'ONLINE_PROVIDER' OR payment.receipt_number IS NULL OR length(btrim(payment.receipt_number))=0
        OR capture.payment_provider_config_id IS DISTINCT FROM root.provider_config_id OR capture.provider_code IS DISTINCT FROM root.provider_code
        OR capture.provider_environment IS DISTINCT FROM root.provider_environment OR capture.merchant_account_reference IS DISTINCT FROM root.merchant_account_reference
        OR capture.amount IS DISTINCT FROM payment.amount OR capture.currency_code IS DISTINCT FROM source.currency_code
        OR entry.amount IS DISTINCT FROM source.amount OR entry.source_id IS NULL OR entry.learner_fee_account_id<>payment.learner_fee_account_id
        OR entry.learner_fee_charge_id IS NOT NULL OR entry.reverses_entry_id IS NOT NULL
        OR NOT EXISTS(SELECT 1 FROM learner_fee_accounts account WHERE account.id=payment.learner_fee_account_id
            AND account.organisation_id=source.organisation_id AND account.school_id=source.school_id AND account.currency_code=source.currency_code)
        OR NOT EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions resolution WHERE resolution.capture_id=capture.id
            AND resolution.organisation_id=source.organisation_id AND resolution.school_id=source.school_id AND resolution.resolution_type='POSTED'
            AND resolution.fee_payment_id=payment.id AND resolution.open_date=payment.received_on)
        OR item.fee_ledger_entry_id<>entry.id OR item.posting_batch_id<>batch.id OR item.status<>'ACTIVE' OR item.source_sha256<>source.source_sha256
        OR batch.configuration_id<>source.configuration_id OR batch.evidence_sha256<>source.batch_sha256 OR batch.accounting_journal_id<>journal.id
        OR batch.disposition<>'JOURNAL' OR batch.event_type<>source.source_kind OR batch.source_count NOT BETWEEN 1 AND 200
        OR journal.source_module<>'FEES' OR journal.source_id IS DISTINCT FROM batch.id OR journal.status<>'POSTED'
        OR journal.reversal_of_journal_id IS NOT NULL OR journal.financial_year_id<>batch.financial_year_id
        OR journal.accounting_period_id<>batch.accounting_period_id OR journal.posting_date<>batch.business_date OR journal.total_amount<>batch.source_amount
        OR clearing.id IS NULL OR clearing.account_type<>'ASSET' OR clearing.category<>'OTHER'
        OR(live_value AND(clearing.status<>'ACTIVE' OR NOT clearing.allow_direct_posting))
        OR NOT EXISTS(SELECT 1 FROM fee_accounting_tender_mappings tender WHERE tender.configuration_id=source.configuration_id
            AND tender.organisation_id=source.organisation_id AND tender.school_id=source.school_id AND tender.payment_method='ONLINE_PROVIDER'
            AND tender.deposit_account_id=source.clearing_account_id)
        OR NOT EXISTS(SELECT 1 FROM fee_accounting_configurations config JOIN accounting_financial_years year ON year.id=config.financial_year_id
            AND year.organisation_id=config.organisation_id AND year.school_id=config.school_id
            JOIN accounting_financial_years target_year ON target_year.id=batch.financial_year_id
                AND target_year.organisation_id=config.organisation_id AND target_year.school_id=config.school_id
            WHERE config.id=source.configuration_id AND config.organisation_id=source.organisation_id AND config.school_id=source.school_id
            AND config.status IN('APPROVED','SUPERSEDED') AND config.effective_from<=batch.business_date
            AND config.accounting_basis_snapshot=year.accounting_basis AND config.currency_code=source.currency_code AND year.currency_code=source.currency_code
            AND target_year.accounting_basis=config.accounting_basis_snapshot AND target_year.currency_code=config.currency_code
            AND((source.source_kind='PAYMENT' AND config.financial_year_id=batch.financial_year_id)
                OR(source.source_kind='REFUND' AND EXISTS(SELECT 1 FROM fee_gateway_refund_postings original_refund
                    JOIN fee_accounting_posting_items original_item ON original_item.id=original_refund.original_payment_posting_item_id
                        AND original_item.organisation_id=original_refund.organisation_id AND original_item.school_id=original_refund.school_id
                    JOIN fee_accounting_posting_batches original_batch ON original_batch.id=original_item.posting_batch_id
                        AND original_batch.organisation_id=original_item.organisation_id AND original_batch.school_id=original_item.school_id
                    WHERE original_refund.id=source.refund_posting_id AND original_refund.organisation_id=source.organisation_id
                        AND original_refund.school_id=source.school_id AND original_batch.event_type='PAYMENT'
                        AND original_batch.configuration_id=config.id AND original_batch.financial_year_id=config.financial_year_id))))
        OR EXISTS(SELECT 1 FROM accounting_journal_lines line WHERE line.accounting_journal_id=journal.id
            AND line.organisation_id=source.organisation_id AND line.school_id=source.school_id AND line.account_id=source.clearing_account_id
            AND(line.vendor_id IS NOT NULL OR line.learner_fee_account_id IS NOT NULL))
        OR EXISTS(SELECT 1 FROM accounting_reversal_requests reversal WHERE reversal.accounting_journal_id=journal.id
            AND reversal.organisation_id=source.organisation_id AND reversal.school_id=source.school_id AND reversal.status IN('PENDING','APPROVED'))
        OR EXISTS(SELECT 1 FROM accounting_journals reversal WHERE reversal.reversal_of_journal_id=journal.id
            AND reversal.organisation_id=source.organisation_id AND reversal.school_id=source.school_id) THEN
        RAISE EXCEPTION 'settlement requires exact unreversed posted receipt/refund and OTHER clearing evidence';
    END IF;
    IF source.source_kind='PAYMENT' THEN
        IF source.refund_posting_id IS NOT NULL OR payment.ledger_entry_id<>entry.id OR payment.amount<>source.amount
            OR entry.entry_type<>'PAYMENT' OR entry.direction<>'CREDIT' OR entry.source_type<>'FEE_PAYMENT' OR entry.source_id<>payment.id
            OR (SELECT count(*) FROM fee_accounting_posting_items member WHERE member.posting_batch_id=batch.id AND member.organisation_id=source.organisation_id
                AND member.school_id=source.school_id AND member.status='ACTIVE')<>batch.source_count
            OR (SELECT sum(ledger.amount) FROM fee_accounting_posting_items member JOIN fee_ledger_entries ledger ON ledger.id=member.fee_ledger_entry_id
                AND ledger.organisation_id=member.organisation_id AND ledger.school_id=member.school_id WHERE member.posting_batch_id=batch.id
                AND member.organisation_id=source.organisation_id AND member.school_id=source.school_id AND member.status='ACTIVE'
                AND ledger.entry_type='PAYMENT' AND ledger.direction='CREDIT') IS DISTINCT FROM batch.source_amount THEN
            RAISE EXCEPTION 'settlement payment claim must preserve its complete original posted batch';
        END IF;
        IF (SELECT COALESCE(sum(line.debit_amount),0) FROM accounting_journal_lines line WHERE line.accounting_journal_id=journal.id
                AND line.organisation_id=source.organisation_id AND line.school_id=source.school_id AND line.account_id=source.clearing_account_id)
            IS DISTINCT FROM (SELECT sum(ledger.amount) FROM fee_accounting_posting_items member JOIN fee_ledger_entries ledger ON ledger.id=member.fee_ledger_entry_id
                AND ledger.organisation_id=member.organisation_id AND ledger.school_id=member.school_id
                JOIN fee_payments receipt ON receipt.id=ledger.source_id AND receipt.ledger_entry_id=ledger.id
                    AND receipt.organisation_id=ledger.organisation_id AND receipt.school_id=ledger.school_id AND receipt.status IN('POSTED','REVERSED')
                JOIN fee_accounting_tender_mappings tender ON tender.configuration_id=source.configuration_id AND tender.payment_method=receipt.payment_method
                    AND tender.organisation_id=source.organisation_id AND tender.school_id=source.school_id AND tender.deposit_account_id=source.clearing_account_id
                WHERE member.posting_batch_id=batch.id AND member.organisation_id=source.organisation_id AND member.school_id=source.school_id
                    AND member.status='ACTIVE' AND ledger.entry_type='PAYMENT' AND ledger.direction='CREDIT')
            OR (SELECT COALESCE(sum(line.credit_amount),0) FROM accounting_journal_lines line WHERE line.accounting_journal_id=journal.id
                AND line.organisation_id=source.organisation_id AND line.school_id=source.school_id AND line.account_id=source.clearing_account_id)<>0 THEN
            RAISE EXCEPTION 'original common journal must contain exact gross original mapped payment clearing';
        END IF;
    ELSE
        SELECT * INTO completion FROM fee_gateway_refund_postings WHERE id=source.refund_posting_id AND organisation_id=source.organisation_id AND school_id=source.school_id;
        IF completion.id IS NULL OR completion.fee_payment_id<>payment.id OR completion.fee_ledger_entry_id<>entry.id OR completion.amount<>source.amount
            OR completion.currency_code<>source.currency_code OR entry.entry_type<>'REFUND' OR entry.direction<>'DEBIT'
            OR entry.source_type<>'GATEWAY_REFUND_POSTING' OR entry.source_id<>completion.id OR batch.refund_posting_id IS DISTINCT FROM completion.id
            OR batch.source_count<>1 OR batch.source_amount<>source.amount OR item.fee_ledger_entry_id<>completion.fee_ledger_entry_id THEN
            RAISE EXCEPTION 'settlement refund claim requires exact completed refund and active posted replacement journal';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM fee_gateway_refund_successes success WHERE success.refund_request_id=completion.refund_request_id
            AND success.organisation_id=source.organisation_id AND success.school_id=source.school_id AND success.status='HOLD'
            AND success.verified_read_id=completion.verified_read_id AND success.evidence_sha256=completion.success_evidence_sha256
            AND success.source_version=completion.source_version AND success.amount=completion.amount AND success.currency_code=completion.currency_code
            AND success.fee_payment_id=payment.id)
            OR EXISTS(SELECT 1 FROM fee_gateway_refund_reads witness WHERE witness.refund_request_id=completion.refund_request_id
                AND witness.organisation_id=source.organisation_id AND witness.school_id=source.school_id
                AND((witness.outcome='VERIFIED_SUCCESS' AND witness.evidence_sha256<>completion.success_evidence_sha256)
                    OR(witness.outcome='REVIEW_REQUIRED' AND witness.refund_status='SUCCESS'))) THEN
            RAISE EXCEPTION 'settlement refund source retains exact noncontradictory verified refund success';
        END IF;
        IF (SELECT COALESCE(sum(line.credit_amount),0) FROM accounting_journal_lines line WHERE line.accounting_journal_id=journal.id
                AND line.organisation_id=source.organisation_id AND line.school_id=source.school_id AND line.account_id=source.clearing_account_id)<>source.amount
            OR (SELECT COALESCE(sum(line.debit_amount),0) FROM accounting_journal_lines line WHERE line.accounting_journal_id=journal.id
                AND line.organisation_id=source.organisation_id AND line.school_id=source.school_id AND line.account_id=source.clearing_account_id)<>0 THEN
            RAISE EXCEPTION 'completed refund journal must contain exact gross original clearing credit';
        END IF;
    END IF;
END; $$;

CREATE FUNCTION guard_gateway_settlement_source() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE root fee_gateway_settlement_roots%ROWTYPE;
BEGIN
    PERFORM gateway_settlement_finance_fences(NEW.school_id);
    SELECT * INTO root FROM fee_gateway_settlement_roots WHERE id=NEW.root_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF root.id IS NULL OR root.created_by_user_id IS DISTINCT FROM current_app_user_id()
        OR EXISTS(SELECT 1 FROM fee_gateway_settlement_preparations WHERE root_id=root.id) THEN
        RAISE EXCEPTION 'economic source set is frozen by its first preparation';
    END IF;
    PERFORM require_gateway_settlement_prepare_actor(root.organisation_id,root.school_id,root.created_by_membership_id,root.created_by_user_id);
    IF EXISTS(SELECT 1 FROM fee_gateway_settlement_sources previous WHERE previous.organisation_id=NEW.organisation_id AND previous.school_id=NEW.school_id
        AND previous.root_id<>NEW.root_id AND previous.source_kind=NEW.source_kind
        AND(CASE WHEN NEW.source_kind='PAYMENT' THEN previous.payment_id=NEW.payment_id ELSE previous.refund_posting_id=NEW.refund_posting_id END)
        AND NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_abandonments abandoned WHERE abandoned.root_id=previous.root_id)) THEN
        RAISE EXCEPTION 'economic source already belongs to a retained settlement' USING ERRCODE='23514',CONSTRAINT='gateway_settlement_source_consumed';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER gateway_settlement_source_insert_guard BEFORE INSERT ON fee_gateway_settlement_sources FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_source();

CREATE FUNCTION guard_gateway_settlement_preparation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE root fee_gateway_settlement_roots%ROWTYPE; bank fee_gateway_settlement_bank_evidence%ROWTYPE;
    previous fee_gateway_settlement_preparations%ROWTYPE; prior_journal accounting_journals%ROWTYPE;
    counter_journal accounting_journals%ROWTYPE; zone_value TEXT; source_value RECORD;
BEGIN
    PERFORM gateway_settlement_finance_fences(NEW.school_id);
    PERFORM require_gateway_settlement_prepare_actor(NEW.organisation_id,NEW.school_id,NEW.prepared_by_membership_id,NEW.prepared_by_user_id);
    SELECT * INTO root FROM fee_gateway_settlement_roots WHERE id=NEW.root_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO bank FROM fee_gateway_settlement_bank_evidence WHERE id=root.bank_evidence_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT time_zone INTO zone_value FROM schools WHERE id=NEW.school_id AND organisation_id=NEW.organisation_id AND status='ACTIVE';
    IF root.id IS NULL OR bank.id IS NULL OR zone_value IS NULL OR bank.credited_amount<>NEW.bank_amount OR bank.currency_code<>NEW.currency_code
        OR NEW.posting_on<bank.credited_on OR NEW.posting_on>(clock_timestamp() AT TIME ZONE zone_value)::date
        OR EXISTS(SELECT 1 FROM fee_day_closes closed WHERE closed.organisation_id=NEW.organisation_id AND closed.school_id=NEW.school_id
            AND closed.status='APPROVED' AND closed.business_date>=NEW.posting_on)
        OR EXISTS(SELECT 1 FROM fee_gateway_settlement_abandonments abandoned WHERE abandoned.root_id=root.id)
        OR EXISTS(SELECT 1 FROM fee_gateway_settlement_bank_evidence_rejections rejected WHERE rejected.bank_evidence_id=bank.id) THEN
        RAISE EXCEPTION 'settlement preparation requires its retained unrejected bank evidence and eligible open posting date';
    END IF;
    PERFORM require_gateway_bank_evidence_report(NEW.organisation_id,NEW.school_id,root.request_id,root.source_summary_read_id,
        root.source_summary_row_index,root.settlement_reference,
        (SELECT school_time_zone FROM fee_gateway_settlement_requests WHERE id=root.request_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id));
    IF NEW.evidence_sha256 IS DISTINCT FROM encode(sha256(convert_to(NEW.evidence_snapshot::text,'UTF8')),'hex')
        OR NEW.evidence_snapshot->>'schemaVersion' IS DISTINCT FROM '1'
        OR NEW.evidence_snapshot->>'contextFingerprint' IS DISTINCT FROM NEW.evidence_snapshot->'command'->>'contextFingerprint'
        OR COALESCE(NEW.evidence_snapshot->>'contextFingerprint','') !~ '^[a-f0-9]{64}$'
        OR NEW.evidence_snapshot->>'financialYearId' IS DISTINCT FROM NEW.financial_year_id::text
        OR NEW.evidence_snapshot->>'accountingPeriodId' IS DISTINCT FROM NEW.accounting_period_id::text
        OR NEW.evidence_snapshot->>'postingOn' IS DISTINCT FROM NEW.posting_on::text
        OR NEW.evidence_snapshot->>'currencyCode' IS DISTINCT FROM NEW.currency_code
        OR NEW.evidence_snapshot->'command'->>'schoolId' IS DISTINCT FROM NEW.school_id::text
        OR NEW.evidence_snapshot->'command'->>'clientRequestId' IS DISTINCT FROM NEW.client_request_id::text
        OR NEW.evidence_snapshot->'command'->>'bankEvidenceId' IS DISTINCT FROM root.bank_evidence_id::text
        OR NEW.evidence_snapshot->'command'->>'previousPreparationId' IS DISTINCT FROM NEW.previous_preparation_id::text
        OR NEW.evidence_snapshot->'command'->>'confirmedSummaryCurrency' IS DISTINCT FROM 'true'
        OR NEW.evidence_snapshot->'command'->>'confirmedReviewed' IS DISTINCT FROM 'true'
        OR NEW.evidence_snapshot->'command'->>'summaryCurrencyCode' IS DISTINCT FROM 'INR'
        OR COALESCE(NEW.evidence_snapshot->'command'->>'componentBasis','') NOT IN('SUMMARY','EVENTS') THEN
        RAISE EXCEPTION 'new version-one settlement snapshot requires its exact canonical database digest and reviewed terms';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM accounting_accounts account WHERE account.id=bank.bank_account_id AND account.organisation_id=NEW.organisation_id
        AND account.school_id=NEW.school_id AND account.account_type='ASSET' AND account.category='BANK' AND account.status='ACTIVE' AND account.allow_direct_posting) THEN
        RAISE EXCEPTION 'settlement preparation requires its active actual bank chart identity';
    END IF;
    PERFORM 1 FROM accounting_financial_years year JOIN accounting_periods period ON period.financial_year_id=year.id
        AND period.organisation_id=year.organisation_id AND period.school_id=year.school_id
        WHERE year.id=NEW.financial_year_id AND period.id=NEW.accounting_period_id AND year.organisation_id=NEW.organisation_id
            AND year.school_id=NEW.school_id AND year.status='OPEN' AND period.status='OPEN' AND year.currency_code=NEW.currency_code
            AND NEW.posting_on BETWEEN year.starts_on AND year.ends_on AND NEW.posting_on BETWEEN period.starts_on AND period.ends_on
        FOR SHARE OF year,period;
    IF NOT FOUND THEN RAISE EXCEPTION 'settlement preparation requires an exact open accounting book'; END IF;
    IF NEW.revision=1 THEN
        IF root.created_by_user_id<>NEW.prepared_by_user_id OR EXISTS(SELECT 1 FROM fee_gateway_settlement_preparations WHERE root_id=root.id) THEN
            RAISE EXCEPTION 'first settlement preparation must complete its original human root';
        END IF;
    ELSE
        SELECT * INTO previous FROM fee_gateway_settlement_preparations WHERE id=NEW.previous_preparation_id AND root_id=root.id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        SELECT * INTO prior_journal FROM accounting_journals WHERE id=NEW.previous_journal_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF previous.id IS NULL OR previous.journal_id IS DISTINCT FROM NEW.previous_journal_id OR previous.revision+1<>NEW.revision
            OR prior_journal.id IS NULL OR prior_journal.source_module<>'GATEWAY_SETTLEMENT' OR prior_journal.source_id IS DISTINCT FROM previous.id
            OR prior_journal.status NOT IN('REJECTED','REVERSED') OR NEW.posting_on<previous.posting_on THEN
            RAISE EXCEPTION 'settlement repair must retain the same root and exact rejected/reversed predecessor';
        END IF;
        IF prior_journal.status='REVERSED' THEN
            SELECT counter.* INTO counter_journal FROM accounting_reversal_requests reversal
                JOIN accounting_journals counter ON counter.id=reversal.reversal_journal_id
                    AND counter.organisation_id=reversal.organisation_id AND counter.school_id=reversal.school_id
                WHERE reversal.accounting_journal_id=prior_journal.id AND reversal.organisation_id=NEW.organisation_id
                    AND reversal.school_id=NEW.school_id AND reversal.status='APPROVED' AND counter.status='POSTED'
                    AND counter.source_module='REVERSAL' AND counter.source_id=prior_journal.id AND counter.reversal_of_journal_id=prior_journal.id
                    AND counter.accounting_period_id=reversal.requested_accounting_period_id
                    AND counter.posting_date=reversal.requested_posting_date;
            IF counter_journal.id IS NULL OR NEW.posting_on<counter_journal.posting_date THEN
                RAISE EXCEPTION 'settlement repair cannot predate its exact approved posted counter-voucher'
                    USING ERRCODE='23514',CONSTRAINT='gateway_settlement_repair_before_counter';
            END IF;
        END IF;
    END IF;
    IF (SELECT count(*) FROM fee_gateway_settlement_sources WHERE root_id=root.id) NOT BETWEEN 1 AND 10000 THEN
        RAISE EXCEPTION 'complete nonempty frozen settlement source set required';
    END IF;
    FOR source_value IN SELECT source.id,batch.business_date FROM fee_gateway_settlement_sources source
        JOIN fee_accounting_posting_batches batch ON batch.id=source.posting_batch_id AND batch.organisation_id=source.organisation_id AND batch.school_id=source.school_id
        WHERE source.root_id=root.id LOOP
        PERFORM assert_gateway_settlement_source(source_value.id,true);
        IF NEW.posting_on<source_value.business_date THEN RAISE EXCEPTION 'settlement cannot predate its actual source posting'; END IF;
    END LOOP;
    NEW.prepared_at:=clock_timestamp(); RETURN NEW;
END; $$;
CREATE TRIGGER gateway_settlement_preparation_insert_guard BEFORE INSERT ON fee_gateway_settlement_preparations FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_preparation();

CREATE FUNCTION guard_gateway_settlement_source_row() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE preparation fee_gateway_settlement_preparations%ROWTYPE; root fee_gateway_settlement_roots%ROWTYPE;
    source fee_gateway_settlement_sources%ROWTYPE; facts JSONB; capture fee_gateway_captures%ROWTYPE;
BEGIN
    SELECT * INTO preparation FROM fee_gateway_settlement_preparations WHERE id=NEW.preparation_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO root FROM fee_gateway_settlement_roots WHERE id=preparation.root_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO source FROM fee_gateway_settlement_sources WHERE id=NEW.source_id AND root_id=root.id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF preparation.id IS NULL OR root.id IS NULL OR source.id IS NULL OR preparation.prepared_by_user_id IS DISTINCT FROM current_app_user_id()
        OR current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id THEN RAISE EXCEPTION 'scoped settlement source row required'; END IF;
    SELECT witness.facts_canonical::jsonb->NEW.row_index INTO facts FROM fee_gateway_settlement_reads witness
        JOIN fee_gateway_settlement_queries query ON query.id=witness.query_id AND query.organisation_id=witness.organisation_id AND query.school_id=witness.school_id
        JOIN fee_gateway_settlement_selections selected ON selected.read_id=witness.id AND selected.query_id=query.id
            AND selected.organisation_id=witness.organisation_id AND selected.school_id=witness.school_id AND selected.outcome='COMPLETE'
        WHERE witness.id=NEW.source_read_id AND witness.organisation_id=NEW.organisation_id AND witness.school_id=NEW.school_id
            AND witness.outcome='COMPLETE' AND query.root_query_id=root.request_id AND query.query_kind='EVENTS' AND selected.request_id=root.request_id;
    SELECT gateway.* INTO capture FROM fee_gateway_captures gateway JOIN fee_payments payment ON payment.gateway_capture_id=gateway.id
        AND payment.organisation_id=gateway.organisation_id AND payment.school_id=gateway.school_id
        WHERE payment.id=source.payment_id AND payment.organisation_id=NEW.organisation_id AND payment.school_id=NEW.school_id;
    IF facts IS NULL OR capture.id IS NULL OR facts->>'reportedType' IS DISTINCT FROM source.source_kind
        OR facts->>'reportedDirection' IS DISTINCT FROM (CASE WHEN source.source_kind='PAYMENT' THEN 'CREDIT' ELSE 'DEBIT' END)
        OR facts->>'reportedStatus' IS DISTINCT FROM 'SUCCESS' OR facts->>'currencyCode' IS DISTINCT FROM source.currency_code
        OR facts->>'settlementReference' IS DISTINCT FROM root.settlement_reference OR(facts->>'reportedAmount')::numeric IS DISTINCT FROM source.amount
        OR facts->>'orderReference' IS DISTINCT FROM capture.order_reference OR facts->>'paymentReference' IS DISTINCT FROM capture.payment_reference
        OR(source.source_kind='REFUND' AND NEW.association_reason IS NULL)
        OR(source.source_kind='PAYMENT' AND NEW.association_reason IS NOT NULL) THEN
        RAISE EXCEPTION 'physical settlement event must match exact payment/refund economic source and explicit refund choice';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER gateway_settlement_source_row_insert_guard BEFORE INSERT ON fee_gateway_settlement_source_rows FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_source_row();

CREATE FUNCTION guard_gateway_settlement_component() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE preparation fee_gateway_settlement_preparations%ROWTYPE; root fee_gateway_settlement_roots%ROWTYPE; bank fee_gateway_settlement_bank_evidence%ROWTYPE; account accounting_accounts%ROWTYPE;
    field_value TEXT; summary_value TEXT; events_value NUMERIC; events_complete BOOLEAN; selected_value TEXT; reported_value NUMERIC;
    event_basis BOOLEAN; mode_value TEXT; expected_basis TEXT;
BEGIN
    SELECT * INTO preparation FROM fee_gateway_settlement_preparations WHERE id=NEW.preparation_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF preparation.id IS NULL OR preparation.prepared_by_user_id IS DISTINCT FROM current_app_user_id()
        OR current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id THEN RAISE EXCEPTION 'scoped reviewed settlement component required'; END IF;
    SELECT * INTO root FROM fee_gateway_settlement_roots WHERE id=preparation.root_id;
    SELECT * INTO bank FROM fee_gateway_settlement_bank_evidence WHERE id=root.bank_evidence_id;
    field_value:=CASE NEW.component_kind WHEN 'SERVICE_CHARGE' THEN 'reportedServiceCharge' WHEN 'SERVICE_TAX' THEN 'reportedServiceTax'
        WHEN 'ADJUSTMENT' THEN 'reportedAdjustment' WHEN 'SETTLEMENT_CHARGE' THEN 'reportedSettlementCharge' WHEN 'SETTLEMENT_TAX' THEN 'reportedSettlementTax'
        WHEN 'SPLIT_CHARGE' THEN 'reportedSplitCharge' WHEN 'SPLIT_TAX' THEN 'reportedSplitTax' WHEN 'VENDOR_COMMISSION' THEN 'reportedVendorCommission' END;
    SELECT witness.facts_canonical::jsonb->root.source_summary_row_index->>field_value INTO summary_value
        FROM fee_gateway_settlement_reads witness WHERE witness.id=root.source_summary_read_id AND witness.organisation_id=NEW.organisation_id AND witness.school_id=NEW.school_id;
    events_complete:=false;
    IF NEW.component_kind IN('SERVICE_CHARGE','SERVICE_TAX') THEN
        SELECT sum((event.item->>field_value)::numeric),COALESCE(bool_and(event.item->>field_value IS NOT NULL AND COALESCE(event.item->>'chargesCurrencyCode'='INR',false)),false)
            INTO events_value,events_complete
            FROM fee_gateway_settlement_selections selected JOIN fee_gateway_settlement_reads witness ON witness.id=selected.read_id
                AND witness.organisation_id=selected.organisation_id AND witness.school_id=selected.school_id
            CROSS JOIN LATERAL jsonb_array_elements(witness.facts_canonical::jsonb) event(item)
            WHERE selected.request_id=root.request_id AND selected.organisation_id=NEW.organisation_id AND selected.school_id=NEW.school_id AND selected.outcome='COMPLETE';
    END IF;
    event_basis:=NEW.component_kind IN('SERVICE_CHARGE','SERVICE_TAX') AND preparation.evidence_snapshot->'command'->>'componentBasis'='EVENTS';
    selected_value:=CASE WHEN event_basis THEN CASE WHEN events_complete THEN events_value::text ELSE NULL END ELSE summary_value END;
    mode_value:=NEW.raw_field_facts->>'mode';
    expected_basis:=CASE WHEN mode_value IN('CONFIRMED_ZERO','EXTERNAL_REVIEW') THEN 'REVIEWED_DOCUMENT' WHEN event_basis THEN 'EVENTS_REPORTED' ELSE 'SUMMARY_REPORTED' END;
    IF EXISTS(SELECT 1 FROM jsonb_object_keys(NEW.raw_field_facts) AS keys(key_value) WHERE key_value NOT IN('mode','summaryAmount','eventsAmount','eventsComplete','selectedReportedAmount'))
        OR EXISTS(SELECT 1 FROM jsonb_each(NEW.raw_field_facts) AS fields(key_value,value)
            WHERE key_value IN('summaryAmount','eventsAmount','selectedReportedAmount') AND value<>'null'::jsonb AND jsonb_typeof(value)<>'string')
        OR NEW.component_basis IS DISTINCT FROM expected_basis
        OR NEW.raw_field_facts->>'summaryAmount' IS DISTINCT FROM summary_value
        OR NEW.raw_field_facts->'eventsComplete' IS DISTINCT FROM to_jsonb(events_complete)
        OR (NEW.raw_field_facts->>'eventsAmount' IS NOT NULL AND (NEW.raw_field_facts->>'eventsAmount')::numeric IS DISTINCT FROM events_value)
        OR (events_complete AND NEW.raw_field_facts->>'eventsAmount' IS NULL)
        OR (NEW.raw_field_facts->>'selectedReportedAmount')::numeric IS DISTINCT FROM selected_value::numeric THEN
        RAISE EXCEPTION 'reviewed settlement component facts must preserve exact selected provider summary/event observations';
    END IF;
    reported_value:=selected_value::numeric;
    IF (mode_value='REPORTED' AND(reported_value IS NULL OR abs(reported_value)<>NEW.amount OR reported_value<>trunc(reported_value,2)))
        OR (mode_value='ROUNDING_REVIEW' AND(reported_value IS NULL OR abs(reported_value)=NEW.amount OR abs(abs(reported_value)-NEW.amount)>=0.01 OR NEW.evidence_reference IS NULL))
        OR (mode_value='CONFIRMED_ZERO' AND(NEW.amount<>0 OR(reported_value IS NOT NULL AND reported_value<>0)))
        OR (mode_value='EXTERNAL_REVIEW' AND(reported_value IS NOT NULL OR NEW.amount=0 OR NEW.evidence_reference IS NULL))
        OR (reported_value IS NOT NULL AND reported_value<>0 AND NEW.component_kind<>'ADJUSTMENT'
            AND NEW.direction IS DISTINCT FROM (CASE WHEN reported_value>0 THEN 'DEBIT' ELSE 'CREDIT' END)) THEN
        RAISE EXCEPTION 'component amount and sign require exact reported value or explicit bounded documented review';
    END IF;
    IF NEW.amount=0 THEN RETURN NEW; END IF;
    SELECT * INTO account FROM accounting_accounts WHERE id=NEW.account_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF account.id IS NULL OR account.status<>'ACTIVE' OR NOT account.allow_direct_posting OR account.id=bank.bank_account_id
        OR account.category IN('CASH','BANK') OR EXISTS(SELECT 1 FROM fee_gateway_settlement_sources source WHERE source.root_id=root.id AND source.clearing_account_id=account.id)
        OR (NEW.component_kind IN('SERVICE_CHARGE','SETTLEMENT_CHARGE','SPLIT_CHARGE','VENDOR_COMMISSION') AND NOT(account.account_type='EXPENSE' AND account.category IN('EXPENSE','OTHER')))
        OR (NEW.component_kind IN('SERVICE_TAX','SETTLEMENT_TAX','SPLIT_TAX') AND NOT((account.account_type='EXPENSE' AND account.category IN('EXPENSE','OTHER')) OR(account.account_type='ASSET' AND account.category='OTHER')))
        OR (NEW.component_kind='ADJUSTMENT' AND NOT(account.account_type IN('EXPENSE','INCOME') OR(account.account_type='ASSET' AND account.category='OTHER') OR(account.account_type='LIABILITY' AND account.category IN('PAYABLE','OTHER')))) THEN
        RAISE EXCEPTION 'settlement component requires an eligible distinct reviewed chart account';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER gateway_settlement_component_insert_guard BEFORE INSERT ON fee_gateway_settlement_components FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_component();

-- Finance already holds its operation fence before approving a close. Join the
-- accounting fence last so a concurrent settlement decision cannot validate an
-- open day while an earlier close approval is still committing. Decisions never
-- acquire a finance fence after accounting (which would invert that lock order).
CREATE FUNCTION fence_gateway_settlement_financial_day_close() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='APPROVED' AND OLD.status IS DISTINCT FROM NEW.status THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || NEW.school_id::text,0));
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_day_closes_settlement_fence BEFORE UPDATE ON fee_day_closes
    FOR EACH ROW EXECUTE FUNCTION fence_gateway_settlement_financial_day_close();

CREATE FUNCTION assert_gateway_settlement_coupling(preparation_value UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE preparation fee_gateway_settlement_preparations%ROWTYPE; root fee_gateway_settlement_roots%ROWTYPE; journal accounting_journals%ROWTYPE;
    bank fee_gateway_settlement_bank_evidence%ROWTYPE; maker UUID; submitter UUID; source_count INTEGER; row_count INTEGER; event_count INTEGER;
    payment_sum NUMERIC; refund_sum NUMERIC; debit_sum NUMERIC; credit_sum NUMERIC; incorrect BOOLEAN;
    snapshot JSONB; summary_facts JSONB; source_value RECORD; component_value RECORD; expected_review JSONB;
BEGIN
    SELECT * INTO preparation FROM fee_gateway_settlement_preparations WHERE id=preparation_value AND organisation_id=current_app_organisation_id();
    SELECT * INTO root FROM fee_gateway_settlement_roots WHERE id=preparation.root_id AND organisation_id=preparation.organisation_id AND school_id=preparation.school_id;
    SELECT * INTO journal FROM accounting_journals WHERE id=preparation.journal_id AND organisation_id=preparation.organisation_id AND school_id=preparation.school_id;
    SELECT * INTO bank FROM fee_gateway_settlement_bank_evidence WHERE id=root.bank_evidence_id AND organisation_id=preparation.organisation_id AND school_id=preparation.school_id;
    SELECT user_id INTO maker FROM memberships WHERE id=journal.created_by_membership_id AND organisation_id=preparation.organisation_id AND school_id=preparation.school_id;
    SELECT user_id INTO submitter FROM memberships WHERE id=journal.submitted_by_membership_id AND organisation_id=preparation.organisation_id AND school_id=preparation.school_id;
    IF preparation.id IS NULL OR root.id IS NULL OR bank.id IS NULL OR journal.id IS NULL OR journal.source_module<>'GATEWAY_SETTLEMENT'
        OR journal.source_id IS DISTINCT FROM preparation.id OR journal.status NOT IN('SUBMITTED','POSTED','REJECTED','REVERSED')
        OR journal.voucher_type<>'JOURNAL' OR journal.reversal_of_journal_id IS NOT NULL OR journal.financial_year_id<>preparation.financial_year_id
        OR journal.accounting_period_id<>preparation.accounting_period_id OR journal.posting_date<>preparation.posting_on
        OR maker IS DISTINCT FROM preparation.prepared_by_user_id OR submitter IS DISTINCT FROM preparation.prepared_by_user_id
        OR bank.credited_amount<>preparation.bank_amount OR bank.currency_code<>preparation.currency_code THEN
        RAISE EXCEPTION 'settlement preparation must couple to its exact independently decided common journal';
    END IF;
    SELECT count(*),COALESCE(sum(amount) FILTER(WHERE source_kind='PAYMENT'),0),COALESCE(sum(amount) FILTER(WHERE source_kind='REFUND'),0)
        INTO source_count,payment_sum,refund_sum FROM fee_gateway_settlement_sources WHERE root_id=root.id;
    SELECT count(*) INTO row_count FROM fee_gateway_settlement_source_rows WHERE preparation_id=preparation.id;
    SELECT COALESCE(sum(jsonb_array_length(witness.facts_canonical::jsonb)),0)::integer INTO event_count
        FROM fee_gateway_settlement_selections selected JOIN fee_gateway_settlement_reads witness ON witness.id=selected.read_id
            AND witness.organisation_id=selected.organisation_id AND witness.school_id=selected.school_id
        WHERE selected.request_id=root.request_id AND selected.organisation_id=root.organisation_id AND selected.school_id=root.school_id AND selected.outcome='COMPLETE';
    SELECT COALESCE(sum(amount) FILTER(WHERE direction='DEBIT'),0),COALESCE(sum(amount) FILTER(WHERE direction='CREDIT'),0)
        INTO debit_sum,credit_sum FROM fee_gateway_settlement_components WHERE preparation_id=preparation.id;
    IF source_count NOT BETWEEN 1 AND 10000 OR row_count<>source_count OR event_count<>row_count
        OR payment_sum<>preparation.payment_total OR refund_sum<>preparation.refund_total
        OR debit_sum<>preparation.component_debit_total OR credit_sum<>preparation.component_credit_total
        OR (SELECT count(*) FROM fee_gateway_settlement_components WHERE preparation_id=preparation.id)<>8
        OR payment_sum-refund_sum<>preparation.bank_amount+debit_sum-credit_sum THEN
        RAISE EXCEPTION 'complete physical events and eight reviewed components must explain exact settlement equality';
    END IF;
    -- The independent reviewer reads this frozen snapshot. Its canonical digest
    -- alone cannot prove that it describes the actual rows/journal being posted.
    -- Couple every displayed financial identity and reviewed choice to those rows.
    snapshot:=preparation.evidence_snapshot;
    SELECT witness.facts_canonical::jsonb->root.source_summary_row_index INTO summary_facts
        FROM fee_gateway_settlement_reads witness WHERE witness.id=root.source_summary_read_id
            AND witness.organisation_id=preparation.organisation_id AND witness.school_id=preparation.school_id;
    IF snapshot->>'rootId' IS DISTINCT FROM root.id::text OR snapshot->>'requestId' IS DISTINCT FROM root.request_id::text
        OR snapshot->>'sourceSummaryReadId' IS DISTINCT FROM root.source_summary_read_id::text
        OR snapshot->>'sourceSummaryRowIndex' IS DISTINCT FROM root.source_summary_row_index::text
        OR snapshot->>'schoolTimeZone' IS DISTINCT FROM bank.school_time_zone
        OR snapshot->'summaryProjection' IS DISTINCT FROM summary_facts
        OR summary_facts->>'reportedStatus' IS DISTINCT FROM 'SUCCESS'
        OR (summary_facts->>'reportedAmountSettled')::numeric IS DISTINCT FROM preparation.bank_amount
        OR snapshot->'command'->>'financialYearId' IS DISTINCT FROM preparation.financial_year_id::text
        OR snapshot->'command'->>'postingOn' IS DISTINCT FROM preparation.posting_on::text
        OR snapshot->'command'->>'reason' IS DISTINCT FROM preparation.preparation_reason
        OR jsonb_typeof(snapshot->'sources') IS DISTINCT FROM 'array'
        OR jsonb_typeof(snapshot->'components') IS DISTINCT FROM 'array'
        OR jsonb_typeof(snapshot->'command'->'componentReviews') IS DISTINCT FROM 'array'
        OR jsonb_typeof(snapshot->'command'->'refundAssociations') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'settlement reviewer snapshot requires exact retained statement and posting terms';
    END IF;
    IF jsonb_array_length(snapshot->'sources')<>source_count OR jsonb_array_length(snapshot->'components')<>8
        OR jsonb_array_length(snapshot->'command'->'componentReviews')<>8
        OR jsonb_array_length(snapshot->'command'->'refundAssociations')<>(SELECT count(*) FROM fee_gateway_settlement_sources WHERE root_id=root.id AND source_kind='REFUND')
        OR NOT COALESCE(snapshot->'bankEvidence' @> jsonb_build_object('id',bank.id,'clientRequestId',bank.client_request_id,
            'bankAccountId',bank.bank_account_id,'bankReference',bank.bank_credit_reference,'currencyCode',bank.currency_code,
            'creditedAmount',to_char(bank.credited_amount,'FM999999999990.00'),'actualCreditedAt',bank.actual_credited_at_exact,
            'creditedOn',bank.credited_on,'verificationKind',bank.verification_kind,'reason',bank.reason),false) THEN
        RAISE EXCEPTION 'settlement reviewer snapshot must preserve exact bank evidence and complete source/component choices';
    END IF;
    FOR source_value IN SELECT source.*,physical.source_read_id,physical.row_index,physical.association_reason
        FROM fee_gateway_settlement_sources source JOIN fee_gateway_settlement_source_rows physical ON physical.source_id=source.id
            AND physical.organisation_id=source.organisation_id AND physical.school_id=source.school_id
        WHERE source.root_id=root.id AND physical.preparation_id=preparation.id LOOP
        IF NOT COALESCE(snapshot->'sources' @> jsonb_build_array(jsonb_build_object('id',source_value.id,'kind',source_value.source_kind,
            'paymentId',source_value.payment_id,'refundId',source_value.refund_posting_id,'ledgerId',source_value.fee_ledger_entry_id,
            'itemId',source_value.posting_item_id,'batchId',source_value.posting_batch_id,'journalId',source_value.source_journal_id,
            'configurationId',source_value.configuration_id,'clearingId',source_value.clearing_account_id,'amount',source_value.amount,
            'currency',source_value.currency_code,'sourceHash',source_value.source_sha256,'batchHash',source_value.batch_sha256,
            'sourceReadId',source_value.source_read_id,'rowIndex',source_value.row_index,'associationReason',source_value.association_reason)),false)
            OR (source_value.source_kind='REFUND' AND NOT COALESCE(snapshot->'command'->'refundAssociations' @>
                jsonb_build_array(jsonb_build_object('sourceReadId',source_value.source_read_id,'rowIndex',source_value.row_index,
                    'refundPostingId',source_value.refund_posting_id,'reason',source_value.association_reason)),false)) THEN
            RAISE EXCEPTION 'settlement reviewer snapshot source and refund association must describe the exact consumed immutable proof'
                USING ERRCODE='23514',CONSTRAINT='gateway_settlement_snapshot_source_mismatch';
        END IF;
    END LOOP;
    FOR component_value IN SELECT * FROM fee_gateway_settlement_components WHERE preparation_id=preparation.id LOOP
        expected_review:=jsonb_build_object('key',component_value.component_key,'mode',component_value.raw_field_facts->>'mode',
            'direction',component_value.direction,'accountId',component_value.account_id,
            'postedAmount',to_char(component_value.amount,'FM999999999990.00'),
            'evidenceReference',component_value.evidence_reference,'reason',component_value.reason);
        IF NOT COALESCE(snapshot->'command'->'componentReviews' @> jsonb_build_array(expected_review),false)
            OR NOT COALESCE(snapshot->'components' @> jsonb_build_array(jsonb_build_object('review',expected_review,
                'basis',component_value.component_basis,'amount',component_value.amount,'fact',jsonb_build_object(
                    'key',component_value.component_key,'summaryAmount',component_value.raw_field_facts->'summaryAmount',
                    'eventsAmount',component_value.raw_field_facts->'eventsAmount','eventsComplete',component_value.raw_field_facts->'eventsComplete'))),false) THEN
            RAISE EXCEPTION 'settlement reviewer component must describe its exact posted account, amount, basis and documented review';
        END IF;
    END LOOP;
    -- Match separate gross debit and credit sides and disallow vendor/learner
    -- dimensions. Do not net source sides or accept an arbitrary balanced journal.
    WITH expected_raw(account_id,debit_amount,credit_amount) AS (
        SELECT bank.bank_account_id,preparation.bank_amount,0::numeric
        UNION ALL SELECT clearing_account_id,CASE WHEN source_kind='REFUND' THEN amount ELSE 0 END,CASE WHEN source_kind='PAYMENT' THEN amount ELSE 0 END
            FROM fee_gateway_settlement_sources WHERE root_id=root.id
        UNION ALL SELECT account_id,CASE WHEN direction='DEBIT' THEN amount ELSE 0 END,CASE WHEN direction='CREDIT' THEN amount ELSE 0 END
            FROM fee_gateway_settlement_components WHERE preparation_id=preparation.id AND amount>0
    ), expected AS(SELECT account_id,sum(debit_amount) debit_amount,sum(credit_amount) credit_amount FROM expected_raw GROUP BY account_id),
    actual AS(SELECT account_id,sum(debit_amount) debit_amount,sum(credit_amount) credit_amount FROM accounting_journal_lines
        WHERE accounting_journal_id=journal.id AND organisation_id=preparation.organisation_id AND school_id=preparation.school_id GROUP BY account_id)
    SELECT EXISTS((SELECT * FROM expected EXCEPT SELECT * FROM actual) UNION ALL(SELECT * FROM actual EXCEPT SELECT * FROM expected))
        OR EXISTS(SELECT 1 FROM accounting_journal_lines WHERE accounting_journal_id=journal.id AND(vendor_id IS NOT NULL OR learner_fee_account_id IS NOT NULL)) INTO incorrect;
    IF incorrect OR journal.total_amount IS DISTINCT FROM preparation.bank_amount+refund_sum+debit_sum
        OR journal.line_count IS DISTINCT FROM(SELECT count(*)::smallint FROM accounting_journal_lines WHERE accounting_journal_id=journal.id) THEN
        RAISE EXCEPTION 'settlement common journal must match exact bank, gross clearing sides and reviewed components';
    END IF;
END; $$;

CREATE FUNCTION check_gateway_settlement_coupling() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE preparation_value UUID;
BEGIN
    IF TG_TABLE_NAME='fee_gateway_settlement_roots' THEN
        SELECT id INTO preparation_value FROM fee_gateway_settlement_preparations WHERE root_id=NEW.id AND revision=1;
    ELSIF TG_TABLE_NAME='accounting_journals' THEN
        IF NEW.source_module<>'GATEWAY_SETTLEMENT' THEN RETURN NULL; END IF;
        preparation_value:=NEW.source_id;
    ELSE preparation_value:=NEW.id; END IF;
    IF preparation_value IS NULL THEN RAISE EXCEPTION 'settlement root and journal cannot commit without their first complete preparation'; END IF;
    PERFORM assert_gateway_settlement_coupling(preparation_value); RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER gateway_settlement_root_coupled AFTER INSERT ON fee_gateway_settlement_roots DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_gateway_settlement_coupling();
CREATE CONSTRAINT TRIGGER gateway_settlement_preparation_coupled AFTER INSERT ON fee_gateway_settlement_preparations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_gateway_settlement_coupling();
CREATE CONSTRAINT TRIGGER gateway_settlement_journal_coupled AFTER INSERT OR UPDATE ON accounting_journals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.source_module='GATEWAY_SETTLEMENT') EXECUTE FUNCTION check_gateway_settlement_coupling();

CREATE FUNCTION assert_gateway_settlement_journal_decision(journal_value UUID,membership_value UUID,decision_value TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE journal accounting_journals%ROWTYPE; preparation fee_gateway_settlement_preparations%ROWTYPE; maker UUID; submitter UUID; source_value RECORD;
BEGIN
    IF current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off'
        OR current_app_user_id() IS NULL OR decision_value NOT IN('POST','REJECT') THEN
        RAISE EXCEPTION 'settlement journal decision requires live writable read committed human authority' USING ERRCODE='40001';
    END IF;
    SELECT * INTO journal FROM accounting_journals WHERE id=journal_value AND organisation_id=current_app_organisation_id();
    IF journal.id IS NULL OR journal.source_module<>'GATEWAY_SETTLEMENT' OR journal.status<>'SUBMITTED' THEN RAISE EXCEPTION 'submitted scoped settlement journal required'; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || journal.school_id::text,0));
    SELECT * INTO journal FROM accounting_journals WHERE id=journal_value AND organisation_id=current_app_organisation_id();
    SELECT * INTO preparation FROM fee_gateway_settlement_preparations WHERE id=journal.source_id AND journal_id=journal.id AND organisation_id=journal.organisation_id AND school_id=journal.school_id;
    SELECT user_id INTO maker FROM memberships WHERE id=journal.created_by_membership_id AND organisation_id=journal.organisation_id AND school_id=journal.school_id;
    SELECT user_id INTO submitter FROM memberships WHERE id=journal.submitted_by_membership_id AND organisation_id=journal.organisation_id AND school_id=journal.school_id;
    IF preparation.id IS NULL OR journal.status<>'SUBMITTED' OR maker IS NULL OR submitter IS NULL
        OR current_app_user_id() IN(maker,submitter,preparation.prepared_by_user_id) THEN
        RAISE EXCEPTION 'settlement journal requires a different actual authorized reviewer' USING ERRCODE='23514',CONSTRAINT='gateway_settlement_independent_reviewer_required';
    END IF;
    PERFORM 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
        JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
        JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
        JOIN membership_roles assignment ON assignment.membership_id=member.id
        JOIN roles role ON role.id=assignment.role_id AND(role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
        JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code='accounting.post'
        WHERE member.id=membership_value AND member.user_id=current_app_user_id() AND member.organisation_id=journal.organisation_id
            AND member.school_id=journal.school_id AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
            AND(member.valid_until IS NULL OR member.valid_until>clock_timestamp()) FOR SHARE OF member,actor,school,assignment;
    IF NOT FOUND THEN RAISE EXCEPTION 'settlement journal requires live school posting authority'; END IF;
    IF EXISTS(SELECT 1 FROM fee_gateway_settlement_abandonments WHERE root_id=preparation.root_id) THEN RAISE EXCEPTION 'abandoned settlement cannot be posted or repaired'; END IF;
    PERFORM assert_gateway_settlement_coupling(preparation.id);
    IF decision_value='POST' THEN
        IF EXISTS(SELECT 1 FROM fee_day_closes closed WHERE closed.organisation_id=journal.organisation_id AND closed.school_id=journal.school_id
            AND closed.status='APPROVED' AND closed.business_date>=preparation.posting_on) THEN
            RAISE EXCEPTION 'closed financial day requires reviewed settlement repair on an eligible open date';
        END IF;
        IF NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_roots root JOIN fee_gateway_settlement_bank_evidence bank ON bank.id=root.bank_evidence_id
            AND bank.organisation_id=root.organisation_id AND bank.school_id=root.school_id
            JOIN accounting_accounts account ON account.id=bank.bank_account_id AND account.organisation_id=bank.organisation_id AND account.school_id=bank.school_id
            WHERE root.id=preparation.root_id AND root.organisation_id=journal.organisation_id AND root.school_id=journal.school_id
                AND account.account_type='ASSET' AND account.category='BANK' AND account.status='ACTIVE' AND account.allow_direct_posting) THEN
            RAISE EXCEPTION 'settlement decision requires the current eligible actual bank account';
        END IF;
        IF EXISTS(SELECT 1 FROM fee_gateway_settlement_components component LEFT JOIN accounting_accounts account ON account.id=component.account_id
            AND account.organisation_id=component.organisation_id AND account.school_id=component.school_id
            WHERE component.preparation_id=preparation.id AND component.amount>0 AND(account.id IS NULL OR account.status<>'ACTIVE' OR NOT account.allow_direct_posting
                OR account.category IN('CASH','BANK')
                OR(component.component_kind IN('SERVICE_CHARGE','SETTLEMENT_CHARGE','SPLIT_CHARGE','VENDOR_COMMISSION') AND NOT(account.account_type='EXPENSE' AND account.category IN('EXPENSE','OTHER')))
                OR(component.component_kind IN('SERVICE_TAX','SETTLEMENT_TAX','SPLIT_TAX') AND NOT((account.account_type='EXPENSE' AND account.category IN('EXPENSE','OTHER')) OR(account.account_type='ASSET' AND account.category='OTHER')))
                OR(component.component_kind='ADJUSTMENT' AND NOT(account.account_type IN('EXPENSE','INCOME') OR(account.account_type='ASSET' AND account.category='OTHER') OR(account.account_type='LIABILITY' AND account.category IN('PAYABLE','OTHER')))))) THEN
            RAISE EXCEPTION 'settlement decision requires the current eligible reviewed component accounts';
        END IF;
        FOR source_value IN SELECT id FROM fee_gateway_settlement_sources WHERE root_id=preparation.root_id LOOP
            PERFORM assert_gateway_settlement_source(source_value.id,true);
        END LOOP;
    END IF;
END; $$;

CREATE FUNCTION guard_gateway_settlement_journal_reviewer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source_module='GATEWAY_SETTLEMENT' AND OLD.status='SUBMITTED' AND NEW.status IN('POSTED','REJECTED') THEN
        PERFORM assert_gateway_settlement_journal_decision(OLD.id,NEW.decided_by_membership_id,CASE WHEN NEW.status='POSTED' THEN 'POST' ELSE 'REJECT' END);
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER gateway_settlement_journal_reviewer BEFORE UPDATE ON accounting_journals FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_journal_reviewer();

CREATE FUNCTION guard_gateway_settlement_abandonment() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE root fee_gateway_settlement_roots%ROWTYPE;
BEGIN
    PERFORM gateway_settlement_finance_fences(NEW.school_id);
    PERFORM require_gateway_settlement_prepare_actor(NEW.organisation_id,NEW.school_id,NEW.abandoned_by_membership_id,NEW.abandoned_by_user_id);
    SELECT * INTO root FROM fee_gateway_settlement_roots WHERE id=NEW.root_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF root.id IS NULL OR NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_preparations WHERE root_id=root.id)
        OR EXISTS(SELECT 1 FROM fee_gateway_settlement_preparations preparation LEFT JOIN accounting_journals journal
            ON journal.id=preparation.journal_id AND journal.organisation_id=preparation.organisation_id AND journal.school_id=preparation.school_id
            WHERE preparation.root_id=root.id AND(journal.id IS NULL OR journal.status<>'REJECTED')) THEN
        RAISE EXCEPTION 'only an entirely rejected never-posted settlement root may release its reservations';
    END IF;
    NEW.abandoned_at:=clock_timestamp(); RETURN NEW;
END; $$;
CREATE TRIGGER gateway_settlement_abandonment_insert_guard BEFORE INSERT ON fee_gateway_settlement_abandonments FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_abandonment();

-- Source correction admission takes accounting only; never acquire finance from
-- these journal/reversal paths. The finance-first producer also owns accounting.
CREATE FUNCTION guard_consumed_gateway_settlement_source() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE journal_value UUID; payment_value UUID; school_value UUID; organisation_value UUID;
BEGIN
    organisation_value:=NEW.organisation_id; school_value:=NEW.school_id;
    IF TG_TABLE_NAME='fee_payments' THEN
        IF OLD.status<>'POSTED' OR NEW.status<>'REVERSED' OR OLD.gateway_capture_id IS NULL THEN RETURN NEW; END IF;
        payment_value:=OLD.id;
        PERFORM pg_advisory_xact_lock(hashtextextended(school_value::text || ':finance',0));
    ELSIF TG_TABLE_NAME='accounting_journals' THEN
        IF OLD.status<>'POSTED' OR NEW.status<>'REVERSED' THEN RETURN NEW; END IF;
        journal_value:=OLD.id;
    ELSE
        IF NEW.status NOT IN('PENDING','APPROVED') THEN RETURN NEW; END IF;
        journal_value:=NEW.accounting_journal_id;
    END IF;
    IF journal_value IS NOT NULL AND NOT EXISTS(SELECT 1 FROM fee_accounting_posting_batches batch
        WHERE batch.accounting_journal_id=journal_value AND batch.organisation_id=organisation_value AND batch.school_id=school_value
            AND((batch.event_type='REFUND' AND batch.refund_posting_id IS NOT NULL) OR(batch.event_type='PAYMENT' AND EXISTS(
                SELECT 1 FROM fee_accounting_posting_items item JOIN fee_payments payment ON payment.ledger_entry_id=item.fee_ledger_entry_id
                    AND payment.organisation_id=item.organisation_id AND payment.school_id=item.school_id
                WHERE item.posting_batch_id=batch.id AND item.organisation_id=batch.organisation_id AND item.school_id=batch.school_id
                    AND payment.gateway_capture_id IS NOT NULL)))) THEN RETURN NEW; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || school_value::text,0));
    IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'settlement source correction requires fresh read committed evidence' USING ERRCODE='40001'; END IF;
    IF EXISTS(SELECT 1 FROM fee_gateway_settlement_sources source WHERE source.organisation_id=organisation_value AND source.school_id=school_value
        AND((payment_value IS NOT NULL AND source.payment_id=payment_value) OR(journal_value IS NOT NULL AND source.source_journal_id=journal_value))
        AND NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_abandonments abandoned WHERE abandoned.root_id=source.root_id)) THEN
        RAISE EXCEPTION 'retained settlement preserves its economic source; use reviewed adjustment rather than source reversal' USING ERRCODE='23514',CONSTRAINT='gateway_settlement_source_anchored';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_payments_settlement_source_guard BEFORE UPDATE ON fee_payments FOR EACH ROW EXECUTE FUNCTION guard_consumed_gateway_settlement_source();
CREATE TRIGGER accounting_journals_settlement_source_guard BEFORE UPDATE ON accounting_journals FOR EACH ROW EXECUTE FUNCTION guard_consumed_gateway_settlement_source();
CREATE TRIGGER accounting_reversals_settlement_source_guard BEFORE INSERT OR UPDATE ON accounting_reversal_requests FOR EACH ROW EXECUTE FUNCTION guard_consumed_gateway_settlement_source();

CREATE OR REPLACE FUNCTION guard_gateway_settlement_bank_evidence_rejection() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE evidence fee_gateway_settlement_bank_evidence%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM require_gateway_bank_evidence_actor(NEW.organisation_id,NEW.school_id,NEW.rejected_by_membership_id,NEW.rejected_by_user_id,true);
    SELECT * INTO evidence FROM fee_gateway_settlement_bank_evidence WHERE id=NEW.bank_evidence_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF evidence.id IS NULL THEN RAISE EXCEPTION 'scoped bank evidence required'; END IF;
    IF evidence.recorded_by_user_id=NEW.rejected_by_user_id THEN RAISE EXCEPTION 'mistaken bank evidence needs a different actual reviewer' USING ERRCODE='23514',CONSTRAINT='gateway_bank_independent_rejection_required'; END IF;
    IF EXISTS(SELECT 1 FROM fee_gateway_settlement_roots root WHERE root.bank_evidence_id=evidence.id AND root.organisation_id=NEW.organisation_id AND root.school_id=NEW.school_id
        AND NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_abandonments abandoned WHERE abandoned.root_id=root.id)) THEN
        RAISE EXCEPTION 'consumed bank evidence cannot be rejected or released independently of its settlement' USING ERRCODE='23514',CONSTRAINT='gateway_settlement_bank_consumed';
    END IF;
    NEW.rejected_at:=clock_timestamp();
    IF NEW.rejected_at<evidence.recorded_at THEN RAISE EXCEPTION 'rejection cannot predate original evidence'; END IF;
    RETURN NEW;
END; $$;

DO $$ DECLARE relation_name TEXT; BEGIN
    FOREACH relation_name IN ARRAY ARRAY['fee_gateway_settlement_roots','fee_gateway_settlement_preparations','fee_gateway_settlement_sources',
        'fee_gateway_settlement_source_rows','fee_gateway_settlement_components','fee_gateway_settlement_abandonments'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation_name);
        EXECUTE format('CREATE POLICY %I ON %I FOR SELECT USING (organisation_id=current_app_organisation_id())',relation_name||'_select',relation_name);
        EXECUTE format('CREATE POLICY %I ON %I FOR INSERT WITH CHECK (organisation_id=current_app_organisation_id() AND current_app_user_id() IS NOT NULL)',relation_name||'_insert',relation_name);
        EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation()',relation_name||'_append_only',relation_name);
    END LOOP;
END $$;
COMMENT ON TABLE fee_gateway_settlement_roots IS 'Immutable retained bank/merchant/statement root. Posted or reversed history never releases bank/economic consumption; accounting repair remains linked to this same root.';
COMMENT ON TABLE fee_gateway_settlement_sources IS 'Economic PAYMENT or completed REFUND identities. Multiple refunds of one payment are separate sources; one common source journal can legitimately contain several payments.';
COMMENT ON TABLE fee_gateway_settlement_abandonments IS 'Append-only release only after every linked journal was independently REJECTED. No posted or reversed history can be abandoned. Original bank evidence still requires its independent mistaken-evidence rejection.';

-- The common reversal workflow predates actual-person settlement approval.
-- Extend only settlement corrections, including direct database decisions, so
-- changing memberships cannot turn a requester into an independent reviewer.
CREATE FUNCTION assert_gateway_settlement_reversal_decision(reversal_value UUID,membership_value UUID,decision_value TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE reversal accounting_reversal_requests%ROWTYPE; original accounting_journals%ROWTYPE; requester UUID;
BEGIN
    IF current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off'
        OR current_app_user_id() IS NULL OR decision_value IS NULL OR decision_value NOT IN('APPROVE','REJECT') THEN
        RAISE EXCEPTION 'settlement reversal decision requires live writable read committed human authority' USING ERRCODE='40001';
    END IF;
    SELECT * INTO reversal FROM accounting_reversal_requests WHERE id=reversal_value AND organisation_id=current_app_organisation_id();
    SELECT * INTO original FROM accounting_journals WHERE id=reversal.accounting_journal_id
        AND organisation_id=reversal.organisation_id AND school_id=reversal.school_id;
    IF reversal.id IS NULL OR original.id IS NULL OR original.source_module<>'GATEWAY_SETTLEMENT' THEN
        RAISE EXCEPTION 'scoped pending settlement reversal required';
    END IF;
    -- Accounting only: never acquire either finance fence from a reversal path.
    PERFORM pg_advisory_xact_lock(hashtextextended('accounting:school:' || original.school_id::text,0));
    SELECT * INTO reversal FROM accounting_reversal_requests WHERE id=reversal_value AND organisation_id=current_app_organisation_id();
    SELECT * INTO original FROM accounting_journals WHERE id=reversal.accounting_journal_id
        AND organisation_id=reversal.organisation_id AND school_id=reversal.school_id;
    SELECT user_id INTO requester FROM memberships WHERE id=reversal.requested_by_membership_id
        AND organisation_id=reversal.organisation_id AND school_id=reversal.school_id;
    IF reversal.id IS NULL OR original.id IS NULL OR original.source_module<>'GATEWAY_SETTLEMENT' OR reversal.status<>'PENDING'
        OR requester IS NULL OR requester=current_app_user_id() THEN
        RAISE EXCEPTION 'settlement reversal requires a different actual authorized reviewer'
            USING ERRCODE='23514',CONSTRAINT='gateway_settlement_independent_reversal_reviewer_required';
    END IF;
    PERFORM 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
        JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
        JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
        JOIN membership_roles assignment ON assignment.membership_id=member.id
        JOIN roles role ON role.id=assignment.role_id AND(role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
        JOIN role_capabilities capability ON capability.role_id=role.id AND capability.capability_code='accounting.reverse.approve'
        WHERE member.id=membership_value AND member.user_id=current_app_user_id() AND member.organisation_id=reversal.organisation_id
            AND member.school_id=reversal.school_id AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
            AND(member.valid_until IS NULL OR member.valid_until>clock_timestamp()) FOR SHARE OF member,actor,school,assignment;
    IF NOT FOUND THEN RAISE EXCEPTION 'settlement reversal requires live school reversal approval authority'; END IF;
    IF decision_value='APPROVE' THEN
        -- A rejected request must remain rejectable after its day/book closes.
        -- Approval, however, cannot backdate or schedule a second bank movement,
        -- translate the original amount into another currency/basis, or bypass
        -- the fee close serialized by the accounting fence above.
        PERFORM 1 FROM accounting_financial_years original_year
            JOIN accounting_periods target_period ON target_period.id=reversal.requested_accounting_period_id
                AND target_period.organisation_id=original_year.organisation_id AND target_period.school_id=original_year.school_id
            JOIN accounting_financial_years target_year ON target_year.id=target_period.financial_year_id
                AND target_year.organisation_id=target_period.organisation_id AND target_year.school_id=target_period.school_id
            JOIN schools school ON school.id=original_year.school_id AND school.organisation_id=original_year.organisation_id AND school.status='ACTIVE'
            WHERE original_year.id=original.financial_year_id AND original_year.organisation_id=reversal.organisation_id
                AND original_year.school_id=reversal.school_id AND original.status='POSTED' AND original.reversal_of_journal_id IS NULL
                AND target_year.status='OPEN' AND target_period.status='OPEN'
                AND target_year.accounting_basis=original_year.accounting_basis AND target_year.currency_code=original_year.currency_code
                AND reversal.requested_posting_date BETWEEN target_year.starts_on AND target_year.ends_on
                AND reversal.requested_posting_date BETWEEN target_period.starts_on AND target_period.ends_on
                AND reversal.requested_posting_date>=original.posting_date
                AND reversal.requested_posting_date<=(clock_timestamp() AT TIME ZONE school.time_zone)::date
                AND NOT EXISTS(SELECT 1 FROM fee_day_closes closed WHERE closed.organisation_id=reversal.organisation_id
                    AND closed.school_id=reversal.school_id AND closed.status='APPROVED' AND closed.business_date>=reversal.requested_posting_date)
            FOR SHARE OF original_year,target_period,target_year,school;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'settlement reversal requires a compatible open book and eligible reviewed open date'
                USING ERRCODE='23514',CONSTRAINT='gateway_settlement_reversal_target_ineligible';
        END IF;
    END IF;
END; $$;

CREATE FUNCTION guard_gateway_settlement_reversal_reviewer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='PENDING' AND NEW.status IN('APPROVED','REJECTED') AND EXISTS(
        SELECT 1 FROM accounting_journals original WHERE original.id=OLD.accounting_journal_id
            AND original.organisation_id=OLD.organisation_id AND original.school_id=OLD.school_id
            AND original.source_module='GATEWAY_SETTLEMENT') THEN
        PERFORM assert_gateway_settlement_reversal_decision(OLD.id,NEW.decided_by_membership_id,
            CASE WHEN NEW.status='APPROVED' THEN 'APPROVE' ELSE 'REJECT' END);
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER accounting_reversals_settlement_reviewer BEFORE UPDATE ON accounting_reversal_requests
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_reversal_reviewer();

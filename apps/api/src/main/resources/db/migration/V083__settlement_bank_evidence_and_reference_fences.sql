-- Human bank-entry evidence only. No provider activation, receipt, fee credit,
-- refund, bank balance or journal is created. Historic V070/V081/V082 is unchanged.
CREATE TABLE fee_gateway_settlement_bank_evidence (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    request_id UUID NOT NULL, source_summary_read_id UUID NOT NULL,
    source_summary_row_index INTEGER NOT NULL CHECK(source_summary_row_index BETWEEN 0 AND 9),
    settlement_reference VARCHAR(100) NOT NULL,
    bank_account_id UUID NOT NULL, currency_code CHAR(3) NOT NULL CHECK(currency_code='INR'),
    credited_amount NUMERIC NOT NULL CHECK(credited_amount>0 AND credited_amount<=100000000.00 AND credited_amount=trunc(credited_amount,2)),
    actual_credited_at TIMESTAMPTZ NOT NULL CHECK(isfinite(actual_credited_at)),
    actual_credited_at_exact VARCHAR(30) NOT NULL,
    credited_on DATE NOT NULL CHECK(credited_on>=DATE '0001-01-01' AND credited_on<=DATE '9999-12-31'),
    school_time_zone VARCHAR(60) NOT NULL,
    verification_kind VARCHAR(30) NOT NULL CHECK(verification_kind IN ('BANK_APP_CREDIT','BANK_STATEMENT_CREDIT')),
    bank_credit_reference VARCHAR(120) NOT NULL,
    normalized_bank_reference VARCHAR(120) NOT NULL,
    confirmed_received BOOLEAN NOT NULL CHECK(confirmed_received),
    reason VARCHAR(240) NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 240 AND reason !~ '[[:cntrl:]]'),
    client_request_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
    recorded_by_user_id UUID NOT NULL REFERENCES user_accounts(id),
    recorded_by_membership_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(recorded_at)),
    UNIQUE(id,organisation_id,school_id),
    UNIQUE(school_id,recorded_by_user_id,client_request_id),
    FOREIGN KEY(request_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_requests(id,organisation_id,school_id),
    FOREIGN KEY(source_summary_read_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_reads(id,organisation_id,school_id),
    FOREIGN KEY(bank_account_id,organisation_id,school_id) REFERENCES accounting_accounts(id,organisation_id,school_id),
    FOREIGN KEY(recorded_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    CHECK(settlement_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'),
    CHECK(bank_credit_reference ~ '^[A-Za-z0-9][A-Za-z0-9/_.: \t-]{0,119}$'
        AND bank_credit_reference !~ '^[A-Za-z][A-Za-z0-9+.-]*://'
        AND normalized_bank_reference ~ '^[A-Z0-9][A-Z0-9/_.:-]{0,119}$'
        AND normalized_bank_reference=normalize_manual_bank_reference(bank_credit_reference)),
    CHECK(actual_credited_at=gateway_instant_floor_us(actual_credited_at_exact)
        AND credited_on=gateway_refund_school_date(actual_credited_at_exact,school_time_zone))
);
CREATE INDEX gateway_bank_evidence_scope_history ON fee_gateway_settlement_bank_evidence(organisation_id,school_id,request_id,recorded_at DESC,id);
-- Active reservation is derived from append-only independent rejections, never
-- by mutating the original bank observation or deleting an obsolete claim.
CREATE INDEX gateway_bank_evidence_reference ON fee_gateway_settlement_bank_evidence(organisation_id,school_id,normalized_bank_reference);

CREATE TABLE fee_gateway_settlement_bank_evidence_rejections (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    bank_evidence_id UUID NOT NULL UNIQUE,
    client_request_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
    confirmed_mistaken_evidence BOOLEAN NOT NULL CHECK(confirmed_mistaken_evidence),
    reason VARCHAR(240) NOT NULL CHECK(length(btrim(reason)) BETWEEN 3 AND 240 AND reason !~ '[[:cntrl:]]'),
    rejected_by_user_id UUID NOT NULL REFERENCES user_accounts(id),
    rejected_by_membership_id UUID NOT NULL,
    rejected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(rejected_at)),
    UNIQUE(id,organisation_id,school_id), UNIQUE(school_id,rejected_by_user_id,client_request_id),
    FOREIGN KEY(bank_evidence_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_bank_evidence(id,organisation_id,school_id),
    FOREIGN KEY(rejected_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id)
);

CREATE FUNCTION require_gateway_bank_evidence_actor(organisation_value UUID,school_value UUID,
    membership_value UUID,user_value UUID,decision_value BOOLEAN)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM organisation_value
        OR current_app_user_id() IS DISTINCT FROM user_value OR user_value IS NULL
        OR current_setting('transaction_isolation')<>'read committed'
        OR current_setting('transaction_read_only')<>'off' THEN
        RAISE EXCEPTION 'bank evidence requires the live human writable read-committed scope'
            USING ERRCODE='23514',CONSTRAINT='gateway_bank_actor_required';
    END IF;
    PERFORM 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id
        JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id
        WHERE member.id=membership_value AND member.user_id=user_value
            AND member.organisation_id=organisation_value AND member.school_id=school_value
        FOR SHARE OF member,actor,school;
    IF NOT FOUND THEN RAISE EXCEPTION 'bank evidence actor unavailable'; END IF;
    PERFORM 1 FROM membership_roles assignment WHERE assignment.membership_id=membership_value FOR SHARE;
    -- Fresh authority after any membership/assignment/finance lock waits.
    PERFORM 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
        JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
        JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
        WHERE member.id=membership_value AND member.user_id=user_value
            AND member.organisation_id=organisation_value AND member.school_id=school_value
            AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
            AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
            AND NOT EXISTS(SELECT 1 FROM unnest(ARRAY['fees.read','accounting.read',
                    CASE WHEN decision_value THEN 'fees.close.approve' ELSE 'fees.close.propose' END]) required(capability)
                WHERE NOT EXISTS(SELECT 1 FROM membership_roles assignment
                    JOIN roles role ON role.id=assignment.role_id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                    JOIN role_capabilities grant_value ON grant_value.role_id=role.id AND grant_value.capability_code=required.capability
                    WHERE assignment.membership_id=member.id));
    IF NOT FOUND THEN RAISE EXCEPTION 'bank evidence live school authority unavailable'; END IF;
END; $$;

-- Accounting-owned validation; operational Java uses the accounting bridge, not
-- accounting repositories/tables. This is account identity, NOT journal admission.
CREATE FUNCTION accounting_validate_gateway_bank_evidence_account(organisation_value UUID,school_value UUID,
    bank_value UUID,currency_value TEXT,zone_value TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE today_value DATE; now_value TIMESTAMPTZ:=clock_timestamp(); years INTEGER;
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM organisation_value OR currency_value IS DISTINCT FROM 'INR' THEN
        RAISE EXCEPTION 'scoped INR bank evidence account required';
    END IF;
    today_value:=gateway_refund_school_date(to_char(now_value AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"'),zone_value);
    PERFORM 1 FROM accounting_accounts account WHERE account.id=bank_value AND account.organisation_id=organisation_value
        AND account.school_id=school_value AND account.status='ACTIVE' AND account.account_type='ASSET'
        AND account.category='BANK' AND account.allow_direct_posting FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'active scoped bank account required'; END IF;
    PERFORM 1 FROM accounting_financial_years year WHERE year.organisation_id=organisation_value AND year.school_id=school_value
        AND year.status='OPEN' AND year.starts_on<=today_value AND year.ends_on>=today_value FOR SHARE;
    SELECT count(*) INTO years FROM accounting_financial_years year
        WHERE year.organisation_id=organisation_value AND year.school_id=school_value AND year.status='OPEN'
            AND year.starts_on<=today_value AND year.ends_on>=today_value;
    IF years<>1 OR NOT EXISTS(SELECT 1 FROM accounting_financial_years year WHERE year.organisation_id=organisation_value
        AND year.school_id=school_value AND year.status='OPEN' AND year.starts_on<=today_value
        AND year.ends_on>=today_value AND year.currency_code=currency_value) THEN
        RAISE EXCEPTION 'one current scoped INR book is required for bank identity';
    END IF;
END; $$;

CREATE FUNCTION require_gateway_bank_evidence_report(organisation_value UUID,school_value UUID,request_value UUID,
    summary_read_value UUID,row_value INTEGER,statement_value TEXT,zone_value TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE request fee_gateway_settlement_requests%ROWTYPE; witness fee_gateway_settlement_reads%ROWTYPE;
    source fee_gateway_settlement_queries%ROWTYPE; work_count INTEGER; chain_count INTEGER; terminals INTEGER;
BEGIN
    SELECT * INTO request FROM fee_gateway_settlement_requests
        WHERE id=request_value AND organisation_id=organisation_value AND school_id=school_value;
    IF request.id IS NULL OR request.query_kind<>'EVENTS'
        OR request.source_summary_read_id IS DISTINCT FROM summary_read_value
        OR request.settlement_reference IS DISTINCT FROM statement_value
        OR request.school_time_zone IS DISTINCT FROM zone_value THEN
        RAISE EXCEPTION 'bank evidence requires exact retained report identity';
    END IF;
    SELECT read_value.* INTO witness FROM fee_gateway_settlement_reads read_value
        JOIN fee_gateway_settlement_selections selected ON selected.read_id=read_value.id AND selected.query_id=read_value.query_id
            AND selected.organisation_id=read_value.organisation_id AND selected.school_id=read_value.school_id
            AND selected.outcome IN ('COMPLETE','REVIEW_REQUIRED')
        WHERE read_value.id=summary_read_value AND read_value.organisation_id=organisation_value
            AND read_value.school_id=school_value AND read_value.outcome='COMPLETE';
    SELECT * INTO source FROM fee_gateway_settlement_queries WHERE id=witness.query_id
        AND organisation_id=organisation_value AND school_id=school_value AND query_kind='SUMMARY';
    IF source.id IS NULL OR source.payment_provider_config_id IS DISTINCT FROM request.payment_provider_config_id
        OR source.provider_code IS DISTINCT FROM request.provider_code OR source.provider_environment IS DISTINCT FROM request.provider_environment
        OR source.merchant_account_reference IS DISTINCT FROM request.merchant_account_reference
        OR source.credentials_secret_reference IS DISTINCT FROM request.credentials_secret_reference
        OR row_value NOT BETWEEN 0 AND 9 OR (witness.facts_canonical::jsonb->row_value->>'settlementReference') IS DISTINCT FROM statement_value
        OR (SELECT count(*) FROM jsonb_array_elements(witness.facts_canonical::jsonb) records(item)
            WHERE item->>'settlementReference'=statement_value)<>1 THEN
        RAISE EXCEPTION 'bank evidence requires one exact selected canonical summary row';
    END IF;
    SELECT count(*) INTO work_count FROM fee_gateway_settlement_work work
        WHERE work.request_id=request_value AND work.organisation_id=organisation_value AND work.school_id=school_value;
    IF work_count NOT BETWEEN 1 AND 1000 OR EXISTS(SELECT 1 FROM fee_gateway_settlement_work work
        WHERE work.request_id=request_value AND work.organisation_id=organisation_value AND work.school_id=school_value
            AND work.status<>'COMPLETE') THEN
        RAISE EXCEPTION 'complete selected event chain required';
    END IF;
    WITH RECURSIVE pages AS (
        SELECT query.id,query.page_number,query.parent_read_id,query.cursor_sha256,query.prepared_at,
            read_value.id read_id,read_value.next_cursor_sha256,read_value.observed_at
        FROM fee_gateway_settlement_work work
        JOIN fee_gateway_settlement_selections selected ON selected.query_id=work.id AND selected.request_id=work.request_id
            AND selected.organisation_id=work.organisation_id AND selected.school_id=work.school_id AND selected.outcome='COMPLETE'
        JOIN fee_gateway_settlement_queries query ON query.id=selected.query_id
            AND query.organisation_id=work.organisation_id AND query.school_id=work.school_id
        JOIN fee_gateway_settlement_reads read_value ON read_value.id=selected.read_id AND read_value.query_id=query.id
            AND read_value.organisation_id=work.organisation_id AND read_value.school_id=work.school_id AND read_value.outcome='COMPLETE'
        WHERE work.request_id=request_value AND work.organisation_id=organisation_value AND work.school_id=school_value
            AND query.root_query_id=request_value AND query.query_kind='EVENTS'
            AND query.summary_read_id=summary_read_value AND query.settlement_reference=statement_value
            AND query.payment_provider_config_id=request.payment_provider_config_id
            AND query.provider_code=request.provider_code AND query.provider_environment=request.provider_environment
            AND query.merchant_account_reference=request.merchant_account_reference
            AND query.credentials_secret_reference=request.credentials_secret_reference
    ), chain AS (
        SELECT * FROM pages WHERE id=request_value AND page_number=1 AND parent_read_id IS NULL AND cursor_sha256 IS NULL
        UNION ALL
        SELECT child.* FROM chain parent JOIN pages child ON child.parent_read_id=parent.read_id
            AND child.page_number=parent.page_number+1 AND child.cursor_sha256=parent.next_cursor_sha256
            AND child.prepared_at>=parent.observed_at WHERE parent.page_number<1000
    )
    SELECT count(*),count(*) FILTER(WHERE next_cursor_sha256 IS NULL) INTO chain_count,terminals FROM chain;
    IF chain_count<>work_count OR terminals<>1 THEN RAISE EXCEPTION 'bank evidence requires complete unforked selected event chain'; END IF;
END; $$;

CREATE FUNCTION guard_gateway_settlement_bank_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE now_value TIMESTAMPTZ; configured_zone TEXT;
BEGIN
    -- Same fence as V070 manual collection, never a parallel reference namespace.
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM require_gateway_bank_evidence_actor(NEW.organisation_id,NEW.school_id,NEW.recorded_by_membership_id,NEW.recorded_by_user_id,false);
    SELECT time_zone INTO configured_zone FROM schools WHERE id=NEW.school_id AND organisation_id=NEW.organisation_id;
    IF configured_zone IS DISTINCT FROM NEW.school_time_zone THEN RAISE EXCEPTION 'refresh the changed school time zone'; END IF;
    PERFORM require_gateway_bank_evidence_report(NEW.organisation_id,NEW.school_id,NEW.request_id,
        NEW.source_summary_read_id,NEW.source_summary_row_index,NEW.settlement_reference,NEW.school_time_zone);
    PERFORM accounting_validate_gateway_bank_evidence_account(NEW.organisation_id,NEW.school_id,NEW.bank_account_id,NEW.currency_code,NEW.school_time_zone);
    now_value:=clock_timestamp();
    NEW.recorded_at:=now_value;
    IF gateway_instant_epoch_ns(NEW.actual_credited_at_exact)>extract(epoch FROM now_value)*1000000000 THEN
        RAISE EXCEPTION 'actual bank credit must be a known nonfuture instant';
    END IF;
    IF EXISTS(SELECT 1 FROM fee_payments payment WHERE payment.organisation_id=NEW.organisation_id AND payment.school_id=NEW.school_id
        AND payment.gateway_capture_id IS NULL AND payment.payment_method IN ('BANK_TRANSFER','SCHOOL_UPI')
        AND normalize_manual_bank_reference(payment.external_reference)=NEW.normalized_bank_reference) THEN
        RAISE EXCEPTION 'bank credit already appears in manual receipt history'
            USING ERRCODE='23514',CONSTRAINT='gateway_bank_reference_already_collected';
    END IF;
    IF EXISTS(SELECT 1 FROM fee_gateway_settlement_bank_evidence previous
        WHERE previous.organisation_id=NEW.organisation_id AND previous.school_id=NEW.school_id
            AND previous.normalized_bank_reference=NEW.normalized_bank_reference
            AND NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_bank_evidence_rejections rejected
                WHERE rejected.bank_evidence_id=previous.id AND rejected.organisation_id=previous.organisation_id AND rejected.school_id=previous.school_id)) THEN
        RAISE EXCEPTION 'bank reference already reserved for settlement review'
            USING ERRCODE='23514',CONSTRAINT='gateway_bank_reference_reserved';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER gateway_bank_evidence_insert_guard BEFORE INSERT ON fee_gateway_settlement_bank_evidence
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_bank_evidence();

CREATE FUNCTION guard_gateway_settlement_bank_evidence_rejection() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE evidence fee_gateway_settlement_bank_evidence%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM require_gateway_bank_evidence_actor(NEW.organisation_id,NEW.school_id,NEW.rejected_by_membership_id,NEW.rejected_by_user_id,true);
    SELECT * INTO evidence FROM fee_gateway_settlement_bank_evidence WHERE id=NEW.bank_evidence_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF evidence.id IS NULL THEN RAISE EXCEPTION 'scoped bank evidence required'; END IF;
    IF evidence.recorded_by_user_id=NEW.rejected_by_user_id THEN
        RAISE EXCEPTION 'mistaken bank evidence needs a different actual reviewer'
            USING ERRCODE='23514',CONSTRAINT='gateway_bank_independent_rejection_required';
    END IF;
    NEW.rejected_at:=clock_timestamp();
    IF NEW.rejected_at<evidence.recorded_at THEN RAISE EXCEPTION 'rejection cannot predate original evidence'; END IF;
    -- There is deliberately no settlement journal/source consumer in V083.
    -- Future monetary integration MUST add a consumed-source exclusion to this
    -- guard before connecting any settlement preparation/posting path.
    RETURN NEW;
END; $$;
CREATE TRIGGER gateway_bank_evidence_reject_guard BEFORE INSERT ON fee_gateway_settlement_bank_evidence_rejections
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_bank_evidence_rejection();

CREATE FUNCTION fence_manual_collection_settlement_reference() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE reference_value TEXT;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    IF current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
        RAISE EXCEPTION 'manual bank identity requires fresh writable read-committed evidence'
            USING ERRCODE='23514',CONSTRAINT='gateway_bank_reference_fresh_snapshot';
    END IF;
    reference_value:=normalize_manual_bank_reference(NEW.external_reference);
    IF reference_value IS NOT NULL AND EXISTS(SELECT 1 FROM fee_gateway_settlement_bank_evidence evidence
        WHERE evidence.organisation_id=NEW.organisation_id AND evidence.school_id=NEW.school_id
            AND evidence.normalized_bank_reference=reference_value
            AND NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_bank_evidence_rejections rejected
                WHERE rejected.bank_evidence_id=evidence.id AND rejected.organisation_id=evidence.organisation_id AND rejected.school_id=evidence.school_id)) THEN
        RAISE EXCEPTION 'bank reference already reserved for settlement review'
            USING ERRCODE='23514',CONSTRAINT='gateway_bank_reference_reserved';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_payments_settlement_bank_reference_guard BEFORE INSERT ON fee_payments
    FOR EACH ROW WHEN (NEW.gateway_capture_id IS NULL AND NEW.payment_method IN ('BANK_TRANSFER','SCHOOL_UPI'))
    EXECUTE FUNCTION fence_manual_collection_settlement_reference();

CREATE TRIGGER gateway_bank_evidence_append_only BEFORE UPDATE OR DELETE ON fee_gateway_settlement_bank_evidence
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
CREATE TRIGGER gateway_bank_rejections_append_only BEFORE UPDATE OR DELETE ON fee_gateway_settlement_bank_evidence_rejections
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
DO $$ DECLARE relation_name TEXT; BEGIN
    FOREACH relation_name IN ARRAY ARRAY['fee_gateway_settlement_bank_evidence','fee_gateway_settlement_bank_evidence_rejections'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation_name);
        EXECUTE format('CREATE POLICY %I ON %I FOR SELECT USING (organisation_id=current_app_organisation_id())',relation_name||'_select',relation_name);
        EXECUTE format('CREATE POLICY %I ON %I FOR INSERT WITH CHECK (organisation_id=current_app_organisation_id() AND current_app_user_id() IS NOT NULL)',relation_name||'_insert',relation_name);
    END LOOP;
END $$;
COMMENT ON TABLE fee_gateway_settlement_bank_evidence IS 'Append-only actual school bank-entry attestation and active reference reservation, not source reconciliation, cash/fee receipt or common-ledger posting. Original evidence survives independent mistaken-evidence rejection.';
COMMENT ON TABLE fee_gateway_settlement_bank_evidence_rejections IS 'One immutable different-human rejection of unposted mistaken bank evidence. Releases only its logical reference reservation, not manual receipt history, financial money or posted journal source. Future monetary integration must fence consumed sources before activation.';

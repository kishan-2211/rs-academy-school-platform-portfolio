-- Report provenance only: no sync activation, bank credit, fee mutation or journal.
CREATE TABLE fee_gateway_settlement_queries (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK(provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL CHECK(merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$'),
    credentials_secret_reference VARCHAR(240) NOT NULL CHECK(credentials_secret_reference ~ '^[A-Za-z][A-Za-z0-9_./:-]{2,239}$'),
    query_kind VARCHAR(10) NOT NULL CHECK(query_kind IN ('SUMMARY','EVENTS')),
    root_query_id UUID NOT NULL, page_number INTEGER NOT NULL CHECK(page_number BETWEEN 1 AND 1000),
    parent_read_id UUID, summary_read_id UUID,
    from_exact VARCHAR(30), until_exact VARCHAR(30), settlement_reference VARCHAR(100),
    cursor_sha256 CHAR(64), cursor_ciphertext VARCHAR(5483), cursor_nonce CHAR(16),
    prepared_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(prepared_at)),
    UNIQUE(id,organisation_id,school_id), UNIQUE(root_query_id,page_number),
    FOREIGN KEY(payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    FOREIGN KEY(root_query_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_queries(id,organisation_id,school_id),
    CHECK((query_kind='SUMMARY' AND from_exact IS NOT NULL AND until_exact IS NOT NULL AND settlement_reference IS NULL AND summary_read_id IS NULL)
        OR (query_kind='EVENTS' AND from_exact IS NULL AND until_exact IS NULL AND settlement_reference IS NOT NULL
            AND settlement_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$' AND summary_read_id IS NOT NULL)),
    CHECK((page_number=1 AND root_query_id=id AND parent_read_id IS NULL AND cursor_sha256 IS NULL AND cursor_ciphertext IS NULL AND cursor_nonce IS NULL)
        OR (page_number>1 AND root_query_id<>id AND parent_read_id IS NOT NULL AND cursor_sha256 IS NOT NULL
            AND cursor_sha256 ~ '^[a-f0-9]{64}$' AND cursor_ciphertext IS NOT NULL AND length(cursor_ciphertext) BETWEEN 23 AND 5483
            AND cursor_ciphertext ~ '^[A-Za-z0-9_-]+$' AND cursor_nonce IS NOT NULL AND cursor_nonce ~ '^[A-Za-z0-9_-]{16}$'))
);

CREATE TABLE fee_gateway_settlement_reads (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, query_id UUID NOT NULL,
    outcome VARCHAR(10) NOT NULL CHECK(outcome IN ('UNKNOWN','COMPLETE')),
    -- Exact application-canonical minimal facts, NOT a raw body. Never rehash jsonb::text.
    facts_canonical TEXT CHECK(octet_length(facts_canonical)<=262144),
    evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[a-f0-9]{64}$'),
    next_cursor_sha256 CHAR(64), next_cursor_ciphertext VARCHAR(5483), next_cursor_nonce CHAR(16),
    observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(observed_at)),
    UNIQUE(id,organisation_id,school_id),
    FOREIGN KEY(query_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_queries(id,organisation_id,school_id),
    CHECK((outcome='UNKNOWN' AND facts_canonical IS NULL AND next_cursor_sha256 IS NULL AND next_cursor_ciphertext IS NULL AND next_cursor_nonce IS NULL)
        OR (outcome='COMPLETE' AND facts_canonical IS NOT NULL)),
    CHECK((next_cursor_sha256 IS NULL AND next_cursor_ciphertext IS NULL AND next_cursor_nonce IS NULL)
        OR (next_cursor_sha256 IS NOT NULL AND next_cursor_sha256 ~ '^[a-f0-9]{64}$'
            AND next_cursor_ciphertext IS NOT NULL AND length(next_cursor_ciphertext) BETWEEN 23 AND 5483
            AND next_cursor_ciphertext ~ '^[A-Za-z0-9_-]+$' AND next_cursor_nonce IS NOT NULL AND next_cursor_nonce ~ '^[A-Za-z0-9_-]{16}$'))
);
ALTER TABLE fee_gateway_settlement_queries ADD FOREIGN KEY(parent_read_id,organisation_id,school_id)
    REFERENCES fee_gateway_settlement_reads(id,organisation_id,school_id);
ALTER TABLE fee_gateway_settlement_queries ADD FOREIGN KEY(summary_read_id,organisation_id,school_id)
    REFERENCES fee_gateway_settlement_reads(id,organisation_id,school_id);
CREATE INDEX fee_gateway_settlement_queries_scope_time ON fee_gateway_settlement_queries(organisation_id,school_id,payment_provider_config_id,prepared_at DESC,id);
CREATE INDEX fee_gateway_settlement_queries_parent ON fee_gateway_settlement_queries(parent_read_id) WHERE parent_read_id IS NOT NULL;
CREATE INDEX fee_gateway_settlement_queries_summary ON fee_gateway_settlement_queries(summary_read_id) WHERE summary_read_id IS NOT NULL;
CREATE INDEX fee_gateway_settlement_reads_query_time ON fee_gateway_settlement_reads(query_id,observed_at DESC,id);

CREATE FUNCTION gateway_settlement_facts_valid(kind_value TEXT,canonical_value TEXT,reference_value TEXT,observed_value TIMESTAMPTZ)
RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE rows JSONB; item JSONB; key_value TEXT; value JSONB; text_value TEXT; epoch_value NUMERIC;
    allowed TEXT[]; keys TEXT[]; required TEXT[];
BEGIN
    IF NOT (canonical_value IS JSON ARRAY WITH UNIQUE KEYS) THEN RETURN false; END IF;
    rows:=canonical_value::jsonb;
    IF jsonb_typeof(rows)<>'array' OR jsonb_array_length(rows)>10 THEN RETURN false; END IF;
    IF kind_value='SUMMARY' THEN
        allowed:=ARRAY['settlementReference','reportedStatus','transferReference','paymentsFrom','paymentsThrough','initiatedAt','processedAt',
            'reportedPaymentAmount','reportedServiceCharge','reportedServiceTax','reportedAdjustment','reportedSettlementCharge','reportedSettlementTax',
            'reportedSplitCharge','reportedSplitTax','reportedVendorCommission','reportedAmountSettled','reportedChargeType','reportedSettlementType'];
        required:=ARRAY['settlementReference','reportedStatus'];
    ELSIF kind_value='EVENTS' THEN
        allowed:=ARRAY['settlementReference','eventReference','reportedType','reportedDirection','reportedStatus','currencyCode','reportedAmount',
            'reportedServiceCharge','reportedServiceTax','reportedSettlementAmount','occurredAt','orderReference','paymentReference','refundReferences','chargesCurrencyCode'];
        required:=ARRAY['settlementReference','eventReference','reportedType','reportedDirection','reportedStatus','currencyCode','reportedAmount','occurredAt','refundReferences'];
    ELSE RETURN false; END IF;
    FOR item IN SELECT element FROM jsonb_array_elements(rows) AS entries(element) LOOP
        IF jsonb_typeof(item)<>'object' THEN RETURN false; END IF;
        SELECT array_agg(name ORDER BY name) INTO keys FROM jsonb_object_keys(item) AS names(name);
        IF NOT (keys @> allowed AND keys <@ allowed) THEN RETURN false; END IF;
        FOREACH key_value IN ARRAY required LOOP
            IF item->key_value='null'::jsonb OR item->key_value IS NULL THEN RETURN false; END IF;
        END LOOP;
        FOR key_value,value IN SELECT * FROM jsonb_each(item) LOOP
            IF value='null'::jsonb THEN CONTINUE; END IF;
            IF key_value='refundReferences' THEN
                IF jsonb_typeof(value)<>'array' OR jsonb_array_length(value)>100 THEN RETURN false; END IF;
                IF EXISTS(SELECT 1 FROM jsonb_array_elements(value) AS refs(ref)
                    WHERE jsonb_typeof(ref)<>'string' OR (ref#>>'{}') !~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$') THEN RETURN false; END IF;
                CONTINUE;
            END IF;
            IF jsonb_typeof(value)<>'string' THEN RETURN false; END IF;
            text_value:=value#>>'{}';
            IF key_value IN ('reportedPaymentAmount','reportedServiceCharge','reportedServiceTax','reportedAdjustment','reportedSettlementCharge',
                    'reportedSettlementTax','reportedSplitCharge','reportedSplitTax','reportedVendorCommission','reportedAmountSettled','reportedAmount','reportedSettlementAmount') THEN
                IF text_value !~ '^-?(0|[1-9][0-9]{0,8})(\.[0-9]{1,8})?$' OR abs(text_value::numeric)>100000000 THEN RETURN false; END IF;
            ELSIF key_value IN ('paymentsFrom','paymentsThrough','initiatedAt','processedAt','occurredAt') THEN
                epoch_value:=gateway_instant_epoch_ns(text_value);
                IF epoch_value<946684800000000000 OR epoch_value>4102444800000000000
                    OR (key_value IN ('initiatedAt','processedAt','occurredAt') AND epoch_value>extract(epoch FROM observed_value)*1000000000) THEN RETURN false; END IF;
            ELSIF key_value IN ('settlementReference','eventReference','orderReference','paymentReference') THEN
                IF text_value !~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$' THEN RETURN false; END IF;
            ELSIF key_value='transferReference' THEN
                IF text_value !~ '^[A-Za-z0-9][A-Za-z0-9_.:/\-]{0,119}$' THEN RETURN false; END IF;
            ELSIF key_value IN ('currencyCode','chargesCurrencyCode') THEN
                IF text_value !~ '^[A-Z]{3}$' THEN RETURN false; END IF;
            ELSE
                IF text_value !~ '^[A-Z][A-Z0-9_]{0,59}$' THEN RETURN false; END IF;
            END IF;
        END LOOP;
        IF kind_value='EVENTS' AND item->>'settlementReference' IS DISTINCT FROM reference_value THEN RETURN false; END IF;
        IF kind_value='SUMMARY' AND item->>'paymentsFrom' IS NOT NULL AND item->>'paymentsThrough' IS NOT NULL
            AND gateway_instant_epoch_ns(item->>'paymentsFrom')>gateway_instant_epoch_ns(item->>'paymentsThrough') THEN RETURN false; END IF;
    END LOOP;
    RETURN true;
EXCEPTION WHEN OTHERS THEN RETURN false;
END; $$;

CREATE FUNCTION guard_gateway_settlement_query() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE previous fee_gateway_settlement_reads%ROWTYPE; parent fee_gateway_settlement_queries%ROWTYPE;
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id OR current_app_user_id() IS NOT NULL THEN
        RAISE EXCEPTION 'settlement preparation requires actual matching background scope';
    END IF;
    PERFORM 1 FROM payment_provider_configs provider
        JOIN schools school ON school.id=provider.school_id AND school.organisation_id=provider.organisation_id AND school.status='ACTIVE'
        JOIN organisations organisation ON organisation.id=provider.organisation_id AND organisation.status='ACTIVE'
        WHERE provider.id=NEW.payment_provider_config_id AND provider.organisation_id=NEW.organisation_id AND provider.school_id=NEW.school_id
            AND provider.provider_code=NEW.provider_code AND provider.environment=NEW.provider_environment
            AND provider.merchant_account_reference=NEW.merchant_account_reference AND provider.credentials_secret_reference=NEW.credentials_secret_reference
            AND provider.status='ACTIVE'
        -- Organisations are deliberately SELECT-only for the runtime role.
        -- Locking them would demand UPDATE privilege; do not broaden that grant.
        FOR SHARE OF provider,school;
    IF NOT FOUND THEN RAISE EXCEPTION 'settlement query requires exact current active school provider binding'; END IF;
    NEW.prepared_at:=clock_timestamp();
    IF NEW.query_kind='SUMMARY' THEN
        IF gateway_instant_epoch_ns(NEW.from_exact)<946684800000000000 OR gateway_instant_epoch_ns(NEW.until_exact)>4102444800000000000
            OR gateway_instant_epoch_ns(NEW.until_exact)>extract(epoch FROM NEW.prepared_at)*1000000000
            OR gateway_instant_epoch_ns(NEW.until_exact)<=gateway_instant_epoch_ns(NEW.from_exact)
            OR gateway_instant_epoch_ns(NEW.until_exact)-gateway_instant_epoch_ns(NEW.from_exact)>2678400000000000 THEN
            RAISE EXCEPTION 'settlement summary requires past bounded exact 31-day window';
        END IF;
    ELSE
        SELECT * INTO previous FROM fee_gateway_settlement_reads WHERE id=NEW.summary_read_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id AND outcome='COMPLETE';
        SELECT * INTO parent FROM fee_gateway_settlement_queries WHERE id=previous.query_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id AND query_kind='SUMMARY';
        IF parent.id IS NULL OR parent.payment_provider_config_id IS DISTINCT FROM NEW.payment_provider_config_id
            OR parent.provider_code IS DISTINCT FROM NEW.provider_code OR parent.provider_environment IS DISTINCT FROM NEW.provider_environment
            OR parent.merchant_account_reference IS DISTINCT FROM NEW.merchant_account_reference OR parent.credentials_secret_reference IS DISTINCT FROM NEW.credentials_secret_reference
            OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(previous.facts_canonical::jsonb) AS rows(item)
                WHERE item->>'settlementReference'=NEW.settlement_reference) THEN
            RAISE EXCEPTION 'settlement events require the actual scoped observed summary reference';
        END IF;
    END IF;
    IF NEW.page_number>1 THEN
        SELECT * INTO previous FROM fee_gateway_settlement_reads WHERE id=NEW.parent_read_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id AND outcome='COMPLETE' AND next_cursor_sha256 IS NOT NULL;
        SELECT * INTO parent FROM fee_gateway_settlement_queries WHERE id=previous.query_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF parent.id IS NULL OR parent.root_query_id IS DISTINCT FROM NEW.root_query_id OR parent.page_number+1<>NEW.page_number
            OR parent.payment_provider_config_id IS DISTINCT FROM NEW.payment_provider_config_id OR parent.provider_code IS DISTINCT FROM NEW.provider_code
            OR parent.provider_environment IS DISTINCT FROM NEW.provider_environment OR parent.merchant_account_reference IS DISTINCT FROM NEW.merchant_account_reference
            OR parent.credentials_secret_reference IS DISTINCT FROM NEW.credentials_secret_reference OR parent.query_kind IS DISTINCT FROM NEW.query_kind
            OR parent.from_exact IS DISTINCT FROM NEW.from_exact OR parent.until_exact IS DISTINCT FROM NEW.until_exact
            OR parent.settlement_reference IS DISTINCT FROM NEW.settlement_reference OR parent.summary_read_id IS DISTINCT FROM NEW.summary_read_id
            OR previous.next_cursor_sha256 IS DISTINCT FROM NEW.cursor_sha256 OR previous.next_cursor_ciphertext IS DISTINCT FROM NEW.cursor_ciphertext
            OR previous.next_cursor_nonce IS DISTINCT FROM NEW.cursor_nonce
            OR EXISTS(SELECT 1 FROM fee_gateway_settlement_queries ancestor WHERE ancestor.root_query_id=NEW.root_query_id
                AND ancestor.organisation_id=NEW.organisation_id AND ancestor.school_id=NEW.school_id AND ancestor.cursor_sha256=NEW.cursor_sha256) THEN
            RAISE EXCEPTION 'settlement continuation requires exact retained advancing source cursor and unchanged scan';
        END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_settlement_query_guard BEFORE INSERT ON fee_gateway_settlement_queries FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_query();

CREATE FUNCTION guard_gateway_settlement_read() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE source fee_gateway_settlement_queries%ROWTYPE;
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id OR current_app_user_id() IS NOT NULL THEN
        RAISE EXCEPTION 'settlement retention requires actual matching background scope';
    END IF;
    SELECT * INTO source FROM fee_gateway_settlement_queries WHERE id=NEW.query_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    NEW.observed_at:=clock_timestamp();
    IF source.id IS NULL OR NEW.observed_at<source.prepared_at OR (NEW.outcome='COMPLETE'
        AND NOT gateway_settlement_facts_valid(source.query_kind,NEW.facts_canonical,source.settlement_reference,NEW.observed_at)) THEN
        RAISE EXCEPTION 'settlement read requires its prepared source and minimal exact bounded facts';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_settlement_read_guard BEFORE INSERT ON fee_gateway_settlement_reads FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_read();
CREATE TRIGGER fee_gateway_settlement_queries_append_only BEFORE UPDATE OR DELETE ON fee_gateway_settlement_queries FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
CREATE TRIGGER fee_gateway_settlement_reads_append_only BEFORE UPDATE OR DELETE ON fee_gateway_settlement_reads FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
DO $$ DECLARE relation_name TEXT; BEGIN
    FOREACH relation_name IN ARRAY ARRAY['fee_gateway_settlement_queries','fee_gateway_settlement_reads'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation_name);
        EXECUTE format('CREATE POLICY %I ON %I FOR SELECT USING (organisation_id=current_app_organisation_id())',relation_name||'_select',relation_name);
        EXECUTE format('CREATE POLICY %I ON %I FOR INSERT WITH CHECK (organisation_id=current_app_organisation_id() AND current_app_user_id() IS NULL)',relation_name||'_insert',relation_name);
    END LOOP;
END $$;
COMMENT ON TABLE fee_gateway_settlement_queries IS 'Append-only bounded report provenance. No credentials, provider activation, transfer or financial authority. Commit before I/O; actual sync requires exclusive school merchant confirmation.';
COMMENT ON TABLE fee_gateway_settlement_reads IS 'Append-only minimal reported facts retained independently of processing success. UNKNOWN is not an empty complete page, provider SUCCESS is not bank receipt, and no page grants journal authority.';

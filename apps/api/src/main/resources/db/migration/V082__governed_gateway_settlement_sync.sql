-- Read-only report workflow. No provider activation, payment, bank receipt or GL posting.
CREATE TABLE fee_gateway_settlement_requests (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK(provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL, credentials_secret_reference VARCHAR(240) NOT NULL,
    school_time_zone VARCHAR(60) NOT NULL,
    query_kind VARCHAR(10) NOT NULL CHECK(query_kind IN ('SUMMARY','EVENTS')),
    from_exact VARCHAR(30), until_exact VARCHAR(30), source_summary_read_id UUID, settlement_reference VARCHAR(100),
    client_request_id UUID NOT NULL, request_fingerprint CHAR(64) NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
    requested_by_user_id UUID NOT NULL REFERENCES user_accounts(id), requested_by_membership_id UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(requested_at)),
    UNIQUE(id,organisation_id,school_id), UNIQUE(school_id,requested_by_user_id,client_request_id),
    FOREIGN KEY(payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    FOREIGN KEY(requested_by_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
    FOREIGN KEY(source_summary_read_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_reads(id,organisation_id,school_id),
    CHECK(merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$' AND credentials_secret_reference ~ '^[A-Za-z][A-Za-z0-9_./:-]{2,239}$'),
    CHECK((query_kind='SUMMARY' AND from_exact IS NOT NULL AND until_exact IS NOT NULL AND source_summary_read_id IS NULL AND settlement_reference IS NULL)
        OR (query_kind='EVENTS' AND from_exact IS NULL AND until_exact IS NULL AND source_summary_read_id IS NOT NULL
            AND settlement_reference IS NOT NULL AND settlement_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'))
);
CREATE INDEX fee_gateway_settlement_requests_scope_time ON fee_gateway_settlement_requests(organisation_id,school_id,requested_at DESC,id);
CREATE INDEX fee_gateway_settlement_requests_provider_time ON fee_gateway_settlement_requests(payment_provider_config_id,requested_at DESC);
CREATE INDEX fee_gateway_settlement_requests_summary ON fee_gateway_settlement_requests(source_summary_read_id) WHERE source_summary_read_id IS NOT NULL;

CREATE TABLE fee_gateway_settlement_work (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, request_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK(provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL, credentials_secret_reference VARCHAR(240) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','LEASED','RETRY','COMPLETE','REVIEW_REQUIRED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 1000000),
    next_attempt_at TIMESTAMPTZ NOT NULL, lease_token UUID, lease_started_at TIMESTAMPTZ, lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ, last_error_code VARCHAR(100), created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK(row_version>=0),
    UNIQUE(id,organisation_id,school_id),
    FOREIGN KEY(request_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_requests(id,organisation_id,school_id),
    FOREIGN KEY(payment_provider_config_id,organisation_id,school_id) REFERENCES payment_provider_configs(id,organisation_id,school_id),
    CHECK(isfinite(created_at) AND isfinite(updated_at) AND isfinite(next_attempt_at) AND next_attempt_at>=created_at AND updated_at>=created_at),
    CHECK((status='LEASED' AND lease_token IS NOT NULL AND lease_started_at IS NOT NULL AND lease_until IS NOT NULL
        AND isfinite(lease_started_at) AND isfinite(lease_until) AND lease_started_at>=created_at
        AND lease_until>lease_started_at AND lease_until<=lease_started_at+interval '2 minutes' AND attempt_count>0)
        OR (status<>'LEASED' AND lease_token IS NULL AND lease_started_at IS NULL AND lease_until IS NULL)),
    CHECK((status IN ('COMPLETE','REVIEW_REQUIRED'))=(completed_at IS NOT NULL)),
    CHECK(completed_at IS NULL OR (isfinite(completed_at) AND completed_at>=created_at)),
    CHECK(last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{2,99}$')
);
CREATE INDEX fee_gateway_settlement_work_due ON fee_gateway_settlement_work(payment_provider_config_id,next_attempt_at,id) WHERE status IN ('READY','RETRY');
CREATE INDEX fee_gateway_settlement_work_expired ON fee_gateway_settlement_work(payment_provider_config_id,lease_until,id) WHERE status='LEASED';
CREATE INDEX fee_gateway_settlement_work_request ON fee_gateway_settlement_work(request_id,status,id);

CREATE TABLE fee_gateway_settlement_selections (
    query_id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL, request_id UUID NOT NULL, read_id UUID NOT NULL,
    lease_token UUID NOT NULL, lease_started_at TIMESTAMPTZ NOT NULL, lease_until TIMESTAMPTZ NOT NULL,
    attempt_number INTEGER NOT NULL CHECK(attempt_number BETWEEN 1 AND 1000000),
    outcome VARCHAR(20) NOT NULL CHECK(outcome IN ('COMPLETE','REVIEW_REQUIRED')),
    reason_code VARCHAR(100) NOT NULL CHECK(reason_code ~ '^GATEWAY_SETTLEMENT_[A-Z0-9_]+$'),
    selected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(query_id,organisation_id,school_id), UNIQUE(read_id),
    FOREIGN KEY(query_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_queries(id,organisation_id,school_id),
    FOREIGN KEY(query_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_work(id,organisation_id,school_id),
    FOREIGN KEY(request_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_requests(id,organisation_id,school_id),
    FOREIGN KEY(read_id,organisation_id,school_id) REFERENCES fee_gateway_settlement_reads(id,organisation_id,school_id),
    CHECK(isfinite(selected_at) AND isfinite(lease_started_at) AND isfinite(lease_until) AND selected_at>=lease_started_at AND selected_at<lease_until)
);
CREATE INDEX fee_gateway_settlement_selections_request ON fee_gateway_settlement_selections(request_id,query_id);

CREATE FUNCTION require_gateway_settlement_actor(organisation_value UUID,school_value UUID,membership_value UUID,user_value UUID,background_value BOOLEAN)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM organisation_value
        OR (background_value AND current_app_user_id() IS NOT NULL)
        OR (NOT background_value AND current_app_user_id() IS DISTINCT FROM user_value)
        OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
        RAISE EXCEPTION 'settlement request actor unavailable';
    END IF;
    -- Definitions/organisations are SELECT-only for runtime; do not widen grants to lock them.
    PERFORM 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id
        JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id
        WHERE member.id=membership_value AND member.user_id=user_value AND member.organisation_id=organisation_value AND member.school_id=school_value
        FOR SHARE OF member,actor,school;
    IF NOT FOUND THEN RAISE EXCEPTION 'settlement request actor unavailable'; END IF;
    PERFORM 1 FROM membership_roles assignment WHERE assignment.membership_id=membership_value FOR SHARE;
    -- Fresh snapshot and actual clock AFTER assignment/source lock waits.
    PERFORM 1 FROM memberships member JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
        JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
        JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
        WHERE member.id=membership_value AND member.user_id=user_value AND member.organisation_id=organisation_value AND member.school_id=school_value
            AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp() AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
            AND NOT EXISTS(SELECT 1 FROM unnest(ARRAY['fees.read','accounting.read','fees.close.propose']) AS required(capability)
                WHERE NOT EXISTS(SELECT 1 FROM membership_roles assignment JOIN roles role ON role.id=assignment.role_id
                    AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                    JOIN role_capabilities grant_value ON grant_value.role_id=role.id AND grant_value.capability_code=required.capability
                    WHERE assignment.membership_id=member.id));
    IF NOT FOUND THEN RAISE EXCEPTION 'settlement request actor unavailable'; END IF;
END; $$;

CREATE FUNCTION guard_gateway_settlement_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE source fee_gateway_settlement_queries%ROWTYPE; witness fee_gateway_settlement_reads%ROWTYPE;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':gateway-settlement-requests',0));
    PERFORM require_gateway_settlement_actor(NEW.organisation_id,NEW.school_id,NEW.requested_by_membership_id,NEW.requested_by_user_id,false);
    PERFORM 1 FROM payment_provider_configs provider JOIN schools school ON school.id=provider.school_id AND school.organisation_id=provider.organisation_id
        WHERE provider.id=NEW.payment_provider_config_id AND provider.organisation_id=NEW.organisation_id AND provider.school_id=NEW.school_id
            AND provider.provider_code=NEW.provider_code AND provider.environment=NEW.provider_environment AND provider.status='ACTIVE'
            AND provider.merchant_account_reference=NEW.merchant_account_reference AND provider.credentials_secret_reference=NEW.credentials_secret_reference
            AND school.time_zone=NEW.school_time_zone FOR SHARE OF provider,school;
    IF NOT FOUND THEN RAISE EXCEPTION 'settlement request requires current exact school provider and time zone'; END IF;
    PERFORM gateway_refund_school_date('2026-01-01T00:00:00Z',NEW.school_time_zone);
    NEW.requested_at:=clock_timestamp();
    IF NEW.query_kind='SUMMARY' THEN
        IF gateway_instant_epoch_ns(NEW.from_exact)<946684800000000000 OR gateway_instant_epoch_ns(NEW.until_exact)>4102444800000000000
            OR gateway_instant_epoch_ns(NEW.until_exact)>extract(epoch FROM NEW.requested_at)*1000000000
            OR gateway_instant_epoch_ns(NEW.until_exact)<=gateway_instant_epoch_ns(NEW.from_exact)
            OR gateway_instant_epoch_ns(NEW.until_exact)-gateway_instant_epoch_ns(NEW.from_exact)>2678400000000000 THEN
            RAISE EXCEPTION 'settlement request requires past bounded exact 31-day window';
        END IF;
    ELSE
        SELECT read_value.* INTO witness FROM fee_gateway_settlement_reads read_value
            JOIN fee_gateway_settlement_selections selected ON selected.read_id=read_value.id AND selected.query_id=read_value.query_id
                AND selected.organisation_id=read_value.organisation_id AND selected.school_id=read_value.school_id
            WHERE read_value.id=NEW.source_summary_read_id AND read_value.organisation_id=NEW.organisation_id AND read_value.school_id=NEW.school_id
                AND read_value.outcome='COMPLETE';
        SELECT * INTO source FROM fee_gateway_settlement_queries WHERE id=witness.query_id AND query_kind='SUMMARY'
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF source.id IS NULL OR source.payment_provider_config_id IS DISTINCT FROM NEW.payment_provider_config_id
            OR source.provider_code IS DISTINCT FROM NEW.provider_code OR source.provider_environment IS DISTINCT FROM NEW.provider_environment
            OR source.merchant_account_reference IS DISTINCT FROM NEW.merchant_account_reference OR source.credentials_secret_reference IS DISTINCT FROM NEW.credentials_secret_reference
            OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(witness.facts_canonical::jsonb) AS records(item) WHERE item->>'settlementReference'=NEW.settlement_reference) THEN
            RAISE EXCEPTION 'settlement request requires actual selected scoped summary statement';
        END IF;
    END IF;
    IF EXISTS(SELECT 1 FROM fee_gateway_settlement_requests previous WHERE previous.payment_provider_config_id=NEW.payment_provider_config_id
        AND previous.organisation_id=NEW.organisation_id AND previous.school_id=NEW.school_id AND previous.query_kind=NEW.query_kind
        AND ((NEW.query_kind='SUMMARY' AND gateway_instant_epoch_ns(NEW.from_exact)<gateway_instant_epoch_ns(previous.until_exact)
                AND gateway_instant_epoch_ns(NEW.until_exact)>gateway_instant_epoch_ns(previous.from_exact))
            OR (NEW.query_kind='EVENTS' AND previous.settlement_reference=NEW.settlement_reference))
        AND EXISTS(SELECT 1 FROM fee_gateway_settlement_work work WHERE work.request_id=previous.id AND work.status IN ('READY','LEASED','RETRY'))) THEN
        RAISE EXCEPTION 'settlement request active overlap';
    END IF;
    IF (SELECT count(*) FROM fee_gateway_settlement_requests previous WHERE previous.payment_provider_config_id=NEW.payment_provider_config_id
        AND previous.organisation_id=NEW.organisation_id AND previous.school_id=NEW.school_id AND previous.requested_at>=NEW.requested_at-interval '10 minutes')>=60
        OR (NEW.query_kind='SUMMARY' AND (SELECT count(*) FROM fee_gateway_settlement_requests previous WHERE previous.payment_provider_config_id=NEW.payment_provider_config_id
            AND previous.organisation_id=NEW.organisation_id AND previous.school_id=NEW.school_id AND previous.query_kind='SUMMARY'
            AND previous.requested_at>=NEW.requested_at-interval '10 minutes')>=5) THEN
        RAISE EXCEPTION 'settlement request rate limit';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_settlement_request_guard BEFORE INSERT ON fee_gateway_settlement_requests FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_request();

CREATE FUNCTION guard_gateway_settlement_work_binding() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE request fee_gateway_settlement_requests%ROWTYPE; source fee_gateway_settlement_queries%ROWTYPE;
BEGIN
    SELECT * INTO request FROM fee_gateway_settlement_requests WHERE id=NEW.request_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF request.id IS NULL OR NEW.organisation_id IS DISTINCT FROM current_app_organisation_id()
        OR request.payment_provider_config_id IS DISTINCT FROM NEW.payment_provider_config_id OR request.provider_code IS DISTINCT FROM NEW.provider_code
        OR request.provider_environment IS DISTINCT FROM NEW.provider_environment OR request.merchant_account_reference IS DISTINCT FROM NEW.merchant_account_reference
        OR request.credentials_secret_reference IS DISTINCT FROM NEW.credentials_secret_reference OR NEW.created_at<request.requested_at OR NEW.created_at>clock_timestamp() THEN
        RAISE EXCEPTION 'settlement work requires exact frozen human request';
    END IF;
    IF current_app_user_id() IS NOT NULL AND (NEW.id<>request.id OR current_app_user_id() IS DISTINCT FROM request.requested_by_user_id) THEN
        RAISE EXCEPTION 'settlement child processing requires matching background scope';
    END IF;
    IF NEW.id<>request.id THEN
        SELECT * INTO source FROM fee_gateway_settlement_queries WHERE id=NEW.id AND root_query_id=request.id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF source.id IS NULL OR source.parent_read_id IS NULL OR NOT EXISTS(SELECT 1 FROM fee_gateway_settlement_selections selected
            WHERE selected.read_id=source.parent_read_id AND selected.request_id=request.id AND selected.organisation_id=NEW.organisation_id
                AND selected.school_id=NEW.school_id AND selected.outcome='COMPLETE') THEN
            RAISE EXCEPTION 'settlement child work requires exact selected parent continuation';
        END IF;
    ELSE
        SELECT * INTO source FROM fee_gateway_settlement_queries WHERE id=NEW.id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    END IF;
    IF source.id IS NOT NULL AND (source.root_query_id IS DISTINCT FROM request.id OR source.query_kind IS DISTINCT FROM request.query_kind
        OR source.payment_provider_config_id IS DISTINCT FROM request.payment_provider_config_id OR source.provider_code IS DISTINCT FROM request.provider_code
        OR source.provider_environment IS DISTINCT FROM request.provider_environment OR source.merchant_account_reference IS DISTINCT FROM request.merchant_account_reference
        OR source.credentials_secret_reference IS DISTINCT FROM request.credentials_secret_reference OR source.from_exact IS DISTINCT FROM request.from_exact
        OR source.until_exact IS DISTINCT FROM request.until_exact OR source.summary_read_id IS DISTINCT FROM request.source_summary_read_id
        OR source.settlement_reference IS DISTINCT FROM request.settlement_reference) THEN
        RAISE EXCEPTION 'settlement work query differs from its immutable requested source';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_settlement_work_binding BEFORE INSERT ON fee_gateway_settlement_work FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_work_binding();
CREATE TRIGGER fee_gateway_settlement_work_transition BEFORE INSERT OR UPDATE OR DELETE ON fee_gateway_settlement_work FOR EACH ROW EXECUTE FUNCTION guard_gateway_work_transition();

CREATE FUNCTION enqueue_gateway_settlement_request() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE stamp TIMESTAMPTZ:=clock_timestamp();
BEGIN
    INSERT INTO fee_gateway_settlement_work(id,organisation_id,school_id,request_id,payment_provider_config_id,provider_code,provider_environment,
        merchant_account_reference,credentials_secret_reference,next_attempt_at,created_at,updated_at)
    VALUES(NEW.id,NEW.organisation_id,NEW.school_id,NEW.id,NEW.payment_provider_config_id,NEW.provider_code,NEW.provider_environment,
        NEW.merchant_account_reference,NEW.credentials_secret_reference,stamp,stamp,stamp);
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_settlement_request_outbox AFTER INSERT ON fee_gateway_settlement_requests FOR EACH ROW EXECUTE FUNCTION enqueue_gateway_settlement_request();

CREATE FUNCTION enqueue_gateway_settlement_query() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE request fee_gateway_settlement_requests%ROWTYPE; stamp TIMESTAMPTZ:=clock_timestamp();
BEGIN
    SELECT * INTO request FROM fee_gateway_settlement_requests WHERE id=NEW.root_query_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    -- Existing isolated/internal V081 readers are not a connected human sync request.
    IF request.id IS NULL THEN RETURN NEW; END IF;
    INSERT INTO fee_gateway_settlement_work(id,organisation_id,school_id,request_id,payment_provider_config_id,provider_code,provider_environment,
        merchant_account_reference,credentials_secret_reference,next_attempt_at,created_at,updated_at)
    VALUES(NEW.id,NEW.organisation_id,NEW.school_id,request.id,NEW.payment_provider_config_id,NEW.provider_code,NEW.provider_environment,
        NEW.merchant_account_reference,NEW.credentials_secret_reference,stamp,stamp,stamp) ON CONFLICT(id) DO NOTHING;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_settlement_query_outbox AFTER INSERT ON fee_gateway_settlement_queries FOR EACH ROW EXECUTE FUNCTION enqueue_gateway_settlement_query();

CREATE FUNCTION guard_gateway_settlement_selection() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE work fee_gateway_settlement_work%ROWTYPE; witness fee_gateway_settlement_reads%ROWTYPE;
    request fee_gateway_settlement_requests%ROWTYPE; source fee_gateway_settlement_queries%ROWTYPE;
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id OR current_app_user_id() IS NOT NULL
        OR current_setting('transaction_isolation')<>'read committed' OR current_setting('transaction_read_only')<>'off' THEN
        RAISE EXCEPTION 'settlement selection requires fresh actual background scope';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('gateway:settlement:request:' || NEW.request_id::text,0));
    SELECT * INTO work FROM fee_gateway_settlement_work WHERE id=NEW.query_id AND request_id=NEW.request_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR UPDATE;
    SELECT * INTO request FROM fee_gateway_settlement_requests WHERE id=NEW.request_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF work.id IS NULL OR request.id IS NULL THEN RAISE EXCEPTION 'settlement selection requires exact current request work'; END IF;
    PERFORM require_gateway_settlement_actor(request.organisation_id,request.school_id,request.requested_by_membership_id,request.requested_by_user_id,true);
    SELECT * INTO source FROM fee_gateway_settlement_queries WHERE id=NEW.query_id AND root_query_id=NEW.request_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO witness FROM fee_gateway_settlement_reads WHERE id=NEW.read_id AND query_id=NEW.query_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id AND outcome='COMPLETE';
    NEW.selected_at:=clock_timestamp();
    IF work.status<>'LEASED' OR work.lease_token IS DISTINCT FROM NEW.lease_token OR work.lease_started_at IS DISTINCT FROM NEW.lease_started_at
        OR work.lease_until IS DISTINCT FROM NEW.lease_until OR work.attempt_count IS DISTINCT FROM NEW.attempt_number
        OR source.id IS NULL OR witness.id IS NULL OR witness.observed_at<work.lease_started_at OR witness.observed_at>NEW.selected_at
        OR NEW.selected_at<work.lease_started_at OR NEW.selected_at>=work.lease_until
        OR source.payment_provider_config_id IS DISTINCT FROM work.payment_provider_config_id OR source.provider_code IS DISTINCT FROM work.provider_code
        OR source.provider_environment IS DISTINCT FROM work.provider_environment OR source.merchant_account_reference IS DISTINCT FROM work.merchant_account_reference
        OR source.credentials_secret_reference IS DISTINCT FROM work.credentials_secret_reference OR source.query_kind IS DISTINCT FROM request.query_kind
        OR source.from_exact IS DISTINCT FROM request.from_exact OR source.until_exact IS DISTINCT FROM request.until_exact
        OR source.summary_read_id IS DISTINCT FROM request.source_summary_read_id OR source.settlement_reference IS DISTINCT FROM request.settlement_reference THEN
        RAISE EXCEPTION 'settlement selection requires exact unexpired claim and retained complete same-query facts';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_settlement_selection_guard BEFORE INSERT ON fee_gateway_settlement_selections FOR EACH ROW EXECUTE FUNCTION guard_gateway_settlement_selection();

CREATE FUNCTION check_gateway_settlement_completion() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE selected fee_gateway_settlement_selections%ROWTYPE; work fee_gateway_settlement_work%ROWTYPE;
    source fee_gateway_settlement_queries%ROWTYPE; witness fee_gateway_settlement_reads%ROWTYPE; child_count INTEGER; work_id UUID;
BEGIN
    IF TG_TABLE_NAME='fee_gateway_settlement_work' THEN work_id:=NEW.id; ELSE work_id:=NEW.query_id; END IF;
    SELECT * INTO work FROM fee_gateway_settlement_work WHERE id=work_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO selected FROM fee_gateway_settlement_selections WHERE query_id=work.id AND request_id=work.request_id
        AND organisation_id=work.organisation_id AND school_id=work.school_id;
    IF work.status='COMPLETE' AND selected.query_id IS NULL THEN RAISE EXCEPTION 'completed settlement work requires its selected complete report witness'; END IF;
    IF selected.query_id IS NULL THEN RETURN NULL; END IF;
    IF work.status IS DISTINCT FROM selected.outcome OR work.completed_at IS NULL THEN
        RAISE EXCEPTION 'selected settlement report and terminal work must commit atomically';
    END IF;
    SELECT * INTO source FROM fee_gateway_settlement_queries WHERE id=selected.query_id AND organisation_id=work.organisation_id AND school_id=work.school_id;
    SELECT * INTO witness FROM fee_gateway_settlement_reads WHERE id=selected.read_id AND organisation_id=work.organisation_id AND school_id=work.school_id;
    SELECT count(*) INTO child_count FROM fee_gateway_settlement_work child JOIN fee_gateway_settlement_queries query ON query.id=child.id
        AND query.organisation_id=child.organisation_id AND query.school_id=child.school_id
        WHERE child.request_id=work.request_id AND child.organisation_id=work.organisation_id AND child.school_id=work.school_id
            AND query.root_query_id=work.request_id AND query.parent_read_id=selected.read_id AND query.page_number=source.page_number+1;
    IF (selected.outcome='REVIEW_REQUIRED' AND child_count<>0)
        OR (selected.outcome='COMPLETE' AND ((witness.next_cursor_sha256 IS NULL AND child_count<>0)
            OR (witness.next_cursor_sha256 IS NOT NULL AND (child_count<>1 OR source.page_number>=1000)))) THEN
        RAISE EXCEPTION 'settlement page completion requires its exact continuation or honest terminal boundary';
    END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fee_gateway_settlement_work_completion AFTER INSERT OR UPDATE ON fee_gateway_settlement_work
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_gateway_settlement_completion();
CREATE CONSTRAINT TRIGGER fee_gateway_settlement_selection_completion AFTER INSERT ON fee_gateway_settlement_selections
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_gateway_settlement_completion();
CREATE TRIGGER fee_gateway_settlement_requests_append_only BEFORE UPDATE OR DELETE ON fee_gateway_settlement_requests FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
CREATE TRIGGER fee_gateway_settlement_selections_append_only BEFORE UPDATE OR DELETE ON fee_gateway_settlement_selections FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();
DO $$ DECLARE relation_name TEXT; BEGIN
    FOREACH relation_name IN ARRAY ARRAY['fee_gateway_settlement_requests','fee_gateway_settlement_work','fee_gateway_settlement_selections'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation_name);
        IF relation_name='fee_gateway_settlement_work' THEN
            EXECUTE format('CREATE POLICY %I ON %I USING (organisation_id=current_app_organisation_id()) WITH CHECK (organisation_id=current_app_organisation_id())',relation_name||'_tenant',relation_name);
        ELSE
            EXECUTE format('CREATE POLICY %I ON %I FOR SELECT USING (organisation_id=current_app_organisation_id())',relation_name||'_select',relation_name);
            EXECUTE format('CREATE POLICY %I ON %I FOR INSERT WITH CHECK (organisation_id=current_app_organisation_id() AND current_app_user_id() IS %s NULL)',relation_name||'_insert',relation_name,
                CASE WHEN relation_name='fee_gateway_settlement_requests' THEN 'NOT' ELSE '' END);
        END IF;
    END LOOP;
END $$;
COMMENT ON TABLE fee_gateway_settlement_requests IS 'Immutable real-human bounded report request and honest actor/timezone provenance. Not bank confirmation or monetary authority. No provider activated by migration.';
COMMENT ON TABLE fee_gateway_settlement_work IS 'Existing bounded background queue semantics. Root query ID is planned before SYSTEM preparation; children require selected same-request cursor evidence. No new executor or broker.';
COMMENT ON TABLE fee_gateway_settlement_selections IS 'Append-only exact live-claim selected report witness. Selection, bounded child work and terminal page result commit together; independent read evidence survives rollback.';

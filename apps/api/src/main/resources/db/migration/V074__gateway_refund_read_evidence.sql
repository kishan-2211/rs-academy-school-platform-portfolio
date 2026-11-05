-- Durable independent provider GET facts only. A verified read is not permission
-- to dispatch a refund, release its budget, restore dues or post a journal.
-- Existing approvals, provider captures and financial history remain unchanged.
-- Java ZoneId uses ISO east-positive fixed offsets. PostgreSQL TEXT zone specs
-- instead use POSIX west-positive signs; INTERVAL zones use ISO signs. Keep this
-- normalization local to new refund evidence, never rewrite historical zones.
-- https://www.postgresql.org/docs/18/datetime-posix-timezone-specs.html
-- https://www.postgresql.org/docs/18/functions-datetime.html#FUNCTIONS-DATETIME-ZONECONVERT
CREATE FUNCTION gateway_refund_school_date(source_exact TEXT,school_zone TEXT)
RETURNS DATE LANGUAGE plpgsql STABLE STRICT PARALLEL SAFE AS $$
DECLARE projected TIMESTAMPTZ; components TEXT[]; offset_seconds INTEGER;
BEGIN
    projected:=gateway_instant_floor_us(source_exact);
    IF length(school_zone) NOT BETWEEN 1 AND 60 THEN
        RAISE EXCEPTION 'refund school time zone is unsupported' USING ERRCODE='22023';
    END IF;
    IF school_zone IN ('Z','UTC','GMT','UT') THEN
        RETURN (projected AT TIME ZONE 'UTC')::date;
    END IF;
    components:=regexp_match(school_zone,'^(?:UTC|GMT|UT)?([+-])([0-9]{2}):([0-5][0-9])(?::([0-5][0-9]))?$');
    IF components IS NOT NULL THEN
        offset_seconds:=components[2]::integer*3600+components[3]::integer*60+COALESCE(components[4]::integer,0);
        -- Canonical ZoneOffset permits at most 18 hours, uses Z/prefix-only
        -- for zero, and omits the seconds group when its value is zero.
        IF offset_seconds=0 OR offset_seconds>64800 OR components[4]='00' THEN
            RAISE EXCEPTION 'refund school time zone is unsupported' USING ERRCODE='22023';
        END IF;
        IF components[1]='-' THEN offset_seconds:=-offset_seconds; END IF;
        RETURN (projected AT TIME ZONE (offset_seconds*INTERVAL '1 second'))::date;
    END IF;
    -- No guessed POSIX abbreviations, noncanonical fixed offsets, whitespace,
    -- or unknown names. Shared IANA region/alias rules retain their own DST and
    -- historical behavior, including named Etc/GMT zones with IANA semantics.
    IF school_zone ~ '^(UTC|GMT|UT)?[+-]'
        OR school_zone !~ '^[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)*$'
        OR school_zone ~ '^(posix|right)/'
        OR NOT EXISTS(SELECT 1 FROM pg_catalog.pg_timezone_names zone WHERE zone.name=school_zone) THEN
        RAISE EXCEPTION 'refund school time zone is unsupported' USING ERRCODE='22023';
    END IF;
    RETURN (projected AT TIME ZONE school_zone)::date;
END; $$;

CREATE TABLE fee_gateway_refund_reads (
    id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
    refund_request_id UUID NOT NULL, approval_decision_id UUID NOT NULL,
    payment_provider_config_id UUID NOT NULL, provider_code VARCHAR(40) NOT NULL,
    provider_environment VARCHAR(10) NOT NULL CHECK(provider_environment IN ('TEST','LIVE')),
    merchant_account_reference VARCHAR(120) NOT NULL,
    credentials_secret_reference VARCHAR(240) NOT NULL,
    -- The caller reads clock_timestamp() after its provider GET and classifies
    -- against that fixed DB instant. Never rewrite it after waiting for a lock.
    observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp() CHECK(isfinite(observed_at)),
    evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[a-f0-9]{64}$'),
    refund_reference VARCHAR(100), order_reference VARCHAR(100), payment_reference VARCHAR(100),
    amount NUMERIC, currency_code CHAR(3), refund_status VARCHAR(20),
    processed_at TIMESTAMPTZ, processed_at_exact VARCHAR(30),
    outcome VARCHAR(20) NOT NULL CHECK(outcome IN ('UNKNOWN','NOT_SUCCESSFUL','REVIEW_REQUIRED','VERIFIED_SUCCESS')),
    processed_on DATE,
    UNIQUE(id,organisation_id,school_id),
    FOREIGN KEY(refund_request_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_requests(id,organisation_id,school_id),
    FOREIGN KEY(approval_decision_id,organisation_id,school_id)
        REFERENCES fee_gateway_refund_decisions(id,organisation_id,school_id),
    FOREIGN KEY(payment_provider_config_id,organisation_id,school_id)
        REFERENCES payment_provider_configs(id,organisation_id,school_id),
    CONSTRAINT fee_gateway_refund_reads_binding_shape CHECK(
        merchant_account_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,119}$'
        AND credentials_secret_reference ~ '^[A-Za-z][A-Za-z0-9_./:-]{2,239}$'),
    -- An unknown GET is genuinely absent, not an invented zero-valued refund.
    -- Present typed facts are complete; a processing instant is optional but its
    -- full exact source and microsecond projection must either both exist or not.
    CONSTRAINT fee_gateway_refund_reads_fact_tuple CHECK(
        (refund_reference IS NULL AND order_reference IS NULL AND payment_reference IS NULL
            AND amount IS NULL AND currency_code IS NULL AND refund_status IS NULL
            AND processed_at IS NULL AND processed_at_exact IS NULL)
        OR (refund_reference IS NOT NULL AND order_reference IS NOT NULL AND payment_reference IS NOT NULL
            AND amount IS NOT NULL AND currency_code IS NOT NULL AND refund_status IS NOT NULL
            AND refund_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'
            AND order_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'
            AND payment_reference ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$'
            AND amount>0 AND amount<=100000000 AND scale(amount)<=2
            AND currency_code ~ '^[A-Z]{3}$'
            AND refund_status IN ('PENDING','SUCCESS','FAILED','CANCELLED','ON_HOLD')
            AND ((processed_at IS NULL AND processed_at_exact IS NULL)
                OR (processed_at IS NOT NULL AND processed_at_exact IS NOT NULL
                    AND isfinite(processed_at) AND gateway_instant_floor_us(processed_at_exact)=processed_at)))
    ),
    CONSTRAINT fee_gateway_refund_reads_success_date CHECK(
        (outcome='VERIFIED_SUCCESS')=(processed_on IS NOT NULL)
        AND (processed_on IS NULL OR processed_on BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'))
);

-- Different reads may report identical source facts. Only the server-generated
-- read identity is unique; the facts hash deliberately is not a deduplication key.
CREATE INDEX fee_gateway_refund_reads_request_time_idx
    ON fee_gateway_refund_reads(organisation_id,school_id,refund_request_id,observed_at DESC,id);
CREATE INDEX fee_gateway_refund_reads_evidence_idx
    ON fee_gateway_refund_reads(organisation_id,school_id,refund_request_id,evidence_sha256);
CREATE INDEX fee_gateway_refund_reads_approval_idx
    ON fee_gateway_refund_reads(approval_decision_id,organisation_id,school_id);

CREATE FUNCTION guard_gateway_refund_read_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    request fee_gateway_refund_requests%ROWTYPE;
    approval fee_gateway_refund_decisions%ROWTYPE;
    capture fee_gateway_captures%ROWTYPE;
    expected_outcome VARCHAR(20);
    processed_ns NUMERIC;
    original_paid_ns NUMERIC;
    processed_date DATE;
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id OR current_app_user_id() IS NOT NULL THEN
        RAISE EXCEPTION 'refund read evidence requires the matching background organisation scope';
    END IF;
    SELECT * INTO request FROM fee_gateway_refund_requests
        WHERE id=NEW.refund_request_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    SELECT * INTO approval FROM fee_gateway_refund_decisions
        WHERE id=NEW.approval_decision_id AND refund_request_id=NEW.refund_request_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
    IF request.id IS NULL OR approval.id IS NULL OR approval.decision<>'APPROVE'
        OR NOT isfinite(NEW.observed_at) OR NEW.observed_at<approval.decided_at OR NEW.observed_at>clock_timestamp()
        OR NEW.payment_provider_config_id IS DISTINCT FROM request.payment_provider_config_id
        OR NEW.provider_code IS DISTINCT FROM request.provider_code
        OR NEW.provider_environment IS DISTINCT FROM request.provider_environment
        OR NEW.merchant_account_reference IS DISTINCT FROM request.merchant_account_reference
        OR NEW.credentials_secret_reference IS DISTINCT FROM request.credentials_secret_reference THEN
        RAISE EXCEPTION 'refund read evidence requires the exact approved request, frozen provider binding and honest DB observation time';
    END IF;
    SELECT * INTO capture FROM fee_gateway_captures
        WHERE id=request.capture_id AND organisation_id=request.organisation_id AND school_id=request.school_id FOR SHARE;
    IF capture.id IS NULL OR NOT isfinite(capture.paid_at) THEN
        RAISE EXCEPTION 'refund read evidence requires its preserved original payment time';
    END IF;

    IF NEW.refund_reference IS NULL THEN
        expected_outcome:='UNKNOWN';
    ELSE
        -- Compare the complete nanosecond source numerically. Never feed a
        -- fractional original to a TIMESTAMPTZ parser that rounds it first.
        -- A genuine pre-V071 NULL retains only its existing microsecond bound;
        -- no canonical exact original is invented or backfilled for that row.
        original_paid_ns:=CASE WHEN capture.paid_at_exact IS NULL
            THEN extract(epoch FROM capture.paid_at)*1000000000
            ELSE gateway_instant_epoch_ns(capture.paid_at_exact) END;
        IF NEW.processed_at_exact IS NOT NULL THEN
            processed_ns:=gateway_instant_epoch_ns(NEW.processed_at_exact);
        END IF;
        IF NEW.refund_reference IS DISTINCT FROM request.id::text
            OR NEW.order_reference IS DISTINCT FROM request.order_reference
            OR NEW.payment_reference IS DISTINCT FROM request.payment_reference
            OR NEW.amount IS DISTINCT FROM request.amount OR NEW.currency_code IS DISTINCT FROM request.currency_code
            OR NEW.currency_code IS DISTINCT FROM 'INR'
            OR (NEW.processed_at_exact IS NOT NULL AND (
                processed_ns<original_paid_ns
                OR processed_ns<extract(epoch FROM approval.decided_at)*1000000000
                OR processed_ns>extract(epoch FROM NEW.observed_at)*1000000000)) THEN
            expected_outcome:='REVIEW_REQUIRED';
        ELSIF NEW.refund_status IS DISTINCT FROM 'SUCCESS' THEN
            expected_outcome:='NOT_SUCCESSFUL';
        ELSIF NEW.processed_at_exact IS NULL THEN
            expected_outcome:='REVIEW_REQUIRED';
        ELSE
            -- Floor cannot change the school date within a valid exact second;
            -- all temporal eligibility comparisons above use the full source.
            processed_date:=gateway_refund_school_date(NEW.processed_at_exact,request.school_time_zone);
            expected_outcome:=CASE WHEN processed_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'
                THEN 'VERIFIED_SUCCESS' ELSE 'REVIEW_REQUIRED' END;
        END IF;
    END IF;
    IF NEW.outcome IS DISTINCT FROM expected_outcome
        OR (expected_outcome='VERIFIED_SUCCESS' AND NEW.processed_on IS DISTINCT FROM processed_date)
        OR (expected_outcome<>'VERIFIED_SUCCESS' AND NEW.processed_on IS NOT NULL) THEN
        RAISE EXCEPTION 'refund read outcome must preserve the exact independently observed facts without claiming financial posting';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_refund_reads_guard BEFORE INSERT ON fee_gateway_refund_reads
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_refund_read_insert();
CREATE TRIGGER fee_gateway_refund_reads_append_only BEFORE UPDATE OR DELETE ON fee_gateway_refund_reads
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();

ALTER TABLE fee_gateway_refund_reads ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_gateway_refund_reads FORCE ROW LEVEL SECURITY;
CREATE POLICY fee_gateway_refund_reads_select ON fee_gateway_refund_reads FOR SELECT
    USING(organisation_id=current_app_organisation_id());
CREATE POLICY fee_gateway_refund_reads_insert ON fee_gateway_refund_reads FOR INSERT
    WITH CHECK(organisation_id=current_app_organisation_id() AND current_app_user_id() IS NULL);
-- No UPDATE/DELETE policy. The immutable trigger also rejects privileged edits.
COMMENT ON TABLE fee_gateway_refund_reads IS 'Append-only independent GET witnesses for approved refund intent. VERIFIED_SUCCESS classifies retained provider facts only; it is not a dispatch record, financial completion, restored dues, budget release or journal posting.';
COMMENT ON COLUMN fee_gateway_refund_reads.id IS 'Server-generated stable independent read identity. An exact retry returns this already-persisted outcome and observation time, never a reclassification at a later current time.';
COMMENT ON COLUMN fee_gateway_refund_reads.evidence_sha256 IS 'Versioned source-facts digest, deliberately not unique. A new independent GET has a new read UUID even if its facts match an earlier future-time or review witness.';
COMMENT ON COLUMN fee_gateway_refund_reads.credentials_secret_reference IS 'Frozen secret label only, copied from approved request provenance. Never the provider secret value.';
COMMENT ON COLUMN fee_gateway_refund_reads.processed_at_exact IS 'Complete canonical UTC processing instant from the provider fact. processed_at is its explicit floor-microsecond derivative; NULL means the provider supplied no processing time.';
COMMENT ON FUNCTION gateway_refund_school_date(TEXT,TEXT) IS 'Pure invoker new-refund school date projection: validated canonical UTC source, Java ISO east-positive fixed offsets through INTERVAL, or an exact configured IANA region/alias. Does not change historical timezone semantics or confer financial posting authority.';

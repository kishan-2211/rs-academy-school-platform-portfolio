-- Preserve full canonical UTC provider instants independently of PostgreSQL's
-- microsecond TIMESTAMPTZ projection. No historical evidence is rewritten or
-- backfilled: NULL companions identify the precision genuinely retained earlier.
-- Deploy the corresponding exact-source writer with this forward-only migration;
-- pre-V071 writers fail closed on new inserts instead of inventing precision.
ALTER TABLE fee_gateway_payment_observations ADD COLUMN provider_paid_at_exact VARCHAR(30);
ALTER TABLE fee_gateway_checkout_observations ADD COLUMN observed_at_exact VARCHAR(30);
ALTER TABLE fee_gateway_captures ADD COLUMN paid_at_exact VARCHAR(30);

-- This is a pure, invoker helper, not a privileged data reader. Compute the full
-- epoch nanoseconds from calendar components BEFORE any timestamp cast. NUMERIC
-- retains the original instant, including negative epochs and sub-microseconds.
CREATE FUNCTION gateway_instant_epoch_ns(source_exact TEXT)
RETURNS NUMERIC LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $$
DECLARE
    calendar_date DATE;
    fraction TEXT;
    fraction_length INTEGER;
    whole_seconds NUMERIC;
BEGIN
    IF source_exact !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\.[0-9]{3}([0-9]{3}){0,2})?Z$'
       OR substring(source_exact FROM 1 FOR 4)='0000' THEN
        RAISE EXCEPTION 'gateway instant source is invalid' USING ERRCODE='22007';
    END IF;
    calendar_date := make_date(substring(source_exact FROM 1 FOR 4)::integer,
        substring(source_exact FROM 6 FOR 2)::integer, substring(source_exact FROM 9 FOR 2)::integer);
    fraction_length := length(source_exact)-20;
    IF fraction_length>0 THEN
        fraction := substring(source_exact FROM 21 FOR fraction_length-1);
        -- Instant.toString uses no fraction or its shortest 3/6/9-digit group.
        -- .000, .123000 and .123456000 are not canonical originals.
        IF fraction::numeric=0 OR (length(fraction)>3 AND right(fraction,3)='000') THEN
            RAISE EXCEPTION 'gateway instant source is invalid' USING ERRCODE='22007';
        END IF;
    ELSE fraction := ''; END IF;
    whole_seconds := (calendar_date-DATE '1970-01-01')::numeric*86400
        + substring(source_exact FROM 12 FOR 2)::numeric*3600
        + substring(source_exact FROM 15 FOR 2)::numeric*60
        + substring(source_exact FROM 18 FOR 2)::numeric;
    RETURN whole_seconds*1000000000 + rpad(fraction,9,'0')::numeric;
EXCEPTION WHEN OTHERS THEN
    -- Do not leak input, an underlying calendar parser error, or provider refs.
    RAISE EXCEPTION 'gateway instant source is invalid' USING ERRCODE='22007';
END; $$;

CREATE FUNCTION gateway_instant_floor_us(source_exact TEXT)
RETURNS TIMESTAMPTZ LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $$
DECLARE whole_second TIMESTAMPTZ; fraction TEXT;
BEGIN
    PERFORM gateway_instant_epoch_ns(source_exact);
    -- Only validated WHOLE seconds are cast. Fractional digits beyond six are
    -- explicitly dropped, never passed to a lossy/rounding timestamp parser.
    whole_second := substring(source_exact FROM 1 FOR 19)::timestamp AT TIME ZONE 'UTC';
    fraction := CASE WHEN length(source_exact)>20
        THEN substring(source_exact FROM 21 FOR length(source_exact)-21) ELSE '' END;
    RETURN whole_second + (rpad(left(fraction,6),6,'0') || ' microseconds')::interval;
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'gateway instant source is invalid' USING ERRCODE='22007';
END; $$;

ALTER TABLE fee_gateway_payment_observations
    ADD CONSTRAINT fee_gateway_payment_exact_projection_valid CHECK (
        provider_paid_at_exact IS NULL OR (provider_paid_at IS NOT NULL AND isfinite(provider_paid_at)
            AND gateway_instant_floor_us(provider_paid_at_exact)=provider_paid_at
            AND gateway_instant_epoch_ns(provider_paid_at_exact)<=extract(epoch FROM observed_at)*1000000000)
    );
ALTER TABLE fee_gateway_checkout_observations
    ADD CONSTRAINT fee_gateway_checkout_exact_projection_valid CHECK (
        observed_at_exact IS NULL OR (isfinite(observed_at)
            AND gateway_instant_floor_us(observed_at_exact)=observed_at)
    );
ALTER TABLE fee_gateway_captures
    ADD CONSTRAINT fee_gateway_capture_exact_projection_valid CHECK (
        paid_at_exact IS NULL OR (isfinite(paid_at) AND isfinite(detected_at)
            AND gateway_instant_floor_us(paid_at_exact)=paid_at
            AND gateway_instant_epoch_ns(paid_at_exact)<=extract(epoch FROM detected_at)*1000000000)
    );

-- CHECK constraints deliberately accept genuine legacy NULL sources. INSERT
-- guards distinguish new evidence, where an original source is mandatory.
CREATE FUNCTION guard_gateway_exact_instant_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE observed fee_gateway_checkout_observations%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME='fee_gateway_payment_observations' THEN
        IF (NEW.provider_paid_at IS NULL) IS DISTINCT FROM (NEW.provider_paid_at_exact IS NULL)
           OR NOT isfinite(NEW.observed_at) OR NEW.observed_at>clock_timestamp() THEN
            RAISE EXCEPTION 'new gateway payment facts require exact retained time sources and an honest observation time';
        END IF;
    ELSIF TG_TABLE_NAME='fee_gateway_checkout_observations' THEN
        IF NEW.observed_at_exact IS NULL OR NOT isfinite(NEW.observed_at)
           OR gateway_instant_epoch_ns(NEW.observed_at_exact)>extract(epoch FROM clock_timestamp())*1000000000 THEN
            RAISE EXCEPTION 'new gateway checkout evidence requires an exact retained observation time';
        END IF;
    ELSIF TG_TABLE_NAME='fee_gateway_captures' THEN
        IF NEW.paid_at_exact IS NULL OR NOT isfinite(NEW.detected_at) OR NEW.detected_at>clock_timestamp() THEN
            RAISE EXCEPTION 'new gateway capture evidence requires an exact retained payment time and honest detection';
        END IF;
        IF NEW.checkout_observation_id IS NOT NULL THEN
            SELECT * INTO observed FROM fee_gateway_checkout_observations
                WHERE id=NEW.checkout_observation_id AND attempt_id=NEW.attempt_id
                    AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
            IF observed.id IS NULL OR observed.observed_at_exact IS NULL
               OR gateway_instant_epoch_ns(NEW.paid_at_exact)>gateway_instant_epoch_ns(observed.observed_at_exact)
               OR gateway_instant_epoch_ns(observed.observed_at_exact)>extract(epoch FROM NEW.detected_at)*1000000000 THEN
                RAISE EXCEPTION 'new gateway capture proof requires exact ordered original instants without mixed legacy evidence';
            END IF;
        END IF;
    ELSE RAISE EXCEPTION 'unsupported gateway instant evidence'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_payment_observations_exact_time BEFORE INSERT ON fee_gateway_payment_observations
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_exact_instant_insert();
CREATE TRIGGER fee_gateway_checkout_observations_exact_time BEFORE INSERT ON fee_gateway_checkout_observations
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_exact_instant_insert();
CREATE TRIGGER fee_gateway_captures_exact_time BEFORE INSERT ON fee_gateway_captures
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_exact_instant_insert();

-- Preserve EVERY V068 scope/economics/account/order/completeness/uniqueness
-- predicate. For new proof, full originals must order paid <= observed <= the
-- actual DB detection time; same projected microsecond is not equality proof.
-- Legacy proof remains eligible only when BOTH sources were genuinely absent.
CREATE OR REPLACE FUNCTION gateway_receipt_evidence_matches(capture_id_value UUID, organisation_id_value UUID, school_id_value UUID)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM fee_gateway_captures capture
        JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
            AND attempt.organisation_id=capture.organisation_id AND attempt.school_id=capture.school_id
        JOIN fee_gateway_checkout_observations observed ON observed.id=capture.checkout_observation_id
            AND observed.attempt_id=attempt.id AND observed.organisation_id=attempt.organisation_id
            AND observed.school_id=attempt.school_id
        JOIN learner_fee_accounts account ON account.id=attempt.learner_fee_account_id
            AND account.organisation_id=attempt.organisation_id AND account.school_id=attempt.school_id
        WHERE capture.id=capture_id_value AND capture.organisation_id=organisation_id_value AND capture.school_id=school_id_value
          AND capture.classification IN ('MATCHED','REVIEW_REQUIRED')
          AND capture.payment_provider_config_id=attempt.payment_provider_config_id
          AND capture.provider_code=attempt.provider_code AND capture.provider_environment=attempt.provider_environment
          AND capture.merchant_account_reference=attempt.merchant_account_reference
          AND capture.checkout_reference=attempt.checkout_reference::text
          AND capture.amount=attempt.amount AND capture.currency_code=attempt.currency_code
          AND account.currency_code=capture.currency_code AND account.status='ACTIVE'
          AND capture.paid_at>=attempt.created_at
          AND capture.original_business_date=(capture.paid_at AT TIME ZONE attempt.school_time_zone)::date
          AND observed.payment_provider_config_id=attempt.payment_provider_config_id
          AND observed.provider_checkout_reference=attempt.checkout_reference AND observed.attempt_reference=attempt.id
          AND observed.amount=attempt.amount AND observed.currency_code=attempt.currency_code
          AND observed.provider_expires_at=attempt.expires_at
          AND observed.checkout_status='PAID' AND observed.amount_paid=capture.amount
          AND observed.discovery_complete AND observed.successful_payment_count=1 AND observed.pending_payment_count=0
          AND capture.order_reference=ANY(observed.order_references)
          AND observed.observed_at>=capture.paid_at AND observed.observed_at<=capture.detected_at
          AND ((capture.paid_at_exact IS NULL AND observed.observed_at_exact IS NULL)
              OR (capture.paid_at_exact IS NOT NULL AND observed.observed_at_exact IS NOT NULL
                  AND gateway_instant_epoch_ns(capture.paid_at_exact)<=gateway_instant_epoch_ns(observed.observed_at_exact)
                  AND gateway_instant_epoch_ns(observed.observed_at_exact)<=extract(epoch FROM capture.detected_at)*1000000000))
          AND NOT EXISTS (SELECT 1 FROM fee_gateway_captures other WHERE other.attempt_id=capture.attempt_id
              AND other.organisation_id=capture.organisation_id AND other.school_id=capture.school_id AND other.id<>capture.id)
    );
$$;

COMMENT ON FUNCTION gateway_instant_epoch_ns(TEXT) IS 'Pure invoker canonical UTC Instant.toString validator and exact numeric epoch nanoseconds, years 0001--9999. No floating-point or fractional timestamp parsing.';
COMMENT ON FUNCTION gateway_instant_floor_us(TEXT) IS 'Explicit floor-microsecond derivative of a complete unchanged canonical UTC original instant. Not the original provider time.';
COMMENT ON COLUMN fee_gateway_payment_observations.provider_paid_at_exact IS 'Complete unchanged canonical UTC original provider instant for new retained facts; NULL means genuinely legacy or no known payment time. No backfill.';
COMMENT ON COLUMN fee_gateway_checkout_observations.observed_at_exact IS 'Complete unchanged canonical UTC original discovery observation instant; observed_at is its explicit floor-microsecond derivative for new rows. NULL is genuine legacy precision.';
COMMENT ON COLUMN fee_gateway_captures.paid_at_exact IS 'Complete unchanged canonical UTC original provider payment instant. Use this source for new receipt provenance; paid_at is its explicit floor-microsecond derivative. NULL remains truthful legacy evidence.';

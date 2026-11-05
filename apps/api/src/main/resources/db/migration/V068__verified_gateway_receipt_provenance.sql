-- Verified captures use the common fee receipt and immutable learner ledger.
-- Existing receipts remain HUMAN evidence, including historical ONLINE_PROVIDER rows.
-- Signature/provider verification is performed by the internal server boundary; these
-- guards bind its retained facts to one exact receipt without inventing a human actor.
ALTER TABLE fee_payments
    ADD COLUMN actor_kind VARCHAR(10) NOT NULL DEFAULT 'HUMAN',
    ADD COLUMN gateway_capture_id UUID,
    ALTER COLUMN received_by_membership_id DROP NOT NULL,
    ADD CONSTRAINT fee_payments_actor_provenance_valid CHECK (
        (actor_kind='HUMAN' AND received_by_membership_id IS NOT NULL)
        OR (actor_kind='SYSTEM' AND received_by_membership_id IS NULL AND gateway_capture_id IS NOT NULL)
    ),
    ADD CONSTRAINT fee_payments_gateway_method_valid CHECK (
        gateway_capture_id IS NULL OR payment_method='ONLINE_PROVIDER'
    ),
    ADD CONSTRAINT fee_payments_gateway_capture_fk FOREIGN KEY (gateway_capture_id,organisation_id,school_id)
        REFERENCES fee_gateway_captures(id,organisation_id,school_id);
CREATE UNIQUE INDEX fee_payments_gateway_capture_once ON fee_payments(gateway_capture_id)
    WHERE gateway_capture_id IS NOT NULL;

ALTER TABLE fee_ledger_entries
    ADD COLUMN actor_kind VARCHAR(10) NOT NULL DEFAULT 'HUMAN',
    ADD COLUMN gateway_capture_id UUID,
    ALTER COLUMN created_by_membership_id DROP NOT NULL,
    ADD CONSTRAINT fee_ledger_entries_actor_provenance_valid CHECK (
        (actor_kind='HUMAN' AND created_by_membership_id IS NOT NULL)
        OR (actor_kind='SYSTEM' AND created_by_membership_id IS NULL AND gateway_capture_id IS NOT NULL)
    ),
    ADD CONSTRAINT fee_ledger_entries_gateway_source_valid CHECK (
        gateway_capture_id IS NULL
        OR (entry_type='PAYMENT' AND direction='CREDIT' AND source_type='FEE_PAYMENT'
            AND learner_fee_charge_id IS NULL AND reverses_entry_id IS NULL)
    ),
    ADD CONSTRAINT fee_ledger_entries_gateway_capture_fk FOREIGN KEY (gateway_capture_id,organisation_id,school_id)
        REFERENCES fee_gateway_captures(id,organisation_id,school_id);
CREATE UNIQUE INDEX fee_ledger_entries_gateway_capture_once ON fee_ledger_entries(gateway_capture_id)
    WHERE gateway_capture_id IS NOT NULL;

ALTER TABLE fee_gateway_capture_resolutions
    ADD COLUMN actor_kind VARCHAR(10) NOT NULL DEFAULT 'HUMAN',
    ADD COLUMN system_component VARCHAR(40),
    ADD COLUMN closed_day_close_id UUID,
    ALTER COLUMN reviewed_by_user_id DROP NOT NULL,
    ALTER COLUMN reviewed_by_membership_id DROP NOT NULL,
    ADD CONSTRAINT fee_gateway_resolutions_closed_day_fk FOREIGN KEY (closed_day_close_id,organisation_id,school_id)
        REFERENCES fee_day_closes(id,organisation_id,school_id),
    ADD CONSTRAINT fee_gateway_resolutions_actor_provenance_valid CHECK (
        (actor_kind='HUMAN' AND reviewed_by_user_id IS NOT NULL
            AND reviewed_by_membership_id IS NOT NULL AND system_component IS NULL)
        OR (actor_kind='SYSTEM' AND reviewed_by_user_id IS NULL AND reviewed_by_membership_id IS NULL
            AND system_component IS NOT NULL AND system_component='GATEWAY_VERIFICATION'
            AND resolution_type IN ('HOLD','POSTED'))
    );

CREATE OR REPLACE FUNCTION guard_fee_payment()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.id IS DISTINCT FROM NEW.id OR OLD.organisation_id IS DISTINCT FROM NEW.organisation_id
       OR OLD.school_id IS DISTINCT FROM NEW.school_id
       OR OLD.learner_fee_account_id IS DISTINCT FROM NEW.learner_fee_account_id
       OR OLD.receipt_number IS DISTINCT FROM NEW.receipt_number
       OR OLD.idempotency_key IS DISTINCT FROM NEW.idempotency_key
       OR OLD.request_fingerprint IS DISTINCT FROM NEW.request_fingerprint
       OR OLD.payment_method IS DISTINCT FROM NEW.payment_method
       OR OLD.external_reference IS DISTINCT FROM NEW.external_reference
       OR OLD.amount IS DISTINCT FROM NEW.amount OR OLD.received_on IS DISTINCT FROM NEW.received_on
       OR OLD.notes IS DISTINCT FROM NEW.notes OR OLD.ledger_entry_id IS DISTINCT FROM NEW.ledger_entry_id
       OR OLD.received_by_membership_id IS DISTINCT FROM NEW.received_by_membership_id
       OR OLD.actor_kind IS DISTINCT FROM NEW.actor_kind
       OR OLD.gateway_capture_id IS DISTINCT FROM NEW.gateway_capture_id
       OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'issued receipt evidence and actor provenance are immutable';
    END IF;
    IF OLD.gateway_capture_id IS NOT NULL THEN
        RAISE EXCEPTION 'capture-backed receipts require a verified provider refund workflow, not manual reversal';
    END IF;
    IF OLD.status <> 'POSTED' OR NEW.status <> 'REVERSED'
       OR current_setting('app.finance_reversal', TRUE) IS DISTINCT FROM 'approved' THEN
        RAISE EXCEPTION 'payments can only be reversed through the approved reversal workflow';
    END IF;
    RETURN NEW;
END; $$;

-- Classifications are not sufficient authority: reviewed closed-day captures must
-- still match the same immutable checkout, merchant, exact amount and fee account.
CREATE FUNCTION gateway_receipt_evidence_matches(capture_id_value UUID, organisation_id_value UUID, school_id_value UUID)
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
          AND NOT EXISTS (SELECT 1 FROM fee_gateway_captures other WHERE other.attempt_id=capture.attempt_id
              AND other.organisation_id=capture.organisation_id AND other.school_id=capture.school_id AND other.id<>capture.id)
    );
$$;

CREATE FUNCTION gateway_system_posting_allowed(capture_id_value UUID, organisation_id_value UUID, school_id_value UUID)
RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT gateway_receipt_evidence_matches(capture_id_value,organisation_id_value,school_id_value)
        AND EXISTS (
            SELECT 1 FROM fee_gateway_captures capture
            JOIN fee_gateway_checkout_observations observed ON observed.id=capture.checkout_observation_id
                AND observed.organisation_id=capture.organisation_id AND observed.school_id=capture.school_id
            WHERE capture.id=capture_id_value AND capture.organisation_id=organisation_id_value AND capture.school_id=school_id_value
              AND capture.classification='MATCHED' AND capture.closed_day_close_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM fee_day_closes close WHERE close.organisation_id=capture.organisation_id
                  AND close.school_id=capture.school_id AND close.status='APPROVED' AND close.business_date>=capture.original_business_date)
              AND NOT EXISTS (SELECT 1 FROM fee_gateway_capture_resolutions prior WHERE prior.capture_id=capture.id
                  AND prior.organisation_id=capture.organisation_id AND prior.school_id=capture.school_id
                  AND prior.resolution_type IN ('HOLD','REFUND_REQUIRED'))
        );
$$;

CREATE FUNCTION guard_gateway_receipt_insert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE capture fee_gateway_captures%ROWTYPE; attempt fee_gateway_attempts%ROWTYPE;
BEGIN
    IF NEW.gateway_capture_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM fee_ledger_entries entry WHERE entry.id=NEW.ledger_entry_id
            AND entry.organisation_id=NEW.organisation_id AND entry.school_id=NEW.school_id AND entry.gateway_capture_id IS NOT NULL) THEN
            RAISE EXCEPTION 'a capture-backed ledger credit cannot be reused by another receipt';
        END IF;
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    SELECT * INTO capture FROM fee_gateway_captures WHERE id=NEW.gateway_capture_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO attempt FROM fee_gateway_attempts WHERE id=capture.attempt_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF capture.id IS NULL OR attempt.id IS NULL
       OR NOT gateway_receipt_evidence_matches(capture.id,NEW.organisation_id,NEW.school_id)
       OR attempt.status NOT IN ('RESERVED','CHECKOUT_PENDING','CAPTURED_REVIEW_REQUIRED')
       OR attempt.fee_payment_id IS NOT NULL OR NEW.learner_fee_account_id IS DISTINCT FROM attempt.learner_fee_account_id
       OR NEW.amount IS DISTINCT FROM capture.amount OR NEW.external_reference IS DISTINCT FROM capture.payment_reference
       OR NEW.status<>'POSTED' OR NEW.row_version<>0 OR NEW.updated_at<>NEW.created_at
       OR NEW.created_at<capture.detected_at
       OR NOT EXISTS (SELECT 1 FROM fee_gateway_allocations allocation WHERE allocation.attempt_id=attempt.id
           AND allocation.organisation_id=NEW.organisation_id AND allocation.school_id=NEW.school_id)
       OR EXISTS (SELECT 1 FROM fee_gateway_allocations allocation WHERE allocation.attempt_id=attempt.id
           AND allocation.organisation_id=NEW.organisation_id AND allocation.school_id=NEW.school_id AND allocation.status<>'ACTIVE') THEN
        RAISE EXCEPTION 'gateway receipt requires exact retained capture and active frozen reservations';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_payments_gateway_insert_guard BEFORE INSERT ON fee_payments
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_receipt_insert();
CREATE TRIGGER fee_ledger_entries_gateway_fence BEFORE INSERT ON fee_ledger_entries
    FOR EACH ROW WHEN (NEW.gateway_capture_id IS NOT NULL) EXECUTE FUNCTION acquire_gateway_finance_fence();

CREATE OR REPLACE FUNCTION guard_gateway_resolution() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE capture fee_gateway_captures%ROWTYPE; attempt fee_gateway_attempts%ROWTYPE; payment fee_payments%ROWTYPE;
    closed_day_id UUID;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    SELECT * INTO capture FROM fee_gateway_captures WHERE id=NEW.capture_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF capture.id IS NULL OR NEW.reviewed_at<capture.detected_at OR NEW.reviewed_at>clock_timestamp() THEN
        RAISE EXCEPTION 'capture disposition requires scoped retained evidence and an honest occurrence time';
    END IF;
    SELECT id INTO closed_day_id FROM fee_day_closes WHERE organisation_id=NEW.organisation_id AND school_id=NEW.school_id
        AND status='APPROVED' AND business_date>=capture.original_business_date ORDER BY business_date DESC,id LIMIT 1;
    IF NEW.closed_day_close_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM fee_day_closes close
        WHERE close.id=NEW.closed_day_close_id AND close.organisation_id=NEW.organisation_id AND close.school_id=NEW.school_id
            AND close.status='APPROVED' AND close.business_date>=capture.original_business_date) THEN
        RAISE EXCEPTION 'capture disposition must preserve an approved matching closed financial day';
    END IF;
    IF NEW.resolution_type IN ('HOLD','POSTED') AND NEW.closed_day_close_id IS DISTINCT FROM closed_day_id THEN
        RAISE EXCEPTION 'capture hold or posting must retain the latest approved close covering its original date';
    END IF;
    IF NEW.actor_kind='HUMAN' THEN
        IF current_app_user_id() IS DISTINCT FROM NEW.reviewed_by_user_id
           OR NOT EXISTS (SELECT 1 FROM memberships member
            JOIN user_accounts reviewer ON reviewer.id=member.user_id AND reviewer.status='ACTIVE'
            JOIN membership_roles mr ON mr.membership_id=member.id
            JOIN roles role ON role.id=mr.role_id
            JOIN role_capabilities rc ON rc.role_id=mr.role_id AND rc.capability_code='fees.collect'
            WHERE member.id=NEW.reviewed_by_membership_id AND member.user_id=NEW.reviewed_by_user_id
              AND member.organisation_id=NEW.organisation_id AND member.school_id=NEW.school_id AND member.status='ACTIVE'
              AND member.valid_from<=CURRENT_TIMESTAMP AND (member.valid_until IS NULL OR member.valid_until>CURRENT_TIMESTAMP)
              AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)) THEN
            RAISE EXCEPTION 'capture resolution requires a live scoped financial reviewer';
        END IF;
    ELSIF NEW.actor_kind='SYSTEM' THEN
        IF current_app_user_id() IS NOT NULL
           OR NEW.reviewed_by_user_id IS NOT NULL OR NEW.reviewed_by_membership_id IS NOT NULL
           OR NEW.system_component IS DISTINCT FROM 'GATEWAY_VERIFICATION'
           OR NEW.resolution_type NOT IN ('HOLD','POSTED') THEN
            RAISE EXCEPTION 'system capture disposition cannot impersonate a human reviewer';
        END IF;
        IF NEW.resolution_type='HOLD' AND (EXISTS (
            SELECT 1 FROM fee_payments receipt WHERE receipt.gateway_capture_id=capture.id
                AND receipt.organisation_id=NEW.organisation_id AND receipt.school_id=NEW.school_id)
            OR EXISTS (SELECT 1 FROM fee_gateway_capture_resolutions prior WHERE prior.capture_id=capture.id
                AND prior.organisation_id=NEW.organisation_id AND prior.school_id=NEW.school_id AND prior.resolution_type='POSTED')) THEN
            RAISE EXCEPTION 'system hold cannot replace an already posted capture';
        END IF;
    ELSE RAISE EXCEPTION 'unsupported capture disposition actor'; END IF;

    IF NEW.resolution_type='POSTED' THEN
        SELECT * INTO attempt FROM fee_gateway_attempts WHERE id=capture.attempt_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        SELECT * INTO payment FROM fee_payments WHERE id=NEW.fee_payment_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF attempt.id IS NULL OR payment.id IS NULL
           OR NOT gateway_receipt_evidence_matches(capture.id,NEW.organisation_id,NEW.school_id)
           OR attempt.status NOT IN ('RESERVED','CHECKOUT_PENDING','CAPTURED_REVIEW_REQUIRED') OR attempt.fee_payment_id IS NOT NULL
           OR payment.gateway_capture_id IS DISTINCT FROM capture.id OR payment.actor_kind IS DISTINCT FROM NEW.actor_kind
           OR payment.learner_fee_account_id IS DISTINCT FROM attempt.learner_fee_account_id
           OR payment.payment_method<>'ONLINE_PROVIDER' OR payment.status<>'POSTED' OR payment.amount IS DISTINCT FROM capture.amount
           OR payment.received_on IS DISTINCT FROM NEW.open_date OR NEW.open_date<capture.original_business_date
           OR NEW.open_date>(NEW.reviewed_at AT TIME ZONE attempt.school_time_zone)::date
           OR EXISTS (SELECT 1 FROM fee_day_closes close WHERE close.organisation_id=NEW.organisation_id
               AND close.school_id=NEW.school_id AND close.status='APPROVED' AND close.business_date>=NEW.open_date)
           OR NOT EXISTS (SELECT 1 FROM fee_gateway_allocations allocation WHERE allocation.attempt_id=attempt.id
               AND allocation.organisation_id=NEW.organisation_id AND allocation.school_id=NEW.school_id)
           OR EXISTS (SELECT 1 FROM fee_gateway_allocations allocation WHERE allocation.attempt_id=attempt.id
               AND allocation.organisation_id=NEW.organisation_id AND allocation.school_id=NEW.school_id AND allocation.status<>'ACTIVE') THEN
            RAISE EXCEPTION 'capture posting requires exact receipt, active reservations and an eligible open date';
        END IF;
        IF NEW.actor_kind='SYSTEM' AND (NEW.open_date IS DISTINCT FROM capture.original_business_date
           OR NOT gateway_system_posting_allowed(capture.id,NEW.organisation_id,NEW.school_id)) THEN
            RAISE EXCEPTION 'automatic capture posting requires complete single-success discovery on its original open date without prior hold';
        END IF;
        IF NEW.actor_kind='HUMAN' AND (payment.received_by_membership_id IS DISTINCT FROM NEW.reviewed_by_membership_id
           OR attempt.status<>'CAPTURED_REVIEW_REQUIRED'
           OR NOT EXISTS (SELECT 1 FROM fee_gateway_capture_resolutions prior WHERE prior.capture_id=capture.id
               AND prior.organisation_id=NEW.organisation_id AND prior.school_id=NEW.school_id AND prior.resolution_type='HOLD')) THEN
            RAISE EXCEPTION 'reviewed capture receipt requires its actual financial reviewer and retained hold history';
        END IF;
    END IF;
    RETURN NEW;
END; $$;

-- Check the final transaction, after the receipt/disposition is inserted and the
-- one-use reservations are consumed. Manual rows return without new restrictions.
CREATE FUNCTION check_gateway_receipt_provenance() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE capture_id_value UUID; capture fee_gateway_captures%ROWTYPE; attempt fee_gateway_attempts%ROWTYPE;
    payment fee_payments%ROWTYPE; ledger fee_ledger_entries%ROWTYPE; resolution fee_gateway_capture_resolutions%ROWTYPE;
BEGIN
    IF TG_TABLE_NAME='fee_payments' THEN capture_id_value:=NEW.gateway_capture_id;
    ELSIF TG_TABLE_NAME='fee_ledger_entries' THEN
        capture_id_value:=NEW.gateway_capture_id;
        IF capture_id_value IS NULL AND NEW.source_type='FEE_PAYMENT' THEN
            SELECT gateway_capture_id INTO capture_id_value FROM fee_payments WHERE id=NEW.source_id
                AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        END IF;
    ELSIF TG_TABLE_NAME='fee_gateway_capture_resolutions' THEN capture_id_value:=NEW.capture_id;
    ELSIF TG_TABLE_NAME='fee_payment_allocations' THEN
        SELECT gateway_capture_id INTO capture_id_value FROM fee_payments WHERE id=NEW.fee_payment_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    ELSIF TG_TABLE_NAME='fee_gateway_attempts' THEN
        IF NEW.status<>'POSTED' THEN RETURN NULL; END IF;
        SELECT gateway_capture_id INTO capture_id_value FROM fee_payments WHERE id=NEW.fee_payment_id
            AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF capture_id_value IS NULL THEN RAISE EXCEPTION 'posted gateway attempt requires capture-backed receipt provenance'; END IF;
    ELSE
        SELECT receipt.gateway_capture_id INTO capture_id_value FROM fee_gateway_attempts intent
            JOIN fee_payments receipt ON receipt.id=intent.fee_payment_id
                AND receipt.organisation_id=intent.organisation_id AND receipt.school_id=intent.school_id
            WHERE intent.id=NEW.attempt_id AND intent.organisation_id=NEW.organisation_id AND intent.school_id=NEW.school_id;
        IF NEW.status='CONSUMED' AND capture_id_value IS NULL THEN
            RAISE EXCEPTION 'consumed gateway reservation requires a capture-backed posted receipt';
        END IF;
    END IF;
    IF capture_id_value IS NULL THEN RETURN NULL; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    SELECT * INTO capture FROM fee_gateway_captures WHERE id=capture_id_value
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF TG_TABLE_NAME='fee_gateway_capture_resolutions' THEN
        IF NEW.resolution_type<>'POSTED' THEN
            IF NEW.actor_kind='SYSTEM' AND NEW.resolution_type='HOLD' AND capture.attempt_id IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM fee_gateway_attempts intent WHERE intent.id=capture.attempt_id
                   AND intent.organisation_id=NEW.organisation_id AND intent.school_id=NEW.school_id
                   AND intent.status IN ('CAPTURED_REVIEW_REQUIRED','POSTED')) THEN
                RAISE EXCEPTION 'system hold requires retained gateway review state';
            END IF;
            RETURN NULL;
        END IF;
    END IF;
    SELECT * INTO payment FROM fee_payments WHERE gateway_capture_id=capture_id_value
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF payment.id IS NULL THEN
        RAISE EXCEPTION 'capture-backed ledger, disposition and reservations require one common fee receipt';
    END IF;
    SELECT * INTO attempt FROM fee_gateway_attempts WHERE id=capture.attempt_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO ledger FROM fee_ledger_entries WHERE id=payment.ledger_entry_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT * INTO resolution FROM fee_gateway_capture_resolutions WHERE capture_id=capture_id_value
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id AND resolution_type='POSTED';
    IF capture.id IS NULL OR attempt.id IS NULL OR ledger.id IS NULL OR resolution.id IS NULL
       OR NOT gateway_receipt_evidence_matches(capture.id,NEW.organisation_id,NEW.school_id)
       OR payment.status<>'POSTED' OR payment.payment_method<>'ONLINE_PROVIDER'
       OR payment.learner_fee_account_id IS DISTINCT FROM attempt.learner_fee_account_id
       OR payment.amount IS DISTINCT FROM capture.amount OR payment.external_reference IS DISTINCT FROM capture.payment_reference
       OR attempt.status<>'POSTED' OR attempt.fee_payment_id IS DISTINCT FROM payment.id
       OR ledger.gateway_capture_id IS DISTINCT FROM capture.id OR ledger.actor_kind IS DISTINCT FROM payment.actor_kind
       OR ledger.learner_fee_account_id IS DISTINCT FROM payment.learner_fee_account_id
       OR ledger.entry_type<>'PAYMENT' OR ledger.direction<>'CREDIT' OR ledger.amount IS DISTINCT FROM payment.amount
       OR ledger.effective_on IS DISTINCT FROM payment.received_on OR ledger.source_type<>'FEE_PAYMENT'
       OR ledger.source_id IS DISTINCT FROM payment.id OR ledger.learner_fee_charge_id IS NOT NULL OR ledger.reverses_entry_id IS NOT NULL
       OR ledger.created_by_membership_id IS DISTINCT FROM payment.received_by_membership_id
       OR EXISTS (SELECT 1 FROM fee_payments other WHERE other.ledger_entry_id=ledger.id
           AND other.organisation_id=NEW.organisation_id AND other.school_id=NEW.school_id AND other.id<>payment.id)
       OR EXISTS (SELECT 1 FROM fee_ledger_entries other WHERE other.source_type='FEE_PAYMENT' AND other.source_id=payment.id
           AND other.entry_type='PAYMENT' AND other.organisation_id=NEW.organisation_id AND other.school_id=NEW.school_id AND other.id<>ledger.id)
       OR resolution.fee_payment_id IS DISTINCT FROM payment.id OR resolution.actor_kind IS DISTINCT FROM payment.actor_kind
       OR resolution.open_date IS DISTINCT FROM payment.received_on
       OR payment.received_on<capture.original_business_date
       OR payment.received_on>(resolution.reviewed_at AT TIME ZONE attempt.school_time_zone)::date
       OR (payment.actor_kind='HUMAN' AND resolution.reviewed_by_membership_id IS DISTINCT FROM payment.received_by_membership_id)
       OR EXISTS (SELECT 1 FROM fee_day_closes close WHERE close.organisation_id=NEW.organisation_id
           AND close.school_id=NEW.school_id AND close.status='APPROVED' AND close.business_date>=payment.received_on)
       OR NOT EXISTS (SELECT 1 FROM fee_gateway_allocations allocation WHERE allocation.attempt_id=attempt.id
           AND allocation.organisation_id=NEW.organisation_id AND allocation.school_id=NEW.school_id)
       OR EXISTS (SELECT 1 FROM fee_gateway_allocations allocation WHERE allocation.attempt_id=attempt.id
           AND allocation.organisation_id=NEW.organisation_id AND allocation.school_id=NEW.school_id AND allocation.status<>'CONSUMED')
       OR (SELECT COALESCE(sum(amount),0) FROM fee_payment_allocations WHERE fee_payment_id=payment.id
           AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id)<>payment.amount
       OR EXISTS (
           (SELECT learner_fee_charge_id,amount FROM fee_gateway_allocations WHERE attempt_id=attempt.id
               AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id
            EXCEPT
            SELECT learner_fee_charge_id,amount FROM fee_payment_allocations WHERE fee_payment_id=payment.id
               AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id)
           UNION ALL
           (SELECT learner_fee_charge_id,amount FROM fee_payment_allocations WHERE fee_payment_id=payment.id
               AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id
            EXCEPT
            SELECT learner_fee_charge_id,amount FROM fee_gateway_allocations WHERE attempt_id=attempt.id
               AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id)
       ) THEN
        RAISE EXCEPTION 'gateway capture, receipt, ledger, exact frozen allocations and posted disposition must agree';
    END IF;
    IF payment.actor_kind='SYSTEM' AND (payment.received_on IS DISTINCT FROM capture.original_business_date
       OR NOT gateway_system_posting_allowed(capture.id,NEW.organisation_id,NEW.school_id)) THEN
        RAISE EXCEPTION 'automatic receipt cannot bypass held evidence or original closed-day history';
    END IF;
    RETURN NULL;
END; $$;

CREATE CONSTRAINT TRIGGER fee_payments_gateway_provenance AFTER INSERT ON fee_payments
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.gateway_capture_id IS NOT NULL)
    EXECUTE FUNCTION check_gateway_receipt_provenance();
CREATE CONSTRAINT TRIGGER fee_ledger_entries_gateway_provenance AFTER INSERT ON fee_ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.gateway_capture_id IS NOT NULL OR NEW.source_type='FEE_PAYMENT')
    EXECUTE FUNCTION check_gateway_receipt_provenance();
CREATE CONSTRAINT TRIGGER fee_payment_allocations_gateway_provenance AFTER INSERT ON fee_payment_allocations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_gateway_receipt_provenance();
CREATE CONSTRAINT TRIGGER fee_gateway_resolutions_receipt_provenance AFTER INSERT ON fee_gateway_capture_resolutions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_gateway_receipt_provenance();
CREATE CONSTRAINT TRIGGER fee_gateway_attempts_receipt_provenance AFTER UPDATE ON fee_gateway_attempts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.status='POSTED') EXECUTE FUNCTION check_gateway_receipt_provenance();
CREATE CONSTRAINT TRIGGER fee_gateway_allocations_receipt_provenance AFTER UPDATE ON fee_gateway_allocations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.status='CONSUMED') EXECUTE FUNCTION check_gateway_receipt_provenance();

COMMENT ON COLUMN fee_payments.actor_kind IS 'HUMAN retains the actual collector/reviewer; SYSTEM is limited to exact retained provider capture evidence and has no fabricated membership.';
COMMENT ON COLUMN fee_payments.gateway_capture_id IS 'Scoped one-use provider capture backing this common receipt; historical manual/provider receipts remain unchanged.';
COMMENT ON COLUMN fee_ledger_entries.gateway_capture_id IS 'One capture-backed PAYMENT credit, paired with the common receipt and its exact frozen obligations.';
COMMENT ON COLUMN fee_gateway_capture_resolutions.system_component IS 'Only GATEWAY_VERIFICATION may append honest SYSTEM HOLD or eligible automatic POSTED evidence; audit_events and closed-day history are unchanged.';
COMMENT ON COLUMN fee_gateway_capture_resolutions.closed_day_close_id IS 'Immutable close covering the original gateway date at disposition time, including days approved after the capture was recorded.';

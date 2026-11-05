-- Add truthful received-funds evidence without changing any historic receipt,
-- gateway capture, accounting posting date, ledger or immutable close.
CREATE FUNCTION normalize_manual_bank_reference(raw_reference TEXT)
RETURNS TEXT LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT nullif(translate(translate(raw_reference,chr(9)||chr(10)||chr(11)||chr(12)||chr(13)||' ',''),
        'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ'),'');
$$;

CREATE TABLE fee_manual_collection_evidence (
    fee_payment_id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    original_received_on DATE NOT NULL,
    actual_received_at TIMESTAMPTZ,
    received_time_precision VARCHAR(12) NOT NULL,
    school_time_zone VARCHAR(100) NOT NULL,
    posting_on DATE NOT NULL,
    verification_kind VARCHAR(30) NOT NULL,
    bank_credit_reference VARCHAR(120),
    normalized_bank_reference VARCHAR(120),
    bank_credit_confirmed BOOLEAN NOT NULL,
    verified_by_membership_id UUID NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    closed_day_close_id UUID,
    review_reason VARCHAR(240),
    CONSTRAINT fee_manual_evidence_id_scope_unique UNIQUE(fee_payment_id,organisation_id,school_id),
    CONSTRAINT fee_manual_evidence_receipt_fk FOREIGN KEY(fee_payment_id,organisation_id,school_id)
        REFERENCES fee_payments(id,organisation_id,school_id),
    CONSTRAINT fee_manual_evidence_verifier_fk FOREIGN KEY(verified_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT fee_manual_evidence_closed_day_fk FOREIGN KEY(closed_day_close_id,organisation_id,school_id)
        REFERENCES fee_day_closes(id,organisation_id,school_id),
    CONSTRAINT fee_manual_evidence_precision_valid CHECK (
        (received_time_precision='INSTANT' AND actual_received_at IS NOT NULL)
        OR (received_time_precision='DATE_ONLY' AND actual_received_at IS NULL AND verification_kind='CASH_COUNTED')),
    CONSTRAINT fee_manual_evidence_dates_valid CHECK (
        original_received_on>=DATE '2000-01-01' AND posting_on>=original_received_on
        AND (actual_received_at IS NULL OR actual_received_at<=verified_at)),
    CONSTRAINT fee_manual_evidence_kind_valid CHECK (
        (verification_kind='CASH_COUNTED' AND NOT bank_credit_confirmed
            AND bank_credit_reference IS NULL AND normalized_bank_reference IS NULL)
        OR (verification_kind IN ('BANK_APP_CREDIT','BANK_STATEMENT_CREDIT') AND bank_credit_confirmed
            AND received_time_precision='INSTANT' AND bank_credit_reference IS NOT NULL
            AND normalized_bank_reference IS NOT NULL
            AND bank_credit_reference !~ '^[A-Za-z][A-Za-z0-9+.-]*://'
            AND normalized_bank_reference !~ '^[A-Z][A-Z0-9+.-]*://'
            AND normalized_bank_reference ~ '^[A-Z0-9][A-Z0-9/_.:-]{0,119}$'
            AND normalized_bank_reference=normalize_manual_bank_reference(bank_credit_reference))),
    CONSTRAINT fee_manual_evidence_review_valid CHECK (
        (review_reason IS NULL OR length(btrim(review_reason)) BETWEEN 3 AND 240)
        AND ((posting_on=original_received_on AND closed_day_close_id IS NULL) OR review_reason IS NOT NULL))
);
-- The new table is empty: legacy duplicates cannot fail migration or be silently
-- chosen as the first/latest legitimate credit. They remain read-only exceptions.
CREATE UNIQUE INDEX fee_manual_evidence_bank_credit_once
    ON fee_manual_collection_evidence(organisation_id,school_id,normalized_bank_reference)
    WHERE normalized_bank_reference IS NOT NULL;
CREATE INDEX fee_manual_evidence_scope_posting
    ON fee_manual_collection_evidence(organisation_id,school_id,posting_on DESC,fee_payment_id);
CREATE INDEX fee_payments_manual_bank_reference_lookup
    ON fee_payments(organisation_id,school_id,normalize_manual_bank_reference(external_reference))
    WHERE gateway_capture_id IS NULL AND payment_method IN ('BANK_TRANSFER','SCHOOL_UPI');

CREATE FUNCTION require_manual_collection_actor(organisation_value UUID,school_value UUID,membership_value UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF current_app_organisation_id() IS DISTINCT FROM organisation_value OR current_app_user_id() IS NULL THEN
        RAISE EXCEPTION 'manual received-funds evidence requires the actual human tenant context';
    END IF;
    PERFORM 1 FROM memberships member
    JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
    JOIN schools school ON school.id=member.school_id AND school.organisation_id=member.organisation_id AND school.status='ACTIVE'
    JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
    JOIN membership_roles assignment ON assignment.membership_id=member.id
    JOIN roles role ON role.id=assignment.role_id
        AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
    JOIN role_capabilities grant_record ON grant_record.role_id=role.id AND grant_record.capability_code='fees.collect'
    WHERE member.id=membership_value AND member.user_id=current_app_user_id()
        AND member.organisation_id=organisation_value AND member.school_id=school_value AND member.status='ACTIVE'
        AND member.valid_from<=clock_timestamp() AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
    -- Global organisations/role definitions are runtime read-only. Lock the
    -- mutable actual membership/assignment instead; never widen global grants.
    FOR SHARE OF member,actor,school,assignment;
    IF NOT FOUND THEN RAISE EXCEPTION 'manual received-funds evidence requires a live school-scoped financial collector'; END IF;
END; $$;

CREATE FUNCTION fence_manual_collection_receipt() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM require_manual_collection_actor(NEW.organisation_id,NEW.school_id,NEW.received_by_membership_id);
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_payments_manual_collection_fence BEFORE INSERT ON fee_payments
    FOR EACH ROW WHEN (NEW.gateway_capture_id IS NULL AND NEW.payment_method IN ('CASH','BANK_TRANSFER','SCHOOL_UPI'))
    EXECUTE FUNCTION fence_manual_collection_receipt();

CREATE FUNCTION guard_manual_collection_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE payment fee_payments%ROWTYPE; configured_zone TEXT; original_close UUID; now_value TIMESTAMPTZ;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.school_id::text || ':finance',0));
    PERFORM require_manual_collection_actor(NEW.organisation_id,NEW.school_id,NEW.verified_by_membership_id);
    now_value:=clock_timestamp();
    SELECT * INTO payment FROM fee_payments WHERE id=NEW.fee_payment_id
        AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    SELECT time_zone INTO configured_zone FROM schools WHERE id=NEW.school_id AND organisation_id=NEW.organisation_id;
    SELECT id INTO original_close FROM fee_day_closes WHERE organisation_id=NEW.organisation_id AND school_id=NEW.school_id
        AND status='APPROVED' AND business_date>=NEW.original_received_on ORDER BY business_date DESC,id LIMIT 1;
    IF payment.id IS NULL OR payment.gateway_capture_id IS NOT NULL OR payment.actor_kind<>'HUMAN' OR payment.status<>'POSTED'
        OR payment.payment_method NOT IN ('CASH','BANK_TRANSFER','SCHOOL_UPI')
        OR payment.received_by_membership_id IS DISTINCT FROM NEW.verified_by_membership_id
        OR payment.received_on IS DISTINCT FROM NEW.posting_on OR NEW.school_time_zone IS DISTINCT FROM configured_zone
        OR NEW.verified_at>now_value OR NEW.verified_at<transaction_timestamp()
        OR NEW.original_received_on>(now_value AT TIME ZONE configured_zone)::date
        OR NEW.posting_on>(now_value AT TIME ZONE configured_zone)::date
        OR (NEW.actual_received_at IS NOT NULL AND (NEW.actual_received_at AT TIME ZONE NEW.school_time_zone)::date<>NEW.original_received_on)
        OR NEW.closed_day_close_id IS DISTINCT FROM original_close
        OR EXISTS(SELECT 1 FROM fee_day_closes close_record WHERE close_record.organisation_id=NEW.organisation_id
            AND close_record.school_id=NEW.school_id AND close_record.status='APPROVED' AND close_record.business_date>=NEW.posting_on)
        OR (payment.payment_method='CASH' AND NEW.verification_kind<>'CASH_COUNTED')
        OR (payment.payment_method IN ('BANK_TRANSFER','SCHOOL_UPI') AND (
            NEW.verification_kind NOT IN ('BANK_APP_CREDIT','BANK_STATEMENT_CREDIT') OR NOT NEW.bank_credit_confirmed
            OR NEW.received_time_precision<>'INSTANT'
            OR normalize_manual_bank_reference(payment.external_reference) IS DISTINCT FROM NEW.normalized_bank_reference)) THEN
        RAISE EXCEPTION 'manual receipt requires exact received funds, actual human verification and eligible reviewed posting evidence';
    END IF;
    IF NEW.normalized_bank_reference IS NOT NULL AND EXISTS (
        SELECT 1 FROM fee_payments other WHERE other.organisation_id=NEW.organisation_id AND other.school_id=NEW.school_id
            AND other.id<>NEW.fee_payment_id AND other.gateway_capture_id IS NULL
            AND other.payment_method IN ('BANK_TRANSFER','SCHOOL_UPI')
            AND normalize_manual_bank_reference(other.external_reference)=NEW.normalized_bank_reference) THEN
        RAISE EXCEPTION 'bank credit already appears in receipt history including reversed or ambiguous legacy records';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_manual_collection_evidence_insert_guard BEFORE INSERT ON fee_manual_collection_evidence
    FOR EACH ROW EXECUTE FUNCTION guard_manual_collection_evidence();
CREATE TRIGGER fee_manual_collection_evidence_immutable BEFORE UPDATE OR DELETE ON fee_manual_collection_evidence
    FOR EACH ROW EXECUTE FUNCTION prevent_finance_evidence_mutation();

-- Check the final transaction: a human receipt, its existing credit and exact
-- charge allocations cannot commit without one truthful sidecar. Existing rows
-- are not backfilled or retroactively revalidated during ordinary reversal.
CREATE FUNCTION check_manual_collection_receipt_provenance() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
                    OR (SELECT COALESCE(sum(posted_allocation.amount),0)
                        FROM fee_payment_allocations posted_allocation
                        JOIN fee_payments posted_payment ON posted_payment.id=posted_allocation.fee_payment_id
                            AND posted_payment.organisation_id=posted_allocation.organisation_id
                            AND posted_payment.school_id=posted_allocation.school_id AND posted_payment.status='POSTED'
                        WHERE posted_allocation.learner_fee_charge_id=charge.id
                            AND posted_allocation.organisation_id=payment.organisation_id AND posted_allocation.school_id=payment.school_id)
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
                        -(SELECT COALESCE(sum(other_allocation.amount),0) FROM fee_payment_allocations other_allocation
                            JOIN fee_payments other_payment ON other_payment.id=other_allocation.fee_payment_id
                                AND other_payment.organisation_id=other_allocation.organisation_id
                                AND other_payment.school_id=other_allocation.school_id AND other_payment.status='POSTED'
                            WHERE other_allocation.learner_fee_charge_id=charge.id AND other_allocation.fee_payment_id<>payment.id
                                AND other_allocation.organisation_id=payment.organisation_id AND other_allocation.school_id=payment.school_id))
                )) THEN
        RAISE EXCEPTION 'manual receipt, truthful received-funds evidence, existing ledger and exact scoped allocations must agree';
    END IF;
    RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER fee_payments_manual_evidence_provenance AFTER INSERT ON fee_payments
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (NEW.gateway_capture_id IS NULL AND NEW.payment_method IN ('CASH','BANK_TRANSFER','SCHOOL_UPI'))
    EXECUTE FUNCTION check_manual_collection_receipt_provenance();
CREATE CONSTRAINT TRIGGER fee_manual_evidence_receipt_provenance AFTER INSERT ON fee_manual_collection_evidence
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_manual_collection_receipt_provenance();
CREATE CONSTRAINT TRIGGER fee_payment_allocations_manual_evidence_provenance AFTER INSERT ON fee_payment_allocations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_manual_collection_receipt_provenance();
CREATE CONSTRAINT TRIGGER fee_ledger_entries_manual_evidence_provenance AFTER INSERT ON fee_ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.source_type='FEE_PAYMENT' AND NEW.gateway_capture_id IS NULL)
    EXECUTE FUNCTION check_manual_collection_receipt_provenance();

ALTER TABLE fee_manual_collection_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE fee_manual_collection_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY fee_manual_collection_evidence_tenant_select ON fee_manual_collection_evidence FOR SELECT
    USING (organisation_id=current_app_organisation_id());
CREATE POLICY fee_manual_collection_evidence_tenant_insert ON fee_manual_collection_evidence FOR INSERT
    WITH CHECK (organisation_id=current_app_organisation_id());

COMMENT ON TABLE fee_manual_collection_evidence IS
    'Append-only human received-funds attestation paired with the existing common receipt, allocations and ledger. No historic backfill or new balance.';
COMMENT ON COLUMN fee_manual_collection_evidence.original_received_on IS
    'Actual original school-local received day; never replaced by the open accounting posting date.';
COMMENT ON COLUMN fee_manual_collection_evidence.actual_received_at IS
    'Actual received instant supplied by the authorized collector; NULL only for honest DATE_ONLY legacy cash requests, not fabricated midnight.';
COMMENT ON COLUMN fee_manual_collection_evidence.posting_on IS
    'Eligible accounting date equal to existing fee_payments.received_on and ledger.effective_on; original closed history remains unchanged.';
COMMENT ON COLUMN fee_manual_collection_evidence.bank_credit_reference IS
    'Actual UTR or uniquely identifiable credited bank entry, attested after checking the school bank credit; reference or payer screenshot alone is not verification.';
COMMENT ON INDEX fee_manual_evidence_bank_credit_once IS
    'Cross-UPI/bank one-credit identity remains retained after reversal. Legacy duplicate references block new reuse; controlled linked replacement is not implemented by this migration.';

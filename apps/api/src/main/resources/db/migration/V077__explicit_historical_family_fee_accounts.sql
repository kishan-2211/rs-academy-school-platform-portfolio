-- Explicit historical account selection does not reactivate academic records,
-- reopen financial days, post refunds or waive fees. Existing intent keeps its
-- original current-session semantics and immutable fingerprint.
ALTER TABLE fee_gateway_attempts
    ADD COLUMN account_selection_mode VARCHAR(20) NOT NULL DEFAULT 'CURRENT_SESSION'
        CHECK (account_selection_mode IN ('CURRENT_SESSION','EXPLICIT_ACCOUNT'));

CREATE FUNCTION guard_gateway_account_selection() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF NEW.account_selection_mode IS DISTINCT FROM OLD.account_selection_mode THEN
            RAISE EXCEPTION 'gateway account selection is immutable';
        END IF;
        RETURN NEW;
    END IF;
    -- The existing finance-fence trigger runs before this guard. Use the actual
    -- live actor/time, not the transaction's start time after a lock wait.
    IF current_app_organisation_id() IS DISTINCT FROM NEW.organisation_id
       OR current_app_user_id() IS DISTINCT FROM NEW.initiated_by_user_id
       OR NOT EXISTS (
           SELECT 1 FROM learner_fee_accounts account
           JOIN learners learner ON learner.id=account.learner_id
             AND learner.organisation_id=account.organisation_id AND learner.school_id=account.school_id AND learner.status='ACTIVE'
           JOIN memberships member ON member.id=NEW.initiated_by_membership_id
             AND member.user_id=NEW.initiated_by_user_id AND member.organisation_id=account.organisation_id
             AND member.school_id=account.school_id AND member.status='ACTIVE'
             AND member.valid_from<=clock_timestamp() AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
           WHERE account.id=NEW.learner_fee_account_id AND account.organisation_id=NEW.organisation_id
             AND account.school_id=NEW.school_id AND account.status='ACTIVE'
             AND (NEW.account_selection_mode='EXPLICIT_ACCOUNT' OR (
                 NEW.account_selection_mode='CURRENT_SESSION' AND account.academic_session_id=(
                   SELECT enrolment.academic_session_id FROM student_enrolments enrolment
                   WHERE enrolment.learner_id=learner.id AND enrolment.organisation_id=learner.organisation_id
                     AND enrolment.school_id=learner.school_id AND enrolment.status='ACTIVE'
                   ORDER BY enrolment.starts_on DESC,enrolment.id DESC LIMIT 1)))
       ) THEN RAISE EXCEPTION 'gateway selection requires its exact active own-child account and live initiating actor'; END IF;
    -- Existing guard_gateway_intent still verifies the actual guardian link,
    -- capability, active school/provider and all frozen source fields. Its JSON
    -- immutability comparison also protects this additive column on UPDATE.
    RETURN NEW;
END; $$;
CREATE TRIGGER fee_gateway_attempts_selection_guard BEFORE INSERT OR UPDATE ON fee_gateway_attempts
    FOR EACH ROW EXECUTE FUNCTION guard_gateway_account_selection();

CREATE INDEX learner_fee_accounts_family_history_idx
    ON learner_fee_accounts(organisation_id,school_id,learner_id,academic_session_id,id);

COMMENT ON COLUMN fee_gateway_attempts.account_selection_mode IS
    'Immutable selection provenance: legacy current-session intent or explicitly selected original own-child account. Neither is authority to reopen or post money.';

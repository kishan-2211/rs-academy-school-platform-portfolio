-- Additive decision guards only: never rewrite previously published payroll evidence.
-- Memberships are access assignments, not independent people. One user can have
-- both school-wide and campus-specific memberships within the same school.
CREATE FUNCTION payroll_run_actor_is_independent(
    actor_id UUID, creator_id UUID, submitter_id UUID, org_id UUID, target_school_id UUID
) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM memberships actor
        JOIN memberships creator ON creator.id=creator_id
          AND creator.organisation_id=org_id AND creator.school_id=target_school_id
        LEFT JOIN memberships submitter ON submitter.id=submitter_id
          AND submitter.organisation_id=org_id AND submitter.school_id=target_school_id
        WHERE actor.id=actor_id AND actor.organisation_id=org_id
          AND actor.school_id=target_school_id AND actor.user_id<>creator.user_id
          AND (submitter_id IS NULL OR actor.user_id<>submitter.user_id)
    );
$$;

CREATE FUNCTION guard_payroll_run_person_decision()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status IN ('FINALIZED','REJECTED','REVERSED')
           AND NOT payroll_run_actor_is_independent(NEW.decided_by_membership_id,
                NEW.created_by_membership_id,NEW.submitted_by_membership_id,
                NEW.organisation_id,NEW.school_id) THEN
            RAISE EXCEPTION 'payroll run decision requires an independent person';
        END IF;
    ELSIF OLD.status='SUBMITTED' AND NEW.status IN ('FINALIZED','REJECTED') THEN
        IF NOT payroll_run_actor_is_independent(NEW.decided_by_membership_id,
                OLD.created_by_membership_id,OLD.submitted_by_membership_id,
                OLD.organisation_id,OLD.school_id) THEN
            RAISE EXCEPTION 'payroll run decision requires an independent person';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payroll_runs_guard_person_decision
    BEFORE INSERT OR UPDATE ON payroll_runs
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_run_person_decision();

CREATE FUNCTION guard_payroll_reversal_person_decision()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='PENDING' AND NEW.status IN ('APPROVED','REJECTED') THEN
        IF NOT payroll_run_actor_is_independent(NEW.decided_by_membership_id,
                OLD.requested_by_membership_id,NULL,OLD.organisation_id,OLD.school_id) THEN
            RAISE EXCEPTION 'payroll reversal decision requires an independent person';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payroll_run_reversals_guard_person_decision
    BEFORE UPDATE ON payroll_run_reversal_requests
    FOR EACH ROW EXECUTE FUNCTION guard_payroll_reversal_person_decision();

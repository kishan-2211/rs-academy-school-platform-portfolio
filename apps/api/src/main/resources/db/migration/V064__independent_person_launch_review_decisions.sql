-- Preserve all existing evidence/reviews. Guard future writes, including direct
-- inserts, without rewriting historical decisions or replacing V024's lifecycle guard.
CREATE FUNCTION guard_launch_review_independent_people()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    submitter_user UUID;
    decider_user UUID;
BEGIN
    IF NEW.decided_by_membership_id IS NOT NULL THEN
        SELECT user_id INTO submitter_user FROM memberships
        WHERE id=NEW.submitted_by_membership_id
          AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        SELECT user_id INTO decider_user FROM memberships
        WHERE id=NEW.decided_by_membership_id
          AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
        IF submitter_user IS NULL OR decider_user IS NULL OR submitter_user=decider_user THEN
            RAISE EXCEPTION 'launch review decisions require a different authorized person'
                USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER school_launch_reviews_person_guard
    BEFORE INSERT OR UPDATE ON school_launch_reviews
    FOR EACH ROW EXECUTE FUNCTION guard_launch_review_independent_people();

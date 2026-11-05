CREATE FUNCTION guard_ptm_initial_state()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.row_version<>0 THEN RAISE EXCEPTION 'ptm records must begin at version zero'; END IF;
    IF TG_TABLE_NAME='ptm_events' AND NEW.status<>'DRAFT' THEN
        RAISE EXCEPTION 'ptm events must begin as a draft';
    ELSIF TG_TABLE_NAME='ptm_slots' THEN
        PERFORM 1 FROM ptm_events WHERE id=NEW.ptm_event_id
          AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id AND status='DRAFT' FOR UPDATE;
        IF NOT FOUND OR NEW.status<>'AVAILABLE' THEN
            RAISE EXCEPTION 'ptm slots can only be added to a draft event';
        END IF;
    ELSIF TG_TABLE_NAME='ptm_bookings' AND NEW.status<>'BOOKED' THEN
        RAISE EXCEPTION 'ptm bookings must begin as booked';
    ELSIF TG_TABLE_NAME='ptm_booking_reminders' AND NEW.status<>'PENDING' THEN
        RAISE EXCEPTION 'ptm reminders must begin pending';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER ptm_events_initial_guard BEFORE INSERT ON ptm_events
    FOR EACH ROW EXECUTE FUNCTION guard_ptm_initial_state();
CREATE TRIGGER ptm_slots_initial_guard BEFORE INSERT ON ptm_slots
    FOR EACH ROW EXECUTE FUNCTION guard_ptm_initial_state();
CREATE TRIGGER ptm_bookings_initial_guard BEFORE INSERT ON ptm_bookings
    FOR EACH ROW EXECUTE FUNCTION guard_ptm_initial_state();
CREATE TRIGGER ptm_reminders_initial_guard BEFORE INSERT ON ptm_booking_reminders
    FOR EACH ROW EXECUTE FUNCTION guard_ptm_initial_state();

CREATE FUNCTION guard_ptm_published_authority()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='PUBLISHED' AND (
        NEW.published_by_membership_id IS DISTINCT FROM OLD.published_by_membership_id
        OR NEW.published_at IS DISTINCT FROM OLD.published_at
        OR NEW.publication_reason IS DISTINCT FROM OLD.publication_reason) THEN
        RAISE EXCEPTION 'ptm publication authority evidence is immutable';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER ptm_events_publication_guard BEFORE UPDATE ON ptm_events
    FOR EACH ROW EXECUTE FUNCTION guard_ptm_published_authority();

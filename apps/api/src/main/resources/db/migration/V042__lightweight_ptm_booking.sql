INSERT INTO capabilities(code,description,risk_level) VALUES
    ('ptm.read','Read parent-teacher meeting schedules within an authorized school or teacher scope','STANDARD'),
    ('ptm.manage','Create, publish, and cancel parent-teacher meeting schedules','SENSITIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_capabilities(role_id,capability_code) VALUES
    ('00000000-0000-4000-8000-000000000001','ptm.read'),
    ('00000000-0000-4000-8000-000000000001','ptm.manage'),
    ('00000000-0000-4000-8000-000000000007','ptm.read'),
    ('00000000-0000-4000-8000-000000000007','ptm.manage'),
    ('00000000-0000-4000-8000-000000000003','ptm.read')
ON CONFLICT DO NOTHING;

CREATE TABLE ptm_events (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    academic_session_id UUID NOT NULL,
    class_section_id UUID NOT NULL,
    teacher_membership_id UUID NOT NULL,
    teacher_staff_profile_id UUID NOT NULL,
    teacher_name_snapshot VARCHAR(120) NOT NULL,
    class_name_snapshot VARCHAR(100) NOT NULL,
    title_en VARCHAR(160) NOT NULL,
    title_hi VARCHAR(160),
    instructions_en VARCHAR(1200),
    instructions_hi VARCHAR(1200),
    venue VARCHAR(160) NOT NULL,
    time_zone_snapshot VARCHAR(60) NOT NULL,
    event_date DATE NOT NULL,
    booking_opens_at TIMESTAMPTZ NOT NULL,
    booking_closes_at TIMESTAMPTZ NOT NULL,
    changes_close_at TIMESTAMPTZ NOT NULL,
    reminder_minutes_before INTEGER NOT NULL DEFAULT 1440,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_by_membership_id UUID NOT NULL,
    published_by_membership_id UUID,
    published_at TIMESTAMPTZ,
    publication_reason VARCHAR(500),
    cancelled_by_membership_id UUID,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ptm_events_id_scope_unique UNIQUE(id,organisation_id,school_id),
    CONSTRAINT ptm_events_school_fk FOREIGN KEY(school_id,organisation_id)
        REFERENCES schools(id,organisation_id),
    CONSTRAINT ptm_events_session_fk FOREIGN KEY(academic_session_id,organisation_id,school_id)
        REFERENCES academic_sessions(id,organisation_id,school_id),
    CONSTRAINT ptm_events_section_fk FOREIGN KEY(class_section_id,organisation_id,school_id)
        REFERENCES class_sections(id,organisation_id,school_id),
    CONSTRAINT ptm_events_teacher_membership_fk FOREIGN KEY(teacher_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT ptm_events_teacher_profile_fk FOREIGN KEY(teacher_staff_profile_id,organisation_id,school_id)
        REFERENCES staff_profiles(id,organisation_id,school_id),
    CONSTRAINT ptm_events_creator_fk FOREIGN KEY(created_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT ptm_events_publisher_fk FOREIGN KEY(published_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT ptm_events_canceller_fk FOREIGN KEY(cancelled_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT ptm_events_retry_unique UNIQUE(school_id,created_by_membership_id,idempotency_key),
    CONSTRAINT ptm_events_text_valid CHECK(
        length(btrim(teacher_name_snapshot)) BETWEEN 1 AND 120
        AND length(btrim(class_name_snapshot)) BETWEEN 1 AND 100
        AND length(btrim(title_en)) BETWEEN 3 AND 160
        AND (title_hi IS NULL OR length(btrim(title_hi)) BETWEEN 3 AND 160)
        AND (instructions_en IS NULL OR length(btrim(instructions_en)) BETWEEN 3 AND 1200)
        AND (instructions_hi IS NULL OR length(btrim(instructions_hi)) BETWEEN 3 AND 1200)
        AND length(btrim(venue)) BETWEEN 2 AND 160
        AND length(btrim(time_zone_snapshot)) BETWEEN 1 AND 60),
    CONSTRAINT ptm_events_window_valid CHECK(
        booking_opens_at < booking_closes_at AND booking_closes_at <= changes_close_at),
    CONSTRAINT ptm_events_reminder_valid CHECK(reminder_minutes_before BETWEEN 0 AND 10080),
    CONSTRAINT ptm_events_status_valid CHECK(status IN('DRAFT','PUBLISHED','CANCELLED')),
    CONSTRAINT ptm_events_state_valid CHECK(
        (status='DRAFT' AND published_by_membership_id IS NULL AND published_at IS NULL
          AND publication_reason IS NULL AND cancelled_by_membership_id IS NULL
          AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status='PUBLISHED' AND published_by_membership_id IS NOT NULL AND published_at IS NOT NULL
          AND length(btrim(publication_reason)) BETWEEN 3 AND 500
          AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status='CANCELLED' AND published_by_membership_id IS NOT NULL AND published_at IS NOT NULL
          AND length(btrim(publication_reason)) BETWEEN 3 AND 500
          AND cancelled_by_membership_id IS NOT NULL AND cancelled_at IS NOT NULL
          AND length(btrim(cancellation_reason)) BETWEEN 3 AND 500))
);

CREATE INDEX ptm_events_school_date_idx
    ON ptm_events(organisation_id,school_id,event_date,status);
CREATE INDEX ptm_events_teacher_date_idx
    ON ptm_events(teacher_membership_id,event_date,status);
CREATE INDEX ptm_events_section_date_idx
    ON ptm_events(class_section_id,event_date,status);

CREATE TABLE ptm_slots (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    ptm_event_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ptm_slots_id_scope_unique UNIQUE(id,organisation_id,school_id),
    CONSTRAINT ptm_slots_event_fk FOREIGN KEY(ptm_event_id,organisation_id,school_id)
        REFERENCES ptm_events(id,organisation_id,school_id),
    CONSTRAINT ptm_slots_natural_unique UNIQUE(ptm_event_id,starts_at),
    CONSTRAINT ptm_slots_time_valid CHECK(ends_at>starts_at AND ends_at-starts_at<=INTERVAL '2 hours'),
    CONSTRAINT ptm_slots_status_valid CHECK(status IN('AVAILABLE','CANCELLED'))
);

CREATE INDEX ptm_slots_event_time_idx ON ptm_slots(ptm_event_id,starts_at,status);

CREATE TABLE ptm_bookings (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    ptm_event_id UUID NOT NULL,
    ptm_slot_id UUID NOT NULL,
    learner_id UUID NOT NULL,
    guardian_id UUID NOT NULL,
    booked_by_membership_id UUID NOT NULL,
    topic VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'BOOKED',
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    booked_at TIMESTAMPTZ NOT NULL,
    cancelled_by_membership_id UUID,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ptm_bookings_id_scope_unique UNIQUE(id,organisation_id,school_id),
    CONSTRAINT ptm_bookings_event_fk FOREIGN KEY(ptm_event_id,organisation_id,school_id)
        REFERENCES ptm_events(id,organisation_id,school_id),
    CONSTRAINT ptm_bookings_slot_fk FOREIGN KEY(ptm_slot_id,organisation_id,school_id)
        REFERENCES ptm_slots(id,organisation_id,school_id),
    CONSTRAINT ptm_bookings_learner_fk FOREIGN KEY(learner_id,organisation_id,school_id)
        REFERENCES learners(id,organisation_id,school_id),
    CONSTRAINT ptm_bookings_guardian_fk FOREIGN KEY(guardian_id,organisation_id,school_id)
        REFERENCES guardians(id,organisation_id,school_id),
    CONSTRAINT ptm_bookings_booker_fk FOREIGN KEY(booked_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT ptm_bookings_canceller_fk FOREIGN KEY(cancelled_by_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT ptm_bookings_retry_unique UNIQUE(school_id,booked_by_membership_id,idempotency_key),
    CONSTRAINT ptm_bookings_topic_valid CHECK(topic IS NULL OR length(btrim(topic)) BETWEEN 3 AND 500),
    CONSTRAINT ptm_bookings_status_valid CHECK(status IN('BOOKED','CANCELLED')),
    CONSTRAINT ptm_bookings_state_valid CHECK(
        (status='BOOKED' AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL
          AND cancellation_reason IS NULL)
        OR (status='CANCELLED' AND cancelled_by_membership_id IS NOT NULL AND cancelled_at IS NOT NULL
          AND length(btrim(cancellation_reason)) BETWEEN 3 AND 500))
);

CREATE UNIQUE INDEX ptm_bookings_one_active_slot_idx ON ptm_bookings(ptm_slot_id) WHERE status='BOOKED';
CREATE UNIQUE INDEX ptm_bookings_one_active_learner_event_idx
    ON ptm_bookings(ptm_event_id,learner_id) WHERE status='BOOKED';
CREATE INDEX ptm_bookings_guardian_time_idx ON ptm_bookings(guardian_id,status,updated_at DESC);

CREATE TABLE ptm_booking_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    ptm_booking_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    previous_slot_id UUID,
    new_slot_id UUID,
    reason VARCHAR(500) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ptm_booking_history_scope_unique UNIQUE(id,organisation_id,school_id),
    CONSTRAINT ptm_booking_history_booking_fk FOREIGN KEY(ptm_booking_id,organisation_id,school_id)
        REFERENCES ptm_bookings(id,organisation_id,school_id),
    CONSTRAINT ptm_booking_history_previous_slot_fk FOREIGN KEY(previous_slot_id,organisation_id,school_id)
        REFERENCES ptm_slots(id,organisation_id,school_id),
    CONSTRAINT ptm_booking_history_new_slot_fk FOREIGN KEY(new_slot_id,organisation_id,school_id)
        REFERENCES ptm_slots(id,organisation_id,school_id),
    CONSTRAINT ptm_booking_history_actor_fk FOREIGN KEY(actor_membership_id,organisation_id,school_id)
        REFERENCES memberships(id,organisation_id,school_id),
    CONSTRAINT ptm_booking_history_event_valid CHECK(event_type IN('BOOKED','RESCHEDULED','CANCELLED','EVENT_CANCELLED')),
    CONSTRAINT ptm_booking_history_reason_valid CHECK(length(btrim(reason)) BETWEEN 3 AND 500)
);

CREATE INDEX ptm_booking_history_booking_idx ON ptm_booking_history(ptm_booking_id,occurred_at,id);

CREATE TABLE ptm_booking_reminders (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    school_id UUID NOT NULL,
    ptm_booking_id UUID NOT NULL,
    ptm_slot_id UUID NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(80),
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ptm_booking_reminders_scope_unique UNIQUE(id,organisation_id,school_id),
    CONSTRAINT ptm_booking_reminders_booking_fk FOREIGN KEY(ptm_booking_id,organisation_id,school_id)
        REFERENCES ptm_bookings(id,organisation_id,school_id),
    CONSTRAINT ptm_booking_reminders_slot_fk FOREIGN KEY(ptm_slot_id,organisation_id,school_id)
        REFERENCES ptm_slots(id,organisation_id,school_id),
    CONSTRAINT ptm_booking_reminders_status_valid CHECK(status IN('PENDING','MATERIALIZED','CANCELLED','FAILED')),
    CONSTRAINT ptm_booking_reminders_attempt_valid CHECK(attempt_count BETWEEN 0 AND 20),
    CONSTRAINT ptm_booking_reminders_state_valid CHECK(
        (status='PENDING' AND processed_at IS NULL)
        OR (status IN('MATERIALIZED','CANCELLED','FAILED') AND processed_at IS NOT NULL))
);

CREATE INDEX ptm_booking_reminders_due_idx
    ON ptm_booking_reminders(organisation_id,status,next_attempt_at) WHERE status='PENDING';
CREATE UNIQUE INDEX ptm_booking_reminders_one_pending_idx
    ON ptm_booking_reminders(ptm_booking_id) WHERE status='PENDING';

CREATE OR REPLACE FUNCTION guard_ptm_event_state()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE slot_count INTEGER;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'ptm events cannot be deleted'; END IF;
    IF OLD.id<>NEW.id OR OLD.organisation_id<>NEW.organisation_id OR OLD.school_id<>NEW.school_id
       OR OLD.academic_session_id<>NEW.academic_session_id OR OLD.class_section_id<>NEW.class_section_id
       OR OLD.teacher_membership_id<>NEW.teacher_membership_id
       OR OLD.teacher_staff_profile_id<>NEW.teacher_staff_profile_id
       OR OLD.teacher_name_snapshot<>NEW.teacher_name_snapshot OR OLD.class_name_snapshot<>NEW.class_name_snapshot
       OR OLD.title_en<>NEW.title_en OR OLD.title_hi IS DISTINCT FROM NEW.title_hi
       OR OLD.instructions_en IS DISTINCT FROM NEW.instructions_en
       OR OLD.instructions_hi IS DISTINCT FROM NEW.instructions_hi OR OLD.venue<>NEW.venue
       OR OLD.time_zone_snapshot<>NEW.time_zone_snapshot OR OLD.event_date<>NEW.event_date
       OR OLD.booking_opens_at<>NEW.booking_opens_at OR OLD.booking_closes_at<>NEW.booking_closes_at
       OR OLD.changes_close_at<>NEW.changes_close_at OR OLD.reminder_minutes_before<>NEW.reminder_minutes_before
       OR OLD.idempotency_key<>NEW.idempotency_key OR OLD.request_fingerprint<>NEW.request_fingerprint
       OR OLD.created_by_membership_id<>NEW.created_by_membership_id OR OLD.created_at<>NEW.created_at THEN
        RAISE EXCEPTION 'ptm event identity and published content are immutable';
    END IF;
    IF NEW.row_version<>OLD.row_version+1 THEN RAISE EXCEPTION 'ptm event version must advance exactly once'; END IF;
    IF OLD.status='DRAFT' AND NEW.status='PUBLISHED' THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('ptm-teacher:'||NEW.teacher_membership_id::text,0));
        IF NOT EXISTS(
          SELECT 1 FROM memberships teacher
          JOIN membership_roles membership_role ON membership_role.membership_id=teacher.id
          JOIN roles role ON role.id=membership_role.role_id
          JOIN staff_profiles profile ON profile.user_account_id=teacher.user_id
            AND profile.organisation_id=teacher.organisation_id AND profile.school_id=teacher.school_id
            AND profile.id=NEW.teacher_staff_profile_id AND profile.status='ACTIVE'
            AND profile.joined_on<=NEW.event_date AND (profile.ended_on IS NULL OR profile.ended_on>=NEW.event_date)
          JOIN staff_assignments assignment ON assignment.staff_profile_id=profile.id
            AND assignment.organisation_id=profile.organisation_id AND assignment.school_id=profile.school_id
            AND assignment.status='ACTIVE' AND assignment.starts_on<=NEW.event_date
            AND (assignment.ends_on IS NULL OR assignment.ends_on>=NEW.event_date)
          JOIN staff_designations designation ON designation.id=assignment.staff_designation_id
            AND designation.organisation_id=assignment.organisation_id AND designation.school_id=assignment.school_id
            AND designation.status='ACTIVE' AND designation.teaching_role
          JOIN class_sections section ON section.id=NEW.class_section_id
            AND section.organisation_id=teacher.organisation_id AND section.school_id=teacher.school_id
            AND section.academic_session_id=NEW.academic_session_id AND section.status='ACTIVE'
          JOIN academic_sessions session ON session.id=NEW.academic_session_id
            AND session.organisation_id=teacher.organisation_id AND session.school_id=teacher.school_id
            AND session.status IN('PLANNED','ACTIVE') AND NEW.event_date BETWEEN session.starts_on AND session.ends_on
          WHERE teacher.id=NEW.teacher_membership_id AND teacher.organisation_id=NEW.organisation_id
            AND teacher.school_id=NEW.school_id AND teacher.status='ACTIVE'
            AND teacher.valid_from<=clock_timestamp()
            AND (teacher.valid_until IS NULL OR teacher.valid_until>clock_timestamp())
            AND role.code='teacher'
            AND (role.organisation_id IS NULL OR role.organisation_id=teacher.organisation_id)
        ) THEN RAISE EXCEPTION 'ptm teacher and academic context must remain active'; END IF;
        SELECT count(*) INTO slot_count FROM ptm_slots WHERE ptm_event_id=NEW.id AND status='AVAILABLE';
        IF slot_count<1 THEN RAISE EXCEPTION 'ptm event requires an available slot'; END IF;
        IF EXISTS(SELECT 1 FROM ptm_slots slot WHERE slot.ptm_event_id=NEW.id
          AND ((slot.starts_at AT TIME ZONE NEW.time_zone_snapshot)::date<>NEW.event_date
            OR ((slot.ends_at-INTERVAL '1 microsecond') AT TIME ZONE NEW.time_zone_snapshot)::date<>NEW.event_date
            OR slot.starts_at<=clock_timestamp() OR slot.starts_at<NEW.changes_close_at)) THEN
            RAISE EXCEPTION 'ptm event slots must be future, on the event date, and after the change deadline';
        END IF;
        IF EXISTS(SELECT 1 FROM ptm_slots first_slot JOIN ptm_slots second_slot
          ON second_slot.ptm_event_id=first_slot.ptm_event_id AND second_slot.id<>first_slot.id
          AND tstzrange(first_slot.starts_at,first_slot.ends_at,'[)') && tstzrange(second_slot.starts_at,second_slot.ends_at,'[)')
          WHERE first_slot.ptm_event_id=NEW.id AND first_slot.status='AVAILABLE' AND second_slot.status='AVAILABLE') THEN
            RAISE EXCEPTION 'ptm event slots overlap';
        END IF;
        IF EXISTS(SELECT 1 FROM ptm_events other JOIN ptm_slots other_slot ON other_slot.ptm_event_id=other.id
          JOIN ptm_slots new_slot ON new_slot.ptm_event_id=NEW.id
          WHERE other.id<>NEW.id AND other.teacher_membership_id=NEW.teacher_membership_id
            AND other.status='PUBLISHED' AND other_slot.status='AVAILABLE' AND new_slot.status='AVAILABLE'
            AND tstzrange(other_slot.starts_at,other_slot.ends_at,'[)') && tstzrange(new_slot.starts_at,new_slot.ends_at,'[)')) THEN
            RAISE EXCEPTION 'teacher already has an overlapping published ptm slot';
        END IF;
    ELSIF OLD.status='PUBLISHED' AND NEW.status='CANCELLED' THEN
        IF OLD.published_by_membership_id IS DISTINCT FROM NEW.published_by_membership_id
          OR OLD.published_at IS DISTINCT FROM NEW.published_at
          OR OLD.publication_reason IS DISTINCT FROM NEW.publication_reason THEN
            RAISE EXCEPTION 'ptm publication evidence is immutable';
        END IF;
    ELSE RAISE EXCEPTION 'invalid ptm event transition';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER ptm_events_state_guard BEFORE UPDATE OR DELETE ON ptm_events
    FOR EACH ROW EXECUTE FUNCTION guard_ptm_event_state();

CREATE OR REPLACE FUNCTION guard_ptm_slot_history()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'ptm slots cannot be deleted'; END IF;
    IF OLD.id<>NEW.id OR OLD.organisation_id<>NEW.organisation_id OR OLD.school_id<>NEW.school_id
       OR OLD.ptm_event_id<>NEW.ptm_event_id OR OLD.starts_at<>NEW.starts_at OR OLD.ends_at<>NEW.ends_at
       OR OLD.created_at<>NEW.created_at THEN RAISE EXCEPTION 'ptm slot time and identity are immutable'; END IF;
    IF NEW.row_version<>OLD.row_version+1 OR OLD.status<>'AVAILABLE' OR NEW.status<>'CANCELLED' THEN
        RAISE EXCEPTION 'invalid ptm slot transition';
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER ptm_slots_history_guard BEFORE UPDATE OR DELETE ON ptm_slots
    FOR EACH ROW EXECUTE FUNCTION guard_ptm_slot_history();

CREATE OR REPLACE FUNCTION guard_ptm_booking_state()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE event_row ptm_events%ROWTYPE; slot_row ptm_slots%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'ptm bookings cannot be deleted'; END IF;
    SELECT * INTO event_row FROM ptm_events WHERE id=NEW.ptm_event_id FOR SHARE;
    SELECT * INTO slot_row FROM ptm_slots WHERE id=NEW.ptm_slot_id FOR UPDATE;
    IF slot_row.ptm_event_id<>NEW.ptm_event_id THEN RAISE EXCEPTION 'ptm slot does not belong to event'; END IF;
    IF TG_OP='UPDATE' THEN
        IF OLD.id<>NEW.id OR OLD.organisation_id<>NEW.organisation_id OR OLD.school_id<>NEW.school_id
           OR OLD.ptm_event_id<>NEW.ptm_event_id OR OLD.learner_id<>NEW.learner_id
           OR OLD.guardian_id<>NEW.guardian_id OR OLD.booked_by_membership_id<>NEW.booked_by_membership_id
           OR OLD.topic IS DISTINCT FROM NEW.topic OR OLD.idempotency_key<>NEW.idempotency_key
           OR OLD.request_fingerprint<>NEW.request_fingerprint OR OLD.booked_at<>NEW.booked_at THEN
            RAISE EXCEPTION 'ptm booking identity is immutable';
        END IF;
        IF NEW.row_version<>OLD.row_version+1 OR OLD.status<>'BOOKED'
           OR NEW.status NOT IN('BOOKED','CANCELLED') THEN RAISE EXCEPTION 'invalid ptm booking transition'; END IF;
    END IF;
    IF NEW.status='BOOKED' THEN
        IF event_row.status<>'PUBLISHED' OR slot_row.status<>'AVAILABLE'
           OR clock_timestamp()<event_row.booking_opens_at
           OR (TG_OP='INSERT' AND clock_timestamp()>event_row.booking_closes_at)
           OR (TG_OP='UPDATE' AND clock_timestamp()>event_row.changes_close_at)
           OR slot_row.starts_at<=clock_timestamp() THEN RAISE EXCEPTION 'ptm booking window is closed'; END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended('ptm-guardian:'||NEW.guardian_id::text,0));
        IF NOT EXISTS(SELECT 1 FROM learner_guardian_relationships relationship
          WHERE relationship.organisation_id=NEW.organisation_id AND relationship.school_id=NEW.school_id
            AND relationship.learner_id=NEW.learner_id AND relationship.guardian_id=NEW.guardian_id
            AND relationship.portal_access_status='ACTIVE') THEN RAISE EXCEPTION 'verified guardian relationship required'; END IF;
        IF NOT EXISTS(SELECT 1 FROM student_section_placements placement JOIN student_enrolments enrolment
          ON enrolment.id=placement.enrolment_id AND enrolment.organisation_id=placement.organisation_id
          AND enrolment.school_id=placement.school_id
          WHERE placement.organisation_id=NEW.organisation_id AND placement.school_id=NEW.school_id
            AND placement.learner_id=NEW.learner_id AND placement.class_section_id=event_row.class_section_id
            AND placement.status='ACTIVE' AND placement.starts_on<=event_row.event_date
            AND (placement.ends_on IS NULL OR placement.ends_on>=event_row.event_date)
            AND enrolment.status='ACTIVE' AND enrolment.academic_session_id=event_row.academic_session_id) THEN
            RAISE EXCEPTION 'learner is not placed in the ptm class';
        END IF;
        IF EXISTS(SELECT 1 FROM ptm_bookings other JOIN ptm_slots other_slot ON other_slot.id=other.ptm_slot_id
          WHERE other.id<>NEW.id AND other.guardian_id=NEW.guardian_id AND other.status='BOOKED'
            AND tstzrange(other_slot.starts_at,other_slot.ends_at,'[)') && tstzrange(slot_row.starts_at,slot_row.ends_at,'[)')) THEN
            RAISE EXCEPTION 'guardian already has an overlapping ptm booking';
        END IF;
    END IF;
    RETURN NEW;
END; $$;

CREATE TRIGGER ptm_bookings_state_guard BEFORE INSERT OR UPDATE OR DELETE ON ptm_bookings
    FOR EACH ROW EXECUTE FUNCTION guard_ptm_booking_state();

CREATE OR REPLACE FUNCTION guard_ptm_append_only()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'ptm booking history is append-only'; END; $$;
CREATE TRIGGER ptm_booking_history_append_only BEFORE UPDATE OR DELETE ON ptm_booking_history
    FOR EACH ROW EXECUTE FUNCTION guard_ptm_append_only();

CREATE OR REPLACE FUNCTION guard_ptm_reminder_state()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'ptm reminders cannot be deleted'; END IF;
    IF OLD.status<>'PENDING' THEN RAISE EXCEPTION 'completed ptm reminders are immutable'; END IF;
    IF NEW.row_version<>OLD.row_version+1 OR NEW.status NOT IN('PENDING','MATERIALIZED','CANCELLED','FAILED') THEN
        RAISE EXCEPTION 'invalid ptm reminder transition';
    END IF;
    IF OLD.id<>NEW.id OR OLD.organisation_id<>NEW.organisation_id OR OLD.school_id<>NEW.school_id
       OR OLD.ptm_booking_id<>NEW.ptm_booking_id OR OLD.ptm_slot_id<>NEW.ptm_slot_id
       OR OLD.due_at<>NEW.due_at OR OLD.created_at<>NEW.created_at THEN
        RAISE EXCEPTION 'ptm reminder identity is immutable';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER ptm_booking_reminders_state_guard BEFORE UPDATE OR DELETE ON ptm_booking_reminders
    FOR EACH ROW EXECUTE FUNCTION guard_ptm_reminder_state();

ALTER TABLE ptm_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE ptm_slots ENABLE ROW LEVEL SECURITY;
ALTER TABLE ptm_bookings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ptm_booking_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE ptm_booking_reminders ENABLE ROW LEVEL SECURITY;
ALTER TABLE ptm_events FORCE ROW LEVEL SECURITY;
ALTER TABLE ptm_slots FORCE ROW LEVEL SECURITY;
ALTER TABLE ptm_bookings FORCE ROW LEVEL SECURITY;
ALTER TABLE ptm_booking_history FORCE ROW LEVEL SECURITY;
ALTER TABLE ptm_booking_reminders FORCE ROW LEVEL SECURITY;

CREATE POLICY ptm_events_tenant_all ON ptm_events USING(organisation_id=current_app_organisation_id())
    WITH CHECK(organisation_id=current_app_organisation_id());
CREATE POLICY ptm_slots_tenant_all ON ptm_slots USING(organisation_id=current_app_organisation_id())
    WITH CHECK(organisation_id=current_app_organisation_id());
CREATE POLICY ptm_bookings_tenant_all ON ptm_bookings USING(organisation_id=current_app_organisation_id())
    WITH CHECK(organisation_id=current_app_organisation_id());
CREATE POLICY ptm_booking_history_tenant_all ON ptm_booking_history USING(organisation_id=current_app_organisation_id())
    WITH CHECK(organisation_id=current_app_organisation_id());
CREATE POLICY ptm_booking_reminders_tenant_all ON ptm_booking_reminders USING(organisation_id=current_app_organisation_id())
    WITH CHECK(organisation_id=current_app_organisation_id());

COMMENT ON TABLE ptm_events IS 'Immutable-after-publication class and teacher meeting schedules.';
COMMENT ON TABLE ptm_slots IS 'Short immutable PTM appointment windows generated with an event.';
COMMENT ON TABLE ptm_bookings IS 'Relationship-scoped family appointments corrected by reschedule or cancellation.';
COMMENT ON TABLE ptm_booking_history IS 'Append-only PTM booking and rescheduling evidence.';
COMMENT ON TABLE ptm_booking_reminders IS 'Revocable scheduled reminder jobs materialized only while a booking remains active.';

-- Keep reviewed announcements unchanged; automatic PTM reminders have explicit source authority, not invented reviewers.
ALTER TABLE communication_announcements
    ADD COLUMN authority_kind VARCHAR(20) NOT NULL DEFAULT 'HUMAN_REVIEW',
    ADD COLUMN ptm_reminder_id UUID,
    ADD COLUMN domain_event_id UUID REFERENCES outbox_events(id),
    ALTER COLUMN created_by_membership_id DROP NOT NULL,
    ADD CONSTRAINT communication_ptm_reminder_fk FOREIGN KEY(ptm_reminder_id,organisation_id,school_id)
        REFERENCES ptm_booking_reminders(id,organisation_id,school_id),
    ADD CONSTRAINT communication_domain_event_unique UNIQUE(domain_event_id);

ALTER TABLE communication_announcements DROP CONSTRAINT communication_announcements_state_valid;
ALTER TABLE communication_announcements ADD CONSTRAINT communication_announcements_state_valid CHECK (
    (authority_kind='HUMAN_REVIEW' AND created_by_membership_id IS NOT NULL
     AND ptm_reminder_id IS NULL AND domain_event_id IS NULL AND (
        (status='DRAFT' AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status='PENDING_APPROVAL' AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status IN ('APPROVED','REJECTED') AND submitted_by_membership_id IS NOT NULL AND submitted_at IS NOT NULL
            AND decided_by_membership_id IS NOT NULL AND decided_at IS NOT NULL AND decision_reason IS NOT NULL
            AND decided_by_membership_id<>created_by_membership_id
            AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL)
        OR (status='CANCELLED' AND cancelled_by_membership_id IS NOT NULL
            AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL)))
    OR (authority_kind='DOMAIN_EVENT' AND status='APPROVED' AND ptm_reminder_id IS NOT NULL
        AND domain_event_id IS NOT NULL AND source_type IS NOT NULL AND source_id IS NOT NULL
        AND source_type='PTM_REMINDER' AND source_id=ptm_reminder_id
        AND created_by_membership_id IS NULL AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
        AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
        AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL
        AND communication_template_id IS NULL AND audience_kind='LEARNER_FAMILIES' AND audience_reference_id IS NOT NULL
        AND category='ACADEMIC' AND priority='NORMAL' AND language_mode='BILINGUAL'
        AND NOT requires_acknowledgement AND NOT request_email AND NOT request_sms AND NOT request_whatsapp
        AND expires_at IS NOT NULL)
);

CREATE FUNCTION guard_communication_domain_authority() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF OLD.authority_kind IS DISTINCT FROM NEW.authority_kind
           OR OLD.ptm_reminder_id IS DISTINCT FROM NEW.ptm_reminder_id
           OR OLD.domain_event_id IS DISTINCT FROM NEW.domain_event_id
           OR OLD.created_by_membership_id IS DISTINCT FROM NEW.created_by_membership_id
           OR OLD.authority_kind='DOMAIN_EVENT' THEN
            RAISE EXCEPTION 'communication authority and domain publications are immutable';
        END IF;
    ELSIF NEW.authority_kind='DOMAIN_EVENT' THEN
        IF NOT EXISTS (
            SELECT 1 FROM ptm_booking_reminders reminder
            JOIN ptm_bookings booking ON booking.id=reminder.ptm_booking_id
              AND booking.organisation_id=reminder.organisation_id AND booking.school_id=reminder.school_id
            JOIN ptm_events event ON event.id=booking.ptm_event_id
              AND event.organisation_id=booking.organisation_id AND event.school_id=booking.school_id
            JOIN ptm_slots slot ON slot.id=reminder.ptm_slot_id
              AND slot.organisation_id=reminder.organisation_id AND slot.school_id=reminder.school_id
            JOIN outbox_events evidence ON evidence.id=NEW.domain_event_id
              AND evidence.aggregate_type='PTM_REMINDER' AND evidence.aggregate_id=reminder.id::text
              AND evidence.event_type='ptm.booking_reminder'
              AND evidence.payload->>'organisationId'=reminder.organisation_id::text
              AND evidence.payload->>'schoolId'=reminder.school_id::text
            WHERE reminder.id=NEW.ptm_reminder_id AND reminder.organisation_id=NEW.organisation_id
              AND reminder.school_id=NEW.school_id AND reminder.status='MATERIALIZED'
              AND booking.status='BOOKED' AND event.status='PUBLISHED' AND slot.status='AVAILABLE'
              AND booking.ptm_slot_id=reminder.ptm_slot_id AND booking.learner_id=NEW.audience_reference_id
              AND NEW.scheduled_for=evidence.occurred_at AND NEW.expires_at=slot.starts_at
              AND slot.starts_at>clock_timestamp()
        ) THEN RAISE EXCEPTION 'PTM notification requires current scoped source evidence'; END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER communication_domain_authority_guard BEFORE INSERT OR UPDATE ON communication_announcements
    FOR EACH ROW EXECUTE FUNCTION guard_communication_domain_authority();

CREATE FUNCTION guard_communication_domain_recipient() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM communication_announcements notice WHERE notice.id=NEW.communication_announcement_id
        AND notice.authority_kind='DOMAIN_EVENT') AND NOT EXISTS (
        SELECT 1 FROM communication_announcements notice
        JOIN ptm_booking_reminders reminder ON reminder.id=notice.ptm_reminder_id
          AND reminder.organisation_id=notice.organisation_id AND reminder.school_id=notice.school_id
        JOIN ptm_bookings booking ON booking.id=reminder.ptm_booking_id
          AND booking.organisation_id=reminder.organisation_id AND booking.school_id=reminder.school_id
        JOIN guardians guardian ON guardian.id=booking.guardian_id
          AND guardian.organisation_id=booking.organisation_id AND guardian.school_id=booking.school_id
        WHERE notice.id=NEW.communication_announcement_id AND notice.organisation_id=NEW.organisation_id
          AND notice.school_id=NEW.school_id AND NEW.recipient_kind='GUARDIAN'
          AND NEW.guardian_id=booking.guardian_id AND NEW.learner_id=booking.learner_id
          AND NEW.user_account_id=guardian.user_account_id
    ) THEN RAISE EXCEPTION 'PTM notification recipient must be the booking guardian'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER communication_domain_recipient_guard BEFORE INSERT ON communication_recipients
    FOR EACH ROW EXECUTE FUNCTION guard_communication_domain_recipient();

ALTER TABLE communication_announcement_history
    ADD COLUMN actor_kind VARCHAR(10) NOT NULL DEFAULT 'HUMAN',
    ALTER COLUMN actor_membership_id DROP NOT NULL,
    ADD CONSTRAINT communication_history_actor_kind_valid CHECK (
        (actor_kind='HUMAN' AND actor_membership_id IS NOT NULL)
        OR (actor_kind='SYSTEM' AND actor_membership_id IS NULL AND event_type='CREATED'
            AND previous_status IS NULL AND new_status='APPROVED'));
CREATE UNIQUE INDEX communication_domain_publication_once ON communication_announcement_history(communication_announcement_id)
    WHERE actor_kind='SYSTEM';
CREATE FUNCTION guard_communication_domain_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE domain_notice BOOLEAN;
BEGIN
    SELECT authority_kind='DOMAIN_EVENT' INTO domain_notice FROM communication_announcements
      WHERE id=NEW.communication_announcement_id AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id;
    IF domain_notice IS DISTINCT FROM (NEW.actor_kind='SYSTEM') THEN
        RAISE EXCEPTION 'communication history actor must match publication authority';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER communication_domain_history_guard BEFORE INSERT ON communication_announcement_history
    FOR EACH ROW EXECUTE FUNCTION guard_communication_domain_history();
CREATE FUNCTION require_communication_domain_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.authority_kind='DOMAIN_EVENT' AND NOT EXISTS (
        SELECT 1 FROM communication_announcement_history history WHERE history.communication_announcement_id=NEW.id
          AND history.organisation_id=NEW.organisation_id AND history.school_id=NEW.school_id AND history.actor_kind='SYSTEM'
    ) THEN RAISE EXCEPTION 'domain publication requires same-transaction system history'; END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER communication_domain_history_required AFTER INSERT ON communication_announcements
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_communication_domain_history();

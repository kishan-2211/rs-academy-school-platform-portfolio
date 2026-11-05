-- Extend verified source authority; existing human/PTM/family branches remain intact.
DO $$
DECLARE previous_check TEXT;
BEGIN
  SELECT pg_get_expr(conbin,conrelid) INTO previous_check FROM pg_constraint
    WHERE conrelid='communication_announcements'::regclass AND conname='communication_announcements_state_valid';
  IF previous_check IS NULL THEN RAISE EXCEPTION 'Existing communication authority constraint is required'; END IF;
  ALTER TABLE communication_announcements DROP CONSTRAINT communication_announcements_state_valid;
  EXECUTE 'ALTER TABLE communication_announcements ADD CONSTRAINT communication_announcements_state_valid CHECK (('
    ||previous_check||') OR (authority_kind=''DOMAIN_EVENT'' AND status=''APPROVED''
    AND source_type IS NOT NULL AND source_type=''STUDENT_LEAVE_SUBMISSION''
    AND source_id IS NOT NULL AND domain_event_id IS NOT NULL AND source_id=domain_event_id
    AND student_leave_request_id IS NOT NULL AND student_leave_history_id IS NOT NULL AND ptm_reminder_id IS NULL
    AND parent_request_id IS NULL AND parent_request_history_id IS NULL AND parent_request_message_id IS NULL
    AND created_by_membership_id IS NULL AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
    AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
    AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL
    AND communication_template_id IS NULL AND audience_kind=''ALL_STAFF'' AND audience_reference_id IS NULL
    AND category=''ATTENDANCE'' AND priority=''NORMAL'' AND language_mode=''BILINGUAL'' AND request_push
    AND NOT requires_acknowledgement AND NOT request_email AND NOT request_sms AND NOT request_whatsapp
    AND expires_at IS NOT NULL))';
END;
$$;

DROP TRIGGER communication_domain_authority_guard ON communication_announcements;
CREATE TRIGGER communication_domain_authority_guard BEFORE INSERT OR UPDATE ON communication_announcements
  FOR EACH ROW WHEN (NEW.source_type IS DISTINCT FROM 'PARENT_REQUEST_UPDATE'
    AND NEW.source_type IS DISTINCT FROM 'STUDENT_LEAVE_UPDATE' AND NEW.source_type IS DISTINCT FROM 'STUDENT_LEAVE_SUBMISSION')
  EXECUTE FUNCTION guard_communication_domain_authority();

CREATE FUNCTION guard_communication_leave_submission_authority() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='UPDATE' AND (OLD.source_type='STUDENT_LEAVE_SUBMISSION' OR NEW.source_type='STUDENT_LEAVE_SUBMISSION') THEN
    RAISE EXCEPTION 'student leave submission publications are immutable';
  ELSIF TG_OP='INSERT' AND NEW.source_type='STUDENT_LEAVE_SUBMISSION' THEN
    IF NOT EXISTS(SELECT 1 FROM student_leave_submission_notification_sources source
      WHERE source.event_id=NEW.domain_event_id AND source.request_id=NEW.student_leave_request_id
        AND source.history_id=NEW.student_leave_history_id AND source.organisation_id=NEW.organisation_id
        AND source.school_id=NEW.school_id AND source.occurred_at=NEW.scheduled_for AND source.expires_at=NEW.expires_at
        AND source.occurred_at<=clock_timestamp() AND source.expires_at>clock_timestamp()) THEN
      RAISE EXCEPTION 'student leave reviewer alert requires current scoped submission evidence';
    END IF;
    IF NEW.title_en IS DISTINCT FROM 'Student leave needs review'
      OR NEW.title_hi IS DISTINCT FROM 'विद्यार्थी अवकाश की समीक्षा आवश्यक है'
      OR NEW.body_en IS DISTINCT FROM 'Open Student leave in the school workspace to review current pending requests.'
      OR NEW.body_hi IS DISTINCT FROM 'वर्तमान लंबित अनुरोधों की समीक्षा के लिए स्कूल कार्यक्षेत्र में विद्यार्थी अवकाश खोलें।' THEN
      RAISE EXCEPTION 'student leave reviewer alerts require privacy-minimal generic content';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER communication_leave_submission_authority_guard BEFORE INSERT OR UPDATE ON communication_announcements
  FOR EACH ROW EXECUTE FUNCTION guard_communication_leave_submission_authority();

CREATE OR REPLACE FUNCTION guard_communication_domain_recipient() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE notice communication_announcements%ROWTYPE;
BEGIN
  SELECT * INTO notice FROM communication_announcements WHERE id=NEW.communication_announcement_id;
  IF notice.authority_kind='DOMAIN_EVENT' THEN
    IF notice.source_type='STUDENT_LEAVE_SUBMISSION' THEN
      -- Serialize additions to this notice before enforcing one recipient per person.
      PERFORM 1 FROM communication_announcements WHERE id=notice.id FOR UPDATE;
      IF NOT EXISTS(SELECT 1 FROM student_leave_submission_notification_reviewers reviewer
        WHERE reviewer.event_id=notice.domain_event_id AND reviewer.organisation_id=NEW.organisation_id
          AND reviewer.school_id=NEW.school_id AND NEW.recipient_kind='STAFF'
          AND reviewer.membership_id=NEW.membership_id AND reviewer.user_account_id=NEW.user_account_id
          AND reviewer.occurred_at<=clock_timestamp() AND reviewer.expires_at>clock_timestamp()) THEN
        RAISE EXCEPTION 'student leave reviewer recipient requires current independent school authority';
      END IF;
      IF EXISTS(SELECT 1 FROM communication_recipients recipient
        WHERE recipient.communication_announcement_id=notice.id AND recipient.user_account_id=NEW.user_account_id) THEN
        RAISE EXCEPTION 'student leave reviewer alert is limited to one recipient per person';
      END IF;
    ELSIF notice.source_type='STUDENT_LEAVE_UPDATE' THEN
      IF NOT EXISTS(SELECT 1 FROM student_leave_notification_sources source
        WHERE source.event_id=notice.domain_event_id AND source.organisation_id=NEW.organisation_id
          AND source.school_id=NEW.school_id AND NEW.recipient_kind='GUARDIAN'
          AND source.guardian_id=NEW.guardian_id AND source.learner_id=NEW.learner_id
          AND source.user_account_id=NEW.user_account_id) THEN
        RAISE EXCEPTION 'student leave recipient must be the current requesting guardian';
      END IF;
    ELSIF notice.source_type='PARENT_REQUEST_UPDATE' THEN
      IF NOT EXISTS(SELECT 1 FROM parent_request_notification_sources source
        WHERE source.event_id=notice.domain_event_id AND source.organisation_id=NEW.organisation_id
          AND source.school_id=NEW.school_id AND NEW.recipient_kind='GUARDIAN'
          AND source.guardian_id=NEW.guardian_id AND source.learner_id=NEW.learner_id
          AND source.user_account_id=NEW.user_account_id) THEN
        RAISE EXCEPTION 'request notification recipient must be the current request guardian';
      END IF;
    ELSIF NOT EXISTS(
      SELECT 1 FROM ptm_booking_reminders reminder JOIN ptm_bookings booking ON booking.id=reminder.ptm_booking_id
        AND booking.organisation_id=reminder.organisation_id AND booking.school_id=reminder.school_id
      JOIN guardians guardian ON guardian.id=booking.guardian_id
        AND guardian.organisation_id=booking.organisation_id AND guardian.school_id=booking.school_id
      WHERE reminder.id=notice.ptm_reminder_id AND reminder.organisation_id=notice.organisation_id
        AND reminder.school_id=notice.school_id AND notice.organisation_id=NEW.organisation_id
        AND notice.school_id=NEW.school_id AND NEW.recipient_kind='GUARDIAN'
        AND NEW.guardian_id=booking.guardian_id AND NEW.learner_id=booking.learner_id
        AND NEW.user_account_id=guardian.user_account_id
    ) THEN RAISE EXCEPTION 'PTM notification recipient must be the booking guardian'; END IF;
  END IF;
  RETURN NEW;
END;
$$;

-- The existing append-only history, source foreign keys, unique domain_event_id,
-- recipient immutability and deferred same-transaction SYSTEM history remain enforced.

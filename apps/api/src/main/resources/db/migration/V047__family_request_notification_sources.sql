-- A source projection, not a second inbox or delivery store. Security-invoker preserves underlying RLS.
CREATE VIEW parent_request_notification_sources WITH (security_invoker=true) AS
SELECT event_id,request_id,history_id,message_id,organisation_id,school_id,guardian_id,learner_id,
       user_account_id,category,occurred_at,expires_at
FROM (
    SELECT evidence.id event_id,request.id request_id,history.id history_id,message.id message_id,
      request.organisation_id,request.school_id,guardian.id guardian_id,request.learner_id,
      guardian.user_account_id,
      CASE WHEN request.category IN ('FEES','ATTENDANCE','TRANSPORT') THEN request.category
           WHEN request.category IN ('ACADEMICS','HOMEWORK') THEN 'ACADEMIC' ELSE 'GENERAL' END category,
      evidence.occurred_at,evidence.occurred_at+interval '7 days' expires_at,
      count(*) OVER (PARTITION BY evidence.id) candidate_count
    FROM outbox_events evidence
    JOIN parent_requests request ON request.id::text=evidence.aggregate_id
    JOIN parent_request_history history ON history.parent_request_id=request.id
      AND history.organisation_id=request.organisation_id AND history.school_id=request.school_id
      AND history.occurred_at=evidence.occurred_at
      AND (NOT (evidence.payload ? 'historyId') OR history.id::text=evidence.payload->>'historyId')
      AND history.event_type=CASE evidence.event_type
        WHEN 'parent_request.staff_replied' THEN 'STAFF_REPLIED'
        WHEN 'parent_request.resolve' THEN 'RESOLVED'
        WHEN 'parent_request.close' THEN 'CLOSED'
        WHEN 'parent_request.reopen' THEN 'REOPENED' END
    LEFT JOIN parent_request_messages message ON evidence.event_type='parent_request.staff_replied'
      AND message.parent_request_id=request.id AND message.organisation_id=request.organisation_id
      AND message.school_id=request.school_id AND message.author_type='STAFF' AND NOT message.internal_note
      AND message.author_membership_id=history.actor_membership_id AND message.created_at=evidence.occurred_at
      AND (NOT (evidence.payload ? 'messageId') OR message.id::text=evidence.payload->>'messageId')
    JOIN guardians guardian ON guardian.id=request.opened_by_guardian_id
      AND guardian.organisation_id=request.organisation_id AND guardian.school_id=request.school_id
    JOIN learners learner ON learner.id=request.learner_id
      AND learner.organisation_id=request.organisation_id AND learner.school_id=request.school_id
    JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
      AND relationship.learner_id=learner.id AND relationship.organisation_id=request.organisation_id
      AND relationship.school_id=request.school_id
    JOIN user_accounts account ON account.id=guardian.user_account_id
    JOIN schools school ON school.id=request.school_id AND school.organisation_id=request.organisation_id
    JOIN organisations organisation ON organisation.id=request.organisation_id
    WHERE evidence.aggregate_type='PARENT_REQUEST'
      AND evidence.payload->>'schoolId'=request.school_id::text
      AND (NOT (evidence.payload ? 'organisationId') OR evidence.payload->>'organisationId'=request.organisation_id::text)
      AND (evidence.event_type<>'parent_request.staff_replied' OR
           (message.id IS NOT NULL AND history.reason='Response sent to family'))
      AND guardian.status='ACTIVE' AND learner.status='ACTIVE' AND relationship.portal_access_status='ACTIVE'
      AND account.status='ACTIVE' AND school.status='ACTIVE' AND organisation.status='ACTIVE'
      AND EXISTS (SELECT 1 FROM memberships membership
        JOIN membership_roles mr ON mr.membership_id=membership.id
        JOIN roles role ON role.id=mr.role_id JOIN role_capabilities capability ON capability.role_id=role.id
        WHERE membership.user_id=guardian.user_account_id AND membership.organisation_id=request.organisation_id
          AND membership.school_id=request.school_id AND membership.status='ACTIVE'
          AND membership.valid_from<=CURRENT_TIMESTAMP AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
          AND (role.organisation_id IS NULL OR role.organisation_id=request.organisation_id)
          AND capability.capability_code='portal.guardian')
) candidate WHERE candidate_count=1;

ALTER TABLE communication_announcements
    ADD COLUMN parent_request_id UUID,
    ADD COLUMN parent_request_history_id UUID REFERENCES parent_request_history(id),
    ADD COLUMN parent_request_message_id UUID,
    ADD CONSTRAINT communication_request_fk FOREIGN KEY(parent_request_id,organisation_id,school_id)
        REFERENCES parent_requests(id,organisation_id,school_id),
    ADD CONSTRAINT communication_request_message_fk FOREIGN KEY(parent_request_message_id,organisation_id,school_id)
        REFERENCES parent_request_messages(id,organisation_id,school_id);

-- Preserve V046's entire existing constraint as a named function-free branch, extending only the new source.
DO $$
DECLARE previous_check TEXT;
BEGIN
    SELECT pg_get_expr(conbin,conrelid) INTO previous_check FROM pg_constraint
      WHERE conrelid='communication_announcements'::regclass AND conname='communication_announcements_state_valid';
    IF previous_check IS NULL THEN RAISE EXCEPTION 'V046 communication authority constraint is required'; END IF;
    ALTER TABLE communication_announcements DROP CONSTRAINT communication_announcements_state_valid;
    EXECUTE 'ALTER TABLE communication_announcements ADD CONSTRAINT communication_announcements_state_valid CHECK (('
      ||previous_check||') AND parent_request_id IS NULL AND parent_request_history_id IS NULL AND parent_request_message_id IS NULL
      OR (authority_kind=''DOMAIN_EVENT'' AND status=''APPROVED'' AND source_type IS NOT NULL
      AND source_type=''PARENT_REQUEST_UPDATE'' AND source_id IS NOT NULL AND domain_event_id IS NOT NULL AND source_id=domain_event_id
      AND parent_request_id IS NOT NULL AND parent_request_history_id IS NOT NULL AND ptm_reminder_id IS NULL
      AND created_by_membership_id IS NULL AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
      AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
      AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL
      AND communication_template_id IS NULL AND audience_kind=''LEARNER_FAMILIES'' AND audience_reference_id IS NOT NULL
      AND category IN (''GENERAL'',''ACADEMIC'',''ATTENDANCE'',''FEES'',''TRANSPORT'') AND priority=''NORMAL'' AND language_mode=''BILINGUAL''
      AND NOT requires_acknowledgement AND NOT request_email AND NOT request_sms AND NOT request_whatsapp AND expires_at IS NOT NULL))';
END;
$$;

-- Existing PTM validation remains intact. Request publications have their own equally scoped source guard.
DROP TRIGGER communication_domain_authority_guard ON communication_announcements;
CREATE TRIGGER communication_domain_authority_guard BEFORE INSERT OR UPDATE ON communication_announcements
    FOR EACH ROW WHEN (NEW.source_type IS DISTINCT FROM 'PARENT_REQUEST_UPDATE')
    EXECUTE FUNCTION guard_communication_domain_authority();
CREATE FUNCTION guard_communication_request_authority() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF OLD.parent_request_id IS DISTINCT FROM NEW.parent_request_id
           OR OLD.parent_request_history_id IS DISTINCT FROM NEW.parent_request_history_id
           OR OLD.parent_request_message_id IS DISTINCT FROM NEW.parent_request_message_id
           OR OLD.source_type='PARENT_REQUEST_UPDATE' THEN
            RAISE EXCEPTION 'request publication and source evidence are immutable';
        END IF;
    ELSIF NEW.source_type='PARENT_REQUEST_UPDATE' THEN
        IF NOT EXISTS (SELECT 1 FROM parent_request_notification_sources source
          WHERE source.event_id=NEW.domain_event_id AND source.request_id=NEW.parent_request_id
            AND source.history_id=NEW.parent_request_history_id
            AND source.message_id IS NOT DISTINCT FROM NEW.parent_request_message_id
            AND source.organisation_id=NEW.organisation_id AND source.school_id=NEW.school_id
            AND source.learner_id=NEW.audience_reference_id AND source.category=NEW.category
            AND source.occurred_at=NEW.scheduled_for AND source.expires_at=NEW.expires_at
            AND source.expires_at>clock_timestamp()) THEN
            RAISE EXCEPTION 'request notification requires current scoped public event evidence';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER communication_request_authority_guard BEFORE INSERT OR UPDATE ON communication_announcements
    FOR EACH ROW EXECUTE FUNCTION guard_communication_request_authority();

CREATE OR REPLACE FUNCTION guard_communication_domain_recipient() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE notice communication_announcements%ROWTYPE;
BEGIN
    SELECT * INTO notice FROM communication_announcements WHERE id=NEW.communication_announcement_id;
    IF notice.authority_kind='DOMAIN_EVENT' THEN
        IF notice.source_type='PARENT_REQUEST_UPDATE' THEN
            IF NOT EXISTS (SELECT 1 FROM parent_request_notification_sources source
              WHERE source.event_id=notice.domain_event_id AND source.organisation_id=NEW.organisation_id
                AND source.school_id=NEW.school_id AND NEW.recipient_kind='GUARDIAN'
                AND source.guardian_id=NEW.guardian_id AND source.learner_id=NEW.learner_id
                AND source.user_account_id=NEW.user_account_id) THEN
                RAISE EXCEPTION 'request notification recipient must be the current request guardian';
            END IF;
        ELSIF NOT EXISTS (
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

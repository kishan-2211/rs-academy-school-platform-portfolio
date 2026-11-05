-- Add source-proven learning alerts to the existing inbox/outbox. No historical
-- publication is backfilled and the frozen assignment learner audience is unchanged.
CREATE UNIQUE INDEX learning_publication_outbox_once
  ON outbox_events(aggregate_id,(payload->>'publicationRevision'),(payload->>'audienceKind'))
  WHERE aggregate_type='ASSIGNMENT' AND event_type='learning.assignment.publication';

CREATE VIEW learning_publication_notification_sources WITH (security_invoker=true) AS
SELECT evidence.id event_id,assignment.id assignment_id,history.id history_id,
  assignment.organisation_id,assignment.school_id,assignment.class_section_id,
  history.publication_revision,evidence.payload->>'audienceKind' audience_kind,
  greatest(history.occurred_at,assignment.available_from) scheduled_for,assignment.due_at expires_at
FROM outbox_events evidence
JOIN assignments assignment ON assignment.id::text=evidence.aggregate_id
JOIN assignment_revision_history history ON history.assignment_id=assignment.id
  AND history.organisation_id=assignment.organisation_id AND history.school_id=assignment.school_id
  AND history.publication_revision=assignment.publication_revision
JOIN academic_sessions session ON session.id=assignment.academic_session_id
  AND session.organisation_id=assignment.organisation_id AND session.school_id=assignment.school_id
JOIN schools school ON school.id=assignment.school_id AND school.organisation_id=assignment.organisation_id
JOIN organisations organisation ON organisation.id=assignment.organisation_id
WHERE evidence.aggregate_type='ASSIGNMENT' AND evidence.event_type='learning.assignment.publication'
  AND evidence.payload->>'audienceKind' IN ('SECTION_FAMILIES','SECTION_STUDENTS')
  AND evidence.payload=jsonb_build_object('organisationId',assignment.organisation_id,'schoolId',assignment.school_id,
    'assignmentId',assignment.id,'historyId',history.id,'publicationRevision',history.publication_revision,
    'audienceKind',evidence.payload->>'audienceKind')
  AND evidence.occurred_at=history.occurred_at
  AND (history.assignment_snapshot->>'publicationRevision')=history.publication_revision::text
  AND history.assignment_snapshot->'availableFrom'=to_jsonb(assignment.available_from)
  AND history.assignment_snapshot->'dueAt'=to_jsonb(assignment.due_at)
  AND history.change_kind IN ('PUBLISHED','REVISED') AND assignment.status='PUBLISHED'
  AND session.status IN ('ACTIVE','PLANNED') AND school.status='ACTIVE' AND organisation.status='ACTIVE';

-- Only live access to a frozen learner is resolved here, never the current section
-- roster. Moving/enrolling another child must not expand a published assignment.
CREATE VIEW learning_publication_notification_recipients WITH (security_invoker=true) AS
SELECT DISTINCT source.event_id,source.organisation_id,source.school_id,recipient.learner_id,
  'GUARDIAN'::text recipient_kind,guardian.id guardian_id,NULL::uuid membership_id,guardian.user_account_id
FROM learning_publication_notification_sources source
JOIN assignment_recipients recipient ON recipient.assignment_id=source.assignment_id
  AND recipient.organisation_id=source.organisation_id AND recipient.school_id=source.school_id
JOIN learners learner ON learner.id=recipient.learner_id AND learner.organisation_id=source.organisation_id
  AND learner.school_id=source.school_id AND learner.status='ACTIVE'
JOIN student_enrolments enrolment ON enrolment.id=recipient.enrolment_id AND enrolment.learner_id=learner.id
  AND enrolment.organisation_id=source.organisation_id AND enrolment.school_id=source.school_id AND enrolment.status='ACTIVE'
JOIN learner_guardian_relationships relationship ON relationship.learner_id=learner.id
  AND relationship.organisation_id=source.organisation_id AND relationship.school_id=source.school_id
  AND relationship.portal_access_status='ACTIVE'
JOIN guardians guardian ON guardian.id=relationship.guardian_id AND guardian.organisation_id=source.organisation_id
  AND guardian.school_id=source.school_id AND guardian.status='ACTIVE'
JOIN user_accounts account ON account.id=guardian.user_account_id AND account.status='ACTIVE'
WHERE source.audience_kind='SECTION_FAMILIES' AND EXISTS (
  SELECT 1 FROM memberships membership JOIN membership_roles mr ON mr.membership_id=membership.id
    JOIN roles role ON role.id=mr.role_id JOIN role_capabilities capability ON capability.role_id=role.id
  WHERE membership.user_id=guardian.user_account_id AND membership.organisation_id=source.organisation_id
    AND membership.school_id=source.school_id AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
    AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
    AND (role.organisation_id IS NULL OR role.organisation_id=source.organisation_id)
    AND capability.capability_code='portal.guardian')
UNION ALL
SELECT source.event_id,source.organisation_id,source.school_id,recipient.learner_id,
  'STUDENT'::text,NULL::uuid,membership.id,portal.user_account_id
FROM learning_publication_notification_sources source
JOIN assignment_recipients recipient ON recipient.assignment_id=source.assignment_id
  AND recipient.organisation_id=source.organisation_id AND recipient.school_id=source.school_id
JOIN learners learner ON learner.id=recipient.learner_id AND learner.organisation_id=source.organisation_id
  AND learner.school_id=source.school_id AND learner.status='ACTIVE'
JOIN student_enrolments enrolment ON enrolment.id=recipient.enrolment_id AND enrolment.learner_id=learner.id
  AND enrolment.organisation_id=source.organisation_id AND enrolment.school_id=source.school_id AND enrolment.status='ACTIVE'
JOIN learner_portal_accounts portal ON portal.learner_id=learner.id AND portal.organisation_id=source.organisation_id
  AND portal.school_id=source.school_id AND portal.status='ACTIVE'
JOIN student_portal_consent_requests consent ON consent.id=portal.current_consent_request_id
  AND consent.organisation_id=source.organisation_id AND consent.school_id=source.school_id
  AND consent.learner_id=learner.id AND consent.status='APPROVED' AND consent.approved_user_account_id=portal.user_account_id
JOIN user_accounts account ON account.id=portal.user_account_id AND account.status='ACTIVE'
JOIN LATERAL (
  SELECT candidate.id FROM memberships candidate
  WHERE candidate.user_id=portal.user_account_id AND candidate.organisation_id=source.organisation_id
    AND candidate.school_id=source.school_id AND candidate.status='ACTIVE' AND candidate.valid_from<=CURRENT_TIMESTAMP
    AND (candidate.valid_until IS NULL OR candidate.valid_until>CURRENT_TIMESTAMP)
    AND EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
      JOIN role_capabilities capability ON capability.role_id=role.id WHERE mr.membership_id=candidate.id
      AND (role.organisation_id IS NULL OR role.organisation_id=source.organisation_id)
      AND capability.capability_code='portal.student')
  ORDER BY candidate.created_at,candidate.id LIMIT 1
) membership ON true
WHERE source.audience_kind='SECTION_STUDENTS';

CREATE FUNCTION guard_learning_publication_outbox() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' AND OLD.aggregate_type='ASSIGNMENT' AND OLD.event_type='learning.assignment.publication' THEN
    RAISE EXCEPTION 'learning publication event proof cannot be deleted';
  ELSIF TG_OP='UPDATE' AND (OLD.event_type='learning.assignment.publication' OR NEW.event_type='learning.assignment.publication') THEN
    IF OLD.id<>NEW.id OR OLD.aggregate_type<>NEW.aggregate_type OR OLD.aggregate_id<>NEW.aggregate_id
      OR OLD.event_type<>NEW.event_type OR OLD.payload<>NEW.payload OR OLD.headers<>NEW.headers
      OR OLD.occurred_at<>NEW.occurred_at OR OLD.created_at<>NEW.created_at THEN
      RAISE EXCEPTION 'learning publication event proof is immutable';
    END IF;
  ELSIF TG_OP='INSERT' AND NEW.event_type='learning.assignment.publication' THEN
    IF NEW.aggregate_type<>'ASSIGNMENT' OR NOT EXISTS(
      SELECT 1 FROM assignments assignment JOIN assignment_revision_history history ON history.assignment_id=assignment.id
        AND history.organisation_id=assignment.organisation_id AND history.school_id=assignment.school_id
        AND history.publication_revision=assignment.publication_revision
      WHERE assignment.id::text=NEW.aggregate_id AND assignment.status='PUBLISHED'
        AND NEW.payload->>'audienceKind' IN ('SECTION_FAMILIES','SECTION_STUDENTS')
        AND NEW.payload=jsonb_build_object('organisationId',assignment.organisation_id,'schoolId',assignment.school_id,
          'assignmentId',assignment.id,'historyId',history.id,'publicationRevision',history.publication_revision,
          'audienceKind',NEW.payload->>'audienceKind') AND NEW.occurred_at=history.occurred_at
        AND NEW.available_at=greatest(history.occurred_at,assignment.available_from)
        AND history.change_kind IN ('PUBLISHED','REVISED')) THEN
      RAISE EXCEPTION 'learning publication events require current immutable scoped revision proof';
    END IF;
  END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER learning_publication_outbox_guard BEFORE INSERT OR UPDATE OR DELETE ON outbox_events
  FOR EACH ROW EXECUTE FUNCTION guard_learning_publication_outbox();

ALTER TABLE communication_announcements ADD COLUMN learning_assignment_id UUID,
  ADD COLUMN learning_history_id UUID REFERENCES assignment_revision_history(id),
  ADD CONSTRAINT communication_learning_assignment_fk FOREIGN KEY(learning_assignment_id,organisation_id,school_id)
    REFERENCES assignments(id,organisation_id,school_id);

-- Preserve every previous authority branch, and keep learning source fields out
-- of human/PTM/request/leave publications rather than widening their permissions.
DO $$
DECLARE previous_check TEXT;
BEGIN
  SELECT pg_get_expr(conbin,conrelid) INTO previous_check FROM pg_constraint
    WHERE conrelid='communication_announcements'::regclass AND conname='communication_announcements_state_valid';
  IF previous_check IS NULL THEN RAISE EXCEPTION 'Existing communication authority constraint is required'; END IF;
  ALTER TABLE communication_announcements DROP CONSTRAINT communication_announcements_state_valid;
  EXECUTE 'ALTER TABLE communication_announcements ADD CONSTRAINT communication_announcements_state_valid CHECK ((('
    ||previous_check||') AND learning_assignment_id IS NULL AND learning_history_id IS NULL)
    OR (authority_kind=''DOMAIN_EVENT'' AND status=''APPROVED'' AND source_type IS NOT NULL
    AND source_type=''ASSIGNMENT_PUBLICATION'' AND source_id IS NOT NULL AND domain_event_id IS NOT NULL AND source_id=domain_event_id
    AND learning_assignment_id IS NOT NULL AND learning_history_id IS NOT NULL
    AND ptm_reminder_id IS NULL AND parent_request_id IS NULL AND parent_request_history_id IS NULL AND parent_request_message_id IS NULL
    AND student_leave_request_id IS NULL AND student_leave_history_id IS NULL
    AND created_by_membership_id IS NULL AND submitted_by_membership_id IS NULL AND submitted_at IS NULL
    AND decided_by_membership_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL
    AND cancelled_by_membership_id IS NULL AND cancelled_at IS NULL AND cancellation_reason IS NULL
    AND communication_template_id IS NULL AND audience_kind IN (''SECTION_FAMILIES'',''SECTION_STUDENTS'') AND audience_reference_id IS NOT NULL
    AND category=''ACADEMIC'' AND priority=''NORMAL'' AND language_mode=''BILINGUAL''
    AND request_push=(audience_kind=''SECTION_FAMILIES'') AND NOT requires_acknowledgement
    AND NOT request_email AND NOT request_sms AND NOT request_whatsapp AND expires_at IS NOT NULL))';
END;
$$;

DROP TRIGGER communication_domain_authority_guard ON communication_announcements;
CREATE TRIGGER communication_domain_authority_guard BEFORE INSERT OR UPDATE ON communication_announcements
  FOR EACH ROW WHEN (NEW.source_type IS DISTINCT FROM 'PARENT_REQUEST_UPDATE'
    AND NEW.source_type IS DISTINCT FROM 'STUDENT_LEAVE_UPDATE' AND NEW.source_type IS DISTINCT FROM 'STUDENT_LEAVE_SUBMISSION'
    AND NEW.source_type IS DISTINCT FROM 'ASSIGNMENT_PUBLICATION') EXECUTE FUNCTION guard_communication_domain_authority();

CREATE FUNCTION guard_communication_learning_authority() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='UPDATE' AND (OLD.source_type='ASSIGNMENT_PUBLICATION' OR NEW.source_type='ASSIGNMENT_PUBLICATION') THEN
    RAISE EXCEPTION 'learning publications are immutable';
  ELSIF TG_OP='INSERT' AND NEW.source_type='ASSIGNMENT_PUBLICATION' THEN
    IF NOT EXISTS(SELECT 1 FROM learning_publication_notification_sources source
      WHERE source.event_id=NEW.domain_event_id AND source.assignment_id=NEW.learning_assignment_id
        AND source.history_id=NEW.learning_history_id AND source.organisation_id=NEW.organisation_id AND source.school_id=NEW.school_id
        AND source.audience_kind=NEW.audience_kind AND source.class_section_id=NEW.audience_reference_id
        AND source.scheduled_for=NEW.scheduled_for AND source.expires_at=NEW.expires_at
        AND source.scheduled_for<=clock_timestamp() AND source.expires_at>clock_timestamp()) THEN
      RAISE EXCEPTION 'learning alert requires current scoped publication proof';
    END IF;
    IF NEW.title_en IS DISTINCT FROM 'School learning work has an update'
      OR NEW.title_hi IS DISTINCT FROM 'स्कूल के अध्ययन कार्य में नया अपडेट है'
      OR NEW.body_en IS DISTINCT FROM 'Open Assignments in your verified school portal to read the published work.'
      OR NEW.body_hi IS DISTINCT FROM 'प्रकाशित कार्य पढ़ने के लिए अपने सत्यापित स्कूल पोर्टल में असाइनमेंट खोलें।' THEN
      RAISE EXCEPTION 'learning alerts require privacy-minimal generic content';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER communication_learning_authority_guard BEFORE INSERT OR UPDATE ON communication_announcements
  FOR EACH ROW EXECUTE FUNCTION guard_communication_learning_authority();

-- Add a dispatch to the new dedicated guard without rewriting any existing
-- recipient branch. Abort if the expected shared contract is no longer present.
DO $$
DECLARE definition TEXT; marker TEXT := '  IF notice.authority_kind=''DOMAIN_EVENT'' THEN';
BEGIN
  SELECT pg_get_functiondef('guard_communication_domain_recipient()'::regprocedure) INTO definition;
  IF position(marker IN definition)=0 THEN RAISE EXCEPTION 'Existing domain recipient guard contract is required'; END IF;
  EXECUTE replace(definition,marker,
    '  IF notice.source_type=''ASSIGNMENT_PUBLICATION'' THEN RETURN NEW; END IF;'||chr(10)||marker);
END;
$$;

CREATE FUNCTION guard_communication_learning_recipient() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE notice communication_announcements%ROWTYPE;
BEGIN
  SELECT * INTO notice FROM communication_announcements WHERE id=NEW.communication_announcement_id;
  IF notice.source_type='ASSIGNMENT_PUBLICATION' AND NOT EXISTS(
    SELECT 1 FROM learning_publication_notification_recipients recipient
    WHERE recipient.event_id=notice.domain_event_id AND recipient.organisation_id=NEW.organisation_id
      AND recipient.school_id=NEW.school_id AND notice.organisation_id=NEW.organisation_id AND notice.school_id=NEW.school_id
      AND recipient.recipient_kind=NEW.recipient_kind AND recipient.learner_id=NEW.learner_id
      AND recipient.guardian_id IS NOT DISTINCT FROM NEW.guardian_id
      AND recipient.membership_id IS NOT DISTINCT FROM NEW.membership_id AND recipient.user_account_id=NEW.user_account_id
  ) THEN RAISE EXCEPTION 'learning recipient requires current access to the frozen learner audience'; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER communication_learning_recipient_guard BEFORE INSERT ON communication_recipients
  FOR EACH ROW EXECUTE FUNCTION guard_communication_learning_recipient();

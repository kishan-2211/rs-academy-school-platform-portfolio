-- Read-only, privacy-minimal authority for pending-request staff alerts.
-- Publication and provider dispatch remain separate controlled consumers.
CREATE VIEW student_leave_submission_notification_sources WITH (security_invoker=true) AS
SELECT evidence.id event_id,request.id request_id,history.id history_id,
  request.organisation_id,request.school_id,history.request_version,
  evidence.occurred_at,evidence.occurred_at+interval '7 days' expires_at
FROM outbox_events evidence
JOIN student_leave_requests request ON request.id::text=evidence.aggregate_id
JOIN student_leave_history history ON history.request_id=request.id
  AND history.organisation_id=request.organisation_id AND history.school_id=request.school_id
  AND history.id::text=evidence.payload->>'historyId'
  AND history.request_version=request.row_version AND history.new_status=request.status
  AND history.previous_status IS NULL AND history.actor_membership_id=request.requested_by_membership_id
  AND history.occurred_at=evidence.occurred_at
JOIN guardians guardian ON guardian.id=request.guardian_id
  AND guardian.organisation_id=request.organisation_id AND guardian.school_id=request.school_id
  AND guardian.user_account_id=request.requested_by_user_id AND guardian.status='ACTIVE'
JOIN learners learner ON learner.id=request.learner_id
  AND learner.organisation_id=request.organisation_id AND learner.school_id=request.school_id AND learner.status='ACTIVE'
JOIN user_accounts account ON account.id=guardian.user_account_id AND account.status='ACTIVE'
JOIN schools school ON school.id=request.school_id AND school.organisation_id=request.organisation_id AND school.status='ACTIVE'
JOIN organisations organisation ON organisation.id=request.organisation_id AND organisation.status='ACTIVE'
JOIN campuses campus ON campus.id=request.campus_id AND campus.organisation_id=request.organisation_id
  AND campus.school_id=request.school_id AND campus.status='ACTIVE'
JOIN academic_sessions session ON session.id=request.academic_session_id
  AND session.organisation_id=request.organisation_id AND session.school_id=request.school_id
  AND session.status IN ('ACTIVE','PLANNED')
JOIN student_enrolments enrolment ON enrolment.id=request.enrolment_id
  AND enrolment.organisation_id=request.organisation_id AND enrolment.school_id=request.school_id
  AND enrolment.learner_id=request.learner_id AND enrolment.campus_id=request.campus_id
  AND enrolment.academic_session_id=request.academic_session_id AND enrolment.status='ACTIVE'
  AND request.starts_on>=enrolment.starts_on AND (enrolment.ends_on IS NULL OR request.ends_on<=enrolment.ends_on)
  AND request.starts_on>=session.starts_on AND request.ends_on<=session.ends_on
WHERE evidence.aggregate_type='STUDENT_LEAVE_REQUEST' AND evidence.event_type='student_leave.submitted'
  AND request.status='SUBMITTED' AND request.row_version=0 AND history.request_version=0
  AND evidence.payload->>'organisationId'=request.organisation_id::text
  AND evidence.payload->>'schoolId'=request.school_id::text
  AND evidence.payload->>'requestId'=request.id::text
  AND evidence.payload->>'requestVersion'='0' AND evidence.payload->>'status'='SUBMITTED'
  AND EXISTS (SELECT 1 FROM learner_guardian_relationships relationship
    WHERE relationship.guardian_id=guardian.id AND relationship.learner_id=learner.id
      AND relationship.organisation_id=request.organisation_id AND relationship.school_id=request.school_id
      AND relationship.portal_access_status='ACTIVE')
  AND EXISTS (SELECT 1 FROM memberships membership
    JOIN membership_roles mr ON mr.membership_id=membership.id
    JOIN roles role ON role.id=mr.role_id JOIN role_capabilities capability ON capability.role_id=role.id
    WHERE membership.user_id=guardian.user_account_id
      AND membership.organisation_id=request.organisation_id AND membership.school_id=request.school_id
      AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
      AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
      AND (role.organisation_id IS NULL OR role.organisation_id=request.organisation_id)
      AND capability.capability_code='portal.guardian');

-- Resolve live reviewer authority, not designation labels or cached session grants.
-- Consumers deduplicate people when freezing recipients, but dispatch must still
-- revalidate the originally chosen membership if another eligible membership exists.
CREATE VIEW student_leave_submission_notification_reviewers WITH (security_invoker=true) AS
SELECT
  source.event_id,source.request_id,source.history_id,source.organisation_id,source.school_id,
  source.request_version,source.occurred_at,source.expires_at,
  membership.id membership_id,membership.user_id user_account_id,membership.created_at membership_created_at
FROM student_leave_submission_notification_sources source
JOIN student_leave_requests request ON request.id=source.request_id
  AND request.organisation_id=source.organisation_id AND request.school_id=source.school_id
JOIN memberships membership ON membership.organisation_id=source.organisation_id
  AND membership.school_id=source.school_id AND membership.status='ACTIVE'
  AND membership.valid_from<=CURRENT_TIMESTAMP AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
JOIN user_accounts account ON account.id=membership.user_id AND account.status='ACTIVE'
WHERE membership.user_id<>request.requested_by_user_id
  AND EXISTS (SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
    JOIN role_capabilities capability ON capability.role_id=role.id
    WHERE mr.membership_id=membership.id
      AND (role.organisation_id IS NULL OR role.organisation_id=source.organisation_id)
      AND capability.capability_code IN ('student_leave.manage','communication.read')
    GROUP BY mr.membership_id HAVING bool_or(capability.capability_code='student_leave.manage')
      AND bool_or(capability.capability_code='communication.read'))
  AND NOT EXISTS (SELECT 1 FROM guardians guardian
    JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
      AND relationship.organisation_id=guardian.organisation_id AND relationship.school_id=guardian.school_id
    WHERE guardian.user_account_id=membership.user_id AND relationship.learner_id=request.learner_id
      AND guardian.organisation_id=source.organisation_id AND guardian.school_id=source.school_id);

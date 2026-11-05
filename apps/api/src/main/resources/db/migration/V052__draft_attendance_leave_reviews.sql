-- Explicit review evidence, separate from submitted-register correction decisions.
CREATE TABLE attendance_leave_reviews (
 id UUID PRIMARY KEY, organisation_id UUID NOT NULL, school_id UUID NOT NULL,
 attendance_register_id UUID NOT NULL, attendance_mark_id UUID NOT NULL,
 student_leave_request_id UUID NOT NULL, student_leave_request_version BIGINT NOT NULL,
 source_status VARCHAR(20) NOT NULL, decision VARCHAR(24) NOT NULL,
 previous_state_code VARCHAR(20) NOT NULL, resulting_state_code VARCHAR(20) NOT NULL,
 previous_mark_version BIGINT NOT NULL, resulting_mark_version BIGINT NOT NULL,
 resulting_register_version BIGINT NOT NULL, mark_history_id UUID REFERENCES attendance_mark_history(id),
 actor_membership_id UUID NOT NULL, reason VARCHAR(240) NOT NULL,
 request_fingerprint CHAR(64) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY(attendance_register_id,organisation_id,school_id) REFERENCES attendance_registers(id,organisation_id,school_id),
 FOREIGN KEY(attendance_mark_id,organisation_id,school_id) REFERENCES attendance_marks(id,organisation_id,school_id),
 FOREIGN KEY(student_leave_request_id,organisation_id,school_id) REFERENCES student_leave_requests(id,organisation_id,school_id),
 FOREIGN KEY(student_leave_request_id,student_leave_request_version) REFERENCES student_leave_history(request_id,request_version),
 FOREIGN KEY(actor_membership_id,organisation_id,school_id) REFERENCES memberships(id,organisation_id,school_id),
 CHECK(source_status IN ('APPROVED','WITHDRAWN','REVOKED')),
 CHECK(previous_mark_version>=0 AND resulting_register_version>=1 AND student_leave_request_version>=1),
 CHECK(length(btrim(reason)) BETWEEN 3 AND 240), CHECK(request_fingerprint ~ '^[0-9a-f]{64}$'),
 CHECK((decision='KEEP_RECORDED' AND previous_state_code=resulting_state_code
     AND previous_mark_version=resulting_mark_version AND mark_history_id IS NULL)
   OR (decision='APPLY_APPROVED' AND source_status='APPROVED'
     AND resulting_state_code IN ('EXCUSED','ABSENT')
     AND resulting_mark_version=previous_mark_version+1 AND mark_history_id IS NOT NULL))
);
CREATE INDEX attendance_leave_review_source_idx ON attendance_leave_reviews
 (organisation_id,school_id,attendance_mark_id,student_leave_request_id,student_leave_request_version,resulting_mark_version,occurred_at DESC);

CREATE FUNCTION guard_draft_attendance_leave_review() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE source student_leave_requests%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Attendance leave reviews are append-only'; END IF;
 SELECT * INTO source FROM student_leave_requests WHERE id=NEW.student_leave_request_id
   AND organisation_id=NEW.organisation_id AND school_id=NEW.school_id FOR SHARE;
 IF NOT FOUND OR source.row_version<>NEW.student_leave_request_version OR source.status<>NEW.source_status
   OR NOT EXISTS(SELECT 1 FROM student_leave_history h WHERE h.request_id=source.id
     AND h.request_version=source.row_version AND h.new_status=source.status
     AND h.organisation_id=source.organisation_id AND h.school_id=source.school_id)
   OR (NEW.decision='APPLY_APPROVED' AND source.policy_snapshot->>'attendanceState' IS DISTINCT FROM NEW.resulting_state_code) THEN
   RAISE EXCEPTION 'Draft leave review requires current immutable source evidence';
 END IF;
 IF NOT EXISTS(
   SELECT 1 FROM attendance_marks mark
   JOIN attendance_registers register ON register.id=mark.attendance_register_id
     AND register.organisation_id=mark.organisation_id AND register.school_id=mark.school_id
   JOIN school_calendar_days day ON day.id=register.calendar_day_id
     AND day.organisation_id=register.organisation_id AND day.school_id=register.school_id
   WHERE mark.id=NEW.attendance_mark_id AND mark.attendance_register_id=NEW.attendance_register_id
     AND mark.organisation_id=source.organisation_id AND mark.school_id=source.school_id
     AND mark.learner_id=source.learner_id AND mark.enrolment_id=source.enrolment_id
     AND mark.row_version=NEW.resulting_mark_version AND mark.state_code=NEW.resulting_state_code
     AND register.status='DRAFT' AND register.row_version=NEW.resulting_register_version
     AND register.campus_id=source.campus_id AND register.academic_session_id=source.academic_session_id
     AND day.calendar_date BETWEEN source.starts_on AND source.ends_on
     AND register.policy_snapshot->'enabledStates' ? NEW.resulting_state_code
     AND ((source.status='APPROVED' AND EXISTS(SELECT 1 FROM student_leave_date_claims claim
       WHERE claim.request_id=source.id AND claim.organisation_id=source.organisation_id
         AND claim.school_id=source.school_id AND claim.learner_id=source.learner_id AND claim.calendar_date=day.calendar_date))
       OR (source.status IN ('WITHDRAWN','REVOKED') AND (
         EXISTS(SELECT 1 FROM attendance_mark_history h WHERE h.attendance_mark_id=mark.id
           AND h.student_leave_request_id=source.id AND h.change_kind='LEAVE_INITIAL')
         OR EXISTS(SELECT 1 FROM attendance_leave_reviews review WHERE review.attendance_mark_id=mark.id
           AND review.student_leave_request_id=source.id AND review.organisation_id=source.organisation_id
           AND review.school_id=source.school_id AND review.decision='APPLY_APPROVED'))))) THEN
   RAISE EXCEPTION 'Draft leave review requires matching scoped draft mark and result version';
 END IF;
 IF NEW.mark_history_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM attendance_mark_history h WHERE h.id=NEW.mark_history_id
     AND h.organisation_id=NEW.organisation_id AND h.school_id=NEW.school_id
     AND h.attendance_mark_id=NEW.attendance_mark_id AND h.change_kind='DRAFT_EDIT'
     AND h.previous_state_code=NEW.previous_state_code AND h.new_state_code=NEW.resulting_state_code
     AND h.actor_membership_id=NEW.actor_membership_id AND h.reason=NEW.reason) THEN
   RAISE EXCEPTION 'Draft leave review requires matching immutable mark history';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER attendance_leave_review_guard BEFORE INSERT OR UPDATE OR DELETE ON attendance_leave_reviews
 FOR EACH ROW EXECUTE FUNCTION guard_draft_attendance_leave_review();
ALTER TABLE attendance_leave_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_leave_reviews FORCE ROW LEVEL SECURITY;
CREATE POLICY attendance_leave_reviews_scope ON attendance_leave_reviews
 USING(organisation_id=current_app_organisation_id()) WITH CHECK(organisation_id=current_app_organisation_id());

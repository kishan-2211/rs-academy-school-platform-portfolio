-- Optional source links preserve existing observation-based corrections and historical rows.
ALTER TABLE attendance_correction_requests
 ADD COLUMN student_leave_request_id UUID,
 ADD COLUMN student_leave_request_version BIGINT,
 ADD COLUMN source_mark_version BIGINT,
 ADD CONSTRAINT attendance_correction_leave_scope_fk
   FOREIGN KEY(student_leave_request_id,organisation_id,school_id)
   REFERENCES student_leave_requests(id,organisation_id,school_id),
 ADD CONSTRAINT attendance_correction_leave_revision_fk
   FOREIGN KEY(student_leave_request_id,student_leave_request_version)
   REFERENCES student_leave_history(request_id,request_version),
 ADD CONSTRAINT attendance_correction_leave_source_valid CHECK (
   (student_leave_request_id IS NULL AND student_leave_request_version IS NULL AND source_mark_version IS NULL)
   OR (student_leave_request_id IS NOT NULL AND student_leave_request_version IS NOT NULL
     AND student_leave_request_version>=1 AND source_mark_version IS NOT NULL AND source_mark_version>=0));
CREATE INDEX attendance_correction_leave_source_idx
 ON attendance_correction_requests(student_leave_request_id,attendance_mark_id)
 WHERE student_leave_request_id IS NOT NULL;

CREATE FUNCTION guard_attendance_correction_leave_source() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE source student_leave_requests%ROWTYPE;
        expected_mark_version BIGINT;
BEGIN
 IF TG_OP='DELETE' THEN
   IF OLD.student_leave_request_id IS NOT NULL THEN
     RAISE EXCEPTION 'Leave-linked correction evidence cannot be deleted';
   END IF;
   RETURN OLD;
 END IF;
 IF TG_OP='UPDATE' THEN
   IF OLD.student_leave_request_id IS NOT NULL AND NEW.row_version<>OLD.row_version+1 THEN
     RAISE EXCEPTION 'Leave correction decision requires the next version';
   END IF;
   IF ROW(NEW.student_leave_request_id,NEW.student_leave_request_version,NEW.source_mark_version)
     IS DISTINCT FROM ROW(OLD.student_leave_request_id,OLD.student_leave_request_version,OLD.source_mark_version)
     OR (OLD.student_leave_request_id IS NOT NULL AND
       ROW(NEW.id,NEW.organisation_id,NEW.school_id,NEW.attendance_mark_id,NEW.requested_state_code,
         NEW.request_reason,NEW.requested_by_membership_id,NEW.created_at)
       IS DISTINCT FROM ROW(OLD.id,OLD.organisation_id,OLD.school_id,OLD.attendance_mark_id,
         OLD.requested_state_code,OLD.request_reason,OLD.requested_by_membership_id,OLD.created_at)) THEN
     RAISE EXCEPTION 'Leave correction source identity is immutable';
   END IF;
 END IF;
 IF NEW.student_leave_request_id IS NULL THEN RETURN NEW; END IF;
 -- Rejecting obsolete evidence remains possible, but approving it never is.
 IF TG_OP='UPDATE' AND NEW.status='REJECTED' THEN RETURN NEW; END IF;
 IF TG_OP='INSERT' AND (NEW.status<>'PENDING' OR NEW.row_version<>0) THEN
   RAISE EXCEPTION 'Leave correction must begin pending review';
 END IF;
 SELECT * INTO source FROM student_leave_requests
 WHERE id=NEW.student_leave_request_id AND organisation_id=NEW.organisation_id
   AND school_id=NEW.school_id FOR SHARE;
 IF NOT FOUND OR source.status<>'APPROVED' OR source.row_version<>NEW.student_leave_request_version
   OR (source.policy_snapshot->>'attendanceState') IS DISTINCT FROM NEW.requested_state_code
   OR NOT EXISTS(SELECT 1 FROM student_leave_history h WHERE h.request_id=source.id
     AND h.request_version=source.row_version AND h.new_status='APPROVED'
     AND h.organisation_id=source.organisation_id AND h.school_id=source.school_id) THEN
   RAISE EXCEPTION 'Leave correction requires matching current approval';
 END IF;
 expected_mark_version := NEW.source_mark_version + CASE WHEN TG_OP='UPDATE' AND NEW.status='APPROVED' THEN 1 ELSE 0 END;
 IF NOT EXISTS(
   SELECT 1 FROM attendance_marks mark
   JOIN attendance_registers register ON register.id=mark.attendance_register_id
     AND register.organisation_id=mark.organisation_id AND register.school_id=mark.school_id
   JOIN school_calendar_days day ON day.id=register.calendar_day_id
     AND day.organisation_id=register.organisation_id AND day.school_id=register.school_id
   JOIN student_leave_date_claims claim ON claim.request_id=source.id AND claim.calendar_date=day.calendar_date
     AND claim.organisation_id=source.organisation_id AND claim.school_id=source.school_id
     AND claim.learner_id=source.learner_id
   WHERE mark.id=NEW.attendance_mark_id AND mark.organisation_id=source.organisation_id
     AND mark.school_id=source.school_id AND mark.learner_id=source.learner_id
     AND mark.enrolment_id=source.enrolment_id AND mark.row_version=expected_mark_version
     AND register.status IN ('SUBMITTED','LOCKED')
     AND register.campus_id=source.campus_id AND register.academic_session_id=source.academic_session_id
     AND day.calendar_date BETWEEN source.starts_on AND source.ends_on
     AND register.policy_snapshot->'enabledStates' ? NEW.requested_state_code
     AND (NEW.status<>'APPROVED' OR mark.state_code=NEW.requested_state_code)) THEN
   RAISE EXCEPTION 'Leave correction requires matching scoped mark and version';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER attendance_corrections_leave_source_guard
 BEFORE INSERT OR UPDATE OR DELETE ON attendance_correction_requests
 FOR EACH ROW EXECUTE FUNCTION guard_attendance_correction_leave_source();

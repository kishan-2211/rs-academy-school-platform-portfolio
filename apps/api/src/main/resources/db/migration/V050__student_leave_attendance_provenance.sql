-- Preserve ordinary INITIAL history semantics. Leave-derived initialization has
-- explicit, immutable source identity/version, not a private reason or parsed note.
ALTER TABLE attendance_mark_history
 ADD COLUMN student_leave_request_id UUID,
 ADD COLUMN student_leave_request_version BIGINT,
 ADD CONSTRAINT attendance_history_leave_scope_fk
   FOREIGN KEY(student_leave_request_id,organisation_id,school_id)
   REFERENCES student_leave_requests(id,organisation_id,school_id),
 ADD CONSTRAINT attendance_history_leave_revision_fk
   FOREIGN KEY(student_leave_request_id,student_leave_request_version)
   REFERENCES student_leave_history(request_id,request_version),
 DROP CONSTRAINT attendance_mark_history_kind_valid;

ALTER TABLE attendance_mark_history
 ADD CONSTRAINT attendance_mark_history_kind_valid
   CHECK(change_kind IN ('INITIAL','DRAFT_EDIT','CORRECTION','LEAVE_INITIAL')),
 ADD CONSTRAINT attendance_history_leave_evidence_valid CHECK (
   (change_kind='LEAVE_INITIAL' AND student_leave_request_id IS NOT NULL
     AND student_leave_request_version IS NOT NULL AND student_leave_request_version>=1
     AND previous_state_code IS NULL AND new_state_code IN ('EXCUSED','ABSENT')
     AND reason IS NOT NULL AND length(btrim(reason)) BETWEEN 3 AND 240)
   OR (change_kind<>'LEAVE_INITIAL' AND student_leave_request_id IS NULL
     AND student_leave_request_version IS NULL));

CREATE FUNCTION guard_attendance_leave_initialization() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE source student_leave_requests%ROWTYPE;
BEGIN
 IF NEW.change_kind<>'LEAVE_INITIAL' THEN RETURN NEW; END IF;
 SELECT * INTO source FROM student_leave_requests
 WHERE id=NEW.student_leave_request_id AND organisation_id=NEW.organisation_id
   AND school_id=NEW.school_id FOR SHARE;
 IF NOT FOUND OR source.status<>'APPROVED' OR source.row_version<>NEW.student_leave_request_version
   OR (source.policy_snapshot->>'attendanceState') IS DISTINCT FROM NEW.new_state_code
   OR NOT EXISTS(SELECT 1 FROM student_leave_history h WHERE h.request_id=source.id
     AND h.request_version=source.row_version AND h.new_status='APPROVED'
     AND h.organisation_id=source.organisation_id AND h.school_id=source.school_id)
   OR NOT EXISTS(
     SELECT 1 FROM attendance_marks mark
     JOIN attendance_registers register ON register.id=mark.attendance_register_id
       AND register.organisation_id=mark.organisation_id AND register.school_id=mark.school_id
     JOIN school_calendar_days day ON day.id=register.calendar_day_id
       AND day.organisation_id=register.organisation_id AND day.school_id=register.school_id
     WHERE mark.id=NEW.attendance_mark_id AND mark.organisation_id=source.organisation_id
       AND mark.school_id=source.school_id AND mark.learner_id=source.learner_id
       AND mark.enrolment_id=source.enrolment_id AND mark.state_code=NEW.new_state_code
       AND mark.row_version=0 AND register.status='DRAFT'
       AND register.campus_id=source.campus_id AND register.academic_session_id=source.academic_session_id
       AND day.calendar_date BETWEEN source.starts_on AND source.ends_on) THEN
   RAISE EXCEPTION 'Attendance leave initialization requires matching current approval and initial mark';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER attendance_leave_initialization_guard BEFORE INSERT ON attendance_mark_history
FOR EACH ROW EXECUTE FUNCTION guard_attendance_leave_initialization();
CREATE UNIQUE INDEX attendance_leave_initial_source_idx ON attendance_mark_history(attendance_mark_id)
WHERE change_kind='LEAVE_INITIAL';

package com.educore.api.attendance;

import static com.educore.api.attendance.AttendanceController.*;
import static com.educore.api.attendance.AttendanceSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.studentleave.ApprovedStudentLeaveLookup;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AttendanceService {
    private static final Set<String> STATES = Set.of("PRESENT","ABSENT","LATE","EXCUSED","HALF_DAY","SCHOOL_ACTIVITY");
    private static final Set<String> MODES = Set.of("DAILY","PERIOD","BOTH");
    private static final Set<String> DAY_TYPES = Set.of("INSTRUCTIONAL","EXAM","EVENT","HOLIDAY","CLOSURE");
    private static final Set<String> STATUSES = Set.of("ACTIVE","INACTIVE");
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ApprovedStudentLeaveLookup approvedLeave;
    public AttendanceService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit, Clock clock,
            ObjectMapper objectMapper, ApprovedStudentLeaveLookup approvedLeave) {
        this.jdbc=jdbc; this.audit=audit; this.clock=clock; this.objectMapper=objectMapper;
        this.approvedLeave=approvedLeave;
    }

    @Transactional
    public AttendanceSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId, LocalDate requestedDate) {
        Scope scope=requireScope(user, requestedSchoolId, "attendance.read");
        LocalDate date=requestedDate==null?LocalDate.now(clock):requestedDate;
        var p=scope.params().addValue("date",date).addValue("canConfigure",scope.canConfigure());
        jdbc.update("""
                UPDATE attendance_registers
                SET status='LOCKED',updated_at=:now,row_version=row_version+1
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='SUBMITTED'
                  AND submitted_at + ((policy_snapshot->>'lockAfterHours')::integer * INTERVAL '1 hour') <= :now
                """,p.addValue("now",Instant.now(clock)));
        List<RegisterItem> registers=jdbc.query("""
                SELECT register.id, register.class_section_id, register.calendar_day_id,
                       register.bell_schedule_period_id, register.register_mode, register.status,
                       register.expected_learner_count, register.submitted_at, register.row_version,
                       register.policy_snapshot,
                       count(mark.id) AS recorded_count,
                       count(mark.id) FILTER (WHERE mark.state_code IN ('ABSENT','EXCUSED')) AS absent_count
                FROM attendance_registers register
                JOIN school_calendar_days day ON day.id=register.calendar_day_id
                LEFT JOIN attendance_marks mark ON mark.attendance_register_id=register.id
                WHERE register.organisation_id=:organisationId AND register.school_id=:schoolId
                  AND day.calendar_date=:date
                  AND (:canConfigure OR (
                    (register.bell_schedule_period_id IS NULL AND EXISTS (
                      SELECT 1 FROM section_subject_offerings offering
                      JOIN teaching_allocations allocation ON allocation.section_subject_offering_id=offering.id
                      WHERE offering.class_section_id=register.class_section_id
                        AND allocation.teacher_membership_id=:actorMembershipId AND allocation.status='ACTIVE'
                        AND allocation.starts_on<=day.calendar_date
                        AND (allocation.ends_on IS NULL OR allocation.ends_on>=day.calendar_date)))
                    OR (register.bell_schedule_period_id IS NOT NULL AND EXISTS (
                      SELECT 1 FROM timetable_plans plan JOIN timetable_entries entry ON entry.timetable_plan_id=plan.id
                      WHERE plan.status='PUBLISHED' AND entry.class_section_id=register.class_section_id
                        AND entry.bell_schedule_period_id=register.bell_schedule_period_id
                        AND entry.teacher_membership_id=:actorMembershipId
                        AND plan.organisation_id=:organisationId AND plan.school_id=:schoolId))))
                GROUP BY register.id ORDER BY register.created_at, register.id
                """,p,(r,n)->new RegisterItem(r.getObject("id",UUID.class),r.getObject("class_section_id",UUID.class),
                r.getObject("calendar_day_id",UUID.class),r.getObject("bell_schedule_period_id",UUID.class),r.getString("register_mode"),policyEvidence(r.getString("policy_snapshot")).enabledStates(),
                r.getString("status"),r.getInt("expected_learner_count"),r.getInt("recorded_count"),r.getInt("absent_count"),
                r.getTimestamp("submitted_at")==null?null:r.getTimestamp("submitted_at").toInstant(),r.getLong("row_version"),List.of()));
        registers=registers.stream().map(item->new RegisterItem(item.id(),item.sectionId(),item.calendarDayId(),item.periodId(),item.mode(),item.enabledStates(),
                item.status(),item.expectedLearnerCount(),item.recordedCount(),item.absentCount(),item.submittedAt(),item.rowVersion(),marks(scope,item.id()))).toList();
        return new AttendanceSnapshot(scope.schoolId(),scope.schoolName(),scope.canConfigure(),scope.canCorrect(),
                jdbc.query("SELECT id,name,starts_on,ends_on,status FROM academic_sessions WHERE organisation_id=:organisationId AND school_id=:schoolId ORDER BY starts_on DESC",p,
                        (r,n)->new SessionItem(r.getObject("id",UUID.class),r.getString("name"),r.getObject("starts_on",LocalDate.class),r.getObject("ends_on",LocalDate.class),r.getString("status"))),
                jdbc.query("SELECT id,display_name FROM campuses WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE' ORDER BY display_name",p,
                        (r,n)->new CampusItem(r.getObject("id",UUID.class),r.getString("display_name"))),
                jdbc.query("""
                        SELECT section.id,section.campus_id,section.academic_session_id,section.grade_code,section.display_name
                        FROM class_sections section WHERE section.organisation_id=:organisationId AND section.school_id=:schoolId
                          AND section.status='ACTIVE' AND (:canConfigure OR EXISTS (
                            SELECT 1 FROM section_subject_offerings offering JOIN teaching_allocations allocation
                              ON allocation.section_subject_offering_id=offering.id
                            WHERE offering.class_section_id=section.id AND allocation.teacher_membership_id=:actorMembershipId
                              AND allocation.status='ACTIVE')) ORDER BY section.grade_code,section.code
                        """,p,(r,n)->new SectionItem(r.getObject("id",UUID.class),r.getObject("campus_id",UUID.class),
                        r.getObject("academic_session_id",UUID.class),r.getString("grade_code"),r.getString("display_name"))),
                jdbc.query("""
                        SELECT id,campus_id,academic_session_id,day_of_week,display_name,starts_at,ends_at
                        FROM bell_schedule_periods WHERE organisation_id=:organisationId AND school_id=:schoolId
                          AND status='ACTIVE' AND period_kind='TEACHING' ORDER BY day_of_week,starts_at
                        """,p,(r,n)->new PeriodItem(r.getObject("id",UUID.class),r.getObject("campus_id",UUID.class),
                        r.getObject("academic_session_id",UUID.class),r.getInt("day_of_week"),r.getString("display_name"),
                        r.getObject("starts_at",LocalTime.class),r.getObject("ends_at",LocalTime.class))),
                jdbc.query("SELECT * FROM attendance_policies WHERE organisation_id=:organisationId AND school_id=:schoolId ORDER BY created_at DESC",p,
                        (r,n)->new PolicyItem(r.getObject("id",UUID.class),r.getObject("academic_session_id",UUID.class),r.getString("policy_name"),
                        r.getString("register_mode"),arrayStrings(r.getArray("enabled_state_codes")),stateWeights(r.getString("state_weights")),r.getObject("submission_cutoff",LocalTime.class),
                        r.getInt("lock_after_hours"),r.getInt("guardian_explanation_days"),r.getBigDecimal("warning_threshold_percent"),r.getString("status"),r.getLong("row_version"))),
                jdbc.query("""
                        SELECT * FROM school_calendar_days WHERE organisation_id=:organisationId AND school_id=:schoolId
                          AND calendar_date BETWEEN :date - 14 AND :date + 31 ORDER BY calendar_date,campus_id
                        """,p,(r,n)->new CalendarItem(r.getObject("id",UUID.class),r.getObject("campus_id",UUID.class),
                        r.getObject("academic_session_id",UUID.class),r.getObject("calendar_date",LocalDate.class),r.getString("day_type"),
                        r.getString("display_name"),r.getBoolean("attendance_required"),r.getString("status"),r.getLong("row_version"))),
                registers,
                scope.canCorrect()?jdbc.query("""
                        SELECT correction.id,correction.attendance_mark_id,learner.legal_name,mark.state_code,
                               correction.requested_state_code,correction.request_reason,correction.status,
                               account.display_name,correction.created_at,correction.row_version,
                               correction.student_leave_request_id,correction.student_leave_request_version,correction.source_mark_version
                        FROM attendance_correction_requests correction
                        JOIN attendance_marks mark ON mark.id=correction.attendance_mark_id
                        JOIN learners learner ON learner.id=mark.learner_id
                        JOIN memberships membership ON membership.id=correction.requested_by_membership_id
                        JOIN user_accounts account ON account.id=membership.user_id
                        WHERE correction.organisation_id=:organisationId AND correction.school_id=:schoolId
                          AND correction.status='PENDING' ORDER BY correction.created_at
                        """,p,(r,n)->new CorrectionItem(r.getObject("id",UUID.class),r.getObject("attendance_mark_id",UUID.class),
                        r.getString("legal_name"),r.getString("state_code"),r.getString("requested_state_code"),r.getString("request_reason"),
                        r.getString("status"),r.getString("display_name"),r.getTimestamp("created_at").toInstant(),r.getLong("row_version"),
                        r.getObject("student_leave_request_id",UUID.class)==null?null:new LeaveCorrectionSource(
                                r.getObject("student_leave_request_id",UUID.class),r.getLong("student_leave_request_version"),r.getLong("source_mark_version")))):List.of(),
                scope.canCorrect()?jdbc.query("""
                        SELECT explanation.id,explanation.attendance_mark_id,learner.legal_name,mark.state_code,
                               guardian.display_name AS guardian_name,explanation.explanation,
                               explanation.status,explanation.created_at,explanation.row_version
                        FROM attendance_explanations explanation
                        JOIN attendance_marks mark ON mark.id=explanation.attendance_mark_id
                        JOIN learners learner ON learner.id=mark.learner_id
                        JOIN guardians guardian ON guardian.id=explanation.guardian_id
                        WHERE explanation.organisation_id=:organisationId AND explanation.school_id=:schoolId
                          AND explanation.status='SUBMITTED' ORDER BY explanation.created_at
                        """,p,(r,n)->new ExplanationItem(r.getObject("id",UUID.class),
                        r.getObject("attendance_mark_id",UUID.class),r.getString("legal_name"),
                        r.getString("state_code"),r.getString("guardian_name"),r.getString("explanation"),
                        r.getString("status"),r.getTimestamp("created_at").toInstant(),r.getLong("row_version"))):List.of());
    }

    private List<MarkItem> marks(Scope s,UUID registerId){List<MarkItem> rows=jdbc.query("""
            SELECT mark.id,mark.learner_id,learner.legal_name,placement.roll_number,mark.state_code,mark.note,mark.row_version
            FROM attendance_marks mark JOIN learners learner ON learner.id=mark.learner_id
            LEFT JOIN attendance_registers register ON register.id=mark.attendance_register_id
            LEFT JOIN school_calendar_days day ON day.id=register.calendar_day_id
            LEFT JOIN student_section_placements placement ON placement.learner_id=mark.learner_id
              AND placement.class_section_id=register.class_section_id AND placement.status<>'CANCELLED'
              AND placement.starts_on<=day.calendar_date
              AND (placement.ends_on IS NULL OR placement.ends_on>=day.calendar_date)
            WHERE mark.attendance_register_id=:registerId AND mark.organisation_id=:organisationId AND mark.school_id=:schoolId
            ORDER BY COALESCE(placement.roll_number,'~'),learner.legal_name
            """,s.params().addValue("registerId",registerId),(r,n)->new MarkItem(r.getObject("id",UUID.class),r.getObject("learner_id",UUID.class),
            r.getString("legal_name"),r.getString("roll_number"),r.getString("state_code"),r.getString("note"),r.getLong("row_version"),List.of()));
        var evidence=approvedLeave.forRecordedMarks(s.organisationId(),s.schoolId(),registerId,rows.stream().map(MarkItem::id).toList());
        return rows.stream().map(mark->new MarkItem(mark.id(),mark.learnerId(),mark.learnerName(),mark.rollNumber(),
                mark.stateCode(),mark.note(),mark.rowVersion(),evidence.getOrDefault(mark.id(),List.of()))).toList();
    }

    @Transactional public AttendanceMutationResponse createPolicy(AuthenticatedUser u,PolicyRequest q){Scope s=requireScope(u,q.schoolId(),"attendance.configure");validatePolicy(q);UUID id=UUID.randomUUID();safe("ATTENDANCE_POLICY_EXISTS",()->jdbc.update("""
            INSERT INTO attendance_policies(id,organisation_id,school_id,academic_session_id,policy_name,register_mode,enabled_state_codes,
              state_weights,submission_cutoff,lock_after_hours,guardian_explanation_days,warning_threshold_percent,status,created_at,updated_at,row_version)
            VALUES(:id,:organisationId,:schoolId,:sessionId,:name,:mode,:states,CAST(:stateWeights AS jsonb),:cutoff,:lockHours,:explanationDays,:threshold,:status,:now,:now,0)
            """,policyParams(s,id,q,null)));audit(u,s,"attendance.policy.created","ATTENDANCE_POLICY",id,q.reason());return new AttendanceMutationResponse(id,0);}
    @Transactional public AttendanceMutationResponse updatePolicy(AuthenticatedUser u,UUID id,PolicyRequest q){Scope s=requireScope(u,q.schoolId(),"attendance.configure");validatePolicy(q);long v=version(q.expectedVersion());int n=safe("ATTENDANCE_POLICY_EXISTS",()->jdbc.update("""
            UPDATE attendance_policies SET academic_session_id=:sessionId,policy_name=:name,register_mode=:mode,enabled_state_codes=:states,state_weights=CAST(:stateWeights AS jsonb),
              submission_cutoff=:cutoff,lock_after_hours=:lockHours,guardian_explanation_days=:explanationDays,
              warning_threshold_percent=:threshold,status=:status,updated_at=:now,row_version=row_version+1
            WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND row_version=:version
            """,policyParams(s,id,q,v)));changed(n);audit(u,s,"attendance.policy.updated","ATTENDANCE_POLICY",id,reason(q.reason()));return new AttendanceMutationResponse(id,v+1);}

    @Transactional public AttendanceMutationResponse createCalendarDay(AuthenticatedUser u,CalendarDayRequest q){Scope s=requireScope(u,q.schoolId(),"attendance.configure");validateCalendar(s,q);UUID id=UUID.randomUUID();safe("CALENDAR_DAY_EXISTS",()->jdbc.update("""
            INSERT INTO school_calendar_days(id,organisation_id,school_id,campus_id,academic_session_id,calendar_date,day_type,display_name,
              attendance_required,status,created_at,updated_at,row_version)
            VALUES(:id,:organisationId,:schoolId,:campusId,:sessionId,:date,:dayType,:name,:required,:status,:now,:now,0)
            """,calendarParams(s,id,q,null)));audit(u,s,"attendance.calendar-day.created","SCHOOL_CALENDAR_DAY",id,q.reason());return new AttendanceMutationResponse(id,0);}
    @Transactional public AttendanceMutationResponse updateCalendarDay(AuthenticatedUser u,UUID id,CalendarDayRequest q){Scope s=requireScope(u,q.schoolId(),"attendance.configure");validateCalendar(s,q);long v=version(q.expectedVersion());int n=safe("CALENDAR_DAY_EXISTS",()->jdbc.update("""
            UPDATE school_calendar_days SET campus_id=:campusId,academic_session_id=:sessionId,calendar_date=:date,day_type=:dayType,
              display_name=:name,attendance_required=:required,status=:status,updated_at=:now,row_version=row_version+1
            WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND row_version=:version
              AND authority_notice_revision_id IS NULL
              AND NOT EXISTS(SELECT 1 FROM attendance_registers WHERE calendar_day_id=:id)
            """,calendarParams(s,id,q,v)));changed(n);audit(u,s,"attendance.calendar-day.updated","SCHOOL_CALENDAR_DAY",id,reason(q.reason()));return new AttendanceMutationResponse(id,v+1);}

    @Transactional public AttendanceMutationResponse openRegister(AuthenticatedUser u,OpenRegisterRequest q){Scope s=requireScope(u,q.schoolId(),"attendance.record");RegisterContext c=registerContext(s,q.sectionId(),q.calendarDayId(),q.periodId(),q.mode());if(c.date().isAfter(LocalDate.now(clock.withZone(ZoneId.of(c.timeZone())))))throw bad("FUTURE_ATTENDANCE_NOT_ALLOWED","Attendance registers cannot be opened before the school day.");authorizeRegister(s,c,q.periodId());ActivePolicy policy=activePolicy(s,c.sessionId());if(!"BOTH".equals(policy.mode())&&!policy.mode().equals(c.mode()))throw conflict("REGISTER_MODE_DISABLED","The active attendance policy does not allow this register mode.");List<RosterLearner>roster=roster(s,c.sectionId(),c.date());UUID id=UUID.randomUUID();long registerVersion=0;int expected=roster.size();boolean created=jdbc.update("""
            INSERT INTO attendance_registers(id,organisation_id,school_id,academic_session_id,campus_id,class_section_id,calendar_day_id,
              attendance_policy_id,policy_snapshot,bell_schedule_period_id,period_name_snapshot,period_starts_at_snapshot,
              period_ends_at_snapshot,register_mode,status,expected_learner_count,created_by_membership_id,created_at,updated_at,row_version)
            VALUES(:id,:organisationId,:schoolId,:sessionId,:campusId,:sectionId,:dayId,:policyId,CAST(:policySnapshot AS jsonb),
              :periodId,:periodName,:periodStartsAt,:periodEndsAt,:mode,'DRAFT',:expected,:actorMembershipId,:now,:now,0)
            ON CONFLICT ON CONSTRAINT attendance_registers_natural_key_unique DO NOTHING
            """,s.params().addValue("id",id).addValue("sessionId",c.sessionId()).addValue("campusId",c.campusId()).addValue("sectionId",c.sectionId())
            .addValue("dayId",q.calendarDayId()).addValue("policyId",policy.id()).addValue("policySnapshot",json(policy.evidence())).addValue("periodId",q.periodId()).addValue("periodName",c.periodName()).addValue("periodStartsAt",c.periodStartsAt()).addValue("periodEndsAt",c.periodEndsAt()).addValue("mode",upper(q.mode())).addValue("expected",expected).addValue("now",Instant.now(clock)))>0;
        if(!created){ExistingRegister existing=jdbc.queryForObject("SELECT id,row_version FROM attendance_registers WHERE organisation_id=:organisationId AND school_id=:schoolId AND class_section_id=:sectionId AND calendar_day_id=:dayId AND bell_schedule_period_id IS NOT DISTINCT FROM :periodId",s.params().addValue("sectionId",q.sectionId()).addValue("dayId",q.calendarDayId()).addValue("periodId",q.periodId()),(r,n)->new ExistingRegister(r.getObject("id",UUID.class),r.getLong("row_version")));id=existing.id();registerVersion=existing.version();}
        if(created) {
            var leaveByEnrolment=approvedLeave.forRoster(s.organisationId(),s.schoolId(),c.sessionId(),
                    c.campusId(),c.date(),roster.stream().map(RosterLearner::enrolmentId).toList());
            for(RosterLearner learner:roster) {
                var leave=leaveByEnrolment.get(learner.enrolmentId());
                String state=leave==null?"PRESENT":leave.attendanceState();
                if(!policy.evidence().enabledStates().contains(state)) throw conflict("LEAVE_ATTENDANCE_STATE_DISABLED",
                        "The register policy does not support the approved leave state. Ask the school to review its attendance policy.");
                UUID markId=UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO attendance_marks(id,organisation_id,school_id,attendance_register_id,learner_id,
                          enrolment_id,state_code,note,recorded_by_membership_id,created_at,updated_at,row_version)
                        VALUES(:id,:organisationId,:schoolId,:registerId,:learnerId,:enrolmentId,:state,NULL,
                          :actorMembershipId,:now,:now,0)
                        """,s.params().addValue("id",markId).addValue("registerId",id)
                        .addValue("learnerId",learner.learnerId()).addValue("enrolmentId",learner.enrolmentId())
                        .addValue("state",state).addValue("now",Instant.now(clock)));
                if(leave==null) history(s,markId,null,state,"INITIAL",null);
                else jdbc.update("""
                        INSERT INTO attendance_mark_history(id,organisation_id,school_id,attendance_mark_id,
                          previous_state_code,new_state_code,change_kind,reason,actor_membership_id,occurred_at,
                          student_leave_request_id,student_leave_request_version)
                        VALUES(:id,:organisationId,:schoolId,:markId,NULL,:state,'LEAVE_INITIAL',
                          'Initialized from approved student leave',:actorMembershipId,:now,:leaveId,:leaveVersion)
                        """,s.params().addValue("id",UUID.randomUUID()).addValue("markId",markId)
                        .addValue("state",state).addValue("now",Instant.now(clock))
                        .addValue("leaveId",leave.requestId()).addValue("leaveVersion",leave.requestVersion()));
            }
        }
        audit(u,s,"attendance.register.opened","ATTENDANCE_REGISTER",id,null);return new AttendanceMutationResponse(id,registerVersion);}

    @Transactional public AttendanceMutationResponse recordMarks(AuthenticatedUser u,UUID registerId,RecordMarksRequest q){Scope s=requireScope(u,q.schoolId(),"attendance.record");StoredRegister reg=lockRegister(s,registerId,q.expectedRegisterVersion());authorizeRegister(s,reg.context(),reg.periodId());Set<String> allowed=policyEvidence(reg.policySnapshot()).enabledStates().stream().collect(java.util.stream.Collectors.toUnmodifiableSet());for(MarkInput m:q.marks()){String state=enumv(m.stateCode(),allowed,"attendance state");String note=clean(m.note());RosterLearner learner=rosterLearner(s,reg.sectionId(),reg.date(),m.learnerId());List<StoredMark> existing=jdbc.query("SELECT id,state_code,note,row_version FROM attendance_marks WHERE attendance_register_id=:registerId AND learner_id=:learnerId",s.params().addValue("registerId",registerId).addValue("learnerId",m.learnerId()),(r,n)->new StoredMark(r.getObject("id",UUID.class),r.getString("state_code"),r.getString("note"),r.getLong("row_version")));if(existing.isEmpty()){UUID markId=UUID.randomUUID();jdbc.update("""
                INSERT INTO attendance_marks(id,organisation_id,school_id,attendance_register_id,learner_id,enrolment_id,state_code,note,
                  recorded_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:registerId,:learnerId,:enrolmentId,:state,:note,:actorMembershipId,:now,:now,0)
                """,s.params().addValue("id",markId).addValue("registerId",registerId).addValue("learnerId",m.learnerId()).addValue("enrolmentId",learner.enrolmentId()).addValue("state",state).addValue("note",note).addValue("now",Instant.now(clock)));history(s,markId,null,state,"INITIAL",null);}else{StoredMark old=existing.getFirst();if(m.expectedVersion()==null||m.expectedVersion()!=old.version())throw conflict("STALE_ATTENDANCE_MARK","Refresh the register before changing an existing mark.");boolean stateChanged=!old.state().equals(state);boolean noteChanged=!Objects.equals(old.note(),note);if(stateChanged||noteChanged){String why=reason(q.reason());int n=jdbc.update("UPDATE attendance_marks SET state_code=:state,note=:note,recorded_by_membership_id=:actorMembershipId,updated_at=:now,row_version=row_version+1 WHERE id=:id AND row_version=:version",s.params().addValue("state",state).addValue("note",note).addValue("now",Instant.now(clock)).addValue("id",old.id()).addValue("version",old.version()));changed(n);if(stateChanged)history(s,old.id(),old.state(),state,"DRAFT_EDIT",why);}}}long v=touchRegister(s,registerId,reg.version());audit(u,s,"attendance.marks.recorded","ATTENDANCE_REGISTER",registerId,q.reason());return new AttendanceMutationResponse(registerId,v);}

    @Transactional public AttendanceMutationResponse submit(AuthenticatedUser u,UUID id,SubmitRegisterRequest q){Scope s=requireScope(u,q.schoolId(),"attendance.record");StoredRegister r=lockRegister(s,id,q.expectedVersion());authorizeRegister(s,r.context(),r.periodId());enforceSubmissionCutoff(s,r);int count=jdbc.queryForObject("SELECT count(*) FROM attendance_marks WHERE attendance_register_id=:id",s.params().addValue("id",id),Integer.class);if(count!=r.expected())throw conflict("ATTENDANCE_INCOMPLETE","Record every learner before submitting the register.");int n=jdbc.update("""
            UPDATE attendance_registers SET status='SUBMITTED',submitted_by_membership_id=:actorMembershipId,submitted_at=:now,
              updated_at=:now,row_version=row_version+1 WHERE id=:id AND status='DRAFT' AND row_version=:version
            """,s.params().addValue("id",id).addValue("version",r.version()).addValue("now",Instant.now(clock)));changed(n);audit(u,s,"attendance.register.submitted","ATTENDANCE_REGISTER",id,reason(q.reason()));return new AttendanceMutationResponse(id,r.version()+1);}

    @Transactional
    public AttendanceMutationResponse reviewDraftLeave(AuthenticatedUser u,UUID registerId,DraftLeaveReviewRequest q) {
        Scope s=requireScope(u,q.schoolId(),"attendance.record");
        StoredMarkContext context=markContext(s,q.markId());
        authorizeRegister(s,context.context(),context.periodId());
        if(q.operationId()==null) throw bad("LEAVE_REVIEW_OPERATION_REQUIRED","Include a unique review operation identifier.");
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked",
                new MapSqlParameterSource("key","attendance-leave-review:"+q.operationId()),Integer.class);
        String fingerprint=reviewFingerprint(registerId,q);
        var existing=jdbc.query("""
                SELECT attendance_register_id,resulting_register_version,request_fingerprint,actor_membership_id
                FROM attendance_leave_reviews WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """,s.params().addValue("id",q.operationId()),(row,index)->new ReviewRetry(
                row.getObject("attendance_register_id",UUID.class),row.getLong("resulting_register_version"),
                row.getString("request_fingerprint"),row.getObject("actor_membership_id",UUID.class)));
        if(!existing.isEmpty()) {
            ReviewRetry retry=existing.getFirst();
            if(!fingerprint.equals(retry.fingerprint()) || !s.actorMembershipId().equals(retry.actorMembershipId()))
                throw conflict("LEAVE_REVIEW_RETRY_CONFLICT","This review identifier was already used. Refresh before another review.");
            return new AttendanceMutationResponse(retry.registerId(),retry.registerVersion());
        }
        StoredRegister register=lockRegister(s,registerId,q.expectedRegisterVersion());
        authorizeRegister(s,register.context(),register.periodId());
        var marks=jdbc.query("""
                SELECT id,state_code,note,row_version FROM attendance_marks
                WHERE id=:markId AND attendance_register_id=:registerId
                  AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
                """,s.params().addValue("markId",q.markId()).addValue("registerId",registerId),
                (row,index)->new StoredMark(row.getObject("id",UUID.class),row.getString("state_code"),row.getString("note"),row.getLong("row_version")));
        if(marks.isEmpty()) throw notFound("ATTENDANCE_MARK_NOT_FOUND","The attendance mark was not found in this register.");
        StoredMark mark=marks.getFirst();
        if(mark.version()!=q.expectedMarkVersion()) throw conflict("STALE_ATTENDANCE_MARK","Refresh before reviewing this changed attendance mark.");
        var source=approvedLeave.requireReviewSource(s.organisationId(),s.schoolId(),registerId,mark.id(),q.leaveRequestId(),q.leaveRequestVersion());
        String decision=enumv(q.decision(),Set.of("KEEP_RECORDED","APPLY_APPROVED"),"leave review decision");
        String why=reason(q.reason());
        if(why.length()<3 || why.length()>240) throw bad("LEAVE_REVIEW_REASON_INVALID","Enter a review reason between 3 and 240 characters.");
        String state=mark.state(); long markVersion=mark.version(); UUID historyId=null;
        if("APPLY_APPROVED".equals(decision)) {
            if(!"APPROVED".equals(source.status())) throw conflict("LEAVE_REVIEW_NOT_APPROVED","Withdrawn or revoked leave cannot be applied to attendance.");
            state=source.attendanceState();
            if(!policyEvidence(register.policySnapshot()).enabledStates().contains(state))
                throw conflict("LEAVE_ATTENDANCE_STATE_DISABLED","The register policy does not support this approved leave state.");
            changed(jdbc.update("""
                    UPDATE attendance_marks SET state_code=:state,recorded_by_membership_id=:actorMembershipId,
                      updated_at=:now,row_version=row_version+1
                    WHERE id=:markId AND organisation_id=:organisationId AND school_id=:schoolId AND row_version=:version
                    """,s.params().addValue("markId",mark.id()).addValue("state",state)
                    .addValue("now",Instant.now(clock)).addValue("version",mark.version())));
            markVersion++;
            historyId=history(s,mark.id(),mark.state(),state,"DRAFT_EDIT",why);
        }
        long registerVersion=touchRegister(s,registerId,register.version());
        var p=s.params().addValue("id",q.operationId()).addValue("registerId",registerId).addValue("markId",mark.id())
                .addValue("leaveId",source.requestId()).addValue("leaveVersion",source.requestVersion())
                .addValue("status",source.status()).addValue("decision",decision).addValue("beforeState",mark.state())
                .addValue("afterState",state).addValue("beforeVersion",mark.version()).addValue("afterVersion",markVersion)
                .addValue("registerVersion",registerVersion).addValue("historyId",historyId)
                .addValue("reason",why).addValue("fingerprint",fingerprint).addValue("now",Instant.now(clock));
        safe("LEAVE_REVIEW_RETRY_CONFLICT",()->jdbc.update("""
                INSERT INTO attendance_leave_reviews(id,organisation_id,school_id,attendance_register_id,attendance_mark_id,
                  student_leave_request_id,student_leave_request_version,source_status,decision,previous_state_code,resulting_state_code,
                  previous_mark_version,resulting_mark_version,resulting_register_version,mark_history_id,actor_membership_id,
                  reason,request_fingerprint,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:registerId,:markId,:leaveId,:leaveVersion,:status,:decision,:beforeState,
                  :afterState,:beforeVersion,:afterVersion,:registerVersion,:historyId,:actorMembershipId,:reason,:fingerprint,:now)
                """,p));
        audit(u,s,"attendance.leave.reviewed","ATTENDANCE_LEAVE_REVIEW",q.operationId(),why);
        return new AttendanceMutationResponse(registerId,registerVersion);
    }

    private String reviewFingerprint(UUID registerId,DraftLeaveReviewRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json(List.of(registerId,request)).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("Required digest unavailable",failure); }
    }

    @Transactional
    public AttendanceMutationResponse requestCorrection(AuthenticatedUser u,CorrectionRequest q) {
        Scope s=requireScope(u,q.schoolId(),"attendance.record");
        StoredMarkContext m=markContext(s,q.markId());
        authorizeRegister(s,m.context(),m.periodId());
        if("DRAFT".equals(m.registerStatus())) throw conflict("REGISTER_NOT_SUBMITTED","Edit the draft mark directly before submission.");
        Set<String> allowed=Set.copyOf(policyEvidence(m.policySnapshot()).enabledStates());
        String state=enumv(q.requestedState(),allowed,"attendance state"), why=reason(q.reason());
        // Requests and decisions both lock the mark before other correction/source rows.
        // This avoids a new pending insert waiting on a decision that is itself waiting for the mark.
        jdbc.queryForObject("SELECT id FROM attendance_marks WHERE id=:markId AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE",
                s.params().addValue("markId",q.markId()),UUID.class);
        LeaveCorrectionSource source=q.leaveSource();
        if(source!=null) {
            if(source.requestId()==null || source.requestVersion()==null || source.requestVersion()<1
                    || source.expectedMarkVersion()==null || source.expectedMarkVersion()<0)
                throw bad("LEAVE_CORRECTION_SOURCE_INVALID","Include the current leave and attendance versions.");
            approvedLeave.requireCorrectionApproval(s.organisationId(),s.schoolId(),q.markId(),source.requestId(),
                    source.requestVersion(),source.expectedMarkVersion(),state);
        }
        UUID id=UUID.randomUUID();
        var p=s.params().addValue("id",id).addValue("markId",q.markId()).addValue("state",state)
                .addValue("reason",why).addValue("now",Instant.now(clock))
                .addValue("leaveId",source==null?null:source.requestId())
                .addValue("leaveVersion",source==null?null:source.requestVersion())
                .addValue("markVersion",source==null?null:source.expectedMarkVersion());
        int inserted=jdbc.update("""
            INSERT INTO attendance_correction_requests(id,organisation_id,school_id,attendance_mark_id,requested_state_code,request_reason,
              status,requested_by_membership_id,created_at,updated_at,row_version,
              student_leave_request_id,student_leave_request_version,source_mark_version)
            VALUES(:id,:organisationId,:schoolId,:markId,:state,:reason,'PENDING',:actorMembershipId,:now,:now,0,
              :leaveId,:leaveVersion,:markVersion)
            ON CONFLICT(attendance_mark_id) WHERE status='PENDING' DO NOTHING
            """,p);
        if(inserted==0) {
            // Exact leave-linked retries are safe; a different pending correction is never replaced.
            if(source!=null) {
                var existing=jdbc.query("""
                        SELECT id,row_version FROM attendance_correction_requests
                        WHERE organisation_id=:organisationId AND school_id=:schoolId AND attendance_mark_id=:markId
                          AND status='PENDING' AND requested_by_membership_id=:actorMembershipId
                          AND requested_state_code=:state AND request_reason=:reason
                          AND student_leave_request_id=:leaveId AND student_leave_request_version=:leaveVersion
                          AND source_mark_version=:markVersion
                        """,p,(row,index)->new AttendanceMutationResponse(row.getObject("id",UUID.class),row.getLong("row_version")));
                if(!existing.isEmpty()) return existing.getFirst();
            }
            throw conflict("CORRECTION_ALREADY_PENDING","A correction is already pending for this mark. Review it before creating another.");
        }
        audit(u,s,"attendance.correction.requested","ATTENDANCE_CORRECTION",id,why);
        return new AttendanceMutationResponse(id,0);
    }

    @Transactional public AttendanceMutationResponse decideCorrection(AuthenticatedUser u,UUID id,CorrectionDecisionRequest q){Scope s=requireScope(u,q.schoolId(),"attendance.correct");
        var lockedMarks=jdbc.query("""
                SELECT mark.id FROM attendance_marks mark JOIN attendance_correction_requests correction
                  ON correction.attendance_mark_id=mark.id AND correction.organisation_id=mark.organisation_id
                  AND correction.school_id=mark.school_id
                WHERE correction.id=:id AND correction.organisation_id=:organisationId AND correction.school_id=:schoolId
                FOR UPDATE OF mark
                """,s.params().addValue("id",id),(row,index)->row.getObject("id",UUID.class));
        if(lockedMarks.isEmpty())throw notFound("CORRECTION_NOT_FOUND","The correction request was not found.");
        List<CorrectionStored> rows=jdbc.query("""
            SELECT correction.attendance_mark_id,correction.requested_state_code,correction.status,correction.row_version,mark.state_code,
              correction.student_leave_request_id,correction.student_leave_request_version,correction.source_mark_version
            FROM attendance_correction_requests correction JOIN attendance_marks mark ON mark.id=correction.attendance_mark_id
            WHERE correction.id=:id AND correction.organisation_id=:organisationId AND correction.school_id=:schoolId FOR UPDATE
            """,s.params().addValue("id",id),(r,n)->new CorrectionStored(r.getObject("attendance_mark_id",UUID.class),r.getString("requested_state_code"),r.getString("status"),r.getLong("row_version"),r.getString("state_code"),
                    r.getObject("student_leave_request_id",UUID.class),r.getObject("student_leave_request_version",Long.class),r.getObject("source_mark_version",Long.class)));
        if(rows.isEmpty())throw notFound("CORRECTION_NOT_FOUND","The correction request was not found.");
        CorrectionStored c=rows.getFirst();
        if(c.version()!=q.expectedVersion()||!"PENDING".equals(c.status()))throw conflict("STALE_CORRECTION","The correction request has already changed.");
        String decision=enumv(q.decision(),Set.of("APPROVED","REJECTED"),"decision"),why=reason(q.reason());
        if("APPROVED".equals(decision)) {
            if(c.leaveRequestId()!=null) approvedLeave.requireCorrectionApproval(s.organisationId(),s.schoolId(),c.markId(),
                    c.leaveRequestId(),c.leaveVersion(),c.sourceMarkVersion(),c.requestedState());
            jdbc.queryForObject("SELECT set_config('app.attendance_correction','approved',true)",new MapSqlParameterSource(),String.class);
            jdbc.update("UPDATE attendance_marks SET state_code=:state,recorded_by_membership_id=:actorMembershipId,updated_at=:now,row_version=row_version+1 WHERE id=:markId",s.params().addValue("state",c.requestedState()).addValue("now",Instant.now(clock)).addValue("markId",c.markId()));
            history(s,c.markId(),c.currentState(),c.requestedState(),"CORRECTION",why);
        }
        jdbc.update("""
            UPDATE attendance_correction_requests SET status=:decision,decided_by_membership_id=:actorMembershipId,decision_reason=:reason,
              decided_at=:now,updated_at=:now,row_version=row_version+1 WHERE id=:id AND row_version=:version
            """,s.params().addValue("decision",decision).addValue("reason",why).addValue("now",Instant.now(clock)).addValue("id",id).addValue("version",c.version()));audit(u,s,"attendance.correction.decided","ATTENDANCE_CORRECTION",id,why);return new AttendanceMutationResponse(id,c.version()+1);}

    @Transactional public AttendanceMutationResponse decideExplanation(AuthenticatedUser u,UUID id,ExplanationDecisionRequest q){Scope s=requireScope(u,q.schoolId(),"attendance.correct");List<ExplanationStored>rows=jdbc.query("SELECT status,row_version FROM attendance_explanations WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE",s.params().addValue("id",id),(r,n)->new ExplanationStored(r.getString("status"),r.getLong("row_version")));if(rows.isEmpty())throw notFound("ATTENDANCE_EXPLANATION_NOT_FOUND","The attendance explanation was not found.");ExplanationStored stored=rows.getFirst();if(stored.version()!=q.expectedVersion()||!"SUBMITTED".equals(stored.status()))throw conflict("STALE_ATTENDANCE_EXPLANATION","The attendance explanation has already changed.");String decision=enumv(q.decision(),Set.of("ACCEPTED","DECLINED"),"explanation decision");String why=reason(q.reason());int n=jdbc.update("UPDATE attendance_explanations SET status=:decision,reviewed_by_membership_id=:actorMembershipId,review_reason=:reason,reviewed_at=:now,updated_at=:now,row_version=row_version+1 WHERE id=:id AND row_version=:version",s.params().addValue("decision",decision).addValue("reason",why).addValue("now",Instant.now(clock)).addValue("id",id).addValue("version",stored.version()));changed(n);audit(u,s,"attendance.explanation.decided","ATTENDANCE_EXPLANATION",id,why);return new AttendanceMutationResponse(id,stored.version()+1);}

    private Scope requireScope(AuthenticatedUser u,UUID schoolId,String cap){if(u.activeOrganisationId()==null)throw conflict("ORGANISATION_SELECTION_REQUIRED","Select a school organisation first.");List<Scope> x=jdbc.query("""
            SELECT school.id AS school_id,school.display_name,membership.id AS membership_id,
              EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                JOIN role_capabilities rc ON rc.role_id=role.id WHERE mr.membership_id=membership.id
                AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                AND rc.capability_code='attendance.configure') can_configure,
              EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                JOIN role_capabilities rc ON rc.role_id=role.id WHERE mr.membership_id=membership.id
                AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                AND rc.capability_code='attendance.correct') can_correct
            FROM memberships membership JOIN schools school ON school.id=membership.school_id
              AND school.organisation_id=membership.organisation_id
            JOIN organisations organisation ON organisation.id=membership.organisation_id
            JOIN user_accounts account ON account.id=membership.user_id
            JOIN membership_roles mr ON mr.membership_id=membership.id JOIN roles role ON role.id=mr.role_id
            JOIN role_capabilities rc ON rc.role_id=role.id
            WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId AND membership.id IN(:membershipIds)
              AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId) AND membership.status='ACTIVE' AND rc.capability_code=:cap
              AND membership.valid_from<=:now AND (membership.valid_until IS NULL OR membership.valid_until>:now)
              AND account.status='ACTIVE' AND school.status='ACTIVE' AND organisation.status='ACTIVE'
              AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
            ORDER BY can_configure DESC,membership.created_at LIMIT 1
            """,new MapSqlParameterSource().addValue("userId",u.userId()).addValue("organisationId",u.activeOrganisationId()).addValue("membershipIds",u.membershipIds()).addValue("schoolId",schoolId).addValue("cap",cap).addValue("now",Instant.now(clock)),(r,n)->new Scope(u.activeOrganisationId(),r.getObject("school_id",UUID.class),r.getString("display_name"),r.getObject("membership_id",UUID.class),r.getBoolean("can_configure"),r.getBoolean("can_correct")));if(x.isEmpty())throw new ApiException(HttpStatus.FORBIDDEN,"ATTENDANCE_SCOPE_DENIED","Attendance access is unavailable for this school.");return x.getFirst();}

    private RegisterContext registerContext(Scope s,UUID sectionId,UUID dayId,UUID periodId,String mode){String m=enumv(mode,Set.of("DAILY","PERIOD"),"register mode");if(("DAILY".equals(m))!=(periodId==null))throw bad("REGISTER_MODE_PERIOD_MISMATCH","Daily registers have no period; period registers require one.");List<RegisterContext>x=jdbc.query("""
            SELECT section.id,section.campus_id,section.academic_session_id,day.calendar_date,day.attendance_required,day.status,
              period.day_of_week,period.display_name AS period_name,period.starts_at AS period_starts_at,
              period.ends_at AS period_ends_at,campus.time_zone FROM class_sections section
              JOIN campuses campus ON campus.id=section.campus_id
              JOIN school_calendar_days day ON day.id=:dayId AND day.campus_id=section.campus_id AND day.academic_session_id=section.academic_session_id
              LEFT JOIN bell_schedule_periods period ON period.id=:periodId AND period.campus_id=section.campus_id AND period.academic_session_id=section.academic_session_id
            WHERE section.id=:sectionId AND section.organisation_id=:organisationId AND section.school_id=:schoolId AND section.status='ACTIVE'
              AND (CAST(:periodId AS uuid) IS NULL OR period.id IS NOT NULL)
            """,s.params().addValue("sectionId",sectionId).addValue("dayId",dayId).addValue("periodId",periodId),(r,n)->new RegisterContext(r.getObject("id",UUID.class),r.getObject("campus_id",UUID.class),r.getObject("academic_session_id",UUID.class),r.getObject("calendar_date",LocalDate.class),m,r.getObject("day_of_week",Integer.class),r.getString("period_name"),r.getObject("period_starts_at",LocalTime.class),r.getObject("period_ends_at",LocalTime.class),r.getString("time_zone")));if(x.isEmpty())throw bad("REGISTER_CONTEXT_INVALID","Choose a matching section, calendar day, and period.");RegisterContext c=x.getFirst();if(!jdbc.queryForObject("SELECT attendance_required AND status='ACTIVE' FROM school_calendar_days WHERE id=:dayId",s.params().addValue("dayId",dayId),Boolean.class))throw conflict("ATTENDANCE_NOT_REQUIRED","Attendance is not required for this calendar day.");if(c.dayOfWeek()!=null&&c.date().getDayOfWeek().getValue()!=c.dayOfWeek())throw bad("PERIOD_DAY_MISMATCH","The selected period does not occur on this calendar date.");return c;}
    private void authorizeRegister(Scope s,RegisterContext c,UUID periodId){if(s.canConfigure())return;int n;if(periodId==null)n=jdbc.queryForObject("SELECT count(*) FROM section_subject_offerings o JOIN teaching_allocations a ON a.section_subject_offering_id=o.id WHERE o.class_section_id=:sectionId AND a.teacher_membership_id=:actorMembershipId AND a.status='ACTIVE' AND a.starts_on<=:date AND (a.ends_on IS NULL OR a.ends_on>=:date)",s.params().addValue("sectionId",c.sectionId()).addValue("date",c.date()),Integer.class);else n=jdbc.queryForObject("""
            SELECT count(*) FROM timetable_plans plan JOIN timetable_entries entry ON entry.timetable_plan_id=plan.id
            WHERE plan.status='PUBLISHED' AND entry.class_section_id=:sectionId AND entry.bell_schedule_period_id=:periodId
              AND entry.teacher_membership_id=:actorMembershipId AND plan.organisation_id=:organisationId AND plan.school_id=:schoolId
            """,s.params().addValue("sectionId",c.sectionId()).addValue("periodId",periodId),Integer.class);if(n<1)throw new ApiException(HttpStatus.FORBIDDEN,"REGISTER_ASSIGNMENT_DENIED","This register is outside your teaching assignment.");}
    private StoredRegister lockRegister(Scope s,UUID id,long v){List<StoredRegister>x=jdbc.query("""
            SELECT register.*,day.calendar_date,campus.time_zone FROM attendance_registers register
            JOIN school_calendar_days day ON day.id=register.calendar_day_id
            JOIN campuses campus ON campus.id=register.campus_id
            WHERE register.id=:id AND register.organisation_id=:organisationId AND register.school_id=:schoolId FOR UPDATE OF register
            """,s.params().addValue("id",id),(r,n)->new StoredRegister(r.getObject("id",UUID.class),r.getObject("academic_session_id",UUID.class),r.getObject("campus_id",UUID.class),r.getObject("class_section_id",UUID.class),r.getObject("bell_schedule_period_id",UUID.class),r.getObject("calendar_date",LocalDate.class),r.getString("register_mode"),r.getString("status"),r.getInt("expected_learner_count"),r.getString("policy_snapshot"),r.getString("time_zone"),r.getLong("row_version")));if(x.isEmpty())throw notFound("REGISTER_NOT_FOUND","The attendance register was not found.");StoredRegister r=x.getFirst();if(!"DRAFT".equals(r.status()))throw conflict("REGISTER_IMMUTABLE","Submitted registers require a correction request.");if(r.version()!=v)throw conflict("STALE_REGISTER","Refresh the register before continuing.");return r;}
    private List<RosterLearner> roster(Scope s,UUID section,LocalDate date){return jdbc.query("""
            SELECT placement.learner_id,enrolment.id AS enrolment_id FROM student_section_placements placement JOIN student_enrolments enrolment ON enrolment.id=placement.enrolment_id
            WHERE placement.class_section_id=:section AND placement.organisation_id=:organisationId AND placement.school_id=:schoolId
              AND placement.starts_on<=:date AND (placement.ends_on IS NULL OR placement.ends_on>=:date)
              AND placement.status<>'CANCELLED' AND enrolment.starts_on<=:date
              AND (enrolment.ends_on IS NULL OR enrolment.ends_on>=:date) AND enrolment.status='ACTIVE'
            ORDER BY placement.roll_number NULLS LAST,placement.learner_id
            """,s.params().addValue("section",section).addValue("date",date),(r,n)->new RosterLearner(r.getObject("learner_id",UUID.class),r.getObject("enrolment_id",UUID.class)));}
    private RosterLearner rosterLearner(Scope s,UUID section,LocalDate date,UUID learner){List<RosterLearner>x=jdbc.query("""
            SELECT enrolment.id FROM student_section_placements placement JOIN student_enrolments enrolment ON enrolment.id=placement.enrolment_id
            WHERE placement.class_section_id=:section AND placement.learner_id=:learner AND placement.organisation_id=:organisationId AND placement.school_id=:schoolId
              AND placement.starts_on<=:date AND (placement.ends_on IS NULL OR placement.ends_on>=:date)
              AND placement.status<>'CANCELLED' AND enrolment.starts_on<=:date
              AND (enrolment.ends_on IS NULL OR enrolment.ends_on>=:date) AND enrolment.status='ACTIVE'
            """,s.params().addValue("section",section).addValue("learner",learner).addValue("date",date),(r,n)->new RosterLearner(learner,r.getObject("id",UUID.class)));if(x.isEmpty())throw bad("LEARNER_NOT_IN_REGISTER_ROSTER","A learner is outside this register's dated section roster.");return x.getFirst();}
    private ActivePolicy activePolicy(Scope s,UUID session){List<ActivePolicy>rows=jdbc.query("SELECT * FROM attendance_policies WHERE academic_session_id=:session AND organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'",s.params().addValue("session",session),(r,n)->{List<String>enabled=arrayStrings(r.getArray("enabled_state_codes"));PolicyEvidence evidence=new PolicyEvidence(r.getString("policy_name"),r.getString("register_mode"),enabled,stateWeights(r.getString("state_weights")),r.getObject("submission_cutoff",LocalTime.class),r.getInt("lock_after_hours"),r.getInt("guardian_explanation_days"),r.getBigDecimal("warning_threshold_percent"));return new ActivePolicy(r.getObject("id",UUID.class),r.getString("register_mode"),evidence);});if(rows.isEmpty())throw conflict("ATTENDANCE_POLICY_REQUIRED","Configure an active attendance policy for this academic session first.");return rows.getFirst();}
    private StoredMarkContext markContext(Scope s,UUID mark){List<StoredMarkContext>x=jdbc.query("""
            SELECT mark.id,register.academic_session_id,register.campus_id,register.class_section_id,register.bell_schedule_period_id,
              day.calendar_date,register.register_mode,register.status,register.policy_snapshot FROM attendance_marks mark JOIN attendance_registers register ON register.id=mark.attendance_register_id JOIN school_calendar_days day ON day.id=register.calendar_day_id
            WHERE mark.id=:mark AND mark.organisation_id=:organisationId AND mark.school_id=:schoolId
            """,s.params().addValue("mark",mark),(r,n)->new StoredMarkContext(r.getObject("id",UUID.class),r.getObject("academic_session_id",UUID.class),r.getObject("campus_id",UUID.class),r.getObject("class_section_id",UUID.class),r.getObject("bell_schedule_period_id",UUID.class),r.getObject("calendar_date",LocalDate.class),r.getString("register_mode"),r.getString("status"),r.getString("policy_snapshot")));if(x.isEmpty())throw notFound("ATTENDANCE_MARK_NOT_FOUND","The attendance mark was not found.");return x.getFirst();}
    private long touchRegister(Scope s,UUID id,long v){int n=jdbc.update("UPDATE attendance_registers SET updated_at=:now,row_version=row_version+1 WHERE id=:id AND row_version=:version AND status='DRAFT'",s.params().addValue("id",id).addValue("version",v).addValue("now",Instant.now(clock)));changed(n);return v+1;}
    private void enforceSubmissionCutoff(Scope s,StoredRegister register){if(s.canConfigure())return;PolicyEvidence policy=policyEvidence(register.policySnapshot());if(policy.submissionCutoff()==null)return;Clock campusClock=clock.withZone(ZoneId.of(register.timeZone()));LocalDate localDate=LocalDate.now(campusClock);LocalTime localTime=LocalTime.now(campusClock);if(register.date().isBefore(localDate)||(register.date().equals(localDate)&&localTime.isAfter(policy.submissionCutoff())))throw conflict("ATTENDANCE_SUBMISSION_CUTOFF_PASSED","The school-configured submission cut-off has passed. Ask an attendance authority for help.");}
    private UUID history(Scope s,UUID mark,String old,String state,String kind,String why){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO attendance_mark_history(id,organisation_id,school_id,attendance_mark_id,previous_state_code,new_state_code,change_kind,reason,actor_membership_id,occurred_at) VALUES(:id,:organisationId,:schoolId,:mark,:old,:state,:kind,:reason,:actorMembershipId,:now)",s.params().addValue("id",id).addValue("mark",mark).addValue("old",old).addValue("state",state).addValue("kind",kind).addValue("reason",why).addValue("now",Instant.now(clock)));return id;}
    private void validatePolicy(PolicyRequest q){enumv(q.registerMode(),MODES,"register mode");Set<String>x=new LinkedHashSet<>();q.enabledStates().forEach(v->x.add(enumv(v,STATES,"attendance state")));if(!x.containsAll(Set.of("PRESENT","ABSENT")))throw bad("REQUIRED_ATTENDANCE_STATES","Present and absent must remain enabled.");Set<String>weightStates=new LinkedHashSet<>();q.stateWeights().forEach((key,value)->{weightStates.add(enumv(key,STATES,"attendance state weight"));if(value==null||value.compareTo(BigDecimal.ZERO)<0||value.compareTo(BigDecimal.ONE)>0)throw bad("ATTENDANCE_WEIGHT_INVALID","Every enabled attendance state needs a credit weight from 0 to 1.");});if(!weightStates.equals(x))throw bad("ATTENDANCE_WEIGHT_MISMATCH","Provide exactly one credit weight for every enabled attendance state.");enumv(q.status(),STATUSES,"policy status");}
    private void validateCalendar(Scope s,CalendarDayRequest q){String type=enumv(q.dayType(),DAY_TYPES,"calendar day type");if(Set.of("HOLIDAY","CLOSURE").contains(type))throw bad("GOVERNED_CALENDAR_WORKFLOW_REQUIRED","Use Calendar & closures so the bilingual notice, independent approval, and family notification evidence stay together.");if(q.attendanceRequired()&&!Set.of("INSTRUCTIONAL","EXAM","EVENT").contains(type))throw bad("CALENDAR_ATTENDANCE_INVALID","Holidays and closures cannot require attendance.");int n=jdbc.queryForObject("SELECT count(*) FROM campuses campus JOIN academic_sessions session ON session.id=:sessionId AND session.school_id=campus.school_id WHERE campus.id=:campusId AND campus.organisation_id=:organisationId AND campus.school_id=:schoolId AND :date BETWEEN session.starts_on AND session.ends_on",s.params().addValue("campusId",q.campusId()).addValue("sessionId",q.sessionId()).addValue("date",q.date()),Integer.class);if(n!=1)throw bad("CALENDAR_SCOPE_INVALID","Choose a date inside a matching campus academic session.");enumv(q.status(),STATUSES,"calendar status");}
    private MapSqlParameterSource policyParams(Scope s,UUID id,PolicyRequest q,Long v){Map<String,BigDecimal>weights=new TreeMap<>();q.stateWeights().forEach((key,value)->weights.put(upper(key),value));return s.params().addValue("id",id).addValue("sessionId",q.sessionId()).addValue("name",required(q.name())).addValue("mode",enumv(q.registerMode(),MODES,"register mode")).addValue("states",q.enabledStates().stream().map(AttendanceService::upper).distinct().toArray(String[]::new)).addValue("stateWeights",json(weights)).addValue("cutoff",q.submissionCutoff()).addValue("lockHours",q.lockAfterHours()).addValue("explanationDays",q.guardianExplanationDays()).addValue("threshold",q.warningThresholdPercent()).addValue("status",enumv(q.status(),STATUSES,"calendar status")).addValue("version",v).addValue("now",Instant.now(clock));}
    private MapSqlParameterSource calendarParams(Scope s,UUID id,CalendarDayRequest q,Long v){return s.params().addValue("id",id).addValue("campusId",q.campusId()).addValue("sessionId",q.sessionId()).addValue("date",q.date()).addValue("dayType",enumv(q.dayType(),DAY_TYPES,"day type")).addValue("name",required(q.name())).addValue("required",q.attendanceRequired()).addValue("status",enumv(q.status(),STATUSES,"calendar status")).addValue("version",v).addValue("now",Instant.now(clock));}
    private void audit(AuthenticatedUser u,Scope s,String action,String type,UUID id,String why){audit.recordScoped(u,s.actorMembershipId(),s.schoolId(),action,type,id.toString(),AuditOutcome.SUCCEEDED,clean(why),Map.of());}
    private static List<String> arrayStrings(Array a){if(a==null)return List.of();try{return Arrays.stream((Object[])a.getArray()).map(Object::toString).toList();}catch(SQLException e){throw new IllegalStateException(e);}}
    private Map<String,BigDecimal> stateWeights(String value){try{return objectMapper.readValue(value,new TypeReference<>(){});}catch(JacksonException e){throw new IllegalStateException("Invalid attendance state weights",e);}}
    private PolicyEvidence policyEvidence(String value){try{return objectMapper.readValue(value,PolicyEvidence.class);}catch(JacksonException e){throw new IllegalStateException("Invalid frozen attendance policy",e);}}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(JacksonException e){throw new IllegalStateException("Unable to encode attendance configuration",e);}}
    private static <T>T safe(String code,Work<T>w){try{return w.run();}catch(DataIntegrityViolationException e){throw conflict(code,"The same attendance configuration or pending workflow already exists.");}}
    private static void changed(int n){if(n!=1)throw conflict("STALE_VERSION","The record changed. Refresh and try again.");}
    private static long version(Long v){if(v==null)throw bad("EXPECTED_VERSION_REQUIRED","Include the current record version before editing.");return v;}
    private static String reason(String s){String x=clean(s);if(x==null)throw bad("CHANGE_REASON_REQUIRED","Enter a reason for this change.");return x;}
    private static String required(String s){String x=clean(s);if(x==null)throw bad("REQUIRED_VALUE_MISSING","Enter the required value.");return x;}
    private static String enumv(String s,Set<String>a,String label){String x=upper(s);if(!a.contains(x))throw bad("INVALID_VALUE","Choose a supported "+label+".");return x;}
    private static String upper(String s){return required(s).toUpperCase(Locale.ROOT).replace(' ','_');}
    private static String clean(String s){return s==null||s.isBlank()?null:s.replaceAll("[\\p{Cntrl}]"," ").strip();}
    private static ApiException bad(String c,String m){return new ApiException(HttpStatus.BAD_REQUEST,c,m);}private static ApiException conflict(String c,String m){return new ApiException(HttpStatus.CONFLICT,c,m);}private static ApiException notFound(String c,String m){return new ApiException(HttpStatus.NOT_FOUND,c,m);}
    private record Scope(UUID organisationId,UUID schoolId,String schoolName,UUID actorMembershipId,boolean canConfigure,boolean canCorrect){MapSqlParameterSource params(){return new MapSqlParameterSource().addValue("organisationId",organisationId).addValue("schoolId",schoolId).addValue("actorMembershipId",actorMembershipId);}}
    private record RegisterContext(UUID sectionId,UUID campusId,UUID sessionId,LocalDate date,String mode,Integer dayOfWeek,String periodName,LocalTime periodStartsAt,LocalTime periodEndsAt,String timeZone){}
    private record StoredRegister(UUID id,UUID sessionId,UUID campusId,UUID sectionId,UUID periodId,LocalDate date,String mode,String status,int expected,String policySnapshot,String timeZone,long version){RegisterContext context(){return new RegisterContext(sectionId,campusId,sessionId,date,mode,null,null,null,null,timeZone);}}
    private record RosterLearner(UUID learnerId,UUID enrolmentId){} private record StoredMark(UUID id,String state,String note,long version){} private record ExistingRegister(UUID id,long version){}
    private record StoredMarkContext(UUID id,UUID sessionId,UUID campusId,UUID sectionId,UUID periodId,LocalDate date,String mode,String registerStatus,String policySnapshot){RegisterContext context(){return new RegisterContext(sectionId,campusId,sessionId,date,mode,null,null,null,null,"UTC");}}
    private record ActivePolicy(UUID id,String mode,PolicyEvidence evidence){}
    private record PolicyEvidence(String policyName,String registerMode,List<String> enabledStates,Map<String,BigDecimal> stateWeights,LocalTime submissionCutoff,int lockAfterHours,int guardianExplanationDays,BigDecimal warningThresholdPercent){}
    private record CorrectionStored(UUID markId,String requestedState,String status,long version,String currentState,
            UUID leaveRequestId,Long leaveVersion,Long sourceMarkVersion){}
    private record ExplanationStored(String status,long version){}
    private record ReviewRetry(UUID registerId,long registerVersion,String fingerprint,UUID actorMembershipId){}
    @FunctionalInterface private interface Work<T>{T run();}
}

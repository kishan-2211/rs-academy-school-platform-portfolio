package com.educore.api.studentleave;

import static com.educore.api.studentleave.StudentLeaveModels.*;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.TenantRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class StudentLeaveService {
    private static final int PAGE_SIZE = 25;
    private static final Set<String> STATUSES = Set.of("SUBMITTED","APPROVED","REJECTED","WITHDRAWN","REVOKED");
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final ObjectMapper json;

    public StudentLeaveService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit, Clock clock, ObjectMapper json) {
        this.jdbc=jdbc; this.audit=audit; this.clock=clock; this.json=json;
    }

    @Transactional(readOnly=true)
    public Workspace workspace(AuthenticatedUser user, UUID schoolId, String status, int page) {
        Scope scope = scope(user, schoolId, null, false);
        return new Workspace(scope.schoolId(),scope.schoolName(),sessions(scope),policies(scope),page(scope,status,page));
    }

    @Transactional(readOnly=true)
    public FamilyWorkspace family(AuthenticatedUser user, UUID learnerId, int page) {
        Scope scope = scope(user,null,learnerId,true);
        return new FamilyWorkspace(learnerId,sessions(scope),policies(scope),page(scope,null,page));
    }

    @Transactional(readOnly=true)
    public Detail staffDetail(AuthenticatedUser user, UUID schoolId, UUID id) {
        return detail(scope(user,schoolId,null,false),id);
    }

    @Transactional(readOnly=true)
    public Detail familyDetail(AuthenticatedUser user, UUID learnerId, UUID id) {
        return detail(scope(user,null,learnerId,true),id);
    }

    @Transactional
    public Mutation savePolicy(AuthenticatedUser user, PolicyRequest request) {
        Scope scope=scope(user,request.schoolId(),null,false);
        String reason=text(request.reason(),3,500);
        if(request.maxCalendarDays()<1 || request.maxCalendarDays()>366 || request.retrospectiveDays()<0
                || request.retrospectiveDays()>90 || !Set.of("EXCUSED","ABSENT").contains(request.attendanceState()))
            throw bad("LEAVE_POLICY_INVALID","Choose valid leave limits and an absence state.");
        var p=params(scope).addValue("sessionId",request.sessionId()).addValue("state",request.attendanceState());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM academic_sessions s JOIN attendance_policies a
                  ON a.academic_session_id=s.id AND a.organisation_id=s.organisation_id AND a.school_id=s.school_id
                  WHERE s.id=:sessionId AND s.organisation_id=:organisationId AND s.school_id=:schoolId
                  AND s.status IN ('ACTIVE','PLANNED') AND a.status='ACTIVE' AND :state=ANY(a.enabled_state_codes))
                """,p,Boolean.class))) throw conflict("LEAVE_ATTENDANCE_POLICY_REQUIRED",
                "Configure an active attendance policy with this absence state for the session first.");
        lock("student-leave-policy:"+scope.schoolId()+":"+request.sessionId());
        var existing=jdbc.query("SELECT id,row_version FROM student_leave_policies WHERE school_id=:schoolId AND organisation_id=:organisationId AND academic_session_id=:sessionId FOR UPDATE",
                p,(r,n)->new Mutation(r.getObject("id",UUID.class),r.getLong("row_version"),"CONFIGURED"));
        UUID id=existing.isEmpty()?UUID.randomUUID():existing.getFirst().id();
        p.addValue("id",id).addValue("maxDays",request.maxCalendarDays()).addValue("retroDays",request.retrospectiveDays())
                .addValue("enabled",request.enabled());
        long version=0;
        if(existing.isEmpty()) {
            if(request.expectedVersion()!=null) throw conflict("STALE_VERSION","The leave policy does not exist. Refresh first.");
            jdbc.update("""
                    INSERT INTO student_leave_policies(id,organisation_id,school_id,academic_session_id,max_calendar_days,
                      retrospective_days,attendance_state,enabled,updated_by_membership_id,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:sessionId,:maxDays,:retroDays,:state,:enabled,:membershipId,:now,:now,0)
                    """,p);
        } else {
            if(request.expectedVersion()==null || existing.getFirst().rowVersion()!=request.expectedVersion())
                throw conflict("STALE_VERSION","The leave policy changed. Refresh first.");
            version=existing.getFirst().rowVersion()+1;
            jdbc.update("""
                    UPDATE student_leave_policies SET max_calendar_days=:maxDays,retrospective_days=:retroDays,
                      attendance_state=:state,enabled=:enabled,updated_by_membership_id=:membershipId,updated_at=:now,
                      row_version=row_version+1 WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                    """,p);
        }
        audit.recordScoped(user,scope.membershipId(),scope.schoolId(),"student-leave.policy.saved","STUDENT_LEAVE_POLICY",
                id.toString(),AuditOutcome.SUCCEEDED,reason,Map.of("sessionId",request.sessionId(),"rowVersion",version,
                        "maxCalendarDays",request.maxCalendarDays(),"retrospectiveDays",request.retrospectiveDays(),
                        "attendanceState",request.attendanceState(),"enabled",request.enabled()));
        return new Mutation(id,version,"CONFIGURED");
    }

    @Transactional
    public Mutation submit(AuthenticatedUser user, UUID learnerId, SubmitRequest request) {
        Scope scope=scope(user,null,learnerId,true);
        String reason=text(request.reason(),3,1000), key=text(request.idempotencyKey(),8,100);
        if(request.sessionId()==null || request.startsOn()==null || request.endsOn()==null
                || !Set.of("PERSONAL","ILLNESS","FAMILY","OTHER").contains(request.leaveType()))
            throw bad("LEAVE_REQUEST_INVALID","Choose a session, date range and leave type.");
        String fingerprint=fingerprint(List.of(learnerId,request.sessionId(),request.startsOn(),request.endsOn(),request.leaveType(),reason));
        var p=params(scope).addValue("key",key).addValue("fingerprint",fingerprint);
        lock("student-leave-learner:"+scope.schoolId()+":"+learnerId);
        var prior=jdbc.query("""
                SELECT id,row_version,status,request_fingerprint FROM student_leave_requests
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND guardian_id=:guardianId AND idempotency_key=:key
                """,p,(r,n)->new Retry(new Mutation(r.getObject("id",UUID.class),r.getLong("row_version"),r.getString("status")),r.getString("request_fingerprint")));
        if(!prior.isEmpty()) {
            if(!prior.getFirst().fingerprint().equals(fingerprint)) throw conflict("LEAVE_RETRY_MISMATCH","This retry key belongs to a different request.");
            return prior.getFirst().mutation();
        }
        var enrolment=enrolment(scope,request.sessionId(),request.startsOn(),request.endsOn());
        var policy=policies(scope).stream().filter(x->x.sessionId().equals(request.sessionId()) && x.enabled()).findFirst()
                .orElseThrow(()->conflict("LEAVE_NOT_CONFIGURED","The school has not enabled leave requests for this session."));
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of(enrolment.timeZone())));
        validateDates(request.startsOn(),request.endsOn(),today,policy.maxCalendarDays(),policy.retrospectiveDays());
        p.addValue("startsOn",request.startsOn()).addValue("endsOn",request.endsOn());
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM student_leave_date_claims WHERE organisation_id=:organisationId
                  AND school_id=:schoolId AND learner_id=:learnerId AND calendar_date BETWEEN :startsOn AND :endsOn)
                """,p,Boolean.class))) throw conflict("LEAVE_OVERLAP","A pending or approved leave request already covers part of these dates.");
        UUID id=UUID.randomUUID();
        p.addValue("id",id).addValue("sessionId",request.sessionId()).addValue("campusId",enrolment.campusId())
                .addValue("enrolmentId",enrolment.id()).addValue("policyId",policy.id()).addValue("snapshot",encode(policy))
                .addValue("startsOn",request.startsOn()).addValue("endsOn",request.endsOn())
                .addValue("type",request.leaveType()).addValue("reason",reason);
        try {
            jdbc.update("""
                    INSERT INTO student_leave_requests(id,organisation_id,school_id,academic_session_id,campus_id,
                      enrolment_id,learner_id,guardian_id,requested_by_user_id,requested_by_membership_id,policy_id,
                      policy_snapshot,starts_on,ends_on,leave_type,request_reason,idempotency_key,request_fingerprint,
                      status,action_by_membership_id,action_reason,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:sessionId,:campusId,:enrolmentId,:learnerId,:guardianId,
                      :userId,:membershipId,:policyId,CAST(:snapshot AS jsonb),:startsOn,:endsOn,:type,:reason,:key,
                      :fingerprint,'SUBMITTED',:membershipId,'Leave request submitted',:now,:now,0)
                    """,p);
        } catch(DataIntegrityViolationException ex) {
            throw conflict("LEAVE_CONFLICT","The leave dates or request changed. Refresh before trying again.");
        }
        record(user,scope,id,"submitted",0);
        return new Mutation(id,0,"SUBMITTED");
    }

    @Transactional
    public Mutation decide(AuthenticatedUser user, UUID id, DecisionRequest request) {
        Scope scope=scope(user,request.schoolId(),null,false);
        String decision=request.decision(),reason=text(request.reason(),3,1000);
        if(!Set.of("APPROVED","REJECTED","REVOKED").contains(decision)) throw bad("LEAVE_DECISION_INVALID","Choose a supported decision.");
        Stored stored=locked(scope,id,request.expectedVersion());
        if(("REVOKED".equals(decision) && !"APPROVED".equals(stored.status()))
                || (!"REVOKED".equals(decision) && !"SUBMITTED".equals(stored.status())))
            throw conflict("LEAVE_TRANSITION_INVALID","This leave request can no longer receive that decision.");
        var p=params(scope).addValue("learnerId",stored.learnerId());
        if(user.userId().equals(stored.requesterUserId()) || Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM guardians g JOIN learner_guardian_relationships r ON r.guardian_id=g.id
                  AND r.organisation_id=g.organisation_id AND r.school_id=g.school_id
                  WHERE g.user_account_id=:userId AND r.learner_id=:learnerId
                  AND r.organisation_id=:organisationId AND r.school_id=:schoolId)
                """,p,Boolean.class))) throw conflict("INDEPENDENT_LEAVE_DECISION_REQUIRED","Another authorized staff member must decide leave for your own child.");
        if("APPROVED".equals(decision)) {
            if(!currentSource(stored,scope)) throw conflict("LEAVE_SOURCE_UNAVAILABLE","The requesting family's access or enrolment has changed. Review the request before deciding.");
            enrolment(scope.withLearner(stored.learnerId()),stored.sessionId(),stored.startsOn(),stored.endsOn());
        }
        return transition(user,scope,stored,decision,reason);
    }

    @Transactional
    public Mutation withdraw(AuthenticatedUser user, UUID learnerId, UUID id, WithdrawalRequest request) {
        Scope scope=scope(user,null,learnerId,true);
        Stored stored=locked(scope,id,request.expectedVersion());
        if(!Set.of("SUBMITTED","APPROVED").contains(stored.status())) throw conflict("LEAVE_TRANSITION_INVALID","This request is already closed.");
        if(stored.endsOn().isBefore(LocalDate.now(clock.withZone(ZoneId.of(stored.timeZone())))))
            throw conflict("LEAVE_ALREADY_ENDED","Contact the school to review leave whose dates have already ended.");
        return transition(user,scope,stored,"WITHDRAWN",text(request.reason(),3,1000));
    }

    private Mutation transition(AuthenticatedUser user, Scope scope, Stored stored, String status, String reason) {
        jdbc.update("""
                UPDATE student_leave_requests SET status=:status,action_by_membership_id=:membershipId,
                  action_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """,params(scope).addValue("id",stored.id()).addValue("status",status).addValue("reason",reason));
        record(user,scope,stored.id(),status.toLowerCase(Locale.ROOT),stored.version()+1);
        return new Mutation(stored.id(),stored.version()+1,status);
    }

    private void record(AuthenticatedUser user, Scope scope, UUID id, String action, long version) {
        // Private leave reasons remain in the scoped request/history, never generic audit metadata or event payloads.
        audit.recordScoped(user,scope.membershipId(),scope.schoolId(),"student-leave."+action,"STUDENT_LEAVE_REQUEST",
                id.toString(),AuditOutcome.SUCCEEDED,"Student leave workflow changed",Map.of("rowVersion",version));
        // Capture the exact trigger-written immutable revision in the same transaction.
        // Consumers must resolve this scoped evidence and current authority; an outbox
        // record alone neither authorizes attendance changes nor proves notification delivery.
        var evidence=jdbc.queryForObject("""
                SELECT id,new_status,occurred_at FROM student_leave_history
                WHERE request_id=:requestId AND request_version=:version
                  AND organisation_id=:organisationId AND school_id=:schoolId
                """,params(scope).addValue("requestId",id).addValue("version",version),
                (row,index)->new EventEvidence(row.getObject("id",UUID.class),row.getString("new_status"),
                        row.getTimestamp("occurred_at").toInstant()));
        if(evidence==null) throw new IllegalStateException("Student leave event requires immutable source evidence");
        String payload=encode(Map.of("organisationId",scope.organisationId(),"schoolId",scope.schoolId(),
                "requestId",id,"historyId",evidence.id(),"requestVersion",version,"status",evidence.status()));
        jdbc.update("""
                INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,headers,
                  occurred_at,available_at,attempt_count,created_at)
                VALUES(:eventId,'STUDENT_LEAVE_REQUEST',:aggregateId,:eventType,CAST(:payload AS jsonb),
                  '{}'::jsonb,:occurredAt,:occurredAt,0,:occurredAt)
                """,new MapSqlParameterSource("eventId",UUID.randomUUID()).addValue("aggregateId",id.toString())
                .addValue("eventType","student_leave."+evidence.status().toLowerCase(Locale.ROOT))
                .addValue("payload",payload).addValue("occurredAt",evidence.occurredAt()));
    }

    static void validateDates(LocalDate from, LocalDate to, LocalDate today, int maxDays, int retrospectiveDays) {
        if(from==null || to==null || from.isAfter(to) || ChronoUnit.DAYS.between(from,to)>=maxDays)
            throw bad("LEAVE_DATE_RANGE_INVALID","Choose a date range within the school's configured limit.");
        if(from.isBefore(today.minusDays(retrospectiveDays)))
            throw bad("LEAVE_RETROSPECTIVE_LIMIT","These dates are outside the school's request window. Contact the school.");
    }

    private Scope scope(AuthenticatedUser user, UUID schoolId, UUID learnerId, boolean family) {
        if(user.activeOrganisationId()==null || user.membershipIds().isEmpty()
                || (family && learnerId==null) || !TenantRequestContext.current()
                .map(t->user.activeOrganisationId().equals(t.organisationId())).orElse(false))
            throw denied();
        var p=new MapSqlParameterSource("userId",user.userId()).addValue("organisationId",user.activeOrganisationId())
                .addValue("schoolId",schoolId).addValue("learnerId",learnerId).addValue("family",family)
                .addValue("membershipIds",user.membershipIds()).addValue("now",Instant.now(clock))
                .addValue("capability",family?"portal.guardian":"student_leave.manage");
        var rows=jdbc.query("""
                SELECT m.id membership_id,s.id school_id,s.display_name school_name,g.id guardian_id
                FROM memberships m JOIN schools s ON s.id=m.school_id AND s.organisation_id=m.organisation_id AND s.status='ACTIVE'
                JOIN organisations o ON o.id=s.organisation_id AND o.status='ACTIVE'
                JOIN user_accounts account ON account.id=m.user_id AND account.status='ACTIVE'
                LEFT JOIN guardians g ON :family AND g.user_account_id=m.user_id
                  AND g.organisation_id=m.organisation_id AND g.school_id=m.school_id AND g.status='ACTIVE'
                LEFT JOIN learner_guardian_relationships relation ON relation.guardian_id=g.id
                  AND relation.organisation_id=g.organisation_id AND relation.school_id=g.school_id
                  AND relation.learner_id=:learnerId AND relation.portal_access_status='ACTIVE'
                LEFT JOIN learners learner ON learner.id=relation.learner_id
                  AND learner.organisation_id=relation.organisation_id AND learner.school_id=relation.school_id AND learner.status='ACTIVE'
                WHERE m.user_id=:userId AND m.organisation_id=:organisationId AND m.id IN(:membershipIds)
                  AND m.status='ACTIVE' AND m.valid_from<=:now AND (m.valid_until IS NULL OR m.valid_until>:now)
                  AND (CAST(:schoolId AS uuid) IS NULL OR m.school_id=:schoolId)
                  AND (NOT :family OR learner.id IS NOT NULL)
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities rc ON rc.role_id=role.id WHERE mr.membership_id=m.id
                    AND (role.organisation_id IS NULL OR role.organisation_id=m.organisation_id)
                    AND rc.capability_code=:capability)
                ORDER BY m.created_at,m.id LIMIT 1
                """,p,(r,n)->new Scope(user.activeOrganisationId(),r.getObject("school_id",UUID.class),
                r.getString("school_name"),r.getObject("membership_id",UUID.class),user.userId(),
                r.getObject("guardian_id",UUID.class),learnerId));
        if(rows.isEmpty()) throw family?missing():denied();
        return rows.getFirst();
    }

    private List<Session> sessions(Scope scope) {
        return jdbc.query("""
                SELECT s.id,s.name,s.starts_on,s.ends_on FROM academic_sessions s
                WHERE s.organisation_id=:organisationId AND s.school_id=:schoolId AND s.status IN ('ACTIVE','PLANNED')
                  AND (CAST(:learnerId AS uuid) IS NULL OR EXISTS(SELECT 1 FROM student_enrolments e
                    WHERE e.academic_session_id=s.id AND e.organisation_id=s.organisation_id AND e.school_id=s.school_id
                    AND e.learner_id=:learnerId AND e.status='ACTIVE')) ORDER BY s.starts_on DESC,s.id
                """,params(scope),(r,n)->new Session(r.getObject("id",UUID.class),r.getString("name"),
                r.getObject("starts_on",LocalDate.class),r.getObject("ends_on",LocalDate.class)));
    }

    private List<Policy> policies(Scope scope) {
        return jdbc.query("""
                SELECT p.*,s.name session_name FROM student_leave_policies p JOIN academic_sessions s
                  ON s.id=p.academic_session_id AND s.organisation_id=p.organisation_id AND s.school_id=p.school_id
                WHERE p.organisation_id=:organisationId AND p.school_id=:schoolId
                  AND (CAST(:learnerId AS uuid) IS NULL OR EXISTS(SELECT 1 FROM student_enrolments e
                    WHERE e.academic_session_id=s.id AND e.organisation_id=s.organisation_id AND e.school_id=s.school_id
                    AND e.learner_id=:learnerId AND e.status='ACTIVE')) ORDER BY s.starts_on DESC,p.id
                """,params(scope),(r,n)->new Policy(r.getObject("id",UUID.class),r.getObject("academic_session_id",UUID.class),
                r.getString("session_name"),r.getInt("max_calendar_days"),r.getInt("retrospective_days"),
                r.getString("attendance_state"),r.getBoolean("enabled"),r.getLong("row_version")));
    }

    private Enrolment enrolment(Scope scope, UUID sessionId, LocalDate from, LocalDate to) {
        var rows=jdbc.query("""
                SELECT e.id,e.campus_id,c.time_zone FROM student_enrolments e
                JOIN academic_sessions s ON s.id=e.academic_session_id AND s.organisation_id=e.organisation_id AND s.school_id=e.school_id
                JOIN campuses c ON c.id=e.campus_id AND c.organisation_id=e.organisation_id AND c.school_id=e.school_id AND c.status='ACTIVE'
                WHERE e.organisation_id=:organisationId AND e.school_id=:schoolId AND e.learner_id=:learnerId
                  AND e.academic_session_id=:sessionId AND e.status='ACTIVE' AND s.status IN ('ACTIVE','PLANNED')
                  AND :fromDate>=s.starts_on AND :toDate<=s.ends_on AND :fromDate>=e.starts_on
                  AND (e.ends_on IS NULL OR :toDate<=e.ends_on) ORDER BY e.starts_on DESC,e.id LIMIT 1
                """,params(scope).addValue("sessionId",sessionId).addValue("fromDate",from).addValue("toDate",to),
                (r,n)->new Enrolment(r.getObject("id",UUID.class),r.getObject("campus_id",UUID.class),r.getString("time_zone")));
        if(rows.isEmpty()) throw conflict("LEAVE_ENROLMENT_UNAVAILABLE","Choose dates within an active enrolment and open academic session.");
        return rows.getFirst();
    }

    private static final String SOURCE_FILTER="""
             r.organisation_id=:organisationId AND r.school_id=:schoolId
             AND (CAST(:guardianId AS uuid) IS NULL OR (r.guardian_id=:guardianId AND r.learner_id=:learnerId))
            """;
    private static final String REQUEST_SELECT="""
            SELECT r.*,learner.legal_name learner_name,c.time_zone FROM student_leave_requests r
            JOIN learners learner ON learner.id=r.learner_id AND learner.organisation_id=r.organisation_id AND learner.school_id=r.school_id
            JOIN campuses c ON c.id=r.campus_id AND c.organisation_id=r.organisation_id AND c.school_id=r.school_id
            WHERE
            """+SOURCE_FILTER;

    private Page page(Scope scope, String status, int page) {
        if(page<0 || page>10000) throw bad("LEAVE_PAGE_INVALID","Choose a valid page.");
        if(status!=null && !STATUSES.contains(status)) throw bad("LEAVE_STATUS_INVALID","Choose a supported leave status.");
        var p=params(scope).addValue("status",status).addValue("offset",page*PAGE_SIZE);
        String filter=" AND (CAST(:status AS varchar) IS NULL OR r.status=:status)";
        long count=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM student_leave_requests r WHERE "+SOURCE_FILTER+filter,p,Long.class));
        var requests=jdbc.query(REQUEST_SELECT+filter+" ORDER BY r.created_at DESC,r.id LIMIT 25 OFFSET :offset",p,(r,n)->request(r,scope));
        return new Page(requests,count,page,PAGE_SIZE);
    }

    private Detail detail(Scope scope, UUID id) {
        var p=params(scope).addValue("id",id);
        var requests=jdbc.query(REQUEST_SELECT+" AND r.id=:id",p,(r,n)->request(r,scope));
        if(requests.isEmpty()) throw missing();
        var history=jdbc.query("""
                SELECT previous_status,new_status,reason,occurred_at,request_version FROM student_leave_history
                WHERE request_id=:id AND organisation_id=:organisationId AND school_id=:schoolId ORDER BY request_version
                """,p,(r,n)->new History(r.getString("previous_status"),r.getString("new_status"),r.getString("reason"),
                r.getTimestamp("occurred_at").toInstant(),r.getLong("request_version")));
        return new Detail(requests.getFirst(),history);
    }

    private Request request(ResultSet r, Scope scope) throws SQLException {
        LocalDate end=r.getObject("ends_on",LocalDate.class);
        boolean canWithdraw=scope.guardianId()!=null && Set.of("SUBMITTED","APPROVED").contains(r.getString("status"))
                && !end.isBefore(LocalDate.now(clock.withZone(ZoneId.of(r.getString("time_zone")))));
        return new Request(r.getObject("id",UUID.class),r.getObject("learner_id",UUID.class),r.getString("learner_name"),
                r.getObject("academic_session_id",UUID.class),r.getObject("starts_on",LocalDate.class),end,
                r.getString("leave_type"),r.getString("request_reason"),r.getString("status"),r.getString("action_reason"),
                r.getTimestamp("created_at").toInstant(),r.getLong("row_version"),canWithdraw);
    }

    private Stored locked(Scope scope, UUID id, long version) {
        if(version<0) throw bad("LEAVE_VERSION_INVALID","Include a valid record version.");
        var rows=jdbc.query(REQUEST_SELECT+" AND r.id=:id FOR UPDATE OF r",params(scope).addValue("id",id),
                (r,n)->new Stored(id,r.getObject("learner_id",UUID.class),r.getObject("academic_session_id",UUID.class),
                r.getObject("requested_by_user_id",UUID.class),r.getObject("guardian_id",UUID.class),
                r.getObject("starts_on",LocalDate.class),r.getObject("ends_on",LocalDate.class),r.getString("time_zone"),
                r.getString("status"),r.getLong("row_version")));
        if(rows.isEmpty()) throw missing();
        if(rows.getFirst().version()!=version) throw conflict("STALE_VERSION","This leave request changed. Refresh first.");
        return rows.getFirst();
    }

    private boolean currentSource(Stored stored, Scope scope) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM guardians g JOIN learner_guardian_relationships relation
                  ON relation.guardian_id=g.id AND relation.organisation_id=g.organisation_id AND relation.school_id=g.school_id
                  AND relation.learner_id=:learnerId AND relation.portal_access_status='ACTIVE'
                JOIN learners learner ON learner.id=relation.learner_id AND learner.organisation_id=relation.organisation_id
                  AND learner.school_id=relation.school_id AND learner.status='ACTIVE'
                JOIN user_accounts a ON a.id=g.user_account_id AND a.status='ACTIVE'
                JOIN memberships m ON m.user_id=a.id AND m.organisation_id=g.organisation_id AND m.school_id=g.school_id
                  AND m.status='ACTIVE' AND m.valid_from<=:now AND (m.valid_until IS NULL OR m.valid_until>:now)
                WHERE g.id=:requestGuardianId AND g.organisation_id=:organisationId AND g.school_id=:schoolId AND g.status='ACTIVE'
                  AND g.user_account_id=:requesterId AND EXISTS(SELECT 1 FROM membership_roles mr
                    JOIN roles role ON role.id=mr.role_id JOIN role_capabilities rc ON rc.role_id=role.id
                    WHERE mr.membership_id=m.id AND (role.organisation_id IS NULL OR role.organisation_id=m.organisation_id)
                      AND rc.capability_code='portal.guardian'))
                """,params(scope).addValue("learnerId",stored.learnerId()).addValue("requestGuardianId",stored.guardianId())
                .addValue("requesterId",stored.requesterUserId()),Boolean.class));
    }

    private MapSqlParameterSource params(Scope scope) {
        return new MapSqlParameterSource("organisationId",scope.organisationId()).addValue("schoolId",scope.schoolId())
                .addValue("membershipId",scope.membershipId()).addValue("userId",scope.userId())
                .addValue("guardianId",scope.guardianId()).addValue("learnerId",scope.learnerId()).addValue("now",Instant.now(clock));
    }
    private void lock(String key) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked",
                new MapSqlParameterSource("key",key),Integer.class);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(Exception ex) { throw new IllegalStateException("Unable to encode student leave evidence"); }
    }
    private String fingerprint(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("Required digest unavailable",ex); }
    }
    private static String text(String value, int min, int max) {
        String clean=value==null?"":value.replaceAll("[\\p{Cntrl}]"," ").strip();
        if(clean.length()<min || clean.length()>max) throw bad("LEAVE_TEXT_INVALID","Enter text within the allowed length.");
        return clean;
    }
    private static ApiException bad(String code,String message) { return new ApiException(HttpStatus.BAD_REQUEST,code,message); }
    private static ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
    private static ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN,"STUDENT_LEAVE_SCOPE_DENIED","Student leave access is unavailable for this school."); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"STUDENT_LEAVE_NOT_FOUND","This student leave record is unavailable to your verified access."); }
    private record Scope(UUID organisationId,UUID schoolId,String schoolName,UUID membershipId,UUID userId,UUID guardianId,UUID learnerId) {
        Scope withLearner(UUID learner) { return new Scope(organisationId,schoolId,schoolName,membershipId,userId,guardianId,learner); }
    }
    private record Enrolment(UUID id,UUID campusId,String timeZone) {}
    private record Retry(Mutation mutation,String fingerprint) {}
    private record EventEvidence(UUID id,String status,Instant occurredAt) {}
    private record Stored(UUID id,UUID learnerId,UUID sessionId,UUID requesterUserId,UUID guardianId,
            LocalDate startsOn,LocalDate endsOn,String timeZone,String status,long version) {}
}

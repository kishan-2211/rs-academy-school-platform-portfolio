package com.educore.api.transport;

import static com.educore.api.transport.TransportNonTravelModels.*;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransportNonTravelService {
    private final TransportService transport;
    private final FamilyTransportService family;
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;
    public TransportNonTravelService(TransportService transport,FamilyTransportService family,
            NamedParameterJdbcTemplate jdbc,SecurityAuditService audit,Clock clock){
        this.transport=transport;this.family=family;this.jdbc=jdbc;this.audit=audit;this.clock=clock;
    }

    @Transactional(readOnly=true)
    public PolicyWorkspace policies(AuthenticatedUser user,UUID schoolId){
        var s=transport.requireScope(user,schoolId,"transport.configure");
        List<Policy> rows=jdbc.query("""
                SELECT campus.id campus_id,campus.display_name,campus.time_zone,policy.*
                FROM campuses campus LEFT JOIN LATERAL (
                  SELECT policy.* FROM transport_non_travel_policy_revisions policy
                  WHERE policy.campus_id=campus.id AND policy.organisation_id=campus.organisation_id
                    AND policy.school_id=campus.school_id ORDER BY policy.revision DESC LIMIT 1
                ) policy ON true
                WHERE campus.organisation_id=:organisationId AND campus.school_id=:schoolId AND campus.status='ACTIVE'
                ORDER BY campus.display_name,campus.id
                """,s.params(),(r,n)->new Policy(r.getObject("id",UUID.class),r.getObject("campus_id",UUID.class),
                        r.getString("display_name"),r.getString("time_zone"),r.getObject("revision")==null?-1:r.getLong("revision"),
                        r.getBoolean("enabled"),r.getObject("pickup_closes_at",LocalTime.class),r.getObject("drop_closes_at",LocalTime.class)));
        return new PolicyWorkspace(s.schoolId(),s.schoolName(),true,rows);
    }

    @Transactional
    public Policy savePolicy(AuthenticatedUser user,PolicyRequest request){
        var s=transport.requireScope(user,request.schoolId(),"transport.configure");
        lock("transport-non-travel-policy:"+request.campusId());
        MapSqlParameterSource p=s.params().addValue("campusId",request.campusId());
        List<String> campuses=jdbc.query("""
                SELECT time_zone FROM campuses WHERE id=:campusId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND status='ACTIVE'
                """,p,(r,n)->r.getString(1));
        if(campuses.isEmpty()) throw unavailable();
        StoredPolicy current=policy(p);
        if(!Objects.equals(current==null?null:current.version(),request.expectedVersion())) throw changed();
        if(request.enabled()&&(request.pickupClosesAt()==null||request.dropClosesAt()==null))
            throw bad("TRANSPORT_NON_TRAVEL_POLICY_INVALID","Choose both school-local change deadlines before enabling parent plans.");
        String reason=request.reason().strip();
        if(reason.length()<3||reason.length()>500)
            throw bad("TRANSPORT_NON_TRAVEL_POLICY_INVALID","Provide a policy-change reason of 3 to 500 characters.");
        UUID id=UUID.randomUUID(); long version=current==null?0:current.version()+1;
        jdbc.update("""
                INSERT INTO transport_non_travel_policy_revisions(id,organisation_id,school_id,campus_id,revision,
                  enabled,pickup_closes_at,drop_closes_at,reason,actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:campusId,:version,:enabled,:pickup,:drop,:reason,:actorMembershipId,:now)
                """,p.addValue("id",id).addValue("version",version).addValue("enabled",request.enabled())
                .addValue("pickup",request.pickupClosesAt()).addValue("drop",request.dropClosesAt())
                .addValue("reason",reason).addValue("now",clock.instant()));
        audit.recordScoped(user,s.actorMembershipId(),s.schoolId(),"transport.non-travel.policy","TRANSPORT_NON_TRAVEL_POLICY",
                id.toString(),AuditOutcome.SUCCEEDED,request.reason(),Map.of("revision",version,"enabled",request.enabled()));
        return policies(user,s.schoolId()).policies().stream().filter(row->row.campusId().equals(request.campusId())).findFirst().orElseThrow();
    }

    @Transactional(readOnly=true)
    public FamilyDay familyDay(AuthenticatedUser user,UUID learnerId){
        var context=family.requireContext(user,learnerId);
        MapSqlParameterSource p=params(user,context);
        Instant now=clock.instant();LocalDate day=now.atZone(ZoneId.of(context.timeZone())).toLocalDate();p.addValue("day",day);
        requireDatedEnrolment(p);
        StoredPolicy policy=policy(p);
        List<Journey> journeys=new ArrayList<>();
        for(Source source:sources(p,day)){
            StoredChange previous=latest(p,source.kind());
            Instant deadline=deadline(policy,source,day,context.timeZone());
            boolean assignmentChanged=previous!=null&&!Objects.equals(previous.assignment(source.kind()),source.assignmentId());
            boolean permitted=policy!=null&&policy.enabled()&&deadline!=null&&now.isBefore(deadline)
                    &&(previous==null||now.isBefore(previous.cutoff(source.kind())))&&tripOpen(p,source.revisionId(),false);
            journeys.add(new Journey(source.kind(),source.assignmentId(),source.label(),source.stopLabel(),source.time(),
                    deadline,permitted,previous!=null&&previous.notTravelling()&&!assignmentChanged,previous==null?null:previous.id(),assignmentChanged));
        }
        List<Change> history=jdbc.query("""
                SELECT id,service_date,journeys,not_travelling,occurred_at FROM transport_non_travel_changes
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND learner_id=:learnerId
                  AND service_date BETWEEN :day-interval '30 days' AND :day
                ORDER BY occurred_at DESC,id DESC LIMIT 30
                """,p,(r,n)->new Change(r.getObject("id",UUID.class),r.getObject("service_date",LocalDate.class),
                        r.getString("journeys"),r.getBoolean("not_travelling"),r.getTimestamp("occurred_at").toInstant()));
        return new FamilyDay(learnerId,day,context.timeZone(),policy!=null&&policy.enabled(),now,journeys,history);
    }

    @Transactional
    public Change change(AuthenticatedUser user,UUID learnerId,ChangeRequest request){
        var context=family.requireContext(user,learnerId);
        MapSqlParameterSource p=params(user,context).addValue("day",request.serviceDate());
        // Consistent policy -> child/day -> sorted trip ordering, shared with database guards.
        lock("transport-non-travel-policy:"+context.campusId());
        lock("transport-non-travel-day:"+context.schoolId()+":"+learnerId+":"+request.serviceDate());
        UUID actor=guardianMembership(user,context.schoolId());
        p.addValue("actor",actor).addValue("userId",user.userId()).addValue("clientId",request.clientRequestId());
        String fingerprint=fingerprint(learnerId,request);
        List<StoredChange> replay=jdbc.query("""
                SELECT * FROM transport_non_travel_changes WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND actor_user_id=:userId AND client_request_id=:clientId
                """,p,(r,n)->stored(r));
        if(!replay.isEmpty()){
            StoredChange old=replay.getFirst();
            if(!fingerprint.equals(old.fingerprint())) throw bad("TRANSPORT_NON_TRAVEL_RETRY_REUSED","This request identifier belongs to a different transport plan.");
            return old.response();
        }
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(!request.serviceDate().equals(now.atZone(ZoneId.of(context.timeZone())).toLocalDate()))
            throw bad("TRANSPORT_NON_TRAVEL_DATE_INVALID","Parent transport plans apply only to today's school-local service date.");
        requireDatedEnrolment(p);
        StoredPolicy policy=policy(p);
        if(policy==null||!policy.enabled()) throw bad("TRANSPORT_NON_TRAVEL_DISABLED","The school has not enabled parent transport plans for this campus.");
        List<String> kinds=kinds(request.journeys());
        Map<String,Source> selected=new LinkedHashMap<>();
        List<Source> available=sources(p,request.serviceDate());
        for(String kind:kinds){
            UUID requestedAssignment="PICKUP".equals(kind)?request.pickupAssignmentId():request.dropAssignmentId();
            Source source=available.stream().filter(row->row.kind().equals(kind)&&row.assignmentId().equals(requestedAssignment)).findFirst().orElseThrow(TransportNonTravelService::unavailable);
            selected.put(kind,source);
        }
        if((!kinds.contains("PICKUP")&&(request.pickupAssignmentId()!=null||request.expectedPickupRevisionId()!=null))
                ||(!kinds.contains("DROP")&&(request.dropAssignmentId()!=null||request.expectedDropRevisionId()!=null)))
            throw bad("TRANSPORT_NON_TRAVEL_SELECTION_INVALID","Only include the journeys selected for this change.");
        selected.values().stream().map(Source::revisionId).distinct().sorted().forEach(id->
                lock("transport-trip:"+context.schoolId()+":"+id+":"+request.serviceDate()));
        Map<String,Instant> deadlines=new HashMap<>();
        for(var entry:selected.entrySet()){
            String kind=entry.getKey();Source source=entry.getValue();StoredChange old=latest(p,kind);
            UUID expected="PICKUP".equals(kind)?request.expectedPickupRevisionId():request.expectedDropRevisionId();
            if(!Objects.equals(expected,old==null?null:old.id())) throw changed();
            if(old!=null&&!now.isAfter(old.occurredAt())) now=old.occurredAt().plusNanos(1000);
            Instant close=deadline(policy,source,request.serviceDate(),context.timeZone());
            if(close==null||!now.isBefore(close)||(old!=null&&!now.isBefore(old.cutoff(kind)))||!tripOpen(p,source.revisionId(),true)) throw closed();
            if(!request.notTravelling()&&(old==null||!old.notTravelling()))
                throw bad("TRANSPORT_NON_TRAVEL_NO_PLAN","There is no current non-travel plan to cancel for this journey.");
            deadlines.put(kind,close);
        }
        // Validate the final monotonic timestamp against every selected deadline before one atomic insert.
        for(String kind:kinds){StoredChange old=latest(p,kind);
            if(!now.isBefore(deadlines.get(kind))||(old!=null&&!now.isBefore(old.cutoff(kind)))) throw closed();}
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transport_non_travel_changes(id,organisation_id,school_id,campus_id,learner_id,service_date,
                  journeys,not_travelling,client_request_id,actor_user_id,actor_membership_id,policy_revision_id,
                  pickup_assignment_id,drop_assignment_id,previous_pickup_revision_id,previous_drop_revision_id,
                  pickup_closes_at,drop_closes_at,request_fingerprint,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:campusId,:learnerId,:day,:journeys,:notTravelling,:clientId,
                  :userId,:actor,:policy,:pickupAssignment,:dropAssignment,:previousPickup,:previousDrop,:pickupClose,:dropClose,:fingerprint,:now)
                """,p.addValue("id",id).addValue("journeys",request.journeys()).addValue("notTravelling",request.notTravelling())
                .addValue("policy",policy.id()).addValue("pickupAssignment",request.pickupAssignmentId()).addValue("dropAssignment",request.dropAssignmentId())
                .addValue("previousPickup",request.expectedPickupRevisionId()).addValue("previousDrop",request.expectedDropRevisionId())
                .addValue("pickupClose",deadlines.get("PICKUP")).addValue("dropClose",deadlines.get("DROP"))
                .addValue("fingerprint",fingerprint).addValue("now",now));
        audit.recordScoped(user,actor,context.schoolId(),"transport.non-travel.change","TRANSPORT_NON_TRAVEL_CHANGE",
                id.toString(),AuditOutcome.SUCCEEDED,"Parent transport plan recorded",Map.of("journeys",request.journeys(),"notTravelling",request.notTravelling()));
        return new Change(id,request.serviceDate(),request.journeys(),request.notTravelling(),now);
    }

    private MapSqlParameterSource params(AuthenticatedUser user,FamilyTransportService.FamilyContext c){
        return new MapSqlParameterSource().addValue("organisationId",user.activeOrganisationId()).addValue("schoolId",c.schoolId())
                .addValue("campusId",c.campusId()).addValue("learnerId",c.learnerId());
    }
    private UUID guardianMembership(AuthenticatedUser user,UUID school){
        List<UUID> rows=jdbc.query("""
                SELECT membership.id FROM memberships membership JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN roles role ON role.id=mr.role_id JOIN role_capabilities capability ON capability.role_id=role.id
                WHERE membership.user_id=:userId AND membership.id IN(:ids) AND membership.organisation_id=:org
                  AND membership.school_id=:school AND membership.status='ACTIVE' AND membership.valid_from<=clock_timestamp()
                  AND (membership.valid_until IS NULL OR membership.valid_until>clock_timestamp())
                  AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                  AND capability.capability_code='portal.guardian' ORDER BY membership.created_at,membership.id LIMIT 1
                """,new MapSqlParameterSource().addValue("userId",user.userId()).addValue("ids",user.membershipIds())
                .addValue("org",user.activeOrganisationId()).addValue("school",school),(r,n)->r.getObject(1,UUID.class));
        if(rows.isEmpty()) throw unavailable();return rows.getFirst();
    }
    private void requireDatedEnrolment(MapSqlParameterSource p){
        Integer count=jdbc.queryForObject("""
                SELECT count(*) FROM student_enrolments WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND campus_id=:campusId AND learner_id=:learnerId AND status='ACTIVE' AND starts_on<=:day
                  AND (ends_on IS NULL OR ends_on>=:day)
                """,p,Integer.class);
        if(count==null||count==0) throw unavailable();
    }
    private StoredPolicy policy(MapSqlParameterSource p){
        var rows=jdbc.query("""
                SELECT id,revision,enabled,pickup_closes_at,drop_closes_at FROM transport_non_travel_policy_revisions
                WHERE campus_id=:campusId AND organisation_id=:organisationId AND school_id=:schoolId ORDER BY revision DESC LIMIT 1
                """,p,(r,n)->new StoredPolicy(r.getObject("id",UUID.class),r.getLong("revision"),r.getBoolean("enabled"),
                        r.getObject("pickup_closes_at",LocalTime.class),r.getObject("drop_closes_at",LocalTime.class)));
        return rows.isEmpty()?null:rows.getFirst();
    }
    private List<Source> sources(MapSqlParameterSource p,LocalDate day){
        return jdbc.query("""
                SELECT assignment.id,assignment.journey_kind,revision.id revision_id,revision.service_label,
                  stop.family_label_snapshot,stop.scheduled_time,
                  (SELECT min(first_stop.scheduled_time) FROM transport_route_revision_stops first_stop
                    WHERE first_stop.transport_route_revision_id=revision.id AND first_stop.organisation_id=revision.organisation_id
                      AND first_stop.school_id=revision.school_id) first_time
                FROM learner_transport_assignments assignment
                JOIN transport_route_revisions revision ON revision.id=assignment.transport_route_revision_id
                  AND revision.organisation_id=assignment.organisation_id AND revision.school_id=assignment.school_id
                JOIN transport_routes route ON route.id=revision.transport_route_id AND route.organisation_id=revision.organisation_id
                  AND route.school_id=revision.school_id AND route.status='ACTIVE' AND route.campus_id=:campusId
                JOIN transport_route_revision_stops stop ON stop.id=assignment.transport_route_stop_id
                  AND stop.transport_route_revision_id=revision.id AND stop.organisation_id=assignment.organisation_id AND stop.school_id=assignment.school_id
                WHERE assignment.organisation_id=:organisationId AND assignment.school_id=:schoolId AND assignment.learner_id=:learnerId
                  AND assignment.status='ACTIVE' AND assignment.starts_on<=:day AND (assignment.ends_on IS NULL OR assignment.ends_on>=:day)
                  AND revision.status IN('PUBLISHED','RETIRED') AND revision.journey_kind=assignment.journey_kind
                  AND revision.effective_from<=:day AND (revision.effective_until IS NULL OR revision.effective_until>=:day)
                  AND :dayCode=ANY(string_to_array(revision.operating_days,','))
                ORDER BY assignment.journey_kind,assignment.id
                """,p.addValue("dayCode",day.getDayOfWeek().name().substring(0,3)),(r,n)->new Source(r.getString("journey_kind"),
                        r.getObject("id",UUID.class),r.getObject("revision_id",UUID.class),r.getString("service_label"),
                        r.getString("family_label_snapshot"),r.getObject("scheduled_time",LocalTime.class),r.getObject("first_time",LocalTime.class)));
    }
    private StoredChange latest(MapSqlParameterSource p,String kind){
        var rows=jdbc.query("""
                SELECT * FROM transport_non_travel_changes WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_id=:learnerId AND service_date=:day
                  AND ((:kind='PICKUP' AND pickup_assignment_id IS NOT NULL) OR (:kind='DROP' AND drop_assignment_id IS NOT NULL))
                ORDER BY occurred_at DESC,id DESC LIMIT 1
                """,p.addValue("kind",kind),(r,n)->stored(r));
        return rows.isEmpty()?null:rows.getFirst();
    }
    private boolean tripOpen(MapSqlParameterSource p,UUID revision,boolean locking){
        var statuses=jdbc.query("""
                SELECT status FROM transport_trips WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND transport_route_revision_id=:revision AND service_date=:day
                """+(locking?" FOR SHARE":""),p.addValue("revision",revision),(r,n)->r.getString(1));
        return statuses.stream().allMatch("PLANNED"::equals);
    }
    static Instant localDeadline(LocalDate day,LocalTime time,String zone){
        var local=day.atTime(time);var rules=ZoneId.of(zone).getRules();var offsets=rules.getValidOffsets(local);
        if(offsets.size()!=1) throw bad("TRANSPORT_NON_TRAVEL_TIME_REVIEW","The school-local deadline is ambiguous or unavailable. Contact the school.");
        return local.toInstant(offsets.getFirst());
    }
    private Instant deadline(StoredPolicy policy,Source source,LocalDate day,String zone){
        if(policy==null||!policy.enabled()) return null;
        Instant configured=localDeadline(day,"PICKUP".equals(source.kind())?policy.pickup():policy.drop(),zone);
        Instant departure=localDeadline(day,source.firstTime(),zone);
        return configured.isBefore(departure)?configured:departure;
    }
    private void lock(String key){jdbc.getJdbcTemplate().queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) locked",Integer.class,key);}
    private static List<String> kinds(String selected){return switch(selected){
        case "PICKUP"->List.of("PICKUP");case "DROP"->List.of("DROP");case "FULL_DAY"->List.of("PICKUP","DROP");
        default->throw bad("TRANSPORT_NON_TRAVEL_SELECTION_INVALID","Choose pickup, drop or full day.");};}
    private static String fingerprint(UUID learner,ChangeRequest r){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("|",learner.toString(),r.serviceDate().toString(),r.journeys(),
                Boolean.toString(r.notTravelling()),Objects.toString(r.pickupAssignmentId(),""),Objects.toString(r.dropAssignmentId(),""),
                Objects.toString(r.expectedPickupRevisionId(),""),Objects.toString(r.expectedDropRevisionId(),"")).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static StoredChange stored(java.sql.ResultSet r) throws java.sql.SQLException {
        return new StoredChange(r.getObject("id",UUID.class),r.getObject("service_date",LocalDate.class),r.getString("journeys"),
                r.getBoolean("not_travelling"),r.getObject("pickup_assignment_id",UUID.class),r.getObject("drop_assignment_id",UUID.class),
                r.getTimestamp("pickup_closes_at")==null?null:r.getTimestamp("pickup_closes_at").toInstant(),
                r.getTimestamp("drop_closes_at")==null?null:r.getTimestamp("drop_closes_at").toInstant(),
                r.getTimestamp("occurred_at").toInstant(),r.getString("request_fingerprint"));
    }
    private record StoredPolicy(UUID id,long version,boolean enabled,LocalTime pickup,LocalTime drop){}
    private record Source(String kind,UUID assignmentId,UUID revisionId,String label,String stopLabel,LocalTime time,LocalTime firstTime){}
    private record StoredChange(UUID id,LocalDate day,String journeys,boolean notTravelling,UUID pickup,UUID drop,Instant pickupClose,Instant dropClose,Instant occurredAt,String fingerprint){
        UUID assignment(String kind){return "PICKUP".equals(kind)?pickup:drop;}
        Instant cutoff(String kind){return "PICKUP".equals(kind)?pickupClose:dropClose;}
        Change response(){return new Change(id,day,journeys,notTravelling,occurredAt);}
    }
    private static ApiException unavailable(){return new ApiException(HttpStatus.NOT_FOUND,"TRANSPORT_NON_TRAVEL_UNAVAILABLE","This journey is unavailable through your verified school access. Refresh or contact the school.");}
    private static ApiException changed(){return new ApiException(HttpStatus.CONFLICT,"TRANSPORT_NON_TRAVEL_CHANGED","The transport plan or policy changed. Refresh before trying again.");}
    private static ApiException closed(){return new ApiException(HttpStatus.CONFLICT,"TRANSPORT_NON_TRAVEL_CLOSED","Changes are closed for a selected journey. Contact the school for urgent arrangements.");}
    private static ApiException bad(String code,String detail){return new ApiException(HttpStatus.BAD_REQUEST,code,detail);}
}

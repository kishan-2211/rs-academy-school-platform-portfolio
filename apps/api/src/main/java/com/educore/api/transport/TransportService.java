package com.educore.api.transport;

import static com.educore.api.transport.TransportController.*;
import static com.educore.api.transport.TransportSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransportService {
    private static final Set<String> CONFIG_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> VEHICLE_STATUSES = Set.of("ACTIVE", "MAINTENANCE", "INACTIVE", "RETIRED");
    private static final Set<String> OWNERSHIP_KINDS = Set.of("SCHOOL", "CONTRACTED");
    private static final Set<String> JOURNEY_KINDS = Set.of("PICKUP", "DROP");
    private static final Set<String> ASSIGNMENT_STATUSES = Set.of("PLANNED", "ACTIVE");
    private static final Set<String> TRIP_EVENTS = Set.of("BOARDED", "ALIGHTED", "ABSENT", "EXCEPTION");
    private static final Set<String> INCIDENT_CATEGORIES = Set.of(
            "DELAY", "VEHICLE", "ROUTE", "BEHAVIOUR", "HANDOVER", "MEDICAL", "ACCIDENT", "OTHER");
    private static final Set<String> INCIDENT_SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Map<DayOfWeek, String> DAY_CODES = Map.of(
            DayOfWeek.MONDAY, "MON", DayOfWeek.TUESDAY, "TUE", DayOfWeek.WEDNESDAY, "WED",
            DayOfWeek.THURSDAY, "THU", DayOfWeek.FRIDAY, "FRI", DayOfWeek.SATURDAY, "SAT",
            DayOfWeek.SUNDAY, "SUN");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public TransportService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TransportSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId, "transport.read");
        MapSqlParameterSource p = scope.params()
                .addValue("broad", scope.broad())
                .addValue("operatorStaffId", scope.operatorStaffId());

        List<CampusItem> campuses = jdbc.query("""
                SELECT id,display_name,status FROM campuses
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,display_name,id
                """, p, (r, n) -> new CampusItem(r.getObject("id", UUID.class),
                r.getString("display_name"), r.getString("status")));

        List<StopItem> stops = jdbc.query("""
                SELECT stop.id,stop.campus_id,stop.code,stop.display_name,stop.family_label,
                  stop.locality,stop.latitude,stop.longitude,stop.accessibility_note,stop.status,stop.row_version
                FROM transport_stops stop
                WHERE stop.organisation_id=:organisationId AND stop.school_id=:schoolId
                  AND (:broad OR EXISTS (
                    SELECT 1 FROM transport_route_revision_stops route_stop
                    JOIN transport_duty_assignments duty
                      ON duty.transport_route_revision_id=route_stop.transport_route_revision_id
                     AND duty.organisation_id=route_stop.organisation_id AND duty.school_id=route_stop.school_id
                    WHERE route_stop.transport_stop_id=stop.id
                      AND duty.status IN ('PLANNED','ACTIVE')
                      AND (:operatorStaffId=duty.driver_staff_profile_id
                        OR :operatorStaffId=duty.attendant_staff_profile_id)))
                ORDER BY CASE stop.status WHEN 'ACTIVE' THEN 0 ELSE 1 END,stop.display_name,stop.id
                """, p, (r, n) -> new StopItem(r.getObject("id", UUID.class),
                r.getObject("campus_id", UUID.class), r.getString("code"), r.getString("display_name"),
                r.getString("family_label"), r.getString("locality"), number(r, "latitude"),
                number(r, "longitude"), r.getString("accessibility_note"), r.getString("status"),
                r.getLong("row_version")));

        List<RouteItem> routes = jdbc.query("""
                SELECT route.id,route.campus_id,route.code,route.display_name,route.description,
                  route.status,route.row_version
                FROM transport_routes route
                WHERE route.organisation_id=:organisationId AND route.school_id=:schoolId
                  AND (:broad OR EXISTS (
                    SELECT 1 FROM transport_route_revisions revision
                    JOIN transport_duty_assignments duty
                      ON duty.transport_route_revision_id=revision.id
                    WHERE revision.transport_route_id=route.id
                      AND duty.status IN ('PLANNED','ACTIVE')
                      AND (:operatorStaffId=duty.driver_staff_profile_id
                        OR :operatorStaffId=duty.attendant_staff_profile_id)))
                ORDER BY CASE route.status WHEN 'ACTIVE' THEN 0 ELSE 1 END,route.display_name,route.id
                """, p, (r, n) -> new RouteItem(r.getObject("id", UUID.class),
                r.getObject("campus_id", UUID.class), r.getString("code"), r.getString("display_name"),
                r.getString("description"), r.getString("status"), r.getLong("row_version")));

        List<RouteRevisionItem> revisions = jdbc.query("""
                SELECT revision.id,revision.transport_route_id,revision.revision_number,
                  revision.supersedes_revision_id,revision.journey_kind,revision.service_label,
                  revision.operating_days,revision.effective_from,revision.effective_until,
                  revision.status,creator.display_name creator_name,decider.display_name decider_name,
                  revision.decision_reason,revision.published_at,revision.row_version
                FROM transport_route_revisions revision
                JOIN memberships creator_membership ON creator_membership.id=revision.created_by_membership_id
                JOIN user_accounts creator ON creator.id=creator_membership.user_id
                LEFT JOIN memberships decider_membership ON decider_membership.id=revision.decided_by_membership_id
                LEFT JOIN user_accounts decider ON decider.id=decider_membership.user_id
                WHERE revision.organisation_id=:organisationId AND revision.school_id=:schoolId
                  AND (:broad OR EXISTS (
                    SELECT 1 FROM transport_duty_assignments duty
                    WHERE duty.transport_route_revision_id=revision.id
                      AND duty.status IN ('PLANNED','ACTIVE')
                      AND (:operatorStaffId=duty.driver_staff_profile_id
                        OR :operatorStaffId=duty.attendant_staff_profile_id)))
                ORDER BY revision.created_at DESC,revision.id DESC
                """, p, (r, n) -> new RouteRevisionItem(r.getObject("id", UUID.class),
                r.getObject("transport_route_id", UUID.class), r.getInt("revision_number"),
                r.getObject("supersedes_revision_id", UUID.class), r.getString("journey_kind"),
                r.getString("service_label"), r.getString("operating_days"),
                r.getObject("effective_from", LocalDate.class), r.getObject("effective_until", LocalDate.class),
                r.getString("status"), r.getString("creator_name"), r.getString("decider_name"),
                r.getString("decision_reason"), instant(r, "published_at"), r.getLong("row_version")));

        List<RouteStopItem> routeStops = jdbc.query("""
                SELECT route_stop.id,route_stop.transport_route_revision_id,route_stop.transport_stop_id,
                  route_stop.sequence_number,route_stop.scheduled_time,route_stop.boarding_window_minutes,
                  route_stop.stop_code_snapshot,route_stop.stop_name_snapshot,
                  route_stop.family_label_snapshot,route_stop.row_version
                FROM transport_route_revision_stops route_stop
                WHERE route_stop.organisation_id=:organisationId AND route_stop.school_id=:schoolId
                  AND (:broad OR EXISTS (
                    SELECT 1 FROM transport_duty_assignments duty
                    WHERE duty.transport_route_revision_id=route_stop.transport_route_revision_id
                      AND duty.status IN ('PLANNED','ACTIVE')
                      AND (:operatorStaffId=duty.driver_staff_profile_id
                        OR :operatorStaffId=duty.attendant_staff_profile_id)))
                ORDER BY route_stop.transport_route_revision_id,route_stop.sequence_number,route_stop.id
                """, p, (r, n) -> new RouteStopItem(r.getObject("id", UUID.class),
                r.getObject("transport_route_revision_id", UUID.class),
                r.getObject("transport_stop_id", UUID.class), r.getInt("sequence_number"),
                r.getObject("scheduled_time", LocalTime.class), r.getInt("boarding_window_minutes"),
                r.getString("stop_code_snapshot"), r.getString("stop_name_snapshot"),
                r.getString("family_label_snapshot"), r.getLong("row_version")));

        List<VehicleItem> vehicles = jdbc.query("""
                SELECT vehicle.id,vehicle.campus_id,vehicle.vehicle_code,vehicle.display_label,
                  vehicle.registration_number,vehicle.ownership_kind,vehicle.vendor_name,
                  vehicle.learner_capacity,vehicle.wheelchair_accessible,
                  vehicle.gps_provider_reference IS NOT NULL gps_configured,
                  vehicle.status,vehicle.row_version
                FROM transport_vehicles vehicle
                WHERE vehicle.organisation_id=:organisationId AND vehicle.school_id=:schoolId
                  AND (:broad OR EXISTS (
                    SELECT 1 FROM transport_duty_assignments duty
                    WHERE duty.transport_vehicle_id=vehicle.id
                      AND duty.status IN ('PLANNED','ACTIVE')
                      AND (:operatorStaffId=duty.driver_staff_profile_id
                        OR :operatorStaffId=duty.attendant_staff_profile_id)))
                ORDER BY CASE vehicle.status WHEN 'ACTIVE' THEN 0 ELSE 1 END,vehicle.display_label,vehicle.id
                """, p, (r, n) -> new VehicleItem(r.getObject("id", UUID.class),
                r.getObject("campus_id", UUID.class), r.getString("vehicle_code"),
                r.getString("display_label"), r.getString("registration_number"),
                r.getString("ownership_kind"), r.getString("vendor_name"), r.getInt("learner_capacity"),
                r.getBoolean("wheelchair_accessible"), r.getBoolean("gps_configured"),
                r.getString("status"), r.getLong("row_version")));

        List<ComplianceTypeItem> complianceTypes = scope.broad() ? jdbc.query("""
                SELECT id,code,display_name,description,validity_months,required_before_service,status,row_version
                FROM transport_vehicle_compliance_types
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,display_name,id
                """, p, (r, n) -> new ComplianceTypeItem(r.getObject("id", UUID.class),
                r.getString("code"), r.getString("display_name"), r.getString("description"),
                integer(r, "validity_months"), r.getBoolean("required_before_service"),
                r.getString("status"), r.getLong("row_version"))) : List.of();

        List<ComplianceCheckItem> checks = scope.broad() ? jdbc.query("""
                SELECT id,transport_vehicle_id,compliance_type_id,status,completed_on,valid_until,
                  safe_reference,restricted_note,decision_reason,row_version
                FROM transport_vehicle_compliance_checks
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY created_at DESC,id DESC
                """, p, (r, n) -> new ComplianceCheckItem(r.getObject("id", UUID.class),
                r.getObject("transport_vehicle_id", UUID.class),
                r.getObject("compliance_type_id", UUID.class), r.getString("status"),
                r.getObject("completed_on", LocalDate.class), r.getObject("valid_until", LocalDate.class),
                r.getString("safe_reference"), scope.canSafety() ? r.getString("restricted_note") : null,
                r.getString("decision_reason"), r.getLong("row_version"))) : List.of();

        List<StaffItem> staff = scope.canConfigure() ? staffOptions(p) : List.of();
        List<LearnerItem> learners = scope.canConfigure() ? learnerOptions(p) : List.of();

        List<DutyItem> duties = jdbc.query("""
                SELECT duty.id,duty.transport_route_revision_id,duty.transport_vehicle_id,
                  duty.driver_staff_profile_id,duty.attendant_staff_profile_id,
                  driver.display_name driver_name,attendant.display_name attendant_name,
                  duty.starts_on,duty.ends_on,duty.status,duty.ending_reason,duty.row_version
                FROM transport_duty_assignments duty
                JOIN staff_profiles driver ON driver.id=duty.driver_staff_profile_id
                JOIN staff_profiles attendant ON attendant.id=duty.attendant_staff_profile_id
                WHERE duty.organisation_id=:organisationId AND duty.school_id=:schoolId
                  AND (:broad OR :operatorStaffId=duty.driver_staff_profile_id
                    OR :operatorStaffId=duty.attendant_staff_profile_id)
                ORDER BY CASE duty.status WHEN 'ACTIVE' THEN 0 WHEN 'PLANNED' THEN 1 ELSE 2 END,
                  duty.starts_on DESC,duty.id DESC
                """, p, (r, n) -> new DutyItem(r.getObject("id", UUID.class),
                r.getObject("transport_route_revision_id", UUID.class),
                r.getObject("transport_vehicle_id", UUID.class),
                r.getObject("driver_staff_profile_id", UUID.class),
                r.getObject("attendant_staff_profile_id", UUID.class), r.getString("driver_name"),
                r.getString("attendant_name"), r.getObject("starts_on", LocalDate.class),
                r.getObject("ends_on", LocalDate.class), r.getString("status"),
                r.getString("ending_reason"), r.getLong("row_version")));

        List<LearnerAssignmentItem> assignments = scope.canConfigure() ? jdbc.query("""
                SELECT id,learner_id,transport_route_revision_id,transport_route_stop_id,
                  journey_kind,starts_on,ends_on,status,ending_reason,row_version
                FROM learner_transport_assignments
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'PLANNED' THEN 1 ELSE 2 END,
                  starts_on DESC,id DESC
                """, p, (r, n) -> new LearnerAssignmentItem(r.getObject("id", UUID.class),
                r.getObject("learner_id", UUID.class), r.getObject("transport_route_revision_id", UUID.class),
                r.getObject("transport_route_stop_id", UUID.class), r.getString("journey_kind"),
                r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class),
                r.getString("status"), r.getString("ending_reason"), r.getLong("row_version"))) : List.of();

        List<TripItem> trips = tripItems(p);
        List<RosterItem> roster = rosterItems(p);
        List<TripEventItem> events = eventItems(p);
        List<IncidentItem> incidents = scope.canSafety() ? incidentItems(p) : List.of();

        return new TransportSnapshot(scope.schoolId(), scope.schoolName(), scope.canConfigure(),
                scope.canOperate(), scope.canSafety(), scope.canPublish(), campuses, stops, routes,
                revisions, routeStops, vehicles, complianceTypes, checks, staff, learners, duties,
                assignments, trips, roster, events, incidents);
    }

    private List<StaffItem> staffOptions(MapSqlParameterSource p) {
        return jdbc.query("""
                SELECT staff.id,staff.employee_code,staff.display_name,
                  string_agg(DISTINCT designation.staff_category,' / ') staff_category,
                  string_agg(DISTINCT designation.display_name,' / ') designation_name,
                  bool_or(designation.eligible_as_transport_driver) driver_eligible,
                  bool_or(designation.eligible_as_transport_attendant) attendant_eligible
                FROM staff_profiles staff
                JOIN staff_assignments assignment ON assignment.staff_profile_id=staff.id
                  AND assignment.organisation_id=staff.organisation_id AND assignment.school_id=staff.school_id
                  AND assignment.status='ACTIVE' AND assignment.starts_on<=CURRENT_DATE
                  AND (assignment.ends_on IS NULL OR assignment.ends_on>=CURRENT_DATE)
                JOIN staff_designations designation ON designation.id=assignment.staff_designation_id
                  AND designation.organisation_id=assignment.organisation_id
                  AND designation.school_id=assignment.school_id AND designation.status='ACTIVE'
                WHERE staff.organisation_id=:organisationId AND staff.school_id=:schoolId
                  AND staff.status='ACTIVE'
                GROUP BY staff.id,staff.employee_code,staff.display_name
                ORDER BY staff.display_name,staff.id
                """, p, (r, n) -> new StaffItem(r.getObject("id", UUID.class),
                r.getString("employee_code"), r.getString("display_name"),
                r.getString("staff_category"), r.getString("designation_name"),
                r.getBoolean("driver_eligible"), r.getBoolean("attendant_eligible")));
    }

    private List<LearnerItem> learnerOptions(MapSqlParameterSource p) {
        return jdbc.query("""
                SELECT learner.id,learner.legal_name,COALESCE(learner.preferred_name,learner.legal_name) display_name,
                  enrolment.grade_code,section.display_name section_name
                FROM learners learner
                JOIN LATERAL (
                  SELECT candidate.* FROM student_enrolments candidate
                  WHERE candidate.learner_id=learner.id
                    AND candidate.organisation_id=learner.organisation_id
                    AND candidate.school_id=learner.school_id AND candidate.status='ACTIVE'
                  ORDER BY candidate.starts_on DESC,candidate.id DESC LIMIT 1
                ) enrolment ON true
                LEFT JOIN student_section_placements placement ON placement.enrolment_id=enrolment.id
                  AND placement.organisation_id=enrolment.organisation_id
                  AND placement.school_id=enrolment.school_id AND placement.status='ACTIVE'
                LEFT JOIN class_sections section ON section.id=placement.class_section_id
                WHERE learner.organisation_id=:organisationId AND learner.school_id=:schoolId
                  AND learner.status='ACTIVE'
                ORDER BY display_name,learner.id
                """, p, (r, n) -> {
                    UUID id = r.getObject("id", UUID.class);
                    return new LearnerItem(id, learnerReference(id), r.getString("display_name"),
                            r.getString("grade_code"), r.getString("section_name"));
                });
    }

    private List<TripItem> tripItems(MapSqlParameterSource p) {
        return jdbc.query("""
                SELECT trip.*,
                  (SELECT count(*) FROM transport_trip_roster_entries roster
                    WHERE roster.transport_trip_id=trip.id) expected_learners,
                  (SELECT count(*) FROM transport_trip_events event
                    WHERE event.transport_trip_id=trip.id) event_count
                FROM transport_trips trip
                JOIN transport_duty_assignments duty ON duty.id=trip.transport_duty_assignment_id
                WHERE trip.organisation_id=:organisationId AND trip.school_id=:schoolId
                  AND trip.service_date BETWEEN CURRENT_DATE-30 AND CURRENT_DATE+14
                  AND (:broad OR :operatorStaffId=duty.driver_staff_profile_id
                    OR :operatorStaffId=duty.attendant_staff_profile_id)
                ORDER BY trip.service_date DESC,trip.created_at DESC,trip.id DESC
                """, p, (r, n) -> new TripItem(r.getObject("id", UUID.class),
                r.getObject("transport_route_revision_id", UUID.class),
                r.getObject("transport_duty_assignment_id", UUID.class),
                r.getObject("service_date", LocalDate.class), r.getString("journey_kind"),
                r.getString("route_code_snapshot"), r.getString("route_name_snapshot"),
                r.getString("service_label_snapshot"), r.getString("vehicle_label_snapshot"),
                r.getString("vehicle_registration_snapshot"), r.getString("driver_name_snapshot"),
                r.getString("attendant_name_snapshot"), r.getString("status"),
                instant(r, "boarding_opened_at"), instant(r, "started_at"), instant(r, "completed_at"),
                r.getString("cancellation_reason"), r.getInt("expected_learners"),
                r.getInt("event_count"), r.getLong("row_version")));
    }

    private List<RosterItem> rosterItems(MapSqlParameterSource p) {
        return jdbc.query("""
                SELECT roster.*,
                  coalesce(parent_plan.not_travelling AND parent_plan.assignment_id=roster.learner_transport_assignment_id,false) parent_not_travelling,
                  parent_plan.occurred_at parent_plan_updated_at,
                  coalesce(parent_plan.assignment_id<>roster.learner_transport_assignment_id,false) parent_plan_assignment_changed
                FROM transport_trip_roster_entries roster
                JOIN transport_trips trip ON trip.id=roster.transport_trip_id
                JOIN transport_duty_assignments duty ON duty.id=trip.transport_duty_assignment_id
                LEFT JOIN LATERAL (
                  SELECT change.not_travelling,change.occurred_at,
                    CASE trip.journey_kind WHEN 'PICKUP' THEN change.pickup_assignment_id ELSE change.drop_assignment_id END assignment_id
                  FROM transport_non_travel_changes change
                  WHERE change.organisation_id=roster.organisation_id AND change.school_id=roster.school_id
                    AND change.learner_id=roster.learner_id AND change.service_date=trip.service_date
                    AND ((trip.journey_kind='PICKUP' AND change.pickup_assignment_id IS NOT NULL)
                      OR (trip.journey_kind='DROP' AND change.drop_assignment_id IS NOT NULL))
                  ORDER BY change.occurred_at DESC,change.id DESC LIMIT 1
                ) parent_plan ON true
                WHERE roster.organisation_id=:organisationId AND roster.school_id=:schoolId
                  AND trip.service_date BETWEEN CURRENT_DATE-7 AND CURRENT_DATE+2
                  AND (:broad OR :operatorStaffId=duty.driver_staff_profile_id
                    OR :operatorStaffId=duty.attendant_staff_profile_id)
                ORDER BY trip.service_date DESC,roster.stop_sequence_snapshot,roster.learner_name_snapshot,roster.id
                """, p, (r, n) -> new RosterItem(r.getObject("id", UUID.class),
                r.getObject("transport_trip_id", UUID.class), r.getObject("learner_id", UUID.class),
                r.getObject("learner_transport_assignment_id", UUID.class),
                r.getObject("transport_route_stop_id", UUID.class), r.getString("learner_name_snapshot"),
                r.getString("learner_reference_snapshot"), r.getInt("stop_sequence_snapshot"),
                r.getString("stop_family_label_snapshot"), r.getObject("scheduled_time_snapshot", LocalTime.class),
                r.getBoolean("parent_not_travelling"),
                r.getTimestamp("parent_plan_updated_at")==null?null:r.getTimestamp("parent_plan_updated_at").toInstant(),
                r.getBoolean("parent_plan_assignment_changed")));
    }

    private List<TripEventItem> eventItems(MapSqlParameterSource p) {
        return jdbc.query("""
                SELECT event.*,account.display_name actor_name
                FROM transport_trip_events event
                JOIN transport_trips trip ON trip.id=event.transport_trip_id
                JOIN transport_duty_assignments duty ON duty.id=trip.transport_duty_assignment_id
                JOIN memberships membership ON membership.id=event.actor_membership_id
                JOIN user_accounts account ON account.id=membership.user_id
                WHERE event.organisation_id=:organisationId AND event.school_id=:schoolId
                  AND trip.service_date BETWEEN CURRENT_DATE-7 AND CURRENT_DATE+2
                  AND (:broad OR :operatorStaffId=duty.driver_staff_profile_id
                    OR :operatorStaffId=duty.attendant_staff_profile_id)
                ORDER BY event.occurred_at DESC,event.id DESC
                """, p, (r, n) -> new TripEventItem(r.getObject("id", UUID.class),
                r.getObject("transport_trip_id", UUID.class), r.getObject("roster_entry_id", UUID.class),
                r.getString("event_type"), r.getTimestamp("occurred_at").toInstant(),
                r.getString("safe_note"), r.getString("actor_name")));
    }

    private List<IncidentItem> incidentItems(MapSqlParameterSource p) {
        return jdbc.query("""
                SELECT incident.*,reporter.display_name reporter_name,resolver.display_name resolver_name
                FROM transport_incidents incident
                JOIN memberships reporter_membership ON reporter_membership.id=incident.reported_by_membership_id
                JOIN user_accounts reporter ON reporter.id=reporter_membership.user_id
                LEFT JOIN memberships resolver_membership ON resolver_membership.id=incident.resolved_by_membership_id
                LEFT JOIN user_accounts resolver ON resolver.id=resolver_membership.user_id
                WHERE incident.organisation_id=:organisationId AND incident.school_id=:schoolId
                ORDER BY CASE incident.status WHEN 'OPEN' THEN 0 ELSE 1 END,
                  incident.reported_at DESC,incident.id DESC LIMIT 200
                """, p, (r, n) -> new IncidentItem(r.getObject("id", UUID.class),
                r.getObject("transport_trip_id", UUID.class), r.getObject("learner_id", UUID.class),
                r.getString("category"), r.getString("severity"), r.getString("summary"),
                r.getString("restricted_details"), r.getString("immediate_action"), r.getString("status"),
                r.getString("reporter_name"), r.getTimestamp("reported_at").toInstant(),
                r.getString("resolver_name"), instant(r, "resolved_at"),
                r.getString("resolution_summary"), r.getLong("row_version")));
    }

    @Transactional
    public TransportMutationResponse createStop(AuthenticatedUser user, StopRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        validateCoordinates(request.latitude(), request.longitude());
        requireCampus(s, request.campusId());
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        MapSqlParameterSource p = stopParams(s, id, request, null, now);
        safe("TRANSPORT_STOP_CONFLICT", () -> jdbc.update("""
                INSERT INTO transport_stops(id,organisation_id,school_id,campus_id,code,display_name,
                  family_label,locality,latitude,longitude,accessibility_note,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:campusId,:code,:name,:familyLabel,:locality,
                  :latitude,:longitude,:accessibility,:status,:actorMembershipId,:now,:now,0)
                """, p));
        history(s, "STOP", id, "CREATED", null, enumValue(request.status(), CONFIG_STATUSES, "stop status"),
                reason(request.reason(), "Transport stop configured"));
        audit(user, s, "transport.stop.create", "TRANSPORT_STOP", id, request.reason());
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse updateStop(AuthenticatedUser user, UUID id, StopRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        validateCoordinates(request.latitude(), request.longitude());
        requireCampus(s, request.campusId());
        StoredConfig current = config(s, "transport_stops", id, true);
        Instant now = Instant.now(clock);
        MapSqlParameterSource p = stopParams(s, id, request, version(request.expectedVersion()), now);
        int changed = safe("TRANSPORT_STOP_CONFLICT", () -> jdbc.update("""
                UPDATE transport_stops SET campus_id=:campusId,code=:code,display_name=:name,
                  family_label=:familyLabel,locality=:locality,latitude=:latitude,longitude=:longitude,
                  accessibility_note=:accessibility,status=:status,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:version
                """, p));
        changed(changed);
        history(s, "STOP", id, "UPDATED", current.status(),
                enumValue(request.status(), CONFIG_STATUSES, "stop status"), reason(request.reason()));
        audit(user, s, "transport.stop.update", "TRANSPORT_STOP", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse createRoute(AuthenticatedUser user, RouteRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        requireCampus(s, request.campusId());
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        MapSqlParameterSource p = routeParams(s, id, request, null, now);
        safe("TRANSPORT_ROUTE_CONFLICT", () -> jdbc.update("""
                INSERT INTO transport_routes(id,organisation_id,school_id,campus_id,code,display_name,
                  description,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:campusId,:code,:name,:description,:status,
                  :actorMembershipId,:now,:now,0)
                """, p));
        history(s, "ROUTE", id, "CREATED", null, enumValue(request.status(), CONFIG_STATUSES, "route status"),
                reason(request.reason(), "Transport route configured"));
        audit(user, s, "transport.route.create", "TRANSPORT_ROUTE", id, request.reason());
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse updateRoute(AuthenticatedUser user, UUID id, RouteRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        requireCampus(s, request.campusId());
        StoredConfig current = config(s, "transport_routes", id, true);
        String status = enumValue(request.status(), CONFIG_STATUSES, "route status");
        if ("INACTIVE".equals(status) && activeRouteWork(s, id) > 0) {
            throw conflict("TRANSPORT_ROUTE_IN_USE", "End active duties and learner assignments before deactivating this route.");
        }
        Instant now = Instant.now(clock);
        int changed = safe("TRANSPORT_ROUTE_CONFLICT", () -> jdbc.update("""
                UPDATE transport_routes SET campus_id=:campusId,code=:code,display_name=:name,
                  description=:description,status=:status,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:version
                """, routeParams(s, id, request, version(request.expectedVersion()), now)));
        changed(changed);
        history(s, "ROUTE", id, "UPDATED", current.status(), status, reason(request.reason()));
        audit(user, s, "transport.route.update", "TRANSPORT_ROUTE", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse createRouteRevision(AuthenticatedUser user, RouteRevisionRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        validateRouteRevision(s, request, null);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("TRANSPORT_ROUTE_REVISION_CONFLICT", () -> jdbc.update("""
                INSERT INTO transport_route_revisions(id,organisation_id,school_id,transport_route_id,
                  revision_number,supersedes_revision_id,journey_kind,service_label,operating_days,
                  effective_from,effective_until,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:routeId,:revision,:supersedesId,:journeyKind,
                  :serviceLabel,:operatingDays,:effectiveFrom,:effectiveUntil,'DRAFT',
                  :actorMembershipId,:now,:now,0)
                """, revisionParams(s, id, request, null, now)));
        history(s, "ROUTE_REVISION", id, "DRAFT_CREATED", null, "DRAFT", reason(request.reason(), "Route draft created"));
        audit(user, s, "transport.route-revision.create", "TRANSPORT_ROUTE_REVISION", id, request.reason());
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse updateRouteRevision(AuthenticatedUser user, UUID id,
            RouteRevisionRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        StoredRevision current = revision(s, id, true);
        if (!"DRAFT".equals(current.status())) throw conflict("TRANSPORT_ROUTE_NOT_EDITABLE",
                "Only a draft route revision can be edited.");
        validateRouteRevision(s, request, id);
        Instant now = Instant.now(clock);
        int changed = safe("TRANSPORT_ROUTE_REVISION_CONFLICT", () -> jdbc.update("""
                UPDATE transport_route_revisions SET transport_route_id=:routeId,revision_number=:revision,
                  supersedes_revision_id=:supersedesId,journey_kind=:journeyKind,service_label=:serviceLabel,
                  operating_days=:operatingDays,effective_from=:effectiveFrom,effective_until=:effectiveUntil,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, revisionParams(s, id, request, version(request.expectedVersion()), now)));
        changed(changed);
        history(s, "ROUTE_REVISION", id, "DRAFT_UPDATED", "DRAFT", "DRAFT", reason(request.reason()));
        audit(user, s, "transport.route-revision.update", "TRANSPORT_ROUTE_REVISION", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse createRouteStop(AuthenticatedUser user, RouteStopRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        RouteStopContext context = validateRouteStop(s, request, null);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        MapSqlParameterSource p = routeStopParams(s, id, request, context, null, now);
        safe("TRANSPORT_ROUTE_STOP_CONFLICT", () -> jdbc.update("""
                INSERT INTO transport_route_revision_stops(id,organisation_id,school_id,
                  transport_route_revision_id,transport_stop_id,sequence_number,scheduled_time,
                  boarding_window_minutes,stop_code_snapshot,stop_name_snapshot,family_label_snapshot,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:revisionId,:stopId,:sequence,:scheduledTime,
                  :window,:stopCode,:stopName,:familyLabel,:actorMembershipId,:now,:now,0)
                """, p));
        history(s, "ROUTE_REVISION", request.routeRevisionId(), "STOP_ADDED", "DRAFT", "DRAFT",
                reason(request.reason(), "Route stop added"));
        audit(user, s, "transport.route-stop.create", "TRANSPORT_ROUTE_STOP", id, request.reason());
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse updateRouteStop(AuthenticatedUser user, UUID id,
            RouteStopRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        StoredConfig current = config(s, "transport_route_revision_stops", id, true);
        RouteStopContext context = validateRouteStop(s, request, id);
        Instant now = Instant.now(clock);
        int changed = safe("TRANSPORT_ROUTE_STOP_CONFLICT", () -> jdbc.update("""
                UPDATE transport_route_revision_stops SET transport_route_revision_id=:revisionId,
                  transport_stop_id=:stopId,sequence_number=:sequence,scheduled_time=:scheduledTime,
                  boarding_window_minutes=:window,stop_code_snapshot=:stopCode,
                  stop_name_snapshot=:stopName,family_label_snapshot=:familyLabel,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:version
                """, routeStopParams(s, id, request, context, version(request.expectedVersion()), now)));
        changed(changed);
        history(s, "ROUTE_REVISION", request.routeRevisionId(), "STOP_UPDATED", "DRAFT", "DRAFT",
                reason(request.reason()));
        audit(user, s, "transport.route-stop.update", "TRANSPORT_ROUTE_STOP", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse removeRouteStop(AuthenticatedUser user, UUID id,
            VersionedReason request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        List<StoredRouteStop> rows = jdbc.query("""
                SELECT id,transport_route_revision_id,row_version
                FROM transport_route_revision_stops
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                FOR UPDATE
                """, s.params().addValue("id", id), (r, n) -> new StoredRouteStop(
                r.getObject("id", UUID.class), r.getObject("transport_route_revision_id", UUID.class),
                r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("TRANSPORT_ROUTE_STOP_NOT_FOUND",
                "The transport route stop was not found.");
        StoredRouteStop current = rows.getFirst();
        StoredRevision routeRevision = revision(s, current.routeRevisionId(), true);
        if (!"DRAFT".equals(routeRevision.status())) throw conflict("TRANSPORT_ROUTE_NOT_EDITABLE",
                "Route stops can be removed only while the route revision is a draft.");
        int changed = jdbc.update("""
                DELETE FROM transport_route_revision_stops
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:version
                """, s.params().addValue("id", id).addValue("version", request.expectedVersion()));
        changed(changed);
        history(s, "ROUTE_REVISION", current.routeRevisionId(), "STOP_REMOVED",
                "DRAFT", "DRAFT", reason(request.reason()));
        audit(user, s, "transport.route-stop.remove", "TRANSPORT_ROUTE_STOP", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse submitRoute(AuthenticatedUser user, UUID id, VersionedReason request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        StoredRevision current = revision(s, id, true);
        if (!"DRAFT".equals(current.status())) throw conflict("TRANSPORT_ROUTE_NOT_DRAFT", "Only a draft route can be submitted.");
        requireCompleteRoute(s, id);
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE transport_route_revisions SET status='PENDING_APPROVAL',
                  submitted_by_membership_id=:actorMembershipId,submitted_at=:now,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, s.params().addValue("id", id).addValue("now", now)
                .addValue("version", request.expectedVersion()));
        changed(changed);
        history(s, "ROUTE_REVISION", id, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", reason(request.reason()));
        audit(user, s, "transport.route-revision.submit", "TRANSPORT_ROUTE_REVISION", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse decideRoute(AuthenticatedUser user, UUID id, DecisionRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.publish");
        StoredRevision current = revision(s, id, true);
        if (!"PENDING_APPROVAL".equals(current.status())) throw conflict("TRANSPORT_ROUTE_NOT_PENDING",
                "Only a submitted route can be decided.");
        if (current.creatorId().equals(s.actorMembershipId())) throw forbidden("TRANSPORT_ROUTE_SELF_APPROVAL",
                "A route plan requires an independent approver.");
        String decision = enumValue(request.decision(), Set.of("PUBLISHED", "REJECTED"), "route decision");
        String why = reason(request.reason());
        Instant now = Instant.now(clock);
        if ("PUBLISHED".equals(decision)) {
            requireCompleteRoute(s, id);
            lockTransactionKey("transport-route-publish:" + s.schoolId() + ":"
                    + current.routeId() + ":" + current.journeyKind());
            List<UUID> retiredIds = jdbc.query("""
                    SELECT id FROM transport_route_revisions
                    WHERE transport_route_id=:routeId AND journey_kind=:journeyKind
                      AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='PUBLISHED' AND id<>:id
                    FOR UPDATE
                    """, s.params().addValue("routeId", current.routeId())
                    .addValue("journeyKind", current.journeyKind()).addValue("id", id),
                    (r, n) -> r.getObject("id", UUID.class));
            UUID publishedId = retiredIds.isEmpty() ? null : retiredIds.getFirst();
            if (!Objects.equals(current.supersedesId(), publishedId))
                throw conflict("TRANSPORT_ROUTE_SUPERSESSION_STALE",
                        "The published journey changed after this draft was created. Create a new revision from the current publication.");
            jdbc.update("""
                    UPDATE transport_route_revisions SET status='RETIRED',
                      retired_by_membership_id=:actorMembershipId,retired_at=:now,
                      retirement_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE transport_route_id=:routeId AND journey_kind=:journeyKind
                      AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='PUBLISHED' AND id<>:id
                    """, s.params().addValue("routeId", current.routeId()).addValue("journeyKind", current.journeyKind())
                    .addValue("id", id).addValue("now", now).addValue("reason", why));
            retiredIds.forEach(retiredId -> history(s, "ROUTE_REVISION", retiredId,
                    "SUPERSEDED", "PUBLISHED", "RETIRED", why));
        }
        int changed = jdbc.update("""
                UPDATE transport_route_revisions SET status=:decision,
                  decided_by_membership_id=:actorMembershipId,decided_at=:now,decision_reason=:reason,
                  published_at=CASE WHEN :decision='PUBLISHED' THEN :now ELSE NULL END,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING_APPROVAL' AND row_version=:version
                """, s.params().addValue("id", id).addValue("decision", decision).addValue("now", now)
                .addValue("reason", why).addValue("version", request.expectedVersion()));
        changed(changed);
        history(s, "ROUTE_REVISION", id, "PUBLISHED".equals(decision) ? "PUBLISHED" : "REJECTED",
                "PENDING_APPROVAL", decision, why);
        audit(user, s, "transport.route-revision." + decision.toLowerCase(Locale.ROOT),
                "TRANSPORT_ROUTE_REVISION", id, why);
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse createVehicle(AuthenticatedUser user, VehicleRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        requireCampus(s, request.campusId());
        validateVehicleRequest(request);
        if (request.clearGpsProviderReference()) throw bad("TRANSPORT_GPS_CLEAR_INVALID",
                "A new vehicle has no existing GPS provider link to remove.");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("TRANSPORT_VEHICLE_CONFLICT", () -> jdbc.update("""
                INSERT INTO transport_vehicles(id,organisation_id,school_id,campus_id,vehicle_code,
                  display_label,registration_number,ownership_kind,vendor_name,learner_capacity,
                  wheelchair_accessible,gps_provider_reference,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:campusId,:code,:label,:registration,
                  :ownership,:vendor,:capacity,:wheelchair,:gpsReference,:status,
                  :actorMembershipId,:now,:now,0)
                """, vehicleParams(s, id, request, null, now)));
        history(s, "VEHICLE", id, "CREATED", null,
                enumValue(request.status(), VEHICLE_STATUSES, "vehicle status"),
                reason(request.reason(), "Transport vehicle configured"));
        audit(user, s, "transport.vehicle.create", "TRANSPORT_VEHICLE", id, request.reason());
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse updateVehicle(AuthenticatedUser user, UUID id, VehicleRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        requireCampus(s, request.campusId());
        validateVehicleRequest(request);
        StoredConfig current = config(s, "transport_vehicles", id, true);
        String next = enumValue(request.status(), VEHICLE_STATUSES, "vehicle status");
        lockTransportCapacity(s);
        int activeRiders = maximumActiveRiders(s, id);
        if (request.learnerCapacity() < activeRiders) throw conflict("TRANSPORT_VEHICLE_CAPACITY_TOO_LOW",
                "This vehicle is assigned to a route with " + activeRiders + " active learners.");
        if (!"ACTIVE".equals(next) && activeVehicleDuties(s, id) > 0) throw conflict("TRANSPORT_VEHICLE_IN_USE",
                "End or replace active duties before making this vehicle unavailable.");
        Instant now = Instant.now(clock);
        int changed = safe("TRANSPORT_VEHICLE_CONFLICT", () -> jdbc.update("""
                UPDATE transport_vehicles SET campus_id=:campusId,vehicle_code=:code,display_label=:label,
                  registration_number=:registration,ownership_kind=:ownership,vendor_name=:vendor,
                  learner_capacity=:capacity,wheelchair_accessible=:wheelchair,
                  gps_provider_reference=CASE WHEN :clearGpsReference THEN NULL
                    WHEN :gpsReference IS NOT NULL THEN :gpsReference ELSE gps_provider_reference END,
                  status=:status,updated_at=:now,
                  row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:version
                """, vehicleParams(s, id, request, version(request.expectedVersion()), now)));
        changed(changed);
        history(s, "VEHICLE", id, "UPDATED", current.status(), next, reason(request.reason()));
        audit(user, s, "transport.vehicle.update", "TRANSPORT_VEHICLE", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse createComplianceType(AuthenticatedUser user,
            ComplianceTypeRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("TRANSPORT_COMPLIANCE_TYPE_CONFLICT", () -> jdbc.update("""
                INSERT INTO transport_vehicle_compliance_types(id,organisation_id,school_id,code,
                  display_name,description,validity_months,required_before_service,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:code,:name,:description,:validityMonths,
                  :required,:status,:actorMembershipId,:now,:now,0)
                """, complianceTypeParams(s, id, request, null, now)));
        history(s, "COMPLIANCE_TYPE", id, "CREATED", null,
                enumValue(request.status(), CONFIG_STATUSES, "compliance status"),
                reason(request.reason(), "Vehicle readiness rule configured"));
        audit(user, s, "transport.compliance-type.create", "TRANSPORT_COMPLIANCE_TYPE", id, request.reason());
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse updateComplianceType(AuthenticatedUser user, UUID id,
            ComplianceTypeRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        StoredConfig current = config(s, "transport_vehicle_compliance_types", id, true);
        Instant now = Instant.now(clock);
        int changed = safe("TRANSPORT_COMPLIANCE_TYPE_CONFLICT", () -> jdbc.update("""
                UPDATE transport_vehicle_compliance_types SET code=:code,display_name=:name,
                  description=:description,validity_months=:validityMonths,
                  required_before_service=:required,status=:status,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:version
                """, complianceTypeParams(s, id, request, version(request.expectedVersion()), now)));
        changed(changed);
        String next = enumValue(request.status(), CONFIG_STATUSES, "compliance status");
        history(s, "COMPLIANCE_TYPE", id, "UPDATED", current.status(), next, reason(request.reason()));
        audit(user, s, "transport.compliance-type.update", "TRANSPORT_COMPLIANCE_TYPE", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse createComplianceCheck(AuthenticatedUser user,
            ComplianceCheckRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.safety");
        requireActiveVehicle(s, request.vehicleId());
        requireActiveComplianceType(s, request.complianceTypeId());
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("TRANSPORT_COMPLIANCE_CHECK_CONFLICT", () -> jdbc.update("""
                INSERT INTO transport_vehicle_compliance_checks(id,organisation_id,school_id,
                  transport_vehicle_id,compliance_type_id,status,safe_reference,restricted_note,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:vehicleId,:typeId,'PENDING',:safeReference,
                  :restrictedNote,:actorMembershipId,:now,:now,0)
                """, s.params().addValue("id", id).addValue("vehicleId", request.vehicleId())
                .addValue("typeId", request.complianceTypeId()).addValue("safeReference", clean(request.safeReference()))
                .addValue("restrictedNote", clean(request.restrictedNote())).addValue("now", now)));
        history(s, "VEHICLE", request.vehicleId(), "READINESS_SUBMITTED", null, "PENDING", reason(request.reason()));
        audit(user, s, "transport.compliance-check.create", "TRANSPORT_COMPLIANCE_CHECK", id, request.reason());
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse decideComplianceCheck(AuthenticatedUser user, UUID id,
            ComplianceDecisionRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.safety");
        StoredCheck current = check(s, id, true);
        if (!"PENDING".equals(current.status())) throw conflict("TRANSPORT_CHECK_ALREADY_DECIDED",
                "This vehicle readiness check was already decided.");
        if (current.creatorId().equals(s.actorMembershipId())) throw forbidden("TRANSPORT_CHECK_SELF_APPROVAL",
                "Vehicle readiness evidence requires an independent reviewer.");
        String decision = enumValue(request.decision(), Set.of("VERIFIED", "REJECTED"),
                "readiness decision");
        LocalDate completedOn = request.completedOn();
        LocalDate validUntil = request.validUntil();
        if ("VERIFIED".equals(decision)) {
            if (completedOn == null) throw bad("TRANSPORT_CHECK_DATE_REQUIRED",
                    "A verified check requires the completion date.");
            Integer validityMonths = validityMonths(s, current.typeId());
            if (validityMonths != null) {
                LocalDate expected = completedOn.plusMonths(validityMonths);
                if (validUntil == null) validUntil = expected;
                if (!expected.equals(validUntil)) throw bad("TRANSPORT_CHECK_VALIDITY_MISMATCH",
                        "The valid-until date must follow the configured validity period.");
            } else if (validUntil != null && validUntil.isBefore(completedOn)) {
                throw bad("TRANSPORT_CHECK_DATES_INVALID", "The valid-until date cannot precede completion.");
            }
        } else if (completedOn != null || validUntil != null) {
            throw bad("TRANSPORT_CHECK_DATES_INVALID", "Rejected checks must not record completion validity dates.");
        }
        String why = reason(request.reason());
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE transport_vehicle_compliance_checks SET status=:decision,completed_on=:completedOn,
                  valid_until=:validUntil,verified_by_membership_id=:actorMembershipId,
                  verified_at=:now,decision_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING' AND row_version=:version
                """, s.params().addValue("id", id).addValue("decision", decision)
                .addValue("completedOn", completedOn).addValue("validUntil", validUntil)
                .addValue("now", now).addValue("reason", why).addValue("version", request.expectedVersion()));
        changed(changed);
        history(s, "VEHICLE", current.vehicleId(), "READINESS_" + decision, "PENDING", decision, why);
        audit(user, s, "transport.compliance-check." + decision.toLowerCase(Locale.ROOT),
                "TRANSPORT_COMPLIANCE_CHECK", id, why);
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse createDuty(AuthenticatedUser user, DutyRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        String status = enumValue(request.status(), ASSIGNMENT_STATUSES, "duty status");
        requirePublishedRoute(s, request.routeRevisionId(), null, request.startsOn());
        requireActiveVehicle(s, request.vehicleId());
        boolean allowPlannedStaff = "PLANNED".equals(status);
        requireEligibleStaff(s, request.driverStaffId(), true, request.startsOn(), allowPlannedStaff);
        requireEligibleStaff(s, request.attendantStaffId(), false, request.startsOn(), allowPlannedStaff);
        if (request.driverStaffId().equals(request.attendantStaffId())) throw bad("TRANSPORT_DUTY_PEOPLE_DUPLICATE",
                "Choose different people for driver and attendant duties.");
        if ("ACTIVE".equals(status)) {
            if (request.startsOn().isAfter(localDateForRevision(s, request.routeRevisionId())))
                throw bad("TRANSPORT_DUTY_START_INVALID",
                        "A future transport duty must remain planned until its local service date.");
            lockTransportCapacity(s);
            requireVehicleReady(s, request.vehicleId(), request.startsOn());
            requireVehicleCapacityForRoute(s, request.routeRevisionId(), request.vehicleId());
            lockDutyResources(s, request.vehicleId(), request.driverStaffId(), request.attendantStaffId());
            requireDutyConflictFree(s, request.routeRevisionId(), request.vehicleId(),
                    request.driverStaffId(), request.attendantStaffId(), request.startsOn());
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("TRANSPORT_DUTY_CONFLICT", () -> jdbc.update("""
                INSERT INTO transport_duty_assignments(id,organisation_id,school_id,
                  transport_route_revision_id,transport_vehicle_id,driver_staff_profile_id,
                  attendant_staff_profile_id,starts_on,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:routeRevisionId,:vehicleId,:driverId,
                  :attendantId,:startsOn,:status,:actorMembershipId,:now,:now,0)
                """, s.params().addValue("id", id).addValue("routeRevisionId", request.routeRevisionId())
                .addValue("vehicleId", request.vehicleId()).addValue("driverId", request.driverStaffId())
                .addValue("attendantId", request.attendantStaffId()).addValue("startsOn", request.startsOn())
                .addValue("status", status).addValue("now", now)));
        history(s, "DUTY", id, "CREATED", null, status, reason(request.reason()));
        audit(user, s, "transport.duty.create", "TRANSPORT_DUTY", id, request.reason());
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse activateDuty(AuthenticatedUser user, UUID id, VersionedReason request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        StoredDuty current = duty(s, id, true);
        if (!"PLANNED".equals(current.status())) throw conflict("TRANSPORT_DUTY_NOT_PLANNED",
                "Only a planned transport duty can be activated.");
        if (current.startsOn().isAfter(localDateForRevision(s, current.routeRevisionId())))
            throw bad("TRANSPORT_DUTY_START_INVALID",
                    "Activate a planned transport duty on or after its local start date.");
        requirePublishedRoute(s, current.routeRevisionId(), null, current.startsOn());
        requireActiveVehicle(s, current.vehicleId());
        requireEligibleStaff(s, current.driverId(), true, current.startsOn(), false);
        requireEligibleStaff(s, current.attendantId(), false, current.startsOn(), false);
        lockTransportCapacity(s);
        requireVehicleReady(s, current.vehicleId(), current.startsOn());
        requireVehicleCapacityForRoute(s, current.routeRevisionId(), current.vehicleId());
        lockDutyResources(s, current.vehicleId(), current.driverId(), current.attendantId());
        requireDutyConflictFree(s, current.routeRevisionId(), current.vehicleId(),
                current.driverId(), current.attendantId(), current.startsOn());
        Instant now = Instant.now(clock);
        int changed = safe("TRANSPORT_DUTY_CONFLICT", () -> jdbc.update("""
                UPDATE transport_duty_assignments SET status='ACTIVE',updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PLANNED' AND row_version=:version
                """, s.params().addValue("id", id).addValue("now", now)
                .addValue("version", request.expectedVersion())));
        changed(changed);
        history(s, "DUTY", id, "ACTIVATED", "PLANNED", "ACTIVE", reason(request.reason()));
        audit(user, s, "transport.duty.activate", "TRANSPORT_DUTY", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse endDuty(AuthenticatedUser user, UUID id, EndRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        StoredDuty current = duty(s, id, true);
        if (!Set.of("PLANNED", "ACTIVE").contains(current.status())) throw conflict("TRANSPORT_DUTY_ALREADY_ENDED",
                "This transport duty is already closed.");
        if (request.endDate().isBefore(current.startsOn())) throw bad("TRANSPORT_DUTY_END_INVALID",
                "The duty end date cannot precede its start date.");
        if ("ACTIVE".equals(current.status())
                && request.endDate().isAfter(localDateForRevision(s, current.routeRevisionId())))
            throw bad("TRANSPORT_DUTY_FUTURE_END_UNSUPPORTED",
                    "An active duty can be ended today or backdated after reconciliation; future scheduling needs a separate replacement plan.");
        if (openTripsForDuty(s, id, request.endDate()) > 0) throw conflict("TRANSPORT_DUTY_HAS_OPEN_TRIPS",
                "Complete or cancel open trips before ending this duty.");
        String next = "PLANNED".equals(current.status()) ? "CANCELLED" : "ENDED";
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE transport_duty_assignments SET status=:next,ends_on=:endDate,
                  ending_reason=:reason,ended_by_membership_id=:actorMembershipId,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status=:previous AND row_version=:version
                """, s.params().addValue("id", id).addValue("next", next).addValue("endDate", request.endDate())
                .addValue("reason", reason(request.reason())).addValue("now", now)
                .addValue("previous", current.status()).addValue("version", request.expectedVersion()));
        changed(changed);
        history(s, "DUTY", id, next, current.status(), next, reason(request.reason()));
        audit(user, s, "transport.duty.end", "TRANSPORT_DUTY", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse createLearnerAssignment(AuthenticatedUser user,
            LearnerAssignmentRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        String status = enumValue(request.status(), ASSIGNMENT_STATUSES, "learner transport status");
        String journey = enumValue(request.journeyKind(), JOURNEY_KINDS, "journey kind");
        requireActiveLearner(s, request.learnerId());
        requirePublishedRoute(s, request.routeRevisionId(), journey, request.startsOn());
        requireStopOnRoute(s, request.routeRevisionId(), request.routeStopId());
        if ("ACTIVE".equals(status)) {
            if (request.startsOn().isAfter(localDateForRevision(s, request.routeRevisionId())))
                throw bad("LEARNER_TRANSPORT_START_INVALID",
                        "A future learner journey must remain planned until its local start date.");
            lockTransportCapacity(s);
            requireRouteCapacity(s, request.routeRevisionId(), request.startsOn(), 1);
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("LEARNER_TRANSPORT_ASSIGNMENT_CONFLICT", () -> jdbc.update("""
                INSERT INTO learner_transport_assignments(id,organisation_id,school_id,learner_id,
                  transport_route_revision_id,transport_route_stop_id,journey_kind,starts_on,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:learnerId,:routeRevisionId,:routeStopId,
                  :journeyKind,:startsOn,:status,:actorMembershipId,:now,:now,0)
                """, s.params().addValue("id", id).addValue("learnerId", request.learnerId())
                .addValue("routeRevisionId", request.routeRevisionId()).addValue("routeStopId", request.routeStopId())
                .addValue("journeyKind", journey).addValue("startsOn", request.startsOn())
                .addValue("status", status).addValue("now", now)));
        history(s, "LEARNER_ASSIGNMENT", id, "CREATED", null, status, reason(request.reason()));
        audit(user, s, "transport.learner-assignment.create", "LEARNER_TRANSPORT_ASSIGNMENT", id, request.reason());
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse activateLearnerAssignment(AuthenticatedUser user, UUID id,
            VersionedReason request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        StoredAssignment current = learnerAssignment(s, id, true);
        if (!"PLANNED".equals(current.status())) throw conflict("LEARNER_TRANSPORT_NOT_PLANNED",
                "Only a planned learner transport assignment can be activated.");
        if (current.startsOn().isAfter(localDateForRevision(s, current.routeRevisionId())))
            throw bad("LEARNER_TRANSPORT_START_INVALID",
                    "Activate a planned learner journey on or after its local start date.");
        requireActiveLearner(s, current.learnerId());
        requirePublishedRoute(s, current.routeRevisionId(), null, current.startsOn());
        lockTransportCapacity(s);
        requireRouteCapacity(s, current.routeRevisionId(), current.startsOn(), 1);
        Instant now = Instant.now(clock);
        int changed = safe("LEARNER_TRANSPORT_ASSIGNMENT_CONFLICT", () -> jdbc.update("""
                UPDATE learner_transport_assignments SET status='ACTIVE',updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PLANNED' AND row_version=:version
                """, s.params().addValue("id", id).addValue("now", now)
                .addValue("version", request.expectedVersion())));
        changed(changed);
        history(s, "LEARNER_ASSIGNMENT", id, "ACTIVATED", "PLANNED", "ACTIVE", reason(request.reason()));
        audit(user, s, "transport.learner-assignment.activate", "LEARNER_TRANSPORT_ASSIGNMENT", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse endLearnerAssignment(AuthenticatedUser user, UUID id,
            EndRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.configure");
        StoredAssignment current = learnerAssignment(s, id, true);
        if (!Set.of("PLANNED", "ACTIVE").contains(current.status())) throw conflict("LEARNER_TRANSPORT_ALREADY_ENDED",
                "This learner transport assignment is already closed.");
        if (request.endDate().isBefore(current.startsOn())) throw bad("LEARNER_TRANSPORT_END_INVALID",
                "The assignment end date cannot precede its start date.");
        if ("ACTIVE".equals(current.status())
                && request.endDate().isAfter(localDateForRevision(s, current.routeRevisionId())))
            throw bad("LEARNER_TRANSPORT_FUTURE_END_UNSUPPORTED",
                    "An active learner journey can be ended today or backdated after reconciliation; future scheduling needs a replacement plan.");
        String next = "PLANNED".equals(current.status()) ? "CANCELLED" : "ENDED";
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE learner_transport_assignments SET status=:next,ends_on=:endDate,
                  ending_reason=:reason,ended_by_membership_id=:actorMembershipId,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status=:previous AND row_version=:version
                """, s.params().addValue("id", id).addValue("next", next).addValue("endDate", request.endDate())
                .addValue("reason", reason(request.reason())).addValue("now", now)
                .addValue("previous", current.status()).addValue("version", request.expectedVersion()));
        changed(changed);
        history(s, "LEARNER_ASSIGNMENT", id, next, current.status(), next, reason(request.reason()));
        audit(user, s, "transport.learner-assignment.end", "LEARNER_TRANSPORT_ASSIGNMENT", id, request.reason());
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse createTrip(AuthenticatedUser user, TripRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.operate");
        RouteForTrip route = routeForTrip(s, request.routeRevisionId(), request.serviceDate());
        LocalDate localToday = LocalDate.now(clock.withZone(ZoneId.of(route.timeZone())));
        if (!request.serviceDate().equals(localToday)) throw bad("TRANSPORT_TRIP_SERVICE_DATE_INVALID",
                "Create a transport trip only on its local school service day.");
        StoredDuty duty = duty(s, request.dutyId(), true);
        if (!duty.routeRevisionId().equals(request.routeRevisionId()) || !"ACTIVE".equals(duty.status())
                || request.serviceDate().isBefore(duty.startsOn())
                || (duty.endsOn() != null && request.serviceDate().isAfter(duty.endsOn()))) {
            throw bad("TRANSPORT_TRIP_DUTY_INVALID", "Choose the active duty assigned to this route and service date.");
        }
        requireOperationalAccess(s, duty);
        requireEligibleStaff(s, duty.driverId(), true, request.serviceDate(), false);
        requireEligibleStaff(s, duty.attendantId(), false, request.serviceDate(), false);
        requireVehicleReady(s, duty.vehicleId(), request.serviceDate());
        lockTransactionKey("transport-trip:" + s.schoolId() + ":"
                + request.routeRevisionId() + ":" + request.serviceDate());

        List<StoredTrip> existing = jdbc.query("""
                SELECT id,transport_duty_assignment_id,transport_route_revision_id,service_date,status,row_version
                FROM transport_trips
                WHERE transport_route_revision_id=:routeRevisionId AND service_date=:serviceDate
                  AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
                """, s.params().addValue("routeRevisionId", request.routeRevisionId())
                .addValue("serviceDate", request.serviceDate()), (r, n) -> new StoredTrip(
                r.getObject("id", UUID.class), r.getObject("transport_duty_assignment_id", UUID.class),
                r.getObject("transport_route_revision_id", UUID.class),
                r.getObject("service_date", LocalDate.class), r.getString("status"), r.getLong("row_version")));
        if (!existing.isEmpty()) {
            StoredTrip prior = existing.getFirst();
            if (prior.dutyId().equals(request.dutyId()))
                return new TransportMutationResponse(prior.id(), prior.version());
            throw conflict("TRANSPORT_TRIP_ALREADY_EXISTS",
                    "A trip already exists for this route and service date with a different duty.");
        }

        VehicleDutySnapshot snapshot = dutySnapshot(s, duty.id());
        List<RosterSeed> roster = rosterSeed(s, request.routeRevisionId(), request.serviceDate());
        if (roster.isEmpty()) throw conflict("TRANSPORT_TRIP_ROSTER_EMPTY",
                "Assign at least one active learner before creating this trip.");
        if (roster.size() > snapshot.capacity()) throw conflict("TRANSPORT_TRIP_OVER_CAPACITY",
                "The frozen roster exceeds the assigned vehicle capacity.");

        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("TRANSPORT_TRIP_CONFLICT", () -> jdbc.update("""
                INSERT INTO transport_trips(id,organisation_id,school_id,transport_route_revision_id,
                  transport_duty_assignment_id,service_date,journey_kind,route_code_snapshot,
                  route_name_snapshot,service_label_snapshot,vehicle_label_snapshot,
                  vehicle_registration_snapshot,driver_name_snapshot,attendant_name_snapshot,status,
                  created_by_membership_id,updated_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:routeRevisionId,:dutyId,:serviceDate,:journeyKind,
                  :routeCode,:routeName,:serviceLabel,:vehicleLabel,:registration,:driverName,
                  :attendantName,'PLANNED',:actorMembershipId,:actorMembershipId,:now,:now,0)
                """, s.params().addValue("id", id).addValue("routeRevisionId", request.routeRevisionId())
                .addValue("dutyId", request.dutyId()).addValue("serviceDate", request.serviceDate())
                .addValue("journeyKind", route.journeyKind()).addValue("routeCode", route.routeCode())
                .addValue("routeName", route.routeName()).addValue("serviceLabel", route.serviceLabel())
                .addValue("vehicleLabel", snapshot.vehicleLabel()).addValue("registration", snapshot.registration())
                .addValue("driverName", snapshot.driverName()).addValue("attendantName", snapshot.attendantName())
                .addValue("now", now)));
        for (RosterSeed seed : roster) {
            jdbc.update("""
                    INSERT INTO transport_trip_roster_entries(id,organisation_id,school_id,transport_trip_id,
                      learner_id,learner_transport_assignment_id,transport_route_stop_id,
                      learner_name_snapshot,learner_reference_snapshot,stop_sequence_snapshot,
                      stop_family_label_snapshot,scheduled_time_snapshot,created_at)
                    VALUES(:id,:organisationId,:schoolId,:tripId,:learnerId,:assignmentId,:routeStopId,
                      :learnerName,:learnerReference,:sequence,:familyLabel,:scheduledTime,:now)
                    """, s.params().addValue("id", UUID.randomUUID()).addValue("tripId", id)
                    .addValue("learnerId", seed.learnerId()).addValue("assignmentId", seed.assignmentId())
                    .addValue("routeStopId", seed.routeStopId()).addValue("learnerName", seed.learnerName())
                    .addValue("learnerReference", learnerReference(seed.learnerId())).addValue("sequence", seed.sequence())
                    .addValue("familyLabel", seed.familyLabel()).addValue("scheduledTime", seed.scheduledTime())
                    .addValue("now", now));
        }
        history(s, "TRIP", id, "CREATED", null, "PLANNED", reason(request.reason()));
        audit(user, s, "transport.trip.create", "TRANSPORT_TRIP", id, request.reason());
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse changeTripState(AuthenticatedUser user, UUID id, TripStateRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.operate");
        StoredTrip current = trip(s, id, true);
        StoredDuty duty = duty(s, current.dutyId(), false);
        requireOperationalAccess(s, duty);
        String action = required(request.action()).toUpperCase(Locale.ROOT).replace(' ', '_');
        String next = switch (action) {
            case "OPEN_BOARDING" -> "BOARDING";
            case "START" -> "IN_PROGRESS";
            case "COMPLETE" -> "COMPLETED";
            case "CANCEL" -> "CANCELLED";
            default -> throw bad("TRANSPORT_TRIP_ACTION_INVALID", "Choose open boarding, start, complete, or cancel.");
        };
        if (!allowedTripTransition(current.status(), next)) throw conflict("TRANSPORT_TRIP_TRANSITION_INVALID",
                "The requested trip action is not valid from " + current.status().toLowerCase(Locale.ROOT) + ".");
        if ("IN_PROGRESS".equals(next)) requireVehicleReady(s, duty.vehicleId(), current.serviceDate());
        if ("COMPLETED".equals(next) && incompleteRosterCount(s, id) > 0) throw conflict("TRANSPORT_TRIP_ROSTER_INCOMPLETE",
                "Every learner must be marked alighted or absent before completing the trip.");
        if ("CANCELLED".equals(next) && unreconciledBoardedCount(s, id) > 0)
            throw conflict("TRANSPORT_TRIP_BOARDED_LEARNERS_UNRECONCILED",
                    "Reconcile every boarded learner as safely alighted before cancelling the trip.");
        String why = reason(request.reason());
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE transport_trips SET status=:next,
                  boarding_opened_at=CASE WHEN :next='BOARDING' THEN :now ELSE boarding_opened_at END,
                  started_at=CASE WHEN :next='IN_PROGRESS' THEN :now ELSE started_at END,
                  completed_at=CASE WHEN :next IN ('COMPLETED','CANCELLED') THEN :now ELSE completed_at END,
                  cancellation_reason=CASE WHEN :next='CANCELLED' THEN :reason ELSE NULL END,
                  updated_by_membership_id=:actorMembershipId,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status=:previous AND row_version=:version
                """, s.params().addValue("id", id).addValue("next", next).addValue("now", now)
                .addValue("reason", why).addValue("previous", current.status())
                .addValue("version", request.expectedVersion()));
        changed(changed);
        history(s, "TRIP", id, action, current.status(), next, why);
        audit(user, s, "transport.trip." + action.toLowerCase(Locale.ROOT), "TRANSPORT_TRIP", id, why);
        return new TransportMutationResponse(id, current.version() + 1);
    }

    @Transactional
    public TransportMutationResponse recordTripEvent(AuthenticatedUser user, TripEventRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.operate");
        String type = enumValue(request.eventType(), TRIP_EVENTS, "trip event");
        if ("EXCEPTION".equals(type) && clean(request.safeNote()) == null)
            throw bad("TRANSPORT_EVENT_NOTE_REQUIRED", "An operational exception requires a safe note.");
        StoredTrip trip = trip(s, request.tripId(), true);
        StoredDuty duty = duty(s, trip.dutyId(), false);
        requireOperationalAccess(s, duty);
        if (!Set.of("BOARDING", "IN_PROGRESS").contains(trip.status())) throw conflict("TRANSPORT_TRIP_NOT_RECORDING",
                "Roster events are accepted only while boarding or in progress.");
        requireRosterEntry(s, request.tripId(), request.rosterEntryId());

        List<StoredEvent> duplicate = jdbc.query("""
                SELECT id,transport_trip_id,roster_entry_id,event_type FROM transport_trip_events
                WHERE id=:eventId AND organisation_id=:organisationId AND school_id=:schoolId
                """, s.params().addValue("eventId", request.eventId()), (r, n) -> new StoredEvent(
                r.getObject("id", UUID.class), r.getObject("transport_trip_id", UUID.class),
                r.getObject("roster_entry_id", UUID.class), r.getString("event_type")));
        if (!duplicate.isEmpty()) {
            StoredEvent event = duplicate.getFirst();
            if (event.tripId().equals(request.tripId()) && event.rosterId().equals(request.rosterEntryId())
                    && event.type().equals(type)) return new TransportMutationResponse(event.id(), 0);
            throw conflict("TRANSPORT_EVENT_ID_REUSED", "This event identifier is already attached to different evidence.");
        }
        validateEventProgression(s, request.rosterEntryId(), type, trip.status());
        Instant occurredAt = request.occurredAt() == null ? Instant.now(clock) : request.occurredAt();
        Instant now = Instant.now(clock);
        if (occurredAt.isAfter(now.plusSeconds(300)) || occurredAt.isBefore(now.minusSeconds(86400)))
            throw bad("TRANSPORT_EVENT_TIME_INVALID", "The event time must be within the operational recording window.");
        safe("TRANSPORT_EVENT_CONFLICT", () -> jdbc.update("""
                INSERT INTO transport_trip_events(id,organisation_id,school_id,transport_trip_id,
                  roster_entry_id,event_type,occurred_at,safe_note,actor_membership_id,created_at)
                VALUES(:eventId,:organisationId,:schoolId,:tripId,:rosterId,:eventType,:occurredAt,
                  :safeNote,:actorMembershipId,:now)
                """, s.params().addValue("eventId", request.eventId()).addValue("tripId", request.tripId())
                .addValue("rosterId", request.rosterEntryId()).addValue("eventType", type)
                .addValue("occurredAt", occurredAt).addValue("safeNote", clean(request.safeNote()))
                .addValue("now", now)));
        audit(user, s, "transport.trip-event." + type.toLowerCase(Locale.ROOT),
                "TRANSPORT_TRIP_EVENT", request.eventId(), request.safeNote());
        return new TransportMutationResponse(request.eventId(), 0);
    }

    @Transactional
    public TransportMutationResponse createIncident(AuthenticatedUser user, IncidentRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.safety");
        String category = enumValue(request.category(), INCIDENT_CATEGORIES, "incident category");
        String severity = enumValue(request.severity(), INCIDENT_SEVERITIES, "incident severity");
        if (request.tripId() != null) trip(s, request.tripId(), false);
        if (request.learnerId() != null) requireLearner(s, request.learnerId());
        if (request.tripId() != null && request.learnerId() != null) {
            int count = jdbc.queryForObject("""
                    SELECT count(*) FROM transport_trip_roster_entries
                    WHERE transport_trip_id=:tripId AND learner_id=:learnerId
                      AND organisation_id=:organisationId AND school_id=:schoolId
                    """, s.params().addValue("tripId", request.tripId())
                    .addValue("learnerId", request.learnerId()), Integer.class);
            if (count != 1) throw bad("TRANSPORT_INCIDENT_LEARNER_INVALID",
                    "The selected learner was not on this trip's frozen roster.");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO transport_incidents(id,organisation_id,school_id,transport_trip_id,learner_id,
                  category,severity,summary,restricted_details,immediate_action,status,
                  reported_by_membership_id,reported_at,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:tripId,:learnerId,:category,:severity,:summary,
                  :details,:action,'OPEN',:actorMembershipId,:now,:now,:now,0)
                """, s.params().addValue("id", id).addValue("tripId", request.tripId())
                .addValue("learnerId", request.learnerId()).addValue("category", category)
                .addValue("severity", severity).addValue("summary", required(request.summary()))
                .addValue("details", required(request.restrictedDetails()))
                .addValue("action", clean(request.immediateAction())).addValue("now", now));
        history(s, "INCIDENT", id, "REPORTED", null, "OPEN", "Restricted incident recorded");
        audit(user, s, "transport.incident.create", "TRANSPORT_INCIDENT", id,
                "Transport incident recorded with severity " + severity);
        return new TransportMutationResponse(id, 0);
    }

    @Transactional
    public TransportMutationResponse resolveIncident(AuthenticatedUser user, UUID id,
            ResolveIncidentRequest request) {
        Scope s = requireScope(user, request.schoolId(), "transport.safety");
        StoredIncident current = incident(s, id, true);
        if (!"OPEN".equals(current.status())) throw conflict("TRANSPORT_INCIDENT_ALREADY_RESOLVED",
                "This incident is already resolved.");
        if (current.reporterId().equals(s.actorMembershipId()))
            throw forbidden("TRANSPORT_INCIDENT_SELF_RESOLUTION",
                    "A restricted transport incident requires an independent resolver.");
        String resolution = required(request.resolutionSummary());
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE transport_incidents SET status='RESOLVED',resolved_by_membership_id=:actorMembershipId,
                  resolved_at=:now,resolution_summary=:resolution,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='OPEN' AND row_version=:version
                """, s.params().addValue("id", id).addValue("now", now).addValue("resolution", resolution)
                .addValue("version", request.expectedVersion()));
        changed(changed);
        history(s, "INCIDENT", id, "RESOLVED", "OPEN", "RESOLVED", "Restricted incident resolved");
        audit(user, s, "transport.incident.resolve", "TRANSPORT_INCIDENT", id, "Transport incident resolved");
        return new TransportMutationResponse(id, current.version() + 1);
    }

    Scope requireScope(AuthenticatedUser user, UUID schoolId, String capability) {
        if (user.activeOrganisationId() == null) throw conflict("ORGANISATION_SELECTION_REQUIRED",
                "Select a school organisation first.");
        if (user.membershipIds().isEmpty()) throw forbidden("TRANSPORT_SCOPE_DENIED",
                "Transport access is unavailable for this school.");
        List<Scope> rows = jdbc.query("""
                SELECT school.id,school.display_name,membership.id membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    JOIN roles role ON role.id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='transport.configure'
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)) can_configure,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    JOIN roles role ON role.id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='transport.operate'
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)) can_operate,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    JOIN roles role ON role.id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='transport.safety'
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)) can_safety,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    JOIN roles role ON role.id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='transport.publish'
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)) can_publish,
                  (SELECT staff.id FROM staff_profiles staff
                    WHERE staff.user_account_id=membership.user_id
                      AND staff.organisation_id=membership.organisation_id
                      AND staff.school_id=membership.school_id AND staff.status='ACTIVE'
                    ORDER BY staff.created_at DESC LIMIT 1) operator_staff_id
                FROM memberships membership
                JOIN user_accounts account ON account.id=membership.user_id AND account.status='ACTIVE'
                JOIN schools school ON school.id=membership.school_id
                  AND school.organisation_id=membership.organisation_id AND school.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=membership.organisation_id
                  AND organisation.status='ACTIVE'
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN roles role ON role.id=mr.role_id
                JOIN role_capabilities rc ON rc.role_id=mr.role_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds) AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                  AND rc.capability_code=:capability
                ORDER BY can_publish DESC,can_safety DESC,can_configure DESC,membership.created_at LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds()).addValue("schoolId", schoolId)
                .addValue("capability", capability), (r, n) -> new Scope(user.activeOrganisationId(),
                r.getObject("id", UUID.class), r.getString("display_name"),
                r.getObject("membership_id", UUID.class), r.getBoolean("can_configure"),
                r.getBoolean("can_operate"), r.getBoolean("can_safety"), r.getBoolean("can_publish"),
                r.getObject("operator_staff_id", UUID.class)));
        if (rows.isEmpty()) throw forbidden("TRANSPORT_SCOPE_DENIED",
                "Transport access is unavailable for this school.");
        Scope scope = rows.getFirst();
        if ("transport.operate".equals(capability) && !scope.broad() && scope.operatorStaffId() == null)
            throw forbidden("TRANSPORT_OPERATOR_NOT_LINKED",
                    "This transport account is not linked to an active staff profile.");
        return scope;
    }

    private void validateRouteRevision(Scope s, RouteRevisionRequest request, UUID editingId) {
        String journey = enumValue(request.journeyKind(), JOURNEY_KINDS, "journey kind");
        String operatingDays = operatingDays(request.operatingDays());
        if (request.effectiveUntil() != null && request.effectiveUntil().isBefore(request.effectiveFrom()))
            throw bad("TRANSPORT_ROUTE_DATES_INVALID", "The route end date cannot precede its start date.");
        int routeCount = jdbc.queryForObject("""
                SELECT count(*) FROM transport_routes
                WHERE id=:routeId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='ACTIVE'
                """, s.params().addValue("routeId", request.routeId()), Integer.class);
        if (routeCount != 1) throw bad("TRANSPORT_ROUTE_NOT_ACTIVE", "Choose an active route from this school.");
        int duplicate = jdbc.queryForObject("""
                SELECT count(*) FROM transport_route_revisions
                WHERE transport_route_id=:routeId AND revision_number=:revision
                  AND organisation_id=:organisationId AND school_id=:schoolId
                  AND (CAST(:editingId AS uuid) IS NULL OR id<>:editingId)
                """, s.params().addValue("routeId", request.routeId())
                .addValue("revision", request.revisionNumber()).addValue("editingId", editingId), Integer.class);
        if (duplicate > 0) throw conflict("TRANSPORT_ROUTE_REVISION_DUPLICATE",
                "That route revision number already exists.");
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(max(revision_number),0) FROM transport_route_revisions
                WHERE transport_route_id=:routeId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND (CAST(:editingId AS uuid) IS NULL OR id<>:editingId)
                """, s.params().addValue("routeId", request.routeId()).addValue("editingId", editingId), Integer.class);
        if (request.revisionNumber() != max + 1) throw bad("TRANSPORT_ROUTE_REVISION_SEQUENCE_INVALID",
                "Use revision " + (max + 1) + " for this route.");
        List<PublishedRevision> published = jdbc.query("""
                SELECT id,journey_kind FROM transport_route_revisions
                WHERE transport_route_id=:routeId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND status='PUBLISHED' AND journey_kind=:journey
                ORDER BY published_at DESC,id DESC LIMIT 1
                """, s.params().addValue("routeId", request.routeId()).addValue("journey", journey),
                (r, n) -> new PublishedRevision(
                r.getObject("id", UUID.class), r.getString("journey_kind")));
        if (published.isEmpty()) {
            if (request.supersedesRevisionId() != null) throw bad("TRANSPORT_ROUTE_SUPERSEDES_INVALID",
                    "A first published route revision cannot supersede another revision.");
        } else {
            PublishedRevision current = published.getFirst();
            if (!current.id().equals(request.supersedesRevisionId()) || !current.journeyKind().equals(journey))
                throw bad("TRANSPORT_ROUTE_SUPERSEDES_INVALID",
                        "A new revision must supersede the current published journey for this route.");
        }
        if (!operatingDays.equals(request.operatingDays().strip().toUpperCase(Locale.ROOT)))
            throw bad("TRANSPORT_ROUTE_DAYS_INVALID", "Enter unique day codes in Monday-to-Sunday order.");
    }

    private RouteStopContext validateRouteStop(Scope s, RouteStopRequest request, UUID editingId) {
        List<RouteStopContext> rows = jdbc.query("""
                SELECT revision.id revision_id,route.campus_id,stop.id stop_id,stop.code,
                  stop.display_name,stop.family_label
                FROM transport_route_revisions revision
                JOIN transport_routes route ON route.id=revision.transport_route_id
                  AND route.organisation_id=revision.organisation_id AND route.school_id=revision.school_id
                JOIN transport_stops stop ON stop.id=:stopId
                  AND stop.organisation_id=revision.organisation_id AND stop.school_id=revision.school_id
                WHERE revision.id=:revisionId AND revision.organisation_id=:organisationId
                  AND revision.school_id=:schoolId AND revision.status='DRAFT'
                  AND route.status='ACTIVE' AND stop.status='ACTIVE' AND stop.campus_id=route.campus_id
                """, s.params().addValue("revisionId", request.routeRevisionId())
                .addValue("stopId", request.stopId()), (r, n) -> new RouteStopContext(
                r.getObject("revision_id", UUID.class), r.getObject("campus_id", UUID.class),
                r.getObject("stop_id", UUID.class), r.getString("code"), r.getString("display_name"),
                r.getString("family_label")));
        if (rows.isEmpty()) throw bad("TRANSPORT_ROUTE_STOP_INVALID",
                "Choose an active stop on the route campus while the revision is still a draft.");
        return rows.getFirst();
    }

    private void requireCompleteRoute(Scope s, UUID id) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM transport_route_revision_stops
                WHERE transport_route_revision_id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, s.params().addValue("id", id), Integer.class);
        if (count == null || count < 2) throw conflict("TRANSPORT_ROUTE_STOPS_INCOMPLETE",
                "A route requires at least two ordered stops before submission.");
        Integer invalid = jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT scheduled_time,lag(scheduled_time) OVER (ORDER BY sequence_number) previous_time
                  FROM transport_route_revision_stops
                  WHERE transport_route_revision_id=:id AND organisation_id=:organisationId
                    AND school_id=:schoolId
                ) ordered_stops WHERE previous_time IS NOT NULL AND scheduled_time<=previous_time
                """, s.params().addValue("id", id), Integer.class);
        if (invalid != null && invalid > 0) throw conflict("TRANSPORT_ROUTE_TIMES_INVALID",
                "Scheduled stop times must increase with stop sequence.");
    }

    private void validateVehicleRequest(VehicleRequest request) {
        String ownership = enumValue(request.ownershipKind(), OWNERSHIP_KINDS, "ownership kind");
        String vendor = clean(request.vendorName());
        if (("CONTRACTED".equals(ownership) && vendor == null) || ("SCHOOL".equals(ownership) && vendor != null))
            throw bad("TRANSPORT_VEHICLE_VENDOR_INVALID",
                    "Contracted vehicles require a vendor; school-owned vehicles must not include one.");
        if (request.clearGpsProviderReference() && clean(request.gpsProviderReference()) != null)
            throw bad("TRANSPORT_GPS_CHANGE_INVALID",
                    "Replace the GPS provider reference or remove it; do not request both changes.");
        enumValue(request.status(), VEHICLE_STATUSES, "vehicle status");
    }

    private void requireCampus(Scope s, UUID campusId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM campuses WHERE id=:campusId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND status='ACTIVE'
                """, s.params().addValue("campusId", campusId), Integer.class);
        if (count != 1) throw bad("TRANSPORT_CAMPUS_INVALID", "Choose an active campus from this school.");
    }

    private void requireActiveVehicle(Scope s, UUID vehicleId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM transport_vehicles WHERE id=:vehicleId
                  AND organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                """, s.params().addValue("vehicleId", vehicleId), Integer.class);
        if (count != 1) throw bad("TRANSPORT_VEHICLE_NOT_ACTIVE", "Choose an active school vehicle.");
    }

    private void requireActiveComplianceType(Scope s, UUID typeId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM transport_vehicle_compliance_types WHERE id=:typeId
                  AND organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                """, s.params().addValue("typeId", typeId), Integer.class);
        if (count != 1) throw bad("TRANSPORT_COMPLIANCE_TYPE_NOT_ACTIVE",
                "Choose an active vehicle readiness rule.");
    }

    private Integer validityMonths(Scope s, UUID typeId) {
        return jdbc.queryForObject("""
                SELECT validity_months FROM transport_vehicle_compliance_types
                WHERE id=:typeId AND organisation_id=:organisationId AND school_id=:schoolId
                """, s.params().addValue("typeId", typeId), Integer.class);
    }

    private void requireVehicleReady(Scope s, UUID vehicleId, LocalDate serviceDate) {
        Integer required = jdbc.queryForObject("""
                SELECT count(*) FROM transport_vehicle_compliance_types
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='ACTIVE' AND required_before_service
                """, s.params(), Integer.class);
        if (required == null || required == 0) throw conflict("TRANSPORT_READINESS_NOT_CONFIGURED",
                "Configure at least one required vehicle readiness rule before active service.");
        Integer missing = jdbc.queryForObject("""
                SELECT count(*) FROM transport_vehicle_compliance_types type
                WHERE type.organisation_id=:organisationId AND type.school_id=:schoolId
                  AND type.status='ACTIVE' AND type.required_before_service
                  AND NOT EXISTS (
                    SELECT 1 FROM transport_vehicle_compliance_checks check_record
                    WHERE check_record.transport_vehicle_id=:vehicleId
                      AND check_record.compliance_type_id=type.id
                      AND check_record.organisation_id=type.organisation_id
                      AND check_record.school_id=type.school_id
                      AND check_record.status='VERIFIED'
                      AND check_record.completed_on<=:serviceDate
                      AND (check_record.valid_until IS NULL OR check_record.valid_until>=:serviceDate))
                """, s.params().addValue("vehicleId", vehicleId).addValue("serviceDate", serviceDate), Integer.class);
        if (missing != null && missing > 0) throw conflict("TRANSPORT_VEHICLE_NOT_READY",
                "Required vehicle readiness evidence is missing or expired for the service date.");
    }

    private void requireEligibleStaff(Scope s, UUID staffId, boolean driver, LocalDate onDate,
            boolean allowPlanned) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM staff_profiles staff
                JOIN staff_assignments assignment ON assignment.staff_profile_id=staff.id
                  AND assignment.organisation_id=staff.organisation_id AND assignment.school_id=staff.school_id
                  AND ((:allowPlanned AND assignment.status IN ('PLANNED','ACTIVE'))
                    OR (NOT :allowPlanned AND assignment.status='ACTIVE'))
                  AND assignment.starts_on<=:onDate
                  AND (assignment.ends_on IS NULL OR assignment.ends_on>=:onDate)
                JOIN staff_designations designation ON designation.id=assignment.staff_designation_id
                  AND designation.organisation_id=assignment.organisation_id
                  AND designation.school_id=assignment.school_id AND designation.status='ACTIVE'
                WHERE staff.id=:staffId AND staff.organisation_id=:organisationId
                  AND staff.school_id=:schoolId AND staff.status='ACTIVE'
                  AND ((:driver AND designation.eligible_as_transport_driver)
                    OR (NOT :driver AND designation.eligible_as_transport_attendant))
        """, s.params().addValue("staffId", staffId).addValue("driver", driver)
                .addValue("onDate", onDate).addValue("allowPlanned", allowPlanned), Integer.class);
        if (count < 1) throw bad(driver ? "TRANSPORT_DRIVER_NOT_ELIGIBLE" : "TRANSPORT_ATTENDANT_NOT_ELIGIBLE",
                driver ? "Choose active staff whose designation explicitly allows driver duty."
                        : "Choose active staff whose designation explicitly allows attendant duty.");
    }

    private void requirePublishedRoute(Scope s, UUID routeRevisionId, String expectedJourney,
            LocalDate effectiveOn) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM transport_route_revisions revision
                JOIN transport_routes route ON route.id=revision.transport_route_id
                  AND route.organisation_id=revision.organisation_id AND route.school_id=revision.school_id
                WHERE revision.id=:revisionId AND revision.organisation_id=:organisationId
                  AND revision.school_id=:schoolId AND revision.status='PUBLISHED' AND route.status='ACTIVE'
                  AND (CAST(:journey AS varchar) IS NULL OR revision.journey_kind=:journey)
                  AND revision.effective_from<=:effectiveOn
                  AND (revision.effective_until IS NULL OR revision.effective_until>=:effectiveOn)
                """, s.params().addValue("revisionId", routeRevisionId).addValue("journey", expectedJourney)
                .addValue("effectiveOn", effectiveOn), Integer.class);
        if (count != 1) throw bad("TRANSPORT_ROUTE_NOT_PUBLISHED",
                "Choose the current published route for this journey.");
    }

    private void requireActiveLearner(Scope s, UUID learnerId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM learners learner
                WHERE learner.id=:learnerId AND learner.organisation_id=:organisationId
                  AND learner.school_id=:schoolId AND learner.status='ACTIVE'
                  AND EXISTS(SELECT 1 FROM student_enrolments enrolment
                    WHERE enrolment.learner_id=learner.id AND enrolment.organisation_id=learner.organisation_id
                      AND enrolment.school_id=learner.school_id AND enrolment.status='ACTIVE')
                """, s.params().addValue("learnerId", learnerId), Integer.class);
        if (count != 1) throw bad("TRANSPORT_LEARNER_NOT_ACTIVE", "Choose an actively enrolled learner.");
    }

    private void requireLearner(Scope s, UUID learnerId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM learners WHERE id=:learnerId AND organisation_id=:organisationId
                  AND school_id=:schoolId
                """, s.params().addValue("learnerId", learnerId), Integer.class);
        if (count != 1) throw bad("TRANSPORT_LEARNER_INVALID", "Choose a learner from this school.");
    }

    private void requireStopOnRoute(Scope s, UUID revisionId, UUID routeStopId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM transport_route_revision_stops
                WHERE id=:routeStopId AND transport_route_revision_id=:revisionId
                  AND organisation_id=:organisationId AND school_id=:schoolId
                """, s.params().addValue("routeStopId", routeStopId).addValue("revisionId", revisionId), Integer.class);
        if (count != 1) throw bad("TRANSPORT_ROUTE_STOP_INVALID", "Choose a stop from the selected route revision.");
    }

    private void requireRouteCapacity(Scope s, UUID revisionId, LocalDate serviceDate, int adding) {
        List<Capacity> rows = jdbc.query("""
                SELECT vehicle.learner_capacity,
                  (SELECT count(*) FROM learner_transport_assignments assignment
                    WHERE assignment.transport_route_revision_id=:revisionId
                      AND assignment.organisation_id=:organisationId AND assignment.school_id=:schoolId
                      AND assignment.status='ACTIVE'
                      AND assignment.starts_on<=:serviceDate
                      AND (assignment.ends_on IS NULL OR assignment.ends_on>=:serviceDate)) active_count
                FROM transport_duty_assignments duty
                JOIN transport_vehicles vehicle ON vehicle.id=duty.transport_vehicle_id
                  AND vehicle.organisation_id=duty.organisation_id AND vehicle.school_id=duty.school_id
                WHERE duty.transport_route_revision_id=:revisionId
                  AND duty.organisation_id=:organisationId AND duty.school_id=:schoolId
                  AND duty.status='ACTIVE' AND duty.starts_on<=:serviceDate
                  AND (duty.ends_on IS NULL OR duty.ends_on>=:serviceDate)
                ORDER BY duty.starts_on DESC,duty.id DESC LIMIT 1
                """, s.params().addValue("revisionId", revisionId).addValue("serviceDate", serviceDate),
                (r, n) -> new Capacity(r.getInt("learner_capacity"), r.getInt("active_count")));
        if (rows.isEmpty()) throw conflict("TRANSPORT_ROUTE_DUTY_REQUIRED",
                "Activate a ready vehicle and duty before activating learner transport.");
        Capacity capacity = rows.getFirst();
        if (capacity.activeCount() + adding > capacity.seats()) throw conflict("TRANSPORT_ROUTE_CAPACITY_EXCEEDED",
                "The assigned vehicle has no remaining learner seat capacity.");
    }

    private void requireVehicleCapacityForRoute(Scope s, UUID revisionId, UUID vehicleId) {
        List<Capacity> rows = jdbc.query("""
                SELECT vehicle.learner_capacity,
                  (SELECT count(*) FROM learner_transport_assignments assignment
                    WHERE assignment.transport_route_revision_id=:revisionId
                      AND assignment.organisation_id=:organisationId AND assignment.school_id=:schoolId
                      AND assignment.status='ACTIVE') active_count
                FROM transport_vehicles vehicle
                WHERE vehicle.id=:vehicleId AND vehicle.organisation_id=:organisationId
                  AND vehicle.school_id=:schoolId AND vehicle.status='ACTIVE'
                """, s.params().addValue("revisionId", revisionId).addValue("vehicleId", vehicleId),
                (r, n) -> new Capacity(r.getInt("learner_capacity"), r.getInt("active_count")));
        if (rows.isEmpty()) throw bad("TRANSPORT_VEHICLE_NOT_ACTIVE", "Choose an active school vehicle.");
        Capacity capacity = rows.getFirst();
        if (capacity.activeCount() > capacity.seats()) throw conflict("TRANSPORT_ROUTE_CAPACITY_EXCEEDED",
                "The vehicle learner capacity is below the route's active learner roster.");
    }

    private void lockTransportCapacity(Scope s) {
        lockTransactionKey("transport-capacity:" + s.schoolId());
    }

    private int activeRouteWork(Scope s, UUID routeId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM transport_route_revisions revision
                WHERE revision.transport_route_id=:routeId AND revision.organisation_id=:organisationId
                  AND revision.school_id=:schoolId AND (
                    EXISTS(SELECT 1 FROM transport_duty_assignments duty
                      WHERE duty.transport_route_revision_id=revision.id AND duty.status IN ('PLANNED','ACTIVE'))
                    OR EXISTS(SELECT 1 FROM learner_transport_assignments assignment
                      WHERE assignment.transport_route_revision_id=revision.id
                        AND assignment.status IN ('PLANNED','ACTIVE')))
                """, s.params().addValue("routeId", routeId), Integer.class);
    }

    private int maximumActiveRiders(Scope s, UUID vehicleId) {
        Integer count = jdbc.queryForObject("""
                SELECT COALESCE(max(riders),0) FROM (
                  SELECT count(assignment.id) riders FROM transport_duty_assignments duty
                  LEFT JOIN learner_transport_assignments assignment
                    ON assignment.transport_route_revision_id=duty.transport_route_revision_id
                   AND assignment.status='ACTIVE'
                  WHERE duty.transport_vehicle_id=:vehicleId AND duty.organisation_id=:organisationId
                    AND duty.school_id=:schoolId AND duty.status='ACTIVE'
                  GROUP BY duty.id
                ) route_counts
                """, s.params().addValue("vehicleId", vehicleId), Integer.class);
        return count == null ? 0 : count;
    }

    private int activeVehicleDuties(Scope s, UUID vehicleId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM transport_duty_assignments
                WHERE transport_vehicle_id=:vehicleId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND status IN ('PLANNED','ACTIVE')
                """, s.params().addValue("vehicleId", vehicleId), Integer.class);
    }

    private void requireDutyConflictFree(Scope s, UUID revisionId, UUID vehicleId,
            UUID driverId, UUID attendantId, LocalDate startsOn) {
        Integer conflicts = jdbc.queryForObject("""
                WITH candidate AS (
                  SELECT revision.operating_days,revision.effective_from,revision.effective_until,
                    min(route_stop.scheduled_time) window_start,
                    max(route_stop.scheduled_time) window_end
                  FROM transport_route_revisions revision
                  JOIN transport_route_revision_stops route_stop
                    ON route_stop.transport_route_revision_id=revision.id
                   AND route_stop.organisation_id=revision.organisation_id
                   AND route_stop.school_id=revision.school_id
                  WHERE revision.id=:revisionId AND revision.organisation_id=:organisationId
                    AND revision.school_id=:schoolId
                  GROUP BY revision.id,revision.operating_days,revision.effective_from,
                    revision.effective_until
                ), active_windows AS (
                  SELECT duty.id,duty.transport_vehicle_id,duty.driver_staff_profile_id,
                    duty.attendant_staff_profile_id,duty.starts_on,duty.ends_on,
                    revision.operating_days,revision.effective_from,revision.effective_until,
                    min(route_stop.scheduled_time) window_start,
                    max(route_stop.scheduled_time) window_end
                  FROM transport_duty_assignments duty
                  JOIN transport_route_revisions revision
                    ON revision.id=duty.transport_route_revision_id
                   AND revision.organisation_id=duty.organisation_id
                   AND revision.school_id=duty.school_id
                  JOIN transport_route_revision_stops route_stop
                    ON route_stop.transport_route_revision_id=revision.id
                   AND route_stop.organisation_id=revision.organisation_id
                   AND route_stop.school_id=revision.school_id
                  WHERE duty.organisation_id=:organisationId AND duty.school_id=:schoolId
                    AND duty.status='ACTIVE' AND duty.transport_route_revision_id<>:revisionId
                  GROUP BY duty.id,revision.id,revision.operating_days,revision.effective_from,
                    revision.effective_until
                )
                SELECT count(*) FROM candidate,active_windows active
                WHERE (active.transport_vehicle_id=:vehicleId
                    OR active.driver_staff_profile_id IN (:driverId,:attendantId)
                    OR active.attendant_staff_profile_id IN (:driverId,:attendantId))
                  AND string_to_array(candidate.operating_days,',')
                    && string_to_array(active.operating_days,',')
                  AND GREATEST(CAST(:startsOn AS date),candidate.effective_from)
                    <= LEAST(COALESCE(candidate.effective_until,'infinity'::date),
                      COALESCE(active.ends_on,'infinity'::date),
                      COALESCE(active.effective_until,'infinity'::date))
                  AND GREATEST(active.starts_on,active.effective_from)
                    <= COALESCE(candidate.effective_until,'infinity'::date)
                  AND candidate.window_start<=active.window_end
                  AND active.window_start<=candidate.window_end
                """, s.params().addValue("revisionId", revisionId).addValue("vehicleId", vehicleId)
                .addValue("driverId", driverId).addValue("attendantId", attendantId)
                .addValue("startsOn", startsOn), Integer.class);
        if (conflicts != null && conflicts > 0) throw conflict("TRANSPORT_DUTY_SCHEDULE_CONFLICT",
                "The vehicle, driver, or attendant already has an overlapping active transport duty.");
    }

    private void lockDutyResources(Scope s, UUID vehicleId, UUID driverId, UUID attendantId) {
        List.of(vehicleId, driverId, attendantId).stream()
                .map(resourceId -> "transport-duty:" + s.schoolId() + ":" + resourceId)
                .sorted()
                .forEach(this::lockTransactionKey);
    }

    private void lockTransactionKey(String key) {
        jdbc.getJdbcTemplate().queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS locked",
                Integer.class, key);
    }

    private int openTripsForDuty(Scope s, UUID dutyId, LocalDate endDate) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM transport_trips WHERE transport_duty_assignment_id=:dutyId
                  AND organisation_id=:organisationId AND school_id=:schoolId
                  AND service_date>=:endDate AND status IN ('PLANNED','BOARDING','IN_PROGRESS')
                """, s.params().addValue("dutyId", dutyId).addValue("endDate", endDate), Integer.class);
    }

    private RouteForTrip routeForTrip(Scope s, UUID revisionId, LocalDate serviceDate) {
        String day = DAY_CODES.get(serviceDate.getDayOfWeek());
        List<RouteForTrip> rows = jdbc.query("""
                SELECT revision.journey_kind,revision.service_label,revision.operating_days,
                  route.code route_code,route.display_name route_name,campus.time_zone
                FROM transport_route_revisions revision
                JOIN transport_routes route ON route.id=revision.transport_route_id
                  AND route.organisation_id=revision.organisation_id AND route.school_id=revision.school_id
                JOIN campuses campus ON campus.id=route.campus_id
                  AND campus.organisation_id=route.organisation_id AND campus.school_id=route.school_id
                WHERE revision.id=:revisionId AND revision.organisation_id=:organisationId
                  AND revision.school_id=:schoolId AND revision.status IN ('PUBLISHED','RETIRED')
                  AND route.status='ACTIVE'
                  AND revision.effective_from<=:serviceDate
                  AND (revision.effective_until IS NULL OR revision.effective_until>=:serviceDate)
                  AND :day=ANY(string_to_array(revision.operating_days,','))
                """, s.params().addValue("revisionId", revisionId).addValue("serviceDate", serviceDate)
                .addValue("day", day), (r, n) -> new RouteForTrip(r.getString("journey_kind"),
                r.getString("service_label"), r.getString("operating_days"),
                r.getString("route_code"), r.getString("route_name"), r.getString("time_zone")));
        if (rows.isEmpty()) throw bad("TRANSPORT_TRIP_ROUTE_INVALID",
                "The route is not published and effective for this approved service day.");
        return rows.getFirst();
    }

    private LocalDate localDateForRevision(Scope s, UUID revisionId) {
        List<String> zones = jdbc.query("""
                SELECT campus.time_zone FROM transport_route_revisions revision
                JOIN transport_routes route ON route.id=revision.transport_route_id
                  AND route.organisation_id=revision.organisation_id AND route.school_id=revision.school_id
                JOIN campuses campus ON campus.id=route.campus_id
                  AND campus.organisation_id=route.organisation_id AND campus.school_id=route.school_id
                WHERE revision.id=:revisionId AND revision.organisation_id=:organisationId
                  AND revision.school_id=:schoolId
                """, s.params().addValue("revisionId", revisionId),
                (r, n) -> r.getString("time_zone"));
        if (zones.isEmpty()) throw bad("TRANSPORT_ROUTE_TIME_ZONE_INVALID",
                "The route campus time zone is unavailable.");
        return LocalDate.now(clock.withZone(ZoneId.of(zones.getFirst())));
    }

    private VehicleDutySnapshot dutySnapshot(Scope s, UUID dutyId) {
        List<VehicleDutySnapshot> rows = jdbc.query("""
                SELECT vehicle.display_label,vehicle.registration_number,vehicle.learner_capacity,
                  driver.display_name driver_name,attendant.display_name attendant_name
                FROM transport_duty_assignments duty
                JOIN transport_vehicles vehicle ON vehicle.id=duty.transport_vehicle_id
                  AND vehicle.organisation_id=duty.organisation_id AND vehicle.school_id=duty.school_id
                JOIN staff_profiles driver ON driver.id=duty.driver_staff_profile_id
                JOIN staff_profiles attendant ON attendant.id=duty.attendant_staff_profile_id
                WHERE duty.id=:dutyId AND duty.organisation_id=:organisationId AND duty.school_id=:schoolId
                """, s.params().addValue("dutyId", dutyId), (r, n) -> new VehicleDutySnapshot(
                r.getString("display_label"), r.getString("registration_number"),
                r.getInt("learner_capacity"), r.getString("driver_name"), r.getString("attendant_name")));
        if (rows.isEmpty()) throw bad("TRANSPORT_DUTY_INVALID", "The selected duty is unavailable.");
        return rows.getFirst();
    }

    private List<RosterSeed> rosterSeed(Scope s, UUID revisionId, LocalDate serviceDate) {
        return jdbc.query("""
                SELECT assignment.id assignment_id,assignment.learner_id,route_stop.id route_stop_id,
                  COALESCE(learner.preferred_name,learner.legal_name) learner_name,
                  route_stop.sequence_number,route_stop.family_label_snapshot,route_stop.scheduled_time
                FROM learner_transport_assignments assignment
                JOIN learners learner ON learner.id=assignment.learner_id
                  AND learner.organisation_id=assignment.organisation_id AND learner.school_id=assignment.school_id
                JOIN transport_route_revision_stops route_stop ON route_stop.id=assignment.transport_route_stop_id
                  AND route_stop.organisation_id=assignment.organisation_id
                  AND route_stop.school_id=assignment.school_id
                WHERE assignment.transport_route_revision_id=:revisionId
                  AND assignment.organisation_id=:organisationId AND assignment.school_id=:schoolId
                  AND assignment.status='ACTIVE' AND assignment.starts_on<=:serviceDate
                  AND (assignment.ends_on IS NULL OR assignment.ends_on>=:serviceDate)
                  AND learner.status='ACTIVE'
                ORDER BY route_stop.sequence_number,learner_name,learner.id
                """, s.params().addValue("revisionId", revisionId).addValue("serviceDate", serviceDate),
                (r, n) -> new RosterSeed(r.getObject("assignment_id", UUID.class),
                r.getObject("learner_id", UUID.class), r.getObject("route_stop_id", UUID.class),
                r.getString("learner_name"), r.getInt("sequence_number"),
                r.getString("family_label_snapshot"), r.getObject("scheduled_time", LocalTime.class)));
    }

    private void requireOperationalAccess(Scope s, StoredDuty duty) {
        if (!s.broad() && !Objects.equals(s.operatorStaffId(), duty.driverId())
                && !Objects.equals(s.operatorStaffId(), duty.attendantId()))
            throw forbidden("TRANSPORT_DUTY_SCOPE_DENIED", "This trip is not assigned to your active staff profile.");
    }

    private void requireRosterEntry(Scope s, UUID tripId, UUID rosterId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM transport_trip_roster_entries
                WHERE id=:rosterId AND transport_trip_id=:tripId
                  AND organisation_id=:organisationId AND school_id=:schoolId
                """, s.params().addValue("rosterId", rosterId).addValue("tripId", tripId), Integer.class);
        if (count != 1) throw bad("TRANSPORT_ROSTER_ENTRY_INVALID",
                "Choose a learner from this trip's frozen roster.");
    }

    private void validateEventProgression(Scope s, UUID rosterId, String type, String tripStatus) {
        Set<String> existing = new HashSet<>(jdbc.query("""
                SELECT event_type FROM transport_trip_events
                WHERE roster_entry_id=:rosterId AND organisation_id=:organisationId AND school_id=:schoolId
                """, s.params().addValue("rosterId", rosterId), (r, n) -> r.getString("event_type")));
        if (!"EXCEPTION".equals(type) && existing.contains(type)) throw conflict("TRANSPORT_EVENT_DUPLICATE",
                "That roster event is already recorded.");
        if ("BOARDED".equals(type) && (existing.contains("ABSENT") || existing.contains("ALIGHTED")))
            throw conflict("TRANSPORT_EVENT_SEQUENCE_INVALID", "An absent or completed journey cannot be boarded.");
        if ("ABSENT".equals(type) && (!existing.isEmpty() && !existing.equals(Set.of("EXCEPTION"))))
            throw conflict("TRANSPORT_EVENT_SEQUENCE_INVALID", "Absence must be recorded before boarding.");
        if ("ALIGHTED".equals(type) && (!existing.contains("BOARDED") || existing.contains("ABSENT")))
            throw conflict("TRANSPORT_EVENT_SEQUENCE_INVALID", "Record boarding before alighting.");
        if ("ALIGHTED".equals(type) && !"IN_PROGRESS".equals(tripStatus))
            throw conflict("TRANSPORT_EVENT_SEQUENCE_INVALID", "Alighting is recorded after the trip starts.");
    }

    private int incompleteRosterCount(Scope s, UUID tripId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM transport_trip_roster_entries roster
                WHERE roster.transport_trip_id=:tripId AND roster.organisation_id=:organisationId
                  AND roster.school_id=:schoolId AND NOT EXISTS (
                    SELECT 1 FROM transport_trip_events event WHERE event.roster_entry_id=roster.id
                      AND event.event_type IN ('ALIGHTED','ABSENT'))
                """, s.params().addValue("tripId", tripId), Integer.class);
    }

    private int unreconciledBoardedCount(Scope s, UUID tripId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM transport_trip_roster_entries roster
                WHERE roster.transport_trip_id=:tripId AND roster.organisation_id=:organisationId
                  AND roster.school_id=:schoolId
                  AND EXISTS (SELECT 1 FROM transport_trip_events boarded
                    WHERE boarded.roster_entry_id=roster.id AND boarded.event_type='BOARDED')
                  AND NOT EXISTS (SELECT 1 FROM transport_trip_events alighted
                    WHERE alighted.roster_entry_id=roster.id AND alighted.event_type='ALIGHTED')
                """, s.params().addValue("tripId", tripId), Integer.class);
    }

    private static boolean allowedTripTransition(String current, String next) {
        return switch (current) {
            case "PLANNED" -> Set.of("BOARDING", "CANCELLED").contains(next);
            case "BOARDING" -> Set.of("IN_PROGRESS", "CANCELLED").contains(next);
            case "IN_PROGRESS" -> Set.of("COMPLETED", "CANCELLED").contains(next);
            default -> false;
        };
    }

    private StoredConfig config(Scope s, String table, UUID id, boolean lock) {
        Set<String> allowed = Set.of("transport_stops", "transport_routes", "transport_route_revision_stops",
                "transport_vehicles", "transport_vehicle_compliance_types");
        if (!allowed.contains(table)) throw new IllegalArgumentException("Unsupported transport table");
        String status = "transport_route_revision_stops".equals(table) ? "'DRAFT'" : "status";
        List<StoredConfig> rows = jdbc.query("SELECT id," + status + " status,row_version FROM " + table
                + " WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId"
                + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id),
                (r, n) -> new StoredConfig(r.getObject("id", UUID.class), r.getString("status"), r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("TRANSPORT_RECORD_NOT_FOUND", "The transport record was not found.");
        return rows.getFirst();
    }

    private StoredRevision revision(Scope s, UUID id, boolean lock) {
        List<StoredRevision> rows = jdbc.query("""
                SELECT id,transport_route_id,supersedes_revision_id,journey_kind,status,
                  created_by_membership_id,row_version
                FROM transport_route_revisions
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id),
                (r, n) -> new StoredRevision(r.getObject("id", UUID.class),
                r.getObject("transport_route_id", UUID.class),
                r.getObject("supersedes_revision_id", UUID.class), r.getString("journey_kind"),
                r.getString("status"), r.getObject("created_by_membership_id", UUID.class),
                r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("TRANSPORT_ROUTE_REVISION_NOT_FOUND", "The route revision was not found.");
        return rows.getFirst();
    }

    private StoredCheck check(Scope s, UUID id, boolean lock) {
        List<StoredCheck> rows = jdbc.query("""
                SELECT id,transport_vehicle_id,compliance_type_id,status,created_by_membership_id,row_version
                FROM transport_vehicle_compliance_checks
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id),
                (r, n) -> new StoredCheck(r.getObject("id", UUID.class),
                r.getObject("transport_vehicle_id", UUID.class), r.getObject("compliance_type_id", UUID.class),
                r.getString("status"), r.getObject("created_by_membership_id", UUID.class),
                r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("TRANSPORT_CHECK_NOT_FOUND", "The vehicle readiness check was not found.");
        return rows.getFirst();
    }

    private StoredDuty duty(Scope s, UUID id, boolean lock) {
        List<StoredDuty> rows = jdbc.query("""
                SELECT id,transport_route_revision_id,transport_vehicle_id,driver_staff_profile_id,
                  attendant_staff_profile_id,starts_on,ends_on,status,row_version
                FROM transport_duty_assignments
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id),
                (r, n) -> new StoredDuty(r.getObject("id", UUID.class),
                r.getObject("transport_route_revision_id", UUID.class),
                r.getObject("transport_vehicle_id", UUID.class),
                r.getObject("driver_staff_profile_id", UUID.class),
                r.getObject("attendant_staff_profile_id", UUID.class),
                r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class),
                r.getString("status"), r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("TRANSPORT_DUTY_NOT_FOUND", "The transport duty was not found.");
        return rows.getFirst();
    }

    private StoredAssignment learnerAssignment(Scope s, UUID id, boolean lock) {
        List<StoredAssignment> rows = jdbc.query("""
                SELECT id,learner_id,transport_route_revision_id,starts_on,status,row_version
                FROM learner_transport_assignments
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id),
                (r, n) -> new StoredAssignment(r.getObject("id", UUID.class),
                r.getObject("learner_id", UUID.class), r.getObject("transport_route_revision_id", UUID.class),
                r.getObject("starts_on", LocalDate.class), r.getString("status"), r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("LEARNER_TRANSPORT_ASSIGNMENT_NOT_FOUND",
                "The learner transport assignment was not found.");
        return rows.getFirst();
    }

    private StoredTrip trip(Scope s, UUID id, boolean lock) {
        List<StoredTrip> rows = jdbc.query("""
                SELECT id,transport_duty_assignment_id,transport_route_revision_id,service_date,status,row_version
                FROM transport_trips WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id),
                (r, n) -> new StoredTrip(r.getObject("id", UUID.class),
                r.getObject("transport_duty_assignment_id", UUID.class),
                r.getObject("transport_route_revision_id", UUID.class),
                r.getObject("service_date", LocalDate.class), r.getString("status"), r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("TRANSPORT_TRIP_NOT_FOUND", "The transport trip was not found.");
        return rows.getFirst();
    }

    private StoredIncident incident(Scope s, UUID id, boolean lock) {
        List<StoredIncident> rows = jdbc.query("""
                SELECT id,status,reported_by_membership_id,row_version FROM transport_incidents
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id),
                (r, n) -> new StoredIncident(r.getObject("id", UUID.class),
                r.getString("status"), r.getObject("reported_by_membership_id", UUID.class),
                r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("TRANSPORT_INCIDENT_NOT_FOUND", "The transport incident was not found.");
        return rows.getFirst();
    }

    private MapSqlParameterSource stopParams(Scope s, UUID id, StopRequest request, Long version, Instant now) {
        return s.params().addValue("id", id).addValue("campusId", request.campusId())
                .addValue("code", configCode(request.code())).addValue("name", required(request.displayName()))
                .addValue("familyLabel", required(request.familyLabel())).addValue("locality", clean(request.locality()))
                .addValue("latitude", request.latitude()).addValue("longitude", request.longitude())
                .addValue("accessibility", clean(request.accessibilityNote()))
                .addValue("status", enumValue(request.status(), CONFIG_STATUSES, "stop status"))
                .addValue("version", version).addValue("now", now);
    }

    private MapSqlParameterSource routeParams(Scope s, UUID id, RouteRequest request, Long version, Instant now) {
        return s.params().addValue("id", id).addValue("campusId", request.campusId())
                .addValue("code", configCode(request.code())).addValue("name", required(request.displayName()))
                .addValue("description", clean(request.description()))
                .addValue("status", enumValue(request.status(), CONFIG_STATUSES, "route status"))
                .addValue("version", version).addValue("now", now);
    }

    private MapSqlParameterSource revisionParams(Scope s, UUID id, RouteRevisionRequest request,
            Long version, Instant now) {
        return s.params().addValue("id", id).addValue("routeId", request.routeId())
                .addValue("revision", request.revisionNumber()).addValue("supersedesId", request.supersedesRevisionId())
                .addValue("journeyKind", enumValue(request.journeyKind(), JOURNEY_KINDS, "journey kind"))
                .addValue("serviceLabel", required(request.serviceLabel()))
                .addValue("operatingDays", operatingDays(request.operatingDays()))
                .addValue("effectiveFrom", request.effectiveFrom()).addValue("effectiveUntil", request.effectiveUntil())
                .addValue("version", version).addValue("now", now);
    }

    private MapSqlParameterSource routeStopParams(Scope s, UUID id, RouteStopRequest request,
            RouteStopContext context, Long version, Instant now) {
        return s.params().addValue("id", id).addValue("revisionId", request.routeRevisionId())
                .addValue("stopId", request.stopId()).addValue("sequence", request.sequenceNumber())
                .addValue("scheduledTime", request.scheduledTime()).addValue("window", request.boardingWindowMinutes())
                .addValue("stopCode", context.stopCode()).addValue("stopName", context.stopName())
                .addValue("familyLabel", context.familyLabel()).addValue("version", version).addValue("now", now);
    }

    private MapSqlParameterSource vehicleParams(Scope s, UUID id, VehicleRequest request,
            Long version, Instant now) {
        return s.params().addValue("id", id).addValue("campusId", request.campusId())
                .addValue("code", configCode(request.vehicleCode())).addValue("label", required(request.displayLabel()))
                .addValue("registration", registration(request.registrationNumber()))
                .addValue("ownership", enumValue(request.ownershipKind(), OWNERSHIP_KINDS, "ownership kind"))
                .addValue("vendor", clean(request.vendorName())).addValue("capacity", request.learnerCapacity())
                .addValue("wheelchair", request.wheelchairAccessible())
                .addValue("gpsReference", clean(request.gpsProviderReference()))
                .addValue("clearGpsReference", request.clearGpsProviderReference())
                .addValue("status", enumValue(request.status(), VEHICLE_STATUSES, "vehicle status"))
                .addValue("version", version).addValue("now", now);
    }

    private MapSqlParameterSource complianceTypeParams(Scope s, UUID id, ComplianceTypeRequest request,
            Long version, Instant now) {
        return s.params().addValue("id", id).addValue("code", configCode(request.code()))
                .addValue("name", required(request.name())).addValue("description", clean(request.description()))
                .addValue("validityMonths", request.validityMonths()).addValue("required", request.requiredBeforeService())
                .addValue("status", enumValue(request.status(), CONFIG_STATUSES, "compliance status"))
                .addValue("version", version).addValue("now", now);
    }

    private void history(Scope s, String subjectType, UUID subjectId, String event,
            String previous, String next, String why) {
        jdbc.update("""
                INSERT INTO transport_history(id,organisation_id,school_id,subject_type,subject_id,
                  event_type,previous_status,new_status,reason,actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:subjectType,:subjectId,:event,:previous,
                  :next,:reason,:actorMembershipId,:now)
                """, s.params().addValue("id", UUID.randomUUID()).addValue("subjectType", subjectType)
                .addValue("subjectId", subjectId).addValue("event", event).addValue("previous", previous)
                .addValue("next", next).addValue("reason", reason(why)).addValue("now", Instant.now(clock)));
    }

    private void audit(AuthenticatedUser user, Scope s, String action, String entityType, UUID id, String why) {
        audit.recordScoped(user, s.actorMembershipId(), s.schoolId(), action, entityType, id.toString(),
                AuditOutcome.SUCCEEDED, clean(why), Map.of());
    }

    private static void validateCoordinates(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) throw bad("TRANSPORT_STOP_COORDINATES_INVALID",
                "Enter both latitude and longitude or leave both blank.");
    }

    private static String operatingDays(String value) {
        String cleaned = required(value).toUpperCase(Locale.ROOT).replace(" ", "");
        List<String> values = Arrays.asList(cleaned.split(","));
        List<String> ordered = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
        if (values.isEmpty() || values.stream().anyMatch(day -> !ordered.contains(day))
                || new HashSet<>(values).size() != values.size())
            throw bad("TRANSPORT_ROUTE_DAYS_INVALID", "Use unique day codes such as MON,TUE,WED,THU,FRI,SAT.");
        return ordered.stream().filter(values::contains).reduce((a, b) -> a + "," + b).orElseThrow();
    }

    private static String registration(String value) {
        return required(value).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String learnerReference(UUID id) {
        return "STU-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null
                : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").strip();
    }

    private static String required(String value) {
        String result = clean(value);
        if (result == null) throw bad("REQUIRED_VALUE_MISSING", "Enter the required value.");
        return result;
    }

    private static String reason(String value) { return reason(value, null); }
    private static String reason(String value, String fallback) {
        String result = clean(value);
        if (result == null) result = fallback;
        if (result == null) throw bad("CHANGE_REASON_REQUIRED", "Enter a reason for this change.");
        return result;
    }

    private static String configCode(String value) {
        return required(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String result = required(value).toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(result)) throw bad("INVALID_VALUE", "Choose a supported " + label + ".");
        return result;
    }

    private static long version(Long value) {
        if (value == null) throw bad("EXPECTED_VERSION_REQUIRED",
                "Refresh the record and include its current version.");
        return value;
    }

    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION",
                "The record changed or is no longer editable. Refresh and try again.");
    }

    private static <T> T safe(String code, java.util.function.Supplier<T> work) {
        try { return work.get(); }
        catch (DataIntegrityViolationException error) {
            throw conflict(code, "A conflicting transport record already exists or violates a protected state.");
        }
    }

    private static Double number(ResultSet result, String name) throws SQLException {
        var value = result.getBigDecimal(name);
        return value == null ? null : value.doubleValue();
    }

    private static Integer integer(ResultSet result, String name) throws SQLException {
        int value = result.getInt(name);
        return result.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet result, String name) throws SQLException {
        var value = result.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }
    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    record Scope(UUID organisationId, UUID schoolId, String schoolName,
            UUID actorMembershipId, boolean canConfigure, boolean canOperate,
            boolean canSafety, boolean canPublish, UUID operatorStaffId) {
        boolean broad() { return canConfigure || canSafety || canPublish; }
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record StoredConfig(UUID id, String status, long version) {}
    private record StoredRevision(UUID id, UUID routeId, UUID supersedesId, String journeyKind, String status,
            UUID creatorId, long version) {}
    private record PublishedRevision(UUID id, String journeyKind) {}
    private record RouteStopContext(UUID revisionId, UUID campusId, UUID stopId,
            String stopCode, String stopName, String familyLabel) {}
    private record StoredRouteStop(UUID id, UUID routeRevisionId, long version) {}
    private record StoredCheck(UUID id, UUID vehicleId, UUID typeId, String status,
            UUID creatorId, long version) {}
    private record StoredDuty(UUID id, UUID routeRevisionId, UUID vehicleId, UUID driverId,
            UUID attendantId, LocalDate startsOn, LocalDate endsOn, String status, long version) {}
    private record StoredAssignment(UUID id, UUID learnerId, UUID routeRevisionId,
            LocalDate startsOn, String status, long version) {}
    private record StoredTrip(UUID id, UUID dutyId, UUID routeRevisionId,
            LocalDate serviceDate, String status, long version) {}
    private record StoredEvent(UUID id, UUID tripId, UUID rosterId, String type) {}
    private record StoredIncident(UUID id, String status, UUID reporterId, long version) {}
    private record Capacity(int seats, int activeCount) {}
    private record RouteForTrip(String journeyKind, String serviceLabel, String operatingDays,
            String routeCode, String routeName, String timeZone) {}
    private record VehicleDutySnapshot(String vehicleLabel, String registration, int capacity,
            String driverName, String attendantName) {}
    private record RosterSeed(UUID assignmentId, UUID learnerId, UUID routeStopId,
            String learnerName, int sequence, String familyLabel, LocalTime scheduledTime) {}
}

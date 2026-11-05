package com.educore.api.transport;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FamilyTransportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public FamilyTransportService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    FamilyContext requireContext(AuthenticatedUser user, UUID learnerId) {
        if (user.activeOrganisationId() == null) throw new ApiException(HttpStatus.CONFLICT,
                "ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        if (user.membershipIds().isEmpty()) throw childNotFound();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("membershipIds", user.membershipIds())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("learnerId", learnerId);
        List<FamilyContext> contexts = jdbc.query("""
                SELECT learner.id learner_id,learner.school_id,campus.id campus_id,campus.time_zone
                FROM guardians guardian
                JOIN user_accounts account ON account.id=guardian.user_account_id AND account.status='ACTIVE'
                JOIN schools school ON school.id=guardian.school_id
                  AND school.organisation_id=guardian.organisation_id AND school.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=guardian.organisation_id
                  AND organisation.status='ACTIVE'
                JOIN learner_guardian_relationships relationship
                  ON relationship.guardian_id=guardian.id
                 AND relationship.organisation_id=guardian.organisation_id
                 AND relationship.school_id=guardian.school_id
                 AND relationship.portal_access_status='ACTIVE'
                JOIN learners learner ON learner.id=relationship.learner_id
                 AND learner.organisation_id=relationship.organisation_id
                 AND learner.school_id=relationship.school_id AND learner.status='ACTIVE'
                JOIN LATERAL (
                  SELECT enrolment.campus_id FROM student_enrolments enrolment
                  WHERE enrolment.learner_id=learner.id
                    AND enrolment.organisation_id=learner.organisation_id
                    AND enrolment.school_id=learner.school_id AND enrolment.status='ACTIVE'
                  ORDER BY enrolment.starts_on DESC,enrolment.id DESC LIMIT 1
                ) current_enrolment ON true
                JOIN campuses campus ON campus.id=current_enrolment.campus_id
                  AND campus.organisation_id=learner.organisation_id
                  AND campus.school_id=learner.school_id AND campus.status='ACTIVE'
                WHERE guardian.user_account_id=:actorUserId
                  AND guardian.organisation_id=:organisationId AND guardian.status='ACTIVE'
                  AND learner.id=:learnerId
                  AND EXISTS(
                    SELECT 1 FROM memberships membership
                    JOIN membership_roles membership_role ON membership_role.membership_id=membership.id
                    JOIN roles role ON role.id=membership_role.role_id
                    JOIN role_capabilities capability ON capability.role_id=membership_role.role_id
                    WHERE membership.user_id=:actorUserId AND membership.id IN(:membershipIds)
                      AND membership.organisation_id=guardian.organisation_id
                      AND membership.school_id=guardian.school_id AND membership.status='ACTIVE'
                      AND membership.valid_from<=CURRENT_TIMESTAMP
                      AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                      AND capability.capability_code='portal.guardian')
                """, p, (r, n) -> new FamilyContext(r.getObject("learner_id", UUID.class),
                r.getObject("school_id", UUID.class),r.getObject("campus_id", UUID.class), r.getString("time_zone")));
        if (contexts.isEmpty()) throw childNotFound();
        return contexts.getFirst();
    }

    @Transactional(readOnly = true)
    public FamilyTransportResponse transport(AuthenticatedUser user, UUID learnerId) {
        FamilyContext context = requireContext(user,learnerId);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("organisationId",user.activeOrganisationId()).addValue("learnerId",learnerId)
                .addValue("schoolId", context.schoolId())
                .addValue("today", LocalDate.now(clock.withZone(ZoneId.of(context.timeZone()))));
        List<FamilyTransportResponse.JourneyItem> journeys = jdbc.query("""
                SELECT assignment.journey_kind,route.display_name route_name,revision.service_label,
                  revision.operating_days,route_stop.family_label_snapshot,route_stop.scheduled_time,
                  route_stop.boarding_window_minutes,vehicle.display_label vehicle_label,
                  vehicle.registration_number,assignment.starts_on,assignment.ends_on,
                  latest_trip.id trip_id,latest_trip.service_date,latest_trip.status trip_status,
                  latest_trip.updated_at trip_updated_at,latest_event.event_type learner_status,
                  latest_event.occurred_at learner_status_at
                FROM learner_transport_assignments assignment
                JOIN transport_route_revisions revision ON revision.id=assignment.transport_route_revision_id
                  AND revision.organisation_id=assignment.organisation_id
                  AND revision.school_id=assignment.school_id
                  AND revision.status IN ('PUBLISHED','RETIRED')
                JOIN transport_routes route ON route.id=revision.transport_route_id
                  AND route.organisation_id=revision.organisation_id AND route.school_id=revision.school_id
                  AND route.status='ACTIVE'
                JOIN transport_route_revision_stops route_stop ON route_stop.id=assignment.transport_route_stop_id
                  AND route_stop.transport_route_revision_id=revision.id
                  AND route_stop.organisation_id=assignment.organisation_id
                  AND route_stop.school_id=assignment.school_id
                LEFT JOIN LATERAL (
                  SELECT duty.transport_vehicle_id FROM transport_duty_assignments duty
                  WHERE duty.transport_route_revision_id=revision.id
                    AND duty.organisation_id=revision.organisation_id AND duty.school_id=revision.school_id
                    AND duty.status='ACTIVE' AND duty.starts_on<=:today
                    AND (duty.ends_on IS NULL OR duty.ends_on>=:today)
                  ORDER BY duty.starts_on DESC,duty.id DESC LIMIT 1
                ) current_duty ON true
                LEFT JOIN transport_vehicles vehicle ON vehicle.id=current_duty.transport_vehicle_id
                  AND vehicle.organisation_id=assignment.organisation_id
                  AND vehicle.school_id=assignment.school_id AND vehicle.status='ACTIVE'
                LEFT JOIN LATERAL (
                  SELECT trip.* FROM transport_trips trip
                  WHERE trip.transport_route_revision_id=revision.id
                    AND trip.organisation_id=revision.organisation_id AND trip.school_id=revision.school_id
                    AND trip.service_date<=:today
                    AND EXISTS (
                      SELECT 1 FROM transport_trip_roster_entries roster
                      WHERE roster.transport_trip_id=trip.id
                        AND roster.learner_transport_assignment_id=assignment.id
                        AND roster.organisation_id=trip.organisation_id
                        AND roster.school_id=trip.school_id)
                  ORDER BY trip.service_date DESC,trip.created_at DESC,trip.id DESC LIMIT 1
                ) latest_trip ON true
                LEFT JOIN LATERAL (
                  SELECT event.event_type,event.occurred_at
                  FROM transport_trip_roster_entries roster
                  JOIN transport_trip_events event ON event.roster_entry_id=roster.id
                    AND event.organisation_id=roster.organisation_id AND event.school_id=roster.school_id
                  WHERE roster.transport_trip_id=latest_trip.id AND roster.learner_id=assignment.learner_id
                    AND roster.organisation_id=assignment.organisation_id
                    AND roster.school_id=assignment.school_id
                  ORDER BY event.occurred_at DESC,event.created_at DESC,event.id DESC LIMIT 1
                ) latest_event ON true
                WHERE assignment.organisation_id=:organisationId AND assignment.school_id=:schoolId
                  AND assignment.learner_id=:learnerId AND assignment.status='ACTIVE'
                  AND assignment.starts_on<=:today
                  AND (assignment.ends_on IS NULL OR assignment.ends_on>=:today)
                ORDER BY CASE assignment.journey_kind WHEN 'PICKUP' THEN 0 ELSE 1 END,assignment.id
                """, p, (r, n) -> {
                    FamilyTransportResponse.LatestTrip latest = r.getObject("trip_id", UUID.class) == null ? null
                            : new FamilyTransportResponse.LatestTrip(
                                    r.getObject("trip_id", UUID.class),
                                    r.getObject("service_date", LocalDate.class), r.getString("trip_status"),
                                    r.getTimestamp("trip_updated_at").toInstant(), r.getString("learner_status"),
                                    r.getTimestamp("learner_status_at") == null ? null
                                            : r.getTimestamp("learner_status_at").toInstant());
                    return new FamilyTransportResponse.JourneyItem(r.getString("journey_kind"),
                            r.getString("route_name"), r.getString("service_label"),
                            r.getString("operating_days"), r.getString("family_label_snapshot"),
                            r.getObject("scheduled_time", java.time.LocalTime.class),
                            r.getInt("boarding_window_minutes"), r.getString("vehicle_label"),
                            r.getString("registration_number"), r.getObject("starts_on", LocalDate.class),
                            r.getObject("ends_on", LocalDate.class), latest);
                });
        return new FamilyTransportResponse(learnerId, journeys);
    }

    record FamilyContext(UUID learnerId, UUID schoolId, UUID campusId, String timeZone) {}

    private static ApiException childNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "FAMILY_CHILD_NOT_FOUND",
                "This learner is not available to your verified relationship.");
    }
}

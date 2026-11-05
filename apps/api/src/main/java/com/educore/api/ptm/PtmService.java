package com.educore.api.ptm;

import static com.educore.api.ptm.FamilyPtmController.*;
import static com.educore.api.ptm.PtmController.*;
import static com.educore.api.ptm.PtmModels.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PtmService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PtmService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit,
            ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Workspace workspace(AuthenticatedUser user, UUID schoolId) {
        StaffScope scope = staffScope(user, schoolId, false);
        MapSqlParameterSource params = scope.params();
        List<SessionOption> sessions = scope.canManage() ? jdbc.query("""
                SELECT id,name,starts_on,ends_on,status FROM academic_sessions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status IN('PLANNED','ACTIVE') ORDER BY starts_on DESC,id
                """, params, (r, n) -> new SessionOption(r.getObject("id", UUID.class), r.getString("name"),
                r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class),
                r.getString("status"))) : List.of();
        List<SectionOption> sections = scope.canManage() ? jdbc.query("""
                SELECT section.id,section.academic_session_id,section.grade_code,section.display_name,campus.time_zone
                FROM class_sections section JOIN campuses campus ON campus.id=section.campus_id
                  AND campus.organisation_id=section.organisation_id AND campus.school_id=section.school_id
                WHERE section.organisation_id=:organisationId AND section.school_id=:schoolId AND section.status='ACTIVE'
                ORDER BY section.academic_session_id,section.grade_code,section.display_name,section.id
                """, params, (r, n) -> new SectionOption(r.getObject("id", UUID.class),
                r.getObject("academic_session_id", UUID.class), r.getString("grade_code"),
                r.getString("display_name"), r.getString("time_zone"))) : List.of();
        List<TeacherOption> teachers = scope.canManage() ? teachers(scope) : List.of();
        return new Workspace(scope.schoolId(), scope.schoolName(), scope.canManage(),
                scope.selfTeacherMembershipId(), sessions, sections, teachers, staffEvents(scope));
    }

    @Transactional(readOnly = true)
    public FamilySchedule familySchedule(AuthenticatedUser user, UUID learnerId) {
        FamilyScope scope = familyScope(user, learnerId);
        List<EventRow> events = jdbc.query("""
                SELECT event.* FROM ptm_events event
                WHERE event.organisation_id=:organisationId AND event.school_id=:schoolId
                  AND (event.status='PUBLISHED' OR (event.status='CANCELLED' AND EXISTS(
                    SELECT 1 FROM ptm_bookings booking WHERE booking.ptm_event_id=event.id
                      AND booking.learner_id=:learnerId AND booking.guardian_id=:guardianId)))
                  AND event.event_date>=(CURRENT_TIMESTAMP AT TIME ZONE event.time_zone_snapshot)::date
                  AND EXISTS(SELECT 1 FROM student_section_placements placement
                    JOIN student_enrolments enrolment ON enrolment.id=placement.enrolment_id
                      AND enrolment.organisation_id=placement.organisation_id
                      AND enrolment.school_id=placement.school_id
                    WHERE placement.organisation_id=event.organisation_id
                      AND placement.school_id=event.school_id AND placement.learner_id=:learnerId
                      AND placement.class_section_id=event.class_section_id AND placement.status='ACTIVE'
                      AND placement.starts_on<=event.event_date
                      AND (placement.ends_on IS NULL OR placement.ends_on>=event.event_date)
                      AND enrolment.status='ACTIVE'
                      AND enrolment.academic_session_id=event.academic_session_id)
                ORDER BY event.event_date,event.title_en,event.id
                """, scope.params(), PtmService::eventRow);
        return new FamilySchedule(scope.learnerId(), scope.learnerName(), events.stream()
                .map(event -> familyEvent(scope, event)).toList());
    }

    @Transactional
    public Mutation create(AuthenticatedUser user, CreateEventRequest request) {
        StaffScope scope = staffScope(user, request.schoolId(), true);
        EventContext context = eventContext(scope, request.academicSessionId(), request.classSectionId(),
                request.teacherMembershipId());
        String titleEn = text(request.titleEn(), 3, 160, "title");
        String titleHi = optionalText(request.titleHi(), 3, 160, "Hindi title");
        String instructionsEn = optionalText(request.instructionsEn(), 3, 1200, "instructions");
        String instructionsHi = optionalText(request.instructionsHi(), 3, 1200, "Hindi instructions");
        String venue = text(request.venue(), 2, 160, "venue");
        String key = text(request.idempotencyKey(), 8, 100, "idempotency key");
        validateWindows(request, context.timeZone(), context.sessionStarts(), context.sessionEnds());
        String fingerprint = hash(String.join("|", request.academicSessionId().toString(),
                request.classSectionId().toString(), request.teacherMembershipId().toString(), titleEn,
                Objects.toString(titleHi, ""), Objects.toString(instructionsEn, ""),
                Objects.toString(instructionsHi, ""), venue, request.eventDate().toString(),
                request.bookingOpensAt().toString(), request.bookingClosesAt().toString(),
                request.changesCloseAt().toString(), Integer.toString(request.reminderMinutesBefore()),
                request.firstSlotStartsAt().toString(), Integer.toString(request.slotDurationMinutes()),
                Integer.toString(request.gapMinutes()), Integer.toString(request.slotCount())));
        lock("ptm-create:" + scope.schoolId() + ":" + scope.actorMembershipId() + ":" + key);
        List<Retry> retries = jdbc.query("""
                SELECT id,request_fingerprint,row_version,status FROM ptm_events
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND created_by_membership_id=:actorMembershipId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (r, n) -> new Retry(
                r.getObject("id", UUID.class), r.getString("request_fingerprint"),
                r.getLong("row_version"), r.getString("status")));
        if (!retries.isEmpty()) {
            Retry retry = retries.getFirst();
            if (!retry.fingerprint().equals(fingerprint)) throw conflict("PTM_RETRY_MISMATCH",
                    "That retry key was already used for a different meeting schedule.");
            return new Mutation(retry.id(), retry.version(), retry.status());
        }
        Instant now = Instant.now(clock);
        UUID eventId = UUID.randomUUID();
        MapSqlParameterSource params = scope.params().addValue("id", eventId)
                .addValue("sessionId", request.academicSessionId()).addValue("sectionId", request.classSectionId())
                .addValue("teacherMembershipId", request.teacherMembershipId())
                .addValue("teacherStaffId", context.teacherStaffId()).addValue("teacherName", context.teacherName())
                .addValue("className", context.className()).addValue("titleEn", titleEn)
                .addValue("titleHi", titleHi).addValue("instructionsEn", instructionsEn)
                .addValue("instructionsHi", instructionsHi).addValue("venue", venue)
                .addValue("timeZone", context.timeZone()).addValue("eventDate", request.eventDate())
                .addValue("bookingOpensAt", request.bookingOpensAt())
                .addValue("bookingClosesAt", request.bookingClosesAt())
                .addValue("changesCloseAt", request.changesCloseAt())
                .addValue("reminderMinutes", request.reminderMinutesBefore())
                .addValue("key", key).addValue("fingerprint", fingerprint).addValue("now", now);
        jdbc.update("""
                INSERT INTO ptm_events(id,organisation_id,school_id,academic_session_id,class_section_id,
                  teacher_membership_id,teacher_staff_profile_id,teacher_name_snapshot,class_name_snapshot,
                  title_en,title_hi,instructions_en,instructions_hi,venue,time_zone_snapshot,event_date,
                  booking_opens_at,booking_closes_at,changes_close_at,reminder_minutes_before,status,
                  idempotency_key,request_fingerprint,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:sessionId,:sectionId,:teacherMembershipId,:teacherStaffId,
                  :teacherName,:className,:titleEn,:titleHi,:instructionsEn,:instructionsHi,:venue,:timeZone,
                  :eventDate,:bookingOpensAt,:bookingClosesAt,:changesCloseAt,:reminderMinutes,'DRAFT',:key,
                  :fingerprint,:actorMembershipId,:now,:now,0)
                """, params);
        Duration step = Duration.ofMinutes((long) request.slotDurationMinutes() + request.gapMinutes());
        for (int index = 0; index < request.slotCount(); index++) {
            Instant startsAt = request.firstSlotStartsAt().plus(step.multipliedBy(index));
            Instant endsAt = startsAt.plus(Duration.ofMinutes(request.slotDurationMinutes()));
            jdbc.update("""
                    INSERT INTO ptm_slots(id,organisation_id,school_id,ptm_event_id,starts_at,ends_at,
                      status,created_at,updated_at,row_version)
                    VALUES(:slotId,:organisationId,:schoolId,:id,:startsAt,:endsAt,'AVAILABLE',:now,:now,0)
                    """, params.addValue("slotId", UUID.randomUUID()).addValue("startsAt", startsAt)
                    .addValue("endsAt", endsAt));
        }
        audit(user, scope, "ptm.event.created", "PTM_EVENT", eventId, Map.of("slotCount", request.slotCount()));
        return new Mutation(eventId, 0, "DRAFT");
    }

    @Transactional
    public Mutation publish(AuthenticatedUser user, UUID eventId, EventDecisionRequest request) {
        StaffScope scope = staffScope(user, request.schoolId(), true);
        EventRow event = event(scope.params(), eventId, true);
        requireVersion(event.version(), request.expectedVersion());
        if (!"DRAFT".equals(event.status())) throw conflict("PTM_EVENT_NOT_DRAFT",
                "Only a draft meeting schedule can be published.");
        String reason = text(request.reason(), 3, 500, "publication reason");
        Instant now = Instant.now(clock);
        try {
            changed(jdbc.update("""
                    UPDATE ptm_events SET status='PUBLISHED',published_by_membership_id=:actorMembershipId,
                      published_at=:now,publication_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='DRAFT' AND row_version=:version
                    """, scope.params().addValue("id", eventId).addValue("now", now)
                    .addValue("reason", reason).addValue("version", event.version())));
        } catch (DataAccessException exception) {
            if (!isConstraintFailure(exception)) throw exception;
            throw conflict("PTM_EVENT_PUBLICATION_INVALID",
                    "The schedule overlaps another meeting or its teacher, class, session, or slots are no longer valid.");
        }
        outbox(eventId, "ptm.event_published", scope.organisationId(), scope.schoolId(),
                Map.of("eventId", eventId, "schoolId", scope.schoolId(), "sectionId", event.sectionId()), now);
        audit(user, scope, "ptm.event.published", "PTM_EVENT", eventId, Map.of());
        return new Mutation(eventId, event.version() + 1, "PUBLISHED");
    }

    @Transactional
    public Mutation cancelEvent(AuthenticatedUser user, UUID eventId, EventDecisionRequest request) {
        StaffScope scope = staffScope(user, request.schoolId(), true);
        EventRow event = event(scope.params(), eventId, true);
        requireVersion(event.version(), request.expectedVersion());
        if (!"PUBLISHED".equals(event.status())) throw conflict("PTM_EVENT_NOT_PUBLISHED",
                "Only a published meeting schedule can be cancelled.");
        String reason = text(request.reason(), 3, 500, "cancellation reason");
        Instant now = Instant.now(clock);
        changed(jdbc.update("""
                UPDATE ptm_events SET status='CANCELLED',cancelled_by_membership_id=:actorMembershipId,
                  cancelled_at=:now,cancellation_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PUBLISHED' AND row_version=:version
                """, scope.params().addValue("id", eventId).addValue("now", now)
                .addValue("reason", reason).addValue("version", event.version())));
        List<BookingRow> bookings = bookingRows(scope.params(),
                "booking.ptm_event_id=:eventId AND booking.status='BOOKED'",
                new MapSqlParameterSource().addValue("eventId", eventId), true);
        for (BookingRow booking : bookings) {
            jdbc.update("""
                    UPDATE ptm_bookings SET status='CANCELLED',cancelled_by_membership_id=:actorMembershipId,
                      cancelled_at=:now,cancellation_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE id=:bookingId AND status='BOOKED'
                    """, scope.params().addValue("bookingId", booking.id()).addValue("now", now)
                    .addValue("reason", reason));
            cancelReminders(scope.params(), booking.id(), now);
            history(scope, booking.id(), "EVENT_CANCELLED", booking.slotId(), null, reason, now);
        }
        jdbc.update("""
                UPDATE ptm_slots SET status='CANCELLED',updated_at=:now,row_version=row_version+1
                WHERE ptm_event_id=:eventId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='AVAILABLE'
                """, scope.params().addValue("eventId", eventId).addValue("now", now));
        outbox(eventId, "ptm.event_cancelled", scope.organisationId(), scope.schoolId(),
                Map.of("eventId", eventId, "schoolId", scope.schoolId(), "sectionId", event.sectionId()), now);
        audit(user, scope, "ptm.event.cancelled", "PTM_EVENT", eventId,
                Map.of("cancelledBookingCount", bookings.size()));
        return new Mutation(eventId, event.version() + 1, "CANCELLED");
    }

    @Transactional
    public Mutation book(AuthenticatedUser user, UUID learnerId, UUID slotId, BookRequest request) {
        FamilyScope scope = familyScope(user, learnerId);
        String topic = optionalText(request.topic(), 3, 500, "meeting topic");
        String key = text(request.idempotencyKey(), 8, 100, "idempotency key");
        String fingerprint = hash(learnerId + "|" + slotId + "|" + Objects.toString(topic, ""));
        lock("ptm-book:" + scope.schoolId() + ":" + scope.actorMembershipId() + ":" + key);
        List<Retry> retries = jdbc.query("""
                SELECT id,request_fingerprint,row_version,status FROM ptm_bookings
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND booked_by_membership_id=:actorMembershipId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (r, n) -> new Retry(
                r.getObject("id", UUID.class), r.getString("request_fingerprint"),
                r.getLong("row_version"), r.getString("status")));
        if (!retries.isEmpty()) {
            Retry retry = retries.getFirst();
            if (!retry.fingerprint().equals(fingerprint)) throw conflict("PTM_RETRY_MISMATCH",
                    "That retry key was already used for another appointment.");
            return new Mutation(retry.id(), retry.version(), retry.status());
        }
        SlotContext slot = familySlot(scope, slotId, true);
        UUID bookingId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        try {
            jdbc.update("""
                    INSERT INTO ptm_bookings(id,organisation_id,school_id,ptm_event_id,ptm_slot_id,learner_id,
                      guardian_id,booked_by_membership_id,topic,status,idempotency_key,request_fingerprint,
                      booked_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:eventId,:slotId,:learnerId,:guardianId,
                      :actorMembershipId,:topic,'BOOKED',:key,:fingerprint,:now,:now,0)
                    """, scope.params().addValue("id", bookingId).addValue("eventId", slot.eventId())
                    .addValue("slotId", slot.id()).addValue("topic", topic).addValue("key", key)
                    .addValue("fingerprint", fingerprint).addValue("now", now));
        } catch (DataAccessException exception) {
            if (!isConstraintFailure(exception)) throw exception;
            throw conflict("PTM_SLOT_UNAVAILABLE",
                    "This appointment cannot be booked. Refresh to choose another available time.");
        }
        scheduleReminder(scope.params(), bookingId, slot.id(), slot.startsAt(), slot.reminderMinutes(), now);
        history(scope, bookingId, "BOOKED", null, slot.id(), "Appointment booked", now);
        outbox(bookingId, "ptm.booking_created", scope.organisationId(), scope.schoolId(),
                Map.of("bookingId", bookingId, "eventId", slot.eventId(), "learnerId", learnerId), now);
        audit(user, scope, "ptm.booking.created", "PTM_BOOKING", bookingId, Map.of("eventId", slot.eventId()));
        return new Mutation(bookingId, 0, "BOOKED");
    }

    @Transactional
    public Mutation reschedule(AuthenticatedUser user, UUID learnerId, UUID bookingId,
            RescheduleRequest request) {
        FamilyScope scope = familyScope(user, learnerId);
        BookingRow booking = familyBooking(scope, bookingId, true);
        requireVersion(booking.version(), request.expectedVersion());
        requireChangeOpen(booking);
        if (booking.slotId().equals(request.slotId())) throw conflict("PTM_SLOT_UNCHANGED",
                "Choose a different appointment time.");
        SlotContext next = familySlot(scope, request.slotId(), true);
        if (!next.eventId().equals(booking.eventId())) throw bad("PTM_SLOT_EVENT_MISMATCH",
                "Choose a time from the same meeting schedule.");
        String reason = text(request.reason(), 3, 500, "reschedule reason");
        Instant now = Instant.now(clock);
        try {
            changed(jdbc.update("""
                    UPDATE ptm_bookings SET ptm_slot_id=:slotId,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='BOOKED' AND row_version=:version
                    """, scope.params().addValue("id", bookingId).addValue("slotId", next.id())
                    .addValue("now", now).addValue("version", booking.version())));
        } catch (DataAccessException exception) {
            if (!isConstraintFailure(exception)) throw exception;
            throw conflict("PTM_SLOT_UNAVAILABLE",
                    "This appointment cannot be selected. Refresh to choose another available time.");
        }
        cancelReminders(scope.params(), bookingId, now);
        scheduleReminder(scope.params(), bookingId, next.id(), next.startsAt(), next.reminderMinutes(), now);
        history(scope, bookingId, "RESCHEDULED", booking.slotId(), next.id(), reason, now);
        outbox(bookingId, "ptm.booking_rescheduled", scope.organisationId(), scope.schoolId(),
                Map.of("bookingId", bookingId, "eventId", booking.eventId(), "learnerId", learnerId), now);
        audit(user, scope, "ptm.booking.rescheduled", "PTM_BOOKING", bookingId,
                Map.of("eventId", booking.eventId()));
        return new Mutation(bookingId, booking.version() + 1, "BOOKED");
    }

    @Transactional
    public Mutation cancelBooking(AuthenticatedUser user, UUID learnerId, UUID bookingId,
            CancelBookingRequest request) {
        FamilyScope scope = familyScope(user, learnerId);
        BookingRow booking = familyBooking(scope, bookingId, true);
        requireVersion(booking.version(), request.expectedVersion());
        requireChangeOpen(booking);
        String reason = text(request.reason(), 3, 500, "cancellation reason");
        Instant now = Instant.now(clock);
        changed(jdbc.update("""
                UPDATE ptm_bookings SET status='CANCELLED',cancelled_by_membership_id=:actorMembershipId,
                  cancelled_at=:now,cancellation_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='BOOKED' AND row_version=:version
                """, scope.params().addValue("id", bookingId).addValue("now", now)
                .addValue("reason", reason).addValue("version", booking.version())));
        cancelReminders(scope.params(), bookingId, now);
        history(scope, bookingId, "CANCELLED", booking.slotId(), null, reason, now);
        outbox(bookingId, "ptm.booking_cancelled", scope.organisationId(), scope.schoolId(),
                Map.of("bookingId", bookingId, "eventId", booking.eventId(), "learnerId", learnerId), now);
        audit(user, scope, "ptm.booking.cancelled", "PTM_BOOKING", bookingId,
                Map.of("eventId", booking.eventId()));
        return new Mutation(bookingId, booking.version() + 1, "CANCELLED");
    }

    private FamilyEvent familyEvent(FamilyScope scope, EventRow event) {
        MapSqlParameterSource params = scope.params().addValue("eventId", event.id());
        List<FamilySlot> slots = jdbc.query("""
                SELECT slot.id,slot.starts_at,slot.ends_at,slot.status,
                  EXISTS(SELECT 1 FROM ptm_bookings booking WHERE booking.ptm_slot_id=slot.id
                    AND booking.status='BOOKED') booked,
                  EXISTS(SELECT 1 FROM ptm_bookings booking WHERE booking.ptm_slot_id=slot.id
                    AND booking.learner_id=:learnerId AND booking.guardian_id=:guardianId
                    AND booking.status='BOOKED') selected
                FROM ptm_slots slot WHERE slot.ptm_event_id=:eventId
                  AND slot.organisation_id=:organisationId AND slot.school_id=:schoolId
                  AND slot.starts_at>CURRENT_TIMESTAMP ORDER BY slot.starts_at,slot.id
                """, params, (r, n) -> new FamilySlot(r.getObject("id", UUID.class), instant(r, "starts_at"),
                instant(r, "ends_at"), "AVAILABLE".equals(r.getString("status")) && !r.getBoolean("booked"),
                r.getBoolean("selected")));
        List<FamilyBooking> bookings = jdbc.query("""
                SELECT id,ptm_slot_id,status,topic,row_version FROM ptm_bookings
                WHERE ptm_event_id=:eventId AND learner_id=:learnerId AND guardian_id=:guardianId
                ORDER BY CASE WHEN status='BOOKED' THEN 0 ELSE 1 END,booked_at DESC,id DESC LIMIT 1
                """, params, (r, n) -> new FamilyBooking(r.getObject("id", UUID.class),
                r.getObject("ptm_slot_id", UUID.class), r.getString("status"), r.getString("topic"),
                r.getLong("row_version")));
        return new FamilyEvent(event.id(), event.titleEn(), event.titleHi(), event.instructionsEn(),
                event.instructionsHi(), event.venue(), event.teacherName(), event.className(), event.eventDate(),
                event.bookingOpensAt(), event.bookingClosesAt(), event.changesCloseAt(), slots,
                bookings.isEmpty() ? null : bookings.getFirst(), event.timeZone(), event.status());
    }

    private List<StaffEvent> staffEvents(StaffScope scope) {
        List<EventRow> events = jdbc.query("""
                SELECT event.* FROM ptm_events event
                WHERE event.organisation_id=:organisationId AND event.school_id=:schoolId
                  AND (:canManage OR event.teacher_membership_id=:teacherMembershipId)
                ORDER BY event.event_date DESC,event.created_at DESC,event.id DESC LIMIT 100
                """, scope.params().addValue("canManage", scope.canManage())
                .addValue("teacherMembershipId", scope.selfTeacherMembershipId()), PtmService::eventRow);
        return events.stream().map(event -> new StaffEvent(event.id(), event.sessionId(), event.sectionId(),
                event.teacherMembershipId(), event.titleEn(), event.titleHi(), event.venue(),
                event.teacherName(), event.className(), event.eventDate(), event.bookingOpensAt(),
                event.bookingClosesAt(), event.changesCloseAt(), event.reminderMinutes(), event.status(),
                staffSlots(scope, event.id()), event.version(), event.timeZone())).toList();
    }

    private List<StaffSlot> staffSlots(StaffScope scope, UUID eventId) {
        MapSqlParameterSource params = scope.params().addValue("eventId", eventId);
        Map<UUID, StaffBooking> bookingBySlot = new HashMap<>();
        for (BookingRow booking : bookingRows(params, "booking.ptm_event_id=:eventId AND booking.status='BOOKED'",
                new MapSqlParameterSource(), false)) {
            bookingBySlot.put(booking.slotId(), new StaffBooking(booking.id(), booking.learnerId(),
                    booking.learnerName(), booking.guardianName(), booking.topic(), booking.status(),
                    booking.version()));
        }
        return jdbc.query("""
                SELECT id,starts_at,ends_at,status FROM ptm_slots
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND ptm_event_id=:eventId
                ORDER BY starts_at,id
                """, params, (r, n) -> {
            UUID id = r.getObject("id", UUID.class);
            return new StaffSlot(id, instant(r, "starts_at"), instant(r, "ends_at"),
                    r.getString("status"), bookingBySlot.get(id));
        });
    }


    private FamilyScope familyScope(AuthenticatedUser user, UUID learnerId) {
        requireOrganisation(user);
        List<FamilyScope> rows = jdbc.query("""
                SELECT guardian.id guardian_id,guardian.display_name guardian_name,
                  learner.school_id,learner.legal_name learner_name,membership.id membership_id
                FROM guardians guardian
                JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
                  AND relationship.organisation_id=guardian.organisation_id
                  AND relationship.school_id=guardian.school_id AND relationship.portal_access_status='ACTIVE'
                JOIN learners learner ON learner.id=relationship.learner_id
                  AND learner.organisation_id=relationship.organisation_id
                  AND learner.school_id=relationship.school_id AND learner.status='ACTIVE'
                JOIN memberships membership ON membership.user_id=guardian.user_account_id
                  AND membership.organisation_id=guardian.organisation_id AND membership.school_id=guardian.school_id
                WHERE guardian.user_account_id=:userId AND guardian.organisation_id=:organisationId
                  AND guardian.status='ACTIVE' AND learner.id=:learnerId AND membership.id IN(:membershipIds)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='portal.guardian')
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, userParams(user).addValue("learnerId", learnerId), (r, n) -> new FamilyScope(
                user.activeOrganisationId(), r.getObject("school_id", UUID.class), learnerId,
                r.getObject("guardian_id", UUID.class), r.getString("learner_name"),
                r.getString("guardian_name"), r.getObject("membership_id", UUID.class)));
        if (rows.isEmpty()) throw notFound("FAMILY_CHILD_NOT_FOUND",
                "This learner is not available to your verified relationship.");
        return rows.getFirst();
    }

    private StaffScope staffScope(AuthenticatedUser user, UUID schoolId, boolean manage) {
        requireOrganisation(user);
        List<StaffScope> rows = jdbc.query("""
                SELECT school.id school_id,school.display_name school_name,membership.id membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='ptm.manage') can_manage
                FROM memberships membership JOIN schools school ON school.id=membership.school_id
                  AND school.organisation_id=membership.organisation_id AND school.status='ACTIVE'
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds) AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code IN(:capabilities))
                ORDER BY can_manage DESC,membership.created_at,membership.id LIMIT 1
                """, userParams(user).addValue("schoolId", schoolId).addValue("capabilities",
                manage ? Set.of("ptm.manage") : Set.of("ptm.read", "ptm.manage")), (r, n) -> new StaffScope(
                user.activeOrganisationId(), r.getObject("school_id", UUID.class), r.getString("school_name"),
                r.getObject("membership_id", UUID.class), r.getObject("membership_id", UUID.class),
                r.getBoolean("can_manage")));
        if (rows.isEmpty()) throw forbidden("PTM_SCOPE_DENIED", "Meeting access is unavailable for this school.");
        return rows.getFirst();
    }

    private List<TeacherOption> teachers(StaffScope scope) {
        return jdbc.query("""
                SELECT DISTINCT membership.id,profile.id profile_id,profile.employee_code,profile.display_name
                FROM memberships membership JOIN staff_profiles profile
                  ON profile.user_account_id=membership.user_id AND profile.organisation_id=membership.organisation_id
                  AND profile.school_id=membership.school_id AND profile.status='ACTIVE'
                JOIN staff_assignments assignment ON assignment.staff_profile_id=profile.id
                  AND assignment.organisation_id=profile.organisation_id AND assignment.school_id=profile.school_id
                  AND assignment.status='ACTIVE'
                JOIN staff_designations designation ON designation.id=assignment.staff_designation_id
                  AND designation.organisation_id=assignment.organisation_id AND designation.school_id=assignment.school_id
                  AND designation.status='ACTIVE' AND designation.teaching_role
                WHERE membership.organisation_id=:organisationId AND membership.school_id=:schoolId
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    WHERE mr.membership_id=membership.id AND role.code='teacher'
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id))
                ORDER BY profile.display_name,membership.id,profile.id
                """, scope.params(), (r, n) -> new TeacherOption(r.getObject("id", UUID.class),
                r.getObject("profile_id", UUID.class), r.getString("employee_code"), r.getString("display_name")));
    }

    private EventContext eventContext(StaffScope scope, UUID sessionId, UUID sectionId, UUID teacherId) {
        TeacherOption teacher = teachers(scope).stream().filter(item -> item.membershipId().equals(teacherId))
                .findFirst().orElseThrow(() -> bad("PTM_TEACHER_INVALID", "Choose an active teaching staff account."));
        List<EventContext> rows = jdbc.query("""
                SELECT section.display_name,campus.time_zone,session.starts_on,session.ends_on
                FROM class_sections section JOIN academic_sessions session ON session.id=section.academic_session_id
                  AND session.organisation_id=section.organisation_id AND session.school_id=section.school_id
                JOIN campuses campus ON campus.id=section.campus_id AND campus.organisation_id=section.organisation_id
                  AND campus.school_id=section.school_id AND campus.status='ACTIVE'
                WHERE section.id=:sectionId AND section.academic_session_id=:sessionId
                  AND section.organisation_id=:organisationId AND section.school_id=:schoolId
                  AND section.status='ACTIVE' AND session.status IN('PLANNED','ACTIVE')
                """, scope.params().addValue("sessionId", sessionId).addValue("sectionId", sectionId),
                (r, n) -> new EventContext(teacher.staffProfileId(), teacher.name(), r.getString("display_name"),
                        r.getString("time_zone"), r.getObject("starts_on", LocalDate.class),
                        r.getObject("ends_on", LocalDate.class)));
        if (rows.isEmpty()) throw bad("PTM_ACADEMIC_CONTEXT_INVALID", "Choose an active class and academic session.");
        return rows.getFirst();
    }

    private EventRow event(MapSqlParameterSource params, UUID id, boolean forUpdate) {
        List<EventRow> rows = jdbc.query("""
                SELECT event.* FROM ptm_events event WHERE event.id=:eventId
                  AND event.organisation_id=:organisationId AND event.school_id=:schoolId
                """ + (forUpdate ? " FOR UPDATE OF event" : ""), params.addValue("eventId", id), PtmService::eventRow);
        if (rows.isEmpty()) throw notFound("PTM_EVENT_NOT_FOUND", "The meeting schedule was not found.");
        return rows.getFirst();
    }

    private SlotContext familySlot(FamilyScope scope, UUID slotId, boolean forUpdate) {
        List<SlotContext> rows = jdbc.query("""
                SELECT slot.id,slot.ptm_event_id,slot.starts_at,event.reminder_minutes_before
                FROM ptm_slots slot JOIN ptm_events event ON event.id=slot.ptm_event_id
                  AND event.organisation_id=slot.organisation_id AND event.school_id=slot.school_id
                WHERE slot.id=:slotId AND slot.organisation_id=:organisationId AND slot.school_id=:schoolId
                  AND event.status='PUBLISHED' AND slot.status='AVAILABLE'
                  AND EXISTS(SELECT 1 FROM student_section_placements placement JOIN student_enrolments enrolment
                    ON enrolment.id=placement.enrolment_id AND enrolment.organisation_id=placement.organisation_id
                    AND enrolment.school_id=placement.school_id
                    WHERE placement.organisation_id=event.organisation_id AND placement.school_id=event.school_id
                      AND placement.learner_id=:learnerId AND placement.class_section_id=event.class_section_id
                      AND placement.status='ACTIVE' AND placement.starts_on<=event.event_date
                      AND (placement.ends_on IS NULL OR placement.ends_on>=event.event_date)
                      AND enrolment.status='ACTIVE' AND enrolment.academic_session_id=event.academic_session_id)
                """ + (forUpdate ? " FOR UPDATE OF event,slot" : ""), scope.params().addValue("slotId", slotId),
                (r, n) -> new SlotContext(r.getObject("id", UUID.class), r.getObject("ptm_event_id", UUID.class),
                        instant(r, "starts_at"), r.getInt("reminder_minutes_before")));
        if (rows.isEmpty()) throw notFound("PTM_SLOT_NOT_FOUND", "This appointment is unavailable for this learner.");
        return rows.getFirst();
    }

    private BookingRow familyBooking(FamilyScope scope, UUID bookingId, boolean forUpdate) {
        // Lock the event before its booking, matching management cancellation.
        List<UUID> events = jdbc.query("""
                SELECT ptm_event_id FROM ptm_bookings WHERE id=:bookingId
                  AND organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_id=:learnerId AND guardian_id=:guardianId
                """, scope.params().addValue("bookingId", bookingId), (r, n) -> r.getObject(1, UUID.class));
        if (events.isEmpty()) throw notFound("PTM_BOOKING_NOT_FOUND", "The appointment was not found.");
        if (forUpdate) event(scope.params(), events.getFirst(), true);
        List<BookingRow> rows = bookingRows(scope.params(),
                "booking.id=:bookingId AND booking.learner_id=:learnerId AND booking.guardian_id=:guardianId",
                new MapSqlParameterSource().addValue("bookingId", bookingId), forUpdate);
        if (rows.isEmpty()) throw notFound("PTM_BOOKING_NOT_FOUND", "The appointment was not found.");
        return rows.getFirst();
    }

    private List<BookingRow> bookingRows(MapSqlParameterSource base, String predicate,
            MapSqlParameterSource extra, boolean forUpdate) {
        return jdbc.query("""
                SELECT booking.*,learner.legal_name learner_name,guardian.display_name guardian_name,
                  event.status event_status,event.changes_close_at,slot.starts_at
                FROM ptm_bookings booking JOIN ptm_events event ON event.id=booking.ptm_event_id
                  AND event.organisation_id=booking.organisation_id AND event.school_id=booking.school_id
                JOIN ptm_slots slot ON slot.id=booking.ptm_slot_id
                  AND slot.organisation_id=booking.organisation_id AND slot.school_id=booking.school_id
                JOIN learners learner ON learner.id=booking.learner_id
                  AND learner.organisation_id=booking.organisation_id AND learner.school_id=booking.school_id
                JOIN guardians guardian ON guardian.id=booking.guardian_id
                  AND guardian.organisation_id=booking.organisation_id AND guardian.school_id=booking.school_id
                WHERE booking.organisation_id=:organisationId AND booking.school_id=:schoolId AND
                """ + predicate + " ORDER BY booking.id" + (forUpdate ? " FOR UPDATE OF booking" : ""),
                new MapSqlParameterSource(base.getValues()).addValues(extra.getValues()), (r, n) -> new BookingRow(
                r.getObject("id", UUID.class), r.getObject("ptm_event_id", UUID.class),
                r.getObject("ptm_slot_id", UUID.class), r.getObject("learner_id", UUID.class),
                r.getString("learner_name"), r.getString("guardian_name"), r.getString("topic"),
                r.getString("status"), r.getLong("row_version"), r.getString("event_status"),
                instant(r, "changes_close_at"), instant(r, "starts_at")));
    }

    private void requireChangeOpen(BookingRow booking) {
        Instant now = Instant.now(clock);
        if (!"BOOKED".equals(booking.status()) || !"PUBLISHED".equals(booking.eventStatus())
                || now.isAfter(booking.changesCloseAt()) || !booking.startsAt().isAfter(now))
            throw conflict("PTM_CHANGES_CLOSED", "This appointment can no longer be changed by the family.");
    }

    static void validateWindows(CreateEventRequest request, String timeZone, LocalDate sessionStarts,
            LocalDate sessionEnds) {
        if (request.eventDate() == null || request.bookingOpensAt() == null || request.bookingClosesAt() == null
                || request.changesCloseAt() == null || request.firstSlotStartsAt() == null
                || request.slotCount() < 1 || request.slotCount() > 100 || request.slotDurationMinutes() < 5
                || request.slotDurationMinutes() > 120 || request.gapMinutes() < 0 || request.gapMinutes() > 60
                || request.reminderMinutesBefore() < 0 || request.reminderMinutesBefore() > 10080)
            throw bad("PTM_WINDOWS_INVALID", "Enter valid meeting windows and slot sizes.");
        ZoneId zone = ZoneId.of(timeZone);
        Instant lastEnd = request.firstSlotStartsAt().plusSeconds(60L *
                ((long) (request.slotCount() - 1) * (request.slotDurationMinutes() + request.gapMinutes())
                        + request.slotDurationMinutes()));
        if (request.eventDate().isBefore(sessionStarts) || request.eventDate().isAfter(sessionEnds)
                || !request.bookingOpensAt().isBefore(request.bookingClosesAt())
                || request.bookingClosesAt().isAfter(request.changesCloseAt())
                || request.changesCloseAt().isAfter(request.firstSlotStartsAt())
                || !request.firstSlotStartsAt().atZone(zone).toLocalDate().equals(request.eventDate())
                || !lastEnd.minusNanos(1).atZone(zone).toLocalDate().equals(request.eventDate()))
            throw bad("PTM_WINDOWS_INVALID", "Keep slots on the meeting date within the session and after the change deadline.");
    }

    private void scheduleReminder(MapSqlParameterSource base, UUID bookingId, UUID slotId,
            Instant startsAt, int minutes, Instant now) {
        if (minutes == 0) return;
        Instant dueAt = startsAt.minusSeconds(minutes * 60L);
        jdbc.update("""
                INSERT INTO ptm_booking_reminders(id,organisation_id,school_id,ptm_booking_id,ptm_slot_id,
                  due_at,next_attempt_at,status,attempt_count,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:bookingId,:slotId,:dueAt,:nextAt,'PENDING',0,:now,:now,0)
                """, new MapSqlParameterSource(base.getValues()).addValue("id", UUID.randomUUID())
                .addValue("bookingId", bookingId).addValue("slotId", slotId).addValue("dueAt", dueAt)
                .addValue("nextAt", dueAt.isBefore(now) ? now : dueAt).addValue("now", now));
    }

    private void cancelReminders(MapSqlParameterSource base, UUID bookingId, Instant now) {
        jdbc.update("""
                UPDATE ptm_booking_reminders SET status='CANCELLED',processed_at=:now,updated_at=:now,
                  row_version=row_version+1 WHERE ptm_booking_id=:bookingId
                  AND organisation_id=:organisationId AND school_id=:schoolId AND status='PENDING'
                """, new MapSqlParameterSource(base.getValues()).addValue("bookingId", bookingId).addValue("now", now));
    }

    private void history(Scope scope, UUID bookingId, String type, UUID previousSlot, UUID nextSlot,
            String reason, Instant now) {
        jdbc.update("""
                INSERT INTO ptm_booking_history(id,organisation_id,school_id,ptm_booking_id,event_type,
                  previous_slot_id,new_slot_id,reason,actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:bookingId,:type,:previousSlot,:nextSlot,:reason,:actorMembershipId,:now)
                """, scope.params().addValue("id", UUID.randomUUID()).addValue("bookingId", bookingId)
                .addValue("type", type).addValue("previousSlot", previousSlot).addValue("nextSlot", nextSlot)
                .addValue("reason", reason).addValue("now", now));
    }

    private void outbox(UUID aggregateId, String eventType, UUID organisationId, UUID schoolId,
            Map<String, ?> payload, Instant now) {
        Map<String, Object> scopedPayload = new HashMap<>(payload);
        scopedPayload.put("organisationId", organisationId);
        scopedPayload.put("schoolId", schoolId);
        try {
            jdbc.update("""
                    INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,headers,
                      occurred_at,available_at,attempt_count,created_at)
                    VALUES(:id,'PTM',:aggregateId,:eventType,CAST(:payload AS jsonb),'{}'::jsonb,:now,:now,0,:now)
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("aggregateId", aggregateId.toString()).addValue("eventType", eventType)
                    .addValue("payload", objectMapper.writeValueAsString(scopedPayload)).addValue("now", now));
        } catch (JacksonException exception) {
            throw new IllegalStateException("PTM outbox payload must be serializable", exception);
        }
    }

    private void audit(AuthenticatedUser user, Scope scope, String action, String type, UUID id, Map<String, ?> metadata) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type, id.toString(),
                AuditOutcome.SUCCEEDED, action.replace('.', ' '), metadata);
    }
    private void lock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",
                new MapSqlParameterSource("key", key), (r, n) -> 0);
    }
    private static EventRow eventRow(ResultSet r, int row) throws SQLException {
        return new EventRow(r.getObject("id", UUID.class), r.getObject("academic_session_id", UUID.class),
                r.getObject("class_section_id", UUID.class), r.getObject("teacher_membership_id", UUID.class),
                r.getString("title_en"), r.getString("title_hi"), r.getString("instructions_en"),
                r.getString("instructions_hi"), r.getString("venue"), r.getString("teacher_name_snapshot"),
                r.getString("class_name_snapshot"), r.getObject("event_date", LocalDate.class),
                instant(r, "booking_opens_at"), instant(r, "booking_closes_at"), instant(r, "changes_close_at"),
                r.getInt("reminder_minutes_before"), r.getString("status"), r.getLong("row_version"),
                r.getString("time_zone_snapshot"));
    }
    private static Instant instant(ResultSet r, String column) throws SQLException { return r.getTimestamp(column).toInstant(); }
    private static MapSqlParameterSource userParams(AuthenticatedUser user) {
        return new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId()).addValue("membershipIds", user.membershipIds());
    }
    private static void requireOrganisation(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null) throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        if (user.membershipIds().isEmpty()) throw forbidden("PTM_SCOPE_DENIED", "An active school membership is required.");
    }
    private static void requireVersion(long current, long expected) {
        if (current != expected) throw conflict("STALE_VERSION", "This meeting changed. Refresh before trying again.");
    }
    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION", "This meeting changed. Refresh before trying again.");
    }
    private static String text(String value, int min, int max, String field) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() < min || clean.length() > max) throw bad("PTM_TEXT_INVALID", "Enter a valid " + field + ".");
        return clean;
    }
    private static String optionalText(String value, int min, int max, String field) {
        return value == null || value.isBlank() ? null : text(value, min, max, field);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
    private static boolean isConstraintFailure(DataAccessException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (!(cause instanceof SQLException sql)) return false;
        return "P0001".equals(sql.getSQLState()) || (sql.getSQLState() != null && sql.getSQLState().startsWith("23"));
    }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException forbidden(String code, String message) { return new ApiException(HttpStatus.FORBIDDEN, code, message); }
    private static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private interface Scope {
        UUID organisationId();
        UUID schoolId();
        UUID actorMembershipId();
        default MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId())
                    .addValue("schoolId", schoolId()).addValue("actorMembershipId", actorMembershipId());
        }
    }
    private record StaffScope(UUID organisationId, UUID schoolId, String schoolName, UUID actorMembershipId,
            UUID selfTeacherMembershipId, boolean canManage) implements Scope {}
    private record FamilyScope(UUID organisationId, UUID schoolId, UUID learnerId, UUID guardianId,
            String learnerName, String guardianName, UUID actorMembershipId) implements Scope {
        @Override public MapSqlParameterSource params() {
            return Scope.super.params().addValue("learnerId", learnerId).addValue("guardianId", guardianId);
        }
    }
    private record Retry(UUID id, String fingerprint, long version, String status) {}
    private record EventContext(UUID teacherStaffId, String teacherName, String className, String timeZone,
            LocalDate sessionStarts, LocalDate sessionEnds) {}
    private record SlotContext(UUID id, UUID eventId, Instant startsAt, int reminderMinutes) {}
    private record BookingRow(UUID id, UUID eventId, UUID slotId, UUID learnerId, String learnerName,
            String guardianName, String topic, String status, long version, String eventStatus,
            Instant changesCloseAt, Instant startsAt) {}
    private record EventRow(UUID id, UUID sessionId, UUID sectionId, UUID teacherMembershipId,
            String titleEn, String titleHi, String instructionsEn, String instructionsHi, String venue,
            String teacherName, String className, LocalDate eventDate, Instant bookingOpensAt,
            Instant bookingClosesAt, Instant changesCloseAt, int reminderMinutes, String status, long version,
            String timeZone) {}
}

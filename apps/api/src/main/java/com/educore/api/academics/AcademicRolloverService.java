package com.educore.api.academics;

import static com.educore.api.academics.AcademicRolloverController.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AcademicRolloverService {

    private static final Set<String> OUTCOMES = Set.of("PROMOTE", "RETAIN", "LEAVE");
    private static final Set<String> LEAVE_STATUSES = Set.of("INACTIVE", "ALUMNI");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AcademicRolloverService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RolloverWorkspace workspace(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId);
        MapSqlParameterSource parameters = scope.parameters();
        return new RolloverWorkspace(
                scope.schoolId(),
                scope.schoolName(),
                jdbc.query("""
                        SELECT id,name,starts_on,ends_on,status,row_version
                        FROM academic_sessions
                        WHERE organisation_id=:organisationId AND school_id=:schoolId
                        ORDER BY starts_on DESC,id
                        """, parameters, (r, row) -> new RolloverSessionOption(
                        r.getObject("id", UUID.class), r.getString("name"),
                        r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class),
                        r.getString("status"), r.getLong("row_version"))),
                jdbc.query("""
                        SELECT id,campus_id,academic_session_id,grade_code,code,display_name,capacity,status
                        FROM class_sections
                        WHERE organisation_id=:organisationId AND school_id=:schoolId
                        ORDER BY academic_session_id,campus_id,grade_code,code,id
                        """, parameters, (r, row) -> new RolloverSectionOption(
                        r.getObject("id", UUID.class), r.getObject("campus_id", UUID.class),
                        r.getObject("academic_session_id", UUID.class), r.getString("grade_code"),
                        r.getString("code"), r.getString("display_name"),
                        nullableIntegerObject(r, "capacity"), r.getString("status"))),
                sourceStudents(scope, null),
                jdbc.query("""
                        SELECT batch.id,batch.source_session_id,source.name AS source_name,
                          batch.target_session_id,target.name AS target_name,batch.promoted_count,
                          batch.retained_count,batch.leaving_count,batch.executed_at,batch.status,batch.row_version
                        FROM academic_rollover_batches batch
                        JOIN academic_sessions source ON source.id=batch.source_session_id
                        JOIN academic_sessions target ON target.id=batch.target_session_id
                        WHERE batch.organisation_id=:organisationId AND batch.school_id=:schoolId
                        ORDER BY batch.executed_at DESC,batch.id DESC
                        LIMIT 25
                        """, parameters, (r, row) -> new RolloverBatchItem(
                        r.getObject("id", UUID.class), r.getObject("source_session_id", UUID.class),
                        r.getString("source_name"), r.getObject("target_session_id", UUID.class),
                        r.getString("target_name"), r.getInt("promoted_count"),
                        r.getInt("retained_count"), r.getInt("leaving_count"),
                        r.getTimestamp("executed_at").toInstant(), r.getString("status"),
                        r.getLong("row_version"))));
    }

    @Transactional(readOnly = true)
    public RolloverPreview preview(AuthenticatedUser user, RolloverPreviewRequest request) {
        Scope scope = requireScope(user, request.schoolId());
        return inspect(scope, request, false).preview();
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public RolloverExecution execute(AuthenticatedUser user, RolloverExecutionRequest request) {
        RolloverPreviewRequest input = request.preview();
        Scope scope = requireScope(user, input.schoolId());
        String requestedHash = lower(required(request.snapshotHash(), "snapshot hash"));
        StoredBatch retry = storedBatch(scope, clean(request.idempotencyKey()));
        if (retry != null) {
            if (!retry.snapshotHash().equals(requestedHash)) {
                throw conflict("ROLLOVER_IDEMPOTENCY_CONFLICT",
                        "This idempotency key already belongs to a different rollover preview.");
            }
            return retry.response();
        }

        PreviewState state = inspect(scope, input, true);
        if (!state.preview().snapshotHash().equals(requestedHash)) {
            throw conflict("ROLLOVER_PREVIEW_STALE",
                    "Academic data or rollover choices changed after preview. Review a fresh preview before execution.");
        }
        if (LocalDate.now(clock).isBefore(state.sourceSession().endsOn())) {
            throw conflict("ROLLOVER_SOURCE_SESSION_STILL_OPEN",
                    "The source session cannot be rolled over before its configured end date.");
        }

        try {
            return executeFresh(user, scope, request, state);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("ROLLOVER_CONFLICT",
                    "The rollover collided with another session, student, section, identifier, or setup change. Review a fresh preview.");
        }
    }

    private RolloverExecution executeFresh(
            AuthenticatedUser user,
            Scope scope,
            RolloverExecutionRequest request,
            PreviewState state) {
        Instant now = Instant.now(clock);
        UUID targetSessionId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        RolloverPreview preview = state.preview();
        RolloverCopyCounts counts = preview.counts();

        jdbc.update("""
                INSERT INTO academic_sessions(
                  id,organisation_id,school_id,name,starts_on,ends_on,status,created_at,updated_at,row_version,
                  rte_class_one_seat_limit,rte_min_age_years,rte_max_age_years)
                SELECT :id,:organisationId,:schoolId,:name,:startsOn,:endsOn,'PLANNED',:now,:now,0,
                    rte_class_one_seat_limit,rte_min_age_years,rte_max_age_years FROM academic_sessions
                WHERE id=:sourceSessionId AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.parameters().addValue("id", targetSessionId)
                .addValue("name", clean(request.preview().targetSessionName()))
                .addValue("sourceSessionId", state.sourceSession().id())
                .addValue("startsOn", request.preview().targetStartsOn())
                .addValue("endsOn", request.preview().targetEndsOn()).addValue("now", now));

        jdbc.update("""
                INSERT INTO academic_rollover_batches(
                  id,organisation_id,school_id,source_session_id,target_session_id,idempotency_key,
                  snapshot_hash,copy_teaching_allocations,copy_published_timetable,copy_fee_plans,
                  copy_calendar_policy,promoted_count,retained_count,leaving_count,section_count,
                  term_count,resource_count,offering_count,allocation_count,period_count,
                  timetable_plan_count,timetable_entry_count,fee_plan_count,fee_item_count,
                  calendar_policy_count,warnings,execution_reason,executed_by_membership_id,
                  executed_at,status,row_version)
                VALUES(:id,:organisationId,:schoolId,:sourceSessionId,:targetSessionId,:idempotencyKey,
                  :snapshotHash,:copyAllocations,:copyTimetable,:copyFees,:copyPolicy,:promoted,:retained,
                  :leaving,:sections,:terms,:resources,:offerings,:allocations,:periods,:plans,:entries,
                  :feePlans,:feeItems,:policies,CAST(:warnings AS jsonb),:reason,:actorMembershipId,
                  :now,'EXECUTED',0)
                """, scope.parameters().addValue("id", batchId)
                .addValue("sourceSessionId", request.preview().sourceSessionId())
                .addValue("targetSessionId", targetSessionId)
                .addValue("idempotencyKey", clean(request.idempotencyKey()))
                .addValue("snapshotHash", preview.snapshotHash())
                .addValue("copyAllocations", request.preview().copyTeachingAllocations())
                .addValue("copyTimetable", request.preview().copyPublishedTimetable())
                .addValue("copyFees", request.preview().copyFeePlans())
                .addValue("copyPolicy", request.preview().copyCalendarPolicy())
                .addValue("promoted", counts.promoted()).addValue("retained", counts.retained())
                .addValue("leaving", counts.leaving()).addValue("sections", counts.sections())
                .addValue("terms", counts.terms()).addValue("resources", counts.resources())
                .addValue("offerings", counts.offerings()).addValue("allocations", counts.allocations())
                .addValue("periods", counts.periods()).addValue("plans", counts.timetablePlans())
                .addValue("entries", counts.timetableEntries()).addValue("feePlans", counts.feePlans())
                .addValue("feeItems", counts.feeItems()).addValue("policies", counts.calendarPolicies())
                .addValue("warnings", warningsJson(preview.warnings()))
                .addValue("reason", required(request.reason(), "execution reason")).addValue("now", now));

        CopyState copy = copyAcademicConfiguration(
                scope, state, targetSessionId, batchId, required(request.reason(), "execution reason"), now);
        rolloverStudents(scope, state, copy, targetSessionId, batchId, now);

        int sourceChanged = jdbc.update("""
                UPDATE academic_sessions
                SET status='CLOSED',updated_at=:now,row_version=row_version+1
                WHERE id=:sourceSessionId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='ACTIVE' AND row_version=:sourceVersion
                """, scope.parameters().addValue("sourceSessionId", state.sourceSession().id())
                .addValue("sourceVersion", state.sourceSession().version()).addValue("now", now));
        if (sourceChanged != 1) throw conflict("ROLLOVER_SOURCE_CHANGED", "The source session changed during rollover.");

        int targetChanged = jdbc.update("""
                UPDATE academic_sessions SET status='ACTIVE',updated_at=:now,row_version=row_version+1
                WHERE id=:targetSessionId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PLANNED' AND row_version=0
                """, scope.parameters().addValue("targetSessionId", targetSessionId).addValue("now", now));
        if (targetChanged != 1) throw conflict("ROLLOVER_TARGET_CHANGED", "The target session changed during rollover.");

        audit.recordScoped(
                user, scope.actorMembershipId(), scope.schoolId(), "academic.rollover.executed",
                "ACADEMIC_ROLLOVER_BATCH", batchId.toString(), AuditOutcome.SUCCEEDED,
                required(request.reason(), "execution reason"),
                Map.ofEntries(
                        Map.entry("sourceSessionId", state.sourceSession().id().toString()),
                        Map.entry("targetSessionId", targetSessionId.toString()),
                        Map.entry("promoted", counts.promoted()),
                        Map.entry("retained", counts.retained()),
                        Map.entry("leaving", counts.leaving()),
                        Map.entry("snapshotHash", preview.snapshotHash())));

        return new RolloverExecution(
                batchId, state.sourceSession().id(), targetSessionId,
                request.preview().targetSessionName().strip(), "EXECUTED", preview.snapshotHash(),
                counts, preview.warnings(), now, 0);
    }

    private PreviewState inspect(Scope scope, RolloverPreviewRequest request, boolean lock) {
        validateTarget(request);
        SessionInfo source = sourceSession(scope, request.sourceSessionId(), lock);
        if (!"ACTIVE".equals(source.status())) {
            throw conflict("ROLLOVER_SOURCE_NOT_ACTIVE", "Choose the currently active academic session.");
        }
        if (!request.targetStartsOn().isAfter(source.endsOn())) {
            throw bad("ROLLOVER_TARGET_OVERLAPS_SOURCE",
                    "The target session must start after the source session ends.");
        }
        if (jdbc.queryForObject("""
                SELECT count(*) FROM academic_rollover_batches
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND source_session_id=:sourceSessionId
                """, scope.parameters().addValue("sourceSessionId", source.id()), Integer.class) > 0) {
            throw conflict("ROLLOVER_ALREADY_EXECUTED", "This source session already has an executed rollover.");
        }
        if (jdbc.queryForObject("""
                SELECT count(*) FROM academic_sessions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND lower(name)=lower(:targetName)
                """, scope.parameters().addValue("targetName", clean(request.targetSessionName())), Integer.class) > 0) {
            throw conflict("ROLLOVER_TARGET_SESSION_EXISTS", "A session with this target name already exists.");
        }
        if (jdbc.queryForObject("""
                SELECT count(*) FROM timetable_plans
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sourceSessionId AND status='DRAFT'
                """, scope.parameters().addValue("sourceSessionId", source.id()), Integer.class) > 0) {
            throw conflict("ROLLOVER_OPEN_TIMETABLE_DRAFT",
                    "Publish or resolve the source session timetable draft before rollover.");
        }
        if (request.copyPublishedTimetable() && !request.copyTeachingAllocations()) {
            throw bad("ROLLOVER_TIMETABLE_REQUIRES_ALLOCATIONS",
                    "Copying a timetable requires eligible teaching allocations to be copied too.");
        }

        List<SourceStudent> students = sourceStudentRows(scope, source.id());
        if (students.isEmpty()) {
            throw conflict("ROLLOVER_NO_ACTIVE_STUDENTS", "The source session has no active students to roll over.");
        }
        List<SectionTemplate> sections = sectionTemplates(scope, source.id());
        Map<UUID, SourceStudent> studentByEnrolment = students.stream()
                .collect(Collectors.toMap(SourceStudent::enrolmentId, Function.identity()));
        Map<SectionKey, SectionTemplate> sectionByKey = sections.stream()
                .collect(Collectors.toMap(SectionTemplate::key, Function.identity()));
        List<NormalizedDecision> decisions = normalizeDecisions(
                request.studentDecisions(), studentByEnrolment, sectionByKey);
        validateCapacities(decisions, sectionByKey);

        List<String> warnings = new ArrayList<>();
        if (LocalDate.now(clock).isBefore(source.endsOn())) {
            warnings.add("Execution remains blocked until the source session end date.");
        }
        int termCount = count(scope, "academic_terms", source.id(), null);
        int resourceCount = count(scope, "academic_subject_resources", source.id(), "status='ACTIVE'");
        int offeringCount = jdbc.queryForObject("""
                SELECT count(*) FROM section_subject_offerings offering
                JOIN class_sections section ON section.id=offering.class_section_id
                WHERE offering.organisation_id=:organisationId AND offering.school_id=:schoolId
                  AND section.academic_session_id=:sourceSessionId
                  AND section.status='ACTIVE' AND offering.status='ACTIVE'
                """, scope.parameters().addValue("sourceSessionId", source.id()), Integer.class);
        int periodCount = count(scope, "bell_schedule_periods", source.id(), "status='ACTIVE'");
        int eligibleAllocationCount = request.copyTeachingAllocations()
                ? eligibleAllocationCount(scope, source.id(), request.targetStartsOn(), request.targetEndsOn())
                : 0;
        int sourceAllocationCount = countAllocations(scope, source.id());
        if (request.copyTeachingAllocations() && eligibleAllocationCount < sourceAllocationCount) {
            warnings.add((sourceAllocationCount - eligibleAllocationCount)
                    + " teaching allocation(s) will not be copied because the staff assignment or membership does not cover the target session.");
        }
        int timetablePlanCount = request.copyPublishedTimetable()
                ? count(scope, "timetable_plans", source.id(), "status='PUBLISHED'") : 0;
        int timetableEntryCount = request.copyPublishedTimetable()
                ? eligibleTimetableEntryCount(scope, source.id(), request.targetStartsOn(), request.targetEndsOn()) : 0;
        int sourceEntryCount = request.copyPublishedTimetable() ? publishedTimetableEntryCount(scope, source.id()) : 0;
        if (request.copyPublishedTimetable() && timetableEntryCount < sourceEntryCount) {
            warnings.add((sourceEntryCount - timetableEntryCount)
                    + " timetable entry or entries will be omitted because their teacher is not eligible for the target session.");
        }
        int feePlanCount = request.copyFeePlans()
                ? count(scope, "fee_plans", source.id(), "status='ACTIVE'") : 0;
        int feeItemCount = request.copyFeePlans() ? activeFeeItemCount(scope, source.id()) : 0;
        int policyCount = request.copyCalendarPolicy() ? latestPolicyCount(scope, source.id()) : 0;

        int promoted = (int) decisions.stream().filter(item -> "PROMOTE".equals(item.outcome())).count();
        int retained = (int) decisions.stream().filter(item -> "RETAIN".equals(item.outcome())).count();
        int leaving = decisions.size() - promoted - retained;
        RolloverCopyCounts counts = new RolloverCopyCounts(
                students.size(), promoted, retained, leaving, sections.size(), termCount,
                resourceCount, offeringCount, eligibleAllocationCount, periodCount,
                timetablePlanCount, timetableEntryCount, feePlanCount, feeItemCount, policyCount);
        String hash = snapshotHash(scope, request, source, students, decisions, counts);
        List<RolloverStudentPreview> studentPreview = decisions.stream()
                .map(item -> item.preview()).toList();
        RolloverPreview preview = new RolloverPreview(
                scope.schoolId(), source.id(), source.name(), request.targetSessionName().strip(),
                request.targetStartsOn(), request.targetEndsOn(), hash, counts,
                List.copyOf(warnings), studentPreview);
        return new PreviewState(request, preview, source, students, decisions, sections);
    }

    private void validateTarget(RolloverPreviewRequest request) {
        if (!request.targetEndsOn().isAfter(request.targetStartsOn())) {
            throw bad("ROLLOVER_TARGET_DATES_INVALID", "The target session end date must be after its start date.");
        }
        required(request.targetSessionName(), "target session name");
    }

    private List<NormalizedDecision> normalizeDecisions(
            List<StudentRolloverDecisionRequest> requested,
            Map<UUID, SourceStudent> students,
            Map<SectionKey, SectionTemplate> sections) {
        Map<UUID, StudentRolloverDecisionRequest> unique = new LinkedHashMap<>();
        for (StudentRolloverDecisionRequest decision : requested) {
            if (unique.put(decision.sourceEnrolmentId(), decision) != null) {
                throw bad("ROLLOVER_DUPLICATE_STUDENT_DECISION",
                        "Each source enrolment must appear exactly once in the preview.");
            }
        }
        if (!unique.keySet().equals(students.keySet())) {
            throw bad("ROLLOVER_STUDENT_DECISIONS_INCOMPLETE",
                    "Provide exactly one decision for every active student in the source session.");
        }

        List<NormalizedDecision> normalized = new ArrayList<>();
        for (SourceStudent student : students.values().stream()
                .sorted(Comparator.comparing(item -> item.enrolmentId().toString())).toList()) {
            StudentRolloverDecisionRequest input = unique.get(student.enrolmentId());
            String outcome = enumValue(input.outcome(), OUTCOMES, "rollover outcome");
            String reason = required(input.reason(), "student decision reason");
            if ("LEAVE".equals(outcome)) {
                String leaveStatus = enumValue(input.leaveStatus(), LEAVE_STATUSES, "leaving status");
                if (clean(input.targetGradeCode()) != null || clean(input.targetSectionCode()) != null
                        || clean(input.targetRollNumber()) != null) {
                    throw bad("ROLLOVER_LEAVING_TARGET_INVALID",
                            "A leaving student cannot have a target grade, section, or roll number.");
                }
                normalized.add(new NormalizedDecision(student, outcome, null, null, null, leaveStatus, reason));
                continue;
            }

            String grade = upperRequired(input.targetGradeCode(), "target grade");
            String sectionCode = upperRequired(input.targetSectionCode(), "target section");
            SectionKey key = new SectionKey(student.campusId(), grade, sectionCode);
            if (!sections.containsKey(key)) {
                throw bad("ROLLOVER_TARGET_SECTION_MISSING",
                        "Every continuing student must target a section template from the same campus.");
            }
            if ("RETAIN".equals(outcome) && !grade.equals(upper(student.gradeCode()))) {
                throw bad("ROLLOVER_RETENTION_GRADE_INVALID",
                        "A retained student must remain in the same grade.");
            }
            if ("PROMOTE".equals(outcome) && grade.equals(upper(student.gradeCode()))) {
                throw bad("ROLLOVER_PROMOTION_GRADE_INVALID",
                        "Use retain when the student remains in the same grade.");
            }
            normalized.add(new NormalizedDecision(
                    student, outcome, grade, sectionCode, clean(input.targetRollNumber()), null, reason));
        }
        return List.copyOf(normalized);
    }

    private void validateCapacities(
            List<NormalizedDecision> decisions,
            Map<SectionKey, SectionTemplate> sections) {
        Map<SectionKey, Integer> totals = new HashMap<>();
        Set<String> rollSlots = new HashSet<>();
        for (NormalizedDecision decision : decisions) {
            if ("LEAVE".equals(decision.outcome())) continue;
            SectionKey key = decision.targetKey();
            int total = totals.merge(key, 1, Integer::sum);
            Integer capacity = sections.get(key).capacity();
            if (capacity != null && total > capacity) {
                throw conflict("ROLLOVER_SECTION_CAPACITY_EXCEEDED",
                        "The preview exceeds the configured capacity of "
                                + decision.targetGradeCode() + " " + decision.targetSectionCode() + ".");
            }
            if (decision.targetRollNumber() != null
                    && !rollSlots.add(key + "|" + decision.targetRollNumber().toLowerCase(Locale.ROOT))) {
                throw conflict("ROLLOVER_ROLL_NUMBER_DUPLICATE",
                        "Two students cannot share the same roll number in one target section.");
            }
        }
    }

    private CopyState copyAcademicConfiguration(
            Scope scope,
            PreviewState state,
            UUID targetSessionId,
            UUID batchId,
            String executionReason,
            Instant now) {
        UUID sourceSessionId = state.sourceSession().id();
        LocalDate sourceStart = state.sourceSession().startsOn();
        LocalDate targetStart = state.preview().targetStartsOn();
        LocalDate targetEnd = state.preview().targetEndsOn();

        Map<UUID, UUID> sectionIds = new HashMap<>();
        Map<SectionKey, TargetSection> targetSections = new HashMap<>();
        for (SectionTemplate source : state.sections()) {
            UUID id = UUID.randomUUID();
            sectionIds.put(source.id(), id);
            targetSections.put(source.key(), new TargetSection(id, source.campusId()));
            jdbc.update("""
                    INSERT INTO class_sections(
                      id,organisation_id,school_id,campus_id,academic_session_id,grade_code,code,
                      display_name,capacity,status,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:campusId,:targetSessionId,:gradeCode,:code,
                      :name,:capacity,'ACTIVE',:now,:now,0)
                    """, scope.parameters().addValue("id", id).addValue("campusId", source.campusId())
                    .addValue("targetSessionId", targetSessionId).addValue("gradeCode", source.gradeCode())
                    .addValue("code", source.code()).addValue("name", source.name())
                    .addValue("capacity", source.capacity()).addValue("now", now));
        }

        Map<UUID, UUID> termIds = new HashMap<>();
        for (SourceTerm source : jdbc.query("""
                SELECT id,code,display_name,starts_on,ends_on,status
                FROM academic_terms
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sourceSessionId
                ORDER BY starts_on,code,id
                """, scope.parameters().addValue("sourceSessionId", sourceSessionId),
                (r, row) -> new SourceTerm(
                        r.getObject("id", UUID.class), r.getString("code"), r.getString("display_name"),
                        r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class),
                        r.getString("status")))) {
            LocalDate startsOn = shift(sourceStart, targetStart, source.startsOn());
            LocalDate endsOn = shift(sourceStart, targetStart, source.endsOn());
            if (startsOn.isBefore(targetStart) || endsOn.isAfter(targetEnd)) {
                throw conflict("ROLLOVER_TERM_OUTSIDE_TARGET",
                        "The target session is too short for the copied term schedule.");
            }
            UUID id = UUID.randomUUID();
            termIds.put(source.id(), id);
            jdbc.update("""
                    INSERT INTO academic_terms(
                      id,organisation_id,school_id,academic_session_id,code,display_name,starts_on,
                      ends_on,status,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:targetSessionId,:code,:name,:startsOn,
                      :endsOn,:status,:now,:now,0)
                    """, scope.parameters().addValue("id", id).addValue("targetSessionId", targetSessionId)
                    .addValue("code", source.code()).addValue("name", source.name())
                    .addValue("startsOn", startsOn).addValue("endsOn", endsOn)
                    .addValue("status", source.status()).addValue("now", now));
        }

        for (SourceResource source : jdbc.query("""
                SELECT subject_id,grade_code,resource_type,title,description,author,publisher,edition,
                  isbn,source_url,display_order,status
                FROM academic_subject_resources
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sourceSessionId AND status='ACTIVE'
                ORDER BY grade_code,subject_id,display_order,id
                """, scope.parameters().addValue("sourceSessionId", sourceSessionId),
                AcademicRolloverService::mapResource)) {
            jdbc.update("""
                    INSERT INTO academic_subject_resources(
                      id,organisation_id,school_id,academic_session_id,subject_id,grade_code,resource_type,
                      title,description,author,publisher,edition,isbn,source_url,display_order,status,
                      created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:targetSessionId,:subjectId,:gradeCode,:type,
                      :title,:description,:author,:publisher,:edition,:isbn,:sourceUrl,:displayOrder,
                      :status,:now,:now,0)
                    """, scope.parameters().addValue("id", UUID.randomUUID())
                    .addValue("targetSessionId", targetSessionId).addValue("subjectId", source.subjectId())
                    .addValue("gradeCode", source.gradeCode()).addValue("type", source.type())
                    .addValue("title", source.title()).addValue("description", source.description())
                    .addValue("author", source.author()).addValue("publisher", source.publisher())
                    .addValue("edition", source.edition()).addValue("isbn", source.isbn())
                    .addValue("sourceUrl", source.sourceUrl()).addValue("displayOrder", source.displayOrder())
                    .addValue("status", source.status()).addValue("now", now));
        }

        Map<UUID, UUID> offeringIds = new HashMap<>();
        for (SourceOffering source : jdbc.query("""
                SELECT offering.id,offering.class_section_id,offering.subject_id,
                  offering.weekly_target_periods,offering.status
                FROM section_subject_offerings offering
                JOIN class_sections section ON section.id=offering.class_section_id
                WHERE offering.organisation_id=:organisationId AND offering.school_id=:schoolId
                  AND section.academic_session_id=:sourceSessionId
                  AND section.status='ACTIVE' AND offering.status='ACTIVE'
                ORDER BY offering.class_section_id,offering.subject_id,offering.id
                """, scope.parameters().addValue("sourceSessionId", sourceSessionId),
                (r, row) -> new SourceOffering(
                        r.getObject("id", UUID.class), r.getObject("class_section_id", UUID.class),
                        r.getObject("subject_id", UUID.class), r.getInt("weekly_target_periods"),
                        r.getString("status")))) {
            UUID id = UUID.randomUUID();
            offeringIds.put(source.id(), id);
            jdbc.update("""
                    INSERT INTO section_subject_offerings(
                      id,organisation_id,school_id,class_section_id,subject_id,weekly_target_periods,
                      status,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:sectionId,:subjectId,:target,:status,:now,:now,0)
                    """, scope.parameters().addValue("id", id)
                    .addValue("sectionId", sectionIds.get(source.sectionId()))
                    .addValue("subjectId", source.subjectId()).addValue("target", source.weeklyTarget())
                    .addValue("status", source.status()).addValue("now", now));
        }

        Set<AllocationKey> eligibleAllocations = new HashSet<>();
        if (state.previewRequest().copyTeachingAllocations()) {
            for (SourceAllocation source : eligibleAllocations(
                    scope, sourceSessionId, targetStart, targetEnd)) {
                UUID targetOfferingId = offeringIds.get(source.offeringId());
                if (targetOfferingId == null) continue;
                eligibleAllocations.add(new AllocationKey(source.offeringId(), source.teacherId()));
                jdbc.update("""
                        INSERT INTO teaching_allocations(
                          id,organisation_id,school_id,section_subject_offering_id,teacher_membership_id,
                          allocation_role,starts_on,ends_on,status,created_at,updated_at,row_version)
                        VALUES(:id,:organisationId,:schoolId,:offeringId,:teacherId,:role,
                          :startsOn,:endsOn,'ACTIVE',:now,:now,0)
                        """, scope.parameters().addValue("id", UUID.randomUUID())
                        .addValue("offeringId", targetOfferingId).addValue("teacherId", source.teacherId())
                        .addValue("role", source.role()).addValue("startsOn", targetStart)
                        .addValue("endsOn", targetEnd).addValue("now", now));
            }
        }

        Map<UUID, UUID> periodIds = new HashMap<>();
        for (SourcePeriod source : jdbc.query("""
                SELECT id,campus_id,day_of_week,sequence_number,code,display_name,starts_at,ends_at,
                  period_kind,status
                FROM bell_schedule_periods
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sourceSessionId AND status='ACTIVE'
                ORDER BY campus_id,day_of_week,sequence_number,id
                """, scope.parameters().addValue("sourceSessionId", sourceSessionId),
                AcademicRolloverService::mapPeriod)) {
            UUID id = UUID.randomUUID();
            periodIds.put(source.id(), id);
            jdbc.update("""
                    INSERT INTO bell_schedule_periods(
                      id,organisation_id,school_id,campus_id,academic_session_id,day_of_week,
                      sequence_number,code,display_name,starts_at,ends_at,period_kind,status,
                      created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:campusId,:targetSessionId,:dayOfWeek,
                      :sequence,:code,:name,:startsAt,:endsAt,:kind,:status,:now,:now,0)
                    """, scope.parameters().addValue("id", id).addValue("campusId", source.campusId())
                    .addValue("targetSessionId", targetSessionId).addValue("dayOfWeek", source.dayOfWeek())
                    .addValue("sequence", source.sequence()).addValue("code", source.code())
                    .addValue("name", source.name()).addValue("startsAt", source.startsAt())
                    .addValue("endsAt", source.endsAt()).addValue("kind", source.kind())
                    .addValue("status", source.status()).addValue("now", now));
        }

        if (state.previewRequest().copyPublishedTimetable()) {
            copyTimetables(scope, sourceSessionId, targetSessionId, termIds, sectionIds,
                    offeringIds, periodIds, eligibleAllocations, now);
        }
        if (state.previewRequest().copyFeePlans()) {
            copyFeePlans(scope, sourceSessionId, targetSessionId, sourceStart, targetStart, now);
        }
        if (state.previewRequest().copyCalendarPolicy()) {
            copyCalendarPolicies(scope, sourceSessionId, targetSessionId, executionReason, now);
        }
        return new CopyState(targetSections);
    }

    private void copyTimetables(
            Scope scope,
            UUID sourceSessionId,
            UUID targetSessionId,
            Map<UUID, UUID> termIds,
            Map<UUID, UUID> sectionIds,
            Map<UUID, UUID> offeringIds,
            Map<UUID, UUID> periodIds,
            Set<AllocationKey> eligibleAllocations,
            Instant now) {
        for (SourcePlan source : jdbc.query("""
                SELECT id,campus_id,academic_term_id,plan_name
                FROM timetable_plans
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sourceSessionId AND status='PUBLISHED'
                ORDER BY campus_id,id
                """, scope.parameters().addValue("sourceSessionId", sourceSessionId),
                (r, row) -> new SourcePlan(
                        r.getObject("id", UUID.class), r.getObject("campus_id", UUID.class),
                        r.getObject("academic_term_id", UUID.class), r.getString("plan_name")))) {
            UUID targetPlanId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO timetable_plans(
                      id,organisation_id,school_id,campus_id,academic_session_id,academic_term_id,
                      plan_name,status,created_by_membership_id,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:campusId,:targetSessionId,:termId,
                      :name,'DRAFT',:actorMembershipId,:now,:now,0)
                    """, scope.parameters().addValue("id", targetPlanId)
                    .addValue("campusId", source.campusId()).addValue("targetSessionId", targetSessionId)
                    .addValue("termId", source.termId() == null ? null : termIds.get(source.termId()))
                    .addValue("name", clip(source.name() + " — rollover draft", 120)).addValue("now", now));

            for (SourceEntry entry : jdbc.query("""
                    SELECT id,class_section_id,section_subject_offering_id,bell_schedule_period_id,
                      teacher_membership_id,subject_name_snapshot,subject_short_name_snapshot,
                      period_name_snapshot,day_of_week_snapshot,sequence_number_snapshot,
                      starts_at_snapshot,ends_at_snapshot,room_label,note
                    FROM timetable_entries
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND timetable_plan_id=:planId
                    ORDER BY class_section_id,bell_schedule_period_id,id
                    """, scope.parameters().addValue("planId", source.id()),
                    AcademicRolloverService::mapEntry)) {
                if (!eligibleAllocations.contains(new AllocationKey(entry.offeringId(), entry.teacherId()))) {
                    continue;
                }
                UUID targetSection = sectionIds.get(entry.sectionId());
                UUID targetOffering = offeringIds.get(entry.offeringId());
                UUID targetPeriod = periodIds.get(entry.periodId());
                if (targetSection == null || targetOffering == null || targetPeriod == null) continue;
                jdbc.update("""
                        INSERT INTO timetable_entries(
                          id,organisation_id,school_id,timetable_plan_id,class_section_id,
                          section_subject_offering_id,bell_schedule_period_id,teacher_membership_id,
                          subject_name_snapshot,subject_short_name_snapshot,period_name_snapshot,
                          day_of_week_snapshot,sequence_number_snapshot,starts_at_snapshot,
                          ends_at_snapshot,room_label,note,created_at,updated_at,row_version)
                        VALUES(:id,:organisationId,:schoolId,:planId,:sectionId,:offeringId,:periodId,
                          :teacherId,:subjectName,:subjectShortName,:periodName,:dayOfWeek,:sequence,
                          :startsAt,:endsAt,:roomLabel,:note,:now,:now,0)
                        """, scope.parameters().addValue("id", UUID.randomUUID())
                        .addValue("planId", targetPlanId).addValue("sectionId", targetSection)
                        .addValue("offeringId", targetOffering).addValue("periodId", targetPeriod)
                        .addValue("teacherId", entry.teacherId()).addValue("subjectName", entry.subjectName())
                        .addValue("subjectShortName", entry.subjectShortName())
                        .addValue("periodName", entry.periodName()).addValue("dayOfWeek", entry.dayOfWeek())
                        .addValue("sequence", entry.sequence()).addValue("startsAt", entry.startsAt())
                        .addValue("endsAt", entry.endsAt()).addValue("roomLabel", entry.roomLabel())
                        .addValue("note", entry.note()).addValue("now", now));
            }
        }
    }

    private void copyFeePlans(
            Scope scope,
            UUID sourceSessionId,
            UUID targetSessionId,
            LocalDate sourceStart,
            LocalDate targetStart,
            Instant now) {
        for (SourceFeePlan source : jdbc.query("""
                SELECT id,plan_code,display_name,grade_code,currency_code,receipt_prefix,notes
                FROM fee_plans
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sourceSessionId AND status='ACTIVE'
                ORDER BY plan_code,id
                """, scope.parameters().addValue("sourceSessionId", sourceSessionId),
                (r, row) -> new SourceFeePlan(
                        r.getObject("id", UUID.class), r.getString("plan_code"),
                        r.getString("display_name"), r.getString("grade_code"),
                        r.getString("currency_code"), r.getString("receipt_prefix"),
                        r.getString("notes")))) {
            UUID targetPlanId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fee_plans(
                      id,organisation_id,school_id,academic_session_id,supersedes_plan_id,plan_code,
                      revision_number,display_name,grade_code,currency_code,public_disclosure,
                      receipt_prefix,notes,status,created_by_membership_id,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:targetSessionId,NULL,:code,1,:name,:gradeCode,
                      :currency,FALSE,:prefix,:notes,'DRAFT',:actorMembershipId,:now,:now,0)
                    """, scope.parameters().addValue("id", targetPlanId)
                    .addValue("targetSessionId", targetSessionId).addValue("code", source.code())
                    .addValue("name", source.name()).addValue("gradeCode", source.gradeCode())
                    .addValue("currency", source.currency()).addValue("prefix", source.receiptPrefix())
                    .addValue("notes", source.notes()).addValue("now", now));
            for (SourceFeeItem item : jdbc.query("""
                    SELECT fee_head_id,sequence_number,label,due_on,amount,allow_partial
                    FROM fee_plan_items
                    WHERE organisation_id=:organisationId AND school_id=:schoolId AND fee_plan_id=:planId
                    ORDER BY sequence_number,id
                    """, scope.parameters().addValue("planId", source.id()),
                    (r, row) -> new SourceFeeItem(
                            r.getObject("fee_head_id", UUID.class), r.getInt("sequence_number"),
                            r.getString("label"), r.getObject("due_on", LocalDate.class),
                            r.getBigDecimal("amount"), r.getBoolean("allow_partial")))) {
                jdbc.update("""
                        INSERT INTO fee_plan_items(
                          id,organisation_id,school_id,fee_plan_id,fee_head_id,sequence_number,label,
                          due_on,amount,allow_partial,created_at,updated_at,row_version)
                        VALUES(:id,:organisationId,:schoolId,:planId,:headId,:sequence,:label,
                          :dueOn,:amount,:allowPartial,:now,:now,0)
                        """, scope.parameters().addValue("id", UUID.randomUUID())
                        .addValue("planId", targetPlanId).addValue("headId", item.headId())
                        .addValue("sequence", item.sequence()).addValue("label", item.label())
                        .addValue("dueOn", shift(sourceStart, targetStart, item.dueOn()))
                        .addValue("amount", item.amount()).addValue("allowPartial", item.allowPartial())
                        .addValue("now", now));
            }
        }
    }

    private void copyCalendarPolicies(
            Scope scope,
            UUID sourceSessionId,
            UUID targetSessionId,
            String reason,
            Instant now) {
        for (SourcePolicy source : jdbc.query("""
                SELECT DISTINCT ON (campus_id) campus_id,school_start_time,same_day_cutoff_minutes,
                  reminder_time,time_zone,guardian_push_enabled,student_in_app_enabled,
                  emergency_ack_required
                FROM school_calendar_policy_revisions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sourceSessionId
                ORDER BY campus_id,revision_number DESC,id DESC
                """, scope.parameters().addValue("sourceSessionId", sourceSessionId),
                AcademicRolloverService::mapPolicy)) {
            jdbc.update("""
                    INSERT INTO school_calendar_policy_revisions(
                      id,organisation_id,school_id,campus_id,academic_session_id,revision_number,
                      supersedes_policy_id,school_start_time,same_day_cutoff_minutes,reminder_time,
                      time_zone,guardian_push_enabled,student_in_app_enabled,emergency_ack_required,
                      change_reason,revised_by_membership_id,revised_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:campusId,:targetSessionId,1,NULL,:startTime,
                      :cutoff,:reminderTime,:timeZone,:guardianPush,:studentInApp,:emergencyAck,
                      :reason,:actorMembershipId,:now,0)
                    """, scope.parameters().addValue("id", UUID.randomUUID())
                    .addValue("campusId", source.campusId()).addValue("targetSessionId", targetSessionId)
                    .addValue("startTime", source.startTime()).addValue("cutoff", source.cutoffMinutes())
                    .addValue("reminderTime", source.reminderTime()).addValue("timeZone", source.timeZone())
                    .addValue("guardianPush", source.guardianPush()).addValue("studentInApp", source.studentInApp())
                    .addValue("emergencyAck", source.emergencyAck())
                    .addValue("reason", clip("Confirmed copy during academic rollover: " + reason, 500))
                    .addValue("now", now));
        }
    }

    private void rolloverStudents(
            Scope scope,
            PreviewState state,
            CopyState copy,
            UUID targetSessionId,
            UUID batchId,
            Instant now) {
        for (NormalizedDecision decision : state.decisions()) {
            SourceStudent source = decision.student();
            if (source.placementId() != null) {
                int changed = jdbc.update("""
                        UPDATE student_section_placements
                        SET status='ENDED',ends_on=:endsOn,ended_by_membership_id=:actorMembershipId,
                          end_reason=:reason,updated_at=:now,row_version=row_version+1
                        WHERE id=:placementId AND organisation_id=:organisationId AND school_id=:schoolId
                          AND status='ACTIVE' AND row_version=:version
                        """, scope.parameters().addValue("placementId", source.placementId())
                        .addValue("endsOn", state.sourceSession().endsOn()).addValue("reason", decision.reason())
                        .addValue("now", now).addValue("version", source.placementVersion()));
                if (changed != 1) throw conflict("ROLLOVER_PLACEMENT_CHANGED",
                        "A source student placement changed after preview.");
            }
            int enrolmentChanged = jdbc.update("""
                    UPDATE student_enrolments
                    SET status='ENDED',ends_on=:endsOn,updated_at=:now,row_version=row_version+1
                    WHERE id=:enrolmentId AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE' AND row_version=:version
                    """, scope.parameters().addValue("enrolmentId", source.enrolmentId())
                    .addValue("endsOn", state.sourceSession().endsOn())
                    .addValue("now", now).addValue("version", source.enrolmentVersion()));
            if (enrolmentChanged != 1) throw conflict("ROLLOVER_ENROLMENT_CHANGED",
                    "A source student enrolment changed after preview.");

            UUID targetEnrolmentId = null;
            UUID targetPlacementId = null;
            if ("LEAVE".equals(decision.outcome())) {
                int learnerChanged = jdbc.update("""
                        UPDATE learners SET status=:status,updated_at=:now,row_version=row_version+1
                        WHERE id=:learnerId AND organisation_id=:organisationId AND school_id=:schoolId
                          AND status='ACTIVE'
                        """, scope.parameters().addValue("learnerId", source.learnerId())
                        .addValue("status", decision.leaveStatus()).addValue("now", now));
                if (learnerChanged != 1) throw conflict("ROLLOVER_LEARNER_CHANGED",
                        "A leaving student's learner record changed after preview.");
            } else {
                TargetSection target = copy.targetSections().get(decision.targetKey());
                if (target == null) throw conflict("ROLLOVER_TARGET_SECTION_CHANGED",
                        "A required target section was not created.");
                targetEnrolmentId = UUID.randomUUID();
                targetPlacementId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO student_enrolments(
                          id,organisation_id,school_id,campus_id,academic_session_id,learner_id,
                          origin_rollover_batch_id,prior_enrolment_id,grade_code,admission_number,
                          status,placement_status,starts_on,created_at,updated_at,row_version)
                        VALUES(:id,:organisationId,:schoolId,:campusId,:targetSessionId,:learnerId,
                          :batchId,:priorEnrolmentId,:gradeCode,:admissionNumber,'ACTIVE','PLACED',
                          :startsOn,:now,:now,0)
                        """, scope.parameters().addValue("id", targetEnrolmentId)
                        .addValue("campusId", target.campusId()).addValue("targetSessionId", targetSessionId)
                        .addValue("learnerId", source.learnerId()).addValue("batchId", batchId)
                        .addValue("priorEnrolmentId", source.enrolmentId())
                        .addValue("gradeCode", decision.targetGradeCode())
                        .addValue("admissionNumber", source.admissionNumber())
                        .addValue("startsOn", state.preview().targetStartsOn()).addValue("now", now));
                jdbc.update("""
                        INSERT INTO student_section_placements(
                          id,organisation_id,school_id,enrolment_id,learner_id,class_section_id,
                          roll_number,starts_on,status,placed_by_membership_id,placement_reason,
                          created_at,updated_at,row_version)
                        VALUES(:id,:organisationId,:schoolId,:enrolmentId,:learnerId,:sectionId,
                          :rollNumber,:startsOn,'ACTIVE',:actorMembershipId,:reason,:now,:now,0)
                        """, scope.parameters().addValue("id", targetPlacementId)
                        .addValue("enrolmentId", targetEnrolmentId).addValue("learnerId", source.learnerId())
                        .addValue("sectionId", target.id()).addValue("rollNumber", decision.targetRollNumber())
                        .addValue("startsOn", state.preview().targetStartsOn())
                        .addValue("reason", decision.reason()).addValue("now", now));
            }

            jdbc.update("""
                    INSERT INTO academic_rollover_student_decisions(
                      id,organisation_id,school_id,rollover_batch_id,source_enrolment_id,
                      source_placement_id,learner_id,outcome,target_grade_code,target_section_code,
                      target_roll_number,leave_status,decision_reason,target_enrolment_id,
                      target_placement_id,recorded_at)
                    VALUES(:id,:organisationId,:schoolId,:batchId,:sourceEnrolmentId,:sourcePlacementId,
                      :learnerId,:outcome,:targetGradeCode,:targetSectionCode,:targetRollNumber,
                      :leaveStatus,:reason,:targetEnrolmentId,:targetPlacementId,:now)
                    """, scope.parameters().addValue("id", UUID.randomUUID()).addValue("batchId", batchId)
                    .addValue("sourceEnrolmentId", source.enrolmentId())
                    .addValue("sourcePlacementId", source.placementId())
                    .addValue("learnerId", source.learnerId()).addValue("outcome", decision.outcome())
                    .addValue("targetGradeCode", decision.targetGradeCode())
                    .addValue("targetSectionCode", decision.targetSectionCode())
                    .addValue("targetRollNumber", decision.targetRollNumber())
                    .addValue("leaveStatus", decision.leaveStatus()).addValue("reason", decision.reason())
                    .addValue("targetEnrolmentId", targetEnrolmentId)
                    .addValue("targetPlacementId", targetPlacementId).addValue("now", now));
        }
    }

    private SessionInfo sourceSession(Scope scope, UUID sessionId, boolean lock) {
        String sql = """
                SELECT id,name,starts_on,ends_on,status,row_version
                FROM academic_sessions
                WHERE id=:sessionId AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : "");
        List<SessionInfo> sessions = jdbc.query(
                sql, scope.parameters().addValue("sessionId", sessionId),
                (r, row) -> new SessionInfo(
                        r.getObject("id", UUID.class), r.getString("name"),
                        r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class),
                        r.getString("status"), r.getLong("row_version")));
        if (sessions.isEmpty()) throw notFound("ROLLOVER_SOURCE_SESSION_NOT_FOUND",
                "The source session was not found in this school.");
        return sessions.getFirst();
    }

    private List<RolloverStudentOption> sourceStudents(Scope scope, UUID sessionId) {
        return sourceStudentRows(scope, sessionId).stream().map(student -> new RolloverStudentOption(
                student.enrolmentId(), student.learnerId(), student.learnerName(), student.sessionId(), student.campusId(),
                student.admissionNumber(), student.gradeCode(), student.placementId(), student.sectionId(),
                student.sectionCode(), student.rollNumber(), student.enrolmentVersion(),
                student.placementVersion())).toList();
    }

    private List<SourceStudent> sourceStudentRows(Scope scope, UUID sessionId) {
        return jdbc.query("""
                SELECT enrolment.id AS enrolment_id,enrolment.learner_id,learner.legal_name,
                  enrolment.academic_session_id,enrolment.campus_id,enrolment.admission_number,enrolment.grade_code,
                  placement.id AS placement_id,placement.class_section_id,section.code AS section_code,
                  placement.roll_number,enrolment.row_version AS enrolment_version,
                  placement.row_version AS placement_version
                FROM student_enrolments enrolment
                JOIN learners learner ON learner.id=enrolment.learner_id
                  AND learner.organisation_id=enrolment.organisation_id
                  AND learner.school_id=enrolment.school_id
                LEFT JOIN student_section_placements placement
                  ON placement.enrolment_id=enrolment.id AND placement.organisation_id=enrolment.organisation_id
                  AND placement.school_id=enrolment.school_id AND placement.status='ACTIVE'
                LEFT JOIN class_sections section ON section.id=placement.class_section_id
                WHERE enrolment.organisation_id=:organisationId AND enrolment.school_id=:schoolId
                  AND enrolment.status='ACTIVE'
                  AND (CAST(:sessionId AS uuid) IS NULL OR enrolment.academic_session_id=:sessionId)
                ORDER BY enrolment.academic_session_id,learner.legal_name,enrolment.id
                """, scope.parameters().addValue("sessionId", sessionId),
                (r, row) -> new SourceStudent(
                        r.getObject("enrolment_id", UUID.class), r.getObject("learner_id", UUID.class),
                        r.getString("legal_name"), r.getObject("academic_session_id", UUID.class),
                        r.getObject("campus_id", UUID.class),
                        r.getString("admission_number"), r.getString("grade_code"),
                        r.getObject("placement_id", UUID.class), r.getObject("class_section_id", UUID.class),
                        r.getString("section_code"), r.getString("roll_number"),
                        r.getLong("enrolment_version"), nullableLong(r, "placement_version")));
    }

    private List<SectionTemplate> sectionTemplates(Scope scope, UUID sessionId) {
        return jdbc.query("""
                SELECT id,campus_id,grade_code,code,display_name,capacity
                FROM class_sections
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sessionId AND status='ACTIVE'
                ORDER BY campus_id,grade_code,code,id
                """, scope.parameters().addValue("sessionId", sessionId),
                (r, row) -> new SectionTemplate(
                        r.getObject("id", UUID.class), r.getObject("campus_id", UUID.class),
                        upper(r.getString("grade_code")), upper(r.getString("code")),
                        r.getString("display_name"), nullableIntegerObject(r, "capacity")));
    }

    private int count(Scope scope, String table, UUID sessionId, String predicate) {
        String allowedTable = switch (table) {
            case "academic_terms", "academic_subject_resources", "bell_schedule_periods",
                    "timetable_plans", "fee_plans" -> table;
            default -> throw new IllegalArgumentException("Unsupported rollover count table");
        };
        String sql = "SELECT count(*) FROM " + allowedTable
                + " WHERE organisation_id=:organisationId AND school_id=:schoolId"
                + " AND academic_session_id=:sessionId"
                + (predicate == null ? "" : " AND " + predicate);
        return jdbc.queryForObject(sql, scope.parameters().addValue("sessionId", sessionId), Integer.class);
    }

    private int countAllocations(Scope scope, UUID sessionId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM teaching_allocations allocation
                JOIN section_subject_offerings offering ON offering.id=allocation.section_subject_offering_id
                JOIN class_sections section ON section.id=offering.class_section_id
                WHERE allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId
                  AND section.academic_session_id=:sessionId
                  AND section.status='ACTIVE' AND offering.status='ACTIVE' AND allocation.status='ACTIVE'
                """, scope.parameters().addValue("sessionId", sessionId), Integer.class);
    }

    private int eligibleAllocationCount(
            Scope scope, UUID sessionId, LocalDate targetStart, LocalDate targetEnd) {
        return eligibleAllocations(scope, sessionId, targetStart, targetEnd).size();
    }

    private List<SourceAllocation> eligibleAllocations(
            Scope scope, UUID sessionId, LocalDate targetStart, LocalDate targetEnd) {
        return jdbc.query("""
                SELECT DISTINCT allocation.id,allocation.section_subject_offering_id,
                  allocation.teacher_membership_id,allocation.allocation_role
                FROM teaching_allocations allocation
                JOIN section_subject_offerings offering ON offering.id=allocation.section_subject_offering_id
                JOIN class_sections section ON section.id=offering.class_section_id
                JOIN memberships membership ON membership.id=allocation.teacher_membership_id
                  AND membership.organisation_id=allocation.organisation_id
                  AND membership.school_id=allocation.school_id
                JOIN membership_roles membership_role ON membership_role.membership_id=membership.id
                JOIN roles role ON role.id=membership_role.role_id AND role.code='teacher'
                  AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                JOIN staff_profiles staff ON staff.user_account_id=membership.user_id
                  AND staff.organisation_id=membership.organisation_id
                  AND staff.school_id=membership.school_id AND staff.status='ACTIVE'
                JOIN staff_assignments employment ON employment.staff_profile_id=staff.id
                  AND employment.organisation_id=staff.organisation_id
                  AND employment.school_id=staff.school_id AND employment.status='ACTIVE'
                JOIN staff_designations designation ON designation.id=employment.staff_designation_id
                  AND designation.organisation_id=employment.organisation_id
                  AND designation.school_id=employment.school_id
                  AND designation.status='ACTIVE' AND designation.teaching_role
                WHERE allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId
                  AND section.academic_session_id=:sessionId
                  AND section.status='ACTIVE' AND offering.status='ACTIVE' AND allocation.status='ACTIVE'
                  AND membership.status='ACTIVE'
                  AND membership.valid_from <= CAST(:targetStart AS date)
                  AND (membership.valid_until IS NULL OR membership.valid_until >= CAST(:targetEnd AS date))
                  AND employment.starts_on <= :targetStart
                  AND (employment.ends_on IS NULL OR employment.ends_on >= :targetEnd)
                ORDER BY allocation.id
                """, scope.parameters().addValue("sessionId", sessionId)
                .addValue("targetStart", targetStart).addValue("targetEnd", targetEnd),
                (r, row) -> new SourceAllocation(
                        r.getObject("id", UUID.class),
                        r.getObject("section_subject_offering_id", UUID.class),
                        r.getObject("teacher_membership_id", UUID.class),
                        r.getString("allocation_role")));
    }

    private int publishedTimetableEntryCount(Scope scope, UUID sessionId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM timetable_entries entry
                JOIN timetable_plans plan ON plan.id=entry.timetable_plan_id
                WHERE entry.organisation_id=:organisationId AND entry.school_id=:schoolId
                  AND plan.academic_session_id=:sessionId AND plan.status='PUBLISHED'
                """, scope.parameters().addValue("sessionId", sessionId), Integer.class);
    }

    private int eligibleTimetableEntryCount(
            Scope scope, UUID sessionId, LocalDate targetStart, LocalDate targetEnd) {
        Set<AllocationKey> eligible = eligibleAllocations(scope, sessionId, targetStart, targetEnd).stream()
                .map(item -> new AllocationKey(item.offeringId(), item.teacherId()))
                .collect(Collectors.toSet());
        if (eligible.isEmpty()) return 0;
        return (int) jdbc.query("""
                SELECT entry.section_subject_offering_id,entry.teacher_membership_id
                FROM timetable_entries entry
                JOIN timetable_plans plan ON plan.id=entry.timetable_plan_id
                JOIN class_sections section ON section.id=entry.class_section_id
                  AND section.organisation_id=entry.organisation_id
                  AND section.school_id=entry.school_id
                  AND section.academic_session_id=plan.academic_session_id
                  AND section.status='ACTIVE'
                JOIN section_subject_offerings offering ON offering.id=entry.section_subject_offering_id
                  AND offering.organisation_id=entry.organisation_id
                  AND offering.school_id=entry.school_id
                  AND offering.class_section_id=section.id
                  AND offering.status='ACTIVE'
                JOIN bell_schedule_periods period ON period.id=entry.bell_schedule_period_id
                  AND period.organisation_id=entry.organisation_id
                  AND period.school_id=entry.school_id
                  AND period.academic_session_id=plan.academic_session_id
                  AND period.status='ACTIVE'
                WHERE entry.organisation_id=:organisationId AND entry.school_id=:schoolId
                  AND plan.academic_session_id=:sessionId AND plan.status='PUBLISHED'
                """, scope.parameters().addValue("sessionId", sessionId),
                (r, row) -> new AllocationKey(
                        r.getObject("section_subject_offering_id", UUID.class),
                        r.getObject("teacher_membership_id", UUID.class))).stream()
                .filter(eligible::contains).count();
    }

    private int activeFeeItemCount(Scope scope, UUID sessionId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM fee_plan_items item
                JOIN fee_plans plan ON plan.id=item.fee_plan_id
                WHERE item.organisation_id=:organisationId AND item.school_id=:schoolId
                  AND plan.academic_session_id=:sessionId AND plan.status='ACTIVE'
                """, scope.parameters().addValue("sessionId", sessionId), Integer.class);
    }

    private int latestPolicyCount(Scope scope, UUID sessionId) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT campus_id) FROM school_calendar_policy_revisions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sessionId
                """, scope.parameters().addValue("sessionId", sessionId), Integer.class);
    }

    private String snapshotHash(
            Scope scope,
            RolloverPreviewRequest request,
            SessionInfo source,
            List<SourceStudent> students,
            List<NormalizedDecision> decisions,
            RolloverCopyCounts counts) {
        StringBuilder canonical = new StringBuilder()
                .append("school=").append(scope.schoolId())
                .append("|source=").append(source.id()).append('|').append(source.version())
                .append('|').append(source.status()).append('|').append(source.startsOn())
                .append('|').append(source.endsOn())
                .append("|target=").append(request.targetSessionName().strip())
                .append('|').append(request.targetStartsOn()).append('|').append(request.targetEndsOn())
                .append("|options=").append(request.copyTeachingAllocations()).append('|')
                .append(request.copyPublishedTimetable()).append('|').append(request.copyFeePlans())
                .append('|').append(request.copyCalendarPolicy())
                .append("|counts=").append(counts);
        students.stream().sorted(Comparator.comparing(item -> item.enrolmentId().toString()))
                .forEach(item -> canonical.append("|student=").append(item.enrolmentId())
                        .append('|').append(item.enrolmentVersion()).append('|').append(item.learnerId())
                        .append('|').append(item.placementId()).append('|').append(item.placementVersion()));
        decisions.stream().sorted(Comparator.comparing(item -> item.student().enrolmentId().toString()))
                .forEach(item -> canonical.append("|decision=").append(item.student().enrolmentId())
                        .append('|').append(item.outcome()).append('|').append(item.targetGradeCode())
                        .append('|').append(item.targetSectionCode()).append('|').append(item.targetRollNumber())
                        .append('|').append(item.leaveStatus()).append('|').append(item.reason()));
        databaseFingerprint(scope, source.id()).forEach(token -> canonical.append("|db=").append(token));
        return sha256(canonical.toString());
    }

    private List<String> databaseFingerprint(Scope scope, UUID sessionId) {
        return jdbc.query("""
                SELECT token FROM (
                  SELECT 'TERM|'||id||'|'||row_version||'|'||updated_at AS token
                    FROM academic_terms WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND academic_session_id=:sessionId
                  UNION ALL
                  SELECT 'SECTION|'||id||'|'||row_version||'|'||updated_at
                    FROM class_sections WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND academic_session_id=:sessionId
                  UNION ALL
                  SELECT 'RESOURCE|'||id||'|'||row_version||'|'||updated_at
                    FROM academic_subject_resources WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND academic_session_id=:sessionId
                  UNION ALL
                  SELECT 'OFFERING|'||offering.id||'|'||offering.row_version||'|'||offering.updated_at
                    FROM section_subject_offerings offering
                    JOIN class_sections section ON section.id=offering.class_section_id
                    WHERE offering.organisation_id=:organisationId AND offering.school_id=:schoolId
                      AND section.academic_session_id=:sessionId
                  UNION ALL
                  SELECT 'ALLOCATION|'||allocation.id||'|'||allocation.row_version||'|'||allocation.updated_at
                    FROM teaching_allocations allocation
                    JOIN section_subject_offerings offering ON offering.id=allocation.section_subject_offering_id
                    JOIN class_sections section ON section.id=offering.class_section_id
                    WHERE allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId
                      AND section.academic_session_id=:sessionId
                  UNION ALL
                  SELECT 'PERIOD|'||id||'|'||row_version||'|'||updated_at
                    FROM bell_schedule_periods WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND academic_session_id=:sessionId
                  UNION ALL
                  SELECT 'PLAN|'||id||'|'||row_version||'|'||updated_at
                    FROM timetable_plans WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND academic_session_id=:sessionId
                  UNION ALL
                  SELECT 'ENTRY|'||entry.id||'|'||entry.row_version||'|'||entry.updated_at
                    FROM timetable_entries entry JOIN timetable_plans plan ON plan.id=entry.timetable_plan_id
                    WHERE entry.organisation_id=:organisationId AND entry.school_id=:schoolId
                      AND plan.academic_session_id=:sessionId
                  UNION ALL
                  SELECT 'FEE_PLAN|'||id||'|'||row_version||'|'||updated_at
                    FROM fee_plans WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND academic_session_id=:sessionId
                  UNION ALL
                  SELECT 'FEE_ITEM|'||item.id||'|'||item.row_version||'|'||item.updated_at
                    FROM fee_plan_items item JOIN fee_plans plan ON plan.id=item.fee_plan_id
                    WHERE item.organisation_id=:organisationId AND item.school_id=:schoolId
                      AND plan.academic_session_id=:sessionId
                  UNION ALL
                  SELECT 'POLICY|'||id||'|'||row_version||'|'||revised_at
                    FROM school_calendar_policy_revisions
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND academic_session_id=:sessionId
                ) snapshot ORDER BY token
                """, scope.parameters().addValue("sessionId", sessionId),
                (r, row) -> r.getString("token"));
    }

    private StoredBatch storedBatch(Scope scope, String idempotencyKey) {
        if (idempotencyKey == null) return null;
        List<StoredBatch> batches = jdbc.query("""
                SELECT batch.id,batch.source_session_id,batch.target_session_id,target.name AS target_name,
                  batch.snapshot_hash,batch.promoted_count,batch.retained_count,batch.leaving_count,
                  batch.section_count,batch.term_count,batch.resource_count,batch.offering_count,
                  batch.allocation_count,batch.period_count,batch.timetable_plan_count,
                  batch.timetable_entry_count,batch.fee_plan_count,batch.fee_item_count,
                  batch.calendar_policy_count,batch.warnings::text,batch.executed_at,batch.status,batch.row_version
                FROM academic_rollover_batches batch
                JOIN academic_sessions target ON target.id=batch.target_session_id
                WHERE batch.organisation_id=:organisationId AND batch.school_id=:schoolId
                  AND batch.idempotency_key=:idempotencyKey
                """, scope.parameters().addValue("idempotencyKey", idempotencyKey),
                (r, row) -> {
                    RolloverCopyCounts counts = new RolloverCopyCounts(
                            r.getInt("promoted_count") + r.getInt("retained_count") + r.getInt("leaving_count"),
                            r.getInt("promoted_count"), r.getInt("retained_count"), r.getInt("leaving_count"),
                            r.getInt("section_count"), r.getInt("term_count"), r.getInt("resource_count"),
                            r.getInt("offering_count"), r.getInt("allocation_count"), r.getInt("period_count"),
                            r.getInt("timetable_plan_count"), r.getInt("timetable_entry_count"),
                            r.getInt("fee_plan_count"), r.getInt("fee_item_count"),
                            r.getInt("calendar_policy_count"));
                    return new StoredBatch(
                            r.getString("snapshot_hash"),
                            new RolloverExecution(
                                    r.getObject("id", UUID.class),
                                    r.getObject("source_session_id", UUID.class),
                                    r.getObject("target_session_id", UUID.class),
                                    r.getString("target_name"), r.getString("status"),
                                    r.getString("snapshot_hash"), counts,
                                    warnings(r.getString("warnings")),
                                    r.getTimestamp("executed_at").toInstant(), r.getLong("row_version")));
                });
        return batches.isEmpty() ? null : batches.getFirst();
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId) {
        UUID organisationId = user.activeOrganisationId();
        if (organisationId == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED",
                    "Select a school organisation before opening academic rollover.");
        }
        List<Scope> scopes = jdbc.query("""
                SELECT school.id AS school_id,school.display_name AS school_name,
                  membership.id AS membership_id
                FROM memberships membership
                JOIN schools school ON school.id=membership.school_id
                  AND school.organisation_id=membership.organisation_id
                JOIN membership_roles membership_role ON membership_role.membership_id=membership.id
                JOIN roles role ON role.id=membership_role.role_id
                JOIN role_capabilities capability ON capability.role_id=role.id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN (:membershipIds) AND membership.school_id IS NOT NULL
                  AND (CAST(:requestedSchoolId AS uuid) IS NULL OR membership.school_id=:requestedSchoolId)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                  AND capability.capability_code='academic.rollover.manage'
                ORDER BY school.display_name,membership.created_at,membership.id
                LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", organisationId).addValue("membershipIds", user.membershipIds())
                .addValue("requestedSchoolId", requestedSchoolId),
                (r, row) -> new Scope(
                        organisationId, r.getObject("school_id", UUID.class),
                        r.getString("school_name"), r.getObject("membership_id", UUID.class)));
        if (scopes.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLLOVER_SCOPE_DENIED",
                    "Academic rollover access is not available for the selected school.");
        }
        return scopes.getFirst();
    }

    private String warningsJson(List<String> warnings) {
        try {
            return objectMapper.writeValueAsString(warnings);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Rollover warnings must be serializable", exception);
        }
    }

    private List<String> warnings(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored rollover warnings are invalid", exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static LocalDate shift(LocalDate sourceStart, LocalDate targetStart, LocalDate value) {
        return targetStart.plusDays(ChronoUnit.DAYS.between(sourceStart, value));
    }

    private static Integer nullableIntegerObject(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static SourceResource mapResource(ResultSet r, int row) throws SQLException {
        return new SourceResource(
                r.getObject("subject_id", UUID.class), r.getString("grade_code"),
                r.getString("resource_type"), r.getString("title"), r.getString("description"),
                r.getString("author"), r.getString("publisher"), r.getString("edition"),
                r.getString("isbn"), r.getString("source_url"), r.getInt("display_order"),
                r.getString("status"));
    }

    private static SourcePeriod mapPeriod(ResultSet r, int row) throws SQLException {
        return new SourcePeriod(
                r.getObject("id", UUID.class), r.getObject("campus_id", UUID.class),
                r.getInt("day_of_week"), r.getInt("sequence_number"), r.getString("code"),
                r.getString("display_name"), r.getObject("starts_at", LocalTime.class),
                r.getObject("ends_at", LocalTime.class), r.getString("period_kind"),
                r.getString("status"));
    }

    private static SourceEntry mapEntry(ResultSet r, int row) throws SQLException {
        return new SourceEntry(
                r.getObject("class_section_id", UUID.class),
                r.getObject("section_subject_offering_id", UUID.class),
                r.getObject("bell_schedule_period_id", UUID.class),
                r.getObject("teacher_membership_id", UUID.class),
                r.getString("subject_name_snapshot"), r.getString("subject_short_name_snapshot"),
                r.getString("period_name_snapshot"), r.getInt("day_of_week_snapshot"),
                r.getInt("sequence_number_snapshot"), r.getObject("starts_at_snapshot", LocalTime.class),
                r.getObject("ends_at_snapshot", LocalTime.class), r.getString("room_label"),
                r.getString("note"));
    }

    private static SourcePolicy mapPolicy(ResultSet r, int row) throws SQLException {
        return new SourcePolicy(
                r.getObject("campus_id", UUID.class), r.getObject("school_start_time", LocalTime.class),
                r.getInt("same_day_cutoff_minutes"), r.getObject("reminder_time", LocalTime.class),
                r.getString("time_zone"), r.getBoolean("guardian_push_enabled"),
                r.getBoolean("student_in_app_enabled"), r.getBoolean("emergency_ack_required"));
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = upperRequired(value, label);
        if (!allowed.contains(normalized)) throw bad("ROLLOVER_VALUE_INVALID", "Choose a valid " + label + ".");
        return normalized;
    }

    private static String upperRequired(String value, String label) {
        String cleaned = required(value, label);
        return cleaned.toUpperCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }

    private static String required(String value, String label) {
        String cleaned = clean(value);
        if (cleaned == null) throw bad("ROLLOVER_FIELD_REQUIRED", "Provide " + label + ".");
        return cleaned;
    }

    private static String clip(String value, int max) {
        String cleaned = required(value, "text");
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private record Scope(UUID organisationId, UUID schoolId, String schoolName, UUID actorMembershipId) {
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource()
                    .addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId)
                    .addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record SessionInfo(
            UUID id, String name, LocalDate startsOn, LocalDate endsOn, String status, long version) {}

    private record SourceStudent(
            UUID enrolmentId,
            UUID learnerId,
            String learnerName,
            UUID sessionId,
            UUID campusId,
            String admissionNumber,
            String gradeCode,
            UUID placementId,
            UUID sectionId,
            String sectionCode,
            String rollNumber,
            long enrolmentVersion,
            Long placementVersion) {}

    private record SectionKey(UUID campusId, String gradeCode, String sectionCode) {}

    private record SectionTemplate(
            UUID id, UUID campusId, String gradeCode, String code, String name, Integer capacity) {
        SectionKey key() {
            return new SectionKey(campusId, gradeCode, code);
        }
    }

    private record NormalizedDecision(
            SourceStudent student,
            String outcome,
            String targetGradeCode,
            String targetSectionCode,
            String targetRollNumber,
            String leaveStatus,
            String reason) {
        SectionKey targetKey() {
            return new SectionKey(student.campusId(), targetGradeCode, targetSectionCode);
        }

        RolloverStudentPreview preview() {
            return new RolloverStudentPreview(
                    student.enrolmentId(), student.learnerId(), student.learnerName(),
                    student.admissionNumber(), student.gradeCode(), student.sectionCode(),
                    outcome, targetGradeCode, targetSectionCode, targetRollNumber, leaveStatus, reason);
        }
    }

    private record PreviewState(
            RolloverPreviewRequest previewRequest,
            RolloverPreview preview,
            SessionInfo sourceSession,
            List<SourceStudent> students,
            List<NormalizedDecision> decisions,
            List<SectionTemplate> sections) {}

    private record TargetSection(UUID id, UUID campusId) {}
    private record CopyState(Map<SectionKey, TargetSection> targetSections) {}
    private record SourceTerm(
            UUID id, String code, String name, LocalDate startsOn, LocalDate endsOn, String status) {}
    private record SourceResource(
            UUID subjectId, String gradeCode, String type, String title, String description,
            String author, String publisher, String edition, String isbn, String sourceUrl,
            int displayOrder, String status) {}
    private record SourceOffering(
            UUID id, UUID sectionId, UUID subjectId, int weeklyTarget, String status) {}
    private record SourceAllocation(UUID id, UUID offeringId, UUID teacherId, String role) {}
    private record AllocationKey(UUID offeringId, UUID teacherId) {}
    private record SourcePeriod(
            UUID id, UUID campusId, int dayOfWeek, int sequence, String code, String name,
            LocalTime startsAt, LocalTime endsAt, String kind, String status) {}
    private record SourcePlan(UUID id, UUID campusId, UUID termId, String name) {}
    private record SourceEntry(
            UUID sectionId, UUID offeringId, UUID periodId, UUID teacherId,
            String subjectName, String subjectShortName, String periodName,
            int dayOfWeek, int sequence, LocalTime startsAt, LocalTime endsAt,
            String roomLabel, String note) {}
    private record SourceFeePlan(
            UUID id, String code, String name, String gradeCode, String currency,
            String receiptPrefix, String notes) {}
    private record SourceFeeItem(
            UUID headId, int sequence, String label, LocalDate dueOn,
            BigDecimal amount, boolean allowPartial) {}
    private record SourcePolicy(
            UUID campusId, LocalTime startTime, int cutoffMinutes, LocalTime reminderTime,
            String timeZone, boolean guardianPush, boolean studentInApp, boolean emergencyAck) {}
    private record StoredBatch(String snapshotHash, RolloverExecution response) {}
}

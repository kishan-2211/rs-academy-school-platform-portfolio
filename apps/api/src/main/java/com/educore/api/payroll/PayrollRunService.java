package com.educore.api.payroll;

import static com.educore.api.payroll.PayrollRunController.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.people.PeoplePayrollBridge;
import com.educore.api.people.PeoplePayrollBridge.PayrollStaff;
import com.educore.api.workforce.WorkforcePayrollBridge;
import com.educore.api.workforce.WorkforcePayrollBridge.PayrollAttendanceDay;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import com.educore.api.common.api.DatabaseConflict;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollRunService {
    private static final Set<String> POLICY_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> ZERO_DAY_ACTIONS = Set.of("FULL_PAY", "BLOCK");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private final NamedParameterJdbcTemplate jdbc;
    private final PeoplePayrollBridge people;
    private final WorkforcePayrollBridge workforce;
    private final PayrollRecoveryService recovery;
    private final SecurityAuditService audit;
    private final Clock clock;

    public PayrollRunService(NamedParameterJdbcTemplate jdbc, PeoplePayrollBridge people,
            WorkforcePayrollBridge workforce, PayrollRecoveryService recovery, SecurityAuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.people = people;
        this.workforce = workforce;
        this.recovery = recovery;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PayrollRunWorkspace workspace(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId);
        Policy policy = activePolicy(scope);
        List<RunHeader> headers = jdbc.query("""
                SELECT run.*,creator_user.display_name AS creator_name,
                       submitter_user.display_name AS submitter_name,
                       decider_user.display_name AS decider_name,
                       payroll_run_actor_is_independent(:actorMembershipId,
                         run.created_by_membership_id,run.submitted_by_membership_id,
                         run.organisation_id,run.school_id) AS actor_independent
                FROM payroll_runs run
                JOIN memberships creator ON creator.id=run.created_by_membership_id
                  AND creator.organisation_id=run.organisation_id AND creator.school_id=run.school_id
                JOIN user_accounts creator_user ON creator_user.id=creator.user_id
                LEFT JOIN memberships submitter ON submitter.id=run.submitted_by_membership_id
                  AND submitter.organisation_id=run.organisation_id AND submitter.school_id=run.school_id
                LEFT JOIN user_accounts submitter_user ON submitter_user.id=submitter.user_id
                LEFT JOIN memberships decider ON decider.id=run.decided_by_membership_id
                  AND decider.organisation_id=run.organisation_id AND decider.school_id=run.school_id
                LEFT JOIN user_accounts decider_user ON decider_user.id=decider.user_id
                WHERE run.organisation_id=:organisationId AND run.school_id=:schoolId
                ORDER BY run.payroll_month DESC,run.attempt_number DESC,run.created_at DESC,run.id
                LIMIT 24
                """, scope.params(), (r, n) -> new RunHeader(r.getObject("id", UUID.class),
                r.getObject("payroll_month", LocalDate.class), r.getObject("period_start", LocalDate.class),
                r.getObject("period_end", LocalDate.class), r.getInt("attempt_number"),
                r.getString("run_code"), r.getString("input_fingerprint"), r.getString("currency_code"),
                r.getString("status"), r.getInt("staff_count"),
                r.getInt("scheduled_working_days_total"), r.getInt("recorded_days_total"),
                r.getBigDecimal("payable_days_total"), r.getBigDecimal("full_gross_total"),
                r.getBigDecimal("payable_gross_total"), r.getBigDecimal("deduction_total"),
                r.getBigDecimal("employer_contribution_total"), r.getBigDecimal("net_total"),
                r.getString("creation_reason"), r.getObject("created_by_membership_id", UUID.class),
                r.getString("creator_name"),
                r.getString("submitter_name"), instant(r.getTimestamp("submitted_at")),
                r.getString("decider_name"), instant(r.getTimestamp("decided_at")),
                r.getString("decision_reason"), r.getLong("row_version"),
                r.getBoolean("actor_independent")));
        if (headers.isEmpty()) return new PayrollRunWorkspace(scope.schoolId(), scope.schoolName(),
                scope.canConfigure(), scope.canPrepare(), scope.canApprove(), scope.canReverseRequest(),
                scope.canReverseApprove(), item(policy), List.of());

        List<UUID> runIds = headers.stream().map(RunHeader::id).toList();
        Map<UUID, List<RunLineRow>> linesByResult = loadRunLines(scope, runIds);
        Map<UUID, List<PayrollRunWorkspace.RunStaffItem>> staffByRun = loadRunStaff(
                scope, runIds, linesByResult);
        Map<UUID, List<PayrollRunWorkspace.RunHistoryItem>> historyByRun = loadRunHistory(scope, runIds);
        Map<UUID, List<PayrollRunWorkspace.RunReversalItem>> reversalsByRun =
                loadRunReversals(scope, runIds);
        List<PayrollRunWorkspace.RunItem> runs = headers.stream().map(header -> header.item(
                staffByRun.getOrDefault(header.id(), List.of()),
                historyByRun.getOrDefault(header.id(), List.of()),
                reversalsByRun.getOrDefault(header.id(), List.of()), scope)).toList();
        return new PayrollRunWorkspace(scope.schoolId(), scope.schoolName(), scope.canConfigure(),
                scope.canPrepare(), scope.canApprove(), scope.canReverseRequest(),
                scope.canReverseApprove(), item(policy), runs);
    }

    @Transactional(readOnly = true)
    public PayrollRunPreview preview(AuthenticatedUser user, UUID schoolId, LocalDate payrollMonth,
            String capability) {
        Scope scope = requireCapability(user, schoolId, capability);
        return calculate(user, scope, month(payrollMonth), capability).view();
    }

    @Transactional
    public PayrollMutationResponse createPolicy(AuthenticatedUser user, RunPolicyRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.configure");
        PolicyInput input = policyInput(request);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("PAYROLL_RUN_POLICY_CONFLICT", () -> jdbc.update("""
                INSERT INTO payroll_run_policies(id,organisation_id,school_id,display_name,
                  currency_code,zero_scheduled_days_action,proration_scale,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:name,:currency,:zeroDays,:scale,:status,
                  :actorMembershipId,:now,:now,0)
                """, scope.params().addValue("id", id).addValue("name", input.name())
                .addValue("currency", input.currency()).addValue("zeroDays", input.zeroDays())
                .addValue("scale", input.scale()).addValue("status", input.status())
                .addValue("now", now)));
        audit(user, scope, "payroll.run-policy.created", "PAYROLL_RUN_POLICY", id, request.reason());
        return new PayrollMutationResponse(id, 0);
    }

    @Transactional
    public PayrollMutationResponse updatePolicy(AuthenticatedUser user, UUID id,
            RunPolicyRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.configure");
        PolicyInput input = policyInput(request);
        long expected = version(request.expectedVersion());
        int updated = safe("PAYROLL_RUN_POLICY_CONFLICT", () -> jdbc.update("""
                UPDATE payroll_run_policies SET display_name=:name,currency_code=:currency,
                  zero_scheduled_days_action=:zeroDays,proration_scale=:scale,status=:status,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:expected
                """, scope.params().addValue("id", id).addValue("name", input.name())
                .addValue("currency", input.currency()).addValue("zeroDays", input.zeroDays())
                .addValue("scale", input.scale()).addValue("status", input.status())
                .addValue("expected", expected).addValue("now", Instant.now(clock))));
        changed(updated);
        audit(user, scope, "payroll.run-policy.updated", "PAYROLL_RUN_POLICY", id, request.reason());
        return new PayrollMutationResponse(id, expected + 1);
    }

    @Transactional
    public PayrollMutationResponse prepare(AuthenticatedUser user, PrepareRunRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.run.prepare");
        LocalDate payrollMonth = month(request.payrollMonth());
        String key = idempotencyKey(request.idempotencyKey());
        String why = reason(request.reason());
        String requestedFingerprint = fingerprint(request.previewFingerprint());
        StoredIdempotent existing = idempotent(scope, key);
        if (existing != null) return idempotentResult(existing, payrollMonth, requestedFingerprint, why);

        CalculatedPreview calculated = calculate(user, scope, payrollMonth, "payroll.run.prepare");
        requireReady(calculated, requestedFingerprint);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                new MapSqlParameterSource("lockKey", scope.schoolId() + ":" + payrollMonth), ignored -> null);
        existing = idempotent(scope, key);
        if (existing != null) return idempotentResult(existing, payrollMonth, requestedFingerprint, why);

        Integer highest = jdbc.queryForObject("""
                SELECT COALESCE(max(attempt_number),0) FROM payroll_runs
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND payroll_month=:payrollMonth
                """, scope.params().addValue("payrollMonth", payrollMonth), Integer.class);
        int attempt = Objects.requireNonNullElse(highest, 0) + 1;
        if (attempt > 999) throw conflict("PAYROLL_RUN_ATTEMPTS_EXHAUSTED",
                "This payroll month has reached its protected retry limit.");
        String runCode = "PAY-" + YearMonth.from(payrollMonth).toString().replace("-", "")
                + "-" + String.format(Locale.ROOT, "%03d", attempt);
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        PayrollRunPreview view = calculated.view();
        safe("PAYROLL_RUN_CONFLICT", () -> {
            jdbc.update("""
                    INSERT INTO payroll_runs(id,organisation_id,school_id,payroll_run_policy_id,
                      policy_snapshot,payroll_month,period_start,period_end,attempt_number,run_code,
                      idempotency_key,input_fingerprint,currency_code,status,staff_count,
                      scheduled_working_days_total,recorded_days_total,payable_days_total,
                      full_gross_total,payable_gross_total,deduction_total,employer_contribution_total,
                      net_total,creation_reason,created_by_membership_id,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:policyId,
                      jsonb_build_object('id',CAST(:policyId AS text),'displayName',:policyName,
                        'currencyCode',:currency,'zeroScheduledDaysAction',:zeroDays,
                        'prorationScale',:scale,'rowVersion',:policyVersion),
                      :payrollMonth,:periodStart,:periodEnd,:attempt,:runCode,:key,:fingerprint,
                      :currency,'DRAFT',:staffCount,:scheduled,:recorded,:payable,:fullGross,
                      :payableGross,:deductions,:employer,:net,:reason,:actorMembershipId,:now,:now,0)
                    """, scope.params().addValue("id", runId).addValue("policyId", calculated.policy().id())
                    .addValue("policyName", calculated.policy().name())
                    .addValue("currency", calculated.policy().currency())
                    .addValue("zeroDays", calculated.policy().zeroDays())
                    .addValue("scale", calculated.policy().scale())
                    .addValue("policyVersion", calculated.policy().version())
                    .addValue("payrollMonth", payrollMonth).addValue("periodStart", view.periodStart())
                    .addValue("periodEnd", view.periodEnd()).addValue("attempt", attempt)
                    .addValue("runCode", runCode).addValue("key", key)
                    .addValue("fingerprint", view.inputFingerprint()).addValue("staffCount", view.staffCount())
                    .addValue("scheduled", view.scheduledWorkingDays()).addValue("recorded", view.recordedDays())
                    .addValue("payable", view.payableDays()).addValue("fullGross", view.fullGross())
                    .addValue("payableGross", view.payableGross()).addValue("deductions", view.deductions())
                    .addValue("employer", view.employerContributions()).addValue("net", view.net())
                    .addValue("reason", why).addValue("now", now));
            jdbc.queryForObject("SELECT set_config('app.payroll_run_prepare','approved',true)",
                    new MapSqlParameterSource(), String.class);
            insertEvidence(scope, runId, calculated.staff(), now,
                    people.payrollAssignmentSnapshots(user, scope.schoolId(), view.periodEnd(), "payroll.run.prepare"));
            jdbc.query("SELECT validate_complete_payroll_run(:id,:organisationId,:schoolId)",
                    scope.params().addValue("id", runId), ignored -> null);
            return 1;
        });
        history(scope, runId, "PREPARED", null, "DRAFT", why);
        audit(user, scope, "payroll.run.prepared", "PAYROLL_RUN", runId, why);
        return new PayrollMutationResponse(runId, 0);
    }

    @Transactional
    public PayrollMutationResponse submit(AuthenticatedUser user, UUID id,
            RunTransitionRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.run.prepare");
        StoredRun run = lockRun(scope, id);
        requireStatus(run, "DRAFT");
        long expected = version(request.expectedVersion());
        if (run.version() != expected) changed(0);
        CalculatedPreview calculated = calculate(user, scope, run.payrollMonth(), "payroll.run.prepare",
                run.id(), run.recoveryVersion() == 1);
        requireReady(calculated, run.fingerprint());
        Instant now = Instant.now(clock);
        int updated = safe("PAYROLL_RUN_TRANSITION_CONFLICT", () -> jdbc.update("""
                UPDATE payroll_runs SET status='SUBMITTED',submitted_by_membership_id=:actorMembershipId,
                  submitted_at=:now,submission_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:expected
                """, scope.params().addValue("id", id).addValue("expected", expected)
                .addValue("reason", reason(request.reason())).addValue("now", now)));
        changed(updated);
        history(scope, id, "SUBMITTED", "DRAFT", "SUBMITTED", request.reason());
        audit(user, scope, "payroll.run.submitted", "PAYROLL_RUN", id, request.reason());
        return new PayrollMutationResponse(id, expected + 1);
    }

    @Transactional
    public PayrollMutationResponse cancel(AuthenticatedUser user, UUID id,
            RunTransitionRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.run.prepare");
        StoredRun run = lockRun(scope, id);
        requireStatus(run, "DRAFT");
        if (!run.creatorMembershipId().equals(scope.actorMembershipId())) {
            throw forbidden("PAYROLL_RUN_CANCEL_DENIED", "Only the run creator can cancel this protected draft.");
        }
        long expected = version(request.expectedVersion());
        if (run.version() != expected) changed(0);
        Instant now = Instant.now(clock);
        int updated = safe("PAYROLL_RUN_TRANSITION_CONFLICT", () -> jdbc.update("""
                UPDATE payroll_runs SET status='CANCELLED',decided_by_membership_id=:actorMembershipId,
                  decided_at=:now,decision_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:expected
                """, scope.params().addValue("id", id).addValue("expected", expected)
                .addValue("reason", reason(request.reason())).addValue("now", now)));
        changed(updated);
        history(scope, id, "CANCELLED", "DRAFT", "CANCELLED", request.reason());
        audit(user, scope, "payroll.run.cancelled", "PAYROLL_RUN", id, request.reason());
        return new PayrollMutationResponse(id, expected + 1);
    }

    @Transactional
    public PayrollMutationResponse decide(AuthenticatedUser user, UUID id, RunDecisionRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.run.approve");
        StoredRun run = lockRun(scope, id);
        requireStatus(run, "SUBMITTED");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT payroll_run_actor_is_independent(:actorMembershipId,
                  created_by_membership_id,submitted_by_membership_id,organisation_id,school_id)
                FROM payroll_runs WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", id), Boolean.class))) {
            throw forbidden("PAYROLL_RUN_SELF_APPROVAL_DENIED",
                    "The person who prepared or submitted payroll cannot make its final decision.");
        }
        long expected = version(request.expectedVersion());
        if (run.version() != expected) changed(0);
        String decision = enumValue(request.decision(), Set.of("FINALIZE", "REJECT"), "payroll decision");
        String next = "FINALIZE".equals(decision) ? "FINALIZED" : "REJECTED";
        if ("FINALIZED".equals(next)) {
            CalculatedPreview calculated = calculate(user, scope, run.payrollMonth(), "payroll.run.approve",
                    run.id(), run.recoveryVersion() == 1);
            requireReady(calculated, run.fingerprint());
        }
        Instant now = Instant.now(clock);
        int updated = safe("PAYROLL_RUN_TRANSITION_CONFLICT", () -> jdbc.update("""
                UPDATE payroll_runs SET status=:next,decided_by_membership_id=:actorMembershipId,
                  decided_at=:now,decision_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='SUBMITTED' AND row_version=:expected
                """, scope.params().addValue("id", id).addValue("next", next)
                .addValue("expected", expected).addValue("reason", reason(request.reason()))
                .addValue("now", now)));
        changed(updated);
        history(scope, id, next, "SUBMITTED", next, request.reason());
        audit(user, scope, "payroll.run." + next.toLowerCase(Locale.ROOT), "PAYROLL_RUN", id,
                request.reason());
        return new PayrollMutationResponse(id, expected + 1);
    }

    @Transactional
    public PayrollMutationResponse requestReversal(AuthenticatedUser user, UUID runId,
            String idempotencyKey, RunReversalRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.run.reverse.request");
        String key = idempotencyKey(idempotencyKey);
        long expected = version(request.expectedRunVersion());
        String why = reason(request.reason());
        String requestFingerprint = sha256("payroll-run-reversal-v1|" + runId + "|"
                + expected + "|" + why);
        StoredReversal existing = reversalByKey(scope, key);
        if (existing != null) {
            if (!existing.runId().equals(runId) || existing.requestedRunVersion() != expected
                    || !existing.fingerprint().equals(requestFingerprint)) {
                throw conflict("PAYROLL_REVERSAL_IDEMPOTENCY_CONFLICT",
                        "This retry key was already used for a different reversal request.");
            }
            return new PayrollMutationResponse(existing.id(), existing.version());
        }

        StoredRun run = lockRun(scope, runId);
        requireStatus(run, "FINALIZED");
        if (run.version() != expected) changed(0);
        Integer pending = jdbc.queryForObject("""
                SELECT count(*) FROM payroll_run_reversal_requests
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND payroll_run_id=:runId AND status='PENDING'
                """, scope.params().addValue("runId", runId), Integer.class);
        if (pending != null && pending > 0) throw conflict("PAYROLL_REVERSAL_ALREADY_PENDING",
                "This finalized payroll run already has a pending reversal request.");

        UUID requestId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("PAYROLL_REVERSAL_REQUEST_CONFLICT", () -> {
            jdbc.queryForObject(
                    "SELECT set_config('app.payroll_run_reverse_request','approved',true)",
                    new MapSqlParameterSource(), String.class);
            jdbc.update("""
                    INSERT INTO payroll_run_reversal_requests(
                      id,organisation_id,school_id,payroll_run_id,requested_run_version,
                      idempotency_key,request_fingerprint,request_reason,status,
                      requested_by_membership_id,requested_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:runId,:expected,:key,:fingerprint,
                      :reason,'PENDING',:actorMembershipId,:now,0)
                    """, scope.params().addValue("id", requestId).addValue("runId", runId)
                    .addValue("expected", expected).addValue("key", key)
                    .addValue("fingerprint", requestFingerprint).addValue("reason", why)
                    .addValue("now", now));
            reversalHistory(scope, requestId, runId, "REQUESTED", null, "PENDING", why, now);
            return 1;
        });
        audit(user, scope, "payroll.run-reversal.requested", "PAYROLL_RUN_REVERSAL_REQUEST",
                requestId, why);
        return new PayrollMutationResponse(requestId, 0);
    }

    @Transactional
    public PayrollMutationResponse decideReversal(AuthenticatedUser user, UUID requestId,
            RunReversalDecisionRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.run.reverse.approve");
        StoredReversal reversal = lockReversal(scope, requestId);
        if (!"PENDING".equals(reversal.status())) throw conflict("PAYROLL_REVERSAL_STATE_INVALID",
                "This payroll reversal request has already been decided.");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT payroll_run_actor_is_independent(:actorMembershipId,
                  :requesterId,NULL,:organisationId,:schoolId)
                """, scope.params().addValue("requesterId", reversal.requesterMembershipId()), Boolean.class))) {
            throw forbidden("PAYROLL_REVERSAL_SELF_APPROVAL_DENIED",
                    "The person who requested reversal cannot make its decision.");
        }
        long expected = version(request.expectedVersion());
        if (reversal.version() != expected) changed(0);
        String decision = enumValue(request.decision(), Set.of("APPROVE", "REJECT"),
                "payroll reversal decision");
        String next = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        String why = reason(request.reason());
        if ("APPROVED".equals(next)
                && (!"FINALIZED".equals(reversal.runStatus())
                    || reversal.runVersion() != reversal.requestedRunVersion())) {
            throw conflict("PAYROLL_REVERSAL_SOURCE_CHANGED",
                    "The finalized payroll run changed after this request. Start a fresh review.");
        }
        Instant now = Instant.now(clock);
        safe("PAYROLL_REVERSAL_DECISION_CONFLICT", () -> {
            jdbc.queryForObject(
                    "SELECT set_config('app.payroll_run_reverse_decide','approved',true)",
                    new MapSqlParameterSource(), String.class);
            int updated = jdbc.update("""
                    UPDATE payroll_run_reversal_requests
                    SET status=:next,decided_by_membership_id=:actorMembershipId,
                      decided_at=:now,decision_reason=:reason,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='PENDING' AND row_version=:expected
                    """, scope.params().addValue("id", requestId).addValue("next", next)
                    .addValue("now", now).addValue("reason", why).addValue("expected", expected));
            changed(updated);
            if ("APPROVED".equals(next)) {
                jdbc.queryForObject("SELECT set_config('app.payroll_run_reverse','approved',true)",
                        new MapSqlParameterSource(), String.class);
                int runUpdated = jdbc.update("""
                        UPDATE payroll_runs
                        SET status='REVERSED',reversal_request_id=:requestId,
                          reversed_by_membership_id=:actorMembershipId,reversed_at=:now,
                          reversal_reason=:reason,updated_at=:now,row_version=row_version+1
                        WHERE id=:runId AND organisation_id=:organisationId
                          AND school_id=:schoolId AND status='FINALIZED'
                          AND row_version=:runVersion
                        """, scope.params().addValue("requestId", requestId)
                        .addValue("runId", reversal.runId())
                        .addValue("runVersion", reversal.requestedRunVersion())
                        .addValue("now", now).addValue("reason", why));
                changed(runUpdated);
                history(scope, reversal.runId(), "REVERSED", "FINALIZED", "REVERSED", why);
            }
            reversalHistory(scope, requestId, reversal.runId(), next, "PENDING", next, why, now);
            return 1;
        });
        audit(user, scope, "payroll.run-reversal." + next.toLowerCase(Locale.ROOT),
                "PAYROLL_RUN_REVERSAL_REQUEST", requestId, why);
        return new PayrollMutationResponse(requestId, expected + 1);
    }

    private CalculatedPreview calculate(AuthenticatedUser user, Scope scope, LocalDate payrollMonth,
            String capability) {
        return calculate(user, scope, payrollMonth, capability, null, true);
    }

    private CalculatedPreview calculate(AuthenticatedUser user, Scope scope, LocalDate payrollMonth,
            String capability, UUID owningRunId, boolean includeRecoveries) {
        LocalDate periodStart = payrollMonth;
        LocalDate periodEnd = YearMonth.from(payrollMonth).atEndOfMonth();
        Policy policy = activePolicy(scope);
        List<PayrollRunPreview.PreviewBlocker> blockers = new ArrayList<>();
        if (policy == null) {
            blockers.add(new PayrollRunPreview.PreviewBlocker(null, null, "ACTIVE_POLICY_REQUIRED",
                    "Configure one active monthly payroll policy before preparing payroll."));
        }
        List<PayrollStaff> eligible = people.payrollStaff(user, scope.schoolId(), capability).stream()
                .filter(staff -> !staff.joinedOn().isAfter(periodEnd)
                        && (staff.endedOn() == null || !staff.endedOn().isBefore(periodStart)))
                .sorted(Comparator.comparing(PayrollStaff::displayName).thenComparing(PayrollStaff::id))
                .toList();
        if (eligible.isEmpty()) blockers.add(new PayrollRunPreview.PreviewBlocker(null, null,
                "ELIGIBLE_STAFF_REQUIRED", "No employed staff are eligible in this payroll month."));
        List<PayrollAttendanceDay> attendance = workforce.payrollAttendanceDays(user, scope.schoolId(),
                periodStart, periodEnd, capability);
        Map<UUID, List<PayrollAttendanceDay>> attendanceByStaff = attendance.stream()
                .collect(Collectors.groupingBy(PayrollAttendanceDay::staffProfileId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<UUID, List<SalaryHeader>> salaryByStaff = loadApprovedSalary(scope, eligible);
        PayrollRecoveryService.Plan recoveryPlan = policy == null || !includeRecoveries
                ? new PayrollRecoveryService.Plan(Map.of(), Map.of(), Map.of())
                : recovery.plan(user, scope.organisationId(), scope.schoolId(), payrollMonth,
                        policy.currency(), owningRunId, eligible.stream().map(PayrollStaff::id).toList());
        List<CalculatedStaff> calculatedStaff = new ArrayList<>();
        for (PayrollStaff staff : eligible) {
            for (String code : recoveryPlan.blockers().getOrDefault(staff.id(), List.of())) {
                blockers.add(blocker(staff, code, recoveryBlockerMessage(code)));
            }
            LocalDate anchor = staff.joinedOn().isAfter(periodStart) ? staff.joinedOn() : periodStart;
            List<SalaryHeader> salaryHistory = salaryByStaff.getOrDefault(staff.id(), List.of());
            SalaryHeader salary = salaryHistory.stream().filter(item -> !item.effectiveFrom().isAfter(anchor))
                    .max(Comparator.comparing(SalaryHeader::effectiveFrom)
                            .thenComparingInt(SalaryHeader::revision)).orElse(null);
            if (salary == null) {
                blockers.add(blocker(staff, "APPROVED_SALARY_REQUIRED",
                        "No approved salary terms apply on the staff member's first eligible day."));
                continue;
            }
            boolean midPeriodChange = salaryHistory.stream().anyMatch(item ->
                    item.effectiveFrom().isAfter(anchor) && !item.effectiveFrom().isAfter(periodEnd));
            if (midPeriodChange) blockers.add(blocker(staff, "MID_PERIOD_SALARY_CHANGE",
                    "A salary revision starts inside this month; split-period payroll is not enabled."));
            if (policy != null && !policy.currency().equals(salary.currency())) {
                blockers.add(blocker(staff, "SALARY_CURRENCY_MISMATCH",
                        "Approved salary currency does not match the active payroll policy."));
            }
            List<PayrollAttendanceDay> days = attendanceByStaff.getOrDefault(staff.id(), List.of()).stream()
                    .filter(day -> !day.attendanceDate().isBefore(anchor)
                            && (staff.endedOn() == null || !day.attendanceDate().isAfter(staff.endedOn())))
                    .sorted(Comparator.comparing(PayrollAttendanceDay::attendanceDate)
                            .thenComparing(PayrollAttendanceDay::calendarDayId)).toList();
            int missing = (int) days.stream().filter(day -> !day.complete()).count();
            if (missing > 0) blockers.add(blocker(staff, "ATTENDANCE_INCOMPLETE",
                    missing + " scheduled day(s) do not have submitted attendance and approved leave evidence."));
            if (days.isEmpty() && policy != null && "BLOCK".equals(policy.zeroDays())) {
                blockers.add(blocker(staff, "SCHEDULED_DAYS_REQUIRED",
                        "No attendance-required calendar days exist for this staff member."));
            }
            CalculatedStaff result = calculateStaff(staff, salary, days, policy).withRecoveries(
                    recoveryPlan.byStaff().getOrDefault(staff.id(), List.of()),
                    recoveryPlan.warnings().getOrDefault(staff.id(), List.of()));
            if (result.net().signum() < 0) blockers.add(blocker(staff, "PAYABLE_NET_NEGATIVE",
                    "Attendance-prorated earnings are lower than the deductions including approved advance installments."));
            calculatedStaff.add(result);
        }
        if (policy == null) calculatedStaff = List.of();
        String inputFingerprint = hash(policy, payrollMonth, blockers, calculatedStaff);
        List<PayrollRunWorkspace.RunStaffItem> staffItems = calculatedStaff.stream()
                .map(CalculatedStaff::item).toList();
        return new CalculatedPreview(policy, calculatedStaff, new PayrollRunPreview(scope.schoolId(),
                payrollMonth, periodStart, periodEnd, blockers.isEmpty(), inputFingerprint, item(policy),
                blockers, staffItems.size(), sumInt(staffItems, PayrollRunWorkspace.RunStaffItem::scheduledWorkingDays),
                sumInt(staffItems, PayrollRunWorkspace.RunStaffItem::recordedDays),
                sum(staffItems, PayrollRunWorkspace.RunStaffItem::payableDays, 4),
                sum(staffItems, PayrollRunWorkspace.RunStaffItem::fullGross, 2),
                sum(staffItems, PayrollRunWorkspace.RunStaffItem::payableGross, 2),
                sum(staffItems, PayrollRunWorkspace.RunStaffItem::deductions, 2),
                sum(staffItems, PayrollRunWorkspace.RunStaffItem::employerContributions, 2),
                sum(staffItems, PayrollRunWorkspace.RunStaffItem::net, 2), staffItems));
    }

    private CalculatedStaff calculateStaff(PayrollStaff staff, SalaryHeader salary,
            List<PayrollAttendanceDay> days, Policy policy) {
        int scheduled = days.size();
        int recorded = (int) days.stream().filter(PayrollAttendanceDay::complete).count();
        BigDecimal payableDays = days.stream().filter(PayrollAttendanceDay::complete)
                .map(PayrollAttendanceDay::payrollCredit).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4);
        BigDecimal paidLeave = leaveDays(days, true);
        BigDecimal unpaidLeave = leaveDays(days, false);
        BigDecimal factor = scheduled == 0
                ? BigDecimal.ONE.setScale(6)
                : payableDays.divide(BigDecimal.valueOf(scheduled), policy == null ? 6 : policy.scale(),
                        RoundingMode.HALF_UP).setScale(6, RoundingMode.HALF_UP);
        List<String> warnings = scheduled == 0 && policy != null && "FULL_PAY".equals(policy.zeroDays())
                ? List.of("ZERO_SCHEDULED_DAYS_FULL_PAY") : List.of();
        BigDecimal basic = salary.lines().stream().filter(SalaryLine::basic).findFirst()
                .map(SalaryLine::amount).orElse(BigDecimal.ZERO);
        List<CalculatedLine> lines = salary.lines().stream().map(line -> {
            BigDecimal full = line.fullAmount(basic);
            BigDecimal payable = line.prorated() && "EARNING".equals(line.kind())
                    ? full.multiply(factor).setScale(2, RoundingMode.HALF_UP) : full;
            return new CalculatedLine(line, full, payable);
        }).toList();
        BigDecimal fullGross = lineTotal(lines, "EARNING", true);
        BigDecimal payableGross = lineTotal(lines, "EARNING", false);
        BigDecimal deductions = lineTotal(lines, "DEDUCTION", false);
        BigDecimal employer = lineTotal(lines, "EMPLOYER_CONTRIBUTION", false);
        BigDecimal net = payableGross.subtract(deductions).setScale(2);
        return new CalculatedStaff(staff, salary, days, scheduled, recorded, payableDays, paidLeave,
                unpaidLeave, scheduled - recorded, factor, fullGross, payableGross, deductions,
                employer, net, warnings, lines, List.of());
    }

    private static String recoveryBlockerMessage(String code) {
        return switch (code) {
            case "ADVANCE_RECOVERY_ALREADY_RESERVED" -> "An installment is already reserved by another payroll run.";
            case "ADVANCE_RECOVERY_SOURCE_DUPLICATED" -> "Duplicate advance installment evidence requires review.";
            case "ADVANCE_DISBURSEMENT_UNVERIFIED" -> "Advance disbursement could not be verified against accounting.";
            default -> "Advance installment currency or source evidence requires review before payroll.";
        };
    }

    private Map<UUID, List<SalaryHeader>> loadApprovedSalary(Scope scope, List<PayrollStaff> staff) {
        if (staff.isEmpty()) return Map.of();
        List<UUID> staffIds = staff.stream().map(PayrollStaff::id).toList();
        List<SalaryHeaderRow> headers = jdbc.query("""
                SELECT id,staff_profile_id,revision_number,effective_from,currency_code,status
                FROM staff_salary_profiles
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND staff_profile_id IN (:staffIds) AND status IN ('APPROVED','SUPERSEDED')
                ORDER BY staff_profile_id,effective_from,revision_number,id
                """, scope.params().addValue("staffIds", staffIds), (r, n) -> new SalaryHeaderRow(
                r.getObject("id", UUID.class), r.getObject("staff_profile_id", UUID.class),
                r.getInt("revision_number"), r.getObject("effective_from", LocalDate.class),
                r.getString("currency_code"), r.getString("status")));
        if (headers.isEmpty()) return Map.of();
        List<UUID> profileIds = headers.stream().map(SalaryHeaderRow::id).toList();
        Map<UUID, List<SalaryLine>> lines = jdbc.query("""
                SELECT salary_profile_id,id,payroll_component_id,component_code_snapshot,
                       component_name_snapshot,component_kind_snapshot,calculation_method_snapshot,
                       is_basic_snapshot,attendance_prorated_snapshot,statutory_snapshot,
                       fixed_amount,percentage_rate
                FROM staff_salary_profile_lines
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND salary_profile_id IN (:profileIds)
                ORDER BY salary_profile_id,component_kind_snapshot,component_code_snapshot,id
                """, scope.params().addValue("profileIds", profileIds), result -> {
                    Map<UUID, List<SalaryLine>> grouped = new LinkedHashMap<>();
                    while (result.next()) {
                        UUID profileId = result.getObject("salary_profile_id", UUID.class);
                        grouped.computeIfAbsent(profileId, ignored -> new ArrayList<>()).add(new SalaryLine(
                                result.getObject("id", UUID.class),
                                result.getObject("payroll_component_id", UUID.class),
                                result.getString("component_code_snapshot"),
                                result.getString("component_name_snapshot"),
                                result.getString("component_kind_snapshot"),
                                result.getString("calculation_method_snapshot"),
                                result.getBoolean("is_basic_snapshot"),
                                result.getBoolean("attendance_prorated_snapshot"),
                                result.getBoolean("statutory_snapshot"), result.getBigDecimal("fixed_amount"),
                                result.getBigDecimal("percentage_rate")));
                    }
                    return grouped;
                });
        return headers.stream().map(header -> new SalaryHeader(header.id(), header.staffId(),
                header.revision(), header.effectiveFrom(), header.currency(), header.status(),
                List.copyOf(lines.getOrDefault(header.id(), List.of()))))
                .collect(Collectors.groupingBy(SalaryHeader::staffId, LinkedHashMap::new, Collectors.toList()));
    }

    private void insertEvidence(Scope scope, UUID runId, List<CalculatedStaff> staff, Instant now,
            Map<UUID, PeoplePayrollBridge.PayrollAssignmentSnapshot> assignments) {
        for (CalculatedStaff result : staff) {
            UUID resultId = UUID.randomUUID();
            var assignment = Objects.requireNonNull(assignments.get(result.staff().id()),
                    "Eligible payroll staff assignment snapshot is unavailable");
            jdbc.update("""
                    INSERT INTO payroll_run_staff_results(id,organisation_id,school_id,payroll_run_id,
                      staff_profile_id,salary_profile_id,employee_code_snapshot,staff_name_snapshot,
                      salary_revision_snapshot,scheduled_working_days,recorded_days,payable_days,
                      paid_leave_days,unpaid_leave_days,missing_attendance_days,proration_factor,
                      full_gross_amount,payable_gross_amount,deduction_amount,
                      employer_contribution_amount,net_amount,warning_codes,created_at,
                      assignment_snapshot_status,assignment_snapshot_date,assignment_id_snapshot,
                      designation_name_snapshot,department_name_snapshot)
                    VALUES(:id,:organisationId,:schoolId,:runId,:staffId,:salaryId,:employeeCode,
                      :staffName,:revision,:scheduled,:recorded,:payable,:paidLeave,:unpaidLeave,
                      :missing,:factor,:fullGross,:payableGross,:deductions,:employer,:net,
                      CASE WHEN :warnings='' THEN ARRAY[]::text[]
                           ELSE string_to_array(:warnings,',') END,:now,
                      :assignmentStatus,:assignmentDate,:assignmentId,:designationName,:departmentName)
                    """, scope.params().addValue("id", resultId).addValue("runId", runId)
                    .addValue("staffId", result.staff().id()).addValue("salaryId", result.salary().id())
                    .addValue("employeeCode", result.staff().employeeCode())
                    .addValue("staffName", result.staff().displayName())
                    .addValue("revision", result.salary().revision()).addValue("scheduled", result.scheduled())
                    .addValue("recorded", result.recorded()).addValue("payable", result.payableDays())
                    .addValue("paidLeave", result.paidLeaveDays()).addValue("unpaidLeave", result.unpaidLeaveDays())
                    .addValue("missing", result.missing()).addValue("factor", result.factor())
                    .addValue("fullGross", result.fullGross()).addValue("payableGross", result.payableGross())
                    .addValue("deductions", result.deductions()).addValue("employer", result.employer())
                    .addValue("net", result.net()).addValue("warnings", String.join(",", result.warnings()))
                    .addValue("now", now).addValue("assignmentStatus", assignment.status())
                    .addValue("assignmentDate", assignment.asOfDate()).addValue("assignmentId", assignment.assignmentId())
                    .addValue("designationName", assignment.designationName()).addValue("departmentName", assignment.departmentName()));
            for (CalculatedLine line : result.lines()) jdbc.update("""
                    INSERT INTO payroll_run_staff_lines(id,organisation_id,school_id,
                      payroll_run_staff_result_id,payroll_component_id,component_code_snapshot,
                      component_name_snapshot,component_kind_snapshot,calculation_method_snapshot,
                      attendance_prorated_snapshot,statutory_snapshot,full_amount,payable_amount,created_at)
                    VALUES(:id,:organisationId,:schoolId,:resultId,:componentId,:code,:name,:kind,
                      :method,:prorated,:statutory,:full,:payable,:now)
                    """, scope.params().addValue("id", UUID.randomUUID()).addValue("resultId", resultId)
                    .addValue("componentId", line.source().componentId()).addValue("code", line.source().code())
                    .addValue("name", line.source().name()).addValue("kind", line.source().kind())
                    .addValue("method", line.source().method()).addValue("prorated", line.source().prorated())
                    .addValue("statutory", line.source().statutory()).addValue("full", line.full())
                    .addValue("payable", line.payable()).addValue("now", now));
            recovery.insert(scope.organisationId(), scope.schoolId(), resultId, result.recoveries(), now);
            for (PayrollAttendanceDay day : result.days()) jdbc.update("""
                    INSERT INTO payroll_run_attendance_sources(id,organisation_id,school_id,
                      payroll_run_staff_result_id,calendar_day_id,staff_attendance_register_id,
                      staff_attendance_mark_id,attendance_date,state_code_snapshot,payroll_credit,
                      staff_leave_request_id,leave_type_code_snapshot,paid_leave_snapshot,leave_fraction,
                      register_row_version,mark_row_version,created_at)
                    VALUES(:id,:organisationId,:schoolId,:resultId,:calendarDayId,:registerId,:markId,
                      :date,:state,:credit,:leaveId,:leaveCode,:paidLeave,:leaveFraction,
                      :registerVersion,:markVersion,:now)
                    """, scope.params().addValue("id", UUID.randomUUID()).addValue("resultId", resultId)
                    .addValue("calendarDayId", day.calendarDayId()).addValue("registerId", day.registerId())
                    .addValue("markId", day.markId()).addValue("date", day.attendanceDate())
                    .addValue("state", day.stateCode()).addValue("credit", day.payrollCredit())
                    .addValue("leaveId", day.leaveRequestId()).addValue("leaveCode", day.leaveTypeCode())
                    .addValue("paidLeave", day.paidLeave()).addValue("leaveFraction", day.leaveFraction())
                    .addValue("registerVersion", day.registerRowVersion())
                    .addValue("markVersion", day.markRowVersion()).addValue("now", now));
        }
    }

    private Map<UUID, List<RunLineRow>> loadRunLines(Scope scope, List<UUID> runIds) {
        return jdbc.query("""
                SELECT result.payroll_run_id,line.* FROM payroll_run_staff_lines line
                JOIN payroll_run_staff_results result ON result.id=line.payroll_run_staff_result_id
                  AND result.organisation_id=line.organisation_id AND result.school_id=line.school_id
                WHERE line.organisation_id=:organisationId AND line.school_id=:schoolId
                  AND result.payroll_run_id IN (:runIds)
                ORDER BY line.payroll_run_staff_result_id,line.component_kind_snapshot,
                         line.component_code_snapshot,line.id
                """, scope.params().addValue("runIds", runIds), result -> {
                    Map<UUID, List<RunLineRow>> grouped = new LinkedHashMap<>();
                    while (result.next()) {
                        UUID resultId = result.getObject("payroll_run_staff_result_id", UUID.class);
                        grouped.computeIfAbsent(resultId, ignored -> new ArrayList<>()).add(new RunLineRow(
                                result.getObject("id", UUID.class),
                                result.getObject("payroll_component_id", UUID.class),
                                result.getString("component_code_snapshot"),
                                result.getString("component_name_snapshot"),
                                result.getString("component_kind_snapshot"),
                                result.getString("calculation_method_snapshot"),
                                result.getBoolean("attendance_prorated_snapshot"),
                                result.getBoolean("statutory_snapshot"), result.getBigDecimal("full_amount"),
                                result.getBigDecimal("payable_amount")));
                    }
                    return grouped;
                });
    }

    private Map<UUID, List<PayrollRunWorkspace.RunStaffItem>> loadRunStaff(Scope scope,
            List<UUID> runIds, Map<UUID, List<RunLineRow>> linesByResult) {
        Map<UUID, List<PayrollRecoveryService.Line>> recoveries = recovery.forRuns(
                scope.organisationId(), scope.schoolId(), runIds);
        return jdbc.query("""
                SELECT * FROM payroll_run_staff_results
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND payroll_run_id IN (:runIds)
                ORDER BY payroll_run_id,staff_name_snapshot,staff_profile_id
                """, scope.params().addValue("runIds", runIds), result -> {
                    Map<UUID, List<PayrollRunWorkspace.RunStaffItem>> grouped = new LinkedHashMap<>();
                    while (result.next()) {
                        UUID runId = result.getObject("payroll_run_id", UUID.class);
                        UUID resultId = result.getObject("id", UUID.class);
                        List<PayrollRunWorkspace.RunLineItem> lines = linesByResult
                                .getOrDefault(resultId, List.of()).stream().map(RunLineRow::item).toList();
                        grouped.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(
                                new PayrollRunWorkspace.RunStaffItem(resultId,
                                        result.getObject("staff_profile_id", UUID.class),
                                        result.getObject("salary_profile_id", UUID.class),
                                        result.getString("employee_code_snapshot"),
                                        result.getString("staff_name_snapshot"),
                                        result.getInt("salary_revision_snapshot"),
                                        result.getInt("scheduled_working_days"), result.getInt("recorded_days"),
                                        result.getBigDecimal("payable_days"), result.getBigDecimal("paid_leave_days"),
                                        result.getBigDecimal("unpaid_leave_days"),
                                        result.getInt("missing_attendance_days"),
                                        result.getBigDecimal("proration_factor"),
                                        result.getBigDecimal("full_gross_amount"),
                                        result.getBigDecimal("payable_gross_amount"),
                                        result.getBigDecimal("deduction_amount"),
                                        result.getBigDecimal("employer_contribution_amount"),
                                        result.getBigDecimal("net_amount"), strings(result.getArray("warning_codes")),
                                        lines, recoveries.getOrDefault(resultId, List.of()).stream()
                                                .map(PayrollRecoveryService.Line::item).toList()));
                    }
                    return grouped;
                });
    }

    private Map<UUID, List<PayrollRunWorkspace.RunHistoryItem>> loadRunHistory(Scope scope,
            List<UUID> runIds) {
        return jdbc.query("""
                SELECT history.*,actor_user.display_name AS actor_name
                FROM payroll_run_history history
                JOIN memberships actor ON actor.id=history.actor_membership_id
                  AND actor.organisation_id=history.organisation_id AND actor.school_id=history.school_id
                JOIN user_accounts actor_user ON actor_user.id=actor.user_id
                WHERE history.organisation_id=:organisationId AND history.school_id=:schoolId
                  AND history.payroll_run_id IN (:runIds)
                ORDER BY history.payroll_run_id,history.occurred_at,history.id
                """, scope.params().addValue("runIds", runIds), result -> {
                    Map<UUID, List<PayrollRunWorkspace.RunHistoryItem>> grouped = new LinkedHashMap<>();
                    while (result.next()) {
                        UUID runId = result.getObject("payroll_run_id", UUID.class);
                        grouped.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(
                                new PayrollRunWorkspace.RunHistoryItem(result.getObject("id", UUID.class),
                                        result.getString("event_type"), result.getString("previous_status"),
                                        result.getString("new_status"), result.getString("reason"),
                                        result.getString("actor_name"), result.getTimestamp("occurred_at").toInstant()));
                    }
                    return grouped;
                });
    }

    private Map<UUID, List<PayrollRunWorkspace.RunReversalItem>> loadRunReversals(
            Scope scope, List<UUID> runIds) {
        return jdbc.query("""
                SELECT request.*,requester_user.display_name AS requester_name,
                  decider_user.display_name AS decider_name,
                  payroll_run_actor_is_independent(:actorMembershipId,
                    request.requested_by_membership_id,NULL,
                    request.organisation_id,request.school_id) AS actor_independent
                FROM payroll_run_reversal_requests request
                JOIN memberships requester ON requester.id=request.requested_by_membership_id
                  AND requester.organisation_id=request.organisation_id
                  AND requester.school_id=request.school_id
                JOIN user_accounts requester_user ON requester_user.id=requester.user_id
                LEFT JOIN memberships decider ON decider.id=request.decided_by_membership_id
                  AND decider.organisation_id=request.organisation_id
                  AND decider.school_id=request.school_id
                LEFT JOIN user_accounts decider_user ON decider_user.id=decider.user_id
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND request.payroll_run_id IN (:runIds)
                ORDER BY request.payroll_run_id,request.requested_at,request.id
                """, scope.params().addValue("runIds", runIds), result -> {
                    Map<UUID, List<PayrollRunWorkspace.RunReversalItem>> grouped =
                            new LinkedHashMap<>();
                    while (result.next()) {
                        UUID runId = result.getObject("payroll_run_id", UUID.class);
                        String status = result.getString("status");
                        boolean canDecide = "PENDING".equals(status) && scope.canReverseApprove()
                                && result.getBoolean("actor_independent");
                        grouped.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(
                                new PayrollRunWorkspace.RunReversalItem(
                                        result.getObject("id", UUID.class), status,
                                        result.getLong("requested_run_version"),
                                        result.getString("request_reason"),
                                        result.getString("requester_name"),
                                        result.getTimestamp("requested_at").toInstant(),
                                        result.getString("decider_name"),
                                        instant(result.getTimestamp("decided_at")),
                                        result.getString("decision_reason"), canDecide,
                                        result.getLong("row_version")));
                    }
                    return grouped;
                });
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId) {
        if (user.activeOrganisationId() == null) throw conflict("ORGANISATION_SELECTION_REQUIRED",
                "Select a school organisation first.");
        List<Scope> rows = jdbc.query("""
                SELECT school.id AS school_id,school.display_name,membership.id AS membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.configure') can_configure,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.run.prepare') can_prepare,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.run.approve') can_approve,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id
                      AND rc.capability_code='payroll.run.reverse.request') can_reverse_request,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id
                      AND rc.capability_code='payroll.run.reverse.approve') can_reverse_approve
                FROM memberships membership JOIN schools school ON school.id=membership.school_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN (:membershipIds) AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.read')
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds()).addValue("schoolId", requestedSchoolId),
                (r, n) -> new Scope(user.activeOrganisationId(), r.getObject("school_id", UUID.class),
                        r.getString("display_name"), r.getObject("membership_id", UUID.class),
                        r.getBoolean("can_configure"), r.getBoolean("can_prepare"),
                        r.getBoolean("can_approve"), r.getBoolean("can_reverse_request"),
                        r.getBoolean("can_reverse_approve")));
        if (rows.isEmpty()) throw forbidden("PAYROLL_SCOPE_DENIED",
                "Payroll access is unavailable for this school.");
        return rows.getFirst();
    }

    private Scope requireCapability(AuthenticatedUser user, UUID schoolId, String capability) {
        Scope scope = requireScope(user, schoolId);
        if (!scope.has(capability)) throw forbidden("PAYROLL_CAPABILITY_DENIED",
                "This payroll action is outside your authorized role.");
        return scope;
    }

    private Policy activePolicy(Scope scope) {
        List<Policy> rows = jdbc.query("""
                SELECT id,display_name,currency_code,zero_scheduled_days_action,proration_scale,
                       status,row_version
                FROM payroll_run_policies
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                ORDER BY updated_at DESC,id LIMIT 1
                """, scope.params(), (r, n) -> new Policy(r.getObject("id", UUID.class),
                r.getString("display_name"), r.getString("currency_code"),
                r.getString("zero_scheduled_days_action"), r.getInt("proration_scale"),
                r.getString("status"), r.getLong("row_version")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredRun lockRun(Scope scope, UUID id) {
        List<StoredRun> rows = jdbc.query("""
                SELECT id,payroll_month,input_fingerprint,status,created_by_membership_id,row_version,recovery_snapshot_version
                FROM payroll_runs WHERE id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), (r, n) -> new StoredRun(
                r.getObject("id", UUID.class), r.getObject("payroll_month", LocalDate.class),
                r.getString("input_fingerprint"), r.getString("status"),
                r.getObject("created_by_membership_id", UUID.class), r.getLong("row_version"),
                r.getInt("recovery_snapshot_version")));
        if (rows.isEmpty()) throw notFound("PAYROLL_RUN_NOT_FOUND", "The payroll run was not found.");
        return rows.getFirst();
    }

    private StoredReversal lockReversal(Scope scope, UUID id) {
        List<StoredReversal> rows = jdbc.query("""
                SELECT request.id,request.payroll_run_id,request.requested_run_version,
                  request.request_fingerprint,request.status,request.requested_by_membership_id,
                  request.row_version,run.status AS run_status,run.row_version AS run_version
                FROM payroll_run_reversal_requests request
                JOIN payroll_runs run ON run.id=request.payroll_run_id
                  AND run.organisation_id=request.organisation_id
                  AND run.school_id=request.school_id
                WHERE request.id=:id AND request.organisation_id=:organisationId
                  AND request.school_id=:schoolId
                FOR UPDATE OF request,run
                """, scope.params().addValue("id", id), (r, n) -> new StoredReversal(
                r.getObject("id", UUID.class), r.getObject("payroll_run_id", UUID.class),
                r.getLong("requested_run_version"), r.getString("request_fingerprint"),
                r.getString("status"), r.getObject("requested_by_membership_id", UUID.class),
                r.getLong("row_version"), r.getString("run_status"), r.getLong("run_version")));
        if (rows.isEmpty()) throw notFound("PAYROLL_REVERSAL_NOT_FOUND",
                "The payroll reversal request was not found.");
        return rows.getFirst();
    }

    private StoredReversal reversalByKey(Scope scope, String key) {
        List<StoredReversal> rows = jdbc.query("""
                SELECT request.id,request.payroll_run_id,request.requested_run_version,
                  request.request_fingerprint,request.status,request.requested_by_membership_id,
                  request.row_version,run.status AS run_status,run.row_version AS run_version
                FROM payroll_run_reversal_requests request
                JOIN payroll_runs run ON run.id=request.payroll_run_id
                  AND run.organisation_id=request.organisation_id
                  AND run.school_id=request.school_id
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND request.idempotency_key=:key
                """, scope.params().addValue("key", key), (r, n) -> new StoredReversal(
                r.getObject("id", UUID.class), r.getObject("payroll_run_id", UUID.class),
                r.getLong("requested_run_version"), r.getString("request_fingerprint"),
                r.getString("status"), r.getObject("requested_by_membership_id", UUID.class),
                r.getLong("row_version"), r.getString("run_status"), r.getLong("run_version")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredIdempotent idempotent(Scope scope, String key) {
        List<StoredIdempotent> rows = jdbc.query("""
                SELECT id,payroll_month,input_fingerprint,creation_reason,row_version
                FROM payroll_runs WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND idempotency_key=:key
                """, scope.params().addValue("key", key), (r, n) -> new StoredIdempotent(
                r.getObject("id", UUID.class), r.getObject("payroll_month", LocalDate.class),
                r.getString("input_fingerprint"), r.getString("creation_reason"), r.getLong("row_version")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static PayrollMutationResponse idempotentResult(StoredIdempotent existing,
            LocalDate month, String inputFingerprint, String why) {
        if (!existing.month().equals(month) || !existing.fingerprint().equals(inputFingerprint)
                || !existing.reason().equals(why)) throw conflict("PAYROLL_RUN_IDEMPOTENCY_CONFLICT",
                "This idempotency key was already used for different payroll inputs.");
        return new PayrollMutationResponse(existing.id(), existing.version());
    }

    private void history(Scope scope, UUID runId, String event, String previous,
            String next, String changeReason) {
        jdbc.update("""
                INSERT INTO payroll_run_history(id,organisation_id,school_id,payroll_run_id,event_type,
                  previous_status,new_status,reason,actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:runId,:event,:previous,:next,:reason,
                  :actorMembershipId,:now)
                """, scope.params().addValue("id", UUID.randomUUID()).addValue("runId", runId)
                .addValue("event", event).addValue("previous", previous).addValue("next", next)
                .addValue("reason", reason(changeReason)).addValue("now", Instant.now(clock)));
    }

    private void reversalHistory(Scope scope, UUID requestId, UUID runId, String event,
            String previous, String next, String changeReason, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO payroll_run_reversal_history(
                  id,organisation_id,school_id,payroll_run_reversal_request_id,payroll_run_id,
                  event_type,previous_status,new_status,reason,actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:requestId,:runId,:event,:previous,:next,
                  :reason,:actorMembershipId,:now)
                """, scope.params().addValue("id", UUID.randomUUID())
                .addValue("requestId", requestId).addValue("runId", runId)
                .addValue("event", event).addValue("previous", previous).addValue("next", next)
                .addValue("reason", reason(changeReason)).addValue("now", occurredAt));
    }

    private void audit(AuthenticatedUser user, Scope scope, String action, String type,
            UUID id, String why) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type,
                id.toString(), AuditOutcome.SUCCEEDED, clean(why), Map.of());
    }

    private static void requireReady(CalculatedPreview calculated, String expectedFingerprint) {
        if (!calculated.view().ready()) throw conflict("PAYROLL_RUN_PREVIEW_BLOCKED",
                "Resolve every payroll preview blocker before continuing.");
        if (!calculated.view().inputFingerprint().equals(expectedFingerprint)) {
            throw conflict("PAYROLL_RUN_INPUTS_CHANGED",
                    "Payroll inputs changed after preview. Refresh and review the new calculation.");
        }
    }

    private static void requireStatus(StoredRun run, String expected) {
        if (!expected.equals(run.status())) throw conflict("PAYROLL_RUN_STATE_INVALID",
                "This payroll run is no longer in the required state.");
    }

    private static PolicyInput policyInput(RunPolicyRequest request) {
        String status = enumValue(request.status(), POLICY_STATUSES, "policy status");
        String zero = enumValue(request.zeroScheduledDaysAction(), ZERO_DAY_ACTIONS,
                "zero-scheduled-days action");
        String currency = required(request.currencyCode()).toUpperCase(Locale.ROOT);
        if (!currency.matches("^[A-Z]{3}$")) throw bad("PAYROLL_CURRENCY_INVALID",
                "Use a three-letter currency code.");
        String name = required(request.displayName());
        if (name.length() < 2 || name.length() > 120) throw bad("PAYROLL_POLICY_NAME_INVALID",
                "Enter a policy name between 2 and 120 characters.");
        if (request.prorationScale() < 2 || request.prorationScale() > 6) {
            throw bad("PAYROLL_PRORATION_SCALE_INVALID", "Choose a proration scale from 2 to 6.");
        }
        reason(request.reason());
        return new PolicyInput(name, currency, zero, request.prorationScale(), status);
    }

    private static PayrollRunWorkspace.RunPolicyItem item(Policy policy) {
        return policy == null ? null : new PayrollRunWorkspace.RunPolicyItem(policy.id(), policy.name(),
                policy.currency(), policy.zeroDays(), policy.scale(), policy.status(), policy.version());
    }

    private static PayrollRunPreview.PreviewBlocker blocker(PayrollStaff staff, String code,
            String message) {
        return new PayrollRunPreview.PreviewBlocker(staff.id(), staff.displayName(), code, message);
    }

    private static BigDecimal leaveDays(List<PayrollAttendanceDay> days, boolean paid) {
        return days.stream().filter(day -> day.complete() && "LEAVE".equals(day.stateCode())
                        && Boolean.valueOf(paid).equals(day.paidLeave()))
                .map(PayrollAttendanceDay::leaveFraction).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4);
    }

    private static BigDecimal lineTotal(List<CalculatedLine> lines, String kind, boolean full) {
        return lines.stream().filter(line -> kind.equals(line.source().kind()))
                .map(full ? CalculatedLine::full : CalculatedLine::payable)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }

    private static int sumInt(List<PayrollRunWorkspace.RunStaffItem> staff,
            java.util.function.ToIntFunction<PayrollRunWorkspace.RunStaffItem> value) {
        return staff.stream().mapToInt(value).sum();
    }

    private static BigDecimal sum(List<PayrollRunWorkspace.RunStaffItem> staff,
            Function<PayrollRunWorkspace.RunStaffItem, BigDecimal> value, int scale) {
        return staff.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(scale);
    }

    private static String hash(Policy policy, LocalDate month,
            List<PayrollRunPreview.PreviewBlocker> blockers, List<CalculatedStaff> staff) {
        StringBuilder canonical = new StringBuilder("payroll-run-v1|").append(month).append('|');
        if (policy != null) canonical.append(policy.id()).append('|').append(policy.version()).append('|')
                .append(policy.currency()).append('|').append(policy.zeroDays()).append('|').append(policy.scale());
        blockers.stream().sorted(Comparator.comparing(PayrollRunPreview.PreviewBlocker::code)
                        .thenComparing(item -> Objects.toString(item.staffProfileId(), "")))
                .forEach(item -> canonical.append("|B:").append(item.code()).append(':')
                        .append(Objects.toString(item.staffProfileId(), "")));
        for (CalculatedStaff item : staff.stream().sorted(
                Comparator.comparing(value -> value.staff().id())).toList()) {
            canonical.append("|S:").append(item.staff().id()).append(':').append(item.salary().id())
                    .append(':').append(item.salary().revision()).append(':').append(item.factor());
            for (CalculatedLine line : item.lines()) canonical.append("|L:")
                    .append(line.source().componentId()).append(':').append(line.full()).append(':')
                    .append(line.payable());
            for (PayrollRecoveryService.Line line : item.recoveries().stream()
                    .sorted(Comparator.comparing(PayrollRecoveryService.Line::installmentId)).toList()) {
                canonical.append("|R:").append(line.advanceId()).append(':').append(line.installmentId())
                        .append(':').append(line.postingId()).append(':').append(line.receivableId())
                        .append(':').append(line.dueMonth()).append(':').append(line.currency()).append(':').append(line.amount());
            }
            for (PayrollAttendanceDay day : item.days()) canonical.append("|A:")
                    .append(day.calendarDayId()).append(':').append(day.registerId()).append(':')
                    .append(day.registerRowVersion()).append(':').append(day.markId()).append(':')
                    .append(day.markRowVersion()).append(':').append(day.stateCode()).append(':')
                    .append(day.payrollCredit()).append(':').append(Objects.toString(day.leaveRequestId(), ""));
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        return List.of((String[]) array.getArray());
    }

    private static LocalDate month(LocalDate value) {
        if (value == null || value.getDayOfMonth() != 1) throw bad("PAYROLL_MONTH_INVALID",
                "Choose the first day of a payroll month.");
        return value;
    }

    private static String fingerprint(String value) {
        String normalized = required(value).toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[0-9a-f]{64}$")) throw bad("PAYROLL_FINGERPRINT_INVALID",
                "Refresh the payroll preview and try again.");
        return normalized;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String idempotencyKey(String value) {
        String normalized = required(value);
        if (normalized.length() < 8 || normalized.length() > 100) throw bad("PAYROLL_IDEMPOTENCY_KEY_INVALID",
                "Use a retry key between 8 and 100 characters.");
        return normalized;
    }

    private static long version(Long value) {
        if (value == null || value < 0) throw bad("EXPECTED_VERSION_REQUIRED", "Refresh and try again.");
        return value;
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value).toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(normalized)) throw bad("INVALID_VALUE", "Choose a supported " + label + ".");
        return normalized;
    }

    private static String reason(String value) {
        String why = required(value);
        if (why.length() < 3 || why.length() > 500) throw bad("PAYROLL_REASON_INVALID",
                "Enter a reason between 3 and 500 characters.");
        return why;
    }

    private static String required(String value) {
        String cleaned = clean(value);
        if (cleaned == null) throw bad("REQUIRED_VALUE", "Complete all required payroll fields.");
        return cleaned;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static <T> T safe(String code, Work<T> work) {
        try { return work.run(); }
        catch (DataAccessException exception) {
            if (!DatabaseConflict.isIntegrityRejection(exception)) throw exception;
            throw conflict(code, "The payroll run conflicts with protected payroll history.");
        }
    }

    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION", "The record changed. Refresh and try again.");
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

    @FunctionalInterface private interface Work<T> { T run(); }

    private record Scope(UUID organisationId, UUID schoolId, String schoolName,
            UUID actorMembershipId, boolean canConfigure, boolean canPrepare, boolean canApprove,
            boolean canReverseRequest, boolean canReverseApprove) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId);
        }
        boolean has(String capability) {
            return switch (capability) {
                case "payroll.configure" -> canConfigure;
                case "payroll.run.prepare" -> canPrepare;
                case "payroll.run.approve" -> canApprove;
                case "payroll.run.reverse.request" -> canReverseRequest;
                case "payroll.run.reverse.approve" -> canReverseApprove;
                default -> false;
            };
        }
    }

    private record Policy(UUID id, String name, String currency, String zeroDays,
            int scale, String status, long version) {}
    private record PolicyInput(String name, String currency, String zeroDays, int scale, String status) {}
    private record SalaryHeaderRow(UUID id, UUID staffId, int revision, LocalDate effectiveFrom,
            String currency, String status) {}
    private record SalaryHeader(UUID id, UUID staffId, int revision, LocalDate effectiveFrom,
            String currency, String status, List<SalaryLine> lines) {}
    private record SalaryLine(UUID id, UUID componentId, String code, String name, String kind,
            String method, boolean basic, boolean prorated, boolean statutory,
            BigDecimal amount, BigDecimal rate) {
        BigDecimal fullAmount(BigDecimal basicAmount) {
            return "FIXED".equals(method) ? amount.setScale(2)
                    : basicAmount.multiply(rate).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        }
    }
    private record CalculatedLine(SalaryLine source, BigDecimal full, BigDecimal payable) {
        PayrollRunWorkspace.RunLineItem item() {
            return new PayrollRunWorkspace.RunLineItem(source.id(), source.componentId(), source.code(),
                    source.name(), source.kind(), source.method(), source.prorated(), source.statutory(),
                    full, payable);
        }
    }
    private record CalculatedStaff(PayrollStaff staff, SalaryHeader salary,
            List<PayrollAttendanceDay> days, int scheduled, int recorded, BigDecimal payableDays,
            BigDecimal paidLeaveDays, BigDecimal unpaidLeaveDays, int missing, BigDecimal factor,
            BigDecimal fullGross, BigDecimal payableGross, BigDecimal deductions, BigDecimal employer,
            BigDecimal net, List<String> warnings, List<CalculatedLine> lines,
            List<PayrollRecoveryService.Line> recoveries) {
        CalculatedStaff withRecoveries(List<PayrollRecoveryService.Line> recoveryLines, List<String> recoveryWarnings) {
            BigDecimal amount = recoveryLines.stream().map(PayrollRecoveryService.Line::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            List<String> combinedWarnings = new ArrayList<>(warnings);
            recoveryWarnings.stream().filter(code -> !combinedWarnings.contains(code)).forEach(combinedWarnings::add);
            return new CalculatedStaff(staff, salary, days, scheduled, recorded, payableDays, paidLeaveDays,
                    unpaidLeaveDays, missing, factor, fullGross, payableGross, deductions.add(amount), employer,
                    net.subtract(amount), List.copyOf(combinedWarnings), lines, List.copyOf(recoveryLines));
        }
        PayrollRunWorkspace.RunStaffItem item() {
            return new PayrollRunWorkspace.RunStaffItem(UUID.nameUUIDFromBytes(
                    ("preview:" + staff.id()).getBytes(StandardCharsets.UTF_8)), staff.id(), salary.id(),
                    staff.employeeCode(), staff.displayName(), salary.revision(), scheduled, recorded,
                    payableDays, paidLeaveDays, unpaidLeaveDays, missing, factor, fullGross, payableGross,
                    deductions, employer, net, warnings, lines.stream().map(CalculatedLine::item).toList(),
                    recoveries.stream().map(PayrollRecoveryService.Line::item).toList());
        }
    }
    private record CalculatedPreview(Policy policy, List<CalculatedStaff> staff, PayrollRunPreview view) {}
    private record StoredRun(UUID id, LocalDate payrollMonth, String fingerprint, String status,
            UUID creatorMembershipId, long version, int recoveryVersion) {}
    private record StoredReversal(UUID id, UUID runId, long requestedRunVersion,
            String fingerprint, String status, UUID requesterMembershipId, long version,
            String runStatus, long runVersion) {}
    private record StoredIdempotent(UUID id, LocalDate month, String fingerprint, String reason, long version) {}
    private record RunLineRow(UUID id, UUID componentId, String code, String name, String kind,
            String method, boolean prorated, boolean statutory, BigDecimal full, BigDecimal payable) {
        PayrollRunWorkspace.RunLineItem item() {
            return new PayrollRunWorkspace.RunLineItem(id, componentId, code, name, kind, method,
                    prorated, statutory, full, payable);
        }
    }
    private record RunHeader(UUID id, LocalDate month, LocalDate periodStart, LocalDate periodEnd,
            int attempt, String runCode, String fingerprint, String currency, String status,
            int staffCount, int scheduled, int recorded, BigDecimal payable, BigDecimal fullGross,
            BigDecimal payableGross, BigDecimal deductions, BigDecimal employer, BigDecimal net,
            String creationReason, UUID creatorMembershipId, String creatorName,
            String submitterName, Instant submittedAt, String deciderName, Instant decidedAt,
            String decisionReason, long version, boolean actorIndependent) {
        PayrollRunWorkspace.RunItem item(List<PayrollRunWorkspace.RunStaffItem> staff,
                List<PayrollRunWorkspace.RunHistoryItem> history,
                List<PayrollRunWorkspace.RunReversalItem> reversals, Scope scope) {
            boolean draft = "DRAFT".equals(status);
            boolean submitted = "SUBMITTED".equals(status);
            boolean finalized = "FINALIZED".equals(status);
            boolean pendingReversal = reversals.stream()
                    .anyMatch(item -> "PENDING".equals(item.status()));
            boolean canDecideReversal = reversals.stream()
                    .anyMatch(PayrollRunWorkspace.RunReversalItem::canDecide);
            return new PayrollRunWorkspace.RunItem(id, month, periodStart, periodEnd, attempt, runCode,
                    fingerprint, currency, status, staffCount, scheduled, recorded, payable, fullGross,
                    payableGross, deductions, employer, net, creationReason, creatorName, submitterName,
                    submittedAt, deciderName, decidedAt, decisionReason,
                    draft && scope.canPrepare(),
                    draft && scope.canPrepare() && creatorMembershipId.equals(scope.actorMembershipId()),
                    submitted && scope.canApprove() && actorIndependent,
                    finalized && scope.canReverseRequest() && !pendingReversal,
                    canDecideReversal, version, staff, history, reversals);
        }
    }
}

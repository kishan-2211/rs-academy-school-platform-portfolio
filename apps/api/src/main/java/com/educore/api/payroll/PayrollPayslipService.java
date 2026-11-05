package com.educore.api.payroll;

import static com.educore.api.payroll.PayrollPayslipController.GeneratePayslipsRequest;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.payroll.PayrollPayslipRenderer.AmountEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.AttendanceEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.EmployeeEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.LineEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.PayrollEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.PayslipEvidence;
import com.educore.api.payroll.PayrollPayslipRenderer.SchoolEvidence;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PayrollPayslipService {
    private static final DateTimeFormatter STORAGE_MONTH =
            DateTimeFormatter.ofPattern("uuuu/MM").withZone(ZoneOffset.UTC);
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final PayrollPayslipRenderer renderer;
    private final PayrollPayslipObjectStore objectStore;
    private final PayrollRecoveryService recovery;

    public PayrollPayslipService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            Clock clock,
            ObjectMapper objectMapper,
            PayrollPayslipRenderer renderer,
            PayrollPayslipObjectStore objectStore, PayrollRecoveryService recovery) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.renderer = renderer;
        this.objectStore = objectStore;
        this.recovery = recovery;
    }

    @Transactional(readOnly = true)
    public PayrollPayslipWorkspace workspace(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId);
        List<PayrollPayslipWorkspace.BatchItem> batches = scope.canReadAll()
                ? jdbc.query("""
                        SELECT batch.id,batch.payroll_run_id,run.run_code,run.payroll_month,
                          batch.locale,run.status AS run_status,batch.total_count,batch.generation_reason,
                          actor.display_name AS generated_by,batch.generated_at
                        FROM payroll_payslip_batches batch
                        JOIN payroll_runs run ON run.id=batch.payroll_run_id
                          AND run.organisation_id=batch.organisation_id AND run.school_id=batch.school_id
                        JOIN memberships membership ON membership.id=batch.generated_by_membership_id
                          AND membership.organisation_id=batch.organisation_id
                          AND membership.school_id=batch.school_id
                        JOIN user_accounts actor ON actor.id=membership.user_id
                        WHERE batch.organisation_id=:organisationId AND batch.school_id=:schoolId
                        ORDER BY run.payroll_month DESC,batch.generated_at DESC,batch.id
                        LIMIT 24
                        """, scope.params(), (result, row) -> new PayrollPayslipWorkspace.BatchItem(
                        result.getObject("id", UUID.class),
                        result.getObject("payroll_run_id", UUID.class), result.getString("run_code"),
                        result.getObject("payroll_month", java.time.LocalDate.class),
                        result.getString("locale"), result.getString("run_status"),
                        result.getInt("total_count"),
                        result.getString("generation_reason"), result.getString("generated_by"),
                        result.getTimestamp("generated_at").toInstant()))
                : List.of();
        List<PayrollPayslipWorkspace.PayslipItem> payslips = jdbc.query("""
                SELECT payslip.id,payslip.payroll_payslip_batch_id,payslip.payroll_run_id,
                  payslip.payroll_run_staff_result_id,payslip.staff_profile_id,
                  payslip.payslip_number,result.employee_code_snapshot,result.staff_name_snapshot,
                  run.payroll_month,run.currency_code,run.status AS run_status,run.reversed_at,
                  run.reversal_reason,result.scheduled_working_days,
                  result.recorded_days,result.payable_days,result.payable_gross_amount,
                  result.deduction_amount,result.employer_contribution_amount,result.net_amount,
                  payslip.generated_at,payslip.pdf_sha256
                FROM payroll_payslips payslip
                JOIN payroll_runs run ON run.id=payslip.payroll_run_id
                  AND run.organisation_id=payslip.organisation_id AND run.school_id=payslip.school_id
                JOIN payroll_run_staff_results result ON result.id=payslip.payroll_run_staff_result_id
                  AND result.organisation_id=payslip.organisation_id AND result.school_id=payslip.school_id
                WHERE payslip.organisation_id=:organisationId AND payslip.school_id=:schoolId
                  AND (:canReadAll OR payslip.staff_profile_id=:selfStaffId)
                ORDER BY run.payroll_month DESC,payslip.payslip_number,payslip.id
                LIMIT 500
                """, scope.params().addValue("canReadAll", scope.canReadAll())
                .addValue("selfStaffId", scope.selfStaffId()), (result, row) ->
                new PayrollPayslipWorkspace.PayslipItem(
                        result.getObject("id", UUID.class),
                        result.getObject("payroll_payslip_batch_id", UUID.class),
                        result.getObject("payroll_run_id", UUID.class),
                        result.getObject("payroll_run_staff_result_id", UUID.class),
                        result.getObject("staff_profile_id", UUID.class), result.getString("payslip_number"),
                        result.getString("employee_code_snapshot"), result.getString("staff_name_snapshot"),
                        result.getObject("payroll_month", java.time.LocalDate.class),
                        result.getString("currency_code"), result.getString("run_status"),
                        result.getTimestamp("reversed_at") == null ? null
                                : result.getTimestamp("reversed_at").toInstant(),
                        result.getString("reversal_reason"), result.getInt("scheduled_working_days"),
                        result.getInt("recorded_days"), result.getBigDecimal("payable_days"),
                        result.getBigDecimal("payable_gross_amount"),
                        result.getBigDecimal("deduction_amount"),
                        result.getBigDecimal("employer_contribution_amount"),
                        result.getBigDecimal("net_amount"),
                        result.getTimestamp("generated_at").toInstant(), result.getString("pdf_sha256")));
        return new PayrollPayslipWorkspace(scope.schoolId(), scope.schoolName(), scope.selfStaffId(),
                scope.canReadAll(), scope.canGenerate(), batches, payslips);
    }

    @Transactional
    public PayrollPayslipMutationResponse generate(
            AuthenticatedUser user, UUID runId, String idempotencyKey, GeneratePayslipsRequest request) {
        Scope scope = requireScope(user, request.schoolId());
        if (!scope.canGenerate()) throw forbidden("PAYROLL_PAYSLIP_GENERATION_DENIED",
                "Generating private payslips is outside your authorized role.");
        String key = idempotencyKey(idempotencyKey);
        String locale = locale(request.locale());
        String reason = reason(request.reason());
        RunEvidence run = lockFinalizedRun(scope, runId);
        String requestFingerprint = sha256(runId + "|" + locale + "|" + reason);
        ExistingBatch idempotent = existingByKey(scope, key);
        if (idempotent != null) return existing(idempotent, runId, requestFingerprint);
        ExistingBatch generated = existingByRun(scope, runId);
        if (generated != null) throw conflict("PAYROLL_PAYSLIPS_ALREADY_GENERATED",
                "This finalized payroll run already has its immutable payslip batch.");

        List<ResultEvidence> results = loadResults(scope, runId);
        if (results.size() != run.staffCount()) throw conflict("PAYROLL_PAYSLIP_SOURCE_INCOMPLETE",
                "The finalized payroll evidence is incomplete; payslips were not generated.");
        UUID batchId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String bucket = required(objectStore.bucket(), "private storage bucket", 255);
        List<StoredPayslip> stored = new ArrayList<>(results.size());
        for (int index = 0; index < results.size(); index++) {
            ResultEvidence result = results.get(index);
            UUID payslipId = UUID.randomUUID();
            String number = "PSL-" + run.payrollMonth().toString().substring(0, 7).replace("-", "")
                    + "-" + String.format(Locale.ROOT, "%03d", index + 1);
            PayslipEvidence evidence = evidence(run, result, number, locale, now);
            String sourceJson = json(evidence);
            String snapshotHash = sha256(sourceJson);
            PayrollPayslipRenderer.RenderedPdf pdf = renderer.render(evidence);
            String storageKey = "payroll-payslips/" + scope.organisationId() + "/" + scope.schoolId()
                    + "/" + STORAGE_MONTH.format(now) + "/" + payslipId + ".pdf";
            store(storageKey, pdf.bytes(), pdf.sha256());
            stored.add(new StoredPayslip(payslipId, result, number, sourceJson, snapshotHash,
                    pdf.sha256(), pdf.bytes().length, bucket, storageKey));
        }

        try {
            jdbc.queryForObject("SELECT set_config('app.payroll_payslip_generate','approved',true)",
                    new MapSqlParameterSource(), String.class);
            jdbc.update("""
                    INSERT INTO payroll_payslip_batches(id,organisation_id,school_id,payroll_run_id,
                      idempotency_key,request_fingerprint,locale,generation_reason,total_count,
                      generated_by_membership_id,generated_at)
                    VALUES(:id,:organisationId,:schoolId,:runId,:key,:fingerprint,:locale,:reason,
                      :count,:actorMembershipId,:now)
                    """, scope.params().addValue("id", batchId).addValue("runId", runId)
                    .addValue("key", key).addValue("fingerprint", requestFingerprint)
                    .addValue("locale", locale).addValue("reason", reason)
                    .addValue("count", stored.size()).addValue("now", now));
            for (StoredPayslip item : stored) {
                jdbc.update("""
                        INSERT INTO payroll_payslips(id,organisation_id,school_id,
                          payroll_payslip_batch_id,payroll_run_id,payroll_run_staff_result_id,
                          staff_profile_id,payslip_number,locale,source_snapshot,snapshot_sha256,
                          pdf_sha256,pdf_size_bytes,storage_bucket,storage_key,
                          generated_by_membership_id,generated_at)
                        VALUES(:id,:organisationId,:schoolId,:batchId,:runId,:resultId,:staffId,
                          :number,:locale,CAST(:source AS jsonb),:snapshotHash,:pdfHash,:pdfSize,
                          :bucket,:storageKey,:actorMembershipId,:now)
                        """, scope.params().addValue("id", item.id()).addValue("batchId", batchId)
                        .addValue("runId", runId).addValue("resultId", item.result().id())
                        .addValue("staffId", item.result().staffId()).addValue("number", item.number())
                        .addValue("locale", locale).addValue("source", item.sourceJson())
                        .addValue("snapshotHash", item.snapshotHash()).addValue("pdfHash", item.pdfHash())
                        .addValue("pdfSize", item.pdfSize()).addValue("bucket", item.bucket())
                        .addValue("storageKey", item.storageKey()).addValue("now", now));
            }
            jdbc.query("SELECT validate_complete_payroll_payslip_batch(:id,:organisationId,:schoolId)",
                    scope.params().addValue("id", batchId), ignored -> null);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("PAYROLL_PAYSLIP_GENERATION_CONFLICT",
                    "Payslip generation conflicted with another completed request. Refresh and try again.");
        }
        audit(user, scope, "payroll.payslip.generated", "PAYROLL_PAYSLIP_BATCH", batchId, reason,
                Map.of("payrollRunId", runId.toString(), "generatedCount", stored.size(),
                        "locale", locale));
        return new PayrollPayslipMutationResponse(batchId, stored.size(), false);
    }

    @Transactional
    public PayrollPayslipDownload download(
            AuthenticatedUser user, UUID requestedSchoolId, UUID payslipId) {
        Scope scope = requireScope(user, requestedSchoolId);
        List<DownloadRow> rows = jdbc.query("""
                SELECT payslip.staff_profile_id,payslip.payslip_number,payslip.pdf_size_bytes,
                  payslip.pdf_sha256,payslip.storage_key,run.status AS run_status
                FROM payroll_payslips payslip
                JOIN payroll_runs run ON run.id=payslip.payroll_run_id
                  AND run.organisation_id=payslip.organisation_id AND run.school_id=payslip.school_id
                WHERE payslip.id=:id AND payslip.organisation_id=:organisationId
                  AND payslip.school_id=:schoolId
                """, scope.params().addValue("id", payslipId), (result, row) -> new DownloadRow(
                result.getObject("staff_profile_id", UUID.class), result.getString("payslip_number"),
                result.getLong("pdf_size_bytes"), result.getString("pdf_sha256"), result.getString("storage_key"),
                result.getString("run_status")));
        if (rows.isEmpty()) throw notFound("PAYROLL_PAYSLIP_NOT_FOUND", "The payslip was not found.");
        DownloadRow row = rows.getFirst();
        if (!scope.canReadAll() && !Objects.equals(scope.selfStaffId(), row.staffId())) {
            throw forbidden("PAYROLL_PAYSLIP_RECORD_DENIED",
                    "This private payslip does not belong to your linked staff profile.");
        }
        boolean reversedEvidence = "REVERSED".equals(row.runStatus());
        if (reversedEvidence && !scope.canReadAll()) throw conflict("PAYROLL_PAYSLIP_REVERSED",
                "This payslip was invalidated by an approved payroll reversal. Contact the school for the replacement record.");
        audit(user, scope, "payroll.payslip.downloaded", "PAYROLL_PAYSLIP", payslipId,
                "Private payroll record downloaded.", Map.of("selfService", !scope.canReadAll(),
                        "reversedEvidence", reversedEvidence));
        String prefix = reversedEvidence ? "reversed-evidence-payslip-" : "payslip-";
        return new PayrollPayslipDownload(prefix + row.number() + ".pdf", row.size(),
                output -> objectStore.copyVerifiedTo(row.key(), row.size(), row.sha256(), output));
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId) {
        if (user == null || user.activeOrganisationId() == null) throw conflict(
                "ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        if (user.membershipIds() == null || user.membershipIds().isEmpty()) throw forbidden(
                "PAYROLL_PAYSLIP_SCOPE_DENIED", "Private payslip access is unavailable for this school.");
        List<Scope> rows = jdbc.query("""
                SELECT school.id AS school_id,school.display_name AS school_name,
                  membership.id AS membership_id,self_profile.id AS self_staff_id,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.read') can_read_all,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.payslip.generate') can_generate,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.payslip.self') can_self
                FROM memberships membership
                JOIN schools school ON school.id=membership.school_id
                  AND school.organisation_id=membership.organisation_id
                LEFT JOIN staff_profiles self_profile ON self_profile.school_id=membership.school_id
                  AND self_profile.organisation_id=membership.organisation_id
                  AND self_profile.user_account_id=membership.user_id AND self_profile.status='ACTIVE'
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds) AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND (EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                        WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.read')
                    OR EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                        WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.payslip.self'))
                ORDER BY can_read_all DESC,membership.created_at,membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds()).addValue("schoolId", requestedSchoolId),
                (result, row) -> new Scope(user.activeOrganisationId(),
                        result.getObject("school_id", UUID.class), result.getString("school_name"),
                        result.getObject("membership_id", UUID.class),
                        result.getObject("self_staff_id", UUID.class), result.getBoolean("can_read_all"),
                        result.getBoolean("can_generate"), result.getBoolean("can_self")));
        if (rows.isEmpty()) throw forbidden("PAYROLL_PAYSLIP_SCOPE_DENIED",
                "Private payslip access is unavailable for this school.");
        Scope scope = rows.getFirst();
        if (!scope.canReadAll() && (!scope.canSelf() || scope.selfStaffId() == null)) {
            throw forbidden("STAFF_PROFILE_LINK_REQUIRED",
                    "Your login is not linked to an active staff profile.");
        }
        return scope;
    }

    private RunEvidence lockFinalizedRun(Scope scope, UUID id) {
        List<RunEvidence> rows = jdbc.query("""
                SELECT run.id,run.run_code,run.payroll_month,run.currency_code,run.status,
                  run.staff_count,school.display_name AS school_name,
                  COALESCE(profile.address_line,'') AS address_line,
                  COALESCE(profile.locality,'') AS locality,COALESCE(profile.city,'') AS city,
                  COALESCE(profile.state_name,'') AS state_name,
                  COALESCE(profile.public_phone,'') AS phone,COALESCE(profile.public_email,'') AS email
                FROM payroll_runs run
                JOIN schools school ON school.id=run.school_id AND school.organisation_id=run.organisation_id
                LEFT JOIN school_profiles profile ON profile.school_id=run.school_id
                  AND profile.organisation_id=run.organisation_id
                WHERE run.id=:id AND run.organisation_id=:organisationId AND run.school_id=:schoolId
                FOR UPDATE OF run
                """, scope.params().addValue("id", id), (result, row) -> new RunEvidence(
                result.getObject("id", UUID.class), result.getString("run_code"),
                result.getObject("payroll_month", java.time.LocalDate.class),
                result.getString("currency_code"), result.getString("status"),
                result.getInt("staff_count"), result.getString("school_name"),
                join(result.getString("address_line"), result.getString("locality"),
                        result.getString("city"), result.getString("state_name")),
                join(result.getString("phone"), result.getString("email"))));
        if (rows.isEmpty()) throw notFound("PAYROLL_RUN_NOT_FOUND", "The payroll run was not found.");
        RunEvidence run = rows.getFirst();
        if (!"FINALIZED".equals(run.status())) throw conflict("PAYROLL_PAYSLIP_RUN_NOT_FINALIZED",
                "Payslips can be generated only after independent payroll finalization.");
        return run;
    }

    private List<ResultEvidence> loadResults(Scope scope, UUID runId) {
        List<ResultEvidence> results = jdbc.query("""
                SELECT id,staff_profile_id,employee_code_snapshot,staff_name_snapshot,
                  scheduled_working_days,recorded_days,payable_days,paid_leave_days,
                  unpaid_leave_days,full_gross_amount,payable_gross_amount,deduction_amount,
                  employer_contribution_amount,net_amount
                FROM payroll_run_staff_results
                WHERE payroll_run_id=:runId AND organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY staff_name_snapshot,staff_profile_id,id
                """, scope.params().addValue("runId", runId), (result, row) -> new ResultEvidence(
                result.getObject("id", UUID.class), result.getObject("staff_profile_id", UUID.class),
                result.getString("employee_code_snapshot"), result.getString("staff_name_snapshot"),
                result.getInt("scheduled_working_days"), result.getInt("recorded_days"),
                result.getBigDecimal("payable_days"), result.getBigDecimal("paid_leave_days"),
                result.getBigDecimal("unpaid_leave_days"), result.getBigDecimal("full_gross_amount"),
                result.getBigDecimal("payable_gross_amount"), result.getBigDecimal("deduction_amount"),
                result.getBigDecimal("employer_contribution_amount"), result.getBigDecimal("net_amount"),
                new ArrayList<>()));
        if (results.isEmpty()) return List.of();
        Map<UUID, ResultEvidence> byId = results.stream().collect(Collectors.toMap(ResultEvidence::id,
                value -> value, (left, right) -> left, LinkedHashMap::new));
        jdbc.query("""
                SELECT line.payroll_run_staff_result_id,line.component_code_snapshot,
                  line.component_name_snapshot,line.component_kind_snapshot,line.statutory_snapshot,
                  line.full_amount,line.payable_amount
                FROM payroll_run_staff_lines line
                WHERE line.organisation_id=:organisationId AND line.school_id=:schoolId
                  AND line.payroll_run_staff_result_id IN(:resultIds)
                ORDER BY line.payroll_run_staff_result_id,line.component_kind_snapshot,
                  line.component_code_snapshot,line.id
                """, scope.params().addValue("resultIds", byId.keySet()), result -> {
                    ResultEvidence owner = byId.get(result.getObject("payroll_run_staff_result_id", UUID.class));
                    owner.lines().add(new LineEvidence(result.getString("component_code_snapshot"),
                            result.getString("component_name_snapshot"),
                            result.getString("component_kind_snapshot"), result.getBoolean("statutory_snapshot"),
                            result.getBigDecimal("full_amount"), result.getBigDecimal("payable_amount")));
                });
        recovery.forRuns(scope.organisationId(), scope.schoolId(), List.of(runId)).forEach((resultId, installments) -> {
            ResultEvidence owner = Objects.requireNonNull(byId.get(resultId), "Recovery result is unavailable");
            for (PayrollRecoveryService.Line installment : installments) {
                owner.lines().add(new LineEvidence("ADVANCE_RECOVERY:" + installment.installmentId(),
                        "Advance recovery " + installment.number() + " / " + installment.dueMonth(),
                        "DEDUCTION", false, installment.amount(), installment.amount()));
            }
        });
        return List.copyOf(results);
    }

    private PayslipEvidence evidence(
            RunEvidence run, ResultEvidence result, String number, String locale, Instant generatedAt) {
        return new PayslipEvidence(1, number, locale, run.id(), result.staffId(),
                new SchoolEvidence(run.schoolName(), run.schoolAddress(), run.schoolContact()),
                new PayrollEvidence(run.runCode(), run.payrollMonth(), run.currency()),
                new EmployeeEvidence(result.employeeCode(), result.staffName()),
                new AttendanceEvidence(result.scheduledDays(), result.recordedDays(), result.payableDays(),
                        result.paidLeaveDays(), result.unpaidLeaveDays()),
                new AmountEvidence(result.payableGross(), result.deductions(),
                        result.employerContributions(), result.net()),
                result.lines(), generatedAt);
    }

    private ExistingBatch existingByKey(Scope scope, String key) {
        return existing(scope, "batch.idempotency_key=:value", key);
    }

    private ExistingBatch existingByRun(Scope scope, UUID runId) {
        return existing(scope, "batch.payroll_run_id=CAST(:value AS uuid)", runId);
    }

    private ExistingBatch existing(Scope scope, String predicate, Object value) {
        List<ExistingBatch> rows = jdbc.query("""
                SELECT batch.id,batch.payroll_run_id,batch.request_fingerprint,batch.total_count
                FROM payroll_payslip_batches batch
                WHERE batch.organisation_id=:organisationId AND batch.school_id=:schoolId
                  AND %s
                """.formatted(predicate), scope.params().addValue("value", value), (result, row) ->
                new ExistingBatch(result.getObject("id", UUID.class),
                        result.getObject("payroll_run_id", UUID.class),
                        result.getString("request_fingerprint"), result.getInt("total_count")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static PayrollPayslipMutationResponse existing(
            ExistingBatch existing, UUID runId, String fingerprint) {
        if (!existing.runId().equals(runId) || !existing.fingerprint().equals(fingerprint)) {
            throw conflict("PAYROLL_PAYSLIP_IDEMPOTENCY_CONFLICT",
                    "This idempotency key was already used for different payslip inputs.");
        }
        return new PayrollPayslipMutationResponse(existing.id(), existing.count(), true);
    }

    private void store(String key, byte[] pdf, String hash) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("educore-payroll-payslip-", ".pdf");
            Files.write(temporary, pdf);
            objectStore.put(key, temporary, pdf.length, hash);
            registerRollbackCleanup(key);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to stage the private payroll payslip", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { /* the operating system will clean its temporary directory */ }
            }
        }
    }

    private void registerRollbackCleanup(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) return;
                try { objectStore.delete(key); }
                catch (RuntimeException ignored) { /* storage reconciliation repairs failed cleanup */ }
            }
        });
    }

    private void audit(AuthenticatedUser user, Scope scope, String action, String type,
            UUID id, String reason, Map<String, Object> metadata) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type,
                id.toString(), AuditOutcome.SUCCEEDED, clean(reason, 240), metadata);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) {
            throw new IllegalStateException("Unable to encode private payslip evidence", exception);
        }
    }

    private static String idempotencyKey(String value) {
        String result = required(value, "Idempotency-Key header", 100);
        if (!result.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{7,99}$")) throw bad(
                "PAYROLL_PAYSLIP_IDEMPOTENCY_KEY_INVALID",
                "Use an opaque idempotency key between 8 and 100 supported characters.");
        return result;
    }

    private static String locale(String value) {
        String result = required(value, "payslip language", 20);
        if (!List.of("en-IN", "hi-IN").contains(result)) throw bad(
                "PAYROLL_PAYSLIP_LOCALE_INVALID", "Choose English or Hindi for the payslip batch.");
        return result;
    }

    private static String reason(String value) {
        String result = required(value, "generation reason", 500);
        if (result.length() < 3) throw bad("PAYROLL_PAYSLIP_REASON_INVALID",
                "Enter a meaningful reason of at least 3 characters.");
        return result;
    }

    private static String required(String value, String label, int maximum) {
        String result = clean(value, maximum);
        if (result == null) throw bad("PAYROLL_PAYSLIP_REQUIRED_VALUE", "Enter the " + label + ".");
        return result;
    }

    private static String clean(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String result = value.replaceAll("[\\p{Cc}]", " ").replaceAll("\\s+", " ").strip();
        if (result.length() > maximum) throw bad("PAYROLL_PAYSLIP_VALUE_TOO_LONG",
                "A payslip value exceeds the supported length.");
        return result;
    }

    private static String join(String... values) {
        return java.util.Arrays.stream(values).filter(Objects::nonNull).map(String::strip)
                .filter(value -> !value.isBlank()).distinct().collect(Collectors.joining(", "));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    private record Scope(UUID organisationId, UUID schoolId, String schoolName,
            UUID actorMembershipId, UUID selfStaffId, boolean canReadAll,
            boolean canGenerate, boolean canSelf) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record RunEvidence(UUID id, String runCode, java.time.LocalDate payrollMonth,
            String currency, String status, int staffCount, String schoolName,
            String schoolAddress, String schoolContact) {}

    private record ResultEvidence(UUID id, UUID staffId, String employeeCode, String staffName,
            int scheduledDays, int recordedDays, BigDecimal payableDays, BigDecimal paidLeaveDays,
            BigDecimal unpaidLeaveDays, BigDecimal fullGross, BigDecimal payableGross,
            BigDecimal deductions, BigDecimal employerContributions, BigDecimal net,
            List<LineEvidence> lines) {}

    private record StoredPayslip(UUID id, ResultEvidence result, String number, String sourceJson,
            String snapshotHash, String pdfHash, long pdfSize, String bucket, String storageKey) {}

    private record ExistingBatch(UUID id, UUID runId, String fingerprint, int count) {}
    private record DownloadRow(UUID staffId, String number, long size, String sha256, String key, String runStatus) {}
}

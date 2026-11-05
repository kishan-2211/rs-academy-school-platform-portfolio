package com.educore.api.finance;

import static com.educore.api.finance.FinanceController.*;
import static com.educore.api.finance.FinanceSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class FinanceService {
    private static final Set<String> HEAD_CATEGORIES = Set.of(
            "TUITION", "ADMISSION", "ANNUAL", "MATERIAL", "TRANSPORT", "ACTIVITY", "EXAM", "OTHER");
    private static final Set<String> HEAD_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> CONCESSION_TYPES = Set.of("SCHOLARSHIP", "SIBLING", "STAFF", "HARDSHIP", "OTHER");
    private static final Set<String> PAYMENT_METHODS = Set.of("CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI");
    private static final List<String> CLOSE_METHODS = List.of(
            "CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI", "ONLINE_PROVIDER");
    private static final Set<String> GATEWAY_QUEUE_STATUSES = Set.of(
            "ALL", "HOLD", "POSTED", "REFUND_REQUIRED", "UNRESOLVED");
    // A display hint, also rechecked under the finance fence before a reviewed receipt is written.
    // The database's immutable-evidence predicate remains the source of truth for provider identity.
    private static final String GATEWAY_REVIEW_ELIGIBILITY = """
            :canCollect AND attempt.status='CAPTURED_REVIEW_REQUIRED' AND attempt.fee_payment_id IS NULL
            AND gateway_receipt_evidence_matches(capture.id,:organisationId,:schoolId)
            AND EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions held
              WHERE held.capture_id=capture.id AND held.organisation_id=:organisationId
                AND held.school_id=:schoolId AND held.resolution_type='HOLD')
            AND NOT EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions disposition
              WHERE disposition.capture_id=capture.id AND disposition.organisation_id=:organisationId
                AND disposition.school_id=:schoolId AND disposition.resolution_type IN ('POSTED','REFUND_REQUIRED'))
            AND GREATEST(capture.original_business_date,
                COALESCE(CAST(:latestCloseDate AS date)+1,capture.original_business_date))
              <=(CAST(:now AS timestamptz) AT TIME ZONE attempt.school_time_zone)::date
            AND EXISTS(SELECT 1 FROM fee_receipt_sequences sequence
              WHERE sequence.organisation_id=:organisationId AND sequence.school_id=:schoolId
                AND sequence.academic_session_id=account.academic_session_id AND sequence.next_value<999999999)
            AND EXISTS(SELECT 1 FROM fee_gateway_allocations frozen WHERE frozen.attempt_id=attempt.id
              AND frozen.organisation_id=:organisationId AND frozen.school_id=:schoolId)
            AND NOT EXISTS(SELECT 1 FROM fee_gateway_allocations frozen WHERE frozen.attempt_id=attempt.id
              AND frozen.organisation_id=:organisationId AND frozen.school_id=:schoolId AND frozen.status<>'ACTIVE')
            AND (SELECT COALESCE(sum(frozen.amount),0) FROM fee_gateway_allocations frozen
              WHERE frozen.attempt_id=attempt.id AND frozen.organisation_id=:organisationId
                AND frozen.school_id=:schoolId AND frozen.status='ACTIVE')=capture.amount
            AND NOT EXISTS(
              SELECT 1 FROM fee_gateway_allocations frozen
              LEFT JOIN learner_fee_charges charge ON charge.id=frozen.learner_fee_charge_id
                AND charge.learner_fee_account_id=account.id AND charge.organisation_id=:organisationId
                AND charge.school_id=:schoolId
              LEFT JOIN LATERAL(SELECT charge.amount
                -COALESCE((SELECT sum(entry.amount) FROM fee_ledger_entries entry
                  WHERE entry.learner_fee_charge_id=charge.id AND entry.organisation_id=:organisationId
                    AND entry.school_id=:schoolId AND entry.entry_type='CONCESSION'),0)
                -fee_charge_net_paid(charge.organisation_id,charge.school_id,charge.id,NULL::date) AS outstanding,
                COALESCE((SELECT sum(reservation.amount) FROM fee_gateway_allocations reservation
                  WHERE reservation.learner_fee_charge_id=charge.id AND reservation.organisation_id=:organisationId
                    AND reservation.school_id=:schoolId AND reservation.status='ACTIVE'
                    AND reservation.attempt_id<>attempt.id),0) AS other_reserved) obligation ON true
              WHERE frozen.attempt_id=attempt.id AND frozen.organisation_id=:organisationId AND frozen.school_id=:schoolId
                AND (charge.id IS NULL OR frozen.amount>obligation.outstanding-obligation.other_reserved
                  OR (NOT charge.allow_partial AND frozen.amount<>obligation.outstanding)))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ManualCollectionEvidence manualEvidence;

    @org.springframework.beans.factory.annotation.Autowired
    public FinanceService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit,
            ObjectMapper objectMapper, Clock clock, ManualCollectionEvidence manualEvidence) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.manualEvidence = Objects.requireNonNull(manualEvidence);
    }

    public FinanceService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit,
            ObjectMapper objectMapper, Clock clock) {
        this(jdbc, audit, objectMapper, clock, new ManualCollectionEvidence(jdbc));
    }

    @Transactional(readOnly = true)
    public CollectionRegisterResponse collections(AuthenticatedUser user, UUID schoolId, LocalDate from,
            LocalDate to, String currencyCode, String paymentMethod, String query, int page, int pageSize) {
        if (schoolId==null) throw bad("COLLECTION_REGISTER_FILTER_INVALID","Choose a school for the collection register.");
        Scope scope=requireScope(user,schoolId,"fees.read");
        var filter=new CollectionRegisterQuery.Filter(from,to,currencyCode==null?scope.schoolCurrency():currencyCode,
                paymentMethod,query,page,pageSize);
        return CollectionRegisterQuery.read(jdbc,objectMapper,scope,filter);
    }

    @Transactional
    public CollectionRegisterExportResponse exportCollections(AuthenticatedUser user, UUID schoolId, LocalDate from,
            LocalDate to, String currencyCode, String paymentMethod, String query) {
        if (schoolId==null) throw bad("COLLECTION_REGISTER_FILTER_INVALID","Choose a school for the collection register.");
        Scope scope=requireScope(user,schoolId,"fees.read");
        var filter=new CollectionRegisterQuery.Filter(from,to,currencyCode==null?scope.schoolCurrency():currencyCode,
                paymentMethod,query,0,25);
        var snapshot=CollectionRegisterQuery.export(jdbc,objectMapper,scope,filter);
        // Audit bulk disclosure, not row identities or the possibly personal search text.
        // A failed audit aborts the request; this never writes a financial record.
        audit.recordScoped(user,scope.actorMembershipId(),schoolId,"FEES_COLLECTION_REGISTER_EXPORTED",
                "SCHOOL",schoolId.toString(),AuditOutcome.SUCCEEDED,null,
                Map.of("from",from.toString(),"to",to.toString(),"currency",filter.currencyCode(),
                        "paymentMethod",paymentMethod==null?"ALL":paymentMethod,"searchApplied",filter.query()!=null,"movements",snapshot.total()));
        return new CollectionRegisterExportResponse(schoolId,scope.schoolName(),filter.currencyCode(),from,to,
                filter.paymentMethod(),filter.query(),Instant.now(clock),snapshot.total(),snapshot.summary(),snapshot.tenderTotals(),snapshot.items());
    }

    @Transactional(readOnly = true)
    public FinanceSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId, "fees.read");
        var parameters = scope.params().addValue("schoolToday", today(scope));
        List<SessionItem> sessions = jdbc.query("""
                SELECT id,name,starts_on,ends_on,status FROM academic_sessions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY starts_on DESC,id
                """, parameters, (result, row) -> new SessionItem(
                result.getObject("id", UUID.class), result.getString("name"),
                result.getObject("starts_on", LocalDate.class), result.getObject("ends_on", LocalDate.class),
                result.getString("status")));
        List<FeeHeadItem> heads = jdbc.query("""
                SELECT * FROM fee_heads WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,display_name,id
                """, parameters, (result, row) -> new FeeHeadItem(
                result.getObject("id", UUID.class), result.getString("code"), result.getString("display_name"),
                result.getString("description"), result.getString("category"), result.getBoolean("refundable"),
                result.getString("status"), result.getLong("row_version")));
        List<FeePlanItem> plans = jdbc.query("""
                SELECT * FROM fee_plans WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY created_at DESC,id
                """, parameters, (result, row) -> planRow(scope, result));
        List<LearnerOption> learners = jdbc.query("""
                SELECT learner.id AS learner_id,enrolment.id AS enrolment_id,enrolment.academic_session_id,
                       learner.legal_name,enrolment.admission_number,enrolment.grade_code,session.name AS session_name
                FROM student_enrolments enrolment
                JOIN learners learner ON learner.id=enrolment.learner_id
                JOIN academic_sessions session ON session.id=enrolment.academic_session_id
                WHERE enrolment.organisation_id=:organisationId AND enrolment.school_id=:schoolId
                  AND enrolment.status='ACTIVE' AND learner.status='ACTIVE'
                ORDER BY learner.legal_name,enrolment.starts_on DESC
                """, parameters, (result, row) -> new LearnerOption(
                result.getObject("learner_id", UUID.class), result.getObject("enrolment_id", UUID.class),
                result.getObject("academic_session_id", UUID.class), result.getString("legal_name"),
                result.getString("admission_number"), result.getString("grade_code"), result.getString("session_name")));
        List<AccountItem> accounts = jdbc.query("""
                SELECT account.*,learner.legal_name,enrolment.admission_number,enrolment.grade_code,
                       session.name AS session_name,placement.roll_number,placement.section_code,
                       COALESCE(sum(CASE WHEN ledger.entry_type='CHARGE' AND ledger.direction='DEBIT' THEN ledger.amount ELSE 0 END),0) total_charges,
                       COALESCE(sum(CASE WHEN ledger.direction='CREDIT' THEN ledger.amount
                         WHEN ledger.direction='DEBIT' AND ledger.entry_type<>'CHARGE' THEN -ledger.amount ELSE 0 END),0) total_credits,
                       COALESCE(sum(CASE WHEN ledger.direction='DEBIT' THEN ledger.amount ELSE -ledger.amount END),0) balance
                FROM learner_fee_accounts account
                JOIN learners learner ON learner.id=account.learner_id
                JOIN student_enrolments enrolment ON enrolment.id=account.enrolment_id
                JOIN academic_sessions session ON session.id=account.academic_session_id
                LEFT JOIN LATERAL (
                  SELECT placed.roll_number,section.code AS section_code
                  FROM student_section_placements placed
                  JOIN class_sections section ON section.id=placed.class_section_id
                    AND section.organisation_id=account.organisation_id AND section.school_id=account.school_id
                    AND section.academic_session_id=account.academic_session_id
                    AND section.grade_code=enrolment.grade_code AND section.status='ACTIVE'
                  WHERE placed.enrolment_id=account.enrolment_id AND placed.learner_id=account.learner_id
                    AND placed.organisation_id=account.organisation_id AND placed.school_id=account.school_id
                    AND placed.status='ACTIVE' AND placed.starts_on<=:schoolToday
                ) placement ON true
                LEFT JOIN fee_ledger_entries ledger ON ledger.learner_fee_account_id=account.id
                WHERE account.organisation_id=:organisationId AND account.school_id=:schoolId
                GROUP BY account.id,learner.legal_name,enrolment.admission_number,enrolment.grade_code,
                  session.name,placement.roll_number,placement.section_code
                ORDER BY learner.legal_name,account.opened_at DESC
                """, parameters, (result, row) -> accountRow(scope, result));
        List<ConcessionItem> concessions = jdbc.query("""
                SELECT concession.*,learner.legal_name,charge.label_snapshot,requester.display_name AS requester_name
                FROM fee_concession_requests concession
                JOIN learner_fee_accounts account ON account.id=concession.learner_fee_account_id
                JOIN learners learner ON learner.id=account.learner_id
                JOIN learner_fee_charges charge ON charge.id=concession.learner_fee_charge_id
                JOIN memberships requester_membership ON requester_membership.id=concession.requested_by_membership_id
                JOIN user_accounts requester ON requester.id=requester_membership.user_id
                WHERE concession.organisation_id=:organisationId AND concession.school_id=:schoolId
                ORDER BY CASE concession.status WHEN 'PENDING' THEN 0 ELSE 1 END,concession.created_at DESC
                """, parameters, (result, row) -> new ConcessionItem(
                result.getObject("id", UUID.class), result.getObject("learner_fee_account_id", UUID.class),
                result.getObject("learner_fee_charge_id", UUID.class), result.getString("legal_name"),
                result.getString("label_snapshot"), result.getString("concession_type"), result.getBigDecimal("amount"),
                result.getString("request_reason"), result.getString("status"), result.getString("requester_name"),
                result.getString("decision_reason"), result.getTimestamp("created_at").toInstant(),
                result.getLong("row_version")));
        AgeingSummary ageing = ageing(scope, today(scope));
        SystemCloseSnapshot currentClose = systemCloseSnapshot(scope, today(scope));
        DayCloseCandidate dayCloseCandidate = new DayCloseCandidate(today(scope),
                scope.schoolCurrency(), currentClose.collectionCount(), currentClose.grossCollections(),
                currentClose.reversalCount(), currentClose.reversalAmount(), currentClose.netCollections(),
                CLOSE_METHODS.stream().map(method -> new TenderSystemTotal(
                        method, currentClose.methodTotals().get(method))).toList(),currentClose.refundCount(),currentClose.refundAmount());
        List<DayCloseItem> dayCloses = dayCloses(scope);
        return new FinanceSnapshot(scope.schoolId(), scope.schoolName(), scope.schoolCurrency(),
                scope.timeZone(), today(scope), latestApprovedCloseDate(scope),
                scope.canConfigure(), scope.canCollect(), scope.canApprove(), scope.canReverse(),
                scope.canProposeDayClose(), scope.canApproveDayClose(), providerStatus(scope),
                ageing, dayCloseCandidate, dayCloses, sessions, heads, plans, learners, accounts, concessions);
    }

    @Transactional(readOnly = true)
    public GatewayCaptureQueueResponse gatewayCaptures(AuthenticatedUser user, UUID schoolId,
            String query, String requestedStatus, int page, int pageSize) {
        if (schoolId == null) throw bad("SCHOOL_REQUIRED", "Choose a school for financial review.");
        Scope scope = requireScope(user, schoolId, "fees.read");
        if (page < 0 || page > 100000 || pageSize < 1 || pageSize > 100)
            throw bad("GATEWAY_QUEUE_PAGE_INVALID", "Choose a supported review queue page and size.");
        if (query != null && query.length() > 100)
            throw bad("GATEWAY_QUEUE_QUERY_INVALID", "Keep the review search within 100 characters.");
        String status = requestedStatus == null ? "HOLD" : requestedStatus;
        if (!GATEWAY_QUEUE_STATUSES.contains(status))
            throw bad("GATEWAY_QUEUE_STATUS_INVALID", "Choose a supported review queue status.");
        String search = clean(query);
        LocalDate latestClose = latestApprovedCloseDate(scope);
        long offset = (long) page * pageSize;
        var parameters = scope.params().addValue("status", status)
                .addValue("query", search == null ? null : "%" + search.toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                .addValue("canCollect", scope.canCollect()).addValue("latestCloseDate", latestClose)
                .addValue("now", Instant.now(clock)).addValue("limit", pageSize).addValue("offset", offset);
        String source = """
                FROM fee_gateway_captures capture
                LEFT JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
                  AND attempt.organisation_id=capture.organisation_id AND attempt.school_id=capture.school_id
                LEFT JOIN learner_fee_accounts account ON account.id=attempt.learner_fee_account_id
                  AND account.organisation_id=capture.organisation_id AND account.school_id=capture.school_id
                LEFT JOIN learners learner ON learner.id=account.learner_id
                  AND learner.organisation_id=capture.organisation_id AND learner.school_id=capture.school_id
                LEFT JOIN student_enrolments enrolment ON enrolment.id=account.enrolment_id
                  AND enrolment.organisation_id=capture.organisation_id AND enrolment.school_id=capture.school_id
                LEFT JOIN LATERAL(SELECT resolution_type FROM fee_gateway_capture_resolutions disposition
                  WHERE disposition.capture_id=capture.id AND disposition.organisation_id=capture.organisation_id
                    AND disposition.school_id=capture.school_id
                  ORDER BY CASE disposition.resolution_type WHEN 'POSTED' THEN 0 WHEN 'REFUND_REQUIRED' THEN 1 ELSE 2 END,
                    disposition.reviewed_at DESC,disposition.id DESC LIMIT 1) disposition ON true
                LEFT JOIN LATERAL(SELECT reason,closed_day_close_id FROM fee_gateway_capture_resolutions held
                  WHERE held.capture_id=capture.id AND held.organisation_id=capture.organisation_id
                    AND held.school_id=capture.school_id AND held.resolution_type='HOLD'
                  ORDER BY held.reviewed_at DESC,held.id DESC LIMIT 1) held ON true
                LEFT JOIN fee_payments receipt ON receipt.gateway_capture_id=capture.id
                  AND receipt.organisation_id=capture.organisation_id AND receipt.school_id=capture.school_id
                WHERE capture.organisation_id=:organisationId AND capture.school_id=:schoolId
                  AND (:status='ALL' OR COALESCE(disposition.resolution_type,'UNRESOLVED')=:status)
                  AND (CAST(:query AS text) IS NULL OR lower(learner.legal_name) LIKE :query ESCAPE '\\'
                    OR lower(enrolment.admission_number) LIKE :query ESCAPE '\\'
                    OR lower(receipt.receipt_number) LIKE :query ESCAPE '\\')
                """;
        Long count = jdbc.queryForObject("SELECT count(*) " + source, parameters, Long.class);
        List<GatewayCaptureQueueResponse.CaptureItem> items = jdbc.query("""
                SELECT capture.id,capture.attempt_id,capture.amount,capture.currency_code,capture.paid_at,capture.paid_at_exact,
                  capture.original_business_date,capture.classification,attempt.status AS attempt_status,
                  learner.id AS learner_id,learner.legal_name,enrolment.admission_number,
                  COALESCE(disposition.resolution_type,'UNRESOLVED') AS review_status,held.reason AS hold_reason,
                  COALESCE(held.closed_day_close_id,capture.closed_day_close_id) AS closed_day_close_id,
                  receipt.id AS payment_id,receipt.receipt_number,receipt.received_on,
                  CASE WHEN capture.original_business_date IS NOT NULL THEN GREATEST(capture.original_business_date,
                    COALESCE(CAST(:latestCloseDate AS date)+1,capture.original_business_date)) END AS earliest_open_date,
                """ + "COALESCE((" + GATEWAY_REVIEW_ELIGIBILITY + "),false) AS review_eligible " + source
                + " ORDER BY capture.detected_at DESC,capture.id DESC LIMIT :limit OFFSET :offset", parameters,
                (result, row) -> new GatewayCaptureQueueResponse.CaptureItem(
                        result.getObject("id", UUID.class), result.getObject("attempt_id", UUID.class),
                        result.getObject("learner_id", UUID.class), result.getString("legal_name"),
                        result.getString("admission_number"), result.getBigDecimal("amount"), result.getString("currency_code"),
                        GatewayExactInstant.read(result,"paid_at_exact","paid_at"),
                        result.getObject("original_business_date", LocalDate.class),
                        result.getString("classification"), result.getString("attempt_status"), result.getString("review_status"),
                        result.getString("hold_reason"), result.getObject("closed_day_close_id", UUID.class),
                        result.getObject("payment_id", UUID.class), result.getString("receipt_number"),
                        result.getObject("received_on", LocalDate.class), result.getObject("earliest_open_date", LocalDate.class),
                        result.getBoolean("review_eligible")));
        long total = count == null ? 0 : count;
        return new GatewayCaptureQueueResponse(scope.schoolId(), today(scope), latestClose,
                items, page, pageSize, total, offset + items.size() < total);
    }

    @Transactional
    public GatewayPostingResult postReviewedGatewayCapture(AuthenticatedUser user, UUID captureId,
            GatewayCapturePostingRequest request) {
        if (captureId == null || request == null || request.schoolId() == null || request.openDate() == null)
            throw bad("GATEWAY_POSTING_REQUEST_INVALID", "Include the capture, school and reviewed open date.");
        String why = reason(request.reason());
        if (request.reason().length() > 240)
            throw bad("GATEWAY_POSTING_REASON_INVALID", "Keep the financial review reason within 240 characters.");
        Scope scope = requireScope(user, request.schoolId(), "fees.collect");
        return postVerifiedGatewayCapture(scope.organisationId(), scope.schoolId(), captureId,
                user, request.openDate(), why);
    }

    private boolean reviewedGatewayCaptureEligible(Scope scope, UUID captureId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fee_gateway_captures capture "
                + "JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id "
                + "AND attempt.organisation_id=capture.organisation_id AND attempt.school_id=capture.school_id "
                + "JOIN learner_fee_accounts account ON account.id=attempt.learner_fee_account_id "
                + "AND account.organisation_id=capture.organisation_id AND account.school_id=capture.school_id "
                + "WHERE capture.id=:captureId AND capture.organisation_id=:organisationId AND capture.school_id=:schoolId AND ("
                + GATEWAY_REVIEW_ELIGIBILITY + "))", scope.params().addValue("captureId", captureId)
                .addValue("canCollect", scope.canCollect()).addValue("latestCloseDate", latestApprovedCloseDate(scope))
                .addValue("now", Instant.now(clock)), Boolean.class));
    }

    @Transactional
    public FinanceMutationResponse createFeeHead(AuthenticatedUser user, FeeHeadRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.configure");
        UUID id = UUID.randomUUID();
        safe("FEE_HEAD_EXISTS", () -> jdbc.update("""
                INSERT INTO fee_heads(id,organisation_id,school_id,code,display_name,description,category,
                  refundable,status,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:code,:name,:description,:category,:refundable,:status,:now,:now,0)
                """, headParams(scope, id, request, null)));
        audit(user, scope, "finance.fee-head.created", "FEE_HEAD", id, request.reason());
        return new FinanceMutationResponse(id, 0);
    }

    @Transactional
    public FinanceMutationResponse updateFeeHead(AuthenticatedUser user, UUID id, FeeHeadRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.configure");
        long version = version(request.expectedVersion());
        int changed = safe("FEE_HEAD_EXISTS", () -> jdbc.update("""
                UPDATE fee_heads SET display_name=:name,description=:description,category=:category,
                  refundable=:refundable,status=:status,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND code=:code AND row_version=:version
                """, headParams(scope, id, request, version)));
        changed(changed);
        audit(user, scope, "finance.fee-head.updated", "FEE_HEAD", id, reason(request.reason()));
        return new FinanceMutationResponse(id, version + 1);
    }

    @Transactional
    public FinanceMutationResponse createPlan(AuthenticatedUser user, FeePlanRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.configure");
        validatePlanRevision(scope, request, null);
        UUID id = UUID.randomUUID();
        safe("FEE_PLAN_EXISTS", () -> jdbc.update("""
                INSERT INTO fee_plans(id,organisation_id,school_id,academic_session_id,supersedes_plan_id,
                  plan_code,revision_number,display_name,grade_code,currency_code,public_disclosure,receipt_prefix,
                  notes,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:sessionId,:supersedesId,:code,:revision,:name,:grade,
                  :currency,false,:prefix,:notes,'DRAFT',:actorMembershipId,:now,:now,0)
                """, planParams(scope, id, request, null)));
        audit(user, scope, "finance.plan.created", "FEE_PLAN", id, request.reason());
        return new FinanceMutationResponse(id, 0);
    }

    @Transactional
    public FinanceMutationResponse updatePlan(AuthenticatedUser user, UUID id, FeePlanRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.configure");
        long version = version(request.expectedVersion());
        validatePlanRevision(scope, request, id);
        int changed = safe("FEE_PLAN_EXISTS", () -> jdbc.update("""
                UPDATE fee_plans SET display_name=:name,grade_code=:grade,currency_code=:currency,
                  receipt_prefix=:prefix,notes=:notes,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sessionId AND supersedes_plan_id IS NOT DISTINCT FROM :supersedesId
                  AND plan_code=:code AND revision_number=:revision AND status='DRAFT' AND row_version=:version
                """, planParams(scope, id, request, version)));
        changed(changed);
        audit(user, scope, "finance.plan.updated", "FEE_PLAN", id, reason(request.reason()));
        return new FinanceMutationResponse(id, version + 1);
    }

    @Transactional
    public FinanceMutationResponse addPlanItem(AuthenticatedUser user, UUID planId, PlanItemRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.configure");
        int valid = jdbc.queryForObject("""
                SELECT count(*) FROM fee_plans plan
                JOIN academic_sessions session ON session.id=plan.academic_session_id
                JOIN fee_heads head ON head.id=:headId AND head.organisation_id=plan.organisation_id
                  AND head.school_id=plan.school_id AND head.status='ACTIVE'
                WHERE plan.id=:planId AND plan.organisation_id=:organisationId AND plan.school_id=:schoolId
                  AND plan.status='DRAFT' AND :dueOn BETWEEN session.starts_on AND session.ends_on
                """, scope.params().addValue("planId", planId).addValue("headId", request.feeHeadId())
                .addValue("dueOn", request.dueOn()), Integer.class);
        if (valid != 1) throw bad("FEE_PLAN_ITEM_CONTEXT_INVALID",
                "Use an active fee head and a due date inside the draft plan's academic session.");
        UUID id = UUID.randomUUID();
        safe("FEE_PLAN_ITEM_EXISTS", () -> jdbc.update("""
                INSERT INTO fee_plan_items(id,organisation_id,school_id,fee_plan_id,fee_head_id,
                  sequence_number,label,due_on,amount,allow_partial,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:planId,:headId,:sequence,:label,:dueOn,:amount,
                  :allowPartial,:now,:now,0)
                """, scope.params().addValue("id", id).addValue("planId", planId)
                .addValue("headId", request.feeHeadId()).addValue("sequence", request.sequenceNumber())
                .addValue("label", required(request.label())).addValue("dueOn", request.dueOn())
                .addValue("amount", money(request.amount())).addValue("allowPartial", request.allowPartial())
                .addValue("now", Instant.now(clock))));
        audit(user, scope, "finance.plan-item.created", "FEE_PLAN_ITEM", id, request.reason());
        return new FinanceMutationResponse(id, 0);
    }

    @Transactional
    public void deletePlanItem(AuthenticatedUser user, UUID schoolId, UUID planId, UUID itemId, String changeReason) {
        Scope scope = requireScope(user, schoolId, "fees.configure");
        int changed = jdbc.update("""
                DELETE FROM fee_plan_items WHERE id=:itemId AND fee_plan_id=:planId
                  AND organisation_id=:organisationId AND school_id=:schoolId
                  AND EXISTS(SELECT 1 FROM fee_plans WHERE id=:planId AND status='DRAFT')
                """, scope.params().addValue("itemId", itemId).addValue("planId", planId));
        changed(changed);
        audit(user, scope, "finance.plan-item.deleted", "FEE_PLAN_ITEM", itemId, reason(changeReason));
    }

    @Transactional
    public FinanceMutationResponse activatePlan(AuthenticatedUser user, UUID id, PlanActivationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.approve");
        List<StoredPlan> plans = jdbc.query("""
                SELECT * FROM fee_plans WHERE id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), (result, row) -> new StoredPlan(
                result.getObject("academic_session_id", UUID.class), result.getObject("supersedes_plan_id", UUID.class),
                result.getString("receipt_prefix"), result.getString("status"), result.getLong("row_version")));
        if (plans.isEmpty()) throw notFound("FEE_PLAN_NOT_FOUND", "The fee plan was not found.");
        StoredPlan plan = plans.getFirst();
        if (!"DRAFT".equals(plan.status()) || plan.version() != request.expectedVersion())
            throw conflict("STALE_FEE_PLAN", "Refresh this draft fee plan before activating it.");
        int itemCount = jdbc.queryForObject("SELECT count(*) FROM fee_plan_items WHERE fee_plan_id=:id",
                scope.params().addValue("id", id), Integer.class);
        if (itemCount < 1) throw conflict("FEE_PLAN_EMPTY", "Add at least one scheduled fee line before activation.");
        Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO fee_receipt_sequences(organisation_id,school_id,academic_session_id,receipt_prefix,next_value,updated_at)
                VALUES(:organisationId,:schoolId,:sessionId,:prefix,1,:now)
                ON CONFLICT (school_id,academic_session_id) DO NOTHING
                """, scope.params().addValue("sessionId", plan.sessionId()).addValue("prefix", plan.prefix()).addValue("now", now));
        String configuredPrefix = jdbc.queryForObject("""
                SELECT receipt_prefix FROM fee_receipt_sequences
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND academic_session_id=:sessionId
                """, scope.params().addValue("sessionId", plan.sessionId()), String.class);
        if (!plan.prefix().equals(configuredPrefix)) throw conflict("RECEIPT_PREFIX_CONFLICT",
                "This session already has a different receipt prefix. Use one continuous school receipt sequence.");
        int changed = jdbc.update("""
                UPDATE fee_plans SET status='ACTIVE',public_disclosure=:publicDisclosure,
                  activated_by_membership_id=:actorMembershipId,activated_at=:now,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND status='DRAFT' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("version", plan.version())
                .addValue("publicDisclosure", request.publicDisclosure()).addValue("now", now));
        changed(changed);
        if (plan.supersedesId() != null) {
            int retired = jdbc.update("""
                    UPDATE fee_plans SET status='RETIRED',retired_by_membership_id=:actorMembershipId,
                      retired_at=:now,updated_at=:now,row_version=row_version+1
                    WHERE id=:supersedesId AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE'
                    """, scope.params().addValue("supersedesId", plan.supersedesId()).addValue("now", now));
            if (retired != 1) throw conflict("SUPERSEDED_PLAN_NOT_ACTIVE",
                    "The plan being superseded is no longer active. Refresh and review the revision chain.");
        }
        audit(user, scope, "finance.plan.activated", "FEE_PLAN", id, reason(request.reason()));
        return new FinanceMutationResponse(id, plan.version() + 1);
    }

    @Transactional
    public FinanceMutationResponse assignPlan(AuthenticatedUser user, PlanAssignmentRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.configure");
        List<AssignmentContext> contexts = jdbc.query("""
                SELECT plan.academic_session_id,plan.currency_code,plan.grade_code,enrolment.learner_id,
                       enrolment.id AS enrolment_id
                FROM fee_plans plan
                JOIN student_enrolments enrolment ON enrolment.id=:enrolmentId
                  AND enrolment.organisation_id=plan.organisation_id AND enrolment.school_id=plan.school_id
                  AND enrolment.academic_session_id=plan.academic_session_id AND enrolment.status='ACTIVE'
                WHERE plan.id=:planId AND plan.organisation_id=:organisationId AND plan.school_id=:schoolId
                  AND plan.status='ACTIVE' AND (plan.grade_code IS NULL OR plan.grade_code=enrolment.grade_code)
                FOR UPDATE OF plan,enrolment
                """, scope.params().addValue("planId", request.feePlanId())
                .addValue("enrolmentId", request.enrolmentId()), (result, row) -> new AssignmentContext(
                result.getObject("academic_session_id", UUID.class), result.getString("currency_code"),
                result.getObject("learner_id", UUID.class), result.getObject("enrolment_id", UUID.class)));
        if (contexts.isEmpty()) throw bad("FEE_PLAN_ENROLMENT_MISMATCH",
                "Choose an active plan matching the learner's session and grade.");
        AssignmentContext context = contexts.getFirst();
        UUID accountId = findOrCreateAccount(scope, context);
        UUID assignmentId = UUID.randomUUID();
        FeePlanItem snapshot = findPlan(scope, request.feePlanId());
        String why = reason(request.reason());
        safe("FEE_PLAN_ALREADY_ASSIGNED", () -> jdbc.update("""
                INSERT INTO fee_plan_assignments(id,organisation_id,school_id,learner_fee_account_id,
                  fee_plan_id,plan_snapshot,assigned_by_membership_id,assigned_at,reason)
                VALUES(:id,:organisationId,:schoolId,:accountId,:planId,CAST(:snapshot AS jsonb),
                  :actorMembershipId,:now,:reason)
                """, scope.params().addValue("id", assignmentId).addValue("accountId", accountId)
                .addValue("planId", request.feePlanId()).addValue("snapshot", json(snapshot))
                .addValue("now", Instant.now(clock)).addValue("reason", why)));
        for (PlanLineItem item : snapshot.items()) {
            UUID chargeId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO learner_fee_charges(id,organisation_id,school_id,learner_fee_account_id,
                      fee_plan_assignment_id,fee_plan_item_id,fee_head_code_snapshot,fee_head_name_snapshot,
                      label_snapshot,due_on,amount,allow_partial,created_at)
                    SELECT :chargeId,:organisationId,:schoolId,:accountId,:assignmentId,item.id,
                      head.code,head.display_name,item.label,item.due_on,item.amount,item.allow_partial,:now
                    FROM fee_plan_items item JOIN fee_heads head ON head.id=item.fee_head_id
                    WHERE item.id=:itemId AND item.organisation_id=:organisationId AND item.school_id=:schoolId
                    """, scope.params().addValue("chargeId", chargeId).addValue("accountId", accountId)
                    .addValue("assignmentId", assignmentId).addValue("itemId", item.id())
                    .addValue("now", Instant.now(clock)));
            ledger(scope, accountId, chargeId, "CHARGE", "DEBIT", item.amount(), item.dueOn(),
                    item.label(), "FEE_CHARGE", chargeId, null);
            BigDecimal rteExemption = jdbc.queryForObject("""
                    SELECT rte_exemption_amount FROM learner_fee_charges WHERE id=:chargeId
                        AND organisation_id=:organisationId AND school_id=:schoolId
                    """, scope.params().addValue("chargeId", chargeId), BigDecimal.class);
            if (rteExemption != null && rteExemption.signum() > 0) {
                ledger(scope, accountId, chargeId, "CONCESSION", "CREDIT", rteExemption, item.dueOn(),
                        "RTE Fee Exemption", "RTE_EXEMPTION", chargeId, null);
                audit(user, scope, "finance.rte.exemption.applied", "LEARNER_FEE_CHARGE", chargeId,
                        "Automatic Admission/Tuition exemption from original RTE admission through Class VIII.");
            }
        }
        audit(user, scope, "finance.plan.assigned", "FEE_PLAN_ASSIGNMENT", assignmentId, why);
        return new FinanceMutationResponse(accountId, 0);
    }

    @Transactional
    public FinanceMutationResponse requestConcession(AuthenticatedUser user, ConcessionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.configure");
        lockFinanceOperations(scope);
        ChargeState charge = chargeState(scope, request.accountId(), request.chargeId(), true);
        BigDecimal amount = money(request.amount());
        if (amount.compareTo(charge.available()) > 0) throw conflict("CONCESSION_EXCEEDS_OUTSTANDING",
                "The proposed concession cannot exceed this charge's current outstanding amount.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fee_concession_requests(id,organisation_id,school_id,learner_fee_account_id,
                  learner_fee_charge_id,concession_type,amount,request_reason,status,requested_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:accountId,:chargeId,:type,:amount,:reason,'PENDING',
                  :actorMembershipId,:now,:now,0)
                """, scope.params().addValue("id", id).addValue("accountId", request.accountId())
                .addValue("chargeId", request.chargeId())
                .addValue("type", enumValue(request.concessionType(), CONCESSION_TYPES, "concession type"))
                .addValue("amount", amount).addValue("reason", reason(request.reason()))
                .addValue("now", Instant.now(clock)));
        audit(user, scope, "finance.concession.requested", "FEE_CONCESSION", id, request.reason());
        return new FinanceMutationResponse(id, 0);
    }

    @Transactional
    public FinanceMutationResponse decideConcession(AuthenticatedUser user, UUID id,
            ConcessionDecisionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.approve");
        lockFinanceOperations(scope);
        List<StoredConcession> rows = jdbc.query("""
                SELECT * FROM fee_concession_requests WHERE id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), (result, row) -> new StoredConcession(
                result.getObject("learner_fee_account_id", UUID.class),
                result.getObject("learner_fee_charge_id", UUID.class), result.getBigDecimal("amount"),
                result.getString("status"), result.getObject("requested_by_membership_id", UUID.class),
                result.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("CONCESSION_NOT_FOUND", "The concession request was not found.");
        StoredConcession stored = rows.getFirst();
        if (!"PENDING".equals(stored.status()) || stored.version() != request.expectedVersion())
            throw conflict("STALE_CONCESSION", "This concession request has already changed.");
        if (stored.requesterId().equals(scope.actorMembershipId())) throw conflict("MAKER_CHECKER_REQUIRED",
                "A different authorized person must decide this concession request.");
        String decision = enumValue(request.decision(), Set.of("APPROVED", "REJECTED"), "decision");
        String why = reason(request.reason());
        UUID ledgerId = null;
        if ("APPROVED".equals(decision)) {
            ChargeState charge = chargeState(scope, stored.accountId(), stored.chargeId(), true);
            if (stored.amount().compareTo(charge.available()) > 0)
                throw conflict("CONCESSION_EXCEEDS_OUTSTANDING",
                        "The charge changed and this concession now exceeds its outstanding amount.");
            ledgerId = ledger(scope, stored.accountId(), stored.chargeId(), "CONCESSION", "CREDIT",
                    stored.amount(), today(scope), "Approved fee concession", "FEE_CONCESSION", id, null);
        }
        int changed = jdbc.update("""
                UPDATE fee_concession_requests SET status=:decision,decided_by_membership_id=:actorMembershipId,
                  decision_reason=:reason,decided_at=:now,ledger_entry_id=:ledgerId,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND status='PENDING' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("decision", decision).addValue("reason", why)
                .addValue("ledgerId", ledgerId).addValue("version", stored.version())
                .addValue("now", Instant.now(clock)));
        changed(changed);
        audit(user, scope, "finance.concession.decided", "FEE_CONCESSION", id, why);
        return new FinanceMutationResponse(id, stored.version() + 1);
    }

    @Transactional
    public FinanceMutationResponse recordPayment(AuthenticatedUser user, String idempotencyKey,
            PaymentRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.collect");
        String key = idempotency(idempotencyKey);
        BigDecimal amount = money(request.amount());
        String method = enumValue(request.paymentMethod(), PAYMENT_METHODS, "payment method");
        String externalReference = clean(request.externalReference());
        if (externalReference == null && request.bankCreditVerification() != null
                && Set.of("BANK_TRANSFER", "SCHOOL_UPI").contains(method)) {
            externalReference = clean(request.bankCreditVerification().creditedEntryReference());
        }
        if (!"CASH".equals(method) && externalReference == null) throw bad("PAYMENT_REFERENCE_REQUIRED",
                "Enter the verified bank, cheque, or school UPI reference for a non-cash collection.");
        if (request.receivedOn().isAfter(today(scope))) throw bad("FUTURE_PAYMENT_DATE",
                "A collection cannot be recorded with a future received date.");
        String fingerprint = fingerprint(request.accountId(), method, externalReference,
                amount, request.receivedOn(), clean(request.notes()), request.allocations());
        // Omitted additive fields keep the exact old digest, including historical digital retries.
        // A retry lookup must precede the newly mandatory received-bank-credit attestation.
        if (hasManualEvidenceFields(request)) fingerprint = sha256(fingerprint + "|" + manualEvidenceFingerprint(request));
        List<ExistingPayment> existing = jdbc.query("""
                SELECT id,row_version,receipt_number,request_fingerprint FROM fee_payments
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (result, row) -> new ExistingPayment(
                result.getObject("id", UUID.class), result.getLong("row_version"),
                result.getString("receipt_number"), result.getString("request_fingerprint")));
        if (!existing.isEmpty()) {
            ExistingPayment payment = existing.getFirst();
            if (!payment.fingerprint().equals(fingerprint)) throw conflict("IDEMPOTENCY_KEY_REUSED",
                    "This idempotency key was already used for a different collection.");
            return new FinanceMutationResponse(payment.id(), payment.version(), payment.receiptNumber());
        }
        lockFinanceOperations(scope);
        // A concurrent identical request may have committed while this transaction waited.
        existing = jdbc.query("""
                SELECT id,row_version,receipt_number,request_fingerprint FROM fee_payments
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (result, row) -> new ExistingPayment(
                result.getObject("id", UUID.class), result.getLong("row_version"),
                result.getString("receipt_number"), result.getString("request_fingerprint")));
        if (!existing.isEmpty()) {
            ExistingPayment payment = existing.getFirst();
            if (!payment.fingerprint().equals(fingerprint)) throw conflict("IDEMPOTENCY_KEY_REUSED",
                    "This idempotency key was already used for a different collection.");
            return new FinanceMutationResponse(payment.id(), payment.version(), payment.receiptNumber());
        }
        scope = requireScope(user, request.schoolId(), "fees.collect"); // Live authority after the finance fence wait.
        ManualCollectionEvidence.Prepared receivedFunds = null;
        var evidenceScope = new ManualCollectionEvidence.ValueScope(scope.organisationId(), scope.schoolId(),
                scope.actorMembershipId(), scope.timeZone());
        if (!"CHEQUE".equals(method)) {
            try {
                receivedFunds = manualEvidence.prepare(evidenceScope, method, externalReference,
                        new ManualCollectionEvidence.EvidenceInput(request.actualReceivedAt(), request.receivedOn(),
                                request.postingOn(), request.bankCreditVerification(), clean(request.reviewReason())));
            } catch (IllegalArgumentException invalidEvidence) {
                throw bad("MANUAL_RECEIVED_FUNDS_INVALID", "Confirm the actual received funds, payment time and eligible posting date.");
            }
            manualEvidence.assertEligible(evidenceScope, receivedFunds);
        } else if (hasManualEvidenceFields(request)) {
            throw bad("CHEQUE_EVIDENCE_UNSUPPORTED", "Use the existing cheque collection workflow without bank-credit review fields.");
        }
        LocalDate postingOn = receivedFunds == null ? request.receivedOn() : receivedFunds.postingOn();
        assertFinancialDateOpen(scope, postingOn);
        AccountState account = lockAccount(scope, request.accountId());
        if (!"ACTIVE".equals(account.status())) throw conflict("FEE_ACCOUNT_CLOSED", "This fee account is closed.");
        BigDecimal balance = accountBalance(scope, account.id()).subtract(jdbc.queryForObject("""
                SELECT COALESCE(sum(amount),0) FROM fee_gateway_allocations
                WHERE learner_fee_account_id=:accountId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND status='ACTIVE'
                """, scope.params().addValue("accountId", account.id()), BigDecimal.class));
        if (amount.compareTo(balance) > 0) throw conflict("PAYMENT_EXCEEDS_BALANCE",
                "The collection amount cannot exceed the current account balance.");
        List<AllocationValue> allocations = allocations(scope, account.id(), amount, request.allocations());
        FinanceMutationResponse posted = postReceipt(scope, account, key, fingerprint, method,
                receivedFunds == null ? externalReference : receivedFunds.receiptReference(),
                amount, postingOn, clean(request.notes()), allocations, null);
        if (receivedFunds != null) {
            manualEvidence.record(evidenceScope, posted.id(), receivedFunds);
            audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), "finance.manual.collection.verified",
                    "FEE_PAYMENT", posted.id().toString(), AuditOutcome.SUCCEEDED, clean(request.reviewReason()),
                    Map.of("paymentMethod", method, "originalReceivedOn", request.receivedOn().toString(),
                            "postingOn", postingOn.toString(), "receivedTimePrecision",
                            request.actualReceivedAt() == null ? "DATE_ONLY" : "INSTANT"));
        }
        audit(user, scope, "finance.payment.recorded", "FEE_PAYMENT", posted.id(), null);
        return posted;
    }

    /** Common atomic writer; gateway callers must first validate persisted capture evidence. */
    private FinanceMutationResponse postReceipt(Scope scope, AccountState account, String key,
            String fingerprint, String method, String externalReference, BigDecimal amount,
            LocalDate receivedOn, String notes, List<AllocationValue> allocations, UUID captureId) {
        SequenceValue sequence = issueSequence(scope, account.sessionId());
        String receiptNumber = receiptNumber(sequence.prefix(), sequence.number());
        UUID paymentId = UUID.randomUUID();
        UUID ledgerId = ledger(scope, account.id(), null, "PAYMENT", "CREDIT", amount,
                receivedOn, "Fee payment " + receiptNumber, "FEE_PAYMENT", paymentId, null, captureId);
        safe("PAYMENT_ALREADY_RECORDED", () -> jdbc.update("""
                INSERT INTO fee_payments(id,organisation_id,school_id,learner_fee_account_id,receipt_number,
                  idempotency_key,request_fingerprint,payment_method,external_reference,amount,received_on,notes,
                  status,ledger_entry_id,received_by_membership_id,created_at,updated_at,row_version,
                  actor_kind,gateway_capture_id)
                VALUES(:id,:organisationId,:schoolId,:accountId,:receipt,:key,:fingerprint,:method,:reference,
                  :amount,:receivedOn,:notes,'POSTED',:ledgerId,:actorMembershipId,:now,:now,0,
                  :actorKind,:captureId)
                """, scope.params().addValue("id", paymentId).addValue("accountId", account.id())
                .addValue("receipt", receiptNumber).addValue("key", key).addValue("fingerprint", fingerprint)
                .addValue("method", method).addValue("reference", externalReference)
                .addValue("amount", amount).addValue("receivedOn", receivedOn)
                .addValue("notes", notes).addValue("ledgerId", ledgerId)
                .addValue("actorKind", scope.actorMembershipId() == null ? "SYSTEM" : "HUMAN")
                .addValue("captureId", captureId)
                .addValue("now", Instant.now(clock))));
        for (AllocationValue allocation : allocations) {
            jdbc.update("""
                    INSERT INTO fee_payment_allocations(id,organisation_id,school_id,fee_payment_id,
                      learner_fee_charge_id,amount,created_at)
                    VALUES(:id,:organisationId,:schoolId,:paymentId,:chargeId,:amount,:now)
                    """, scope.params().addValue("id", UUID.randomUUID()).addValue("paymentId", paymentId)
                    .addValue("chargeId", allocation.chargeId()).addValue("amount", allocation.amount())
                    .addValue("now", Instant.now(clock)));
        }
        return new FinanceMutationResponse(paymentId, 0, receiptNumber);
    }

    /** Internal server boundary, never mapped to a browser success/redirect endpoint. */
    @Transactional
    public GatewayPostingResult postVerifiedGatewayCapture(UUID organisationId, UUID schoolId,
            UUID captureId, AuthenticatedUser reviewer, LocalDate openDate, String reviewReason) {
        var tenant = com.educore.api.tenancy.TenantRequestContext.current().orElseThrow(
                () -> new IllegalStateException("Gateway posting requires a bound tenant transaction"));
        if (!organisationId.equals(tenant.organisationId())
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !organisationId.equals(jdbc.queryForObject("SELECT current_app_organisation_id()",
                        new MapSqlParameterSource(), UUID.class))) {
            throw new IllegalStateException("Gateway posting requires the matching writable tenant transaction");
        }
        boolean system = reviewer == null;
        if (system && (tenant.userId() != null || openDate != null || reviewReason != null)) {
            throw new IllegalStateException("Automatic gateway posting must use honest background context");
        }
        Scope scope;
        if (system) {
            List<Scope> schools = jdbc.query("""
                    SELECT school.display_name,school.currency_code,school.time_zone FROM schools school
                    JOIN organisations organisation ON organisation.id=school.organisation_id
                    WHERE school.id=:schoolId AND school.organisation_id=:organisationId
                      AND school.status='ACTIVE' AND organisation.status='ACTIVE'
                    """, new MapSqlParameterSource().addValue("schoolId", schoolId).addValue("organisationId", organisationId),
                    (r,n) -> new Scope(organisationId,schoolId,r.getString("display_name"),r.getString("currency_code"),
                            null,false,false,false,false,false,false,r.getString("time_zone")));
            if (schools.isEmpty()) throw notFound("GATEWAY_SCHOOL_UNAVAILABLE", "Gateway posting school is unavailable.");
            scope = schools.getFirst();
        } else {
            if (!organisationId.equals(reviewer.activeOrganisationId()) || !reviewer.userId().equals(tenant.userId())) {
                throw forbiddenGateway();
            }
            scope = requireScope(reviewer, schoolId, "fees.collect");
        }
        lockFinanceOperations(scope);
        // Live authority is checked again after waiting for another finance transaction.
        if (!system) scope = requireScope(reviewer, schoolId, "fees.collect");
        List<GatewayCapture> captures = jdbc.query("""
                SELECT capture.*,attempt.learner_fee_account_id,attempt.amount AS expected_amount,
                  attempt.status AS attempt_status,attempt.checkout_reference AS expected_checkout,
                  attempt.created_at AS intent_created_at,attempt.school_time_zone,
                  observed.checkout_status,observed.amount_paid,observed.pending_payment_count,
                  observed.successful_payment_count,observed.discovery_complete
                FROM fee_gateway_captures capture JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
                  AND attempt.organisation_id=capture.organisation_id AND attempt.school_id=capture.school_id
                LEFT JOIN fee_gateway_checkout_observations observed ON observed.id=capture.checkout_observation_id
                  AND observed.attempt_id=attempt.id AND observed.organisation_id=attempt.organisation_id
                  AND observed.school_id=attempt.school_id
                WHERE capture.id=:captureId AND capture.organisation_id=:organisationId AND capture.school_id=:schoolId
                FOR UPDATE OF attempt
                """, scope.params().addValue("captureId",captureId), (r,n) -> new GatewayCapture(
                        r.getObject("attempt_id",UUID.class),r.getObject("learner_fee_account_id",UUID.class),
                        r.getBigDecimal("amount"),r.getBigDecimal("expected_amount"),r.getString("currency_code"),
                        r.getString("checkout_reference"),r.getObject("expected_checkout",UUID.class),r.getString("order_reference"),
                        r.getString("payment_reference"),GatewayExactInstant.read(r,"paid_at_exact","paid_at"),
                        r.getTimestamp("intent_created_at").toInstant(),
                        r.getObject("original_business_date",LocalDate.class),r.getString("school_time_zone"),r.getString("classification"),r.getString("attempt_status"),
                        r.getString("checkout_status"),r.getBigDecimal("amount_paid"),r.getInt("pending_payment_count"),
                        r.getInt("successful_payment_count"),r.getBoolean("discovery_complete")));
        if (captures.isEmpty()) throw notFound("GATEWAY_CAPTURE_NOT_FOUND", "The scoped gateway capture was not found.");
        GatewayCapture capture = captures.getFirst();
        List<GatewayPostingResult> posted = jdbc.query("""
                SELECT payment.id,payment.receipt_number FROM fee_gateway_capture_resolutions resolution
                JOIN fee_payments payment ON payment.id=resolution.fee_payment_id
                WHERE resolution.capture_id=:captureId AND resolution.organisation_id=:organisationId
                  AND resolution.school_id=:schoolId AND resolution.resolution_type='POSTED'
                """,scope.params().addValue("captureId",captureId), (r,n)->new GatewayPostingResult("POSTED",r.getObject("id",UUID.class),r.getString("receipt_number")));
        if (!posted.isEmpty()) return posted.getFirst();
        if (!system && !reviewedGatewayCaptureEligible(scope, captureId)) {
            throw conflict("GATEWAY_CAPTURE_REVIEW_MISMATCH",
                    "This capture is not eligible for a reviewed fee receipt. Preserve it for financial reconciliation.");
        }
        boolean exact = capture.amount().compareTo(capture.expectedAmount()) == 0 && "INR".equals(capture.currency())
                && capture.expectedCheckout().toString().equals(capture.checkout()) && !capture.paidAt().isBefore(capture.intentCreatedAt())
                && "PAID".equals(capture.checkoutStatus()) && capture.amountPaid() != null
                && capture.amountPaid().compareTo(capture.amount()) == 0 && capture.discoveryComplete()
                && capture.pending() == 0 && capture.successful() == 1;
        LocalDate date = system ? capture.originalDate() : Objects.requireNonNull(openDate,"Reviewed posting date required");
        LocalDate gatewayToday=businessDate(clock,capture.timeZone());
        boolean closed = financialDateClosed(scope,date);
        if (system && (!exact || !"MATCHED".equals(capture.classification()) || closed
                || capture.originalDate().isAfter(gatewayToday) || gatewayPreviouslyHeld(scope,captureId)
                || Set.of("RELEASED","POSTED").contains(capture.attemptStatus()))) {
            return holdGateway(scope,captureId,capture,"Verified capture requires authorized financial review");
        }
        if (!exact || Set.of("RELEASED","POSTED").contains(capture.attemptStatus())) {
            throw conflict("GATEWAY_CAPTURE_REVIEW_MISMATCH", "This capture cannot be allocated to the original fee intent; review refund or reconciliation instead.");
        }
        if (date.isBefore(capture.originalDate()) || date.isAfter(gatewayToday) || closed) {
            throw conflict("GATEWAY_POSTING_DATE_CLOSED", "Choose an eligible open financial date without changing the original gateway date.");
        }
        String why = system ? "Verified gateway capture posted on its original open financial date" : reason(reviewReason);
        AccountState account = lockAccount(scope,capture.accountId());
        if (!"ACTIVE".equals(account.status()) || !"INR".equals(account.currency())) {
            if (system) return holdGateway(scope,captureId,capture,"Fee account requires authorized financial review");
            throw conflict("GATEWAY_ACCOUNT_UNAVAILABLE","The original fee account is not eligible for posting.");
        }
        List<AllocationValue> frozen = jdbc.query("""
                SELECT learner_fee_charge_id,amount FROM fee_gateway_allocations
                WHERE attempt_id=:attemptId AND organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                ORDER BY learner_fee_charge_id FOR UPDATE
                """,scope.params().addValue("attemptId",capture.attemptId()),(r,n)->new AllocationValue(r.getObject("learner_fee_charge_id",UUID.class),r.getBigDecimal("amount")));
        if (frozen.isEmpty() || frozen.stream().map(AllocationValue::amount).reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(capture.amount()) != 0) {
            throw conflict("GATEWAY_RESERVATION_UNAVAILABLE","The original frozen fee allocations are not eligible for posting.");
        }
        for (AllocationValue allocation : frozen) {
            ChargeState charge = chargeState(scope,account.id(),allocation.chargeId(),true);
            if (allocation.amount().compareTo(charge.outstanding().subtract(charge.reserved()).add(allocation.amount())) > 0
                    || allocation.amount().compareTo(charge.outstanding()) > 0
                    || (!charge.allowPartial() && allocation.amount().compareTo(charge.outstanding()) != 0)) {
                throw conflict("GATEWAY_OBLIGATION_CHANGED","The original fee obligation needs financial review.");
            }
        }
        // RESERVED cannot jump directly to POSTED; no network operation is performed here.
        if ("RESERVED".equals(capture.attemptStatus())) jdbc.update("""
                UPDATE fee_gateway_attempts SET status='CHECKOUT_PENDING',updated_at=:now,row_version=row_version+1
                WHERE id=:attemptId AND organisation_id=:organisationId AND school_id=:schoolId
                """,scope.params().addValue("attemptId",capture.attemptId()).addValue("now",Instant.now(clock)));
        String key = "gateway:"+captureId;
        FinanceMutationResponse receipt = postReceipt(scope,account,key,
                sha256(captureId+"|"+capture.payment()+"|"+capture.amount().toPlainString()+"|"+date),
                "ONLINE_PROVIDER",capture.payment(),capture.amount(),date,null,frozen,captureId);
        jdbc.update("""
                INSERT INTO fee_gateway_capture_resolutions(id,organisation_id,school_id,capture_id,resolution_type,reason,
                  open_date,fee_payment_id,reviewed_by_user_id,reviewed_by_membership_id,reviewed_at,actor_kind,system_component,closed_day_close_id)
                VALUES(:id,:organisationId,:schoolId,:captureId,'POSTED',:reason,:date,:paymentId,:userId,:actorMembershipId,:now,:kind,:component,:closedDayId)
                """,scope.params().addValue("id",UUID.randomUUID()).addValue("captureId",captureId).addValue("reason",why)
                .addValue("date",date).addValue("paymentId",receipt.id()).addValue("userId",system?null:reviewer.userId())
                .addValue("now",Instant.now(clock)).addValue("kind",system?"SYSTEM":"HUMAN").addValue("component",system?"GATEWAY_VERIFICATION":null)
                .addValue("closedDayId",gatewayClosedDay(scope,capture.originalDate())));
        jdbc.update("""
                UPDATE fee_gateway_allocations SET status='CONSUMED',updated_at=:now,row_version=row_version+1
                WHERE attempt_id=:attemptId AND organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                """,scope.params().addValue("attemptId",capture.attemptId()).addValue("now",Instant.now(clock)));
        jdbc.update("""
                UPDATE fee_gateway_attempts SET status='POSTED',fee_payment_id=:paymentId,updated_at=:now,row_version=row_version+1
                WHERE id=:attemptId AND organisation_id=:organisationId AND school_id=:schoolId
                """,scope.params().addValue("attemptId",capture.attemptId()).addValue("paymentId",receipt.id()).addValue("now",Instant.now(clock)));
        if (system) audit.recordGatewaySystemScoped(organisationId,schoolId,captureId,receipt.id(),"POSTED");
        else audit(reviewer,scope,"finance.gateway.capture.reviewed-posted","FEE_GATEWAY_CAPTURE",captureId,why);
        return new GatewayPostingResult("POSTED",receipt.id(),receipt.receiptNumber());
    }

    private GatewayPostingResult holdGateway(Scope scope, UUID captureId, GatewayCapture capture, String why) {
        UUID closedDayId=gatewayClosedDay(scope,capture.originalDate());
        boolean sameHold=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions WHERE capture_id=:captureId
                  AND organisation_id=:organisationId AND school_id=:schoolId AND resolution_type='HOLD'
                  AND actor_kind='SYSTEM' AND closed_day_close_id IS NOT DISTINCT FROM :closedDayId)
                """,scope.params().addValue("captureId",captureId).addValue("closedDayId",closedDayId),Boolean.class));
        if (!sameHold) {
            jdbc.update("""
                    INSERT INTO fee_gateway_capture_resolutions(id,organisation_id,school_id,capture_id,resolution_type,reason,
                      reviewed_by_user_id,reviewed_by_membership_id,reviewed_at,actor_kind,system_component,closed_day_close_id)
                    VALUES(:id,:organisationId,:schoolId,:captureId,'HOLD',:reason,NULL,NULL,:now,'SYSTEM','GATEWAY_VERIFICATION',:closedDayId)
                    """,scope.params().addValue("id",UUID.randomUUID()).addValue("captureId",captureId).addValue("reason",why)
                    .addValue("now",Instant.now(clock)).addValue("closedDayId",closedDayId));
            audit.recordGatewaySystemScoped(scope.organisationId(),scope.schoolId(),captureId,null,"HOLD");
        }
        if (!Set.of("POSTED","CAPTURED_REVIEW_REQUIRED").contains(capture.attemptStatus())) jdbc.update("""
                UPDATE fee_gateway_attempts SET status='CAPTURED_REVIEW_REQUIRED',updated_at=:now,row_version=row_version+1
                WHERE id=:attemptId AND organisation_id=:organisationId AND school_id=:schoolId
                """,scope.params().addValue("attemptId",capture.attemptId()).addValue("now",Instant.now(clock)));
        return new GatewayPostingResult("CAPTURED_REVIEW_REQUIRED",null,null);
    }

    private boolean gatewayPreviouslyHeld(Scope scope, UUID captureId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions WHERE capture_id=:captureId
                  AND organisation_id=:organisationId AND school_id=:schoolId AND resolution_type IN ('HOLD','REFUND_REQUIRED'))
                """,scope.params().addValue("captureId",captureId),Boolean.class));
    }

    private boolean financialDateClosed(Scope scope, LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_day_closes WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='APPROVED' AND business_date>=:date)
                """,scope.params().addValue("date",date),Boolean.class));
    }

    private UUID gatewayClosedDay(Scope scope, LocalDate date) {
        return jdbc.queryForObject("""
                SELECT (SELECT id FROM fee_day_closes WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='APPROVED' AND business_date>=:date ORDER BY business_date DESC,id LIMIT 1)
                """,scope.params().addValue("date",date),UUID.class);
    }

    private static ApiException forbiddenGateway() {
        return new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_REVIEW_SCOPE_DENIED","Gateway review requires the authenticated financial actor and school context.");
    }

    public record GatewayPostingResult(String status, UUID paymentId, String receiptNumber) {}
    private record GatewayCapture(UUID attemptId, UUID accountId, BigDecimal amount, BigDecimal expectedAmount,
            String currency, String checkout, UUID expectedCheckout, String order, String payment, Instant paidAt,
            Instant intentCreatedAt, LocalDate originalDate, String timeZone, String classification, String attemptStatus,
            String checkoutStatus, BigDecimal amountPaid, int pending, int successful, boolean discoveryComplete) {}

    @Transactional
    public FinanceMutationResponse reversePayment(AuthenticatedUser user, UUID id,
            PaymentReversalRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.refund");
        lockFinanceOperations(scope);
        assertFinancialDateOpen(scope, today(scope));
        List<StoredPayment> rows = jdbc.query("""
                SELECT * FROM fee_payments WHERE id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), (result, row) -> new StoredPayment(
                result.getObject("learner_fee_account_id", UUID.class), result.getObject("ledger_entry_id", UUID.class),
                result.getString("receipt_number"), result.getBigDecimal("amount"),
                result.getObject("received_on", LocalDate.class), result.getString("status"),
                result.getLong("row_version"),result.getObject("gateway_capture_id",UUID.class)));
        if (rows.isEmpty()) throw notFound("PAYMENT_NOT_FOUND", "The fee payment was not found.");
        StoredPayment payment = rows.getFirst();
        if (payment.gatewayCaptureId() != null) throw conflict("GATEWAY_REFUND_REQUIRED",
                "Gateway receipts require a verified provider refund workflow, not manual reversal.");
        if (!"POSTED".equals(payment.status()) || payment.version() != request.expectedVersion())
            throw conflict("STALE_PAYMENT", "This payment is no longer available for reversal.");
        String why = reason(request.reason());
        UUID reversalId = ledger(scope, payment.accountId(), null, "PAYMENT_REVERSAL", "DEBIT",
                payment.amount(), today(scope), "Reversal of receipt " + payment.receiptNumber(),
                "FEE_PAYMENT_REVERSAL", id, payment.ledgerId());
        jdbc.queryForObject("SELECT set_config('app.finance_reversal','approved',true)",
                new MapSqlParameterSource(), String.class);
        int changed = jdbc.update("""
                UPDATE fee_payments SET status='REVERSED',reversed_by_membership_id=:actorMembershipId,
                  reversed_at=:now,reversal_reason=:reason,reversal_ledger_entry_id=:reversalId,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND status='POSTED' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("version", payment.version())
                .addValue("reason", why).addValue("reversalId", reversalId).addValue("now", Instant.now(clock)));
        changed(changed);
        audit(user, scope, "finance.payment.reversed", "FEE_PAYMENT", id, why);
        return new FinanceMutationResponse(id, payment.version() + 1, payment.receiptNumber());
    }

    @Transactional
    public FinanceMutationResponse proposeDayClose(AuthenticatedUser user, DayCloseRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.close.propose");
        LocalDate businessDate = Objects.requireNonNull(request.businessDate(), "businessDate");
        if (businessDate.isAfter(today(scope))) throw bad("FUTURE_DAY_CLOSE",
                "A finance close cannot be prepared for a future date.");
        lockFinanceOperations(scope);
        assertCloseDateAfterApprovedHistory(scope, businessDate);
        List<LocalDate> otherPendingDates = jdbc.query("""
                SELECT business_date FROM fee_day_closes
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING' AND business_date<>:businessDate
                LIMIT 1 FOR UPDATE
                """, scope.params().addValue("businessDate", businessDate),
                (result, row) -> result.getObject("business_date", LocalDate.class));
        if (!otherPendingDates.isEmpty()) throw conflict("DAY_CLOSE_PENDING",
                "Decide the pending finance close for " + otherPendingDates.getFirst()
                        + " before preparing another business date.");
        List<PreviousDayClose> previous = jdbc.query("""
                SELECT id,revision_number,status FROM fee_day_closes
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND business_date=:businessDate
                ORDER BY revision_number DESC LIMIT 1 FOR UPDATE
                """, scope.params().addValue("businessDate", businessDate), (result, row) ->
                new PreviousDayClose(result.getObject("id", UUID.class),
                        result.getInt("revision_number"), result.getString("status")));
        if (!previous.isEmpty() && !"REJECTED".equals(previous.getFirst().status())) {
            throw conflict("DAY_CLOSE_ALREADY_EXISTS",
                    "This financial date already has a pending or approved close.");
        }
        int revision = previous.isEmpty() ? 1 : previous.getFirst().revision() + 1;
        if (revision > 1000) throw conflict("DAY_CLOSE_REVISION_LIMIT",
                "This financial date has reached its close revision limit.");
        UUID supersedesId = previous.isEmpty() ? null : previous.getFirst().id();
        SystemCloseSnapshot system = systemCloseSnapshot(scope, businessDate);
        LinkedHashMap<String, BigDecimal> declared = declaredTotals(request.declarations(),system);
        BigDecimal declaredTotal = declared.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal variance = declaredTotal.subtract(system.netCollections()).setScale(2);
        String varianceReason = variance.signum() == 0 ? null : clean(request.varianceReason());
        if (variance.signum() != 0 && varianceReason == null) throw bad("DAY_CLOSE_VARIANCE_REASON_REQUIRED",
                "Explain every difference between declared and system collection totals.");
        String submissionReason = reason(request.reason());
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("system", readJson(system.json()));
        evidence.put("declaredMethodTotals", declared);
        evidence.put("declaredTotal", declaredTotal);
        evidence.put("varianceTotal", variance);
        evidence.put("submissionReason", submissionReason);
        evidence.put("varianceReason", varianceReason);
        String evidenceJson = json(evidence);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("DAY_CLOSE_ALREADY_EXISTS", () -> jdbc.update("""
                INSERT INTO fee_day_closes(
                  id,organisation_id,school_id,business_date,revision_number,supersedes_close_id,
                  currency_code,status,collection_count,gross_collections,reversal_count,
                  reversal_amount,net_collections,declared_total,variance_total,refund_count,refund_amount,
                  system_method_totals,declared_method_totals,system_snapshot,system_sha256,
                  evidence_snapshot,evidence_sha256,submission_reason,variance_reason,
                  submitted_by_membership_id,submitted_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:businessDate,:revision,:supersedesId,
                  :currency,'PENDING',:collectionCount,:grossCollections,:reversalCount,
                  :reversalAmount,:netCollections,:declaredTotal,:variance,:refundCount,:refundAmount,
                  CAST(:systemTotals AS jsonb),CAST(:declaredTotals AS jsonb),CAST(:systemSnapshot AS jsonb),
                  :systemSha,CAST(:evidenceSnapshot AS jsonb),:evidenceSha,:submissionReason,
                  :varianceReason,:actorMembershipId,:now,0)
                """, scope.params().addValue("id", id).addValue("businessDate", businessDate)
                .addValue("revision", revision).addValue("supersedesId", supersedesId)
                .addValue("currency", scope.schoolCurrency())
                .addValue("collectionCount", system.collectionCount())
                .addValue("grossCollections", system.grossCollections())
                .addValue("reversalCount", system.reversalCount())
                .addValue("reversalAmount", system.reversalAmount())
                .addValue("refundCount",system.refundCount()).addValue("refundAmount",system.refundAmount())
                .addValue("netCollections", system.netCollections())
                .addValue("declaredTotal", declaredTotal).addValue("variance", variance)
                .addValue("systemTotals", json(system.methodTotals()))
                .addValue("declaredTotals", json(declared))
                .addValue("systemSnapshot", system.json()).addValue("systemSha", system.sha256())
                .addValue("evidenceSnapshot", evidenceJson).addValue("evidenceSha", sha256(evidenceJson))
                .addValue("submissionReason", submissionReason).addValue("varianceReason", varianceReason)
                .addValue("now", now)));
        audit(user, scope, "finance.day-close.proposed", "FEE_DAY_CLOSE", id, submissionReason);
        return new FinanceMutationResponse(id, 0);
    }

    @Transactional
    public FinanceMutationResponse decideDayClose(AuthenticatedUser user, UUID id,
            DayCloseDecisionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "fees.close.approve");
        lockFinanceOperations(scope);
        List<StoredDayClose> rows = jdbc.query("""
                SELECT business_date,status,system_sha256,submitted_by_membership_id,row_version
                FROM fee_day_closes WHERE id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), (result, row) -> new StoredDayClose(
                result.getObject("business_date", LocalDate.class), result.getString("status"),
                result.getString("system_sha256"),
                result.getObject("submitted_by_membership_id", UUID.class),
                result.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("DAY_CLOSE_NOT_FOUND", "The finance close was not found.");
        StoredDayClose stored = rows.getFirst();
        if (!"PENDING".equals(stored.status()) || stored.version() != request.expectedVersion()) {
            throw conflict("STALE_DAY_CLOSE", "This finance close has already changed.");
        }
        if (stored.submitterId().equals(scope.actorMembershipId())) throw conflict("MAKER_CHECKER_REQUIRED",
                "A different authorized person must decide this finance close.");
        String decision = enumValue(request.decision(), Set.of("APPROVED", "REJECTED"), "decision");
        if ("APPROVED".equals(decision)) {
            assertCloseDateAfterApprovedHistory(scope, stored.businessDate());
            SystemCloseSnapshot current = systemCloseSnapshot(scope, stored.businessDate());
            if (!stored.systemSha256().equals(current.sha256())) throw conflict("DAY_CLOSE_EVIDENCE_STALE",
                    "Collections, reversals or completed refunds changed after submission. Reject this close and prepare a new revision.");
        }
        String why = reason(request.reason());
        jdbc.queryForObject("SELECT set_config('app.finance_day_close_decision','approved',true)",
                new MapSqlParameterSource(), String.class);
        int changed = jdbc.update("""
                UPDATE fee_day_closes SET status=:decision,
                  decided_by_membership_id=:actorMembershipId,decided_at=:now,
                  decision_reason=:reason,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("decision", decision)
                .addValue("reason", why).addValue("version", stored.version())
                .addValue("now", Instant.now(clock)));
        changed(changed);
        audit(user, scope, "finance.day-close.decided", "FEE_DAY_CLOSE", id, why);
        return new FinanceMutationResponse(id, stored.version() + 1);
    }

    private FeePlanItem planRow(Scope scope, java.sql.ResultSet result) throws java.sql.SQLException {
        UUID id = result.getObject("id", UUID.class);
        return new FeePlanItem(id, result.getObject("academic_session_id", UUID.class),
                result.getObject("supersedes_plan_id", UUID.class), result.getString("plan_code"),
                result.getInt("revision_number"), result.getString("display_name"), result.getString("grade_code"),
                result.getString("currency_code"), result.getBoolean("public_disclosure"),
                result.getString("receipt_prefix"), result.getString("notes"), result.getString("status"),
                result.getTimestamp("activated_at") == null ? null : result.getTimestamp("activated_at").toInstant(),
                result.getLong("row_version"), planItems(scope, id));
    }

    private List<PlanLineItem> planItems(Scope scope, UUID planId) {
        return jdbc.query("""
                SELECT item.*,head.display_name AS head_name FROM fee_plan_items item
                JOIN fee_heads head ON head.id=item.fee_head_id
                WHERE item.organisation_id=:organisationId AND item.school_id=:schoolId
                  AND item.fee_plan_id=:planId ORDER BY item.sequence_number,item.id
                """, scope.params().addValue("planId", planId), (result, row) -> new PlanLineItem(
                result.getObject("id", UUID.class), result.getObject("fee_head_id", UUID.class),
                result.getString("head_name"), result.getInt("sequence_number"), result.getString("label"),
                result.getObject("due_on", LocalDate.class), result.getBigDecimal("amount"),
                result.getBoolean("allow_partial"), result.getLong("row_version")));
    }

    private FeePlanItem findPlan(Scope scope, UUID id) {
        List<FeePlanItem> rows = jdbc.query("""
                SELECT * FROM fee_plans WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", id), (result, row) -> planRow(scope, result));
        if (rows.isEmpty()) throw notFound("FEE_PLAN_NOT_FOUND", "The fee plan was not found.");
        return rows.getFirst();
    }

    private AccountItem accountRow(Scope scope, java.sql.ResultSet result) throws java.sql.SQLException {
        UUID id = result.getObject("id", UUID.class);
        return new AccountItem(id, result.getObject("learner_id", UUID.class),
                result.getObject("enrolment_id", UUID.class), result.getObject("academic_session_id", UUID.class),
                result.getString("legal_name"), result.getString("admission_number"), result.getString("grade_code"),
                result.getString("session_name"), result.getString("roll_number"), result.getString("section_code"),
                result.getString("currency_code"), result.getString("status"),
                result.getBigDecimal("total_charges"), result.getBigDecimal("total_credits"),
                result.getBigDecimal("balance"), charges(scope, id), payments(scope, id), ledger(scope, id));
    }

    List<ChargeItem> charges(Scope scope, UUID accountId) {
        return jdbc.query("""
                SELECT charge.*,
                  COALESCE((SELECT sum(entry.amount) FROM fee_ledger_entries entry
                    WHERE entry.learner_fee_charge_id=charge.id AND entry.entry_type='CONCESSION'),0) concession_amount,
                  fee_charge_net_paid(charge.organisation_id,charge.school_id,charge.id,NULL::date) paid_amount,
                  COALESCE((SELECT sum(reservation.amount) FROM fee_gateway_allocations reservation
                    WHERE reservation.learner_fee_charge_id=charge.id AND reservation.organisation_id=charge.organisation_id
                      AND reservation.school_id=charge.school_id AND reservation.status='ACTIVE'),0) reserved_amount
                FROM learner_fee_charges charge
                WHERE charge.organisation_id=:organisationId AND charge.school_id=:schoolId
                  AND charge.learner_fee_account_id=:accountId
                ORDER BY charge.due_on,charge.created_at,charge.id
                """, scope.params().addValue("accountId", accountId), (result, row) -> {
                    BigDecimal amount = result.getBigDecimal("amount");
                    BigDecimal concession = result.getBigDecimal("concession_amount");
                    BigDecimal paid = result.getBigDecimal("paid_amount");
                    return new ChargeItem(result.getObject("id", UUID.class),
                            result.getString("fee_head_code_snapshot"), result.getString("fee_head_name_snapshot"),
                            result.getString("label_snapshot"), result.getObject("due_on", LocalDate.class), amount,
                            concession, paid, amount.subtract(concession).subtract(paid).max(BigDecimal.ZERO),
                            result.getBoolean("allow_partial"), result.getBigDecimal("rte_exemption_amount"),
                            result.getBigDecimal("reserved_amount"));
                });
    }

    private List<PaymentItem> payments(Scope scope, UUID accountId) {
        return jdbc.query("""
                SELECT payment.*,COALESCE(manual.original_received_on,capture.original_business_date,payment.received_on) AS original_received_on,
                  COALESCE(manual.actual_received_at,capture.paid_at) AS actual_received_at,
                  capture.paid_at_exact AS actual_received_at_exact,
                  COALESCE(manual.received_time_precision,CASE WHEN capture.paid_at IS NOT NULL THEN 'INSTANT' ELSE 'DATE_ONLY' END) AS received_time_precision,
                  COALESCE(manual.school_time_zone,attempt.school_time_zone) AS received_time_zone
                FROM fee_payments payment
                LEFT JOIN fee_manual_collection_evidence manual ON manual.fee_payment_id=payment.id
                  AND manual.organisation_id=payment.organisation_id AND manual.school_id=payment.school_id
                LEFT JOIN fee_gateway_captures capture ON capture.id=payment.gateway_capture_id
                  AND capture.organisation_id=payment.organisation_id AND capture.school_id=payment.school_id
                LEFT JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
                  AND attempt.organisation_id=payment.organisation_id AND attempt.school_id=payment.school_id
                WHERE payment.organisation_id=:organisationId AND payment.school_id=:schoolId
                  AND payment.learner_fee_account_id=:accountId ORDER BY payment.received_on DESC,payment.created_at DESC,payment.id
                """, scope.params().addValue("accountId", accountId), (result, row) -> new PaymentItem(
                result.getObject("id", UUID.class), result.getString("receipt_number"),
                result.getString("payment_method"), result.getString("external_reference"),
                result.getBigDecimal("amount"), result.getObject("received_on", LocalDate.class),
                result.getString("notes"), result.getString("status"),
                result.getTimestamp("created_at").toInstant(), result.getLong("row_version"),
                new ReceiptTiming(result.getObject("original_received_on", LocalDate.class),
                        GatewayExactInstant.readNullable(result,"actual_received_at_exact","actual_received_at"),
                        result.getString("received_time_precision"), result.getString("received_time_zone")),
                result.getObject("gateway_capture_id") != null && "ONLINE_PROVIDER".equals(result.getString("payment_method"))));
    }

    private List<LedgerItem> ledger(Scope scope, UUID accountId) {
        return jdbc.query("""
                SELECT * FROM fee_ledger_entries WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_fee_account_id=:accountId ORDER BY effective_on DESC,created_at DESC,id DESC
                """, scope.params().addValue("accountId", accountId), (result, row) -> new LedgerItem(
                result.getObject("id", UUID.class), result.getObject("learner_fee_charge_id", UUID.class),
                result.getString("entry_type"), result.getString("direction"), result.getBigDecimal("amount"),
                result.getObject("effective_on", LocalDate.class), result.getString("description"),
                result.getTimestamp("created_at").toInstant()));
    }

    private AgeingSummary ageing(Scope scope, LocalDate asOf) {
        List<AgeingAccountItem> accounts = jdbc.query("""
                WITH charge_balances AS (
                  SELECT account.id AS account_id,learner.legal_name,enrolment.admission_number,
                    enrolment.grade_code,session.name AS session_name,account.currency_code,charge.due_on,
                    GREATEST(charge.amount
                      - COALESCE((SELECT sum(entry.amount) FROM fee_ledger_entries entry
                          WHERE entry.learner_fee_charge_id=charge.id
                            AND entry.entry_type='CONCESSION' AND entry.direction='CREDIT'
                            AND entry.effective_on<=:asOf),0)
                      - fee_charge_net_paid(charge.organisation_id,charge.school_id,charge.id,CAST(:asOf AS date)),0) outstanding
                  FROM learner_fee_charges charge
                  JOIN learner_fee_accounts account ON account.id=charge.learner_fee_account_id
                  JOIN learners learner ON learner.id=account.learner_id
                  JOIN student_enrolments enrolment ON enrolment.id=account.enrolment_id
                  JOIN academic_sessions session ON session.id=account.academic_session_id
                  WHERE charge.organisation_id=:organisationId AND charge.school_id=:schoolId
                    AND charge.created_at < CAST(:asOf AS date) + INTERVAL '1 day'
                )
                SELECT account_id,legal_name,admission_number,grade_code,session_name,currency_code,
                  sum(CASE WHEN due_on>=:asOf THEN outstanding ELSE 0 END) current_amount,
                  sum(CASE WHEN :asOf-due_on BETWEEN 1 AND 30 THEN outstanding ELSE 0 END) days_1_30,
                  sum(CASE WHEN :asOf-due_on BETWEEN 31 AND 60 THEN outstanding ELSE 0 END) days_31_60,
                  sum(CASE WHEN :asOf-due_on BETWEEN 61 AND 90 THEN outstanding ELSE 0 END) days_61_90,
                  sum(CASE WHEN :asOf-due_on>90 THEN outstanding ELSE 0 END) days_over_90,
                  sum(outstanding) total_outstanding,
                  min(due_on) FILTER (WHERE due_on<:asOf AND outstanding>0) oldest_due_on,
                  COALESCE(max(:asOf-due_on) FILTER (WHERE due_on<:asOf AND outstanding>0),0) maximum_days_overdue
                FROM charge_balances WHERE outstanding>0
                GROUP BY account_id,legal_name,admission_number,grade_code,session_name,currency_code
                ORDER BY days_over_90 DESC,days_61_90 DESC,total_outstanding DESC,legal_name,account_id
                """, scope.params().addValue("asOf", asOf), (result, row) -> new AgeingAccountItem(
                result.getObject("account_id", UUID.class), result.getString("legal_name"),
                result.getString("admission_number"), result.getString("grade_code"),
                result.getString("session_name"), result.getString("currency_code"),
                result.getBigDecimal("current_amount"), result.getBigDecimal("days_1_30"),
                result.getBigDecimal("days_31_60"), result.getBigDecimal("days_61_90"),
                result.getBigDecimal("days_over_90"), result.getBigDecimal("total_outstanding"),
                result.getObject("oldest_due_on", LocalDate.class), result.getInt("maximum_days_overdue")));
        BigDecimal current = accounts.stream().map(AgeingAccountItem::current)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal days1To30 = accounts.stream().map(AgeingAccountItem::days1To30)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal days31To60 = accounts.stream().map(AgeingAccountItem::days31To60)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal days61To90 = accounts.stream().map(AgeingAccountItem::days61To90)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal daysOver90 = accounts.stream().map(AgeingAccountItem::daysOver90)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int overdueAccounts = (int) accounts.stream()
                .filter(item -> item.maximumDaysOverdue() > 0).count();
        return new AgeingSummary(asOf,
                current.add(days1To30).add(days31To60).add(days61To90).add(daysOver90),
                accounts.size(), overdueAccounts, current, days1To30, days31To60,
                days61To90, daysOver90, accounts);
    }

    private List<DayCloseItem> dayCloses(Scope scope) {
        return jdbc.query("""
                SELECT close_record.*,submitter.display_name AS submitted_by,
                  decider.display_name AS decided_by
                FROM fee_day_closes close_record
                JOIN memberships submitter_membership
                  ON submitter_membership.id=close_record.submitted_by_membership_id
                JOIN user_accounts submitter ON submitter.id=submitter_membership.user_id
                LEFT JOIN memberships decider_membership
                  ON decider_membership.id=close_record.decided_by_membership_id
                LEFT JOIN user_accounts decider ON decider.id=decider_membership.user_id
                WHERE close_record.organisation_id=:organisationId
                  AND close_record.school_id=:schoolId
                ORDER BY close_record.business_date DESC,close_record.revision_number DESC
                LIMIT 90
                """, scope.params(), (result, row) -> {
                    JsonNode system = readJson(result.getString("system_method_totals"));
                    JsonNode declared = readJson(result.getString("declared_method_totals"));
                    List<TenderTotal> tenders = CLOSE_METHODS.stream().map(method -> {
                        BigDecimal systemAmount = decimalNode(system.path(method));
                        BigDecimal declaredAmount = decimalNode(declared.path(method));
                        return new TenderTotal(method, systemAmount, declaredAmount,
                                declaredAmount.subtract(systemAmount).setScale(2));
                    }).toList();
                    return new DayCloseItem(result.getObject("id", UUID.class),
                            result.getObject("business_date", LocalDate.class),
                            result.getInt("revision_number"),
                            result.getObject("supersedes_close_id", UUID.class),
                            result.getString("currency_code"), result.getString("status"),
                            result.getInt("collection_count"), result.getBigDecimal("gross_collections"),
                            result.getInt("reversal_count"), result.getBigDecimal("reversal_amount"),
                            result.getBigDecimal("net_collections"), result.getBigDecimal("declared_total"),
                            result.getBigDecimal("variance_total"), tenders,
                            result.getString("evidence_sha256"), result.getString("submission_reason"),
                            result.getString("variance_reason"), result.getString("submitted_by"),
                            result.getTimestamp("submitted_at").toInstant(), result.getString("decided_by"),
                            result.getTimestamp("decided_at") == null ? null
                                    : result.getTimestamp("decided_at").toInstant(),
                            result.getString("decision_reason"), result.getLong("row_version"),result.getInt("refund_count"),result.getBigDecimal("refund_amount"));
                });
    }

    private LinkedHashMap<String, BigDecimal> declaredTotals(List<TenderDeclaration> declarations,SystemCloseSnapshot system) {
        if (declarations == null || declarations.size() != CLOSE_METHODS.size()) {
            throw bad("DAY_CLOSE_TENDERS_INCOMPLETE",
                    "Declare one verified total for every supported collection method.");
        }
        LinkedHashMap<String, BigDecimal> totals = zeroMethodTotals();
        Set<String> seen = new HashSet<>();
        for (TenderDeclaration declaration : declarations) {
            String method = enumValue(declaration.paymentMethod(), Set.copyOf(CLOSE_METHODS), "payment method");
            if (!seen.add(method)) throw bad("DUPLICATE_DAY_CLOSE_TENDER",
                    "Each collection method may be declared only once.");
            BigDecimal amount = dayCloseDeclaration(method,declaration.declaredAmount(),system.methodTotals().get(method),system.refundAmount());
            totals.put(method, amount);
        }
        if (!seen.containsAll(CLOSE_METHODS)) throw bad("DAY_CLOSE_TENDERS_INCOMPLETE",
                "Declare one verified total for every supported collection method.");
        return totals;
    }

    static BigDecimal dayCloseDeclaration(String method,BigDecimal value,BigDecimal expectedNet,BigDecimal completedRefundAmount) {
        BigDecimal amount=money(value);
        if(amount.abs().compareTo(new BigDecimal("100000000"))>0) throw bad("AMOUNT_TOO_LARGE","The declared tender total exceeds the supported limit.");
        if(amount.signum()<0 && (!"ONLINE_PROVIDER".equals(method) || completedRefundAmount==null || completedRefundAmount.signum()<=0
                || expectedNet==null || expectedNet.signum()>=0 || amount.compareTo(expectedNet)<0)) {
            throw bad("DAY_CLOSE_NEGATIVE_DECLARATION_INVALID","Only a genuine negative online-gateway refund movement may have a signed negative declaration, within its verified net amount.");
        }
        return amount;
    }

    private SystemCloseSnapshot systemCloseSnapshot(Scope scope, LocalDate businessDate) {
        CollectionRegisterQuery.Snapshot snapshot=CollectionRegisterQuery.dayClose(jdbc,objectMapper,scope,businessDate);
        List<ClosePaymentEvidence> payments=snapshot.items().stream().filter(item->"COLLECTION".equals(item.kind()))
                .map(item->new ClosePaymentEvidence(item.paymentId(),item.receiptNumber(),item.paymentMethod(),item.amount(),item.postingOn(),item.recordedAt()))
                .sorted(java.util.Comparator.comparing(ClosePaymentEvidence::createdAt).thenComparing(item->item.id().toString())).toList();
        List<CloseReversalEvidence> reversals=snapshot.items().stream().filter(item->"REVERSAL".equals(item.kind()))
                .map(item->new CloseReversalEvidence(item.paymentId(),item.receiptNumber(),item.paymentMethod(),item.amount(),item.id(),item.postingOn(),item.recordedAt()))
                .sorted(java.util.Comparator.comparing(CloseReversalEvidence::reversedAt).thenComparing(item->item.paymentId().toString())).toList();
        List<CollectionRegisterResponse.Movement> refunds=snapshot.items().stream().filter(item->"REFUND".equals(item.kind()))
                .sorted(java.util.Comparator.comparing(CollectionRegisterResponse.Movement::recordedAt).thenComparing(item->item.id().toString())).toList();
        LinkedHashMap<String, BigDecimal> methodTotals = zeroMethodTotals();
        BigDecimal gross = BigDecimal.ZERO.setScale(2);
        for (ClosePaymentEvidence payment : payments) {
            BigDecimal amount = money(payment.amount());
            gross = gross.add(amount);
            methodTotals.compute(payment.method(), (method, value) -> {
                if (value == null) throw new IllegalStateException("Unsupported stored payment method " + method);
                return value.add(amount);
            });
        }
        BigDecimal reversalAmount = BigDecimal.ZERO.setScale(2);
        for (CloseReversalEvidence reversal : reversals) {
            BigDecimal amount = money(reversal.amount());
            reversalAmount = reversalAmount.add(amount);
            methodTotals.compute(reversal.method(), (method, value) -> {
                if (value == null) throw new IllegalStateException("Unsupported stored payment method " + method);
                return value.subtract(amount);
            });
        }
        BigDecimal refundAmount=BigDecimal.ZERO.setScale(2);
        for(var refund:refunds){
            BigDecimal amount=money(refund.amount());refundAmount=refundAmount.add(amount);
            methodTotals.compute(refund.paymentMethod(),(method,value)->{
                if(value==null || !"ONLINE_PROVIDER".equals(method)) throw new IllegalStateException("Unsupported stored refund tender");
                return value.subtract(amount);
            });
        }
        List<Map<String, Object>> paymentEvidence = payments.stream().map(payment -> {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("paymentId", payment.id());
            item.put("receiptNumber", payment.receiptNumber());
            item.put("paymentMethod", payment.method());
            item.put("amount", money(payment.amount()));
            item.put("receivedOn", payment.receivedOn());
            item.put("createdAt", payment.createdAt());
            return Collections.unmodifiableMap(item);
        }).toList();
        List<Map<String, Object>> reversalEvidence = reversals.stream().map(reversal -> {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("paymentId", reversal.paymentId());
            item.put("receiptNumber", reversal.receiptNumber());
            item.put("paymentMethod", reversal.method());
            item.put("amount", money(reversal.amount()));
            item.put("reversalLedgerId", reversal.reversalLedgerId());
            item.put("effectiveOn", reversal.effectiveOn());
            item.put("reversedAt", reversal.reversedAt());
            return Collections.unmodifiableMap(item);
        }).toList();
        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("businessDate", businessDate);
        evidence.put("currencyCode", scope.schoolCurrency());
        evidence.put("methodTotals", methodTotals);
        evidence.put("payments", paymentEvidence);
        evidence.put("reversals", reversalEvidence);
        // Keep legacy no-refund evidence bytes stable. Only real completed
        // sources add this new key; pending intents/reads/HOLDs never appear.
        if(!refunds.isEmpty()) evidence.put("refunds",refunds.stream().map(refund->{
            LinkedHashMap<String,Object> item=new LinkedHashMap<>();
            item.put("refundPostingId",refund.id());item.put("paymentId",refund.paymentId());item.put("receiptNumber",refund.receiptNumber());
            item.put("paymentMethod",refund.paymentMethod());item.put("amount",money(refund.amount()));
            item.put("postedOn",refund.postingOn());item.put("postedAt",refund.recordedAt());
            item.put("refundTiming",refund.refundTiming());return Collections.unmodifiableMap(item);
        }).toList());
        String evidenceJson = json(evidence);
        return new SystemCloseSnapshot(evidenceJson, sha256(evidenceJson), methodTotals,
                payments.size(), gross, reversals.size(), reversalAmount,
                gross.subtract(reversalAmount).subtract(refundAmount).setScale(2),refunds.size(),refundAmount);
    }

    private LinkedHashMap<String, BigDecimal> zeroMethodTotals() {
        LinkedHashMap<String, BigDecimal> totals = new LinkedHashMap<>();
        CLOSE_METHODS.forEach(method -> totals.put(method, BigDecimal.ZERO.setScale(2)));
        return totals;
    }

    private void lockFinanceOperations(Scope scope) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                new MapSqlParameterSource().addValue("lockKey", scope.schoolId() + ":finance"),
                (result, row) -> Boolean.TRUE);
    }

    private void assertFinancialDateOpen(Scope scope, LocalDate effectiveDate) {
        LocalDate latest = latestApprovedCloseDate(scope);
        if (latest != null && !effectiveDate.isAfter(latest)) throw conflict("FINANCIAL_DATE_CLOSED",
                "This date is locked by an approved finance close. Record a current-date correction instead.");
    }

    private void assertCloseDateAfterApprovedHistory(Scope scope, LocalDate businessDate) {
        LocalDate latest = latestApprovedCloseDate(scope);
        if (latest != null && !businessDate.isAfter(latest)) throw conflict("FINANCIAL_DATE_CLOSED",
                "This date is already covered by approved finance-close history.");
    }

    private LocalDate latestApprovedCloseDate(Scope scope) {
        return jdbc.queryForObject("""
                SELECT max(business_date) FROM fee_day_closes
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='APPROVED'
                """, scope.params(), LocalDate.class);
    }

    private JsonNode readJson(String value) {
        try { return objectMapper.readTree(value); }
        catch (JacksonException exception) {
            throw new IllegalStateException("Stored finance evidence is not valid JSON", exception);
        }
    }

    private static BigDecimal decimalNode(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue().setScale(2) : BigDecimal.ZERO.setScale(2);
    }

    private UUID findOrCreateAccount(Scope scope, AssignmentContext context) {
        List<AccountState> rows = jdbc.query("""
                SELECT id,academic_session_id,currency_code,status FROM learner_fee_accounts
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sessionId AND learner_id=:learnerId FOR UPDATE
                """, scope.params().addValue("sessionId", context.sessionId())
                .addValue("learnerId", context.learnerId()), (result, row) -> new AccountState(
                result.getObject("id", UUID.class), result.getObject("academic_session_id", UUID.class),
                result.getString("currency_code"), result.getString("status")));
        if (!rows.isEmpty()) {
            AccountState account = rows.getFirst();
            if (!account.currency().equals(context.currency())) throw conflict("ACCOUNT_CURRENCY_MISMATCH",
                    "All plans assigned to a learner account must use the same currency.");
            if (!"ACTIVE".equals(account.status())) throw conflict("FEE_ACCOUNT_CLOSED", "This fee account is closed.");
            return account.id();
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO learner_fee_accounts(id,organisation_id,school_id,academic_session_id,learner_id,
                  enrolment_id,currency_code,status,opened_by_membership_id,opened_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:sessionId,:learnerId,:enrolmentId,:currency,
                  'ACTIVE',:actorMembershipId,:now,0)
                """, scope.params().addValue("id", id).addValue("sessionId", context.sessionId())
                .addValue("learnerId", context.learnerId()).addValue("enrolmentId", context.enrolmentId())
                .addValue("currency", context.currency()).addValue("now", Instant.now(clock)));
        return id;
    }

    private AccountState lockAccount(Scope scope, UUID id) {
        List<AccountState> rows = jdbc.query("""
                SELECT id,academic_session_id,currency_code,status FROM learner_fee_accounts
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), (result, row) -> new AccountState(
                result.getObject("id", UUID.class), result.getObject("academic_session_id", UUID.class),
                result.getString("currency_code"), result.getString("status")));
        if (rows.isEmpty()) throw notFound("FEE_ACCOUNT_NOT_FOUND", "The learner fee account was not found.");
        return rows.getFirst();
    }

    private BigDecimal accountBalance(Scope scope, UUID accountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END),0)
                FROM fee_ledger_entries WHERE learner_fee_account_id=:accountId
                  AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("accountId", accountId), BigDecimal.class);
    }

    private ChargeState chargeState(Scope scope, UUID accountId, UUID chargeId, boolean lock) {
        List<ChargeState> rows = jdbc.query("""
                SELECT charge.id,charge.due_on,charge.amount,charge.allow_partial,
                  COALESCE((SELECT sum(entry.amount) FROM fee_ledger_entries entry
                    WHERE entry.learner_fee_charge_id=charge.id AND entry.entry_type='CONCESSION'),0) concessions,
                  fee_charge_net_paid(charge.organisation_id,charge.school_id,charge.id,NULL::date) paid,
                  COALESCE((SELECT sum(reservation.amount) FROM fee_gateway_allocations reservation
                    WHERE reservation.learner_fee_charge_id=charge.id AND reservation.status='ACTIVE'),0) reserved
                FROM learner_fee_charges charge WHERE charge.id=:chargeId
                  AND charge.learner_fee_account_id=:accountId
                  AND charge.organisation_id=:organisationId AND charge.school_id=:schoolId
                """ + (lock ? " FOR UPDATE OF charge" : ""), scope.params().addValue("accountId", accountId)
                .addValue("chargeId", chargeId), (result, row) -> new ChargeState(
                result.getObject("id", UUID.class), result.getObject("due_on", LocalDate.class),
                result.getBigDecimal("amount"), result.getBigDecimal("concessions"),
                result.getBigDecimal("paid"), result.getBoolean("allow_partial"), result.getBigDecimal("reserved")));
        if (rows.isEmpty()) throw notFound("FEE_CHARGE_NOT_FOUND", "The learner fee charge was not found.");
        return rows.getFirst();
    }

    private List<AllocationValue> allocations(Scope scope, UUID accountId, BigDecimal amount,
            List<PaymentAllocation> requested) {
        List<AllocationValue> values = new ArrayList<>();
        if (requested != null && !requested.isEmpty()) {
            Set<UUID> seen = new HashSet<>();
            BigDecimal total = BigDecimal.ZERO;
            for (PaymentAllocation input : requested) {
                if (!seen.add(input.chargeId())) throw bad("DUPLICATE_PAYMENT_ALLOCATION",
                        "Each charge may appear only once in a payment allocation.");
                ChargeState charge = chargeState(scope, accountId, input.chargeId(), true);
                BigDecimal allocated = money(input.amount());
                validateAllocation(charge, allocated);
                values.add(new AllocationValue(charge.id(), allocated));
                total = total.add(allocated);
            }
            if (total.compareTo(amount) != 0) throw bad("PAYMENT_ALLOCATION_MISMATCH",
                    "Payment allocations must add up exactly to the collection amount.");
            return values;
        }
        List<UUID> chargeIds = jdbc.query("""
                SELECT id FROM learner_fee_charges WHERE learner_fee_account_id=:accountId
                  AND organisation_id=:organisationId AND school_id=:schoolId ORDER BY due_on,created_at,id
                FOR UPDATE
                """, scope.params().addValue("accountId", accountId),
                (result, row) -> result.getObject("id", UUID.class));
        List<ChargeState> charges = chargeIds.stream()
                .map(chargeId -> chargeState(scope, accountId, chargeId, false)).toList();
        BigDecimal remaining = amount;
        for (ChargeState charge : charges) {
            if (remaining.signum() <= 0 || charge.available().signum() <= 0) continue;
            BigDecimal allocated = remaining.min(charge.available());
            validateAllocation(charge, allocated);
            values.add(new AllocationValue(charge.id(), allocated));
            remaining = remaining.subtract(allocated);
        }
        if (remaining.signum() != 0) throw conflict("PAYMENT_CANNOT_BE_ALLOCATED",
                "The collection could not be fully allocated to the current charges.");
        return values;
    }

    private static void validateAllocation(ChargeState charge, BigDecimal amount) {
        if (amount.compareTo(charge.available()) > 0) throw conflict("ALLOCATION_EXCEEDS_CHARGE",
                "A payment allocation cannot exceed the charge's outstanding amount.");
        if (!charge.allowPartial() && amount.compareTo(charge.outstanding()) != 0)
            throw conflict("PARTIAL_PAYMENT_DISABLED", "This installment must be collected in full.");
    }

    private SequenceValue issueSequence(Scope scope, UUID sessionId) {
        List<SequenceValue> rows = jdbc.query("""
                UPDATE fee_receipt_sequences SET next_value=next_value+1,updated_at=:now
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND academic_session_id=:sessionId
                  AND next_value < 999999999
                RETURNING receipt_prefix,next_value-1 AS issued_number
                """, scope.params().addValue("sessionId", sessionId).addValue("now", Instant.now(clock)),
                (result, row) -> new SequenceValue(result.getString("receipt_prefix"), result.getLong("issued_number")));
        if (rows.isEmpty()) throw conflict("RECEIPT_SEQUENCE_NOT_CONFIGURED",
                "Activate a fee plan with an approved receipt prefix before recording collections.");
        return rows.getFirst();
    }

    private UUID ledger(Scope scope, UUID accountId, UUID chargeId, String type, String direction,
            BigDecimal amount, LocalDate effectiveOn, String description, String sourceType,
            UUID sourceId, UUID reversesId) {
        return ledger(scope, accountId, chargeId, type, direction, amount, effectiveOn,
                description, sourceType, sourceId, reversesId, null);
    }

    private UUID ledger(Scope scope, UUID accountId, UUID chargeId, String type, String direction,
            BigDecimal amount, LocalDate effectiveOn, String description, String sourceType,
            UUID sourceId, UUID reversesId, UUID captureId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fee_ledger_entries(id,organisation_id,school_id,learner_fee_account_id,
                  learner_fee_charge_id,entry_type,direction,amount,effective_on,description,source_type,
                  source_id,reverses_entry_id,created_by_membership_id,created_at,actor_kind,gateway_capture_id)
                VALUES(:id,:organisationId,:schoolId,:accountId,:chargeId,:type,:direction,:amount,
                  :effectiveOn,:description,:sourceType,:sourceId,:reversesId,:actorMembershipId,:now,:actorKind,:captureId)
                """, scope.params().addValue("id", id).addValue("accountId", accountId)
                .addValue("chargeId", chargeId).addValue("type", type).addValue("direction", direction)
                .addValue("amount", money(amount)).addValue("effectiveOn", effectiveOn)
                .addValue("description", required(description)).addValue("sourceType", sourceType)
                .addValue("sourceId", sourceId).addValue("reversesId", reversesId)
                .addValue("actorKind", scope.actorMembershipId() == null ? "SYSTEM" : "HUMAN")
                .addValue("captureId", captureId)
                .addValue("now", Instant.now(clock)));
        return id;
    }

    // Narrow package boundary for the additive refund workflow; it does not
    // expose arbitrary capability selection or change existing financial writers.
    Scope requireRefundReadScope(AuthenticatedUser user, UUID schoolId) {
        if(schoolId==null) throw bad("GATEWAY_REFUND_FILTER_INVALID","Choose a school for refund review.");
        return requireScope(user,schoolId,"fees.read");
    }

    Scope requireRefundWriteScope(AuthenticatedUser user, UUID schoolId) {
        if(schoolId==null) throw bad("GATEWAY_REFUND_FILTER_INVALID","Choose a school for refund review.");
        Scope scope=requireScope(user,schoolId,"fees.refund");
        lockFinanceOperations(scope);
        // Re-check the real wall clock and lock the actual live actor assignment
        // AFTER waiting for the common finance fence. Cached capabilities and
        // transaction-start CURRENT_TIMESTAMP are not refund authority.
        try {
            jdbc.query("SELECT require_gateway_refund_actor(:organisationId,:schoolId,:actorMembershipId)",scope.params(),row->{});
        } catch(org.springframework.dao.DataAccessException unavailable) {
            // Only this actor validator's explicit denial becomes 403. Do not
            // disguise connection, missing-schema or other SQL defects as access.
            Throwable cause=unavailable;
            while(cause!=null){
                if(cause instanceof java.sql.SQLException sql && "P0001".equals(sql.getSQLState()))
                    throw new ApiException(HttpStatus.FORBIDDEN,"FINANCE_SCOPE_DENIED","Fee and finance access is unavailable for this school.");
                cause=cause.getCause();
            }
            throw unavailable;
        }
        return scope;
    }

    private Scope requireScope(AuthenticatedUser user, UUID schoolId, String capability) {
        if (user == null || user.userId() == null || user.membershipIds().isEmpty())
            throw new ApiException(HttpStatus.FORBIDDEN, "FINANCE_SCOPE_DENIED",
                    "Fee and finance access is unavailable for this school.");
        if (user.activeOrganisationId() == null) throw conflict("ORGANISATION_SELECTION_REQUIRED",
                "Select a school organisation first.");
        List<Scope> rows = jdbc.query("""
                SELECT school.id AS school_id,school.display_name,school.currency_code,school.time_zone,
                       membership.id AS membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id WHERE mr.membership_id=membership.id
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                      AND rc.capability_code='fees.configure') can_configure,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id WHERE mr.membership_id=membership.id
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                      AND rc.capability_code='fees.collect') can_collect,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id WHERE mr.membership_id=membership.id
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                      AND rc.capability_code='fees.approve') can_approve,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id WHERE mr.membership_id=membership.id
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                      AND rc.capability_code='fees.refund') can_reverse,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id WHERE mr.membership_id=membership.id
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                      AND rc.capability_code='fees.close.propose') can_propose_close,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id WHERE mr.membership_id=membership.id
                      AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                      AND rc.capability_code='fees.close.approve') can_approve_close
                FROM memberships membership JOIN user_accounts actor ON actor.id=membership.user_id AND actor.status='ACTIVE'
                JOIN schools school ON school.id=membership.school_id
                  AND school.organisation_id=membership.organisation_id AND school.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=membership.organisation_id AND organisation.status='ACTIVE'
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN roles role ON role.id=mr.role_id
                  AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                JOIN role_capabilities rc ON rc.role_id=mr.role_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds) AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND rc.capability_code=:capability
                ORDER BY can_approve DESC,membership.created_at LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId()).addValue("membershipIds", user.membershipIds())
                .addValue("schoolId", schoolId).addValue("capability", capability), (result, row) -> new Scope(
                user.activeOrganisationId(), result.getObject("school_id", UUID.class), result.getString("display_name"),
                result.getString("currency_code"), result.getObject("membership_id", UUID.class),
                result.getBoolean("can_configure"), result.getBoolean("can_collect"),
                result.getBoolean("can_approve"), result.getBoolean("can_reverse"),
                result.getBoolean("can_propose_close"), result.getBoolean("can_approve_close"), result.getString("time_zone")));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN, "FINANCE_SCOPE_DENIED",
                "Fee and finance access is unavailable for this school.");
        return rows.getFirst();
    }

    private String providerStatus(Scope scope) {
        List<String> rows = jdbc.query("""
                SELECT environment FROM payment_provider_configs WHERE organisation_id=:organisationId
                  AND school_id=:schoolId AND status='ACTIVE' ORDER BY environment DESC LIMIT 1
                """, scope.params(), (result, row) -> result.getString("environment"));
        return rows.isEmpty() ? "NOT_CONFIGURED" : "LIVE".equals(rows.getFirst()) ? "LIVE_REDIRECT" : "TEST_REDIRECT";
    }

    private void validatePlanRevision(Scope scope, FeePlanRequest request, UUID editingId) {
        String code = code(request.code());
        if (request.supersedesPlanId() == null) {
            if (request.revisionNumber() != 1) throw bad("FEE_PLAN_REVISION_INVALID",
                    "A new plan code starts at revision 1.");
            return;
        }
        List<RevisionSource> rows = jdbc.query("""
                SELECT plan_code,revision_number,academic_session_id FROM fee_plans
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", request.supersedesPlanId()), (result, row) -> new RevisionSource(
                result.getString("plan_code"), result.getInt("revision_number"),
                result.getObject("academic_session_id", UUID.class)));
        if (rows.isEmpty()) throw bad("SUPERSEDED_PLAN_NOT_FOUND", "The superseded fee plan was not found.");
        RevisionSource source = rows.getFirst();
        if (!source.code().equals(code) || !source.sessionId().equals(request.academicSessionId())
                || request.revisionNumber() != source.revision() + 1)
            throw bad("FEE_PLAN_REVISION_INVALID",
                    "A superseding plan must keep the plan code and session and use the next revision number.");
        if (editingId != null && editingId.equals(request.supersedesPlanId()))
            throw bad("FEE_PLAN_REVISION_INVALID", "A fee plan cannot supersede itself.");
    }

    private MapSqlParameterSource headParams(Scope scope, UUID id, FeeHeadRequest request, Long version) {
        return scope.params().addValue("id", id).addValue("code", code(request.code()))
                .addValue("name", required(request.name())).addValue("description", clean(request.description()))
                .addValue("category", enumValue(request.category(), HEAD_CATEGORIES, "fee category"))
                .addValue("refundable", request.refundable())
                .addValue("status", enumValue(request.status(), HEAD_STATUSES, "fee head status"))
                .addValue("version", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource planParams(Scope scope, UUID id, FeePlanRequest request, Long version) {
        return scope.params().addValue("id", id).addValue("sessionId", request.academicSessionId())
                .addValue("supersedesId", request.supersedesPlanId()).addValue("code", code(request.code()))
                .addValue("revision", request.revisionNumber()).addValue("name", required(request.name()))
                .addValue("grade", clean(request.gradeCode())).addValue("currency", upper(request.currencyCode()))
                .addValue("prefix", upper(request.receiptPrefix()).replace(' ', '-'))
                .addValue("notes", clean(request.notes())).addValue("version", version)
                .addValue("now", Instant.now(clock));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Unable to freeze the fee plan snapshot", exception); }
    }

    private static String fingerprint(UUID accountId, String method, String reference, BigDecimal amount,
            LocalDate receivedOn, String notes, List<PaymentAllocation> allocations) {
        List<String> allocationValues = allocations == null ? List.of() : allocations.stream()
                .map(value -> value.chargeId() + ":" + money(value.amount()).toPlainString()).sorted().toList();
        return sha256(String.join("|", accountId.toString(), method, Objects.toString(reference, ""),
                amount.toPlainString(), receivedOn.toString(), Objects.toString(notes, ""),
                String.join(",", allocationValues)));
    }

    private static boolean hasManualEvidenceFields(PaymentRequest request) {
        return request.actualReceivedAt() != null || request.postingOn() != null
                || request.bankCreditVerification() != null || request.reviewReason() != null;
    }

    private static String manualEvidenceFingerprint(PaymentRequest request) {
        var credit = request.bankCreditVerification();
        StringBuilder material = new StringBuilder("MANUAL_RECEIVED_FUNDS_V1");
        for (Object value : new Object[] { request.actualReceivedAt(), request.postingOn(), clean(request.reviewReason()),
                credit == null ? null : credit.sourceKind(), credit == null ? null : credit.confirmedReceived(),
                credit == null ? null : clean(credit.creditedEntryReference()) }) {
            String text = value == null ? "" : value.toString();
            material.append('|').append(value == null ? -1 : text.length()).append(':').append(text);
        }
        return material.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private void audit(AuthenticatedUser user, Scope scope, String action, String type, UUID id, String why) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type, id.toString(),
                AuditOutcome.SUCCEEDED, clean(why), Map.of());
    }

    private static String receiptNumber(String prefix, long number) {
        String separator = prefix.endsWith("/") || prefix.endsWith("-") ? "" : "-";
        return prefix + separator + String.format(Locale.ROOT, "%06d", number);
    }
    private static BigDecimal money(BigDecimal value) {
        if (value == null) throw bad("AMOUNT_REQUIRED", "Enter an amount.");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException exception) { throw bad("AMOUNT_SCALE_INVALID", "Amounts support at most two decimal places."); }
    }
    private static BigDecimal moneyAllowZero(BigDecimal value) {
        BigDecimal result = money(value);
        if (result.signum() < 0) throw bad("AMOUNT_NEGATIVE", "Declared totals cannot be negative.");
        return result;
    }
    private static String idempotency(String value) {
        String clean = clean(value);
        if (clean == null || clean.length() < 8 || clean.length() > 100)
            throw bad("IDEMPOTENCY_KEY_INVALID", "Use a unique idempotency key from 8 to 100 characters.");
        return clean;
    }
    private static <T> T safe(String code, Work<T> work) {
        try { return work.run(); }
        catch (DataIntegrityViolationException exception) {
            throw conflict(code, "The same finance configuration or transaction already exists.");
        }
    }
    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION", "The record changed. Refresh and try again.");
    }
    private static long version(Long value) {
        if (value == null) throw bad("EXPECTED_VERSION_REQUIRED", "Include the current record version before editing.");
        return value;
    }
    private static String reason(String value) {
        String result = clean(value);
        if (result == null) throw bad("CHANGE_REASON_REQUIRED", "Enter a reason for this financial change.");
        return result;
    }
    private static String required(String value) {
        String result = clean(value);
        if (result == null) throw bad("REQUIRED_VALUE_MISSING", "Enter the required value.");
        return result;
    }
    private static String enumValue(String value, Set<String> allowed, String label) {
        String result = upper(value).replace(' ', '_');
        if (!allowed.contains(result)) throw bad("INVALID_VALUE", "Choose a supported " + label + ".");
        return result;
    }
    private static String code(String value) { return upper(value).replace(' ', '_'); }
    private static String upper(String value) { return required(value).toUpperCase(Locale.ROOT); }
    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }

    private LocalDate today(Scope scope) { return businessDate(clock, scope.timeZone()); }
    static LocalDate businessDate(Clock clock, String timeZone) {
        return LocalDate.now(clock.withZone(java.time.ZoneId.of(timeZone)));
    }

    record Scope(UUID organisationId, UUID schoolId, String schoolName, String schoolCurrency,
            UUID actorMembershipId, boolean canConfigure, boolean canCollect, boolean canApprove,
            boolean canReverse, boolean canProposeDayClose, boolean canApproveDayClose, String timeZone) {
        MapSqlParameterSource params() { return new MapSqlParameterSource()
                .addValue("organisationId", organisationId).addValue("schoolId", schoolId)
                .addValue("actorMembershipId", actorMembershipId); }
    }
    private record StoredPlan(UUID sessionId, UUID supersedesId, String prefix, String status, long version) {}
    private record AssignmentContext(UUID sessionId, String currency, UUID learnerId, UUID enrolmentId) {}
    private record AccountState(UUID id, UUID sessionId, String currency, String status) {}
    private record ChargeState(UUID id, LocalDate dueOn, BigDecimal amount, BigDecimal concessions,
            BigDecimal paid, boolean allowPartial, BigDecimal reserved) {
        BigDecimal outstanding() { return amount.subtract(concessions).subtract(paid).max(BigDecimal.ZERO); }
        BigDecimal available() { return outstanding().subtract(reserved).max(BigDecimal.ZERO); }
    }
    private record StoredConcession(UUID accountId, UUID chargeId, BigDecimal amount, String status,
            UUID requesterId, long version) {}
    private record AllocationValue(UUID chargeId, BigDecimal amount) {}
    private record SequenceValue(String prefix, long number) {}
    private record ExistingPayment(UUID id, long version, String receiptNumber, String fingerprint) {}
    private record StoredPayment(UUID accountId, UUID ledgerId, String receiptNumber, BigDecimal amount,
            LocalDate receivedOn, String status, long version, UUID gatewayCaptureId) {}
    private record PreviousDayClose(UUID id, int revision, String status) {}
    private record StoredDayClose(LocalDate businessDate, String status, String systemSha256,
            UUID submitterId, long version) {}
    private record ClosePaymentEvidence(UUID id, String receiptNumber, String method,
            BigDecimal amount, LocalDate receivedOn, Instant createdAt) {}
    private record CloseReversalEvidence(UUID paymentId, String receiptNumber, String method,
            BigDecimal amount, UUID reversalLedgerId, LocalDate effectiveOn, Instant reversedAt) {}
    private record SystemCloseSnapshot(String json, String sha256,
            LinkedHashMap<String, BigDecimal> methodTotals, int collectionCount,
            BigDecimal grossCollections, int reversalCount, BigDecimal reversalAmount,
            BigDecimal netCollections, int refundCount, BigDecimal refundAmount) {}
    private record RevisionSource(String code, int revision, UUID sessionId) {}
    @FunctionalInterface private interface Work<T> { T run(); }
}

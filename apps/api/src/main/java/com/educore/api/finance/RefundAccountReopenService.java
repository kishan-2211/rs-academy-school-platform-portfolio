package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit original-account review only; no provider, fee, receipt, GL or academic mutation. */
@Service
public class RefundAccountReopenService {
    private static final Set<String> STATES = Set.of("ALL", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED");
    private static final String STATE = "CASE decision.decision WHEN 'APPROVE' THEN 'APPROVED' WHEN 'REJECT' THEN 'REJECTED' WHEN 'CANCEL' THEN 'CANCELLED' ELSE 'SUBMITTED' END";
    private static final String APPROVAL_READY = """
            account.status='CLOSED' AND account.row_version=request.account_row_version
            AND ROW(account.academic_session_id,account.learner_id,account.enrolment_id,account.currency_code,
              account.opened_by_membership_id,account.opened_at,account.closed_by_membership_id,account.closed_at,account.close_reason)
              IS NOT DISTINCT FROM ROW(request.account_academic_session_id,request.account_learner_id,request.account_enrolment_id,request.account_currency_code,
              request.account_opened_by_membership_id,request.account_opened_at,request.account_closed_by_membership_id,request.account_closed_at,request.account_close_reason)
            AND refund_account_reopening_source_ready(request.refund_request_id,request.organisation_id,request.school_id)
            """;
    private static final String REVIEW_COLUMNS = """
            request.id,request.refund_request_id,request.fee_payment_id,request.learner_fee_account_id,
            learner.legal_name AS learner_name,session.name AS session_name,request.reason,request.requested_at,
            request.requested_by_user_id,maker.display_name AS maker_name,request.account_row_version,
            request.account_closed_at,request.account_close_reason,request.closed_by_name_snapshot,
            decision.id AS decision_id,decision.decision,decision.reason AS decision_reason,decision.decided_at,
            reviewer.display_name AS reviewer_name,
            """ + STATE + " AS state,(" + APPROVAL_READY + ") AS approval_ready ";
    private static final String REVIEW_JOINS = """
            FROM fee_refund_account_reopen_requests request
            JOIN learner_fee_accounts account ON account.id=request.learner_fee_account_id
              AND account.organisation_id=request.organisation_id AND account.school_id=request.school_id
            JOIN learners learner ON learner.id=request.account_learner_id
              AND learner.organisation_id=request.organisation_id AND learner.school_id=request.school_id
            JOIN academic_sessions session ON session.id=request.account_academic_session_id
              AND session.organisation_id=request.organisation_id AND session.school_id=request.school_id
            JOIN student_enrolments enrolment ON enrolment.id=request.account_enrolment_id
              AND enrolment.organisation_id=request.organisation_id AND enrolment.school_id=request.school_id
            JOIN user_accounts maker ON maker.id=request.requested_by_user_id
            LEFT JOIN fee_refund_account_reopen_decisions decision ON decision.reopen_request_id=request.id
              AND decision.organisation_id=request.organisation_id AND decision.school_id=request.school_id
            LEFT JOIN user_accounts reviewer ON reviewer.id=decision.decided_by_user_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final FinanceService finance;
    private final SecurityAuditService audit;

    public RefundAccountReopenService(NamedParameterJdbcTemplate jdbc, FinanceService finance, SecurityAuditService audit) {
        this.jdbc = jdbc; this.finance = finance; this.audit = audit;
    }

    @Transactional(readOnly = true)
    public RefundAccountReopenModels.Context context(AuthenticatedUser user, UUID schoolId, UUID refundId) {
        requireContext(user, false);
        var scope = finance.requireRefundReadScope(user, schoolId);
        Source source = source(scope, refundId);
        var history = jdbc.query("SELECT " + REVIEW_COLUMNS + REVIEW_JOINS + """
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId AND request.refund_request_id=:refundId
                ORDER BY CASE WHEN decision.id IS NULL THEN 0 ELSE 1 END,request.requested_at DESC,request.id LIMIT 51
                """, scope.params().addValue("refundId", refundId), (row, index) -> review(row));
        boolean more = history.size() > 50;
        var items = history.stream().limit(50).map(row -> projection(user, scope, row)).toList();
        boolean closed = "CLOSED".equals(source.status());
        return new RefundAccountReopenModels.Context(scope.schoolId(), refundId, source.paymentId(), source.accountId(), source.learnerName(),
                source.sessionName(), source.currency(), scope.timeZone(), source.sourceState(), source.status(), source.version(),
                closed ? new RefundAccountReopenModels.Closure(source.closedAt(), source.closeReason(), source.closerName()) : null,
                source.balance(), "HOLD".equals(source.sourceState()) ? source.holdAmount() : null,
                source.captureId() != null && closed, scope.canReverse() && "HOLD".equals(source.sourceState()) && closed && !source.pending(),
                false, more, items);
    }

    @Transactional(readOnly = true)
    public RefundAccountReopenModels.Queue queue(AuthenticatedUser user, UUID schoolId, String status, String query, int page, int pageSize) {
        requireContext(user, false);
        var scope = finance.requireRefundReadScope(user, schoolId);
        String q = search(query);
        if (!STATES.contains(status == null ? "" : status) || page < 0 || page > 10000 || pageSize < 1 || pageSize > 50) throw invalid();
        var p = scope.params().addValue("state", status).addValue("search", q == null ? null : "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                .addValue("limit", pageSize).addValue("offset", (long) page * pageSize);
        String sql = "WITH filtered AS MATERIALIZED (SELECT " + REVIEW_COLUMNS + REVIEW_JOINS + """
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND (CAST(:search AS text) IS NULL OR learner.legal_name ILIKE :search ESCAPE '\\'
                    OR enrolment.admission_number ILIKE :search ESCAPE '\\' OR session.name ILIKE :search ESCAPE '\\')
                ), matching AS MATERIALIZED (SELECT * FROM filtered WHERE :state='ALL' OR state=:state)
                SELECT totals.total,items.* FROM (SELECT count(*) AS total FROM matching) totals
                LEFT JOIN LATERAL(SELECT * FROM matching ORDER BY requested_at DESC,id LIMIT :limit OFFSET :offset) items ON true
                ORDER BY items.requested_at DESC,items.id
                """;
        QueueRows result = jdbc.query(sql, p, (ResultSetExtractor<QueueRows>) row -> {
            if (!row.next()) throw new IllegalStateException("Account review aggregate unavailable");
            long total = row.getLong("total");
            var rows = new java.util.ArrayList<ReviewRow>();
            do { if (row.getObject("id") != null) rows.add(review(row)); } while (row.next());
            return new QueueRows(total, List.copyOf(rows));
        });
        if (result == null) throw new IllegalStateException("Account review aggregate unavailable");
        var items = result.rows().stream().map(row -> projection(user, scope, row)).toList();
        return new RefundAccountReopenModels.Queue(scope.schoolId(), status, q, page, pageSize, result.total(),
                (long) page * pageSize + items.size() < result.total(), false, items);
    }

    @Transactional
    public RefundAccountReopenModels.Result submit(AuthenticatedUser user, UUID refundId, RefundAccountReopenModels.Submit command) {
        requireContext(user, true);
        if (refundId == null || command == null || command.clientRequestId() == null || command.expectedAccountVersion() == null
                || command.expectedAccountVersion() < 0) throw invalid();
        var scope = finance.requireRefundWriteScope(user, command.schoolId());
        String why = GatewayRefundTerms.reason(command.reason());
        String fingerprint = fingerprint(scope.schoolId(), refundId, command.expectedAccountVersion(), why);
        Replay old = replay(scope, user.userId(), command.clientRequestId());
        if (old != null) {
            fence(scope, old.accountId(), old.captureId());
            old = replay(scope, user.userId(), command.clientRequestId());
            if (old == null) throw unavailable();
            return replayResult(old, refundId, fingerprint);
        }
        Source source = source(scope, refundId);
        if (source.captureId() == null) throw unavailable();
        fence(scope, source.accountId(), source.captureId());
        // Retry predicates and mutable account state are read after the real epoch writes.
        old = replay(scope, user.userId(), command.clientRequestId());
        if (old != null) return replayResult(old, refundId, fingerprint);
        source = source(scope, refundId);
        if (!"HOLD".equals(source.sourceState()) || !"CLOSED".equals(source.status()) || source.pending()
                || source.version() != command.expectedAccountVersion()) throw unavailable();
        UUID id = UUID.randomUUID();
        var p = scope.params().addValue("id", id).addValue("refundId", refundId).addValue("paymentId", source.paymentId())
                .addValue("accountId", source.accountId()).addValue("readId", source.readId()).addValue("sourceHash", source.holdHash())
                .addValue("sourceVersion", source.holdVersion()).addValue("sessionId", source.sessionId()).addValue("learnerId", source.learnerId())
                .addValue("enrolmentId", source.enrolmentId()).addValue("currency", source.currency()).addValue("openerId", source.openerId())
                .addValue("openedAt", timestampParameter(source.openedAt())).addValue("closerId", source.closerId()).addValue("closedAt", timestampParameter(source.closedAt()))
                .addValue("closeReason", source.closeReason()).addValue("closerName", source.closerName()).addValue("version", source.version())
                .addValue("clientId", command.clientRequestId()).addValue("fingerprint", fingerprint).addValue("reason", why).addValue("actor", user.userId());
        write("""
                INSERT INTO fee_refund_account_reopen_requests(id,organisation_id,school_id,refund_request_id,fee_payment_id,learner_fee_account_id,
                  verified_read_id,success_evidence_sha256,success_source_version,account_academic_session_id,account_learner_id,account_enrolment_id,
                  account_currency_code,account_opened_by_membership_id,account_opened_at,account_closed_by_membership_id,account_closed_at,
                  account_close_reason,closed_by_name_snapshot,account_row_version,client_request_id,request_fingerprint,reason,requested_by_user_id,requested_by_membership_id)
                VALUES(:id,:organisationId,:schoolId,:refundId,:paymentId,:accountId,:readId,:sourceHash,:sourceVersion,:sessionId,:learnerId,:enrolmentId,
                  :currency,:openerId,:openedAt,:closerId,:closedAt,:closeReason,:closerName,:version,:clientId,:fingerprint,:reason,:actor,:actorMembershipId)
                """, p);
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), "finance.gateway.refund.account_reopening.requested",
                "REFUND_ACCOUNT_REOPENING", id.toString(), AuditOutcome.SUCCEEDED, why,
                Map.of("refundId", refundId, "accountId", source.accountId(), "closedAccountVersion", source.version()));
        return new RefundAccountReopenModels.Result(id, refundId, source.accountId(), "SUBMITTED", false, false);
    }

    @Transactional
    public RefundAccountReopenModels.Result decide(AuthenticatedUser user, UUID reviewId, RefundAccountReopenModels.DecisionCommand command) {
        requireContext(user, true);
        if (reviewId == null || command == null || !Set.of("APPROVE", "REJECT", "CANCEL").contains(command.decision() == null ? "" : command.decision())) throw invalid();
        var scope = finance.requireRefundWriteScope(user, command.schoolId());
        String why = GatewayRefundTerms.reason(command.reason());
        DecisionSource request = decisionSource(scope, reviewId);
        fence(scope, request.accountId(), request.captureId());
        var p = scope.params().addValue("id", reviewId);
        var old = jdbc.query("""
                SELECT decision,reason,decided_by_user_id FROM fee_refund_account_reopen_decisions
                WHERE reopen_request_id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, p, row -> row.next() ? new OldDecision(row.getString("decision"), row.getString("reason"), row.getObject("decided_by_user_id", UUID.class)) : null);
        if (old != null) {
            if (!old.actor().equals(user.userId()) || !old.action().equals(command.decision()) || !old.reason().equals(why)) throw conflict("REFUND_ACCOUNT_REOPEN_ALREADY_REVIEWED", "This account request already has an immutable review. Refresh its history.");
            return result(reviewId, request.refundId(), request.accountId(), old.action());
        }
        boolean maker = request.maker().equals(user.userId());
        if (command.decision().equals("CANCEL") ? !maker : maker) throw new ApiException(HttpStatus.FORBIDDEN,
                "REFUND_ACCOUNT_REOPEN_INDEPENDENT_REVIEW_REQUIRED", "A different authorized person must approve or reject. Only the maker may withdraw the request.");
        Long appliedVersion = null;
        if (command.decision().equals("APPROVE")) {
            var rows = jdbc.query("SELECT " + REVIEW_COLUMNS + REVIEW_JOINS + """
                    WHERE request.id=:id AND request.organisation_id=:organisationId AND request.school_id=:schoolId
                    """, p, (row, index) -> review(row));
            if (rows.size() != 1 || !rows.getFirst().approvalReady()) throw unavailable();
            try { appliedVersion = Math.addExact(request.version(), 1); }
            catch (ArithmeticException exhausted) { throw unavailable(); }
        }
        write("""
                INSERT INTO fee_refund_account_reopen_decisions(id,organisation_id,school_id,reopen_request_id,learner_fee_account_id,
                  closed_account_version,decision,reason,decided_by_user_id,decided_by_membership_id,applied_account_version)
                VALUES(:decisionId,:organisationId,:schoolId,:id,:accountId,:version,:decision,:reason,:actor,:actorMembershipId,:appliedVersion)
                """, p.addValue("decisionId", UUID.randomUUID()).addValue("accountId", request.accountId()).addValue("version", request.version())
                .addValue("decision", command.decision()).addValue("reason", why).addValue("actor", user.userId()).addValue("appliedVersion", appliedVersion, java.sql.Types.BIGINT));
        // The database has paired APPROVE with its one original account transition.
        // An audit error still rolls back both; no fee, receipt or accounting row is written.
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), "finance.gateway.refund.account_reopening." + state(command.decision()).toLowerCase(java.util.Locale.ROOT),
                "REFUND_ACCOUNT_REOPENING", reviewId.toString(), AuditOutcome.SUCCEEDED, why,
                Map.of("refundId", request.refundId(), "accountId", request.accountId(), "closedAccountVersion", request.version()));
        return result(reviewId, request.refundId(), request.accountId(), command.decision());
    }

    private Source source(FinanceService.Scope scope, UUID refundId) {
        if (refundId == null) throw missing();
        var rows = jdbc.query("""
                SELECT request.fee_payment_id,account.id,account.academic_session_id,account.learner_id,account.enrolment_id,account.currency_code,
                  account.status,account.row_version,account.opened_by_membership_id,account.opened_at,account.closed_by_membership_id,
                  account.closed_at,account.close_reason,closer.display_name AS closer_name,learner.legal_name AS learner_name,session.name AS session_name,
                  success.capture_id,success.verified_read_id,success.evidence_sha256,success.source_version,success.amount AS hold_amount,
                  CASE WHEN approval.decision IS DISTINCT FROM 'APPROVE' THEN 'UNAPPROVED'
                    WHEN success.refund_request_id IS NULL THEN 'NO_HOLD'
                    WHEN refund_account_reopening_source_ready(request.id,request.organisation_id,request.school_id) THEN 'HOLD'
                    ELSE 'CONFLICTING' END AS source_state,
                  COALESCE((SELECT sum(CASE ledger.direction WHEN 'DEBIT' THEN ledger.amount ELSE -ledger.amount END)
                    FROM fee_ledger_entries ledger WHERE ledger.learner_fee_account_id=account.id
                      AND ledger.organisation_id=account.organisation_id AND ledger.school_id=account.school_id),0) AS signed_balance,
                  EXISTS(SELECT 1 FROM fee_refund_account_reopen_requests pending WHERE pending.learner_fee_account_id=account.id
                    AND pending.organisation_id=account.organisation_id AND pending.school_id=account.school_id
                    AND NOT EXISTS(SELECT 1 FROM fee_refund_account_reopen_decisions decision WHERE decision.reopen_request_id=pending.id)) AS pending
                FROM fee_gateway_refund_requests request
                JOIN learner_fee_accounts account ON account.id=request.learner_fee_account_id AND account.organisation_id=request.organisation_id AND account.school_id=request.school_id
                JOIN learners learner ON learner.id=account.learner_id AND learner.organisation_id=account.organisation_id AND learner.school_id=account.school_id
                JOIN academic_sessions session ON session.id=account.academic_session_id AND session.organisation_id=account.organisation_id AND session.school_id=account.school_id
                LEFT JOIN memberships closer_member ON closer_member.id=account.closed_by_membership_id
                  AND closer_member.organisation_id=account.organisation_id AND closer_member.school_id=account.school_id
                LEFT JOIN user_accounts closer ON closer.id=closer_member.user_id
                LEFT JOIN fee_gateway_refund_decisions approval ON approval.refund_request_id=request.id AND approval.organisation_id=request.organisation_id AND approval.school_id=request.school_id
                LEFT JOIN fee_gateway_refund_successes success ON success.refund_request_id=request.id AND success.organisation_id=request.organisation_id AND success.school_id=request.school_id
                WHERE request.id=:refundId AND request.organisation_id=:organisationId AND request.school_id=:schoolId
                """, scope.params().addValue("refundId", refundId), (row, index) -> new Source(row.getObject("fee_payment_id", UUID.class),
                row.getObject("id", UUID.class), row.getObject("academic_session_id", UUID.class), row.getObject("learner_id", UUID.class),
                row.getObject("enrolment_id", UUID.class), row.getString("currency_code"), row.getString("status"), row.getLong("row_version"),
                row.getObject("opened_by_membership_id", UUID.class), instant(row, "opened_at"), row.getObject("closed_by_membership_id", UUID.class),
                instant(row, "closed_at"), row.getString("close_reason"), row.getString("closer_name"), row.getString("learner_name"), row.getString("session_name"),
                row.getObject("capture_id", UUID.class), row.getObject("verified_read_id", UUID.class), row.getString("evidence_sha256"),
                row.getInt("source_version"), row.getBigDecimal("hold_amount"), row.getString("source_state"), row.getBigDecimal("signed_balance"), row.getBoolean("pending")));
        if (rows.size() != 1) throw missing();
        return rows.getFirst();
    }

    private Replay replay(FinanceService.Scope scope, UUID actor, UUID clientId) {
        return jdbc.query("""
                SELECT request.id,request.refund_request_id,request.learner_fee_account_id,request.request_fingerprint,success.capture_id,
                  decision.decision FROM fee_refund_account_reopen_requests request
                JOIN fee_gateway_refund_successes success ON success.refund_request_id=request.refund_request_id
                  AND success.organisation_id=request.organisation_id AND success.school_id=request.school_id
                LEFT JOIN fee_refund_account_reopen_decisions decision ON decision.reopen_request_id=request.id
                  AND decision.organisation_id=request.organisation_id AND decision.school_id=request.school_id
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND request.requested_by_user_id=:actor AND request.client_request_id=:clientId
                """, scope.params().addValue("actor", actor).addValue("clientId", clientId), row -> row.next() ? new Replay(
                row.getObject("id", UUID.class), row.getObject("refund_request_id", UUID.class), row.getObject("learner_fee_account_id", UUID.class),
                row.getString("request_fingerprint"), row.getObject("capture_id", UUID.class), row.getString("decision")) : null);
    }

    private DecisionSource decisionSource(FinanceService.Scope scope, UUID id) {
        return jdbc.query("""
                SELECT request.refund_request_id,request.learner_fee_account_id,request.requested_by_user_id,request.account_row_version,success.capture_id
                FROM fee_refund_account_reopen_requests request JOIN fee_gateway_refund_successes success ON success.refund_request_id=request.refund_request_id
                  AND success.organisation_id=request.organisation_id AND success.school_id=request.school_id
                WHERE request.id=:id AND request.organisation_id=:organisationId AND request.school_id=:schoolId
                """, scope.params().addValue("id", id), row -> {
            if (!row.next()) throw missing();
            return new DecisionSource(row.getObject("refund_request_id", UUID.class), row.getObject("learner_fee_account_id", UUID.class),
                    row.getObject("requested_by_user_id", UUID.class), row.getLong("account_row_version"), row.getObject("capture_id", UUID.class));
        });
    }

    private void fence(FinanceService.Scope scope, UUID accountId, UUID captureId) {
        var p = scope.params().addValue("accountId", accountId).addValue("captureId", captureId);
        jdbc.query("SELECT touch_gateway_refund_fence(:captureId,:organisationId,:schoolId)", p, row -> {});
        jdbc.query("SELECT touch_refund_account_reopen_fence(:accountId,:organisationId,:schoolId)", p, row -> {});
    }

    private void write(String sql, MapSqlParameterSource p) {
        try { if (jdbc.update(sql, p) != 1) throw unavailable(); }
        catch (DataAccessException failure) {
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof SQLException database && Set.of("23505", "23514", "P0001").contains(database.getSQLState() == null ? "" : database.getSQLState())) throw unavailable();
                cause = cause.getCause();
            }
            throw failure;
        }
    }

    private void requireContext(AuthenticatedUser user, boolean write) {
        var context = TenantRequestContext.current().orElse(null);
        if (user == null || user.userId() == null || user.activeOrganisationId() == null || user.membershipIds().isEmpty() || context == null
                || !user.userId().equals(context.userId()) || !user.activeOrganisationId().equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive() || (write && TransactionSynchronizationManager.isCurrentTransactionReadOnly())) throw denied();
        Boolean bound = jdbc.queryForObject("SELECT current_app_organisation_id()=:org AND current_app_user_id()=:actor",
                new MapSqlParameterSource().addValue("org", user.activeOrganisationId()).addValue("actor", user.userId()), Boolean.class);
        if (!Boolean.TRUE.equals(bound)) throw denied();
    }

    private static RefundAccountReopenModels.Review projection(AuthenticatedUser user, FinanceService.Scope scope, ReviewRow row) {
        boolean maker = row.maker().equals(user.userId()), pending = row.state().equals("SUBMITTED"), reviewer = scope.canReverse() && !maker;
        return new RefundAccountReopenModels.Review(row.id(), row.refundId(), row.paymentId(), row.accountId(), row.learnerName(), row.sessionName(),
                row.state(), row.reason(), row.requestedAt(), row.makerName(), maker, pending && reviewer && row.approvalReady(),
                pending && reviewer, pending && scope.canReverse() && maker, row.version(), row.closure(), row.decision());
    }
    private static ReviewRow review(ResultSet row) throws SQLException {
        UUID decisionId = row.getObject("decision_id", UUID.class);
        var decision = decisionId == null ? null : new RefundAccountReopenModels.Decision(decisionId, row.getString("decision"), row.getString("decision_reason"),
                instant(row, "decided_at"), row.getString("reviewer_name"));
        return new ReviewRow(row.getObject("id", UUID.class), row.getObject("refund_request_id", UUID.class), row.getObject("fee_payment_id", UUID.class),
                row.getObject("learner_fee_account_id", UUID.class), row.getString("learner_name"), row.getString("session_name"), row.getString("state"),
                row.getString("reason"), instant(row, "requested_at"), row.getObject("requested_by_user_id", UUID.class), row.getString("maker_name"),
                row.getLong("account_row_version"), new RefundAccountReopenModels.Closure(instant(row, "account_closed_at"), row.getString("account_close_reason"),
                row.getString("closed_by_name_snapshot")), row.getBoolean("approval_ready"), decision);
    }
    private static Instant instant(ResultSet row, String field) throws SQLException {
        var value = row.getTimestamp(field); return value == null ? null : value.toInstant();
    }
    private static OffsetDateTime timestampParameter(Instant value) {
        if (value == null) throw unavailable();
        // These values came from native PostgreSQL account timestamps: preserve
        // their microseconds exactly and bind an explicitly supported JDBC type.
        return value.atOffset(ZoneOffset.UTC);
    }
    private static String search(String value) {
        if (value != null && (value.length() > 100 || value.codePoints().anyMatch(Character::isISOControl))) throw invalid();
        return value == null || value.isBlank() ? null : value.strip();
    }
    private static String fingerprint(UUID school, UUID refund, long version, String reason) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("refund-account-reopen:v1|" + school + "|" + refund + "|" + version + "|" + reason).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static RefundAccountReopenModels.Result replayResult(Replay old, UUID refund, String fingerprint) {
        if (!old.refundId().equals(refund) || !old.fingerprint().equals(fingerprint)) throw conflict("REFUND_ACCOUNT_REOPEN_REQUEST_REUSED", "This request identity belongs to different original account review terms.");
        return result(old.id(), old.refundId(), old.accountId(), old.action());
    }
    private static RefundAccountReopenModels.Result result(UUID id, UUID refundId, UUID accountId, String decision) {
        return new RefundAccountReopenModels.Result(id, refundId, accountId, state(decision), "APPROVE".equals(decision), false);
    }
    private static String state(String decision) {
        if (decision == null) return "SUBMITTED";
        return switch (decision) { case "APPROVE" -> "APPROVED"; case "REJECT" -> "REJECTED"; case "CANCEL" -> "CANCELLED";
            default -> throw new IllegalStateException("Invalid immutable account review decision"); };
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "REFUND_ACCOUNT_REOPEN_FILTER_INVALID", "Choose valid school, review terms, literal search and bounded page."); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "REFUND_ACCOUNT_REOPEN_NOT_FOUND", "The original refund or account review is unavailable for this school."); }
    private static ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN, "REFUND_ACCOUNT_REOPEN_SCOPE_DENIED", "Account reopening is unavailable for this school and session."); }
    private static ApiException unavailable() { return conflict("REFUND_ACCOUNT_REOPEN_UNAVAILABLE", "The original CLOSED account, pending review or retained refund evidence changed. Refresh and review the preserved history."); }
    private static ApiException conflict(String code, String copy) { return new ApiException(HttpStatus.CONFLICT, code, copy); }
    private record Source(UUID paymentId, UUID accountId, UUID sessionId, UUID learnerId, UUID enrolmentId, String currency, String status,
            long version, UUID openerId, Instant openedAt, UUID closerId, Instant closedAt, String closeReason, String closerName,
            String learnerName, String sessionName, UUID captureId, UUID readId, String holdHash, int holdVersion, BigDecimal holdAmount,
            String sourceState, BigDecimal balance, boolean pending) { @Override public String toString() { return "Source[private original account and HOLD]"; } }
    private record Replay(UUID id, UUID refundId, UUID accountId, String fingerprint, UUID captureId, String action) { @Override public String toString() { return "Replay[private immutable review identity]"; } }
    private record DecisionSource(UUID refundId, UUID accountId, UUID maker, long version, UUID captureId) { @Override public String toString() { return "DecisionSource[private account review source]"; } }
    private record OldDecision(String action, String reason, UUID actor) { @Override public String toString() { return "OldDecision[private immutable account decision]"; } }
    private record QueueRows(long total, List<ReviewRow> rows) { @Override public String toString() { return "QueueRows[private account review page]"; } }
    private record ReviewRow(UUID id, UUID refundId, UUID paymentId, UUID accountId, String learnerName, String sessionName, String state,
            String reason, Instant requestedAt, UUID maker, String makerName, long version, RefundAccountReopenModels.Closure closure,
            boolean approvalReady, RefundAccountReopenModels.Decision decision) { @Override public String toString() { return "ReviewRow[private account review projection]"; } }
}

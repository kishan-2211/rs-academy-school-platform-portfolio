package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.finance.FamilyFeesResponse.ReceiptItem;
import com.educore.api.finance.FamilyFeesResponse.StatementItem;
import com.educore.api.finance.FinanceSnapshot.ChargeItem;
import com.educore.api.identity.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FamilyFinanceService {
    private final NamedParameterJdbcTemplate jdbc;
    public FamilyFinanceService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public FamilyFeesResponse fees(AuthenticatedUser user, UUID learnerId) {
        if (user == null || user.userId() == null || learnerId == null || user.membershipIds().isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FAMILY_CHILD_NOT_FOUND",
                    "This learner is not available to your verified relationship.");
        }
        if (user.activeOrganisationId() == null) throw new ApiException(HttpStatus.CONFLICT,
                "ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        // Preserve the default account selection below, but reauthorize the real
        // actor/relationship at the same live boundary as explicit selections.
        new FamilyGatewayAccess(jdbc).requireChild(user, learnerId);
        var parameters = new MapSqlParameterSource()
                .addValue("actorUserId", user.userId()).addValue("membershipIds", user.membershipIds())
                .addValue("organisationId", user.activeOrganisationId()).addValue("learnerId", learnerId);
        List<FamilyContext> contexts = jdbc.query("""
                SELECT learner.id AS learner_id,learner.school_id,school.currency_code,
                       enrolment.academic_session_id,session.name AS session_name,account.id AS account_id
                FROM guardians guardian
                JOIN user_accounts actor ON actor.id=guardian.user_account_id AND actor.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=guardian.organisation_id AND organisation.status='ACTIVE'
                JOIN learner_guardian_relationships relationship
                  ON relationship.guardian_id=guardian.id
                 AND relationship.organisation_id=guardian.organisation_id
                 AND relationship.school_id=guardian.school_id
                 AND relationship.portal_access_status='ACTIVE'
                JOIN learners learner ON learner.id=relationship.learner_id
                 AND learner.organisation_id=relationship.organisation_id
                 AND learner.school_id=relationship.school_id AND learner.status='ACTIVE'
                JOIN schools school ON school.id=learner.school_id
                  AND school.organisation_id=learner.organisation_id AND school.status='ACTIVE'
                JOIN LATERAL (
                  SELECT candidate.* FROM student_enrolments candidate
                  WHERE candidate.learner_id=learner.id AND candidate.organisation_id=learner.organisation_id
                    AND candidate.school_id=learner.school_id AND candidate.status='ACTIVE'
                  ORDER BY candidate.starts_on DESC,candidate.id DESC LIMIT 1
                ) enrolment ON true
                JOIN academic_sessions session ON session.id=enrolment.academic_session_id
                LEFT JOIN learner_fee_accounts account ON account.learner_id=learner.id
                 AND account.academic_session_id=enrolment.academic_session_id
                 AND account.organisation_id=learner.organisation_id AND account.school_id=learner.school_id
                WHERE guardian.user_account_id=:actorUserId AND guardian.organisation_id=:organisationId
                  AND guardian.status='ACTIVE' AND learner.id=:learnerId
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
                """, parameters, (result, row) -> new FamilyContext(
                result.getObject("learner_id", UUID.class), result.getObject("school_id", UUID.class),
                result.getString("currency_code"), result.getObject("academic_session_id", UUID.class),
                result.getString("session_name"), result.getObject("account_id", UUID.class)));
        if (contexts.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "FAMILY_CHILD_NOT_FOUND",
                "This learner is not available to your verified relationship.");
        FamilyContext context = contexts.getFirst();
        String providerStatus = providerStatus(parameters.addValue("schoolId", context.schoolId()));
        if (context.accountId() == null) return new FamilyFeesResponse(learnerId, null,
                context.sessionName(), context.currency(), providerStatus, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(), List.of());
        UUID accountId = context.accountId();
        parameters.addValue("accountId", accountId);
        return statement(learnerId, accountId, context.sessionName(), context.currency(), providerStatus, parameters);
    }

    /** Exact historical selection never changes the legacy latest-active default. */
    @Transactional(readOnly = true)
    public FamilyFeesResponse fees(AuthenticatedUser user, UUID learnerId, UUID accountId) {
        if (accountId == null) return fees(user, learnerId);
        var account = new FamilyGatewayAccess(jdbc).requireAccount(user, learnerId, accountId);
        var parameters = new MapSqlParameterSource("organisationId", account.scope().organisationId())
                .addValue("schoolId", account.scope().schoolId()).addValue("accountId", account.accountId());
        return statement(learnerId, account.accountId(), account.sessionName(), account.currencyCode(),
                providerStatus(parameters), parameters);
    }

    /** Live own-child access, including CLOSED accounts without an active enrolment. */
    @Transactional(readOnly = true)
    public FamilyFeeAccountsResponse feeAccounts(AuthenticatedUser user, UUID learnerId, int page, int pageSize) {
        var scope = new FamilyGatewayAccess(jdbc).requireChild(user, learnerId);
        if (page < 0 || page > 10000 || pageSize < 1 || pageSize > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FAMILY_FEE_ACCOUNT_FILTER_INVALID",
                    "Choose a bounded fee-account page and page size.");
        }
        var parameters = new MapSqlParameterSource("organisationId", scope.organisationId())
                .addValue("schoolId", scope.schoolId()).addValue("learnerId", learnerId)
                .addValue("fetchSize", pageSize + 1).addValue("offset", (long) page * pageSize);
        AccountPage result = jdbc.query("""
                WITH current_enrolment AS MATERIALIZED (
                  SELECT candidate.academic_session_id FROM student_enrolments candidate
                  WHERE candidate.learner_id=:learnerId AND candidate.organisation_id=:organisationId
                    AND candidate.school_id=:schoolId AND candidate.status='ACTIVE'
                  ORDER BY candidate.starts_on DESC,candidate.id DESC LIMIT 1
                ), current_account AS MATERIALIZED (
                  SELECT enrolment.academic_session_id,account.id FROM current_enrolment enrolment
                  LEFT JOIN learner_fee_accounts account ON account.academic_session_id=enrolment.academic_session_id
                    AND account.learner_id=:learnerId AND account.organisation_id=:organisationId AND account.school_id=:schoolId
                )
                SELECT current_context.current_account_id,accounts.*
                FROM (SELECT (SELECT id FROM current_account) AS current_account_id,
                  (SELECT academic_session_id FROM current_account) AS current_session_id) current_context
                LEFT JOIN LATERAL (
                  SELECT account.id AS account_id,session.id AS session_id,session.name AS session_name,
                    session.starts_on AS session_starts_on,account.status,account.currency_code,
                    COALESCE((SELECT sum(CASE entry.direction WHEN 'DEBIT' THEN entry.amount ELSE -entry.amount END)
                      FROM fee_ledger_entries entry WHERE entry.learner_fee_account_id=account.id
                        AND entry.organisation_id=account.organisation_id AND entry.school_id=account.school_id),0) AS balance,
                    COALESCE(account.academic_session_id=current_context.current_session_id,false) AS current_session
                  FROM learner_fee_accounts account
                  JOIN academic_sessions session ON session.id=account.academic_session_id
                    AND session.organisation_id=account.organisation_id AND session.school_id=account.school_id
                  WHERE account.learner_id=:learnerId AND account.organisation_id=:organisationId AND account.school_id=:schoolId
                    AND account.status IN ('ACTIVE','CLOSED')
                  ORDER BY session.starts_on DESC,session.id DESC,account.id DESC LIMIT :fetchSize OFFSET :offset
                ) accounts ON true
                ORDER BY accounts.session_starts_on DESC,accounts.session_id DESC,accounts.account_id DESC
                """, parameters, (ResultSetExtractor<AccountPage>) rows -> {
            if (!rows.next()) throw new IllegalStateException("Family fee-account page unavailable");
            UUID currentAccountId = rows.getObject("current_account_id", UUID.class);
            var accounts = new ArrayList<FamilyFeeAccountsResponse.Account>();
            do {
                UUID id = rows.getObject("account_id", UUID.class);
                if (id != null) accounts.add(new FamilyFeeAccountsResponse.Account(id, rows.getObject("session_id", UUID.class),
                        rows.getString("session_name"), rows.getString("status"), rows.getString("currency_code"),
                        rows.getBigDecimal("balance"), rows.getBoolean("current_session")));
            } while (rows.next());
            return new AccountPage(currentAccountId, List.copyOf(accounts));
        });
        if (result == null) throw new IllegalStateException("Family fee-account page unavailable");
        return new FamilyFeeAccountsResponse(learnerId, result.currentAccountId(), page, pageSize,
                result.accounts().size() > pageSize, result.accounts().stream().limit(pageSize).toList());
    }

    private FamilyFeesResponse statement(UUID learnerId, UUID accountId, String sessionName, String currency,
            String providerStatus, MapSqlParameterSource parameters) {
        Totals totals = jdbc.queryForObject("""
                SELECT COALESCE(sum(CASE WHEN entry_type='CHARGE' AND direction='DEBIT' THEN amount ELSE 0 END),0) debits,
                       COALESCE(sum(CASE WHEN direction='CREDIT' THEN amount
                         WHEN direction='DEBIT' AND entry_type<>'CHARGE' THEN -amount ELSE 0 END),0) credits,
                       COALESCE(sum(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END),0) balance
                FROM fee_ledger_entries WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_fee_account_id=:accountId
                """, parameters, (result, row) -> new Totals(result.getBigDecimal("debits"),
                result.getBigDecimal("credits"), result.getBigDecimal("balance")));
        List<ChargeItem> charges = jdbc.query("""
                SELECT charge.*,
                  COALESCE((SELECT sum(entry.amount) FROM fee_ledger_entries entry
                    WHERE entry.learner_fee_charge_id=charge.id AND entry.entry_type='CONCESSION'),0) concession_amount,
                  fee_charge_net_paid(charge.organisation_id,charge.school_id,charge.id,NULL::date) paid_amount,
                  COALESCE((SELECT sum(reservation.amount) FROM fee_gateway_allocations reservation
                    WHERE reservation.learner_fee_charge_id=charge.id AND reservation.organisation_id=charge.organisation_id
                      AND reservation.school_id=charge.school_id AND reservation.status='ACTIVE'),0) reserved_amount
                FROM learner_fee_charges charge WHERE charge.organisation_id=:organisationId
                  AND charge.school_id=:schoolId AND charge.learner_fee_account_id=:accountId
                ORDER BY charge.due_on,charge.created_at,charge.id
                """, parameters, (result, row) -> {
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
        List<ReceiptItem> receipts = jdbc.query("""
                SELECT payment.id,payment.receipt_number,payment.payment_method,payment.amount,payment.received_on,payment.status,payment.created_at,
                  COALESCE(manual.original_received_on,capture.original_business_date,payment.received_on) AS original_received_on,
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
                """, parameters, (result, row) -> new ReceiptItem(result.getObject("id", UUID.class),
                result.getString("receipt_number"), result.getString("payment_method"),
                result.getBigDecimal("amount"), result.getObject("received_on", LocalDate.class),
                result.getString("status"), result.getTimestamp("created_at").toInstant(),
                new FinanceSnapshot.ReceiptTiming(result.getObject("original_received_on", LocalDate.class),
                        GatewayExactInstant.readNullable(result,"actual_received_at_exact","actual_received_at"),
                        result.getString("received_time_precision"), result.getString("received_time_zone"))));
        List<StatementItem> statement = jdbc.query("""
                SELECT id,entry_type,direction,amount,effective_on,description,created_at
                FROM fee_ledger_entries WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND learner_fee_account_id=:accountId ORDER BY effective_on DESC,created_at DESC,id DESC
                """, parameters, (result, row) -> new StatementItem(result.getObject("id", UUID.class),
                result.getString("entry_type"), result.getString("direction"), result.getBigDecimal("amount"),
                result.getObject("effective_on", LocalDate.class), result.getString("description"),
                result.getTimestamp("created_at").toInstant()));
        List<FamilyFeesResponse.RefundItem> refunds = jdbc.query("""
                SELECT completion.id,completion.fee_payment_id,payment.receipt_number,completion.amount,completion.currency_code,
                  completion.posted_on,success.processed_at_exact,success.processed_on,success.school_time_zone
                FROM fee_gateway_refund_postings completion
                JOIN fee_payments payment ON payment.id=completion.fee_payment_id
                  AND payment.organisation_id=completion.organisation_id AND payment.school_id=completion.school_id
                  AND payment.learner_fee_account_id=completion.learner_fee_account_id
                JOIN fee_gateway_refund_successes success ON success.refund_request_id=completion.refund_request_id
                  AND success.organisation_id=completion.organisation_id AND success.school_id=completion.school_id
                WHERE completion.organisation_id=:organisationId AND completion.school_id=:schoolId
                  AND completion.learner_fee_account_id=:accountId
                ORDER BY completion.posted_on DESC,completion.posted_at DESC,completion.id
                """, parameters, (row, index) -> new FamilyFeesResponse.RefundItem(row.getObject("id", UUID.class),
                    row.getObject("fee_payment_id", UUID.class), row.getString("receipt_number"), row.getBigDecimal("amount"),
                    row.getString("currency_code"), row.getObject("posted_on", LocalDate.class), row.getString("processed_at_exact"),
                    row.getObject("processed_on", LocalDate.class), row.getString("school_time_zone")));
        return new FamilyFeesResponse(learnerId, accountId, sessionName, currency,
                providerStatus, totals.debits(), totals.credits(), totals.balance(), charges, receipts, statement, refunds);
    }

    private String providerStatus(MapSqlParameterSource parameters) {
        List<String> rows = jdbc.query("""
                SELECT environment FROM payment_provider_configs WHERE organisation_id=:organisationId
                  AND school_id=:schoolId AND status='ACTIVE' ORDER BY environment DESC LIMIT 1
                """, parameters, (result, row) -> result.getString("environment"));
        return rows.isEmpty() ? "NOT_CONFIGURED" : "LIVE".equals(rows.getFirst()) ? "LIVE_REDIRECT" : "TEST_REDIRECT";
    }

    private record FamilyContext(UUID learnerId, UUID schoolId, String currency, UUID sessionId,
            String sessionName, UUID accountId) {}
    private record Totals(BigDecimal debits, BigDecimal credits, BigDecimal balance) {}
    private record AccountPage(UUID currentAccountId, List<FamilyFeeAccountsResponse.Account> accounts) {
        @Override public String toString() { return "AccountPage[private own-child fee-account page]"; }
    }
}

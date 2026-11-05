package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.tenancy.TenantRequestContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Focused selection, live-boundary and projection contracts; real RLS is tested separately. */
class FamilyHistoricalFeesTest {
    private final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), learner = UUID.randomUUID(), member = UUID.randomUUID();
    private final UUID account = UUID.randomUUID(), currentAccount = UUID.randomUUID(), session = UUID.randomUUID(), charge = UUID.randomUUID();
    private final UUID receipt = UUID.randomUUID(), chargeEntry = UUID.randomUUID(), paymentEntry = UUID.randomUUID();
    private final Instant paidAt = Instant.parse("2026-10-18T10:20:30.123456789Z");
    private final AuthenticatedUser parent = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(),
            "synthetic-historical-family", null, "Synthetic guardian", false, org, Set.of(member), Set.of("portal.guardian"), List.of());
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final FamilyFinanceService service = new FamilyFinanceService(jdbc);
    private final List<String> queries = new ArrayList<>();
    private boolean linked = true, matchingAccount = true, defaultHasAccount = true;
    private List<Map<String, Object>> pageRows = List.of();
    private List<Map<String, Object>> refundRows = List.of();

    @AfterEach void cleanup() {
        TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void explicitClosedAccountUsesItsOwnCurrencyStatementAndExactOriginalReceiptEvidence() throws Exception {
        stub();
        var statement = run(() -> service.fees(parent, learner, account));
        assertThat(statement.accountId()).isEqualTo(account);
        assertThat(statement.academicSessionName()).isEqualTo("Synthetic historical session");
        assertThat(statement.currencyCode()).isEqualTo("INR");
        assertThat(statement.totalCharges()).isEqualByComparingTo("1000.00");
        assertThat(statement.totalCredits()).isEqualByComparingTo("200.00");
        assertThat(statement.balance()).isEqualByComparingTo("800.00");
        assertThat(statement.charges()).hasSize(1);
        assertThat(statement.charges().getFirst().outstandingAmount()).isEqualByComparingTo("800.00");
        assertThat(statement.receipts()).hasSize(1);
        assertThat(statement.receipts().getFirst().paymentId()).isEqualTo(receipt);
        assertThat(statement.receipts().getFirst().timing().actualReceivedAt()).isEqualTo(paidAt);
        assertThat(statement.statement()).extracting(FamilyFeesResponse.StatementItem::id).containsExactly(chargeEntry, paymentEntry);
        assertThat(statement.refunds()).isEmpty();
        assertThat(String.join("\n", queries)).doesNotContain("student_enrolments", "fee_gateway_refund_resolutions", "reopen");
        assertReadsOnly();
    }

    @Test void explicitSelectionDoesNotResolveAGatewayOrAddConfirmedButUnpostedRefundMoney() throws Exception {
        stub();
        var statement = run(() -> service.fees(parent, learner, account));
        assertThat(statement.balance()).isEqualByComparingTo("800.00");
        assertThat(statement.onlinePaymentStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(statement.refunds()).isEmpty();
        assertThat(String.join("\n", queries)).doesNotContain("hold_amount", "credentials_secret_reference", "merchant_account_reference");
        assertReadsOnly();
    }

    @Test void completedRefundProjectionRetainsOriginalReceiptAndExactProcessingAndPostingDates() throws Exception {
        UUID completion = UUID.randomUUID();
        Instant processedAt = Instant.parse("2026-10-19T06:07:08.123456789Z");
        refundRows = List.of(values("id", completion, "fee_payment_id", receipt, "receipt_number", "SYN-OLD-001",
                "amount", new BigDecimal("50.25"), "currency_code", "INR", "posted_on", LocalDate.of(2026, 10, 20),
                "processed_at_exact", GatewayExactInstant.canonical(processedAt), "processed_on", LocalDate.of(2026, 10, 19),
                "school_time_zone", "Asia/Kolkata"));
        stub();
        var statement = run(() -> service.fees(parent, learner, account));
        assertThat(statement.refunds()).hasSize(1);
        var refund = statement.refunds().getFirst();
        assertThat(refund.id()).isEqualTo(completion);
        assertThat(refund.paymentId()).isEqualTo(receipt);
        assertThat(refund.receiptNumber()).isEqualTo("SYN-OLD-001");
        assertThat(refund.amount()).isEqualByComparingTo("50.25");
        assertThat(refund.processedAtExact()).isEqualTo(GatewayExactInstant.canonical(processedAt));
        assertThat(refund.processedOn()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(refund.postingOn()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(refund.schoolTimeZone()).isEqualTo("Asia/Kolkata");
        assertThat(statement.receipts().getFirst().amount()).isEqualByComparingTo("200.00");
        assertThat(statement.receipts().getFirst().timing().actualReceivedAt()).isEqualTo(paidAt);
        assertReadsOnly();
    }

    @Test void exactAccountScopeIsNotReplacedWithALatestOrDifferentChildAccount() throws Exception {
        matchingAccount = false; stub();
        run(() -> { missing(() -> service.fees(parent, learner, account)); return null; });
        assertThat(queries).noneMatch(sql -> sql.contains("FROM fee_ledger_entries") || sql.contains("SELECT environment"));
    }

    @Test void revokedRelationshipDeniesExplicitListAndLegacyDefaultBeforeFinancialReads() throws Exception {
        linked = false; stub();
        run(() -> {
            missing(() -> service.fees(parent, learner, account));
            missing(() -> service.feeAccounts(parent, learner, 0, 20));
            missing(() -> service.fees(parent, learner));
            return null;
        });
        assertThat(queries).allMatch(sql -> sql.contains("SELECT membership.school_id"));
    }

    @Test void explicitAndAccountListRequireAnActualMatchingActorOrganisationAndTransaction() {
        missing(() -> service.fees(parent, learner, account));
        missing(() -> service.feeAccounts(parent, learner, 0, 20));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        missing(() -> service.fees(parent, learner, account));
        missing(() -> service.feeAccounts(parent, learner, 0, 20));
        for (UUID actor : List.of(UUID.randomUUID())) {
            try (var ignored = TenantRequestContext.open(actor, org)) { missing(() -> service.fees(parent, learner, account)); }
        }
        try (var ignored = TenantRequestContext.open(parent.userId(), UUID.randomUUID())) {
            missing(() -> service.feeAccounts(parent, learner, 0, 20));
        }
        try (var ignored = TenantRequestContext.open(null, org)) { missing(() -> service.fees(parent, learner, account)); }
        verifyNoInteractions(jdbc);
    }

    @Test void defaultAlsoRequiresLiveActorContextRatherThanCachedGuardianAuthority() {
        missing(() -> service.fees(parent, learner));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), org)) { missing(() -> service.fees(parent, learner)); }
        verifyNoInteractions(jdbc);
    }

    @Test void emptyMembershipsOrMissingLearnerNeverEnumerateHistoricalAccounts() {
        var noMembership = new AuthenticatedUser(parent.userId(), parent.sessionId(), parent.loginIdentifier(), null,
                parent.displayName(), false, org, Set.of(), Set.of("portal.guardian"), List.of());
        run(() -> {
            missing(() -> service.feeAccounts(noMembership, learner, 0, 20));
            missing(() -> service.fees(parent, null, account));
            return null;
        });
        verifyNoInteractions(jdbc);
    }

    @Test void omittedAccountRetainsExactLegacyLatestActiveSelectionAndItsStatement() throws Exception {
        stub();
        var implicit = run(() -> service.fees(parent, learner));
        var nullable = run(() -> service.fees(parent, learner, null));
        assertThat(nullable).isEqualTo(implicit);
        assertThat(implicit.accountId()).isEqualTo(currentAccount);
        assertThat(implicit.academicSessionName()).isEqualTo("Synthetic current session");
        assertThat(queries).anyMatch(sql -> sql.contains("ORDER BY candidate.starts_on DESC,candidate.id DESC LIMIT 1"));
        assertReadsOnly();
    }

    @Test void legacyNoCurrentAccountStillReturnsItsExistingEmptyStatementShape() throws Exception {
        defaultHasAccount = false; stub();
        var statement = run(() -> service.fees(parent, learner, null));
        assertThat(statement.accountId()).isNull();
        assertThat(statement.academicSessionName()).isEqualTo("Synthetic current session");
        assertThat(statement.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(statement.charges()).isEmpty(); assertThat(statement.receipts()).isEmpty(); assertThat(statement.statement()).isEmpty();
        assertThat(queries).noneMatch(sql -> sql.contains("FROM fee_ledger_entries"));
    }

    @Test void listKeepsSignedBalancesStablePagingAndCurrentAccountIndependentOfItsPage() throws Exception {
        pageRows = List.of(pageRow(account, false, "CLOSED", "-25.25"), pageRow(UUID.randomUUID(), false, "ACTIVE", "10.00"));
        stub();
        var page = run(() -> service.feeAccounts(parent, learner, 2, 1));
        assertThat(page.currentAccountId()).isEqualTo(currentAccount);
        assertThat(page.page()).isEqualTo(2); assertThat(page.pageSize()).isEqualTo(1); assertThat(page.hasMore()).isTrue();
        assertThat(page.accounts()).hasSize(1);
        assertThat(page.accounts().getFirst().id()).isEqualTo(account);
        assertThat(page.accounts().getFirst().status()).isEqualTo("CLOSED");
        assertThat(page.accounts().getFirst().balance()).isEqualByComparingTo("-25.25");
        assertThat(page.accounts().getFirst().currentSession()).isFalse();
        assertReadsOnly();
    }

    @Test void currentSessionSummaryIsOnlyAHintAndDoesNotHideClosedAccounts() throws Exception {
        pageRows = List.of(pageRow(currentAccount, true, "CLOSED", "800.00")); stub();
        var page = run(() -> service.feeAccounts(parent, learner, 0, 20));
        assertThat(page.accounts().getFirst().currentSession()).isTrue();
        assertThat(page.accounts().getFirst().status()).isEqualTo("CLOSED");
        assertThat(page.hasMore()).isFalse();
        assertThat(String.join("\n", queries)).doesNotContain("session.status='ACTIVE'", "capture_id", "fee_gateway_refund");
    }

    @Test void emptyPageRetainsCurrentAccountWithoutInventingAnAccountRow() throws Exception {
        pageRows = List.of(values("current_account_id", currentAccount)); stub();
        var page = run(() -> service.feeAccounts(parent, learner, 10000, 50));
        assertThat(page.currentAccountId()).isEqualTo(currentAccount);
        assertThat(page.accounts()).isEmpty(); assertThat(page.hasMore()).isFalse();
    }

    @Test void historicalOnlyAccountListRetainsExplicitNullCurrentIdUnderApplicationNonNullDefaults() throws Exception {
        var historical = pageRow(account, false, "ACTIVE", "800.00"); historical.put("current_account_id", null);
        pageRows = List.of(historical); stub();
        var page = run(() -> service.feeAccounts(parent, learner, 0, 20));
        assertThat(page.currentAccountId()).isNull(); assertThat(page.accounts()).hasSize(1);
        var tree = JsonMapper.builder().changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build().valueToTree(page);
        assertThat(tree.has("currentAccountId")).isTrue(); assertThat(tree.get("currentAccountId").isNull()).isTrue();
        assertThat(tree.size()).isEqualTo(6);
        assertThat(tree.get("accounts").get(0).size()).isEqualTo(7);
        assertThat(tree.toString()).doesNotContain("closedBy", "reason", "refund", "provider", "merchant", "credentials");
    }

    @Test void boundedPagingRejectsInvalidTermsWithoutReadingAnyAccounts() throws Exception {
        stub();
        run(() -> {
            for (int[] invalid : List.of(new int[]{-1, 20}, new int[]{10001, 20}, new int[]{0, 0}, new int[]{0, 51})) {
                assertThatThrownBy(() -> service.feeAccounts(parent, learner, invalid[0], invalid[1]))
                        .isInstanceOfSatisfying(ApiException.class, error -> {
                            assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                            assertThat(error.code()).isEqualTo("FAMILY_FEE_ACCOUNT_FILTER_INVALID");
                        });
            }
            return null;
        });
        assertThat(queries).allMatch(sql -> sql.contains("SELECT membership.school_id"));
    }

    @Test void familyAccountProjectionIsImmutableAndRedactsDiagnostics() {
        var item = new FamilyFeeAccountsResponse.Account(account, session, "Synthetic private session", "CLOSED", "INR", new BigDecimal("-25.25"), false);
        var mutable = new ArrayList<>(List.of(item));
        var response = new FamilyFeeAccountsResponse(learner, null, 0, 20, false, mutable); mutable.clear();
        assertThat(response.accounts()).containsExactly(item);
        assertThatThrownBy(() -> response.accounts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(item.toString()).doesNotContain(account.toString(), session.toString(), "Synthetic private session", "25.25");
        assertThat(response.toString()).doesNotContain(learner.toString(), account.toString());
    }

    private <T> T run(Supplier<T> action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (var ignored = TenantRequestContext.open(parent.userId(), org)) { return action.get(); }
    }

    private static void missing(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(error.code()).isEqualTo("FAMILY_PAYMENT_NOT_FOUND");
        });
    }

    private void assertReadsOnly() {
        assertThat(mockingDetails(jdbc).getInvocations()).allMatch(call -> Set.of("query", "queryForObject").contains(call.getMethod().getName()));
    }

    @SuppressWarnings("unchecked")
    private void stub() throws Exception {
        doAnswer(call -> {
            String sql = call.getArgument(0); SqlParameterSource parameters = call.getArgument(1);
            RowMapper<Object> mapper = call.getArgument(2); queries.add(sql);
            var mapped = new ArrayList<>();
            var source = rows(sql, parameters);
            for (int index = 0; index < source.size(); index++) mapped.add(mapper.mapRow(resultSet(List.of(source.get(index)), false), index));
            return mapped;
        }).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        doAnswer(call -> {
            String sql = call.getArgument(0); SqlParameterSource parameters = call.getArgument(1);
            RowMapper<Object> mapper = call.getArgument(2); queries.add(sql);
            assertStatementScope(parameters);
            assertThat(sql).contains("FROM fee_ledger_entries", "learner_fee_account_id=:accountId");
            return mapper.mapRow(resultSet(List.of(values("debits", new BigDecimal("1000.00"), "credits", new BigDecimal("200.00"), "balance", new BigDecimal("800.00"))), false), 0);
        }).when(jdbc).queryForObject(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        doAnswer(call -> {
            String sql = call.getArgument(0); SqlParameterSource parameters = call.getArgument(1);
            ResultSetExtractor<Object> extractor = call.getArgument(2); queries.add(sql);
            assertThat(parameters.getValue("organisationId")).isEqualTo(org);
            assertThat(parameters.getValue("schoolId")).isEqualTo(school);
            assertThat(parameters.getValue("learnerId")).isEqualTo(learner);
            assertThat(((Number) parameters.getValue("fetchSize")).intValue()).isBetween(2, 51);
            assertThat(((Number) parameters.getValue("offset")).longValue()).isBetween(0L, 500000L);
            assertThat(sql).contains("WITH current_enrolment AS MATERIALIZED", "candidate.status='ACTIVE'",
                    "ORDER BY candidate.starts_on DESC,candidate.id DESC LIMIT 1", "account.status IN ('ACTIVE','CLOSED')",
                    "session.organisation_id=account.organisation_id", "entry.organisation_id=account.organisation_id",
                    "ORDER BY session.starts_on DESC,session.id DESC,account.id DESC LIMIT :fetchSize OFFSET :offset");
            return extractor.extractData(resultSet(pageRows, true));
        }).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(ResultSetExtractor.class));
    }

    private List<Map<String, Object>> rows(String sql, SqlParameterSource p) {
        assertThat(p.getValue("organisationId")).isEqualTo(org);
        if (sql.contains("SELECT membership.school_id")) {
            assertThat(p.getValue("learnerId")).isEqualTo(learner);
            assertThat(p.getValue("membershipIds")).isEqualTo(parent.membershipIds());
            assertThat(sql).contains("current_app_user_id()=:userId", "current_app_organisation_id()=:organisationId",
                    "membership.valid_from<=clock_timestamp()", "membership.valid_until>clock_timestamp()",
                    "relationship.portal_access_status='ACTIVE'", "learner.status='ACTIVE'", "capability.capability_code='portal.guardian'");
            return linked ? List.of(values("school_id", school, "membership_id", member)) : List.of();
        }
        if (sql.contains("FROM learner_fee_accounts account JOIN academic_sessions")) {
            assertThat(p.getValue("schoolId")).isEqualTo(school); assertThat(p.getValue("accountId")).isEqualTo(account);
            assertThat(p.getValue("learnerId")).isEqualTo(learner);
            assertThat(sql).contains("account.learner_id=:learnerId", "account.organisation_id=:organisationId", "account.school_id=:schoolId")
                    .doesNotContain("student_enrolments", "account.status='ACTIVE'");
            return matchingAccount ? List.of(values("id", account, "status", "CLOSED", "currency_code", "INR", "session_name", "Synthetic historical session")) : List.of();
        }
        if (sql.contains("FROM guardians guardian")) {
            assertThat(sql).contains("ORDER BY candidate.starts_on DESC,candidate.id DESC LIMIT 1");
            return List.of(values("learner_id", learner, "school_id", school, "currency_code", "INR",
                    "academic_session_id", session, "session_name", "Synthetic current session", "account_id", defaultHasAccount ? currentAccount : null));
        }
        if (sql.contains("SELECT environment")) { assertThat(p.getValue("schoolId")).isEqualTo(school); return List.of(); }
        assertStatementScope(p);
        if (sql.contains("FROM fee_gateway_refund_postings completion")) {
            assertThat(sql).contains("completion.organisation_id=:organisationId", "completion.school_id=:schoolId",
                    "completion.learner_fee_account_id=:accountId", "payment.organisation_id=completion.organisation_id",
                    "payment.school_id=completion.school_id", "payment.learner_fee_account_id=completion.learner_fee_account_id",
                    "success.refund_request_id=completion.refund_request_id", "success.organisation_id=completion.organisation_id",
                    "success.school_id=completion.school_id", "ORDER BY completion.posted_on DESC,completion.posted_at DESC,completion.id")
                    .doesNotContain("fee_gateway_refund_resolutions", "hold_amount", "merchant_account_reference", "credentials_secret_reference");
            return refundRows;
        }
        if (sql.contains("FROM learner_fee_charges charge")) return List.of(values("id", charge,
                "fee_head_code_snapshot", "SYN", "fee_head_name_snapshot", "Synthetic tuition", "label_snapshot", "Synthetic old installment",
                "due_on", LocalDate.of(2025, 10, 1), "amount", new BigDecimal("1000.00"), "concession_amount", BigDecimal.ZERO,
                "paid_amount", new BigDecimal("200.00"), "allow_partial", true, "rte_exemption_amount", BigDecimal.ZERO, "reserved_amount", BigDecimal.ZERO));
        if (sql.contains("FROM fee_payments payment")) return List.of(values("id", receipt, "receipt_number", "SYN-OLD-001",
                "payment_method", "ONLINE_PROVIDER", "amount", new BigDecimal("200.00"), "received_on", LocalDate.of(2026, 10, 18),
                "status", "POSTED", "created_at", paidAt.plusSeconds(1), "original_received_on", LocalDate.of(2026, 10, 18),
                "actual_received_at", GatewayExactInstant.floor(paidAt).atOffset(ZoneOffset.UTC), "actual_received_at_exact", GatewayExactInstant.canonical(paidAt),
                "received_time_precision", "INSTANT", "received_time_zone", "Asia/Kolkata"));
        if (sql.contains("FROM fee_ledger_entries")) return List.of(
                values("id", chargeEntry, "entry_type", "CHARGE", "direction", "DEBIT", "amount", new BigDecimal("1000.00"),
                        "effective_on", LocalDate.of(2025, 10, 1), "description", "Synthetic original charge", "created_at", paidAt.minusSeconds(10)),
                values("id", paymentEntry, "entry_type", "PAYMENT", "direction", "CREDIT", "amount", new BigDecimal("200.00"),
                        "effective_on", LocalDate.of(2026, 10, 18), "description", "Synthetic original receipt", "created_at", paidAt.plusSeconds(1)));
        throw new AssertionError("Unexpected read query in focused fixture");
    }

    private void assertStatementScope(SqlParameterSource p) {
        assertThat(p.getValue("schoolId")).isEqualTo(school);
        assertThat(p.getValue("accountId")).isIn(account, currentAccount);
    }

    private Map<String, Object> pageRow(UUID id, boolean current, String state, String balance) {
        return values("current_account_id", currentAccount, "account_id", id, "session_id", session,
                "session_name", "Synthetic historical session", "status", state, "currency_code", "INR",
                "balance", new BigDecimal(balance), "current_session", current);
    }

    private static Map<String, Object> values(Object... entries) {
        var values = new HashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) values.put((String) entries[index], entries[index + 1]);
        return values;
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows, boolean beforeFirst) {
        var position = new AtomicInteger(beforeFirst ? -1 : 0);
        return mock(ResultSet.class, call -> {
            String method = call.getMethod().getName();
            if (method.equals("next")) return position.incrementAndGet() < rows.size();
            if (call.getArguments().length > 0 && call.getArgument(0) instanceof String column) {
                Object value = rows.get(position.get()).get(column);
                if (method.equals("getObject") || method.equals("getBigDecimal")) return value;
                if (method.equals("getString")) return value == null ? null : value.toString();
                if (method.equals("getBoolean")) return Boolean.TRUE.equals(value);
                if (method.equals("getTimestamp")) return value instanceof Instant instant ? Timestamp.from(instant) : value;
            }
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
    }
}

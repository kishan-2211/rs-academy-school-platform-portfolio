package com.educore.api.accounting;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Query/admission unit evidence only; real PostgreSQL guards and financial posting are separate tests. */
class ReviewedRefundAccountingTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 20);

    @AfterEach void clearMarkers() { TransactionSynchronizationManager.clear(); }

    @Test void admissionRequiresTheExistingWritableTransaction() throws Exception {
        var annotation = AccountingService.class.getMethod("validateFeeRefundPosting", AuthenticatedUser.class,
                UUID.class, UUID.class, UUID.class, LocalDate.class, List.class).getAnnotation(Transactional.class);
        assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(annotation.readOnly()).isFalse();
        var f = new Fixture();
        try (var ignored = TenantRequestContext.open(f.actor, f.org)) {
            assertThatThrownBy(f::validate).isInstanceOf(ApiException.class);
            writable(); TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThatThrownBy(f::validate).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(f.jdbc, f.audit);
    }

    @Test void missingWrongHumanOrForeignOrganisationCannotUseTheBridge() {
        var f = new Fixture(); writable();
        assertThatThrownBy(f::validate).isInstanceOf(ApiException.class);
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), f.org)) {
            assertThatThrownBy(f::validate).isInstanceOf(ApiException.class);
        }
        try (var ignored = TenantRequestContext.open(f.actor, UUID.randomUUID())) {
            assertThatThrownBy(f::validate).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(f.jdbc, f.audit);
    }

    @Test void originalSourceAndLiveActorAreCheckedAfterTheCommonAccountingLock() throws Exception {
        var f = new Fixture(); f.install();
        try (var ignored = f.context()) {
            var book = f.validate();
            assertThat(book.financialYearId()).isEqualTo(f.year);
            assertThat(book.accountingPeriodId()).isEqualTo(f.period);
        }
        assertThat(f.events).containsExactly("scope", "accounting-lock", "isolation", "live-actor", "original", "book", "accounts");
        assertThat(f.actorSql).contains("current_app_user_id()=:userId", "clock_timestamp()", "actor.status='ACTIVE'",
                "school.status='ACTIVE'", "organisation.status='ACTIVE'", "grant_capability.capability_code=:capability",
                "role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id");
        assertThat(f.originalSql).contains("pending.status='PENDING'", "original.source_module='FEES'", "FOR SHARE OF original",
                "original.organisation_id=:organisationId", "original.school_id=:schoolId");
        assertThat(f.lockKey).isEqualTo("accounting:school:" + f.school);
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(f.audit);
    }

    @Test void absentOrRevokedDatabaseActorFailsBeforeReadingOriginalMoney() throws Exception {
        var f = new Fixture(); f.install(); f.liveActor = false;
        try (var ignored = f.context()) { assertThatThrownBy(f::validate).isInstanceOf(ApiException.class); }
        assertThat(f.events).doesNotContain("original", "book", "accounts");
    }

    @Test void staleRepeatableReadAndSerializableSnapshotsCannotAdmitRefunds() throws Exception {
        for (String isolation : List.of("repeatable read", "serializable")) {
            var f = new Fixture(); f.install(); f.isolation = isolation;
            try (var ignored = f.context()) {
                assertThatThrownBy(f::validate).isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo("FEE_REFUND_ACCOUNTING_RETRY_REQUIRED"));
            }
            assertThat(f.events).doesNotContain("original", "book", "accounts");
        }
    }

    @Test void pendingReversedOrNotPostedOriginalsNeverAdmitCounterEntries() throws Exception {
        for (String kind : List.of("PENDING", "REVERSED", "SUBMITTED", "REVERSAL")) {
            var f = new Fixture(); f.install();
            if (kind.equals("PENDING")) f.original.put("has_pending_reversal", true);
            else if (kind.equals("REVERSED")) f.original.put("has_reversal", true);
            else if (kind.equals("REVERSAL")) f.original.put("reversal_of_journal_id", UUID.randomUUID());
            else f.original.put("status", kind);
            try (var ignored = f.context()) { assertThatThrownBy(f::validate).isInstanceOf(ApiException.class); }
            assertThat(f.events).doesNotContain("book", "accounts");
        }
    }

    @Test void closedCurrentBookOrChangedBasisCurrencyCannotRewriteOriginalEconomics() throws Exception {
        for (String kind : List.of("YEAR", "PERIOD", "BASIS", "CURRENCY")) {
            var f = new Fixture(); f.install();
            switch (kind) {
                case "YEAR" -> f.book.put("financial_year_status", "CLOSED");
                case "PERIOD" -> f.book.put("accounting_period_status", "LOCKED");
                case "BASIS" -> f.book.put("accounting_basis", "ACCRUAL");
                default -> f.book.put("currency_code", "USD");
            }
            try (var ignored = f.context()) { assertThatThrownBy(f::validate).isInstanceOf(ApiException.class); }
            verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        }
    }

    @Test void originalClearingMustStillBeAnActiveDirectClearingAssetNotBankCash() throws Exception {
        for (String kind : List.of("BANK", "INACTIVE", "INDIRECT")) {
            var f = new Fixture(); f.install();
            if (kind.equals("BANK")) f.account.put("category", "BANK");
            if (kind.equals("INACTIVE")) f.account.put("status", "INACTIVE");
            if (kind.equals("INDIRECT")) f.account.put("allow_direct_posting", false);
            try (var ignored = f.context()) { assertThatThrownBy(f::validate).isInstanceOf(ApiException.class); }
        }
    }

    @Test void sameHumanWithDifferentMembershipCannotApproveOrRejectRefundJournal() throws Exception {
        for (String decision : List.of("POST", "REJECT")) {
            var f = new Fixture(); f.install(); f.refundDecision = true;
            try (var ignored = f.context()) {
                assertThatThrownBy(() -> f.service.decideJournal(f.user, f.refundJournal,
                        new AccountingController.DecisionRequest(f.school, decision, "Synthetic independent decision", 0)))
                        .isInstanceOfSatisfying(ApiException.class,
                                failure -> assertThat(failure.code()).isEqualTo("INDEPENDENT_REFUND_JOURNAL_DECISION_REQUIRED"));
            }
            verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        }
    }

    @Test void ordinaryJournalDecisionDoesNotReadRefundOnlyProvenanceOrAddRefundAuthorityRequirements() throws Exception {
        var f = new Fixture(); f.install();
        when(f.jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        try (var ignored = f.context()) {
            var result = f.service.decideJournal(f.user, f.refundJournal,
                    new AccountingController.DecisionRequest(f.school, "REJECT", "Synthetic ordinary journal decision", 0));
            assertThat(result.rowVersion()).isEqualTo(1);
        }
        assertThat(f.events).contains("refund-probe").doesNotContain("isolation", "live-actor", "original", "book", "accounts");
    }

    private static void writable() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private static final class Fixture {
        final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), actor = UUID.randomUUID(), membership = UUID.randomUUID();
        final UUID year = UUID.randomUUID(), period = UUID.randomUUID(), originalJournal = UUID.randomUUID(), clearing = UUID.randomUUID();
        final UUID refundJournal = UUID.randomUUID(), otherMembership = UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        final SecurityAuditService audit = mock(SecurityAuditService.class);
        final AccountingService service = new AccountingService(jdbc, audit, Clock.fixed(Instant.parse("2026-10-20T06:00:00Z"), ZoneOffset.UTC));
        final AuthenticatedUser user = new AuthenticatedUser(actor, UUID.randomUUID(), "synthetic", null, "Synthetic user", true,
                org, Set.of(membership), Set.of(), List.of());
        final Map<String, Object> original = new HashMap<>(Map.of("status", "POSTED", "accounting_basis", "CASH", "currency_code", "INR",
                "has_reversal", false, "has_pending_reversal", false));
        final Map<String, Object> book = new HashMap<>(Map.of("financial_year_id", year, "accounting_period_id", period,
                "year_starts_on", LocalDate.of(2026,4,1), "year_ends_on", LocalDate.of(2027,3,31), "accounting_basis", "CASH",
                "currency_code", "INR", "financial_year_status", "OPEN", "accounting_period_status", "OPEN"));
        final Map<String, Object> account = new HashMap<>(Map.of("id", clearing, "code", "SYNTHETIC_CLEARING", "display_name", "Synthetic clearing",
                "account_type", "ASSET", "category", "OTHER", "status", "ACTIVE", "allow_direct_posting", true));
        final List<String> events = new ArrayList<>();
        boolean liveActor = true, refundDecision;
        String isolation = "read committed", lockKey, actorSql, originalSql;

        TenantRequestContext.Scope context() { writable(); return TenantRequestContext.open(actor, org); }
        FeeBookContext validate() { return service.validateFeeRefundPosting(user, school, originalJournal, year, DATE,
                List.of(new FeeAccountUse(clearing, FeeAccountUseType.ONLINE_CLEARING))); }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void install() throws Exception {
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenAnswer(call -> {
                events.add("accounting-lock"); lockKey = (String) call.<MapSqlParameterSource>getArgument(1).getValue("lockKey"); return 1;
            });
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(String.class))).thenAnswer(call -> {
                events.add("isolation"); return isolation;
            });
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> {
                if (call.<String>getArgument(0).contains("FROM fee_accounting_posting_batches batch")) {
                    events.add("refund-probe"); return refundDecision;
                }
                actorSql = call.getArgument(0); events.add("live-actor"); return liveActor;
            });
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
                String sql = call.getArgument(0); RowMapper mapper = call.getArgument(2); Map<String,Object> values;
                if (sql.contains("reviewed-refund-original-journal")) { events.add("original"); originalSql = sql; values = original; }
                else if (sql.contains("reviewed-refund-journal-decision")) {
                    if (!refundDecision) return List.of();
                    values = Map.of("submitter_user_id", actor, "original_journal_id", originalJournal,
                            "financial_year_id", year, "accounting_period_id", period, "posting_date", DATE);
                } else if (sql.contains("FROM accounting_journals") && sql.contains("FOR UPDATE")) {
                    values = new HashMap<>(Map.of("id", refundJournal, "financial_year_id", year, "accounting_period_id", period,
                            "voucher_type", "PAYMENT", "status", "SUBMITTED", "created_by_membership_id", otherMembership,
                            "submitted_by_membership_id", otherMembership, "request_fingerprint", "a".repeat(64), "row_version", 0L));
                } else if (sql.contains("FROM memberships membership")) {
                    events.add("scope"); values = new HashMap<>(Map.of("school_id", school, "display_name", "Synthetic school", "membership_id", membership,
                            "can_prepare", true, "can_post", true));
                } else if (sql.contains("FROM accounting_financial_years")) { events.add("book"); values = book; }
                else throw new AssertionError("Unexpected unit query route");
                return List.of(mapper.mapRow(row(values), 0));
            });
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class))).thenAnswer(call -> {
                events.add("accounts"); ResultSet result = row(account); int[] index = {-1};
                when(result.next()).thenAnswer(next -> ++index[0] == 0);
                return ((ResultSetExtractor) call.getArgument(2)).extractData(result);
            });
        }

        private static ResultSet row(Map<String,Object> values) throws Exception {
            var result = mock(ResultSet.class);
            when(result.getObject(anyString(), any(Class.class))).thenAnswer(call -> values.get(call.<String>getArgument(0)));
            when(result.getObject(anyString())).thenAnswer(call -> values.get(call.<String>getArgument(0)));
            when(result.getString(anyString())).thenAnswer(call -> (String) values.get(call.<String>getArgument(0)));
            when(result.getBoolean(anyString())).thenAnswer(call -> Boolean.TRUE.equals(values.get(call.<String>getArgument(0))));
            when(result.getLong(anyString())).thenAnswer(call -> ((Number)values.getOrDefault(call.<String>getArgument(0), 0L)).longValue());
            return result;
        }
    }
}

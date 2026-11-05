package com.educore.api.accounting;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked bridge guards and query contracts; real non-owner PostgreSQL coverage is separate. */
class GatewayAccountingReadinessTest {
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 12);

    @AfterEach
    void clearOnlyTestTransactionMarkers() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void absentHumanAndWrongOrganisationContextsCannotReadAnAccountingBook() {
        var f = new Fixture();
        writable();
        assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), f.org)) {
            assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
        }
        try (var ignored = TenantRequestContext.open(null, UUID.randomUUID())) {
            assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(f.jdbc, f.audit);
    }

    @Test
    void absentAndReadOnlyTransactionsDenyBeforePersistenceAccess() {
        var f = new Fixture();
        try (var ignored = TenantRequestContext.open(null, f.org)) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
            writable();
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(f.jdbc, f.audit);
    }

    @Test
    void incompleteExplicitScopeDateAndMappingListFailBeforeDatabaseAccess() {
        var f = new Fixture();
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.service.validateGatewayCheckoutBook(null, f.school, BUSINESS_DATE, null, List.of()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> f.service.validateGatewayCheckoutBook(f.org, null, BUSINESS_DATE, null, List.of()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> f.service.validateGatewayCheckoutBook(f.org, f.school, null, null, List.of()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> f.service.validateGatewayCheckoutBook(f.org, f.school, BUSINESS_DATE, null, null))
                    .isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(f.jdbc, f.audit);
    }

    @Test
    void actualDatabaseContextAndInactiveScopeCannotBeReplacedByThreadMarkers() throws Exception {
        for (Boolean accepted : Arrays.asList(false, null)) {
            var f = new Fixture();
            f.install();
            when(f.jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(accepted);
            try (var ignored = background(f)) {
                assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
            }
            assertThat(f.reads).isEmpty();
            verify(f.jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class));
            verifyNoInteractions(f.audit);
        }
    }

    @Test
    void matchingSystemContextUsesCurrentDatabaseScopeAndTheExistingAccountingFence() throws Exception {
        var f = new Fixture();
        f.install();
        try (var ignored = background(f)) {
            var value = f.validate();
            assertThat(value.financialYearId()).isEqualTo(f.year);
            assertThat(value.accountingPeriodId()).isEqualTo(f.period);
            assertThat(value.startsOn()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(value.endsOn()).isEqualTo(LocalDate.of(2027, 3, 31));
            assertThat(value.currencyCode()).isEqualTo("INR");
            assertThat(value.financialYearStatus()).isEqualTo("OPEN");
            assertThat(value.accountingPeriodStatus()).isEqualTo("OPEN");
        }
        assertThat(f.contextSql).contains("current_app_organisation_id()=:organisationId", "current_app_user_id() IS NULL",
                "organisation.status='ACTIVE'", "school.status='ACTIVE'", "school.organisation_id=:organisationId");
        assertThat(f.contextParameters).containsEntry("organisationId", f.org).containsEntry("schoolId", f.school);
        assertThat(f.lockSql).contains("pg_advisory_xact_lock", "hashtextextended(:lockKey,0)");
        assertThat(f.lockParameters).containsEntry("lockKey", "accounting:school:" + f.school);
        assertThat(f.events).containsExactly("database-context", "accounting-lock", "accounting-book");
        assertNoFinancialMutation(f);
    }

    @Test
    void dateSchoolOrganisationAndOptionalExpectedYearAreBoundWithoutFallback() throws Exception {
        var f = new Fixture();
        f.install();
        try (var ignored = background(f)) {
            assertThat(f.service.validateGatewayCheckoutBook(f.org, f.school, BUSINESS_DATE, f.year, List.of()).financialYearId())
                    .isEqualTo(f.year);
            assertThatThrownBy(() -> f.service.validateGatewayCheckoutBook(f.org, f.school, BUSINESS_DATE, UUID.randomUUID(), List.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
        var read = f.reads.getFirst();
        assertThat(read.sql()).contains("year.organisation_id=:organisationId", "year.school_id=:schoolId",
                "period.organisation_id=year.organisation_id", "period.school_id=year.school_id",
                ":businessDate BETWEEN year.starts_on AND year.ends_on",
                ":businessDate BETWEEN period.starts_on AND period.ends_on",
                "CAST(:expectedYearId AS uuid) IS NULL OR year.id=:expectedYearId", "FOR SHARE OF year,period");
        assertThat(read.parameters()).containsEntry("organisationId", f.org).containsEntry("schoolId", f.school)
                .containsEntry("businessDate", BUSINESS_DATE).containsEntry("expectedYearId", f.year);
        assertThat(read.parameters()).doesNotContainKeys("actorMembershipId", "actorUserId", "userId");
        assertNoFinancialMutation(f);
    }

    @Test
    void missingAndAmbiguousDateCoveredBooksCannotEnableCheckout() throws Exception {
        var f = new Fixture();
        f.install();
        try (var ignored = background(f)) {
            f.books.clear();
            assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
            f.books.add(f.openBook());
            f.books.add(f.openBook());
            assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
        }
        assertNoFinancialMutation(f);
    }

    @Test
    void datesOutsideTheYearCannotSelectAnotherAccountingBook() throws Exception {
        var f = new Fixture();
        f.install();
        try (var ignored = background(f)) {
            for (LocalDate date : List.of(LocalDate.of(2026, 3, 31), LocalDate.of(2027, 4, 1))) {
                assertThatThrownBy(() -> f.service.validateGatewayCheckoutBook(f.org, f.school, date, null, List.of()))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
        assertNoFinancialMutation(f);
    }

    @Test
    void closedDraftAndAbsentPeriodStatesFailWithoutOpeningOrPostingAnything() throws Exception {
        var f = new Fixture();
        f.install();
        try (var ignored = background(f)) {
            for (String state : List.of("DRAFT", "CLOSED")) {
                f.books.set(0, f.openBook());
                f.books.getFirst().put("financial_year_status", state);
                assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
                f.books.set(0, f.openBook());
                f.books.getFirst().put("accounting_period_status", state);
                assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
            }
            f.books.set(0, f.openBook());
            f.books.getFirst().put("accounting_period_id", null);
            assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
        }
        assertNoFinancialMutation(f);
    }

    @Test
    void onlyIndianRupeesAndExistingCashOrAccrualBasesAreEligible() throws Exception {
        var f = new Fixture();
        f.install();
        try (var ignored = background(f)) {
            for (String currency : List.of("USD", "inr", "INR ", "")) {
                f.books.set(0, f.openBook());
                f.books.getFirst().put("currency_code", currency);
                assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
            }
            f.books.set(0, f.openBook());
            f.books.getFirst().put("accounting_basis", "UNKNOWN");
            assertThatThrownBy(f::validate).isInstanceOf(IllegalStateException.class);
            for (String basis : List.of("CASH", "ACCRUAL")) {
                f.books.set(0, f.openBook());
                f.books.getFirst().put("accounting_basis", basis);
                assertThat(f.validate().accountingBasis()).isEqualTo(basis);
            }
        }
        assertNoFinancialMutation(f);
    }

    @Test
    void nullMalformedAndExactDuplicateMappingUsesCannotBecomeApprovalEvidence() throws Exception {
        var f = new Fixture();
        f.install();
        var clearing = new FeeAccountUse(f.account, FeeAccountUseType.ONLINE_CLEARING);
        try (var ignored = background(f)) {
            for (List<FeeAccountUse> mappings : List.of(
                    Arrays.asList((FeeAccountUse) null),
                    List.of(new FeeAccountUse(null, FeeAccountUseType.INCOME)),
                    List.of(new FeeAccountUse(f.account, null)),
                    List.of(clearing, clearing))) {
                assertThatThrownBy(() -> f.service.validateGatewayCheckoutBook(f.org, f.school, BUSINESS_DATE, null, mappings))
                        .isInstanceOf(IllegalStateException.class);
            }
        }
        assertThat(f.reads).allMatch(read -> !read.sql().contains("FROM accounting_accounts"));
        assertNoFinancialMutation(f);
    }

    @Test
    void accountMappingsRequireScopedActiveDirectPostingAccounts() throws Exception {
        var f = new Fixture();
        f.install();
        var mappings = List.of(new FeeAccountUse(f.account, FeeAccountUseType.ONLINE_CLEARING));
        try (var ignored = background(f)) {
            f.accounts.clear();
            assertThatThrownBy(() -> f.validate(mappings)).isInstanceOf(IllegalStateException.class);
            f.accounts.put(f.account, f.accountRow("ASSET", "OTHER", "INACTIVE", true));
            assertThatThrownBy(() -> f.validate(mappings)).isInstanceOf(IllegalStateException.class);
            f.accounts.put(f.account, f.accountRow("ASSET", "OTHER", "ACTIVE", false));
            assertThatThrownBy(() -> f.validate(mappings)).isInstanceOf(IllegalStateException.class);
            f.accounts.put(f.account, f.accountRow("ASSET", "OTHER", "ACTIVE", true));
            assertThat(f.validate(mappings).financialYearId()).isEqualTo(f.year);
        }
        var read = f.reads.getLast();
        assertThat(read.sql()).contains("organisation_id=:organisationId", "school_id=:schoolId", "id IN(:accountIds)", "FOR SHARE");
        assertThat(read.parameters()).containsEntry("organisationId", f.org).containsEntry("schoolId", f.school)
                .containsEntry("accountIds", java.util.Set.of(f.account));
        assertNoFinancialMutation(f);
    }

    @Test
    void everyExistingAccountUseStillRequiresItsMatchingTypeAndCategory() throws Exception {
        var f = new Fixture();
        f.install();
        var uses = Map.of(
                FeeAccountUseType.RECEIVABLE, List.of("ASSET", "RECEIVABLE"),
                FeeAccountUseType.CONCESSION, List.of("EXPENSE", "EXPENSE"),
                FeeAccountUseType.INCOME, List.of("INCOME", "INCOME"),
                FeeAccountUseType.CASH, List.of("ASSET", "CASH"),
                FeeAccountUseType.BANK, List.of("ASSET", "BANK"));
        try (var ignored = background(f)) {
            for (var use : uses.entrySet()) {
                var mappings = List.of(new FeeAccountUse(f.account, use.getKey()));
                f.accounts.put(f.account, f.accountRow(use.getValue().get(0), use.getValue().get(1), "ACTIVE", true));
                assertThat(f.validate(mappings).financialYearId()).isEqualTo(f.year);
                f.accounts.put(f.account, f.accountRow("LIABILITY", "OTHER", "ACTIVE", true));
                assertThatThrownBy(() -> f.validate(mappings)).isInstanceOf(IllegalStateException.class);
            }
        }
        assertNoFinancialMutation(f);
    }

    @Test
    void newOnlineCheckoutRequiresClearingAssetAndNeverClaimsUnsettledFundsAreBankOrCash() throws Exception {
        var f = new Fixture();
        f.install();
        var mappings = List.of(new FeeAccountUse(f.account, FeeAccountUseType.ONLINE_CLEARING));
        try (var ignored = background(f)) {
            for (List<String> classification : List.of(List.of("ASSET", "BANK"), List.of("ASSET", "CASH"),
                    List.of("ASSET", "RECEIVABLE"), List.of("LIABILITY", "OTHER"), List.of("INCOME", "OTHER"))) {
                f.accounts.put(f.account, f.accountRow(classification.get(0), classification.get(1), "ACTIVE", true));
                assertThatThrownBy(() -> f.validate(mappings)).isInstanceOf(IllegalStateException.class);
            }
            f.accounts.put(f.account, f.accountRow("ASSET", "OTHER", "ACTIVE", true));
            assertThat(f.validate(mappings).financialYearId()).isEqualTo(f.year);
        }
        assertNoFinancialMutation(f);
    }

    @Test
    void existingOnlineTenderBankAndClearingMappingsRemainCompatible() throws Exception {
        var f = new Fixture();
        f.install();
        try (var ignored = background(f)) {
            for (String category : List.of("BANK", "OTHER")) {
                f.accounts.put(f.account, f.accountRow("ASSET", category, "ACTIVE", true));
                assertThat(f.validate(List.of(new FeeAccountUse(f.account, FeeAccountUseType.ONLINE_TENDER))).financialYearId())
                        .isEqualTo(f.year);
            }
            f.accounts.put(f.account, f.accountRow("ASSET", "CASH", "ACTIVE", true));
            assertThatThrownBy(() -> f.validate(List.of(new FeeAccountUse(f.account, FeeAccountUseType.ONLINE_TENDER))))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertNoFinancialMutation(f);
    }

    @Test
    void distinctCompatibleUsesForTheSameAccountAreCheckedWithoutDuplicateDatabaseRows() throws Exception {
        var f = new Fixture();
        f.install();
        var compatible = List.of(new FeeAccountUse(f.account, FeeAccountUseType.ONLINE_CLEARING),
                new FeeAccountUse(f.account, FeeAccountUseType.ONLINE_TENDER));
        try (var ignored = background(f)) {
            assertThat(f.validate(compatible).financialYearId()).isEqualTo(f.year);
            var incompatible = List.of(new FeeAccountUse(f.account, FeeAccountUseType.ONLINE_CLEARING),
                    new FeeAccountUse(f.account, FeeAccountUseType.CASH));
            assertThatThrownBy(() -> f.validate(incompatible)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(f.reads.getLast().parameters()).containsEntry("accountIds", java.util.Set.of(f.account));
        assertNoFinancialMutation(f);
    }

    @Test
    void mappingScopeRequiresEveryRequestedAccountRatherThanASubset() throws Exception {
        var f = new Fixture();
        f.install();
        var mappings = List.of(new FeeAccountUse(f.account, FeeAccountUseType.ONLINE_CLEARING),
                new FeeAccountUse(UUID.randomUUID(), FeeAccountUseType.RECEIVABLE));
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.validate(mappings)).isInstanceOf(IllegalStateException.class);
        }
        assertNoFinancialMutation(f);
    }

    @Test
    void oversizedMappingListsFailBeforeExpandingTheAccountQuery() throws Exception {
        var f = new Fixture();
        f.install();
        var mappings = new ArrayList<FeeAccountUse>();
        for (int index = 0; index < 104; index++) {
            mappings.add(new FeeAccountUse(UUID.randomUUID(), FeeAccountUseType.INCOME));
        }
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.validate(mappings)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(f.reads).allMatch(read -> !read.sql().contains("FROM accounting_accounts"));
        assertNoFinancialMutation(f);
    }

    @Test
    void boundaryCannotManufactureHumanIdentityAndRequiresTheCallersExistingTransaction() throws Exception {
        var method = AccountingService.class.getDeclaredMethod("validateGatewayCheckoutBook", UUID.class,
                UUID.class, LocalDate.class, UUID.class, List.class);
        var annotation = method.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(annotation.readOnly()).isFalse();
        assertThat(Arrays.stream(method.getParameterTypes()).map(Class::getName).toList())
                .noneMatch(name -> name.contains("AuthenticatedUser"));
    }

    private static void assertNoFinancialMutation(Fixture f) {
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(f.audit);
        assertThat(f.reads).allMatch(read -> !read.sql().matches("(?is).*\\b(INSERT|UPDATE|DELETE)\\b.*"));
    }

    private static TenantRequestContext.Scope background(Fixture f) {
        writable();
        return TenantRequestContext.open(null, f.org);
    }

    private static void writable() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    private record Read(String sql, Map<String, Object> parameters) {}

    private static final class Fixture {
        final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), year = UUID.randomUUID();
        final UUID period = UUID.randomUUID(), account = UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        final SecurityAuditService audit = mock(SecurityAuditService.class);
        final AccountingService service = new AccountingService(jdbc, audit,
                Clock.fixed(Instant.parse("2026-10-12T06:00:00Z"), ZoneOffset.UTC));
        final List<Map<String, Object>> books = new ArrayList<>(List.of(openBook()));
        final Map<UUID, Map<String, Object>> accounts = new LinkedHashMap<>();
        final List<Read> reads = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        String contextSql, lockSql;
        Map<String, Object> contextParameters, lockParameters;

        FeeBookContext validate() {
            return validate(List.of());
        }

        FeeBookContext validate(List<FeeAccountUse> mappings) {
            return service.validateGatewayCheckoutBook(org, school, BUSINESS_DATE, null, mappings);
        }

        Map<String, Object> openBook() {
            var value = new HashMap<String, Object>();
            value.put("financial_year_id", year);
            value.put("accounting_period_id", period);
            value.put("year_starts_on", LocalDate.of(2026, 4, 1));
            value.put("year_ends_on", LocalDate.of(2027, 3, 31));
            value.put("accounting_basis", "ACCRUAL");
            value.put("currency_code", "INR");
            value.put("financial_year_status", "OPEN");
            value.put("accounting_period_status", "OPEN");
            return value;
        }

        Map<String, Object> accountRow(String type, String category, String status, boolean direct) {
            return Map.of("id", account, "code", "SYNTHETIC_CLEARING", "display_name", "Synthetic clearing",
                    "account_type", type, "category", category, "status", status, "allow_direct_posting", direct);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void install() throws Exception {
            accounts.put(account, accountRow("ASSET", "OTHER", "ACTIVE", true));
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> {
                contextSql = call.getArgument(0);
                contextParameters = new LinkedHashMap<>(call.<MapSqlParameterSource>getArgument(1).getValues());
                events.add("database-context");
                return true;
            });
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenAnswer(call -> {
                lockSql = call.getArgument(0);
                lockParameters = new LinkedHashMap<>(call.<MapSqlParameterSource>getArgument(1).getValues());
                events.add("accounting-lock");
                return 1;
            });
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                var parameters = call.<MapSqlParameterSource>getArgument(1);
                reads.add(new Read(sql, new LinkedHashMap<>(parameters.getValues())));
                events.add("accounting-book");
                RowMapper mapper = call.getArgument(2);
                var values = new ArrayList<>();
                LocalDate date = (LocalDate) parameters.getValue("businessDate");
                UUID expected = (UUID) parameters.getValue("expectedYearId");
                for (var book : books) {
                    if ((expected == null || expected.equals(book.get("financial_year_id")))
                            && !date.isBefore((LocalDate) book.get("year_starts_on"))
                            && !date.isAfter((LocalDate) book.get("year_ends_on"))) {
                        values.add(mapper.mapRow(row(book), values.size()));
                    }
                }
                return values;
            });
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                var parameters = call.<MapSqlParameterSource>getArgument(1);
                reads.add(new Read(sql, new LinkedHashMap<>(parameters.getValues())));
                events.add("accounting-mappings");
                var ids = (java.util.Set<UUID>) parameters.getValue("accountIds");
                var selected = accounts.entrySet().stream().filter(entry -> ids.contains(entry.getKey()))
                        .map(Map.Entry::getValue).toList();
                var result = mock(ResultSet.class);
                int[] index = {-1};
                when(result.next()).thenAnswer(ignored -> ++index[0] < selected.size());
                when(result.getObject(anyString(), any(Class.class))).thenAnswer(read -> selected.get(index[0]).get(read.<String>getArgument(0)));
                when(result.getString(anyString())).thenAnswer(read -> (String) selected.get(index[0]).get(read.<String>getArgument(0)));
                when(result.getBoolean(anyString())).thenAnswer(read -> (Boolean) selected.get(index[0]).get(read.<String>getArgument(0)));
                return ((ResultSetExtractor) call.getArgument(2)).extractData(result);
            });
        }

        private static ResultSet row(Map<String, Object> values) throws Exception {
            var result = mock(ResultSet.class);
            when(result.getObject(anyString(), any(Class.class))).thenAnswer(read -> values.get(read.<String>getArgument(0)));
            when(result.getString(anyString())).thenAnswer(read -> (String) values.get(read.<String>getArgument(0)));
            return result;
        }
    }
}

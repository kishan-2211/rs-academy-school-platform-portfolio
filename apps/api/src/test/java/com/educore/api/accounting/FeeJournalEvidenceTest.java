package com.educore.api.accounting;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

class FeeJournalEvidenceTest {
    @Test
    void internalReaderIsReadOnlyAndDoesNotAddAReadinessOrPostingDecision() throws Exception {
        var method = AccountingService.class.getDeclaredMethod("feeJournalEvidence",
                AuthenticatedUser.class, UUID.class, List.class);
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(AccountingModuleBridge.class.getDeclaredMethod("feeJournalEvidence",
                AuthenticatedUser.class, UUID.class, List.class).getReturnType()).isEqualTo(Map.class);
    }

    @Test
    void completeOriginalAndReversalDimensionsRemainSeparateWithBothGrossSides() throws Exception {
        var f = new Fixture();
        UUID vendor = UUID.randomUUID(), learner = UUID.randomUUID(), otherLearner = UUID.randomUUID();
        f.rows.add(f.line(f.journal, f.account, vendor, learner, "125.00", "25.00"));
        f.rows.add(f.line(f.journal, f.account, vendor, otherLearner, "0.00", "100.00"));
        f.rows.add(f.line(f.reversal, f.account, vendor, learner, "25.00", "125.00"));
        f.install();

        var evidence = f.read(List.of(f.journal)).get(f.journal);

        assertThat(evidence.id()).isEqualTo(f.journal);
        assertThat(evidence.sourceId()).isEqualTo(f.batch);
        assertThat(evidence.financialYearId()).isEqualTo(f.year);
        assertThat(evidence.accountingPeriodId()).isEqualTo(f.period);
        assertThat(evidence.postingDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(evidence.accountingBasis()).isEqualTo("ACCRUAL");
        assertThat(evidence.currencyCode()).isEqualTo("INR");
        assertThat(evidence.status()).isEqualTo("REVERSED");
        assertThat(evidence.financialYearStatus()).isEqualTo("CLOSED");
        assertThat(evidence.accountingPeriodStatus()).isEqualTo("CLOSED");
        assertThat(evidence.reversalId()).isEqualTo(f.reversal);
        assertThat(evidence.reversalStatus()).isEqualTo("SUBMITTED");
        assertThat(evidence.lines()).containsExactly(
                new FeeJournalLineEvidence(f.account, vendor, learner, new BigDecimal("125.00"),
                        new BigDecimal("25.00"), "ASSET", "OTHER", "ACTIVE", true),
                new FeeJournalLineEvidence(f.account, vendor, otherLearner, new BigDecimal("0.00"),
                        new BigDecimal("100.00"), "ASSET", "OTHER", "ACTIVE", true));
        assertThat(evidence.reversalLines()).containsExactly(
                new FeeJournalLineEvidence(f.account, vendor, learner, new BigDecimal("25.00"),
                        new BigDecimal("125.00"), "ASSET", "OTHER", "ACTIVE", true));
        assertReadOnly(f);
    }

    @Test
    void oneScopedStatementReadsWholeOriginalBatchesWithoutNettingOrTruncatingLines() throws Exception {
        var f = new Fixture();
        f.rows.add(f.line(f.journal, f.account, null, null, "80.00", "20.00"));
        f.install();

        f.read(List.of(f.journal, f.journal));

        assertThat(f.evidenceReads).hasSize(1);
        var read = f.evidenceReads.getFirst();
        assertThat(read.parameters()).containsEntry("organisationId", f.org)
                .containsEntry("schoolId", f.school).containsEntry("ids", Set.of(f.journal))
                .containsEntry("maxGroups", 20000);
        assertThat(read.sql()).contains("WITH selected AS MATERIALIZED", "normalized AS MATERIALIZED",
                "j.organisation_id=:organisationId AND j.school_id=:schoolId",
                "j.source_module='FEES' AND j.reversal_of_journal_id IS NULL",
                "y.organisation_id=j.organisation_id AND y.school_id=j.school_id",
                "p.organisation_id=j.organisation_id AND p.school_id=j.school_id",
                "p.financial_year_id=j.financial_year_id",
                "reversal.organisation_id=j.organisation_id AND reversal.school_id=j.school_id",
                "l.organisation_id=:organisationId AND l.school_id=:schoolId",
                "account.organisation_id=:organisationId AND account.school_id=:schoolId",
                "GROUP BY l.accounting_journal_id,l.account_id,l.vendor_id,l.learner_fee_account_id",
                "sum(l.debit_amount) debit_amount,sum(l.credit_amount) credit_amount",
                "SELECT count(*) normalized_group_count FROM normalized",
                "budget.normalized_group_count<=:maxGroups");
        assertThat(read.sql()).doesNotContain("debit_amount-l.credit_amount", "HAVING", "LIMIT",
                "status='POSTED'", "status='OPEN'", "FOR UPDATE", "FOR SHARE");
        assertReadOnly(f);
    }

    @Test
    void draftSubmittedRejectedAndClosedBookStatesAreEvidenceNotReadinessDecisions() throws Exception {
        var f = new Fixture();
        var row = f.line(f.journal, f.account, null, null, "50.00", "0.00");
        row.put("accounting_basis", "CASH");
        row.put("account_status", "INACTIVE");
        row.put("allow_direct_posting", false);
        row.put("reversal_id", null);
        row.put("reversal_status", null);
        f.rows.add(row);
        f.install();
        for (String status : List.of("DRAFT", "SUBMITTED", "REJECTED", "POSTED", "REVERSED")) {
            row.put("status", status);
            var evidence = f.read(List.of(f.journal)).get(f.journal);
            assertThat(evidence.status()).isEqualTo(status);
            assertThat(evidence.accountingBasis()).isEqualTo("CASH");
            assertThat(evidence.financialYearStatus()).isEqualTo("CLOSED");
            assertThat(evidence.lines().getFirst().accountStatus()).isEqualTo("INACTIVE");
            assertThat(evidence.lines().getFirst().allowDirectPosting()).isFalse();
            assertThat(evidence.reversalId()).isNull();
            assertThat(evidence.reversalLines()).isEmpty();
        }
        assertReadOnly(f);
    }

    @Test
    void aHeaderWithoutLinesDoesNotManufactureZeroAmountProof() throws Exception {
        var f = new Fixture();
        var row = f.header();
        row.put("line_journal_id", null);
        f.rows.add(row);
        f.install();

        var evidence = f.read(List.of(f.journal)).get(f.journal);

        assertThat(evidence.lines()).isEmpty();
        assertThat(evidence.reversalLines()).isEmpty();
        assertReadOnly(f);
    }

    @Test
    void absentCurrentAccountMetadataDoesNotSilentlyDropTheOriginalDimension() throws Exception {
        var f = new Fixture();
        var row = f.line(f.journal, f.account, null, null, "60.00", "0.00");
        row.put("account_type", null);
        row.put("account_category", null);
        row.put("account_status", null);
        row.put("allow_direct_posting", null);
        f.rows.add(row);
        f.install();

        var line = f.read(List.of(f.journal)).get(f.journal).lines().getFirst();

        assertThat(line.accountId()).isEqualTo(f.account);
        assertThat(line.debitAmount()).isEqualByComparingTo("60.00");
        assertThat(line.accountType()).isNull();
        assertThat(line.accountCategory()).isNull();
        assertThat(line.accountStatus()).isNull();
        assertThat(line.allowDirectPosting()).isFalse();
        assertReadOnly(f);
    }

    @Test
    void nullAndEmptyRequestsStillRequireLiveSchoolAuthority() throws Exception {
        var f = new Fixture();
        f.install();
        assertThat(f.read(null)).isEmpty();
        assertThat(f.read(List.of())).isEmpty();
        assertThat(f.scopeReads).hasSize(2);
        assertThat(f.evidenceReads).isEmpty();
        var scope = f.scopeReads.getFirst();
        assertThat(scope.sql()).contains("membership.user_id=:userId",
                "membership.organisation_id=:organisationId", "membership.id IN (:membershipIds)",
                "membership.status='ACTIVE'", "membership.school_id=:schoolId",
                "rc.capability_code='accounting.read'");
        assertThat(scope.parameters()).containsEntry("userId", f.userId)
                .containsEntry("organisationId", f.org).containsEntry("schoolId", f.school)
                .containsEntry("membershipIds", Set.of(f.membership));
        assertReadOnly(f);
    }

    @Test
    void cachedCapabilityCannotBypassRevokedOrForeignSchoolScopeEvenForEmptyInput() throws Exception {
        var f = new Fixture();
        f.install();
        f.authorized = false;
        assertDenied(() -> f.read(List.of(f.journal)), "ACCOUNTING_SCOPE_DENIED", HttpStatus.FORBIDDEN);
        assertDenied(() -> f.read(null), "ACCOUNTING_SCOPE_DENIED", HttpStatus.FORBIDDEN);
        f.authorized = true;
        assertDenied(() -> f.service.feeJournalEvidence(f.user, UUID.randomUUID(), List.of(f.journal)),
                "ACCOUNTING_SCOPE_DENIED", HttpStatus.FORBIDDEN);
        assertThat(f.evidenceReads).isEmpty();
        assertReadOnly(f);
    }

    @Test
    void missingOrganisationSelectionFailsBeforeAnyDatabaseRead() throws Exception {
        var f = new Fixture();
        var user = new AuthenticatedUser(f.userId, UUID.randomUUID(), "synthetic@example.test", null,
                "Synthetic actor", true, null, Set.of(f.membership), Set.of("accounting.read"), List.of());
        assertDenied(() -> f.service.feeJournalEvidence(user, f.school, List.of(f.journal)),
                "ORGANISATION_SELECTION_REQUIRED", HttpStatus.CONFLICT);
        verifyNoInteractions(f.jdbc, f.audit);
    }

    @Test
    void missingForeignAndReversalIdentifiersAreOmittedWithoutInventedEvidence() throws Exception {
        var f = new Fixture();
        f.rows.add(f.line(f.journal, f.account, null, null, "10.00", "0.00"));
        f.install();
        UUID absent = UUID.randomUUID(), foreign = UUID.randomUUID();
        var evidence = f.read(List.of(absent, foreign, f.reversal, f.journal));
        assertThat(evidence).containsOnlyKeys(f.journal);
        assertThat(f.read(List.of(absent, foreign, f.reversal))).isEmpty();
        assertReadOnly(f);
    }

    @Test
    void nullIdentifiersAndMoreThanOneThousandRawInputsHaveControlledErrors() throws Exception {
        var f = new Fixture();
        f.install();
        assertDenied(() -> f.read(Arrays.asList(f.journal, null)), "FEE_JOURNAL_EVIDENCE_INVALID",
                HttpStatus.BAD_REQUEST);
        assertDenied(() -> f.read(Collections.nCopies(1001, f.journal)), "FEE_JOURNAL_EVIDENCE_TOO_LARGE",
                HttpStatus.BAD_REQUEST);
        assertThat(f.evidenceReads).isEmpty();
        assertThat(f.read(Collections.nCopies(1000, f.journal))).isEmpty();
        assertThat(f.evidenceReads).hasSize(1);
        assertThat(f.evidenceReads.getFirst().parameters()).containsEntry("ids", Set.of(f.journal));
        assertReadOnly(f);
    }

    @Test
    void globalOriginalAndReversalGroupOverflowFailsTheWholeReadInsteadOfReturningASubset() throws Exception {
        var f = new Fixture();
        var first = f.line(f.journal, f.account, null, null, "10.00", "0.00");
        first.put("normalized_group_count", 20001L);
        f.rows.add(first);
        f.install();
        assertDenied(() -> f.read(List.of(f.journal)), "FEE_JOURNAL_EVIDENCE_TOO_LARGE",
                HttpStatus.BAD_REQUEST);
        first.put("normalized_group_count", 20000L);
        assertThat(f.read(List.of(f.journal))).containsOnlyKeys(f.journal);
        assertReadOnly(f);
    }

    @Test
    void evidenceAndBothLineListsAreDefensiveImmutableAndRedacted() throws Exception {
        var f = new Fixture();
        var row = f.line(f.journal, f.account, null, null, "98765.43", "123.45");
        f.rows.add(row);
        f.install();
        var result = f.read(List.of(f.journal));
        var evidence = result.get(f.journal);
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evidence.lines().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evidence.reversalLines().add(evidence.lines().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        var original = new ArrayList<>(evidence.lines());
        var reversal = new ArrayList<>(evidence.lines());
        var copy = new FeeJournalEvidence(evidence.id(), evidence.sourceId(), evidence.financialYearId(),
                evidence.accountingPeriodId(), evidence.postingDate(), evidence.accountingBasis(),
                evidence.currencyCode(), evidence.status(), evidence.financialYearStatus(),
                evidence.accountingPeriodStatus(), evidence.reversalId(), evidence.reversalStatus(),
                original, reversal);
        original.clear();
        reversal.clear();
        assertThat(copy.lines()).hasSize(1);
        assertThat(copy.reversalLines()).hasSize(1);
        assertThat(copy.toString()).isEqualTo("FeeJournalEvidence[redacted]");
        assertThat(copy.lines().getFirst().toString()).isEqualTo("FeeJournalLineEvidence[redacted]");
        assertReadOnly(f);
    }

    private static void assertDenied(Runnable action, String code, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.status()).isEqualTo(status);
        });
    }

    private static void assertReadOnly(Fixture f) {
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(f.audit);
        assertThat(f.evidenceReads).allMatch(read -> !read.sql().matches("(?is).*\\b(INSERT|UPDATE|DELETE)\\b.*"));
    }

    private record Read(String sql, Map<String, Object> parameters) {}

    private static final class Fixture {
        final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), membership = UUID.randomUUID();
        final UUID userId = UUID.randomUUID(), journal = UUID.randomUUID(), batch = UUID.randomUUID();
        final UUID year = UUID.randomUUID(), period = UUID.randomUUID(), reversal = UUID.randomUUID();
        final UUID account = UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        final SecurityAuditService audit = mock(SecurityAuditService.class);
        final AccountingService service = new AccountingService(jdbc, audit,
                Clock.fixed(Instant.parse("2026-10-12T06:00:00Z"), ZoneOffset.UTC));
        final AuthenticatedUser user = new AuthenticatedUser(userId, UUID.randomUUID(),
                "synthetic@example.test", null, "Synthetic actor", true, org, Set.of(membership),
                Set.of("accounting.read"), List.of());
        final List<Map<String, Object>> rows = new ArrayList<>();
        final List<Read> scopeReads = new ArrayList<>(), evidenceReads = new ArrayList<>();
        boolean authorized = true;

        Map<UUID, FeeJournalEvidence> read(List<UUID> ids) {
            return service.feeJournalEvidence(user, school, ids);
        }

        Map<String, Object> header() {
            var row = new HashMap<String, Object>();
            row.put("id", journal);
            row.put("source_id", batch);
            row.put("financial_year_id", year);
            row.put("accounting_period_id", period);
            row.put("posting_date", LocalDate.of(2026, 10, 12));
            row.put("accounting_basis", "ACCRUAL");
            row.put("currency_code", "INR");
            row.put("status", "REVERSED");
            row.put("financial_year_status", "CLOSED");
            row.put("accounting_period_status", "CLOSED");
            row.put("reversal_id", reversal);
            row.put("reversal_status", "SUBMITTED");
            row.put("normalized_group_count", 3L);
            return row;
        }

        Map<String, Object> line(UUID lineJournal, UUID accountId, UUID vendor, UUID learner,
                String debit, String credit) {
            var row = header();
            row.put("line_journal_id", lineJournal);
            row.put("account_id", accountId);
            row.put("vendor_id", vendor);
            row.put("learner_fee_account_id", learner);
            row.put("debit_amount", new BigDecimal(debit));
            row.put("credit_amount", new BigDecimal(credit));
            row.put("account_type", "ASSET");
            row.put("account_category", "OTHER");
            row.put("account_status", "ACTIVE");
            row.put("allow_direct_posting", true);
            return row;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void install() throws Exception {
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                var parameters = call.<MapSqlParameterSource>getArgument(1);
                scopeReads.add(new Read(sql, new LinkedHashMap<>(parameters.getValues())));
                UUID requestedSchool = (UUID) parameters.getValue("schoolId");
                if (!authorized || (requestedSchool != null && !school.equals(requestedSchool))) return List.of();
                var scope = new HashMap<String, Object>();
                scope.put("school_id", school);
                scope.put("display_name", "Synthetic school");
                scope.put("membership_id", membership);
                RowMapper mapper = call.getArgument(2);
                return List.of(mapper.mapRow(row(scope), 0));
            });
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                var parameters = call.<MapSqlParameterSource>getArgument(1);
                evidenceReads.add(new Read(sql, new LinkedHashMap<>(parameters.getValues())));
                var requested = (Set<UUID>) parameters.getValue("ids");
                var selected = rows.stream().filter(value -> requested.contains(value.get("id"))).toList();
                int[] index = {-1};
                var result = mock(ResultSet.class);
                when(result.next()).thenAnswer(ignored -> ++index[0] < selected.size());
                when(result.getObject(anyString(), any(Class.class)))
                        .thenAnswer(read -> selected.get(index[0]).get(read.<String>getArgument(0)));
                when(result.getString(anyString()))
                        .thenAnswer(read -> (String) selected.get(index[0]).get(read.<String>getArgument(0)));
                when(result.getLong(anyString())).thenAnswer(read ->
                        ((Number) selected.get(index[0]).get(read.<String>getArgument(0))).longValue());
                when(result.getBigDecimal(anyString()))
                        .thenAnswer(read -> (BigDecimal) selected.get(index[0]).get(read.<String>getArgument(0)));
                when(result.getBoolean(anyString())).thenAnswer(read ->
                        Boolean.TRUE.equals(selected.get(index[0]).get(read.<String>getArgument(0))));
                return ((ResultSetExtractor) call.getArgument(2)).extractData(result);
            });
        }

        private static ResultSet row(Map<String, Object> values) throws Exception {
            var result = mock(ResultSet.class);
            when(result.getObject(anyString(), any(Class.class)))
                    .thenAnswer(read -> values.get(read.<String>getArgument(0)));
            when(result.getString(anyString())).thenAnswer(read -> (String) values.get(read.<String>getArgument(0)));
            when(result.getBoolean(anyString()))
                    .thenAnswer(read -> Boolean.TRUE.equals(values.get(read.<String>getArgument(0))));
            return result;
        }
    }
}

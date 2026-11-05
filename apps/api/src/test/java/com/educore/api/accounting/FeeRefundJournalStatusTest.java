package com.educore.api.accounting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Minimal read-boundary units; actual database tenant/provider/financial tests remain separate. */
class FeeRefundJournalStatusTest {
    @AfterEach void clearMarkers() { TransactionSynchronizationManager.clear(); }

    @Test void narrowStatusReadIsReadOnlyAndDoesNotGrantTheOrdinaryAccountingReader() throws Exception {
        var method = AccountingService.class.getMethod("feeRefundJournalStatuses", AuthenticatedUser.class, UUID.class, List.class);
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
        var f = new Fixture(); f.install();
        try (var ignored = f.context()) { assertThat(f.read(List.of(f.journal))).containsEntry(f.journal, "SUBMITTED"); }
        assertThat(f.authoritySql).contains("capability.capability_code='fees.read'", "clock_timestamp()", "actor.status='ACTIVE'",
                "organisation.status='ACTIVE'", "school.status='ACTIVE'", "current_app_user_id()=:userId",
                "membership.id IN(:membershipIds)", "membership.school_id=:schoolId", "membership.organisation_id=:organisationId");
        assertThat(f.authoritySql).doesNotContain("accounting.read", "accounting.prepare", "accounting.post");
        verifyNoInteractions(f.audit);
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test void missingWrongHumanForeignOrganisationAndAbsentTransactionDenyBeforePersistence() {
        var f = new Fixture();
        assertThatThrownBy(() -> f.read(List.of(f.journal))).isInstanceOf(ApiException.class);
        try (var ignored = TenantRequestContext.open(f.actor, f.org)) {
            assertThatThrownBy(() -> f.read(List.of(f.journal))).isInstanceOf(ApiException.class);
        }
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), f.org)) {
            assertThatThrownBy(() -> f.read(List.of(f.journal))).isInstanceOf(ApiException.class);
        }
        try (var ignored = TenantRequestContext.open(f.actor, UUID.randomUUID())) {
            assertThatThrownBy(() -> f.read(List.of(f.journal))).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(f.jdbc, f.audit);
    }

    @Test void parentStaleGrantOrMismatchedDatabaseActorCannotBorrowCachedClaims() throws Exception {
        for (Set<String> claimed : List.of(Set.of("portal.guardian"), Set.of("fees.read", "accounting.read"))) {
            var f = new Fixture(); f.install(); f.allowed = false;
            f.user = new AuthenticatedUser(f.actor, UUID.randomUUID(), "synthetic", null, "Synthetic reader", true,
                    f.org, Set.of(f.membership), claimed, List.of());
            try (var ignored = f.context()) {
                assertThatThrownBy(() -> f.read(List.of(f.journal))).isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo("FEE_REFUND_JOURNAL_STATUS_DENIED"));
            }
            assertThat(f.readSql).isNull();
        }
    }

    @Test void onlyExactRefundSourceStatusesAreSelectedWithSameStatementLiveAuthority() throws Exception {
        var f = new Fixture(); f.install();
        try (var ignored = f.context()) { f.read(List.of(f.journal)); }
        assertThat(f.readSql).contains("SELECT journal.id,journal.status", "batch.id=journal.source_id",
                "batch.accounting_journal_id=journal.id", "batch.organisation_id=journal.organisation_id",
                "batch.school_id=journal.school_id", "batch.event_type='REFUND'", "batch.refund_posting_id IS NOT NULL",
                "journal.source_module='FEES'", "journal.reversal_of_journal_id IS NULL", "journal.id IN(:journalIds)",
                "journal.organisation_id=:organisationId", "journal.school_id=:schoolId", "capability.capability_code='fees.read'", "clock_timestamp()");
        assertThat(f.readSql).doesNotContain("SELECT journal.*", "total_amount", "debit_amount", "credit_amount", "accounting_journal_lines", "merchant_account_reference");
        assertThat(f.readParameters).containsEntry("organisationId", f.org).containsEntry("schoolId", f.school)
                .containsEntry("userId", f.actor).containsEntry("membershipIds", Set.of(f.membership));
    }

    @Test void missingForeignNonRefundAndUnrequestedIdsAreNotInventedOrReturnedAsStatuses() throws Exception {
        var f = new Fixture(); f.install(); UUID absent = UUID.randomUUID(), foreign = UUID.randomUUID(), ordinary = UUID.randomUUID();
        try (var ignored = f.context()) {
            var partial = f.read(List.of(f.journal, absent, foreign, ordinary));
            assertThat(partial).containsExactly(Map.entry(f.journal, "SUBMITTED"));
            assertThat(f.read(List.of(absent, foreign, ordinary))).isEmpty();
            assertThatThrownBy(() -> partial.put(absent, "POSTED")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test void boundedRawListRejectsNullsAndOver200BeforeExpandingAQuery() throws Exception {
        var f = new Fixture(); f.install();
        try (var ignored = f.context()) {
            assertThat(f.read(Collections.nCopies(200, f.journal))).containsEntry(f.journal, "SUBMITTED");
            for (List<UUID> invalid : Arrays.asList(null, Arrays.asList(f.journal, null), Collections.nCopies(201, f.journal))) {
                assertThatThrownBy(() -> f.read(invalid)).isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo("FEE_REFUND_JOURNAL_STATUS_INVALID"));
            }
        }
        assertThat(f.readParameters.get("journalIds")).isEqualTo(Set.of(f.journal));
    }

    @Test void emptyRequestStillRequiresLiveScopeButDoesNotReadAnyJournals() throws Exception {
        var f = new Fixture(); f.install();
        try (var ignored = f.context()) {
            assertThat(f.read(List.of())).isEmpty();
            f.allowed = false;
            assertThatThrownBy(() -> f.read(List.of())).isInstanceOf(ApiException.class);
        }
        assertThat(f.readSql).isNull();
    }

    private static final class Fixture {
        final UUID org=UUID.randomUUID(), school=UUID.randomUUID(), actor=UUID.randomUUID(), membership=UUID.randomUUID(), journal=UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
        final SecurityAuditService audit=mock(SecurityAuditService.class);
        final AccountingService service=new AccountingService(jdbc,audit,Clock.systemUTC());
        AuthenticatedUser user=new AuthenticatedUser(actor,UUID.randomUUID(),"synthetic",null,"Synthetic fees reader",true,org,Set.of(membership),Set.of(),List.of());
        boolean allowed=true;
        String authoritySql,readSql;
        Map<String,Object> readParameters;
        TenantRequestContext.Scope context(){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);return TenantRequestContext.open(actor,org);}
        Map<UUID,String> read(List<UUID> ids){return service.feeRefundJournalStatuses(user,school,ids);}

        @SuppressWarnings({"unchecked","rawtypes"})
        void install() throws Exception {
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->{authoritySql=call.getArgument(0);return allowed;});
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(ResultSetExtractor.class))).thenAnswer(call->{
                readSql=call.getArgument(0);readParameters=new LinkedHashMap<>(call.<MapSqlParameterSource>getArgument(1).getValues());
                Set<UUID> ids=(Set<UUID>)readParameters.get("journalIds");
                ResultSet result=mock(ResultSet.class);int[] index={-1};
                when(result.next()).thenAnswer(next->++index[0]==0 && ids.contains(journal));
                when(result.getObject("id",UUID.class)).thenReturn(journal);when(result.getString("status")).thenReturn("SUBMITTED");
                return ((ResultSetExtractor)call.getArgument(2)).extractData(result);
            });
        }
    }
}

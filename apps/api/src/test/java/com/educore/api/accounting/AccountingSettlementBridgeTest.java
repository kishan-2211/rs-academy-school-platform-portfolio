package com.educore.api.accounting;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Focused Java boundary tests; real journal/source coupling and independence also require PostgreSQL. */
class AccountingSettlementBridgeTest {
    private static final LocalDate DATE=LocalDate.of(2026,10,27);
    private static final Instant NOW=Instant.parse("2026-10-27T06:00:00Z");

    @AfterEach void clearTransactionMarkers() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test void preparationAndAdmissionJoinTheCallerTransactionWhileChoicesAndStatusesAreReadOnly() throws Exception {
        for (String method:List.of("validateSettlementPosting","prepareSettlementJournal")) {
            var found=java.util.Arrays.stream(AccountingService.class.getDeclaredMethods()).filter(value->value.getName().equals(method)).findFirst().orElseThrow();
            assertThat(found.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
            assertThat(found.getAnnotation(Transactional.class).readOnly()).isFalse();
        }
        for (String method:List.of("settlementAccountingOptions","settlementJournalStatuses")) {
            var found=java.util.Arrays.stream(AccountingService.class.getDeclaredMethods()).filter(value->value.getName().equals(method)).findFirst().orElseThrow();
            assertThat(found.getAnnotation(Transactional.class).readOnly()).isTrue();
        }
    }

    @Test void admissionRequiresTheActualHumanTenantAndWritableTransactionBeforeReadingMoney() throws Exception {
        for (String defect:List.of("NO_CONTEXT","NO_TRANSACTION","READ_ONLY","OTHER_USER","OTHER_ORGANISATION")) {
            var f=new Fixture();f.install();
            if ("NO_CONTEXT".equals(defect)) {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                forbidden(f::admit);
                forbidden(()->f.service.prepareSettlementJournal(f.user,f.command()));
            } else {
                UUID user="OTHER_USER".equals(defect)?UUID.randomUUID():f.userId;
                UUID org="OTHER_ORGANISATION".equals(defect)?UUID.randomUUID():f.org;
                try (var ignored=TenantRequestContext.open(user,org)) {
                    TransactionSynchronizationManager.setActualTransactionActive(!"NO_TRANSACTION".equals(defect));
                    TransactionSynchronizationManager.setCurrentTransactionReadOnly("READ_ONLY".equals(defect));
                    forbidden(f::admit);
                    forbidden(()->f.service.prepareSettlementJournal(f.user,f.command()));
                }
            }
            assertThat(f.reads).isEmpty();f.assertNoWrites();
        }
    }

    @Test void admissionRechecksIsolationAndLiveAuthorityAfterTheAccountingFence() throws Exception {
        for (String defect:List.of("REPEATABLE_READ","REVOKED_AFTER_WAIT","WRONG_DATABASE_ACTOR")) {
            var f=new Fixture();f.isolation="REPEATABLE_READ".equals(defect)?"repeatable read":"read committed";
            f.revokeAfterFence="REVOKED_AFTER_WAIT".equals(defect);
            f.liveAllowed=!"WRONG_DATABASE_ACTOR".equals(defect);f.install();
            try(var ignored=f.human(false)) {
                if ("REPEATABLE_READ".equals(defect)) conflict(f::admit);else forbidden(f::admit);
            }
            assertThat(f.reads).anySatisfy(read->assertThat(read.sql()).contains("pg_advisory_xact_lock"));
            assertThat(f.reads).noneMatch(read->read.sql().contains("settlement-accounting-source-admission"));
            f.assertNoWrites();
        }
    }

    @Test void admissionAcceptsCompatiblePostedSourcesInOneScopedBatchIncludingSharedJournalReferences() throws Exception {
        for (String basis:List.of("CASH","ACCRUAL")) {
            var f=new Fixture();f.book.put("accounting_basis",basis);f.source.put("accounting_basis",basis);f.install();
            try(var ignored=f.human(false)) {
                var result=f.service.validateSettlementPosting(f.user,f.school,f.year,DATE,f.uses(),List.of(f.sourceJournal,f.sourceJournal));
                assertThat(result.financialYearId()).isEqualTo(f.year);assertThat(result.accountingPeriodId()).isEqualTo(f.period);
                assertThat(result.accountingBasis()).isEqualTo(basis);assertThat(result.currencyCode()).isEqualTo("INR");
            }
            assertThat(f.reads.stream().filter(read->read.sql().contains("settlement-accounting-source-admission")).toList()).singleElement().satisfies(read->{
                assertThat(read.sql()).contains("source_module='FEES'","FOR SHARE OF original");
                assertThat(read.parameters()).containsEntry("organisationId",f.org).containsEntry("schoolId",f.school)
                        .containsEntry("journalIds",Set.of(f.sourceJournal));
            });
            f.assertNoWrites();
        }
    }

    @Test void admissionRefusesPendingActualReversedUnpostedForeignAndIncompatibleJournalEvidence() throws Exception {
        for (String defect:List.of("PENDING","COUNTER","REVERSAL_SOURCE","SUBMITTED","REVERSED","BASIS","CURRENCY","MISSING")) {
            var f=new Fixture();
            switch(defect) {
                case "PENDING" -> f.source.put("has_pending",true);
                case "COUNTER" -> f.source.put("has_reversal",true);
                case "REVERSAL_SOURCE" -> f.source.put("reversal_of_journal_id",UUID.randomUUID());
                case "SUBMITTED","REVERSED" -> f.source.put("status",defect);
                case "BASIS" -> f.source.put("accounting_basis","CASH");
                case "CURRENCY" -> f.source.put("currency_code","USD");
                case "MISSING" -> f.sourceVisible=false;
                default -> throw new AssertionError(defect);
            }
            f.install();try(var ignored=f.human(false)){conflict(f::admit);}f.assertNoWrites();
        }
    }

    @Test void admissionRefusesClosedTargetsAndOutOfYearDatesWithoutChangingHistory() throws Exception {
        for (String defect:List.of("YEAR_CLOSED","PERIOD_CLOSED","NO_PERIOD","DATE")) {
            var f=new Fixture();
            switch(defect) {
                case "YEAR_CLOSED" -> f.book.put("financial_year_status","CLOSED");
                case "PERIOD_CLOSED" -> f.book.put("accounting_period_status","CLOSED");
                case "NO_PERIOD" -> f.book.put("accounting_period_id",null);
                case "DATE" -> f.book.put("year_starts_on",DATE.plusDays(1));
                default -> throw new AssertionError(defect);
            }
            f.install();try(var ignored=f.human(false)){assertThatThrownBy(f::admit).isInstanceOf(ApiException.class);}f.assertNoWrites();
        }
    }

    @Test void accountingBoundaryRequiresExactlyOneBankAndAtLeastOneClearingRole() throws Exception {
        for (String defect:List.of("NO_BANK","NO_CLEARING","TWO_BANKS")) {
            var f=new Fixture();var uses=new ArrayList<>(f.uses());
            if ("NO_BANK".equals(defect)) uses.removeIf(use->use.useType()==SettlementAccountUseType.BANK);
            if ("NO_CLEARING".equals(defect)) uses.removeIf(use->use.useType()==SettlementAccountUseType.CLEARING);
            if ("TWO_BANKS".equals(defect)) {
                UUID other=UUID.randomUUID();f.accounts.add(f.account(other,"ASSET","BANK"));uses.add(new SettlementAccountUse(other,SettlementAccountUseType.BANK));
            }
            f.install();try(var ignored=f.human(false)) {
                conflict(()->f.service.validateSettlementPosting(f.user,f.school,f.year,DATE,uses,List.of(f.sourceJournal)));
            }
            f.assertNoWrites();
        }
    }

    @Test void admissionRejectsForeignInactiveNonPostingAndMisclassifiedAccountsAndClearingReuse() throws Exception {
        for (String defect:List.of("FOREIGN","INACTIVE","NOT_DIRECT","BANK_AS_CASH","CLEARING_AS_RECEIVABLE","EXPENSE_AS_FUND","COMPONENT_REUSES_CLEARING")) {
            var f=new Fixture();var uses=new ArrayList<>(f.uses());
            switch(defect) {
                case "FOREIGN" -> f.accounts.removeIf(account->f.bank.equals(account.get("id")));
                case "INACTIVE" -> f.accounts.getFirst().put("status","INACTIVE");
                case "NOT_DIRECT" -> f.accounts.getFirst().put("allow_direct_posting",false);
                case "BANK_AS_CASH" -> f.accounts.getFirst().put("category","CASH");
                case "CLEARING_AS_RECEIVABLE" -> f.accounts.get(1).put("category","RECEIVABLE");
                case "EXPENSE_AS_FUND" -> {f.accounts.get(2).put("account_type","EQUITY");f.accounts.get(2).put("category","FUND");}
                case "COMPONENT_REUSES_CLEARING" -> uses.add(new SettlementAccountUse(f.clearing,SettlementAccountUseType.TAX_ASSET));
                default -> throw new AssertionError(defect);
            }
            f.install();try(var ignored=f.human(false)) {
                conflict(()->f.service.validateSettlementPosting(f.user,f.school,f.year,DATE,uses,List.of(f.sourceJournal)));
            }
            f.assertNoWrites();
        }
    }

    @Test void cachedPermissionsCannotReadOrPrepareAnotherSchool() throws Exception {
        var f=new Fixture();f.install();try(var ignored=f.human(false)) {
            forbidden(()->f.service.validateSettlementPosting(f.user,UUID.randomUUID(),f.year,DATE,f.uses(),List.of(f.sourceJournal)));
            forbidden(()->f.service.prepareSettlementJournal(f.user,new SettlementJournalRequest(UUID.randomUUID(),f.year,f.period,DATE,
                    "Synthetic statement","Synthetic settlement",f.preparation,"synthetic:settlement:retry","Synthetic reviewed terms",f.command().lines())));
        }
        f.assertNoWrites();
    }

    @Test void exactCanonicalReplaySurvivesClosedBookAndInactiveAccountsWithoutWrites() throws Exception {
        var f=new Fixture();var command=f.command();f.replay=f.header("GATEWAY_SETTLEMENT","POSTED",4,fingerprint(command));
        f.book.clear();f.accounts.clear();f.install();
        try(var ignored=f.human(false)) {
            var response=f.service.prepareSettlementJournal(f.user,command);
            assertThat(response.id()).isEqualTo(f.journal);assertThat(response.rowVersion()).isEqualTo(4);
            assertThat(response.voucherNumber()).isEqualTo("SYN-JV-000001");
        }
        assertThat(f.reads).noneMatch(read->read.sql().contains("year.starts_on AS year_starts_on")||read.sql().contains("id IN (:accountIds)"));
        f.assertNoWrites();
    }

    @Test void replayCannotChangeMoneySourceIdentityDateOrMemoOrBorrowAnotherModulesJournalKey() throws Exception {
        for (String defect:List.of("AMOUNT","PREPARATION","DATE","MEMO","MODULE","SOURCE")) {
            var f=new Fixture();var original=f.command();f.replay=f.header("GATEWAY_SETTLEMENT","SUBMITTED",1,fingerprint(original));
            var lines=original.lines();UUID preparation=f.preparation;LocalDate date=DATE;
            if ("AMOUNT".equals(defect)) lines=List.of(new SettlementJournalLine(f.bank,new BigDecimal("199.00"),BigDecimal.ZERO,"Synthetic bank"),
                    new SettlementJournalLine(f.clearing,BigDecimal.ZERO,new BigDecimal("199.00"),"Synthetic clearing"));
            if ("PREPARATION".equals(defect)) preparation=UUID.randomUUID();
            if ("DATE".equals(defect)) date=DATE.minusDays(1);
            if ("MEMO".equals(defect)) lines=List.of(new SettlementJournalLine(f.bank,new BigDecimal("200.00"),BigDecimal.ZERO,"Different synthetic memo"),original.lines().getLast());
            if ("MODULE".equals(defect)) f.replay.put("source_module","MANUAL");
            if ("SOURCE".equals(defect)) f.replay.put("source_id",UUID.randomUUID());
            var changed=new SettlementJournalRequest(f.school,f.year,f.period,date,original.reference(),original.narration(),preparation,
                    original.idempotencyKey(),original.submissionReason(),lines);
            f.install();try(var ignored=f.human(false)) {
                assertThatThrownBy(()->f.service.prepareSettlementJournal(f.user,changed)).isInstanceOfSatisfying(ApiException.class,
                        error->assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
            }
            f.assertNoWrites();
        }
    }

    @Test void settlementDecisionAssertsDatabaseIndependenceBeforeAnyPostingOrRejectionWrite() throws Exception {
        for (String decision:List.of("POST","REJECT")) {
            var f=new Fixture();f.locked=f.header("GATEWAY_SETTLEMENT","SUBMITTED",1,"synthetic-fingerprint");
            f.rejectDecision=true;f.install();try(var ignored=f.human(false)) {
                assertThatThrownBy(()->f.service.decideJournal(f.user,f.journal,new AccountingController.DecisionRequest(f.school,decision,"Synthetic independent decision",1)))
                        .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo("GATEWAY_SETTLEMENT_DECISION_CONFLICT"));
            }
            assertThat(f.reads.stream().filter(read->read.sql().contains("assert_gateway_settlement_journal_decision")).toList()).singleElement().satisfies(read->{
                assertThat(read.parameters()).containsEntry("journalId",f.journal).containsEntry("membershipId",f.membership).containsEntry("decision",decision);
            });
            assertThat(f.reads).noneMatch(read->read.sql().contains("accounting_journal_sequences"));f.assertNoWrites();
        }
    }

    @Test void ordinaryJournalDecisionDoesNotReadNewSettlementContracts() throws Exception {
        var f=new Fixture();f.locked=f.header("MANUAL","SUBMITTED",1,"synthetic-fingerprint");f.install();
        try(var ignored=f.human(false)) {
            var response=f.service.decideJournal(f.user,f.journal,new AccountingController.DecisionRequest(f.school,"REJECT","Synthetic ordinary decision",1));
            assertThat(response.rowVersion()).isEqualTo(2);
        }
        assertThat(f.reads).noneMatch(read->read.sql().contains("assert_gateway_settlement_journal_decision")||read.sql().contains("fee_gateway_settlement_"));
        assertThat(f.writes).hasSize(2);
    }

    @Test void statusProjectionIsScopedBoundedImmutableAndCannotHideDuplicateJournals() throws Exception {
        var f=new Fixture();var status=f.header("GATEWAY_SETTLEMENT","POSTED",2,"synthetic-fingerprint");
        status.put("source_id",f.preparation);f.statuses.add(status);f.install();
        try(var ignored=f.human(true)) {
            var result=f.service.settlementJournalStatuses(f.user,f.school,List.of(f.preparation));
            assertThat(result.get(f.preparation).journalId()).isEqualTo(f.journal);assertThat(result.get(f.preparation).status()).isEqualTo("POSTED");
            assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThat(result.get(f.preparation).toString()).doesNotContain(f.journal.toString(),"SYN-JV");
            f.statuses.add(new LinkedHashMap<>(status));
            conflict(()->f.service.settlementJournalStatuses(f.user,f.school,List.of(f.preparation)));
        }
        assertThat(f.reads.stream().filter(read->read.sql().contains("source_id IN(:preparationIds)")).toList()).allSatisfy(read->{
            assertThat(read.sql()).contains("source_module='GATEWAY_SETTLEMENT'");
            assertThat(read.parameters()).containsEntry("organisationId",f.org).containsEntry("schoolId",f.school)
                    .containsEntry("preparationIds",Set.of(f.preparation));
        });f.assertNoWrites();
    }

    @Test void sourceAndStatusRequestsRefuseUnboundedIdentifiersAndInvalidUseTerms() throws Exception {
        var f=new Fixture();f.install();try(var ignored=f.human(false)) {
            var excessive=java.util.stream.IntStream.range(0,1001).mapToObj(index->UUID.randomUUID()).toList();
            conflict(()->f.service.settlementJournalStatuses(f.user,f.school,excessive));
            conflict(()->f.service.validateSettlementPosting(f.user,f.school,f.year,DATE,f.uses(),excessive));
            conflict(()->f.service.validateSettlementPosting(f.user,f.school,f.year,DATE,List.of(),List.of(f.sourceJournal)));
            conflict(()->f.service.validateSettlementPosting(f.user,f.school,f.year,DATE,
                    List.of(new SettlementAccountUse(f.bank,null)),List.of(f.sourceJournal)));
        }
        f.assertNoWrites();
    }

    private static String fingerprint(SettlementJournalRequest command) throws Exception {
        var canonical=new StringBuilder().append(command.schoolId()).append('|').append(command.financialYearId()).append('|')
                .append(command.accountingPeriodId()).append("|JOURNAL|").append(command.postingDate()).append('|')
                .append(command.reference().trim()).append('|').append(command.narration().trim());
        for(var line:command.lines()) canonical.append('|').append(line.accountId()).append(":null:null:")
                .append(line.debitAmount().setScale(2).toPlainString()).append(':').append(line.creditAmount().setScale(2).toPlainString())
                .append(':').append(line.memo().trim());
        return sha(sha(canonical.toString())+"|GATEWAY_SETTLEMENT|"+command.preparationId());
    }
    private static String sha(String value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static void forbidden(Runnable action) {assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN));}
    private static void conflict(Runnable action) {assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));}
    private record Read(String sql,Map<String,Object> parameters) {}

    private static final class Fixture {
        final UUID org=UUID.randomUUID(),school=UUID.randomUUID(),userId=UUID.randomUUID(),membership=UUID.randomUUID();
        final UUID year=UUID.randomUUID(),period=UUID.randomUUID(),bank=UUID.randomUUID(),clearing=UUID.randomUUID(),expense=UUID.randomUUID();
        final UUID sourceJournal=UUID.randomUUID(),journal=UUID.randomUUID(),preparation=UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
        final SecurityAuditService audit=mock(SecurityAuditService.class);
        final AccountingService service=new AccountingService(jdbc,audit,Clock.fixed(NOW,ZoneOffset.UTC));
        final AuthenticatedUser user=new AuthenticatedUser(userId,UUID.randomUUID(),"synthetic@example.test",null,"Synthetic accountant",true,org,
                Set.of(membership),Set.of("accounting.read","accounting.prepare","accounting.post"),List.of());
        final Map<String,Object> scope=new HashMap<>(),book=new HashMap<>(),source=new HashMap<>();
        final List<Map<String,Object>> accounts=new ArrayList<>(),statuses=new ArrayList<>();
        final List<Read> reads=new ArrayList<>(),writes=new ArrayList<>();
        Map<String,Object> replay,locked;
        String isolation="read committed";boolean liveAllowed=true,revokeAfterFence,sourceVisible=true,rejectDecision;

        Fixture() {
            scope.put("school_id",school);scope.put("display_name","Synthetic school");scope.put("membership_id",membership);
            for(String capability:List.of("can_configure","can_prepare","can_post","can_close","can_request","can_approve"))scope.put(capability,true);
            book.put("financial_year_id",year);book.put("accounting_period_id",period);book.put("year_starts_on",DATE.withDayOfYear(1));
            book.put("year_ends_on",DATE.withMonth(12).withDayOfMonth(31));book.put("accounting_basis","ACCRUAL");book.put("currency_code","INR");
            book.put("financial_year_status","OPEN");book.put("accounting_period_status","OPEN");
            source.put("id",sourceJournal);source.put("status","POSTED");source.put("accounting_basis","ACCRUAL");source.put("currency_code","INR");
            source.put("has_reversal",false);source.put("has_pending",false);
            accounts.add(account(bank,"ASSET","BANK"));accounts.add(account(clearing,"ASSET","OTHER"));accounts.add(account(expense,"EXPENSE","EXPENSE"));
        }
        List<SettlementAccountUse> uses(){return List.of(new SettlementAccountUse(bank,SettlementAccountUseType.BANK),
                new SettlementAccountUse(clearing,SettlementAccountUseType.CLEARING),new SettlementAccountUse(expense,SettlementAccountUseType.EXPENSE));}
        FeeBookContext admit(){return service.validateSettlementPosting(user,school,year,DATE,uses(),List.of(sourceJournal));}
        SettlementJournalRequest command(){return new SettlementJournalRequest(school,year,period,DATE,"Synthetic statement","Synthetic settlement",preparation,
                "synthetic:settlement:retry","Synthetic reviewed terms",List.of(new SettlementJournalLine(bank,new BigDecimal("200.00"),BigDecimal.ZERO,"Synthetic bank"),
                new SettlementJournalLine(clearing,BigDecimal.ZERO,new BigDecimal("200.00"),"Synthetic clearing")));}
        Map<String,Object> account(UUID id,String type,String category){var row=new HashMap<String,Object>();row.put("id",id);row.put("code","SYN");row.put("display_name","Synthetic account");
            row.put("account_type",type);row.put("category",category);row.put("status","ACTIVE");row.put("allow_direct_posting",true);row.put("currency_code","INR");return row;}
        Map<String,Object> header(String module,String status,long version,String fingerprint){var row=new HashMap<String,Object>();row.put("id",journal);
            row.put("financial_year_id",year);row.put("accounting_period_id",period);row.put("voucher_type","JOURNAL");row.put("voucher_number","SYN-JV-000001");
            row.put("status",status);row.put("created_by_membership_id",UUID.randomUUID());row.put("submitted_by_membership_id",UUID.randomUUID());
            row.put("request_fingerprint",fingerprint);row.put("row_version",version);row.put("source_module",module);row.put("source_id",preparation);return row;}
        TenantRequestContext.Scope human(boolean readOnly){TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);return TenantRequestContext.open(userId,org);}
        void assertNoWrites(){assertThat(writes).isEmpty();verify(jdbc,never()).update(anyString(),any(MapSqlParameterSource.class));verifyNoInteractions(audit);}

        @SuppressWarnings({"unchecked","rawtypes"})
        void install() throws Exception {
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->{
                String sql=call.getArgument(0);var parameters=call.<MapSqlParameterSource>getArgument(1);read(sql,parameters);
                return sql.contains("SELECT EXISTS(SELECT 1 FROM fee_accounting_posting_batches")?false:liveAllowed;
            });
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(String.class))).thenAnswer(call->{read(call.getArgument(0),call.getArgument(1));return isolation;});
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Integer.class))).thenAnswer(call->{
                String sql=call.getArgument(0);read(sql,call.getArgument(1));
                if(sql.contains("pg_advisory_xact_lock")&&revokeAfterFence)liveAllowed=false;
                if(sql.contains("assert_gateway_settlement_journal_decision")&&rejectDecision)throw new DataIntegrityViolationException("Synthetic independent reviewer denied");
                return 1;
            });
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(RowMapper.class))).thenAnswer(call->{
                String sql=call.getArgument(0);var parameters=call.<MapSqlParameterSource>getArgument(1);read(sql,parameters);
                List<Map<String,Object>> rows;
                if(sql.contains("SELECT school.id AS school_id"))rows=school.equals(parameters.getValue("schoolId"))?List.of(scope):List.of();
                else if(sql.contains("year.starts_on AS year_starts_on"))rows=book.isEmpty()?List.of():List.of(book);
                else if(sql.contains("settlement-accounting-source-admission"))rows=sourceVisible?List.of(source):List.of();
                else if(sql.contains("idempotency_key=:key"))rows=replay==null?List.of():List.of(replay);
                else if(sql.contains("FROM accounting_journals")&&sql.contains("FOR UPDATE"))rows=locked==null?List.of():List.of(locked);
                else if(sql.contains("settlement-bank-account-options"))rows=List.of(accounts.isEmpty()?account(bank,"ASSET","BANK"):accounts.getFirst());
                else throw new AssertionError("Unexpected scoped mapper query: "+sql);
                RowMapper mapper=call.getArgument(2);var mapped=new ArrayList<>();for(int index=0;index<rows.size();index++)mapped.add(mapper.mapRow(row(rows.get(index)),index));return mapped;
            });
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(ResultSetExtractor.class))).thenAnswer(call->{
                String sql=call.getArgument(0);read(sql,call.getArgument(1));if(!sql.contains("id IN (:accountIds)"))throw new AssertionError("Unexpected extractor query");
                return call.<ResultSetExtractor>getArgument(2).extractData(cursor(accounts));
            });
            doAnswer(call->{String sql=call.getArgument(0);read(sql,call.getArgument(1));var handler=call.<RowCallbackHandler>getArgument(2);
                for(var value:statuses)handler.processRow(row(value));return null;
            }).when(jdbc).query(anyString(),any(MapSqlParameterSource.class),any(RowCallbackHandler.class));
            when(jdbc.update(anyString(),any(MapSqlParameterSource.class))).thenAnswer(call->{writes.add(new Read(call.getArgument(0),new LinkedHashMap<>(call.<MapSqlParameterSource>getArgument(1).getValues())));return 1;});
        }
        void read(String sql,MapSqlParameterSource parameters){reads.add(new Read(sql,new LinkedHashMap<>(parameters.getValues())));}
        static ResultSet row(Map<String,Object> values)throws Exception{return rowAt(List.of(values),new int[]{0});}
        static ResultSet cursor(List<Map<String,Object>> values)throws Exception{return rowAt(values,new int[]{-1});}
        static ResultSet rowAt(List<Map<String,Object>> values,int[] index)throws Exception{
            ResultSet row=mock(ResultSet.class);when(row.next()).thenAnswer(call->++index[0]<values.size());
            when(row.getObject(anyString(),any(Class.class))).thenAnswer(call->values.get(index[0]).get(call.<String>getArgument(0)));
            when(row.getObject(anyString())).thenAnswer(call->values.get(index[0]).get(call.<String>getArgument(0)));
            when(row.getString(anyString())).thenAnswer(call->{Object value=values.get(index[0]).get(call.<String>getArgument(0));return value==null?null:value.toString();});
            when(row.getBoolean(anyString())).thenAnswer(call->Boolean.TRUE.equals(values.get(index[0]).get(call.<String>getArgument(0))));
            when(row.getLong(anyString())).thenAnswer(call->{Object value=values.get(index[0]).get(call.<String>getArgument(0));return value==null?0L:((Number)value).longValue();});
            when(row.getBigDecimal(anyString())).thenAnswer(call->(BigDecimal)values.get(index[0]).get(call.<String>getArgument(0)));
            when(row.getTimestamp(anyString())).thenAnswer(call->{Object value=values.get(index[0]).get(call.<String>getArgument(0));return value==null?null:Timestamp.from((Instant)value);});
            return row;
        }
    }
}

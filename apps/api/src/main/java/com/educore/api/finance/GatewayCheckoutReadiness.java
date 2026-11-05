package com.educore.api.finance;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.accounting.AccountingModuleBridge.FeeAccountUse;
import com.educore.api.accounting.AccountingModuleBridge.FeeAccountUseType;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal non-posting readiness; existing human configuration approval is never automated. */
@Service
public class GatewayCheckoutReadiness {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountingModuleBridge accounting;

    public GatewayCheckoutReadiness(NamedParameterJdbcTemplate jdbc, AccountingModuleBridge accounting) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.accounting = Objects.requireNonNull(accounting);
    }

    public record ReadyBook(UUID configurationId, UUID financialYearId, UUID accountingPeriodId) {
        @Override public String toString() { return "ReadyBook[internal approved gateway accounting readiness]"; }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReadyBook validate(UUID organisationId, UUID schoolId, UUID attemptId,
            UUID feeAccountId, LocalDate businessDate) {
        var context = TenantRequestContext.current().orElseThrow(GatewayCheckoutReadiness::unavailable);
        if (organisationId == null || schoolId == null || attemptId == null || feeAccountId == null || businessDate == null
                || context.userId() != null || !organisationId.equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
        var p = new MapSqlParameterSource().addValue("organisationId", organisationId).addValue("schoolId", schoolId)
                .addValue("attemptId", attemptId).addValue("accountId", feeAccountId).addValue("businessDate", businessDate);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL
                """, p, Boolean.class))) throw unavailable();
        // Different existing fences protect receipts and mapping approvals. The
        // CREATE caller takes schoolId+:finance FIRST, then this mapping fence,
        // then AccountingModuleBridge takes its existing accounting school lock.
        jdbc.queryForObject("""
                SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))) locked
                """, p.addValue("lockKey", "finance:school:" + schoolId), Integer.class);
        var book = accounting.validateGatewayCheckoutBook(organisationId, schoolId, businessDate, null, List.of());
        p.addValue("financialYearId", book.financialYearId());
        var configurations = jdbc.query("""
                SELECT id,accounting_basis_snapshot,currency_code,receivable_account_id,concession_account_id
                FROM fee_accounting_configurations WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND financial_year_id=:financialYearId AND status IN ('APPROVED','SUPERSEDED')
                  AND effective_from<=:businessDate
                ORDER BY effective_from DESC,revision_number DESC LIMIT 1 FOR SHARE
                """, p, (r, n) -> new Configuration(r.getObject("id", UUID.class), r.getString("accounting_basis_snapshot"),
                r.getString("currency_code"), r.getObject("receivable_account_id", UUID.class),
                r.getObject("concession_account_id", UUID.class)));
        if (configurations.size() != 1) throw unavailable();
        var configuration = configurations.getFirst();
        if (!book.accountingBasis().equals(configuration.basis()) || !book.currencyCode().equals(configuration.currency())) throw unavailable();
        p.addValue("configurationId", configuration.id());
        var incomeAccounts = jdbc.query("""
                SELECT DISTINCT item.fee_head_id,mapping.income_account_id
                FROM fee_gateway_allocations allocation JOIN learner_fee_charges charge
                  ON charge.id=allocation.learner_fee_charge_id AND charge.learner_fee_account_id=allocation.learner_fee_account_id
                  AND charge.organisation_id=allocation.organisation_id AND charge.school_id=allocation.school_id
                JOIN fee_plan_items item ON item.id=charge.fee_plan_item_id
                  AND item.organisation_id=charge.organisation_id AND item.school_id=charge.school_id
                LEFT JOIN fee_accounting_head_mappings mapping ON mapping.fee_head_id=item.fee_head_id
                  AND mapping.configuration_id=:configurationId AND mapping.organisation_id=allocation.organisation_id
                  AND mapping.school_id=allocation.school_id
                WHERE allocation.attempt_id=:attemptId AND allocation.learner_fee_account_id=:accountId
                  AND allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId AND allocation.status='ACTIVE'
                """, p, (r, n) -> r.getObject("income_account_id", UUID.class));
        if (incomeAccounts.isEmpty() || incomeAccounts.size() > 100 || incomeAccounts.stream().anyMatch(Objects::isNull)) throw unavailable();
        var clearingAccounts = jdbc.query("""
                SELECT deposit_account_id FROM fee_accounting_tender_mappings
                WHERE configuration_id=:configurationId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND payment_method='ONLINE_PROVIDER'
                """, p, (r, n) -> r.getObject("deposit_account_id", UUID.class));
        if (clearingAccounts.size() != 1 || clearingAccounts.getFirst() == null) throw unavailable();
        var uses = new ArrayList<FeeAccountUse>();
        uses.add(new FeeAccountUse(clearingAccounts.getFirst(), FeeAccountUseType.ONLINE_CLEARING));
        for (UUID income : incomeAccounts) uses.add(new FeeAccountUse(income, FeeAccountUseType.INCOME));
        if ("ACCRUAL".equals(book.accountingBasis())) {
            uses.add(new FeeAccountUse(configuration.receivable(), FeeAccountUseType.RECEIVABLE));
            uses.add(new FeeAccountUse(configuration.concession(), FeeAccountUseType.CONCESSION));
        } else if (configuration.receivable() != null || configuration.concession() != null) throw unavailable();
        var checked = accounting.validateGatewayCheckoutBook(organisationId, schoolId, businessDate,
                book.financialYearId(), uses.stream().distinct().toList());
        if (!book.equals(checked)) throw unavailable();
        return new ReadyBook(configuration.id(), book.financialYearId(), book.accountingPeriodId());
    }

    private record Configuration(UUID id, String basis, String currency, UUID receivable, UUID concession) {}
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Gateway CREATE requires an independently approved effective fee configuration and real clearing mapping");
    }
}

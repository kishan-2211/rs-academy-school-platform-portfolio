package com.educore.api.payroll;

import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Complete the stable read, then reauthorize/audit using a fresh committed audit-chain view before delivery. */
@Service
public class PayrollReportingService {
    private final PayrollReportReader reader;
    private final PayrollAdvanceReconciliationReader advanceReconciliation;
    private final PayrollAccountingService payrollAccounting;
    private final SecurityAuditService audit;
    private final TransactionTemplate auditTransaction;

    public PayrollReportingService(PayrollReportReader reader,
            PayrollAdvanceReconciliationReader advanceReconciliation,
            PayrollAccountingService payrollAccounting, SecurityAuditService audit,
            PlatformTransactionManager transactions) {
        this.reader = reader; this.advanceReconciliation = advanceReconciliation;
        this.payrollAccounting = payrollAccounting; this.audit = audit;
        this.auditTransaction = new TransactionTemplate(transactions);
        this.auditTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public PayrollReports.Page<PayrollReports.RegisterRow> register(AuthenticatedUser user, UUID schoolId,
            PayrollReports.Query query) {
        var result = reader.register(user, schoolId, query);
        recordAccess(user, result.schoolId(), "register", false, result.items().size());
        return result;
    }
    public PayrollReports.Page<PayrollReports.ReconciliationRow> reconciliation(AuthenticatedUser user, UUID schoolId,
            PayrollReports.Query query) {
        var result = reader.reconciliation(user, schoolId, query);
        recordAccess(user, result.schoolId(), "reconciliation", false, result.items().size());
        return result;
    }
    public PayrollReports.AdvancePage<PayrollReports.AdvanceReconciliationRow> advanceReconciliation(
            AuthenticatedUser user, UUID schoolId, PayrollReports.AdvanceQuery query) {
        var result = advanceReconciliation.read(user, schoolId, query);
        recordAccess(user, result.schoolId(), "advance_reconciliation", false, result.items().size());
        return result;
    }
    public PayrollReports.Page<PayrollReports.SummaryRow> summary(AuthenticatedUser user, UUID schoolId,
            PayrollReports.Query query, PayrollReports.GroupBy groupBy) {
        var result = reader.summary(user, schoolId, query, groupBy);
        recordAccess(user, result.schoolId(), "summary." + groupBy.name().toLowerCase(java.util.Locale.ROOT),
                false, result.items().size());
        return result;
    }
    public String exportRegister(AuthenticatedUser user, UUID schoolId, PayrollReports.Query query) {
        var result = reader.exportRegister(user, schoolId, query);
        recordAccess(user, result.schoolId(), "register", true, result.rows());
        return result.csv();
    }
    public String exportReconciliation(AuthenticatedUser user, UUID schoolId, PayrollReports.Query query) {
        var result = reader.exportReconciliation(user, schoolId, query);
        recordAccess(user, result.schoolId(), "reconciliation", true, result.rows());
        return result.csv();
    }
    public String exportAdvanceReconciliation(AuthenticatedUser user, UUID schoolId,
            PayrollReports.AdvanceQuery query) {
        var result = advanceReconciliation.export(user, schoolId, query);
        recordAccess(user, result.schoolId(), "advance_reconciliation", true, result.rows());
        return result.csv();
    }
    public String exportSummary(AuthenticatedUser user, UUID schoolId, PayrollReports.Query query,
            PayrollReports.GroupBy groupBy) {
        var result = reader.exportSummary(user, schoolId, query, groupBy);
        recordAccess(user, result.schoolId(), "summary." + groupBy.name().toLowerCase(java.util.Locale.ROOT),
                true, result.rows());
        return result.csv();
    }
    private void recordAccess(AuthenticatedUser user, UUID schoolId, String report, boolean export, int count) {
        auditTransaction.executeWithoutResult(transaction -> {
            var scope = payrollAccounting.reportingScope(user, schoolId);
            audit.recordScoped(user, scope.actorId(), scope.schoolId(), "payroll.report." + (export ? "exported" : "read"),
                    "PAYROLL_REPORT", scope.schoolId().toString(), AuditOutcome.SUCCEEDED, null,
                    Map.of("report", report, "rowCount", count));
        });
    }
}

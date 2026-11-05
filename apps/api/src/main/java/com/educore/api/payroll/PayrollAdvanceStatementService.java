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

/** Delivers stable statements, then reauthorizes and appends protected access evidence. */
@Service
public class PayrollAdvanceStatementService {
    private final PayrollAdvanceStatementReader reader;
    private final PayrollAccountingService payrollAccounting;
    private final SecurityAuditService audit;
    private final TransactionTemplate auditTransaction;

    public PayrollAdvanceStatementService(PayrollAdvanceStatementReader reader,
            PayrollAccountingService payrollAccounting, SecurityAuditService audit,
            PlatformTransactionManager transactions) {
        this.reader = reader;
        this.payrollAccounting = payrollAccounting;
        this.audit = audit;
        this.auditTransaction = new TransactionTemplate(transactions);
        this.auditTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public PayrollAdvanceStatement read(AuthenticatedUser user, UUID schoolId, UUID advanceId) {
        PayrollAdvanceStatement result = reader.read(user, schoolId, advanceId);
        audit(user, result.schoolId(), result.advanceId(), "payroll.advance.statement.read",
                result.installments().size());
        return result;
    }

    public PayrollAdvanceStatementReader.CsvExport export(AuthenticatedUser user, UUID schoolId, UUID advanceId) {
        var result = reader.export(user, schoolId, advanceId);
        audit(user, result.schoolId(), advanceId, "payroll.advance.statement.exported", result.rows());
        return result;
    }

    private void audit(AuthenticatedUser user, UUID schoolId, UUID advanceId, String action, int rows) {
        auditTransaction.executeWithoutResult(transaction -> {
            var scope = payrollAccounting.reportingScope(user, schoolId);
            audit.recordScoped(user, scope.actorId(), scope.schoolId(), action,
                    "PAYROLL_EMPLOYEE_ADVANCE", advanceId.toString(), AuditOutcome.SUCCEEDED,
                    null, Map.of("installmentCount", rows));
        });
    }
}

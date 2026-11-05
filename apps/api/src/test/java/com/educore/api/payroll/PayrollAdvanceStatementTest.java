package com.educore.api.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.accounting.AccountingModuleBridge.PayrollJournalEvidence;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class PayrollAdvanceStatementTest {
    private final UUID recoveryId = UUID.randomUUID(), batchId = UUID.randomUUID();
    private final UUID yearId = UUID.randomUUID(), journalId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2025, 10, 31);
    private final BigDecimal amount = new BigDecimal("33.33");

    private PayrollAdvanceStatementReader.PostingRow source(String status) {
        return new PayrollAdvanceStatementReader.PostingRow(recoveryId, batchId, yearId,
                date, "INR", amount, status, journalId);
    }

    private PayrollJournalEvidence evidence(String status, String reversalStatus, boolean offsets) {
        return new PayrollJournalEvidence(journalId, batchId, yearId, date, "INR", status,
                amount, amount, reversalStatus == null ? null : UUID.randomUUID(), reversalStatus,
                reversalStatus == null ? BigDecimal.ZERO : amount,
                reversalStatus == null ? BigDecimal.ZERO : amount, offsets);
    }

    @Test void onlyMatchingActivePostedLedgerEvidenceCountsAsRecovered() {
        assertThat(PayrollAdvanceStatementReader.ledgerPosting(source("ACTIVE"),
                evidence("POSTED", null, false)).countsAsRecovered()).isTrue();
        assertThat(PayrollAdvanceStatementReader.ledgerPosting(source("RELEASED"),
                evidence("POSTED", null, false)).countsAsRecovered()).isFalse();
        assertThat(PayrollAdvanceStatementReader.ledgerPosting(source("ACTIVE"),
                evidence("SUBMITTED", null, false)).evidenceStatus()).isEqualTo("AWAITING_APPROVAL");
    }

    @Test void missingSubstitutedOrBadReversalEvidenceFailsClosed() {
        assertThat(PayrollAdvanceStatementReader.ledgerPosting(source("ACTIVE"), null).evidenceStatus())
                .contains("JOURNAL_UNAVAILABLE");
        var substituted = new PayrollJournalEvidence(journalId, UUID.randomUUID(), yearId, date,
                "USD", "POSTED", amount, amount, null, null, BigDecimal.ZERO, BigDecimal.ZERO, false);
        assertThat(PayrollAdvanceStatementReader.ledgerPosting(source("ACTIVE"), substituted).evidenceStatus())
                .contains("JOURNAL_PROVENANCE_MISMATCH");
        var wrongAmount = new PayrollJournalEvidence(journalId, batchId, yearId, date,
                "INR", "POSTED", BigDecimal.ONE, BigDecimal.ONE, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, false);
        assertThat(PayrollAdvanceStatementReader.ledgerPosting(source("ACTIVE"), wrongAmount).evidenceStatus())
                .contains("JOURNAL_AMOUNT_MISMATCH");
        assertThat(PayrollAdvanceStatementReader.ledgerPosting(source("ACTIVE"),
                evidence("REVERSED", "POSTED", false)).evidenceStatus()).contains("REVERSAL_MISMATCH");
    }

    @Test void stableReaderAndFreshAuditFacadeUseSeparateTransactionBoundaries() throws Exception {
        var read = PayrollAdvanceStatementReader.class.getMethod("read",
                com.educore.api.identity.AuthenticatedUser.class, UUID.class, UUID.class);
        Transactional transaction = read.getAnnotation(Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        var facade = PayrollAdvanceStatementService.class.getMethod("read",
                com.educore.api.identity.AuthenticatedUser.class, UUID.class, UUID.class);
        assertThat(facade.getAnnotation(Transactional.class)).isNull();
        var method = PayrollAdvanceController.class.getDeclaredMethod("statement",
                com.educore.api.identity.AuthenticatedUser.class, UUID.class, UUID.class);
        assertThat(method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value())
                .contains("CAPABILITY_payroll.read", "CAPABILITY_accounting.read");
    }
}

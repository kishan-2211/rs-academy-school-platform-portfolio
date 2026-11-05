export type AdvanceLedgerPosting = {
  batchId: string;
  postingDate: string;
  sourceStatus: string;
  accountingJournalId: string | null;
  journalStatus: string | null;
  reversalJournalId: string | null;
  reversalStatus: string | null;
  evidenceStatus: string;
  countsAsRecovered: boolean;
};

export type AdvanceRecoveryAttempt = {
  id: string;
  payrollRunId: string;
  runCode: string;
  payrollMonth: string;
  payrollRunStatus: string;
  amount: number;
  reservationStatus: string;
  reservedAt: string;
  releasedAt: string | null;
  releaseReason: string | null;
  releasedBy: string | null;
  status: string;
  ledgerPostings: AdvanceLedgerPosting[];
};

export type AdvanceStatement = {
  schoolId: string;
  schoolName: string;
  generatedAt: string;
  advanceId: string;
  advanceNumber: string;
  advanceType: string;
  purpose: string;
  staffProfileId: string;
  employeeCode: string;
  staffName: string;
  issueDate: string;
  currencyCode: string;
  principalAmount: number;
  advanceStatus: string;
  postedDisbursementAmount: number | null;
  recoveredAmount: number | null;
  outstandingAmount: number | null;
  evidenceStatus: string;
  disbursements: {
    id: string;
    sequenceNumber: number;
    postingDate: string;
    paymentMethod: string;
    paymentReference: string | null;
    sourceAmount: number;
    sourceStatus: string;
    accountingJournalId: string | null;
    journalStatus: string | null;
    countsAsDisbursed: boolean;
    preparedAt: string;
  }[];
  installments: {
    id: string;
    installmentNumber: number;
    dueMonth: string;
    plannedAmount: number;
    recoveredAmount: number;
    outstandingAmount: number;
    status: string;
    recoveryAttempts: AdvanceRecoveryAttempt[];
  }[];
};

export function isScopedAdvanceStatement(
  statement: AdvanceStatement,
  schoolId: string,
  advanceId: string,
) {
  return statement.schoolId === schoolId && statement.advanceId === advanceId;
}

/** Search only the already authorized snapshot; this is never an access-control filter. */
export type SearchableFeeAccount = {
  learnerName: string;
  admissionNumber?: string | null;
  gradeCode: string;
  sessionName: string;
  rollNumber?: string | null;
  sectionCode?: string | null;
  charges: ReadonlyArray<{ label: string; feeHeadName: string }>;
};

function searchText(value: string): string {
  return value.normalize("NFKC").toLocaleLowerCase("en-IN");
}

export function feeAccountMatches(account: SearchableFeeAccount, query: string): boolean {
  const tokens = searchText(query).trim().split(/\s+/u).filter(Boolean);
  if (!tokens.length) return true;
  const fields = [account.learnerName, account.admissionNumber ?? "", account.gradeCode,
    account.sessionName, account.rollNumber ?? "", account.sectionCode ?? "",
    ...account.charges.flatMap((charge) => [charge.label, charge.feeHeadName])].map(searchText);
  return tokens.every((token) => fields.some((field) => field.includes(token)));
}

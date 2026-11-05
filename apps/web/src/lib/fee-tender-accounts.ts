type PostingAccount = {
  accountType: string;
  category: string;
  status: string;
  allowDirectPosting: boolean;
};

/** Mirror approved-book eligibility, without treating unsettled gateway funds as bank cash. */
export function feeTenderAccounts<T extends PostingAccount>(accounts: readonly T[], tender: string): T[] {
  const categories = tender === "CASH" ? ["CASH"]
    : tender === "ONLINE_PROVIDER" ? ["OTHER", "BANK"]
      : ["BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI"].includes(tender) ? ["BANK"] : [];
  return accounts.filter((account) => account.status === "ACTIVE" && account.allowDirectPosting
    && account.accountType === "ASSET" && categories.includes(account.category));
}

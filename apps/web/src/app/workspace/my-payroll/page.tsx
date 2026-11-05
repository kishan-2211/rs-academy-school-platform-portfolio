import { PayrollPayslipWorkspace } from "@/components/workspace/payroll-payslip-workspace";
import "../payroll/payroll.css";

export const metadata = { title: "My payslips · School workspace" };

export default function MyPayrollPage() {
  return <section className="payroll-page payroll-self-page">
    <header className="payroll-hero">
      <div><span><ShieldMark /> Private employment record</span><h1>Your finalized <em>payslips.</em></h1><p>Download only the salary records issued to the staff profile securely linked to this account.</p></div>
      <aside><small>Privacy boundary</small><strong>Own record only</strong><span>Authenticated access · private storage · no public link</span></aside>
    </header>
    <PayrollPayslipWorkspace selfService />
  </section>;
}

function ShieldMark() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 5 6v5c0 4.6 2.8 8 7 10 4.2-2 7-5.4 7-10V6l-7-3Zm-1 12-3-3 1.4-1.4 1.6 1.6 3.6-3.6L16 10l-5 5Z" fill="currentColor" /></svg>;
}

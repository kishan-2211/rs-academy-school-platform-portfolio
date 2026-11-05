import { AcademicRolloverWorkspace } from "@/components/workspace/academic-rollover-workspace";
import "./rollover.css";
import "./rollover-detail.css";
import "./rollover-responsive.css";

export const metadata = { title: "Academic rollover · School workspace" };

export default function AcademicRolloverPage() {
  return <AcademicRolloverWorkspace />;
}

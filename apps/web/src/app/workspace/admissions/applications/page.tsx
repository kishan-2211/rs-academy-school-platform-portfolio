import { AdmissionApplicationsWorkspace } from "@/components/workspace/admission-applications-workspace";

export default async function WorkspaceAdmissionApplicationsPage({
  searchParams,
}: {
  searchParams: Promise<{ application?: string }>;
}) {
  const { application } = await searchParams;
  return <AdmissionApplicationsWorkspace initialApplicationId={application ?? ""} />;
}

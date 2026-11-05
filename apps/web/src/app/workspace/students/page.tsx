import { StudentRegistryWorkspace } from "@/components/workspace/student-registry-workspace";

export default async function WorkspaceStudentsPage({
  searchParams,
}: {
  searchParams: Promise<{ student?: string }>;
}) {
  const { student } = await searchParams;
  return <StudentRegistryWorkspace initialStudentId={student ?? ""} />;
}

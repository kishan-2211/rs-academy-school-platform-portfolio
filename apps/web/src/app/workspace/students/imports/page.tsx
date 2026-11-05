import { StudentImportWorkspace } from "@/components/workspace/student-import-workspace";

export default async function StudentImportsPage({
  searchParams,
}: {
  searchParams: Promise<{ batch?: string }>;
}) {
  const { batch } = await searchParams;
  return <StudentImportWorkspace initialBatchId={batch ?? ""} />;
}

import { Text } from "@/components/i18n/language-provider";

export default function Loading() {
  return (
    <div className="page-loading" role="status">
      <span className="page-loading__mark" aria-hidden="true" />
      <span className="sr-only"><Text>Loading…</Text></span>
    </div>
  );
}

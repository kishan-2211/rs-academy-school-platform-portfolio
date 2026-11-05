import { PublicInnerPage } from "@/components/public-inner-page";
import { PublicNotices } from "@/components/public-notices";
import { publicPages } from "@/content/public-pages";

export const metadata = {
  title: "Stories & notices",
  description: "Authority-approved notices and school stories from R.S. Academy.",
};

export default function NewsPage() {
  return <PublicInnerPage page={publicPages.news!} afterSections={<PublicNotices />} />;
}

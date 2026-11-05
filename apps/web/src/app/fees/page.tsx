import { PublicFeeDisclosure } from "@/components/public-fee-disclosure";
import "./fees.css";

export const metadata = {
  title: "Fee disclosure",
  description: "Authority-approved R.S. Academy fee structures by academic session and class.",
};

export default function FeesPage() { return <PublicFeeDisclosure />; }

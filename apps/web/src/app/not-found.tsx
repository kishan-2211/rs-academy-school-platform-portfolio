import Link from "next/link";
import { ArrowRight } from "@/components/icons";

export default function NotFound() {
  return (
    <main id="main-content" className="simple-page simple-page--center">
      <p className="eyebrow"><span /> 404</p>
      <h1>This page wandered off campus.</h1>
      <p>The link may have changed, but the way home is just here.</p>
      <Link className="button button--dark" href="/">Return home <ArrowRight /></Link>
    </main>
  );
}

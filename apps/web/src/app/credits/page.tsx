import type { Metadata } from "next";

export const metadata: Metadata = { title: "Creative credits" };

export default function CreditsPage() {
  return (
    <main id="main-content" className="policy-page shell">
      <p className="eyebrow"><span /> Creative credits</p>
      <h1>Motion with thoughtful origins</h1>
      <p className="policy-page__lead">R.S. Academy uses a small, curated set of locally hosted animations. Motion supports understanding and delight, never access control or essential information.</p>
      <h2>Lottie animations</h2>
      <ul>
        <li><a href="https://lottiefiles.com/free-animation/study-discussion-HfaDECsT9H" rel="noreferrer">Study discussion</a> by <a href="https://lottiefiles.com/mograph" rel="noreferrer">Md. Imam Hossain</a>.</li>
        <li><a href="https://lottiefiles.com/free-animation/successful-target-wvXfLnnZLn" rel="noreferrer">Successful target</a> by <a href="https://lottiefiles.com/mograph" rel="noreferrer">Md. Imam Hossain</a>.</li>
      </ul>
      <p>Both animation files are used under the <a href="https://lottiefiles.com/page/license" rel="noreferrer">Lottie Simple License</a>. Their license does not apply to R.S. Academy branding or the original EduCore product design and code.</p>
    </main>
  );
}

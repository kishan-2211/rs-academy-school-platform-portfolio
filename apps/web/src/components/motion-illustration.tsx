"use client";

import dynamic from "next/dynamic";
import { useSyncExternalStore } from "react";

const DotLottiePlayer = dynamic(
  () => import("@lottiefiles/dotlottie-react").then((module) => {
    module.setWasmUrl("/animations/dotlottie-player.wasm");
    return module.DotLottieReact;
  }),
  { ssr: false, loading: () => <span className="motion-illustration__loading" /> },
);

function subscribeToReducedMotion(callback: () => void) {
  const media = window.matchMedia("(prefers-reduced-motion: reduce)");
  media.addEventListener("change", callback);
  return () => media.removeEventListener("change", callback);
}

function reducedMotionSnapshot() {
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

export function MotionIllustration({
  src,
  className = "",
  label,
  loop = true,
}: {
  src: string;
  className?: string;
  label?: string;
  loop?: boolean;
}) {
  const reducedMotion = useSyncExternalStore(subscribeToReducedMotion, reducedMotionSnapshot, () => true);

  return (
    <span
      className={`motion-illustration ${className}`.trim()}
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      <DotLottiePlayer
        src={src}
        autoplay={!reducedMotion}
        loop={!reducedMotion && loop}
        aria-hidden="true"
      />
    </span>
  );
}

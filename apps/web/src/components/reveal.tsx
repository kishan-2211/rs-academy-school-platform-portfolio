"use client";

import { useEffect, useRef, useState, type HTMLAttributes, type ReactNode } from "react";

type RevealProps = HTMLAttributes<HTMLDivElement> & {
  children: ReactNode;
  delay?: number;
};

export function Reveal({ children, className = "", delay = 0, ...props }: RevealProps) {
  const elementRef = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const element = elementRef.current;
    if (!element) return;

    // IntersectionObserver should enhance the entrance, never gate the content.
    // The timeout protects fast in-page navigation, print/capture tools, and unusual browser scheduling.
    const safetyTimer = window.setTimeout(() => setVisible(true), 1800 + delay);

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) {
          setVisible(true);
          observer.disconnect();
          window.clearTimeout(safetyTimer);
        }
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.08 },
    );

    observer.observe(element);
    return () => {
      observer.disconnect();
      window.clearTimeout(safetyTimer);
    };
  }, [delay]);

  return (
    <div
      ref={elementRef}
      className={`reveal${visible ? " reveal--visible" : ""} ${className}`}
      style={{ "--reveal-delay": `${delay}ms` } as React.CSSProperties}
      {...props}
    >
      {children}
    </div>
  );
}

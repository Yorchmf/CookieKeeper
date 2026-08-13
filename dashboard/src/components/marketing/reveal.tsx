"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { cn } from "@/lib/utils";

const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";

/** Subscribes to the reduced-motion media query without a setState-in-effect. */
function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const mq = window.matchMedia(REDUCED_MOTION_QUERY);
      mq.addEventListener("change", onChange);
      return () => mq.removeEventListener("change", onChange);
    },
    () => window.matchMedia(REDUCED_MOTION_QUERY).matches,
    () => false,
  );
}

type RevealProps = {
  children: React.ReactNode;
  className?: string;
  /** Stagger delay in ms, for sequencing sibling reveals. */
  delay?: number;
  as?: "div" | "section" | "li" | "article";
};

/**
 * Scroll-reveal wrapper: fades + lifts its children into view once, using only
 * compositor-friendly properties (opacity/transform). Honours
 * `prefers-reduced-motion` by rendering fully visible with no animation.
 */
export function Reveal({
  children,
  className,
  delay = 0,
  as: Tag = "div",
}: RevealProps) {
  const ref = useRef<HTMLElement | null>(null);
  const [isVisible, setIsVisible] = useState(false);
  const prefersReducedMotion = usePrefersReducedMotion();

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setIsVisible(true);
          observer.disconnect();
        }
      },
      { rootMargin: "0px 0px -10% 0px", threshold: 0.1 },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  const shown = isVisible || prefersReducedMotion;

  return (
    <Tag
      // @ts-expect-error — ref typing across the polymorphic tag union is safe here.
      ref={ref}
      className={cn(
        "motion-safe:transition-all motion-safe:duration-700 motion-safe:ease-[cubic-bezier(0.16,1,0.3,1)]",
        shown
          ? "opacity-100 translate-y-0"
          : "motion-safe:opacity-0 motion-safe:translate-y-6",
        className,
      )}
      style={shown && delay ? undefined : { transitionDelay: `${delay}ms` }}
    >
      {children}
    </Tag>
  );
}

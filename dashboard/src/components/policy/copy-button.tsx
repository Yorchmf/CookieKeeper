"use client";

import { CheckIcon, CopyIcon } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";

const COPY_FEEDBACK_MS = 2000;

/**
 * Copies a string to the clipboard with transient "copied" feedback. Degrades quietly when the
 * Clipboard API is unavailable (insecure context / denied permission) — callers keep the source
 * value visible and selectable so it can still be copied by hand.
 */
export function CopyButton({
  value,
  label,
  copiedLabel,
}: {
  value: string;
  label: string;
  copiedLabel: string;
}) {
  const [isCopied, setIsCopied] = useState(false);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, []);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value);
      setIsCopied(true);
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
      timeoutRef.current = setTimeout(() => setIsCopied(false), COPY_FEEDBACK_MS);
    } catch {
      setIsCopied(false);
    }
  };

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      onClick={() => void handleCopy()}
    >
      {isCopied ? (
        <CheckIcon aria-hidden="true" />
      ) : (
        <CopyIcon aria-hidden="true" />
      )}
      {isCopied ? copiedLabel : label}
      <span role="status" className="sr-only">
        {isCopied ? copiedLabel : ""}
      </span>
    </Button>
  );
}

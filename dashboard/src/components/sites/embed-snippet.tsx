"use client";

import { CheckIcon, CopyIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";

const COPY_FEEDBACK_MS = 2000;

/**
 * Renders the embed snippet strictly as text (never injected as HTML) with a
 * copy-to-clipboard button and transient feedback.
 */
export function EmbedSnippet({ snippet }: { snippet: string }) {
  const t = useTranslations("sites.detail");
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
      await navigator.clipboard.writeText(snippet);
      setIsCopied(true);
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
      timeoutRef.current = setTimeout(
        () => setIsCopied(false),
        COPY_FEEDBACK_MS,
      );
    } catch {
      // Clipboard unavailable (permissions, insecure context) — keep the
      // snippet selectable so users can copy manually.
      setIsCopied(false);
    }
  };

  return (
    <div className="flex flex-col gap-2">
      <pre className="overflow-x-auto rounded-lg border border-border bg-muted/50 p-4 text-xs leading-relaxed">
        <code>{snippet}</code>
      </pre>
      <div>
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
          {isCopied ? t("copied") : t("copy")}
        </Button>
        <span role="status" className="sr-only">
          {isCopied ? t("copied") : ""}
        </span>
      </div>
    </div>
  );
}

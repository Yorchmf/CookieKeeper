"use client";

import { Check, Copy } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";

const COPY_FEEDBACK_MS = 2000;

/** Read-only marketing embed snippet with copy-to-clipboard feedback. */
export function SnippetCopy({ code }: { code: string }) {
  const t = useTranslations("marketing.how.snippet");
  const [isCopied, setIsCopied] = useState(false);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
  }, []);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(code);
      setIsCopied(true);
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      timeoutRef.current = setTimeout(() => setIsCopied(false), COPY_FEEDBACK_MS);
    } catch {
      setIsCopied(false);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-4">
        <span className="text-xs font-medium tracking-wider text-muted-foreground uppercase">
          {t("label")}
        </span>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => void handleCopy()}
        >
          {isCopied ? <Check aria-hidden /> : <Copy aria-hidden />}
          {isCopied ? t("copied") : t("copy")}
        </Button>
      </div>
      <pre className="overflow-x-auto rounded-lg border border-border bg-background/60 p-4 font-mono text-xs leading-relaxed text-foreground">
        <code>{code}</code>
      </pre>
      <span role="status" className="sr-only">
        {isCopied ? t("copied") : ""}
      </span>
    </div>
  );
}

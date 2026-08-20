"use client";

import { useTranslations } from "next-intl";
import { CopyButton } from "@/components/policy/copy-button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

/**
 * The marker element the widget fills with the live cookie list (ADR-27).
 *
 * Deliberately not parameterised by site: the widget already knows the site key from the embed
 * script it was loaded with, so the same one-liner works on every page of every site.
 */
const LIVE_TABLE_SNIPPET = "<div data-complyr-policy></div>";

/**
 * Offers the self-updating alternative to the copyable HTML block: a customer whose cookie policy
 * page has been through a lawyer keeps that page and marks the spot where the cookie list goes, and
 * the widget repaints it after every scan.
 *
 * Shown whether or not a Complyr policy has been generated — the backend read is gated only on an
 * active site and its public key, and the customers this is for are precisely the ones who may never
 * publish our hosted document.
 */
export function LiveTableCard() {
  const t = useTranslations("policy.liveTable");

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <pre className="overflow-auto rounded-lg border border-border bg-muted/50 p-4 text-xs leading-relaxed">
          <code>{LIVE_TABLE_SNIPPET}</code>
        </pre>
        <p className="text-sm text-muted-foreground">{t("requirement")}</p>
        <div>
          <CopyButton
            value={LIVE_TABLE_SNIPPET}
            label={t("copySnippet")}
            copiedLabel={t("copied")}
          />
        </div>
      </CardContent>
    </Card>
  );
}

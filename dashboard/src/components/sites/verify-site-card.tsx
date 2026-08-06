"use client";

import { AlertCircleIcon, CheckCircle2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";

import { EmbedSnippet } from "@/components/sites/embed-snippet";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { CopyButton } from "@/components/policy/copy-button";
import { useVerifySite } from "@/hooks/use-sites";
import type { SiteDetail, SiteVerification } from "@/lib/api/sites";

/** The two customer-facing miss reasons the backend returns (SiteVerificationResponse.reason). */
const KNOWN_REASONS = ["snippet_not_found", "unreachable"] as const;

/**
 * Domain-verification activation card. Verification gates the public hosted policy page and unlocks the
 * full multi-page crawl, so this is the #1 activation action — it sits directly under the site header.
 *
 * A verification *miss* is a normal outcome (`verified: false`, HTTP 200), not an error: the user reads
 * the reason and fixes their site, so it renders as **persistent** inline instructional text (with a
 * non-color icon so the problem state isn't conveyed by red alone), not a transient toast.
 *
 * Accessibility contract (a11y-architect review):
 * - A single **stable** `role="status"` live region (rendered outside the verified/unverified branch, so
 *   the DOM node survives the branch swap) announces *both* a successful verification and every miss —
 *   including a repeat of an identical miss, because `handleVerify` clears it before each attempt so the
 *   settle is always a distinct text mutation the AT re-reads (SC 4.1.3).
 * - When the site flips to verified the "Verify now" button unmounts; focus is moved to the card heading
 *   so a keyboard/AT user isn't dropped to `<body>` (SC 2.4.3), and the success is announced (SC 4.1.3).
 * - The card title carries `role="heading"`/`aria-level` so the method sub-sections' `<h3>`s nest under a
 *   real heading instead of skipping a level (SC 1.3.1).
 */
export function VerifySiteCard({ site }: { site: SiteDetail }) {
  const t = useTranslations("sites.detail.verify");
  const tDetail = useTranslations("sites.detail");
  const tSites = useTranslations("sites");
  const verify = useVerifySite(site.id);
  const [attempt, setAttempt] = useState<SiteVerification | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const headingRef = useRef<HTMLSpanElement>(null);

  const isVerified = Boolean(site.verifiedAt);
  const wasVerified = useRef(isVerified);

  // On the unverified → verified flip, move focus to the heading (the "Verify now" button that had focus
  // is now unmounted) and announce the success. Guarded by `wasVerified` so an already-verified mount
  // never steals focus. `t` may not be referentially stable, but the guard makes a re-run a no-op.
  useEffect(() => {
    if (isVerified && !wasVerified.current) {
      headingRef.current?.focus();
      setAnnouncement(t("success"));
    }
    wasVerified.current = isVerified;
  }, [isVerified, t]);

  const reasonText = (reason: string | null): string =>
    KNOWN_REASONS.includes(reason as (typeof KNOWN_REASONS)[number])
      ? t(`failure.${reason}`)
      : t("error");

  const handleVerify = async () => {
    if (verify.isPending) return;
    // Clear first so an identical repeat miss is still a distinct text change the live region re-reads.
    setAnnouncement("");
    try {
      const result = await verify.mutateAsync();
      setAttempt(result);
      if (!result.verified) {
        setAnnouncement(reasonText(result.reason));
      }
      // A `verified: true` result announces via the branch-flip effect once the refetched site lands.
    } catch {
      // Transport/auth failure — collapse to a generic persistent notice, same treatment as a miss.
      setAttempt({ verified: false, verifiedAt: null, method: null, reason: null });
      setAnnouncement(t("error"));
    }
  };

  const body = isVerified ? (
    <Card>
      <CardHeader>
        <CardTitle
          role="heading"
          aria-level={2}
          className="flex flex-wrap items-center gap-2"
        >
          <span
            ref={headingRef}
            tabIndex={-1}
            className="rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {t("title")}
          </span>
          <Badge variant="default">
            <CheckCircle2Icon aria-hidden="true" className="mr-1 size-3.5" />
            {tSites("verifiedBadge")}
          </Badge>
        </CardTitle>
        <CardDescription>{t("success")}</CardDescription>
      </CardHeader>
      {site.verificationMethod && (
        <CardContent>
          <p className="text-sm text-muted-foreground">
            {t(`verifiedVia.${site.verificationMethod}`)}
          </p>
        </CardContent>
      )}
    </Card>
  ) : (
    <Card>
      <CardHeader>
        <CardTitle
          role="heading"
          aria-level={2}
          className="flex flex-wrap items-center gap-2"
        >
          <span
            ref={headingRef}
            tabIndex={-1}
            className="rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {t("title")}
          </span>
          <Badge variant="outline">{tSites("unverifiedBadge")}</Badge>
        </CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <section
          aria-label={t("methodSnippet.title")}
          className="flex flex-col gap-2"
        >
          <h3 className="text-sm font-medium">{t("methodSnippet.title")}</h3>
          <p className="text-sm text-muted-foreground">
            {t("methodSnippet.description")}
          </p>
          <EmbedSnippet snippet={site.embedSnippet} />
        </section>

        <section
          aria-label={t("methodDns.title")}
          className="flex flex-col gap-2"
        >
          <h3 className="text-sm font-medium">{t("methodDns.title")}</h3>
          <p className="text-sm text-muted-foreground">
            {t("methodDns.description")}
          </p>
          <dl className="flex flex-col gap-3">
            <div className="flex flex-col gap-1">
              <dt className="text-xs font-medium text-muted-foreground">
                {t("methodDns.nameLabel")}
              </dt>
              <dd className="flex flex-wrap items-center gap-2">
                <code className="rounded bg-muted px-2 py-1 font-mono text-xs">
                  {site.dnsRecordName}
                </code>
                <CopyButton
                  value={site.dnsRecordName}
                  label={tDetail("copy")}
                  copiedLabel={tDetail("copied")}
                />
              </dd>
            </div>
            <div className="flex flex-col gap-1">
              <dt className="text-xs font-medium text-muted-foreground">
                {t("methodDns.valueLabel")}
              </dt>
              <dd className="flex flex-wrap items-center gap-2">
                <code className="rounded bg-muted px-2 py-1 font-mono text-xs">
                  {site.dnsRecordValue}
                </code>
                <CopyButton
                  value={site.dnsRecordValue}
                  label={tDetail("copy")}
                  copiedLabel={tDetail("copied")}
                />
              </dd>
            </div>
          </dl>
        </section>

        <div className="flex flex-col gap-2">
          <div>
            <Button
              type="button"
              aria-disabled={verify.isPending}
              aria-busy={verify.isPending}
              onClick={() => void handleVerify()}
            >
              {verify.isPending ? t("checking") : t("cta")}
            </Button>
          </div>
          {/* Visible instructional text (re-read while fixing the site). The AT announcement is owned by
              the stable live region below, so this carries no `role="status"` — that would double-announce
              and would miss a repeat of an identical reason. The icon keeps the state from being red-only. */}
          {attempt && !attempt.verified && (
            <p className="flex items-center gap-1.5 text-sm text-destructive">
              <AlertCircleIcon aria-hidden="true" className="size-4 shrink-0" />
              {reasonText(attempt.reason)}
            </p>
          )}
        </div>
      </CardContent>
    </Card>
  );

  return (
    <>
      {body}
      {/* Stable across the branch swap: same fragment slot in both states, so updating its text reliably
          announces success and every verification miss to assistive tech. */}
      <span role="status" aria-live="polite" className="sr-only">
        {announcement}
      </span>
    </>
  );
}

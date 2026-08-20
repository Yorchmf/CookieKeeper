"use client";

import { Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { ScanDomainForm } from "@/components/public-scan/scan-domain-form";
import type { ScanDomainValues } from "@/components/public-scan/scan-domain-form";
import { ScanReport } from "@/components/public-scan/scan-report";
import { ScanReportGate } from "@/components/public-scan/scan-report-gate";
import { ScanVerdict } from "@/components/public-scan/scan-verdict";
import { Button } from "@/components/ui/button";
import { ButtonLink } from "@/components/ui/button-link";
import {
  usePublicScanTeaser,
  useRequestPublicScan,
  useUnlockPublicScanReport,
} from "@/hooks/use-public-scan";
import { getApiErrorCode } from "@/lib/api-error-codes";
import type { PublicScanReport as PublicScanReportData } from "@/lib/api/public-scan";

/** The visible stages of the funnel. Focus is moved to each stage's landmark on entry. */
type ScanPhase = "idle" | "scanning" | "failed" | "verdict" | "report";

/** Screen-reader-only errors from the funnel map to the shared `auth.errors` catalog. */
function useErrorMessage() {
  const tErrors = useTranslations("auth.errors");
  return (error: unknown): string => tErrors(getApiErrorCode(error));
}

export function PublicScanWidget() {
  const t = useTranslations("marketing.scan");
  const toMessage = useErrorMessage();

  const [token, setToken] = useState<string | null>(null);
  const [scannedDomain, setScannedDomain] = useState<string | null>(null);
  const [report, setReport] = useState<PublicScanReportData | null>(null);

  const requestScan = useRequestPublicScan();
  const unlock = useUnlockPublicScanReport();
  const teaser = usePublicScanTeaser(token);

  // One focus target per phase. Moving focus to the phase's landmark on entry both repositions
  // keyboard users and lets screen readers announce the new content — so no aria-live region is
  // needed and nothing is announced twice (see the a11y review of Slice F).
  const containerRef = useRef<HTMLDivElement | null>(null);
  const scanningRef = useRef<HTMLParagraphElement | null>(null);
  const failedRef = useRef<HTMLParagraphElement | null>(null);
  const verdictRef = useRef<HTMLHeadingElement | null>(null);
  const reportRef = useRef<HTMLHeadingElement | null>(null);
  const prevPhaseRef = useRef<ScanPhase>("idle");

  const status = teaser.data?.status;
  const isScanning =
    token !== null &&
    report === null &&
    (teaser.isPending || status === "queued" || status === "running");
  const isFailed = status === "failed" || (token !== null && teaser.isError);
  const verdict = report?.verdict ?? teaser.data?.verdict ?? null;
  const domain = scannedDomain ?? report?.domain ?? teaser.data?.domain ?? "";
  const hasVerdict = verdict !== null && !isScanning && !isFailed;

  const phase: ScanPhase = isFailed
    ? "failed"
    : isScanning
      ? "scanning"
      : report !== null
        ? "report"
        : hasVerdict
          ? "verdict"
          : "idle";

  // Move focus on each phase change so the outcome is never announced-yet-unreachable. The initial
  // idle render is skipped (prev === phase), so the page does not steal focus on load.
  useEffect(() => {
    const prev = prevPhaseRef.current;
    if (phase === prev) {
      return;
    }
    prevPhaseRef.current = phase;
    if (phase === "scanning") {
      scanningRef.current?.focus();
    } else if (phase === "failed") {
      failedRef.current?.focus();
    } else if (phase === "verdict") {
      verdictRef.current?.focus();
    } else if (phase === "report") {
      reportRef.current?.focus();
    } else if (prev !== "idle") {
      // Returning to the form (reset/retry) — send focus back to the domain input.
      containerRef.current
        ?.querySelector<HTMLInputElement>("#scan-domain")
        ?.focus();
    }
  }, [phase]);

  const handleScan = async (values: ScanDomainValues) => {
    setReport(null);
    requestScan.reset();
    try {
      const created = await requestScan.mutateAsync(values);
      setScannedDomain(values.domain);
      setToken(created.token);
    } catch {
      // Surfaced via requestScan.error below; nothing else to do here.
    }
  };

  const handleUnlock = async (email: string) => {
    if (token === null) {
      return;
    }
    unlock.reset();
    try {
      setReport(await unlock.mutateAsync({ token, email }));
    } catch {
      // Surfaced via unlock.error below.
    }
  };

  const reset = () => {
    setToken(null);
    setScannedDomain(null);
    setReport(null);
    requestScan.reset();
    unlock.reset();
  };

  return (
    <div
      ref={containerRef}
      className="rounded-2xl border border-border bg-card p-6 shadow-sm sm:p-8"
    >
      {phase === "idle" && (
        <ScanDomainForm
          onSubmit={handleScan}
          isPending={requestScan.isPending}
          errorMessage={
            requestScan.isError ? toMessage(requestScan.error) : null
          }
        />
      )}

      {phase === "scanning" && (
        <div className="flex flex-col items-center gap-3 py-8 text-center">
          <Loader2Icon
            aria-hidden="true"
            className="size-8 text-primary motion-safe:animate-spin"
          />
          <p
            ref={scanningRef}
            tabIndex={-1}
            className="font-medium outline-none"
          >
            {t("scanning", { domain })}
          </p>
          <p className="max-w-sm text-sm text-muted-foreground">
            {t("scanningHint")}
          </p>
        </div>
      )}

      {phase === "failed" && (
        <div className="flex flex-col items-center gap-3 py-8 text-center">
          <p ref={failedRef} tabIndex={-1} className="font-medium outline-none">
            {t("failed.title")}
          </p>
          <p className="max-w-sm text-sm text-muted-foreground">
            {t("failed.description")}
          </p>
          <Button variant="outline" onClick={reset}>
            {t("failed.retry")}
          </Button>
        </div>
      )}

      {(phase === "verdict" || phase === "report") && verdict && (
        <div className="flex flex-col gap-8 motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-2">
          <div className="flex flex-col gap-6">
            <h3
              ref={verdictRef}
              tabIndex={-1}
              className="text-xl font-semibold tracking-tight outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card"
            >
              {t("verdict.title")}
            </h3>
            <ScanVerdict verdict={verdict} domain={domain} />
          </div>

          {phase === "verdict" ? (
            <ScanReportGate
              onUnlock={handleUnlock}
              isPending={unlock.isPending}
              errorMessage={unlock.isError ? toMessage(unlock.error) : null}
            />
          ) : (
            report !== null && (
              <div className="flex flex-col gap-6">
                <h3
                  ref={reportRef}
                  tabIndex={-1}
                  className="text-xl font-semibold tracking-tight outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card"
                >
                  {t("report.heading")}
                </h3>
                <ScanReport report={report} />

                <div className="flex flex-col gap-4 rounded-xl border border-primary/20 bg-primary/5 p-6">
                  <div>
                    <h4 className="text-lg font-semibold tracking-tight">
                      {t("cta.title")}
                    </h4>
                    <p className="mt-1 max-w-prose text-sm text-muted-foreground">
                      {t("cta.description")}
                    </p>
                  </div>
                  <ButtonLink size="lg" className="w-fit" href="/signup">
                    {t("cta.action")}
                  </ButtonLink>
                </div>
              </div>
            )
          )}

          <div>
            <Button variant="ghost" size="sm" onClick={reset}>
              {t("reset")}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

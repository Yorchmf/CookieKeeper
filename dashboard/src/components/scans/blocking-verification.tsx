"use client";

import { useTranslations } from "next-intl";
import { CopyButton } from "@/components/policy/copy-button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Link } from "@/i18n/navigation";
import type {
  BlockingStatus,
  BlockingVendor,
  BlockingVerification as BlockingVerificationData,
} from "@/lib/api/scans";
import { cn } from "@/lib/utils";

/** Card accent per verdict: the two unresolved states are the ones that must not read as neutral. */
const STATUS_TONE: Record<BlockingStatus, string> = {
  unknown: "",
  not_installed: "border-sky-500/40",
  wrong_site_key: "border-destructive/50",
  unblocked: "border-destructive/50",
  clean: "border-emerald-500/40",
};

/**
 * The exact tag the owner has to end up with for one vendor. Code, not prose — deliberately outside
 * the message catalogue, because a translated attribute name would be a broken install.
 */
function blockingTag(vendor: BlockingVendor): string {
  return `<script type="text/plain" data-complyr-category="${vendor.consentCategory}" data-src="https://${vendor.domain}/…"></script>`;
}

/**
 * What the before-consent crawl found out about our own embed (BACKLOG #19).
 *
 * This is the panel that closes the worst failure mode in the product: a customer who pays for a
 * consent tool, embeds it, and is *still* non-compliant because they never tagged the scripts. The
 * banner is a written claim, so a site that shows one and fires Google Analytics anyway is worse off
 * than a site with no banner at all — and until this panel existed, nothing told them.
 *
 * The crawl loads the site in its before-consent state, so a vendor named here is not a guess: it
 * fired without a choice having been made. Every vendor row therefore carries the literal line to
 * change, with the exact `data-complyr-category` value that vendor needs.
 *
 * Renders nothing for `unknown` — a scan that predates the probe has no verdict, and an empty
 * "we don't know" card would be noise on every historical scan.
 */
export function BlockingVerification({
  verification,
  siteId,
}: {
  verification: BlockingVerificationData;
  siteId: string;
}) {
  const t = useTranslations("scans.blocking");
  const { status } = verification;

  if (status === "unknown") return null;

  return (
    <Card
      aria-labelledby="blocking-heading"
      className={cn(STATUS_TONE[status])}
    >
      <CardHeader>
        <h2
          id="blocking-heading"
          className="text-sm font-medium tracking-wide text-muted-foreground uppercase"
        >
          {t("title")}
        </h2>
        <p className="text-lg font-semibold text-pretty">
          {t(`status.${status}.headline`, { count: verification.vendors.length })}
        </p>
        <p className="text-sm text-pretty text-muted-foreground">
          {t(`status.${status}.description`)}
        </p>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {status === "unblocked" && (
          <UnblockedVendors vendors={verification.vendors} />
        )}

        {(status === "not_installed" || status === "wrong_site_key") && (
          <Link
            href={`/sites/${siteId}`}
            className="w-fit rounded text-sm font-medium underline underline-offset-4 outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            {t("embedLink")}
          </Link>
        )}

        {status === "clean" && verification.blockedScriptCount != null && (
          <p className="text-sm text-muted-foreground">
            {t("blockedScripts", { count: verification.blockedScriptCount })}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

/** One remediation row per vendor: what fired, which category gates it, and the tag to write. */
function UnblockedVendors({ vendors }: { vendors: BlockingVendor[] }) {
  const t = useTranslations("scans.blocking");
  // Consent categories share one catalogue with the cookie tables, so the badge here and the section
  // heading a customer scrolls to below always use the same word.
  const categories = useTranslations("scans.categories");

  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-pretty">
        {t.rich("howToFix", {
          code: (chunks) => (
            <code className="rounded bg-muted px-1 py-0.5 text-xs">
              {chunks}
            </code>
          ),
        })}
      </p>
      <ul className="flex flex-col gap-4">
        {vendors.map((vendor) => (
          <li
            key={vendor.domain}
            className="flex flex-col gap-2 border-b border-border/60 pb-4 last:border-0 last:pb-0"
          >
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{vendor.name}</span>
              <code className="text-xs text-muted-foreground">
                {vendor.domain}
              </code>
              <Badge variant="secondary">
                {t("categoryBadge", {
                  category: categories.has(vendor.consentCategory)
                    ? categories(vendor.consentCategory)
                    : vendor.consentCategory,
                })}
              </Badge>
            </div>
            <pre className="overflow-x-auto rounded-lg border border-border bg-muted/50 p-3 text-xs leading-relaxed">
              <code>{blockingTag(vendor)}</code>
            </pre>
            <div>
              <CopyButton
                value={blockingTag(vendor)}
                label={t("copy", { vendor: vendor.name })}
                copiedLabel={t("copied")}
              />
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

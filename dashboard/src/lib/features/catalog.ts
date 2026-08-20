import type { EntitlementLimits } from "@/lib/api/billing";

/**
 * The boolean plan limits a feature can be gated on. Narrowed to the boolean keys of
 * {@link EntitlementLimits} so a typo, or a limit that is a number rather than a switch, fails to
 * compile instead of silently locking (or unlocking) a card.
 */
export type FeatureFlag = {
  [K in keyof EntitlementLimits]: EntitlementLimits[K] extends boolean ? K : never;
}[keyof EntitlementLimits];

/** Which plans include a gated feature — the suffix of the `features.locked.*` message. */
export type LockPlan = "pro" | "business";

export interface FeatureLock {
  flag: FeatureFlag;
  plan: LockPlan;
}

/**
 * One capability. `key` is both the React key and the `features.items.*` message key, so a card can
 * never be listed without copy — a missing key throws in next-intl rather than rendering blank.
 *
 * `scope` decides how `path` is read: `"account"` paths are absolute; `"site"` paths are the suffix
 * appended to `/sites/{id}` (`""` meaning the site page itself). See {@link featureHref}.
 */
export interface FeatureEntry {
  key: string;
  scope: "account" | "site";
  path: string;
  lock?: FeatureLock;
}

export interface FeatureGroup {
  key: string;
  features: readonly FeatureEntry[];
}

/**
 * Everything the product does, grouped the way a customer walks it: get installed → tune the banner →
 * publish the policy → prove it worked → run the account.
 *
 * The inclusion rule is **a place you can go and use it**. Plan behaviours with no destination
 * (priority scan position in the queue, the site cap, retention length) are not cards here — they are
 * the plan comparison on `/billing`, and an "Open" button that opens nothing would be a lie. Gated
 * capabilities *with* a destination are listed and rendered locked, which is the point of the page:
 * a feature a customer cannot use yet is still a feature they should know exists.
 *
 * This table is the index of BACKLOG items 16–21, so it gets a new row whenever a capability lands.
 */
export const FEATURE_GROUPS: readonly FeatureGroup[] = [
  {
    key: "setup",
    features: [
      { key: "addSite", scope: "account", path: "/sites" },
      // Scan history, the re-scan control and the install verdict all live on the site page itself —
      // there is no `/scans` index route, only `/scans/{scanId}` for one result.
      { key: "cookieScan", scope: "site", path: "" },
      { key: "installCheck", scope: "site", path: "" },
      {
        key: "onDemandRescan",
        scope: "site",
        path: "",
        lock: { flag: "onDemandRescan", plan: "pro" },
      },
    ],
  },
  {
    key: "banner",
    features: [
      { key: "bannerDesign", scope: "site", path: "/banner" },
      { key: "consentLifetime", scope: "site", path: "/banner" },
      { key: "accessibility", scope: "site", path: "/banner" },
      { key: "consentMode", scope: "site", path: "" },
      { key: "regionTargeting", scope: "site", path: "" },
      { key: "reprompt", scope: "site", path: "/analytics" },
      {
        key: "removeBranding",
        scope: "site",
        path: "",
        lock: { flag: "removeBranding", plan: "pro" },
      },
    ],
  },
  {
    key: "policy",
    features: [
      { key: "cookiePolicy", scope: "site", path: "/policy" },
      { key: "cookieTable", scope: "site", path: "/policy" },
    ],
  },
  {
    key: "proof",
    features: [
      { key: "siteAnalytics", scope: "site", path: "/analytics" },
      {
        key: "crossSiteAnalytics",
        scope: "account",
        path: "/analytics",
        lock: { flag: "crossSiteAnalytics", plan: "pro" },
      },
      { key: "consentLog", scope: "site", path: "/consent-log" },
      {
        key: "csvExport",
        scope: "site",
        path: "/analytics",
        lock: { flag: "csvExport", plan: "business" },
      },
      {
        key: "evidencePack",
        scope: "site",
        path: "/analytics",
        lock: { flag: "csvExport", plan: "business" },
      },
    ],
  },
  {
    key: "account",
    features: [
      { key: "billing", scope: "account", path: "/billing" },
      { key: "notifications", scope: "account", path: "/settings/notifications" },
      { key: "profile", scope: "account", path: "/settings/profile" },
      { key: "yourData", scope: "account", path: "/settings/data" },
    ],
  },
];

/** The sites list — where a per-site feature sends you when we can't pick the site for you. */
export const SITES_HREF = "/sites";

/**
 * Where a card's link goes. Account-scoped features have one destination. Per-site features need a
 * site, and only the customer can say which: `siteId` is passed only when the account has exactly one
 * active site (the ordinary case for our customers), and otherwise this falls back to the sites list,
 * which is both where you pick one and where you add your first.
 */
export function featureHref(feature: FeatureEntry, siteId: string | null): string {
  if (feature.scope === "account") return feature.path;
  return siteId ? `/sites/${siteId}${feature.path}` : SITES_HREF;
}

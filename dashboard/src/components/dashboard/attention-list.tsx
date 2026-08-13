"use client";

import {
  FileWarningIcon,
  ScanSearchIcon,
  ShieldAlertIcon,
  ShieldCheckIcon,
  UnplugIcon,
} from "lucide-react";
import { useTranslations } from "next-intl";

import { Link } from "@/i18n/navigation";
import type { OverviewAction, OverviewActionKind } from "@/lib/api/overview";

/**
 * Where each kind of problem is actually fixed, and the icon that stands for it. Keeping the mapping in
 * one table (rather than a switch per concern) means adding a kind is one row, and it cannot be routed
 * somewhere the customer can't act.
 */
const ACTION_ROUTES: Record<
  OverviewActionKind,
  { href: (siteId: string) => string; Icon: typeof ShieldAlertIcon }
> = {
  unverified: { href: (id) => `/sites/${id}`, Icon: UnplugIcon },
  never_scanned: { href: (id) => `/sites/${id}/scans`, Icon: ScanSearchIcon },
  policy_missing: { href: (id) => `/sites/${id}/policy`, Icon: FileWarningIcon },
  policy_stale: { href: (id) => `/sites/${id}/policy`, Icon: FileWarningIcon },
  insecure_cookies: { href: (id) => `/sites/${id}/analytics`, Icon: ShieldAlertIcon },
};

function AttentionRow({ action }: { action: OverviewAction }) {
  const t = useTranslations("dashboard.attention");
  const { href, Icon } = ACTION_ROUTES[action.kind];

  return (
    <li>
      <Link
        href={href(action.siteId)}
        className="group flex items-start gap-3 rounded-xl border border-border bg-card px-4 py-3 transition-colors hover:bg-muted/50 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 outline-none"
      >
        <Icon
          aria-hidden="true"
          className="mt-0.5 size-4 shrink-0 text-muted-foreground transition-colors group-hover:text-foreground"
        />
        <span className="min-w-0 flex-1">
          <span className="block font-medium">
            {t(`kind.${action.kind}`, { count: action.count ?? 0 })}
          </span>
          <span className="block text-sm text-muted-foreground">
            {t(`hint.${action.kind}`)}
          </span>
        </span>
        <span className="min-w-0 max-w-[45%] truncate text-sm text-muted-foreground">
          {action.domain}
        </span>
      </Link>
    </li>
  );
}

/**
 * The to-do list of the dashboard home: one row per site that needs something, already ordered by
 * severity server-side (so this component never re-sorts and cannot disagree with the API). An account
 * in good shape gets an explicit all-clear rather than an empty gap — absence of rows should read as a
 * verified state, not as a page that failed to load.
 */
export function AttentionList({ actions }: { actions: OverviewAction[] }) {
  const t = useTranslations("dashboard.attention");

  if (actions.length === 0) {
    return (
      <div className="flex items-center gap-3 rounded-xl border border-dashed border-border px-4 py-6">
        <ShieldCheckIcon aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
        <span>
          <span className="block font-medium">{t("allClear")}</span>
          <span className="block text-sm text-muted-foreground">{t("allClearHint")}</span>
        </span>
      </div>
    );
  }

  return (
    <ul className="flex flex-col gap-2">
      {actions.map((action) => (
        <AttentionRow key={`${action.siteId}-${action.kind}`} action={action} />
      ))}
    </ul>
  );
}

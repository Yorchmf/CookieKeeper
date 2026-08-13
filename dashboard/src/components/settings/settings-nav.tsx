"use client";

import { useTranslations } from "next-intl";
import { Link, usePathname } from "@/i18n/navigation";
import { cn } from "@/lib/utils";

const SETTINGS_NAV_ITEMS = [
  { key: "profile", href: "/settings/profile" },
  { key: "security", href: "/settings/security" },
  { key: "notifications", href: "/settings/notifications" },
  { key: "data", href: "/settings/data" },
] as const;

/**
 * Sub-navigation for the account settings surfaces. `usePathname` from the i18n router is already
 * locale-stripped, so the comparison is against the plain route.
 */
export function SettingsNav() {
  const t = useTranslations("settings");
  const pathname = usePathname();

  return (
    <nav aria-label={t("navLabel")}>
      <ul className="flex flex-wrap gap-1 border-b border-border">
        {SETTINGS_NAV_ITEMS.map((item) => {
          const isCurrent = pathname === item.href;
          return (
            <li key={item.key}>
              <Link
                href={item.href}
                aria-current={isCurrent ? "page" : undefined}
                className={cn(
                  "-mb-px block border-b-2 px-3 py-2 text-sm transition-colors",
                  isCurrent
                    ? "border-primary font-medium text-foreground"
                    : "border-transparent text-muted-foreground hover:border-border hover:text-foreground",
                )}
              >
                {t(`nav.${item.key}`)}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

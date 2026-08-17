import { useTranslations } from "next-intl";
import { LifeBuoy } from "lucide-react";
import { Link } from "@/i18n/navigation";
import { UserNav } from "@/components/app/user-nav";
import { SUPPORT_MAILTO } from "@/lib/site";

// Only routes that exist today; scans/consent-log/policies
// return here as their pages are built in later weeks.
const NAV_ITEMS = [
  { key: "dashboard", href: "/dashboard" },
  { key: "sites", href: "/sites" },
  // Cross-site consent roll-up. Shown to every account as the discovery/upsell path — the page itself
  // renders a Pro/Business upgrade prompt for accounts that don't have the entitlement yet.
  { key: "analytics", href: "/analytics" },
  { key: "billing", href: "/billing" },
  { key: "settings", href: "/settings/data" },
] as const;

export default function AppShellLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const t = useTranslations();

  return (
    <div className="flex min-h-dvh flex-1">
      <aside className="hidden w-60 shrink-0 flex-col border-r border-border bg-sidebar md:flex">
        <header className="flex h-14 items-center border-b border-border px-5">
          <Link href="/" className="font-semibold tracking-tight">
            {t("app.name")}
          </Link>
        </header>
        <nav aria-label={t("nav.dashboard")} className="flex-1 p-3">
          <ul className="flex flex-col gap-1">
            {NAV_ITEMS.map((item) => (
              <li key={item.key}>
                <Link
                  href={item.href}
                  className="block rounded-md px-3 py-2 text-sm text-sidebar-foreground transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
                >
                  {t(`nav.${item.key}`)}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
        {/* Persistent contact line — the FAQ promises a human reply, so give logged-in customers the
            same one click away. A mailto keeps it honest with no unbuilt form behind it. */}
        <div className="border-t border-border p-3">
          <a
            href={SUPPORT_MAILTO}
            className="flex items-center gap-2 rounded-md px-3 py-2 text-sm text-sidebar-foreground transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
          >
            <LifeBuoy aria-hidden="true" className="size-4 shrink-0" />
            {t("nav.support")}
          </a>
        </div>
      </aside>
      <div className="flex flex-1 flex-col">
        <header className="flex h-14 items-center justify-between border-b border-border px-6">
          <span className="font-semibold tracking-tight md:hidden">
            {t("app.name")}
          </span>
          <UserNav />
        </header>
        {children}
      </div>
    </div>
  );
}

import { useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { UserNav } from "@/components/app/user-nav";

// Only routes that exist today; scans/consent-log/policies/settings
// return here as their pages are built in later weeks.
const NAV_ITEMS = [
  { key: "dashboard", href: "/dashboard" },
  { key: "sites", href: "/sites" },
  { key: "billing", href: "/billing" },
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

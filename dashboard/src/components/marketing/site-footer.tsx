import { useTranslations } from "next-intl";
import { LanguageSwitcher } from "@/components/marketing/language-switcher";
import { ThemeToggle } from "@/components/marketing/theme-toggle";
import { Link } from "@/i18n/navigation";
import { SUPPORT_EMAIL, SUPPORT_MAILTO } from "@/lib/site";

// Baked at build time in static generation — good enough for a copyright line.
const CURRENT_YEAR = new Date().getFullYear();

export function SiteFooter() {
  const t = useTranslations("marketing.footer");

  const columns = [
    {
      heading: t("product"),
      links: [
        { label: t("features"), href: "#features" },
        { label: t("pricing"), href: "#pricing" },
        { label: t("faq"), href: "#faq" },
      ],
    },
    {
      heading: t("legal"),
      links: [
        { label: t("privacy"), href: "/privacy" },
        { label: t("terms"), href: "/terms" },
        { label: t("imprint"), href: "/imprint" },
      ],
    },
  ];

  return (
    <footer className="border-t border-border/60 bg-muted/30">
      <div className="mx-auto grid max-w-6xl gap-10 px-6 py-14 md:grid-cols-[1.5fr_1fr_1fr_auto]">
        <div className="flex flex-col gap-3">
          <span className="flex items-center gap-2 text-lg font-semibold tracking-tight">
            <span
              aria-hidden
              className="size-2.5 rounded-full bg-brand shadow-[0_0_0_3px_var(--brand-subtle)]"
            />
            Complyr
          </span>
          <p className="max-w-xs text-sm text-pretty text-muted-foreground">
            {t("tagline")}
          </p>
          <p className="mt-2 inline-flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
            <span aria-hidden>🇪🇺</span> {t("madeIn")}
          </p>
          <a
            href={SUPPORT_MAILTO}
            aria-label={t("contactAria", { email: SUPPORT_EMAIL })}
            className="mt-1 text-sm text-muted-foreground transition-colors hover:text-foreground"
          >
            {t("contact")}
          </a>
        </div>

        {columns.map((column) => (
          <nav key={column.heading} aria-label={column.heading} className="flex flex-col gap-3">
            <h2 className="text-xs font-semibold tracking-wider text-foreground uppercase">
              {column.heading}
            </h2>
            <ul className="flex flex-col gap-2">
              {column.links.map((link) =>
                link.href.startsWith("#") ? (
                  <li key={link.label}>
                    <a
                      href={link.href}
                      className="text-sm text-muted-foreground transition-colors hover:text-foreground"
                    >
                      {link.label}
                    </a>
                  </li>
                ) : (
                  <li key={link.label}>
                    <Link
                      href={link.href}
                      className="text-sm text-muted-foreground transition-colors hover:text-foreground"
                    >
                      {link.label}
                    </Link>
                  </li>
                ),
              )}
            </ul>
          </nav>
        ))}

        <div className="flex items-start gap-1">
          <LanguageSwitcher />
          <ThemeToggle />
        </div>
      </div>

      <div className="border-t border-border/60">
        <p className="mx-auto max-w-6xl px-6 py-6 text-xs text-muted-foreground">
          {t("rights", { year: CURRENT_YEAR })}
        </p>
      </div>
    </footer>
  );
}

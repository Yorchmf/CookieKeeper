"use client";

import { Menu, X } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { LanguageSwitcher } from "@/components/marketing/language-switcher";
import { ThemeToggle } from "@/components/marketing/theme-toggle";
import { Button } from "@/components/ui/button";
import { Link } from "@/i18n/navigation";
import { cn } from "@/lib/utils";

const NAV_LINKS = [
  { href: "#features", key: "features" },
  { href: "#pricing", key: "pricing" },
  { href: "#faq", key: "faq" },
] as const;

export function SiteHeader() {
  const t = useTranslations("marketing.nav");
  const [isOpen, setIsOpen] = useState(false);

  return (
    <header className="sticky top-0 z-50 border-b border-border/60 bg-background/80 backdrop-blur-md">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-6">
        <Link
          href="/"
          className="flex items-center gap-2 text-lg font-semibold tracking-tight"
        >
          <span
            aria-hidden
            className="size-2.5 rounded-full bg-brand shadow-[0_0_0_3px_var(--brand-subtle)]"
          />
          Complyr
        </Link>

        <nav
          aria-label="Primary"
          className="hidden items-center gap-1 md:flex"
        >
          {NAV_LINKS.map((link) => (
            <a
              key={link.key}
              href={link.href}
              className="rounded-md px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
            >
              {t(link.key)}
            </a>
          ))}
        </nav>

        <div className="hidden items-center gap-1 md:flex">
          <LanguageSwitcher />
          <ThemeToggle />
          <Button
            variant="ghost"
            size="sm"
            nativeButton={false}
            render={<Link href="/login" />}
          >
            {t("signIn")}
          </Button>
          <Button
            variant="brand"
            size="sm"
            nativeButton={false}
            render={<Link href="/signup" />}
          >
            {t("cta")}
          </Button>
        </div>

        <div className="flex items-center gap-1 md:hidden">
          <ThemeToggle />
          <Button
            variant="ghost"
            size="icon"
            aria-label="Menu"
            aria-expanded={isOpen}
            onClick={() => setIsOpen((v) => !v)}
          >
            {isOpen ? <X /> : <Menu />}
          </Button>
        </div>
      </div>

      <div
        className={cn(
          "overflow-hidden border-t border-border/60 md:hidden",
          isOpen ? "block" : "hidden",
        )}
      >
        <nav
          aria-label="Mobile"
          className="mx-auto flex max-w-6xl flex-col gap-1 px-6 py-4"
        >
          {NAV_LINKS.map((link) => (
            <a
              key={link.key}
              href={link.href}
              onClick={() => setIsOpen(false)}
              className="rounded-md px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              {t(link.key)}
            </a>
          ))}
          <div className="mt-2 flex items-center gap-2">
            <LanguageSwitcher />
            <Button
              variant="ghost"
              size="sm"
              nativeButton={false}
              render={<Link href="/login" />}
            >
              {t("signIn")}
            </Button>
            <Button
              variant="brand"
              size="sm"
              className="flex-1"
              nativeButton={false}
              render={<Link href="/signup" />}
            >
              {t("cta")}
            </Button>
          </div>
        </nav>
      </div>
    </header>
  );
}

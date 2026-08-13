"use client";

import { Globe } from "lucide-react";
import { useLocale } from "next-intl";
import { useTransition } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { usePathname, useRouter } from "@/i18n/navigation";
import { routing } from "@/i18n/routing";

const LOCALE_LABELS: Record<string, string> = {
  en: "English",
  de: "Deutsch",
  fr: "Français",
  es: "Español",
  it: "Italiano",
};

/** Switches locale while preserving the current pathname. */
export function LanguageSwitcher() {
  const locale = useLocale();
  const pathname = usePathname();
  const router = useRouter();
  const [isPending, startTransition] = useTransition();

  function handleSelect(next: string) {
    startTransition(() => {
      router.replace(pathname, { locale: next });
    });
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            variant="ghost"
            size="sm"
            disabled={isPending}
            aria-label={LOCALE_LABELS[locale] ?? locale}
          >
            <Globe />
            <span className="uppercase">{locale}</span>
          </Button>
        }
      />
      <DropdownMenuContent align="end">
        {routing.locales.map((code) => (
          <DropdownMenuItem
            key={code}
            onClick={() => handleSelect(code)}
            data-active={code === locale}
            className="data-[active=true]:font-medium"
          >
            {LOCALE_LABELS[code] ?? code}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

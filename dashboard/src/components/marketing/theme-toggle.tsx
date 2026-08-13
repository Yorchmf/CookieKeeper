"use client";

import { Monitor, Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";
import { useTranslations } from "next-intl";
import { useSyncExternalStore } from "react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

const THEMES = [
  { value: "light", icon: Sun },
  { value: "dark", icon: Moon },
  { value: "system", icon: Monitor },
] as const;

const noopSubscribe = () => () => {};

/** True only after client hydration — avoids a setState-in-effect mount guard. */
function useHasMounted(): boolean {
  return useSyncExternalStore(
    noopSubscribe,
    () => true,
    () => false,
  );
}

/**
 * Light / dark / system switch. next-themes resolves the active theme only on
 * the client, so we render a stable placeholder until mounted to avoid a
 * hydration mismatch on the icon.
 */
export function ThemeToggle() {
  const t = useTranslations("marketing.theme");
  const { setTheme } = useTheme();
  const isMounted = useHasMounted();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button variant="ghost" size="icon" aria-label={t("label")}>
            {isMounted ? (
              <>
                <Sun className="hidden dark:block" />
                <Moon className="block dark:hidden" />
              </>
            ) : (
              <Sun />
            )}
          </Button>
        }
      />
      <DropdownMenuContent align="end">
        {THEMES.map(({ value, icon: Icon }) => (
          <DropdownMenuItem key={value} onClick={() => setTheme(value)}>
            <Icon />
            {t(value)}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

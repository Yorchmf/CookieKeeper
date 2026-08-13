"use client";

import { ThemeProvider as NextThemesProvider } from "next-themes";
import type { ComponentProps } from "react";

/**
 * App-wide theme provider. Drives light/dark off the system preference by
 * default (brand.md §7) while still allowing an explicit manual override that
 * persists in localStorage. `attribute="class"` toggles the `.dark` class the
 * Tailwind v4 `@custom-variant dark` selector keys off.
 */
export function ThemeProvider({
  children,
  ...props
}: ComponentProps<typeof NextThemesProvider>) {
  return (
    <NextThemesProvider
      attribute="class"
      defaultTheme="system"
      enableSystem
      disableTransitionOnChange
      {...props}
    >
      {children}
    </NextThemesProvider>
  );
}

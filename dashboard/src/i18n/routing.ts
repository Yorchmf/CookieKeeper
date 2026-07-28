import { defineRouting } from "next-intl/routing";

export const routing = defineRouting({
  locales: ["en", "de", "fr", "es", "it"],
  defaultLocale: "en",
});

export type Locale = (typeof routing.locales)[number];

/**
 * Locale-aware pathname helpers shared by the middleware (src/proxy.ts) and
 * client code that must reason about the current route (query-provider).
 */
import { routing } from "./routing";

/** Split `/de/sites/123` into `{ locale: "de", path: "/sites/123" }`. */
export function splitLocale(pathname: string): {
  locale: string;
  path: string;
} {
  const [, first, ...rest] = pathname.split("/");
  if (first && (routing.locales as readonly string[]).includes(first)) {
    return { locale: first, path: `/${rest.join("/")}` };
  }
  return { locale: routing.defaultLocale, path: pathname };
}

export function matchesPrefix(
  path: string,
  prefixes: readonly string[],
): boolean {
  return prefixes.some(
    (prefix) => path === prefix || path.startsWith(`${prefix}/`),
  );
}

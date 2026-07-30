import createMiddleware from "next-intl/middleware";
import { NextResponse, type NextRequest } from "next/server";
import { matchesPrefix, splitLocale } from "./i18n/pathname";
import { routing } from "./i18n/routing";

const intlMiddleware = createMiddleware(routing);

const ACCESS_TOKEN_COOKIE = "cmplyr_at";

/** Path prefixes (locale-stripped) of the authenticated (app) route group. */
const PROTECTED_PREFIXES = ["/dashboard", "/sites"];

/** Auth pages that bounce already-signed-in users back to the app. */
const AUTH_PAGES = ["/login", "/signup"];

/**
 * Locale routing (next-intl) plus a cheap auth gate: presence of the access
 * token cookie only. Real authorization is enforced by the backend — this
 * just avoids rendering app pages that would immediately 401.
 */
export default function proxy(request: NextRequest) {
  const { locale, path } = splitLocale(request.nextUrl.pathname);
  const hasAccessCookie = request.cookies.has(ACCESS_TOKEN_COOKIE);

  if (!hasAccessCookie && matchesPrefix(path, PROTECTED_PREFIXES)) {
    const loginUrl = new URL(`/${locale}/login`, request.url);
    loginUrl.searchParams.set("next", `${path}${request.nextUrl.search}`);
    return NextResponse.redirect(loginUrl);
  }

  if (hasAccessCookie && matchesPrefix(path, AUTH_PAGES)) {
    return NextResponse.redirect(new URL(`/${locale}/dashboard`, request.url));
  }

  return intlMiddleware(request);
}

export const config = {
  // Match all pathnames except:
  // - /api routes (health check, future route handlers)
  // - Next.js internals (/_next, /_vercel)
  // - static files (contain a dot, e.g. favicon.ico)
  matcher: "/((?!api|_next|_vercel|.*\\..*).*)",
};

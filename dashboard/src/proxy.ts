import createMiddleware from "next-intl/middleware";
import { NextResponse, type NextRequest } from "next/server";
import { matchesPrefix, splitLocale } from "./i18n/pathname";
import { routing } from "./i18n/routing";

const intlMiddleware = createMiddleware(routing);

const ACCESS_TOKEN_COOKIE = "cmplyr_at";

// Non-secret marker set at Path=/ that outlives the short-lived access token (it
// tracks the refresh token's lifetime). The refresh token itself is path-scoped to
// /api/v1/auth, so it is never sent on a page navigation and is invisible here — the
// marker is how we tell "logged out" apart from "access expired but refreshable".
const SESSION_MARKER_COOKIE = "cmplyr_session";

/** Path prefixes (locale-stripped) of the authenticated (app) route group. */
const PROTECTED_PREFIXES = ["/dashboard", "/sites"];

/** Auth pages that bounce already-signed-in users back to the app. */
const AUTH_PAGES = ["/login", "/signup"];

/**
 * Locale routing (next-intl) plus a cheap auth gate: presence of a refreshable
 * session (a live access token, or the marker showing the refresh token is still
 * valid). Real authorization is enforced by the backend — this just avoids rendering
 * app pages that would immediately 401, while not logging out an idle-but-refreshable
 * user on a hard navigation. A page reached on the marker alone renders, then the
 * client's first API call 401s and transparently refreshes.
 */
export default function proxy(request: NextRequest) {
  const { locale, path } = splitLocale(request.nextUrl.pathname);
  const hasSession =
    request.cookies.has(ACCESS_TOKEN_COOKIE) ||
    request.cookies.has(SESSION_MARKER_COOKIE);

  if (!hasSession && matchesPrefix(path, PROTECTED_PREFIXES)) {
    const loginUrl = new URL(`/${locale}/login`, request.url);
    loginUrl.searchParams.set("next", `${path}${request.nextUrl.search}`);
    return NextResponse.redirect(loginUrl);
  }

  if (hasSession && matchesPrefix(path, AUTH_PAGES)) {
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

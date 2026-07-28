import createMiddleware from "next-intl/middleware";
import { routing } from "./i18n/routing";

export default createMiddleware(routing);

export const config = {
  // Match all pathnames except:
  // - /api routes (health check, future route handlers)
  // - Next.js internals (/_next, /_vercel)
  // - static files (contain a dot, e.g. favicon.ico)
  matcher: "/((?!api|_next|_vercel|.*\\..*).*)",
};

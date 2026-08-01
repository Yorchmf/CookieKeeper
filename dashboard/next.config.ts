import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";
import { routing } from "./src/i18n/routing";

const nextConfig: NextConfig = {
  output: "standalone",
  // Same-origin dev proxy: the client always calls relative /api/v1/* so the
  // httpOnly auth cookies are first-party everywhere. When API_PROXY_TARGET
  // is set (bare `pnpm dev` via .env.local, local compose via a build arg),
  // Next forwards those calls to the backend. NOTE: rewrites are resolved
  // when this config is evaluated — dev-server startup or `next build` — not
  // per request. Deployed dev/prd leave it unset; Caddy proxies /api/v1 there.
  async rewrites() {
    // The backend hands customers a locale-less hosted-policy URL (`/p/{publicId}`); serve it from the
    // locale-scoped route (which owns <html>/<body> + the i18n provider) without changing the address
    // bar. The `?lang=` query is preserved through the rewrite.
    const hostedPolicyRewrite = {
      source: "/p/:publicId",
      destination: `/${routing.defaultLocale}/p/:publicId`,
    };

    const target = process.env.API_PROXY_TARGET;
    if (!target) {
      return [hostedPolicyRewrite];
    }
    return [
      hostedPolicyRewrite,
      {
        source: "/api/v1/:path*",
        destination: `${target.replace(/\/$/, "")}/api/v1/:path*`,
      },
    ];
  },
};

const withNextIntl = createNextIntlPlugin();

export default withNextIntl(nextConfig);

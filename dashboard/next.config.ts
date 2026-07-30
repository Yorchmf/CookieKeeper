import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

const nextConfig: NextConfig = {
  output: "standalone",
  // Same-origin dev proxy: the client always calls relative /api/v1/* so the
  // httpOnly auth cookies are first-party everywhere. When API_PROXY_TARGET
  // is set (bare `pnpm dev` via .env.local, local compose via a build arg),
  // Next forwards those calls to the backend. NOTE: rewrites are resolved
  // when this config is evaluated — dev-server startup or `next build` — not
  // per request. Deployed dev/prd leave it unset; Caddy proxies /api/v1 there.
  async rewrites() {
    const target = process.env.API_PROXY_TARGET;
    if (!target) {
      return [];
    }
    return [
      {
        source: "/api/v1/:path*",
        destination: `${target.replace(/\/$/, "")}/api/v1/:path*`,
      },
    ];
  },
};

const withNextIntl = createNextIntlPlugin();

export default withNextIntl(nextConfig);

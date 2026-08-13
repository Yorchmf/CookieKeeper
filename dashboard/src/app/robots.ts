import type { MetadataRoute } from "next";
import { SITE_URL } from "@/lib/site";

// Authenticated app + auth flows + per-customer hosted policies have no SEO
// value and shouldn't be crawled. Patterns are locale-agnostic (`/*/segment`)
// so they match every `[locale]` prefix.
const DISALLOWED = [
  "/api/",
  "/*/dashboard",
  "/*/sites",
  "/*/billing",
  "/*/login",
  "/*/signup",
  "/*/forgot-password",
  "/*/reset-password",
  "/*/verify-email",
  "/*/p/",
];

export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      allow: "/",
      disallow: DISALLOWED,
    },
    sitemap: `${SITE_URL}/sitemap.xml`,
    host: SITE_URL,
  };
}

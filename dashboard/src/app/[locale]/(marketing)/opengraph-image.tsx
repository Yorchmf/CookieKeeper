import { ImageResponse } from "next/og";
import { getTranslations } from "next-intl/server";
import { routing } from "@/i18n/routing";

// Dynamic OG/Twitter card, generated at the edge from our own catalog — no
// external image host, no third-party fonts (GDPR-safe). One image per locale.
export const alt = "Complyr — GDPR cookie consent, done right";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

export function generateStaticParams() {
  return routing.locales.map((locale) => ({ locale }));
}

const BRAND = "#0f9488";
const INK = "#0b1220";

export default async function Image({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  const [app, t] = await Promise.all([
    getTranslations({ locale, namespace: "app" }),
    getTranslations({ locale, namespace: "marketing" }),
  ]);

  return new ImageResponse(
    (
      <div
        style={{
          height: "100%",
          width: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          background: "#ffffff",
          padding: "80px",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: "20px" }}>
          <div
            style={{
              width: 56,
              height: 56,
              borderRadius: 16,
              background: BRAND,
            }}
          />
          <div style={{ fontSize: 40, fontWeight: 700, color: INK }}>
            {app("name")}
          </div>
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: "24px" }}>
          <div
            style={{
              fontSize: 68,
              fontWeight: 700,
              color: INK,
              lineHeight: 1.1,
              maxWidth: 960,
            }}
          >
            {app("tagline")}
          </div>
          <div
            style={{
              fontSize: 32,
              color: "#475569",
              lineHeight: 1.3,
              maxWidth: 900,
            }}
          >
            {t("hero.euBadge")}
          </div>
        </div>

        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: "12px",
            fontSize: 28,
            color: BRAND,
            fontWeight: 600,
          }}
        >
          <div
            style={{
              width: 12,
              height: 12,
              borderRadius: 999,
              background: BRAND,
            }}
          />
          complyr.eu
        </div>
      </div>
    ),
    { ...size },
  );
}

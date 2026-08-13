"use client";

import { useTranslations } from "next-intl";
import type { BannerEditorState } from "@/components/banner/banner-editor-state";

/**
 * A static, non-interactive mock of the consent banner rendered from the current editor state. It
 * approximates the widget's look (theme colors, position, offered categories, per-language text) so
 * the customer sees the effect of every change without leaving the page. All copy comes from the
 * editor state itself — this component renders no hardcoded user-facing strings from the banner.
 */
export function BannerPreview({
  state,
  language,
}: {
  state: BannerEditorState;
  language: string;
}) {
  const t = useTranslations("banner.categories");
  const texts = state.texts[language] ?? state.texts[state.defaultLanguage];
  const { primaryColor, background, textColor } = state.theme;

  const alignment =
    state.position === "top"
      ? "items-start justify-center"
      : "items-end justify-center";

  return (
    <div
      className={`flex min-h-64 rounded-lg border border-dashed border-border bg-[repeating-linear-gradient(45deg,transparent,transparent_10px,color-mix(in_oklch,var(--muted),transparent_40%)_10px,color-mix(in_oklch,var(--muted),transparent_40%)_20px)] p-4 ${alignment}`}
    >
      <div
        aria-hidden="true"
        className="w-full max-w-md rounded-xl border p-4 shadow-lg"
        style={{
          background,
          color: textColor,
          borderColor: `color-mix(in oklch, ${textColor} 15%, transparent)`,
        }}
      >
        <p className="text-sm font-semibold">{texts.title}</p>
        <p className="mt-1 text-xs leading-relaxed opacity-80">
          {texts.description}
        </p>

        <div className="mt-3 flex flex-wrap gap-1.5">
          {state.offeredCategories.map((key) => (
            <span
              key={key}
              className="rounded-full border px-2 py-0.5 text-[0.65rem] font-medium"
              style={{
                borderColor: `color-mix(in oklch, ${textColor} 25%, transparent)`,
                opacity: key === "necessary" ? 1 : 0.7,
              }}
            >
              {t(`names.${key}`)}
            </span>
          ))}
        </div>

        <div className="mt-4 flex flex-wrap gap-2">
          <span
            className="rounded-md px-3 py-1.5 text-xs font-medium"
            style={{ background: primaryColor, color: contrastOn(primaryColor) }}
          >
            {texts.acceptAll}
          </span>
          <span
            className="rounded-md border px-3 py-1.5 text-xs font-medium"
            style={{
              borderColor: `color-mix(in oklch, ${textColor} 30%, transparent)`,
            }}
          >
            {texts.rejectAll}
          </span>
          <span className="px-3 py-1.5 text-xs font-medium underline underline-offset-2 opacity-70">
            {texts.preferences}
          </span>
        </div>
      </div>
    </div>
  );
}

/**
 * Picks black or white text for a hex background by relative luminance so the preview button stays
 * legible whatever primary color the customer chooses. Falls back to white for malformed input.
 */
function contrastOn(hex: string): string {
  const rgb = parseHex(hex);
  if (!rgb) {
    return "#ffffff";
  }
  const [r, g, b] = rgb.map((channel) => {
    const c = channel / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  return luminance > 0.5 ? "#000000" : "#ffffff";
}

function parseHex(hex: string): [number, number, number] | null {
  const match = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(hex.trim());
  if (!match) {
    return null;
  }
  const value = match[1];
  const full =
    value.length === 3
      ? value
          .split("")
          .map((c) => c + c)
          .join("")
      : value;
  return [
    parseInt(full.slice(0, 2), 16),
    parseInt(full.slice(2, 4), 16),
    parseInt(full.slice(4, 6), 16),
  ];
}

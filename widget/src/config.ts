/**
 * Per-site widget configuration, fetched from the CDN (edge-cached ~5 min).
 * Falls back to a safe built-in default so the banner still works if the
 * config fetch fails — the widget must never break the host page.
 */

import { CDN_BASE } from './constants';

export interface BannerTexts {
  title: string;
  message: string;
  acceptAll: string;
  rejectAll: string;
  preferences: string;
}

export interface CategoryDef {
  /** e.g. "necessary", "preferences", "statistics", "marketing" */
  id: string;
  /** Required categories cannot be rejected (always granted). */
  required: boolean;
}

export interface WidgetConfig {
  version: number;
  colors: {
    background: string;
    text: string;
    button: string;
    buttonText: string;
  };
  position: 'bottom' | 'top';
  /** BCP-47 language code → banner texts. */
  texts: Record<string, BannerTexts>;
  defaultLanguage: string;
  categories: CategoryDef[];
}

export const DEFAULT_CONFIG: WidgetConfig = {
  version: 0,
  colors: {
    background: '#1f2430',
    text: '#f5f6f8',
    button: '#4c7dff',
    buttonText: '#ffffff',
  },
  position: 'bottom',
  texts: {
    en: {
      title: 'We value your privacy',
      message:
        'We use cookies to operate this site and, with your consent, to measure usage and personalize content.',
      acceptAll: 'Accept all',
      rejectAll: 'Reject all',
      preferences: 'Preferences',
    },
  },
  defaultLanguage: 'en',
  categories: [
    { id: 'necessary', required: true },
    { id: 'preferences', required: false },
    { id: 'statistics', required: false },
    { id: 'marketing', required: false },
  ],
};

/** Fetch site config from `${CDN_BASE}/cfg/{siteKey}.json`; fall back on any failure. */
export async function fetchConfig(siteKey: string): Promise<WidgetConfig> {
  try {
    const response = await fetch(
      `${CDN_BASE}/cfg/${encodeURIComponent(siteKey)}.json`,
    );
    if (!response.ok) return DEFAULT_CONFIG;
    const config = (await response.json()) as WidgetConfig;
    return isUsableConfig(config) ? config : DEFAULT_CONFIG;
  } catch {
    return DEFAULT_CONFIG;
  }
}

/** Pick banner texts for the visitor's language, falling back sanely. */
export function resolveTexts(config: WidgetConfig, lang: string): BannerTexts {
  const short = lang.slice(0, 2).toLowerCase();
  return (
    config.texts[short] ??
    config.texts[config.defaultLanguage] ??
    DEFAULT_CONFIG.texts['en']!
  );
}

function isUsableConfig(value: unknown): value is WidgetConfig {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate['colors'] === 'object' &&
    typeof candidate['texts'] === 'object' &&
    Array.isArray(candidate['categories'])
  );
}

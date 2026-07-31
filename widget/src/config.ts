/**
 * Per-site widget configuration, fetched from the CDN (edge-cached ~5 min).
 * Falls back to a safe built-in default so the banner still works if the
 * config fetch fails — the widget must never break the host page.
 */

import { CDN_BASE } from './constants';
import { warn } from './debug';

/** Abort a stalled config fetch so a hung CDN never delays the banner. */
const CONFIG_FETCH_TIMEOUT_MS = 4000;

/** Localized label + description for one consent category in the panel. */
export interface CategoryText {
  label: string;
  description: string;
}

export interface BannerTexts {
  title: string;
  message: string;
  acceptAll: string;
  rejectAll: string;
  preferences: string;
  /** Preferences panel heading. */
  preferencesTitle: string;
  /** Confirm-choices button in the panel. */
  save: string;
  /** Close/dismiss control in the panel (pointer-operable exit). */
  close: string;
  /** Shown beside required categories that can never be switched off. */
  alwaysActive: string;
  /** Category id → localized label + description shown in the preferences panel. */
  categoryLabels: Record<string, CategoryText>;
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
      preferencesTitle: 'Privacy preferences',
      save: 'Save my choices',
      close: 'Close',
      alwaysActive: 'Always active',
      categoryLabels: {
        necessary: {
          label: 'Strictly necessary',
          description:
            'Required for the site to work. These cannot be switched off.',
        },
        preferences: {
          label: 'Preferences',
          description: 'Remember your settings and choices on this site.',
        },
        statistics: {
          label: 'Statistics',
          description:
            'Help us understand how visitors use the site, anonymously.',
        },
        marketing: {
          label: 'Marketing',
          description: 'Used to personalize ads and measure their performance.',
        },
      },
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
  const controller =
    typeof AbortController === 'function' ? new AbortController() : null;
  const timer = controller
    ? setTimeout(() => controller.abort(), CONFIG_FETCH_TIMEOUT_MS)
    : null;
  try {
    const response = await fetch(
      `${CDN_BASE}/cfg/${encodeURIComponent(siteKey)}.json`,
      controller ? { signal: controller.signal } : undefined,
    );
    if (!response.ok) {
      warn(`config fetch returned ${response.status}; using defaults`);
      return DEFAULT_CONFIG;
    }
    const config = (await response.json()) as WidgetConfig;
    if (!isUsableConfig(config)) {
      warn('config failed validation; using defaults');
      return DEFAULT_CONFIG;
    }
    // Colors are interpolated verbatim into the shadow <style>; sanitize them
    // so a hostile/malformed value can't inject arbitrary CSS rules.
    return { ...config, colors: sanitizeColors(config.colors) };
  } catch (error) {
    warn('config fetch failed; using defaults', error);
    return DEFAULT_CONFIG;
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/**
 * Color tokens allowed verbatim in a stylesheet: hex, the common CSS color
 * functions, or a bare keyword. Anything containing `;`, `{`, `}`, `url(...)`
 * etc. fails to match and is replaced with the default — closing the CSS
 * injection sink at the point untrusted config meets the `<style>` element.
 */
const SAFE_COLOR =
  /^(#[0-9a-f]{3,8}|(?:rgb|rgba|hsl|hsla|oklch|oklab|lab|lch)\([0-9a-z%.,/\s-]+\)|[a-z]+)$/i;

export function sanitizeColors(
  colors: WidgetConfig['colors'],
): WidgetConfig['colors'] {
  const fallback = DEFAULT_CONFIG.colors;
  const safe = (value: unknown, backup: string): string =>
    typeof value === 'string' && SAFE_COLOR.test(value.trim())
      ? value.trim()
      : backup;
  return {
    background: safe(colors?.background, fallback.background),
    text: safe(colors?.text, fallback.text),
    button: safe(colors?.button, fallback.button),
    buttonText: safe(colors?.buttonText, fallback.buttonText),
  };
}

/**
 * Pick banner texts for the visitor's language, merged over the English default
 * so the returned object is always complete: a per-site config (or an older
 * cached one) that omits the newer panel fields still renders, and the panel
 * never shows an empty label.
 */
export function resolveTexts(config: WidgetConfig, lang: string): BannerTexts {
  const short = lang.slice(0, 2).toLowerCase();
  const fallback = DEFAULT_CONFIG.texts['en']!;
  const chosen =
    config.texts[short] ?? config.texts[config.defaultLanguage] ?? fallback;
  return {
    ...fallback,
    ...chosen,
    categoryLabels: {
      ...fallback.categoryLabels,
      ...chosen.categoryLabels,
    },
  };
}

function isUsableConfig(value: unknown): value is WidgetConfig {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  const categories = candidate['categories'];
  return (
    typeof candidate['colors'] === 'object' &&
    candidate['colors'] !== null &&
    typeof candidate['texts'] === 'object' &&
    candidate['texts'] !== null &&
    // A config with no categories renders a banner whose buttons decide
    // nothing — treat it as unusable and fall back to the built-in default
    // rather than showing an empty, non-functional panel.
    Array.isArray(categories) &&
    categories.length > 0 &&
    categories.every(isCategoryDef)
  );
}

function isCategoryDef(value: unknown): value is CategoryDef {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate['id'] === 'string' &&
    candidate['id'].length > 0 &&
    typeof candidate['required'] === 'boolean'
  );
}

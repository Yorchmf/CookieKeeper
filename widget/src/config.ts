/**
 * Per-site widget configuration, fetched from the CDN (edge-cached ~5 min).
 * Falls back to a safe built-in default so the banner still works if the
 * config fetch fails — the widget must never break the host page.
 */

import { CDN_BASE, DEFAULT_CONSENT_LIFETIME_DAYS } from './constants';
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
  /** Attribution label shown unless the site's plan removes branding. */
  poweredBy: string;
  /** Appended (SR-only) to the attribution link, which opens a new tab (WCAG 3.2.5). */
  opensInNewTab: string;
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
  /**
   * Suppress the "Powered by Complyr" attribution in the banner. Driven by the
   * site owner's plan entitlement (paid plans only); absent/false on the free
   * tier, where the attribution shows.
   */
  removeBranding?: boolean;
  /**
   * How long a consent choice stays valid before the banner asks again, in days
   * (12 months by default; CNIL guidance is 6). Applied when a choice is stored
   * — see `writeConsent` — so it is never needed on a returning visit.
   */
  consentLifetimeDays?: number;
  /**
   * Version of what visitors are consenting TO: bumped when a consent-decidable
   * category comes newly into use on the site (a marketing tracker added in
   * March), never by a colour or copy edit. Stamped into the cookie at the moment
   * of choice; a returning visitor carrying a lower stamp is asked again.
   *
   * Absent from [DEFAULT_CONFIG] on purpose — see `ConsentState.bv`.
   */
  consentBasisVersion?: number;
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
      poweredBy: 'Powered by Complyr',
      opensInNewTab: '(opens in a new tab)',
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
  consentLifetimeDays: DEFAULT_CONSENT_LIFETIME_DAYS,
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
    // so a hostile/malformed value can't inject arbitrary CSS rules. Coerce
    // removeBranding to a strict boolean so only a literal `true` suppresses the
    // attribution — a malformed truthy value (e.g. "false", 1) can't silently
    // remove branding a plan hasn't paid for.
    return {
      ...config,
      colors: sanitizeColors(config.colors),
      removeBranding: config.removeBranding === true,
    };
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
 * The language the notice is actually *shown* in — which is not always the one the
 * browser asked for, since a site only publishes copy for the languages it offers.
 *
 * One resolution, three consumers: the texts below, the `lang` attribute on both
 * dialogs (WCAG 3.1.2 — declaring `fr` while displaying English copy is worse than
 * declaring nothing), and the language stamped on the consent event, where the
 * audit question is "what did this visitor read", not "what did they prefer".
 */
export function resolveLanguage(config: WidgetConfig, lang: string): string {
  const short = lang.slice(0, 2).toLowerCase();
  if (config.texts[short]) return short;
  if (config.texts[config.defaultLanguage]) return config.defaultLanguage;
  return 'en';
}

/**
 * Pick banner texts for the visitor's language, merged over the English default
 * so the returned object is always complete: a per-site config (or an older
 * cached one) that omits the newer panel fields still renders, and the panel
 * never shows an empty label.
 */
export function resolveTexts(config: WidgetConfig, lang: string): BannerTexts {
  const fallback = DEFAULT_CONFIG.texts['en']!;
  const chosen = config.texts[resolveLanguage(config, lang)] ?? fallback;
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

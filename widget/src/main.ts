/**
 * Widget entry point. Bundled by Vite as a single IIFE → dist/v1.js.
 *
 * Order matters (CLAUDE.md hard constraint #5): Consent Mode v2 defaults are
 * pushed synchronously at the top of the IIFE, BEFORE any async work (config
 * fetch, banner render) and therefore before vendor tags can consume consent.
 */

import { sendConsentEvent } from './api';
import { removeBanner, renderBanner, type BannerAction } from './banner';
import {
  setConsentDefaults,
  updateConsent,
  type ConsentDecision,
} from './consent-mode';
import { fetchConfig, resolveTexts, type WidgetConfig } from './config';
import {
  getOrCreateVid,
  readConsent,
  writeConsent,
  type ConsentState,
} from './storage';

interface ComplyrApi {
  /** Reopen the banner ("withdraw consent as easily as given"). */
  show: () => void;
  /** Read the visitor's current consent state (null = no choice yet). */
  consent: () => ConsentState | null;
}

declare global {
  interface Window {
    Complyr?: ComplyrApi;
  }
}

// 1. Consent Mode defaults — synchronous, before anything else.
setConsentDefaults();

// 2. Site key from our own <script data-complyr="pk_…"> tag. currentScript is
//    only reliable during synchronous top-level execution, so read it now.
const siteKey = readSiteKey();

// 3. Public API, available even while config is still loading.
window.Complyr = {
  show: () => {
    void loadAndShowBanner();
  },
  consent: () => readConsent(),
};

// 4. Apply a stored choice or show the banner. Errors fail silent-safe.
void init();

function readSiteKey(): string | null {
  const own = document.currentScript;
  const tagged =
    own?.getAttribute('data-complyr') ??
    document
      .querySelector('script[data-complyr]')
      ?.getAttribute('data-complyr');
  return tagged ?? null;
}

async function init(): Promise<void> {
  const stored = readConsent();
  if (stored) {
    updateConsent(stored.categories);
    return;
  }
  await loadAndShowBanner();
}

async function loadAndShowBanner(): Promise<void> {
  if (!siteKey) return; // Misconfigured embed — do nothing, never break the page.

  const config = await fetchConfig(siteKey);
  const lang = navigator.language || config.defaultLanguage;
  renderBanner(config, resolveTexts(config, lang), {
    onAction: (action) => applyChoice(config, action, lang),
    onPreferences: () => {
      // Preferences panel ships in the widget-core milestone; until then the
      // button is a visible placeholder and simply keeps the banner open.
    },
  });
}

function applyChoice(
  config: WidgetConfig,
  action: BannerAction,
  lang: string,
): void {
  const granted = action === 'accept_all';
  const categories: ConsentDecision = {};
  for (const category of config.categories) {
    categories[category.id] = category.required || granted;
  }

  const vid = getOrCreateVid();
  writeConsent(categories, vid);
  updateConsent(categories);
  removeBanner();

  if (siteKey) {
    sendConsentEvent({
      siteKey,
      action,
      categories,
      lang,
      ts: Date.now(),
      vid,
    });
  }
}

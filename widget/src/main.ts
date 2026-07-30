/**
 * Widget entry point. Bundled by Vite as a single IIFE → dist/v1.js.
 *
 * Order matters (CLAUDE.md hard constraint #5): Consent Mode v2 defaults are
 * pushed synchronously at the top of the IIFE, BEFORE any async work (config
 * fetch, banner render) and therefore before vendor tags can consume consent.
 */

import { sendConsentEvent, type ConsentEventPayload } from './api';
import { removeBanner, renderBanner, type BannerAction } from './banner';
import {
  setConsentDefaults,
  updateConsent,
  type ConsentDecision,
} from './consent-mode';
import { fetchConfig, resolveTexts, type WidgetConfig } from './config';
import {
  isPreferencesOpen,
  removePreferences,
  renderPreferences,
} from './preferences';
import { grantedCategories, unblockScripts } from './script-blocking';
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
    // Returning visitor: re-signal consent and run the tags they already allowed,
    // before any banner work — no page render is ever blocked on this.
    updateConsent(stored.categories);
    unblockScripts(grantedCategories(stored.categories));
    return;
  }
  await loadAndShowBanner();
}

async function loadAndShowBanner(): Promise<void> {
  if (!siteKey) return; // Misconfigured embed — do nothing, never break the page.
  // Never re-render the banner underneath an open preferences modal — that
  // would mount an interactive surface behind the inert background barrier.
  if (isPreferencesOpen()) return;

  const config = await fetchConfig(siteKey);
  const lang = navigator.language || config.defaultLanguage;
  renderBanner(config, resolveTexts(config, lang), {
    onAction: (action) => applyChoice(config, action, lang),
    onPreferences: () => openPreferences(config, lang),
  });
}

/** Open the granular preferences panel, seeded with any prior choice. */
function openPreferences(config: WidgetConfig, lang: string): void {
  const current = readConsent()?.categories ?? {};
  renderPreferences(config, resolveTexts(config, lang), lang, current, {
    onSave: (categories) => commit(categories, 'custom', lang),
    onCancel: () => removePreferences(),
  });
}

/** Whole-banner accept/reject → a full category decision, then commit. */
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
  commit(categories, action, lang);
}

/**
 * Persist and enact a consent decision from any surface (banner or panel):
 * store it, signal Consent Mode, run the now-allowed scripts, tear down the UI,
 * and record the audit event. Shared so every path stays consistent.
 */
function commit(
  categories: ConsentDecision,
  action: ConsentEventPayload['action'],
  lang: string,
): void {
  const vid = getOrCreateVid();
  writeConsent(categories, vid);
  updateConsent(categories);
  unblockScripts(grantedCategories(categories));
  removePreferences();
  removeBanner();

  if (siteKey) {
    sendConsentEvent({ siteKey, action, categories, lang, ts: Date.now(), vid });
  }
}

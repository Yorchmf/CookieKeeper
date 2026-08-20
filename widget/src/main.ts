/**
 * Widget entry point. Bundled by Vite as a single IIFE → dist/v1.js.
 *
 * Order matters (CLAUDE.md hard constraint #5): Consent Mode v2 defaults are
 * pushed synchronously at the top of the IIFE, BEFORE any async work (config
 * fetch, banner render) and therefore before vendor tags can consume consent.
 */

import {
  flushPendingEvents,
  sendConsentEvent,
  sendImpression,
  type ConsentEventPayload,
} from './api';
import { removeBanner, renderBanner, type BannerAction } from './banner';
import { setDebug, warn } from './debug';
import {
  setConsentDefaults,
  updateConsent,
  type ConsentDecision,
} from './consent-mode';
import { fetchConfig, resolveTexts, type WidgetConfig } from './config';
import { fetchOriginToken } from './origin-token';
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
import { uuidv7 } from './uuid';

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

// currentScript is only reliable during synchronous top-level execution, so
// read our own <script> tag's attributes now, before any async work.
const ownScript = readOwnScript();

// Impressions are counted at most once per page load: Complyr.show() can render
// the banner repeatedly (a visitor reopening it to withdraw consent), but that
// is one banner *appearance*, not N. The interaction-rate denominator must stay
// comparable to unique page loads that saw the banner, so guard the beacon here.
let impressionSent = false;

// 1. Diagnostics opt-in (silent by default) — set before anything can warn().
setDebug(readDebugFlag(ownScript));

// 2. Consent Mode defaults — synchronous, before anything else.
setConsentDefaults({ urlPassthrough: readUrlPassthroughFlag(ownScript) });

// 3. Site key from our own <script data-complyr="pk_…"> tag.
const siteKey = ownScript?.getAttribute('data-complyr') ?? null;

// 4. Public API, available even while config is still loading.
window.Complyr = {
  show: () => {
    void loadAndShowBanner().catch((error: unknown) => {
      warn('Complyr.show() failed', error);
    });
  },
  consent: () => readConsent(),
};

// 5. Apply a stored choice or show the banner. Errors fail silent-safe.
void init().catch((error: unknown) => {
  warn('init failed', error);
});

function readOwnScript(): HTMLScriptElement | null {
  const own = document.currentScript;
  if (own instanceof HTMLScriptElement && own.hasAttribute('data-complyr')) {
    return own;
  }
  return document.querySelector<HTMLScriptElement>('script[data-complyr]');
}

/** Enable diagnostics via `data-complyr-debug` on the embed or `window.__complyrDebug`. */
function readDebugFlag(script: HTMLScriptElement | null): boolean {
  return (
    script?.hasAttribute('data-complyr-debug') === true ||
    (window as { __complyrDebug?: boolean }).__complyrDebug === true
  );
}

/**
 * Opt in to Consent Mode's `url_passthrough` via `data-complyr-url-passthrough`
 * on the embed. Off unless the site owner adds it: it rewrites the host page's
 * own internal links to carry the ad-click id forward, which is their call to
 * make — see ConsentModeOptions.
 */
function readUrlPassthroughFlag(script: HTMLScriptElement | null): boolean {
  return script?.hasAttribute('data-complyr-url-passthrough') === true;
}

async function init(): Promise<void> {
  // Retry any consent events a previous visit failed to deliver — audit
  // evidence must not be lost to a transient network blip.
  flushPendingEvents();

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
  if (!siteKey) {
    // Misconfigured embed — do nothing, never break the page.
    warn('no data-complyr site key found on the embed script; banner skipped');
    return;
  }
  // Never re-render the banner underneath an open preferences modal — that
  // would mount an interactive surface behind the inert background barrier.
  if (isPreferencesOpen()) return;

  // Mint an origin token (ADR-13) in the background while the banner loads, so a
  // fresh one is on hand by the time the visitor clicks. Fire-and-forget: it
  // never blocks the banner and never throws; if it doesn't arrive the consent
  // POST simply goes out tokenless (and still records).
  void fetchOriginToken(siteKey);

  const config = await fetchConfig(siteKey);
  const lang = navigator.language || config.defaultLanguage;
  renderBanner(config, resolveTexts(config, lang), {
    onAction: (action) => applyChoice(config, action, lang),
    onPreferences: () => openPreferences(config, lang),
  });

  // Count the banner appearance once it has actually rendered, at most once per
  // page load. Fire-and-forget: a dropped beacon only under-counts the
  // interaction-rate denominator and never blocks the visitor.
  if (!impressionSent) {
    impressionSent = true;
    sendImpression(siteKey);
  }
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
 * store it, RECORD the audit event, then signal Consent Mode, run the
 * now-allowed scripts, and tear down the UI. Shared so every path stays
 * consistent.
 *
 * Order matters: the cookie and the audit event are written first, before any
 * enactment step that could throw (updateConsent / unblockScripts). A visitor's
 * recorded choice must never be lost just because a downstream vendor tag or a
 * malformed placeholder blew up while we were applying it.
 */
function commit(
  categories: ConsentDecision,
  action: ConsentEventPayload['action'],
  lang: string,
): void {
  const vid = getOrCreateVid();
  writeConsent(categories, vid);

  // Audit evidence first — this is the compliance-critical write. eventKey is
  // minted here, once per decision, and rides inside the payload — so every
  // localStorage-queued retry replays the SAME key and the backend records the
  // event exactly once.
  if (siteKey) {
    sendConsentEvent({ siteKey, action, categories, lang, eventKey: uuidv7(), vid });
  }

  // Enactment second. unblockScripts already isolates per-tag failures; guard
  // the rest so a throw here can't strand the banner/panel on screen.
  try {
    updateConsent(categories);
    unblockScripts(grantedCategories(categories));
  } catch (error) {
    warn('enacting consent failed after it was recorded', error);
  } finally {
    removePreferences();
    removeBanner();
  }
}

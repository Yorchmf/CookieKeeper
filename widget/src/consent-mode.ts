/**
 * Google Consent Mode v2 helpers.
 *
 * Defaults MUST be pushed synchronously, before any vendor script runs
 * (CLAUDE.md hard constraint #5) — main.ts calls setConsentDefaults() first.
 */

export type ConsentDecision = Record<string, boolean>;

declare global {
  interface Window {
    dataLayer?: unknown[];
  }
}

/** Per-embed Consent Mode options, read off the `<script>` tag in main.ts. */
export interface ConsentModeOptions {
  /**
   * Opt in to Google's `url_passthrough`. OFF unless the site owner asks for it:
   * it appends the ad-click id the visitor already arrived with (`gclid`,
   * `gbraid`, …) to internal links, so a conversion stays attributable while
   * `ad_storage` is denied. That is a measurement feature with a visible side
   * effect on the host page's own URLs — the site owner's decision to make, not
   * a default we make for them.
   */
  urlPassthrough?: boolean;
}

/**
 * Minimal gtag shim. Google's snippet pushes the `arguments` object (not an
 * array) onto dataLayer — GTM relies on that, so we do the same.
 */
function gtag(..._args: unknown[]): void {
  window.dataLayer = window.dataLayer || [];
  window.dataLayer.push(arguments);
}

/** How long tags hold off firing while the banner applies a stored choice. */
const WAIT_FOR_UPDATE_MS = 500;

/**
 * All seven Consent Mode v2 signals, derived from one Complyr category decision.
 * Pass `{}` for the pre-choice defaults — every signal a visitor can decide is
 * then denied, which is the whole point of the default push.
 *
 * The mapping is deliberately conservative: a signal is granted only when the
 * category it belongs to was actively accepted. A site whose banner does not
 * OFFER a category (say, no `preferences`) leaves that signal denied forever —
 * the visitor was never asked, so there is no consent to infer. The fix for a
 * site that needs functionality storage is to add the category to its banner,
 * not for us to grant on the visitor's behalf. This is also why every signal is
 * always sent: omitting one makes Google assume it is granted.
 */
function signalsFor(categories: ConsentDecision): Record<string, string> {
  const state = (granted: boolean): string => (granted ? 'granted' : 'denied');
  const marketing = categories['marketing'] === true;
  const preferences = categories['preferences'] === true;
  return {
    ad_storage: state(marketing),
    ad_user_data: state(marketing),
    ad_personalization: state(marketing),
    analytics_storage: state(categories['statistics'] === true),
    // Functionality (language, layout) and personalization (recommendations)
    // are both "remember my choices" storage — the `preferences` category.
    functionality_storage: state(preferences),
    personalization_storage: state(preferences),
    // Maps to the `necessary` category, which is locked on and cannot be
    // rejected, so anti-fraud/security storage is granted from the first paint.
    security_storage: 'granted',
  };
}

/**
 * Push Consent Mode v2 defaults: everything the visitor can decide is denied
 * until they choose, plus the two `set` directives that shape what Google's tags
 * may send while consent is missing.
 */
export function setConsentDefaults(options: ConsentModeOptions = {}): void {
  gtag('consent', 'default', {
    ...signalsFor({}),
    // Give the banner a short window to apply a stored choice before tags fire.
    wait_for_update: WAIT_FOR_UPDATE_MS,
  });
  // Purely privacy-enhancing and therefore unconditional here: with ad_storage
  // denied, it strips ad-click identifiers out of the requests Google Ads and
  // Floodlight tags make. updateConsent() lifts it only once marketing is
  // actually granted.
  gtag('set', 'ads_data_redaction', true);
  if (options.urlPassthrough === true) {
    gtag('set', 'url_passthrough', true);
  }
}

/** Map Complyr categories to Consent Mode signals and push an update. */
export function updateConsent(categories: ConsentDecision): void {
  gtag('consent', 'update', signalsFor(categories));
  // Keep redaction tied to the ad signals it protects: on while marketing is
  // denied, off once the visitor has granted it.
  gtag('set', 'ads_data_redaction', categories['marketing'] !== true);
}

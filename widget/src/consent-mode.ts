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
  /**
   * Scope the denied defaults to [GDPR_REGIONS] and grant everywhere else. OFF
   * unless the site owner adds `data-complyr-regions="gdpr"`, because it is only
   * coherent alongside the matching banner gate: granting by default for a
   * visitor we then show a consent banner to would contradict the banner.
   */
  gdprRegionsOnly?: boolean;
}

/**
 * The countries whose visitors must be asked before anything non-essential is
 * stored: the EU 27 (including the outermost regions Google lists separately),
 * the rest of the EEA, the UK plus the Crown Dependencies and Gibraltar, and
 * Switzerland. Fed to Consent Mode's `region` parameter, so Google — not us —
 * does the geolocation: no IP ever reaches this code, and no round trip delays
 * the default push.
 *
 * The list errs wide on purpose. Being on it costs a visitor a banner they may
 * not have strictly needed; being off it wrongly means tags run before consent.
 * Kept in step with `GdprRegions.IN_SCOPE_COUNTRIES` on the backend, which
 * classifies the same countries for the banner half of the same flag — change
 * one, change the other.
 */
export const GDPR_REGIONS: readonly string[] = (
  'AT BE BG HR CY CZ DK EE FI FR DE GR HU IE IT LV LT LU MT NL PL PT RO SK SI ES SE ' +
  'AX GF GP MQ RE YT MF ' +
  'IS LI NO SJ ' +
  'GB GG JE IM GI ' +
  'CH'
).split(' ');

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
 * The decision behind the out-of-region fallback default. Written as a decision
 * rather than a literal signal map so it can never drift out of step with
 * [signalsFor] — every signal that exists is granted, by construction.
 */
const GRANT_EVERYTHING: ConsentDecision = {
  marketing: true,
  statistics: true,
  preferences: true,
};

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
 *
 * With `gdprRegionsOnly` the denial is pushed FIRST scoped to [GDPR_REGIONS] and
 * a granting fallback second — Google applies the most specific match, and the
 * order is the one Google's own documentation prescribes. Without it (the
 * default) a single, unscoped denial covers every visitor on earth.
 */
export function setConsentDefaults(options: ConsentModeOptions = {}): void {
  const denied = {
    ...signalsFor({}),
    // Give the banner a short window to apply a stored choice before tags fire.
    wait_for_update: WAIT_FOR_UPDATE_MS,
  };
  if (options.gdprRegionsOnly === true) {
    gtag('consent', 'default', { ...denied, region: GDPR_REGIONS });
    // Everyone else: no banner is coming, so nothing will ever update these —
    // and no wait_for_update, since there is nothing to wait for.
    gtag('consent', 'default', signalsFor(GRANT_EVERYTHING));
  } else {
    gtag('consent', 'default', denied);
  }
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

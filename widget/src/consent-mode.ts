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

/**
 * Minimal gtag shim. Google's snippet pushes the `arguments` object (not an
 * array) onto dataLayer — GTM relies on that, so we do the same.
 */
function gtag(..._args: unknown[]): void {
  window.dataLayer = window.dataLayer || [];
  window.dataLayer.push(arguments);
}

/** Push Consent Mode v2 defaults: everything denied until the visitor chooses. */
export function setConsentDefaults(): void {
  gtag('consent', 'default', {
    ad_storage: 'denied',
    analytics_storage: 'denied',
    ad_user_data: 'denied',
    ad_personalization: 'denied',
    // Give the banner a short window to apply a stored choice before tags fire.
    wait_for_update: 500,
  });
}

/** Map Complyr categories to Consent Mode signals and push an update. */
export function updateConsent(categories: ConsentDecision): void {
  const analytics = categories['statistics'] === true;
  const marketing = categories['marketing'] === true;
  gtag('consent', 'update', {
    ad_storage: marketing ? 'granted' : 'denied',
    ad_user_data: marketing ? 'granted' : 'denied',
    ad_personalization: marketing ? 'granted' : 'denied',
    analytics_storage: analytics ? 'granted' : 'denied',
  });
}

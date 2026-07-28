/**
 * Base URLs for the widget's two network touchpoints (see ARCHITECTURE.md §3).
 *
 * Derived from our own <script src> origin so the SAME bundle works on every
 * environment: cdn.complyr.eu → api.complyr.eu, cdn.dev.complyr.eu →
 * api.dev.complyr.eu, localhost (Vite dev harness) → same origin. Falls back
 * to production for exotic embeds (inline scripts, self-hosting).
 */

export interface ResolvedBases {
  cdn: string;
  api: string;
}

const PROD_BASES: ResolvedBases = {
  cdn: 'https://cdn.complyr.eu',
  api: 'https://api.complyr.eu',
};

/** Pure resolver — exported for tests. */
export function resolveBases(
  scriptSrc: string | null | undefined,
): ResolvedBases {
  if (!scriptSrc) return PROD_BASES;
  try {
    const url = new URL(scriptSrc);
    if (url.hostname === 'localhost' || url.hostname === '127.0.0.1') {
      return { cdn: url.origin, api: url.origin };
    }
    if (url.host.startsWith('cdn.')) {
      return {
        cdn: url.origin,
        api: `${url.protocol}//api.${url.host.slice('cdn.'.length)}`,
      };
    }
    return PROD_BASES;
  } catch {
    return PROD_BASES;
  }
}

// document.currentScript is only reliable during synchronous top-level
// execution — this module is evaluated at IIFE start, so it is.
const ownScript = document.currentScript;
const bases = resolveBases(
  ownScript instanceof HTMLScriptElement ? ownScript.src : null,
);

export const CDN_BASE = bases.cdn;
export const API_BASE = bases.api;

/** Consent cookie schema version — bump when the cookie payload shape changes. */
export const COOKIE_SCHEMA_VERSION = 1;

/** 12-month consent expiry, per GDPR guidance and ARCHITECTURE.md §4.3. */
export const COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365;

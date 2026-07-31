/**
 * Base URLs for the widget's two network touchpoints (see ARCHITECTURE.md §3).
 *
 * Derived from our own <script src> origin so the SAME bundle works on every
 * environment: cdn.complyr.eu → api.complyr.eu, cdn.dev.complyr.eu →
 * api.dev.complyr.eu, localhost (Vite dev harness) → same origin. Falls back
 * to production for exotic embeds (inline scripts, self-hosting).
 *
 * Dev-only escape hatch: `data-complyr-api` / `data-complyr-cdn` on the embed
 * script override the derived bases — but ONLY when the override resolves to a
 * loopback origin (localhost / 127.0.0.1 / [::1]). This lets `pnpm dev` point
 * the widget at a local backend on another port, while a production embed can
 * never be redirected to an arbitrary (exfiltration) host.
 */

export interface ResolvedBases {
  cdn: string;
  api: string;
}

export interface BaseOverrides {
  api?: string | null;
  cdn?: string | null;
}

const PROD_BASES: ResolvedBases = {
  cdn: 'https://cdn.complyr.eu',
  api: 'https://api.complyr.eu',
};

const LOOPBACK_HOSTS: ReadonlySet<string> = new Set([
  'localhost',
  '127.0.0.1',
  '[::1]',
]);

/**
 * Accept an override only if it parses to a loopback origin; returns that
 * origin, or null when the value is absent/malformed/non-loopback (so the
 * caller keeps the safe derived base).
 */
function loopbackOverride(value: string | null | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    return LOOPBACK_HOSTS.has(url.hostname) ? url.origin : null;
  } catch {
    return null;
  }
}

/** Pure resolver — exported for tests. */
export function resolveBases(
  scriptSrc: string | null | undefined,
  overrides?: BaseOverrides,
): ResolvedBases {
  const derived = deriveBases(scriptSrc);
  return {
    cdn: loopbackOverride(overrides?.cdn) ?? derived.cdn,
    api: loopbackOverride(overrides?.api) ?? derived.api,
  };
}

function deriveBases(scriptSrc: string | null | undefined): ResolvedBases {
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

// Prefer document.currentScript (reliable for the classic IIFE bundle in prod),
// but fall back to the tagged embed — currentScript is null for the module
// script used in the Vite dev harness (`<script type="module">`).
const ownScript =
  document.currentScript instanceof HTMLScriptElement &&
  document.currentScript.hasAttribute('data-complyr')
    ? document.currentScript
    : document.querySelector<HTMLScriptElement>('script[data-complyr]');
const bases = resolveBases(ownScript?.src ?? null, {
  api: ownScript?.getAttribute('data-complyr-api') ?? null,
  cdn: ownScript?.getAttribute('data-complyr-cdn') ?? null,
});

export const CDN_BASE = bases.cdn;
export const API_BASE = bases.api;

/**
 * Consent cookie schema version — bump when the cookie payload shape changes.
 * v2 added `vid`, a stable per-browser id sent with each consent event so a
 * visitor's audit history correlates without storing any reversible identifier.
 * v1 cookies (no `vid`) are still honored on read; the vid is minted on the
 * next choice.
 */
export const COOKIE_SCHEMA_VERSION = 2;

/** 12-month consent expiry, per GDPR guidance and ARCHITECTURE.md §4.3. */
export const COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365;

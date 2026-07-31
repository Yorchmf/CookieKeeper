/**
 * First-party consent cookie `cmplyr`.
 * JSON payload: { version, categories, ts, vid } — 12-month expiry, SameSite=Lax.
 *
 * `vid` is a random per-browser UUID (schema v2). It lives in this necessary,
 * first-party cookie and is sent with every consent event so a visitor's audit
 * history correlates server-side, while the stored IP stays irreversibly hashed.
 */

import { COOKIE_MAX_AGE_SECONDS, COOKIE_SCHEMA_VERSION } from './constants';
import type { ConsentDecision } from './consent-mode';
import { warn } from './debug';
import { randomBytes16, toUuidString } from './uuid';

export const COOKIE_NAME = 'cmplyr';

export interface ConsentState {
  /** Cookie schema version. */
  version: number;
  /** Category id → granted. */
  categories: ConsentDecision;
  /** Unix ms timestamp of the choice. */
  ts: number;
  /** Stable per-browser id (UUID). Absent in v1 cookies. */
  vid?: string;
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Read and validate the consent cookie. Returns null when absent or invalid. */
export function readConsent(): ConsentState | null {
  const raw = document.cookie
    .split('; ')
    .find((part) => part.startsWith(`${COOKIE_NAME}=`));
  if (!raw) return null;

  try {
    const parsed: unknown = JSON.parse(
      decodeURIComponent(raw.slice(COOKIE_NAME.length + 1)),
    );
    return isConsentState(parsed) ? parsed : null;
  } catch {
    // Corrupt cookie — treat as "no consent yet"; never throw on the host page.
    return null;
  }
}

/**
 * Return the visitor's existing vid, or mint a fresh UUID when there is none
 * (first visit, corrupt cookie, or a v1 cookie predating `vid`).
 */
export function getOrCreateVid(): string {
  const existing = readConsent()?.vid;
  return existing && UUID_PATTERN.test(existing) ? existing : generateVid();
}

/** Persist a consent choice keyed to [vid]; returns the stored state. */
export function writeConsent(
  categories: ConsentDecision,
  vid: string,
): ConsentState {
  const state: ConsentState = {
    version: COOKIE_SCHEMA_VERSION,
    categories,
    ts: Date.now(),
    vid,
  };
  const value = encodeURIComponent(JSON.stringify(state));
  // Secure only on https so the local dev harness (http) keeps working.
  const secure = location.protocol === 'https:' ? '; Secure' : '';
  document.cookie =
    `${COOKIE_NAME}=${value}; Max-Age=${COOKIE_MAX_AGE_SECONDS}; Path=/; SameSite=Lax${secure}`;
  // Read back: if cookies are disabled/blocked the write silently no-ops, and
  // the choice would be lost on the next page load. We can't force persistence,
  // but the consent event still records the decision — surface it for debugging.
  if (!document.cookie.includes(`${COOKIE_NAME}=`)) {
    warn('consent cookie was not persisted (cookies blocked?)');
  }
  return state;
}

/**
 * RFC 4122 v4 UUID — native `crypto.randomUUID()` when available, else derived
 * from shared CSPRNG bytes. vid is a stable correlation id, so v4 (fully random)
 * is the right shape here; the time-ordered v7 in [uuid.ts] is only for the
 * index-hot server-side keys.
 */
function generateVid(): string {
  const cryptoObj = globalThis.crypto;
  if (cryptoObj && typeof cryptoObj.randomUUID === 'function') {
    return cryptoObj.randomUUID();
  }
  const bytes = randomBytes16();
  bytes[6] = (bytes[6]! & 0x0f) | 0x40; // version 4
  bytes[8] = (bytes[8]! & 0x3f) | 0x80; // variant 10xx
  return toUuidString(bytes);
}

function isConsentState(value: unknown): value is ConsentState {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  const vid = candidate['vid'];
  return (
    typeof candidate['version'] === 'number' &&
    typeof candidate['ts'] === 'number' &&
    typeof candidate['categories'] === 'object' &&
    candidate['categories'] !== null &&
    (vid === undefined || typeof vid === 'string')
  );
}

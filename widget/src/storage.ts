/**
 * First-party consent cookie `cmplyr`.
 * JSON payload: { version, categories, ts } — 12-month expiry, SameSite=Lax.
 */

import { COOKIE_MAX_AGE_SECONDS, COOKIE_SCHEMA_VERSION } from './constants';
import type { ConsentDecision } from './consent-mode';

export const COOKIE_NAME = 'cmplyr';

export interface ConsentState {
  /** Cookie schema version. */
  version: number;
  /** Category id → granted. */
  categories: ConsentDecision;
  /** Unix ms timestamp of the choice. */
  ts: number;
}

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

/** Persist a consent choice; returns the stored state. */
export function writeConsent(categories: ConsentDecision): ConsentState {
  const state: ConsentState = {
    version: COOKIE_SCHEMA_VERSION,
    categories,
    ts: Date.now(),
  };
  const value = encodeURIComponent(JSON.stringify(state));
  // Secure only on https so the local dev harness (http) keeps working.
  const secure = location.protocol === 'https:' ? '; Secure' : '';
  document.cookie =
    `${COOKIE_NAME}=${value}; Max-Age=${COOKIE_MAX_AGE_SECONDS}; Path=/; SameSite=Lax${secure}`;
  return state;
}

function isConsentState(value: unknown): value is ConsentState {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate['version'] === 'number' &&
    typeof candidate['ts'] === 'number' &&
    typeof candidate['categories'] === 'object' &&
    candidate['categories'] !== null
  );
}

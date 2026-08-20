/**
 * First-party consent cookie `cmplyr`.
 * JSON payload: { version, categories, ts, vid, exp, bv } — SameSite=Lax, expiring
 * after the site's configured consent lifetime (12 months by default).
 *
 * `vid` is a random per-browser UUID (schema v2). It lives in this necessary,
 * first-party cookie and is sent with every consent event so a visitor's audit
 * history correlates server-side, while the stored IP stays irreversibly hashed.
 *
 * `exp` (schema v3) is the moment the choice stops counting, stamped at write
 * time from the config in force when the visitor chose. Stamping it — rather
 * than leaving expiry to the cookie's own `Max-Age` — makes the deadline a
 * property of the recorded decision, so a cookie that outlives its window (a
 * restored browser profile, a jar synced from another device) still re-prompts.
 * It is also what keeps a returning visitor's tags running without a config
 * fetch: the widget can tell a live choice from a stale one offline.
 *
 * `bv` (schema v4) is the site's consent-basis version — a count of how many
 * times a consent-decidable category came NEWLY into use on the site, bumped by
 * the scanner, never by a banner edit. A visitor whose stamp is lower than the
 * site's current basis consented to a shorter list of purposes than the site now
 * uses, so they are asked again. Unlike `exp`, this cannot be judged offline: it
 * is a property of the site, not of the decision, so the check happens AFTER the
 * stored choice has already been enacted (see `askAgainIfStale` in main.ts).
 */

import {
  COOKIE_SCHEMA_VERSION,
  DEFAULT_CONSENT_LIFETIME_DAYS,
  MAX_CONSENT_LIFETIME_DAYS,
  SECONDS_PER_DAY,
} from './constants';
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
  /** Unix ms after which the choice is stale. Absent in v1/v2 cookies. */
  exp?: number;
  /**
   * Site consent-basis version the choice was made against. Absent in pre-v4
   * cookies, and absent when the choice was made against the widget's built-in
   * fallback config (a config fetch that failed knows no basis, and inventing
   * one would re-prompt a visitor over our own network error).
   */
  bv?: number;
}

/** Everything about a write that comes from the site's config rather than the visitor. */
export interface ConsentWriteOptions {
  /** Site's configured consent lifetime in days; see [resolveLifetimeDays]. */
  lifetimeDays?: number | undefined;
  /** Site's current consent-basis version; omitted when unknown (see [ConsentState.bv]). */
  basisVersion?: number | undefined;
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Read and validate the consent cookie. Returns null when absent, invalid, or
 * past its expiry — an expired choice is "no consent yet", which is what makes
 * the banner come back and ask again.
 */
export function readConsent(): ConsentState | null {
  const stored = readStoredConsent();
  if (!stored || isExpired(stored)) return null;
  return stored;
}

/** Parse the cookie without judging its age. Returns null when absent or invalid. */
function readStoredConsent(): ConsentState | null {
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
 * Whether a stored choice has outlived its window. A v1/v2 cookie carries no
 * `exp`, so it is measured against the 12 months it was written with — the
 * behaviour those visitors already have, not a retroactive re-prompt.
 */
function isExpired(state: ConsentState): boolean {
  const expiresAt =
    state.exp ?? state.ts + DEFAULT_CONSENT_LIFETIME_DAYS * SECONDS_PER_DAY * 1000;
  return Date.now() >= expiresAt;
}

/**
 * Clamp a configured lifetime to something a cookie can actually express:
 * a whole number of days, at least one, at most [MAX_CONSENT_LIFETIME_DAYS].
 * Anything else (missing, NaN, negative, absurd) falls back to the default —
 * a bad config must never silently shorten or extend a visitor's consent.
 */
export function resolveLifetimeDays(days: number | undefined): number {
  if (typeof days !== 'number' || !Number.isFinite(days)) {
    return DEFAULT_CONSENT_LIFETIME_DAYS;
  }
  const whole = Math.floor(days);
  if (whole < 1) return DEFAULT_CONSENT_LIFETIME_DAYS;
  return Math.min(whole, MAX_CONSENT_LIFETIME_DAYS);
}

/**
 * A basis version worth stamping: a whole number of at least 1 (the value every
 * site starts at). Anything else — missing, NaN, zero, negative, fractional —
 * yields null, meaning "unknown", and the cookie carries no `bv` at all.
 */
export function resolveBasisVersion(version: number | undefined): number | null {
  if (typeof version !== 'number' || !Number.isFinite(version)) return null;
  const whole = Math.floor(version);
  return whole >= 1 ? whole : null;
}

/**
 * Whether a stored choice was made against an older list of purposes than the
 * site uses now. Unknown on either side (a pre-v4 cookie, or a config that
 * carries no basis) is NOT stale: only a strict increase between two known
 * versions means the visitor was asked a narrower question than we would ask
 * today. `current` is deliberately the caller's raw config value, so the same
 * validation applies to both sides.
 */
export function isBasisStale(
  state: ConsentState,
  current: number | undefined,
): boolean {
  const stamped = resolveBasisVersion(state.bv);
  const site = resolveBasisVersion(current);
  if (stamped === null || site === null) return false;
  return site > stamped;
}

/**
 * Return the visitor's existing vid, or mint a fresh UUID when there is none
 * (first visit, corrupt cookie, or a v1 cookie predating `vid`).
 *
 * Reads past the expiry check on purpose: renewing an expired choice is the same
 * visitor answering again, and their audit history should stay one thread rather
 * than splitting at every renewal.
 */
export function getOrCreateVid(): string {
  const existing = readStoredConsent()?.vid;
  return existing && UUID_PATTERN.test(existing) ? existing : generateVid();
}

/**
 * Persist a consent choice keyed to [vid]; returns the stored state.
 *
 * `lifetimeDays` is the site's configured consent lifetime, applied to both the
 * cookie's `Max-Age` and the stamped `exp` so the two always agree. Changing it
 * affects choices made from then on: a cookie already in a visitor's browser
 * keeps the window it was written with until they choose again. `basisVersion`
 * is stamped the same way and read the same way — what this visitor was asked
 * about, frozen at the moment they answered.
 */
export function writeConsent(
  categories: ConsentDecision,
  vid: string,
  options: ConsentWriteOptions = {},
): ConsentState {
  const days = resolveLifetimeDays(options.lifetimeDays);
  const maxAgeSeconds = days * SECONDS_PER_DAY;
  const now = Date.now();
  const basisVersion = resolveBasisVersion(options.basisVersion);
  const state: ConsentState = {
    version: COOKIE_SCHEMA_VERSION,
    categories,
    ts: now,
    vid,
    exp: now + maxAgeSeconds * 1000,
    // Omitted rather than defaulted when unknown: a `bv` we invented would make
    // the next real basis look like a change and re-prompt the whole site.
    ...(basisVersion === null ? {} : { bv: basisVersion }),
  };
  const value = encodeURIComponent(JSON.stringify(state));
  // Secure only on https so the local dev harness (http) keeps working.
  const secure = location.protocol === 'https:' ? '; Secure' : '';
  document.cookie =
    `${COOKIE_NAME}=${value}; Max-Age=${maxAgeSeconds}; Path=/; SameSite=Lax${secure}`;
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
  const exp = candidate['exp'];
  const bv = candidate['bv'];
  return (
    typeof candidate['version'] === 'number' &&
    typeof candidate['ts'] === 'number' &&
    typeof candidate['categories'] === 'object' &&
    candidate['categories'] !== null &&
    (vid === undefined || typeof vid === 'string') &&
    (exp === undefined || typeof exp === 'number') &&
    // Same strictness as `vid`/`exp`: a non-numeric `bv` is a tampered or
    // corrupt payload, not a choice. An out-of-range NUMBER is a different case
    // — it parses, and [resolveBasisVersion] simply declines to judge it.
    (bv === undefined || typeof bv === 'number')
  );
}

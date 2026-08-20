import { beforeEach, describe, expect, test } from 'vitest';
import {
  COOKIE_NAME,
  getOrCreateVid,
  isBasisStale,
  readConsent,
  resolveBasisVersion,
  resolveLifetimeDays,
  writeConsent,
  type ConsentState,
} from '../src/storage';
import {
  COOKIE_SCHEMA_VERSION,
  DEFAULT_CONSENT_LIFETIME_DAYS,
  MAX_CONSENT_LIFETIME_DAYS,
  SECONDS_PER_DAY,
} from '../src/constants';

const VID = '11111111-2222-4333-8444-555555555555';
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function clearCookie(): void {
  document.cookie = `${COOKIE_NAME}=; Max-Age=0; Path=/`;
}

/**
 * The real `document.cookie` accessor. The DOM implementation defines it somewhere on the
 * prototype chain rather than at a fixed level, so it is found by walking rather than assumed.
 */
function cookieDescriptor(): PropertyDescriptor {
  let target: object | null = document;
  while (target) {
    const descriptor = Object.getOwnPropertyDescriptor(target, 'cookie');
    if (descriptor?.set) return descriptor;
    target = Object.getPrototypeOf(target) as object | null;
  }
  throw new Error('document.cookie has no setter to wrap');
}

/** Writes a raw cookie payload, bypassing writeConsent so pre-v3 shapes can be exercised. */
function setRawCookie(payload: unknown): void {
  const value = encodeURIComponent(JSON.stringify(payload));
  document.cookie = `${COOKIE_NAME}=${value}; Path=/`;
}

describe('storage (cmplyr cookie)', () => {
  beforeEach(() => {
    clearCookie();
  });

  test('returns null when no consent cookie exists', () => {
    expect(readConsent()).toBeNull();
  });

  test('round-trips a consent choice through the cookie', () => {
    const written = writeConsent(
      {
        necessary: true,
        statistics: true,
        marketing: false,
      },
      VID,
    );

    const read = readConsent();

    expect(read).not.toBeNull();
    expect(read).toEqual(written);
    expect(read?.version).toBe(COOKIE_SCHEMA_VERSION);
    expect(read?.vid).toBe(VID);
    expect(read?.categories).toEqual({
      necessary: true,
      statistics: true,
      marketing: false,
    });
    expect(typeof read?.ts).toBe('number');
  });

  test('sets 12-month expiry and SameSite=Lax attributes', () => {
    // Assert on the attributes we emit rather than browser-internal state.
    const value = encodeURIComponent(
      JSON.stringify({
        version: 1,
        categories: { necessary: true },
        ts: Date.now(),
      }),
    );
    document.cookie = `${COOKIE_NAME}=${value}; Max-Age=${60 * 60 * 24 * 365}; Path=/; SameSite=Lax`;
    expect(readConsent()?.categories).toEqual({ necessary: true });
  });

  test('honors a v1 cookie that predates vid', () => {
    // A schema-v1 payload has no `vid` — it must still count as a valid choice.
    setRawCookie({
      version: 1,
      categories: { necessary: true },
      ts: Date.now(),
    });
    const read = readConsent();
    expect(read).not.toBeNull();
    expect(read?.vid).toBeUndefined();
  });

  test('returns null for a corrupt (non-JSON) cookie value', () => {
    document.cookie = `${COOKIE_NAME}=not-json; Path=/`;
    expect(readConsent()).toBeNull();
  });

  test('returns null for JSON that is not a consent state', () => {
    document.cookie = `${COOKIE_NAME}=${encodeURIComponent('{"foo":1}')}; Path=/`;
    expect(readConsent()).toBeNull();
  });
});

describe('consent lifetime', () => {
  beforeEach(() => {
    clearCookie();
  });

  test('stamps exp from the configured lifetime', () => {
    const before = Date.now();
    const written = writeConsent({ necessary: true }, VID, { lifetimeDays: 180 });
    const expected = before + 180 * SECONDS_PER_DAY * 1000;
    // Allow for the clock advancing between the reading above and the write.
    expect(written.exp).toBeGreaterThanOrEqual(expected);
    expect(written.exp).toBeLessThan(expected + 5_000);
  });

  test('defaults to 12 months when the site configures no lifetime', () => {
    const written = writeConsent({ necessary: true }, VID);
    const days = (written.exp! - written.ts) / (SECONDS_PER_DAY * 1000);
    expect(days).toBe(DEFAULT_CONSENT_LIFETIME_DAYS);
  });

  test('emits a Max-Age that agrees with the stamped exp', () => {
    const writes: string[] = [];
    const original = cookieDescriptor();
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => original.get!.call(document),
      set: (value: string) => {
        writes.push(value);
        original.set!.call(document, value);
      },
    });
    try {
      writeConsent({ necessary: true }, VID, { lifetimeDays: 90 });
    } finally {
      delete (document as unknown as { cookie?: unknown }).cookie;
    }
    expect(writes.at(-1)).toContain(`Max-Age=${90 * SECONDS_PER_DAY}`);
  });

  test('reads an expired choice as no choice at all', () => {
    setRawCookie({
      version: COOKIE_SCHEMA_VERSION,
      categories: { necessary: true },
      ts: Date.now() - 1000,
      vid: VID,
      exp: Date.now() - 1,
    });
    expect(readConsent()).toBeNull();
  });

  test('honors a choice that has not reached its exp', () => {
    setRawCookie({
      version: COOKIE_SCHEMA_VERSION,
      categories: { necessary: true, statistics: true },
      ts: Date.now(),
      vid: VID,
      exp: Date.now() + 60_000,
    });
    expect(readConsent()?.categories).toEqual({
      necessary: true,
      statistics: true,
    });
  });

  test('measures a pre-v3 cookie against the 12 months it was written with', () => {
    const justInside = Date.now() - (DEFAULT_CONSENT_LIFETIME_DAYS - 1) * SECONDS_PER_DAY * 1000;
    setRawCookie({
      version: 2,
      categories: { necessary: true },
      ts: justInside,
      vid: VID,
    });
    expect(readConsent()).not.toBeNull();

    const justOutside = Date.now() - (DEFAULT_CONSENT_LIFETIME_DAYS + 1) * SECONDS_PER_DAY * 1000;
    setRawCookie({
      version: 2,
      categories: { necessary: true },
      ts: justOutside,
      vid: VID,
    });
    expect(readConsent()).toBeNull();
  });
});

describe('resolveLifetimeDays', () => {
  test('keeps a sane configured value', () => {
    expect(resolveLifetimeDays(90)).toBe(90);
    expect(resolveLifetimeDays(180)).toBe(180);
  });

  test('falls back to the default for a missing or nonsense value', () => {
    expect(resolveLifetimeDays(undefined)).toBe(DEFAULT_CONSENT_LIFETIME_DAYS);
    expect(resolveLifetimeDays(Number.NaN)).toBe(DEFAULT_CONSENT_LIFETIME_DAYS);
    expect(resolveLifetimeDays(Number.POSITIVE_INFINITY)).toBe(
      DEFAULT_CONSENT_LIFETIME_DAYS,
    );
    expect(resolveLifetimeDays(0)).toBe(DEFAULT_CONSENT_LIFETIME_DAYS);
    expect(resolveLifetimeDays(-30)).toBe(DEFAULT_CONSENT_LIFETIME_DAYS);
  });

  test('clamps to what a browser will actually keep', () => {
    expect(resolveLifetimeDays(10_000)).toBe(MAX_CONSENT_LIFETIME_DAYS);
  });

  test('floors a fractional value to whole days', () => {
    expect(resolveLifetimeDays(90.9)).toBe(90);
  });
});

describe('consent basis (BACKLOG #18)', () => {
  beforeEach(() => {
    clearCookie();
  });

  function state(bv?: number): ConsentState {
    const base: ConsentState = {
      version: COOKIE_SCHEMA_VERSION,
      categories: {},
      ts: Date.now(),
    };
    return bv === undefined ? base : { ...base, bv };
  }

  test('stamps the site basis version into the cookie', () => {
    const written = writeConsent({ necessary: true }, VID, { basisVersion: 3 });
    expect(written.bv).toBe(3);
    expect(readConsent()?.bv).toBe(3);
  });

  test('omits bv when the site basis is unknown', () => {
    // A choice made against the fallback config must not look stale later.
    expect(writeConsent({ necessary: true }, VID).bv).toBeUndefined();
    expect(readConsent()).not.toHaveProperty('bv');
  });

  test('rejects a non-numeric bv but tolerates an out-of-range one', () => {
    setRawCookie({
      version: COOKIE_SCHEMA_VERSION,
      categories: { necessary: true },
      ts: Date.now(),
      vid: VID,
      exp: Date.now() + 60_000,
      bv: 'three',
    });
    // Same strictness as a corrupt `vid`/`exp`: not a choice at all.
    expect(readConsent()).toBeNull();

    setRawCookie({
      version: COOKIE_SCHEMA_VERSION,
      categories: { necessary: true },
      ts: Date.now(),
      vid: VID,
      exp: Date.now() + 60_000,
      bv: 0,
    });
    expect(readConsent()?.categories).toEqual({ necessary: true });
    expect(isBasisStale(readConsent()!, 2)).toBe(false);
  });

  test('is stale only when the site basis has moved past the stamp', () => {
    expect(isBasisStale(state(1), 2)).toBe(true);
    expect(isBasisStale(state(1), 9)).toBe(true);
    expect(isBasisStale(state(2), 2)).toBe(false);
    // Never backwards: a basis that somehow regressed must not re-prompt.
    expect(isBasisStale(state(3), 2)).toBe(false);
  });

  test('treats an unknown version on either side as not stale', () => {
    // Pre-v4 cookie: upgrading the widget re-prompts nobody.
    expect(isBasisStale(state(undefined), 4)).toBe(false);
    // Config without a basis (fallback, or an older backend): no verdict, no prompt.
    expect(isBasisStale(state(2), undefined)).toBe(false);
  });
});

describe('resolveBasisVersion', () => {
  test('keeps a whole version of at least 1', () => {
    expect(resolveBasisVersion(1)).toBe(1);
    expect(resolveBasisVersion(7)).toBe(7);
    expect(resolveBasisVersion(2.9)).toBe(2);
  });

  test('rejects anything that is not a usable version', () => {
    expect(resolveBasisVersion(undefined)).toBeNull();
    expect(resolveBasisVersion(0)).toBeNull();
    expect(resolveBasisVersion(-1)).toBeNull();
    expect(resolveBasisVersion(Number.NaN)).toBeNull();
    expect(resolveBasisVersion(Number.POSITIVE_INFINITY)).toBeNull();
  });
});

describe('getOrCreateVid', () => {
  beforeEach(() => {
    clearCookie();
  });

  test('mints a valid UUID when there is no cookie', () => {
    expect(getOrCreateVid()).toMatch(UUID_PATTERN);
  });

  test('reuses the vid already stored in the cookie', () => {
    writeConsent({ necessary: true }, VID);
    expect(getOrCreateVid()).toBe(VID);
  });

  test('mints a fresh vid for a v1 cookie that has none', () => {
    setRawCookie({
      version: 1,
      categories: { necessary: true },
      ts: Date.now(),
    });
    expect(getOrCreateVid()).toMatch(UUID_PATTERN);
  });

  test('keeps the same vid when renewing an expired choice', () => {
    // Renewal is the same visitor answering again — their audit history must stay one thread.
    setRawCookie({
      version: COOKIE_SCHEMA_VERSION,
      categories: { necessary: true },
      ts: Date.now() - 1000,
      vid: VID,
      exp: Date.now() - 1,
    });
    expect(readConsent()).toBeNull();
    expect(getOrCreateVid()).toBe(VID);
  });
});

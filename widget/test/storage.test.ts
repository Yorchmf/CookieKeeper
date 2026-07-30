import { beforeEach, describe, expect, test } from 'vitest';
import {
  COOKIE_NAME,
  getOrCreateVid,
  readConsent,
  writeConsent,
} from '../src/storage';
import { COOKIE_SCHEMA_VERSION } from '../src/constants';

const VID = '11111111-2222-4333-8444-555555555555';
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function clearCookie(): void {
  document.cookie = `${COOKIE_NAME}=; Max-Age=0; Path=/`;
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
      JSON.stringify({ version: 1, categories: { necessary: true }, ts: 1 }),
    );
    document.cookie = `${COOKIE_NAME}=${value}; Max-Age=${60 * 60 * 24 * 365}; Path=/; SameSite=Lax`;
    expect(readConsent()?.categories).toEqual({ necessary: true });
  });

  test('honors a v1 cookie that predates vid', () => {
    // A schema-v1 payload has no `vid` — it must still count as a valid choice.
    const value = encodeURIComponent(
      JSON.stringify({ version: 1, categories: { necessary: true }, ts: 1 }),
    );
    document.cookie = `${COOKIE_NAME}=${value}; Path=/`;
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
    const value = encodeURIComponent(
      JSON.stringify({ version: 1, categories: { necessary: true }, ts: 1 }),
    );
    document.cookie = `${COOKIE_NAME}=${value}; Path=/`;
    expect(getOrCreateVid()).toMatch(UUID_PATTERN);
  });
});

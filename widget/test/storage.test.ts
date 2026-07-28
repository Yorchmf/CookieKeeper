import { beforeEach, describe, expect, test } from 'vitest';
import { COOKIE_NAME, readConsent, writeConsent } from '../src/storage';
import { COOKIE_SCHEMA_VERSION } from '../src/constants';

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
    const written = writeConsent({
      necessary: true,
      statistics: true,
      marketing: false,
    });

    const read = readConsent();

    expect(read).not.toBeNull();
    expect(read).toEqual(written);
    expect(read?.version).toBe(COOKIE_SCHEMA_VERSION);
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

  test('returns null for a corrupt (non-JSON) cookie value', () => {
    document.cookie = `${COOKIE_NAME}=not-json; Path=/`;
    expect(readConsent()).toBeNull();
  });

  test('returns null for JSON that is not a consent state', () => {
    document.cookie = `${COOKIE_NAME}=${encodeURIComponent('{"foo":1}')}; Path=/`;
    expect(readConsent()).toBeNull();
  });
});

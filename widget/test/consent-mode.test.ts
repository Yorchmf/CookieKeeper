import { beforeEach, describe, expect, test } from 'vitest';
import { setConsentDefaults, updateConsent } from '../src/consent-mode';

/** dataLayer entries are `arguments` objects — normalize to arrays for asserts. */
function entries(): unknown[][] {
  return (window.dataLayer ?? []).map((entry) =>
    Array.from(entry as ArrayLike<unknown>),
  );
}

/** The params object of the first `consent` push (default or update). */
function consentParams(): Record<string, unknown> {
  const push = entries().find(([command]) => command === 'consent');
  expect(push, 'expected a consent push on dataLayer').toBeDefined();
  return push![2] as Record<string, unknown>;
}

/** The value of the last `gtag('set', key, …)` push, or undefined if never set. */
function setValue(key: string): unknown {
  const push = entries()
    .filter(([command, name]) => command === 'set' && name === key)
    .pop();
  return push?.[2];
}

describe('consent-mode (Google Consent Mode v2)', () => {
  beforeEach(() => {
    window.dataLayer = [];
  });

  test('setConsentDefaults denies every decidable signal', () => {
    setConsentDefaults();

    const [command, verb] = entries()[0]!;
    expect(command).toBe('consent');
    expect(verb).toBe('default');
    expect(consentParams()).toMatchObject({
      ad_storage: 'denied',
      analytics_storage: 'denied',
      ad_user_data: 'denied',
      ad_personalization: 'denied',
      functionality_storage: 'denied',
      personalization_storage: 'denied',
    });
  });

  test('setConsentDefaults grants security_storage (the locked-on necessary category)', () => {
    setConsentDefaults();

    expect(consentParams()['security_storage']).toBe('granted');
  });

  test('setConsentDefaults sends all seven v2 signals — an omitted one reads as granted', () => {
    setConsentDefaults();

    expect(Object.keys(consentParams()).sort()).toEqual([
      'ad_personalization',
      'ad_storage',
      'ad_user_data',
      'analytics_storage',
      'functionality_storage',
      'personalization_storage',
      'security_storage',
      'wait_for_update',
    ]);
  });

  test('setConsentDefaults turns on ads_data_redaction', () => {
    setConsentDefaults();

    expect(setValue('ads_data_redaction')).toBe(true);
  });

  test('url_passthrough is off unless the embed opts in', () => {
    setConsentDefaults();
    expect(setValue('url_passthrough')).toBeUndefined();

    window.dataLayer = [];
    setConsentDefaults({ urlPassthrough: false });
    expect(setValue('url_passthrough')).toBeUndefined();
  });

  test('url_passthrough is set when the embed opts in', () => {
    setConsentDefaults({ urlPassthrough: true });

    expect(setValue('url_passthrough')).toBe(true);
  });

  test('setConsentDefaults creates dataLayer when it does not exist', () => {
    delete (window as { dataLayer?: unknown[] }).dataLayer;

    setConsentDefaults();

    expect(Array.isArray(window.dataLayer)).toBe(true);
    expect(window.dataLayer!.length).toBeGreaterThan(0);
  });

  test('updateConsent grants analytics when statistics accepted', () => {
    updateConsent({ necessary: true, statistics: true, marketing: false });

    const [, verb] = entries()[0]!;
    expect(verb).toBe('update');
    expect(consentParams()).toMatchObject({
      analytics_storage: 'granted',
      ad_storage: 'denied',
      ad_user_data: 'denied',
      ad_personalization: 'denied',
    });
  });

  test('updateConsent grants all ad signals when marketing accepted', () => {
    updateConsent({ statistics: false, marketing: true });

    expect(consentParams()).toMatchObject({
      ad_storage: 'granted',
      ad_user_data: 'granted',
      ad_personalization: 'granted',
      analytics_storage: 'denied',
    });
  });

  test('updateConsent maps preferences to functionality and personalization storage', () => {
    updateConsent({ necessary: true, preferences: true });

    expect(consentParams()).toMatchObject({
      functionality_storage: 'granted',
      personalization_storage: 'granted',
    });
  });

  test('a category the banner never offered stays denied', () => {
    // No `preferences` key at all — the visitor was never asked, so there is no
    // consent to infer and the signal must not be granted.
    updateConsent({ necessary: true, statistics: true, marketing: true });

    expect(consentParams()).toMatchObject({
      functionality_storage: 'denied',
      personalization_storage: 'denied',
    });
  });

  test('updateConsent keeps ads_data_redaction on until marketing is granted', () => {
    updateConsent({ statistics: true, marketing: false });
    expect(setValue('ads_data_redaction')).toBe(true);

    window.dataLayer = [];
    updateConsent({ statistics: true, marketing: true });
    expect(setValue('ads_data_redaction')).toBe(false);
  });

  test('pushes preserve gtag arguments-object shape (GTM requirement)', () => {
    setConsentDefaults({ urlPassthrough: true });

    for (const raw of window.dataLayer!) {
      // Must be an arguments object, not a plain array.
      expect(Array.isArray(raw)).toBe(false);
      expect((raw as ArrayLike<unknown>).length).toBe(3);
    }
  });
});

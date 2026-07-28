import { beforeEach, describe, expect, test } from 'vitest';
import { setConsentDefaults, updateConsent } from '../src/consent-mode';

/** dataLayer entries are `arguments` objects — normalize to arrays for asserts. */
function entries(): unknown[][] {
  return (window.dataLayer ?? []).map((entry) =>
    Array.from(entry as ArrayLike<unknown>),
  );
}

describe('consent-mode (Google Consent Mode v2)', () => {
  beforeEach(() => {
    window.dataLayer = [];
  });

  test('setConsentDefaults pushes denied defaults for all four v2 signals', () => {
    setConsentDefaults();

    const pushed = entries();
    expect(pushed).toHaveLength(1);
    const [command, verb, params] = pushed[0]!;
    expect(command).toBe('consent');
    expect(verb).toBe('default');
    expect(params).toMatchObject({
      ad_storage: 'denied',
      analytics_storage: 'denied',
      ad_user_data: 'denied',
      ad_personalization: 'denied',
    });
  });

  test('setConsentDefaults creates dataLayer when it does not exist', () => {
    delete (window as { dataLayer?: unknown[] }).dataLayer;

    setConsentDefaults();

    expect(Array.isArray(window.dataLayer)).toBe(true);
    expect(window.dataLayer).toHaveLength(1);
  });

  test('updateConsent grants analytics when statistics accepted', () => {
    updateConsent({ necessary: true, statistics: true, marketing: false });

    const [, verb, params] = entries()[0]!;
    expect(verb).toBe('update');
    expect(params).toMatchObject({
      analytics_storage: 'granted',
      ad_storage: 'denied',
      ad_user_data: 'denied',
      ad_personalization: 'denied',
    });
  });

  test('updateConsent grants all ad signals when marketing accepted', () => {
    updateConsent({ statistics: false, marketing: true });

    const [, , params] = entries()[0]!;
    expect(params).toMatchObject({
      ad_storage: 'granted',
      ad_user_data: 'granted',
      ad_personalization: 'granted',
      analytics_storage: 'denied',
    });
  });

  test('pushes preserve gtag arguments-object shape (GTM requirement)', () => {
    setConsentDefaults();
    const raw = window.dataLayer![0];
    // Must be an arguments object, not a plain array.
    expect(Array.isArray(raw)).toBe(false);
    expect((raw as ArrayLike<unknown>).length).toBe(3);
  });
});

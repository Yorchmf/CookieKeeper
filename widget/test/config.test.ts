import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  DEFAULT_CONFIG,
  fetchConfig,
  resolveLanguage,
  resolveTexts,
  sanitizeColors,
  type WidgetConfig,
} from '../src/config';

const validConfig: WidgetConfig = {
  version: 3,
  colors: {
    background: '#101010',
    text: '#fafafa',
    button: '#4c7dff',
    buttonText: '#ffffff',
  },
  position: 'bottom',
  texts: DEFAULT_CONFIG.texts,
  defaultLanguage: 'en',
  categories: [
    { id: 'necessary', required: true },
    { id: 'statistics', required: false },
  ],
};

const okJson = (body: unknown) =>
  Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));

describe('config', () => {
  describe('sanitizeColors', () => {
    test('keeps well-formed color tokens', () => {
      const colors = {
        background: '#1f2430',
        text: 'rgb(245, 246, 248)',
        button: 'oklch(68% 0.21 250)',
        buttonText: 'white',
      };
      expect(sanitizeColors(colors)).toEqual(colors);
    });

    test('replaces a CSS-injection payload with the default', () => {
      const hostile = {
        background: '#000; } .evil { background: url(https://evil/?leak)',
        text: '#f5f6f8',
        button: '#4c7dff',
        buttonText: '#ffffff',
      };
      const safe = sanitizeColors(hostile);
      // The malformed value is dropped; the rest pass through untouched.
      expect(safe.background).toBe(DEFAULT_CONFIG.colors.background);
      expect(safe.text).toBe('#f5f6f8');
    });

    test('falls back when a value is missing or the wrong type', () => {
      const safe = sanitizeColors({
        background: undefined as unknown as string,
        text: 42 as unknown as string,
        button: '#4c7dff',
        buttonText: '#ffffff',
      });
      expect(safe.background).toBe(DEFAULT_CONFIG.colors.background);
      expect(safe.text).toBe(DEFAULT_CONFIG.colors.text);
    });
  });

  describe('fetchConfig validation', () => {
    afterEach(() => {
      vi.unstubAllGlobals();
      vi.restoreAllMocks();
    });

    test('accepts a well-formed config and sanitizes its colors', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(() => okJson(validConfig)),
      );
      const result = await fetchConfig('pk_test');
      expect(result.version).toBe(3);
      expect(result.categories).toHaveLength(2);
      expect(result.colors.background).toBe('#101010');
    });

    test('falls back to the default when categories is empty', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(() => okJson({ ...validConfig, categories: [] })),
      );
      expect(await fetchConfig('pk_test')).toBe(DEFAULT_CONFIG);
    });

    test('falls back when a category entry is malformed', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(() => okJson({ ...validConfig, categories: [{ id: 42 }] })),
      );
      expect(await fetchConfig('pk_test')).toBe(DEFAULT_CONFIG);
    });

    test('falls back when the fetch rejects', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(() => Promise.reject(new Error('network'))),
      );
      expect(await fetchConfig('pk_test')).toBe(DEFAULT_CONFIG);
    });
  });

  describe('resolveLanguage', () => {
    // What this returns is what the dialogs declare in `lang` (WCAG 3.1.2) and what
    // the consent event records — so it must name a language we actually rendered.
    const multilingual: WidgetConfig = {
      ...DEFAULT_CONFIG,
      texts: { ...DEFAULT_CONFIG.texts, de: DEFAULT_CONFIG.texts.en! },
      defaultLanguage: 'de',
    };

    test('narrows a BCP-47 tag to the published language', () => {
      expect(resolveLanguage(multilingual, 'de-AT')).toBe('de');
      expect(resolveLanguage(multilingual, 'DE')).toBe('de');
    });

    test("falls back to the site's default when the visitor's is not published", () => {
      expect(resolveLanguage(multilingual, 'fr-FR')).toBe('de');
    });

    test('falls back to English when even the default is missing copy', () => {
      const broken: WidgetConfig = { ...multilingual, defaultLanguage: 'it' };
      expect(resolveLanguage(broken, 'fr')).toBe('en');
    });
  });

  describe('resolveTexts', () => {
    test('merges panel fields over the English fallback for a partial config', () => {
      const texts = resolveTexts(DEFAULT_CONFIG, 'fr');
      // Unknown language falls back to English, keeping the panel complete.
      expect(texts.save).toBe(DEFAULT_CONFIG.texts.en!.save);
      expect(texts.close).toBe(DEFAULT_CONFIG.texts.en!.close);
      expect(texts.categoryLabels.necessary?.label).toBeTruthy();
    });
  });
});

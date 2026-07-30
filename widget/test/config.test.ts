import { describe, expect, test } from 'vitest';
import { DEFAULT_CONFIG, resolveTexts, sanitizeColors } from '../src/config';

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

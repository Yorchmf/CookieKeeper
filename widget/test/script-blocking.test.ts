import { beforeEach, describe, expect, test } from 'vitest';
import {
  blockedScripts,
  grantedCategories,
  unblockScripts,
} from '../src/script-blocking';

/**
 * These tests assert the DOM transformation (placeholder → live <script>), not
 * actual script execution: happy-dom does not run injected script src, and
 * asserting on execution would be flaky. The transformation is the contract —
 * the browser then executes whatever we un-neutralise.
 */

/** A neutralised third-party tag as a site owner would author it. */
function addBlocked(
  category: string,
  attrs: Record<string, string> = {},
  inline?: string,
): HTMLScriptElement {
  const script = document.createElement('script');
  script.type = 'text/plain';
  script.setAttribute('data-complyr-category', category);
  for (const [name, value] of Object.entries(attrs)) {
    script.setAttribute(name, value);
  }
  if (inline !== undefined) script.textContent = inline;
  document.body.appendChild(script);
  return script;
}

/** The live scripts currently in the DOM (executable, not placeholders). */
function liveScripts(): HTMLScriptElement[] {
  return Array.from(
    document.querySelectorAll<HTMLScriptElement>('script:not([type="text/plain"])'),
  );
}

describe('script-blocking (prior blocking, GDPR/ePrivacy)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  describe('grantedCategories', () => {
    test('returns only the categories set to true', () => {
      const granted = grantedCategories({
        necessary: true,
        statistics: true,
        marketing: false,
      });
      expect([...granted].sort()).toEqual(['necessary', 'statistics']);
    });

    test('is empty when nothing is granted', () => {
      expect(grantedCategories({ statistics: false }).size).toBe(0);
    });
  });

  describe('blockedScripts', () => {
    test('finds only neutralised, categorised placeholders', () => {
      addBlocked('statistics', { 'data-src': 'https://x.test/a.js' });
      // A normal script is not a placeholder.
      const normal = document.createElement('script');
      normal.textContent = '// app code';
      document.body.appendChild(normal);
      // text/plain without a category is not ours.
      const untagged = document.createElement('script');
      untagged.type = 'text/plain';
      document.body.appendChild(untagged);

      const found = blockedScripts();
      expect(found).toHaveLength(1);
      expect(found[0]!.getAttribute('data-complyr-category')).toBe('statistics');
    });
  });

  describe('unblockScripts', () => {
    test('activates a placeholder whose category is granted', () => {
      addBlocked('statistics', { 'data-src': 'https://x.test/analytics.js' });

      unblockScripts(new Set(['statistics']));

      expect(blockedScripts()).toHaveLength(0);
      const live = liveScripts();
      expect(live).toHaveLength(1);
      expect(live[0]!.src).toBe('https://x.test/analytics.js');
      // Ordered execution for dependent tags.
      expect(live[0]!.async).toBe(false);
    });

    test('leaves a placeholder whose category is denied untouched', () => {
      const placeholder = addBlocked('marketing', {
        'data-src': 'https://x.test/pixel.js',
      });

      unblockScripts(new Set(['statistics']));

      expect(blockedScripts()).toHaveLength(1);
      expect(document.body.contains(placeholder)).toBe(true);
      expect(liveScripts()).toHaveLength(0);
    });

    test('copies inline content when there is no src', () => {
      addBlocked('statistics', {}, 'window.__ran = true;');

      unblockScripts(new Set(['statistics']));

      const live = liveScripts();
      expect(live).toHaveLength(1);
      expect(live[0]!.textContent).toBe('window.__ran = true;');
      expect(live[0]!.src).toBe('');
    });

    test('carries over author attributes but drops control attributes', () => {
      addBlocked('statistics', {
        'data-src': 'https://x.test/a.js',
        'data-cookieconsent': 'ignore',
      });

      unblockScripts(new Set(['statistics']));

      const live = liveScripts()[0]!;
      expect(live.getAttribute('data-cookieconsent')).toBe('ignore');
      // Control attributes must not leak onto the live tag.
      expect(live.getAttribute('data-complyr-category')).toBeNull();
      expect(live.getAttribute('data-src')).toBeNull();
      expect(live.type).not.toBe('text/plain');
    });

    test('never copies inline event-handler attributes (XSS surface)', () => {
      const placeholder = addBlocked('statistics', {
        'data-src': 'https://x.test/a.js',
      });
      placeholder.setAttribute('onload', 'window.__pwned = true');
      placeholder.setAttribute('onerror', 'window.__pwned = true');

      unblockScripts(new Set(['statistics']));

      const live = liveScripts()[0]!;
      expect(live.getAttribute('onload')).toBeNull();
      expect(live.getAttribute('onerror')).toBeNull();
    });

    test('preserves the CSP nonce via the IDL property', () => {
      const placeholder = addBlocked('statistics', {
        'data-src': 'https://x.test/a.js',
      });
      // Browsers expose the nonce on the property, not the (blanked) attribute.
      placeholder.nonce = 'n0nce-abc';

      unblockScripts(new Set(['statistics']));

      expect(liveScripts()[0]!.nonce).toBe('n0nce-abc');
    });

    test('data-src takes precedence over a literal src attribute', () => {
      addBlocked('statistics', {
        'data-src': 'https://x.test/real.js',
        src: 'https://x.test/ignored.js',
      });

      unblockScripts(new Set(['statistics']));

      expect(liveScripts()[0]!.src).toBe('https://x.test/real.js');
    });

    test('activates multiple src scripts in document order, all non-async', () => {
      addBlocked('statistics', { 'data-src': 'https://x.test/1.js' });
      addBlocked('statistics', { 'data-src': 'https://x.test/2.js' });
      addBlocked('statistics', { 'data-src': 'https://x.test/3.js' });

      unblockScripts(new Set(['statistics']));

      const srcs = liveScripts().map((s) => s.src);
      expect(srcs).toEqual([
        'https://x.test/1.js',
        'https://x.test/2.js',
        'https://x.test/3.js',
      ]);
      expect(liveScripts().every((s) => s.async === false)).toBe(true);
    });

    test('data-complyr-type sets the executable script type', () => {
      addBlocked('statistics', {
        'data-src': 'https://x.test/m.js',
        'data-complyr-type': 'module',
      });

      unblockScripts(new Set(['statistics']));

      expect(liveScripts()[0]!.type).toBe('module');
    });

    test('is idempotent — a second call does not re-activate', () => {
      addBlocked('statistics', { 'data-src': 'https://x.test/a.js' });

      unblockScripts(new Set(['statistics']));
      unblockScripts(new Set(['statistics']));

      expect(liveScripts()).toHaveLength(1);
    });

    test('activates each granted placeholder and skips the rest', () => {
      addBlocked('statistics', { 'data-src': 'https://x.test/a.js' });
      addBlocked('marketing', { 'data-src': 'https://x.test/b.js' });
      addBlocked('statistics', {}, '// inline stat');

      unblockScripts(new Set(['statistics']));

      expect(liveScripts()).toHaveLength(2);
      expect(blockedScripts()).toHaveLength(1); // marketing stays blocked
    });
  });
});

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { removeBanner, renderBanner } from '../src/banner';
import { DEFAULT_CONFIG, resolveTexts, type WidgetConfig } from '../src/config';

const texts = resolveTexts(DEFAULT_CONFIG, 'en');

function show(
  overrides: Partial<WidgetConfig> = {},
  lang = 'en',
  onPreferences = () => undefined,
): ShadowRoot {
  renderBanner({ ...DEFAULT_CONFIG, ...overrides }, texts, lang, {
    onAction: () => undefined,
    onPreferences,
  });
  return document.getElementById('complyr-host')!.shadowRoot!;
}

/**
 * The banner is the one surface a visitor cannot skip, and under the European
 * Accessibility Act an unusable one is our customer's legal exposure (ADR-28).
 * These are the WCAG 2.2 AA behaviours that are ours to get right rather than the
 * backend's — semantics, language, focus movement and restoration.
 */
describe('consent banner', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });
  afterEach(() => {
    removeBanner();
  });

  test('is a dialog named by its own heading', () => {
    const root = show();
    const dialog = root.querySelector('[role="dialog"]')!;

    const labelId = dialog.getAttribute('aria-labelledby')!;
    expect(root.getElementById(labelId)?.textContent).toBe(texts.title);
    // Non-modal on purpose: it does not inert the page, so it must not claim to.
    expect(dialog.hasAttribute('aria-modal')).toBe(false);
  });

  test('declares the language it is actually displayed in', () => {
    // WCAG 3.1.2. The visitor's language is routinely not the host page's, and the
    // value passed in is already resolved against what this site publishes.
    expect(show({}, 'de').querySelector('[role="dialog"]')!.getAttribute('lang')).toBe(
      'de',
    );
  });

  test('moves focus into the banner without trapping the page', () => {
    const root = show();
    const accept = Array.from(root.querySelectorAll('button')).find(
      (b) => b.textContent === texts.acceptAll,
    );

    expect(root.activeElement).toBe(accept);
    // No keydown handler on the dialog: Tab must be able to leave (2.1.2).
    expect(document.body.hasAttribute('inert')).toBe(false);
  });

  test('restores focus to where it was when the banner is torn down', () => {
    const trigger = document.createElement('button');
    document.body.appendChild(trigger);
    trigger.focus();

    show();
    removeBanner();

    expect(document.activeElement).toBe(trigger);
  });

  test('the attribution link announces that it opens a new tab', () => {
    const credit = show().querySelector('a.credit')!;

    expect(credit.getAttribute('target')).toBe('_blank');
    expect(credit.getAttribute('rel')).toBe('noopener noreferrer');
    // WCAG 3.2.5 — the context switch is announced without cluttering the visible label.
    expect(credit.getAttribute('aria-label')).toContain(texts.opensInNewTab);
  });

  test('renders no attribution when the plan pays for branding removal', () => {
    expect(show({ removeBranding: true }).querySelector('a.credit')).toBeNull();
  });

  test('the preferences entry is a real button, reachable by keyboard', () => {
    const onPreferences = vi.fn();
    const root = show({}, 'en', onPreferences);
    const prefs = Array.from(root.querySelectorAll('button')).find(
      (b) => b.textContent === texts.preferences,
    )!;

    expect(prefs.type).toBe('button');
    prefs.click();
    expect(onPreferences).toHaveBeenCalledTimes(1);
  });
});

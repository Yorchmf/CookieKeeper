import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { DEFAULT_CONFIG, resolveTexts, type WidgetConfig } from '../src/config';
import type { ConsentDecision } from '../src/consent-mode';
import {
  isPreferencesOpen,
  removePreferences,
  renderPreferences,
  type PreferencesHandlers,
} from '../src/preferences';

const config: WidgetConfig = DEFAULT_CONFIG;
const texts = resolveTexts(config, 'en');

function open(
  current: ConsentDecision = {},
  handlers: Partial<PreferencesHandlers> = {},
  lang = 'en',
): ShadowRoot {
  renderPreferences(config, texts, lang, current, {
    onSave: handlers.onSave ?? (() => undefined),
    onCancel: handlers.onCancel ?? (() => undefined),
  });
  const host = document.getElementById('complyr-prefs-host')!;
  return host.shadowRoot!;
}

function checkbox(root: ShadowRoot, id: string): HTMLInputElement {
  return root.getElementById(`complyr-cat-${id}`) as HTMLInputElement;
}

function buttonByText(root: ShadowRoot, text: string): HTMLButtonElement {
  const found = Array.from(root.querySelectorAll('button')).find(
    (b) => b.textContent === text,
  );
  return found as HTMLButtonElement;
}

describe('preferences panel', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });
  afterEach(() => {
    removePreferences();
  });

  describe('accessibility', () => {
    test('is a labelled, described modal dialog', () => {
      const root = open();
      const dialog = root.querySelector('[role="dialog"]')!;

      expect(dialog.getAttribute('aria-modal')).toBe('true');
      const labelId = dialog.getAttribute('aria-labelledby')!;
      const descId = dialog.getAttribute('aria-describedby')!;
      // The referenced ids must resolve to real elements (no dangling ARIA).
      expect(root.getElementById(labelId)?.textContent).toBe(
        texts.preferencesTitle,
      );
      expect(root.getElementById(descId)).not.toBeNull();
    });

    test('declares its own language for assistive tech', () => {
      const root = open({}, {}, 'de-DE');
      expect(root.querySelector('[role="dialog"]')!.getAttribute('lang')).toBe(
        'de',
      );
    });

    test('opens with focus on the dialog container, not a control', () => {
      const root = open();
      const dialog = root.querySelector('[role="dialog"]') as HTMLElement;
      // Focused for announcement, but never a Tab stop.
      expect(root.activeElement).toBe(dialog);
      expect(dialog.tabIndex).toBe(-1);
    });

    test('each category is a checkbox with an associated label', () => {
      const root = open();
      for (const category of config.categories) {
        const input = checkbox(root, category.id);
        expect(input.type).toBe('checkbox');
        const label = root.querySelector(
          `label[for="complyr-cat-${category.id}"]`,
        );
        expect(label?.textContent).toBe(texts.categoryLabels[category.id]!.label);
        // Description is wired via aria-describedby to a real node.
        const descId = input.getAttribute('aria-describedby')!;
        expect(root.getElementById(descId)).not.toBeNull();
      }
    });

    test('required categories are checked, locked, and labelled "always active"', () => {
      const root = open();
      const necessary = checkbox(root, 'necessary');
      expect(necessary.checked).toBe(true);
      expect(necessary.disabled).toBe(true);
      // A visible text explanation, not opacity/colour alone.
      const row = necessary.closest('.category')!;
      expect(row.querySelector('.always-active')?.textContent).toBe(
        texts.alwaysActive,
      );
    });

    test('makes the rest of the page inert while open, and restores it on close', () => {
      const background = document.createElement('div');
      document.body.appendChild(background);

      open();
      expect(background.hasAttribute('inert')).toBe(true);
      // Scrolling is locked while open, since `inert` alone does not stop it.
      expect(document.documentElement.style.overflow).toBe('hidden');

      removePreferences();
      expect(background.hasAttribute('inert')).toBe(false);
      expect(document.documentElement.style.overflow).toBe('');
    });

    test('does not clobber an inert attribute the host page already set', () => {
      const background = document.createElement('div');
      background.setAttribute('inert', '');
      document.body.appendChild(background);

      open();
      removePreferences();
      // Still inert: we never set it, so we must not remove it.
      expect(background.hasAttribute('inert')).toBe(true);
    });

    test('Escape cancels and tears the panel down', () => {
      const onCancel = vi.fn();
      const root = open({}, { onCancel });

      root
        .querySelector('[role="dialog"]')!
        .dispatchEvent(
          new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
        );

      expect(onCancel).toHaveBeenCalledTimes(1);
    });

    test('the Close control cancels (pointer-operable exit)', () => {
      const onCancel = vi.fn();
      const root = open({}, { onCancel });

      const close = root.querySelector(
        `button[aria-label="${texts.close}"]`,
      ) as HTMLButtonElement;
      close.click();

      expect(onCancel).toHaveBeenCalledTimes(1);
    });

    test('clicking the backdrop cancels, clicking the panel does not', () => {
      const onCancel = vi.fn();
      const root = open({}, { onCancel });

      (root.querySelector('.panel') as HTMLElement).click();
      expect(onCancel).not.toHaveBeenCalled();

      (root.querySelector('.backdrop') as HTMLElement).click();
      expect(onCancel).toHaveBeenCalledTimes(1);
    });

    test('removePreferences restores focus to the invoking control', () => {
      const opener = document.createElement('button');
      document.body.appendChild(opener);
      opener.focus();

      open();
      expect(isPreferencesOpen()).toBe(true);

      removePreferences();
      expect(isPreferencesOpen()).toBe(false);
      expect(document.activeElement).toBe(opener);
    });
  });

  describe('decision collection', () => {
    test('seeds checkboxes from the prior choice', () => {
      const root = open({ statistics: true, marketing: false });
      expect(checkbox(root, 'statistics').checked).toBe(true);
      expect(checkbox(root, 'marketing').checked).toBe(false);
    });

    test('Save reports the toggled state, forcing required on', () => {
      const onSave = vi.fn();
      const root = open({}, { onSave });

      checkbox(root, 'statistics').checked = true;
      // Even if a required box were somehow unchecked, it must save as granted.
      checkbox(root, 'necessary').checked = false;
      buttonByText(root, texts.save).click();

      expect(onSave).toHaveBeenCalledTimes(1);
      expect(onSave.mock.calls[0]![0]).toEqual({
        necessary: true,
        preferences: false,
        statistics: true,
        marketing: false,
      });
    });

    test('Accept all grants every category', () => {
      const onSave = vi.fn();
      const root = open({}, { onSave });

      buttonByText(root, texts.acceptAll).click();

      expect(onSave.mock.calls[0]![0]).toEqual({
        necessary: true,
        preferences: true,
        statistics: true,
        marketing: true,
      });
    });

    test('Reject all denies everything except required', () => {
      const onSave = vi.fn();
      const root = open({ statistics: true }, { onSave });

      buttonByText(root, texts.rejectAll).click();

      expect(onSave.mock.calls[0]![0]).toEqual({
        necessary: true,
        preferences: false,
        statistics: false,
        marketing: false,
      });
    });
  });
});

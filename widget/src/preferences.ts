/**
 * Preferences panel: a modal dialog letting the visitor grant/deny each
 * non-necessary category individually ("granular consent", GDPR/ePrivacy) and
 * withdraw as easily as they gave.
 *
 * Rendered in its own Shadow DOM host so customer CSS can neither style nor
 * break it. Accessibility (WCAG 2.2, since consent UIs get regulator + user
 * scrutiny):
 *   - role="dialog" + aria-modal, labelled/described by its own heading + intro;
 *   - the rest of the page is made `inert` while open, so screen-reader, mouse
 *     and touch users are all confined to the dialog (aria-modal alone only
 *     constrains some AT; a JS Tab-trap only constrains the keyboard);
 *   - initial focus lands on the dialog container so its name/description are
 *     announced before the controls;
 *   - a pointer-operable Close control and backdrop click, plus ESC, so nobody
 *     is trapped without a way out that is not a consent choice (no dark pattern);
 *   - focus is restored to the invoking control on close, resolved *through* the
 *     shadow boundary (`document.activeElement` only ever sees the shadow host).
 * Each category is a real <input type="checkbox"> + <label>, operable and
 * announced without custom ARIA widgets.
 */

import { deepActiveElement } from './focus';
import type { ConsentDecision } from './consent-mode';
import type { BannerTexts, CategoryDef, WidgetConfig } from './config';

export interface PreferencesHandlers {
  /** Persist the visitor's per-category choices. */
  onSave: (categories: ConsentDecision) => void;
  /** Dismiss without changing anything (ESC, Close, or backdrop click). */
  onCancel: () => void;
}

const HOST_ID = 'complyr-prefs-host';
const FOCUSABLE = 'button, input:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** The control focused before the panel opened, restored on close. */
let previouslyFocused: HTMLElement | null = null;
/** Elements we set `inert` on while open, cleared verbatim on close. */
let inertedElements: HTMLElement[] = [];
/** The page's own `<html>` overflow, restored when the modal closes. */
let previousHtmlOverflow: string | null = null;

export function renderPreferences(
  config: WidgetConfig,
  texts: BannerTexts,
  lang: string,
  current: ConsentDecision,
  handlers: PreferencesHandlers,
): void {
  removePreferences();
  previouslyFocused = deepActiveElement();

  const host = document.createElement('div');
  host.id = HOST_ID;
  const root = host.attachShadow({ mode: 'open' });

  const style = document.createElement('style');
  style.textContent = buildStyles(config);

  const backdrop = document.createElement('div');
  backdrop.className = 'backdrop';

  const dialog = document.createElement('div');
  dialog.className = 'panel';
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-modal', 'true');
  dialog.setAttribute('aria-labelledby', 'complyr-prefs-title');
  dialog.setAttribute('aria-describedby', 'complyr-prefs-intro');
  // Declare the language of the panel's own text (WCAG 3.1.2) so AT pronounces
  // it correctly even when it differs from the host page's lang. `lang` is the
  // language resolved against what this site publishes (config.resolveLanguage),
  // never the raw browser preference — declaring a language we did not render in
  // is worse than declaring none.
  dialog.setAttribute('lang', lang);
  // Programmatically focusable (initial focus target) but not a Tab stop.
  dialog.tabIndex = -1;

  const header = document.createElement('div');
  header.className = 'header';

  const heading = document.createElement('h2');
  heading.id = 'complyr-prefs-title';
  heading.className = 'title';
  heading.textContent = texts.preferencesTitle;

  const closeButton = document.createElement('button');
  closeButton.type = 'button';
  closeButton.className = 'close';
  closeButton.setAttribute('aria-label', texts.close);
  closeButton.textContent = '✕'; // ✕
  closeButton.addEventListener('click', () => handlers.onCancel());

  header.append(heading, closeButton);

  const intro = document.createElement('p');
  intro.id = 'complyr-prefs-intro';
  intro.className = 'intro';
  intro.textContent = texts.message;

  const list = document.createElement('div');
  list.className = 'categories';
  const inputs = new Map<string, HTMLInputElement>();
  for (const category of config.categories) {
    const { row, input } = categoryRow(category, texts, current);
    inputs.set(category.id, input);
    list.appendChild(row);
  }

  const actions = document.createElement('div');
  actions.className = 'actions';
  actions.append(
    button(texts.save, 'primary', () =>
      handlers.onSave(collect(config.categories, inputs)),
    ),
    button(texts.acceptAll, 'ghost', () =>
      handlers.onSave(decisionFor(config.categories, true)),
    ),
    button(texts.rejectAll, 'ghost', () =>
      handlers.onSave(decisionFor(config.categories, false)),
    ),
  );

  dialog.append(header, intro, list, actions);
  backdrop.appendChild(dialog);
  root.append(style, backdrop);

  dialog.addEventListener('keydown', (event) =>
    onKeydown(event, root, handlers.onCancel),
  );
  // Click on the backdrop itself (outside the panel) dismisses — a pointer exit
  // that is not a consent choice.
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop) handlers.onCancel();
  });

  document.body.appendChild(host);
  makeBackgroundInert(host);
  // Focus the dialog container, not the first control, so its accessible name
  // and description are announced before the visitor reaches the toggles.
  dialog.focus();
}

export function removePreferences(): void {
  const host = document.getElementById(HOST_ID);
  if (!host) return;
  clearBackgroundInert();
  host.remove();
  // Return focus where it was, so keyboard users are not dumped at page top.
  previouslyFocused?.focus();
  previouslyFocused = null;
}

/** True while the panel is mounted. */
export function isPreferencesOpen(): boolean {
  return document.getElementById(HOST_ID) !== null;
}

/**
 * Make everything except the panel host inert while the modal is open, so
 * pointer, touch and screen-reader users cannot reach the page (or the banner)
 * behind it. We only touch elements we ourselves inert, and restore exactly
 * those on close — never clobbering a host-page `inert` that was already there.
 */
function makeBackgroundInert(host: HTMLElement): void {
  inertedElements = [];
  for (const child of Array.from(document.body.children)) {
    if (child === host || !(child instanceof HTMLElement)) continue;
    if (child.hasAttribute('inert')) continue;
    child.setAttribute('inert', '');
    inertedElements.push(child);
  }
  // `inert` disables interaction but not scrolling; lock the page behind the
  // modal so the background can't be wheel/touch-scrolled while it is open.
  previousHtmlOverflow = document.documentElement.style.overflow;
  document.documentElement.style.overflow = 'hidden';
}

function clearBackgroundInert(): void {
  for (const el of inertedElements) el.removeAttribute('inert');
  inertedElements = [];
  if (previousHtmlOverflow !== null) {
    document.documentElement.style.overflow = previousHtmlOverflow;
    previousHtmlOverflow = null;
  }
}

function categoryRow(
  category: CategoryDef,
  texts: BannerTexts,
  current: ConsentDecision,
): { row: HTMLElement; input: HTMLInputElement } {
  const label = texts.categoryLabels[category.id]?.label ?? category.id;
  const description = texts.categoryLabels[category.id]?.description ?? '';
  const inputId = `complyr-cat-${category.id}`;
  const descId = `${inputId}-desc`;

  const row = document.createElement('div');
  row.className = 'category';

  const input = document.createElement('input');
  input.type = 'checkbox';
  input.id = inputId;
  // Required categories are always granted and cannot be toggled off.
  input.checked = category.required || current[category.id] === true;
  input.disabled = category.required;
  if (description) input.setAttribute('aria-describedby', descId);

  const labelEl = document.createElement('label');
  labelEl.htmlFor = inputId;
  labelEl.className = 'category-label';
  labelEl.textContent = label;

  const labelRow = document.createElement('div');
  labelRow.className = 'category-label-row';
  labelRow.appendChild(labelEl);
  if (category.required) {
    // A visible, text-based explanation of why the toggle is fixed — not
    // conveyed by the disabled/greyed appearance alone (WCAG 1.4.1 / 3.3.2).
    const badge = document.createElement('span');
    badge.className = 'always-active';
    badge.textContent = texts.alwaysActive;
    labelRow.appendChild(badge);
  }

  const descEl = document.createElement('p');
  descEl.id = descId;
  descEl.className = 'category-desc';
  descEl.textContent = description;

  const text = document.createElement('div');
  text.className = 'category-text';
  text.append(labelRow, descEl);

  row.append(input, text);
  return { row, input };
}

/** Read the visitor's toggles; required categories are forced granted. */
function collect(
  categories: readonly CategoryDef[],
  inputs: Map<string, HTMLInputElement>,
): ConsentDecision {
  const decision: ConsentDecision = {};
  for (const category of categories) {
    decision[category.id] =
      category.required || inputs.get(category.id)?.checked === true;
  }
  return decision;
}

/** Grant/deny everything at once; required categories stay granted. */
function decisionFor(
  categories: readonly CategoryDef[],
  granted: boolean,
): ConsentDecision {
  const decision: ConsentDecision = {};
  for (const category of categories) {
    decision[category.id] = category.required || granted;
  }
  return decision;
}

function onKeydown(
  event: KeyboardEvent,
  root: ShadowRoot,
  onCancel: () => void,
): void {
  if (event.key === 'Escape') {
    event.preventDefault();
    onCancel();
    return;
  }
  if (event.key !== 'Tab') return;
  trapFocus(event, root);
}

/** Keep Tab / Shift+Tab cycling inside the dialog (WCAG 2.4.3 / 2.1.2). */
function trapFocus(event: KeyboardEvent, root: ShadowRoot): void {
  const focusable = Array.from(
    root.querySelectorAll<HTMLElement>(FOCUSABLE),
  );
  if (focusable.length === 0) return;
  const first = focusable[0]!;
  const last = focusable[focusable.length - 1]!;
  const active = root.activeElement;
  const index = active instanceof HTMLElement ? focusable.indexOf(active) : -1;

  // `index === -1` is the dialog container itself, which holds initial focus and is
  // not a Tab stop. Without treating it as "before the first", the very first
  // Shift+Tab a visitor presses walks out of the shadow root — into a page we just
  // made `inert`, so there is nothing to land on and no obvious way back (2.1.2).
  if (event.shiftKey && (index === -1 || index === 0)) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && index === focusable.length - 1) {
    event.preventDefault();
    first.focus();
  }
  // Forward Tab from the container needs no help: the browser's own order already
  // takes it to `first`, which is inside the dialog.
}

function button(
  label: string,
  variant: 'primary' | 'ghost',
  onClick: () => void,
): HTMLButtonElement {
  const el = document.createElement('button');
  el.type = 'button';
  el.className = variant;
  el.textContent = label;
  el.addEventListener('click', onClick);
  return el;
}

function buildStyles(config: WidgetConfig): string {
  const { colors } = config;
  return `
:host { all: initial; }
.backdrop {
  position: fixed; inset: 0; z-index: 2147483647;
  display: flex; align-items: center; justify-content: center;
  padding: 16px; background: rgba(0,0,0,.5);
}
.panel {
  width: 100%; max-width: 480px; max-height: calc(100vh - 32px); overflow: auto;
  padding: 24px; border-radius: 14px; box-shadow: 0 12px 40px rgba(0,0,0,.35);
  background: ${colors.background}; color: ${colors.text};
  font: 14px/1.55 system-ui, sans-serif;
}
.header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.title { margin: 0 0 8px; font-size: 18px; font-weight: 700; }
.close {
  flex: none; width: 32px; height: 32px; line-height: 1; font-size: 16px;
  display: flex; align-items: center; justify-content: center;
  background: transparent; color: ${colors.text}; border: 0; border-radius: 8px;
  cursor: pointer;
}
.intro { margin: 0 0 18px; }
.categories { display: flex; flex-direction: column; gap: 16px; margin-bottom: 20px; }
.category { display: flex; gap: 12px; align-items: flex-start; }
.category input {
  margin-top: 1px; width: 24px; height: 24px; flex: none;
  accent-color: ${colors.button}; cursor: pointer;
}
.category input:disabled { cursor: default; }
.category-label-row { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px; }
/* The label toggles the checkbox, so it is a target in its own right and needs the
   24px minimum (WCAG 2.5.8) — line-height, not padding, so the row stays aligned. */
.category-label { font-weight: 600; cursor: pointer; line-height: 24px; }
.category input:disabled + .category-text .category-label { cursor: default; }
/* Text in colors.text (the pair the backend holds to 4.5:1), outline in colors.button
   (held to 3:1). Painting the text in colors.button too would let a 3:1 brand color
   through as body copy. */
.always-active {
  font-size: 12px; font-weight: 600; padding: 1px 8px; border-radius: 999px;
  border: 1px solid ${colors.button}; color: ${colors.text};
}
.category-desc { margin: 2px 0 0; font-size: 13px; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
button {
  cursor: pointer; border: 0; border-radius: 8px; padding: 10px 16px;
  font: inherit; font-weight: 600;
}
button.primary { background: ${colors.button}; color: ${colors.buttonText}; }
button.ghost { background: transparent; color: ${colors.text}; text-decoration: underline; }
/* Focus ring must contrast with the element it rings. On the primary button the
   fill is colors.button, so the ring uses colors.buttonText (guaranteed legible
   against that fill); everywhere else the ring uses colors.text over the panel
   background. (WCAG 2.4.7 / 2.4.11) */
button:focus-visible, .close:focus-visible, .category input:focus-visible {
  outline: 2px solid ${colors.text}; outline-offset: 2px;
}
button.primary:focus-visible {
  outline-color: ${colors.buttonText}; outline-offset: -4px;
}
@media (prefers-reduced-motion: reduce) {
  * { transition: none !important; animation: none !important; }
}
`;
}

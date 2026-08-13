/**
 * Consent banner rendered inside a Shadow DOM host, so customer CSS cannot
 * break the banner and banner styles never leak out.
 *
 * Accept-all / reject-all / preferences entry, styled from config colors.
 * role="dialog" named by its heading; focus is moved into the banner on show
 * and restored on removal. Granular choices live in the preferences panel
 * (preferences.ts), which owns the modal focus trap and inert background.
 */

import { deepActiveElement } from './focus';
import type { BannerTexts, WidgetConfig } from './config';

export type BannerAction = 'accept_all' | 'reject_all';

export interface BannerHandlers {
  onAction: (action: BannerAction) => void;
  /** Open the granular preferences panel. */
  onPreferences: () => void;
}

const HOST_ID = 'complyr-host';

/** Where focus was before the banner appeared, restored when it is removed. */
let previouslyFocused: HTMLElement | null = null;

export function renderBanner(
  config: WidgetConfig,
  texts: BannerTexts,
  handlers: BannerHandlers,
): void {
  removeBanner();
  previouslyFocused = deepActiveElement();

  const host = document.createElement('div');
  host.id = HOST_ID;
  const root = host.attachShadow({ mode: 'open' });

  const style = document.createElement('style');
  style.textContent = buildStyles(config);

  const dialog = document.createElement('div');
  dialog.className = 'banner';
  dialog.setAttribute('role', 'dialog');
  // Named by its own heading; no aria-live (it would double-announce and
  // contradicts the dialog role).
  dialog.setAttribute('aria-labelledby', 'complyr-banner-title');

  const heading = document.createElement('h2');
  heading.id = 'complyr-banner-title';
  heading.className = 'title';
  heading.textContent = texts.title;

  const message = document.createElement('p');
  message.className = 'message';
  message.textContent = texts.message;

  const actions = document.createElement('div');
  actions.className = 'actions';
  const acceptButton = button(texts.acceptAll, 'primary', () =>
    handlers.onAction('accept_all'),
  );
  actions.append(
    acceptButton,
    button(texts.rejectAll, 'primary', () => handlers.onAction('reject_all')),
    button(texts.preferences, 'ghost', handlers.onPreferences),
  );

  dialog.append(heading, message, actions);
  // "Powered by Complyr" attribution, suppressed on plans that pay for branding
  // removal (config.removeBranding). Free tier shows it; a link, not a bare label,
  // so it is a real (keyboard-reachable) credit rather than decoration.
  if (!config.removeBranding) {
    const credit = document.createElement('a');
    credit.className = 'credit';
    credit.textContent = texts.poweredBy;
    credit.href = 'https://complyr.eu';
    credit.target = '_blank';
    credit.rel = 'noopener noreferrer';
    // The link opens a new tab; announce that so screen-reader and low-vision users aren't
    // surprised by the context switch (WCAG 3.2.5). Kept out of the visible label to avoid
    // cluttering the credit, and localised alongside the credit text itself.
    credit.setAttribute('aria-label', `${texts.poweredBy} ${texts.opensInNewTab}`);
    dialog.append(credit);
  }
  root.append(style, dialog);
  document.body.appendChild(host);

  // Focus handling stub: move focus to the first action so keyboard users
  // land in the banner. Full focus trap arrives with the preferences panel.
  acceptButton.focus();
}

export function removeBanner(): void {
  const host = document.getElementById(HOST_ID);
  if (!host) return;
  host.remove();
  // Return focus to wherever it was before the banner showed, so tearing the
  // banner down (e.g. after saving from the preferences panel) never dumps
  // keyboard/AT focus at the top of the page (WCAG 2.4.3).
  previouslyFocused?.focus();
  previouslyFocused = null;
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
  const { colors, position } = config;
  return `
:host { all: initial; }
.banner {
  position: fixed; left: 16px; right: 16px; ${position === 'top' ? 'top' : 'bottom'}: 16px;
  z-index: 2147483647;
  max-width: 720px; margin: 0 auto; padding: 20px;
  border-radius: 12px; box-shadow: 0 8px 30px rgba(0,0,0,.25);
  background: ${colors.background}; color: ${colors.text};
  font: 14px/1.5 system-ui, sans-serif;
}
.title { margin: 0 0 4px; font-size: 15px; font-weight: 600; }
.message { margin: 0 0 14px; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
button {
  cursor: pointer; border: 0; border-radius: 8px; padding: 9px 16px;
  font: inherit; font-weight: 600;
}
button.primary { background: ${colors.button}; color: ${colors.buttonText}; }
button.ghost { background: transparent; color: ${colors.text}; text-decoration: underline; }
.credit {
  display: inline-block; margin: 12px 0 0; font-size: 11px; opacity: .85;
  color: ${colors.text}; text-decoration: underline;
}
.credit:hover { opacity: 1; }
.credit:focus-visible { outline: 2px solid ${colors.text}; outline-offset: 2px; }
/* Ring contrasts with what it rings: colors.text over the panel, but the
   primary buttons' fill is colors.button, so their ring uses colors.buttonText. */
button:focus-visible { outline: 2px solid ${colors.text}; outline-offset: 2px; }
button.primary:focus-visible { outline-color: ${colors.buttonText}; outline-offset: -4px; }
`;
}

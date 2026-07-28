/**
 * Consent banner rendered inside a Shadow DOM host, so customer CSS cannot
 * break the banner and banner styles never leak out.
 *
 * Functional skeleton: accept-all / reject-all / preferences placeholder,
 * basic styling from config colors, minimal focus handling. Final design,
 * full preferences panel, and the a11y pass (focus trap, ESC, ARIA audit)
 * come in the widget-core milestone (ARCHITECTURE.md §13, W3).
 */

import type { BannerTexts, WidgetConfig } from './config';

export type BannerAction = 'accept_all' | 'reject_all';

export interface BannerHandlers {
  onAction: (action: BannerAction) => void;
  /** Preferences panel is a later milestone — stub hook for now. */
  onPreferences: () => void;
}

const HOST_ID = 'complyr-host';

export function renderBanner(
  config: WidgetConfig,
  texts: BannerTexts,
  handlers: BannerHandlers,
): void {
  removeBanner();

  const host = document.createElement('div');
  host.id = HOST_ID;
  const root = host.attachShadow({ mode: 'open' });

  const style = document.createElement('style');
  style.textContent = buildStyles(config);

  const dialog = document.createElement('div');
  dialog.className = 'banner';
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-live', 'polite');
  dialog.setAttribute('aria-label', texts.title);

  const heading = document.createElement('p');
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
  root.append(style, dialog);
  document.body.appendChild(host);

  // Focus handling stub: move focus to the first action so keyboard users
  // land in the banner. Full focus trap arrives with the preferences panel.
  acceptButton.focus();
}

export function removeBanner(): void {
  document.getElementById(HOST_ID)?.remove();
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
button:focus-visible { outline: 2px solid ${colors.buttonText}; outline-offset: 2px; }
`;
}

/**
 * Embeddable cookie table (ADR-27).
 *
 * A customer whose cookie policy page has been through a lawyer does not want to
 * replace it with our hosted `/p/{publicId}` page — but the cookie list inside it
 * goes stale the moment a scan finds something new. So they mark the spot:
 *
 *   <div data-complyr-policy></div>
 *
 * and the widget paints the current list there, in the language their page
 * declares, with the same wording, ordering and fallbacks the generated document
 * uses (the server resolves all three — see CookieTableReadService).
 *
 * Three properties this must not lose:
 *
 * - **Never blocks the host page.** The fetch is fired independently of the banner
 *   and awaited by nothing; a failure leaves whatever the customer already had in
 *   the element (their pasted snapshot makes a perfectly good fallback).
 * - **No HTML sink.** Every node is built with createElement/textContent, like the
 *   rest of the widget. We could have served rendered HTML and assigned innerHTML
 *   for fewer bytes, but that would put an HTML sink on every visitor's page for
 *   an identical result.
 * - **Costs nothing on ordinary pages.** No marker element → no request at all.
 *
 * The markup mirrors the copyable embed's classes (`cmplyr-policy`,
 * `cmplyr-policy-table`, `cmplyr-policy-updated`), so CSS a customer already wrote
 * for the pasted version styles the live one unchanged. Light DOM on purpose: this
 * is the customer's content and must inherit the customer's page styles, unlike
 * the banner, which is isolated in a shadow root.
 */

import { API_BASE } from './constants';
import { warn } from './debug';

/** Marks the element(s) to fill; its value optionally forces the language. */
const POLICY_ATTR = 'data-complyr-policy';

/** Optional `h2`–`h6` override, for a page whose surrounding heading level differs. */
const HEADING_ATTR = 'data-complyr-policy-heading';

const DEFAULT_HEADING_LEVEL = 2;
const MIN_HEADING_LEVEL = 2;
const MAX_HEADING_LEVEL = 6;

/** Same budget as the config fetch — nothing on the page waits for either. */
const TABLE_FETCH_TIMEOUT_MS = 4000;

interface CookieTableRow {
  name: string;
  provider: string;
  expiry: string;
}

interface CookieTableSection {
  heading: string;
  description: string;
  cookies: CookieTableRow[];
}

interface CookieTableLabels {
  name: string;
  provider: string;
  expiry: string;
  updated: string;
  noCookies: string;
}

interface CookieTable {
  language: string;
  scannedOn: string | null;
  labels: CookieTableLabels;
  sections: CookieTableSection[];
}

/**
 * Fill every `[data-complyr-policy]` element on the page. Returns immediately when
 * there are none, so a site that never uses the feature pays no request.
 *
 * Waits for DOMContentLoaded when the document is still parsing: the embed script
 * is async and may well run before the marker element exists.
 */
export async function mountPolicyTables(siteKey: string): Promise<void> {
  await documentReady();
  const targets = document.querySelectorAll<HTMLElement>(`[${POLICY_ATTR}]`);
  if (targets.length === 0) return;

  const table = await fetchCookieTable(siteKey, requestedLanguage(targets[0]!));
  if (!table) return;
  for (const target of targets) render(target, table);
}

function documentReady(): Promise<void> {
  if (document.readyState !== 'loading') return Promise.resolve();
  return new Promise((resolve) => {
    document.addEventListener('DOMContentLoaded', () => resolve(), {
      once: true,
    });
  });
}

/**
 * The language to ask for: the marker's own value first, then the language the
 * page declares, and only then the browser's. A legal document belongs to the page
 * it sits in — a German page read in a French browser is still German.
 *
 * Returns null when nothing usable is declared, letting the server pick.
 */
function requestedLanguage(target: HTMLElement): string | null {
  const candidates = [
    target.getAttribute(POLICY_ATTR),
    document.documentElement.getAttribute('lang'),
    navigator.language,
  ];
  for (const candidate of candidates) {
    const short = candidate?.trim().slice(0, 2).toLowerCase();
    if (short && /^[a-z]{2}$/.test(short)) return short;
  }
  return null;
}

async function fetchCookieTable(
  siteKey: string,
  lang: string | null,
): Promise<CookieTable | null> {
  const controller =
    typeof AbortController === 'function' ? new AbortController() : null;
  const timer = controller
    ? setTimeout(() => controller.abort(), TABLE_FETCH_TIMEOUT_MS)
    : null;
  const query = lang ? `?lang=${lang}` : '';
  try {
    const response = await fetch(
      `${API_BASE}/api/v1/public/cookie-table/${encodeURIComponent(siteKey)}${query}`,
      controller ? { signal: controller.signal } : undefined,
    );
    if (!response.ok) {
      warn(`cookie table fetch returned ${response.status}; leaving the page as-is`);
      return null;
    }
    const body = (await response.json()) as { data?: unknown };
    const table = body.data;
    if (!isCookieTable(table)) {
      warn('cookie table failed validation; leaving the page as-is');
      return null;
    }
    return table;
  } catch (error) {
    warn('cookie table fetch failed; leaving the page as-is', error);
    return null;
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/**
 * Replace the target's contents with the live table. Replacing rather than
 * appending is what makes a pasted snapshot a real fallback: it shows when we
 * can't answer, and is superseded the moment we can.
 */
function render(target: HTMLElement, table: CookieTable): void {
  const block = document.createElement('div');
  block.className = 'cmplyr-policy';
  block.lang = table.language;

  if (table.scannedOn) {
    const updated = document.createElement('p');
    updated.className = 'cmplyr-policy-updated';
    updated.textContent = `${table.labels.updated}: ${table.scannedOn}`;
    block.appendChild(updated);
  }

  if (table.sections.length === 0) {
    const empty = document.createElement('p');
    empty.textContent = table.labels.noCookies;
    block.appendChild(empty);
  } else {
    const heading = headingTag(target);
    for (const section of table.sections) {
      appendSection(block, section, table.labels, heading);
    }
  }

  target.replaceChildren(block);
}

/** `h2` unless the page asks for a deeper level, so the block nests correctly. */
function headingTag(target: HTMLElement): string {
  const raw = Number(target.getAttribute(HEADING_ATTR)?.replace(/^h/i, ''));
  const level =
    Number.isInteger(raw) && raw >= MIN_HEADING_LEVEL && raw <= MAX_HEADING_LEVEL
      ? raw
      : DEFAULT_HEADING_LEVEL;
  return `h${level}`;
}

function appendSection(
  block: HTMLElement,
  section: CookieTableSection,
  labels: CookieTableLabels,
  headingTagName: string,
): void {
  const heading = document.createElement(headingTagName);
  heading.textContent = section.heading;
  block.appendChild(heading);

  const description = document.createElement('p');
  description.textContent = section.description;
  block.appendChild(description);

  const table = document.createElement('table');
  table.className = 'cmplyr-policy-table';
  // A caption ties each table to its category for a screen reader that jumps
  // straight to it; visually it repeats the heading, so it is hidden from sight
  // the way a page's own CSS can't accidentally undo.
  const caption = document.createElement('caption');
  caption.textContent = section.heading;
  caption.style.cssText =
    'position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap';
  table.appendChild(caption);

  const head = document.createElement('thead');
  head.appendChild(row('th', [labels.name, labels.provider, labels.expiry]));
  table.appendChild(head);

  const body = document.createElement('tbody');
  for (const cookie of section.cookies) {
    body.appendChild(row('td', [cookie.name, cookie.provider, cookie.expiry]));
  }
  table.appendChild(body);
  block.appendChild(table);
}

function row(cellTag: 'th' | 'td', values: string[]): HTMLTableRowElement {
  const tr = document.createElement('tr');
  for (const value of values) {
    const cell = document.createElement(cellTag);
    if (cellTag === 'th') cell.setAttribute('scope', 'col');
    cell.textContent = value;
    tr.appendChild(cell);
  }
  return tr;
}

function isCookieTable(value: unknown): value is CookieTable {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate['language'] === 'string' &&
    isLabels(candidate['labels']) &&
    Array.isArray(candidate['sections']) &&
    candidate['sections'].every(isSection)
  );
}

function isLabels(value: unknown): value is CookieTableLabels {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (['name', 'provider', 'expiry', 'updated', 'noCookies'] as const).every(
    (key) => typeof candidate[key] === 'string',
  );
}

function isSection(value: unknown): value is CookieTableSection {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate['heading'] === 'string' &&
    typeof candidate['description'] === 'string' &&
    Array.isArray(candidate['cookies']) &&
    candidate['cookies'].every(isRow)
  );
}

function isRow(value: unknown): value is CookieTableRow {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (['name', 'provider', 'expiry'] as const).every(
    (key) => typeof candidate[key] === 'string',
  );
}

/**
 * Prior script blocking (GDPR / ePrivacy Art. 5(3)): third-party tags must NOT
 * run before the visitor consents. The site owner neutralises a tag by setting
 * type="text/plain" — browsers never execute those — and tagging its category:
 *
 *   <script type="text/plain" data-complyr-category="statistics"
 *           data-src="https://example.com/analytics.js"></script>
 *
 *   <script type="text/plain" data-complyr-category="marketing">
 *     // inline pixel / tracker
 *   </script>
 *
 * When consent is granted for a category, unblockScripts() swaps each matching
 * placeholder for a real, executing <script>. Denied categories stay inert.
 *
 * The swap is one-shot: an activated tag is no longer a `text/plain` placeholder,
 * so re-running after a preferences change never double-executes a script. The
 * widget only ever un-neutralises markup the site owner already placed on their
 * own page — it never injects script content of its own.
 *
 * SECURITY NOTE (accepted risk, inherent to the consent model): promoting a
 * `text/plain` placeholder to a live <script> intentionally runs code the host
 * page authored. If an attacker can inject a `text/plain` + data-complyr-category
 * placeholder into the page (a host-side XSS/HTML-injection sink), consent then
 * executes it. We narrow the surface here — control-only attribute copy, on*
 * handler stripping — but the real defence is the host page's CSP and output
 * encoding. A future author-configured `data-src` origin allowlist would harden
 * this further.
 *
 * Activation is forward-only: once a category's tags run they cannot be un-run
 * without a page reload. Withdrawing consent (preferences panel, Slice 4) must
 * therefore trigger a reload — re-blocking a loaded tracker in-place is not
 * possible.
 */

import { warn } from './debug';

const BLOCKED_TYPE = 'text/plain';
const CATEGORY_ATTR = 'data-complyr-category';
const SRC_ATTR = 'data-src';
/** Optional executable type override, e.g. data-complyr-type="module". */
const TYPE_ATTR = 'data-complyr-type';

/** Attributes that drive blocking and must not be copied onto the live script. */
const CONTROL_ATTRS: ReadonlySet<string> = new Set([
  'type',
  'src',
  CATEGORY_ATTR,
  SRC_ATTR,
  TYPE_ATTR,
]);

/** Every placeholder script still awaiting consent, in document order. */
export function blockedScripts(): HTMLScriptElement[] {
  const selector = `script[type="${BLOCKED_TYPE}"][${CATEGORY_ATTR}]`;
  return Array.from(document.querySelectorAll<HTMLScriptElement>(selector));
}

/**
 * Every category id that appears on a placeholder still awaiting consent, read
 * straight off the page. Used only by the region gate in main.ts: a visitor we
 * deliberately show no banner to has no decision to derive categories from, and
 * leaving their tags inert forever would silently break the site owner's
 * analytics outside the EEA — the opposite of what opting into region targeting
 * asks for.
 */
export function allBlockedCategories(): Set<string> {
  const categories = new Set<string>();
  for (const placeholder of blockedScripts()) {
    const category = placeholder.getAttribute(CATEGORY_ATTR);
    if (category) categories.add(category);
  }
  return categories;
}

/** The category ids a decision grants (value === true). */
export function grantedCategories(
  categories: Record<string, boolean>,
): Set<string> {
  const granted = new Set<string>();
  for (const [id, on] of Object.entries(categories)) {
    if (on === true) granted.add(id);
  }
  return granted;
}

/**
 * Execute every blocked script whose category is in [granted], in document
 * order; leave the rest inert. Safe to call repeatedly — already-activated
 * scripts are no longer placeholders and are not touched again.
 */
export function unblockScripts(granted: ReadonlySet<string>): void {
  for (const placeholder of blockedScripts()) {
    const category = placeholder.getAttribute(CATEGORY_ATTR);
    if (!category || !granted.has(category)) continue;
    try {
      activate(placeholder);
    } catch (error) {
      // Isolate per tag: a single malformed placeholder must not abort the
      // remaining consented scripts, nor propagate up to the caller (commit)
      // and take the audit-event send down with it.
      warn('failed to activate a consented script', error);
    }
  }
}

/** Inline event-handler attributes (onload, onerror, …) — never promoted. */
function isEventHandlerAttr(name: string): boolean {
  // Case-insensitive: HTML attribute names are lowercased on parse, but XHTML
  // (application/xhtml+xml) preserves case, so `onLoad` would slip a raw
  // startsWith('on') check. Normalise before comparing.
  return name.length > 2 && name.toLowerCase().startsWith('on');
}

/** Replace a `text/plain` placeholder with a live, executing <script>. */
function activate(placeholder: HTMLScriptElement): void {
  const parent = placeholder.parentNode;
  if (!parent) return;

  const script = document.createElement('script');
  for (const attr of Array.from(placeholder.attributes)) {
    // Skip control attributes, and never carry inline event handlers (onload,
    // onerror, …). createElement('script') executes even markup a sanitizer let
    // through as inert text/plain data, so copying an author's on* handlers
    // would hand an XSS vector to any host injection sink — drop them.
    if (CONTROL_ATTRS.has(attr.name) || isEventHandlerAttr(attr.name)) continue;
    script.setAttribute(attr.name, attr.value);
  }

  // The CSP nonce lives on the IDL property, not the content attribute (browsers
  // blank the attribute after parsing as an anti-exfiltration measure), so copy
  // it explicitly — otherwise the promoted script is blocked under a nonce CSP.
  if (placeholder.nonce) script.nonce = placeholder.nonce;

  const explicitType = placeholder.getAttribute(TYPE_ATTR);
  if (explicitType) script.type = explicitType;

  const src = placeholder.getAttribute(SRC_ATTR) ?? placeholder.getAttribute('src');
  if (src) {
    // Preserve author order for dependent tags — dynamically created scripts
    // are async by default; async=false runs them in insertion order.
    script.async = false;
    script.src = src;
  } else {
    script.textContent = placeholder.textContent;
  }

  parent.replaceChild(script, placeholder);
}

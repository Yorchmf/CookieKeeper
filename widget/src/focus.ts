/**
 * Focus helpers shared by the banner and preferences panel.
 *
 * The widget renders inside open Shadow DOM, so `document.activeElement`
 * resolves only to the shadow *host*, not the real focused control. To capture
 * and later restore focus correctly we must descend the shadow tree.
 */

/**
 * The genuinely focused element, descending through open shadow roots.
 *
 * Returns null when nothing is meaningfully focused (i.e. focus is on `<body>`
 * or absent). Callers use the result to *restore* focus later, and restoring to
 * `<body>` is a no-op that reads as "focus lost to page top" — so we report no
 * target instead, letting the caller's `?.focus()` skip harmlessly.
 */
export function deepActiveElement(): HTMLElement | null {
  let active: Element | null = document.activeElement;
  while (active?.shadowRoot?.activeElement) {
    active = active.shadowRoot.activeElement;
  }
  if (!(active instanceof HTMLElement) || active === document.body) return null;
  return active;
}

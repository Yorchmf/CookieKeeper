/**
 * WCAG 2.2 contrast math for the banner customizer (ADR-28).
 *
 * A deliberate second implementation of `eu.cookiekeeper.banner.ColorContrast`: the backend's copy
 * is the one that decides — it returns 400 on a theme that fails — and this one exists only so the
 * customizer can say so *before* the save. The thresholds below must stay identical to the
 * backend's; if they ever drift, the symptom is a rejected save with no inline warning, which is
 * exactly the experience this readout is here to prevent.
 */

/** WCAG 1.4.3 AA — body text (the banner message, category labels). */
export const AA_NORMAL_TEXT = 4.5;

/** WCAG 1.4.11 AA — UI component boundaries and states (button fill, checkbox, badge outline). */
export const AA_NON_TEXT = 3.0;

const CONTRAST_OFFSET = 0.05;

/**
 * Contrast ratio between two `#RGB`/`#RRGGBB` colors, or `null` when either is not a hex color
 * the backend would accept. Callers must treat `null` as "cannot judge", never as a pass — the
 * customizer's inputs are free text, so a half-typed `#ab` lands here on every keystroke.
 */
export function contrastRatio(first: string, second: string): number | null {
  const a = relativeLuminance(first);
  const b = relativeLuminance(second);
  if (a === null || b === null) {
    return null;
  }
  return (Math.max(a, b) + CONTRAST_OFFSET) / (Math.min(a, b) + CONTRAST_OFFSET);
}

function relativeLuminance(color: string): number | null {
  const hex = sixDigitHex(color);
  if (hex === null) {
    return null;
  }
  const value = Number.parseInt(hex, 16);
  const r = linearize(((value >> 16) & 0xff) / 255);
  const g = linearize(((value >> 8) & 0xff) / 255);
  const b = linearize((value & 0xff) / 255);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function sixDigitHex(color: string): string | null {
  const trimmed = color.trim();
  if (/^#[0-9a-f]{6}$/i.test(trimmed)) {
    return trimmed.slice(1);
  }
  if (/^#[0-9a-f]{3}$/i.test(trimmed)) {
    const [, r, g, b] = trimmed;
    return `${r}${r}${g}${g}${b}${b}`;
  }
  return null;
}

function linearize(channel: number): number {
  return channel <= 0.03928
    ? channel / 12.92
    : Math.pow((channel + 0.055) / 1.055, 2.4);
}

/**
 * Opt-in diagnostics. The widget must never break or spam the host page, so it
 * is SILENT by default: nothing is logged unless the integrator explicitly
 * enables it with `<script ... data-complyr-debug>` or `window.__complyrDebug`.
 *
 * This is the widget's only observability channel. Every degrade-gracefully
 * path (missing siteKey, config-fetch fallback, failed banner render, failed
 * cookie write, failed audit send) routes a `warn()` here so a broken embed is
 * diagnosable without a network round-trip or any PII.
 */

let enabled = false;

export function setDebug(on: boolean): void {
  enabled = on;
}

export function warn(message: string, detail?: unknown): void {
  if (!enabled) return;
  // Gated behind explicit opt-in; not a production console statement.
  console.warn(`[Complyr] ${message}`, detail ?? '');
}

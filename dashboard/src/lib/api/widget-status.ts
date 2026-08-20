/**
 * Typed client for `GET /api/v1/sites/{id}/widget-status` — "is the banner actually live on my site?".
 *
 * Read-only and derived from the banner-impression counter the widget already feeds, which bounds what
 * the UI may claim (see the backend's WidgetStatusService): the signal is day-grained, and only visitors
 * who have NOT yet chosen render a banner, so quiet is ambiguous rather than broken.
 */
import { apiFetch } from "@/lib/api";

/**
 * - `never_seen` — no impression has ever been recorded; nothing has confirmed the install yet.
 * - `active` — an impression landed inside the window; the widget is installed and rendering.
 * - `idle` — impressions exist but none recently. Ambiguous: could be no new visitors, could be a
 *   snippet that was removed. The copy must offer both readings.
 */
export type WidgetStatusState = "never_seen" | "active" | "idle";

export interface WidgetStatus {
  state: WidgetStatusState;
  /** UTC calendar day (`YYYY-MM-DD`), not a timestamp — the counter stores no finer grain. */
  lastSeenDay: string | null;
  impressionsToday: number;
  impressionsInWindow: number;
  /** The window the backend applied, so the copy states the same number the verdict used. */
  windowDays: number;
}

export async function getWidgetStatus(siteId: string): Promise<WidgetStatus> {
  const { data } = await apiFetch<WidgetStatus>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/widget-status`,
  );
  return data;
}

/**
 * Typed client for the site consent audit log:
 *   GET /api/v1/sites/{siteId}/consent-events           — keyset-paginated JSON read
 *   GET /api/v1/sites/{siteId}/consent-events/export.csv — Business-plan CSV stream
 *
 * The backend deliberately never sends `ipHash` or the trimmed user agent to the browser, so neither
 * appears on {@link ConsentEvent}. The CSV export enforces the Business-plan gate server-side (403);
 * the dashboard gate on it is display-only.
 */
import { apiFetch } from "@/lib/api";

/** The consent actions the filter UI and URL accept — single source of truth for the union + guard. */
export const CONSENT_ACTIONS = ["accept_all", "reject_all", "custom"] as const;

/** Mirrors the backend `ck_consent_events_action` check constraint. */
export type ConsentAction = (typeof CONSENT_ACTIONS)[number];

/**
 * Narrow an untrusted string (URL param, DOM value) to a {@link ConsentAction}, or undefined if it is
 * not one of the known actions. The URL is user-controlled, so `?action=foo` must not be trusted as a
 * valid literal — validating here keeps client state honest rather than leaning on the backend to reject it.
 */
export function parseConsentAction(raw: string | null | undefined): ConsentAction | undefined {
  return raw && (CONSENT_ACTIONS as readonly string[]).includes(raw)
    ? (raw as ConsentAction)
    : undefined;
}

/** One consent event as the audit log surfaces it (backend `ConsentEventLogResponse`). */
export interface ConsentEvent {
  eventId: string;
  visitorId: string;
  action: ConsentAction;
  categories: Record<string, boolean>;
  bannerVersion: number | null;
  policyVersion: number | null;
  lang: string | null;
  createdAt: string;
}

/**
 * User-facing filters (mirrors the backend `ConsentLogFilter` minus the `cursor`/`limit` paging
 * internals). `from` is inclusive, `to` exclusive; both are ISO-8601 instants. Empty fields are omitted.
 */
export interface ConsentLogFilters {
  from?: string;
  to?: string;
  action?: ConsentAction;
  lang?: string;
  visitorId?: string;
}

/**
 * Whether any filter field is set. An empty log with active filters is a "nothing matched — clear the
 * filters" state, not a genuine "no consent events yet" state; the two need different empty-state copy
 * and actions. Kept next to `buildConsentParams` so both derive "active" from the exact same field set.
 */
export function hasActiveConsentFilters(filters: ConsentLogFilters): boolean {
  return Boolean(
    filters.from || filters.to || filters.action || filters.lang || filters.visitorId,
  );
}

/** A keyset page of events plus the cursor for the next (older) page — `null` on the last page. */
export interface ConsentEventsPage {
  events: ConsentEvent[];
  nextCursor: string | null;
}

/**
 * Serialize the active filters into query params (empty fields dropped). Shared by the JSON read, the
 * CSV export href, and the URL-as-state writer so all three stay in lockstep on the exact param set.
 */
export function buildConsentParams(filters: ConsentLogFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.from) params.set("from", filters.from);
  if (filters.to) params.set("to", filters.to);
  if (filters.action) params.set("action", filters.action);
  if (filters.lang) params.set("lang", filters.lang);
  if (filters.visitorId) params.set("visitorId", filters.visitorId);
  return params;
}

/** Fetch one keyset page of the site's consent log, newest-first. `cursor` continues a prior page. */
export async function listConsentEvents(
  siteId: string,
  filters: ConsentLogFilters,
  cursor?: string,
): Promise<ConsentEventsPage> {
  const params = buildConsentParams(filters);
  if (cursor) params.set("cursor", cursor);
  const query = params.toString();
  const { data, meta } = await apiFetch<ConsentEvent[]>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/consent-events${query ? `?${query}` : ""}`,
  );
  return { events: data, nextCursor: meta?.nextCursor ?? null };
}

/**
 * Same-origin path for the Business-plan CSV export with the active filters applied. Used as an
 * `<a download>` href so the browser streams the file straight from the backend (auth cookies attach
 * automatically). The export carries no cursor/limit — it is the whole filtered set, newest-first.
 */
export function consentExportPath(siteId: string, filters: ConsentLogFilters): string {
  const query = buildConsentParams(filters).toString();
  return `/api/v1/sites/${encodeURIComponent(siteId)}/consent-events/export.csv${query ? `?${query}` : ""}`;
}

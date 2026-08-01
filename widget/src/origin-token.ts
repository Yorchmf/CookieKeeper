/**
 * Consent-origin token (client half of ADR-13).
 *
 * On banner show the widget fetches a short-lived HMAC token from
 * `GET /api/v1/consent-token/{siteKey}` and attaches it to the LIVE consent POST. It proves the
 * post came from a real page load on this origin, so the backend can reject a replayed `curl` of a
 * captured payload. The token is OPTIONAL end-to-end: fetching is best-effort and never throws, and
 * a consent event is always sent with or without one — losing audit evidence would be worse than the
 * bounded replay window the token closes.
 *
 * Two rules keep a token from ever causing a legitimate event to be REJECTED (a 400 the server
 * returns for a present-but-invalid token):
 *  1. We attach a token only while it is comfortably fresh ([TOKEN_MAX_AGE_MS] < the server TTL), so
 *     client→server latency and modest clock skew can't push it past expiry in flight.
 *  2. The retry queue (see api.ts) never stores a token; a token is read fresh at POST time, so a
 *     delayed replay simply goes out tokenless (and records) rather than carrying an expired one.
 */

import { API_BASE } from './constants';
import { warn } from './debug';

/**
 * Attach a token only if fetched within this window. Kept safely under the server's ~120s TTL so a
 * token is never sent once it's within the danger zone of expiring mid-flight; a slightly-too-old
 * token is simply omitted and the event goes out tokenless (still recorded).
 */
export const TOKEN_MAX_AGE_MS = 90_000;

/** Abort a slow mint fetch — it must never delay the consent POST or hold the page. */
const TOKEN_FETCH_TIMEOUT_MS = 3_000;

interface HeldToken {
  token: string;
  fetchedAt: number;
}

let held: HeldToken | null = null;

function endpoint(siteKey: string): string {
  return `${API_BASE}/api/v1/consent-token/${encodeURIComponent(siteKey)}`;
}

/**
 * Best-effort fetch of an origin token for [siteKey], stored for a later consent POST. Fire-and-
 * forget: swallows every error (network, non-2xx, abort, unusable body) and simply leaves no token
 * held, in which case the consent event is sent without one. Never throws.
 */
export async function fetchOriginToken(siteKey: string): Promise<void> {
  // The 3s abort guard needs AbortController. On the rare engine without it we still fetch, just
  // without the timeout — acceptable because this call is already fire-and-forget off the render
  // path (main.ts never awaits it), so a slow request delays no consent POST and blocks no UI.
  const controller =
    typeof AbortController === 'function' ? new AbortController() : null;
  const timer = controller
    ? setTimeout(() => controller.abort(), TOKEN_FETCH_TIMEOUT_MS)
    : null;
  try {
    const response = await fetch(
      endpoint(siteKey),
      controller ? { signal: controller.signal } : undefined,
    );
    if (!response.ok) {
      warn(`consent-token endpoint returned ${response.status}; sending consent without a token`);
      return;
    }
    const body: unknown = await response.json();
    const token = extractToken(body);
    if (token) {
      held = { token, fetchedAt: Date.now() };
    }
  } catch (error: unknown) {
    warn('consent-token fetch failed; sending consent without a token', error);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/**
 * The held token if it is still fresh enough to attach, else undefined. Freshness uses the same
 * device clock for `fetchedAt` and `now`, so a stable offset cancels; the margin under the server
 * TTL absorbs the drift that assumption can't guarantee away.
 */
export function freshOriginToken(): string | undefined {
  if (held && Date.now() - held.fetchedAt < TOKEN_MAX_AGE_MS) {
    return held.token;
  }
  return undefined;
}

/** Test seam: drop any held token so module state doesn't leak across cases. */
export function clearOriginToken(): void {
  held = null;
}

/** Pull `data.token` out of the `{ success, data, error, meta }` envelope, or null if unusable. */
function extractToken(body: unknown): string | null {
  if (typeof body !== 'object' || body === null) return null;
  const data = (body as Record<string, unknown>)['data'];
  if (typeof data !== 'object' || data === null) return null;
  const token = (data as Record<string, unknown>)['token'];
  return typeof token === 'string' && token.length > 0 ? token : null;
}

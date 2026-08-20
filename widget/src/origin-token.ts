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

/**
 * The visitor's region bucket from the last mint response — `'gdpr'`, `'other'`, or null when the
 * server could not tell (or was never asked). Read by main.ts for the optional banner gate; null
 * always means "show the banner".
 */
let region: string | null = null;

function endpoint(siteKey: string): string {
  return `${API_BASE}/api/v1/consent-token/${encodeURIComponent(siteKey)}`;
}

/**
 * Best-effort fetch of an origin token for [siteKey], stored for a later consent POST, together
 * with the region bucket the response carries. Swallows every error (network, non-2xx, abort,
 * timeout, unusable body) and simply leaves no token and no region held. Never throws, and never
 * runs longer than [TOKEN_FETCH_TIMEOUT_MS] — main.ts awaits it on the region-gated path, so an
 * unbounded request here would be an unbounded delay before the banner appears.
 */
export async function fetchOriginToken(siteKey: string): Promise<void> {
  // Prefer a real abort so a timed-out request stops consuming the connection; fall back to racing
  // the promise on engines without AbortController, which at least bounds the wait.
  const controller =
    typeof AbortController === 'function' ? new AbortController() : null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const expiry = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      controller?.abort();
      reject(new Error('consent-token fetch timed out'));
    }, TOKEN_FETCH_TIMEOUT_MS);
  });
  try {
    const response = await Promise.race([
      fetch(endpoint(siteKey), controller ? { signal: controller.signal } : undefined),
      expiry,
    ]);
    if (!response.ok) {
      warn(`consent-token endpoint returned ${response.status}; sending consent without a token`);
      return;
    }
    const body: unknown = await Promise.race([response.json() as Promise<unknown>, expiry]);
    const token = extractToken(body);
    if (token) {
      held = { token, fetchedAt: Date.now() };
    }
    region = extractRegion(body);
  } catch (error: unknown) {
    warn('consent-token fetch failed; sending consent without a token', error);
  } finally {
    clearTimeout(timer);
  }
}

/**
 * The visitor's region bucket, or null when unknown. Callers must treat null as "in scope": we
 * only ever suppress a banner on a positive `'other'`.
 */
export function visitorRegion(): string | null {
  return region;
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
  region = null;
}

/** Pull `data.token` out of the `{ success, data, error, meta }` envelope, or null if unusable. */
function extractToken(body: unknown): string | null {
  return envelopeString(body, 'token');
}

/** Pull `data.region` out of the same envelope. Absent, null or non-string all mean "unknown". */
function extractRegion(body: unknown): string | null {
  return envelopeString(body, 'region');
}

/** A non-empty string field of `data` in the `{ success, data, error, meta }` envelope. */
function envelopeString(body: unknown, field: string): string | null {
  if (typeof body !== 'object' || body === null) return null;
  const data = (body as Record<string, unknown>)['data'];
  if (typeof data !== 'object' || data === null) return null;
  const value = (data as Record<string, unknown>)[field];
  return typeof value === 'string' && value.length > 0 ? value : null;
}

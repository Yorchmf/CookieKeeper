/**
 * Consent event reporting → POST /api/v1/consent.
 *
 * Consent events are append-only audit evidence (CLAUDE.md constraint #3), so
 * losing one is a compliance defect, not a cosmetic glitch. Delivery is still
 * fire-and-forget from the visitor's point of view — the widget never blocks
 * the host page or surfaces a network error — but a send that we can observe
 * failing is persisted to a small localStorage queue and retried on the next
 * page load (`flushPendingEvents`, called from init). sendBeacon is preferred
 * so events survive page unload; fetch(keepalive) is the observable fallback.
 */

import { API_BASE } from './constants';
import type { ConsentDecision } from './consent-mode';
import { warn } from './debug';

export interface ConsentEventPayload {
  siteKey: string;
  action: 'accept_all' | 'reject_all' | 'custom';
  categories: ConsentDecision;
  lang: string;
  ts: number;
  /** Stable per-browser id (UUID) for audit correlation. */
  vid: string;
}

const PENDING_KEY = 'cmplyr_pending';
/** Hard cap on the retry queue so a permanently-offline visitor can't grow it unbounded. */
const MAX_PENDING = 20;

function endpoint(): string {
  return `${API_BASE}/api/v1/consent`;
}

/**
 * Report a consent event via fetch(keepalive). If delivery can be observed to
 * have failed (fetch rejects or returns a non-2xx), the event is queued for
 * retry on the next load. Never throws to the caller.
 *
 * keepalive: true gives the same "survive page unload" semantics as sendBeacon
 * for small payloads, while keeping the full CORS flow observable so failures
 * are caught and queued rather than silently dropped. Privacy browsers
 * (e.g. Brave) block sendBeacon cross-origin; fetch is unaffected.
 */
export function sendConsentEvent(payload: ConsentEventPayload): void {
  postWithRetry(endpoint(), JSON.stringify(payload), payload);
}

/**
 * Re-attempt delivery of any events queued by a previous failed send. Called
 * once from init(), best-effort; drains entries only as the server confirms
 * them, and never throws.
 */
export function flushPendingEvents(): void {
  const pending = readPending();
  if (pending.length === 0) return;

  // Clear the store up front, then re-queue whatever fails, so a flush that
  // races another send can't duplicate-then-drop entries.
  writePending([]);
  for (const payload of pending) {
    postWithRetry(endpoint(), JSON.stringify(payload), payload);
  }
}

/** POST via fetch; re-queue the payload on a rejected request or non-2xx response. */
function postWithRetry(
  url: string,
  body: string,
  payload: ConsentEventPayload,
): void {
  void fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
    keepalive: true,
  })
    .then((response) => {
      if (!response.ok) {
        warn(`consent endpoint returned ${response.status}; queued for retry`);
        enqueuePending(payload);
      }
    })
    .catch((error: unknown) => {
      warn('consent event POST failed; queued for retry', error);
      enqueuePending(payload);
    });
}

function enqueuePending(payload: ConsentEventPayload): void {
  const pending = readPending();
  // Keep the most recent events; a very stale queue is less valuable than a
  // bounded one, and the backend de-duplicates on (vid, ts).
  const next = [...pending, payload].slice(-MAX_PENDING);
  writePending(next);
}

function readPending(): ConsentEventPayload[] {
  try {
    const raw = localStorage.getItem(PENDING_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as ConsentEventPayload[]) : [];
  } catch {
    // localStorage unavailable (privacy mode) or corrupt — nothing to retry.
    return [];
  }
}

function writePending(events: ConsentEventPayload[]): void {
  try {
    if (events.length === 0) {
      localStorage.removeItem(PENDING_KEY);
      return;
    }
    localStorage.setItem(PENDING_KEY, JSON.stringify(events));
  } catch (error) {
    // Storage blocked or full — the audit gap is now visible, not silent.
    warn('could not persist pending consent events', error);
  }
}

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
import { freshOriginToken } from './origin-token';

export interface ConsentEventPayload {
  siteKey: string;
  action: 'accept_all' | 'reject_all' | 'custom';
  categories: ConsentDecision;
  lang: string;
  /**
   * Idempotency key (UUIDv7), minted once per consent decision and replayed
   * verbatim on every retry. The backend claims it in `consent_idempotency`, so
   * a retry of an already-recorded event is de-duplicated instead of writing a
   * second append-only audit row. The audit timestamp is server-stamped, never
   * sent by the client.
   */
  eventKey: string;
  /** Stable per-browser id (UUID) for audit correlation. */
  vid: string;
}

/**
 * A queued retry: the exact payload to POST plus when it was FIRST enqueued.
 * The enqueue time lets the queue expire entries the backend can no longer
 * de-duplicate — it is deliberately NOT part of the payload, so no client
 * timestamp is ever sent (the audit timestamp is server-stamped).
 */
export interface PendingEntry {
  /**
   * Unix ms of the first enqueue attempt. Preserved across retries (never
   * re-stamped) so a permanently-failing entry still ages out of the queue
   * instead of resetting its clock on every page load.
   */
  enqueuedAt: number;
  payload: ConsentEventPayload;
}

const PENDING_KEY = 'cmplyr_pending';
/** Hard cap on the retry queue so a permanently-offline visitor can't grow it unbounded. */
const MAX_PENDING = 20;
/**
 * Max age of a queued retry. The backend de-duplicates on `eventKey` only while
 * the key still exists in `consent_idempotency` (14-day retention). A retry
 * replayed after that window would bypass dedupe and write a duplicate
 * append-only audit row, so the queue must drop entries well before then — 7
 * days keeps a 2× safety margin under the backend's retention.
 */
export const PENDING_TTL_MS = 7 * 24 * 60 * 60 * 1000;

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
  send({ enqueuedAt: Date.now(), payload });
}

/**
 * Re-attempt delivery of any events queued by a previous failed send. Called
 * once from init(), best-effort; drains entries only as the server confirms
 * them, and never throws.
 */
export function flushPendingEvents(): void {
  const pending = readPending();

  // Clear the store up front — this also discards any expired or un-replayable
  // legacy entries readPending filtered out — then re-queue whatever fails, so
  // a flush that races another send can't duplicate-then-drop entries.
  writePending([]);
  for (const entry of pending) {
    send(entry);
  }
}

/**
 * POST an entry's payload; re-queue the entry — preserving its original
 * `enqueuedAt` — on a rejected request or non-2xx response.
 *
 * The origin token (ADR-13) is read fresh here and attached to the body only
 * when present, NEVER stored in the queued entry: a token lives ~2 min but a
 * retry can replay days later. A retry therefore goes out tokenless (the backend
 * still records it), instead of carrying an expired token that would be rejected
 * — losing audit evidence is worse than the bounded replay window the token
 * closes. On the first live send freshOriginToken() returns the just-fetched
 * token; flushPendingEvents() runs at init before any fetch, so retries are
 * naturally tokenless.
 */
function send(entry: PendingEntry): void {
  const token = freshOriginToken();
  const body = token
    ? { ...entry.payload, originToken: token }
    : entry.payload;
  void fetch(endpoint(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    keepalive: true,
  })
    .then((response) => {
      if (!response.ok) {
        warn(`consent endpoint returned ${response.status}; queued for retry`);
        enqueuePending(entry);
      }
    })
    .catch((error: unknown) => {
      warn('consent event POST failed; queued for retry', error);
      enqueuePending(entry);
    });
}

function enqueuePending(entry: PendingEntry): void {
  const pending = readPending();
  // Keep the most recent events; a very stale queue is less valuable than a
  // bounded one. Each payload carries a stable eventKey, so a redelivered entry
  // is de-duplicated by the backend rather than double-recorded.
  const next = [...pending, entry].slice(-MAX_PENDING);
  writePending(next);
}

function readPending(): PendingEntry[] {
  try {
    const raw = localStorage.getItem(PENDING_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    // Keep only entries that are still replayable: a valid envelope whose
    // payload carries an eventKey (older widget versions queued bare, keyless
    // payloads) AND which is young enough that the backend can still dedupe it.
    // Replaying a keyless or expired entry would risk a duplicate audit row —
    // the exact failure eventKey exists to prevent — so drop it instead.
    //
    // The age check assumes a roughly-monotonic wall clock: `enqueuedAt` and
    // `now` are read from the same device, so a stable offset cancels. The
    // 7-day TTL (2× under the backend's 14-day retention) exists to absorb the
    // ordinary NTP drift that assumption can't guarantee away.
    const now = Date.now();
    return parsed.filter(
      (entry): entry is PendingEntry =>
        isReplayableEntry(entry) && now - entry.enqueuedAt < PENDING_TTL_MS,
    );
  } catch {
    // localStorage unavailable (privacy mode) or corrupt — nothing to retry.
    return [];
  }
}

/** A queued entry is replayable only if it's a well-formed envelope around a payload that still carries the fields the backend needs to dedupe and record it. */
function isReplayableEntry(value: unknown): value is PendingEntry {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate['enqueuedAt'] === 'number' &&
    isReplayablePayload(candidate['payload'])
  );
}

function isReplayablePayload(value: unknown): value is ConsentEventPayload {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  const categories = candidate['categories'];
  // The identity fields (eventKey/siteKey/vid) must be non-empty: eventKey is
  // the dedupe key itself, so replaying an entry with a blank one would defeat
  // the whole idempotency guarantee.
  return (
    isNonEmptyString(candidate['eventKey']) &&
    isNonEmptyString(candidate['siteKey']) &&
    isNonEmptyString(candidate['vid']) &&
    typeof candidate['action'] === 'string' &&
    typeof candidate['lang'] === 'string' &&
    typeof categories === 'object' &&
    categories !== null &&
    !Array.isArray(categories)
  );
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function writePending(entries: PendingEntry[]): void {
  try {
    if (entries.length === 0) {
      localStorage.removeItem(PENDING_KEY);
      return;
    }
    localStorage.setItem(PENDING_KEY, JSON.stringify(entries));
  } catch (error) {
    // Storage blocked or full — the audit gap is now visible, not silent.
    warn('could not persist pending consent events', error);
  }
}

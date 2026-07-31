import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  flushPendingEvents,
  PENDING_TTL_MS,
  sendConsentEvent,
  type ConsentEventPayload,
  type PendingEntry,
} from '../src/api';

const PENDING_KEY = 'cmplyr_pending';

const payload: ConsentEventPayload = {
  siteKey: 'pk_test',
  action: 'accept_all',
  categories: { necessary: true, statistics: true },
  lang: 'en',
  eventKey: '0190d6a1-7c00-7000-8000-0123456789ab',
  vid: '11111111-1111-4111-8111-111111111111',
};

/** Let the fetch .then/.catch microtasks settle. */
const flushMicrotasks = () => new Promise((resolve) => setTimeout(resolve, 0));

function readPending(): PendingEntry[] {
  const raw = localStorage.getItem(PENDING_KEY);
  return raw ? (JSON.parse(raw) as PendingEntry[]) : [];
}

/** Seed the queue with a single envelope, aged `ageMs` in the past. */
function seedPending(entryPayload: ConsentEventPayload, ageMs = 0): void {
  const entry: PendingEntry = { enqueuedAt: Date.now() - ageMs, payload: entryPayload };
  localStorage.setItem(PENDING_KEY, JSON.stringify([entry]));
}

describe('consent event durability', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  test('queues the event for retry when the POST rejects', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('offline'))),
    );

    sendConsentEvent(payload);
    await flushMicrotasks();

    const pending = readPending();
    expect(pending).toHaveLength(1);
    expect(pending[0]!.payload.vid).toBe(payload.vid);
    // The idempotency key must survive queuing byte-for-byte, or the retry
    // would look like a distinct event and write a duplicate audit row.
    expect(pending[0]!.payload.eventKey).toBe(payload.eventKey);
    // The enqueue time is recorded so the entry can later age out of the queue.
    expect(typeof pending[0]!.enqueuedAt).toBe('number');
  });

  test('queues the event when the server returns a non-2xx status', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('', { status: 503 }))),
    );

    sendConsentEvent(payload);
    await flushMicrotasks();

    expect(readPending()).toHaveLength(1);
  });

  test('does not queue when the POST succeeds', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('', { status: 204 }))),
    );

    sendConsentEvent(payload);
    await flushMicrotasks();

    expect(readPending()).toHaveLength(0);
  });

  test('flushPendingEvents redelivers a queued event and drains it on success', async () => {
    seedPending(payload);
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(new Response('', { status: 204 })),
    );
    vi.stubGlobal('fetch', fetchMock);

    flushPendingEvents();
    await flushMicrotasks();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    // The redelivered body is the pure payload — no client-side envelope leaks.
    const body = JSON.parse(String(fetchMock.mock.calls[0]![1]!.body));
    expect(body.eventKey).toBe(payload.eventKey);
    expect(body).not.toHaveProperty('enqueuedAt');
    expect(readPending()).toHaveLength(0);
  });

  test('flushPendingEvents re-queues a failed event without resetting its age', async () => {
    // Seed an entry already 6 days old, then fail its redelivery.
    const sixDaysMs = 6 * 24 * 60 * 60 * 1000;
    seedPending(payload, sixDaysMs);
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('still offline'))),
    );

    flushPendingEvents();
    await flushMicrotasks();

    const pending = readPending();
    expect(pending).toHaveLength(1);
    // enqueuedAt is preserved (still ~6 days old), so the entry keeps aging
    // toward expiry instead of resetting its clock on every retry.
    const ageMs = Date.now() - pending[0]!.enqueuedAt;
    expect(ageMs).toBeGreaterThanOrEqual(sixDaysMs);
  });

  test('still replays a queued entry that is just under the retry TTL', async () => {
    seedPending(payload, PENDING_TTL_MS - 60_000);
    const fetchMock = vi.fn(() =>
      Promise.resolve(new Response('', { status: 204 })),
    );
    vi.stubGlobal('fetch', fetchMock);

    flushPendingEvents();
    await flushMicrotasks();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(readPending()).toHaveLength(0);
  });

  test('ignores a corrupt queue without throwing or sending', async () => {
    localStorage.setItem(PENDING_KEY, '{ not valid json');
    const fetchMock = vi.fn(() =>
      Promise.resolve(new Response('', { status: 204 })),
    );
    vi.stubGlobal('fetch', fetchMock);

    expect(() => flushPendingEvents()).not.toThrow();
    await flushMicrotasks();

    expect(fetchMock).not.toHaveBeenCalled();
  });

  test('drops a queued entry older than the retry TTL instead of replaying it', async () => {
    // Past the backend's dedupe window, a replay would write a duplicate audit
    // row, so the entry must be discarded rather than redelivered.
    seedPending(payload, PENDING_TTL_MS + 1);
    const fetchMock = vi.fn(() =>
      Promise.resolve(new Response('', { status: 204 })),
    );
    vi.stubGlobal('fetch', fetchMock);

    flushPendingEvents();
    await flushMicrotasks();

    expect(fetchMock).not.toHaveBeenCalled();
    // The expired entry is cleared from storage, not left to linger.
    expect(readPending()).toHaveLength(0);
  });

  test('drops a legacy queued entry that has no eventKey instead of replaying it', async () => {
    // A bare payload serialized by a pre-eventKey widget version: no envelope,
    // no `eventKey`. Replaying it would bypass backend dedupe and risk a
    // duplicate audit row, so it must be discarded rather than redelivered.
    const legacy = {
      siteKey: 'pk_test',
      action: 'accept_all',
      categories: { necessary: true },
      lang: 'en',
      ts: 1_700_000_000_000,
      vid: '11111111-1111-4111-8111-111111111111',
    };
    localStorage.setItem(PENDING_KEY, JSON.stringify([legacy]));
    const fetchMock = vi.fn(() =>
      Promise.resolve(new Response('', { status: 204 })),
    );
    vi.stubGlobal('fetch', fetchMock);

    flushPendingEvents();
    await flushMicrotasks();

    expect(fetchMock).not.toHaveBeenCalled();
    // The un-replayable entry is cleared from storage, not left to linger.
    expect(readPending()).toHaveLength(0);
  });
});

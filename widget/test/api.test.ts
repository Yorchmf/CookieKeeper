import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  flushPendingEvents,
  PENDING_TTL_MS,
  sendConsentEvent,
  sendImpression,
  type ConsentEventPayload,
  type PendingEntry,
} from '../src/api';
import { clearOriginToken, fetchOriginToken } from '../src/origin-token';

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
    // No origin token held unless a test explicitly fetches one.
    clearOriginToken();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    clearOriginToken();
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

  test('attaches a freshly-held origin token to the live consent POST', async () => {
    // A page that fetched a token holds it; the very next live send rides it.
    const tokenEnvelope = JSON.stringify({
      success: true,
      data: { token: 'payload.signature' },
      error: null,
    });
    const fetchMock = vi.fn((url: string, _init?: RequestInit) =>
      url.includes('/consent-token/')
        ? Promise.resolve(
            new Response(tokenEnvelope, {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            }),
          )
        : Promise.resolve(new Response('', { status: 204 })),
    );
    vi.stubGlobal('fetch', fetchMock);

    await fetchOriginToken(payload.siteKey);
    sendConsentEvent(payload);
    await flushMicrotasks();

    const postCall = fetchMock.mock.calls.find((c) =>
      String(c[0]).endsWith('/api/v1/consent'),
    );
    const body = JSON.parse(String(postCall![1]!.body));
    expect(body.originToken).toBe('payload.signature');
    // The token is a transport-only concession; it never enters the persisted payload.
    expect(readPending()).toHaveLength(0);
  });

  test('replays a queued event tokenless (no stale token on retries)', async () => {
    // Init order in the real widget: flushPendingEvents() runs before any token
    // is fetched, so a retry carries no token and the backend records it anyway.
    seedPending(payload);
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(new Response('', { status: 204 })),
    );
    vi.stubGlobal('fetch', fetchMock);

    flushPendingEvents();
    await flushMicrotasks();

    const body = JSON.parse(String(fetchMock.mock.calls[0]![1]!.body));
    expect(body).not.toHaveProperty('originToken');
  });
});

describe('banner impression beacon', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  test('POSTs a keepalive beacon carrying only the site key', async () => {
    const fetchMock = vi.fn((_url: string, _init?: RequestInit) =>
      Promise.resolve(new Response('', { status: 200 })),
    );
    vi.stubGlobal('fetch', fetchMock);

    sendImpression('pk_test');
    await flushMicrotasks();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(String(url)).toMatch(/\/api\/v1\/impression$/);
    expect(init!.method).toBe('POST');
    expect(init!.keepalive).toBe(true);
    // The beacon is a disposable count: only the public site key, nothing that
    // could identify a visitor (no vid, timestamp, eventKey, or origin token).
    const body = JSON.parse(String(init!.body));
    expect(body).toEqual({ siteKey: 'pk_test' });
  });

  test('never queues or throws when the beacon POST fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('offline'))),
    );

    expect(() => sendImpression('pk_test')).not.toThrow();
    await flushMicrotasks();

    // Unlike consent, a dropped impression is not retried — it leaves no queue.
    expect(localStorage.getItem(PENDING_KEY)).toBeNull();
  });
});

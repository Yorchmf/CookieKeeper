import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  flushPendingEvents,
  sendConsentEvent,
  type ConsentEventPayload,
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

function readPending(): ConsentEventPayload[] {
  const raw = localStorage.getItem(PENDING_KEY);
  return raw ? (JSON.parse(raw) as ConsentEventPayload[]) : [];
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
    expect(pending[0]!.vid).toBe(payload.vid);
    // The idempotency key must survive queuing byte-for-byte, or the retry
    // would look like a distinct event and write a duplicate audit row.
    expect(pending[0]!.eventKey).toBe(payload.eventKey);
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
    localStorage.setItem(PENDING_KEY, JSON.stringify([payload]));
    const fetchMock = vi.fn(() =>
      Promise.resolve(new Response('', { status: 204 })),
    );
    vi.stubGlobal('fetch', fetchMock);

    flushPendingEvents();
    await flushMicrotasks();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(readPending()).toHaveLength(0);
  });

  test('drops a legacy queued entry that has no eventKey instead of replaying it', async () => {
    // A payload serialized by a pre-eventKey widget version: has `ts`, no
    // `eventKey`. Replaying it would bypass backend dedupe and risk a duplicate
    // audit row, so it must be discarded rather than redelivered.
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

  test('flushPendingEvents re-queues an event that still fails', async () => {
    localStorage.setItem(PENDING_KEY, JSON.stringify([payload]));
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('still offline'))),
    );

    flushPendingEvents();
    await flushMicrotasks();

    expect(readPending()).toHaveLength(1);
  });
});

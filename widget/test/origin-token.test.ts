import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import {
  clearOriginToken,
  fetchOriginToken,
  freshOriginToken,
  visitorRegion,
  TOKEN_MAX_AGE_MS,
} from '../src/origin-token';

/** A success envelope carrying a minted token, as the backend returns it. */
function tokenResponse(token: string, region: string | null = null): Response {
  return new Response(
    JSON.stringify({ success: true, data: { token, region }, error: null }),
    {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    },
  );
}

describe('origin token', () => {
  beforeEach(() => {
    clearOriginToken();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    vi.useRealTimers();
    clearOriginToken();
  });

  test('holds a freshly fetched token for the next consent POST', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(tokenResponse('payload.signature'))),
    );

    await fetchOriginToken('pk_test');

    expect(freshOriginToken()).toBe('payload.signature');
  });

  test('requests the token endpoint for the url-encoded site key', async () => {
    const fetchMock = vi.fn((_url: string) =>
      Promise.resolve(tokenResponse('t.s')),
    );
    vi.stubGlobal('fetch', fetchMock);

    await fetchOriginToken('pk test/weird');

    const url = String(fetchMock.mock.calls[0]![0]);
    expect(url).toContain('/api/v1/consent-token/');
    expect(url).toContain(encodeURIComponent('pk test/weird'));
  });

  test('holds no token when the endpoint returns a non-2xx status', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('', { status: 429 }))),
    );

    await fetchOriginToken('pk_test');

    expect(freshOriginToken()).toBeUndefined();
  });

  test('never throws and holds no token when the fetch rejects', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('offline'))),
    );

    await expect(fetchOriginToken('pk_test')).resolves.toBeUndefined();
    expect(freshOriginToken()).toBeUndefined();
  });

  test('ignores an envelope without a usable token string', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ success: true, data: { token: '' }, error: null }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        ),
      ),
    );

    await fetchOriginToken('pk_test');

    expect(freshOriginToken()).toBeUndefined();
  });

  test('ignores a malformed (non-JSON) body without throwing', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('not json', { status: 200 }))),
    );

    await expect(fetchOriginToken('pk_test')).resolves.toBeUndefined();
    expect(freshOriginToken()).toBeUndefined();
  });

  test('stops offering the token once it is older than the freshness window', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(tokenResponse('payload.signature'))),
    );

    await fetchOriginToken('pk_test');
    expect(freshOriginToken()).toBe('payload.signature');

    // Just inside the window it is still offered; one tick past, it is withheld
    // so it can never be sent close to its server-side expiry.
    vi.advanceTimersByTime(TOKEN_MAX_AGE_MS - 1);
    expect(freshOriginToken()).toBe('payload.signature');

    vi.advanceTimersByTime(1);
    expect(freshOriginToken()).toBeUndefined();
  });

  test('a slow fetch is aborted and leaves no token', async () => {
    // Capture the abort signal the module wires up, then reject as fetch does on abort.
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
      return new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('aborted', 'AbortError'));
        });
      });
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.useFakeTimers();

    const pending = fetchOriginToken('pk_test');
    await vi.advanceTimersByTimeAsync(3_000);
    await expect(pending).resolves.toBeUndefined();

    expect(fetchMock.mock.calls[0]![1]!.signal).toBeInstanceOf(AbortSignal);
    expect(freshOriginToken()).toBeUndefined();
  });

  test('a slow fetch is still bounded on an engine without AbortController', async () => {
    // main.ts awaits this call on the region-gated path, so "no AbortController,
    // no timeout" would mean an unbounded delay before the banner appears.
    vi.stubGlobal('AbortController', undefined);
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise(() => {})),
    );
    vi.useFakeTimers();

    const pending = fetchOriginToken('pk_test');
    await vi.advanceTimersByTimeAsync(3_000);

    await expect(pending).resolves.toBeUndefined();
    expect(freshOriginToken()).toBeUndefined();
  });

  test('holds the region bucket the mint response carries', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(tokenResponse('t.s', 'other'))),
    );

    await fetchOriginToken('pk_test');

    expect(visitorRegion()).toBe('other');
  });

  test('holds no region when the server could not tell', async () => {
    // A null bucket is the server saying "unknown", not "out of scope" — the
    // caller must not be able to mistake one for the other.
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(tokenResponse('t.s', null))),
    );

    await fetchOriginToken('pk_test');

    expect(visitorRegion()).toBeNull();
  });

  test('holds no region when the fetch fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('offline'))),
    );

    await fetchOriginToken('pk_test');

    expect(visitorRegion()).toBeNull();
  });
});

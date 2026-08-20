import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { mountPolicyTables } from '../src/policy-table';

const table = {
  language: 'en',
  scannedOn: '2026-08-20',
  labels: {
    name: 'Cookie',
    provider: 'Provider',
    expiry: 'Expiry',
    updated: 'Last updated',
    noCookies: 'Our latest scan found no cookies.',
  },
  sections: [
    {
      heading: 'Strictly necessary cookies',
      description: 'Required for the website to function.',
      cookies: [
        { name: 'cmplyr_consent', provider: 'Complyr', expiry: '365 days' },
        { name: 'PHPSESSID', provider: 'example.com', expiry: 'Session' },
      ],
    },
    {
      heading: 'Statistics cookies',
      description: 'Help us understand how visitors use the website.',
      cookies: [{ name: '_ga', provider: 'Google', expiry: '2 years' }],
    },
  ],
};

const okJson = (body: unknown) =>
  Promise.resolve(new Response(JSON.stringify(body), { status: 200 }));

function fetchMock(body: unknown = { success: true, data: table }) {
  // Typed with fetch's own parameters so the tests can assert on the requested URL.
  const mock = vi.fn((_url: RequestInfo | URL, _init?: RequestInit) =>
    okJson(body),
  );
  vi.stubGlobal('fetch', mock);
  return mock;
}

beforeEach(() => {
  document.documentElement.removeAttribute('lang');
  document.body.innerHTML = '';
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/**
 * The embeddable cookie table (ADR-27) renders into someone else's page, so the
 * two things it must never do are as important as what it renders: no request at
 * all when the page has no marker, and no damage to the page when the request
 * fails.
 */
describe('mountPolicyTables', () => {
  test('makes no request when the page has no marker element', async () => {
    const fetchSpy = fetchMock();

    await mountPolicyTables('pk_test');

    expect(fetchSpy).not.toHaveBeenCalled();
  });

  test('renders one table per section with the server-resolved strings', async () => {
    document.body.innerHTML = '<div data-complyr-policy></div>';
    fetchMock();

    await mountPolicyTables('pk_test');

    const tables = document.querySelectorAll('table.cmplyr-policy-table');
    expect(tables.length).toBe(2);
    expect(
      [...document.querySelectorAll('h2')].map((h) => h.textContent),
    ).toEqual(['Strictly necessary cookies', 'Statistics cookies']);
    expect(
      [...tables[0]!.querySelectorAll('thead th')].map((th) => th.textContent),
    ).toEqual(['Cookie', 'Provider', 'Expiry']);
    expect(
      [...tables[0]!.querySelectorAll('tbody tr')].map((tr) =>
        [...tr.children].map((cell) => cell.textContent),
      ),
    ).toEqual([
      ['cmplyr_consent', 'Complyr', '365 days'],
      ['PHPSESSID', 'example.com', 'Session'],
    ]);
    expect(
      document.querySelector('.cmplyr-policy-updated')?.textContent,
    ).toBe('Last updated: 2026-08-20');
  });

  /** The whole point of the feature: the customer's stale paste is superseded. */
  test('replaces whatever the customer had in the element', async () => {
    document.body.innerHTML =
      '<div data-complyr-policy><p id="stale">an old pasted table</p></div>';
    fetchMock();

    await mountPolicyTables('pk_test');

    expect(document.getElementById('stale')).toBeNull();
    expect(document.querySelectorAll('table.cmplyr-policy-table').length).toBe(2);
  });

  /** ...but only when we actually have something better to put there. */
  test('leaves the fallback markup alone when the fetch fails', async () => {
    document.body.innerHTML =
      '<div data-complyr-policy><p id="stale">an old pasted table</p></div>';
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('offline'))),
    );

    await mountPolicyTables('pk_test');

    expect(document.getElementById('stale')).not.toBeNull();
  });

  test('leaves the fallback markup alone when the payload is malformed', async () => {
    document.body.innerHTML =
      '<div data-complyr-policy><p id="stale">an old pasted table</p></div>';
    fetchMock({ success: true, data: { language: 'en', sections: 'nope' } });

    await mountPolicyTables('pk_test');

    expect(document.getElementById('stale')).not.toBeNull();
  });

  test('states the empty case rather than rendering an empty table', async () => {
    document.body.innerHTML = '<div data-complyr-policy></div>';
    fetchMock({ success: true, data: { ...table, sections: [] } });

    await mountPolicyTables('pk_test');

    expect(document.querySelector('table')).toBeNull();
    expect(document.body.textContent).toContain(
      'Our latest scan found no cookies.',
    );
  });

  test('asks for the language the marker names, over the page and the browser', async () => {
    document.documentElement.lang = 'fr';
    document.body.innerHTML = '<div data-complyr-policy="de"></div>';
    const fetchSpy = fetchMock();

    await mountPolicyTables('pk_test');

    expect(String(fetchSpy.mock.calls[0]![0])).toContain('?lang=de');
  });

  /** A legal document belongs to the page it sits in, not to the reader's browser. */
  test("falls back to the page's declared language", async () => {
    document.documentElement.lang = 'fr-FR';
    document.body.innerHTML = '<div data-complyr-policy></div>';
    const fetchSpy = fetchMock();

    await mountPolicyTables('pk_test');

    expect(String(fetchSpy.mock.calls[0]![0])).toContain('?lang=fr');
  });

  test('honours a heading-level override so the block nests in the host page', async () => {
    document.body.innerHTML =
      '<div data-complyr-policy data-complyr-policy-heading="h3"></div>';
    fetchMock();

    await mountPolicyTables('pk_test');

    expect(document.querySelectorAll('h3').length).toBe(2);
    expect(document.querySelector('h2')).toBeNull();
  });

  test('fills every marker on the page from a single request', async () => {
    document.body.innerHTML =
      '<div data-complyr-policy></div><div data-complyr-policy></div>';
    const fetchSpy = fetchMock();

    await mountPolicyTables('pk_test');

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(document.querySelectorAll('table.cmplyr-policy-table').length).toBe(4);
  });

  /** Values are painted as text, never parsed as markup — the widget has no HTML sink. */
  test('never interprets a value as markup', async () => {
    document.body.innerHTML = '<div data-complyr-policy></div>';
    fetchMock({
      success: true,
      data: {
        ...table,
        sections: [
          {
            heading: '<img src=x onerror=alert(1)>',
            description: 'd',
            cookies: [
              { name: '<script>alert(1)</script>', provider: 'p', expiry: 'e' },
            ],
          },
        ],
      },
    });

    await mountPolicyTables('pk_test');

    expect(document.querySelector('img')).toBeNull();
    expect(document.querySelector('table script')).toBeNull();
    expect(document.querySelector('h2')?.textContent).toBe(
      '<img src=x onerror=alert(1)>',
    );
  });
});

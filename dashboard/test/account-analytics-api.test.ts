import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";

import { ApiError } from "@/lib/api";
import { getAccountAnalytics } from "@/lib/api/account-analytics";

type Envelope = {
  success: boolean;
  data: unknown;
  error: { code: string; message: string } | null;
};

function jsonResponse(status: number, envelope: Envelope): Response {
  return new Response(JSON.stringify(envelope), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const rollup = {
  range: { from: "2026-07-13T00:00:00Z", to: "2026-08-12T00:00:00Z" },
  consent: {
    totalEvents: 42,
    byAction: { acceptAll: 20, rejectAll: 12, custom: 10 },
    trend: [],
    categoryOptIn: [],
    languageSplit: [],
  },
  previous: null,
  siteCount: 3,
};

const ok = (data: unknown): Response =>
  jsonResponse(200, { success: true, data, error: null });

const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function calledUrl(): string {
  return String(fetchMock.mock.calls[0]?.[0]);
}

describe("getAccountAnalytics", () => {
  test("hits the JWT-scoped account rollup endpoint — no site id in the path", async () => {
    fetchMock.mockResolvedValue(ok(rollup));

    const result = await getAccountAnalytics({ from: "2026-07-13T00:00:00Z" });

    expect(result.siteCount).toBe(3);
    expect(result.consent.totalEvents).toBe(42);
    expect(calledUrl()).toBe(
      "/api/v1/analytics/accounts/rollup?from=2026-07-13T00%3A00%3A00Z",
    );
  });

  test("omits the query string entirely when the window is unfiltered", async () => {
    fetchMock.mockResolvedValue(ok(rollup));

    await getAccountAnalytics({});

    expect(calledUrl()).toBe("/api/v1/analytics/accounts/rollup");
  });

  test("surfaces the plan gate (403) as an ApiError the caller can branch on", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(403, {
        success: false,
        data: null,
        error: { code: "CROSS_SITE_ANALYTICS_NOT_ENTITLED", message: "nope" },
      }),
    );

    const error = await getAccountAnalytics({}).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(403);
    expect((error as ApiError).code).toBe("CROSS_SITE_ANALYTICS_NOT_ENTITLED");
  });
});

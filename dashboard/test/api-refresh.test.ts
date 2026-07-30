import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import {
  ApiError,
  UnauthenticatedError,
  apiFetch,
} from "@/lib/api";

type Envelope = {
  success: boolean;
  data: unknown;
  error: { code: string; message: string } | null;
  meta?: { total?: number };
};

function jsonResponse(status: number, envelope: Envelope): Response {
  return new Response(JSON.stringify(envelope), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const ok = (data: unknown, meta?: { total?: number }): Response =>
  jsonResponse(200, { success: true, data, error: null, meta });

const unauthorized = (code = "UNAUTHENTICATED"): Response =>
  jsonResponse(401, {
    success: false,
    data: null,
    error: { code, message: "nope" },
  });

const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function calledPaths(): string[] {
  return fetchMock.mock.calls.map(([url]) => String(url));
}

describe("apiFetch 401 refresh-retry", () => {
  test("refreshes once and retries the original request on 401", async () => {
    fetchMock.mockImplementation((url) => {
      if (String(url).endsWith("/auth/refresh")) {
        return Promise.resolve(ok({}));
      }
      // First sites call 401s, retry succeeds.
      const sitesCalls = fetchMock.mock.calls.filter(([u]) =>
        String(u).includes("/sites"),
      ).length;
      return Promise.resolve(
        sitesCalls <= 1 ? unauthorized() : ok([{ id: "s1" }]),
      );
    });

    const result = await apiFetch<{ id: string }[]>("/api/v1/sites");

    expect(result.data).toEqual([{ id: "s1" }]);
    expect(calledPaths()).toEqual([
      "/api/v1/sites",
      "/api/v1/auth/refresh",
      "/api/v1/sites",
    ]);
  });

  test("parallel 401s share a single in-flight refresh", async () => {
    const perPathCalls = new Map<string, number>();
    fetchMock.mockImplementation((url) => {
      const path = String(url);
      const count = (perPathCalls.get(path) ?? 0) + 1;
      perPathCalls.set(path, count);

      if (path.endsWith("/auth/refresh")) {
        // Resolve on a microtask so both callers are waiting simultaneously.
        return Promise.resolve().then(() => ok({}));
      }
      return Promise.resolve(count === 1 ? unauthorized() : ok({ path }));
    });

    await Promise.all([
      apiFetch("/api/v1/sites"),
      apiFetch("/api/v1/sites/abc"),
    ]);

    const refreshCalls = calledPaths().filter((p) =>
      p.endsWith("/auth/refresh"),
    );
    expect(refreshCalls).toHaveLength(1);
  });

  test("throws UnauthenticatedError when the refresh itself 401s", async () => {
    fetchMock.mockImplementation((url) =>
      Promise.resolve(
        String(url).endsWith("/auth/refresh")
          ? unauthorized("INVALID_REFRESH_TOKEN")
          : unauthorized(),
      ),
    );

    await expect(apiFetch("/api/v1/sites")).rejects.toBeInstanceOf(
      UnauthenticatedError,
    );
  });

  test("throws UnauthenticatedError when the retried request 401s again", async () => {
    fetchMock.mockImplementation((url) =>
      Promise.resolve(
        String(url).endsWith("/auth/refresh") ? ok({}) : unauthorized(),
      ),
    );

    await expect(apiFetch("/api/v1/sites")).rejects.toBeInstanceOf(
      UnauthenticatedError,
    );
    // Original, refresh, retry — never a second refresh loop.
    expect(calledPaths()).toEqual([
      "/api/v1/sites",
      "/api/v1/auth/refresh",
      "/api/v1/sites",
    ]);
  });

  test("never refreshes for auth endpoints (401 = definitive answer)", async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(unauthorized("INVALID_CREDENTIALS")),
    );

    const error = await apiFetch("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ email: "a@b.eu", password: "x" }),
    }).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).not.toBeInstanceOf(UnauthenticatedError);
    expect((error as ApiError).code).toBe("INVALID_CREDENTIALS");
    expect(calledPaths()).toEqual(["/api/v1/auth/login"]);
  });

  test("does refresh for /auth/me (behaves like a protected resource)", async () => {
    fetchMock.mockImplementation((url) => {
      if (String(url).endsWith("/auth/refresh")) {
        return Promise.resolve(ok({}));
      }
      const meCalls = fetchMock.mock.calls.filter(([u]) =>
        String(u).endsWith("/auth/me"),
      ).length;
      return Promise.resolve(
        meCalls <= 1 ? unauthorized() : ok({ id: "u1", email: "a@b.eu" }),
      );
    });

    const result = await apiFetch<{ id: string }>("/api/v1/auth/me");

    expect(result.data.id).toBe("u1");
    expect(calledPaths()).toEqual([
      "/api/v1/auth/me",
      "/api/v1/auth/refresh",
      "/api/v1/auth/me",
    ]);
  });

  test("normalizes null data on success to an empty object", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { success: true, data: null, error: null }),
    );

    const result = await apiFetch<Record<string, never>>(
      "/api/v1/auth/logout",
      { method: "POST" },
    );

    expect(result.data).toEqual({});
  });

  test("propagates non-401 errors without refreshing", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(429, {
        success: false,
        data: null,
        error: { code: "RATE_LIMITED", message: "slow down" },
      }),
    );

    const error = await apiFetch("/api/v1/sites").catch(
      (caught: unknown) => caught,
    );

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe("RATE_LIMITED");
    expect(calledPaths()).toEqual(["/api/v1/sites"]);
  });
});

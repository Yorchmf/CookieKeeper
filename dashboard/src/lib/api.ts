/**
 * Typed fetch helper for the Complyr backend REST API.
 *
 * Every backend response uses the envelope defined in CLAUDE.md:
 * `{ success, data, error, meta }`.
 *
 * Client-only: the module-level single-flight refresh state must never be
 * shared across requests in a server runtime.
 */
import "client-only";

export interface ApiMeta {
  total?: number;
  page?: number;
  limit?: number;
}

/** Mirrors the backend's ApiError DTO (ApiResponse.kt). */
export interface ApiErrorBody {
  code: string;
  message: string;
}

export interface ApiEnvelope<T> {
  success: boolean;
  data: T | null;
  error: ApiErrorBody | null;
  meta?: ApiMeta;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(message: string, status: number, code = "UNKNOWN") {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

/**
 * Thrown when the session cannot be recovered (refresh failed or the retried
 * request still came back 401). Callers should redirect to the login page.
 */
export class UnauthenticatedError extends ApiError {
  constructor(message = "Not authenticated") {
    super(message, 401, "UNAUTHENTICATED");
    this.name = "UnauthenticatedError";
  }
}

// All requests are same-origin relative paths: in deployed dev/prd, Caddy
// proxies /api/v1/* on the app domain to the backend; in local development,
// a Next rewrite forwards them to API_PROXY_TARGET (see next.config.ts).
// Same-origin keeps the httpOnly auth cookies first-party everywhere.
const AUTH_PATH_PREFIX = "/api/v1/auth";
const REFRESH_PATH = "/api/v1/auth/refresh";
const ME_PATH = "/api/v1/auth/me";

/**
 * Auth endpoints must never trigger the refresh-and-retry flow: a 401 there
 * is a definitive answer (bad credentials, invalid token, …), not an expired
 * access token. The one exception is `/auth/me`, which behaves like a normal
 * protected resource read.
 */
function isRefreshExempt(path: string): boolean {
  return path.startsWith(AUTH_PATH_PREFIX) && path !== ME_PATH;
}

async function rawFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<{ data: T; meta?: ApiMeta }> {
  const response = await fetch(path, {
    ...init,
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
  });

  let envelope: ApiEnvelope<T>;
  try {
    envelope = (await response.json()) as ApiEnvelope<T>;
  } catch {
    throw new ApiError(
      `Invalid response from API (${response.status})`,
      response.status,
    );
  }

  if (!response.ok || !envelope.success) {
    throw new ApiError(
      envelope.error?.message ?? `API request failed (${response.status})`,
      response.status,
      envelope.error?.code,
    );
  }

  // Some endpoints (logout, forgot-password, …) legitimately return an empty
  // payload; normalize `null` to `{}` so callers with `Record<string, never>`
  // style types don't blow up.
  return { data: (envelope.data ?? {}) as T, meta: envelope.meta };
}

/**
 * Single-flight session refresh: concurrent 401s share one in-flight
 * POST /auth/refresh instead of stampeding the backend (and burning the
 * one-time-use rotated refresh token).
 */
let refreshInFlight: Promise<void> | null = null;

function refreshSession(): Promise<void> {
  refreshInFlight ??= (async () => {
    try {
      await rawFetch<Record<string, never>>(REFRESH_PATH, { method: "POST" });
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

/**
 * Fetch a resource from the backend API and unwrap the response envelope.
 *
 * Cookies (httpOnly `cmplyr_at` / `cmplyr_rt`) are attached by the browser;
 * client code never reads tokens. On a 401 from a non-auth endpoint, the
 * session is refreshed once (deduped across parallel callers) and the
 * original request retried once; a second 401 throws {@link UnauthenticatedError}.
 *
 * @param path - API path starting with `/`, e.g. `/api/v1/sites`
 * @throws {ApiError} when the request fails or the envelope reports failure
 * @throws {UnauthenticatedError} when the session cannot be recovered
 */
export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<{ data: T; meta?: ApiMeta }> {
  try {
    return await rawFetch<T>(path, init);
  } catch (error) {
    const isExpiredSession =
      error instanceof ApiError &&
      error.status === 401 &&
      !isRefreshExempt(path);
    if (!isExpiredSession) {
      throw error;
    }

    try {
      await refreshSession();
    } catch (refreshError) {
      if (refreshError instanceof ApiError && refreshError.status === 401) {
        throw new UnauthenticatedError();
      }
      throw refreshError;
    }

    try {
      return await rawFetch<T>(path, init);
    } catch (retryError) {
      if (retryError instanceof ApiError && retryError.status === 401) {
        throw new UnauthenticatedError();
      }
      throw retryError;
    }
  }
}

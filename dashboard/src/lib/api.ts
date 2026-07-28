/**
 * Typed fetch helper for the Complyr backend REST API.
 *
 * Every backend response uses the envelope defined in CLAUDE.md:
 * `{ success, data, error, meta }`.
 */

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

// Empty string = same-origin: in dev/prd, Caddy proxies /api/v1/* on the app
// domain to the backend, so ONE image serves both environments (nothing baked
// at build time). Set NEXT_PUBLIC_API_URL only for local dev (`pnpm dev`
// against localhost:8080) or the local compose stack.
const API_BASE_URL = (process.env.NEXT_PUBLIC_API_URL ?? "").replace(/\/$/, "");

/**
 * Fetch a resource from the backend API and unwrap the response envelope.
 *
 * @param path - API path starting with `/`, e.g. `/api/v1/sites`
 * @throws {ApiError} when the request fails or the envelope reports failure
 */
export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<{ data: T; meta?: ApiMeta }> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
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

  if (!response.ok || !envelope.success || envelope.data === null) {
    throw new ApiError(
      envelope.error?.message ?? `API request failed (${response.status})`,
      response.status,
      envelope.error?.code,
    );
  }

  return { data: envelope.data, meta: envelope.meta };
}

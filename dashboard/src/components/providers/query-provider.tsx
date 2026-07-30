"use client";

import {
  MutationCache,
  QueryCache,
  QueryClient,
  QueryClientProvider,
} from "@tanstack/react-query";
import { useState } from "react";
import { matchesPrefix, splitLocale } from "@/i18n/pathname";
import { ApiError, UnauthenticatedError } from "@/lib/api";

const STALE_TIME_MS = 30_000;
const MAX_RETRIES = 3;

/** Auth pages (locale-stripped) that must never bounce back to /login. */
const AUTH_PAGE_PREFIXES = [
  "/login",
  "/signup",
  "/forgot-password",
  "/reset-password",
  "/verify-email",
];

/**
 * Client errors (4xx) are definitive answers — retrying only re-runs the
 * refresh dance and hammers rate limits. Everything else (network, 5xx)
 * gets the usual exponential-backoff retries.
 */
function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
    return false;
  }
  return failureCount < MAX_RETRIES;
}

/**
 * Session expired beyond recovery: hard-navigate to the localized login page
 * with a `?next=` back-reference. A full navigation (not router.push) also
 * drops all in-memory caches of the dead session.
 */
function redirectToLogin(): void {
  const { locale, path } = splitLocale(window.location.pathname);
  if (matchesPrefix(path, AUTH_PAGE_PREFIXES)) {
    // Already on an auth page — redirecting again would loop.
    return;
  }
  const next = `${path}${window.location.search}`;
  window.location.assign(`/${locale}/login?next=${encodeURIComponent(next)}`);
}

function handleAuthError(error: unknown): void {
  if (typeof window !== "undefined" && error instanceof UnauthenticatedError) {
    redirectToLogin();
  }
}

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        queryCache: new QueryCache({ onError: handleAuthError }),
        mutationCache: new MutationCache({ onError: handleAuthError }),
        defaultOptions: {
          queries: {
            staleTime: STALE_TIME_MS,
            refetchOnWindowFocus: false,
            retry: shouldRetry,
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

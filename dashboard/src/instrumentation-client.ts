import * as Sentry from "@sentry/nextjs";

import {
  requireEuSentryDsn,
  scrubSentryPii,
  sentryClientEnvironment,
} from "@/lib/sentry";

/**
 * Browser Sentry init (ADR-15). The DSN is a public ingest key, inlined at build time via
 * NEXT_PUBLIC_SENTRY_DSN. If it is unset, tracking is disabled; if it is set but not EU-region, the
 * server init has already failed startup, so here we defensively disable rather than crash the app.
 */
function resolveClientDsn(): string {
  try {
    return requireEuSentryDsn(process.env.NEXT_PUBLIC_SENTRY_DSN);
  } catch {
    return "";
  }
}

const dsn = resolveClientDsn();

if (dsn) {
  Sentry.init({
    dsn,
    environment: sentryClientEnvironment(window.location.hostname),
    // Perf tracing off for MVP; error capture only. Bump via the server-side sample rate when needed.
    tracesSampleRate: 0,
    sendDefaultPii: false,
    beforeSend: scrubSentryPii,
  });
}

// Instruments Next.js App Router client-side navigations for Sentry (required for navigation spans).
export const onRouterTransitionStart = Sentry.captureRouterTransitionStart;

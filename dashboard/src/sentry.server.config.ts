import * as Sentry from "@sentry/nextjs";

import {
  requireEuSentryDsn,
  scrubSentryPii,
  sentryServerEnvironment,
  sentryTracesSampleRate,
} from "@/lib/sentry";

// Server-runtime DSN is read at runtime (not inlined), so one image works across environments.
// requireEuSentryDsn throws on a non-EU DSN — that aborts server startup (fail-closed, ADR-15).
const dsn = requireEuSentryDsn(process.env.NEXT_PUBLIC_SENTRY_DSN);

if (dsn) {
  Sentry.init({
    dsn,
    environment: sentryServerEnvironment(),
    release: process.env.SENTRY_RELEASE || undefined,
    tracesSampleRate: sentryTracesSampleRate(process.env.SENTRY_TRACES_SAMPLE_RATE),
    sendDefaultPii: false,
    beforeSend: scrubSentryPii,
  });
}

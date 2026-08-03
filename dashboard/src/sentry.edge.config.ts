import * as Sentry from "@sentry/nextjs";

import {
  requireEuSentryDsn,
  scrubSentryPii,
  sentryServerEnvironment,
  sentryTracesSampleRate,
} from "@/lib/sentry";

// Edge runtime (middleware, edge routes). Same EU-residency + no-PII contract as the Node server init.
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

import type { ErrorEvent } from "@sentry/nextjs";

/**
 * Shared, runtime-agnostic Sentry helpers (ADR-15). Imported by the server, edge, and browser init
 * files so the EU-residency guard and PII scrub are defined once. Keep this module free of
 * `next/server` or DOM imports so it is safe in every runtime.
 *
 * Constraints enforced:
 *  - EU data residency (CLAUDE.md #2): only a DSN whose host is under Sentry's EU region is accepted.
 *  - No PII (CLAUDE.md #4): `sendDefaultPii` is off at every init site and {@link scrubSentryPii}
 *    drops request/user context before an event leaves the process.
 */

const EU_INGEST_HOST = "de.sentry.io";

/** True when the DSN's parsed host is `de.sentry.io` or a subdomain of it (not a substring match). */
export function isEuRegionSentryDsn(dsn: string): boolean {
  let hostname: string;
  try {
    hostname = new URL(dsn).hostname.toLowerCase();
  } catch {
    return false;
  }
  return hostname === EU_INGEST_HOST || hostname.endsWith(`.${EU_INGEST_HOST}`);
}

/**
 * Validate a configured Sentry DSN for EU data residency. A blank/unset value returns `""` (Sentry
 * disabled — the default everywhere a DSN hasn't been provisioned). A configured non-EU (or malformed)
 * DSN throws: server/edge callers let that abort startup (fail-closed); the browser catches it and
 * disables tracking rather than crashing the app.
 */
export function requireEuSentryDsn(raw: string | undefined): string {
  const dsn = (raw ?? "").trim();
  if (dsn === "") {
    return "";
  }
  if (!isEuRegionSentryDsn(dsn)) {
    throw new Error(
      `Sentry DSN must target the EU region (host *.${EU_INGEST_HOST}) for EU data residency ` +
        `(ADR-15, CLAUDE.md #2). Refusing a non-EU or malformed DSN.`,
    );
  }
  return dsn;
}

const DEFAULT_TRACES_SAMPLE_RATE = 0;

/** Parse a [0.0, 1.0] traces sample rate, falling back to 0 (tracing off) on anything out of range. */
export function sentryTracesSampleRate(raw: string | undefined): number {
  const parsed = Number(raw);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 1) {
    return DEFAULT_TRACES_SAMPLE_RATE;
  }
  return parsed;
}

/**
 * Map a browser hostname to a Sentry environment tag. Client-side env vars are inlined at build time,
 * which would bake a single value into the one-image-for-all-environments build; deriving from the
 * hostname at runtime keeps a single image correct across local/dev/prd. Unknown hosts fall back to
 * `prd` (the safest label for an unrecognised deployed origin).
 */
export function sentryClientEnvironment(hostname: string): string {
  if (hostname === "localhost" || hostname === "127.0.0.1") {
    return "local";
  }
  if (hostname.startsWith("dev.") || hostname.includes(".dev.")) {
    return "dev";
  }
  return "prd";
}

/** Server/edge environment tag from the runtime env (not inlined), defaulting to `local`. */
export function sentryServerEnvironment(): string {
  const env = process.env.SENTRY_ENVIRONMENT?.trim();
  return env && env.length > 0 ? env : "local";
}

/**
 * Drop request and user context before an event is sent so no PII leaves the process (CLAUDE.md #4).
 * Returns a new event rather than mutating the argument (immutability). `sendDefaultPii: false` already
 * suppresses IP/cookies/headers; this is defense-in-depth for anything a layer attached explicitly.
 */
export function scrubSentryPii(event: ErrorEvent): ErrorEvent {
  return { ...event, request: undefined, user: undefined };
}

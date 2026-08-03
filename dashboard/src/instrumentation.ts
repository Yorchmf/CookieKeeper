import * as Sentry from "@sentry/nextjs";

/**
 * Next.js server/edge instrumentation hook (ADR-15). Loads the runtime-appropriate Sentry init on
 * server startup. The DSN and environment are read from the runtime env here (not inlined), so one
 * built image stays correct across local/dev/prd.
 */
export async function register(): Promise<void> {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    await import("./sentry.server.config");
  }
  if (process.env.NEXT_RUNTIME === "edge") {
    await import("./sentry.edge.config");
  }
}

// Captures errors thrown in nested React Server Components (Next 15+/16 requestError hook).
export const onRequestError = Sentry.captureRequestError;

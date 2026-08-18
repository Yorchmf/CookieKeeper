import type { ErrorEvent } from "@sentry/nextjs";
import { describe, expect, test } from "vitest";
import {
  isEuRegionSentryDsn,
  requireEuSentryDsn,
  scrubSentryPii,
  sentryClientEnvironment,
  sentryTracesSampleRate,
} from "@/lib/sentry";

describe("isEuRegionSentryDsn", () => {
  test.each([
    ["https://key@o0.ingest.de.sentry.io/1", true],
    ["https://key@de.sentry.io/1", true],
    // US region — the default ingest host has no EU marker.
    ["https://key@o0.ingest.us.sentry.io/1", false],
    ["https://key@o0.ingest.sentry.io/1", false],
    // EU marker only in the userinfo, host is US — must not be treated as EU.
    ["https://de.sentry.io@o0.ingest.us.sentry.io/1", false],
    // Spoofed host that merely starts with the marker as a label prefix.
    ["https://key@de.sentry.io.attacker.example/1", false],
    ["not-a-url", false],
  ])("classifies %s as EU=%s", (dsn, expected) => {
    expect(isEuRegionSentryDsn(dsn)).toBe(expected);
  });
});

describe("requireEuSentryDsn", () => {
  test("returns empty string when the DSN is blank or unset (tracking disabled)", () => {
    expect(requireEuSentryDsn(undefined)).toBe("");
    expect(requireEuSentryDsn("   ")).toBe("");
  });

  test("returns the DSN unchanged when it targets the EU region", () => {
    const dsn = "https://key@o0.ingest.de.sentry.io/1";
    expect(requireEuSentryDsn(dsn)).toBe(dsn);
  });

  test.each([
    "https://key@o0.ingest.us.sentry.io/1",
    "https://de.sentry.io@o0.ingest.us.sentry.io/1",
    "https://key@de.sentry.io.attacker.example/1",
    "garbage",
  ])("throws for a non-EU or malformed DSN: %s", (dsn) => {
    expect(() => requireEuSentryDsn(dsn)).toThrow(/EU region/);
  });
});

describe("sentryTracesSampleRate", () => {
  test.each([
    ["0", 0],
    ["0.1", 0.1],
    ["1", 1],
    // Out of range / unparseable -> tracing off.
    ["1.5", 0],
    ["-0.2", 0],
    ["abc", 0],
    [undefined, 0],
  ])("parses %s to %s", (raw, expected) => {
    expect(sentryTracesSampleRate(raw)).toBe(expected);
  });
});

describe("sentryClientEnvironment", () => {
  test.each([
    ["localhost", "local"],
    ["127.0.0.1", "local"],
    ["dev.complyr.eu", "dev"],
    ["api.dev.complyr.eu", "dev"],
    ["app.complyr.eu", "prd"],
    ["complyr.eu", "prd"],
  ])("maps hostname %s to environment %s", (hostname, expected) => {
    expect(sentryClientEnvironment(hostname)).toBe(expected);
  });
});

describe("scrubSentryPii", () => {
  test("drops request, user, and breadcrumb context without mutating the input", () => {
    const event = {
      request: { url: "https://app.complyr.eu/billing?email=owner@example.com" },
      user: { email: "owner@example.com", ip_address: "203.0.113.7" },
      // Automatic fetch breadcrumbs record the public-scan capability token and query-string PII; these
      // must not ride along into Sentry just because request.url was scrubbed.
      breadcrumbs: [
        { category: "fetch", data: { url: "/api/v1/public-scan/tok_secret123/report" } },
        { category: "navigation", data: { to: "/scan?email=lead@example.com" } },
      ],
      message: "checkout failed",
    } as unknown as ErrorEvent;

    const scrubbed = scrubSentryPii(event);

    expect(scrubbed.request).toBeUndefined();
    expect(scrubbed.user).toBeUndefined();
    expect(scrubbed.breadcrumbs).toBeUndefined();
    expect(scrubbed.message).toBe("checkout failed");
    // Immutability: the original event is untouched.
    expect(event.request).toBeDefined();
    expect(event.user).toBeDefined();
    expect(event.breadcrumbs).toBeDefined();
  });
});

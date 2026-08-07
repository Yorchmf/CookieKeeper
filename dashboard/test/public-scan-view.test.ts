import { describe, expect, test } from "vitest";
import type { ComplianceIssue } from "@/lib/api/scans";
import {
  deriveComparisonRows,
  isThirdPartyCookie,
  STATIC_COMPARISON_ROWS,
} from "@/lib/public-scan-view";

function issue(code: string): ComplianceIssue {
  return { code, severity: "warning", count: 1 };
}

describe("deriveComparisonRows", () => {
  test("returns detected issue rows in backend order, then the static rows", () => {
    const { issueKeys, staticKeys } = deriveComparisonRows([
      issue("pre_consent_tracking"),
      issue("third_party_trackers"),
    ]);

    expect(issueKeys).toEqual(["pre_consent_tracking", "third_party_trackers"]);
    expect(staticKeys).toEqual(STATIC_COMPARISON_ROWS);
  });

  test("a clean scan yields no issue rows but keeps the static value props", () => {
    const { issueKeys, staticKeys } = deriveComparisonRows([]);

    expect(issueKeys).toEqual([]);
    expect(staticKeys).toEqual(["banner", "policy", "logs"]);
  });

  test("skips issue codes we have no copy for rather than rendering blank rows", () => {
    const { issueKeys } = deriveComparisonRows([
      issue("marketing_cookies"),
      issue("some_future_finding"),
    ]);

    expect(issueKeys).toEqual(["marketing_cookies"]);
  });

  test("de-duplicates a code defensively", () => {
    const { issueKeys } = deriveComparisonRows([
      issue("insecure_cookies"),
      issue("insecure_cookies"),
    ]);

    expect(issueKeys).toEqual(["insecure_cookies"]);
  });
});

describe("isThirdPartyCookie", () => {
  test("cookie on the scanned host is first-party", () => {
    expect(isThirdPartyCookie("example.com", "example.com")).toBe(false);
  });

  test("subdomain of the scanned host is first-party", () => {
    expect(isThirdPartyCookie("www.example.com", "example.com")).toBe(false);
  });

  test("leading-dot domain cookie on the scanned host is first-party", () => {
    expect(isThirdPartyCookie(".example.com", "example.com")).toBe(false);
  });

  test("an unrelated domain is third-party", () => {
    expect(isThirdPartyCookie("doubleclick.net", "example.com")).toBe(true);
  });

  test("a domain that merely ends with the site name (no dot boundary) is third-party", () => {
    expect(isThirdPartyCookie("notexample.com", "example.com")).toBe(true);
  });

  test("matching is case-insensitive", () => {
    expect(isThirdPartyCookie("WWW.Example.COM", "example.com")).toBe(false);
  });

  test("a null cookie domain is treated as first-party", () => {
    expect(isThirdPartyCookie(null, "example.com")).toBe(false);
  });
});

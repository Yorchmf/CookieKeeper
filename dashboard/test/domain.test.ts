import { describe, expect, test } from "vitest";
import {
  createDomainSchema,
  isValidDomain,
  normalizeDomain,
} from "@/lib/domain";

describe("normalizeDomain", () => {
  test.each([
    ["Example.COM", "example.com"],
    ["  example.com  ", "example.com"],
    ["https://example.com", "example.com"],
    ["http://example.com/path?q=1#frag", "example.com"],
    ["example.com:8080", "example.com"],
    ["example.com.", "example.com"],
    ["https://www.example.co.uk/shop/", "www.example.co.uk"],
  ])("normalizes %s to %s", (input, expected) => {
    expect(normalizeDomain(input)).toBe(expected);
  });
});

describe("isValidDomain", () => {
  test.each([
    "example.com",
    "www.example.eu",
    "sub.domain.example.co.uk",
    "xn--bcher-kva.de",
    "a-b.example.io",
  ])("accepts %s", (domain) => {
    expect(isValidDomain(domain)).toBe(true);
  });

  test.each([
    "",
    "localhost",
    "intranet",
    "192.168.1.1",
    "127.0.0.1",
    "[::1]",
    "2001:db8::1",
    "-bad.example.com",
    "bad-.example.com",
    "exa mple.com",
    "example.c0m1",
    "example.5",
    "*.example.com",
    `${"a".repeat(64)}.com`,
  ])("rejects %s", (domain) => {
    expect(isValidDomain(domain)).toBe(false);
  });
});

describe("createDomainSchema", () => {
  const schema = createDomainSchema("invalid");

  test("normalizes then validates pasted URLs", () => {
    expect(schema.parse("https://Shop.Example.EU/checkout")).toBe(
      "shop.example.eu",
    );
  });

  test("rejects localhost with the provided message", () => {
    const result = schema.safeParse("http://localhost:3000");
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toBe("invalid");
    }
  });

  test("rejects IP addresses", () => {
    expect(schema.safeParse("https://192.168.0.10").success).toBe(false);
  });

  test("rejects single-label hosts", () => {
    expect(schema.safeParse("myserver").success).toBe(false);
  });
});

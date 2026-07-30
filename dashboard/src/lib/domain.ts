/**
 * Client-side mirror of the backend domain validation for the "add site"
 * form. The server remains the authority — this only gives instant feedback
 * and normalizes obvious paste artifacts (scheme, path, port).
 */
import { z } from "zod";

const SCHEME_PATTERN = /^[a-z][a-z0-9+.-]*:\/\//;
const PORT_PATTERN = /:\d+$/;
const IPV4_PATTERN = /^\d{1,3}(\.\d{1,3}){3}$/;
const LABEL_PATTERN = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$/;
const TLD_PATTERN = /^[a-z]{2,}$/;
const MAX_DOMAIN_LENGTH = 253;

/** Lowercase, strip scheme / path / query / port / trailing dot. */
export function normalizeDomain(raw: string): string {
  let value = raw.trim().toLowerCase();
  value = value.replace(SCHEME_PATTERN, "");
  value = value.split(/[/?#]/, 1)[0] ?? "";
  value = value.replace(PORT_PATTERN, "");
  value = value.replace(/\.$/, "");
  return value;
}

/** Rejects IPs, localhost, single-label hosts, and malformed labels. */
export function isValidDomain(domain: string): boolean {
  if (domain.length === 0 || domain.length > MAX_DOMAIN_LENGTH) {
    return false;
  }
  if (domain === "localhost") {
    return false;
  }
  if (IPV4_PATTERN.test(domain)) {
    return false;
  }
  if (domain.includes(":")) {
    // IPv6 literal (or leftover port artifact) — never a registrable domain.
    return false;
  }

  const labels = domain.split(".");
  if (labels.length < 2) {
    return false;
  }
  if (!labels.every((label) => LABEL_PATTERN.test(label))) {
    return false;
  }
  return TLD_PATTERN.test(labels[labels.length - 1] ?? "");
}

/** Builds the zod schema with a translated error message. */
export function createDomainSchema(invalidMessage: string) {
  return z
    .string()
    .transform(normalizeDomain)
    .refine(isValidDomain, { message: invalidMessage });
}

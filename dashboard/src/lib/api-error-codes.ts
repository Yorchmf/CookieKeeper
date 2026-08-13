/**
 * Maps backend error codes to i18n message keys under `auth.errors.*`.
 * Unknown codes fall back to the generic message so we never leak raw
 * backend text to users.
 */
import { ApiError } from "@/lib/api";

export const KNOWN_ERROR_CODES = [
  "EMAIL_IN_USE",
  "INVALID_CREDENTIALS",
  "INVALID_TOKEN",
  "RATE_LIMITED",
  "EMAIL_NOT_VERIFIED",
  "DOMAIN_ALREADY_REGISTERED",
  "INVALID_DOMAIN",
  "ON_DEMAND_RESCAN_NOT_ENTITLED",
  "SCAN_ALREADY_IN_PROGRESS",
  "DELETE_CONFIRMATION_FAILED",
  "CURRENT_PASSWORD_INCORRECT",
  "NEW_PASSWORD_SAME_AS_CURRENT",
  "NEW_EMAIL_SAME_AS_CURRENT",
] as const;

export type KnownErrorCode = (typeof KNOWN_ERROR_CODES)[number];
export type ErrorMessageCode = KnownErrorCode | "GENERIC";

export function getApiErrorCode(error: unknown): ErrorMessageCode {
  if (
    error instanceof ApiError &&
    (KNOWN_ERROR_CODES as readonly string[]).includes(error.code)
  ) {
    return error.code as KnownErrorCode;
  }
  return "GENERIC";
}

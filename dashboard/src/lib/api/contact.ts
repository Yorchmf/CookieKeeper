/** Typed client for the authenticated in-app support contact endpoint. */
import { apiFetch } from "@/lib/api";

/**
 * Field limits mirror the backend DTO (SupportContactRequest.MAX_SUBJECT_LENGTH /
 * MAX_MESSAGE_LENGTH). The client validates against them so oversized input is
 * caught before the round-trip; the backend stays the authority and re-validates.
 */
export const CONTACT_SUBJECT_MAX_LENGTH = 150;
export const CONTACT_MESSAGE_MAX_LENGTH = 5_000;

export interface ContactRequest {
  subject: string;
  message: string;
}

/**
 * Send a support message. The backend composes an email to our support inbox with the account's own
 * address as Reply-To — no customer address is sent in the request. Rejects with a 429 (RATE_LIMITED)
 * when the per-user contact throttle trips, or 503 (CONTACT_DELIVERY_FAILED) when the mail provider is
 * down; both surface to the form as an inline error rather than a false "sent" confirmation.
 */
export async function submitContact(input: ContactRequest): Promise<void> {
  await apiFetch<Record<string, never>>("/api/v1/support/contact", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

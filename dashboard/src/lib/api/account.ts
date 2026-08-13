/**
 * Typed client for the `/api/v1/account` endpoints — the customer's own GDPR rights:
 *   GET  /api/v1/account/export.json — Art. 20 portability document
 *   POST /api/v1/account/delete      — Art. 17 erasure, re-authenticated with the password
 *
 * The export is never fetched through {@link apiFetch}: it is a file the customer keeps, served
 * outside the `{success,data,error,meta}` envelope with a `Content-Disposition` attachment header.
 * The browser downloads it from the same-origin path below, so the auth cookies attach on their own.
 */
import { apiFetch } from "@/lib/api";
import type { User } from "@/lib/api/auth";

/** Same-origin download path for the account export, used as an `<a download>` href. */
export const ACCOUNT_EXPORT_PATH = "/api/v1/account/export.json";

/**
 * Update the display name (backend `PATCH /api/v1/account/profile`). A blank or whitespace-only
 * value clears the name — the backend normalizes it to null — so the caller may pass the raw field.
 * Returns the refreshed user, which the caller seeds straight into the `me` cache.
 */
export async function updateProfileName(name: string): Promise<User> {
  const { data } = await apiFetch<User>("/api/v1/account/profile", {
    method: "PATCH",
    body: JSON.stringify({ name }),
  });
  return data;
}

/**
 * Change the account password (backend `POST /api/v1/account/password`). Re-authenticates with
 * `currentPassword`; on success the backend revokes every session and clears the auth cookies, so the
 * caller must send the user back to sign in rather than keep making requests with dead cookies.
 */
export async function changePassword(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  await apiFetch<{ ok: boolean }>("/api/v1/account/password", {
    method: "POST",
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}

/**
 * Start an email change (backend `POST /api/v1/account/email`, verify-new-first per ADR-20). Re-authenticates
 * with `currentPassword` and only PARKS the new address: the confirmation link is mailed to `newEmail`, and the
 * login email is untouched until that link is redeemed at `/auth/confirm-email-change`. The session is NOT
 * cleared, so the returned user (login email unchanged, `pendingEmail` now set) is authoritative — seed it
 * straight into the `me` cache so the card can render the pending state without refetching.
 */
export async function requestEmailChange(
  newEmail: string,
  currentPassword: string,
): Promise<User> {
  const { data } = await apiFetch<User>("/api/v1/account/email", {
    method: "POST",
    body: JSON.stringify({ newEmail, currentPassword }),
  });
  return data;
}

/**
 * Sign out of every device (backend `POST /api/v1/account/sessions/revoke-all`). Re-authenticates with
 * `currentPassword` and revokes every refresh token — including this browser's — so on success the backend
 * has cleared the auth cookies and the caller must send the user back to sign in rather than keep making
 * requests with dead cookies. The password itself is untouched.
 */
export async function signOutEverywhere(currentPassword: string): Promise<void> {
  await apiFetch<{ ok: boolean }>("/api/v1/account/sessions/revoke-all", {
    method: "POST",
    body: JSON.stringify({ currentPassword }),
  });
}

/**
 * The account's email notification preferences (backend `NotificationPreferencesResponse`). Both default
 * to `true` — an account that never touched this page reads as all-on, and the backend materializes a row
 * only on the first change. `scanComplete` gates the first-scan email when a site is added; `scanChanges`
 * gates the alert when a scheduled re-scan finds new or changed trackers.
 */
export interface NotificationPreferences {
  scanComplete: boolean;
  scanChanges: boolean;
}

/** Read the signed-in account's notification preferences (backend `GET /api/v1/account/notifications`). */
export async function getNotificationPreferences(): Promise<NotificationPreferences> {
  const { data } = await apiFetch<NotificationPreferences>(
    "/api/v1/account/notifications",
  );
  return data;
}

/**
 * Replace the notification preferences (backend `PUT /api/v1/account/notifications`). The PUT is a full
 * replace: both flags are required, so callers send the complete pair rather than a partial patch — an
 * omitted flag is a 400, never a silent opt-out. Returns the stored values the backend persisted.
 */
export async function updateNotificationPreferences(
  preferences: NotificationPreferences,
): Promise<NotificationPreferences> {
  const { data } = await apiFetch<NotificationPreferences>(
    "/api/v1/account/notifications",
    { method: "PUT", body: JSON.stringify(preferences) },
  );
  return data;
}

/**
 * What the erasure actually did (backend `AccountDeletionResponse`). Sites that never recorded a
 * consent event are deleted outright; sites that did survive as anonymized tombstones so the
 * append-only `consent_events` stay referentially valid until the retention job drops them (ADR-20).
 */
export interface AccountDeletionResult {
  sitesDeleted: number;
  sitesAnonymized: number;
}

/** Erase the signed-in account. The backend re-authenticates with `password` and clears the cookies. */
export async function deleteAccount(
  password: string,
): Promise<AccountDeletionResult> {
  const { data } = await apiFetch<AccountDeletionResult>(
    "/api/v1/account/delete",
    { method: "POST", body: JSON.stringify({ password }) },
  );
  return data;
}

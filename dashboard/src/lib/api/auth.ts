/**
 * Typed client for the `/api/v1/auth` endpoints.
 *
 * Tokens live in httpOnly cookies (`cmplyr_at`, `cmplyr_rt`) managed by the
 * browser — none of these functions ever see or store a token.
 */
import { apiFetch } from "@/lib/api";

export interface User {
  id: string;
  email: string;
  name: string | null;
  locale: string;
  verifiedAt: string | null;
  /**
   * The address awaiting confirmation while an email change is in flight, or null in the steady state.
   * Set by `POST /account/email` (verify-new-first) and cleared once the link mailed to it is redeemed;
   * never the account's login identity until then.
   */
  pendingEmail: string | null;
}

export interface SignupInput {
  email: string;
  password: string;
  locale: string;
}

export interface LoginInput {
  email: string;
  password: string;
}

export async function signup(input: SignupInput): Promise<User> {
  const { data } = await apiFetch<User>("/api/v1/auth/signup", {
    method: "POST",
    body: JSON.stringify(input),
  });
  return data;
}

export async function login(input: LoginInput): Promise<User> {
  const { data } = await apiFetch<User>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify(input),
  });
  return data;
}

export async function logout(): Promise<void> {
  await apiFetch<Record<string, never>>("/api/v1/auth/logout", {
    method: "POST",
  });
}

export async function getMe(): Promise<User> {
  const { data } = await apiFetch<User>("/api/v1/auth/me");
  return data;
}

export async function verifyEmail(token: string): Promise<User> {
  const { data } = await apiFetch<User>("/api/v1/auth/verify-email", {
    method: "POST",
    body: JSON.stringify({ token }),
  });
  return data;
}

/**
 * Redeem the link mailed to a pending new address, swapping it in as the login email (verify-new-first).
 * Unauthenticated on purpose — the link may be opened from the new inbox in a browser that never signed in —
 * so it neither reads nor issues a session; any current session stays as-is.
 */
export async function confirmEmailChange(token: string): Promise<User> {
  const { data } = await apiFetch<User>("/api/v1/auth/confirm-email-change", {
    method: "POST",
    body: JSON.stringify({ token }),
  });
  return data;
}

export async function resendVerification(email: string): Promise<void> {
  await apiFetch<Record<string, never>>("/api/v1/auth/resend-verification", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}

export async function forgotPassword(email: string): Promise<void> {
  await apiFetch<Record<string, never>>("/api/v1/auth/forgot-password", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}

export async function resetPassword(
  token: string,
  newPassword: string,
): Promise<void> {
  await apiFetch<Record<string, never>>("/api/v1/auth/reset-password", {
    method: "POST",
    body: JSON.stringify({ token, newPassword }),
  });
}

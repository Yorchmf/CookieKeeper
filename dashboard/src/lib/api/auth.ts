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
  locale: string;
  verifiedAt: string | null;
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

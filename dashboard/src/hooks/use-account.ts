"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ME_QUERY_KEY } from "@/hooks/use-auth";
import {
  changePassword,
  deleteAccount,
  getNotificationPreferences,
  requestEmailChange,
  signOutEverywhere,
  updateNotificationPreferences,
  updateProfileName,
  type NotificationPreferences,
} from "@/lib/api/account";
import type { User } from "@/lib/api/auth";

/** Cache key for the account's email notification preferences — its own server-state entry, not part of `me`. */
export const NOTIFICATION_PREFERENCES_QUERY_KEY = [
  "account",
  "notifications",
] as const;

/** Read the account's notification preferences. All-on until the customer changes something. */
export function useNotificationPreferences() {
  return useQuery({
    queryKey: NOTIFICATION_PREFERENCES_QUERY_KEY,
    queryFn: getNotificationPreferences,
  });
}

/**
 * Flip a notification toggle. The switch should feel instant, so the update is optimistic: snapshot the
 * cache, write the new pair immediately, and roll back to the snapshot if the PUT fails. On settle the
 * cache is reconciled with whatever the server actually stored, so a rejected or reordered request can
 * never leave the toggle showing a value the backend didn't persist.
 */
export function useUpdateNotificationPreferences() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (preferences: NotificationPreferences) =>
      updateNotificationPreferences(preferences),
    onMutate: async (next: NotificationPreferences) => {
      await queryClient.cancelQueries({
        queryKey: NOTIFICATION_PREFERENCES_QUERY_KEY,
      });
      const previous = queryClient.getQueryData<NotificationPreferences>(
        NOTIFICATION_PREFERENCES_QUERY_KEY,
      );
      queryClient.setQueryData(NOTIFICATION_PREFERENCES_QUERY_KEY, next);
      return { previous };
    },
    onError: (_error, _next, context) => {
      if (context?.previous) {
        queryClient.setQueryData(
          NOTIFICATION_PREFERENCES_QUERY_KEY,
          context.previous,
        );
      }
    },
    onSuccess: (stored: NotificationPreferences) => {
      queryClient.setQueryData(NOTIFICATION_PREFERENCES_QUERY_KEY, stored);
    },
  });
}

/**
 * Update the account display name. The PATCH returns the refreshed user, so seed it straight into the
 * `me` cache — no invalidation/refetch, the response is already authoritative. Everything else the
 * dashboard shows keys off `sites`/analytics, not the name, so nothing else needs busting.
 */
export function useUpdateName() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => updateProfileName(name),
    onSuccess: (user: User) => {
      queryClient.setQueryData(ME_QUERY_KEY, user);
    },
  });
}

/**
 * Start an email change (verify-new-first). The POST only parks the new address and mails a confirmation link
 * to it — the session stays valid and the login email is unchanged — so the returned user is authoritative:
 * seed it into the `me` cache so the card renders the pending state immediately, no invalidation/refetch. The
 * actual swap happens later at `/auth/confirm-email-change` when the link is redeemed, in this browser or
 * another, which is why nothing here waits on it.
 */
export function useRequestEmailChange() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { newEmail: string; currentPassword: string }) =>
      requestEmailChange(input.newEmail, input.currentPassword),
    onSuccess: (user: User) => {
      queryClient.setQueryData(ME_QUERY_KEY, user);
    },
  });
}

/**
 * Change password. On success the backend has revoked every session and expired the auth cookies, so this
 * session is over: clear the cache rather than let a stale `me`/`sites` keep rendering or a poll refetch
 * into a 401. The caller shows a terminal "signed out" panel and points the user back at sign-in.
 */
export function useChangePassword() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { currentPassword: string; newPassword: string }) =>
      changePassword(input.currentPassword, input.newPassword),
    onSuccess: () => {
      queryClient.clear();
    },
  });
}

/**
 * Sign out of every device. On success the backend has revoked every session and expired the auth cookies,
 * so this session is over too: clear the cache rather than let a stale `me`/`sites` keep rendering or a poll
 * refetch into a 401. The caller shows a terminal "signed out" panel and points the user back at sign-in —
 * mirrors {@link useChangePassword}, since both end the session server-side.
 */
export function useSignOutEverywhere() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (currentPassword: string) => signOutEverywhere(currentPassword),
    onSuccess: () => {
      queryClient.clear();
    },
  });
}

/**
 * Art. 17 erasure. On success the backend has already expired the auth cookies, so every cached query
 * belongs to an account that no longer exists — clear the cache rather than let a stale `me` or `sites`
 * entry keep rendering. Nothing is invalidated (that would refetch into a 401); the caller shows a
 * terminal confirmation panel and sends the visitor back to the public site.
 */
export function useDeleteAccount() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (password: string) => deleteAccount(password),
    onSuccess: () => {
      queryClient.clear();
    },
  });
}

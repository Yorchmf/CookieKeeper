"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import {
  createCheckoutSession,
  createPortalSession,
  getEntitlement,
  type PlanId,
} from "@/lib/api/billing";

export const BILLING_QUERY_KEY = ["billing", "entitlement"] as const;

/**
 * Reject an empty or non-HTTPS session URL before navigating. These URLs are minted server-side by
 * Stripe, so this is defense-in-depth: it turns a malformed backend response into the mutation's error
 * path (a toast) instead of a full-page navigation to `/undefined` or an unexpected scheme.
 */
function assertRedirectUrl(url: string): string {
  if (!/^https:\/\//i.test(url)) {
    throw new Error("Unexpected billing redirect URL");
  }
  return url;
}

/** The current account's billing state + usage (dashboard billing page). */
export function useEntitlement() {
  return useQuery({
    queryKey: BILLING_QUERY_KEY,
    queryFn: getEntitlement,
  });
}

/**
 * Start a Stripe Checkout for a plan and hand the browser off to the returned hosted URL. Navigation
 * is a full-page redirect to Stripe, so there is no local cache to invalidate — the account returns
 * via the success URL, where the entitlement query refetches on mount.
 */
export function useCheckout() {
  return useMutation({
    mutationFn: (plan: PlanId) => createCheckoutSession(plan).then(assertRedirectUrl),
    onSuccess: (url) => {
      window.location.href = url;
    },
  });
}

/** Open the Stripe Customer Portal and redirect the browser to the returned hosted URL. */
export function usePortal() {
  return useMutation({
    mutationFn: () => createPortalSession().then(assertRedirectUrl),
    onSuccess: (url) => {
      window.location.href = url;
    },
  });
}

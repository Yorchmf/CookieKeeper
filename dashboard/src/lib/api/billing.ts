/**
 * Typed client for the authenticated billing endpoints under `/api/v1/billing`:
 * - `GET  /entitlement`      — the account's billing state + usage (dashboard billing page)
 * - `POST /checkout-session` — start a Stripe Checkout for a plan, returns a hosted redirect URL
 * - `POST /portal-session`   — open the Stripe Customer Portal, returns a hosted redirect URL
 *
 * Mirrors the backend DTOs in `com.complyr.billing.dto` (BillingDtos.kt). The two session calls return
 * a Stripe-hosted URL the browser then navigates to; nothing is rendered from them.
 */
import { apiFetch } from "@/lib/api";

/** The purchasable plans — the enum names the checkout endpoint accepts (Plan.kt). */
export type PlanId = "STARTER" | "PRO" | "BUSINESS";

/** The account's effective billing state (EntitlementResponse.state). */
export type BillingState = "trial" | "subscribed" | "expired";

/** The concrete limits for the resolved entitlement (EntitlementLimits). */
export interface EntitlementLimits {
  maxSites: number;
  rescanFrequency: string;
  onDemandRescan: boolean;
  priorityScan: boolean;
  removeBranding: boolean;
  csvExport: boolean;
  consentRetentionMonths: number;
  consentEventCap: number | null;
}

/**
 * The current account's billing snapshot (EntitlementResponse). `plan` is set only when
 * `state === "subscribed"`; `trialEndsAt` only while `state === "trial"`.
 */
export interface Entitlement {
  state: BillingState;
  plan: PlanId | null;
  trialEndsAt: string | null;
  activeSites: number;
  limits: EntitlementLimits;
}

/** The account's billing state + usage, for the dashboard billing page. */
export async function getEntitlement(): Promise<Entitlement> {
  const { data } = await apiFetch<Entitlement>("/api/v1/billing/entitlement");
  return data;
}

/** Start a Stripe Checkout for `plan`; returns the hosted URL the browser should navigate to. */
export async function createCheckoutSession(plan: PlanId): Promise<string> {
  const { data } = await apiFetch<{ url: string }>(
    "/api/v1/billing/checkout-session",
    { method: "POST", body: JSON.stringify({ plan }) },
  );
  return data.url;
}

/** Open the Stripe Customer Portal; returns the hosted URL the browser should navigate to. */
export async function createPortalSession(): Promise<string> {
  const { data } = await apiFetch<{ url: string }>(
    "/api/v1/billing/portal-session",
    { method: "POST" },
  );
  return data.url;
}

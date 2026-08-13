/**
 * Typed client for the cookie-policy endpoints:
 * - authenticated management under `/api/v1/sites/{siteId}/policy` (generate + read current)
 * - the authenticated owner preview `/api/v1/sites/{siteId}/policy/preview`
 * - the public hosted read `/api/v1/public/policy/{publicId}` the `/p/{publicId}` page renders.
 *
 * The preview and the hosted read return the identical payload but differ in what gates them: the
 * hosted page 404s until the site's domain is verified (ADR-17), while the preview is reached behind
 * the JWT so the owner can see what they are about to publish *before* verifying.
 *
 * Mirrors the backend DTOs in `com.complyr.policy.dto` (PolicyDtos.kt).
 */
import { ApiError, apiFetch } from "@/lib/api";

/** The site's current published policy (PolicyCurrentResponse). */
export interface PolicyCurrent {
  version: number;
  publicId: string;
  hostedUrl: string;
  languages: string[];
  publishedAt: string | null;
}

/** Result of a generate call (PolicyGenerationResponse). */
export interface PolicyGenerated {
  version: number;
  publicId: string;
  hostedUrl: string;
  languages: string[];
}

/**
 * The public hosted-page payload (PublicPolicyResponse). `html` is the self-contained, backend-escaped
 * policy block — see {@link import("@/components/policy/policy-html").PolicyHtml} for the injection contract.
 */
export interface PublicPolicy {
  version: number;
  language: string;
  availableLanguages: string[];
  companyName: string;
  html: string;
  publishedAt: string | null;
  /** Site owner's plan entitlement: when true, hide the "Powered by Complyr" footer. */
  removeBranding: boolean;
}

/** Business details + optional language subset that fill the template (PolicyGenerationRequest). */
export interface PolicyGenerationInput {
  companyName: string;
  contactEmail: string;
  websiteUrl?: string;
  address?: string;
  languages?: string[];
}

/**
 * The site's current published policy, or `null` when none has been generated yet. The backend answers
 * an un-generated site with a 404 (PolicyNotFoundException) — a normal "empty" state here, not an error.
 */
export async function getCurrentPolicy(
  siteId: string,
): Promise<PolicyCurrent | null> {
  try {
    const { data } = await apiFetch<PolicyCurrent>(
      `/api/v1/sites/${encodeURIComponent(siteId)}/policy`,
    );
    return data;
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}

/** (Re)generate and publish the site's policy from the given business details. */
export async function generatePolicy(
  siteId: string,
  input: PolicyGenerationInput,
): Promise<PolicyGenerated> {
  const { data } = await apiFetch<PolicyGenerated>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/policy`,
    { method: "POST", body: JSON.stringify(input) },
  );
  return data;
}

/**
 * The owner's preview of their published policy, by site id. Same payload as the hosted page and the
 * same language resolution, but ungated by domain verification — see the module header. Throws
 * {@link ApiError} with status 404 when nothing has been generated yet, or when the site isn't the
 * caller's (the usual anti-enumeration 404).
 */
export async function getPolicyPreview(
  siteId: string,
  lang?: string,
): Promise<PublicPolicy> {
  const query = lang ? `?lang=${encodeURIComponent(lang)}` : "";
  const { data } = await apiFetch<PublicPolicy>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/policy/preview${query}`,
  );
  return data;
}

/**
 * Public hosted read by opaque public id; `lang` narrows to one of the version's languages (defaults to
 * the first). Throws {@link ApiError} with status 404 for an unknown/unpublished id — the page turns that
 * into a "not found" state.
 */
export async function getPublicPolicy(
  publicId: string,
  lang?: string,
): Promise<PublicPolicy> {
  const query = lang ? `?lang=${encodeURIComponent(lang)}` : "";
  const { data } = await apiFetch<PublicPolicy>(
    `/api/v1/public/policy/${encodeURIComponent(publicId)}${query}`,
  );
  return data;
}

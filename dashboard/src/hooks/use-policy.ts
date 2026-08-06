"use client";

import {
  keepPreviousData,
  skipToken,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  generatePolicy,
  getCurrentPolicy,
  getPolicyPreview,
  getPublicPolicy,
  type PolicyGenerationInput,
} from "@/lib/api/policy";

export const POLICY_QUERY_KEY = ["policy"] as const;

/** The site's current published policy, or null when none exists yet (dashboard policy page). */
export function usePolicy(siteId: string) {
  return useQuery({
    queryKey: [...POLICY_QUERY_KEY, siteId],
    queryFn: () => getCurrentPolicy(siteId),
  });
}

/**
 * The public hosted policy for one language — powers the hosted `/p/{publicId}` page. `publicId` is
 * optional so a caller can render before an id is known; the query stays idle until one arrives.
 *
 * The dashboard preview does NOT use this: the hosted read 404s until the domain is verified
 * (ADR-17), which would blind the owner to what they are about to publish. See {@link usePolicyPreview}.
 */
export function usePublicPolicy(publicId: string | undefined, lang?: string) {
  return useQuery({
    queryKey: [...POLICY_QUERY_KEY, "public", publicId ?? null, lang ?? null],
    // skipToken keeps the query idle (no cast) until an id exists; keepPreviousData holds the last
    // policy on screen while a language switch refetches, so the language control never unmounts.
    queryFn: publicId ? () => getPublicPolicy(publicId, lang) : skipToken,
    placeholderData: keepPreviousData,
  });
}

/**
 * The owner's rendered policy for one language, behind the JWT and ungated by domain verification —
 * what the dashboard preview shows. Resolves the same language the hosted page would, so the preview
 * and the live page can never disagree.
 */
export function usePolicyPreview(siteId: string | undefined, lang?: string) {
  return useQuery({
    queryKey: [...POLICY_QUERY_KEY, "preview", siteId ?? null, lang ?? null],
    queryFn: siteId ? () => getPolicyPreview(siteId, lang) : skipToken,
    placeholderData: keepPreviousData,
  });
}

/**
 * Generate/regenerate the policy, then refresh the current policy plus any cached rendered reads —
 * both the preview the dashboard is showing and the hosted read, which a `/p/{publicId}` tab may hold.
 */
export function useGeneratePolicy(siteId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: PolicyGenerationInput) => generatePolicy(siteId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...POLICY_QUERY_KEY, siteId],
      });
      void queryClient.invalidateQueries({
        queryKey: [...POLICY_QUERY_KEY, "preview"],
      });
      void queryClient.invalidateQueries({
        queryKey: [...POLICY_QUERY_KEY, "public"],
      });
    },
  });
}

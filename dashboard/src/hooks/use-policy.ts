"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  generatePolicy,
  getCurrentPolicy,
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
 * The public hosted policy for one language — powers both the hosted `/p/{publicId}` page and the
 * dashboard preview. `publicId` is optional so the dashboard can call it before a policy exists; the
 * query stays idle until an id is available.
 */
export function usePublicPolicy(publicId: string | undefined, lang?: string) {
  return useQuery({
    queryKey: [...POLICY_QUERY_KEY, "public", publicId ?? null, lang ?? null],
    queryFn: () => getPublicPolicy(publicId as string, lang),
    enabled: Boolean(publicId),
  });
}

/** Generate/regenerate the policy, then refresh the current policy and any cached hosted reads. */
export function useGeneratePolicy(siteId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: PolicyGenerationInput) => generatePolicy(siteId, input),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: [...POLICY_QUERY_KEY, siteId],
      });
      void queryClient.invalidateQueries({
        queryKey: [...POLICY_QUERY_KEY, "public"],
      });
    },
  });
}

"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getMe,
  login,
  logout,
  signup,
  type LoginInput,
  type SignupInput,
  type User,
} from "@/lib/api/auth";

export const ME_QUERY_KEY = ["me"] as const;

export function useMe() {
  return useQuery({
    queryKey: ME_QUERY_KEY,
    queryFn: getMe,
    retry: false,
  });
}

export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: LoginInput) => login(input),
    onSuccess: (user: User) => {
      // A different user may have used this SPA session before — drop every
      // cached query (e.g. ["sites"]) so nothing leaks across accounts.
      queryClient.clear();
      queryClient.setQueryData(ME_QUERY_KEY, user);
    },
  });
}

export function useSignup() {
  return useMutation({
    mutationFn: (input: SignupInput) => signup(input),
  });
}

export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: logout,
    onSuccess: () => {
      // Nothing cached is valid for an anonymous visitor.
      queryClient.clear();
    },
  });
}

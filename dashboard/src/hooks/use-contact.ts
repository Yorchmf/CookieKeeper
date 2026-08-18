"use client";

import { useMutation } from "@tanstack/react-query";
import { submitContact } from "@/lib/api/contact";

/**
 * Send a support message. There is no server state to cache or invalidate — the message is fire-and-
 * forget from the client's side of things (the backend emails it to our inbox), so this is a plain
 * mutation. Callers read `isPending` for the submit button and surface failures via the thrown ApiError.
 */
export function useSubmitContact() {
  return useMutation({
    mutationFn: submitContact,
  });
}

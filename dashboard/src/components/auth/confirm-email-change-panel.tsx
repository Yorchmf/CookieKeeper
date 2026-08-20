"use client";

import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { ButtonLink } from "@/components/ui/button-link";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { getApiErrorCode } from "@/lib/api-error-codes";
import { confirmEmailChange } from "@/lib/api/auth";

type ConfirmState = "confirming" | "success" | "invalid";

/**
 * Landing page for the link mailed to a pending NEW address (verify-new-first, ADR-20). Redeeming the token
 * swaps the address in as the login email. The endpoint is unauthenticated and issues no session, so this
 * panel assumes nothing about the current sign-in state — it just reports the outcome and points the user at
 * sign-in. Mirrors the verify-email panel, minus the resend form: re-requesting a change requires signing in
 * and using the settings card, so there is nothing to resend from here.
 */
export function ConfirmEmailChangePanel() {
  const t = useTranslations("auth.confirmEmailChange");
  const tErrors = useTranslations("auth.errors");
  const searchParams = useSearchParams();
  const token = searchParams.get("token");

  const [state, setState] = useState<ConfirmState>(
    token ? "confirming" : "invalid",
  );
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const attemptedToken = useRef<string | null>(null);

  useEffect(() => {
    if (!token || attemptedToken.current === token) {
      return;
    }
    // Guard against StrictMode double-invocation (the token is single-use) while still confirming again if a
    // different ?token= arrives.
    attemptedToken.current = token;
    setState("confirming");
    // Ignore a response that resolves after unmount or after a newer ?token= superseded this run. apiFetch
    // takes no signal, so an ignore flag is the pragmatic guard.
    let ignore = false;
    confirmEmailChange(token)
      .then(() => {
        if (!ignore) {
          setState("success");
        }
      })
      .catch((error: unknown) => {
        if (ignore) {
          return;
        }
        setErrorCode(getApiErrorCode(error));
        setState("invalid");
      });
    return () => {
      ignore = true;
    };
  }, [token]);

  if (state === "confirming") {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t("title")}</CardTitle>
          <CardDescription role="status">{t("confirming")}</CardDescription>
        </CardHeader>
      </Card>
    );
  }

  if (state === "success") {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t("successTitle")}</CardTitle>
          <CardDescription>{t("successDescription")}</CardDescription>
        </CardHeader>
        <CardFooter>
          <ButtonLink href="/login" className="w-full">
            {t("goToLogin")}
          </ButtonLink>
        </CardFooter>
      </Card>
    );
  }

  // A token that resolves before a ?token= is present shows the generic invalid link message.
  const description = errorCode ? tErrors(errorCode) : t("invalidDescription");

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("invalidTitle")}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>
        <p className="text-sm text-muted-foreground">{t("invalidHelp")}</p>
      </CardContent>
      <CardFooter className="mt-4">
        <ButtonLink href="/login" className="w-full">
          {t("goToLogin")}
        </ButtonLink>
      </CardFooter>
    </Card>
  );
}

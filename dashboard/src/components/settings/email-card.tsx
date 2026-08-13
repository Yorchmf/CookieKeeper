"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslations } from "next-intl";
import { useMemo } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { FormError } from "@/components/forms/form-error";
import { FormField } from "@/components/forms/form-field";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useRequestEmailChange } from "@/hooks/use-account";
import { useMe } from "@/hooks/use-auth";
import { getApiErrorCode } from "@/lib/api-error-codes";

/**
 * The change-email half of `/settings/profile` (verify-new-first, ADR-20). Submitting only PARKS the new
 * address and mails a confirmation link to it; the login email stays as-is until that link is redeemed at
 * `/confirm-email-change`. So this card is deliberately non-terminal — unlike the password card, the session
 * survives. It re-authenticates with the current password and shows the pending address (seeded from `me`,
 * which the request response updates) until confirmation completes elsewhere.
 *
 * Backend error codes are mapped through this card's own `errors.*` namespace rather than the shared
 * `auth.errors`, so the wording is email-specific (e.g. "your email address has not been changed") without
 * disturbing the password card that shares `CURRENT_PASSWORD_INCORRECT`.
 */
export function EmailCard() {
  const t = useTranslations("settings.profile.email");
  const tValidation = useTranslations("auth.validation");
  const { data: me } = useMe();
  const requestEmailChange = useRequestEmailChange();

  const schema = useMemo(
    () =>
      z.object({
        newEmail: z.email({ message: tValidation("emailInvalid") }),
        currentPassword: z.string().min(1, tValidation("passwordRequired")),
      }),
    [tValidation],
  );

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<z.input<typeof schema>, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
  });

  const errorCode = requestEmailChange.error
    ? getApiErrorCode(requestEmailChange.error)
    : null;
  // Prefer the just-sent address from the mutation result, falling back to whatever `me` already carried.
  const pendingEmail =
    requestEmailChange.data?.pendingEmail ?? me?.pendingEmail ?? null;

  const onSubmit = handleSubmit(async (values) => {
    try {
      await requestEmailChange.mutateAsync({
        newEmail: values.newEmail,
        currentPassword: values.currentPassword,
      });
      // Clear the password field so it never lingers; the pending banner now carries the confirmation.
      reset({ newEmail: "", currentPassword: "" });
    } catch {
      // Surfaced through requestEmailChange.error → errorCode; nothing more to do here.
    }
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <form onSubmit={onSubmit} noValidate>
        <CardContent className="flex flex-col gap-4">
          <FormError
            message={errorCode ? mapError(t, errorCode) : null}
          />
          <div className="text-sm">
            <span className="text-muted-foreground">{t("currentLabel")}: </span>
            <span className="font-medium">{me?.email ?? ""}</span>
          </div>
          {pendingEmail ? (
            <p
              role="status"
              className="rounded-md bg-muted px-3 py-2 text-sm text-muted-foreground"
            >
              {t("pending", { email: pendingEmail })}
            </p>
          ) : null}
          <FormField
            id="change-new-email"
            label={t("newLabel")}
            type="email"
            autoComplete="email"
            error={errors.newEmail?.message}
            {...register("newEmail")}
          />
          <FormField
            id="change-email-current-password"
            label={t("currentPasswordLabel")}
            type="password"
            autoComplete="current-password"
            error={errors.currentPassword?.message}
            {...register("currentPassword")}
          />
        </CardContent>
        <CardFooter className="mt-4">
          <Button
            type="submit"
            disabled={isSubmitting || requestEmailChange.isPending}
          >
            {t("save")}
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}

/** Email-specific wording for the codes this endpoint can return; anything else falls back to generic. */
function mapError(
  t: ReturnType<typeof useTranslations>,
  code: string,
): string {
  switch (code) {
    case "CURRENT_PASSWORD_INCORRECT":
    case "EMAIL_IN_USE":
    case "NEW_EMAIL_SAME_AS_CURRENT":
    case "RATE_LIMITED":
      return t(`errors.${code}`);
    default:
      return t("errors.GENERIC");
  }
}

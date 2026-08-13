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
import { useSignOutEverywhere } from "@/hooks/use-account";
import { Link } from "@/i18n/navigation";
import { getApiErrorCode } from "@/lib/api-error-codes";

/**
 * The "sign out of all devices" control on `/settings/security`. Re-authenticates with the current password
 * and revokes every refresh token — including this browser's — so it is terminal: on success the card is
 * replaced by a "signed out" panel pointing back at sign-in rather than trying to keep the now-dead session
 * alive. The copy is honest about the limit: other devices' access tokens are stateless and stay valid until
 * they expire, so those sessions drop out within minutes rather than instantly.
 */
export function SecurityCard() {
  const t = useTranslations("settings.security");
  const tValidation = useTranslations("auth.validation");
  const signOutEverywhere = useSignOutEverywhere();

  const schema = useMemo(
    () =>
      z.object({
        currentPassword: z.string().min(1, tValidation("passwordRequired")),
      }),
    [tValidation],
  );

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<z.input<typeof schema>, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
  });

  const errorCode = signOutEverywhere.error
    ? getApiErrorCode(signOutEverywhere.error)
    : null;

  const onSubmit = handleSubmit(async (values) => {
    try {
      await signOutEverywhere.mutateAsync(values.currentPassword);
    } catch {
      // Surfaced through signOutEverywhere.error → errorCode; nothing more to do here.
    }
  });

  if (signOutEverywhere.isSuccess) {
    return <SignedOutPanel />;
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <form onSubmit={onSubmit} noValidate>
        <CardContent className="flex flex-col gap-4">
          <FormError message={errorCode ? mapError(t, errorCode) : null} />
          <FormField
            id="revoke-current-password"
            label={t("currentPasswordLabel")}
            type="password"
            autoComplete="current-password"
            error={errors.currentPassword?.message}
            {...register("currentPassword")}
          />
          <p className="text-sm text-muted-foreground">{t("note")}</p>
        </CardContent>
        <CardFooter className="mt-4">
          <Button
            type="submit"
            variant="destructive"
            disabled={isSubmitting || signOutEverywhere.isPending}
          >
            {t("submit")}
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}

/** Wording for the codes this endpoint can return; anything else falls back to generic. */
function mapError(
  t: ReturnType<typeof useTranslations>,
  code: string,
): string {
  switch (code) {
    case "CURRENT_PASSWORD_INCORRECT":
    case "RATE_LIMITED":
      return t(`errors.${code}`);
    default:
      return t("errors.GENERIC");
  }
}

/**
 * Terminal state: the revoke killed every session and the cookies are already gone, so this panel must not
 * depend on any further request. It confirms the sign-out and sends the user to sign in again.
 */
function SignedOutPanel() {
  const t = useTranslations("settings.security.done");

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardFooter>
        <Button nativeButton={false} render={<Link href="/login" />}>
          {t("login")}
        </Button>
      </CardFooter>
    </Card>
  );
}

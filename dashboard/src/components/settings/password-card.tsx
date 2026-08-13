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
import { useChangePassword } from "@/hooks/use-account";
import { Link } from "@/i18n/navigation";
import { getApiErrorCode } from "@/lib/api-error-codes";
import { MAX_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH } from "@/lib/password";

/**
 * The change-password half of `/settings/profile`. Re-authenticates with the current password; the new
 * one carries the same length policy as signup/reset (mirrored from the backend). A successful change
 * revokes every session server-side, so this is terminal: the card is replaced by a "signed out" panel
 * pointing back at sign-in rather than trying to keep the now-dead session alive.
 */
export function PasswordCard() {
  const t = useTranslations("settings.profile.password");
  const tValidation = useTranslations("auth.validation");
  const tErrors = useTranslations("auth.errors");
  const changePassword = useChangePassword();

  const schema = useMemo(
    () =>
      z
        .object({
          currentPassword: z.string().min(1, tValidation("passwordRequired")),
          newPassword: z
            .string()
            .min(
              MIN_PASSWORD_LENGTH,
              tValidation("passwordMin", { min: MIN_PASSWORD_LENGTH }),
            )
            .max(
              MAX_PASSWORD_LENGTH,
              tValidation("passwordMax", { max: MAX_PASSWORD_LENGTH }),
            ),
          confirmPassword: z.string(),
        })
        .refine((values) => values.newPassword === values.confirmPassword, {
          message: tValidation("passwordMismatch"),
          path: ["confirmPassword"],
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

  const errorCode = changePassword.error
    ? getApiErrorCode(changePassword.error)
    : null;

  const onSubmit = handleSubmit(async (values) => {
    try {
      await changePassword.mutateAsync({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });
    } catch {
      // Surfaced through changePassword.error → errorCode; nothing more to do here.
    }
  });

  if (changePassword.isSuccess) {
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
          <FormError message={errorCode ? tErrors(errorCode) : null} />
          <FormField
            id="change-current-password"
            label={t("currentLabel")}
            type="password"
            autoComplete="current-password"
            error={errors.currentPassword?.message}
            {...register("currentPassword")}
          />
          <FormField
            id="change-new-password"
            label={t("newLabel")}
            type="password"
            autoComplete="new-password"
            error={errors.newPassword?.message}
            {...register("newPassword")}
          />
          <FormField
            id="change-confirm-password"
            label={t("confirmLabel")}
            type="password"
            autoComplete="new-password"
            error={errors.confirmPassword?.message}
            {...register("confirmPassword")}
          />
        </CardContent>
        <CardFooter className="mt-4">
          <Button type="submit" disabled={isSubmitting || changePassword.isPending}>
            {t("save")}
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}

/**
 * Terminal state: the change revoked every session and the cookies are already gone, so this panel must
 * not depend on any further request. It confirms the change and sends the user to sign in again.
 */
function SignedOutPanel() {
  const t = useTranslations("settings.profile.password.done");

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

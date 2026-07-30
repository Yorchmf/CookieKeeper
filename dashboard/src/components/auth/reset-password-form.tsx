"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { useState } from "react";
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
import { Link } from "@/i18n/navigation";
import { getApiErrorCode } from "@/lib/api-error-codes";
import { resetPassword } from "@/lib/api/auth";
import { MAX_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH } from "@/lib/password";

export function ResetPasswordForm() {
  const t = useTranslations("auth");
  const searchParams = useSearchParams();
  const token = searchParams.get("token");

  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [isSuccess, setIsSuccess] = useState(false);

  const schema = z
    .object({
      newPassword: z
        .string()
        .min(
          MIN_PASSWORD_LENGTH,
          t("validation.passwordMin", { min: MIN_PASSWORD_LENGTH }),
        )
        .max(
          MAX_PASSWORD_LENGTH,
          t("validation.passwordMax", { max: MAX_PASSWORD_LENGTH }),
        ),
      confirmPassword: z.string(),
    })
    .refine((values) => values.newPassword === values.confirmPassword, {
      message: t("validation.passwordMismatch"),
      path: ["confirmPassword"],
    });
  type FormValues = z.infer<typeof schema>;

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });

  const onSubmit = handleSubmit(async (values) => {
    if (!token) {
      setErrorCode("INVALID_TOKEN");
      return;
    }
    setErrorCode(null);
    try {
      await resetPassword(token, values.newPassword);
      setIsSuccess(true);
    } catch (error) {
      setErrorCode(getApiErrorCode(error));
    }
  });

  if (isSuccess) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t("resetPassword.successTitle")}</CardTitle>
          <CardDescription>
            {t("resetPassword.successDescription")}
          </CardDescription>
        </CardHeader>
        <CardFooter>
          <Button render={<Link href="/login" />} className="w-full">
            {t("resetPassword.goToLogin")}
          </Button>
        </CardFooter>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("resetPassword.title")}</CardTitle>
        <CardDescription>{t("resetPassword.description")}</CardDescription>
      </CardHeader>
      <form onSubmit={onSubmit} noValidate>
        <CardContent className="flex flex-col gap-4">
          <FormError
            message={
              !token
                ? t("errors.INVALID_TOKEN")
                : errorCode
                  ? t(`errors.${errorCode}`)
                  : null
            }
          />
          <FormField
            id="reset-new-password"
            label={t("fields.newPassword")}
            type="password"
            autoComplete="new-password"
            error={errors.newPassword?.message}
            {...register("newPassword")}
          />
          <FormField
            id="reset-confirm-password"
            label={t("fields.confirmPassword")}
            type="password"
            autoComplete="new-password"
            error={errors.confirmPassword?.message}
            {...register("confirmPassword")}
          />
        </CardContent>
        <CardFooter className="mt-4">
          <Button
            type="submit"
            className="w-full"
            disabled={isSubmitting || !token}
          >
            {t("resetPassword.submit")}
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}

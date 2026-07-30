"use client";

import { zodResolver } from "@hookform/resolvers/zod";
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
import { forgotPassword } from "@/lib/api/auth";

export function ForgotPasswordForm() {
  const t = useTranslations("auth");
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [isSubmitted, setIsSubmitted] = useState(false);

  const schema = z.object({
    email: z.email({ message: t("validation.emailInvalid") }),
  });
  type FormValues = z.infer<typeof schema>;

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });

  const onSubmit = handleSubmit(async (values) => {
    setErrorCode(null);
    try {
      await forgotPassword(values.email);
      setIsSubmitted(true);
    } catch (error) {
      setErrorCode(getApiErrorCode(error));
    }
  });

  if (isSubmitted) {
    // Deliberately generic — never reveal whether the address has an account.
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t("forgotPassword.confirmationTitle")}</CardTitle>
          <CardDescription>
            {t("forgotPassword.confirmationDescription")}
          </CardDescription>
        </CardHeader>
        <CardFooter>
          <Link
            href="/login"
            className="text-sm text-muted-foreground underline-offset-4 hover:underline"
          >
            {t("forgotPassword.backToLogin")}
          </Link>
        </CardFooter>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("forgotPassword.title")}</CardTitle>
        <CardDescription>{t("forgotPassword.description")}</CardDescription>
      </CardHeader>
      <form onSubmit={onSubmit} noValidate>
        <CardContent className="flex flex-col gap-4">
          <FormError message={errorCode ? t(`errors.${errorCode}`) : null} />
          <FormField
            id="forgot-email"
            label={t("fields.email")}
            type="email"
            autoComplete="email"
            error={errors.email?.message}
            {...register("email")}
          />
        </CardContent>
        <CardFooter className="mt-4 flex flex-col gap-3">
          <Button type="submit" className="w-full" disabled={isSubmitting}>
            {t("forgotPassword.submit")}
          </Button>
          <Link
            href="/login"
            className="text-sm text-muted-foreground underline-offset-4 hover:underline"
          >
            {t("forgotPassword.backToLogin")}
          </Link>
        </CardFooter>
      </form>
    </Card>
  );
}

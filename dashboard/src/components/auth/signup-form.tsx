"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useLocale, useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
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
import { useSignup } from "@/hooks/use-auth";
import { Link } from "@/i18n/navigation";
import { getApiErrorCode } from "@/lib/api-error-codes";
import { resendVerification } from "@/lib/api/auth";
import { MAX_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH } from "@/lib/password";

// Client-side guard against resend spamming; the backend also rate-limits this
// endpoint per IP. The countdown doubles as visible confirmation that the mail
// was (re)sent.
const RESEND_COOLDOWN_SECONDS = 60;

export function SignupForm() {
  const t = useTranslations("auth");
  const locale = useLocale();
  const signup = useSignup();
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [submittedEmail, setSubmittedEmail] = useState<string | null>(null);
  const [isResending, setIsResending] = useState(false);
  const [cooldown, setCooldown] = useState(0);

  useEffect(() => {
    if (cooldown <= 0) {
      return;
    }
    const timer = setTimeout(() => setCooldown((seconds) => seconds - 1), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

  const schema = z
    .object({
      email: z.email({ message: t("validation.emailInvalid") }),
      password: z
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
    .refine((values) => values.password === values.confirmPassword, {
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
    setErrorCode(null);
    try {
      await signup.mutateAsync({
        email: values.email,
        password: values.password,
        locale,
      });
      setSubmittedEmail(values.email);
      // Signup already sent a verification email — start the cooldown so the
      // user can't immediately fire another one.
      setCooldown(RESEND_COOLDOWN_SECONDS);
    } catch (error) {
      setErrorCode(getApiErrorCode(error));
    }
  });

  const handleResend = async () => {
    if (!submittedEmail || cooldown > 0 || isResending) {
      return;
    }
    setIsResending(true);
    try {
      await resendVerification(submittedEmail);
      toast.success(t("signup.success.resent"));
      setCooldown(RESEND_COOLDOWN_SECONDS);
    } catch (error) {
      toast.error(t(`errors.${getApiErrorCode(error)}`));
    } finally {
      setIsResending(false);
    }
  };

  if (submittedEmail) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t("signup.success.title")}</CardTitle>
          <CardDescription>
            {t("signup.success.description", { email: submittedEmail })}
          </CardDescription>
        </CardHeader>
        <CardFooter className="flex flex-col gap-3">
          <Button
            type="button"
            variant="outline"
            className="w-full"
            onClick={handleResend}
            disabled={isResending || cooldown > 0}
          >
            {cooldown > 0
              ? t("signup.success.resendCooldown", { seconds: cooldown })
              : t("signup.success.resend")}
          </Button>
          <Link
            href="/login"
            className="text-sm text-muted-foreground underline-offset-4 hover:underline"
          >
            {t("signup.success.goToLogin")}
          </Link>
        </CardFooter>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("signup.title")}</CardTitle>
        <CardDescription>{t("signup.description")}</CardDescription>
      </CardHeader>
      <form onSubmit={onSubmit} noValidate>
        <CardContent className="flex flex-col gap-4">
          <FormError message={errorCode ? t(`errors.${errorCode}`) : null} />
          <FormField
            id="signup-email"
            label={t("fields.email")}
            type="email"
            autoComplete="email"
            error={errors.email?.message}
            {...register("email")}
          />
          <FormField
            id="signup-password"
            label={t("fields.password")}
            type="password"
            autoComplete="new-password"
            error={errors.password?.message}
            {...register("password")}
          />
          <FormField
            id="signup-confirm-password"
            label={t("fields.confirmPassword")}
            type="password"
            autoComplete="new-password"
            error={errors.confirmPassword?.message}
            {...register("confirmPassword")}
          />
        </CardContent>
        <CardFooter className="mt-4 flex flex-col gap-3">
          <Button type="submit" className="w-full" disabled={isSubmitting}>
            {t("signup.submit")}
          </Button>
          <p className="text-sm text-muted-foreground">
            {t("signup.haveAccount")}{" "}
            <Link
              href="/login"
              className="text-foreground underline-offset-4 hover:underline"
            >
              {t("signup.signInLink")}
            </Link>
          </p>
        </CardFooter>
      </form>
    </Card>
  );
}

"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
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
import { resendVerification, verifyEmail } from "@/lib/api/auth";

type VerifyState = "verifying" | "success" | "invalid";

export function VerifyEmailPanel() {
  const t = useTranslations("auth");
  const searchParams = useSearchParams();
  const token = searchParams.get("token");

  const [state, setState] = useState<VerifyState>(
    token ? "verifying" : "invalid",
  );
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [resent, setResent] = useState(false);
  const attemptedToken = useRef<string | null>(null);

  useEffect(() => {
    if (!token || attemptedToken.current === token) {
      return;
    }
    // Guard against StrictMode double-invocation (the token is single-use)
    // while still verifying again if a *different* ?token= arrives.
    attemptedToken.current = token;
    setState("verifying");
    verifyEmail(token)
      .then(() => setState("success"))
      .catch((error: unknown) => {
        setErrorCode(getApiErrorCode(error));
        setState("invalid");
      });
  }, [token]);

  const resendSchema = z.object({
    email: z.email({ message: t("validation.emailInvalid") }),
  });
  type ResendValues = z.infer<typeof resendSchema>;

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ResendValues>({ resolver: zodResolver(resendSchema) });

  const onResend = handleSubmit(async (values) => {
    setErrorCode(null);
    try {
      await resendVerification(values.email);
      setResent(true);
    } catch (error) {
      setErrorCode(getApiErrorCode(error));
    }
  });

  if (state === "verifying") {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t("verifyEmail.title")}</CardTitle>
          <CardDescription role="status">
            {t("verifyEmail.verifying")}
          </CardDescription>
        </CardHeader>
      </Card>
    );
  }

  if (state === "success") {
    return (
      <Card>
        <CardHeader>
          <CardTitle>{t("verifyEmail.successTitle")}</CardTitle>
          <CardDescription>{t("verifyEmail.successDescription")}</CardDescription>
        </CardHeader>
        <CardFooter>
          <Button
            render={<Link href="/login" />}
            className="w-full"
          >
            {t("verifyEmail.goToLogin")}
          </Button>
        </CardFooter>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("verifyEmail.invalidTitle")}</CardTitle>
        <CardDescription>{t("verifyEmail.invalidDescription")}</CardDescription>
      </CardHeader>
      {resent ? (
        <CardContent>
          <p role="status" className="text-sm text-muted-foreground">
            {t("verifyEmail.resent")}
          </p>
        </CardContent>
      ) : (
        <form onSubmit={onResend} noValidate>
          <CardContent className="flex flex-col gap-4">
            <FormError message={errorCode ? t(`errors.${errorCode}`) : null} />
            <FormField
              id="verify-resend-email"
              label={t("fields.email")}
              type="email"
              autoComplete="email"
              error={errors.email?.message}
              {...register("email")}
            />
          </CardContent>
          <CardFooter className="mt-4">
            <Button type="submit" className="w-full" disabled={isSubmitting}>
              {t("verifyEmail.resend")}
            </Button>
          </CardFooter>
        </form>
      )}
    </Card>
  );
}

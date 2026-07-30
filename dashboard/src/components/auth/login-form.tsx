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
import { useLogin } from "@/hooks/use-auth";
import { Link, useRouter } from "@/i18n/navigation";
import { getApiErrorCode } from "@/lib/api-error-codes";

const DEFAULT_NEXT_PATH = "/dashboard";

/**
 * Only allow same-app relative redirect targets. Backslashes are rejected
 * outright (browsers normalize `\` to `/`, so `/\evil.com` would become the
 * scheme-relative `//evil.com`); everything else must parse to our origin.
 */
function safeNextPath(next: string | null): string {
  if (!next || !next.startsWith("/") || next.includes("\\")) {
    return DEFAULT_NEXT_PATH;
  }
  try {
    const url = new URL(next, window.location.origin);
    if (url.origin !== window.location.origin) {
      return DEFAULT_NEXT_PATH;
    }
    return `${url.pathname}${url.search}`;
  } catch {
    return DEFAULT_NEXT_PATH;
  }
}

export function LoginForm() {
  const t = useTranslations("auth");
  const router = useRouter();
  const searchParams = useSearchParams();
  const login = useLogin();
  const [errorCode, setErrorCode] = useState<string | null>(null);

  const schema = z.object({
    email: z.email({ message: t("validation.emailInvalid") }),
    password: z.string().min(1, t("validation.passwordRequired")),
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
      await login.mutateAsync(values);
      router.push(safeNextPath(searchParams.get("next")));
    } catch (error) {
      setErrorCode(getApiErrorCode(error));
    }
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("login.title")}</CardTitle>
        <CardDescription>{t("login.description")}</CardDescription>
      </CardHeader>
      <form onSubmit={onSubmit} noValidate>
        <CardContent className="flex flex-col gap-4">
          <FormError message={errorCode ? t(`errors.${errorCode}`) : null} />
          <FormField
            id="login-email"
            label={t("fields.email")}
            type="email"
            autoComplete="email"
            error={errors.email?.message}
            {...register("email")}
          />
          <FormField
            id="login-password"
            label={t("fields.password")}
            type="password"
            autoComplete="current-password"
            error={errors.password?.message}
            {...register("password")}
          />
          <Link
            href="/forgot-password"
            className="text-sm text-muted-foreground underline-offset-4 hover:underline"
          >
            {t("login.forgotPassword")}
          </Link>
        </CardContent>
        <CardFooter className="mt-4 flex flex-col gap-3">
          <Button type="submit" className="w-full" disabled={isSubmitting}>
            {t("login.submit")}
          </Button>
          <p className="text-sm text-muted-foreground">
            {t("login.noAccount")}{" "}
            <Link
              href="/signup"
              className="text-foreground underline-offset-4 hover:underline"
            >
              {t("login.signUpLink")}
            </Link>
          </p>
        </CardFooter>
      </form>
    </Card>
  );
}

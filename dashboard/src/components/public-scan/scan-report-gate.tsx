"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { LockIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { FormError } from "@/components/forms/form-error";
import { FormField } from "@/components/forms/form-field";
import { Button } from "@/components/ui/button";

interface GateValues {
  email: string;
}

/**
 * The email gate over the detailed report. A blurred, decorative preview sits behind a glass panel to
 * signal "there is more here, unlock it" (the preview is aria-hidden so screen readers meet the real
 * form directly). Submitting the address unlocks the full breakdown; the consent line states what the
 * email is used for so the capture has a clear lawful basis.
 */
export function ScanReportGate({
  onUnlock,
  isPending,
  errorMessage,
}: {
  onUnlock: (email: string) => void | Promise<void>;
  isPending: boolean;
  errorMessage: string | null;
}) {
  const t = useTranslations("marketing.scan");
  const tAuth = useTranslations("auth");

  const schema = z.object({
    email: z.email({ message: tAuth("validation.emailInvalid") }),
  });

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<GateValues>({ resolver: zodResolver(schema) });

  const submit = handleSubmit((values) => onUnlock(values.email));

  return (
    <div className="relative overflow-hidden rounded-xl border border-border">
      {/* Decorative blurred hint of the locked report — hidden from assistive tech.
          Absolutely positioned so it forms the background layer; the real form below
          is what gives the panel its height (otherwise the form would overflow a panel
          sized only to these few skeleton rows and get clipped by overflow-hidden). */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 flex select-none flex-col gap-2 p-6 opacity-60 blur-[3px]"
      >
        {[80, 65, 72, 58].map((width) => (
          <div key={width} className="flex items-center gap-3">
            <div className="h-3 w-24 rounded bg-muted-foreground/40" />
            <div
              className="h-3 rounded bg-muted-foreground/25"
              style={{ width: `${width}%` }}
            />
          </div>
        ))}
      </div>

      {/* Real form layer — in normal flow, so the panel grows to fit it. */}
      <div className="relative flex items-center justify-center bg-background/70 p-4 backdrop-blur-sm">
        <div className="w-full max-w-sm rounded-lg border border-border bg-card p-5 shadow-lg">
          <div className="mb-3 flex items-center gap-2">
            <span className="flex size-8 items-center justify-center rounded-full bg-primary/10 text-primary">
              <LockIcon aria-hidden="true" className="size-4" />
            </span>
            <h4 className="text-base font-semibold tracking-tight">
              {t("gate.title")}
            </h4>
          </div>
          <p className="mb-4 text-sm text-pretty text-muted-foreground">
            {t("gate.description")}
          </p>
          <form onSubmit={submit} noValidate className="flex flex-col gap-3">
            <FormField
              id="scan-report-email"
              type="email"
              autoComplete="email"
              label={t("gate.emailLabel")}
              placeholder={t("gate.emailPlaceholder")}
              error={errors.email?.message}
              {...register("email")}
            />
            <Button type="submit" disabled={isPending}>
              {isPending ? t("gate.submitting") : t("gate.submit")}
            </Button>
            <FormError message={errorMessage} />
            <p className="text-xs text-muted-foreground">{t("gate.consent")}</p>
          </form>
        </div>
      </div>
    </div>
  );
}

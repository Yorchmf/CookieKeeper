"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { SearchIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { FormError } from "@/components/forms/form-error";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { createDomainSchema } from "@/lib/domain";

export interface ScanDomainValues {
  domain: string;
  website?: string;
}

/**
 * The domain entry point of the funnel: a prominent search-style row. Includes an off-screen
 * `website` honeypot — a real visitor never sees or fills it, a naive bot does; the backend silently
 * no-ops any submission that fills it (see PublicScanRequest). The field is deliberately unvalidated
 * so its presence never signals the trap.
 */
export function ScanDomainForm({
  onSubmit,
  isPending,
  errorMessage,
}: {
  onSubmit: (values: ScanDomainValues) => void | Promise<void>;
  isPending: boolean;
  errorMessage: string | null;
}) {
  const t = useTranslations("marketing.scan");

  const schema = z.object({
    domain: createDomainSchema(t("domainInvalid")),
    website: z.string().optional(),
  });

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<z.input<typeof schema>, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
  });

  const submit = handleSubmit((values) => onSubmit(values));
  const errorId = errors.domain ? "scan-domain-error" : undefined;

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-3">
      <Label htmlFor="scan-domain" className="sr-only">
        {t("domainLabel")}
      </Label>
      <div className="flex flex-col gap-3 sm:flex-row">
        <div className="relative flex-1">
          <SearchIcon
            aria-hidden="true"
            className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            id="scan-domain"
            type="text"
            inputMode="url"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            placeholder={t("domainPlaceholder")}
            aria-invalid={errors.domain ? true : undefined}
            aria-describedby={errorId}
            className="h-11 pl-9 font-mono text-base"
            {...register("domain")}
          />
        </div>
        <Button type="submit" size="lg" className="h-11" disabled={isPending}>
          {isPending ? t("scanningShort") : t("submit")}
        </Button>
      </div>

      {/* Honeypot: off-screen, not tab-reachable, never announced. Left blank by humans. */}
      <div aria-hidden="true" className="absolute -left-[9999px] h-0 w-0 overflow-hidden">
        <label htmlFor="scan-website">Website</label>
        <input
          id="scan-website"
          type="text"
          tabIndex={-1}
          autoComplete="off"
          {...register("website")}
        />
      </div>

      {errors.domain && (
        <p id="scan-domain-error" className="text-sm text-destructive">
          {errors.domain.message}
        </p>
      )}
      <FormError message={errorMessage} />
    </form>
  );
}

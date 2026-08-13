"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslations } from "next-intl";
import { useMemo } from "react";
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
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { useUpdateSite } from "@/hooks/use-sites";
import { getApiErrorCode } from "@/lib/api-error-codes";
import { createDomainSchema } from "@/lib/domain";

type RenameSiteCardProps = {
  siteId: string;
  /** The site's current domain — seeds the field and gates the Save button (no change ⇒ no save). */
  domain: string;
  /** Whether the site is currently verified; if so, the copy warns the change resets verification. */
  isVerified: boolean;
};

/**
 * Change the site's registered domain. The domain *is* the site's identity across the dashboard, so
 * this is the closest thing to a rename. On the server `SiteService.changeDomain` clears `verifiedAt`
 * (ownership proof does not transfer between domains), so a currently-verified site is warned it will
 * need re-verifying. The server stays the domain authority — `createDomainSchema` only gives instant
 * feedback and normalizes paste artifacts (scheme, path, port) before the value is sent.
 */
export function RenameSiteCard({
  siteId,
  domain,
  isVerified,
}: RenameSiteCardProps) {
  const t = useTranslations("sites.detail.rename");
  const tErrors = useTranslations("auth.errors");
  const update = useUpdateSite(siteId);

  const schema = useMemo(
    () => z.object({ domain: createDomainSchema(t("invalidDomain")) }),
    [t],
  );

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<z.input<typeof schema>, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
    // Seed from the current domain; `keepDirtyValues` stops a background site refetch from clobbering
    // an in-progress edit, and a successful save re-seeds explicitly from the authoritative response.
    values: { domain },
    resetOptions: { keepDirtyValues: true },
  });

  // Derive the error from the mutation rather than mirroring it into local state; editing again resets
  // the mutation, clearing any prior error (a 409 DOMAIN_ALREADY_REGISTERED, INVALID_DOMAIN, etc.).
  const errorCode = update.error ? getApiErrorCode(update.error) : null;

  const onSubmit = handleSubmit(async (values) => {
    try {
      const site = await update.mutateAsync({ domain: values.domain });
      reset({ domain: site.domain });
      toast.success(t("saved", { domain: site.domain }));
    } catch {
      // Failure is surfaced through update.error → errorCode; nothing more to do here.
    }
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
          <FormError message={errorCode ? tErrors(errorCode) : null} />
          <FormField
            id="site-domain"
            label={t("label")}
            type="text"
            autoComplete="off"
            placeholder={t("placeholder")}
            error={errors.domain?.message}
            hint={isVerified ? t("resetHint") : undefined}
            {...register("domain", {
              onChange: () => {
                if (update.isSuccess || update.isError) update.reset();
              },
            })}
          />
          <div>
            <Button type="submit" disabled={update.isPending || !isDirty}>
              {t("save")}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

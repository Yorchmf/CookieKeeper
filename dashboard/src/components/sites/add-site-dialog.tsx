"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { PlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { FormError } from "@/components/forms/form-error";
import { FormField } from "@/components/forms/form-field";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { useEntitlement } from "@/hooks/use-billing";
import { useCreateSite } from "@/hooks/use-sites";
import { Link } from "@/i18n/navigation";
import { getApiErrorCode } from "@/lib/api-error-codes";
import { resendVerification } from "@/lib/api/auth";
import { createDomainSchema } from "@/lib/domain";
import { useMe } from "@/hooks/use-auth";

/** Ties the cap explanation to the submit button, so the reason reaches anyone who tabs straight to it. */
const CAP_NOTICE_ID = "add-site-cap-notice";

export function AddSiteDialog() {
  const t = useTranslations("sites.addDialog");
  const tErrors = useTranslations("auth.errors");
  const me = useMe();
  const createSite = useCreateSite();
  // Site count vs. plan cap. The backend stays the authority (403 SITE_LIMIT_REACHED, taken under an
  // advisory lock); this only moves the news forward so the cap is visible *before* the domain is typed
  // and submitted. It deliberately does not disable the submit: the count comes from a cache that can
  // lag a change made in another tab, so a stale read must not lock someone out of a slot they have.
  // While the entitlement is loading there is no count, so nothing is claimed either way.
  const entitlement = useEntitlement();
  const cap = entitlement.data
    ? {
        maxSites: entitlement.data.limits.maxSites,
        remaining: entitlement.data.limits.maxSites - entitlement.data.activeSites,
      }
    : null;
  // A lapsed account reports zero sites while still owning the ones it created, so it lands here with a
  // negative remainder — "your plan includes 0 sites" would be nonsense where "your trial ended" is true.
  const isExpired = cap !== null && cap.maxSites === 0;
  const isAtCap = cap !== null && cap.remaining <= 0;

  const [isOpen, setIsOpen] = useState(false);
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [isResending, setIsResending] = useState(false);

  const schema = z.object({
    domain: createDomainSchema(t("invalidDomain")),
  });

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<z.input<typeof schema>, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
  });

  const closeAndReset = () => {
    setIsOpen(false);
    reset();
    setErrorCode(null);
  };

  const handleOpenChange = (open: boolean) => {
    if (open) {
      setIsOpen(true);
      return;
    }
    // Keep the dialog up while the site is being created — closing would
    // discard the pending state and any error feedback.
    if (createSite.isPending) {
      return;
    }
    closeAndReset();
  };

  const onSubmit = handleSubmit(async (values) => {
    // `values` is the resolver-transformed schema output: already normalized.
    setErrorCode(null);
    try {
      const site = await createSite.mutateAsync(values.domain);
      toast.success(t("created", { domain: site.domain }));
      closeAndReset();
    } catch (error) {
      setErrorCode(getApiErrorCode(error));
    }
  });

  const handleResendVerification = async () => {
    const email = me.data?.email;
    if (!email) {
      return;
    }
    setIsResending(true);
    try {
      await resendVerification(email);
      toast.success(t("verificationResent"));
    } catch (error) {
      toast.error(tErrors(getApiErrorCode(error)));
    } finally {
      setIsResending(false);
    }
  };

  return (
    <Dialog open={isOpen} onOpenChange={handleOpenChange}>
      <DialogTrigger
        render={
          <Button>
            <PlusIcon aria-hidden="true" />
            {t("trigger")}
          </Button>
        }
      />
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("title")}</DialogTitle>
          <DialogDescription>{t("description")}</DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
          {isAtCap ? (
            <div className="flex flex-col gap-2 rounded-lg border border-border bg-muted/50 px-3 py-2 text-sm">
              {/* Text-only live region: it announces the cap if the entitlement lands after the dialog
                  opened, and the id makes the submit button carry the same explanation for anyone who
                  tabs straight to it. The CTA sits outside so it is not read as part of the message. */}
              <p role="status" id={CAP_NOTICE_ID}>
                {isExpired
                  ? t("capExpired")
                  : t("capReached", { max: cap.maxSites })}
              </p>
              <div>
                <Button
                  nativeButton={false}
                  variant="outline"
                  size="sm"
                  render={<Link href="/billing" />}
                >
                  {t("capUpgrade")}
                </Button>
              </div>
            </div>
          ) : cap?.remaining === 1 ? (
            <p className="text-sm text-muted-foreground">
              {t("capLastSlot", { max: cap.maxSites })}
            </p>
          ) : null}
          <FormError message={errorCode ? tErrors(errorCode) : null} />
          {errorCode === "EMAIL_NOT_VERIFIED" ? (
            <div className="flex flex-col gap-2 rounded-lg border border-border bg-muted/50 px-3 py-2 text-sm">
              <p>{t("notVerifiedHint")}</p>
              <div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => void handleResendVerification()}
                  disabled={isResending || !me.data?.email}
                >
                  {t("resendVerification")}
                </Button>
              </div>
            </div>
          ) : null}
          <FormField
            id="add-site-domain"
            label={t("domainLabel")}
            type="text"
            autoComplete="off"
            placeholder={t("domainPlaceholder")}
            error={errors.domain?.message}
            {...register("domain")}
          />
          <DialogFooter>
            <Button
              type="button"
              variant="ghost"
              onClick={() => handleOpenChange(false)}
              disabled={createSite.isPending}
            >
              {t("cancel")}
            </Button>
            <Button
              type="submit"
              disabled={isSubmitting}
              aria-describedby={isAtCap ? CAP_NOTICE_ID : undefined}
            >
              {t("submit")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

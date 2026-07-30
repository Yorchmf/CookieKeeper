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
import { useCreateSite } from "@/hooks/use-sites";
import { getApiErrorCode } from "@/lib/api-error-codes";
import { resendVerification } from "@/lib/api/auth";
import { createDomainSchema } from "@/lib/domain";
import { useMe } from "@/hooks/use-auth";

export function AddSiteDialog() {
  const t = useTranslations("sites.addDialog");
  const tErrors = useTranslations("auth.errors");
  const me = useMe();
  const createSite = useCreateSite();

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
            <Button type="submit" disabled={isSubmitting}>
              {t("submit")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

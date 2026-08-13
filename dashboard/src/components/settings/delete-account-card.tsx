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
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { useDeleteAccount } from "@/hooks/use-account";
import { Link } from "@/i18n/navigation";
import { getApiErrorCode } from "@/lib/api-error-codes";
import type { AccountDeletionResult } from "@/lib/api/account";

/** What erasure destroys — spelled out so consent is informed rather than inferred from a red button. */
const ERASED_ITEMS = ["identity", "sites", "scans", "policies", "billing"] as const;

/**
 * Art. 17 erasure (ADR-20). Deliberately honest about the one thing that is *not* destroyed: consent
 * events are the site visitors' audit evidence, not the customer's own data, and are append-only. The
 * site row survives stripped of its domain and key so those events stay referentially valid until the
 * 3-year retention job drops the partition holding them.
 *
 * The password re-authentication is enforced by the backend (403 `DELETE_CONFIRMATION_FAILED`); the
 * field here exists so the user supplies it, not as a gate.
 */
export function DeleteAccountCard() {
  const t = useTranslations("settings.data.delete");
  const tErrors = useTranslations("auth.errors");
  const deleteAccount = useDeleteAccount();

  const [isOpen, setIsOpen] = useState(false);
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [result, setResult] = useState<AccountDeletionResult | null>(null);

  const schema = z.object({
    password: z.string().min(1, t("passwordRequired")),
  });

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<z.input<typeof schema>, unknown, z.output<typeof schema>>({
    resolver: zodResolver(schema),
  });

  const handleOpenChange = (open: boolean) => {
    if (open) {
      setIsOpen(true);
      return;
    }
    // Closing mid-erasure would hide an operation that cannot be cancelled.
    if (deleteAccount.isPending) {
      return;
    }
    setIsOpen(false);
    reset();
    setErrorCode(null);
  };

  const onSubmit = handleSubmit(async (values) => {
    setErrorCode(null);
    try {
      const deletion = await deleteAccount.mutateAsync(values.password);
      setIsOpen(false);
      setResult(deletion);
    } catch (error) {
      setErrorCode(getApiErrorCode(error));
    }
  });

  if (result) {
    return <DeletedPanel result={result} />;
  }

  return (
    <Card className="border-destructive/30">
      <CardHeader>
        <CardTitle className="text-destructive">{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">{t("erasedTitle")}</h3>
          <ul className="list-disc pl-5 text-sm text-muted-foreground">
            {ERASED_ITEMS.map((item) => (
              <li key={item}>{t(`erased.${item}`)}</li>
            ))}
          </ul>
        </div>
        <div className="rounded-lg border border-border bg-muted/50 px-3 py-2">
          <h3 className="text-sm font-medium">{t("keptTitle")}</h3>
          <p className="mt-1 text-sm text-muted-foreground">{t("keptBody")}</p>
        </div>
        <p className="text-sm text-muted-foreground">{t("irreversible")}</p>
        <div>
          <Dialog open={isOpen} onOpenChange={handleOpenChange}>
            <DialogTrigger
              render={<Button variant="destructive">{t("trigger")}</Button>}
            />
            <DialogContent>
              <DialogHeader>
                <DialogTitle>{t("confirmTitle")}</DialogTitle>
                <DialogDescription>{t("confirmDescription")}</DialogDescription>
              </DialogHeader>
              <form
                onSubmit={onSubmit}
                noValidate
                className="flex flex-col gap-4"
              >
                <FormError message={errorCode ? tErrors(errorCode) : null} />
                <FormField
                  id="delete-account-password"
                  label={t("passwordLabel")}
                  type="password"
                  autoComplete="current-password"
                  error={errors.password?.message}
                  {...register("password")}
                />
                <DialogFooter>
                  <Button
                    type="button"
                    variant="ghost"
                    onClick={() => handleOpenChange(false)}
                    disabled={deleteAccount.isPending}
                  >
                    {t("cancel")}
                  </Button>
                  <Button
                    type="submit"
                    variant="destructive"
                    disabled={deleteAccount.isPending}
                  >
                    {t("confirm")}
                  </Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * Terminal state: the session is already gone (the backend expired the cookies), so this panel must
 * not depend on any further request. It reports exactly what happened to the account's sites and
 * points back at the public site.
 */
function DeletedPanel({ result }: { result: AccountDeletionResult }) {
  const t = useTranslations("settings.data.delete.done");

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("title")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <ul className="list-disc pl-5 text-sm text-muted-foreground">
          <li>{t("sitesDeleted", { count: result.sitesDeleted })}</li>
          <li>{t("sitesAnonymized", { count: result.sitesAnonymized })}</li>
        </ul>
        <div>
          <Button
            variant="outline"
            nativeButton={false}
            render={<Link href="/" />}
          >
            {t("home")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

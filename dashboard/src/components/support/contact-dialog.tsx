"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { LifeBuoy } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";
import { FormError } from "@/components/forms/form-error";
import { FormField } from "@/components/forms/form-field";
import { FormTextarea } from "@/components/forms/form-textarea";
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
import { useMe } from "@/hooks/use-auth";
import { useSubmitContact } from "@/hooks/use-contact";
import { getApiErrorCode, type ErrorMessageCode } from "@/lib/api-error-codes";
import {
  CONTACT_MESSAGE_MAX_LENGTH,
  CONTACT_SUBJECT_MAX_LENGTH,
} from "@/lib/api/contact";

/**
 * In-app support contact form. Replaces the sidebar `mailto:` with a real posting form (BACKLOG #12):
 * the message is emailed to our support inbox with the account's own address as Reply-To, so a reply
 * lands in the customer's inbox. The trigger keeps the sidebar row's look so the entry point is
 * unchanged; only what happens on click does.
 */
export function ContactDialog() {
  const t = useTranslations("support.contact");
  const tNav = useTranslations("nav");
  const tErrors = useTranslations("auth.errors");
  const me = useMe();
  const submitContact = useSubmitContact();

  const [isOpen, setIsOpen] = useState(false);
  const [errorCode, setErrorCode] = useState<ErrorMessageCode | null>(null);

  const schema = z.object({
    subject: z
      .string()
      .trim()
      .min(1, t("subjectRequired"))
      .max(CONTACT_SUBJECT_MAX_LENGTH, t("subjectTooLong", { max: CONTACT_SUBJECT_MAX_LENGTH })),
    message: z
      .string()
      .trim()
      .min(1, t("messageRequired"))
      .max(CONTACT_MESSAGE_MAX_LENGTH, t("messageTooLong", { max: CONTACT_MESSAGE_MAX_LENGTH })),
  });

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
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
    // Keep the dialog up while the message is sending — closing would discard the pending state and
    // any error feedback, and the customer would not know whether it went through.
    if (submitContact.isPending) {
      return;
    }
    closeAndReset();
  };

  const onSubmit = handleSubmit(async (values) => {
    setErrorCode(null);
    try {
      await submitContact.mutateAsync(values);
      toast.success(t("sent"));
      closeAndReset();
    } catch (error) {
      setErrorCode(getApiErrorCode(error));
    }
  });

  const replyEmail = me.data?.email;

  return (
    <Dialog open={isOpen} onOpenChange={handleOpenChange}>
      <DialogTrigger
        render={
          <button
            type="button"
            className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm text-sidebar-foreground transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
          >
            <LifeBuoy aria-hidden="true" className="size-4 shrink-0" />
            {tNav("support")}
          </button>
        }
      />
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("title")}</DialogTitle>
          <DialogDescription>
            {replyEmail ? t("replyNotice", { email: replyEmail }) : t("description")}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
          <FormError message={errorCode ? tErrors(errorCode) : null} />
          <FormField
            id="contact-subject"
            label={t("subjectLabel")}
            type="text"
            autoComplete="off"
            maxLength={CONTACT_SUBJECT_MAX_LENGTH}
            placeholder={t("subjectPlaceholder")}
            error={errors.subject?.message}
            {...register("subject")}
          />
          <FormTextarea
            id="contact-message"
            label={t("messageLabel")}
            rows={6}
            maxLength={CONTACT_MESSAGE_MAX_LENGTH}
            placeholder={t("messagePlaceholder")}
            error={errors.message?.message}
            {...register("message")}
          />
          <DialogFooter>
            <Button
              type="button"
              variant="ghost"
              onClick={() => handleOpenChange(false)}
              disabled={submitContact.isPending}
            >
              {t("cancel")}
            </Button>
            <Button type="submit" disabled={submitContact.isPending}>
              {t("submit")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

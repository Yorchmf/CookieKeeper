"use client";

import { useTranslations } from "next-intl";
import { useId, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { PolicyGenerationInput } from "@/lib/api/policy";

/** Lightweight client-side gate; the backend re-validates every field (@Email, @Size, @NotBlank). */
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface FieldErrors {
  companyName?: string;
  contactEmail?: string;
}

/**
 * Business-details form that drives generate/regenerate. Controlled inputs (values feed inline
 * validation); the parent owns the mutation and its pending/label state. Optional fields are sent as
 * `undefined` when blank so the backend applies its own defaults (website → site domain, address omitted).
 */
export function PolicyForm({
  hasExisting,
  isSubmitting,
  onSubmit,
}: {
  hasExisting: boolean;
  isSubmitting: boolean;
  onSubmit: (input: PolicyGenerationInput) => void;
}) {
  const t = useTranslations("policy.form");
  const fieldId = useId();
  const [companyName, setCompanyName] = useState("");
  const [contactEmail, setContactEmail] = useState("");
  const [websiteUrl, setWebsiteUrl] = useState("");
  const [address, setAddress] = useState("");
  const [errors, setErrors] = useState<FieldErrors>({});

  const companyId = `${fieldId}-company`;
  const emailId = `${fieldId}-email`;
  const websiteId = `${fieldId}-website`;
  const addressId = `${fieldId}-address`;

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const nextErrors: FieldErrors = {};
    if (!companyName.trim()) {
      nextErrors.companyName = t("validation.companyRequired");
    }
    if (!EMAIL_RE.test(contactEmail.trim())) {
      nextErrors.contactEmail = t("validation.emailInvalid");
    }
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) {
      // Move focus to the first field in error so keyboard/SR users land on what to fix.
      const firstInvalidId = nextErrors.companyName ? companyId : emailId;
      document.getElementById(firstInvalidId)?.focus();
      return;
    }
    onSubmit({
      companyName: companyName.trim(),
      contactEmail: contactEmail.trim(),
      websiteUrl: websiteUrl.trim() || undefined,
      address: address.trim() || undefined,
    });
  };

  return (
    <form className="flex flex-col gap-5" onSubmit={handleSubmit} noValidate>
      <div className="flex flex-col gap-2">
        <Label htmlFor={companyId}>
          {t("companyName")}
          <span aria-hidden="true" className="text-destructive">
            *
          </span>
        </Label>
        <Input
          id={companyId}
          value={companyName}
          onChange={(event) => setCompanyName(event.target.value)}
          placeholder={t("companyNamePlaceholder")}
          maxLength={200}
          aria-required="true"
          aria-invalid={errors.companyName ? "true" : undefined}
          aria-describedby={errors.companyName ? `${companyId}-error` : undefined}
        />
        {errors.companyName ? (
          <p id={`${companyId}-error`} role="alert" className="text-sm text-destructive">
            {errors.companyName}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor={emailId}>
          {t("contactEmail")}
          <span aria-hidden="true" className="text-destructive">
            *
          </span>
        </Label>
        <Input
          id={emailId}
          type="email"
          value={contactEmail}
          onChange={(event) => setContactEmail(event.target.value)}
          placeholder={t("contactEmailPlaceholder")}
          maxLength={254}
          aria-required="true"
          aria-invalid={errors.contactEmail ? "true" : undefined}
          aria-describedby={errors.contactEmail ? `${emailId}-error` : undefined}
        />
        {errors.contactEmail ? (
          <p id={`${emailId}-error`} role="alert" className="text-sm text-destructive">
            {errors.contactEmail}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor={websiteId}>{t("websiteUrl")}</Label>
        <Input
          id={websiteId}
          type="url"
          value={websiteUrl}
          onChange={(event) => setWebsiteUrl(event.target.value)}
          placeholder={t("websiteUrlPlaceholder")}
          maxLength={2048}
          aria-describedby={`${websiteId}-hint`}
        />
        <p id={`${websiteId}-hint`} className="text-sm text-muted-foreground">
          {t("websiteUrlHint")}
        </p>
      </div>

      <div className="flex flex-col gap-2">
        <Label htmlFor={addressId}>{t("address")}</Label>
        <Input
          id={addressId}
          value={address}
          onChange={(event) => setAddress(event.target.value)}
          placeholder={t("addressPlaceholder")}
          maxLength={500}
          aria-describedby={`${addressId}-hint`}
        />
        <p id={`${addressId}-hint`} className="text-sm text-muted-foreground">
          {t("addressHint")}
        </p>
      </div>

      <div>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting
            ? t("submitting")
            : hasExisting
              ? t("regenerate")
              : t("submit")}
        </Button>
      </div>
    </form>
  );
}

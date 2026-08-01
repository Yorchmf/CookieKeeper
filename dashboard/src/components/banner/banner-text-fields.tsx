"use client";

import { useTranslations } from "next-intl";
import { useId } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { BannerTexts } from "@/lib/api/banner";

/** Max lengths mirror BannerConfigValidator (title/description/button). */
const MAX = { title: 120, description: 600, button: 60 } as const;

type TextField = keyof BannerTexts;

/**
 * Edits the text bundle for a single language. Controlled by the parent editor, which owns the
 * full per-language texts map; this component only reports the field-level changes. Blank fields are
 * flagged inline (the backend rejects them) but never block typing.
 */
export function BannerTextFields({
  language,
  texts,
  onChange,
}: {
  language: string;
  texts: BannerTexts;
  onChange: (field: TextField, value: string) => void;
}) {
  const t = useTranslations("banner.texts");
  const fieldId = useId();

  const descriptionId = `${fieldId}-description`;
  const descriptionBlank = texts.description.trim() === "";

  const shortFields: { field: TextField; max: number }[] = [
    { field: "acceptAll", max: MAX.button },
    { field: "rejectAll", max: MAX.button },
    { field: "save", max: MAX.button },
    { field: "preferences", max: MAX.button },
  ];

  return (
    <div className="flex flex-col gap-4" lang={language}>
      <TextRow
        id={`${fieldId}-title`}
        label={t("title")}
        value={texts.title}
        max={MAX.title}
        blankLabel={t("blank")}
        onChange={(value) => onChange("title", value)}
      />

      <div className="flex flex-col gap-2">
        <Label htmlFor={descriptionId}>{t("body")}</Label>
        <textarea
          id={descriptionId}
          value={texts.description}
          rows={3}
          maxLength={MAX.description}
          onChange={(event) => onChange("description", event.target.value)}
          aria-invalid={descriptionBlank ? "true" : undefined}
          aria-describedby={
            descriptionBlank ? `${descriptionId}-error` : undefined
          }
          className="w-full resize-y rounded-lg border border-input bg-transparent px-2.5 py-1.5 text-sm outline-none transition-colors focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive"
        />
        {descriptionBlank ? (
          <p
            id={`${descriptionId}-error`}
            role="alert"
            className="text-sm text-destructive"
          >
            {t("blank")}
          </p>
        ) : null}
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        {shortFields.map(({ field, max }) => (
          <TextRow
            key={field}
            id={`${fieldId}-${field}`}
            label={t(field)}
            value={texts[field]}
            max={max}
            blankLabel={t("blank")}
            onChange={(value) => onChange(field, value)}
          />
        ))}
      </div>
    </div>
  );
}

function TextRow({
  id,
  label,
  value,
  max,
  blankLabel,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  max: number;
  blankLabel: string;
  onChange: (value: string) => void;
}) {
  const isBlank = value.trim() === "";
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        value={value}
        maxLength={max}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={isBlank ? "true" : undefined}
        aria-describedby={isBlank ? `${id}-error` : undefined}
      />
      {isBlank ? (
        <p id={`${id}-error`} role="alert" className="text-sm text-destructive">
          {blankLabel}
        </p>
      ) : null}
    </div>
  );
}

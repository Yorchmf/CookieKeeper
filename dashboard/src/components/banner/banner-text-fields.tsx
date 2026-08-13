"use client";

import { useTranslations } from "next-intl";
import { useId } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { BannerCategoryText, BannerTexts } from "@/lib/api/banner";

/** Max lengths mirror BannerConfigValidator (title/description/button/category copy). */
const MAX = {
  title: 120,
  description: 600,
  button: 60,
  categoryLabel: 80,
  categoryDescription: 300,
} as const;

type TextField = keyof BannerTexts;
type CategoryField = keyof BannerCategoryText;

/**
 * Edits the text bundle for a single language. Controlled by the parent editor, which owns the
 * full per-language texts map; this component only reports the field-level changes.
 *
 * Two tiers of field, with deliberately different blank handling: the banner copy is required and a
 * blank value is flagged inline (the backend rejects it), while the preferences-panel copy is
 * optional — leaving it empty publishes our own translation for that language, so a blank there is a
 * hint, not an error.
 */
export function BannerTextFields({
  language,
  texts,
  categories,
  onChange,
  onCategoryChange,
}: {
  language: string;
  texts: BannerTexts;
  /** Category keys currently offered on the banner, in display order. */
  categories: string[];
  onChange: (field: TextField, value: string) => void;
  onCategoryChange: (
    categoryKey: string,
    field: CategoryField,
    value: string,
  ) => void;
}) {
  const t = useTranslations("banner.texts");
  const tCategories = useTranslations("banner.categories.names");
  const fieldId = useId();

  const descriptionId = `${fieldId}-description`;
  const descriptionBlank = texts.description.trim() === "";

  const shortFields: { field: TextField; max: number }[] = [
    { field: "acceptAll", max: MAX.button },
    { field: "rejectAll", max: MAX.button },
    { field: "save", max: MAX.button },
    { field: "preferences", max: MAX.button },
  ];

  const panelFields: { field: TextField; max: number }[] = [
    { field: "preferencesTitle", max: MAX.title },
    { field: "close", max: MAX.button },
    { field: "alwaysActive", max: MAX.button },
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
            value={texts[field] as string}
            max={max}
            blankLabel={t("blank")}
            onChange={(value) => onChange(field, value)}
          />
        ))}
      </div>

      <section
        aria-labelledby={`${fieldId}-panel-heading`}
        className="flex flex-col gap-4 rounded-xl border border-dashed border-border/70 p-4"
      >
        <div className="flex flex-col gap-1">
          <h4
            id={`${fieldId}-panel-heading`}
            className="text-sm font-medium tracking-tight"
          >
            {t("panel.label")}
          </h4>
          <p className="text-sm text-muted-foreground">
            {t("panel.description")}
          </p>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          {panelFields.map(({ field, max }) => (
            <TextRow
              key={field}
              id={`${fieldId}-${field}`}
              label={t(field)}
              value={texts[field] as string}
              max={max}
              optionalHint={t("panel.usesDefault")}
              onChange={(value) => onChange(field, value)}
            />
          ))}
        </div>

        {categories.map((key) => {
          const label = texts.categoryLabels[key];
          return (
            <fieldset key={key} className="flex flex-col gap-3">
              <legend className="text-sm font-medium">
                {tCategories(key)}
              </legend>
              <TextRow
                id={`${fieldId}-cat-${key}-label`}
                label={t("panel.categoryLabel")}
                value={label?.label ?? ""}
                max={MAX.categoryLabel}
                optionalHint={t("panel.usesDefault")}
                onChange={(value) => onCategoryChange(key, "label", value)}
              />
              <TextRow
                id={`${fieldId}-cat-${key}-description`}
                label={t("panel.categoryDescription")}
                value={label?.description ?? ""}
                max={MAX.categoryDescription}
                optionalHint={t("panel.usesDefault")}
                onChange={(value) => onCategoryChange(key, "description", value)}
              />
            </fieldset>
          );
        })}
      </section>
    </div>
  );
}

/**
 * One labelled text input. Exactly one of [blankLabel] (required field — blank is an error) or
 * [optionalHint] (optional field — blank falls back to our translation) should be supplied.
 */
function TextRow({
  id,
  label,
  value,
  max,
  blankLabel,
  optionalHint,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  max: number;
  blankLabel?: string;
  optionalHint?: string;
  onChange: (value: string) => void;
}) {
  const isBlank = value.trim() === "";
  const isInvalid = isBlank && blankLabel !== undefined;
  const note = isInvalid ? blankLabel : isBlank ? optionalHint : undefined;
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        value={value}
        maxLength={max}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={isInvalid ? "true" : undefined}
        aria-describedby={note ? `${id}-note` : undefined}
      />
      {note ? (
        <p
          id={`${id}-note`}
          role={isInvalid ? "alert" : undefined}
          className={
            isInvalid ? "text-sm text-destructive" : "text-sm text-muted-foreground"
          }
        >
          {note}
        </p>
      ) : null}
    </div>
  );
}

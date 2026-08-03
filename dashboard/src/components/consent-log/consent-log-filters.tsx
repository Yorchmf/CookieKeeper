"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  CONSENT_ACTIONS,
  type ConsentLogFilters,
  parseConsentAction,
} from "@/lib/api/consent";

const LANGUAGES = ["en", "de", "fr", "es", "it"];

/** Canonical UUID v4-ish shape; the backend keys visitors by UUID, so anything else is a guaranteed 400. */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// A native <date> input yields "YYYY-MM-DD"; the backend filter wants a full instant. `from` maps to
// start-of-day UTC (inclusive); `to` maps to end-of-day UTC so the picked day is included even though
// the backend treats `to` as exclusive. An empty input clears the field.
function dateToInstant(date: string, endOfDay: boolean): string | undefined {
  if (!date) return undefined;
  return `${date}T${endOfDay ? "23:59:59.999" : "00:00:00.000"}Z`;
}

function instantToDate(instant: string | undefined): string {
  return instant ? instant.slice(0, 10) : "";
}

const selectClass =
  "h-9 rounded-lg border border-input bg-transparent px-2.5 text-sm shadow-xs transition-[color,box-shadow] focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none";

/**
 * URL-as-state filter bar. Controlled entirely by the `values` prop (read from the URL); every change
 * calls `onChange` with the full next filter object. Date/action/language apply immediately.
 *
 * The visitor id is an uncontrolled input keyed to the committed URL value: it re-seeds on Back/Forward
 * (the `key` forces a remount when the URL's visitorId changes) while letting the user type freely
 * between submits. It commits on submit and only once it is a well-formed UUID, so a partial or malformed
 * id never reaches the backend as a guaranteed 400.
 */
export function ConsentLogFilters({
  values,
  onChange,
}: {
  values: ConsentLogFilters;
  onChange: (next: ConsentLogFilters) => void;
}) {
  const t = useTranslations("consentLog.filters");
  const tActions = useTranslations("consentLog.actions");
  const [isVisitorInvalid, setIsVisitorInvalid] = useState(false);

  const update = (patch: Partial<ConsentLogFilters>) => {
    onChange({ ...values, ...patch });
  };

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const raw = String(new FormData(event.currentTarget).get("visitorId") ?? "").trim();
    if (!raw) {
      setIsVisitorInvalid(false);
      update({ visitorId: undefined });
      return;
    }
    if (!UUID_PATTERN.test(raw)) {
      setIsVisitorInvalid(true);
      return;
    }
    setIsVisitorInvalid(false);
    update({ visitorId: raw });
  };

  const handleClear = () => {
    setIsVisitorInvalid(false);
    onChange({});
  };

  return (
    <form
      onSubmit={handleSubmit}
      className="flex flex-wrap items-end gap-4 rounded-xl border border-border bg-card p-4"
    >
      <fieldset className="contents">
        <legend className="sr-only">{t("legend")}</legend>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="consent-from">{t("from")}</Label>
          <Input
            id="consent-from"
            type="date"
            value={instantToDate(values.from)}
            onChange={(event) => update({ from: dateToInstant(event.target.value, false) })}
            className="w-40"
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="consent-to">{t("to")}</Label>
          <Input
            id="consent-to"
            type="date"
            value={instantToDate(values.to)}
            onChange={(event) => update({ to: dateToInstant(event.target.value, true) })}
            className="w-40"
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="consent-action">{t("action")}</Label>
          <select
            id="consent-action"
            value={values.action ?? ""}
            onChange={(event) => update({ action: parseConsentAction(event.target.value) })}
            className={selectClass}
          >
            <option value="">{t("allActions")}</option>
            {CONSENT_ACTIONS.map((action) => (
              <option key={action} value={action}>
                {tActions(action)}
              </option>
            ))}
          </select>
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="consent-lang">{t("language")}</Label>
          <select
            id="consent-lang"
            value={values.lang ?? ""}
            onChange={(event) => update({ lang: event.target.value || undefined })}
            className={selectClass}
          >
            <option value="">{t("allLanguages")}</option>
            {LANGUAGES.map((lang) => (
              <option key={lang} value={lang}>
                {lang.toUpperCase()}
              </option>
            ))}
          </select>
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="consent-visitor">{t("visitorId")}</Label>
          <Input
            // Remount when the committed URL value changes so Back/Forward re-seeds the field.
            key={values.visitorId ?? ""}
            id="consent-visitor"
            name="visitorId"
            defaultValue={values.visitorId ?? ""}
            placeholder={t("visitorIdPlaceholder")}
            inputMode="text"
            autoComplete="off"
            aria-invalid={isVisitorInvalid}
            aria-describedby={isVisitorInvalid ? "consent-visitor-error" : undefined}
            className="w-64 font-mono text-xs"
          />
          {isVisitorInvalid && (
            <span id="consent-visitor-error" role="alert" className="text-xs text-destructive">
              {t("visitorIdInvalid")}
            </span>
          )}
        </div>

        <div className="flex items-center gap-2">
          <Button type="submit" variant="secondary">
            {t("apply")}
          </Button>
          <Button type="button" variant="ghost" onClick={handleClear}>
            {t("clear")}
          </Button>
        </div>
      </fieldset>
    </form>
  );
}

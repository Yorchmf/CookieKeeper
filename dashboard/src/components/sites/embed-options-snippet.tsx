"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmbedSnippet } from "@/components/sites/embed-snippet";
import { Switch } from "@/components/ui/switch";
import {
  NO_EMBED_OPTIONS,
  withEmbedOptions,
  type EmbedOptions,
} from "@/lib/embed-options";

/** The toggles, in display order. Each maps to a `sites.detail.embedOptions.<key>` copy block. */
const TOGGLES: (keyof EmbedOptions)[] = ["regionGating", "urlPassthrough"];

/**
 * The embed snippet plus the two optional widget behaviours that are turned on by adding an
 * attribute to it.
 *
 * There is nothing to save here: the switches only rewrite the text above them, and the setting
 * takes effect when the customer pastes the result into their site. That is deliberate — the widget
 * has to act on both before its first network call, so an attribute is the only place they can
 * live, and pretending otherwise with a Save button would promise a persistence we do not have.
 */
export function EmbedOptionsSnippet({ snippet }: { snippet: string }) {
  const t = useTranslations("sites.detail.embedOptions");
  const [options, setOptions] = useState<EmbedOptions>(NO_EMBED_OPTIONS);

  return (
    <div className="flex flex-col gap-5">
      <EmbedSnippet snippet={withEmbedOptions(snippet, options)} />

      <div className="flex flex-col gap-5 border-t border-border pt-5">
        <p className="text-sm font-medium">{t("title")}</p>
        {TOGGLES.map((key) => (
          <div key={key} className="flex items-start justify-between gap-4">
            <div className="space-y-0.5">
              <label htmlFor={`embed-${key}`} className="text-sm font-medium">
                {t(`${key}.label`)}
              </label>
              <p className="text-sm text-muted-foreground">
                {t(`${key}.description`)}
              </p>
            </div>
            <Switch
              id={`embed-${key}`}
              checked={options[key]}
              onCheckedChange={(checked) =>
                setOptions((current) => ({ ...current, [key]: checked }))
              }
            />
          </div>
        ))}
        <p className="text-sm text-muted-foreground">{t("recopyHint")}</p>
      </div>
    </div>
  );
}

"use client";

import { Button } from "@/components/ui/button";

/**
 * Renders each language's endonym (its own name, e.g. "Deutsch") so the control reads correctly
 * regardless of the surrounding UI locale. Falls back to the upper-cased code if `Intl.DisplayNames`
 * lacks the pairing.
 */
function displayName(code: string): string {
  try {
    return new Intl.DisplayNames([code], { type: "language" }).of(code) ?? code.toUpperCase();
  } catch {
    return code.toUpperCase();
  }
}

/** A row of language toggles. Renders nothing for a single-language policy — there is nothing to switch. */
export function LanguageSwitcher({
  label,
  languages,
  current,
  onSelect,
}: {
  label: string;
  languages: string[];
  current: string;
  onSelect: (lang: string) => void;
}) {
  if (languages.length <= 1) {
    return null;
  }

  return (
    <nav aria-label={label} className="flex flex-wrap gap-2">
      {languages.map((code) => {
        const isActive = code === current;
        return (
          <Button
            key={code}
            type="button"
            size="sm"
            variant={isActive ? "default" : "outline"}
            aria-current={isActive ? "true" : undefined}
            onClick={() => onSelect(code)}
          >
            {displayName(code)}
          </Button>
        );
      })}
    </nav>
  );
}

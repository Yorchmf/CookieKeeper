import { describe, expect, test } from "vitest";
import de from "../messages/de.json";
import en from "../messages/en.json";
import es from "../messages/es.json";
import fr from "../messages/fr.json";
import it from "../messages/it.json";

const LOCALES = { en, de, fr, es, it } as const;

/** Recursively flattens a message catalog into dot-notation key paths. */
function flattenKeys(value: unknown, prefix = ""): string[] {
  if (value === null || typeof value !== "object") {
    return [prefix];
  }
  return Object.entries(value as Record<string, unknown>).flatMap(
    ([key, child]) => flattenKeys(child, prefix ? `${prefix}.${key}` : key),
  );
}

describe("i18n message catalogs", () => {
  const referenceKeys = flattenKeys(en).sort();

  test("English catalog is not empty", () => {
    expect(referenceKeys.length).toBeGreaterThan(0);
  });

  test.each(Object.keys(LOCALES).filter((locale) => locale !== "en"))(
    "%s has exactly the same keys as en",
    (locale) => {
      const keys = flattenKeys(
        LOCALES[locale as keyof typeof LOCALES],
      ).sort();

      const missing = referenceKeys.filter((key) => !keys.includes(key));
      const extra = keys.filter((key) => !referenceKeys.includes(key));

      expect(missing, `missing keys in ${locale}`).toEqual([]);
      expect(extra, `extra keys in ${locale}`).toEqual([]);
    },
  );

  test("no locale has empty string values", () => {
    for (const [locale, catalog] of Object.entries(LOCALES)) {
      const emptyKeys = flattenKeys(catalog).filter((key) => {
        const value = key
          .split(".")
          .reduce<unknown>(
            (node, part) => (node as Record<string, unknown>)[part],
            catalog,
          );
        return typeof value === "string" && value.trim() === "";
      });
      expect(emptyKeys, `empty values in ${locale}`).toEqual([]);
    }
  });
});

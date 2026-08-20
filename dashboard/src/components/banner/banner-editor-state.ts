/**
 * Editor state for the banner customizer, kept separate from the React tree so the
 * config → editable-state → update-request transforms are unit-testable and the component stays thin.
 *
 * The state holds a text bundle for ALL supported languages (not just the offered ones) so toggling a
 * language off and back on never loses the customer's wording. Only offered-language texts are sent.
 */
import {
  BANNER_POSITIONS,
  CATEGORY_KEYS,
  CONSENT_LIFETIME_DAYS,
  DEFAULT_CONSENT_LIFETIME_DAYS,
  SUPPORTED_LANGUAGES,
  type BannerConfig,
  type BannerConfigUpdateInput,
  type BannerPosition,
  type BannerTexts,
  type BannerTheme,
  type CategoryKey,
  type ConsentLifetimeDays,
  type SupportedLanguage,
} from "@/lib/api/banner";

export interface BannerEditorState {
  position: BannerPosition;
  theme: BannerTheme;
  /** Category keys offered on the banner, in taxonomy order; `necessary` is always present. */
  offeredCategories: CategoryKey[];
  /** Offered languages, in taxonomy order. */
  languages: SupportedLanguage[];
  defaultLanguage: SupportedLanguage;
  /** Text bundle per supported language (kept for all languages so toggling is non-destructive). */
  texts: Record<string, BannerTexts>;
  /** Days a consent choice stays valid before the banner asks again. */
  consentLifetimeDays: ConsentLifetimeDays;
}

/** Narrows an untrusted position code to the allow-list, falling back to the default slot. */
export function asPosition(value: string): BannerPosition {
  return BANNER_POSITIONS.find((p) => p === value) ?? "bottom";
}

/** Narrows an untrusted language code to a supported locale, falling back to English. */
export function asLanguage(value: string): SupportedLanguage {
  return SUPPORTED_LANGUAGES.find((l) => l === value) ?? "en";
}

/**
 * Narrows a stored lifetime to one the editor offers. A config published before the field existed
 * has none, and one published against a value we later stopped offering would have no matching
 * option to select — both land on the 12-month default rather than an empty select.
 */
export function asConsentLifetime(value: number | undefined): ConsentLifetimeDays {
  return (
    CONSENT_LIFETIME_DAYS.find((days) => days === value) ??
    DEFAULT_CONSENT_LIFETIME_DAYS
  );
}

const BLANK_TEXTS: BannerTexts = {
  title: "",
  description: "",
  acceptAll: "",
  rejectAll: "",
  save: "",
  preferences: "",
  preferencesTitle: "",
  close: "",
  alwaysActive: "",
  categoryLabels: {},
};

/**
 * Copies one language's bundle. `categoryLabels` is cloned explicitly: a shallow spread would leave
 * every language sharing the fallback's single object, so editing German would silently edit French.
 */
function cloneTexts(texts: BannerTexts): BannerTexts {
  return {
    ...texts,
    categoryLabels: Object.fromEntries(
      Object.entries(texts.categoryLabels ?? {}).map(([key, value]) => [
        key,
        { ...value },
      ]),
    ),
  };
}

/** Derives editable state from a published config, seeding missing-language texts from the default. */
export function toEditorState(config: BannerConfig): BannerEditorState {
  const doc = config.config;
  const fallback = doc.texts[doc.defaultLanguage] ?? BLANK_TEXTS;
  const texts = Object.fromEntries(
    SUPPORTED_LANGUAGES.map((lang) => [
      lang,
      cloneTexts(doc.texts[lang] ?? fallback),
    ]),
  );
  return {
    position: asPosition(doc.position),
    theme: { ...doc.theme },
    offeredCategories: orderByTaxonomy(
      doc.categories.map((c) => c.key),
      CATEGORY_KEYS,
    ),
    languages: orderByTaxonomy(doc.languages, SUPPORTED_LANGUAGES),
    defaultLanguage: asLanguage(doc.defaultLanguage),
    texts,
    consentLifetimeDays: asConsentLifetime(doc.consentLifetimeDays),
  };
}

/** Serializes editor state into the publish request, sending texts only for offered languages. */
export function toUpdateInput(state: BannerEditorState): BannerConfigUpdateInput {
  return {
    position: state.position,
    theme: state.theme,
    categories: state.offeredCategories.map((key) => ({ key })),
    languages: state.languages,
    defaultLanguage: state.defaultLanguage,
    texts: Object.fromEntries(
      state.languages.map((lang) => [lang, state.texts[lang]]),
    ),
    consentLifetimeDays: state.consentLifetimeDays,
  };
}

/** Whether the editor holds unsaved edits relative to the published config. */
export function isDirty(state: BannerEditorState, config: BannerConfig): boolean {
  return (
    JSON.stringify(toUpdateInput(state)) !==
    JSON.stringify(toUpdateInput(toEditorState(config)))
  );
}

function orderByTaxonomy<T extends string>(
  keys: string[],
  order: readonly T[],
): T[] {
  const wanted = new Set(keys);
  return order.filter((key) => wanted.has(key));
}

import { describe, expect, test } from "vitest";
import {
  asLanguage,
  asPosition,
  isDirty,
  toEditorState,
  toUpdateInput,
} from "@/components/banner/banner-editor-state";
import type { BannerConfig, BannerTexts } from "@/lib/api/banner";

function texts(title: string): BannerTexts {
  return {
    title,
    description: "We use cookies.",
    acceptAll: "Accept",
    rejectAll: "Reject",
    save: "Save",
    preferences: "Manage",
    preferencesTitle: "Privacy preferences",
    close: "Close",
    alwaysActive: "Always active",
    categoryLabels: {
      necessary: { label: "Strictly necessary", description: "Required." },
    },
  };
}

function config(): BannerConfig {
  return {
    version: 3,
    publishedAt: "2026-08-01T00:00:00Z",
    config: {
      position: "bottom",
      theme: { primaryColor: "#2563eb", background: "#ffffff", textColor: "#0f172a" },
      categories: [
        { key: "necessary", required: true, enabledByDefault: true },
        { key: "statistics", required: false, enabledByDefault: false },
      ],
      languages: ["en", "de"],
      defaultLanguage: "en",
      texts: { en: texts("Privacy"), de: texts("Datenschutz") },
    },
  };
}

describe("asPosition", () => {
  test("passes through a known position and falls back for anything else", () => {
    expect(asPosition("top")).toBe("top");
    expect(asPosition("floating")).toBe("bottom");
    expect(asPosition("")).toBe("bottom");
  });

  test("folds a legacy center config onto the layout the widget can render", () => {
    // The editor used to offer `center`, but the widget only implements bottom/top and the
    // backend serves such a config as `bottom` (ADR-19). Loading it as `bottom` keeps the
    // preview honest — and matches what visitors already see today.
    expect(asPosition("center")).toBe("bottom");
  });
});

describe("asLanguage", () => {
  test("passes through a supported locale and falls back to English", () => {
    expect(asLanguage("de")).toBe("de");
    expect(asLanguage("pt")).toBe("en");
    expect(asLanguage("")).toBe("en");
  });
});

describe("toEditorState", () => {
  test("derives offered categories and languages in taxonomy order", () => {
    const state = toEditorState(config());
    expect(state.offeredCategories).toEqual(["necessary", "statistics"]);
    expect(state.languages).toEqual(["en", "de"]);
    expect(state.defaultLanguage).toBe("en");
  });

  test("reorders out-of-order categories and languages into taxonomy order", () => {
    const base = config();
    const scrambled: BannerConfig = {
      ...base,
      config: {
        ...base.config,
        categories: [
          { key: "marketing", required: false, enabledByDefault: false },
          { key: "necessary", required: true, enabledByDefault: true },
          { key: "preferences", required: false, enabledByDefault: false },
        ],
        languages: ["it", "en", "de"],
      },
    };
    const state = toEditorState(scrambled);
    expect(state.offeredCategories).toEqual([
      "necessary",
      "preferences",
      "marketing",
    ]);
    expect(state.languages).toEqual(["en", "de", "it"]);
  });

  test("normalizes an unknown persisted position to the default slot", () => {
    const base = config();
    const legacy: BannerConfig = {
      ...base,
      config: { ...base.config, position: "floating" },
    };
    expect(toEditorState(legacy).position).toBe("bottom");
  });

  test("seeds blank text bundles when the default language has no texts", () => {
    const base = config();
    const missing: BannerConfig = {
      ...base,
      config: { ...base.config, texts: {} },
    };
    const state = toEditorState(missing);
    expect(state.texts.en.title).toBe("");
    expect(state.texts.de.description).toBe("");
  });

  test("seeds text bundles for every supported language from the default", () => {
    const state = toEditorState(config());
    // en/de come from the config; fr/es/it are seeded from the default (en) so toggling never blanks.
    expect(Object.keys(state.texts).sort()).toEqual(["de", "en", "es", "fr", "it"]);
    expect(state.texts.fr.title).toBe("Privacy");
    expect(state.texts.de.title).toBe("Datenschutz");
  });

  test("gives every language its own category-label objects", () => {
    // Seeded languages share the default's bundle by reference unless it is deep-copied, which would
    // make editing the French category label silently rewrite the Italian one too.
    const state = toEditorState(config());
    state.texts.fr.categoryLabels.necessary.label = "Strictement nécessaire";

    expect(state.texts.it.categoryLabels.necessary.label).toBe(
      "Strictly necessary",
    );
    expect(state.texts.en.categoryLabels.necessary.label).toBe(
      "Strictly necessary",
    );
  });

  test("seeds a blank bundle with an empty category-label map", () => {
    const base = config();
    const missing: BannerConfig = {
      ...base,
      config: { ...base.config, texts: {} },
    };
    const state = toEditorState(missing);
    expect(state.texts.en.categoryLabels).toEqual({});
    expect(state.texts.en.preferencesTitle).toBe("");
  });
});

describe("toUpdateInput", () => {
  test("sends texts only for offered languages and keys categories by taxonomy", () => {
    const input = toUpdateInput(toEditorState(config()));
    expect(Object.keys(input.texts).sort()).toEqual(["de", "en"]);
    expect(input.categories).toEqual([{ key: "necessary" }, { key: "statistics" }]);
    expect(input.defaultLanguage).toBe("en");
  });
});

describe("isDirty", () => {
  test("is false for the unedited round-trip", () => {
    const base = config();
    expect(isDirty(toEditorState(base), base)).toBe(false);
  });

  test("is true after a position edit", () => {
    const base = config();
    const edited = { ...toEditorState(base), position: "top" as const };
    expect(isDirty(edited, base)).toBe(true);
  });

  test("is true after a theme edit", () => {
    const base = config();
    const state = toEditorState(base);
    const edited = {
      ...state,
      theme: { ...state.theme, primaryColor: "#ff0000" },
    };
    expect(isDirty(edited, base)).toBe(true);
  });

  test("is true after dropping an offered language", () => {
    const base = config();
    const state = toEditorState(base);
    const edited = { ...state, languages: ["en" as const] };
    expect(isDirty(edited, base)).toBe(true);
  });

  test("ignores edits to non-offered languages", () => {
    const base = config();
    const state = toEditorState(base);
    // Italian isn't offered, so changing its (seeded) text must not count as a pending change.
    const edited = { ...state, texts: { ...state.texts, it: texts("Cambiato") } };
    expect(isDirty(edited, base)).toBe(false);
  });
});

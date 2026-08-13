"use client";

import { useTranslations } from "next-intl";
import {
  useId,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
} from "react";
import { toast } from "sonner";
import {
  asLanguage,
  isDirty,
  toEditorState,
  toUpdateInput,
  type BannerEditorState,
} from "@/components/banner/banner-editor-state";
import { BannerPreview } from "@/components/banner/banner-preview";
import { BannerTextFields } from "@/components/banner/banner-text-fields";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useUpdateBannerConfig } from "@/hooks/use-banner";
import {
  BANNER_POSITIONS,
  CATEGORY_KEYS,
  SUPPORTED_LANGUAGES,
  type BannerConfig,
  type BannerPosition,
  type BannerTexts,
  type BannerTheme,
  type CategoryKey,
  type SupportedLanguage,
} from "@/lib/api/banner";

/** The theme color slots, in the order they're shown. */
const COLOR_SLOTS: (keyof BannerTheme)[] = [
  "primaryColor",
  "background",
  "textColor",
];

/**
 * The banner customizer form. Owns the editable state derived from the published config, keeps a text
 * bundle per language so language toggling is non-destructive, and publishes a new version on save.
 * Save is blocked while any offered-language text is blank — the backend rejects those, so we surface
 * it inline rather than round-tripping a 400.
 */
export function BannerEditor({
  siteId,
  config,
}: {
  siteId: string;
  config: BannerConfig;
}) {
  const t = useTranslations("banner");
  const update = useUpdateBannerConfig(siteId);
  const [state, setState] = useState<BannerEditorState>(() =>
    toEditorState(config),
  );
  const [editingLang, setEditingLang] = useState<SupportedLanguage>(
    state.defaultLanguage,
  );

  const dirty = isDirty(state, config);
  const blankLanguages = useMemo(() => offeredBlankLanguages(state), [state]);
  const canSave = dirty && blankLanguages.length === 0 && !update.isPending;

  const activeLang = state.languages.includes(editingLang)
    ? editingLang
    : state.defaultLanguage;

  const handleSave = async () => {
    if (blankLanguages.length > 0) {
      return;
    }
    try {
      await update.mutateAsync(toUpdateInput(state));
      toast.success(t("saved"));
    } catch {
      toast.error(t("saveError"));
    }
  };

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
      <div className="flex flex-col gap-6">
        <PositionCard
          value={state.position}
          onChange={(position) => setState((s) => ({ ...s, position }))}
        />
        <ColorsCard
          theme={state.theme}
          onChange={(theme) => setState((s) => ({ ...s, theme }))}
        />
        <CategoriesCard
          offered={state.offeredCategories}
          onToggle={(key) =>
            setState((s) => ({
              ...s,
              offeredCategories: toggleCategory(s.offeredCategories, key),
            }))
          }
        />
        <LanguagesCard
          state={state}
          onChange={(next) => {
            setState(next);
            // Keep the active text tab if its language is still offered; otherwise fall back to
            // the default so the tab panel never points at a dropped language.
            setEditingLang((cur) =>
              next.languages.includes(cur) ? cur : next.defaultLanguage,
            );
          }}
        />
        <TextCard
          state={state}
          activeLang={activeLang}
          onSelectLang={setEditingLang}
          onChangeField={(field, value) =>
            setState((s) => ({
              ...s,
              texts: {
                ...s.texts,
                [activeLang]: { ...s.texts[activeLang], [field]: value },
              },
            }))
          }
          onChangeCategoryText={(categoryKey, field, value) =>
            setState((s) => {
              const bundle = s.texts[activeLang];
              const current = bundle.categoryLabels[categoryKey] ?? {
                label: "",
                description: "",
              };
              return {
                ...s,
                texts: {
                  ...s.texts,
                  [activeLang]: {
                    ...bundle,
                    categoryLabels: {
                      ...bundle.categoryLabels,
                      [categoryKey]: { ...current, [field]: value },
                    },
                  },
                },
              };
            })
          }
        />
      </div>

      <div className="flex flex-col gap-4 lg:sticky lg:top-6 lg:self-start">
        <Card>
          <CardHeader>
            <CardTitle>{t("preview.label")}</CardTitle>
            <CardDescription>{t("preview.description")}</CardDescription>
          </CardHeader>
          <CardContent>
            <BannerPreview state={state} language={activeLang} />
          </CardContent>
        </Card>

        <div className="flex flex-col gap-2 rounded-xl border border-border bg-card p-4">
          {blankLanguages.length > 0 ? (
            <p role="alert" className="text-sm text-destructive">
              {t("texts.incomplete")}
            </p>
          ) : dirty ? (
            <p className="text-sm text-muted-foreground">{t("unsaved")}</p>
          ) : (
            <p className="text-sm text-muted-foreground">
              {t("version", { version: config.version })}
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <Button onClick={() => void handleSave()} disabled={!canSave}>
              {update.isPending ? t("saving") : t("save")}
            </Button>
            {dirty ? (
              <Button
                variant="ghost"
                onClick={() => {
                  setState(toEditorState(config));
                  setEditingLang(asLanguage(config.config.defaultLanguage));
                }}
                disabled={update.isPending}
              >
                {t("reset")}
              </Button>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * The banner copy the backend rejects when blank. The preferences-panel fields are deliberately not
 * listed: leaving them empty publishes our own translation, so a blank one must not block Save.
 */
const REQUIRED_TEXT_FIELDS = [
  "title",
  "description",
  "acceptAll",
  "rejectAll",
  "save",
  "preferences",
] as const satisfies readonly (keyof BannerTexts)[];

/** Offered languages whose text bundle has at least one blank required field. */
function offeredBlankLanguages(state: BannerEditorState): SupportedLanguage[] {
  return state.languages.filter((lang) => {
    const texts = state.texts[lang];
    return (
      !texts ||
      REQUIRED_TEXT_FIELDS.some((field) => texts[field].trim() === "")
    );
  });
}

function toggleCategory(
  offered: CategoryKey[],
  key: CategoryKey,
): CategoryKey[] {
  const has = offered.includes(key);
  const next = has ? offered.filter((k) => k !== key) : [...offered, key];
  return CATEGORY_KEYS.filter((k) => next.includes(k));
}

/**
 * Returns the item a roving-tabindex ring should move to for arrow/Home/End keys, or `null` when the
 * key isn't a navigation key. Shared by the position radiogroup and the language tablist.
 */
function nextInRing<T extends string>(
  event: ReactKeyboardEvent,
  items: readonly T[],
  current: T,
): T | null {
  const idx = items.indexOf(current);
  if (idx < 0) {
    return null;
  }
  switch (event.key) {
    case "ArrowRight":
    case "ArrowDown":
      return items[(idx + 1) % items.length];
    case "ArrowLeft":
    case "ArrowUp":
      return items[(idx - 1 + items.length) % items.length];
    case "Home":
      return items[0];
    case "End":
      return items[items.length - 1];
    default:
      return null;
  }
}

function PositionCard({
  value,
  onChange,
}: {
  value: BannerPosition;
  onChange: (value: BannerPosition) => void;
}) {
  const t = useTranslations("banner.position");
  const refs = useRef<Partial<Record<BannerPosition, HTMLButtonElement | null>>>(
    {},
  );

  const handleKeyDown = (event: ReactKeyboardEvent) => {
    const next = nextInRing(event, BANNER_POSITIONS, value);
    if (!next) {
      return;
    }
    event.preventDefault();
    onChange(next);
    refs.current[next]?.focus();
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("label")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent>
        <div
          role="radiogroup"
          aria-label={t("label")}
          className="flex flex-wrap gap-2"
          onKeyDown={handleKeyDown}
        >
          {BANNER_POSITIONS.map((position) => {
            const checked = position === value;
            return (
              <button
                key={position}
                ref={(el) => {
                  refs.current[position] = el;
                }}
                type="button"
                role="radio"
                aria-checked={checked}
                tabIndex={checked ? 0 : -1}
                onClick={() => onChange(position)}
                className={buttonVariants({
                  variant: checked ? "default" : "outline",
                  size: "sm",
                })}
              >
                {t(position)}
              </button>
            );
          })}
        </div>
      </CardContent>
    </Card>
  );
}

function ColorsCard({
  theme,
  onChange,
}: {
  theme: BannerTheme;
  onChange: (theme: BannerTheme) => void;
}) {
  const t = useTranslations("banner.theme");
  const fieldId = useId();
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("label")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4 sm:grid-cols-3">
        {COLOR_SLOTS.map((slot) => {
          const id = `${fieldId}-${slot}`;
          return (
            <div key={slot} className="flex flex-col gap-2">
              <Label htmlFor={id}>{t(slot)}</Label>
              <div className="flex items-center gap-2">
                <input
                  type="color"
                  aria-label={t(slot)}
                  value={normalizeHex(theme[slot])}
                  onChange={(event) =>
                    onChange({ ...theme, [slot]: event.target.value })
                  }
                  className="h-8 w-9 shrink-0 cursor-pointer rounded-md border border-input bg-transparent p-0.5"
                />
                <Input
                  id={id}
                  value={theme[slot]}
                  maxLength={7}
                  spellCheck={false}
                  onChange={(event) =>
                    onChange({ ...theme, [slot]: event.target.value })
                  }
                  className="font-mono"
                />
              </div>
            </div>
          );
        })}
      </CardContent>
    </Card>
  );
}

function CategoriesCard({
  offered,
  onToggle,
}: {
  offered: CategoryKey[];
  onToggle: (key: CategoryKey) => void;
}) {
  const t = useTranslations("banner.categories");
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("label")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        {CATEGORY_KEYS.map((key) => {
          const locked = key === "necessary";
          const isOffered = offered.includes(key);
          return (
            <label
              key={key}
              className="flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2"
            >
              <span className="flex flex-col">
                <span className="text-sm font-medium">{t(`names.${key}`)}</span>
                {locked ? (
                  <span className="text-xs text-muted-foreground">
                    {t("always")}
                  </span>
                ) : null}
              </span>
              <input
                type="checkbox"
                checked={isOffered}
                disabled={locked}
                onChange={() => onToggle(key)}
                className="size-4 accent-primary disabled:opacity-60"
              />
            </label>
          );
        })}
      </CardContent>
    </Card>
  );
}

function LanguagesCard({
  state,
  onChange,
}: {
  state: BannerEditorState;
  onChange: (next: BannerEditorState) => void;
}) {
  const t = useTranslations("banner.languages");
  const selectId = useId();

  const toggleLanguage = (lang: SupportedLanguage) => {
    const has = state.languages.includes(lang);
    if (has && state.languages.length === 1) {
      return; // never drop the last language
    }
    const next = has
      ? state.languages.filter((l) => l !== lang)
      : [...state.languages, lang];
    const languages = SUPPORTED_LANGUAGES.filter((l) => next.includes(l));
    const defaultLanguage = languages.includes(state.defaultLanguage)
      ? state.defaultLanguage
      : languages[0];
    onChange({ ...state, languages, defaultLanguage });
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("label")}</CardTitle>
        <CardDescription>{t("description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap gap-2" role="group" aria-label={t("label")}>
          {SUPPORTED_LANGUAGES.map((lang) => {
            const isOffered = state.languages.includes(lang);
            return (
              <Button
                key={lang}
                type="button"
                size="sm"
                variant={isOffered ? "default" : "outline"}
                aria-pressed={isOffered}
                onClick={() => toggleLanguage(lang)}
              >
                {t(`names.${lang}`)}
              </Button>
            );
          })}
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={selectId}>{t("defaultLabel")}</Label>
          <select
            id={selectId}
            value={state.defaultLanguage}
            onChange={(event) =>
              onChange({
                ...state,
                defaultLanguage: asLanguage(event.target.value),
              })
            }
            className="h-8 w-full max-w-48 rounded-lg border border-input bg-transparent px-2.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            {state.languages.map((lang) => (
              <option key={lang} value={lang}>
                {t(`names.${lang}`)}
              </option>
            ))}
          </select>
          <p className="text-sm text-muted-foreground">{t("defaultHint")}</p>
        </div>
      </CardContent>
    </Card>
  );
}

function TextCard({
  state,
  activeLang,
  onSelectLang,
  onChangeField,
  onChangeCategoryText,
}: {
  state: BannerEditorState;
  activeLang: SupportedLanguage;
  onSelectLang: (lang: SupportedLanguage) => void;
  onChangeField: (field: keyof BannerTexts, value: string) => void;
  onChangeCategoryText: (
    categoryKey: string,
    field: "label" | "description",
    value: string,
  ) => void;
}) {
  const t = useTranslations("banner");
  const tabId = useId();
  const refs = useRef<Partial<Record<SupportedLanguage, HTMLButtonElement | null>>>(
    {},
  );
  const multiLang = state.languages.length > 1;

  const handleKeyDown = (event: ReactKeyboardEvent) => {
    const next = nextInRing(event, state.languages, activeLang);
    if (!next) {
      return;
    }
    event.preventDefault();
    onSelectLang(next);
    refs.current[next]?.focus();
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("texts.label")}</CardTitle>
        <CardDescription>{t("texts.description")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {multiLang ? (
          <div
            role="tablist"
            aria-label={t("texts.languageNav")}
            className="flex flex-wrap gap-2"
            onKeyDown={handleKeyDown}
          >
            {state.languages.map((lang) => {
              const selected = lang === activeLang;
              return (
                <button
                  key={lang}
                  ref={(el) => {
                    refs.current[lang] = el;
                  }}
                  type="button"
                  role="tab"
                  id={`${tabId}-tab-${lang}`}
                  aria-selected={selected}
                  aria-controls={`${tabId}-panel`}
                  tabIndex={selected ? 0 : -1}
                  onClick={() => onSelectLang(lang)}
                  className={buttonVariants({
                    variant: selected ? "secondary" : "ghost",
                    size: "sm",
                  })}
                >
                  {t(`languages.names.${lang}`)}
                </button>
              );
            })}
          </div>
        ) : null}
        <div
          role={multiLang ? "tabpanel" : undefined}
          id={multiLang ? `${tabId}-panel` : undefined}
          aria-labelledby={multiLang ? `${tabId}-tab-${activeLang}` : undefined}
        >
          <BannerTextFields
            language={activeLang}
            texts={state.texts[activeLang]}
            categories={state.offeredCategories}
            onChange={onChangeField}
            onCategoryChange={onChangeCategoryText}
          />
        </div>
      </CardContent>
    </Card>
  );
}

/** Coerces an editable hex string into a valid `#rrggbb` for the native color input's `value`. */
function normalizeHex(value: string): string {
  const trimmed = value.trim();
  if (/^#[0-9a-f]{6}$/i.test(trimmed)) {
    return trimmed;
  }
  if (/^#[0-9a-f]{3}$/i.test(trimmed)) {
    const [, r, g, b] = trimmed;
    return `#${r}${r}${g}${g}${b}${b}`;
  }
  return "#000000";
}

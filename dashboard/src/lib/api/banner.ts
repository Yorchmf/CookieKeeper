/**
 * Typed client for the authenticated banner-config endpoints under
 * `/api/v1/sites/{siteId}/banner-config` (read current, publish a new version, copy to other sites).
 *
 * Mirrors the backend DTOs in `eu.cookiekeeper.banner.dto` (BannerConfigDtos.kt) and the
 * `BannerConfigDocument` served verbatim to the widget. The backend re-validates and normalizes
 * every field before persisting ({@link https} BannerConfigValidator) — the client-side allow-lists
 * below (positions, supported languages, category taxonomy) exist only to shape the editor UI.
 */
import { ApiError, apiFetch } from "@/lib/api";

/**
 * Where the banner renders. The backend still accepts `center` (older configs carry it), but the
 * widget only implements `bottom` and `top` — a `center` config is served to the widget as `bottom`
 * (ADR-19). Offering it here would promise a layout no visitor ever sees, so the editor omits it
 * and `asPosition` folds a stored `center` back to `bottom` on load.
 */
export const BANNER_POSITIONS = ["bottom", "top"] as const;
export type BannerPosition = (typeof BANNER_POSITIONS)[number];

/** The languages the banner can be offered in. Must match backend SupportedLocales.CODES. */
export const SUPPORTED_LANGUAGES = ["en", "de", "fr", "es", "it"] as const;
export type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number];

/** The canonical consent-category taxonomy, in display order. Must match backend ConsentCategory. */
export const CATEGORY_KEYS = [
  "necessary",
  "preferences",
  "statistics",
  "marketing",
] as const;
export type CategoryKey = (typeof CATEGORY_KEYS)[number];

export interface BannerTheme {
  primaryColor: string;
  background: string;
  textColor: string;
}

export interface BannerCategory {
  key: string;
  /** Required categories (necessary) are shown locked-on and cannot be rejected. */
  required: boolean;
  /** Initial toggle state for optional categories before the visitor chooses. */
  enabledByDefault: boolean;
}

/** One category's copy in the preferences panel. */
export interface BannerCategoryText {
  label: string;
  description: string;
}

/**
 * One language's banner copy.
 *
 * The preferences-panel fields are optional on the wire: the backend fills a blank one with its own
 * translation for that language rather than publishing an empty label, and backfills them on read so
 * configs predating ADR-19 Slice 2 arrive here already populated. The "Powered by Complyr"
 * attribution is deliberately absent — it is server-owned, because suppressing it is a paid
 * entitlement and an editable string would be a way around it.
 */
export interface BannerTexts {
  title: string;
  description: string;
  acceptAll: string;
  rejectAll: string;
  save: string;
  preferences: string;
  preferencesTitle: string;
  close: string;
  alwaysActive: string;
  categoryLabels: Record<string, BannerCategoryText>;
}

/**
 * How long a visitor's consent choice stays valid before the banner asks again, in days. Must match
 * backend CONSENT_LIFETIME_DAY_OPTIONS — the backend rejects anything else, so this list is the
 * editor's menu, not the rule.
 */
export const CONSENT_LIFETIME_DAYS = [90, 180, 365] as const;
export type ConsentLifetimeDays = (typeof CONSENT_LIFETIME_DAYS)[number];

/** What a site gets unless it chooses otherwise, and the fallback for configs predating the field. */
export const DEFAULT_CONSENT_LIFETIME_DAYS: ConsentLifetimeDays = 365;

/** The versioned per-site widget configuration (BannerConfigDocument). */
export interface BannerConfigDocument {
  position: string;
  theme: BannerTheme;
  categories: BannerCategory[];
  languages: string[];
  defaultLanguage: string;
  texts: Record<string, BannerTexts>;
  /** Optional on the wire: configs published before this field existed carry the 12-month default. */
  consentLifetimeDays?: number;
}

/** The current (or freshly published) banner configuration (BannerConfigResponse). */
export interface BannerConfig {
  version: number;
  publishedAt: string | null;
  config: BannerConfigDocument;
}

/**
 * The request that publishes a new version (BannerConfigUpdateRequest). Categories carry only their
 * key — `required`/`enabledByDefault` are derived server-side from the taxonomy (GDPR: the client can
 * never pre-enable a non-necessary category).
 */
export interface BannerConfigUpdateInput {
  position: BannerPosition;
  theme: BannerTheme;
  categories: { key: CategoryKey }[];
  languages: SupportedLanguage[];
  defaultLanguage: SupportedLanguage;
  texts: Record<string, BannerTexts>;
  consentLifetimeDays: ConsentLifetimeDays;
}

/**
 * The site's current published banner config, or `null` when none exists. Every site seeds a default
 * v1 on creation, so a 404 (BANNER_CONFIG_NOT_FOUND) is an edge case handled as an empty state.
 */
export async function getBannerConfig(
  siteId: string,
): Promise<BannerConfig | null> {
  try {
    const { data } = await apiFetch<BannerConfig>(
      `/api/v1/sites/${encodeURIComponent(siteId)}/banner-config`,
    );
    return data;
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return null;
    }
    throw error;
  }
}

/** Targets accepted in one copy request. Must match backend MAX_COPY_TARGETS (BannerConfigDtos.kt). */
export const MAX_BANNER_COPY_TARGETS = 20;

/**
 * What a copy did (BannerConfigCopyResponse): the source version that was applied, and the sites that
 * received it — the source itself is silently dropped from the target list server-side, so this is the
 * authoritative list to report back rather than what the caller asked for.
 */
export interface BannerConfigCopyResult {
  sourceVersion: number;
  copiedToSiteIds: string[];
}

/**
 * Apply this site's published banner to other sites, each as a new version of its own.
 *
 * Server-side the source document is re-read from the database (never sent up from the browser) and the
 * whole copy is one all-or-nothing transaction, so a target that is not yours or is archived fails the
 * request without half-applying it.
 */
export async function copyBannerConfig(
  siteId: string,
  targetSiteIds: string[],
): Promise<BannerConfigCopyResult> {
  const { data } = await apiFetch<BannerConfigCopyResult>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/banner-config/copy`,
    { method: "POST", body: JSON.stringify({ targetSiteIds }) },
  );
  return data;
}

/** Validate and publish a new banner version from the customizer's Save. */
export async function updateBannerConfig(
  siteId: string,
  input: BannerConfigUpdateInput,
): Promise<BannerConfig> {
  const { data } = await apiFetch<BannerConfig>(
    `/api/v1/sites/${encodeURIComponent(siteId)}/banner-config`,
    { method: "PUT", body: JSON.stringify(input) },
  );
  return data;
}

package com.complyr.banner.dto

/**
 * The widget's own config contract, served flat (no `{ success, data, … }` envelope) at
 * `GET /cfg/{siteKey}.json` — see ADR-19. It mirrors `WidgetConfig` in `widget/src/config.ts`
 * field for field; the widget parses this shape directly and falls back to its built-in default
 * if anything here fails its validation, so the names and types below are load-bearing.
 *
 * Deliberately narrower than [WidgetConfigResponse]: no `siteKey` (the widget already knows it),
 * no `languages` (the widget keys off [texts] and [defaultLanguage]), no `enabledByDefault`
 * (only `necessary` is ever on before a choice, which the widget derives from `required`).
 * Every field is one the widget reads, because this payload ships on every page load of every
 * customer site.
 */
data class WidgetConfigPayload(
    /** Banner config version shown to the visitor; echoed back on the consent event. */
    val version: Int,
    val colors: WidgetColors,
    /** `bottom` | `top` — the widget has no `center` layout (ADR-19). */
    val position: String,
    /** Language code → text bundle. Partial bundles are merged over the widget's English default. */
    val texts: Map<String, WidgetTexts>,
    val defaultLanguage: String,
    val categories: List<WidgetCategory>,
    val removeBranding: Boolean,
)

/**
 * The widget's four-token palette. The stored theme has three (`primaryColor`, `background`,
 * `textColor`); [buttonText] is computed, not stored — see `WidgetConfigMapper.readableTextOn`.
 */
data class WidgetColors(
    val background: String,
    val text: String,
    val button: String,
    val buttonText: String,
)

data class WidgetTexts(
    val title: String,
    /** The stored `description`, under the name the widget reads. */
    val message: String,
    val acceptAll: String,
    val rejectAll: String,
    val preferences: String,
    val save: String,
    /** Heading of the preferences panel. */
    val preferencesTitle: String,
    /** Label of the panel's close control. */
    val close: String,
    /** Badge on categories the visitor cannot switch off. */
    val alwaysActive: String,
    /** Attribution text — server-owned, never customer-editable (`WidgetAttributionTexts`). */
    val poweredBy: String,
    /** Screen-reader-only suffix on the attribution link (WCAG 2.2 3.2.5); server-owned. */
    val opensInNewTab: String,
    /** Category id → the label and explanation shown in the preferences panel. */
    val categoryLabels: Map<String, WidgetCategoryText>,
)

/** One category's copy in the preferences panel. */
data class WidgetCategoryText(
    val label: String,
    val description: String,
)

/** The stored category `key`, under the name the widget reads. */
data class WidgetCategory(
    val id: String,
    val required: Boolean,
)

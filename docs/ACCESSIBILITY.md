# Accessibility conformance statement — Complyr consent banner

**Applies to:** the embeddable consent widget (`widget/`) — the consent banner and the privacy
preferences dialog it opens — as shipped in `v1.js`.
**Target:** WCAG 2.2 Level AA, as referenced by **EN 301 549 v3.2.1** and the
**European Accessibility Act** (Directive (EU) 2019/882, applicable since 28 June 2025).
**Status:** Partially conformant — see [Known limitations](#known-limitations).
**Last reviewed:** 2026-08-20. See ADR-28 in [ARCHITECTURE.md](ARCHITECTURE.md).

## Why this document exists

The EAA covers e-commerce services sold to consumers in the EU — which is most of our customer base.
A consent banner is the first thing a visitor meets and the one element they cannot skip past, so an
inaccessible banner does not merely annoy: it blocks the whole service, and it does so on the
customer's own site, under the customer's own liability. Our customers cannot audit our widget
themselves. This statement is what they can point at.

## Scope

| Surface | Covered here |
|---|---|
| Consent banner (`widget/src/banner.ts`) | Yes |
| Preferences dialog (`widget/src/preferences.ts`) | Yes |
| Embeddable cookie table (`widget/src/policy-table.ts`, ADR-27) | Yes — it renders a plain `<table>` with a caption and header cells into the customer's page and adds no interaction |
| Customer dashboard (`dashboard/`) | **No.** Reviewed informally, not audited; no conformance is claimed |
| Hosted policy page `/p/{publicId}` | **No.** Static text, not yet audited |
| The customer's own site | No — outside our control by definition |

## How the banner meets WCAG 2.2 AA

Criteria are listed where we made a deliberate decision; unlisted criteria are either not applicable
(no audio, video, motion, timing or drag interactions exist anywhere in the widget) or met by
ordinary semantic HTML.

### Both surfaces

| SC | How |
|---|---|
| **1.3.1** Info and Relationships | Real elements throughout: `<h2>`, `<p>`, `<button type="button">`, `<input type="checkbox">` with `<label for>`. There is **no `innerHTML` anywhere in the widget** — every node is built with `createElement` + `textContent`, which is a security property (ADR-27) that happens to also rule out div-soup |
| **1.4.3** Contrast (Minimum) | The customer's `textColor`/`background` pair is **rejected by the API** below 4.5:1 (`BannerConfigValidator.checkContrast`), and the banner customizer shows the live ratio beside the colour pickers and holds Save until it passes |
| **1.4.11** Non-text Contrast | `primaryColor` (button fill, checkbox accent, "always active" badge outline) is held to 3:1 against the background by the same validator. Focus rings are painted in a colour chosen to contrast with *the thing they ring* — `textColor` over the panel, `buttonText` inside a filled primary button |
| **1.4.4 / 1.4.10** Resize, Reflow | Sizes are in `px` within a Shadow DOM `:host { all: initial }`, so host-page zoom and browser text scaling apply normally; the panel is `max-height: calc(100vh - 32px)` with `overflow: auto` and the banner wraps its actions |
| **1.4.12** Text Spacing | No fixed heights on text containers; all boxes grow with their content |
| **2.1.1** Keyboard | Every control is a native `button` or `input`. Nothing is a click-handled `div` |
| **2.4.7** Focus Visible | Explicit `:focus-visible` rings on every control, never `outline: none` |
| **2.5.3** Label in Name | Visible label text *is* the accessible name; the only `aria-label` that differs is the attribution link, where the visible text is a prefix of the accessible name |
| **2.5.8** Target Size (Minimum) | Buttons are ≥ 24 CSS px by padding; checkboxes are 24×24; the close control is 32×32; category labels and the attribution link are sized by `line-height: 24px` rather than left at their font size |
| **3.1.2** Language of Parts | Both dialogs carry a `lang` attribute set to the language **actually rendered** — resolved once in `config.resolveLanguage` against the languages the site publishes, never the raw `navigator.language`. Declaring `fr` while showing English copy is worse than declaring nothing |
| **3.2.4** Consistent Identification | The same three actions, in the same order, in both surfaces |
| **4.1.2** Name, Role, Value | Roles and states come from native elements; `aria-describedby`/`aria-labelledby` targets are asserted to resolve to real nodes in the test suite |

### Consent banner

| SC | How |
|---|---|
| **2.4.3** Focus Order | Focus moves to *Accept all* when the banner appears, so keyboard users are not required to tab past the page to reach it, and is **restored to the previously focused element** when the banner is removed |
| **2.1.2** No Keyboard Trap | The banner is **deliberately not a focus trap and deliberately not `aria-modal`** — see [Known limitations](#known-limitations) |
| **2.2.2** Pause, Stop, Hide | The banner has **no animation or transition at all**. There is nothing to pause |
| **3.2.5** Change on Request | The "Powered by Complyr" link opens a new tab and says so in its accessible name (`"Powered by Complyr (opens in a new tab)"`), localised alongside the rest of the copy |

### Preferences dialog

| SC | How |
|---|---|
| **1.3.1 / 3.3.2** Labels or Instructions | Each category is a checkbox with a `<label for>` and an `aria-describedby` pointing at its description. Required categories are checked, `disabled`, and carry a **visible "Always active" text badge** — the state is never conveyed by colour or opacity alone |
| **2.1.2** No Keyboard Trap | The dialog is modal, so Tab and Shift+Tab cycle within it — but **Escape always closes it**, and so do the Close control and a backdrop click. Trapping without an exit would be the violation; this has three |
| **2.4.3** Focus Order | Initial focus goes to the dialog container (`tabindex="-1"`, announced but never a Tab stop); Shift+Tab from there wraps to the *last* control rather than escaping the shadow root. Focus returns to the control that opened the dialog on close |
| **2.4.11** Focus Not Obscured | The rest of the page is `inert` and scroll-locked while the dialog is open, so no focusable element can end up behind it. `inert` is restored only if we were the ones who set it — a host page that had already marked a region inert keeps it |
| **2.3.3 / 2.2.2** Motion | `@media (prefers-reduced-motion: reduce)` disables every transition and animation inside the dialog |

## Known limitations

1. **The banner is not a focus trap, on purpose.** WCAG 2.4.11 (Focus Not Obscured, Minimum) is
   satisfied when a focused element is not *entirely* hidden; a fixed-position banner can still
   overlap page content behind it. The available fixes were to trap focus in the banner or to make
   the page `inert` behind it — both of which hold the entire site hostage behind a cookie notice for
   keyboard and screen-reader users, which is a considerably worse barrier than the one they fix. We
   instead move focus into the banner immediately and let it be dismissed in one keystroke. The
   preferences dialog, which *is* modal by nature, does inert the background.
2. **Contrast is enforced at save time, not retroactively.** A theme stored before this rule existed
   keeps serving until the customer next opens the customizer, where the failing pair is shown and
   Save is blocked until it is fixed. We do not rewrite a customer's published banner without them.
3. **Banner copy is the customer's.** We validate that required text is present and that it is
   escaped, not that it is clear, correctly translated, or written at any particular reading level
   (3.1.5 is AAA in any case). Our own default copy in the five supported languages is ours.
4. **The composed page is not fully ours.** The widget renders in a Shadow DOM with `all: initial`,
   but a host page can still run its own focus trap, stack an overlay above us, or set a page-wide
   `prefers-reduced-motion` override. We cannot test every host.
5. **No third-party audit and no assistive-technology lab testing yet.** The assessment below is a
   self-assessment. An external audit is on the roadmap, not done.

## How this was assessed

Self-assessment, on 2026-08-20, by code review of both surfaces against the WCAG 2.2 AA success
criteria, plus automated regression tests that pin the behaviours above:

- `widget/test/banner.test.ts` — dialog semantics and naming, `lang`, focus move, focus restore,
  the attribution link's new-tab announcement, and that the banner does **not** inert the page.
- `widget/test/preferences.test.ts` — labelled/described modal, initial focus on a non-tabbable
  container, Tab and Shift+Tab wrapping (including the container case), Escape/Close/backdrop exits,
  `inert` set and restored without clobbering the host's own, visible always-active text.
- `widget/test/config.test.ts` — language resolution, so `lang` can only name a language we rendered.
- `backend/.../ColorContrastTest.kt`, `BannerConfigValidatorTest.kt` — the contrast thresholds
  themselves, including a sweep proving the derived button-label colour clears 4.5:1 against every
  possible background.
- `dashboard/test/color-contrast.test.ts` — the customizer's readout agrees with the validator.

## Feedback

If you hit an accessibility barrier in a Complyr banner, tell us at **support@complyr.eu**. Include
the site, the browser and assistive technology, and what you were trying to do. We aim to reply
within five working days.

> **Note for the public-facing version.** Article 13 of the EAA and EN 301 549 clause 9.6 expect a
> published statement naming the responsible entity, the enforcement body and a complaints route.
> Those are the same legal-entity facts blocked on BACKLOG Tier 1 #1 (legal pages), so the public
> statement ships with that slice; this document is its source of truth.

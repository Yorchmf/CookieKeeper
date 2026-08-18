# CookieKeeper — Brand & Messaging Guide

The single source of truth for how CookieKeeper looks, sounds, and describes itself.
Everything on the public site — every headline, feature card, FAQ answer, and
compliance claim — must trace back to this file. If a claim isn't here (or in
`docs/ARCHITECTURE.md`), don't put it on the page.

---

## 1. Identity

| Field | Value |
|-------|-------|
| **Name** | Complyr |
| **Tagline** | Simple GDPR cookie consent for European businesses |
| **One-liner** | The simplest, most affordable GDPR cookie consent tool for EU small businesses, freelancers, and agencies. |
| **Category** | GDPR / cookie-consent management (CMP) micro-SaaS |

### Positioning

CookieKeeper is the **simplest, most affordable** GDPR cookie consent tool for EU
small businesses, freelancers, and agencies. Enterprise CMPs (Cookiebot, Osano,
OneTrust) are powerful but overbuilt, expensive (€50–500+/mo), and take training
to use. CookieKeeper does the 95% that a small European business actually needs — scan,
banner, policy, consent logs, Consent Mode v2 — for €9–29/mo, with an interface
you understand in 30 seconds.

**We win on:** simplicity, price, EU data residency, and being genuinely nice to use.
**We do not compete on:** enterprise breadth, custom legal consulting, or a free-forever tier.

---

## 2. Target Audience

Primary:
- **EU freelancers** running a portfolio or client sites
- **Small agencies** managing consent across a handful of client domains
- **Small e-commerce** (Shopify/WooCommerce shops) that added a tracker and now need consent
- **Indie SaaS founders** who want compliance handled and forgotten

Shared traits: not lawyers, not privacy engineers, time-poor, price-sensitive,
allergic to enterprise sales calls. They want to paste one line of code and move on.

---

## 3. Tone of Voice

**Friendly, direct, human, slightly witty. Anti-corporate. Speak *to* the user, not *at* them.**

Do:
- Short sentences. Plain words. Second person ("you", "your site").
- A little dry humour where it lands — never forced.
- Confident and reassuring about compliance; we remove anxiety, not add jargon.
- Concrete over abstract ("under 20KB, won't slow your site" > "high performance").

Don't:
- Legalese, buzzwords, or "leverage synergies" corporate filler.
- Fear-mongering about fines as the main hook (mention consequences, don't weaponise them).
- Overpromise. Every claim must be true *today* (see §6). "Coming soon" is fine; lying is not.

**Voice test:** read it aloud. If it sounds like a SaaS press release, rewrite it.
If it sounds like a helpful, slightly clever friend who happens to know GDPR, ship it.

---

## 4. Messaging Pillars (the "why CookieKeeper")

Adapted for the Problem & Solution section — 8 cards, each true to what ships today:

1. 🎨 **Beautiful by default** — "Banners your visitors won't hate. Modern designs, smooth animations, and full customisation — no CSS required."
2. 🌍 **5 EU languages, more coming** — "Auto-detects your visitor's language. English, German, French, Spanish, and Italian today — with more on the way."
3. 🔍 **Automatic scanner** — "We crawl your site and detect every cookie and tracker for you. No manual lists, no guesswork."
4. 📝 **Auto-generated policy** — "A cookie policy written for you, always in sync with the trackers we actually find on your site."
5. 🔗 **Consent Mode v2** — "Google Consent Mode v2 built in. One switch, done — mandatory for EU Google Ads since 2024."
6. 📊 **Audit-ready logs** — "Every consent choice stored for 3 years as tamper-proof evidence. Proof you're compliant when a regulator asks."
7. 🇪🇺 **Hosted in the EU** — "Your visitors' data never leaves Europe. Stored in German data centres — full data sovereignty, no US transfer."
8. ⚡ **Lightning fast** — "Our widget is under 20KB and fully async. It won't slow your site or dent your Core Web Vitals."

---

## 5. FAQ (canonical answers)

Reuse these near-verbatim on the site. Route every string through i18n.

1. **Is Complyr really GDPR compliant?**
   "Yes. Complyr is built specifically for GDPR and ePrivacy compliance. We store
   consent logs for 3 years as audit-ready evidence, support granular per-category
   consent, and generate compliant cookie policies automatically. Your visitors'
   data is hosted entirely within the EU."

2. **How is Complyr different from Cookiebot or Osano?**
   "Two words: simplicity and price. Enterprise tools charge €50–500+/month and
   take training to use. CookieKeeper does 95% of what they do, for a fraction of the
   price, with an interface you'll understand in 30 seconds."

3. **Do I need this if I only use Google Analytics?**
   "Yes. If your site uses Google Analytics, Facebook Pixel, YouTube embeds, or any
   tracking cookie, EU law requires prior consent. CookieKeeper handles this properly,
   with Google Consent Mode v2 built in."

4. **What is Google Consent Mode v2?**
   "It's Google's system for respecting user consent while still enabling analytics.
   Since March 2024 it's mandatory for Google Ads users in the EU. CookieKeeper sets it up
   automatically — defaulting every signal to 'denied' until your visitor chooses."

5. **Where is my data stored?**
   "All visitor and consent data is stored in EU data centres in Germany. We use
   EU-based infrastructure exclusively — no US data transfer, no Schrems II headache."

6. **Can I cancel anytime?**
   "Yes. Cancel with one click — no contracts, no cancellation fees, no hoops. You
   keep access until the end of your billing period."

7. **Does it work with WordPress, Shopify, Wix, etc.?**
   "Yes. CookieKeeper works on any website — just paste one line of code before `</head>`.
   A dedicated WordPress plugin is on the way."

8. **Do you offer a free plan?**
   "We offer a 14-day free trial, no credit card required. After that, plans start
   at €9/month. We keep prices low so we can invest in the product instead of
   subsidising a permanent free tier."

---

## 6. Facts & Claims — verified source of truth

**Do not publish a compliance or capability claim that contradicts this table.**
These are reconciled against `CLAUDE.md` and `docs/ARCHITECTURE.md` as of v1.0.0.

| Claim | Truth (what ships) | Notes |
|-------|--------------------|-------|
| Data residency | **EU — Germany (Hetzner, Falkenstein/Nuremberg)** | Never say "Amsterdam/Netherlands" — infra is German. |
| Consent-log retention | **3 years** (36-month rolling default) | Not "2 years". CLAUDE.md ceiling is 5y; default shipped is 36 months. |
| Languages | **5 today: EN, DE, FR, ES, IT** — "more coming" | Not "12". Overclaiming here breaks CLAUDE.md #6. |
| Widget size | **Under 20KB gzipped** (actually ~6.7KB today) | Safe to say "under 20KB"; "under 7KB" is even stronger if desired. |
| Consent Mode v2 | **Built in, defaults to `denied`** before any vendor script | Core differentiator, fully shipped. |
| Pricing | **Starter €9 · Pro €19 · Business €29** /mo (annual ~10× monthly) | Keep in sync with billing (`billing/Plan.kt`). |
| Trial | **14 days, no credit card** | Consent ingestion capped during trial. |
| Scanner | Playwright crawl, homepage + up to 10 same-origin pages | Detects cookies, trackers, localStorage, third-party hosts. |
| Policy generator | **Template-based (not LLM)**, versioned, 5 languages | Deterministic + auditable = a selling point, not a limitation. |
| Consent logs | **Append-only, tamper-proof** (DB-enforced), no raw IPs | "Audit-ready evidence" is literally true. |
| WordPress plugin | **Not yet — "coming soon"** | Do not imply it exists today. |
| Company domicile / lead DPA | **TBD — fill in before launch** | ⚠️ Do NOT assert "Dutch AP" or any specific supervisory authority until the operating entity is confirmed. Leave out rather than guess. |

---

## 7. Visual Direction

**Style:** clean, editorial, Swiss-influenced — generous whitespace, strong type
hierarchy, one confident accent. Simple enough for a non-technical shop owner,
credible enough for a developer. **Not** a gradient-blob-hero template.

- **Theme:** light + dark, driven by **system preference** (`next-themes`,
  `defaultTheme="system"`), with an optional manual toggle in nav/footer.
- **Accent colour:** a single trustworthy accent (proposed: a calm teal-green —
  "consent = go", differentiates from the sea of SaaS blue — or a considered EU-blue;
  final choice is a design decision). Everything else stays on the neutral scale
  already in `globals.css`. **One accent, used semantically**, not decoratively.
- **Typography:** one strong display/heading face + a clean readable body
  (max two families, `font-display: swap`, self-hosted + subset). Big scale contrast
  between hero and body.
- **Depth:** soft surfaces, subtle borders, restrained shadows, tasteful overlap —
  layering over flatness, but never heavy.
- **Motion:** compositor-friendly only (`transform`/`opacity`), subtle scroll-reveal
  and hover states. **Always respect `prefers-reduced-motion`.**
- **Imagery:** favour real product UI (banner previews, scan results) over stock.
  The product *is* the hero image.

Design tokens live in `globals.css` (`@theme` + `:root`/`.dark`). Never hardcode a
hex; add a token. See the web design-quality rules for the anti-template checklist.

---

## 8. Naming & Copy Conventions

- Product name is always **CookieKeeper** (capital C, no accent, no "the").
- Prices: **€9/mo**, **€19/mo**, **€29/mo** (Euro symbol, lowercase "mo").
- "GDPR", "ePrivacy", "Consent Mode v2", "Core Web Vitals" — exact casing.
- Say "cookie banner" / "consent banner", not "cookie popup" or "cookie wall".
- Refer to the buyer as "you"; refer to their audience as "your visitors".

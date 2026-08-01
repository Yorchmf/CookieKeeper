-- === policy_settings: per-site policy inputs + stable hosted-page id ==========
-- The cookie-policy generator (docs/ARCHITECTURE.md §4.5) fills a template from two
-- sources: the site's latest scan results (already in `scans`/`scan_cookies`) and the
-- customer's business details, which live here. One row per site.
--
-- `public_id` is the STABLE, opaque address of the hosted policy page
-- (`https://app.complyr.eu/p/{public_id}`). It is intentionally decoupled from the
-- per-version `policies` rows so the shared, customer-published URL never changes when a
-- new policy version is generated (a new scan → republish bumps `policies.version`, not
-- this id). Enumeration is a non-threat — the policy page is public by design — but a
-- random v4 uuid keeps it non-sequential and unguessable rather than leaking site ids.
--
-- The `policies` table itself (versioned per site × language HTML) already exists from the
-- V1 baseline and is unchanged here.

CREATE TABLE policy_settings (
    site_id    uuid        NOT NULL,
    -- Stable public URL id for the hosted page; generated once, never rotated on republish.
    public_id  uuid        NOT NULL DEFAULT gen_random_uuid(),
    -- Business details that fill the template (company name, contact email, address, url).
    -- Attacker-controlled only insofar as the authenticated site owner types them; the app
    -- validates + HTML-escapes on render, but keep it a bounded jsonb document all the same.
    details    jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_policy_settings PRIMARY KEY (site_id),
    -- One stable id per site; also the hosted-page read-path lookup index.
    CONSTRAINT uq_policy_settings_public_id UNIQUE (public_id),
    -- Settings die with the site (mirrors banner_configs / policies FK behaviour).
    CONSTRAINT fk_policy_settings_site
        FOREIGN KEY (site_id) REFERENCES sites (id) ON DELETE CASCADE
);

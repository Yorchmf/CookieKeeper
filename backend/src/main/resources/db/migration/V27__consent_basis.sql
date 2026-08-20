-- Consent basis: the version of "what the visitor was actually consenting to" (BACKLOG #18).
--
-- The version inside the consent cookie has always been the widget's PAYLOAD SCHEMA version, not a
-- statement about the site's tracking. So a site that adds a marketing tracker in March keeps serving
-- consents collected in January, against a cookie list that never mentioned it — consent that is not
-- valid for the new purpose. These columns give the widget something to compare against.
--
-- `consent_basis_version` is stamped into the cookie at the moment of choice and served on the widget
-- config; the widget re-prompts only when the served version is strictly HIGHER than the one it stored.
-- It is bumped ONLY when a consent-decidable category is newly in use (statistics/preferences/marketing
-- appearing in a scan's classified findings, or the marketing tracker count going above zero) — never
-- for a colour edit, a new cookie name inside a category the visitor already decided, or a banner
-- publish. `necessary` is excluded: it cannot be rejected, so it can never invalidate a prior consent.
--
-- `consent_basis_categories` is NULL until the first completed scan records one, which is what keeps
-- this from firing a re-prompt wave across every existing site on deploy: the first observation SEEDS
-- the basis at version 1 without bumping it. NULL therefore means "never recorded" and an empty string
-- means "recorded, nothing decidable in use" — the two are deliberately different.
--
-- The stored set only ever GROWS (union with each new observation). A tracker that disappears and comes
-- back must not cost visitors a second re-prompt for a purpose they already answered.
--
-- `sites` is small and ADD COLUMN with a constant DEFAULT is metadata-only on Postgres 11+, but bound
-- the brief ACCESS EXCLUSIVE lock anyway (mirrors V20/V21) so this fails fast instead of queuing.
SET LOCAL lock_timeout = '3s';

ALTER TABLE sites
    ADD COLUMN consent_basis_version integer NOT NULL DEFAULT 1,
    -- Comma-separated category keys, sorted. A tiny closed set (at most three values) read once per
    -- completed scan and never queried on — a text column beats a join table or an array type here.
    ADD COLUMN consent_basis_categories text,
    -- When the last bump happened and which categories caused it, so the dashboard can tell the
    -- customer *why* their visitors were asked again — and warn that the re-prompt wave steps the
    -- banner-impression denominators (the analytics change is ours, not their traffic's).
    ADD COLUMN consent_basis_changed_at timestamptz,
    ADD COLUMN consent_basis_added text;

ALTER TABLE sites
    ADD CONSTRAINT ck_sites_consent_basis_version CHECK (consent_basis_version >= 1);

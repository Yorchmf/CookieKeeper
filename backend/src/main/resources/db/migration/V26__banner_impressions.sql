-- === banner_impressions: how often the consent banner was shown ============
-- Track 4 Slice D. The dashboard already reports how visitors *decided* (accept/
-- reject/custom, from the append-only consent_events log), but an opt-in count with
-- no denominator can't answer "of everyone who SAW the banner, what fraction
-- interacted?". This table is that denominator: the widget fires a fire-and-forget
-- beacon once per page-load when it renders the banner, and ingestion bumps a bare
-- per-site, per-day counter here. Interaction rate = consent events / impressions.
--
-- NOT audit evidence (unlike consent_events, CLAUDE.md #3): this is a disposable
-- aggregate counter, so — deliberately unlike the append-only, partitioned consent
-- log — rows here are UPSERTed (ON CONFLICT DO UPDATE) on the hot path and DELETE-
-- pruned by a retention reaper. There is no append-only trigger and no partitioning.
--
-- ZERO personal data by construction (CLAUDE.md #4): a row is (site_id, day, count).
-- The beacon carries only the site key; ingestion persists no IP, no visitor id, no
-- user-agent, no timestamp finer than the UTC calendar day. The client IP is used
-- only as an ephemeral in-memory rate-limit bucket key (RateLimitFilter), never
-- stored. So there is nothing here to erase for an Art. 17 request and nothing that
-- can leak — which is also why it can be freely UPSERTed and pruned.
--
-- GRAIN: one row per (site_id, day). `day` is the UTC calendar day the impression
-- was counted (LocalDate at ZoneOffset.UTC in the service), matching how the consent
-- trend buckets created_at (date_trunc('day', ... AT TIME ZONE 'UTC')) so the rate's
-- numerator and denominator share one day definition. The composite PRIMARY KEY is
-- the UPSERT conflict target: the first beacon of a (site, day) inserts count=1, and
-- every later one does `count = count + 1` against the same row.

CREATE TABLE banner_impressions (
    -- The site whose banner was shown. FK to sites, ON DELETE CASCADE: an aggregate
    -- counter carries no independent value once its site is gone, unlike consent_events
    -- (RESTRICT — audit evidence must outlive a careless delete). Erasure of a site's
    -- impression counters is a plain cascade, since there is no PII to account for.
    site_id uuid   NOT NULL,
    -- UTC calendar day the impressions were counted (see grain note above). `date`, not
    -- `timestamptz`: the counter is day-bucketed, so a finer type would imply a precision
    -- the data does not have.
    day     date   NOT NULL,
    -- Monotonically incremented per beacon. bigint: a busy site over a retention window
    -- can accumulate far past int range. DEFAULT 0 documents the identity; the UPSERT
    -- always inserts 1 and updates count+1, so a row is never actually left at 0.
    count   bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_banner_impressions PRIMARY KEY (site_id, day),
    CONSTRAINT fk_banner_impressions_site FOREIGN KEY (site_id) REFERENCES sites (id) ON DELETE CASCADE
);

-- Supports the retention reaper's `DELETE ... WHERE day < :cutoff ORDER BY day LIMIT`
-- batch scan. The PRIMARY KEY leads with site_id, so it cannot serve a day-only range
-- scan; this day index makes the prune (and any future all-sites-by-day read) cheap.
CREATE INDEX idx_banner_impressions_day ON banner_impressions (day);

COMMENT ON TABLE banner_impressions IS
    'Per-site, per-day banner-impression counters (Track 4 Slice D). Disposable aggregate, not audit evidence: UPSERTed on ingest, DELETE-pruned on retention. Stores no personal data.';

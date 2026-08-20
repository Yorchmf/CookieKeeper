-- Post-install blocking verification (BACKLOG #19).
--
-- The scan already reports what a site drops before consent, and the widget already blocks the scripts
-- the site owner TAGGED. Nothing until now joined those two facts: a customer can install Complyr,
-- never tag their Google Analytics snippet, and keep firing it before consent — while a banner on the
-- same page claims otherwise. That is worse than having no banner at all, because the banner is a
-- written promise the site does not honour.
--
-- Our crawl is already a before-consent crawl, so it observes exactly the state a first-time visitor
-- gets. If the widget is present and a known vendor still fires, that vendor is provably unblocked.
--
-- Everything recorded here is either a boolean, a count, or a key from our OWN curated dataset
-- (`resources/trackers/trackers.json`) — never an observed request host or script URL. The crawled page
-- is attacker-influenced (§4 no attacker-controlled data at rest / in logs), so the browser-side probe
-- returns only booleans and an int, and host matching is reduced to a dataset key before it is stored.
--
-- All four `scans` columns are nullable on purpose: NULL means "this scan predates the probe" (or the
-- scan never completed), which the read layer renders as *unknown* rather than as a passing or failing
-- verdict. Backfilling a verdict we never measured would be a fabricated compliance claim.
--
-- Both tables are small and ADD COLUMN with no default is metadata-only, but bound the brief
-- ACCESS EXCLUSIVE lock anyway (mirrors V20/V21/V27) so this fails fast instead of queuing.
SET LOCAL lock_timeout = '3s';

ALTER TABLE scans
    -- Whether the Complyr embed was found on any crawled page (a `script[data-complyr]` tag, or the
    -- `window.Complyr` global for installs injected by a tag manager).
    ADD COLUMN widget_detected boolean,
    -- Whether the embed found carries THIS site's key. False with widget_detected true is the
    -- copy-paste failure mode of a multi-site account: the banner renders but reports to another site,
    -- so this site's consent log stays empty and its blocking never matches its own configuration.
    ADD COLUMN widget_site_key_matched boolean,
    -- How many `script[type="text/plain"][data-complyr-category]` placeholders the page carries. It is
    -- what separates "never tagged anything" from "tagged some scripts and missed this one" — the two
    -- need different remediation copy.
    ADD COLUMN blocked_script_count integer,
    -- Comma-separated tracker-dataset DOMAIN KEYS (not observed hosts) for the known third-party
    -- vendors that fired during the before-consent crawl, sorted, capped by the writer. A tiny list
    -- read once per scan detail view and never queried on — a text column beats a child table here,
    -- the same trade-off `sites.consent_basis_categories` makes (V27).
    ADD COLUMN observed_trackers text;

ALTER TABLE sites
    -- When the site first entered an unresolved blocking state (widget installed AND still firing
    -- known trackers, or installed with the wrong site key) and stayed there. Cleared the moment a
    -- scan comes back clean, so the "still unfixed after N days" nudge measures the CURRENT streak
    -- rather than the first time we ever saw a problem.
    ADD COLUMN blocking_alert_since timestamptz,
    -- When we last nudged the owner about it, so a weekly re-scan cadence cannot turn a single
    -- unresolved finding into a weekly complaint.
    ADD COLUMN blocking_alert_notified_at timestamptz;

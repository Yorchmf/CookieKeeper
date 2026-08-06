-- === sites: how the customer proved they control the domain (ADR-17) ===
-- `verified_at` has existed since the baseline but nothing ever set it, so every authenticated scan
-- dead-ended on a DOMAIN_NOT_VERIFIED failure. ADR-17 makes verification a real self-serve step and
-- re-points what it gates: crawl DEPTH (QUICK vs FULL) and PUBLISHING the hosted policy page — not
-- permission to crawl, which the anonymous funnel already does for arbitrary domains behind the same
-- [com.complyr.scan.ScanTargetValidator].
--
-- `verification_method` records WHICH proof was accepted. It is not speculative generality: the state
-- transition is un-backfillable (once verified we never re-derive how), it is the provenance half of
-- an audit-relevant flag, and it drives the "verified via your installed widget" copy in the dashboard.
-- Same rationale as `scans.trigger_source` (V7). See [com.complyr.site.SiteVerificationService].
--
-- The paired CHECK keeps the two columns from drifting: a row is either fully unverified or fully
-- verified with a known method. NOTE for future writers — [com.complyr.site.SiteService.changeDomain]
-- resets ownership proof on a domain change and MUST clear BOTH columns or this constraint rejects it.

-- Self-healing pre-flight. The paired CHECK is validated against existing rows, so a single row with
-- `verified_at` set would abort this migration mid-deploy. No application code has ever written that
-- column, so any non-null value came from a console and proves nothing — clearing it fails closed
-- (the site simply re-verifies) instead of leaving the deploy to a manual `SELECT count(*)` on each
-- environment. Expected to affect 0 rows everywhere.
UPDATE sites SET verified_at = NULL WHERE verified_at IS NOT NULL;

ALTER TABLE sites
    ADD COLUMN verification_method text
        CONSTRAINT ck_sites_verification_method CHECK (verification_method IN ('snippet', 'dns_txt')),
    ADD CONSTRAINT ck_sites_verification_method_pairs
        CHECK ((verified_at IS NULL) = (verification_method IS NULL));

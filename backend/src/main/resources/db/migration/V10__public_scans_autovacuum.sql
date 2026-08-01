-- === public_scans / public_scan_cookies: per-table autovacuum tuning =========
-- V9 shipped the tables and the retention index but no storage params; this migration
-- delivers the same churn tuning V6 gave consent_idempotency, because both tables here
-- exhibit the same insert-right / delete-left btree churn the retention reaper drives —
-- and public_scans carries EXTRA churn consent_idempotency does not.
--
-- WHY public_scans NEEDS IT: rows are inserted right (gen_random_uuid PK, monotonic
-- created_at / expires_at) and PublicScanReaper DELETEs the oldest-expiring rows daily
-- (left edge of idx_public_scans_expires_at). On top of that — unlike the insert-once/
-- delete-once consent_idempotency — every scan takes several full-row UPDATEs via
-- copy(...)+save (queued -> running -> done/failed, plus the email-gate write), each
-- leaving a dead heap tuple and dead entries in uq_public_scans_token,
-- idx_public_scans_domain_created_at and idx_public_scans_expires_at. Default autovacuum
-- (scale_factor 0.2 = vacuum only after 20% dead) lets that accumulate between the once-
-- daily prunes.
--
-- WHY public_scan_cookies NEEDS IT MORE: it has no reaper of its own — its rows are
-- removed only by the ON DELETE CASCADE when a parent scan is pruned. One prune batch
-- deletes up to 500 parents, each cascading up to max-cookies (500) children, so a single
-- batch can leave ~250k dead tuples here — the largest single bloat source in this slice.
-- Left at the 20% default it would carry a large dead-tuple backlog between prunes.
--
-- WHAT WE SET (table-level storage params override cluster defaults for these tables only;
-- catalog-only change, no table rewrite) — same rationale as V6:
--   * autovacuum_vacuum_scale_factor 0.02 + autovacuum_vacuum_threshold 500 — vacuum once
--       ~2% of rows are dead (with a floor so a near-empty table isn't vacuumed for a
--       handful of dead rows), so each prune's dead tuples are reclaimed promptly.
--   * autovacuum_vacuum_insert_scale_factor 0.05 + autovacuum_vacuum_insert_threshold 500
--       — growth is insert-driven; insert-triggered vacuum (PG13+) keeps the visibility
--       map and indexes tidy between the delete-driven vacuums. Floor lowered from the
--       1000 default to match the delete side so it stays a real independent trigger.
--   * autovacuum_analyze_scale_factor 0.02 — the prune's `expires_at < :cutoff` scan and
--       the domain-cache lookup lean on fresh planner stats; the boundary shifts every
--       prune, so keep ANALYZE eager to avoid stale row-count/boundary estimates.
--
-- As V9/V6 note, sustained high volume may still warrant periodic REINDEX/pg_repack; this
-- tuning bounds the steady-state case so that stays rare.

ALTER TABLE public_scans SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_threshold = 500,
    autovacuum_vacuum_insert_scale_factor = 0.05,
    autovacuum_vacuum_insert_threshold = 500,
    autovacuum_analyze_scale_factor = 0.02
);

ALTER TABLE public_scan_cookies SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_threshold = 500,
    autovacuum_vacuum_insert_scale_factor = 0.05,
    autovacuum_vacuum_insert_threshold = 500,
    autovacuum_analyze_scale_factor = 0.02
);

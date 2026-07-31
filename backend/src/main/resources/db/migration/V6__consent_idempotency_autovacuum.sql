-- === consent_idempotency: per-table autovacuum tuning ======================
-- V5 documented the bloat tradeoff for this table but shipped no storage params
-- to counter it; this migration delivers the tuning that comment promised.
--
-- WHY THIS TABLE NEEDS IT: consent_idempotency takes one INSERT per consent event
-- (right-appending on a UUIDv7 PK, monotonic created_at) and the reaper DELETEs the
-- OLDEST keys daily (left-edge of the created_at index). That insert-right / delete-
-- left churn is exactly the pattern that bloats a btree: the deletes leave dead heap
-- tuples and dead index entries at the low end while inserts keep extending the high
-- end. Postgres' default autovacuum thresholds (scale_factor 0.2 = vacuum only after
-- 20% of the table is dead) let that dead weight accumulate between the once-daily
-- prune runs, growing the heap and both indexes.
--
-- WHAT WE SET (table-level storage params override the cluster defaults for this
-- table only; a catalog-only change, no table rewrite):
--   * autovacuum_vacuum_scale_factor 0.02  — vacuum once ~2% of rows are dead (not
--       20%), so each day's pruned tuples are reclaimed promptly instead of lingering.
--   * autovacuum_vacuum_threshold 500      — floor so a nearly-empty table isn't
--       vacuumed for a trivial handful of dead rows; churn must clear a real bar first.
--   * autovacuum_vacuum_insert_scale_factor 0.05 + autovacuum_vacuum_insert_threshold
--       500 — this table's growth is insert-driven; insert-triggered vacuum (PG13+)
--       keeps the visibility map and index tidy between the delete-driven vacuums, not
--       just at antiwraparound time. The threshold floor is lowered from its 1000
--       default to match the delete-side floor, so this stays a real independent
--       trigger rather than one the dead-tuple vacuum always pre-empts.
--   * autovacuum_analyze_scale_factor 0.02 — the prune's `created_at < :cutoff` scan
--       leans on fresh planner stats; the min(created_at) shifts every prune, so keep
--       ANALYZE eager to avoid stale row-count/boundary estimates.
--
-- Bloat may still warrant periodic REINDEX/pg_repack under sustained high volume
-- (as V5 notes); this tuning bounds the steady-state case so that stays rare.

ALTER TABLE consent_idempotency SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_vacuum_threshold = 500,
    autovacuum_vacuum_insert_scale_factor = 0.05,
    autovacuum_vacuum_insert_threshold = 500,
    autovacuum_analyze_scale_factor = 0.02
);

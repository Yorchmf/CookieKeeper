-- Scan-queue priority (roadmap 0.5, ADR-4 queue).
--
-- Business-plan sites get their scans served ahead of everyone else's. Priority is a
-- per-job property (resolved from the site owner's entitlement at enqueue time and frozen
-- onto the row) rather than a live join in the claim path, so the hot `FOR UPDATE SKIP
-- LOCKED` claim stays a single-table index scan with no billing lookup on the critical path.
--
-- Higher number = served first. Default 0 is the normal (non-priority) tier, so every
-- existing pending/running job keeps its current relative order under the new sort.
-- `integer` (not smallint) to match the JPA entity's Kotlin `Int` mapping — Hibernate schema
-- validation rejects an int2/int4 mismatch. The extra two bytes per row are immaterial here.
--
-- `SET LOCAL` up front bounds EVERY lock this migration takes, not just the index rebuild: the
-- `ADD COLUMN` below needs ACCESS EXCLUSIVE on the hot `jobs` table too, and an in-flight claim
-- (`FOR UPDATE SKIP LOCKED`) held across a deploy could otherwise queue it — and all enqueue/claim
-- traffic behind it — indefinitely. Bounded to 3s, the migration fails fast instead. (Effective
-- from this statement onward within the Flyway per-migration transaction, hence before the DDL.)
SET LOCAL lock_timeout = '3s';

ALTER TABLE jobs
    ADD COLUMN priority integer NOT NULL DEFAULT 0;

-- Rebuild the claim index to lead with priority so the ORDER BY (priority DESC, available_at)
-- is served directly from the index — the planner walks high-priority-first, oldest-first
-- within a tier, and the LIMIT 1 claim stops at the first live row. Partial on the live
-- states keeps it small (accumulating done/failed rows are excluded), same as before.
--
-- NOTE on fairness: the sort is strict priority with no aging, so sustained high-priority
-- (Business) volume can starve normal-tier scheduled scans against the single crawl worker.
-- Acceptable at MVP scale; revisit with a bounded-aging term if Business volume grows.
--
-- The DROP/CREATE takes a brief exclusive lock on `jobs` (not CONCURRENTLY — Flyway runs each
-- migration in a transaction, which CONCURRENTLY forbids); the `SET LOCAL` above bounds it, and
-- `IF EXISTS` keeps the drop idempotent if the index name ever drifted.
DROP INDEX IF EXISTS idx_jobs_claim;
CREATE INDEX idx_jobs_claim ON jobs (type, priority DESC, available_at)
    WHERE status IN ('pending', 'running');

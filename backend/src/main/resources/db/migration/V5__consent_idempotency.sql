-- === consent_idempotency: de-dupe replayed widget consent retries ===========
-- The widget delivers consent events fire-and-forget; a send it can observe
-- failing is queued in localStorage and replayed on the next page load (see
-- widget/src/api.ts). Without a dedupe gate, every replay writes a SECOND row to
-- the append-only consent_events log — permanent duplicate audit evidence that
-- can never be corrected (V4 makes the table immutable).
--
-- The widget generates a stable UUID once per consent decision (crypto.randomUUID)
-- and replays it verbatim on each retry. This table's PRIMARY KEY is the dedupe
-- gate: the service claims the key with `INSERT ... ON CONFLICT DO NOTHING` inside
-- the same transaction as the consent insert. A losing claim (0 rows) means the
-- event is already recorded, so the consent write is skipped. Because both writes
-- share one transaction, a failed consent insert rolls the claim back too — a
-- transient failure never leaves a poisoned key that blocks a legitimate retry.
--
-- WHY A SEPARATE TABLE (not a unique index on consent_events):
--   consent_events is RANGE-partitioned on created_at, and Postgres requires the
--   partition key to be part of every unique constraint. created_at is server-
--   stamped fresh on each retry, so it cannot be part of a dedupe key — a unique
--   index there could never match two replays. This side table carries the unique
--   constraint on the client-stable key instead, leaving the partitioned,
--   append-only audit table physically untouched.
--
-- NOT audit evidence: unlike consent_events, this is disposable dedupe bookkeeping.
-- It carries no append-only trigger and rows ARE deletable — a retention reaper
-- prunes keys older than the plausible replay window (the created_at index below
-- supports that scan). Keys only need to outlive a pending retry (widget localStorage
-- retries resolve within days), not the 5-year consent retention.
--
-- BLOAT TRADEOFF: because this table cannot be partitioned on created_at (that would
-- force created_at into the unique constraint and defeat the global dedupe), retention
-- is row DELETE, not the cheap DROP PARTITION used for consent_events. Left-edge deletes
-- against a right-appending index leave heap+index dead tuples, so the reaper MUST keep
-- the window short (bounding absolute table size), autovacuum should be tuned for this
-- table's churn, and periodic REINDEX/pg_repack may be needed if bloat is observed.

CREATE TABLE consent_idempotency (
    -- The client-generated idempotency key (UUIDv7 from the widget: time-ordered, so PK-index
    -- inserts stay sequential on this hot path — same locality rationale as consent_events' v7
    -- id, see V3). uuid-typed so a malformed value fails at the DB edge; the service also
    -- parse-guards before insert.
    event_key  uuid        NOT NULL,
    -- Claim time, for the retention prune only — NEVER the consent audit timestamp
    -- (that stays server-stamped on consent_events.created_at).
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_consent_idempotency PRIMARY KEY (event_key)
);

-- Supports the future retention prune (`DELETE ... WHERE created_at < :cutoff`).
CREATE INDEX idx_consent_idempotency_created_at ON consent_idempotency (created_at);

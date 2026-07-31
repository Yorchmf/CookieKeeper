-- === scan queue: lifecycle columns on scans + claim support on jobs =========
-- W4 slice 1 turns the baseline (V1) `scans` result table and generic `jobs`
-- queue into a working Postgres-as-queue (ADR-4): scans are enqueued as `jobs`
-- rows of type 'scan' and claimed with FOR UPDATE SKIP LOCKED. This migration
-- only adds the columns/indexes that mechanism needs; it never rewrites data.
--
-- WHY TWO TABLES: `scans` is the durable, user-visible RESULT record (its
-- `status` is the scan lifecycle queued -> running -> done/failed, shown in the
-- dashboard). `jobs` is disposable QUEUE bookkeeping (its `status` is the
-- delivery state). Keeping them apart lets a scan stay visible as "queued" the
-- instant it is enqueued, and lets the queue carry retry/visibility state the
-- result record should not.

-- --- scans: make it listable-while-queued and carry provenance ---------------
ALTER TABLE scans
    -- Enqueue provenance (see com.complyr.scan.ScanTrigger). Defaulted so the ALTER
    -- is valid against any pre-existing rows; new rows always set it explicitly.
    -- CHECK mirrors the ScanTrigger enum so a bad value fails at write, not read.
    ADD COLUMN trigger_source text        NOT NULL DEFAULT 'site_added'
        CHECK (trigger_source IN ('site_added', 'manual', 'scheduled')),
    -- A queued scan has no started_at yet, so the existing (site_id, started_at)
    -- index cannot order the pending ones. created_at gives a stable enqueue order
    -- for "latest scans" listings that include not-yet-started scans.
    ADD COLUMN created_at     timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at     timestamptz NOT NULL DEFAULT now();

-- Newest-first listing per site, including queued scans (started_at still null).
CREATE INDEX idx_scans_site_id_created_at ON scans (site_id, created_at DESC);

-- --- jobs: retry accounting + a claim-shaped index ---------------------------
ALTER TABLE jobs
    -- Dead-letter threshold: once attempts reaches this, a failing job stops
    -- retrying and is parked as 'failed' (the scan is marked failed too).
    ADD COLUMN max_attempts integer     NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    -- Not-before time. A fresh job is available immediately; a retry pushes this
    -- into the future for backoff. The claim query orders by it.
    ADD COLUMN available_at timestamptz NOT NULL DEFAULT now(),
    -- Last failure reason, for operators inspecting a parked/retrying job.
    ADD COLUMN last_error   text,
    ADD COLUMN updated_at   timestamptz NOT NULL DEFAULT now();

-- The claim path selects the oldest due job of a type that is either pending-and-
-- available or a crashed 'running' job whose visibility lock (locked_until) has
-- expired. A partial index on the live states keeps it small (done/failed rows,
-- which accumulate, are excluded) and serves the ORDER BY available_at directly.
CREATE INDEX idx_jobs_claim ON jobs (type, available_at)
    WHERE status IN ('pending', 'running');

-- === consent_events: DB-enforced append-only ================================
-- CLAUDE.md constraint #3: consent_events is append-only audit evidence. Rows
-- are NEVER updated or deleted by application code — the only sanctioned removal
-- is time-based retention, done with DROP PARTITION (see V3), not row DELETEs.
--
-- Application-layer discipline alone (the repository extends the bare Repository
-- marker, so no delete/save-with-update method is inherited) is not enough for
-- audit evidence: a stray query, a future refactor, or a psql session could still
-- mutate history. We enforce the invariant in the database itself.
--
-- WHY A TRIGGER, NOT `REVOKE UPDATE, DELETE`:
--   REVOKE is bypassed by the table owner, and our application connects as the
--   schema owner (single-role datasource — see docs/ARCHITECTURE.md). Making
--   REVOKE effective would require a separate non-owner runtime role, a new
--   secret, and a Flyway/runtime datasource split — and it would be untestable
--   (Testcontainers connects as owner). A BEFORE ROW trigger that RAISEs applies
--   to EVERY role including the owner, so the guarantee holds regardless of how
--   the app authenticates, and it is exercised by the integration tests.
--
-- WHY THIS DOES NOT BLOCK RETENTION:
--   Retention drops whole monthly partitions. DROP TABLE / DETACH PARTITION is
--   DDL and does not fire row-level DELETE triggers, so partition-based retention
--   keeps working. Individual-row deletes are intentionally impossible — targeted
--   erasure is not needed here (no direct PII: IPs are hashed, visitor ids are
--   opaque UUIDs, user agents are trimmed) and would undermine the audit trail
--   that GDPR Art. 7(1) requires us to be able to produce.

CREATE OR REPLACE FUNCTION consent_events_reject_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'consent_events is append-only: % is not permitted (retention uses DROP PARTITION)', TG_OP
        USING ERRCODE = 'raise_exception',
              HINT = 'Time-based retention drops whole partitions; individual rows are immutable audit evidence.';
END;
$$;

-- Row-level trigger on the partitioned parent cascades to all current and future
-- partitions automatically (Postgres propagates parent row triggers to children).
CREATE TRIGGER trg_consent_events_append_only
    BEFORE UPDATE OR DELETE ON consent_events
    FOR EACH ROW
    EXECUTE FUNCTION consent_events_reject_mutation();

-- TRUNCATE is NOT a row-level event, so the trigger above does not fire on it — a
-- stray `TRUNCATE consent_events` (or a child partition) would wipe audit evidence
-- silently. A statement-level BEFORE TRUNCATE guard closes that hole. The same
-- reject function works unchanged (TG_OP reports 'TRUNCATE'). Retention is still
-- DROP PARTITION (DDL), which fires neither trigger.
-- Caveat: statement-level TRUNCATE triggers are NOT propagated to child partitions,
-- so this guards `TRUNCATE consent_events` (the accidental full-table footgun) but not
-- a direct `TRUNCATE consent_events_2026_07`. Truncating a specific partition is a
-- deliberate DBA act in the same residual-trust bucket as DROP PARTITION; row-level
-- UPDATE/DELETE — the paths reachable from application code — are fully covered above.
CREATE TRIGGER trg_consent_events_no_truncate
    BEFORE TRUNCATE ON consent_events
    FOR EACH STATEMENT
    EXECUTE FUNCTION consent_events_reject_mutation();

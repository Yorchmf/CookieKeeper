-- === billing: webhook-handler hardening (payload PII lifecycle + out-of-order guard) ===
-- Slice 3 turns the `stripe_events` inbox (V12) live. Three changes make the write path
-- exemplary before any real (PII-bearing) webhook body is stored:
--
--   1. `payload` becomes NULLable. It is stored verbatim only long enough to apply an event;
--      the handler NULLs it the moment it sets `processed_at` (redact-on-process). Real Stripe
--      events carry customer PII (email, name, billing address), and dedupe/audit only need
--      `stripe_event_id` + `type` + timestamps — not the body (CLAUDE.md #4, GDPR Art.5(1)(e)).
--      Un-applied (failed) events keep their body so a retry / debugging still has it.
--
--   2. A `received_at` index so the retention reaper's "delete rows older than the window" scan
--      is cheap (mirrors the consent-idempotency / public-scans reaper indexes). The reaper is
--      the only thing that removes rows — bounding how long even a redacted row lingers.
--
--   3. `subscriptions.stripe_event_at` — the Stripe `created` timestamp of the last subscription
--      event we applied. Stripe delivers at-least-once and NOT strictly in order, so a late
--      `customer.subscription.updated` could otherwise clobber a newer `.deleted`. The handler
--      only applies an event whose `created` is newer than this watermark.

-- 1. Redact-on-process needs the column to accept NULL.
ALTER TABLE stripe_events
    ALTER COLUMN payload DROP NOT NULL;

-- 2. Retention reaper support: prune by age cheaply.
CREATE INDEX idx_stripe_events_received_at ON stripe_events (received_at);

-- 3. Out-of-order guard watermark (nullable: rows predating this column simply have no watermark
--    and accept the next event, which then sets it).
ALTER TABLE subscriptions
    ADD COLUMN stripe_event_at timestamptz;

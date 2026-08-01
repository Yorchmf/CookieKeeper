-- === billing: idempotent Stripe webhook log + subscription audit/one-per-user ===
-- Week 6a wires Stripe (docs/ARCHITECTURE.md §10). The `subscriptions` table already
-- exists from the V1 baseline (id, user_id, stripe_customer_id, stripe_sub_id, plan,
-- status, period_end). This migration adds the pieces the webhook handler needs:
--
--   1. `stripe_events` — a verbatim, idempotent log of every webhook Stripe delivers.
--      Stripe retries deliveries and can send the same event more than once (at-least-once
--      delivery), so the handler must dedupe. The unique `stripe_event_id` is the dedupe
--      key: an INSERT ... ON CONFLICT DO NOTHING claims an event exactly once. `payload`
--      stores the raw request body byte-for-byte (as text, not jsonb) so it can be re-read
--      or signature-re-verified later without Postgres re-serializing/normalizing it.
--
--   2. subscription audit timestamps + one-row-per-user. The handler upserts a single
--      subscription per user on `checkout.session.completed` / `customer.subscription.*`,
--      so a per-user UNIQUE both enforces that invariant and gives the upsert its conflict
--      target. The non-unique V1 `idx_subscriptions_user_id` is redundant once a unique
--      index on the same column exists, so it is dropped.

CREATE TABLE stripe_events (
    id              uuid        NOT NULL DEFAULT gen_random_uuid(),
    -- Stripe's own event id (evt_...); the idempotency/dedupe key.
    stripe_event_id text        NOT NULL,
    -- Event type (e.g. customer.subscription.updated) — kept for observability/filtering.
    type            text        NOT NULL,
    -- Raw request body, stored verbatim (text, not jsonb) so it survives re-verification.
    payload         text        NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    -- NULL until the handler finishes applying the event; lets a crashed mid-process event
    -- be told apart from a fully-applied one.
    processed_at    timestamptz,
    CONSTRAINT pk_stripe_events PRIMARY KEY (id),
    CONSTRAINT uq_stripe_events_stripe_event_id UNIQUE (stripe_event_id)
);

-- Audit timestamps for subscription rows (existing rows: none yet, so a now() default is safe).
ALTER TABLE subscriptions
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- One subscription per user (upsert conflict target); supersedes the redundant non-unique index.
DROP INDEX IF EXISTS idx_subscriptions_user_id;
ALTER TABLE subscriptions
    ADD CONSTRAINT uq_subscriptions_user_id UNIQUE (user_id);

-- One Stripe customer maps to one subscription row (one customer per user). This both enforces that
-- invariant and gives `findByStripeCustomerId` — hit on the webhook path for events that carry only
-- the customer — a supporting index. Partial so the many pre-checkout rows with a NULL customer id
-- don't collide (Postgres already treats NULLs as distinct, but a partial index also stays smaller).
CREATE UNIQUE INDEX uq_subscriptions_stripe_customer_id
    ON subscriptions (stripe_customer_id)
    WHERE stripe_customer_id IS NOT NULL;

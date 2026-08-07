-- === billing: trial-ending reminder (send-once marker) ===
-- The no-card trial is derived, not stored: an account is on trial while now < users.created_at +
-- complyr.billing.trial-period (see [com.complyr.billing.PlanResolver]). That means there is no
-- subscription row to hang "we already reminded them" off, and the reminder job runs daily — so
-- without a marker every due account would be mailed every single day of the lead window.
--
-- This column IS that marker: [com.complyr.billing.TrialEndingReminderJob] sets it in the same
-- transaction that requests the mail, and the candidate query excludes any row where it is already
-- set. It is deliberately a timestamp rather than a boolean: it doubles as support evidence for
-- "when did you warn me?" and keeps the door open for a second, later reminder keyed on it.
--
-- Nullable with no default: NULL means "not yet reminded", which is the correct state for every
-- existing account. Backfilling accounts whose trial has already lapsed is unnecessary — the
-- candidate query only looks at trials still inside the lead window ahead of `now`.

ALTER TABLE users
    ADD COLUMN trial_ending_email_sent_at timestamptz;

-- The candidate scan is "verified accounts, ordered by created_at, not yet reminded". A partial index
-- on the not-yet-reminded rows keeps that daily sweep off a full table scan and shrinks as accounts are
-- reminded (a reminded row leaves the index entirely).
CREATE INDEX idx_users_trial_reminder_pending
    ON users (created_at)
    WHERE trial_ending_email_sent_at IS NULL;

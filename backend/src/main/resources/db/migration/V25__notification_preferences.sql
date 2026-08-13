-- Week 7: per-account email notification preferences (settings sub-slice — /settings/notifications).
--
-- Complyr already SENDS product email (scan-complete, new-tracker, trial-ending, payment-issue); what
-- it lacked was a way for the account holder to opt out. This table is that opt-out layer. One row per
-- user, created lazily the first time the customer changes a default (absence of a row == all defaults),
-- so a signup writes nothing here and the steady state for most accounts is no row at all.
--
-- Scope today: only the two scan-completion emails are gated on these flags (ScanCompletionNotifier).
-- The columns model the customer-facing choice, not the trigger plumbing:
--   * scan_complete  -> the first scan of a newly added site finished (ScanTrigger.SITE_ADDED)
--   * scan_changes   -> a monitoring re-scan found new/changed trackers (ScanTrigger.SCHEDULED w/ diff)
-- Both default TRUE: the product's core promise is "we watch your site", so a new account is opted in
-- and can turn it down, never the reverse.

CREATE TABLE notification_preferences (
    -- PK *is* the FK: exactly one preference row per user. ON DELETE CASCADE mirrors every other
    -- user-owned table, but note the Art. 17 erasure never deletes the users row (it tombstones it),
    -- so this row is removed EXPLICITLY by AccountIdentityErasureRepository.deleteNotificationPreferences
    -- inside the erasure transaction — the cascade is a schema-level backstop, not the live path.
    user_id       uuid        NOT NULL,
    scan_complete boolean     NOT NULL DEFAULT true,
    scan_changes  boolean     NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_notification_preferences PRIMARY KEY (user_id),
    CONSTRAINT fk_notification_preferences_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

COMMENT ON TABLE notification_preferences IS
    'Per-account email opt-out flags. Row is created lazily on first change; no row == all defaults (opted in).';

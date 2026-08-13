-- === display name =========================================================
-- The account holder's display name, shown in the dashboard and usable as a
-- greeting in transactional emails. Optional: an account created before this
-- column (or one that never sets a name) simply has NULL, and the UI falls back
-- to the email. Bounded at 120 characters to match the DTO validation — long
-- enough for any real name, short enough that it can never become a payload.
--
-- Erasure safety comes from application code, not this migration: the Art. 17
-- tombstone in AccountDeletionService.tombstone() nulls this column alongside the
-- other identity fields, so an erased row carries no leftover name.

ALTER TABLE users
    ADD COLUMN name varchar(120);

COMMENT ON COLUMN users.name IS
    'Optional account-holder display name (<=120 chars). NULL when never set; UI falls back to the email.';

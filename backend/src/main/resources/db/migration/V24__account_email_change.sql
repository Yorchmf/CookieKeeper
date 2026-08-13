-- Week 7: change-of-email support (account-settings sub-slice C).
--
-- A customer changes their login email with a "verify the new address first"
-- flow: the new address is parked in users.pending_email and an email_change
-- token is mailed to it; the account email swaps to the parked value only when
-- that token is confirmed (see AuthService.confirmEmailChange). Until then the
-- current address stays live and unchanged, and it receives a security notice
-- once the swap completes.

-- === users.pending_email ===================================================
-- The new address awaiting confirmation. NULL whenever no change is in flight
-- (the steady state). Bounded at 255 to match the request DTO's @Size(max=255)
-- and the existing users.email width, so it can never become a payload. It is
-- deliberately NOT unique: uniqueness is enforced at SWAP time against
-- users.email (uq_users_email), so two accounts may transiently park the same
-- pending address and only the first to confirm wins.
--
-- Erasure safety comes from application code, not this migration: the Art. 17
-- tombstone in AccountDeletionService.tombstone() nulls this column alongside
-- the other identity fields, so an erased row carries no leftover address.
ALTER TABLE users
    ADD COLUMN pending_email varchar(255);

COMMENT ON COLUMN users.pending_email IS
    'New email awaiting confirmation (email-change flow). NULL when no change is in flight; cleared on confirm and on Art. 17 erasure.';

-- === auth_tokens.purpose gains 'email_change' ==============================
-- The V2 CHECK constraint enumerates the allowed purposes, and a CHECK cannot
-- be extended in place, so it is dropped and recreated. Recreated under the
-- identical name so a future migration (or a reviewer) still finds it by
-- ck_auth_tokens_purpose.
ALTER TABLE auth_tokens
    DROP CONSTRAINT ck_auth_tokens_purpose;

ALTER TABLE auth_tokens
    ADD CONSTRAINT ck_auth_tokens_purpose
        CHECK (purpose IN ('email_verification', 'password_reset', 'email_change'));

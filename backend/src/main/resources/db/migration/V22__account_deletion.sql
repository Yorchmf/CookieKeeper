-- === account erasure (GDPR Art. 17) ========================================
-- ADR-20. "Delete my account" must erase the customer's identity while the
-- append-only consent evidence their sites recorded stays referentially valid:
-- `consent_events.site_id` is ON DELETE RESTRICT (V3) precisely so no application
-- path can silently orphan audit rows, and CLAUDE.md #3 forbids deleting them from
-- application code at all. Deleting the `users` row would cascade its `sites`
-- (V1 `fk_sites_user`) straight into that RESTRICT — so a plain DELETE is not just
-- undesirable here, it is impossible for any account that ever served a banner.
--
-- The erasure therefore SCRUBS the two rows the FK graph needs and DELETES the rest:
--   * a `sites` row that has consent evidence is stripped to a tombstone (synthetic
--     domain + site_key, archived, verification cleared) and kept;
--   * a `sites` row with no consent evidence is deleted outright, so an account that
--     never served a banner leaves nothing behind at all;
--   * the `users` row is stripped to a tombstone (synthetic email, unusable password,
--     `deleted_at` stamped) because the surviving sites still reference it.
-- Everything else the account owned — banner configs, scans, policies, overrides,
-- tokens, subscription — is deleted outright.
--
-- The tombstones carry no personal data: what survives is a random UUID and rows
-- derived from it. The consent evidence itself ages out through the existing
-- tenant-blind 3-year partition drop (ADR-16); nothing here changes that schedule.

ALTER TABLE users
    ADD COLUMN deleted_at timestamptz;

COMMENT ON COLUMN users.deleted_at IS
    'Set when the account was erased under GDPR Art. 17 (ADR-20). A non-null row is a tombstone: it holds no personal data and exists only to keep the surviving sites (and through them consent_events) referentially valid. Every application path that loads a user by id must refuse a tombstone — login already cannot reach one because the email is destroyed, but an access JWT issued before the erasure stays verifiable for its remaining TTL.';

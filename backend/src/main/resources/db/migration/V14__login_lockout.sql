-- === auth: per-account login lockout (distributed brute-force backstop) ===
-- The per-IP rate limit (complyr.rate-limit.auth-per-minute) only bounds one source address, so a
-- botnet spraying guesses at ONE email from many IPs is unbounded at the app layer. These two columns
-- add a per-account failed-attempt counter and a temporary lock window: after
-- `complyr.auth.max-failed-login-attempts` consecutive failures the account is locked until
-- `locked_until`, regardless of source IP. The lock is TEMPORARY (auto-expires) so a targeted attacker
-- can only inconvenience a victim briefly, and a successful login clears the counter. See
-- [com.complyr.auth.LoginAttemptService] / [com.complyr.auth.AuthService].

ALTER TABLE users
    ADD COLUMN failed_login_attempts integer     NOT NULL DEFAULT 0,
    ADD COLUMN locked_until          timestamptz,
    ADD CONSTRAINT ck_users_failed_login_attempts_nonneg CHECK (failed_login_attempts >= 0);

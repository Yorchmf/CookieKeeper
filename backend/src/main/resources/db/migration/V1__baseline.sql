-- Complyr baseline schema (docs/ARCHITECTURE.md §5).
-- Conventions: uuid PKs with gen_random_uuid() (PostgreSQL 13+), timestamptz for instants,
-- text over varchar, jsonb for structured payloads.

-- === users =================================================================

CREATE TABLE users (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    password_hash text        NOT NULL,
    locale        text        NOT NULL DEFAULT 'en',
    created_at    timestamptz NOT NULL DEFAULT now(),
    verified_at   timestamptz,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- === refresh_tokens ========================================================

CREATE TABLE refresh_tokens (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL,
    token_hash   text        NOT NULL,
    expires_at   timestamptz NOT NULL,
    rotated_from uuid,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_rotated_from FOREIGN KEY (rotated_from) REFERENCES refresh_tokens (id) ON DELETE SET NULL
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

-- === sites =================================================================

CREATE TABLE sites (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id              uuid        NOT NULL,
    domain               text        NOT NULL,
    site_key             text        NOT NULL,
    verified_at          timestamptz,
    plan_limits_snapshot jsonb,
    CONSTRAINT pk_sites PRIMARY KEY (id),
    CONSTRAINT uq_sites_site_key UNIQUE (site_key),
    CONSTRAINT uq_sites_user_domain UNIQUE (user_id, domain),
    CONSTRAINT fk_sites_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_sites_user_id ON sites (user_id);

-- === subscriptions =========================================================

CREATE TABLE subscriptions (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id            uuid        NOT NULL,
    stripe_customer_id text,
    stripe_sub_id      text,
    plan               text        NOT NULL,
    status             text        NOT NULL,
    period_end         timestamptz,
    CONSTRAINT pk_subscriptions PRIMARY KEY (id),
    CONSTRAINT uq_subscriptions_stripe_sub_id UNIQUE (stripe_sub_id),
    CONSTRAINT fk_subscriptions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_subscriptions_user_id ON subscriptions (user_id);

-- === banner_configs (append new versions, never overwrite) ================

CREATE TABLE banner_configs (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    site_id      uuid        NOT NULL,
    version      integer     NOT NULL,
    config_jsonb jsonb       NOT NULL,
    published_at timestamptz,
    CONSTRAINT pk_banner_configs PRIMARY KEY (id),
    CONSTRAINT uq_banner_configs_site_version UNIQUE (site_id, version),
    CONSTRAINT fk_banner_configs_site FOREIGN KEY (site_id) REFERENCES sites (id) ON DELETE CASCADE
);

-- === scans =================================================================

CREATE TABLE scans (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    site_id       uuid        NOT NULL,
    status        text        NOT NULL,
    started_at    timestamptz,
    finished_at   timestamptz,
    pages_crawled integer,
    error         text,
    CONSTRAINT pk_scans PRIMARY KEY (id),
    CONSTRAINT fk_scans_site FOREIGN KEY (site_id) REFERENCES sites (id) ON DELETE CASCADE
);

CREATE INDEX idx_scans_site_id_started_at ON scans (site_id, started_at DESC);

-- === scan_cookies ==========================================================

CREATE TABLE scan_cookies (
    id       uuid    NOT NULL DEFAULT gen_random_uuid(),
    scan_id  uuid    NOT NULL,
    name     text    NOT NULL,
    domain   text,
    expiry   text,
    category text,
    provider text,
    is_known boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_scan_cookies PRIMARY KEY (id),
    CONSTRAINT fk_scan_cookies_scan FOREIGN KEY (scan_id) REFERENCES scans (id) ON DELETE CASCADE
);

CREATE INDEX idx_scan_cookies_scan_id ON scan_cookies (scan_id);

-- === cookie_overrides (customer categorizations) ===========================

CREATE TABLE cookie_overrides (
    id          uuid NOT NULL DEFAULT gen_random_uuid(),
    site_id     uuid NOT NULL,
    cookie_name text NOT NULL,
    category    text NOT NULL,
    CONSTRAINT pk_cookie_overrides PRIMARY KEY (id),
    CONSTRAINT uq_cookie_overrides_site_cookie UNIQUE (site_id, cookie_name),
    CONSTRAINT fk_cookie_overrides_site FOREIGN KEY (site_id) REFERENCES sites (id) ON DELETE CASCADE
);

-- === policies (versioned per site × language) ==============================

CREATE TABLE policies (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    site_id      uuid        NOT NULL,
    version      integer     NOT NULL,
    language     text        NOT NULL,
    html         text        NOT NULL,
    published_at timestamptz,
    CONSTRAINT pk_policies PRIMARY KEY (id),
    CONSTRAINT uq_policies_site_version_language UNIQUE (site_id, version, language),
    CONSTRAINT fk_policies_site FOREIGN KEY (site_id) REFERENCES sites (id) ON DELETE CASCADE
);

-- === consent_events (APPEND-ONLY audit evidence) ===========================
-- Never UPDATE or DELETE from application code; retention job only.
-- No raw IPs (rotating-salt hash), no raw user agents. Partition by month when volume demands.

CREATE TABLE consent_events (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    site_id          uuid        NOT NULL,
    visitor_id       uuid        NOT NULL,
    action           text        NOT NULL,
    categories_jsonb jsonb       NOT NULL,
    banner_version   integer,
    policy_version   integer,
    lang             text,
    ip_hash          text,
    ua_trimmed       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_consent_events PRIMARY KEY (id),
    CONSTRAINT fk_consent_events_site FOREIGN KEY (site_id) REFERENCES sites (id) ON DELETE RESTRICT
);

CREATE INDEX idx_consent_events_site_id_created_at ON consent_events (site_id, created_at);

-- === jobs (Postgres-backed queue, claimed with FOR UPDATE SKIP LOCKED) =====

CREATE TABLE jobs (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    type          text        NOT NULL,
    payload_jsonb jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status        text        NOT NULL DEFAULT 'pending',
    attempts      integer     NOT NULL DEFAULT 0,
    locked_until  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_jobs PRIMARY KEY (id)
);

CREATE INDEX idx_jobs_status_locked_until ON jobs (status, locked_until);

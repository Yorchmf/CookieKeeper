-- Week 2: single-use auth tokens (email verification / password reset),
-- refresh-token lifecycle columns, and site soft-archive lifecycle.

-- === auth_tokens (single-use, hashed at rest) ==============================

CREATE TABLE auth_tokens (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL,
    token_hash text        NOT NULL,
    purpose    text        NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_auth_tokens PRIMARY KEY (id),
    CONSTRAINT uq_auth_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_auth_tokens_purpose CHECK (purpose IN ('email_verification', 'password_reset')),
    CONSTRAINT fk_auth_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_auth_tokens_user_purpose ON auth_tokens (user_id, purpose);

-- === refresh_tokens: lifecycle columns =====================================

ALTER TABLE refresh_tokens
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN revoked_at timestamptz;

-- === sites: soft-archive lifecycle =========================================
-- DELETE /api/v1/sites/{id} archives (status='archived'), never hard-deletes.

ALTER TABLE sites
    ADD COLUMN status     text        NOT NULL DEFAULT 'active',
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD CONSTRAINT ck_sites_status CHECK (status IN ('active', 'archived'));

-- Replace the hard unique (user_id, domain) with a partial unique index so an
-- archived domain can be re-registered while active domains stay unique.
ALTER TABLE sites
    DROP CONSTRAINT uq_sites_user_domain;

CREATE UNIQUE INDEX uq_sites_user_domain_active ON sites (user_id, domain) WHERE status = 'active';

-- === public_scans: anonymous free-scan results (acquisition funnel) ===========
-- The marketing-site "scan your domain for free" lead magnet. Deliberately SEPARATE
-- from `scans` (owned, site-scoped, long-lived history): a public scan has no user
-- or site, a short TTL, an opaque read token, and an ip_hash for abuse analysis only.
-- See docs/anonymous-scan-funnel.md (ADR-12). It crawls a domain the visitor has NOT
-- verified ownership of, so ScanTargetValidator (public-only DNS resolution) is the
-- sole app-layer SSRF guard for this path — the ownership layer `scans` relies on is
-- intentionally given up here and compensated elsewhere (rate limit, homepage-only
-- crawl, per-domain cache).

CREATE TABLE public_scans (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    domain       text        NOT NULL,
    -- Same lifecycle enum as scans.status (com.complyr.scan.ScanStatus): queued -> running
    -- -> done/failed. CHECK mirrors the enum so a bad value fails at write, not read.
    status       text        NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'running', 'done', 'failed')),
    -- Opaque, unguessable read key (32B SecureRandom, base64url — see auth.OpaqueTokens).
    -- The result is fetched by this token, NEVER by id: id is time-adjacent and must not
    -- be the capability that reveals a stranger's scan.
    public_token text        NOT NULL,
    -- Captured only when the visitor asks for the detailed report (email gate). Null until then.
    email        text,
    -- Rotating-salt hash of the requester IP for abuse analysis (CLAUDE.md #4: never the raw IP).
    -- Null when the IP is unavailable.
    ip_hash      text,
    -- Last failure reason for operators, mirroring scans.error. Never holds attacker-controlled
    -- cookie data (§4 no-PII/no-injection in logs/columns).
    error        text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    -- Retention horizon: the reaper (slice G) purges rows past this (7-day TTL). Cookies cascade.
    expires_at   timestamptz NOT NULL,
    CONSTRAINT pk_public_scans PRIMARY KEY (id),
    -- One row per token; also the read-path lookup index.
    CONSTRAINT uq_public_scans_token UNIQUE (public_token),
    -- Defense-in-depth on attacker-controlled, unauthenticated inputs: `domain` and `email` come
    -- straight from a public endpoint. ScanTargetValidator/email validation gate them in the app,
    -- but bound them at the schema too so a validation gap can't bloat the (indexed) domain column
    -- or store a multi-KB "domain"/"email". `ip_hash` is app-produced (fixed-width) — bound generously.
    CONSTRAINT ck_public_scans_domain_len  CHECK (length(domain) <= 253),                 -- max DNS hostname
    CONSTRAINT ck_public_scans_email_len   CHECK (email IS NULL OR length(email) <= 254),  -- RFC 5321 practical max
    CONSTRAINT ck_public_scans_ip_hash_len CHECK (ip_hash IS NULL OR length(ip_hash) <= 128),
    -- The TTL horizon must be after creation; a mis-set past value would make the reaper delete the
    -- row on its next pass with no error. Cheap safety net (the app always sets created + 7d).
    CONSTRAINT ck_public_scans_expires_after_created CHECK (expires_at > created_at)
);

-- 24h per-domain cache/dedupe: "most recent scan for this domain" so a fresh result is
-- returned instead of crawling again. Newest-first so the lookup reads the head.
CREATE INDEX idx_public_scans_domain_created_at ON public_scans (domain, created_at DESC);

-- Retention sweep support: WHERE expires_at < now(). A plain btree serves the range scan.
CREATE INDEX idx_public_scans_expires_at ON public_scans (expires_at);

-- --- public_scan_cookies: observations for a public scan ----------------------
-- Mirrors scan_cookies (same classified shape, incl. `provider`) but FK'd to public_scans
-- so a purge/re-run cleans them up. Replaceable, not audit evidence. The same per-scan caps
-- (bounded count, truncated names — ScanCookieMapper) apply on the write path.
CREATE TABLE public_scan_cookies (
    id             uuid    NOT NULL DEFAULT gen_random_uuid(),
    public_scan_id uuid    NOT NULL,
    name           text    NOT NULL,
    domain         text,
    expiry         text,
    category       text,
    provider       text,
    is_known       boolean NOT NULL DEFAULT false,
    CONSTRAINT pk_public_scan_cookies PRIMARY KEY (id),
    CONSTRAINT fk_public_scan_cookies_scan
        FOREIGN KEY (public_scan_id) REFERENCES public_scans (id) ON DELETE CASCADE
);

CREATE INDEX idx_public_scan_cookies_scan_id ON public_scan_cookies (public_scan_id);

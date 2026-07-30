-- === consent_events → monthly RANGE partitions + UUIDv7 PK ==================
-- consent_events is the highest-volume, longest-lived table (append-only audit
-- evidence, up to 5y retention). It is still EMPTY pre-launch, which is the only
-- cheap moment to change its physical shape — Postgres cannot convert a populated
-- table to partitioned in place. V1 created it as a plain table; we recreate it
-- here (safe: no data) so that:
--
--   * Retention becomes DROP PARTITION (an instant metadata op) instead of a mass
--     row-by-row DELETE across millions of rows — and never touches live data.
--   * The PK is UUIDv7 (time-ordered), giving sequential B-tree insert locality on
--     the hot write path instead of the random scattering of gen_random_uuid()/v4.
--
-- Partitioning requires the partition key (created_at) to be part of every unique
-- constraint, hence the composite PK (id, created_at). The application still treats
-- `id` alone as the entity identifier (UUIDv7 is globally unique on its own).

DROP TABLE IF EXISTS consent_events;

CREATE TABLE consent_events (
    -- UUIDv7 assigned by the app (Hibernate @UuidGenerator VERSION_7); no DB default,
    -- so a stray direct INSERT that forgets the id fails loudly rather than silently
    -- writing a non-time-ordered v4.
    id               uuid        NOT NULL,
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
    CONSTRAINT pk_consent_events PRIMARY KEY (id, created_at),
    CONSTRAINT fk_consent_events_site FOREIGN KEY (site_id) REFERENCES sites (id) ON DELETE RESTRICT,
    -- Mirrors the ConsentAction enum client values; rejects forged/garbage actions at the DB edge.
    CONSTRAINT ck_consent_events_action CHECK (action IN ('accept_all', 'reject_all', 'custom'))
) PARTITION BY RANGE (created_at);

-- site_id+created_at: per-site audit exports and retention scans.
CREATE INDEX idx_consent_events_site_id_created_at ON consent_events (site_id, created_at);
-- visitor_id: audit correlation ("show every choice this visitor made").
CREATE INDEX idx_consent_events_visitor_id ON consent_events (visitor_id);

-- Bootstrap partitions for the launch window. A scheduled maintenance job (or
-- pg_partman later) must pre-create upcoming months ahead of time. The DEFAULT
-- partition is a safety net so an insert never fails if a month is missing — rows
-- there are NOT reclaimable by DROP PARTITION, so the maintenance job must keep it
-- empty by always provisioning the current+next month in advance.
--
-- Bounds are written as explicit UTC `timestamptz` literals. `created_at` is
-- timestamptz, so a bare date literal would be parsed using the session TimeZone at
-- DDL time — shifting every boundary by the server's UTC offset and making the
-- boundaries non-reproducible across environments. Pinning +00 keeps month edges at
-- midnight UTC everywhere; the maintenance job MUST use the same UTC convention.
CREATE TABLE consent_events_2026_07 PARTITION OF consent_events FOR VALUES FROM (TIMESTAMPTZ '2026-07-01 00:00:00+00') TO (TIMESTAMPTZ '2026-08-01 00:00:00+00');
CREATE TABLE consent_events_2026_08 PARTITION OF consent_events FOR VALUES FROM (TIMESTAMPTZ '2026-08-01 00:00:00+00') TO (TIMESTAMPTZ '2026-09-01 00:00:00+00');
CREATE TABLE consent_events_2026_09 PARTITION OF consent_events FOR VALUES FROM (TIMESTAMPTZ '2026-09-01 00:00:00+00') TO (TIMESTAMPTZ '2026-10-01 00:00:00+00');
CREATE TABLE consent_events_2026_10 PARTITION OF consent_events FOR VALUES FROM (TIMESTAMPTZ '2026-10-01 00:00:00+00') TO (TIMESTAMPTZ '2026-11-01 00:00:00+00');
CREATE TABLE consent_events_2026_11 PARTITION OF consent_events FOR VALUES FROM (TIMESTAMPTZ '2026-11-01 00:00:00+00') TO (TIMESTAMPTZ '2026-12-01 00:00:00+00');
CREATE TABLE consent_events_2026_12 PARTITION OF consent_events FOR VALUES FROM (TIMESTAMPTZ '2026-12-01 00:00:00+00') TO (TIMESTAMPTZ '2027-01-01 00:00:00+00');
CREATE TABLE consent_events_2027_01 PARTITION OF consent_events FOR VALUES FROM (TIMESTAMPTZ '2027-01-01 00:00:00+00') TO (TIMESTAMPTZ '2027-02-01 00:00:00+00');
CREATE TABLE consent_events_default PARTITION OF consent_events DEFAULT;

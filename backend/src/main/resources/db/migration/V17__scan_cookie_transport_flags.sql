-- === scan cookies: persist the Secure / HttpOnly transport flags the crawl already observes ===
-- The compliance report ([com.complyr.scan.ComplianceAnalyzer]) gains an insecure-flags finding: a
-- non-essential cookie served without Secure AND without HttpOnly is sent in the clear and readable from
-- page script. Playwright already reports both flags on every observed cookie ([Cookie.secure] /
-- [Cookie.httpOnly]); we simply had nowhere to store them, so the analyzer could not score them. This
-- adds that backing column to BOTH cookie tables so the authenticated per-site scan and the anonymous
-- free-scan funnel are scored identically (mirrors the shape parity in V9__public_scans.sql).
--
-- NOT NULL DEFAULT false: existing rows predate flag capture, so they backfill to false. That means a
-- historical scan's non-essential cookies may now surface an insecure-flags finding until re-crawled —
-- acceptable, because scan findings are replaceable (not append-only audit evidence like consent_events)
-- and a re-scan refreshes them with the real observed flags. Fresh crawls carry the true values.
ALTER TABLE scan_cookies
    ADD COLUMN secure boolean NOT NULL DEFAULT false,
    ADD COLUMN http_only boolean NOT NULL DEFAULT false;

ALTER TABLE public_scan_cookies
    ADD COLUMN secure boolean NOT NULL DEFAULT false,
    ADD COLUMN http_only boolean NOT NULL DEFAULT false;

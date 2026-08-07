-- === scans: persist the marketing third-party tracker count the crawl observes ===
-- The compliance report ([com.complyr.scan.ComplianceAnalyzer]) gains a `third_party_trackers` finding:
-- a before-consent crawl already sees every request a page fires, so a request to a marketing tracker
-- (an ad/pixel network) with no consent given is a pre-consent tracking violation the cookie-only checks
-- miss when the tracker rides on a request rather than a cookie. The scanner matches observed off-site
-- request hosts against the bundled signature dataset ([com.complyr.scan.TrackerClassifier]) and stores
-- ONLY the resulting count — never the raw hosts, which are attacker-influenced (§4 no untrusted data at
-- rest / in logs). This adds that backing column to BOTH scan tables so the authenticated per-site scan
-- and the anonymous free-scan funnel are scored identically (mirrors the shape parity in V17 / V9).
--
-- Nullable, no default: it mirrors `scans.pages_crawled` — populated only when a crawl completes, so a
-- queued/running/failed row and every historical row read as "no data". [ComplianceAnalyzer] treats a
-- null count as 0 trackers, so pre-existing scans simply omit the finding until re-crawled (scan findings
-- are replaceable, not append-only audit evidence like consent_events).
ALTER TABLE scans
    ADD COLUMN marketing_tracker_count integer;

ALTER TABLE public_scans
    ADD COLUMN marketing_tracker_count integer;

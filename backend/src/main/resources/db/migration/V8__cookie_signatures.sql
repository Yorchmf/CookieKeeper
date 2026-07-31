-- === cookie_signatures (scanner classification reference data) =============
--
-- The seeded signature DB the scanner classifies observed cookies against (ARCHITECTURE §13, W4):
-- a match sets scan_cookies.category/provider and flags is_known; a miss lands the cookie in the
-- dashboard "needs review" bucket. category mirrors the canonical taxonomy in
-- com.complyr.banner.ConsentCategory (necessary/preferences/statistics/marketing) and is CHECK-pinned
-- here the same way V7 pins scan trigger sources.
--
-- Matching (see com.complyr.scan.CookieSignatureMatcher): exact name_pattern wins; otherwise the
-- longest is_wildcard prefix that the cookie name starts with; otherwise unknown. name_pattern is
-- either the full cookie name (is_wildcard = false) or the stable prefix of a family whose suffix is
-- site/instance-specific (is_wildcard = true), e.g. GA4's `_ga_<container-id>`.
--
-- Seed data is a curated subset bootstrapped from the Open Cookie Database
-- (https://github.com/jkwakman/Open-Cookie-Database), licensed CC-BY 4.0. Their Functional/Security →
-- necessary, Analytics → statistics, Marketing → marketing mapping is applied. The full import is a
-- follow-up; this subset covers the highest-frequency trackers so most real sites classify on day one.

CREATE TABLE cookie_signatures (
    id           uuid    NOT NULL DEFAULT gen_random_uuid(),
    name_pattern text    NOT NULL,
    is_wildcard  boolean NOT NULL DEFAULT false,
    provider     text    NOT NULL,
    category     text    NOT NULL,
    description  text,
    CONSTRAINT pk_cookie_signatures PRIMARY KEY (id),
    CONSTRAINT uq_cookie_signatures_pattern UNIQUE (name_pattern, is_wildcard),
    CONSTRAINT ck_cookie_signatures_category
        CHECK (category IN ('necessary', 'preferences', 'statistics', 'marketing')),
    -- An empty pattern would make the matcher's startsWith("") match every cookie — reject it here so
    -- a bad seed row can never silently reclassify a whole scan.
    CONSTRAINT ck_cookie_signatures_pattern_nonempty CHECK (length(name_pattern) > 0)
);

-- No standalone index on name_pattern: the whole (tiny, curated) table is loaded once per scan via
-- findAll() and matched in memory with Kotlin startsWith, so there is no SQL prefix scan to serve, and
-- the UNIQUE(name_pattern, is_wildcard) constraint already indexes name_pattern as its leading column
-- for the occasional exact/admin lookup.

INSERT INTO cookie_signatures (name_pattern, is_wildcard, provider, category, description) VALUES
    -- necessary: session, CSRF, CDN/load-balancer, consent state
    ('PHPSESSID',            false, 'PHP',              'necessary', 'PHP application session identifier'),
    ('JSESSIONID',           false, 'Java',             'necessary', 'Java servlet session identifier'),
    ('ASP.NET_SessionId',    false, 'ASP.NET',          'necessary', 'ASP.NET session identifier'),
    ('ASPSESSIONID',         true,  'ASP',              'necessary', 'Classic ASP session identifier'),
    ('connect.sid',          false, 'Express',          'necessary', 'Express/Node session identifier'),
    ('csrftoken',            false, 'Django',           'necessary', 'Django CSRF protection token'),
    ('XSRF-TOKEN',           false, 'Angular',          'necessary', 'Anti-CSRF token'),
    ('_csrf',                false, 'Generic',          'necessary', 'Anti-CSRF token'),
    ('cookieconsent_status', false, 'Cookie Consent',   'necessary', 'Stores the visitor consent choice'),
    ('__cf_bm',              false, 'Cloudflare',       'necessary', 'Cloudflare bot management'),
    ('cf_clearance',         false, 'Cloudflare',       'necessary', 'Cloudflare challenge clearance'),
    ('AWSALB',               false, 'AWS',              'necessary', 'AWS application load balancer stickiness'),
    ('AWSALBCORS',           false, 'AWS',              'necessary', 'AWS load balancer stickiness (CORS)'),
    ('AWSELB',               false, 'AWS',              'necessary', 'AWS elastic load balancer stickiness'),
    -- statistics: analytics
    ('_ga',                  false, 'Google Analytics', 'statistics', 'Google Analytics visitor identifier'),
    ('_gid',                 false, 'Google Analytics', 'statistics', 'Google Analytics 24h visitor identifier'),
    ('_gat',                 false, 'Google Analytics', 'statistics', 'Google Analytics request-rate throttle'),
    ('_ga_',                 true,  'Google Analytics', 'statistics', 'GA4 per-property session state (_ga_<container-id>)'),
    ('_gat_',                true,  'Google Analytics', 'statistics', 'Google Analytics per-property throttle'),
    ('__utma',               false, 'Google Analytics', 'statistics', 'Legacy Universal Analytics visitor'),
    ('__utmb',               false, 'Google Analytics', 'statistics', 'Legacy Universal Analytics session'),
    ('__utmc',               false, 'Google Analytics', 'statistics', 'Legacy Universal Analytics session'),
    ('__utmz',               false, 'Google Analytics', 'statistics', 'Legacy Universal Analytics campaign source'),
    ('_pk_id.',              true,  'Matomo',           'statistics', 'Matomo visitor identifier'),
    ('_pk_ses.',             true,  'Matomo',           'statistics', 'Matomo short-lived session'),
    ('_hj',                  true,  'Hotjar',           'statistics', 'Hotjar behaviour analytics (_hjSession*, _hjid, ...)'),
    ('_clck',                false, 'Microsoft Clarity','statistics', 'Microsoft Clarity user identifier'),
    ('_clsk',                false, 'Microsoft Clarity','statistics', 'Microsoft Clarity session'),
    ('amplitude_',           true,  'Amplitude',        'statistics', 'Amplitude product analytics'),
    -- marketing: advertising / retargeting
    ('_fbp',                 false, 'Meta',             'marketing', 'Meta (Facebook) Pixel browser identifier'),
    ('_fbc',                 false, 'Meta',             'marketing', 'Meta (Facebook) Pixel click identifier'),
    ('fr',                   false, 'Meta',             'marketing', 'Meta (Facebook) advertising'),
    ('_gcl_au',              false, 'Google Ads',       'marketing', 'Google Ads conversion linker'),
    ('_gcl_',                true,  'Google Ads',       'marketing', 'Google Ads conversion linker family'),
    ('IDE',                  false, 'Google DoubleClick','marketing', 'DoubleClick ad targeting'),
    ('test_cookie',          false, 'Google DoubleClick','marketing', 'DoubleClick cookie-support probe'),
    ('_ttp',                 false, 'TikTok',           'marketing', 'TikTok Pixel identifier'),
    ('personalization_id',   false, 'X (Twitter)',      'marketing', 'X/Twitter advertising identifier'),
    ('muc_ads',              false, 'X (Twitter)',      'marketing', 'X/Twitter advertising measurement'),
    ('bcookie',              false, 'LinkedIn',         'marketing', 'LinkedIn browser identifier'),
    ('bscookie',             false, 'LinkedIn',         'marketing', 'LinkedIn secure browser identifier'),
    ('lidc',                 false, 'LinkedIn',         'marketing', 'LinkedIn data-centre routing'),
    ('li_sugr',              false, 'LinkedIn',         'marketing', 'LinkedIn browser-identifier match'),
    ('UserMatchHistory',     false, 'LinkedIn',         'marketing', 'LinkedIn Ads visitor sync'),
    ('MUID',                 false, 'Microsoft',        'marketing', 'Microsoft/Bing advertising identifier'),
    ('_uetsid',              false, 'Microsoft',        'marketing', 'Bing Ads session identifier'),
    ('_uetvid',              false, 'Microsoft',        'marketing', 'Bing Ads visitor identifier');

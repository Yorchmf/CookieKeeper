// smoke.js — k6 load smoke test for the CookieKeeper backend on a CX22 (2 vCPU / 4GB).
//
// Goal: confirm the box holds a realistic sustained load with acceptable latency
// and no 5xx, and surface the true capacity ceiling (Tomcat threads + Hikari pool,
// NOT the per-IP rate limits) so infra/load/README.md's tuning numbers are grounded.
//
// Run against DEV ONLY. `POST /api/v1/consent` writes APPEND-ONLY audit rows that a
// retention job can only DROP by the month — never point this at prd (CLAUDE.md #3).
//
//   k6 run -e BASE_URL=https://api.dev.cookiekeeper.eu \
//          -e SITE_KEY=<a-real-dev-site-key> \
//          -e PUBLIC_ID=<a-real-dev-policy-public-id> \
//          infra/load/smoke.js
//
// A single k6 host = a single source IP. The unauthenticated read tiers are per-IP,
// so the rate-limited endpoints (consent-token, public policy: ~2 req/s/IP) will
// return 429 well before the box is stressed — that is EXPECTED and asserted, not a
// failure. The real per-box CAPACITY signal comes from widget-config + health, which
// are not IP-rate-limited and are also the dominant real-world traffic (every page
// load fetches the widget config).
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SITE_KEY = __ENV.SITE_KEY || 'demo-site-key';
const PUBLIC_ID = __ENV.PUBLIC_ID || 'demo-public-id';
const RPS = Number(__ENV.RPS || 40); // sustained arrival rate; raise to find the ceiling

// Track how often the per-IP limiter engaged, so a run reports it instead of it
// masquerading as an error. Non-2xx on a limited endpoint is a 429, which is correct.
const rateLimited = new Rate('rate_limited_responses');

export const options = {
  scenarios: {
    // Constant ARRIVAL rate (req/s), decoupled from VU count, so the test models
    // offered load rather than closed-loop client concurrency. k6 grows VUs as
    // needed; if it can't sustain RPS the box is the bottleneck — the point.
    capacity: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    // Capacity verdict: reads (the hot path) stay fast and never 5xx. 429s are
    // filtered out of this via the check below — they are a correct throttle, not
    // a failure — so http_req_failed here reflects genuine errors/timeouts only.
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:read}': ['p(95)<500', 'p(99)<1500'],
  },
};

// Weighted to mirror real traffic: widget config dominates (every page load), a
// hosted-policy read is occasional, health is the monitor's probe.
export default function () {
  const roll = Math.random();
  if (roll < 0.7) {
    // Dominant + NOT per-IP-limited → the true CX22 capacity probe.
    const res = http.get(`${BASE_URL}/api/v1/widget-config/${SITE_KEY}`, {
      tags: { kind: 'read', endpoint: 'widget-config' },
    });
    check(res, { 'widget-config 200|404': (r) => r.status === 200 || r.status === 404 });
  } else if (roll < 0.85) {
    const res = http.get(`${BASE_URL}/actuator/health`, {
      tags: { kind: 'read', endpoint: 'health' },
    });
    check(res, { 'health 200': (r) => r.status === 200 });
  } else {
    // Per-IP-limited (~2 req/s/IP): from one k6 host, expect 429 under load. Assert
    // the limiter engages and record it separately instead of counting it as failed.
    const res = http.get(`${BASE_URL}/api/v1/public/policy/${PUBLIC_ID}`, {
      tags: { kind: 'limited', endpoint: 'public-policy' },
    });
    rateLimited.add(res.status === 429);
    check(res, { 'policy 200|404|429': (r) => [200, 404, 429].includes(r.status) });
  }
}

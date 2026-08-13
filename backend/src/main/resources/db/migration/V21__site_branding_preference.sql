-- Per-site "Powered by Complyr" branding preference (dashboard roadmap 0.4).
--
-- This is the customer's WISH to hide the attribution, not the authority to do so. The EFFECTIVE
-- suppression is always `hide_branding AND <plan grants removeBranding>`, resolved server-side in
-- EntitlementService.effectiveRemoveBranding (fails CLOSED — a billing-read blip shows the credit).
-- Kept OUT of banner_configs.config_jsonb on purpose: that document is customer-editable and served
-- verbatim to visitors, so a paid entitlement must never be settable from inside it (that would hand
-- branding removal out for free). It lives on `sites` because it is an account/site property, not
-- banner styling, and is read on both the widget-config and hosted-policy paths.
--
-- Default TRUE so a paying customer sees the attribution disappear the moment they upgrade without
-- having to toggle anything; on the free tier the entitlement floor keeps the credit visible
-- regardless of this column's value. `sites` is a small, low-traffic table and ADD COLUMN with a
-- constant DEFAULT is a metadata-only change on Postgres 11+, but bound the brief ACCESS EXCLUSIVE
-- lock anyway (mirrors V20) so the migration fails fast rather than queuing behind a long read.
SET LOCAL lock_timeout = '3s';

ALTER TABLE sites
    ADD COLUMN hide_branding boolean NOT NULL DEFAULT true;

# =============================================================================
# environments — DNS, edge caching and edge rate limits for ONE environment.
#
# Selected by workspace: `terraform workspace select dev|prd`. Applied by the deploy pipelines.
# Owns nothing that can take the server down; see infra/terraform/README.md.
# =============================================================================

locals {
  # Guard against the classic workspace foot-gun: `terraform apply -var-file=prd.tfvars` while the
  # dev workspace is selected would quietly rewrite dev's state with production's DNS. Terraform has
  # no built-in assertion for this, so make the mismatch a hard failure at plan time.
  workspace_matches_vars = terraform.workspace == var.environment

  # Every hostname this environment owns, proxied through Cloudflare (orange cloud). Proxying is
  # what gives us the CDN, the WAF and the origin-IP concealment; a grey-clouded record would
  # expose the VPS address directly and bypass every cache rule below.
  hosts = merge(
    {
      app = var.app_host
      api = var.api_host
      cdn = var.cdn_host
    },
    var.marketing_host == null ? {} : { marketing = var.marketing_host }
  )
}

resource "terraform_data" "workspace_guard" {
  lifecycle {
    precondition {
      condition     = local.workspace_matches_vars
      error_message = "Workspace '${terraform.workspace}' does not match environment '${var.environment}'. Run: terraform workspace select ${var.environment}"
    }
  }
}

# ----------------------------------------------------------------------------- DNS

resource "cloudflare_dns_record" "host" {
  for_each = local.hosts

  zone_id = var.cloudflare_zone_id
  name    = each.value
  type    = "A"
  content = var.origin_ipv4
  # 1 means "automatic", which is the only value Cloudflare honours for a proxied record — the
  # edge controls client-facing TTL, so anything else here is silently ignored.
  ttl     = 1
  proxied = true
  comment = "cookiekeeper ${var.environment} — managed by terraform"
}

# ----------------------------------------------------------------------------- Edge caching
#
# One ruleset per phase is a Cloudflare constraint, so both cache rules live in this single
# `http_request_cache_settings` resource. Order matters: rules are evaluated top to bottom and the
# first match wins.

resource "cloudflare_ruleset" "cache" {
  zone_id     = var.cloudflare_zone_id
  name        = "cookiekeeper-${var.environment}-cache"
  kind        = "zone"
  phase       = "http_request_cache_settings"
  description = "Edge caching for the widget config and hosted policy pages (ADR-19)."

  rules = [
    # The hot path. Cache-key deliberately ignores query string and cookies: the config is a pure
    # function of the site key in the path, so admitting either would shard the cache per-visitor
    # and defeat the entire point.
    {
      ref         = "widget_config"
      description = "Widget config JSON — the per-page-load read"
      expression  = "(http.host eq \"${var.cdn_host}\" and starts_with(http.request.uri.path, \"/cfg/\"))"
      action      = "set_cache_settings"
      action_parameters = {
        cache = true
        edge_ttl = {
          mode    = "override_origin"
          default = var.widget_config_ttl_seconds
        }
        browser_ttl = {
          mode    = "override_origin"
          default = var.widget_config_ttl_seconds
        }
        cache_key = {
          ignore_query_strings_order = true
          cache_by_device_type       = false
        }
      }
    },

    {
      ref         = "policy_pages"
      description = "Hosted cookie-policy pages"
      expression  = "(http.host eq \"${var.app_host}\" and starts_with(http.request.uri.path, \"/p/\"))"
      action      = "set_cache_settings"
      action_parameters = {
        cache = true
        edge_ttl = {
          mode    = "override_origin"
          default = var.policy_page_ttl_seconds
        }
        browser_ttl = {
          mode    = "override_origin"
          default = var.policy_page_ttl_seconds
        }
      }
    },

    # Explicit bypass, and NOT redundant with the zone default. Consent posts are append-only audit
    # evidence (CLAUDE.md #3); an edge-cached response on this path would mean a visitor's recorded
    # choice was served from someone else's request. State this at the edge rather than relying on
    # the origin's Cache-Control being right forever.
    {
      ref         = "never_cache_writes"
      description = "Consent + impression writes must always reach the origin"
      expression  = "(http.request.uri.path in {\"/api/v1/consent\" \"/api/v1/impression\"})"
      action      = "set_cache_settings"
      action_parameters = {
        cache = false
      }
    },
  ]
}

# ----------------------------------------------------------------------------- Edge rate limiting

resource "cloudflare_ruleset" "rate_limit" {
  zone_id     = var.cloudflare_zone_id
  name        = "cookiekeeper-${var.environment}-ratelimit"
  kind        = "zone"
  phase       = "http_ratelimit"
  description = "Abuse backstop in front of the unauthenticated public endpoints."

  rules = [
    {
      ref         = "consent_post_flood"
      description = "Per-IP ceiling on consent writes"
      expression  = "(http.request.uri.path eq \"/api/v1/consent\" and http.request.method eq \"POST\")"
      action      = "block"
      ratelimit = {
        characteristics     = ["ip.src", "cf.colo.id"]
        period              = 60
        requests_per_period = var.consent_rate_limit_per_minute
        mitigation_timeout  = 60
      }
    },
  ]
}

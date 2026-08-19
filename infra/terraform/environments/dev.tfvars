environment    = "dev"
app_host       = "dev.cookiekeeper.eu"
api_host       = "api.dev.cookiekeeper.eu"
cdn_host       = "cdn.dev.cookiekeeper.eu"
marketing_host = null # dev serves no marketing site — the apex belongs to prd

# Short edge TTL in dev so a banner-config change is visible almost immediately while testing.
# prd keeps the full 5 minutes.
widget_config_ttl_seconds = 30

# cloudflare_zone_id and origin_ipv4 are supplied by the pipeline via TF_VAR_* (origin_ipv4 comes
# from the platform module's server_ipv4 output), so this file holds no account-specific IDs.

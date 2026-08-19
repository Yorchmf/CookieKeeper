variable "environment" {
  description = "Which environment this workspace manages."
  type        = string

  validation {
    condition     = contains(["dev", "prd"], var.environment)
    error_message = "There are exactly two environments: dev and prd. A workstation runs the dev profile locally and has no infrastructure."
  }
}

variable "cloudflare_zone_id" {
  description = "Cloudflare zone ID for cookiekeeper.eu."
  type        = string
}

variable "origin_ipv4" {
  description = "Public IPv4 of this environment's APPLICATION server — `app_ipv4[\"<env>\"]` from the platform module's output. Never a database host: those have no DNS record and no public service."
  type        = string
}

variable "app_host" {
  description = "Dashboard hostname, e.g. app.cookiekeeper.eu (prd) or dev.cookiekeeper.eu (dev)."
  type        = string
}

variable "api_host" {
  description = "API hostname, e.g. api.cookiekeeper.eu."
  type        = string
}

variable "cdn_host" {
  description = "Widget/CDN hostname, e.g. cdn.cookiekeeper.eu."
  type        = string
}

variable "marketing_host" {
  description = <<-EOT
    Apex/marketing hostname served by this environment, or null when the environment does not own
    one. Only prd serves the public marketing site at the zone apex.
  EOT
  type        = string
  default     = null
}

variable "widget_config_ttl_seconds" {
  description = <<-EOT
    Edge TTL for /cfg/*.json — the per-site banner config the widget fetches on EVERY page load of
    every customer site (ADR-19). This is the single most important cache setting in the product:
    it is what keeps a traffic spike on a customer's site from becoming load on our API. Five
    minutes bounds how long a customer waits to see a banner edit go live.
  EOT
  type        = number
  default     = 300
}

variable "policy_page_ttl_seconds" {
  description = <<-EOT
    Edge TTL for /p/* hosted policy pages. Longer than the widget config: policy documents change
    only when a customer regenerates one, and a stale hour on a legal page is harmless.
  EOT
  type        = number
  default     = 3600
}

variable "consent_rate_limit_per_minute" {
  description = <<-EOT
    Per-IP ceiling on POST /api/v1/consent at the edge. This is an abuse backstop that sheds load
    BEFORE it reaches the origin; the application keeps its own per-site limits regardless. Set it
    well above any believable single visitor — a real browser posts consent once per session.
  EOT
  type        = number
  default     = 60
}

# =============================================================================
# platform — every machine in the estate, the private networks joining each
# environment's pair, and the zone-wide Cloudflare settings.
#
# Applied BY HAND, rarely. The deploy pipelines never touch this module: if
# `release-prd` could plan against it, a bad merge could replace the production
# database server. See infra/terraform/README.md.
#
# Topology (ADR-24): four servers, two per environment.
#
#            dev private net 10.20.10.0/24        prd private net 10.20.20.0/24
#            ─────────────────────────────        ─────────────────────────────
#   dev-app .10 ──┐                         prd-app .10 ──┐
#     caddy       │                           caddy       │
#     api         │  5432                     api         │  5432
#     scanner     │                           scanner     │
#     dashboard   │                           dashboard   │
#     mailpit     └──▶ .20 dev-db              └───────────┴──▶ .20 prd-db
#                        postgres (bare)                          postgres (bare)
#
# No route exists between the two networks. dev cannot reach prd's database at
# any layer — that isolation is the thing the second pair of machines buys.
# =============================================================================

provider "hcloud" {} # HCLOUD_TOKEN from the environment

provider "cloudflare" {} # CLOUDFLARE_API_TOKEN from the environment

locals {
  # Exactly one of each per environment — guaranteed by the validations on var.servers, so these
  # comprehensions cannot silently collapse two entries into one.
  app_private_ip = { for name, s in var.servers : s.environment => s.private_ip if s.role == "app" }
  db_private_ip  = { for name, s in var.servers : s.environment => s.private_ip if s.role == "db" }

  app_servers = { for name, s in var.servers : name => s if s.role == "app" }
  db_servers  = { for name, s in var.servers : name => s if s.role == "db" }
}

# ----------------------------------------------------------------------------- SSH keys

resource "hcloud_ssh_key" "admin" {
  name       = "cookiekeeper-admin"
  public_key = var.ssh_public_key
}

# Installed on app servers only — see the variable's description for why CI has no database login.
resource "hcloud_ssh_key" "ci_deploy" {
  name       = "cookiekeeper-ci-deploy"
  public_key = var.ci_deploy_public_key
}

# ----------------------------------------------------------------------------- Private networks

resource "hcloud_network" "env" {
  for_each = var.private_networks

  name     = "cookiekeeper-${each.key}"
  ip_range = each.value

  labels = {
    project     = "cookiekeeper"
    environment = each.key
    managed     = "terraform"
  }
}

resource "hcloud_network_subnet" "env" {
  for_each = var.private_networks

  network_id   = hcloud_network.env[each.key].id
  type         = "cloud"
  network_zone = var.network_zone
  ip_range     = each.value
}

# ----------------------------------------------------------------------------- Servers

resource "hcloud_server" "node" {
  for_each = var.servers

  name        = each.key
  server_type = each.value.server_type
  image       = var.image
  location    = var.location

  # A database host has no business accepting the CI deploy key. `deploy` exists to run
  # `docker compose` on an app host; nothing in the pipeline touches Postgres directly.
  ssh_keys = each.value.role == "app" ? [hcloud_ssh_key.admin.id, hcloud_ssh_key.ci_deploy.id] : [hcloud_ssh_key.admin.id]

  public_net {
    # Public IPv4 stays on the database hosts for two unglamorous reasons: `apt` updates and the
    # rclone push of encrypted dumps to Hetzner Object Storage. Nothing listens on it — the cloud
    # firewall below admits only SSH, and Postgres binds the private address only.
    ipv4_enabled = true
    ipv6_enabled = true
  }

  user_data = each.value.role == "app" ? templatefile("${path.module}/cloud-init-app.yaml", {
    environment = each.value.environment
    }) : templatefile("${path.module}/cloud-init-db.yaml", {
    environment    = each.value.environment
    db_private_ip  = each.value.private_ip
    app_private_ip = local.app_private_ip[each.value.environment]
  })

  labels = {
    project     = "cookiekeeper"
    environment = each.value.environment
    role        = each.value.role
    managed     = "terraform"
  }

  lifecycle {
    # Applies to all four instances — `prevent_destroy` takes a literal, not an expression, so it
    # cannot be relaxed for dev alone. That is the right default anyway: the dev database holds real
    # test state and rebuilding it silently is never what you meant. Delete the line deliberately
    # when a rebuild IS the intent, and run a restore first (infra/scripts/restore-drill.sh).
    prevent_destroy = true

    ignore_changes = [
      # cloud-init only ever runs on first boot. Editing the templates later would show a permanent
      # diff that, if applied, recreates the machine for no benefit. Post-creation changes belong in
      # infra/scripts/server-setup.md.
      user_data,
    ]
  }
}

resource "hcloud_server_network" "node" {
  for_each = var.servers

  server_id  = hcloud_server.node[each.key].id
  network_id = hcloud_network.env[each.value.environment].id
  ip         = each.value.private_ip

  # The subnet must exist before an address inside it can be claimed; Terraform cannot infer this
  # from the network_id reference alone.
  depends_on = [hcloud_network_subnet.env]
}

# ----------------------------------------------------------------------------- Firewalls
#
# IMPORTANT: a Hetzner cloud firewall filters PUBLIC traffic only. It does not see packets on the
# private network, so it is structurally incapable of restricting who may reach Postgres on
# 10.20.x.20:5432. That restriction lives in three places on the database host instead, none of
# which is this file: ufw (private interface), postgres `listen_addresses`, and pg_hba.conf. All
# three are rendered by cloud-init-db.yaml.
#
# The corollary is the rule that is deliberately ABSENT below: 5432 is never opened publicly. If it
# were, the private-network restrictions would be decoration.

resource "hcloud_firewall" "app" {
  name = "cookiekeeper-app-fw"

  # Inbound only. Egress from the host is unrestricted here; *container* egress is separately locked
  # down by the two-chain iptables firewall in infra/scripts/egress-firewall.sh (ADR-18), which
  # Hetzner's cloud firewall cannot express because it does not see container traffic.

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "22"
    source_ips = var.admin_ssh_cidrs
  }

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "80"
    source_ips = ["0.0.0.0/0", "::/0"]
  }

  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "443"
    source_ips = ["0.0.0.0/0", "::/0"]
  }

  # ICMP: keep the box pingable for the uptime monitor and for basic reachability triage.
  rule {
    direction  = "in"
    protocol   = "icmp"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
}

resource "hcloud_firewall" "db" {
  name = "cookiekeeper-db-fw"

  # A database host serves no public traffic at all. SSH is the only way in, and only from the admin
  # ranges — never the wide-open default the app servers tolerate for CI, because nothing automated
  # logs in here.
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "22"
    source_ips = var.admin_ssh_cidrs
  }

  rule {
    direction  = "in"
    protocol   = "icmp"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
}

resource "hcloud_firewall_attachment" "app" {
  firewall_id = hcloud_firewall.app.id
  server_ids  = [for name in keys(local.app_servers) : hcloud_server.node[name].id]
}

resource "hcloud_firewall_attachment" "db" {
  firewall_id = hcloud_firewall.db.id
  server_ids  = [for name in keys(local.db_servers) : hcloud_server.node[name].id]
}

# ----------------------------------------------------------------------------- Cloudflare zone settings

# TLS between Cloudflare and our origin. "strict" requires a valid cert on the origin — Caddy
# obtains one from Let's Encrypt via ACME (DEPLOYMENT.md §5). "full" (non-strict) would accept any
# self-signed cert and so accepts a MITM between edge and origin; never downgrade this.
resource "cloudflare_zone_setting" "ssl" {
  zone_id    = var.cloudflare_zone_id
  setting_id = "ssl"
  value      = "strict"
}

resource "cloudflare_zone_setting" "always_use_https" {
  zone_id    = var.cloudflare_zone_id
  setting_id = "always_use_https"
  value      = "on"
}

resource "cloudflare_zone_setting" "min_tls_version" {
  zone_id    = var.cloudflare_zone_id
  setting_id = "min_tls_version"
  value      = "1.2"
}

# HSTS. `preload` is intentionally NOT enabled: getting onto the preload list is easy and getting
# off it takes months, so it waits until the domain has been stable in production for a while.
resource "cloudflare_zone_setting" "security_header" {
  zone_id    = var.cloudflare_zone_id
  setting_id = "security_header"

  value = {
    strict_transport_security = {
      enabled            = true
      max_age            = 31536000
      include_subdomains = true
      preload            = false
      nosniff            = true
    }
  }
}

resource "cloudflare_zone_setting" "brotli" {
  zone_id    = var.cloudflare_zone_id
  setting_id = "brotli"
  value      = "on"
}

# Off by default at the zone level. Caching is opted into per-hostname by the cache rules in the
# `environments` module, because most of this zone is authenticated dashboard traffic that must
# never be edge-cached.
resource "cloudflare_zone_setting" "cache_level" {
  zone_id    = var.cloudflare_zone_id
  setting_id = "cache_level"
  value      = "standard"
}

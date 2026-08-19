variable "servers" {
  description = <<-EOT
    Every machine in the estate, keyed by hostname. Four of them: an application server and a
    dedicated Postgres server per environment (ADR-24).

    `private_ip` is assigned STATICALLY rather than left to Hetzner's DHCP. Three things read these
    addresses and none of them can wait for a computed value: the db host's pg_hba.conf and ufw
    rules (rendered into cloud-init at create time), the app host's JDBC URL (hand-written into
    .env), and the container egress firewall's narrow 5432 exemption. A dynamic address would make
    all three a post-provision scavenger hunt.

    `server_type` is per-server so the database boxes can be sized independently of the app boxes
    later without touching the app boxes — a resize moves that instance to current Hetzner pricing,
    so the blast radius of a resize should be one machine.
  EOT

  type = map(object({
    environment = string
    role        = string
    server_type = string
    private_ip  = string
  }))

  default = {
    "cookiekeeper-dev-app" = { environment = "dev", role = "app", server_type = "cx22", private_ip = "10.20.10.10" }
    "cookiekeeper-dev-db"  = { environment = "dev", role = "db", server_type = "cx22", private_ip = "10.20.10.20" }
    "cookiekeeper-prd-app" = { environment = "prd", role = "app", server_type = "cx22", private_ip = "10.20.20.10" }
    "cookiekeeper-prd-db"  = { environment = "prd", role = "db", server_type = "cx22", private_ip = "10.20.20.20" }
  }

  validation {
    condition     = alltrue([for s in var.servers : contains(["dev", "prd"], s.environment)])
    error_message = "There are exactly two environments: dev and prd."
  }

  validation {
    condition     = alltrue([for s in var.servers : contains(["app", "db"], s.role)])
    error_message = "role must be 'app' (runs the compose stack) or 'db' (runs bare Postgres)."
  }

  validation {
    # Exactly one db per environment. Two would mean the app host's single JDBC URL silently
    # points at one of them while the other accumulates nothing and gets backed up anyway.
    condition = alltrue([
      for env in ["dev", "prd"] :
      length([for s in var.servers : s if s.environment == env && s.role == "db"]) == 1
    ])
    error_message = "Each environment needs exactly one server with role = \"db\"."
  }

  validation {
    condition = alltrue([
      for env in ["dev", "prd"] :
      length([for s in var.servers : s if s.environment == env && s.role == "app"]) == 1
    ])
    error_message = "Each environment needs exactly one server with role = \"app\"."
  }
}

variable "private_networks" {
  description = <<-EOT
    One Hetzner private network per environment, so dev and prd are not merely different subnets on
    a shared wire — they are separate layer-2 domains with no route between them. This is what makes
    the split worth paying for: dev cannot reach prd's database even if every software control above
    it is misconfigured.

    Must not overlap the docker subnets pinned in infra/compose.*.yml (10.31.10.0/24 dev,
    10.31.20.0/24 prd, 10.31.30.0/24 caddy-net) — an overlap would make the container routing table
    ambiguous and the failure would look like intermittent packet loss, not a config error.
  EOT

  type = map(string)

  default = {
    dev = "10.20.10.0/24"
    prd = "10.20.20.0/24"
  }
}

variable "network_zone" {
  description = "Hetzner network zone containing var.location. fsn1 and nbg1 are both eu-central."
  type        = string
  default     = "eu-central"
}

variable "location" {
  description = <<-EOT
    Hetzner location. MUST stay German (fsn1 Falkenstein / nbg1 Nuremberg) — CLAUDE.md #2 requires
    an ADR before customer data leaves EU infrastructure, and the brand claims German residency
    specifically (ARCHITECTURE.md, Verified Claims).
  EOT
  type        = string
  default     = "fsn1"

  validation {
    condition     = contains(["fsn1", "nbg1"], var.location)
    error_message = "EU/German data residency is non-negotiable: location must be fsn1 or nbg1."
  }
}

variable "image" {
  description = "Base OS image for every server."
  type        = string
  default     = "ubuntu-24.04"
}

variable "ssh_public_key" {
  description = "Your admin SSH public key (the ed25519 one you log in with, not the CI deploy key)."
  type        = string
}

variable "ci_deploy_public_key" {
  description = <<-EOT
    Public half of the SSH key GitHub Actions uses to reach the `deploy` user. Installed on the APP
    servers only. CI has no reason to log into a database host, and the narrower the deploy key's
    reach, the less a leaked one is worth.
  EOT
  type        = string
}

variable "admin_ssh_cidrs" {
  description = <<-EOT
    Source ranges permitted to reach port 22. Leaving this at 0.0.0.0/0 exposes sshd to the whole
    internet; it is the default only because GitHub Actions runners have no stable egress range.
    Narrow it to your own address if you ever move deploys onto a fixed-IP runner.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0", "::/0"]
}

variable "cloudflare_zone_id" {
  description = "Cloudflare zone ID for cookiekeeper.eu."
  type        = string
}

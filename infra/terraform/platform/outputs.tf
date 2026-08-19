# Consumed by the release pipelines (which read this module's state READ-ONLY — they never plan or
# apply it) and by server-setup.md when filling in the hand-written .env files.
#
# These are maps keyed by environment rather than single values, because there is no longer "the"
# server. A pipeline wanting dev's origin does:
#     terraform output -json app_ipv4 | jq -r '.dev'

output "app_ipv4" {
  description = "Public IPv4 of each environment's application server — the A-record target the `environments` module points that environment's hostnames at."
  value       = { for name, s in var.servers : s.environment => hcloud_server.node[name].ipv4_address if s.role == "app" }
}

output "app_ipv6" {
  description = "Public IPv6 of each environment's application server."
  value       = { for name, s in var.servers : s.environment => hcloud_server.node[name].ipv6_address if s.role == "app" }
}

output "db_ipv4" {
  description = <<-EOT
    Public IPv4 of each environment's database server. Present for SSH administration and off-site
    backup egress ONLY — no service listens on it. Never put this address in a JDBC URL; use
    `db_private_ip`, which is the interface Postgres actually binds.
  EOT
  value       = { for name, s in var.servers : s.environment => hcloud_server.node[name].ipv4_address if s.role == "db" }
}

output "db_private_ip" {
  description = <<-EOT
    Private-network address of each environment's Postgres. This is the host in DB_URL on the
    matching app server, and the destination of the narrow 5432 exemption in the container egress
    firewall (EGRESS_DB_TARGETS, infra/scripts/egress-firewall.sh).
  EOT
  value       = { for name, s in var.servers : s.environment => s.private_ip if s.role == "db" }
}

output "app_private_ip" {
  description = "Private-network address of each environment's application server — the only source pg_hba.conf on the matching database host accepts."
  value       = { for name, s in var.servers : s.environment => s.private_ip if s.role == "app" }
}

output "servers" {
  description = "Every machine, for `terraform output servers` at a glance."
  value = {
    for name, s in var.servers : name => {
      environment = s.environment
      role        = s.role
      type        = s.server_type
      public_ipv4 = hcloud_server.node[name].ipv4_address
      private_ip  = s.private_ip
      status      = hcloud_server.node[name].status
    }
  }
}

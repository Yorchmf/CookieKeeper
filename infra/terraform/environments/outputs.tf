output "hostnames" {
  description = "Every hostname this environment serves."
  value       = { for k, v in cloudflare_dns_record.host : k => v.name }
}

output "environment" {
  value = var.environment
}

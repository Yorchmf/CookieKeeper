terraform {
  # >= 1.10 is a hard floor, not a preference: `use_lockfile` (native S3 state locking) landed in
  # 1.10, and Hetzner Object Storage has no DynamoDB equivalent to lock against otherwise.
  required_version = ">= 1.10"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.68"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.23"
    }
  }

  # State in Hetzner Object Storage (Falkenstein) — EU residency, same as everything else.
  # Terraform Cloud is deliberately not used; see infra/terraform/README.md.
  backend "s3" {
    bucket = "cookiekeeper-tfstate"
    key    = "platform.tfstate"
    region = "fsn1"

    endpoints = {
      s3 = "https://fsn1.your-objectstorage.com"
    }

    # Native S3 locking (Terraform >= 1.10) — writes a .tflock object next to the state.
    use_lockfile = true

    # Hetzner is S3-compatible but is not AWS: skip every AWS-specific preflight, and use
    # path-style addressing because the bucket is not a DNS subdomain of the endpoint.
    use_path_style              = true
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    skip_s3_checksum            = true
  }
}

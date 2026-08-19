terraform {
  required_version = ">= 1.10"

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.23"
    }
  }

  # Workspace-aware: Terraform stores each workspace's state under `env:/<workspace>/<key>`, so
  # `dev` and `prd` never share a state file even though they share this bucket and key.
  backend "s3" {
    bucket = "cookiekeeper-tfstate"
    key    = "environments.tfstate"
    region = "fsn1"

    endpoints = {
      s3 = "https://fsn1.your-objectstorage.com"
    }

    use_lockfile                = true
    use_path_style              = true
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    skip_s3_checksum            = true
  }
}

provider "cloudflare" {} # CLOUDFLARE_API_TOKEN from the environment

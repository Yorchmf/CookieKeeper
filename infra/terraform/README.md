# Terraform

Infrastructure as code for CookieKeeper. Two **separate root modules**, deliberately not one:

| Module | What it owns | Who applies it | How often |
|--------|--------------|----------------|-----------|
| `platform/` | The four Hetzner servers, the two private networks, the cloud firewalls, SSH keys, the Cloudflare zone's global settings | **You, by hand** | Rarely — initial build, resize, provider change |
| `environments/` | Per-environment DNS records, cache rules and WAF rate limits | The deploy pipelines | Every release |

## Why two modules and not one

Each environment owns two machines — an app host running the compose stack and a dedicated Postgres
host — on a private network only that pair is attached to (ADR-24, ARCHITECTURE.md §3). If one
Terraform config owned the servers *and* both environments' DNS, then the dev pipeline's
`terraform apply` would hold a plan that could destroy or recreate production's machines. Splitting
means the pipeline's blast radius is a handful of DNS records, and nothing a routine deploy does can
reach a server — least of all a database.

Every instance also carries `prevent_destroy`, which `platform/` can afford because a human runs it
and reads the plan.

`environments/` uses a **workspace per environment** (`dev`, `prd`), so each has its own state file
and a `terraform apply` in one cannot see, let alone modify, the other's resources.

## What Terraform deliberately does NOT own

- **Secrets.** No `.env` value is a Terraform variable. Anything passed as a variable is written to
  state in **plaintext**, so putting the JWT secret or the Stripe key in here would turn the state
  bucket into a credential store. Secrets live only in `/opt/cookiekeeper/<env>/.env`, edited by
  hand on the app host (`chmod 600`). See DEPLOYMENT.md §7.

  This is also why `cloud-init-db.yaml` creates the `cookiekeeper` role with **no password at all**
  rather than a generated one: an operator sets it by hand (DEPLOYMENT.md §11.1). Until they do,
  the database refuses every connection — it fails closed, not open.
- **Postgres configuration after first boot.** `cloud-init-db.yaml` runs once, and `user_data` is in
  `ignore_changes`, so editing it later is a no-op on running machines. Changes to `pg_hba.conf`,
  `listen_addresses` or ufw are made on the host and written down in
  [infra/scripts/server-setup.md](../scripts/server-setup.md).
- **Docker containers and images.** `deploy.sh` + compose own those.
- **The Caddyfile.** It is copied to the server verbatim, not templated — one less indirection when
  you are debugging a 502 at 2am.
- **Object Storage buckets.** Hetzner's buckets are created once in the console; the `hcloud`
  provider does not manage them.

## State

State lives in Hetzner Object Storage (Falkenstein), via Terraform's S3-compatible backend.

Terraform Cloud is **not** used: it is a US-hosted processor, and CLAUDE.md #2 requires an ADR
before any customer-adjacent data leaves EU infrastructure. State contains resource IDs and IP
addresses, so it stays in the EU with everything else.

Locking uses Terraform's native S3 lockfile (`use_lockfile = true`, Terraform ≥ 1.10) — no DynamoDB
table, which Hetzner has no equivalent of anyway.

## Usage

```bash
# One-time, per module
cd infra/terraform/platform
terraform init

# The platform module — run by hand, read the plan carefully
terraform plan  -var-file=terraform.tfvars
terraform apply -var-file=terraform.tfvars

# The environments module — what the pipelines run
cd ../environments
terraform init
terraform workspace select dev || terraform workspace new dev
terraform apply -var-file=dev.tfvars
```

### Credentials

Exported as environment variables, never committed:

```bash
export HCLOUD_TOKEN=...              # Hetzner Cloud API token (Read & Write)
export CLOUDFLARE_API_TOKEN=...      # scoped: Zone:Read, DNS:Edit, Zone Settings:Edit
export AWS_ACCESS_KEY_ID=...         # Hetzner Object Storage S3 credentials (state backend)
export AWS_SECRET_ACCESS_KEY=...
```

## OpenTofu

[OpenTofu](https://opentofu.org) is a drop-in fork of Terraform under MPL-2.0 rather than
Terraform's BUSL-1.1 licence. Everything here works unchanged under it — substitute `tofu` for
`terraform` in the commands above and in `.github/workflows/*`. Nothing in this configuration
depends on which you use; the choice is purely about the licence you want to be bound by.

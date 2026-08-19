#!/usr/bin/env bash
# =============================================================================
# egress-firewall.sh — container egress firewall (ADR-18, blocking deploy gate).
#
# The scanner crawls domains nobody has verified we own (the anonymous public
# scan funnel, ADR-12). `ScanTargetValidator` is the app-layer SSRF guard, but it
# resolves DNS itself and therefore cannot survive a DNS-rebinding race: the
# address it validated is not necessarily the address the browser connects to.
# This script is the layer that does not care what the app believed — it drops
# the packet on the way out.
#
# TWO hooks, because one is not enough:
#   DOCKER-USER   container → somewhere else   (routed, i.e. FORWARD traffic)
#   INPUT         container → this host        (the bridge gateway, every public
#                 address of the box, and anything docker-proxy publishes on
#                 0.0.0.0). Host-destined packets never reach FORWARD, so rules
#                 in DOCKER-USER alone cannot see — let alone block — sshd or a
#                 loop back in through Caddy.
#
# Effect: a container may reach the public internet, the containers its own
# environment wires it to, and its own environment's database — that last one on
# port 5432 and nothing else. Cloud metadata (169.254.169.254), RFC1918, CGNAT,
# loopback, this host and everything else behind the reverse proxy are all
# unreachable.
#
# Since ADR-24 the database is a SEPARATE MACHINE on a Hetzner private network
# (10.20.x), not a sibling container — so reaching it means crossing the bridge
# to the private NIC, straight into the 10.0.0.0/8 bogon DROP below. That is why
# EGRESS_DB_TARGETS exists: one RETURN per environment, scoped to a single source
# subnet, a single /32 and a single port. Everything else about 10.20.x, the
# database host's sshd included, stays dropped.
#
# It is a DENYLIST of reserved/special-use space plus the host — not a strict
# allowlist. Any globally routable address is permitted, which is the point: the
# scanner has to be able to crawl the web.
#
# Fail-closed by construction: the DROPs match on the docker bridge INTERFACE
# (`br-+` matches every user-defined bridge, present and future), not on a source
# subnet, so a network that is renamed, renumbered or created behind our back is
# filtered too. Only the narrow RETURN exemptions are subnet-scoped, and those
# subnets are PINNED in infra/compose.*.yml — keep the defaults below in sync.
#
# Usage:
#   egress-firewall.sh [apply]   install/refresh the rules (idempotent)
#   egress-firewall.sh verify    prove the rules do what they claim
#   egress-firewall.sh remove    tear the rules back out
#
# Env overrides:
#   EGRESS_MESH_SUBNETS      space-separated CIDRs whose members may talk to each
#                            other (one per environment)
#   EGRESS_INGRESS_SUBNETS   space-separated <cidr>=<proxy-ip>; inside such a
#                            network only <proxy-ip> may OPEN a connection
#   EGRESS_DB_TARGETS        space-separated <cidr>=<db-ip>:<port>; that source
#                            subnet may reach that one address on that one port
#   EGRESS_CONTROL_TARGET    <ip> <port> that must stay reachable (verify only)
#
# Must run as root. Install it root-owned OUTSIDE the CI-rsynced tree
# (/usr/local/sbin/cookiekeeper-egress-firewall) so a compromised deploy key cannot
# rewrite the thing that constrains it. See server-setup.md §2.1.
# =============================================================================
set -euo pipefail

FWD_BASE="COOKIEKEEPER-EGRESS"        # hooked into DOCKER-USER
HOST_BASE="COOKIEKEEPER-EGRESS-HOST"  # hooked into INPUT
FWD6_BASE="COOKIEKEEPER-EGRESS6"
HOST6_BASE="COOKIEKEEPER-EGRESS6-HOST"

# Must match the `ipam` blocks in infra/compose.dev.yml / compose.prd.yml and the
# `docker network create --subnet` for caddy-net (server-setup.md §4).
#
# Both environments are listed even though each app host now runs only ONE of
# them (ADR-24). That is deliberate: the file is then byte-identical on both
# machines, with no per-host config to drift or be forgotten, and a rule naming a
# subnet that does not exist on this box matches nothing. Getting it wrong in the
# other direction — a missing entry — is what breaks an environment, so both stay.
MESH_SUBNETS=${EGRESS_MESH_SUBNETS:-"10.31.10.0/24 10.31.20.0/24"}
INGRESS_SUBNETS=${EGRESS_INGRESS_SUBNETS:-"10.31.30.0/24=10.31.30.2"}

# <container subnet>=<database ip>:<port>. The database addresses are the `db`
# entries of `servers` in infra/terraform/platform/variables.tf; renumbering there
# means editing here. Source-scoped, so even on a box that somehow had both
# stacks, dev's containers could not open a connection to prd's database.
DB_TARGETS=${EGRESS_DB_TARGETS:-"10.31.10.0/24=10.20.10.20:5432 10.31.20.0/24=10.20.20.20:5432"}

# `br-+` is an iptables prefix wildcard: every docker user-defined bridge is
# `br-<netid>`. `docker0` is the legacy default bridge — a bare `docker run`
# lands there, so it has to be covered too.
readonly BRIDGES=(br-+ docker0)

# Everything a crawler has no business reaching. Wider than RFC1918 on purpose:
# every reserved and special-use block is denied up front rather than enumerated
# one incident at a time.
readonly BOGONS=(
  0.0.0.0/8          # "this network"
  10.0.0.0/8         # RFC1918 — also every pinned cookiekeeper subnet
  100.64.0.0/10      # CGNAT
  127.0.0.0/8        # loopback
  169.254.0.0/16     # link-local — cloud metadata lives at 169.254.169.254
  172.16.0.0/12      # RFC1918 (also docker's default allocation pool)
  192.0.0.0/24       # IETF protocol assignments
  192.0.2.0/24       # TEST-NET-1
  192.31.196.0/24    # AS112-v4
  192.52.193.0/24    # AMT
  192.88.99.0/24     # 6to4 relay anycast (deprecated)
  192.168.0.0/16     # RFC1918
  192.175.48.0/24    # AS112 direct delegation
  198.18.0.0/15      # benchmarking
  198.51.100.0/24    # TEST-NET-2
  203.0.113.0/24     # TEST-NET-3
  224.0.0.0/4        # multicast
  240.0.0.0/4        # reserved (includes 255.255.255.255)
)

# A blocked attempt is a security signal (an SSRF guard was bypassed, or a rebind
# landed), so it is logged — but rate-limited per rule, because the same signal
# repeated 10k times is a full disk, not an alert. Worst case here is
# len(BOGONS) x len(BRIDGES) buckets; keep the per-bucket rate low accordingly.
readonly LOG_LIMIT=(-m limit --limit 2/min --limit-burst 5)

log() { echo "[egress-firewall] $*"; }
die() { echo "[egress-firewall] ERROR: $*" >&2; exit 1; }

require_root() { [[ ${EUID} -eq 0 ]] || die "must run as root"; }

# -w: dockerd rewrites iptables on every container start, and this script runs
# every couple of minutes. Without the lock wait a collision aborts a rebuild.
ipt() { iptables -w 5 "$@"; }
ipt6() { ip6tables -w 5 "$@"; }

# --- chain construction ------------------------------------------------------

# Rules shared by every chain: let replies through, and keep ICMP working.
# RELATED is deliberately narrowed to ICMP. The usual `ESTABLISHED,RELATED` form
# also honours conntrack helper expectations — and the scanner connects to
# servers chosen by whoever asked for the scan, so an FTP/SIP helper would let
# one of them conjure a RELATED exemption to an arbitrary address.
prologue() {
  local ipt=$1 chain=$2 icmp=$3
  "$ipt" -A "$chain" -m conntrack --ctstate ESTABLISHED -j RETURN
  "$ipt" -A "$chain" -p "$icmp" -m conntrack --ctstate RELATED -j RETURN
}

build_forward() {
  local ipt=$1 chain=$2 subnet spec proxy_ip iface bogon endpoint db_ip db_port
  prologue "$ipt" "$chain" icmp

  # The app's own wiring: api → dashboard, and whatever else one environment's
  # containers need from each other. Scoped to one subnet, and each environment
  # has its own, so dev cannot reach prd.
  for subnet in $MESH_SUBNETS; do
    "$ipt" -A "$chain" -s "$subnet" -d "$subnet" -j RETURN
  done

  # api/scanner → the environment's database host. This has to be built BEFORE
  # the bogon loop: the database lives at 10.20.x, which 10.0.0.0/8 would
  # otherwise drop. Narrow on all three axes — one source subnet, one /32, one
  # TCP port — so the hole is "this stack may speak Postgres to its own database"
  # and not "this stack may reach the private network".
  for spec in $DB_TARGETS; do
    subnet=${spec%%=*}
    endpoint=${spec#*=}
    db_ip=${endpoint%%:*}
    db_port=${endpoint##*:}
    [[ "$subnet" != "$spec" && "$db_ip" != "$endpoint" && -n "$db_ip" && -n "$db_port" ]] \
      || die "bad db target '${spec}' (want <cidr>=<ip>:<port>)"
    "$ipt" -A "$chain" -s "$subnet" -d "${db_ip}/32" -p tcp --dport "$db_port" -j RETURN
  done

  # caddy-net exists so ONE reverse proxy can reach upstreams, and it is SHARED
  # by dev and prd. A blanket intra-subnet allow there would let a rebound
  # scanner reach every vhost behind Caddy and every container of the other
  # environment. Only the proxy may open a connection; upstreams answering it
  # are ESTABLISHED and matched above, and they never need to initiate.
  for spec in $INGRESS_SUBNETS; do
    subnet=${spec%%=*}
    proxy_ip=${spec#*=}
    [[ "$subnet" != "$spec" && -n "$proxy_ip" ]] \
      || die "bad ingress spec '${spec}' (want <cidr>=<proxy-ip>)"
    "$ipt" -A "$chain" -s "${proxy_ip}/32" -d "$subnet" -j RETURN
  done

  for iface in "${BRIDGES[@]}"; do
    for bogon in "${BOGONS[@]}"; do
      "$ipt" -A "$chain" -i "$iface" -d "$bogon" "${LOG_LIMIT[@]}" \
        -j LOG --log-prefix "cookiekeeper-egress-drop: " --log-level warning
      "$ipt" -A "$chain" -i "$iface" -d "$bogon" -j DROP
    done
  done
}

# Container → THIS host. Still a blanket drop on the bridge interfaces: nothing in
# the stack needs a service on this machine. The database exemption above does not
# weaken it — that is a FORWARD rule to another host's address, and host-destined
# packets never reach FORWARD. A container asking this box for 5432 gets nothing,
# which is correct: there is no Postgres here to ask.
build_host() {
  local ipt=$1 chain=$2 iface
  prologue "$ipt" "$chain" icmp
  for iface in "${BRIDGES[@]}"; do
    "$ipt" -A "$chain" -i "$iface" "${LOG_LIMIT[@]}" \
      -j LOG --log-prefix "cookiekeeper-host-drop: " --log-level warning
    "$ipt" -A "$chain" -i "$iface" -j DROP
  done
}

# IPv6 is meant to be OFF on every docker network (ADR-18). This turns that from
# a convention documented in a runbook into a rule: enable it by accident and
# containers get no v6 egress at all, rather than a silent path around every
# rule above. NDP/PMTUD stay open so nothing breaks in a confusing way.
build_v6() {
  local ipt=$1 chain=$2 iface
  "$ipt" -A "$chain" -m conntrack --ctstate ESTABLISHED -j RETURN
  "$ipt" -A "$chain" -p ipv6-icmp -j RETURN
  for iface in "${BRIDGES[@]}"; do
    "$ipt" -A "$chain" -i "$iface" -j DROP
  done
}

# --- atomic install ----------------------------------------------------------

# Which slot is live, i.e. jumped to FIRST from <parent> — empty for none.
# Order matters, not mere presence: a run that died between inserting the new
# jump and removing the old leaves both, and the one at the lower position is
# the one packets actually hit. Picking that one means the next rebuild targets
# the stale slot instead of flushing the live one.
active_slot() {
  local ipt=$1 parent=$2 base=$3
  "$ipt" -S "$parent" 2>/dev/null | sed -n "s/.* -j ${base}-\([AB]\)\$/\1/p" | head -1
}

# Build into the unused slot, then move the jump. Flushing the live chain in
# place would leave a window where it is jumped-to but empty — and because
# ESTABLISHED is exempt, any connection opened during that window stays exempt
# for its whole life, which for a Playwright session is a long time. If the
# build fails, `set -e` aborts before the jump moves and the previous rules stay
# in force: an error here fails closed, not open.
install_chain() {
  local ipt=$1 parent=$2 base=$3 builder=$4
  local cur next
  cur=$(active_slot "$ipt" "$parent" "$base")
  if [[ "$cur" == "A" ]]; then next=B; else next=A; fi

  local new="${base}-${next}"
  "$ipt" -N "$new" 2>/dev/null || "$ipt" -F "$new"
  "$builder" "$ipt" "$new"

  "$ipt" -I "$parent" 1 -j "$new"
  [[ -n "$cur" ]] || return 0

  local old="${base}-${cur}"
  while "$ipt" -D "$parent" -j "$old" 2>/dev/null; do :; done
  "$ipt" -F "$old" 2>/dev/null || true
  "$ipt" -X "$old" 2>/dev/null || true
}

drop_chain() {
  local ipt=$1 parent=$2 base=$3 slot
  for slot in A B; do
    while "$ipt" -D "$parent" -j "${base}-${slot}" 2>/dev/null; do :; done
    "$ipt" -F "${base}-${slot}" 2>/dev/null || true
    "$ipt" -X "${base}-${slot}" 2>/dev/null || true
  done
}

# --- apply -------------------------------------------------------------------

# Serialise runs. systemd already orders the timer against the docker.service
# trigger, but an operator running `apply` by hand during a timer tick would have
# two rebuilds computing the same spare slot.
LOCKFILE=/run/cookiekeeper-egress-firewall.lock
take_lock() {
  command -v flock >/dev/null 2>&1 || return 0
  exec 9>"$LOCKFILE" || die "cannot open ${LOCKFILE}"
  flock -w 30 9 || die "another egress-firewall run holds the lock"
}

apply_rules() {
  require_root
  take_lock
  command -v iptables >/dev/null || die "iptables not found"
  # DOCKER-USER is the one chain docker guarantees it will not rewrite. Its
  # absence means either docker has not started yet or it is running an
  # nftables-native backend this script does not hook — both must be loud.
  ipt -S DOCKER-USER >/dev/null 2>&1 \
    || die "DOCKER-USER chain missing — is dockerd up, and using the iptables backend?"

  # No docker API calls in this path on purpose: the subnets are pinned in the
  # compose files, so the rules can be built before a single network exists and
  # do not go stale (or briefly wrong) while a stack is coming up.
  install_chain ipt DOCKER-USER "$FWD_BASE" build_forward
  install_chain ipt INPUT "$HOST_BASE" build_host

  apply_v6

  log "applied — forward $(ipt -S "${FWD_BASE}-$(active_slot ipt DOCKER-USER "$FWD_BASE")" | wc -l) rules," \
      "host $(ipt -S "${HOST_BASE}-$(active_slot ipt INPUT "$HOST_BASE")" | wc -l) rules"
}

apply_v6() {
  # A functional probe, not `command -v`: ip6tables is installed almost
  # everywhere, but on a box booted with `ipv6.disable=1` every call fails —
  # and aborting there would leave the unit permanently failed after the v4
  # rules were already installed correctly.
  if ! command -v ip6tables >/dev/null 2>&1 || ! ip6tables -w 5 -S INPUT >/dev/null 2>&1; then
    log "IPv6 filtering unavailable (no ip6tables, or IPv6 disabled) — skipping the v6 backstop"
    return 0
  fi
  # The v6 DOCKER-USER only exists when docker's ip6tables support is on. When it
  # is off no container v6 traffic is forwarded anyway, so INPUT alone is enough.
  if ip6tables -S DOCKER-USER >/dev/null 2>&1; then
    install_chain ipt6 DOCKER-USER "$FWD6_BASE" build_v6
  fi
  install_chain ipt6 INPUT "$HOST6_BASE" build_v6
}

# --- remove ------------------------------------------------------------------

remove_rules() {
  require_root
  take_lock
  drop_chain ipt DOCKER-USER "$FWD_BASE"
  drop_chain ipt INPUT "$HOST_BASE"
  if command -v ip6tables >/dev/null 2>&1; then
    drop_chain ipt6 DOCKER-USER "$FWD6_BASE"
    drop_chain ipt6 INPUT "$HOST6_BASE"
  fi
  log "removed"
}

# --- verify ------------------------------------------------------------------
#
# Asserts BEHAVIOUR, not rule text: a throwaway container is attached to each
# filtered network and made to try the connections that must fail and the ones
# that must work. Every negative probe targets something that genuinely answers
# when the firewall is absent (host sshd, this box's TLS port, the database
# host's sshd) — a probe at an address nothing listens on would pass on a
# completely unprotected machine and is worse than no check at all.
#
# Not asserted, because it CANNOT be asserted honestly: dev↔prd isolation. Since
# ADR-24 the two environments are different machines on different private
# networks with no route between them, so a probe from here at the other
# environment's database would come back closed with every rule removed. That is
# a probe that proves nothing. The isolation is now physical; what remains
# testable is that this stack's reach into 10.20.x is one address on one port.

VERIFY_IMAGE="alpine:3"
CONTROL_TARGET=${EGRESS_CONTROL_TARGET:-"185.12.64.1 53"}  # Hetzner EU resolver

probe() {
  local net=$1 target=$2 port=$3
  docker run --rm --network "$net" "$VERIFY_IMAGE" \
    sh -c "nc -z -w 3 ${target} ${port} >/dev/null 2>&1 && echo open || echo closed" 2>/dev/null
}

resolves() {
  local net=$1
  docker run --rm --network "$net" "$VERIFY_IMAGE" \
    sh -c "getent hosts example.com >/dev/null 2>&1 && echo ok || echo fail" 2>/dev/null
}

# Stands a throwaway listener up on <net> and probes it from a SECOND throwaway
# container on the same bridge. This is the only assertion that can tell our
# rules apart from docker's own inter-network isolation: two containers on one
# bridge reach each other by default, so "closed" here is ours and nobody
# else's. It is what actually covers the caddy-net policy — every cross-network
# probe would pass with no rules installed at all.
peer_probe() {
  local net=$1 cid ip out
  cid=$(docker run -d --rm --network "$net" "$VERIFY_IMAGE" \
    sh -c 'nc -l -p 5432 >/dev/null 2>&1; sleep 10' 2>/dev/null) || { echo error; return 0; }
  sleep 1  # let busybox nc bind before anything knocks
  ip=$(docker inspect -f "{{(index .NetworkSettings.Networks \"${net}\").IPAddress}}" "$cid" 2>/dev/null || true)
  if [[ -n "$ip" ]]; then out=$(probe "$net" "$ip" 5432); else out=error; fi
  docker rm -f "$cid" >/dev/null 2>&1 || true
  echo "$out"
}

assertions=0
failures=0
expect() {
  local label=$1 want=$2 got=$3
  assertions=$((assertions + 1))
  if [[ "$got" == "$want" ]]; then
    echo "  PASS  ${label} (${got})"
  else
    echo "  FAIL  ${label} — expected ${want}, got ${got:-<no output>}"
    failures=$((failures + 1))
  fi
}

# Docker's view of a network: "<subnet> <gateway>". One record per line, IPv4
# only — a dual-stack network emits several, and the v6 row must not be parsed
# into an iptables argument.
network_v4() {
  local out subnet gateway
  out=$(docker network inspect "$1" \
    --format '{{range .IPAM.Config}}{{.Subnet}} {{.Gateway}}{{"\n"}}{{end}}' 2>/dev/null) || return 1
  while read -r subnet gateway; do
    [[ "$subnet" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}/[0-9]{1,2}$ ]] || continue
    [[ "$gateway" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || continue
    printf '%s %s\n' "$subnet" "$gateway"
    return 0
  done <<<"$out"
  return 1
}

# "<ip>:<port>" for the database <subnet> may reach, or non-zero if it has none.
db_target_for() {
  local subnet=$1 spec
  for spec in $DB_TARGETS; do
    [[ "${spec%%=*}" == "$subnet" ]] || continue
    printf '%s\n' "${spec#*=}"
    return 0
  done
  return 1
}

verify_rules() {
  require_root
  command -v docker >/dev/null || die "docker not found"
  docker info >/dev/null 2>&1 || die "docker daemon not reachable"

  # Structural: the chains exist AND are actually jumped to. A chain full of
  # perfect rules that nothing branches into is the classic silent no-op.
  local fwd_slot host_slot
  fwd_slot=$(active_slot ipt DOCKER-USER "$FWD_BASE")
  host_slot=$(active_slot ipt INPUT "$HOST_BASE")
  [[ -n "$fwd_slot" ]] || die "DOCKER-USER does not jump to ${FWD_BASE} — run 'apply' first"
  [[ -n "$host_slot" ]] || die "INPUT does not jump to ${HOST_BASE} — run 'apply' first"
  echo "chains: ${FWD_BASE}-${fwd_slot} (DOCKER-USER), ${HOST_BASE}-${host_slot} (INPUT)"

  docker pull -q "$VERIFY_IMAGE" >/dev/null 2>&1 || true

  # Discover the live networks by SUBNET rather than by name, so a renamed
  # compose project cannot quietly drop a network out of the checked set.
  local -a want=() kinds=() nets=() subnets=() gateways=() netkinds=()
  local name cidr subnet gateway spec k
  for spec in $MESH_SUBNETS; do want+=("$spec"); kinds+=(mesh); done
  for spec in $INGRESS_SUBNETS; do want+=("${spec%%=*}"); kinds+=(ingress); done

  while read -r name; do
    if ! cidr=$(network_v4 "$name"); then
      log "note: ${name} has no parseable IPv4 IPAM config — not checked"
      continue
    fi
    read -r subnet gateway <<<"$cidr"
    for k in "${!want[@]}"; do
      [[ "$subnet" == "${want[$k]}" ]] || continue
      nets+=("$name"); subnets+=("$subnet"); gateways+=("$gateway"); netkinds+=("${kinds[$k]}")
      break
    done
  done < <(docker network ls --filter driver=bridge --format '{{.Name}}')

  [[ ${#nets[@]} -gt 0 ]] || die "no docker network matches the configured subnets (${MESH_SUBNETS} ${INGRESS_SUBNETS}) — nothing to verify"

  # Same-bridge traffic only reaches iptables when br_netfilter is on. Without
  # it the caddy-net policy is not enforced at all, and no probe below would
  # look any different — so assert it directly.
  expect "bridged frames traverse netfilter (br_netfilter)" 1 \
    "$(sysctl -n net.bridge.bridge-nf-call-iptables 2>/dev/null || echo 0)"

  # A public address of this host. Reaching it from a container would loop back
  # in through Caddy and land inside the trust boundary. Taken from the route to
  # the control target, not the first `ip addr` line — that list also contains
  # every docker gateway, and picking one of those would silently turn the probe
  # below into an address nothing listens on.
  local host_ip
  # shellcheck disable=SC2086  # deliberate split into <ip> <port>
  set -- $CONTROL_TARGET
  host_ip=$(ip -4 route get "$1" 2>/dev/null | sed -n 's/.* src \([0-9.]*\).*/\1/p' | head -1)
  [[ -n "$host_ip" ]] || die "could not determine this host's public address"

  local i endpoint db_ip db_port
  for i in "${!nets[@]}"; do
    echo "network ${nets[$i]} (${subnets[$i]}):"
    expect "cloud metadata unreachable" closed "$(probe "${nets[$i]}" 169.254.169.254 80)"
    expect "docker gateway (host sshd) unreachable" closed "$(probe "${nets[$i]}" "${gateways[$i]}" 22)"
    expect "this host's public IP unreachable" closed "$(probe "${nets[$i]}" "$host_ip" 443)"
    # The control: if this fails the rules are over-blocking and the scanner can
    # crawl nothing, which is as much of a failure as under-blocking.
    # shellcheck disable=SC2086  # deliberate split into <ip> <port>
    expect "public internet reachable" open "$(probe "${nets[$i]}" $CONTROL_TARGET)"
    expect "DNS resolves" ok "$(resolves "${nets[$i]}")"

    # Container-to-container ON THIS BRIDGE. Mesh networks are one environment's
    # own wiring, so it must work. caddy-net is shared by dev and prd and exists
    # only for the proxy, so a container that is not the proxy must not get
    # through — that is the dev/prd isolation the ingress policy buys.
    if [[ "${netkinds[$i]}" == "mesh" ]]; then
      expect "peer container reachable within the environment" open "$(peer_probe "${nets[$i]}")"
    else
      expect "peer container blocked (only the proxy may initiate here)" closed \
        "$(peer_probe "${nets[$i]}")"
    fi

    # The database exemption, both halves. The positive probe is a control — get
    # it wrong and the whole product is down. The negative one is the point of
    # the pair: the database host's ufw allows 22 from anywhere, and sshd is
    # listening, so with our rules gone this probe comes back open. "closed" here
    # is the proof that the exemption is scoped to a PORT and not to a host.
    if endpoint=$(db_target_for "${subnets[$i]}"); then
      db_ip=${endpoint%%:*}
      db_port=${endpoint##*:}
      expect "database ${db_ip}:${db_port} reachable" open \
        "$(probe "${nets[$i]}" "$db_ip" "$db_port")"
      expect "database host ssh (${db_ip}:22) blocked" closed \
        "$(probe "${nets[$i]}" "$db_ip" 22)"
    elif [[ "${netkinds[$i]}" == "mesh" ]]; then
      log "note: no EGRESS_DB_TARGETS entry for ${subnets[$i]} — this stack cannot reach any database, and that is not being verified"
    fi
  done

  [[ $assertions -gt 0 ]] || die "no assertions ran — verify proved nothing"
  [[ $failures -eq 0 ]] || die "${failures}/${assertions} check(s) failed"
  log "all ${assertions} checks passed"
}

case "${1:-apply}" in
  apply) apply_rules ;;
  verify) verify_rules ;;
  remove) remove_rules ;;
  *) die "unknown command: $1 (expected apply|verify|remove)" ;;
esac

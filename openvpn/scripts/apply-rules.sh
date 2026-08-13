#!/bin/bash
# Base firewall for the OpenVPN container (requires NET_ADMIN capability).
#
# Installs NAT (default) or routed-mode forwarding rules once per container start
# and whenever daemon configs change (see entrypoint.sh). Per-client access
# chains are added dynamically by client-connect.sh and torn down by
# client-disconnect.sh, so no static rule references individual clients.
#
# Dual-stack: when OPNL_VPN_POOL6 is set, an ip6tables mirror of the base rules
# (forwarding, NAT, OPNL_DOMAINS6) is installed and IPv6 forwarding is enabled.
#
# Env:
#   OPNL_VPN_POOL      VPN client subnet in CIDR form (required).
#   OPNL_VPN_POOL6     VPN client IPv6 subnet in CIDR form (optional).
#   OPNL_FIREWALL_IFACE  Uplink interface the VPN pool is routed out of (default eth0).
#   OPNL_NETWORK_MODE  "nat" (masquerade) or "routed" (no NAT) (default nat).
set -euo pipefail

iface="${OPNL_FIREWALL_IFACE:-eth0}"
pool="${OPNL_VPN_POOL:-}"
pool6="${OPNL_VPN_POOL6:-}"
mode="${OPNL_NETWORK_MODE:-nat}"

if [[ -z "$pool" ]]; then
    echo "apply-rules: OPNL_VPN_POOL not set; skipping" >&2
    exit 0
fi

if [[ -n "$pool6" ]]; then
    sysctl -w net.ipv6.conf.all.forwarding=1 >/dev/null 2>&1 || true
    sysctl -w net.ipv6.conf.default.forwarding=1 >/dev/null 2>&1 || true
fi

# Idempotent forwarding defaults shared by both modes: return traffic into the
# tunnel and a permissive forward policy (per-client ACCEPT chains narrow it).
if ! iptables -C FORWARD -i "$iface" -o tun+ -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null; then
    iptables -A FORWARD -i "$iface" -o tun+ -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
fi
iptables -P FORWARD ACCEPT

if [[ -n "$pool6" ]]; then
    if ! ip6tables -C FORWARD -i "$iface" -o tun+ -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null; then
        ip6tables -A FORWARD -i "$iface" -o tun+ -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
    fi
    ip6tables -P FORWARD ACCEPT
fi

# Domain matcher chain: one RETURN per pinned domain address taken from the
# backend-generated dnsmasq config (opnl-domains.conf), then DROP. This is the
# address set the per-client rules and the VPN DNS agree on, so a domain rule's
# ALLOW/DENY and the addresses clients actually resolve to stay consistent.
# Inspect with: iptables -L OPNL_DOMAINS.
apply_domain_rules() {
    local conf="${OPNL_CONFIG_DIR:-/etc/opnl/config}/dnsmasq.d/opnl-domains.conf"
    local chain="OPNL_DOMAINS"
    if ! iptables -N "$chain" 2>/dev/null; then
        iptables -F "$chain"
    fi
    local count=0 ip
    if [[ -f "$conf" ]]; then
        while IFS= read -r ip; do
            [[ -n "$ip" ]] && iptables -A "$chain" -d "$ip/32" -j RETURN && count=$((count + 1))
        done < <(grep -oE '^address=/[^/]+/[0-9.]+$' "$conf" | awk -F/ '{print $3}')
    fi
    iptables -A "$chain" -j DROP
    echo "apply-rules: $chain pinned $count domain address(es)"
}

# IPv6 mirror of apply_domain_rules: matches the AAAA pins dnsmasq serves for
# access-rule domains when the primary daemon is dual-stack.
apply_domain_rules6() {
    local conf="${OPNL_CONFIG_DIR:-/etc/opnl/config}/dnsmasq.d/opnl-domains.conf"
    local chain="OPNL_DOMAINS6"
    if ! ip6tables -N "$chain" 2>/dev/null; then
        ip6tables -F "$chain"
    fi
    local count=0 ip
    if [[ -f "$conf" ]]; then
        while IFS= read -r ip; do
            [[ -n "$ip" ]] && ip6tables -A "$chain" -d "$ip/128" -j RETURN && count=$((count + 1))
        done < <(grep -oE '^address=/[^/]+/[0-9a-fA-F:]+$' "$conf" | awk -F/ '{print $3}')
    fi
    ip6tables -A "$chain" -j DROP
    echo "apply-rules: $chain pinned $count domain address(es)"
}

if [[ "$mode" == "routed" ]]; then
    # Routed mode: keep client source IPs, so no MASQUERADE. External networks
    # must route replies for the pool back to this host. Install the explicit
    # return route once the tun device is up; OpenVPN also adds its connected
    # route, so a missing tun is tolerated.
    tun=""
    for _ in $(seq 1 10); do
        tun="$(ip -o link show type tun 2>/dev/null | head -n1 | awk -F': ' '{print $2}')"
        [[ -n "$tun" ]] && break
        sleep 1
    done
    if [[ -n "$tun" ]]; then
        ip route replace "$pool" dev "$tun" 2>/dev/null || true
        [[ -n "$pool6" ]] && ip -6 route replace "$pool6" dev "$tun" 2>/dev/null || true
        echo "apply-rules: routed mode; return route $pool${pool6:+ / $pool6} via $tun (no NAT)"
    else
        echo "apply-rules: routed mode; no tun device yet, return route deferred to OpenVPN" >&2
    fi
    apply_domain_rules
    if [[ -n "$pool6" ]]; then
        apply_domain_rules6
    fi
    exit 0
fi

# NAT mode (default): source NAT the VPN pool out of the uplink.
if ! iptables -t nat -C POSTROUTING -s "$pool" -o "$iface" -j MASQUERADE 2>/dev/null; then
    iptables -t nat -A POSTROUTING -s "$pool" -o "$iface" -j MASQUERADE
fi

if [[ -n "$pool6" ]]; then
    if ! ip6tables -t nat -C POSTROUTING -s "$pool6" -o "$iface" -j MASQUERADE 2>/dev/null; then
        ip6tables -t nat -A POSTROUTING -s "$pool6" -o "$iface" -j MASQUERADE
    fi
fi

apply_domain_rules
if [[ -n "$pool6" ]]; then
    apply_domain_rules6
fi

echo "apply-rules: NAT + FORWARD configured for $pool${pool6:+ and $pool6} via $iface"

#!/bin/bash
# Base firewall for the OpenVPN container (requires NET_ADMIN capability).
#
# Installs NAT (default) or routed-mode forwarding rules once per container start
# and whenever daemon configs change (see entrypoint.sh). Per-client access
# chains are added dynamically by client-connect.sh and torn down by
# client-disconnect.sh, so no static rule references individual clients.
#
# Env:
#   OPNL_VPN_POOL      VPN client subnet in CIDR form (required).
#   OPNL_FIREWALL_IFACE  Uplink interface the VPN pool is routed out of (default eth0).
#   OPNL_NETWORK_MODE  "nat" (masquerade) or "routed" (no NAT) (default nat).
set -euo pipefail

iface="${OPNL_FIREWALL_IFACE:-eth0}"
pool="${OPNL_VPN_POOL:-}"
mode="${OPNL_NETWORK_MODE:-nat}"

if [[ -z "$pool" ]]; then
    echo "apply-rules: OPNL_VPN_POOL not set; skipping" >&2
    exit 0
fi

# Idempotent forwarding defaults shared by both modes: return traffic into the
# tunnel and a permissive forward policy (per-client ACCEPT chains narrow it).
if ! iptables -C FORWARD -i "$iface" -o tun+ -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null; then
    iptables -A FORWARD -i "$iface" -o tun+ -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
fi
iptables -P FORWARD ACCEPT

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
        echo "apply-rules: routed mode; return route $pool via $tun (no NAT)"
    else
        echo "apply-rules: routed mode; no tun device yet, return route deferred to OpenVPN" >&2
    fi
    exit 0
fi

# NAT mode (default): source NAT the VPN pool out of the uplink.
if ! iptables -t nat -C POSTROUTING -s "$pool" -o "$iface" -j MASQUERADE 2>/dev/null; then
    iptables -t nat -A POSTROUTING -s "$pool" -o "$iface" -j MASQUERADE
fi

echo "apply-rules: NAT + FORWARD configured for $pool via $iface"

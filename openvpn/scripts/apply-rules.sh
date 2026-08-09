#!/bin/bash
# Base firewall for the OpenVPN container (requires NET_ADMIN capability).
#
# Installs NAT + forwarding defaults once per container start. Per-client access
# chains are added dynamically by client-connect.sh and torn down by
# client-disconnect.sh, so no static rule references individual clients.
set -euo pipefail

iface="${OPNL_FIREWALL_IFACE:-eth0}"
pool="${OPNL_VPN_POOL:-}"

if [[ -z "$pool" ]]; then
    echo "apply-rules: OPNL_VPN_POOL not set; skipping" >&2
    exit 0
fi

# Idempotent base rules: source NAT for the VPN pool and return traffic.
if ! iptables -t nat -C POSTROUTING -s "$pool" -o "$iface" -j MASQUERADE 2>/dev/null; then
    iptables -t nat -A POSTROUTING -s "$pool" -o "$iface" -j MASQUERADE
fi

if ! iptables -C FORWARD -i "$iface" -o tun+ -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT 2>/dev/null; then
    iptables -A FORWARD -i "$iface" -o tun+ -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
fi

iptables -P FORWARD ACCEPT

echo "apply-rules: NAT + FORWARD configured for $pool via $iface"

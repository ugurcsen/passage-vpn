#!/bin/bash
# OpenVPN client-disconnect script — tears down the per-client iptables chain and
# fire-and-forget session logging.
set -uo pipefail

PASSAGE_INTERNAL_BASE_URL="${PASSAGE_INTERNAL_BASE_URL:-http://backend:8080}"

resp="$(curl -sS --max-time 5 \
    -X POST "$PASSAGE_INTERNAL_BASE_URL/internal/disconnect" \
    -H 'Content-Type: application/json' \
    -H 'X-Internal-Token: __INTERNAL_TOKEN__' \
    -d "{\"commonName\":$(jq -Rn --arg v "${common_name:-}" '$v'),\"username\":$(jq -Rn --arg v "${username:-}" '$v'),\"remoteIp\":$(jq -Rn --arg v "${trusted_ip:-}" '$v'),\"virtualIp\":$(jq -Rn --arg v "${ifconfig_pool_remote_ip:-}" '$v'),\"virtualIp6\":$(jq -Rn --arg v "${ifconfig_pool_remote_ip6:-${ifconfig_ipv6_remote:-}}" '$v'),\"bytesSent\":$(jq -Rn --arg v "${bytes_sent:-0}" '$v'),\"bytesReceived\":$(jq -Rn --arg v "${bytes_received:-0}" '$v'),\"duration\":$(jq -Rn --arg v "${time_duration:-0}" '$v')}" \
    || true)" || true

if [[ -n "$resp" ]]; then
    while IFS= read -r cmd; do
        [[ -n "$cmd" ]] || continue
        eval "$cmd" 2>/dev/null || true
    done < <(echo "$resp" | jq -r '.remove[]?' 2>/dev/null || true)
    while IFS= read -r cmd; do
        [[ -n "$cmd" ]] || continue
        eval "$cmd" 2>/dev/null || true
    done < <(echo "$resp" | jq -r '.remove6[]?' 2>/dev/null || true)
fi

exit 0

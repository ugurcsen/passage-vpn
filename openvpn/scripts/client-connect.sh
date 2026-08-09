#!/bin/bash
# OpenVPN client-connect script.
#
# Called after authentication, before the tunnel is established. The backend
# validates user state, allocates a static IP, resolves group subnets and
# access rules. Lines printed to stdout become per-connection config pushes
# (e.g. `push "..."`); exit 1 denies the connection.
set -euo pipefail

OPNL_INTERNAL_BASE_URL="${OPNL_INTERNAL_BASE_URL:-http://backend:8080}"

resp="$(curl -sS --max-time 8 \
    -X POST "$OPNL_INTERNAL_BASE_URL/internal/connect" \
    -H 'Content-Type: application/json' \
    -H 'X-Internal-Token: __INTERNAL_TOKEN__' \
    -d "{\"commonName\":$(jq -Rn --arg v "${common_name:-}" '$v'),\"username\":$(jq -Rn --arg v "${username:-}" '$v'),\"daemonName\":$(jq -Rn --arg v "${daemon_name:-}" '$v'),\"remoteIp\":$(jq -Rn --arg v "${trusted_ip:-}" '$v'),\"virtualIp\":$(jq -Rn --arg v "${ifconfig_pool_remote_ip:-}" '$v'),\"localVirtualIp\":$(jq -Rn --arg v "${ifconfig_local:-}" '$v')}" \
    || true)" || true

if [[ -z "$resp" ]]; then
    echo "INTERNAL: connect backend unreachable" >&2
    exit 1
fi

allowed="$(echo "$resp" | jq -r '.allowed // "false"' 2>/dev/null || echo "false")"
if [[ "$allowed" != "true" ]]; then
    reason="$(echo "$resp" | jq -r '.reason // "connection denied"' 2>/dev/null || echo "connection denied")"
    echo "DENY: $reason" >&2
    exit 1
fi

# Echo any pushed directives returned by the backend.
echo "$resp" | jq -r '.pushes[]?' 2>/dev/null || true
exit 0

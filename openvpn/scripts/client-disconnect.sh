#!/bin/bash
# OpenVPN client-disconnect script — fire-and-forget session logging.
set -uo pipefail

OPNL_INTERNAL_BASE_URL="${OPNL_INTERNAL_BASE_URL:-http://backend:8080}"

curl -sS --max-time 5 \
    -X POST "$OPNL_INTERNAL_BASE_URL/internal/disconnect" \
    -H 'Content-Type: application/json' \
    -H 'X-Internal-Token: __INTERNAL_TOKEN__' \
    -d "{\"commonName\":$(jq -Rn --arg v "${common_name:-}" '$v'),\"username\":$(jq -Rn --arg v "${username:-}" '$v'),\"remoteIp\":$(jq -Rn --arg v "${trusted_ip:-}" '$v'),\"virtualIp\":$(jq -Rn --arg v "${ifconfig_pool_remote_ip:-}" '$v'),\"bytesSent\":$(jq -Rn --arg v "${bytes_sent:-0}" '$v'),\"bytesReceived\":$(jq -Rn --arg v "${bytes_received:-0}" '$v'),\"duration\":$(jq -Rn --arg v "${time_duration:-0}" '$v')}" \
    >/dev/null 2>&1 || true

exit 0

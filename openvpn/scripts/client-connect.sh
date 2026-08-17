#!/bin/bash
# OpenVPN client-connect script.
#
# Called after authentication, before the tunnel is established. The backend
# validates user state and returns config pushes plus per-client iptables rules
# (chain named from the common name). Lines printed to stdout become
# per-connection config pushes (e.g. `push "..."`); exit 1 denies the connection.
set -euo pipefail

PASSAGE_INTERNAL_BASE_URL="${PASSAGE_INTERNAL_BASE_URL:-http://backend:8080}"

# OpenVPN does not export a daemon identity to scripts; derive it from the
# --config path (e.g. /etc/passage/config/daemon-0.conf -> daemon-0).
daemon_name="${daemon_name:-}"
if [[ -z "$daemon_name" && -n "${config:-}" ]]; then
    daemon_name="$(basename "${config}" .conf)"
fi

resp="$(curl -sS --max-time 8 \
    -X POST "$PASSAGE_INTERNAL_BASE_URL/internal/connect" \
    -H 'Content-Type: application/json' \
    -H 'X-Internal-Token: __INTERNAL_TOKEN__' \
    -d "{\"commonName\":$(jq -Rn --arg v "${common_name:-}" '$v'),\"username\":$(jq -Rn --arg v "${username:-}" '$v'),\"daemonName\":$(jq -Rn --arg v "$daemon_name" '$v'),\"remoteIp\":$(jq -Rn --arg v "${trusted_ip:-}" '$v'),\"virtualIp\":$(jq -Rn --arg v "${ifconfig_pool_remote_ip:-}" '$v'),\"virtualIp6\":$(jq -Rn --arg v "${ifconfig_pool_remote_ip6:-${ifconfig_ipv6_remote:-}}" '$v')}" \
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

# Install per-client iptables chains (no-op when the backend returned no rules).
while IFS= read -r cmd; do
    [[ -n "$cmd" ]] || continue
    eval "$cmd"
done < <(echo "$resp" | jq -r '.iptablesApply[]?' 2>/dev/null || true)

while IFS= read -r cmd; do
    [[ -n "$cmd" ]] || continue
    eval "$cmd"
done < <(echo "$resp" | jq -r '.iptablesApply6[]?' 2>/dev/null || true)

# Echo any pushed directives returned by the backend.
echo "$resp" | jq -r '.pushes[]?' 2>/dev/null || true
exit 0

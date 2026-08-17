#!/bin/bash
# OpenVPN learn-address script — records virtual IP assignments for the
# connection registry (used to correlate users <-> IPs in the UI).
# Fire-and-forget; failures are non-fatal.
set -uo pipefail

PASSAGE_INTERNAL_BASE_URL="${PASSAGE_INTERNAL_BASE_URL:-http://backend:8080}"

# learn-address passes: operation, address, common_name
operation="${1:-}"
address="${2:-}"
cn="${3:-}"

curl -sS --max-time 5 \
    -X POST "$PASSAGE_INTERNAL_BASE_URL/internal/learn-address" \
    -H 'Content-Type: application/json' \
    -H 'X-Internal-Token: __INTERNAL_TOKEN__' \
    -d "{\"operation\":$(jq -Rn --arg v "$operation" '$v'),\"address\":$(jq -Rn --arg v "$address" '$v'),\"commonName\":$(jq -Rn --arg v "$cn" '$v')}" \
    >/dev/null 2>&1 || true

exit 0

#!/bin/bash
# auth-user-pass-verify script (OpenVPN `via-env` mode).
#
# Receives credentials via environment:
#   username, password, common_name, trusted_ip, trusted_port
# For MFA, the client uses `static-challenge`; password then contains the
# plaintext password followed by the OTP on a second line (split here).
#
# Contract: exit 0 = authenticated, exit 1 = denied (stderr printed to client).
set -euo pipefail

OPNL_INTERNAL_BASE_URL="${OPNL_INTERNAL_BASE_URL:-http://backend:8080}"

pass1="${password:-}"
otp=""
if [[ "$pass1" == *$'\n'* ]]; then
    otp="${pass1#*$'\n'}"
    pass1="${pass1%%$'\n'*}"
fi

resp="$(curl -sS --max-time 8 \
    -X POST "$OPNL_INTERNAL_BASE_URL/internal/auth/verify" \
    -H 'Content-Type: application/json' \
    -H 'X-Internal-Token: __INTERNAL_TOKEN__' \
    -d "{\"username\":$(jq -Rn --arg v "${username:-}" '$v' 2>/dev/null || echo '"'"${username:-}"'"'),\"password\":$(jq -Rn --arg v "$pass1" '$v' 2>/dev/null || echo '"'"$pass1"'"'),\"otp\":$(jq -Rn --arg v "$otp" '$v' 2>/dev/null || echo '"'"$otp"'"'),\"commonName\":$(jq -Rn --arg v "${common_name:-}" '$v' 2>/dev/null || echo '"'"${common_name:-}"'"'),\"remoteIp\":$(jq -Rn --arg v "${trusted_ip:-}" '$v' 2>/dev/null || echo '"'"${trusted_ip:-}"'"')}" \
    || true)" || true

if [[ -z "$resp" ]]; then
    echo "AUTH: verification backend unreachable" >&2
    exit 1
fi

allowed="$(echo "$resp" | jq -r '.allowed // "false"' 2>/dev/null || echo "false")"
if [[ "$allowed" == "true" ]]; then
    exit 0
fi

reason="$(echo "$resp" | jq -r '.reason // "invalid credentials"' 2>/dev/null || echo "invalid credentials")"
echo "AUTH_FAILED: $reason" >&2
exit 1

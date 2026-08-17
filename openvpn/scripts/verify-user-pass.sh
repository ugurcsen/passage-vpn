#!/bin/bash
# auth-user-pass-verify + client-crresponse script (OpenVPN `via-env` mode).
#
# Phase 1 (script_type=user-pass-verify):
#   Verifies username/password against the backend.
#   When the account requires MFA and no code was supplied, the backend returns
#   a single-use pendingId; the script stashes it in two files:
#     /tmp/passage-pending-<pendingId>  — "username:pendingId" (for Phase 2 lookup)
#     /tmp/passage-pending-latest       — just the pendingId (Phase 2 entry point)
#   and writes an auth-pending file with a crtext prompt and exits 2, so
#   auth-pending capable clients (OpenVPN Connect, OpenVPN 3) prompt for the
#   TOTP code.
#
# Phase 2 (script_type=client-crresponse):
#   OpenVPN writes the client's base64-encoded response to a temporary file
#   and passes the path as argv[1]. The code is decoded, combined with the
#   phase-1 pendingId and validated via the backend; the verdict is written to
#   $auth_control_file ("1" allow, "0" deny) and the script exits 0.
#
#   IMPORTANT: OpenVPN does NOT export $username to client-crresponse scripts
#   (only $common_name from the client certificate).  For GENERIC profiles
#   (no client cert) $common_name is empty, so we rely on the pending files
#   written in Phase 1 to recover the username.
#
# Contract: exit 0 = authenticated/controlled, 1 = denied, 2 = auth pending.
set -euo pipefail

PASSAGE_INTERNAL_BASE_URL="${PASSAGE_INTERNAL_BASE_URL:-http://backend:8080}"

call_internal() { # $1 = endpoint path, $2 = JSON body
    curl -sS --max-time 8 \
        -X POST "$PASSAGE_INTERNAL_BASE_URL$1" \
        -H 'Content-Type: application/json' \
        -H 'X-Internal-Token: __INTERNAL_TOKEN__' \
        -d "$2"
}

if [[ "${script_type:-}" == "client-crresponse" ]]; then
    # Phase 2: validate the TOTP from the auth-pending challenge.
    #
    # OpenVPN does NOT export $username to client-crresponse scripts — only
    # $common_name (from the client certificate CN).  For GENERIC profiles
    # (no client cert) $common_name is empty, so we cannot derive the
    # username from the environment alone.
    #
    # Instead Phase 1 writes two files:
    #   /tmp/passage-pending-<pendingId>  — contains "username:pendingId"
    #   /tmp/passage-pending-latest       — contains just the pendingId
    # Phase 2 reads the latest pendingId, looks up the full pending data,
    # and extracts both username and pendingId from it.
    otp=""
    resp_file="${1:-}"
    if [[ -r "$resp_file" ]]; then
        otp="$(tr -d '\r\n' < "$resp_file" | base64 -d 2>/dev/null || true)"
    fi

    auth_user=""
    pending_id=""
    latest_file="/tmp/passage-pending-latest"
    if [[ -r "$latest_file" ]]; then
        pending_id="$(cat "$latest_file" 2>/dev/null || true)"
        rm -f "$latest_file"
        if [[ -n "$pending_id" ]]; then
            pending_file="/tmp/passage-pending-$pending_id"
            if [[ -r "$pending_file" ]]; then
                data="$(cat "$pending_file" 2>/dev/null || true)"
                rm -f "$pending_file"
                auth_user="${data%%:*}"
                pending_id="${data#*:}"
            fi
        fi
    fi

    # Security: if a client certificate is present, its CN must match the
    # username that was authenticated in phase 1.  A mismatch indicates a
    # possible certificate-reuse attack — deny silently.
    if [[ -n "${common_name:-}" && -n "$auth_user" && "$common_name" != "$auth_user" ]]; then
        if [[ -n "${auth_control_file:-}" ]]; then
            printf '0' > "$auth_control_file"
        fi
        exit 0
    fi

    # Fallback: if we still have no username, try common_name (works for
    # USER_LOCKED / SERVER_LOCKED profiles where CN == username).
    if [[ -z "$auth_user" ]]; then
        auth_user="${common_name:-${username:-}}"
    fi

    body="$(jq -n --arg u "$auth_user" --arg o "$otp" --arg r "${trusted_ip:-}" --arg p "$pending_id" \
        '{username:$u, otp:$o, remoteIp:$r, pendingId:$p}' 2>/dev/null || true)"
    resp="$(call_internal "/internal/auth/verify-otp" "$body" || true)"
    allowed="$(printf '%s' "$resp" | jq -r '.allowed // "false"' 2>/dev/null || echo "false")"
    if [[ -n "${auth_control_file:-}" ]]; then
        if [[ "$allowed" == "true" ]]; then
            printf '1' > "$auth_control_file"
        else
            printf '0' > "$auth_control_file"
        fi
    fi
    exit 0
fi

# ---- Phase 1: user-pass-verify ----
pass1="${password:-}"
otp=""
if [[ "$pass1" == *$'\n'* ]]; then
    otp="${pass1#*$'\n'}"
    pass1="${pass1%%$'\n'*}"
fi

body="$(jq -n --arg u "${username:-}" --arg p "$pass1" --arg o "$otp" \
    --arg c "${common_name:-}" --arg r "${trusted_ip:-}" \
    '{username:$u, password:$p, otp:$o, commonName:$c, remoteIp:$r}' 2>/dev/null || true)"

resp="$(call_internal "/internal/auth/verify" "$body" || true)"

if [[ -z "$resp" ]]; then
    echo "AUTH: verification backend unreachable" >&2
    exit 1
fi

allowed="$(printf '%s' "$resp" | jq -r '.allowed // "false"' 2>/dev/null || echo "false")"
if [[ "$allowed" == "true" ]]; then
    exit 0
fi

reason="$(printf '%s' "$resp" | jq -r '.reason // "invalid credentials"' 2>/dev/null || echo "invalid credentials")"

if [[ "$reason" == "mfa_required" ]]; then
    if [[ -n "${auth_pending_file:-}" ]]; then
        # Stash the phase-1 nonce so phase 2 can redeem it (120s TTL, matching
        # the backend's PENDING_VPN_AUTH_TTL_SECONDS).
        #
        # Phase 2 (client-crresponse) does NOT receive $username — only
        # $common_name from the client certificate.  For GENERIC profiles
        # (no client cert) $common_name is empty, so we cannot key the
        # pending file by username.  Instead we key by pendingId and write
        # the pendingId to a well-known file that Phase 2 can always read.
        if [[ -n "${username:-}" ]]; then
            pending_id="$(printf '%s' "$resp" | jq -r '.pendingId // ""' 2>/dev/null || true)"
            if [[ -n "$pending_id" ]]; then
                umask 077
                printf '%s:%s' "$username" "$pending_id" > "/tmp/passage-pending-$pending_id"
                printf '%s' "$pending_id" > "/tmp/passage-pending-latest"
                chmod 600 "/tmp/passage-pending-$pending_id" "/tmp/passage-pending-latest"
            fi
        fi
        # Signal auth-pending: 120s timeout, crtext method, prompt text.
        printf '120\ncrtext\nCR_TEXT:E,R:Please enter your TOTP code!\n' > "$auth_pending_file"
        exit 2
    fi
    echo "AUTH_FAILED: mfa_required (client does not support auth-pending)" >&2
    exit 1
fi

echo "AUTH_FAILED: $reason" >&2
exit 1

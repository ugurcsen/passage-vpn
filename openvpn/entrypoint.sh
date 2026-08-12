#!/bin/bash
# OpenVPN container entrypoint.
#
# Starts one openvpn daemon per generated daemon-N.conf (multi-daemon support).
# If no configs exist yet (first run / not configured), stays idle so the
# backend can write configs and signal reloads later without container restart.
set -e

mkdir -p "$OPNL_CONFIG_DIR" "$OPNL_CCD_DIR" "$OPNL_PKI_DIR" "$OPNL_LOG_DIR"

# --- Base firewall ---
mask_to_prefix() {
    local m="$1" bits=0 o
    IFS=. read -r a b c d <<<"$m"
    for o in "$a" "$b" "$c" "$d"; do
        while (( o > 0 )); do bits=$((bits + o % 2)); o=$((o / 2)); done
    done
    echo "$bits"
}

extract_pool() {
    local conf="$1"
    local net mask
    net="$(grep -m1 '^server ' "$conf" | awk '{print $2}')"
    mask="$(grep -m1 '^server ' "$conf" | awk '{print $3}')"
    [[ -n "$net" && -n "$mask" ]] && echo "$net/$(mask_to_prefix "$mask")"
}

extract_mode() {
    local conf="$1" mode
    mode="$(grep -m1 '^# network-mode ' "$conf" 2>/dev/null | awk '{print $3}')"
    echo "${mode:-nat}"
}

# (Re)applies the base firewall for the first daemon's pool. Runs after daemons
# are up so routed mode can install its tun return route; re-run on config
# reloads so pool/mode changes refresh NAT or return routes.
reapply_rules() {
    local conf pool mode
    conf="$(ls "$OPNL_CONFIG_DIR"/daemon-*.conf 2>/dev/null | head -1 || true)"
    [[ -z "$conf" ]] && return 0
    pool="$(extract_pool "$conf" 2>/dev/null || true)"
    [[ -z "$pool" ]] && return 0
    mode="$(extract_mode "$conf" 2>/dev/null || true)"
    OPNL_VPN_POOL="$pool" OPNL_NETWORK_MODE="$mode" /etc/openvpn/scripts/apply-rules.sh || true
}

start_daemon() {
    local conf="$1"
    local name
    name="$(basename "$conf" .conf)"
    openvpn --config "$conf" \
        --daemon "$name" \
        --log-append "$OPNL_LOG_DIR/$name.log" \
        --status "$OPNL_LOG_DIR/$name.status" 5 \
        --writepid "$OPNL_LOG_DIR/$name.pid" || {
        echo "[entrypoint] ERROR starting $name (config: $conf)" >&2
        return 1
    }
    echo "[entrypoint] started $name (pid $(cat "$OPNL_LOG_DIR/$name.pid"))"
}

shopt -s nullglob

conf_sig() {
    local sig="" conf
    for conf in "$OPNL_CONFIG_DIR"/daemon-*.conf; do
        [ -f "$conf" ] && sig+="$(md5sum "$conf" | cut -d' ' -f1)"
    done
    echo "$sig"
}

restart_all() {
    # SIGTERM running daemons gracefully; configs are re-read on next connect.
    for pidfile in "$OPNL_LOG_DIR"/daemon-*.pid; do
        [ -f "$pidfile" ] && kill -TERM "$(cat "$pidfile")" 2>/dev/null || true
    done
    sleep 1
    for conf in "$OPNL_CONFIG_DIR"/daemon-*.conf; do
        start_daemon "$conf" || true
    done
    reapply_rules
}

# Start whatever is present on boot.
boot_sig=""
for conf in "$OPNL_CONFIG_DIR"/daemon-*.conf; do
    start_daemon "$conf"
    boot_sig="$(conf_sig)"
done

# Base firewall (NAT or routed) after daemons are up so routed mode can install
# its return route; re-applied on every config reload via restart_all.
reapply_rules

if [ -z "$boot_sig" ]; then
    echo "[entrypoint] no daemon configs found; waiting for backend to provision."
fi

# Watch the shared config volume: when the backend writes/updates daemon configs
# (first-run wizard, settings changes), reload the daemons without a restart.
(
    last_sig="$boot_sig"
    while :; do
        cur="$(conf_sig)"
        if [ -n "$cur" ] && [ "$cur" != "$last_sig" ]; then
            echo "[entrypoint] daemon config changed; restarting daemons"
            restart_all
            last_sig="$cur"
        fi
        sleep 2
    done
) &

# Keep the container alive; a config watcher can later trigger restart_all.
trap 'restart_all' USR1

echo "[entrypoint] ready"
tail -f /dev/null &
wait

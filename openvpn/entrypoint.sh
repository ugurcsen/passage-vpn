#!/bin/bash
# OpenVPN container entrypoint.
#
# Starts one openvpn daemon per generated daemon-N.conf (multi-daemon support).
# If no configs exist yet (first run / not configured), stays idle so the
# backend can write configs and signal reloads later without container restart.
set -e

mkdir -p "$OPNL_CONFIG_DIR" "$OPNL_CCD_DIR" "$OPNL_PKI_DIR" "$OPNL_LOG_DIR"

start_daemon() {
    local conf="$1"
    local name
    name="$(basename "$conf" .conf)"
    openvpn --config "$conf" \
        --daemon "$name" \
        --log-append "$OPNL_LOG_DIR/$name.log" \
        --status "$OPNL_LOG_DIR/$name.status" 5 \
        --writepid "$OPNL_LOG_DIR/$name.pid"
    echo "[entrypoint] started $name (pid $(cat "$OPNL_LOG_DIR/$name.pid"))"
}

shopt -s nullglob
restart_all() {
    # SIGTERM running daemons gracefully; configs are re-read on next connect.
    for pidfile in "$OPNL_LOG_DIR"/daemon-*.pid; do
        [ -f "$pidfile" ] && kill -TERM "$(cat "$pidfile")" 2>/dev/null || true
    done
    sleep 1
    for conf in "$OPNL_CONFIG_DIR"/daemon-*.conf; do
        start_daemon "$conf"
    done
}

# Start whatever is present on boot.
for conf in "$OPNL_CONFIG_DIR"/daemon-*.conf; do
    start_daemon "$conf"
done

if [ -z "$(ls "$OPNL_CONFIG_DIR"/daemon-*.conf 2>/dev/null)" ]; then
    echo "[entrypoint] no daemon configs found; waiting for backend to provision."
fi

# Keep the container alive; a config watcher can later trigger restart_all.
trap 'restart_all' USR1

echo "[entrypoint] ready"
tail -f /dev/null &
wait

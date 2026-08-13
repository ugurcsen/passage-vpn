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

# OpenVPN's `server net mask` assigns the first pool address (network+1, e.g.
# 10.8.0.0/24 -> 10.8.0.1) to the server-side tun device. dnsmasq listens there
# so VPN clients can reach it as their DNS resolver.
server_ip_of() {
    local conf="$1" net mask
    net="$(grep -m1 '^server ' "$conf" | awk '{print $2}')"
    mask="$(grep -m1 '^server ' "$conf" | awk '{print $3}')"
    [[ -z "$net" || -z "$mask" ]] && return 1
    awk -v ip="$net" 'BEGIN{
        split(ip, o, ".");
        v = o[1]*16777216 + o[2]*65536 + o[3]*256 + o[4] + 1;
        printf "%d.%d.%d.%d\n", int(v/16777216)%256, int(v/65536)%256, int(v/256)%256, v%256
    }'
}

# Lists the tun server IP (pool network + 1) of every daemon config. dnsmasq
# listens on all of them so clients of any daemon reach the resolver via their
# own tun gateway (each daemon's config pushes the matching address).
tun_ips_of() {
    local conf ip
    for conf in "$OPNL_CONFIG_DIR"/daemon-*.conf; do
        ip="$(server_ip_of "$conf" 2>/dev/null || true)"
        [ -n "$ip" ] && echo "$ip"
    done | sort -u
}

# Starts dnsmasq once per container boot and SIGHUPs it on later reloads so the
# backend's domain-pinning config (opnl-domains.conf) is re-read. The resolver
# listens on loopback plus every daemon tun server IP; when the set of tun IPs
# changes (new daemon/pool) the process is restarted instead of reloaded.
start_dnsmasq() {
    local ip ips current wanted args pid tries
    mkdir -p "$OPNL_CONFIG_DIR/dnsmasq.d"
    ips=()
    while IFS= read -r ip; do
        [ -n "$ip" ] && ips+=("$ip")
    done < <(tun_ips_of)
    wanted=" --listen-address=127.0.0.1"
    for ip in "${ips[@]}"; do
        wanted+=" --listen-address=$ip"
    done
    wanted="${wanted:1} "
    current=""
    pid="$(cat /var/run/dnsmasq.pid 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        current="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -oE -- '--listen-address=[^ ]+' | tr '\n' ' ')"
    fi
    if [ "$current" = "$wanted" ]; then
        kill -HUP "$pid" 2>/dev/null || true
        echo "[entrypoint] dnsmasq reloaded (pid $pid)"
        return 0
    fi
    [ -n "$pid" ] && kill -TERM "$pid" 2>/dev/null || true
    sleep 1
    args=(--conf-file=/etc/dnsmasq.conf --pid-file=/var/run/dnsmasq.pid --listen-address=127.0.0.1)
    for ip in "${ips[@]}"; do
        args+=(--listen-address="$ip")
    done
    # bind-interfaces fails while a tun IP does not exist yet; the tun comes up
    # a moment after the daemon is started, so retry briefly.
    for tries in $(seq 1 15); do
        if dnsmasq "${args[@]}"; then
            echo "[entrypoint] dnsmasq listening on 127.0.0.1${ips:+ + }${ips[*]}"
            return 0
        fi
        sleep 2
    done
    echo "[entrypoint] WARNING dnsmasq failed to start after retries" >&2
    return 1
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
    start_dnsmasq || true
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

dnsmasq_sig() {
    local sig="" conf
    for conf in "$OPNL_CONFIG_DIR"/dnsmasq.d/*.conf; do
        [ -f "$conf" ] && sig+="$(md5sum "$conf" | cut -d' ' -f1)"
    done
    echo "$sig"
}

# Called when the pinning config (opnl-domains.conf) changes: refreshes the
# OPNL_DOMAINS chain then RESTARTS dnsmasq. A plain SIGHUP would re-read the
# config but keep the stale cache, so clients could keep resolving a domain to
# an old address that no longer matches the firewall.
refresh_dnsmasq_d() {
    local pid
    reapply_rules || true
    pid="$(cat /var/run/dnsmasq.pid 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill -TERM "$pid" 2>/dev/null || true
        sleep 1
    fi
    start_dnsmasq || true
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
# The dnsmasq pinning config (opnl-domains.conf) is watched separately so access
# rule changes refresh the resolver and the OPNL_DOMAINS chain without touching
# the running VPN daemons.
(
    last_sig="$boot_sig"
    last_dnsmasq="$(dnsmasq_sig)"
    while :; do
        cur="$(conf_sig)"
        if [ -n "$cur" ] && [ "$cur" != "$last_sig" ]; then
            echo "[entrypoint] daemon config changed; restarting daemons"
            restart_all
            last_sig="$cur"
        fi
        cur_dnsmasq="$(dnsmasq_sig)"
        if [ -n "$cur_dnsmasq" ] && [ "$cur_dnsmasq" != "$last_dnsmasq" ]; then
            echo "[entrypoint] dnsmasq config changed; refreshing resolver + firewall"
            refresh_dnsmasq_d
            last_dnsmasq="$cur_dnsmasq"
        fi
        sleep 2
    done
) &

# Keep the container alive; a config watcher can later trigger restart_all.
trap 'restart_all' USR1

echo "[entrypoint] ready"
tail -f /dev/null &
wait

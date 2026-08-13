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

# The IPv6 client subnet of a dual-stack daemon config (`server-ipv6 fd00:1::/64`),
# or nothing for IPv4-only daemons.
extract_pool6() {
    local conf="$1" v6
    v6="$(grep -m1 '^server-ipv6 ' "$conf" | awk '{print $2}')"
    [[ -n "$v6" ]] && echo "$v6"
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

# OpenVPN's `server-ipv6 cidr` assigns the network + 1 (e.g. fd00:1::/64 ->
# fd00:1::1) to the server-side tun device; dnsmasq listens there so dual-stack
# clients reach the resolver over IPv6. Runs as an embedded awk program (no
# python dependency in the container).
server_ip6_of() {
    local cidr="$1"
    [[ -z "$cidr" ]] && return 1
    awk -v cidr="$cidr" '
        function compress(hex,   i, g, best, beststart, cur, curstart, out) {
            for (i = 1; i <= 8; i++) g[i] = substr(hex, (i - 1) * 4 + 1, 4)
            cur = 0; curstart = 0; best = 0; beststart = 0
            for (i = 1; i <= 8; i++) {
                if (g[i] == "0000") {
                    if (cur == 0) curstart = i
                    cur++
                    if (cur > best) { best = cur; beststart = curstart }
                } else { cur = 0 }
            }
            if (best < 2) best = 0
            out = ""
            for (i = 1; i <= 8; i++) {
                if (best >= 2 && i == beststart) { out = out "::"; i += best - 1; continue }
                while (length(g[i]) > 1 && substr(g[i], 1, 1) == "0") g[i] = substr(g[i], 2)
                if (out ~ /::$/) out = out g[i]
                else if (out == "") out = g[i]
                else out = out ":" g[i]
            }
            return out
        }
        function next_addr(cidr,   parts, n, net, prefix, g, i, j, expo, miss, full, hb, carry, d, out) {
            n = split(cidr, parts, "/")
            net = parts[1]
            prefix = n > 1 ? parts[2] : 128
            n = split(net, g, ":")
            expo = 0
            for (i = 1; i <= n; i++) if (g[i] != "") expo++
            miss = 8 - expo
            full = ""
            for (i = 1; i <= n; i++) {
                if (g[i] == "") {
                    for (j = 1; j <= miss; j++) full = full "0000"
                    miss = 0
                } else {
                    full = full sprintf("%04s", g[i])
                }
            }
            while (length(full) < 32) full = full "0000"
            if (prefix < 128) {
                hb = int((128 - prefix) / 4)
                full = substr(full, 1, 32 - hb)
                for (i = 1; i <= hb; i++) full = full "0"
            }
            carry = 1
            out = ""
            for (i = 32; i >= 1; i--) {
                d = index("0123456789abcdef", tolower(substr(full, i, 1))) - 1 + carry
                if (d >= 16) { d -= 16; carry = 1 } else { carry = 0 }
                out = substr("0123456789abcdef", d + 1, 1) out
            }
            return compress(out)
        }
        BEGIN { print next_addr(cidr) }
    '
}

# Lists the tun server IP (pool network + 1) of every daemon config. dnsmasq
# listens on all of them so clients of any daemon reach the resolver via their
# own tun gateway (each daemon's config pushes the matching address). IPv6 tun
# addresses (dual-stack daemons) are included the same way.
tun_ips_of() {
    local conf ip
    for conf in "$OPNL_CONFIG_DIR"/daemon-*.conf; do
        ip="$(server_ip_of "$conf" 2>/dev/null || true)"
        [ -n "$ip" ] && echo "$ip"
        ip="$(server_ip6_of "$(extract_pool6 "$conf" 2>/dev/null || true)" 2>/dev/null || true)"
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
    local conf pool pool6 mode
    conf="$(ls "$OPNL_CONFIG_DIR"/daemon-*.conf 2>/dev/null | head -1 || true)"
    [[ -z "$conf" ]] && return 0
    pool="$(extract_pool "$conf" 2>/dev/null || true)"
    [[ -z "$pool" ]] && return 0
    pool6="$(extract_pool6 "$conf" 2>/dev/null || true)"
    mode="$(extract_mode "$conf" 2>/dev/null || true)"
    OPNL_VPN_POOL="$pool" OPNL_VPN_POOL6="$pool6" OPNL_NETWORK_MODE="$mode" /etc/openvpn/scripts/apply-rules.sh || true
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

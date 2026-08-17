#!/bin/bash
# Tests for apply-rules.sh: passage-nat comment marker on MASQUERADE rules.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OPENVPN_DIR="$(dirname "$SCRIPT_DIR")"

pass=0
fail=0
assert_contains() {
    local label="$1" haystack="$2" needle="$3"
    if [[ "$haystack" == *"$needle"* ]]; then
        echo "  PASS: $label"
        pass=$((pass + 1))
    else
        echo "  FAIL: $label (expected to contain '$needle')"
        echo "        got: $haystack"
        fail=$((fail + 1))
    fi
}

tmpdir="$(mktemp -d)"
LOG="$tmpdir/iptables.log"
: > "$LOG"

# Create iptables mock that logs all commands
cat > "$tmpdir/iptables" <<'MOCK'
#!/bin/bash
echo "$@" >> "${IPTABLES_LOG:-/dev/null}"
# Simulate -C (check): return "not found" so -A (append) is always called
[[ "$1" == "-C" ]] && exit 1
# Simulate -N (new chain): succeed (chain doesn't exist yet)
[[ "$1" == "-N" ]] && exit 0
exit 0
MOCK
chmod +x "$tmpdir/iptables"
cp "$tmpdir/iptables" "$tmpdir/ip6tables"

# Create minimal dnsmasq config dir
mkdir -p "$tmpdir/config/dnsmasq.d"
touch "$tmpdir/config/dnsmasq.d/passage-domains.conf"

echo "=== apply-rules.sh passage-nat comment ==="

IPTABLES_LOG="$LOG" \
PASSAGE_VPN_POOL="10.8.0.0/24" \
PASSAGE_NETWORK_MODE="nat" \
PASSAGE_FIREWALL_IFACE="eth0" \
PASSAGE_CONFIG_DIR="$tmpdir/config" \
    PATH="$tmpdir:$PATH" \
    bash "$OPENVPN_DIR/scripts/apply-rules.sh" 2>&1 || true

iptables_log="$(cat "$LOG")"

assert_contains "IPv4 MASQUERADE includes --comment" "$iptables_log" "--comment"
assert_contains "IPv4 MASQUERADE includes passage-nat" "$iptables_log" "passage-nat"
assert_contains "IPv4 MASQUERADE includes -s 10.8.0.0/24" "$iptables_log" "-s 10.8.0.0/24"
assert_contains "IPv4 MASQUERADE includes -j MASQUERADE" "$iptables_log" "-j MASQUERADE"
assert_contains "IPv4 MASQUERADE includes -o eth0" "$iptables_log" "-o eth0"

# Test IPv6 with pool6
LOG6="$tmpdir/iptables6.log"
: > "$LOG6"

IPTABLES_LOG="$LOG6" \
PASSAGE_VPN_POOL="10.8.0.0/24" \
PASSAGE_VPN_POOL6="fd00:1::/64" \
PASSAGE_NETWORK_MODE="nat" \
PASSAGE_FIREWALL_IFACE="eth0" \
PASSAGE_CONFIG_DIR="$tmpdir/config" \
    PATH="$tmpdir:$PATH" \
    bash "$OPENVPN_DIR/scripts/apply-rules.sh" 2>&1 || true

ip6tables_log="$(cat "$LOG6")"

assert_contains "IPv6 MASQUERADE includes --comment" "$ip6tables_log" "--comment"
assert_contains "IPv6 MASQUERADE includes passage-nat" "$ip6tables_log" "passage-nat"
assert_contains "IPv6 MASQUERADE includes -s fd00:1::/64" "$ip6tables_log" "-s fd00:1::/64"

# Cleanup
rm -rf "$tmpdir"

echo ""
echo "=== Results: $pass passed, $fail failed ==="
[[ $fail -eq 0 ]] && exit 0 || exit 1

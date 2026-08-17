# Access Rules — Firewall and DNS Control

How the panel decides what a connected VPN client may reach, and how those
decisions become iptables rules inside the OpenVPN container. Backend
implementation lives in `com.passagevpn.access` (`AccessRule`, `RuleEngine`,
`AccessRuleService`) and `com.passagevpn.dns` (`DnsRecord`,
`DnsOverrideService`); enforcement scripts are in `openvpn/scripts/`.

## 1. Rule model

An access rule (`access_rules` table) has:

- **Target** — who the rule applies to:
  - `GLOBAL` — every client.
  - `USER` — one account (`target_id` = user id).
  - `GROUP` — every member of a group, including subgroups (inheritance).
- **Action** — `ALLOW` or `DENY` (mapped to iptables `ACCEPT` / `DROP`).
- **Match** — optionally constrain *protocol* (`TCP`/`UDP`), *source CIDR*
  within the VPN (defaults to the client itself), and exactly **one**
  destination kind:
  - `dstCidr` — IP network, e.g. `10.0.0.0/8` (IPv4 or IPv6).
  - `dstGroupId` — another group's **allocated subnet** (its static IP pool, or
    the members' static IPs when no pool exists).
  - `dstDomain` — a hostname; resolved to the pinned addresses (see §5).
  - none — any destination.
  - `dstPort` — destination port, requires a protocol.
- **priority** — lowest number wins (assigned sequentially on create).
- **enabled** — disabled rules are ignored.

## 2. Evaluation

For a user, the engine collects **all** enabled rules that apply:

1. `GLOBAL` rules.
2. Rules of each group in the user's group chain, **child first** (deepest
   subgroup up to the root parent).
3. The user's own `USER` rules.

Rules are sorted by priority (stable: global rules before group rules before
user rules at equal priority). The set is then rendered into a per-client
iptables chain.

> **Semantics**: a user for whom **any** rule exists gets a **default-deny**
> chain: traffic is dropped unless an `ALLOW` rule matches. A user with no
> rules at all gets no chain and relies on the container's default `FORWARD`
> policy (permissive). This mirrors "list what is allowed" and keeps
> rule-less users unblocked.

## 3. Rendering to iptables

Each client gets a dedicated chain named `PASSAGE_` + the first 6 bytes of the
SHA-256 of the common name, e.g. `PASSAGE_1a2b3c4d5e6f` (`RuleEngine.chainName`).
On connect the backend returns the exact `iptables` argv lists; the container
executes them (see `client-connect.sh`).

Per-chain, in order:

```
iptables -N PASSAGE_<hash>
iptables -A PASSAGE_<hash> -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
iptables -A PASSAGE_<hash> -p udp --dport 53 -j ACCEPT      # DNS always allowed
iptables -A PASSAGE_<hash> -p tcp --dport 53 -j ACCEPT
<one ACCEPT/DROP line per rule match, source = client's VPN IP>
<one DROP line per scope-denied DNS-override address>     # §6
iptables -A PASSAGE_<hash> -j DROP                           # default deny (if any rule exists)
iptables -I FORWARD -s <client-vpn-ip> -j PASSAGE_<hash>     # hook into forwarding
```

Teardown (disconnect) removes the FORWARD jump and deletes the chain:

```
iptables -D FORWARD -s <client-vpn-ip> -j PASSAGE_<hash>
iptables -F PASSAGE_<hash> && iptables -X PASSAGE_<hash>
```

A rule's destination expands to match fragments:

| Destination | Resulting match |
|---|---|
| `dstCidr` IPv4 | `-d 10.0.0.0/8` |
| `dstGroupId` with pool | `-m iprange --dst-range start-end` of the group's pool |
| `dstGroupId` without pool | `-d <member static IP>/32` per member |
| `dstDomain` | `-d <pinned IP>/32` per resolved address (DNS overrides win, §5) |
| `dstPort`/`protocol` | `-p tcp --dport 22` etc. |

If a rule's destination cannot resolve in a family it is skipped for that
table (e.g. an IPv4-only domain in the IPv6 chain).

### 3.1 Base firewall (`apply-rules.sh`)

Once per container start and on every config reload the container installs the
base rules, which the per-client chains then narrow:

- `FORWARD` policy `ACCEPT` plus a `ESTABLISHED,RELATED` return-path rule.
- **NAT mode** (default): `MASQUERADE` of the VPN pool out of the uplink
  interface (`PASSAGE_FIREWALL_IFACE`, default `eth0`).
- **Routed mode** (`PASSAGE_NETWORK_MODE=routed`): no masquerade; the VPN pool
  must be routed back to the host and an explicit `ip route` into the tun
  device is installed (deferred until the tun exists).
- `PASSAGE_DOMAINS` / `PASSAGE_DOMAINS6` chains pin every domain-rule address to
  `RETURN` then `DROP` (see §5).

## 4. Static IPs and group pools

Groups may define a static IP pool (`STATIC_IP_POOL`, e.g.
`10.8.0.100-10.8.0.149`) and users a static IP, via settings. Pools are
inherited down the group chain (a subgroup without a pool uses the closest
ancestor's). Static IPs and pools are used by:

- CCD files (assigned tunnel address),
- `dstGroupId` rule destinations (the group's pool range or member IPs).

An IPv6 pool (`STATIC_IPV6_POOL`) is used the same way for dual-stack rules.

## 5. Domain pinning

The backend renders two dnsmasq config files into the shared volume:

- `dnsmasq.d/passage-domains.conf` — A/AAAA pins for every domain used in an
  **enabled access rule** (override addresses win over public DNS).
- `dnsmasq.d/passage-dns-overrides.conf` — admin-defined override hostnames.

The container's dnsmasq serves these authoritatively to VPN clients. The
`PASSAGE_DOMAINS`/`PASSAGE_DOMAINS6` iptables chains and the per-client rules match
**the same pinned addresses**, so what a client resolves is exactly what the
firewall allows/blocks. When the pinning file changes, the container restarts
dnsmasq (not just a SIGHUP) to avoid a stale cache diverging from the
firewall.

## 6. DNS overrides and scope denies

A DNS override (`dns_records`) maps a hostname to a pinned IPv4 (and
optionally IPv6) answer, with a scope:

- `GLOBAL` — everyone may reach the address.
- `GROUP` / `USER` — only that group's members / that user may reach it.

Because dnsmasq serves **one** answer per hostname for everyone, scoped
hostnames still resolve for all clients, but `RuleEngine.scopeDenyIpsFor`
computes the addresses the connecting user may **not** reach and emits
per-client `DROP` lines for them. GLOBAL records never deny. Scope-only chains
keep `ACCEPT` as the terminal rule so otherwise-unrestricted clients are only
blocked from the denied addresses.

## 7. Dual-stack

When the serving daemon is dual-stack, the engine also emits an `ip6tables`
mirror chain (`PASSAGE_<hash>6`) scoped to the client's virtual IPv6 address:
same terminal policy, same per-rule destinations (IPv6 forms), DNS over IPv6
allowed. The IPv6 chain is only emitted when the client actually has a virtual
IPv6 address; IPv4-only clients get IPv4 rules only. DNS pinning includes AAAA
answers exactly when the primary daemon is dual-stack.

## 8. When rules apply

The per-client chain is installed by `client-connect.sh` on every new tunnel
and torn down on disconnect (`client-disconnect.sh`). Because the chain is
recomputed from the live rule set at connect time, rule changes affect new
connections immediately; established sessions keep their original chain until
disconnect (optionally re-killed via the VPN Nodes / Daemons pages).

## 9. Worked examples

**Restrict a group to the LAN only**

```
GROUP "Engineering"  ALLOW 10.0.0.0/8
GLOBAL               ALLOW 10.8.0.0/24        # VPN-internal traffic
```

Every Engineering member gets a default-deny chain opening the two ranges;
everyone else has no rules and stays unrestricted.

**Expose an internal host to one user**

```
DNS override  git.internal -> 10.8.0.5   (scope USER alice)
USER alice    ALLOW tcp dstDomain git.internal port 22
```

dnsmasq answers `git.internal` → `10.8.0.5` for everyone; alice's chain ACCEPTs
`tcp --dport 22` to that address, everyone else's chain DROPs it (scope deny).

**Route by group subnet**

```
USER alice  ALLOW dstGroupId "Database" tcp port 5432
```

`dstGroupId` expands to the Database group's static IP pool range, so alice
reaches any member regardless of which member actually has the address.

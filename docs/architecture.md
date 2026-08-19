# Architecture — PassageVPN

This document describes how the management panel is put together: the runtime
topology, the responsibilities of each component, how the backend talks to
OpenVPN, and the security model. It is a companion to `docs/api.md` (REST
reference) and `docs/access-rules.md` (firewall / DNS control).

## 1. Components

The panel is a set of cooperating containers deployed with Docker Compose:

| Component | Role |
|---|---|
| `backend` | Spring Boot 3.5 application: web API, PKI, config generation, monitoring, access control, node registry. Java 25, Gradle (Kotlin DSL), JPA + Hibernate, SQLite (WAL) with a portable PostgreSQL profile. |
| `frontend` | React 18 + TypeScript SPA served by nginx; static bundle that calls `/api/**`, `/api/portal/**` and consumes `/ws` WebSocket events. Vite build. |
| `openvpn` | Alpine container running OpenVPN 2.7 (one daemon per generated config), Easy-RSA 3.1 (hosted in the backend via subprocess), dnsmasq (VPN DNS + domain pinning) and the iptables/ip6tables firewall that enforces access rules. Requires `NET_ADMIN`. |
| `db` | Not a separate container for SQLite — the database is a file in the shared `passage-data` volume. A PostgreSQL profile (`PASSAGE_PROFILE=postgres`) swaps in a real database service. |
| `passage-agent` | Optional (compose `--profile node`): the backend's `agent` Spring profile running on a remote gateway node; registers and heartbeats to the central backend and pulls + provisions its config bundle (daemon configs, PKI incl. the CRL, CCD, scripts, dnsmasq) so the node's daemons appear in the panel and run like the local ones. |

```
                    +--------------------------------------------+
                    |               docker network               |
                    |                                            |
  +-----------+     |  +---------+      +-------------------+    |
  |  Browser  |-----|->|frontend |----->|    backend        |    |
  +-----------+     |  | (nginx) | /api |   (Spring Boot)   |    |
                    |  +---------+      +-------------------+    |
                    |       ^                |    |    |         |
                    |       | /ws            |    |    |         |
                    |       |                |    |    |         |
                    |  +--------------------+    |    |         |
                    |  |   openvpn container     |    |         |
                    |  |                         |    |         |
                    |  |  openvpn daemon(s)      |    |         |
                    |  |  dnsmasq               |    |         |
                    |  |  iptables/ip6tables    |    |         |
                    |  +--------------------+    |    |         |
                    +--------------------------------------------+
                                         |   config volume
                                         |   (write, read-only in openvpn)
                                         v
                              passage-data / passage-pki / passage-ccd /
                              passage-config / passage-logs volumes
```

## 2. Runtime topology and data flow

### 2.1 Shared volumes

All state that must survive restarts lives in Docker volumes:

- `passage-data` — SQLite database (`passage.db`), backups.
- `passage-pki` — Easy-RSA PKI (`pki/run`), CA, issued certificates, CRL.
- `passage-ccd` — per-user client-config-dir files (static IPs, pushed options).
- `passage-config` — generated `daemon-N.conf` files plus `dnsmasq.d/*.conf`;
  written by the backend, mounted **read-only** into the openvpn container.
- `passage-logs` — daemon logs, OpenVPN status files, audit/access logs.

### 2.2 Config generation and hot reload

The backend renders every daemon config from
`backend/src/main/resources/templates/daemon.conf` (placeholders substituted by
`ServerConfigGenerator`), writes it into the shared config volume and records a
per-daemon row in `daemons`. The openvpn container `entrypoint.sh` watches the
config directory (md5 of all `daemon-*.conf`); on change it gracefully SIGTERMs
the running daemons and starts fresh ones (`restart_all`). Base firewall
(`apply-rules.sh`) and dnsmasq are re-applied on every reload. First boot with
no configs leaves the container idle until the setup wizard provisions it.

The dnsmasq pinning file `dnsmasq.d/passage-domains.conf` is watched separately:
changes refresh the `PASSAGE_DOMAINS`/`PASSAGE_DOMAINS6` iptables chains and restart
dnsmasq (a plain SIGHUP would keep the stale cache and diverge from the
firewall).

### 2.3 OpenVPN management interface

Each daemon opens its management socket on port `7505 + daemonIndex` (exposed
in the docker network). The backend's `MgmtClientManager` keeps one persistent
TCP client per (node, daemon index); connections are established lazily, cached,
and transparently re-established after failure with a 10 s cooldown to prevent
reconnect storms. Protocol is the OpenVPN management interface: plain-text
commands (`status 3`, `kill`, `signal`) and asynchronous `>` events consumed by
the monitor.

The management interface is **password-protected**: every generated daemon
config points at a per-daemon password file (`daemon-<index>.mgmt-pass`, mode
0600) via `management 0.0.0.0 <port> <file>`, and the backend authenticates on
connect (`MgmtHandshake`). Both the local backend (`PASSAGE_OPENVPN_MGMT_PASSWORD`)
and every registered node must carry a management password; connections to a
daemon without one are refused (fail closed). Startup fails fast when the local
password is missing or still the placeholder (`SecurityBootstrapCheck`).

The daemon template deliberately does **not** enable
`restart-on-management-disconnect`, because the backend keeps persistent
connections and container-level reloads replace that mechanism.

### 2.4 Script callbacks (the connect/disconnect path)

OpenVPN invokes scripts that call back into the backend over the restricted
docker network (`/internal/**`, guarded by the mandatory `X-Internal-Token`
shared secret):

- `verify-user-pass.sh` — `auth-user-pass-verify` (password only) against
  `/internal/auth/verify`; triggers the auth-pending flow when MFA is required
  and completes it via `client-crresponse` → `/internal/auth/verify-otp`.
- `client-connect.sh` — `/internal/connect`: the backend authorizes the user
  (banned/locked/max-connections), registers the session, records history and
  returns per-client iptables commands plus config pushes to apply.
- `client-disconnect.sh` — `/internal/disconnect`: returns the chain teardown
  commands; fire-and-forget session logging.
- `learn-address.sh` — `/internal/learn-address`: correlates virtual IPs with
  usernames.

`post-auth-hook.py` is an optional best-effort hook executed by the backend in
the container after a successful VPN login (SIEM push, device registration…).

## 3. Backend module map

Package root `com.passagevpn` (`backend/src/main/java/com/passage/vpn`):

| Package | Responsibility |
|---|---|
| `auth` | Login (password + TOTP), JWT issuance, VPN login verification for scripts, `spi` for pluggable auth. |
| `security` | JWT filter, API-token filter, internal-token filter, rate limiting. |
| `user`, `group` | Accounts, groups, memberships, per-user locking/banning. |
| `pki` | Easy-RSA subprocess wrapper, certificate lifecycle (issue/revoke/restore/rotate), CRL, index parsing. |
| `profile` | `.ovpn` generation (user-locked / auto-login / server-locked / generic), token URLs, QR sharing. |
| `access` | Access-rule CRUD, rule engine (effective rules → iptables), CIDR/domain resolution, group hierarchy. |
| `dns` | DNS overrides (`DnsRecord`), scoped resolution, conflict detection. |
| `ccd` | client-config-dir rendering (static IPs, pushed per-user options). |
| `network` | Daemon registry, server config generation, dnsmasq config, connection registry, node registry (`OpenVpnNode`), script sync. |
| `network.util` | `Ipv6Util` (IPv6 parsing/compression), `IpPoolAllocator` (subnet-aware IP pool management). |
| `monitor` | Management clients, status polling, WebSocket push (`/ws`), traffic aggregation, session history. |
| `node` | Agent registration service (remote gateway nodes). |
| `setting` | Settings service: server/group/user settings stored as JSON in TEXT columns, inheritance and validation. |
| `setup` | First-run wizard state machine (admin → VPN server → PKI → complete). |
| `audit` | Audit log recording for every mutating operation. |
| `backup` | Backup/restore archive generation. |
| `brand` | Branding overrides (product name, colors, logo). |
| `api`, `internal`, `api/portal` | HTTP surface: admin API, portal self-service, script-facing endpoints. |
| `system` | Maintenance, smoke tests, demo/seed mode, application restarter. |
| `common` | `ApiException`/`ApiError`, global exception handler, process runner, app metadata. |

## 3.1 Extracted Services (refactored)

The following services were extracted from god classes during the Phase A
refactoring to improve separation of concerns and testability:

| Extracted Service | Extracted From | Responsibility |
|---|---|---|
| `Ipv6Util` | `CcdService` | IPv6 address parsing, compression, expansion, and network calculations. |
| `IpPoolAllocator` | `CcdService` | Subnet-aware IP pool management for automatic address allocation. |
| `UserIpAdminService` | `UserAdminService` | User IP address management: static IPs, pool allocation, IPv6 support. |
| `ConnectionOrchestrator` | `UserAdminService` | VPN connection management: kill, kick, max-connections enforcement. |

## 4. API surface and security

### 4.1 Endpoint tiers

- `/api/**` — admin + group-admin endpoints, JWT or API-token authenticated,
  `@PreAuthorize` role checks. `GROUP_ADMIN` access is scoped to the root groups
  the account is bound to (`group_admin_assignments`) plus all their subgroups.
- `/api/portal/**` — self-service (own profile, own certificates), scoped to the
  calling user.
- `/internal/**` — script-facing endpoints; not routable outside the docker
  network. The local script callbacks are guarded by the mandatory
  `X-Internal-Token` shared secret (`InternalTokenFilter`), and the node-agent
  endpoints (`/internal/node/*`) additionally run on a **mutual-TLS connector**
  (port 9443) that requires a client certificate whose `CN = agent-<nodeName>`.
- `/api/setup/**`, `/api/public/**`, `/api/portal/share/**`, `/share/**` (public
  token-based `.ovpn` downloads), auth endpoints,
  `/ws/**`, Swagger and health paths are public by design
  (`PUBLIC_PATHS` in `SecurityConfig`).

### 4.2 Authentication

- Passwords hashed with BCrypt (`BCryptPasswordEncoder`); accounts support
  TOTP MFA (`AuthService`).
- JWT: short-lived access token + rotating refresh token, stateless sessions.
- API tokens (`passage_...`) for automation, exchanged via `X-API-Token`.
- Brute-force lockout via the `RateLimitFilter` on auth endpoints and per-account
  lock/ban state.

### 4.3 RBAC

Roles are `ADMIN`, `GROUP_ADMIN`, `USER`. Endpoints that write data require an
admin role unless explicitly marked portal-scoped or `@Anonymous`. Method-level
authorization via `@PreAuthorize("hasRole('ADMIN')")` etc. and the Swagger
`bearerAuth` scheme in `OpenApiConfig`. A `GROUP_ADMIN` can only manage the
groups it is bound to (including their subgroups), the `USER` accounts that are
members of those groups, their per-user settings/static IPs, and the connection
logs of those users; it cannot grant or manage other admins, create new root
groups, or delete the root groups it manages. `GroupScope` in `com.passagevpn.group`
resolves the managed scope (root group + descendants). API tokens always carry
the `ADMIN` role.

## 5. VPN control model

- **Multi-daemon**: one `daemon-N.conf` per listening daemon (distinct
  port/protocol/subnet). All are managed from a single panel and monitored
  independently. Docker Compose publishes a configurable UDP/TCP port range
  (`PASSAGE_OPENVPN_PORT[_END]` / `PASSAGE_OPENVPN_TCP_PORT[_END]`, single port by
  default); a daemon's configured port is both the container listen port and the
  externally advertised port, so the backend auto-assigns the next free port of
  the protocol range when a new daemon is created without a port and rejects
  explicit ports outside the published range (`daemon_port_not_published`) —
  daemons can never be silently unreachable from the host. Remote-node daemons
  are exempt (their gateway publishes its own range) but must always carry an
  explicit port.

### 5.1 Daemon port allocation

A daemon's configured port plays three roles at once: the container **listen**
port (`daemon-N.conf`), the **host-published** port, and the port advertised to
clients in `.ovpn` `remote <host> <port> <proto>` lines (multi-remote
profiles). Any mismatch makes the daemon silently unreachable, so compose and
the backend agree on a single source of truth: the **published port range**.

**Compose publishes ranges, not single ports** (`docker-compose.yml`):

| Gateway | Protocol | Range env vars | Default |
|---|---|---|---|
| central | UDP | `PASSAGE_OPENVPN_PORT` … `PASSAGE_OPENVPN_PORT_END` | `1194`–`1194` |
| central | TCP | `PASSAGE_OPENVPN_TCP_PORT` … `PASSAGE_OPENVPN_TCP_PORT_END` | `1195`–`1195` |
| remote node | UDP | `PASSAGE_NODE_OPENVPN_PORT` … `PASSAGE_NODE_OPENVPN_PORT_END` | `1196`–`1196` |
| remote node | TCP | `PASSAGE_NODE_OPENVPN_TCP_PORT` … `PASSAGE_NODE_OPENVPN_TCP_PORT_END` | `1197`–`1197` |

Each is mapped **identity** (`"<base>-<end>:<base>-<end>/udp|tcp"`), so the
container listens on the very port the host publishes. The node gateway uses its
own base so its range can never collide with the central one on a shared host.
Defaults collapse to the single-port behaviour (one UDP + one TCP daemon); set
`_END` higher to publish more daemon ports.

**Backend enforcement** (`DaemonService.resolvePort` / `validateUnique`, on
every create *and* update; `id` excluded from the used set on update):

- **Empty port** (local daemon) — auto-assigns the **lowest free port** in the
  range of the daemon's protocol family (UDP or TCP), scanning from the base and
  skipping ports held by any other daemon, node daemons included.
- **Explicit port** (local daemon) — must fall inside the published range,
  otherwise `daemon_port_not_published`; must also be free
  (`daemon_port_taken`), with a unique index (`daemon_index_taken`) and subnet
  (`daemon_subnet_taken`).
- **Exhausted range** — `daemon_port_range_full`.
- **Remote-node daemons** (`nodeId` set) — exempt from the central range check
  (the node's own gateway publishes its own range), but **must** carry an
  explicit port or they get `daemon_port_required`.
- **Existing daemons** are never rewritten by a later range change; the range is
  only an create/update-time invariant. Upgrading to multiple daemons only
  requires widening `_END` and recreating the stack — new daemons then auto-fill
  from the free ports.

The Daemons page exposes this: leaving **Port** empty auto-assigns, entering a
value validates it against the published range and surfaces the error codes
above.

- **Dual-stack**: a daemon may carry `server-ipv6`; IPv4 and IPv6 per-client
  chains are generated by the same rule engine and enforced in both tables.
- **Multi-node**: registered `openvpn_nodes` map a node to a management host and
  port base. Status/kill/monitoring route per (node, daemon). The `agent`
  Spring profile (`com.passagevpn.node`) turns a backend image into a node agent
  that registers and heartbeats via `/internal/node/*`.
  - **Transport security**: the agent talks to the central backend over an
    mTLS-only connector (`passage.internal.mtls-port`, default 9443). The central
    backend generates its internal CA + server keystore on first boot
    (`InternalTlsBootstrap`) and issues one client certificate per node via
    `POST /api/admin/nodes/{id}/agent-cert` (CN `agent-<nodeName>`); the agent
    presents it through `passage.agent.tls-ca|cert|key`. Requests that do not
    arrive on the mTLS port, lack a client cert, or carry a cert for a
    different node are rejected (`mtls_required` / `client_cert_required` /
    `cert_identity_mismatch`). `X-Internal-Token` remains as defense-in-depth.
  - **Management credentials**: `register` requires a management password,
    stored per node; the central backend authenticates to the remote daemon
    with it (fail closed if missing).
  - **Source-IP pinning**: when a node has `adminIp` set, agent `register` and
    `heartbeat` are only accepted from that address (403
    `source_ip_mismatch`), hardening the registration path even before the
    certificate is provisioned.
  - **Config distribution (config-pull)**: `POST /internal/node/config`
    (`NodeConfigBundleService`, same mTLS/CN/source-IP protection as
    `register`) returns the node's full gateway bundle: rendered daemon
    configs + per-daemon management passwords, PKI (`ca.crt`, `server.crt`,
    `server.key`, `ta.key`, **`crl.pem`** — so revocations reach remote
    gateways), CCD overrides, connect scripts and dnsmasq domains. The bundle
    carries a content hash (`version`, SHA-256 over all entries). The remote
    agent (`AgentConfigSyncService`, `@Profile("agent")`) polls it on a fixed
    schedule (`passage.agent.sync-seconds`, default 60s) and skips applying when
    the hash is unchanged; otherwise it writes everything atomically (temp file
    + atomic move) into the shared gateway volumes, pins management passwords
    and `server.key` to owner-read/write, marks scripts executable, deletes
    stale managed files it previously owned, and rejects any entry whose name
    would escape its directory. Central-rendered daemon configs embed the
    central instance's volume paths, so this contract only holds because the
    gateway containers mount the **same layout** (`/etc/passage/pki`,
    `/etc/passage/ccd`, `/etc/passage/config`, `/var/log/passage`) — enforced by the
    compose `node` profile, documented in `docs/configuration.md`. Script
    callbacks (`verify-user-pass`, `client-connect`, ...) on the remote gateway
    reach the central backend via `PASSAGE_NODE_INTERNAL_BASE_URL`.
- **Multi-remote profiles**: the server setting `profile_multi_remote` (default
  on) makes `.ovpn` generation embed **every** enabled daemon serving the
  requested profile type as a `remote <host> <port> <proto>` line plus
  `remote-random`, so clients load-balance across gateways. Disabling it pins a
  profile to a single endpoint (first match by daemon index). A daemon bound to
  a disabled or unknown node is never advertised. The advertised host resolves
  as daemon `adminHost` → node `adminHost` → `PASSAGE_ADMIN_HOST`.
- **Certificate lifetime & auto-rotation**: certificates are issued with a
  configurable lifetime (`PASSAGE_PKI_CERT_EXPIRE`, default 730 days) and the CRL
  is generated for at least that long so a revoked cert cannot outlive the CRL
  window. A daily scheduler (`CertService.applyRotationPolicy`, 03:35 UTC) acts
  on the server settings `cert_auto_rotate` (`off` | `notify` | `auto`,
  default `notify`) and `cert_rotate_days_before` (default 14): in `auto` mode
  any VALID, user-bound certificate within `cert_rotate_days_before` days of
  expiry is rotated (new cert issued, old revoked, audit `CERT_ROTATE_AUTO`);
  orphaned or non-user certificates are never auto-rotated. In `notify` mode
  the same scan only flags candidates via the existing expiry-warning query
  (`GET /api/admin/certs?expiring=true`). The portal AccountPage surfaces a
  "certificate expires soon" warning for the signed-in user.
- **Per-client firewall**: see `docs/access-rules.md`.

## 6. Frontend

- React 18 + TypeScript, Vite, MUI v6 (dark by default), TanStack Query for
  server state, React Hook Form + Zod for forms, `@mui/x-data-grid` for list
  views.
- Feature-folder structure under `frontend/src/features/<feature>/`; each
  feature contains its page component, sub-components, hooks, and tests.
  Shared components in `src/components/`, hooks in `src/hooks/`, utilities in
  `src/lib/`.
- Single API client in `frontend/src/lib/api.ts`; WebSocket hook for live
  monitoring events.
- The setup wizard gates login until setup is complete; a first-run admin can
  be created via the wizard, `make seed-admin`, or demo data via
  `make seed-demo` / `PASSAGE_DEMO_MODE=true`.

## 7. Storage

- **SQLite** is the default: single file with WAL, foreign keys on, busy
  timeout. Repositories use JPQL/Criteria only so a PostgreSQL switch stays
  trivial (dedicated `db/migration-postgresql` set + `MigrationParityTest`).
- Settings (server/group/user) are stored as JSON strings in TEXT columns via a
  Jackson converter — never SQLite `JSON` type.
- Flyway migrations version both SQLite and PostgreSQL paths (V1–V17).

## 8. Deployment and operations

- `install.sh` (single-command) → `docker compose up -d`. `make` targets cover
  build/test/backup/api-docs/migrate/seed.
- Default exposed ports: frontend `80`, OpenVPN `1194/udp` + `1195/tcp`,
  management `7505-7510` (network-internal), Swagger at `/swagger-ui.html`.
  The backend's HTTP listener is **not published to the host**: all user/API
  traffic enters through the frontend nginx container (which proxies `/api`,
  `/share` and `/ws` but deliberately not `/internal/**`), and the OpenVPN
  container reaches the backend only over the internal docker network at
  `http://backend:8080`. Local development still runs the backend directly on
  host `8080` (`make backend-dev`).
- Secrets come from `.env` (see `.env.example`): JWT secret, internal token,
  optional bootstrap token, admin password, DB URL. Never commit real secrets.
- The bootstrap-only seed endpoints (`/internal/seed-admin`,
  `/internal/seed-demo`) additionally require the `X-Bootstrap-Token` header
  when `PASSAGE_BOOTSTRAP_TOKEN` is configured. Unlike `PASSAGE_INTERNAL_TOKEN` this
  secret is never exposed to the OpenVPN container, so a compromised gateway
  cannot re-create an admin account.
- Backups: `make backup` produces an archive via `BackupService`.
- **Never run a full-tunnel VPN client on the VPN server host itself.** A
  full-tunnel profile installs default routes via the tunnel; on the server
  host that redirects the host's own egress (SSH/panel/ICMP replies) into the
  VPN and black-holes it — the host becomes unreachable and requires
  out-of-band console recovery (`pkill -f "openvpn --config ..."` or a reboot;
  the client is not persisted). Clients must run on a separate host/VM.

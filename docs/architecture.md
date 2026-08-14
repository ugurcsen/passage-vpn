# Architecture — OpenVPN Management Panel

This document describes how the management panel is put together: the runtime
topology, the responsibilities of each component, how the backend talks to
OpenVPN, and the security model. It is a companion to `docs/api.md` (REST
reference) and `docs/access-rules.md` (firewall / DNS control).

## 1. Components

The panel is a set of cooperating containers deployed with Docker Compose:

| Component | Role |
|---|---|
| `backend` | Spring Boot 3.5 application: web API, PKI, config generation, monitoring, access control, node registry. Java 21, Gradle (Kotlin DSL), JPA + Hibernate, SQLite (WAL) with a portable PostgreSQL profile. |
| `frontend` | React 18 + TypeScript SPA served by nginx; static bundle that calls `/api/**`, `/api/portal/**` and consumes `/ws` WebSocket events. Vite build. |
| `openvpn` | Alpine container running OpenVPN 2.6 (one daemon per generated config), Easy-RSA 3.1 (hosted in the backend via subprocess), dnsmasq (VPN DNS + domain pinning) and the iptables/ip6tables firewall that enforces access rules. Requires `NET_ADMIN`. |
| `db` | Not a separate container for SQLite — the database is a file in the shared `opnl-data` volume. A PostgreSQL profile (`OPNL_PROFILE=postgres`) swaps in a real database service. |
| `opnl-agent` | Optional (compose `--profile node`): the backend's `agent` Spring profile running on a remote gateway node; registers and heartbeats to the central backend so the node's daemons appear in the panel. |

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
                              opnl-data / opnl-pki / opnl-ccd /
                              opnl-config / opnl-logs volumes
```

## 2. Runtime topology and data flow

### 2.1 Shared volumes

All state that must survive restarts lives in Docker volumes:

- `opnl-data` — SQLite database (`opnl.db`), backups.
- `opnl-pki` — Easy-RSA PKI (`pki/run`), CA, issued certificates, CRL.
- `opnl-ccd` — per-user client-config-dir files (static IPs, pushed options).
- `opnl-config` — generated `daemon-N.conf` files plus `dnsmasq.d/*.conf`;
  written by the backend, mounted **read-only** into the openvpn container.
- `opnl-logs` — daemon logs, OpenVPN status files, audit/access logs.

### 2.2 Config generation and hot reload

The backend renders every daemon config from
`backend/src/main/resources/templates/daemon.conf` (placeholders substituted by
`ServerConfigGenerator`), writes it into the shared config volume and records a
per-daemon row in `daemons`. The openvpn container `entrypoint.sh` watches the
config directory (md5 of all `daemon-*.conf`); on change it gracefully SIGTERMs
the running daemons and starts fresh ones (`restart_all`). Base firewall
(`apply-rules.sh`) and dnsmasq are re-applied on every reload. First boot with
no configs leaves the container idle until the setup wizard provisions it.

The dnsmasq pinning file `dnsmasq.d/opnl-domains.conf` is watched separately:
changes refresh the `OPNL_DOMAINS`/`OPNL_DOMAINS6` iptables chains and restart
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

The daemon template deliberately does **not** enable
`restart-on-management-disconnect`, because the backend keeps persistent
connections and container-level reloads replace that mechanism.

### 2.4 Script callbacks (the connect/disconnect path)

OpenVPN invokes scripts that call back into the backend over the restricted
docker network (`/internal/**`, guarded by `X-Internal-Token`):

- `verify-user-pass.sh` — `auth-user-pass-verify` (password, optional inline
  TOTP) against `/internal/auth/verify`; triggers the auth-pending flow when MFA
  is required and completes it via `client-crresponse` →
  `/internal/auth/verify-otp`.
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

Package root `com.opnl.vpn` (`backend/src/main/java/com/opnl/vpn`):

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

## 4. API surface and security

### 4.1 Endpoint tiers

- `/api/**` — admin + reseller endpoints, JWT or API-token authenticated,
  `@PreAuthorize` role checks.
- `/api/portal/**` — self-service (own profile, own certificates), scoped to the
  calling user.
- `/internal/**` — script-facing endpoints; not routable outside the docker
  network, guarded by `X-Internal-Token` (`InternalTokenFilter`).
- `/api/setup/**`, `/api/public/**`, `/api/portal/share/**`, auth endpoints,
  `/ws/**`, Swagger and health paths are public by design
  (`PUBLIC_PATHS` in `SecurityConfig`).

### 4.2 Authentication

- Passwords hashed with BCrypt (`BCryptPasswordEncoder`); accounts support
  TOTP MFA (`AuthService`).
- JWT: short-lived access token + rotating refresh token, stateless sessions.
- API tokens (`opnl_...`) for automation, exchanged via `X-API-Token`.
- Brute-force lockout via the `RateLimitFilter` on auth endpoints and per-account
  lock/ban state.

### 4.3 RBAC

Roles are `ADMIN`, `RESELLER`, `USER`. Endpoints that write data require an
admin role unless explicitly marked portal-scoped or `@Anonymous`. Method-level
authorization via `@PreAuthorize("hasRole('ADMIN')")` etc. and the Swagger
`bearerAuth` scheme in `OpenApiConfig`.

## 5. VPN control model

- **Multi-daemon**: one `daemon-N.conf` per listening daemon (distinct
  port/protocol/subnet). All are managed from a single panel and monitored
  independently.
- **Dual-stack**: a daemon may carry `server-ipv6`; IPv4 and IPv6 per-client
  chains are generated by the same rule engine and enforced in both tables.
- **Multi-node**: registered `openvpn_nodes` map a node to a management host and
  port base. Status/kill/monitoring route per (node, daemon). The `agent`
  Spring profile (`com.opnl.vpn.node`) turns a backend image into a node agent
  that registers and heartbeats via `/internal/node/*`.
- **Per-client firewall**: see `docs/access-rules.md`.

## 6. Frontend

- React 18 + TypeScript, Vite, MUI v6 (dark by default), TanStack Query for
  server state, React Hook Form + Zod for forms, `@mui/x-data-grid` for list
  views.
- Feature pages under `frontend/src/pages`; a single API client in
  `frontend/src/lib/api.ts`; WebSocket hook for live monitoring events.
- The setup wizard gates login until setup is complete; a first-run admin can
  be created via the wizard, `make seed-admin`, or demo data via
  `make seed-demo` / `OPNL_DEMO_MODE=true`.

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
- Default exposed ports: frontend `80`, backend `8080`, OpenVPN `1194/udp` +
  `1195/tcp`, management `7505-7510` (network-internal), Swagger at
  `/swagger-ui.html`.
- Secrets come from `.env` (see `.env.example`): JWT secret, internal token,
  admin password, DB URL. Never commit real secrets.
- Backups: `make backup` produces an archive via `BackupService`.
- **Never run a full-tunnel VPN client on the VPN server host itself.** A
  full-tunnel profile installs default routes via the tunnel; on the server
  host that redirects the host's own egress (SSH/panel/ICMP replies) into the
  VPN and black-holes it — the host becomes unreachable and requires
  out-of-band console recovery (`pkill -f "openvpn --config ..."` or a reboot;
  the client is not persisted). Clients must run on a separate host/VM.

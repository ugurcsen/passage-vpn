# Release Notes

This file tracks the current state of the project. Version numbers follow the
[SemVer](https://semver.org) convention; until the first production release the
project is in a pre-1.0 state.

Legend: `[x]` released, `[~]` partial.

---

## Unreleased — Pre-release (0.1.0-SNAPSHOT)

### Phase 0 — Project scaffolding
- [x] Repo layout, `TODO.md`, `AGENTS.md`, `.gitignore`, `.env.example`
- [x] Gradle wrapper + build scripts (Java 21, Spring Boot 3.5, Kotlin DSL)
- [x] `docker-compose.yml` (openvpn / backend / frontend / db) + PostgreSQL profile
- [x] `Makefile` and `install.sh`
- [x] OpenVPN container image (`openvpn/Dockerfile`) with helper scripts
- [x] Backend application entrypoint + health/actuator endpoints
- [x] Frontend Vite + MUI scaffold (dark theme default, routing shell, layout)

### Phase 1 — Core OpenVPN + Easy-RSA integration
- [x] OpenVPN 2.6 Alpine image with Easy-RSA 3.1, iptables, dnsmasq
- [x] Base server config template: management socket, status file, auth scripts,
      CCD, CRL, multi-daemon layout
- [x] `ProcessRunner` — safe subprocess wrapper (timeouts + env)
- [x] `EasyRsaService` — init-pki, build-ca, build-server-full, build-client-full,
      revoke, gen-crl; `index.txt` parser
- [x] Server config engine (`ServerConfigGenerator`, `ConfigWriter`, `ScriptSync`)
- [x] SQLite + Flyway wired (WAL mode, community dialect, PostgreSQL-ready)
- [x] Setup wizard backend API (admin user → PKI → network settings → apply)
- [~] `CertService` (issue/revoke/restore/rotate metadata sync)
- [~] Multi-daemon support and DCO detection

### Phase 2 — Users, groups & authentication
- [x] `User` / `Group` entities + settings inheritance (user > group > server)
- [x] Settings stored as JSON strings, typed accessors
- [x] User CRUD: create, edit, delete, ban/unban, admin grant (RESELLER-scoped)
- [x] Group CRUD + membership, nested groups
- [x] JWT access + refresh tokens with rotation and logout (hash-stored)
- [x] RBAC: `ADMIN` / `RESELLER` / `USER`
- [x] Local password auth (BCrypt)
- [x] TOTP MFA (Google Authenticator compatible) — enable / disable / reset
- [x] Password-auth .ovpn profiles render an interactive `static-challenge` prompt
      when MFA is in force (per-user TOTP or server `require_mfa_on_connect`);
      AUTO_LOGIN profiles are excluded
- [x] Admin MFA management UI — Users page "Manage MFA" dialog (QR + secret copy →
      verify code → enable; disable with confirmation)
- [x] Portal self-service account page — MFA setup/enable/disable (current password
      re-verified) and password change (all refresh tokens revoked,
      `must_change_password` cleared) via `/api/portal/account`
- [x] Brute-force lockout policy
- [x] VPN `auth-user-pass-verify` → `/internal/auth/verify` with password + OTP,
      lockout and ban checks
- [x] User search, status filter and bulk operations (ban/unban/delete) — API + UI
- [x] Frontend: login + MFA screens, Users and Groups pages (MUI DataGrid),
      session handling, toasts, route guards, sign-out
- [x] Rate limiting (bucket4j, per-IP) on login/MFA/refresh/VPN-verify
- [x] `AuthProvider` SPI — `local` (BCrypt) plus LDAP/RADIUS/SAML stub interfaces
      selectable via `opnl.auth.provider`

### Phase 3 — Access control & connection profiles
- [x] `CcdService` — per-user CCD files (static IP, per-user DNS/routes), static IP
      conflict detection, written to the shared volume
- [x] `AccessRule` entity + CRUD (user/group target, action, protocol, port, CIDR,
      priority) with admin API and rules editor UI
- [x] `RuleEngine` — resolves global/group/user rules into per-client iptables
      chains (default deny + ALLOW opens); `apply-rules.sh` base firewall
      (NAT + FORWARD) with `NET_ADMIN`; `client-connect.sh` / `client-disconnect.sh`
      install and tear down chains via `/internal/connect` + `/internal/disconnect`
- [x] `CertService` — issue / reuse / revoke per-user client certificates backed by
      Easy-RSA, with admin certificates page
- [x] `OvpnGenerator` — all four profile types (user-locked, auto-login,
      server-locked, generic) with inline CA/tls-crypt/cert/key blocks
- [x] `ProfileService` — per-user downloads, time/use-limited share tokens
      (`/api/portal/share/{token}`), QR codes (OpenVPN Connect import), admin share
      link management, self-service portal UI
- [x] `ServerConfig.authUserPass` + `verify-client-cert none` so auto-login /
      cert-only daemons work
- [x] Per-daemon profile mapping — generic / auto-login / user-locked daemons as
      first-class configs (`daemons` entity, per-daemon conf + management port),
      GENERIC daemons render `verify-client-cert none`

### Phase 4 — Operations dashboard, live status & server settings
- [~] Admin dashboard — live stat cards (active connections, users, groups,
      active certificates), per-daemon running summary and recent connections
- [~] Live status page — brand/version/uptime chips, per-daemon health table
      (config present + management socket reachable via TCP probe),
      active connections, 10s auto-refresh and manual refresh
- [x] Server settings store — generic JSON key/value admin CRUD (list, upsert
      with key validation, delete) with settings management UI
- [x] Settings page redesign — friendly typed editors for well-known server
      defaults (boolean switches, validated numbers, comma-separated lists,
      strings) with human labels and descriptions, add/edit dialog, empty state
      and a collapsible raw-JSON "Advanced" section for custom keys. The `network`
      config is surfaced as a structured form (port, protocol, subnet, DNS,
      routes, tunnel toggles, admin host) instead of raw JSON.

### Phase 4b — Real-time monitoring (management interface + WebSocket)
- [x] `MgmtClient` — persistent TCP client to the OpenVPN management interface
      (auth via `OPNL_OPENVPN_MGMT_PASSWORD`), async `>INFO/` + event parsing,
      reconnecting with backoff; per-daemon clients multiplexed by daemon index
- [x] `MgmtStatusMonitor` — periodic `status` polling per daemon into
      `VirtualAddress/ROUTING_TABLE` snapshots (client IPs, byte counters,
      connect times); `kill <cn>` support with `KILL` confirmation
- [x] `TrafficAggregator` — in-memory ring buffer (max 64 samples) of aggregate
      bytes-in/out per second and active connection counts; baseline reset on
      daemon reconnect; RATE samples over 3s
- [x] `ConnectionLogService` — records connect/disconnect events (duration,
      byte counters, daemon) into `connection_logs` (Flyway V6, BIGINT counters);
      exposed via `GET /api/admin/connection-logs?limit=`
- [x] `MonitorSnapshotService` — combined snapshot (connections with live bytes,
      daemon health incl. DCO, rates, history ring, host system info via
      `oshi-core`); `GET /api/admin/monitor` and `GET /api/admin/system`
- [x] `/ws/status` WebSocket — `WsAuthHandshakeInterceptor` (token in query
      param, ADMIN-only, MFA challenge tokens rejected, 401 on failure) +
      `MonitorSnapshotWebSocketHandler` broadcasting snapshots every 2s
- [x] DCO detection from management `TITLE` (DCO data channel vs userspace)
- [x] Frontend: `useLiveStatus` hook (WebSocket with 5s reconnect + REST
      fallback polling), Dashboard traffic chart (`@mui/x-charts` LineChart,
      15-min history) + host System card (CPU/RAM/disk) + daemon chips with DCO
      badge + Live/Offline indicator, Status page byte counters, rates chip,
      DCO column and Recent sessions table

### Verified end-to-end
- Backend: 181 unit tests green; frontend: 69 component tests; `make test`,
  `make lint` and spotless pass.
- Live E2E (production `65.21.108.250`, docker compose): setup wizard → PKI
  provisioned → OpenVPN daemon boots from the generated config → management
  interface works → admin login (password aligned with `OPNL_ADMIN_PASSWORD`)
  → Dashboard, Users and Connection Profiles render without console errors →
  USER_LOCKED profile downloads and authenticates via `verify-user-pass.sh` →
  `/internal/auth/verify`; user search, bulk operations and per-IP rate limiting
  (429 + `Retry-After`) verified.
- Live E2E (dashboard/status/settings): redeployed on production
  `65.21.108.250` with the dashboard, live status and settings pages; admin
  login → Dashboard stat cards and "Daemons 3 / 3 running" summary render →
  Live Status shows all daemons `Enabled/Present/Reachable` (real management
  socket probe) → Settings lists stored JSON values, add/delete round-trips
  with toasts, persistence survives reload; invalid setting keys rejected with
  400 `invalid_setting_key`; no console errors.
- Deployment hardening: strong `OPNL_INTERNAL_TOKEN` generated and injected into
  the auth script; `OPNL_OPENVPN_TCP_PORT` exposed; leftover test users and
  orphaned rows (certs/tokens/rules/refresh tokens) purged from SQLite; stale
  host `data/` directory removed (containers use named volumes); orphaned
  `opnl-test-client` container removed.
- Phase 3 verified via unit tests: rule resolution (global/group/user, disabled
  rules), iptables rendering (per-client chain + teardown), certificate issue/
  reuse/revoke, and all four profile types + token use consumption.
- Live E2E bugfix sweep (`docs/test-findings.md` 2.1–2.7): GENERIC daemons no
  longer emit the removed `client-cert-not-required` option; the entrypoint
  config watcher survives failing daemons; the container healthcheck compares
  configs to live pidfiles per daemon; profile-token create without `userId`
  → 400 (was 500); unknown `/api/**` path → 404 `not_found` (was 500); MFA
  challenge tokens are single-use; access rule `dstCidr` is validated as CIDR
  (malformed values rejected with 400 `validation_failed`).
- Live E2E (Phase 4b monitoring): redeployed on production `65.21.108.250`
  with the management-interface monitor; Flyway V6 applied; `/api/admin/monitor`
  returns connections + daemon health (all 3 daemons `mgmtReachable`,
  `dco: false`) + history ring (growing over time) + host system stats;
  `/api/admin/connection-logs` → `[]`; `/ws/status` handshake returns 101 with
  an admin token and 401 with a bad token; backend suite green (181 tests,
  spotless clean).
- Live E2E round-2 minor sweep (SSH ops on `65.21.108.250`, `docs/test-findings.md`
  2.8–2.12): legacy `not-a-cidr:443` access rule purged via admin API (grid clean);
  one stale "Active" session row (`disconnected_at IS NULL`, client gone from live
  `status 3`) found and closed — confirming finding 2.8; `jq` installed on the host;
  stale test artifacts in `/root` (12 log/diff files) removed; deploy scripts
  (`opnl-vpn-pull.sh`/`push.sh`) preserved.
- Finding 2.8 backend fix (session reconciliation): `ConnectionLogService` now
  reconciles open session rows against the live `status 3` view every monitor poll —
  rows whose session is gone from every daemon (restart, crash, missed
  `client-disconnect`) are closed with last-known byte counters via a shared
  `closeRow`; `ConnectionRegistry.retainOnly` drops matching stale live sessions so
  the UI view and history stay consistent. Reconciliation runs only while all enabled
  daemons are visible and at least one daemon reports (an empty client set is valid).
  Covered by 9 new unit tests (reconcile open/keep/close-empty/null cases, aggregator
  byte attach, poll guards, registry retain/delete); backend suite green
  (42 monitor+registry tests, full suite BUILD SUCCESSFUL), spotless clean on all
  touched files.
- Post-deploy monitoring fixups: `MgmtStatus` now parses OpenVPN 2.6's
  `--status-version 3` output, which is tab-separated (the legacy comma format is
  still accepted), so the live `status 3` view and per-session byte counters work
  on 2.6 daemons where the previous comma-only parser produced no client rows;
  a display-string `Connected Since` column no longer aborts row parsing.
  Frontend Download/Upload semantics corrected everywhere: Download =
  server→client (`bytesOut` / `bytesOutPerSec`), Upload = client→server
  (`bytesIn` / `bytesInPerSec`) across the Dashboard traffic chart, the rate
  chips and both connection tables. A spotless formatting sweep was applied to
  the touched monitor/API/controller files. Covered by new tab-separated parser
  tests in `MgmtStatusTest` and `MgmtClientTest`; backend + frontend suites
  green, lint clean.
- Frontend robustness sweep (`docs/test-findings.md` 2.9–2.11): background polls
  no longer burst 401s across the access-token expiry boundary — `api.ts` decodes
  the JWT `exp` claim and schedules a silent refresh at 80% TTL (re-arming after
  each rotation; the 401 retry stays as a shared-in-flight safety net), verified
  with fake-timer tests; `index.html` embeds a static dark splash inside `#root`
  so a hard reload never paints an empty root while the bundle loads; login-flow
  form controls (`username`, `password`, MFA `code`) now carry explicit
  `id`/`name` attributes with labelled-controls regression tests. Frontend suite
  61/61 green, lint clean, `vite build` passes (verified on staging host).
- Settings page typed-editor redesign: frontend suite green (70 tests across
  17 files, SettingsPage coverage for typed rendering, boolean toggle PUT,
  list/number serialization + validation, structured `network` config form,
  add-default flow, advanced add/delete and confirmation deletes), `npm run
  lint` clean (0 errors), `tsc -b` and `vite build` pass; backend suite
  unaffected and green. SSH-verified on production `65.21.108.250` (tests run
  on the deployed checkout).
- MFA integration completion: `OvpnGenerator` renders `static-challenge` on
  password-auth profiles when MFA is in force (`ProfileService.requiresMfaChallenge`:
  per-user TOTP or server `require_mfa_on_connect`; AUTO_LOGIN excluded) so
  MFA-on-connect actually prompts for the OTP; admin Users page gains a
  Manage-MFA dialog (setup → QR + secret copy → code verify → enable, disable
  with confirm, ADMIN-only); new `PortalAccountController`/`PortalAccountService`
  add self-service MFA setup/enable/disable (current password re-verified) and
  password change (all refresh tokens revoked, `must_change_password` cleared)
  under `/api/portal/account`, surfaced in a new My Account page
  (`/portal/account`). Covered by `PortalAccountServiceTest`,
  `ProfileServiceTest` static-challenge cases and frontend UsersPage/AccountPage
  tests. Verified via SSH on the production checkout: backend 213 tests green
  (spotlessCheck clean), frontend 76 tests across 18 files green, lint 0
  errors, `tsc -b` clean.

### Not yet released
- Phase 3 — group subnet allocation, per-user full/split tunnel, inter-group
  connectivity rules, NAT-vs-routing mode, dnsmasq domain control, static IP/CCD
  editor UI
- Phase 4 — logging & audit, Swagger, branding, backup, multi-node, installer
  polish, PostgreSQL profile validation

---

## Previous releases

None yet — this is the first release notes entry.

# TODO — PassageVPN

Development roadmap for the OpenVPN Access Server–like management panel built on
OpenVPN Community Edition + Easy-RSA + OpenVPN Management Interface.

All code and UI text are in **English**. Backend is **Java 25 + Spring Boot** (Gradle
Kotlin DSL), frontend is **React + TypeScript + MUI**. Database is **SQLite** first,
with a portable-to-PostgreSQL path.

Legend: `[ ]` = pending, `[x]` = done, `[~]` = partial.

---

## Backlog (current) — remaining work, prioritized

> Consolidated list of everything still open. Individual items are tracked in the
> phase sections below; this is the execution order.

**P1 — finish Phase 3 gaps**
- [x] Static IP + CCD editor UI (backend ready: `CcdService`)
- [x] Group subnet allocation from pool + static IP from group settings
- [x] Per-user/group full-tunnel vs split-tunnel (now global only)
- [x] Per-daemon profile mapping (generic/auto-login daemons as first-class configs)
- [x] Inter-group connectivity rules + NAT-vs-routing mode
- [x] `CertService` restore/rotate + expiry-warning scheduler
- [x] Multi-daemon (`daemons` entity) + DCO detection display
- [x] Domain-based control via dnsmasq (advanced) — → M4 (4.2)
- [x] DNS overrides (internal hostnames served by dnsmasq, global + per-user/group scope) — → M4 (4.7)

**P0.5 — Bugfix sweep (live E2E findings, `docs/test-findings.md`)**
- [x] 2.1 CRIT — drop removed `client-cert-not-required` from config generator; GENERIC daemons use `verify-client-cert none`
- [x] 2.2 CRIT — entrypoint config watcher survives a failing daemon (no `set -e` death; auto-recovery verified)
- [x] 2.3 HIGH — per-daemon healthcheck compares configs ↔ live pidfiles (`/proc/<pid>/cmdline`)
- [x] 2.4 MED — profile-token create without `userId` → 400 `validation_failed` (was 500); bogus UUID → 404
- [x] 2.5 MED — unknown `/api/**` path → 404 `not_found` (was 500)
- [x] 2.6 LOW — MFA challenge tokens are single-use (redeemed `jti` set, TTL-pruned)
- [x] 2.7 LOW — access rule `dstCidr` validated as CIDR (was storing `not-a-cidr`)

**P0.5 — Minor follow-up sweep (live E2E round 2, `docs/test-findings.md` 2.8–2.12)**
> Separate work item for the minor findings from the round-2 live E2E pass. Everything
> here is polish/robustness — no core flow is broken (the CRIT/HIGH/MED of round 1 are
> fixed and verified above).
- [x] 2.8 MED — reconcile stale "Active" session rows when a daemon restarts (close rows
      whose session is gone from the live `status 3` view; unit + integration test)
- [x] 2.9 LOW — eliminate 401 bursts from background polls at token expiry (proactive
      refresh at ~80% TTL, silent JWT-exp scheduling in `api.ts`; frontend tests with
      fake timers)
- [x] 2.10 LOW — fix empty `#root` flash on `/login` after hard reload (inline splash in
      `index.html` so the root renders immediately while the bundle loads; manual
      regression step)
- [x] 2.11 LOW — a11y: give the flagged login form control an `id`/`name`/`aria-label`
      (all login-flow fields now carry `id`+`name`; frontend tests)
- [x] 2.12 LOW — purge legacy `not-a-cidr:443` access rule from the staging DB; regression:
      re-create → 400 (finding 2.7 holds)

**P0.5 — Follow-up sweep (M6 fresh-install E2E, `docs/test-findings.md` 2.13–2.14)**
- [x] 2.13 MED — demo-seeded cert rows have no backing PKI files: profile download for a
      demo user returns `pki_missing`; either issue the real cert on demand or return a
      clearer demo-capped error. Fix: `CertService.ensureUserCert` issues the real
      artifact on demand when a VALID row has no backing file (refreshes serial/expiry),
      so demo users' profiles download with real certs.
- [x] 2.14 HIGH — docs: never run a full-tunnel VPN client on the VPN server host
      (black-holes host routing, requires OOB recovery); add an operational note to
      `README`/`docs/architecture.md`
- [x] 2.15 MED — `DaemonService.validateUnique` does not check `ipv6Subnet`
      uniqueness: two daemons can share the same IPv6 tunnel subnet (e.g.
      `fd00:1::/64`). Fix: skip check when `ipv6Subnet` is blank/null, reject
      duplicates otherwise; tests for duplicate, different, and empty IPv6 subnets.
- [x] 2.16 HIGH — setup wizard settings not applied to first daemon: race
      condition between `MonitorService.poll()` (fires 3s after startup) and
      wizard. `list()` → `ensurePrimary()` creates daemon 0 with
      `ServerConfig.defaults()` before wizard saves settings; subsequent
      `ensurePrimary()` returns the stale daemon. Fix: new
      `DaemonService.createOrUpdatePrimary(ServerConfig)` that updates existing
      daemon fields; `SetupService.saveServerConfig()` calls it instead of
      `ensurePrimary()`. Tests for create-or-update paths.

**P4 — MFA integration completion (approved plan)**
- [x] Client `static-challenge` directive in password-auth .ovpn profiles
      (USER_LOCKED/SERVER_LOCKED/GENERIC) when MFA is in force (user `mfaEnabled`
      or server `require_mfa_on_connect`; AUTO_LOGIN excluded) — makes
      MFA-on-connect actually prompt for OTP
- [x] Admin MFA management UI on Users page (setup → QR + secret copy → verify
      code → enable; disable with confirm) — backend endpoints exist
      (`/api/admin/users/{id}/mfa/setup|enable|disable`)
- [x] Portal self-service account page (`/api/portal/account`): MFA
      setup/enable/disable (current-password verified) + password change (revoke
      all refresh tokens, clear `must_change_password`)
- [x] Tests: backend (PortalAccountService, OvpnGenerator/ProfileService) +
      frontend (UsersPage MFA dialog, AccountPage) — run via SSH on production
      checkout: backend 213 green (spotlessCheck clean), frontend 76 green
- [x] Docs: RELEASE_NOTES.md entry, docs/test-plan.md MFA E2E item
- [x] OpenVPN Connect (iOS/Android/3.x) MFA fix: `static-challenge` is not
      supported by Connect → server now uses the auth-pending flow
      (`auth-gen-token` + `client-crresponse`); `verify-user-pass.sh` writes a
      crtext auth-pending file and exits 2 when the account requires MFA,
      `POST /internal/auth/verify-otp` (`AuthService.verifyVpnOtp`) validates
      the TOTP second factor. CLI clients keep the inline `password\nOTP`
      static-challenge path (backend `verifyVpnLogin` unchanged).
- [x] GENERIC profile MFA fix: Phase 2 (`client-crresponse`) does NOT receive
      `$username` — only `$common_name` from the client cert. GENERIC profiles
      (no client cert) have empty `$common_name` → pending file lookup fails
      → `AUTH_FAILED`. Fix: Phase 1 keys pending data by `pendingId` instead of
      username, writes pendingId to `/tmp/passage-pending-latest` (well-known file
      Phase 2 can always read); Phase 2 reads pendingId → looks up username from
      `pending-data` file → sends correct username to backend. CN mismatch check
      added as security guard for cert-bearing profiles.

**P4.1 — Mandatory MFA (server-wide policy)**
- [x] `require_mfa` server setting (existing settings hierarchy): when effective,
      every user must enroll TOTP and present a code at web login and VPN connect
- [x] Forced enrollment at login: `/api/auth/login` returns `mustEnrollMfa` +
      short-lived single-use enroll challenge; `/api/auth/mfa/enroll` (QR/secret)
      and `/api/auth/mfa/enroll/confirm` (verify code, issue tokens); frontend
      `MfaEnrollPage` at `/login/enroll`
- [x] Enforcement: web `mfa()` relaxed to secret-based so provisioned-but-unconfirmed
      secrets verify; VPN `verifyVpnLogin`/`verifyVpnOtp` deny with
      `mfa_required`/`invalid_code` when policy requires
- [x] Disable-MFA blocked under policy (admin `UserAdminService` + portal
      `PortalAccountService`); `UserDto.mfaRequired` (policy-only) drives the
      frontend (AccountPage banner + disabled button, UsersPage "Required" chip)
- [x] Tests: backend `AuthServiceTest` (enroll/login/VPN enforcement) + frontend
      (MfaEnrollPage, LoginPage redirect, AccountPage policy state) — `make test` green

**P1.5 — Status / Settings / Dashboard phase (live E2E finding 2.5)** — DONE
> Scoped from live E2E testing (`docs/test-findings.md` §2.5): the frontend defined
> `/admin/status`, `/admin/settings`, `/admin/dashboard` but no backend controller existed.
> Instead of removing the dead endpoints, implement them as a dedicated phase.
- [x] `GET /api/admin/status` — `ServerStatusDto`: brand/version, per-daemon
      `{index,name,port,proto,enabled,configPresent,mgmtReachable}` (TCP probe to
      `openvpn:7505+index`), active connections from `ConnectionRegistry`, backend uptime
- [x] `GET/PUT/DELETE /api/admin/settings` — `SettingsAdminController` over the
      `SettingsService` server-level store (generic JSON key-value, same shape as per-user)
- [x] `GET /api/admin/dashboard` — `DashboardDto`: user/group/cert/connection counts,
      running-daemon count, recent connections
- [x] Frontend: `DashboardPage` consumes `/admin/dashboard` (live stat cards + daemon status
      + recent connections); new `StatusPage` (daemon table + active connections) and
      `SettingsPage` (server settings editor) replace the placeholders
- [x] Unit/component tests for the three endpoints and pages
- [x] Live E2E verification against production (login → dashboard/status/settings flows)

**P2 — Phase 4: real-time monitoring & advanced dashboard**
> Operational dashboard (stat cards, daemon health, settings) shipped in P1.5.
> This batch adds live traffic + session history on top.
- [x] `MgmtClient` (persistent TCP to `openvpn:7505+index`) + async event handling,
      per-daemon clients, reconnect with backoff
- [x] `TrafficAggregator` + WebSocket push (`/ws/status`, 2s snapshots) with REST
      fallback polling (bytes in/out per session)
- [x] Online users list, session duration, connection history persisted
      (`connection_logs`, Flyway V6, `/api/admin/connection-logs`)
- [x] Dashboard: MUI X Charts traffic, system info (oshi), DCO badge
- [x] Audit log entity + UI; syslog integration; `connection_logs` retention
  (Flyway V8/V9, `/api/admin/audit-logs` with pagination + filters)

**M4 — v0.1.0-alpha.8 — Automation & Advanced Access** (detail: `docs/ROADMAP.md` M4)
- [x] Post-auth Python script hook support (Phase 2.4/3.6)
- [x] Domain-based control via dnsmasq (Phase 3.2, advanced)
- [x] DNS overrides: admin-defined hostname→IPv4 records served by dnsmasq with
      GLOBAL/GROUP/USER scope (Flyway V12, `/api/admin/dns-overrides`, DNS
      Overrides page; out-of-scope users get a per-client firewall deny)
- [x] DNS override ⇄ access-rule conflict warnings: server-side detection when a
      scoped override is reachable by out-of-scope users via an ALLOW rule
      (ALLOW-wins ordering unchanged), surfaced as tooltip warnings in the DNS
      Overrides and Access Rules grids
- [x] `docs/api.md` generation — regenerated live via `make api-docs`
      (`v0.1.0-alpha.15`, 110 endpoints + schemas)
- [x] Full CRUD API completion — audit done: daemon management ✓, connection
      kill ✓, node lifecycle → M5; admin + portal namespaces full CRUD
- [x] Makefile polish — `api-docs` backend guard + split
      `test-backend`/`test-frontend`/`lint-backend`/`lint-frontend` targets
- [x] M4 release — `v0.1.0-alpha.8` tag + `RELEASE_NOTES.md` entry

**P1 — PKI reconciliation & deletion cleanup (v0.1.0-alpha.12)**
- [x] `CertService.reconcile()` — manual DB↔PKI sync (`POST /api/admin/certs/reconcile`, `CERT_RECONCILE`): rows created/updated from `index.txt` (incl. `revokedAt`), linked to users by username; server/phantom entries skipped, rows never deleted
- [x] User deletion cleanup — `DELETE /api/admin/users/{id}` + `/bulk` accept `{deleteCertificates, deleteAccessRules, clearCcd}`; certs revoked+purged (`purgeForUser`), access rules removed with dnsmasq refresh (`deleteForUser`), CCD static IP cleared
- [x] Frontend — Certificates "Sync with PKI" button; Users page delete dialog with cleanup checkboxes (single + bulk)
- [x] alpha.12 release — tag + `RELEASE_NOTES.md` entry

**M5 — v0.1.0-alpha.9 — Multi-node & Ops** (detail: `docs/ROADMAP.md` M5)
- [x] `openvpn_nodes` registry + node-aware status/kill/monitoring routing (Phase 4.6)
- [x] Backend `agent` Spring profile (Phase 4.6)
- [x] PostgreSQL docker profile validation (Phase 4.7)

**M6 — v0.1.0-beta.1 — Release Hardening** (detail: `docs/ROADMAP.md` M6)
- [x] Demo/seed mode (Phase 4.7)
- [x] CI docker build job (Phase 4.7)
- [x] Fresh-install E2E test pass (Phase 4.7)
- [x] README/docs finalization (Phase 4.7)
- [x] Cross-cutting sweep (Phase 4.7)

**M7 — Node & internal security hardening** (detail: `docs/ROADMAP.md` M7)
- [x] Mandatory management-interface password (per-daemon `.mgmt-pass` files, `MgmtHandshake`, fail-closed `MgmtClientManager`, startup check)
- [x] mTLS transport for node agents (internal CA/keystore bootstrap, 9443 connector, per-node agent certs via `/agent-cert`)
- [x] Mandatory internal token + fail-fast startup (`InternalTokenFilter`, `SecurityBootstrapCheck`, `NodeSecurityCheck`)
- [x] Source-IP pinning for agent register/heartbeat + `last_seen_ip` (Flyway V18)

**P0.5 — Security hardening sweep (approved plan A1–A3, B1–B4, C1–C2)**
- [x] A1 — portal profile availability: portal list/download/QR only expose profile
      types that are policy-enabled AND map to a matching daemon
      (`DaemonService.findMatchingForProfile`, `PortalProfileController.list()`
      `allowed`/`available` flags; PortalPage hides unavailable types + info note)
- [x] A2 — `portal_profile_types` server setting: default `USER_LOCKED, SERVER_LOCKED`;
      AUTO_LOGIN/GENERIC require an admin to enable (validation via
      `SettingValidator`, frontend known-settings entry)
- [x] A3 — portal certificate self-service: `GET /api/portal/cert` +
      `POST /api/portal/cert/rotate` (AccountPage "VPN certificate" card)
- [x] B1 — rate-limit SENSITIVE_PATHS extended (`/internal/auth/verify-otp`,
      `/internal/seed-admin`, `/internal/seed-demo`)
- [x] B2 — auth-pending nonce binding: phase-1 verify returns a single-use 120s
      `pendingId`; `verify-otp` consumes it (fail-closed); `verify-user-pass.sh`
      stashes it in `/tmp/passage-pending-<user>` between phases
- [x] B3 — `PASSAGE_BOOTSTRAP_TOKEN` + `SeedGuard`: seed-admin/seed-demo require
      `X-Bootstrap-Token` when configured; Makefile targets pass it
- [x] B4 — backend HTTP listener no longer published to the host (traffic enters via
      frontend nginx on :80; `/internal/**` stays network-internal)
- [x] C1 — verify reason normalization: `account_locked`/`account_disabled` →
      `invalid_credentials` (no account-state leak to connecting clients)
- [x] C2 — `IpFailureTracker` per-IP sliding-window lockout on VPN auth failures
      (reuses `passage.auth.lockout-*` settings; `ip_blocked` fail-fast)
- [x] C3 — RESELLER privilege-escalation fix: `assertCanManageUser` limits non-admin
      actors to managing USER-role accounts only (`resetPassword`/`updateUser`/`deleteUser`/
      `setBanned`/static-IP/settings now take the acting user and reject ADMIN/RESELLER
      targets; UI hides those actions for resellers). Found + verified live: a reseller
      could previously take over the admin account via password reset.
- [x] C4 — post-C3 endpoint sweep (every controller audited for role-independent access):
      `/api/admin/**` role-gated, `/api/portal/**` self-scoped, `/internal/**` token+
      mTLS guarded, `/ws/**` admin-JWT handshake, share tokens single-use. Fix:
      `GET /api/setup/server-config` (was anonymous forever, leaks subnet/ports/DNS/admin
      host) is now `@PreAuthorize("hasRole('ADMIN')")`; wizard state/`/wizard` steps stay
      public because the state machine guards transitions. `SetupControllerSecurityTest`
      added.

**P5 — Multi-node cert/config distribution (approved plan: A/B/C)**
- [x] A — shorter certificate lifetime + rotation policy: `EASYRSA_CERT_EXPIRE` 3650→730
      (`PASSAGE_PKI_CERT_EXPIRE`), CRL generated for ≥ cert lifetime; server settings
      `cert_auto_rotate` (`off`/`notify`/`auto`, default `notify`) + `cert_rotate_days_before`
      (default 14); daily scheduler auto-rotates (audit `CERT_ROTATE_AUTO`) only
      account-bound VALID certs; portal "certificate expires soon" warning on AccountPage
- [x] B — multi-remote profiles: `OpenVpnNode.adminHost` (Flyway V19), all matching
      daemons across nodes as multiple `remote` lines + `remote-random`;
      `profile_multi_remote` server setting (default on)
- [x] C — agent config + CRL pull (Pritunl-link pattern): `GET /internal/node/config`
      bundle (daemon conf/mgmt-pass rendered for remote paths, ca/crl/ta.key/server
      cert/key, CCD, scripts, dnsmasq), `AgentConfigSyncService` atomic write +
      entrypoint-watcher reload, compose `node` profile volumes
- [x] Live E2E on the staging host: agent mTLS registration + heartbeats, bundle pull
      (17 files), central→node management connection (passage-node-openvpn:7508), node
      openvpn daemon running, CRL revocation propagation (revoke → new bundle →
      node `crl.pem` matches central). Real bugs found & fixed: agent env vars
      (`PASSAGE_JWT_SECRET`/`PASSAGE_OPENVPN_MGMT_PASSWORD`), `@Nullable DnsmasqConfigService`
      for the agent profile, `@Autowired AgentRegistrationService`, nginx backend DNS
      caching (`resolver 127.0.0.11` + variable `proxy_pass`), 9443 connector
      `SSLEnabled`, keytool-based truststore bootstrap
- [x] Idempotent cert re-issue on username re-use: `CertService.ensureUserCert` purges
      stale certs left by a deleted account (revoke → CRL rejects, purge on-disk
      artifacts, drop bookkeeping row) before issuing a fresh certificate

**P6 — Scoped GROUP_ADMIN RBAC (`RESELLER` → `GROUP_ADMIN`)**
- [x] Data model: Flyway `V20__group_admin` (SQLite + Postgres) — `group_admin_assignments`
      join table + `RESELLER` → `USER` demotion; `GroupAdminAssignment` entity/repository;
      `User.Role` = `ADMIN`/`GROUP_ADMIN`/`USER`
- [x] `GroupScope` scope resolution (managed root groups + descendants, scoped user
      ids/usernames) applied across `UserAdminService`, `GroupAdminService`,
      `ConnectionLogService`; group-admin restricted to USER accounts in scope
- [x] Controllers: users/groups/connection-logs open to `GROUP_ADMIN` with actor
      plumbing; group admins cannot create new root groups or delete managed roots
- [x] API tokens ADMIN-only (`invalid_role` otherwise); demo seed binds `dave` → `devops`
- [x] Frontend: roles/routing/nav, Users page managed-groups picker + "Manages" column,
      Groups page root-group guards, new Connection Logs page, ApiTokens ADMIN-only
- [x] Tests: backend 599 green, frontend 147 green; docs (architecture, README,
      AGENTS, test-plan, api.md, RELEASE_NOTES) updated

---

## Phase 0 — Project Scaffolding

- [x] `TODO.md` and `AGENTS.md` (this file + agent guide)
- [x] Repo layout, `.gitignore`, `.env.example`
- [x] Gradle wrapper + `settings.gradle.kts` + `build.gradle.kts` (Java 25, Spring Boot 3.5)
- [x] `docker-compose.yml` (openvpn / backend / frontend / db)
- [x] `Makefile` skeleton (`up/down/build/logs/test/...`)
- [x] `install.sh` single-command installer skeleton
- [x] OpenVPN container image (`openvpn/Dockerfile`, base `server.conf`, scripts)
- [x] Backend application entrypoint + health/actuator endpoints
- [x] Frontend Vite + MUI scaffold (dark theme, routing shell, layout)

## Phase 1 — Core OpenVPN + Easy-RSA Integration

### 1.1 OpenVPN daemon + management interface
- [x] OpenVPN 2.6 image (alpine) with Easy-RSA 3.1, iptables, dnsmasq
- [x] Base `server.conf` template: management socket, status file, auth scripts, CCD, CRL
- [x] Expose management interface (TCP 7505) on the docker network
- [x] Healthcheck: openvpn process + management socket reachable

### 1.2 Easy-RSA / PKI service (backend)
- [x] `ProcessRunner` — safe subprocess wrapper with timeouts + env
- [x] `EasyRsaService` — init-pki, build-ca, build-server-full, build-client-full, revoke, gen-crl
- [x] `index.txt` parser → certificate metadata sync into DB
- [x] `CertService` — issue, revoke, restore, rotate; expiry warnings (scheduler)
- [x] Certificate entity + Flyway migration (metadata sync in Phase 2)
- [x] Shared PKI volume mounted into openvpn container

### 1.3 Server configuration engine
- [x] `ServerSettings` entity (port, proto, pool, DNS, routes, cipher, DCO, ...)
- [x] `ServerConfigGenerator` — renders `server.conf` from settings
- [x] Multi-daemon support (`daemons` entity) — per-daemon conf + management port
- [x] Auto daemon port allocation: publish a configurable UDP/TCP host range
      (`PASSAGE_OPENVPN_PORT[_END]`, `PASSAGE_OPENVPN_TCP_PORT[_END]`); daemons without
      an explicit port get the next free port of their protocol range and out-of-range
      ports are rejected, so added daemons are always reachable
- [x] Apply flow: write conf → management `signal` reload / container restart
- [x] DCO (Data Channel Offload) detection + display (status-based, UI on Status/Daemons)

### 1.4 Database layer
- [x] SQLite + Flyway wired (community dialect, WAL mode)
- [x] `application.yml` + `application-postgres.yml` profile
- [x] Base entities: `server_settings`, `daemons`, `users`, `groups`, `connection_logs`, ...

### 1.5 Setup wizard (backend API)
- [x] `/api/setup/state` — initialization state machine
- [x] Wizard steps: admin user → PKI init (CA + server cert) → network settings → apply
- [x] Lock admin endpoints until setup complete

## Phase 2 — Users, Groups & Authentication

### 2.1 Data model
- [x] `User` / `Group` entities + settings inheritance (user > group > server default)
- [x] Settings stored as JSON strings (portable), typed accessors
- [x] Flyway migrations (V3: groups, group_members, user/group settings, refresh_tokens)

### 2.2 User/Group administration
- [x] User CRUD: create, edit, delete, ban/unban, admin grant (GROUP_ADMIN-scoped restrictions)
- [x] Group CRUD + assignment, nested groups
- [x] Per-user and per-group settings (inheritance resolution)
- [x] User search (server-side), status filter, bulk operations (UI)
- [x] Static IP assignment (Phase 3 CCD)

### 2.3 Authentication (web UI)
- [x] JWT access + refresh tokens, rotation, logout (hash-stored refresh tokens)
- [x] RBAC: `ADMIN` / `GROUP_ADMIN` / `USER`
- [x] Local password auth (BCrypt)
- [x] TOTP MFA (Google Authenticator compatible) — enable/disable/reset (admin API)
- [x] Brute-force lockout policy (attempts + lock duration)
- [x] Rate limiting (bucket4j) on login/MFA/refresh/internal-verify; CSRF disabled (stateless bearer API)
- [x] `AuthProvider` SPI: local now; LDAP/RADIUS/SAML stub interfaces (selectable via `passage.auth.provider`)

### 2.4 VPN authentication integration
- [x] `auth-user-pass-verify` script → backend `/internal/auth/verify`
- [x] Password + OTP verification, lockout, user ban checks
- [x] Auto-login (cert-only) + `client-connect` validation path (Phase 3)
- [x] `static-challenge` handling for MFA-on-connect (challenge via verify-user-pass script)
- [x] Post-auth Python script hook support

### 2.5 Frontend
- [x] Login + MFA screens (dark/light) — wired to real session state
- [x] User management page (MUI DataGrid: search, filter, bulk, confirm dialogs)
- [x] Group management page
- [x] Toasts, loading states, dialogs (data-grid loading, pending buttons)
- [x] Profile settings page (password, MFA setup) (portal, Phase 3)

## Phase 3 — Access Control & Connection Profiles

### 3.1 CCD (Client Config Dir)
- [x] `CcdService` — per-user CCD files (static IP, routes, per-user directives)
- [x] Group subnet allocation from pool
- [x] Static IP conflict detection
- [x] CCD written to shared volume, applied per connection

### 3.2 Access control / ZTNA
- [x] `AccessRule` entity (user/group target, action, src/dst, protocol/port, priority)
- [x] `RuleEngine` — evaluate rules → iptables rule set
- [x] iptables generator + `apply-rules.sh` (requires `NET_ADMIN`)
- [x] End-to-end validated: ALLOW/DENY → ACCEPT/DROP targets; per-client chain enforced over live tunnel
- [x] Full-tunnel / split-tunnel per user/group
- [x] Inter-group connectivity rules (dstGroupId target → pool range / member IPs)
- [x] NAT vs routing mode (network_mode setting; apply-rules skips MASQUERADE in routed mode)
- [x] Domain-based control via dnsmasq (advanced)

### 3.3 Connection profiles (.ovpn)
- [x] `OvpnGenerator` — 4 profile types: user-locked, auto-login, server-locked, generic
- [x] Profile types mapped to daemons (generic → `verify-client-cert none` daemon; auto-login → daemon without auth-user-pass)
- [x] Token URLs — time-limited or permanent, single/multi-use
- [x] QR code sharing (OpenVPN Connect import XML)
- [x] Client portal — users download own profiles

### 3.4 Frontend
- [x] Access rules editor page
- [x] Profile download/QR/token management UI
- [x] Client portal UI (self-service)
- [x] Static IP + CCD editor UI

## Phase 4 — Monitoring, Admin & Deployment

### 4.1 Real-time monitoring
- [x] Management interface TCP client (`MgmtClient`) — async events + `status 3` poll,
      per-daemon clients (`openvpn:7505+index`), reconnect with backoff
- [x] Event handling: `>CLIENT:ESTABLISHED`, `>CLIENT:DISCONNECT`, `>BYTECOUNT:` plus
      `kill <cn>` with confirmation
- [x] In-memory `ConnectionRegistry` (fed by `client-connect`/`learn-address`, read via
      `/api/admin/connections` + dashboard); `TrafficAggregator` shipped
- [x] WebSocket push to frontend (`/ws/status`, ADMIN-only handshake, 2s snapshots,
      REST fallback polling)
- [x] Online users list (live), session duration, bytes in/out
- [x] Connection history (session logs) persisted (`connection_logs`, Flyway V6)

### 4.2 Dashboard
- [~] Live stat cards (connections/users/groups/certs) + recent connections — shipped (P1.5)
- [x] Traffic rate + charts (MUI X Charts — dependency already present)
- [x] System info: CPU/mem/disk (oshi), OpenVPN version, DCO

### 4.3 Logging & audit
- [x] Audit log entity (admin actions) + UI
- [x] Syslog integration (audit + auth events)
- [x] `connection_logs` retention policy

### 4.4 REST API
- [x] Full CRUD API for all resources (admin + portal namespaces)
- [x] OpenAPI/Swagger UI + bearer-auth testing (v0.1.0-alpha.4)
- [x] `docs/api.md` generation; API tokens for automation
- [x] API tokens for automation

### 4.5 Branding, backup, config report
- [x] Brand settings (logo, name, footer, primary color) → theme via API
- [x] Configuration report (settings snapshot + PKI inventory + versions)
- [x] Backup: ZIP of config + PKI + DB snapshot; restore flow
- [x] Backup import (upload + marker/zip-slip validation) + auto-restore prompt; exec-bit preserved on restore
- [x] Maintenance: preflight checks (db, settings, daemon config, pki) + backend restart + daemon reload (Danger Zone)

### 4.6 Multi-node
- [x] `openvpn_nodes` registry (name, mgmtHost, mgmtPortBase, adminIp, enabled) + admin CRUD + VPN Nodes page
- [x] Node-aware status/kill/monitoring routing (per-node `MgmtClient`, node-scoped monitor/status, connection-log reconciliation)
- [x] Backend `agent` Spring profile (lightweight OpenVPN node agent registering/heartbeating via `/internal/node/*`)

### 4.7 Deployment & polish
- [x] Complete `Makefile` targets (api-docs guard, split test/lint targets)
- [x] `install.sh` full installer (preflight, env, build, up, wizard)
- [x] First-run wizard UI (frontend) — admin → VPN server → PKI → complete; login gated on `COMPLETE`
- [x] PostgreSQL profile validation — `db/migration-postgresql` set (V1–V17 minus V13), `application-postgres.yml`, `MigrationParityTest`
- [x] Seed data + demo mode
- [ ] E2E test pass (backend unit+integration, frontend unit)
- [x] README + docs finalization
- [x] CI workflow (backend test + spotless, frontend lint + build + test, docker image build job)
- [x] Coverage gates — backend JaCoCo instruction ≥ 80% (measured 88.9%), frontend vitest thresholds 90/80/90 (measured 95.1/85.6/95.1), enforced via `jacocoTestCoverageVerification` + `npm run test:coverage` in CI

---

## Cross-cutting
- [ ] Environment-variable-driven configuration everywhere
- [ ] No secrets in code; `.env` only
- [ ] English-only UI strings and comments
- [ ] Code quality: lint + format (Spotless, ESLint), unit tests per module

## Optimization (resource consumption)
- [x] Monitor loop: idle gating (30s cadence with no WS subscribers) + delta broadcasts (skip unchanged snapshots) — `MonitorService`
- [x] Traffic history: sparse append — identical consecutive polls do not grow history — `TrafficAggregator`
- [x] User list: N+1 settings resolution replaced by one batched pass — `SettingsService.effectiveForUsers` + `UserAdminService.listUsers`
- [x] Server settings decoded once and cached, invalidated on write — `SettingsService`
- [x] Dashboard DB counters cached with a 2s TTL — `DashboardAdminService`
- [x] API token lookups cached with a 60s TTL, cleared on create/delete — `ApiTokenService`
- [x] JVM heap/metaspace bounded via `JAVA_TOOL_OPTIONS` (`MaxRAMPercentage=60`, metaspace 256m, exit on OOM) — `backend/Dockerfile`
- [x] Frontend: route-level lazy loading (per-page chunks) + `mui-x` split into grid/charts chunks — `App.tsx`, `vite.config.ts`
- [x] nginx: `gzip_static` (pre-compressed assets), immutable caching for hashed `/assets/`, `gzip_vary` — `nginx.conf`, `frontend/Dockerfile`
- [x] Tomcat request thread pool capped at 50 (`PASSAGE_TOMCAT_THREADS`) — `application.yml`
- [x] Docker Compose resource limits (CPU/mem) for openvpn/backend/frontend/agent
- [x] Backend image: BuildKit Gradle cache mounts, `.dockerignore`, runtime trim kept on glibc Temurin (Alpine rejected: sqlite-jdbc JNI is glibc-only)
- [x] CI: Gradle dependency cache via `gradle/actions/setup-gradle` — `.github/workflows/ci.yml`
- [x] SQLite `VACUUM` nightly at 03:30 (after log/cert purges), SQLite-only via raw JDBC — `MaintenanceService.vacuumSqlite`

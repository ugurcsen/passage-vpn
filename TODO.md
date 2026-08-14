# TODO — OpenVPN Management Panel

Development roadmap for the OpenVPN Access Server–like management panel built on
OpenVPN Community Edition + Easy-RSA + OpenVPN Management Interface.

All code and UI text are in **English**. Backend is **Java 21 + Spring Boot** (Gradle
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
- [ ] `docs/api.md` generation (script + make target ready; regenerate live in E2E)
- [ ] Full CRUD API completion (Phase 4.4)
- [ ] Makefile polish (Phase 4.7)
- [x] M4 release — `v0.1.0-alpha.8` tag + `RELEASE_NOTES.md` entry

**P1 — PKI reconciliation & deletion cleanup (v0.1.0-alpha.12)**
- [x] `CertService.reconcile()` — manual DB↔PKI sync (`POST /api/admin/certs/reconcile`, `CERT_RECONCILE`): rows created/updated from `index.txt` (incl. `revokedAt`), linked to users by username; server/phantom entries skipped, rows never deleted
- [x] User deletion cleanup — `DELETE /api/admin/users/{id}` + `/bulk` accept `{deleteCertificates, deleteAccessRules, clearCcd}`; certs revoked+purged (`purgeForUser`), access rules removed with dnsmasq refresh (`deleteForUser`), CCD static IP cleared
- [x] Frontend — Certificates "Sync with PKI" button; Users page delete dialog with cleanup checkboxes (single + bulk)
- [x] alpha.12 release — tag + `RELEASE_NOTES.md` entry

**M5 — v0.1.0-alpha.9 — Multi-node & Ops** (detail: `docs/ROADMAP.md` M5)
- [ ] `openvpn_nodes` registry + node-aware status/kill/monitoring routing (Phase 4.6)
- [ ] Backend `agent` Spring profile (Phase 4.6)
- [ ] PostgreSQL docker profile validation (Phase 4.7)

**M6 — v0.1.0-beta.1 — Release Hardening** (detail: `docs/ROADMAP.md` M6)
- [ ] Demo/seed mode (Phase 4.7)
- [ ] CI docker build job (Phase 4.7)
- [ ] Fresh-install E2E test pass, README/docs finalization (Phase 4.7)
- [ ] Cross-cutting sweep

---

## Phase 0 — Project Scaffolding

- [x] `TODO.md` and `AGENTS.md` (this file + agent guide)
- [x] Repo layout, `.gitignore`, `.env.example`
- [x] Gradle wrapper + `settings.gradle.kts` + `build.gradle.kts` (Java 21, Spring Boot 3.5)
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
- [x] User CRUD: create, edit, delete, ban/unban, admin grant (RESELLER-scoped restrictions)
- [x] Group CRUD + assignment, nested groups
- [x] Per-user and per-group settings (inheritance resolution)
- [x] User search (server-side), status filter, bulk operations (UI)
- [x] Static IP assignment (Phase 3 CCD)

### 2.3 Authentication (web UI)
- [x] JWT access + refresh tokens, rotation, logout (hash-stored refresh tokens)
- [x] RBAC: `ADMIN` / `RESELLER` / `USER`
- [x] Local password auth (BCrypt)
- [x] TOTP MFA (Google Authenticator compatible) — enable/disable/reset (admin API)
- [x] Brute-force lockout policy (attempts + lock duration)
- [x] Rate limiting (bucket4j) on login/MFA/refresh/internal-verify; CSRF disabled (stateless bearer API)
- [x] `AuthProvider` SPI: local now; LDAP/RADIUS/SAML stub interfaces (selectable via `opnl.auth.provider`)

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
- [ ] Full CRUD API for all resources (admin + portal namespaces)
- [x] OpenAPI/Swagger UI + bearer-auth testing (v0.1.0-alpha.4)
- [ ] `docs/api.md` generation; API tokens for automation
- [x] API tokens for automation

### 4.5 Branding, backup, config report
- [x] Brand settings (logo, name, footer, primary color) → theme via API
- [x] Configuration report (settings snapshot + PKI inventory + versions)
- [x] Backup: ZIP of config + PKI + DB snapshot; restore flow
- [x] Backup import (upload + marker/zip-slip validation) + auto-restore prompt; exec-bit preserved on restore
- [x] Maintenance: preflight checks (db, settings, daemon config, pki) + backend restart + daemon reload (Danger Zone)

### 4.6 Multi-node
- [ ] `openvpn_nodes` registry (name, management endpoint, admin IP)
- [ ] Node-aware status/kill/monitoring routing
- [ ] Backend `agent` Spring profile (lightweight OpenVPN node agent) — stretch

### 4.7 Deployment & polish
- [ ] Complete `Makefile` targets
- [x] `install.sh` full installer (preflight, env, build, up, wizard)
- [x] First-run wizard UI (frontend) — admin → VPN server → PKI → complete; login gated on `COMPLETE`
- [ ] PostgreSQL docker profile + `application-postgres.yml` validation
- [ ] Seed data + demo mode
- [ ] E2E test pass (backend unit+integration, frontend unit)
- [ ] README + docs finalization
- [~] CI workflow (backend test + spotless, frontend lint + build + test; docker build job pending)

---

## Cross-cutting
- [ ] Environment-variable-driven configuration everywhere
- [ ] No secrets in code; `.env` only
- [ ] English-only UI strings and comments
- [ ] Code quality: lint + format (Spotless, ESLint), unit tests per module

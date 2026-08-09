# TODO — OpenVPN Management Panel

Development roadmap for the OpenVPN Access Server–like management panel built on
OpenVPN Community Edition + Easy-RSA + OpenVPN Management Interface.

All code and UI text are in **English**. Backend is **Java 21 + Spring Boot** (Gradle
Kotlin DSL), frontend is **React + TypeScript + MUI**. Database is **SQLite** first,
with a portable-to-PostgreSQL path.

Legend: `[ ]` = pending, `[x]` = done, `[~]` = partial.

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
- [~] `CertService` — issue, revoke, restore, rotate; expiry warnings (scheduler)
- [x] Certificate entity + Flyway migration (metadata sync in Phase 2)
- [x] Shared PKI volume mounted into openvpn container

### 1.3 Server configuration engine
- [x] `ServerSettings` entity (port, proto, pool, DNS, routes, cipher, DCO, ...)
- [x] `ServerConfigGenerator` — renders `server.conf` from settings
- [~] Multi-daemon support (`daemons` entity) — per-daemon conf + management port
- [x] Apply flow: write conf → management `signal` reload / container restart
- [~] DCO (Data Channel Offload) detection + display (status-based, UI in Phase 4)

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
- [ ] Post-auth Python script hook support

### 2.5 Frontend
- [x] Login + MFA screens (dark/light) — wired to real session state
- [x] User management page (MUI DataGrid: search, filter, bulk, confirm dialogs)
- [x] Group management page
- [x] Toasts, loading states, dialogs (data-grid loading, pending buttons)
- [ ] Profile settings page (password, MFA setup) (portal, Phase 3)

## Phase 3 — Access Control & Connection Profiles

### 3.1 CCD (Client Config Dir)
- [x] `CcdService` — per-user CCD files (static IP, routes, per-user directives)
- [ ] Group subnet allocation from pool
- [x] Static IP conflict detection
- [x] CCD written to shared volume, applied per connection

### 3.2 Access control / ZTNA
- [x] `AccessRule` entity (user/group target, action, src/dst, protocol/port, priority)
- [x] `RuleEngine` — evaluate rules → iptables rule set
- [x] iptables generator + `apply-rules.sh` (requires `NET_ADMIN`)
- [ ] Full-tunnel / split-tunnel per user/group
- [ ] Inter-group connectivity rules
- [ ] NAT vs routing mode
- [ ] Domain-based control via dnsmasq (advanced)

### 3.3 Connection profiles (.ovpn)
- [x] `OvpnGenerator` — 4 profile types: user-locked, auto-login, server-locked, generic
- [~] Profile types mapped to daemons (generic → `client-cert-not-required` daemon; auto-login → daemon without auth-user-pass)
- [x] Token URLs — time-limited or permanent, single/multi-use
- [x] QR code sharing (OpenVPN Connect import XML)
- [x] Client portal — users download own profiles

### 3.4 Frontend
- [x] Access rules editor page
- [x] Profile download/QR/token management UI
- [x] Client portal UI (self-service)
- [ ] Static IP + CCD editor UI

## Phase 4 — Monitoring, Admin & Deployment

### 4.1 Real-time monitoring
- [ ] Management interface TCP client (`MgmtClient`) — async events + `status 3` poll
- [ ] Event handling: `>CLIENT:ESTABLISHED`, `>CLIENT:DISCONNECT`, `>BYTECOUNT:`
- [ ] In-memory `ConnectionRegistry` + `TrafficAggregator` (per-minute metrics)
- [ ] WebSocket push to frontend (native WS, tiny JSON protocol)
- [ ] Online users list (live), session duration, bytes in/out
- [ ] Connection history (session logs) persisted

### 4.2 Dashboard
- [ ] Live: active connections, total users, traffic rate
- [ ] Charts (MUI X Charts): connections + traffic over time
- [ ] System info: CPU/mem/disk (backend actuator), OpenVPN version, DCO

### 4.3 Logging & audit
- [ ] Audit log entity (admin actions) + UI
- [ ] Syslog integration (audit + auth events)
- [ ] `connection_logs` retention policy

### 4.4 REST API
- [ ] Full CRUD API for all resources (admin + portal namespaces)
- [ ] OpenAPI/Swagger UI + `docs/api.md`
- [ ] API tokens for automation

### 4.5 Branding, backup, config report
- [ ] Brand settings (logo, name, footer, primary color) → theme via API
- [ ] Configuration report (settings snapshot + PKI inventory + versions)
- [ ] Backup: tar of config + PKI + DB dump; restore flow

### 4.6 Multi-node
- [ ] `openvpn_nodes` registry (name, management endpoint, admin IP)
- [ ] Node-aware status/kill/monitoring routing
- [ ] Backend `agent` Spring profile (lightweight OpenVPN node agent) — stretch

### 4.7 Deployment & polish
- [ ] Complete `Makefile` targets
- [ ] `install.sh` full installer (preflight, env, build, up, wizard)
- [ ] First-run wizard UI (frontend)
- [ ] PostgreSQL docker profile + `application-postgres.yml` validation
- [ ] Seed data + demo mode
- [ ] E2E test pass (backend unit+integration, frontend unit)
- [ ] README + docs finalization
- [ ] CI workflow (build, test, docker)

---

## Cross-cutting
- [ ] Environment-variable-driven configuration everywhere
- [ ] No secrets in code; `.env` only
- [ ] English-only UI strings and comments
- [ ] Code quality: lint + format (Spotless, ESLint), unit tests per module

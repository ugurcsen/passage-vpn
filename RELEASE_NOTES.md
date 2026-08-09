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

### Verified end-to-end
- Backend: 59 unit tests green; `make test`, `make lint` and spotless pass.
- Live E2E: setup wizard → PKI provisioned → OpenVPN daemon boots from the
  generated config → management interface (`status`, `dco_enabled`) works →
  login / MFA challenge+redeem / token refresh / VPN credential verify all pass
  through the API and the Vite dev proxy; user search, bulk ban/unban/delete and
  per-IP rate limiting (429 + `Retry-After`) verified live.
- Phase 3 verified via unit tests: rule resolution (global/group/user, disabled
  rules), iptables rendering (per-client chain + teardown), certificate issue/
  reuse/revoke, and all four profile types + token use consumption.

### Not yet released
- Phase 3 — group subnet allocation, per-user full/split tunnel, inter-group
  connectivity rules, NAT-vs-routing mode, dnsmasq domain control, static IP/CCD
  editor UI, per-daemon profile mapping
- Phase 4 — Monitoring/dashboard, logging & audit, Swagger, branding, backup,
  multi-node, installer polish, PostgreSQL profile validation

---

## Previous releases

None yet — this is the first release notes entry.

# OPNL-VPN Roadmap

Execution roadmap for the remaining OpenVPN management panel work. This file is
the agent-facing plan; task checkboxes mirror `TODO.md` and `RELEASE_NOTES.md`.

Legend: `[ ]` = pending, `[x]` = done, `[~]` = partial.

---

## Working protocol (every task)

1. Pick an item from the milestone below; flip its checkbox to `[~]` while working.
2. Implement code + unit tests (backend and frontend where applicable).
3. Run verification **via SSH on the production checkout** (`root@65.21.108.250:/root/opnl_vpn`):
   - `./gradlew test` and `./gradlew spotlessCheck`
   - `npm run test` (and `npm run lint`)
4. Sync changed files to the server checkout (`scp`), commit there with the repo
   push script, tag when a milestone ships.
5. Flip the checkbox to `[x]`; update `RELEASE_NOTES.md` when a milestone is released.

## M1 — Phase 3 completion (network & access) — DONE (v0.1.0-alpha.2, 2026-08-12)

- [x] **1.1 Static IP pool + CCD editor UI** — backend `CcdService` is ready
      (`setStaticIp`/`clearStaticIp`/`writeUserCcd` + conflict detection).
      Add: per-group static IP pool (group setting `static_ip_pool`, e.g.
      `10.8.0.100-10.8.0.199`), auto-allocation of the next free IP from the
      group pool, allocation through the group API; CCD editor dialog on the
      Users page (static IP, per-user DNS/routes) and pool editor on the Groups
      page. Tests: `CcdServiceTest` allocation/conflict/pool-exhausted cases.
- [x] **1.2 Per-user/group full vs split tunnel** — `SettingKeys.TUNNEL_MODE`
      (`full`/`split`) at user and group level; `CcdService.writeUserCcd` pushes
      `redirect-gateway def1 bypass-dhcp` for full-tunnel users, otherwise only
      configured routes; falls back to the server network config's global
      `fullTunnel` flag when unset. UI: tunnel mode selector on the Users CCD
      editor and the Groups settings dialog. Tests: `CcdServiceTest` 4 new
      tunnel-mode cases (full/split/split-minimal/server-default).
- [x] **1.3 Inter-group connectivity rules** — `AccessRule` gains a
      `dstGroupId` target (Flyway V7); `RuleEngine` resolves the destination
      group's allocated subnet when rendering iptables: the group's `static_ip_pool`
      range as an exact `-m iprange --dst-range` match, falling back to member
      static IPs (`/32`) when no pool is set. UI: destination group picker on the
      Access Rules page (mutually exclusive with CIDR). Tests: `RuleEngineTest`
      pool-range / member-IP / unresolvable cases + `AccessRuleServiceTest`
      dstGroup validation.
- [x] **1.4 NAT vs routing mode** — new server setting `network_mode`
      (`nat`|`routed`) validated at the settings API; a change (or delete →
      default `nat`) rewrites the daemon configs. `ServerConfigGenerator` surfaces
      the mode as `# network-mode <mode>` in `daemon.conf`; the container
      `entrypoint.sh` extracts it and `apply-rules.sh` skips MASQUERADE in routed
      mode and installs the tun return route instead. UI: `network_mode` choice
      toggle on the Settings page. Tests: `ServerConfigGeneratorTest`
      surfacing/normalization, `DaemonServiceTest` mode resolution, and
      `SettingsAdminControllerTest` validation + rewrite trigger.
- [x] **1.5 CertService restore/rotate + expiry-warning scheduler** —
      `CertService.restore(id)` (re-verify a revoked cert in `index.txt`),
      `CertService.rotate(userId)` (issue new cert + revoke old), and a
      `@Scheduled` daily expiry scan exposing
      `GET /api/admin/certs?expiring=true`. UI: Restore/Rotate actions and an
      expiry badge on the Certs page. Tests: restore/rotate + scheduler cases.
- [x] **1.6 DCO detection display** — surface DCO (Data Channel Offload)
      availability consistently on the Status and Daemons pages (per-daemon
      badge; backend `ServerStatusDto`/`DaemonDto` already carry fields).

## M2 — Monitoring & ops completion — DONE (v0.1.0-alpha.3, 2026-08-13)

- [x] **2.1 Audit log (UI included)** — new `audit_logs` entity + Flyway V9;
      audit service records admin actions (user/group/rule/daemon/settings
      mutations, MFA changes, login events); `GET /api/admin/audit-logs`
      (paginated, filterable); new Audit Log page with DataGrid. Tests: backend
      audit recording + frontend page.
- [x] **2.2 Syslog integration** — ship audit + auth events to syslog
      (configurable target; RFC3164 line format) while still storing in DB.
- [x] **2.3 `connection_logs` retention** — periodic purge of closed rows older
      than a configurable retention window (server setting), plus index tuning.

## M3 — API, ops & deployment (superseded)

Work started under M3 has been split: completed items shipped in
`v0.1.0-alpha.4`/`v0.1.0-alpha.5`; remaining items roll into M4–M6 below.

- [x] **3.1a OpenAPI/Swagger UI + bearer-auth testing** — shipped in `v0.1.0-alpha.4`
- [x] **3.1b API tokens for automation** — shipped (`/api/admin/api-tokens`, `X-API-Token`)
- [x] **3.2 Brand settings + configuration report + backup/restore** — shipped in `v0.1.0-alpha.5`
- [x] **3.4a `install.sh` full installer + first-run wizard UI** — shipped
- [ ] **3.1c `docs/api.md` generation** — → M4
- [ ] **3.3 Multi-node registry + node-aware routing + `agent` profile** — → M5
- [ ] **3.4b Demo/seed mode** — → M6
- [ ] **3.5 PostgreSQL profile validation** — → M5
- [x] **3.6 Post-auth Python script hook** — shipped in M4 (4.1)
- [ ] **3.7 E2E pass + docs finalization + CI docker job** — → M6

## M4 — Automation & Advanced Access — TARGET `v0.1.0-alpha.6`

- [x] **4.1 Post-auth Python script hook** — `SettingKeys.post_auth_script` (+
      timeout); `AuthService.verifyVpnLogin` runs the script via `ProcessRunner`
      after a successful VPN login (env: username, remote_ip, daemon; stderr
      captured; hook failure must not drop the connection — audit it). Script
      synced into the shared config volume by `ScriptSync`. Tests:
      `AuthServiceTest` hook success/failure/timeout cases.
- [x] **4.2 Domain-based control via dnsmasq** — `AccessRule` domain target
      (`dstDomain`, Flyway V11) resolved to IPs by `RuleEngine`; per-domain
      entries rendered into `/etc/dnsmasq.d/opnl-domains.conf` and the matching
      iptables rules by `apply-rules.sh`; domain picker on the Access Rules page.
      Tests: `RuleEngineTest` domain resolution, `ServerConfigGeneratorTest`
      dnsmasq config rendering.
- [ ] **4.3 `docs/api.md` generation** — regenerate via `make api-docs` against
      the live backend and commit; verify in live E2E.
- [ ] **4.4 Full CRUD API completion** — audit admin/portal namespaces for gaps
      (daemon management, connection kill, node lifecycle) and fill them.
- [ ] **4.5 Makefile polish** — complete targets, `api-docs` verification step,
      `help` refresh.
- [x] **4.7 DNS overrides** — admin-defined internal hostname → IPv4 records
      served authoritatively by the shared dnsmasq (`Flyway V12`, entity
      `DnsRecord`, `DnsOverrideService`, `/api/admin/dns-overrides`). GLOBAL
      records apply to all clients; GROUP/USER-scoped records resolve for
      everyone but only the target scope may reach the address (per-client
      scope-DENY lines from `RuleEngine.scopeDenyIpsFor`; chain terminal stays
      ACCEPT when only scope denials exist, DROP otherwise). Overrides also win
      over public DNS when an access-rule domain matches. Written to
      `/etc/dnsmasq.d/opnl-dns-overrides.conf` by `DnsmasqConfigService`.
      Tests: `DnsOverrideServiceTest`, `DnsRecordAdminControllerTest`,
      `RuleEngineTest`/`ServerConfigGeneratorTest`/`DnsmasqConfigServiceTest`
      extensions, `DnsOverridesPage` (+ frontend tests). Live E2E on production
      (2026-08-13): GLOBAL `git.internal→10.8.0.1` resolved in-VPN (AAAA NODATA),
      NXDOMAIN on public DNS; USER (alice)/GROUP (DevOps) overrides → per-client
      DROP only for out-of-scope IPs, none after adding alice to DevOps; config
      regenerated and dnsmasq reloaded per change.
- [x] **4.8 Release** — `v0.1.0-alpha.8` tag + `RELEASE_NOTES.md` entry.

## M5 — Multi-node & Ops — TARGET `v0.1.0-alpha.7`

- [ ] **5.1 `openvpn_nodes` registry** — Flyway V13 entity (name, mgmtHost,
      mgmtPortBase, adminIp, enabled); `NodeRegistryService` + admin CRUD API.
      (V12 is taken by `dns_records` in 4.7.)
- [ ] **5.2 Node-aware status/kill/monitoring routing** — per-node `MgmtClient`
      (reconnect/backoff), node-scoped `/api/admin/monitor`, `/api/admin/status`,
      connection-logs reconciliation and `kill <cn>`; node column/picker on the
      Status page.
- [ ] **5.3 Backend `agent` Spring profile** — lightweight agent managing its own
      openvpn container, registering/heartbeating to the central backend via
      `/internal/node/*` (network-restricted, `OPNL_INTERNAL_TOKEN`).
      `application-agent.yml`.
- [ ] **5.4 PostgreSQL profile validation** — end-to-end validation of
      `docker-compose.postgres.yml` + `application-postgres.yml`; all Flyway
      migrations V1–V13 apply on Postgres; fix any SQLite-only SQL.
- [ ] **5.5 Release** — `v0.1.0-alpha.7` tag + `RELEASE_NOTES.md` entry.

## M6 — Release Hardening — TARGET `v0.1.0-beta.1`

- [ ] **6.1 Demo/seed mode** — `make seed-demo` / `OPNL_DEMO_MODE`: sample users,
      groups, access rules, certs and connection history.
- [ ] **6.2 CI docker build job** — docker build job in `.github/workflows/ci.yml`
      (`docker compose build` for backend/frontend/openvpn images).
- [ ] **6.3 Fresh-install E2E test pass** — `install.sh` clean install → wizard →
      login → full UI → VPN connect flow; findings into `docs/test-findings.md`.
- [ ] **6.4 README/docs finalization** — feature matrix, architecture diagram,
      final `docs/api.md`.
- [ ] **6.5 Cross-cutting sweep** — env-var-driven config audit, secret scan,
      English-only verification, Spotless/ESLint clean, mutation test coverage.
- [ ] **6.6 Release** — `v0.1.0-beta.1` tag + `RELEASE_NOTES.md` entry.

## Cross-cutting

- [ ] Environment-variable-driven configuration everywhere (no hardcoded secrets)
- [ ] English-only UI strings, comments, commits
- [ ] Spotless + ESLint clean; unit tests per module

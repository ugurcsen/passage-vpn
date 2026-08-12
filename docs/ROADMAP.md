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

## M1 — Phase 3 completion (network & access) — CURRENT

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
- [ ] **1.5 CertService restore/rotate + expiry-warning scheduler** —
      `CertService.restore(id)` (re-verify a revoked cert in `index.txt`),
      `CertService.rotate(userId)` (issue new cert + revoke old), and a
      `@Scheduled` daily expiry scan exposing
      `GET /api/admin/certs?expiring=true`. UI: Restore/Rotate actions and an
      expiry badge on the Certs page. Tests: restore/rotate + scheduler cases.
- [ ] **1.6 DCO detection display** — surface DCO (Data Channel Offload)
      availability consistently on the Status and Daemons pages (per-daemon
      badge; backend `ServerStatusDto`/`DaemonDto` already carry fields).

## M2 — Monitoring & ops completion

- [ ] **2.1 Audit log (UI included)** — new `audit_logs` entity + Flyway V9;
      audit service records admin actions (user/group/rule/daemon/settings
      mutations, MFA changes, login events); `GET /api/admin/audit-logs`
      (paginated, filterable); new Audit Log page with DataGrid. Tests: backend
      audit recording + frontend page.
- [ ] **2.2 Syslog integration** — ship audit + auth events to syslog
      (configurable target; RFC3164 line format) while still storing in DB.
- [ ] **2.3 `connection_logs` retention** — periodic purge of closed rows older
      than a configurable retention window (server setting), plus index tuning.

## M3 — API, ops & deployment

- [ ] **3.1 OpenAPI/Swagger + `docs/api.md` + API tokens** — Springdoc UI,
      generated `docs/api.md`, API token entity + header auth for automation.
- [ ] **3.2 Brand settings + configuration report + backup/restore (DB dump)** —
      brand settings (name/logo/colors) surfaced through the theme API;
      configuration report (settings snapshot + PKI inventory + versions);
      **backup with full DB dump + tar of config/PKI, and a restore flow**
      (upload/extract + SQLite dump import). UI on the Settings page.
- [ ] **3.3 Multi-node registry + node-aware routing** — `openvpn_nodes`
      registry, node-aware status/kill/monitor routing, backend `agent` profile.
- [ ] **3.4 `install.sh` full installer + demo/seed mode** — preflight, env,
      build, up, wizard trigger; optional seed/demo data mode.
- [ ] **3.5 PostgreSQL profile validation + Makefile polish** — validate
      `application-postgres.yml` + `docker-compose.postgres.yml` end-to-end.
- [ ] **3.6 Post-auth Python script hook** — optional per-account post-auth
      hook (configurable script path, timeout, stderr capture).
- [ ] **3.7 E2E pass + docs finalization + CI** — full E2E test pass against a
      fresh install, `README.md`/`docs/` finalization, CI workflow
      (`.github/workflows/ci.yml` build/test/docker).

## Cross-cutting

- [ ] Environment-variable-driven configuration everywhere (no hardcoded secrets)
- [ ] English-only UI strings, comments, commits
- [ ] Spotless + ESLint clean; unit tests per module

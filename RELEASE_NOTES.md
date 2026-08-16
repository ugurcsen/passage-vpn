# Release Notes

This file tracks the current state of the project. Version numbers follow the
[SemVer](https://semver.org) convention; until the first production release the
project is in a pre-1.0 state.

Legend: `[x]` released, `[~]` partial.

---

## v0.1.0-beta.10 — 2026-08-16

Tenth **beta** milestone (SemVer pre-release): fixes the CI `docker-build` job,
which was red because Compose interpolates the whole compose file even when only
building a subset of services — the `opnl-agent` service's strict
`${OPNL_JWT_SECRET:?}` / `${OPNL_OPENVPN_MGMT_PASSWORD:?}` variables failed
file-wide whenever no `.env` was present. The agent now uses soft defaults
(matching the `backend` service), so `docker compose build backend frontend
openvpn` works in CI and locally without a `.env`; runtime fail-fast is
unchanged because the backend's own `SecurityBootstrapCheck` (blank management
password) and `JwtService` (32-byte secret minimum) still refuse to start with a
missing secret. Includes everything from `v0.1.0-beta.9` plus the changes below.

### CI/CD fix
- `opnl-agent` compose env — `OPNL_JWT_SECRET` and
  `OPNL_OPENVPN_MGMT_PASSWORD` relaxed from `:?` (required) to `:-` soft
  defaults, keeping `docker compose build` interpolable without a `.env`
  (`docker-compose.yml`). Verified: `docker compose build backend frontend
  openvpn` succeeds with no env file; `docker compose --profile node config`
  still resolves.

### Verified
- Backend suite green (unchanged); frontend **31 files / 169 tests** green.
  Tag: `v0.1.0-beta.10`.

---

## v0.1.0-beta.9 — 2026-08-16

Ninth **beta** milestone (SemVer pre-release): fixes the frontend test suite,
which was red after the jsdom copyToClipboard fallback tests referenced the
`HTMLDocument` global and the `document.execCommand` method — neither of which
exists in the jsdom test environment (`ReferenceError: HTMLDocument is not
defined`). The tests now stub `execCommand` directly on `document`. Includes
everything from `v0.1.0-beta.8` plus the changes below.

### Test fix
- `copyToClipboard` fallback tests — define `document.execCommand` via
  `Object.defineProperty` instead of poking `HTMLDocument.prototype`; the
  property is stubbed per test so the async-Clipboard and rejection paths both
  exercise the legacy fallback (`45b6710`).

### Verified
- Backend suite green (unchanged); frontend **31 files / 169 tests** green
  (was 2 failed / 167 passed). Tag: `v0.1.0-beta.9`.

---

## v0.1.0-beta.8 — 2026-08-16

Eighth **beta** milestone (SemVer pre-release): PKI lifecycle hardening round —
certificate restore/re-revoke artifact bugs, a login-lockout persistence bug,
and the orphan-certificate deletion leak — plus the UX simplification that
**deleting a user always revokes and purges their certificate** (the cleanup
checkbox is gone). All changes were live-verified on the staging host.
Includes everything from `v0.1.0-beta.7` plus the changes below.

### PKI lifecycle fixes
- **F15 — restore artifacts / re-revoke** — `CertService.restore` now restores
  the on-disk artifacts (`issued/`, `private/`) *before* touching `index.txt`
  (`9005aa2`), and clears the Easy-RSA 3.2.x revoked archive so a second
  revoke after a restore no longer fails with `pki_command` "conflicting file
  exists" (`eec3949`). Live: issue → revoke → restore → rotate (200, new
  serial) → revoke again (200).
- **M9 — orphan certificate rows** — `UserAdminService.deleteUser` now always
  removes the user's certificate bookkeeping rows (`CertService.deleteRowsForUser`,
  bulk `deleteByUserId`), and `CertService.ensureUserCert` purges a stale
  same-`common_name` row with an **immediate bulk delete** instead of a deferred
  entity delete that Hibernate flushed *after* the new INSERT — fixing the
  `UNIQUE constraint (common_name)` 500 when a username was re-created after a
  delete (`4df2588`). Live: no-body DELETE → re-create → issue 200.
- **M10 — user delete always purges the PKI** — deleting a user now always
  revokes + purges their certificate; the `deleteCertificates` option and its
  UI checkbox are removed, leaving `DeleteOptions` with `deleteAccessRules` +
  `clearCcd` only (`d984b7f`). Without the checkbox the deleted user's
  certificate stayed **VALID** in the PKI index with its on-disk artifacts
  intact, so a stolen profile could still authenticate against a cert-only
  (AUTO_LOGIN) daemon. "Deactivate but keep the certificate" remains available
  via the separate ban/disable action. Live: no-body DELETE → index entry
  `V → R` (REVOKED), `issued/`+`private/` artifacts removed, `CERT_PURGE`
  audit written, re-create → new serial (200); the delete dialog now shows only
  "Delete access rules" and "Clear static IP".

### Auth fix
- **F16 — persisted login lockout** — the failed-login lockout counters were
  updated inside the same `@Transactional` login method that then threw, so the
  exception rolled the updates back and the lockout never persisted. The
  accounting now commits outside the rollback (`66d97c0`). Live: 5× wrong
  password → `users.failed_attempts` reset + `users.locked_until` set, exactly
  5 `LOGIN_FAILED` audit rows committed, and a correct-password 6th attempt →
  `account_locked`.

### Verified
- Backend suite green: **908 tests**; frontend **31 files / 169 tests**, lint
  clean. All three fixes live-verified on staging (see `docs/test-findings.md`
  §M8, §M9, §M10). Tag: `v0.1.0-beta.8`.

---

## v0.1.0-beta.7 — 2026-08-15

Seventh **beta** milestone (SemVer pre-release): scoped `GROUP_ADMIN` RBAC
(replacing the flat `RESELLER` role) and a **source-free production deployment
workflow** — a deploy-only release tarball plus prebuilt GHCR images, so servers
install without the source tree or a build toolchain. Includes everything from
`v0.1.0-beta.6` plus the changes below.

### Scoped GROUP_ADMIN RBAC

Replaces the flat `RESELLER` role with a scoped `GROUP_ADMIN` role bound to one
or more **root groups**. A group admin can manage only what an administrator
delegates to it: the assigned root groups (and every subgroup beneath them),
their member accounts, per-user/group settings and static-IP pools, and the
connection logs of users in scope. The frontend (navigation, Users/Groups pages,
new Connection Logs page, API-token page) and docs mirror the same boundary.

### RBAC model
- [x] `RESELLER` removed — migration `V20__group_admin.sql` (SQLite + Postgres)
      creates `group_admin_assignments (user_id, group_id)` and demotes existing
      `RESELLER` accounts to `USER`.
- [x] `GroupScope` (`com.opnl.vpn.group`) resolves the managed scope: root group +
      descendants (BFS), scoped user ids/usernames for list filtering.
- [x] Group admins can create/edit/set members for groups in scope and static-IP
      pools/settings; they cannot create new **root** groups, reparent/delete the
      root groups they manage, or touch accounts outside their scope.
- [x] Group admins manage `USER` accounts only (never `ADMIN`/`GROUP_ADMIN`),
      including per-user CCD settings, static IPs, and password resets.
- [x] Connection logs: `GET /api/admin/connection-logs` returns only in-scope
      sessions for group admins.
- [x] API tokens now always carry the `ADMIN` role (any other role rejected with
      `invalid_role`); existing reseller tokens are inert.
- [x] Only an `ADMIN` may grant `GROUP_ADMIN` and must pick ≥1 managed group
      (`admin_groups_required`); assignment changes persist via the join table.
- [x] Demo seed: `dave` is now a group admin over `devops`.

### Frontend
- [x] Role plumbing `RESELLER` → `GROUP_ADMIN` (`useAuth`, routes, nav); group
      admins land on Users, get Groups + new **Connection Logs** page.
- [x] Users page: role selector (admin-only) with managed-groups multi-select,
      "Manages" column, action hiding on admin rows.
- [x] Groups page: no root-group creation/deletion for group admins; the Parent
      column shows the parent group's name instead of the raw id; Delete is hidden
      on managed root groups (kept on subgroups).
- [x] ApiTokens page: ADMIN role only.

### Tests
- [x] Backend 903 green (UserAdminService/GroupAdminService scope suites,
      ConnectionLogService scoping, DemoSeed assignments, token-role filter,
      coverage gates enforced in CI).
- [x] Frontend 169 green (role-based routing/nav, Users/Groups role gating,
      parent-name rendering, group-admin Delete visibility, `useLiveStatus`
      WS/REST coverage).

### Deployment & release workflow
- [x] `install.sh --mode=release` pulls prebuilt images from GHCR instead of
      building from source — the server never needs the source tree or a build
      toolchain.
- [x] Deploy-only release tarball `opnl-vpn-<tag>.tar.gz` (compose files,
      `.env.example`, installer, deploy doc): built locally with `make release`
      and attached to the GitHub Release by the CI `package` job on version tags.
- [x] `docker-compose.yml` services carry env-driven `image:` tags
      (`OPNL_IMAGE_REGISTRY` / `OPNL_IMAGE_NAMESPACE` / `OPNL_IMAGE_TAG`) so the
      same compose file serves dev builds and prod pulls.
- [x] Swagger/OpenAPI off by default in production (`OPNL_API_DOCS_ENABLED=false`);
      expanded `.dockerignore` keeps the build context lean.
- [x] Docs: `docs/installation.md` (dev vs. release install), README badges +
      production install commands, `docs/configuration.md` new variables.

---

## v0.1.0-beta.6 — 2026-08-15

Sixth **beta** milestone (SemVer pre-release): multi-node certificate and config
distribution (approved P5 plan A/B/C) — shorter certificate lifetimes with an
automated rotation policy, multi-remote profiles that list every matching
daemon across nodes, and a full agent config+CRL pull pipeline that keeps remote
VPN nodes in sync. The whole flow was verified end-to-end live against a real
remote node (agent registration, bundle pull, central→node management
connection, CRL revocation propagation). Includes everything from
`v0.1.0-beta.5` plus the changes below.

### Phase P5 — Multi-node cert/config distribution
- [x] **A — Shorter certificate lifetime + rotation policy** —
      `EASYRSA_CERT_EXPIRE` 3650→730 (`OPNL_PKI_CERT_EXPIRE`), CRL generated to
      cover ≥ cert lifetime; new server settings `cert_auto_rotate`
      (`off`/`notify`/`auto`, default `notify`) and `cert_rotate_days_before`
      (default 14); a daily scheduler auto-rotates only account-bound VALID
      certificates (audit `CERT_ROTATE_AUTO`); the Account page warns the user
      when their certificate expires soon.
- [x] **B — Multi-remote profiles** — `OpenVpnNode.adminHost` (Flyway V19);
      generated `.ovpn` files list every matching enabled daemon across nodes as
      multiple `remote` lines with `remote-random`; new `profile_multi_remote`
      server setting (default on).
- [x] **C — Agent config + CRL pull (Pritunl-link pattern)** —
      `GET /internal/node/config` returns a config bundle (daemon conf +
      mgmt-pass rendered for remote paths, ca/crl/ta.key/server cert/key, CCD,
      scripts, dnsmasq) versioned by content hash; `AgentConfigSyncService`
      writes it atomically and the node openvpn entrypoint watcher reloads the
      affected daemons; the compose `node` profile adds `opnl-node-openvpn` +
      `opnl-agent`.

### Live E2E verification (staging host)
- Agent mTLS registration + heartbeats (node reports online), bundle pull
  (17 files), central→node management connection (`opnl-node-openvpn:7508`),
  remote openvpn daemon running, and CRL revocation propagation verified:
  revoking a throwaway user's cert updated the central CRL, the agent pulled the
  new bundle, and the node's `crl.pem` matched the central hash with the revoked
  serial present.
- Real bugs found and fixed during the run: missing agent env vars
  (`OPNL_JWT_SECRET`/`OPNL_OPENVPN_MGMT_PASSWORD`), `@Nullable`
  `DnsmasqConfigService` for the agent profile, `@Autowired`
  `AgentRegistrationService`, nginx caching the backend container IP
  (`resolver 127.0.0.11` + variable `proxy_pass`), the 9443 mTLS connector not
  enabling `SSLEnabled`, and a keytool-based truststore bootstrap (the openssl
  PKCS12 export produced a zero-entry store Java rejected).
- **Idempotent cert re-issue on username re-use** — `CertService.ensureUserCert`
  now purges stale certificates left by a deleted account (revoke → CRL
  rejects, purge on-disk artifacts, drop the bookkeeping row) before issuing a
  fresh certificate, fixing a `UNIQUE constraint (common_name)` 500 when a
  username was re-created after a delete without certificate cleanup.

### Verified
- Backend suite green: 589 tests, 0 failures; `./gradlew test` +
  `spotlessApply` BUILD SUCCESSFUL. Frontend: 28 files / 147 tests green, lint
  0 errors. Release tag: `v0.1.0-beta.6`.

---

## v0.1.0-beta.4 — 2026-08-15

Fourth **beta** milestone (SemVer pre-release): client-side and bootstrap-path
security hardening of the VPN auth and portal flows — profile-availability
gating, a nonce-bound auth-pending MFA flow, a separate bootstrap token for
seed endpoints, per-IP VPN-auth failure lockout and removal of the published
backend port. Includes everything from `v0.1.0-beta.3` plus the changes below.

### Security hardening
- **Portal profile availability (A1)** — the portal list/download/QR endpoints
  only expose profile types that are both policy-enabled and map to a matching
  daemon (`DaemonService.findMatchingForProfile`; `allowed`/`available` flags on
  `GET /api/portal/profiles`). Unavailable types are hidden in the portal with an
  explanatory note.
- **`portal_profile_types` server setting (A2)** — new `portal_profile_types`
  setting controls which profile types the portal may offer. Default is
  `USER_LOCKED, SERVER_LOCKED`; `AUTO_LOGIN` and `GENERIC` are now disabled
  unless an administrator explicitly enables them (behavior change).
- **Portal certificate self-service (A3)** — users can view and rotate their VPN
  certificate on the account page (`GET/POST /api/portal/cert`, certificate
  re-issued on demand when no backing PKI file exists).
- **Nonce-bound auth-pending MFA (B2)** — phase-1 `/internal/auth/verify`
  returns a single-use 120s `pendingId` when an MFA challenge starts; the
  client-crresponse phase (`verify-otp`) consumes it and fails closed on missing
  or replayed nonces. `verify-user-pass.sh` stashes the nonce between phases
  (0600, `/tmp/opnl-pending-<user>`).
- **Bootstrap token for seed endpoints (B3)** — optional `OPNL_BOOTSTRAP_TOKEN`;
  when set, `/internal/seed-admin` and `/internal/seed-demo` require the
  `X-Bootstrap-Token` header (`SeedGuard`). Unlike the internal token it is never
  exposed to the OpenVPN container.
- **No published backend port (B4)** — the backend HTTP listener is no longer
  mapped to the host; all user traffic enters via the frontend nginx on :80 and
  `/internal/**` is reachable only inside the docker network.
- **No account-state leak (C1)** — `account_locked`/`account_disabled` are
  normalized to `invalid_credentials` in `/internal/auth/verify` responses.
- **Per-IP VPN-auth lockout (C2)** — `IpFailureTracker` throttles repeated
  failed VPN connect attempts per source IP (sliding window reusing the
  `opnl.auth.lockout-*` settings; `ip_blocked` fail-fast before credential
  checks). Login rate limiting extended to `verify-otp` and the seed endpoints.
- **Makefile** — `seed-admin` now sends the internal token (was missing) and
  both seed targets forward `X-Bootstrap-Token` when set.
- **Reseller privilege escalation (C3)** — `assertCanManageUser` limits non-admin
  actors to managing USER-role accounts: `resetPassword`, `updateUser`,
  `deleteUser`, `setBanned`, static-IP and per-user settings now take the acting
  user and reject ADMIN/RESELLER targets (`forbidden`). Found and verified live:
  a reseller could previously take over the admin account via password reset.
  The users UI hides those actions for resellers.

### Verified
- Backend suite green (`./gradlew test` + `spotlessCheck` BUILD SUCCESSFUL);
  frontend 145 tests green, ESLint clean. Release tag: `v0.1.0-beta.4`.

---

## v0.1.0-beta.5 — 2026-08-15

Fifth **beta** milestone (SemVer pre-release): a role-independence audit of every
HTTP endpoint after the beta.4 reseller escalation fix, plus the one hole it
found — the setup wizard's network configuration was readable anonymously forever.
Includes everything from `v0.1.0-beta.4` plus the changes below.

### Security hardening
- **Endpoint authorization audit (C4)** — every controller was reviewed for
  role-independent access. Confirmed clean: all `/api/admin/**` endpoints are
  role-gated (`@PreAuthorize`), all `/api/portal/**` endpoints act only on the
  authenticated principal, `/internal/**` stays behind the internal token + mTLS,
  `/ws/**` requires an admin JWT at the handshake, and profile share tokens are
  128-bit single-use. 
- **Admin-only server config (C4)** — `GET /api/setup/server-config` (previously
  in the public setup allow-list forever) is now `@PreAuthorize("hasRole('ADMIN')")`.
  It returned the full network configuration — VPN subnet/mask, port/protocol, DNS
  servers, IPv6 subnet and admin host — to anyone, even after setup completed. The
  wizard's `state`/`wizard` endpoints stay public because the state machine already
  guards step transitions (admin creation only from `NOT_STARTED`).
  `SetupControllerSecurityTest` locks in the behavior (anonymous 403, USER 403,
  ADMIN 200, `state` public).

### Verified
- Backend suite green (`./gradlew test` + `spotlessCheck` BUILD SUCCESSFUL);
  live deployment verified in Chrome (admin + reseller users UI intact, wizard
  state flow unchanged). Release tag: `v0.1.0-beta.5`.

---

## v0.1.0-beta.3 — 2026-08-15

Third **beta** milestone (SemVer pre-release): on-demand portal share downloads
and QR codes plus a complete environment-variable reference. Includes
everything from `v0.1.0-beta.2` plus the changes below. Tag: `v0.1.0-beta.3`.

### Changes
- **Portal share downloads served by the backend** — new `ShareController`
  (`/share/**`) validates the share token and streams the `.ovpn` file, so QR
  codes no longer redirect to login; nginx and the Vite dev server proxy
  `/share/` to the backend; the SPA share page is gone.
- **On-demand share QR codes** — portal profile cards generate share QRs with a
  5-minute expiry and a live countdown; expired codes are disabled.
- **Environment-variable documentation** — new `docs/configuration.md`
  documents every `OPNL_*` variable (default, allowed values, where it is
  consumed); `.env.example` is in full sync; unused `OPNL_OPENVPN_PROTO`,
  `OPNL_ADMIN_USERNAME` and `OPNL_MGMT_BASE_PORT` removed; README and AGENTS.md
  link the new reference.

### Verified
- Backend suite green (`./gradlew test` + `spotlessCheck` BUILD SUCCESSFUL);
  frontend 142 tests green.

---

## v0.1.0-beta.2 — 2026-08-15

Second **beta** milestone (SemVer pre-release): node & internal security
hardening of the OpenVPN Access Server–style panel — a mandatory management
password on every daemon, an mTLS-only internal transport for node agents, a
mandatory internal token with fail-fast startup checks and source-IP pinning
for agent endpoints. Includes everything from `v0.1.0-beta.1` plus the changes
below. Tag: `v0.1.0-beta.2`.

### Phase M7 — Node & internal security hardening
- [x] **7.1 Mandatory management-interface password** — per-daemon password
      files (`daemon-<idx>.mgmt-pass`, 0600) referenced from `daemon.conf`;
      `MgmtHandshake` authenticates every management connection; `MgmtClientManager`
      fails closed when a local/remote password is missing; `OPNL_OPENVPN_MGMT_PASSWORD`
      enforced at startup (`SecurityBootstrapCheck`) and config-write time.
- [x] **7.2 mTLS transport for node agents** — internal CA + keystore generated
      on boot (`InternalTlsBootstrap`), mTLS-only Tomcat connector
      (`opnl.internal.mtls-port`, 9443); per-node client certs issued via
      `POST /api/admin/nodes/{id}/agent-cert` (CN `agent-<nodeName>`); the agent
      authenticates with `opnl.agent.tls-ca|cert|key`; requests outside the mTLS
      port or with a mismatched cert are rejected.
- [x] **7.3 Mandatory internal token + fail-fast startup** — `OPNL_INTERNAL_TOKEN`
      required, blank/`change-me-internal-token` rejected by `InternalTokenFilter`
      and at startup; `NodeSecurityCheck` warns on nodes without a management
      password.
- [x] **7.4 Source-IP pinning for agent endpoints** — `adminIp`-based check on
      `register`/`heartbeat` (403 `source_ip_mismatch`); `last_seen_ip` tracking
      (Flyway V18).
- [x] **7.5 Release** — this entry.

### Fixed during production verification
- **`MgmtHandshake` protocol bugs** — the real OpenVPN daemon sends the
  `ENTER PASSWORD:` prompt without a trailing newline and expects the bare
  password line back (no `PASSWORD:` prefix; `man_check_password` compares the
  raw first line). The old `readLine()`-based greeting timed out and the
  `PASSWORD:`-prefixed reply was rejected. Handshake rewritten as a char-based
  reader; tests now drive a realistic fake daemon (newline-less prompt) and
  assert the exact bytes the client sends.
- **`mgmtReachable` probe starvation** — the status endpoint opened a fresh TCP
  connection per call, but OpenVPN's management interface services a single
  session at a time, so probes queued dead sockets and starved the persistent
  monitor. Reachability now derives from the last fresh status polled over the
  persistent session (90s freshness window) instead of probing.

### Verified
- Backend suite green (includes new `MgmtHandshakeTest` 5, `ConfigWriterTest` 3,
  `SecurityBootstrapCheckTest` 4, `InternalControllerTest` 27,
  `NodeRegistryServiceTest` 23); `./gradlew test` + `spotlessCheck` BUILD
  SUCCESSFUL. Frontend: 142 tests green.
- Production verification on `65.21.108.250`: backend + openvpn images rebuilt
  and running healthy; V18 migration applied; internal TLS CA generated at
  `/var/lib/opnl/internal-tls`; Tomcat on 8080 (http) + 9443 (mTLS-only, client
  cert required — handshake without a certificate fails).
- Management security confirmed: all three daemons run with
  `management ... daemon-<idx>.mgmt-pass` (files `0600`); backend authenticates
  with the generated `OPNL_OPENVPN_MGMT_PASSWORD` and all daemons report
  `mgmtReachable`; wrong passwords are rejected by the daemon; monitoring polls
  `status 3` over the persistent sessions every 30s.
- Internal token enforced: `/internal/**` returns 401 without or with a wrong
  `X-Internal-Token`; the real token passes. Preflight (Maintenance page)
  reports PASS for database, settings, all daemons and PKI.
- Chrome end-to-end pass on the production UI: login, dashboard (6 users, 2
  groups, 4 active certs, 3/3 daemons), VPN Daemons, Live Status (all daemons
  `Present | Reachable`, recent sessions with traffic), Settings (network mode
  nat), Certificates (5 rows, revoke state), Users, Audit Log and VPN Nodes
  pages all render; only a pre-existing MUI Data Grid height warning remains.

---

## v0.1.0-beta.1 — 2026-08-14

First **beta** milestone (SemVer pre-release): release hardening of the OpenVPN
Access Server–style panel — demo/seed mode, CI container build, docs
finalization, cross-cutting sweep and a full fresh-install E2E pass on a clean
production deployment. Includes everything from `v0.1.0-alpha.16` plus the
changes below. Tag: `v0.1.0-beta.1`.

### Phase M6 — Release hardening
- [x] **6.1 Demo/seed mode** — `make seed-demo` / `OPNL_DEMO_MODE`: sample users
      (admin/ALICE/Bob/Carol/Dave), groups, GLOBAL/GROUP/USER access rules,
      DNS overrides, certificate rows and connection history; dashboard "Load
      demo data" button; `/api/admin/demo/seed` (admin) +
      `/internal/seed-demo` (script-facing, network-restricted).
- [x] **6.2 CI docker build job** — docker-build job in
      `.github/workflows/ci.yml` (`docker compose build` backend/frontend/openvpn
      images) so broken Dockerfiles fail CI.
- [x] **6.3 Fresh-install E2E test pass** — clean `install.sh --reset` install
      on the production host: setup wizard (admin → VPN server → PKI
      provisioning), login, full dashboard, demo-data loading, real Easy-RSA
      cert issuance, `.ovpn` profile generation and an end-to-end VPN connect
      flow (TLS + user/pass auth, virtual IP allocation, DNS override + upstream
      resolution through the tunnel, default-deny firewall enforcement, session
      cleanup on disconnect). Findings recorded in `docs/test-findings.md`
      (§2.13 demo certs without backing PKI files, §2.14 do not run a client on
      the server host).
- [x] **6.4 README/docs finalization** — `docs/architecture.md` (design,
      openvpn integration, PKI/firewall model) and `docs/access-rules.md`
      (rule-engine semantics, iptables mapping, worked examples) added; README
      rewritten (deployment modes, feature matrix, release flow); `docs/api.md`
      regenerated to cover the new demo/seed endpoints.
- [x] **6.5 Cross-cutting sweep** — env-var-driven config audit, secret scan,
      English-only verification, Spotless/ESLint clean, full test suites green.
- [x] **6.6 Release** — this entry.

### Verified
- Backend suite green: 492 tests, 0 failures; `./gradlew test` +
  `spotlessCheck` BUILD SUCCESSFUL. Frontend: ESLint 0 errors, 142 tests green,
  production build passes.
- Fresh-install E2E on `65.21.108.250` (`install.sh --reset`, clean volumes,
  `.env` preserved): wizard completed, setup state COMPLETE, CA + server
  certificate + CRL provisioned; login and dashboard render; demo data loads
  through the UI (5 users / 2 groups / 2 certs) with all 4 access rules and 2
  DNS overrides confirmed via API.
- VPN connect flow verified end-to-end from a separate client: fresh user →
  real cert → `.ovpn` (`remote <adminHost> 1194`) → UDP connect with auth →
  virtual IP `10.8.0.2`, AES-256-GCM data channel, session visible in the panel
  Live Status with byte counters, `git.internal`/`docs.internal` DNS overrides
  and upstream resolution through the tunnel, default-deny firewall block
  confirmed, and full cleanup (session + iptables chain) after an abrupt client
  kill via the server inactivity timeout.
- Production deploy: backend/frontend/openvpn images rebuilt and running
  healthy on the prod host after the fresh install.

---

## v0.1.0-alpha.16 — 2026-08-14

Sixteenth tagged milestone (SemVer pre-release): the full M5 multi-node
milestone — node registry, node-aware status/kill/monitoring routing, an agent
Spring profile for remote VPN nodes and PostgreSQL profile validation. A node
is now a first-class entity (`openvpn_nodes`, Flyway V15) managed through the
admin `VPN Nodes` UI and `/api/admin/nodes` CRUD; status/kill/monitoring and
connection-log reconciliation route per node through a reconnect/backoff
`MgmtClient`; a new `agent` Spring profile (with the opt-in `node` compose
profile) runs a lightweight agent that manages its own openvpn container and
registers/heartbeats to the central backend via network-restricted
`/internal/node/*` (protected by `OPNL_INTERNAL_TOKEN`). PostgreSQL support is
validated end to end: the full Flyway set now lives under
`db/migration-postgresql` (V1–V17 minus the never-created V13, with portable
`TRUE`/`FALSE` boolean defaults) and a parity test guards the two sets.
Includes everything from `v0.1.0-alpha.15` plus the changes below. Tag:
`v0.1.0-alpha.16`.

### Phase M5 — Multi-node & ops (node registry, agent, PostgreSQL validation)
- [x] **5.1 `openvpn_nodes` registry** — Flyway V15 entity (name, mgmtHost,
      mgmtPortBase, adminIp, enabled); `NodeRegistryService` + admin CRUD API
      + `/api/admin/nodes`; frontend VPN Nodes page.
- [x] **5.2 Node-aware status/kill/monitoring routing** — per-node `MgmtClient`
      (reconnect/backoff), node-scoped `/api/admin/monitor`, `/api/admin/status`,
      connection-logs reconciliation and `kill <cn>`; node column/picker on the
      Status page.
- [x] **5.3 Backend `agent` Spring profile** — lightweight agent managing its own
      openvpn container, registering/heartbeating to the central backend via
      `/internal/node/*` (network-restricted, `OPNL_INTERNAL_TOKEN`).
      `application-agent.yml`.
- [x] **5.4 PostgreSQL profile validation** — end-to-end validation of the
      Postgres profile: all Flyway migrations V1–V17 (minus the never-created
      V13) apply on `postgres:16-alpine` with portable `TRUE`/`FALSE` boolean
      defaults via the dedicated `db/migration-postgresql` set; a
      `MigrationParityTest` guards version parity and SQLite-only idioms.
- [x] **5.5 Release** — this entry.

### Verified
- Backend suite green on the production checkout (480 tests, 0 failures).
- Postgres validation: fresh `postgres:16-alpine` container, backend boot with
  `OPNL_PROFILE=postgres` applied all 16 migrations ("now at version v17"),
  Hibernate `ddl-auto: validate` passed, `/actuator/health` UP, and a
  `/internal/seed-admin` write (with `X-Internal-Token`) created a row whose
  booleans verified the portable defaults.
- Production deploy: backend image rebuilt and restarted on `65.21.108.250`,
  migrations V15–V17 applied to the live SQLite DB (data intact),
  `/api/admin/nodes` returns 200 with the node registry and daemon status
  still streams per node; `docs/api.md` regenerated live (now documents the
  `/api/admin/nodes` CRUD + `/internal/node/*` and `nodeId` fields).

---

## v0.1.0-alpha.15 — 2026-08-14

Fifteenth tagged milestone (SemVer pre-release): M4 completion — generated API
reference, Makefile polish and a full CRUD audit. `docs/api.md` is now committed
(regenerated live from the production backend via `make api-docs`, 110 endpoint
sections plus schemas), the Makefile gains a backend-reachability guard for
`api-docs` and split `test-backend`/`test-frontend`/`lint-backend`/
`lint-frontend` targets, and the admin/portal namespaces were audited against
the live OpenAPI document. Includes everything from `v0.1.0-alpha.14.2` plus
the changes below. Tag: `v0.1.0-alpha.15`.

### Phase M4 — M4 leftovers (docs/api.md, Makefile polish, CRUD audit)
- [x] `docs/api.md` generation — `make api-docs` renders the API reference
      from the live `/v3/api-docs` document; the document is committed to the
      repo and was regenerated live in this milestone (110 endpoint sections +
      schemas)
- [x] Makefile polish — the `api-docs` target fails fast with a friendly
      message when the backend is unreachable (previously a raw `urllib`
      failure); new split quality targets `test-backend`/`test-frontend`/
      `lint-backend`/`lint-frontend` on top of the combined `test`/`lint`;
      `help` refreshed
- [x] Full CRUD API completion audit — admin + portal namespaces cross-checked
      against the live OpenAPI document: users full CRUD; groups/rules/daemons/
      dns-overrides list+create+update+delete; certs lifecycle ops
      (issue/revoke/restore/rotate/reconcile); settings/api-tokens/
      profile-tokens/backups complete; connections list + kill; portal
      self-service complete (account MFA + password, profiles download/QR,
      share). The named M4.4 gaps are done (daemon management, connection
      kill); node lifecycle rolls into the M5 multi-node milestone.

### Verified
- Backend suite green on the production checkout (`./gradlew test
  spotlessCheck` → BUILD SUCCESSFUL in 2m 1s); frontend suite green
  (28 files / 137 tests, lint 0 errors).
- `make api-docs` regenerates `docs/api.md` against the live backend and the
  new guard prints the friendly error when the backend is unreachable.

---

## v0.1.0-alpha.14.2 — 2026-08-14

Patch release recording the live verification of mandatory MFA on
`65.21.108.250` (deploys `v0.1.0-alpha.14` + `.14.1`). Full API end-to-end
pass: admin and an unenrolled user are both forced to enroll after login, the
QR/secret provisioning and TOTP confirm succeed and issue session tokens,
`GET /api/auth/me` reports `mfaEnabled=true`/`mfaRequired=true`, VPN
`verifyVpnLogin` denies with `mfa_required` without a code and allows with a
valid code (wrong code → `invalid_code`), and admin + self-service
`mfa/disable` both return 403 under the policy. Browser UI pass: Settings lists
"Require MFA", the Users MFA column renders, and an unenrolled user is
redirected to `/login/enroll`, scans the QR, enters a valid code and signs in.
Test users and the `require_mfa` setting were removed afterwards, restoring the
original production state. Tag: `v0.1.0-alpha.14.2`.

---

## v0.1.0-alpha.14.1 — 2026-08-14

Patch release fixing forced-MFA enrollment on the live deployment: the new
`/api/auth/mfa/enroll` and `/api/auth/mfa/enroll/confirm` endpoints were missing
from the security permit list, so a first-time login returned `mustEnrollMfa`
but the enrollment calls were rejected with `401 Authentication required`. They
are now public like the other pre-auth login endpoints, guarded by a new
`MfaEnrollEndpointSecurityTest` that asserts a blank `preAuthToken` yields a
validation 400 (not 401). Tag: `v0.1.0-alpha.14.1`.

---

## v0.1.0-alpha.14 — 2026-08-14

Fourteenth tagged milestone (SemVer pre-release): mandatory MFA. A server-wide
`require_mfa` policy setting forces every user to enroll TOTP and to present a
code at both web login and VPN connect; users who have not enrolled are
redirected to a QR-enrollment flow right after login, and MFA can no longer be
disabled while the policy is in force. Includes everything from
`v0.1.0-alpha.13` plus the changes below. Tag: `v0.1.0-alpha.14`.

### Phase P4.1 — Mandatory MFA (server-wide policy)
- [x] `require_mfa` server setting (existing settings hierarchy): when
      effective for a user, the account must be MFA-enrolled to sign in or
      connect
- [x] Forced enrollment at login: `POST /api/auth/login` returns
      `mustEnrollMfa: true` plus a short-lived single-use enroll challenge
      token when MFA is required but the user has no secret;
      `POST /api/auth/mfa/enroll` provisions the TOTP secret (QR + secret,
      idempotent) and `POST /api/auth/mfa/enroll/confirm` verifies the code,
      marks the account enrolled and issues the session tokens
- [x] Frontend — new `MfaEnrollPage` at `/login/enroll` (QR, secret, verify
      code, "Enable and sign in"); `LoginPage` auto-redirects when the server
      returns `mustEnrollMfa`
- [x] Enforcement: web `mfa()` gate relaxed to secret-based so a
      provisioned-but-unconfirmed secret still verifies; VPN
      `verifyVpnLogin`/`verifyVpnOtp` deny with `mfa_required`/`invalid_code`
      when the policy applies and no code was given
- [x] Disable-MFA blocked under policy (admin `UserAdminService` + portal
      `PortalAccountService`); `UserDto.mfaRequired` (policy-only, so
      self-enabled MFA stays user-manageable) drives the frontend: AccountPage
      shows a warning banner and disables "Disable MFA", UsersPage shows a
      "Required" chip in the MFA column

### Verified
- Backend suite green (spotless clean), `make test` green; frontend suite
  green (28 files / 137 tests, lint 0 errors, `tsc -b` clean).
- Live on `65.21.108.250`: forced-enrollment flow verified end-to-end — set
  `require_mfa`, log in as an unenrolled user, confirm QR enrollment page
  appears, verify a valid code signs in.

---

## v0.1.0-alpha.13 — 2026-08-14

Thirteenth tagged milestone (SemVer pre-release): backup import with restore
hardening, and the Maintenance page (Danger Zone). Uploaded archives are
validated before they are kept, a restored database snapshot must pass
integrity, foreign-key and schema-version checks (with a rollback copy kept),
and a preflight gate runs config smoke tests so restart/reload are refused
while the installation is unhealthy. Includes everything from
`v0.1.0-alpha.12` plus the changes below. Tag: `v0.1.0-alpha.13`.

### Phase M4.5 follow-up — Backup import & restore hardening
- [x] `POST /api/admin/backups/import` (ADMIN, multipart) — uploads streamed to
      a temp file, validated (must contain `manifest.json` or `opnl.db`, plus a
      normalized-path zip-slip scan), then stored under the backup filename
      pattern or renamed `imported-<stamp>.zip` on collision; audited as
      `BACKUP_IMPORT`; Spring + nginx request limits raised to 256 MB
- [x] `BackupService.validateSnapshot` — a backup DB is swapped in only after
      `PRAGMA integrity_check`, `PRAGMA foreign_key_check` and a Flyway
      schema-version match against the live database, so a restore can never
      crash-loop the backend
- [x] `BackupService.createRollbackCopy` — pre-restore DB preserved as
      `rollback/opnl.db.pre-restore-<stamp>` (newest 5 kept) and surfaced in
      the restore response + audit record
- [x] Restore re-marks extracted `.sh`/`.py` helper scripts executable — ZIP
      entries and plain copies drop the exec bit, which broke daemon reloads
      after a restore (`Options error: ... Permission denied (errno=13)`)
- [x] `createBackup` staging cleanup moved to a `finally` so no
      `backup-<random>` directories leak after successful backups
- [x] Frontend — Backups page gains an "Import" button; a successful import
      opens a "Restore imported backup" confirmation that can restore right away

### Phase M4.5 follow-up — Maintenance page (Danger Zone)
- [x] `SettingValidator` — server-setting validation extracted from
      `SettingsAdminController` so the settings API and the preflight check
      share the same rules
- [x] `ConfigSmokeTester`/`OpenvpnConfigSmokeTester` — parses each daemon
      config with `openvpn --config <file> --dev null --route-noexec
      --ifconfig-noexec` (3 s timeout); `Options error` = FAIL, runtime issues
      = WARN so a healthy config is never blocked
- [x] `MaintenanceService.preflight` — database integrity + foreign keys,
      server settings, per-daemon config smoke test, PKI file/expiry sanity;
      `passed` only when nothing FAILs
- [x] `POST /api/admin/system/preflight|restart-backend|reload-daemons`
      (ADMIN) — restart/reload return 409 while any check FAILs
- [x] `MgmtClient.signal`/`MgmtClientManager.signal` — management `signal
      SIGHUP` with reconnect, then re-verification after the restart
- [x] `ApplicationRestarter`/`DefaultApplicationRestarter` — graceful context
      close shortly after the HTTP response so Docker's restart policy brings
      the backend back; audited `SYSTEM_RESTART`/`SYSTEM_RELOAD`
- [x] Frontend — Maintenance page: preflight check list with PASS/WARN/FAIL
      chips plus a Danger Zone with "Restart backend" / "Reload OpenVPN
      daemons" confirm dialogs; action rows use `flex: 1` + `minWidth: 0` text
      and `flexShrink: 0` buttons so text and action never overlap
- [x] `docker-compose.yml` — backend mounts the shared `opnl-logs` volume so
      the daemon smoke test can open the config's `status __LOG_DIR__/status`
      file (preflight was FAIL without it)

### Verified
- Backend suite green (56 classes / 433 tests, spotless clean); frontend suite
  green (27 files / 133 tests, lint 0 errors, `tsc -b` clean).
- Live on `65.21.108.250`: an `e2e-import.zip` upload triggered the
  auto-restore prompt and restored end-to-end ("must be restarted" toast);
  preflight reports 4/4 PASS; reload-daemons returns
  `{"signaled":1,"total":1,"failed":[]}`; restart-backend responds first, then
  the container restarts (`RestartCount=1`) and the UI reconnects. The smoke
  test correctly caught a restored config whose helper scripts had lost their
  exec bit, fixed by `markScriptExecutable` on restore.

---

## v0.1.0-alpha.12 — 2026-08-14

Twelfth tagged milestone (SemVer pre-release): PKI reconciliation and
deletion-time resource cleanup. The Easy-RSA `index.txt` is the PKI truth and
the `certificates` table the bookkeeping, so a manual reconcile keeps them in
sync (rows created/updated, never deleted), and deleting a user can optionally
revoke+purge their certificates, remove their access rules and clear their
static IP — per user or in bulk. Includes everything from `v0.1.0-alpha.11`
plus the changes below. Tag: `v0.1.0-alpha.12`.

### Phase M4.7 follow-up — PKI reconciliation & deletion cleanup
- [x] `IndexParser`/`CertIndexEntry` — parse the `revokedAt` column from
      `index.txt` so revoked entries carry their revocation date into the DB
- [x] `CertService.reconcile()` + `POST /api/admin/certs/reconcile` (ADMIN) —
      one-click sync of the `certificates` table with the PKI index: missing
      rows are created (linked to the user by username), existing rows are
      updated (status/expiry/revokedAt, matched by serial then common name),
      server certificates and phantom file-less entries are skipped, and rows
      are never deleted; the outcome is reported as created/updated/skipped
      and audited (`CERT_RECONCILE`)
- [x] `CertService.purgeForUser()` — when a user is deleted with cleanup,
      their VALID certificates are revoked (best-effort) and all certificate
      rows are removed (`CERT_PURGE`)
- [x] `UserAdminService.DeleteOptions` — `DELETE /api/admin/users/{id}` and
      `POST /api/admin/users/bulk` accept an optional body
      `{deleteCertificates, deleteAccessRules, clearCcd}` (defaults all
      false); access-rule cleanup also refreshes the dnsmasq domain pins
      (`AccessRuleService.deleteForUser`, `RULE_DELETE_FOR_USER` audit)
- [x] Frontend — Certificates page gains a "Sync with PKI" button; the Users
      page delete flow (single and bulk) opens a dialog with the three cleanup
      checkboxes before confirming

### Verified
- Backend suite green (407 tests, spotless clean); frontend suite green
  (26 files / 128 tests, lint 0 errors, `tsc -b` clean).
- Live on `65.21.108.250`: reconcile from a cold PKI created 7 rows
  (1 skipped) and is idempotent on repeat ("0 created, 0 updated"); a
  reconciled cert with no issue date renders correctly in the UI (fix
  `173200b`, null-safe sort). Single-user delete with "revoke certs +
  delete access rules" removed the user and audited the options
  (`USER_DELETE` → `{"accessRules":true,"certificates":true,...}`); bulk
  delete of two users with "revoke certs + clear static IP" audited
  `USER_BULK` (`{"count":2,"action":"DELETE"}`) plus one `USER_DELETE` per
  user carrying the cleanup options.

---

## v0.1.0-alpha.11 — 2026-08-14

Eleventh tagged milestone (SemVer pre-release): DNS override ⇄ access-rule
conflict awareness — the admin UI now surfaces when a scoped DNS override
(GROUP/USER) is reachable by out-of-scope users because an ALLOW access rule
covers its address. The per-client firewall ordering is unchanged (explicit
ALLOW wins); this milestone only reports the conflict. Includes everything from
`v0.1.0-alpha.10.1` plus the changes below. Tag: `v0.1.0-alpha.11`.

### Phase M4.7 follow-up — DNS override ⇄ access-rule conflict warnings
- [x] `CidrUtil` — IPv4/IPv6 CIDR containment helper (`contains(cidr, ip)`) with
      family-mismatch/malformed-input protection, shared by the warning logic
- [x] `GroupHierarchy` — group-chain resolution extracted from `RuleEngine`
      (`groupChainFor`) so firewall rendering and conflict detection use the
      same audience logic
- [x] `DnsScopeConflictService` — for each scoped DNS override, finds ALLOW
      rules (CIDR/domain) that let out-of-scope users reach its address, and for
      each ALLOW rule the scoped overrides it exposes; GLOBAL overrides and
      DENY/disabled rules never warn
- [x] `DnsRecordDto.warnings` + `AccessRuleDto.warnings` populated by
      `DnsOverrideService`/`AccessRuleService` on list/create/update/enabled
- [x] Frontend — "Warnings" column in the DNS Overrides and Access Rules grids:
      warning icon + count with a tooltip listing the conflicting rule/override

### Verified
- Backend suite green (391 tests); frontend suite green (125 tests, lint 0
  errors); spotless clean.
- Deployed to production `65.21.108.250` and verified in Chrome: creating an
  ALLOW rule covering the address of a QA-scoped override raised the warning on
  both the Access Rules and DNS Overrides pages; deleting the rule cleared it.

---

## v0.1.0-alpha.10.1 — 2026-08-14

Patch release on top of `v0.1.0-alpha.10`: fixes IPv6 routing for connected
clients. The server already pushed `redirect-gateway ipv6`, but several clients
(notably OpenVPN Connect) ignore server pushes for the default IPv6 route, so a
connected client kept routing IPv6 off-tunnel despite holding a `fd00:1::`
address. Tag: `v0.1.0-alpha.10.1`.

### Phase M4.8 follow-up — Client-profile IPv6 routing
- [x] `OvpnGenerator` embeds `tun-ipv6` and `redirect-gateway ipv6` directly in
      the generated `.ovpn` when the daemon is dual-stack, so the client applies
      the IPv6 default route through the tunnel even when it ignores server
      pushes
- [x] Unit tests added for both dual-stack (directives present) and IPv4-only
      (directives absent) profile rendering

### Verified
- Backend suite green (370 tests); spotless clean.
- Deployed to production `65.21.108.250`: backend rebuilt and recreated; the
  downloaded `USER_LOCKED` profile now carries `tun-ipv6` + `redirect-gateway
  ipv6`.

---

## v0.1.0-alpha.10 — 2026-08-14

Tenth tagged milestone (SemVer pre-release): full IPv6 dual-stack support — the
VPN tunnels now carry IPv6 alongside IPv4: dual-stack daemon configs (`tun-ipv6`,
`server-ipv6`, IPv6 route push), static IPv6 for users/groups (CCD
`ifconfig-ipv6`), a complete ip6tables mirror of the per-client firewall, and
IPv6 (AAAA) answers for DNS overrides. Includes everything from `v0.1.0-alpha.9`
plus the changes below. Tag: `v0.1.0-alpha.10`.

### Phase M4.8 — IPv6 dual-stack
- [x] Flyway V14 — `users.static_ipv6`, `dns_records.ipv6`, and
      `daemons.ipv6_enabled`/`daemons.ipv6_subnet`; dual-stack is off by default
      with `fd00:1::/64` as the default IPv6 subnet
- [x] `ServerConfig` gains `ipv6Enabled`/`ipv6Subnet`; `ServerConfigGenerator`
      emits `tun-ipv6`, `server-ipv6 <subnet>`, `push "route-ipv6"` and
      `ifconfig-ipv6-remote` per daemon only when dual-stack is enabled; IPv6
      `udp6`/`tcp6` protocols supported
- [x] `RuleEngine` — `IptablesResult` extended with `apply6`/`remove6`;
      `iptablesFor` renders the ip6tables mirror (default forward ACCEPT, per
      rule `-d <v6>/128` matches, `scopeDenyIpv6For` for out-of-scope DNS
      overrides); `AccessRuleService.iptablesFor` threads `ipv6Enabled` +
      client v6 address through it
- [x] `InternalController` — `/internal/connect` + `/internal/disconnect`
      accept `virtualIp6` and return `iptablesApply6`/`iptablesRemove6`;
      `ConnectionRegistry` registers and indexes the dual-stack virtual address
- [x] `MgmtStatus` parses the IPv6 virtual address from the status file;
      monitor, `DashboardAdminService`, `ConnectionLogService` and
      `TrafficAggregator` carry the v6 address end to end
- [x] CCD — `CcdService` writes `ifconfig-ipv6` for static IPv6 (conflict
      detection), group `static_ipv6_pool` auto-allocation
- [x] DNS — `DnsRecordDto.ipv6` (AAAA); `DnsmasqConfigService` emits the AAAA
      record for dual-stack overrides; `apply-rules.sh` mirrors the `OPNL_DOMAINS`
      pins in an `OPNL_DOMAINS6` ip6tables chain when `OPNL_VPN_POOL6` is set
- [x] openvpn scripts — `client-connect.sh`/`client-disconnect.sh` pass
      `virtualIp6` (from `ifconfig_pool_remote_ip6`/`ifconfig_ipv6_remote`);
      `entrypoint.sh` derives the pool from `server-ipv6` for the NAT + dnsmasq
      IPv6 gateway
- [x] Frontend — Settings network form IPv6 toggle + subnet; VPN Daemons IPv6
      column + edit dialog; Users Static IPv6 column + CCD dialog (SET /
      ALLOCATE IPV6); Groups Static IPv6 pool dialog; DNS Overrides IPv6 column
      + AAAA field; setup wizard carries the dual-stack flag through

### Verified
- Backend suite green (370 tests, spotless clean); frontend suite green
  (26 files / 123 tests, lint 0 errors); `make test` green.
- Live E2E on production `65.21.108.250`: checkout synced and the stack rebuilt
  (backend + frontend + openvpn), all containers healthy; Flyway V14 applied
  (schema at v14). Chrome verification: Settings IPv6 toggle reveals the
  `fd00:1::/64` subnet; VPN Daemons edit dialog toggles dual-stack; Users grid
  shows Static IPv6 with CCD SET/ALLOCATE IPV6; Groups Static IPv6 pool dialog;
  DNS Overrides edit dialog carries the IPv6 (AAAA) field; daemons/users/groups/
  dns admin APIs return the new v6 fields; config generation verified (no IPv6
  directives when disabled, `virtualIp6` flowing through the internal connect
  contract via the container scripts).

---

## v0.1.0-alpha.8 — 2026-08-13

Eighth tagged milestone (SemVer pre-release): DNS overrides — admin-defined
internal hostnames served authoritatively by the shared dnsmasq, with
GLOBAL/GROUP/USER scope enforced per-client by the firewall. Includes
everything from `v0.1.0-alpha.7` plus the changes below.
Tag: `v0.1.0-alpha.8`.

### Phase M4.7 — DNS overrides
- [x] `DnsRecord` entity + `dns_records` table (Flyway V12, unique hostname,
      strict IPv4, `enabled`, created_at); scope is GLOBAL/GROUP/USER with the
      target group/user resolved for display (`scopeName`) and validated on the
      DTO (`scopeValid` — `@AssertTrue`, scope required exactly when not GLOBAL)
- [x] `DnsOverrideService` — CRUD with hostname lowercasing/normalization and
      scope-target existence checks; `resolveDomain` / `nonGlobalEnabled` feeds
      the firewall; every mutation is audited (`DNS_RECORD_CREATE/UPDATE/
      DELETE/ENABLE/DISABLE`, `CAT_DNS`) and re-renders the dnsmasq override
      config
- [x] `GET/POST /api/admin/dns-overrides` + `PUT/DELETE .../{id}` +
      `POST .../{id}/enabled` (JSON boolean body) — ADMIN-only, `@Valid` DTOs
- [x] `DnsmasqConfigService` — writes `address=/<host>/<ip>` +
      `server=/<host>/` into `dnsmasq.d/opnl-dns-overrides.conf` (enabled records
      only), alongside the domain pins; the entrypoint watcher SIGHUPs dnsmasq on
      every change; resolution is override-first so an override wins over public
      DNS for a pinned access-rule domain
- [x] `RuleEngine.scopeDenyIpsFor` — GLOBAL records are never denied; GROUP
      records deny every group not containing the user (`groupChainFor`);
      USER records deny all other users. The per-client chain emits one
      `-s <vip> -d <ip>/32 -j DROP` per out-of-scope address AFTER rule ACCEPTs
      (explicit ALLOW wins) and keeps terminal ACCEPT when only scope denials
      exist, DROP otherwise; `AccessRuleService.iptablesFor` threads the denied
      set through `/internal/connect` and `/internal/disconnect`
- [x] DNS Overrides admin page (`/dns`) — DataGrid (hostname, address, scope
      chip with target, enabled switch, actions), create/edit dialog with
      scope select + user/group picker, delete confirmation; `api.ts`
      `endpoints.dnsOverrides` + `DnsRecordDto`
- [x] Tests — `DnsOverrideServiceTest`, `DnsRecordAdminControllerTest`,
      `RuleEngineTest` (override-first resolution, scope-deny rendering,
      ACCEPT/DROP terminals), `DnsmasqConfigServiceTest` (overrides file,
      override-priority), `DnsOverridesPage` frontend tests

### Verified
- Backend suite green (369 tests, spotless clean); frontend suite green
  (26 files / 123 tests, lint 0 errors, `vite build` passes); `make test`
  green; `make lint` green.
- Live E2E on production `65.21.108.250`: checkout synced to the milestone and
  the stack rebuilt (backend + frontend images), all containers healthy;
  Flyway V12 applied; backend suite re-run green on the deployed checkout.
- DNS overrides verified end-to-end: a GLOBAL `git.internal→10.8.0.1` record
  answers `10.8.0.1` via the VPN dnsmasq with AAAA NODATA (authoritative
  `server=/`), while public DNS returns NXDOMAIN for `git.internal`. USER
  (`nas.internal`→10.9.0.1 for alice) and GROUP (`mon.internal`→10.10.0.1 for
  DevOps) records resolve for everyone but render per-client scope DENY lines:
  alice was denied only `10.10.0.1` (not her record nor GLOBAL), an
  out-of-scope user was denied `10.9.0.1` + `10.10.0.1` (not GLOBAL), and after
  adding alice to DevOps the deny set became empty (scope chain terminal ACCEPT
  when only scope denials exist). `opnl-dns-overrides.conf` regenerated and
  dnsmasq reloaded on every create/update/delete; all test records, simulated
  connections and the temporary group membership were purged afterwards.

---

## v0.1.0-alpha.9 — 2026-08-13

Ninth tagged milestone (SemVer pre-release): accessibility hardening of the
DNS Overrides admin page found during a live Chrome audit. Includes
everything from `v0.1.0-alpha.8` plus the changes below. Tag:
`v0.1.0-alpha.9`.

### Phase M4.7 follow-up — DNS Overrides a11y
- [x] Dialog form fields carry explicit `id`/`name` attributes (`dns-hostname`,
      `dns-ipv4`, `dns-scope`, `dns-scope-target`, `dns-enabled`), following the
      login-page pattern from `v0.1.0-alpha.5`
- [x] The DataGrid per-row enable toggle is labeled with `name="enabled"` and a
      per-row `aria-label` (`Toggle enabled for <hostname>`) so screen readers
      can distinguish each row's switch
- [x] The two scope `TextField select`s pass `id`/`name` through `inputProps` to
      their hidden native inputs (the visible button already carried the id)

### Verified
- Frontend suite green (26 files / 123 tests, lint 0 errors); `vite build`
  passes; `make test` green.
- Live in Chrome against production `65.21.108.250`: the DNS Overrides page
  was exercised end-to-end (create → enable toggle → edit → delete with
  confirmation) and the full lifecycle appeared in the audit trail
  (`DNS_RECORD_CREATE/DISABLE/ENABLE/UPDATE/DELETE`, CAT_DNS); a malformed
  IPv4 was rejected with `400 validation_failed`. The Chrome a11y audit dropped
  from 4 flagged form fields to 1 — the remaining one is the DataGrid's
  built-in "Rows per page" pagination select (framework internal, present on
  every DataGrid page, not patched per page). The stack was rebuilt and the
  frontend container recreated with the fix.

---

## v0.1.0-alpha.7 — 2026-08-13

Seventh tagged milestone (SemVer pre-release): domain-based access control via
dnsmasq. Includes everything from `v0.1.0-alpha.6` plus the changes below.
Tag: `v0.1.0-alpha.7`.

### Phase M4.2 — Domain-based control via dnsmasq
- [x] `AccessRule` domain target — new `dstDomain` column (Flyway V11) that is
      mutually exclusive with the CIDR and group destinations; hostname syntax
      validated on the DTO and only one of the three destination types may be set
- [x] `DomainResolver` — resolves a domain target to its IPv4 addresses at
      render time (2s timeout, IPv4 only, one resolver thread per domain group)
- [x] `RuleEngine` — a domain rule emits one `-d <ip>/32 ` match per resolved
      address, inheriting the rule's action/protocol/port; unresolvable domains
      are skipped defensively so the firewall never blocks a live network
- [x] `DnsmasqConfigService` — pins each domain to its resolved IPs in
      `opnl-domains.conf` inside the shared config volume, regenerated on every
      access-rule change and at backend startup; the openvpn container's dnsmasq
      SIGHUPs to re-read it
- [x] dnsmasq in the openvpn container — base `/etc/dnsmasq.conf` (upstream
      Cloudflare/Google resolvers, `conf-dir` for pinning) plus `start_dnsmasq()`
      in the entrypoint, listening on loopback and the tun server IP so VPN
      clients resolve through it; retried until the tun address exists
- [x] `apply-rules.sh` `OPNL_DOMAINS` chain — `RETURN` per pinned IP and a final
      `DROP`, so domain rules match exactly the addresses dnsmasq hands out; kept
      in sync on every rule application
- [x] Access Rules page — "Destination domain" picker with mutual exclusion
      against CIDR/group; the rules table shows `Domain: <name>`

### Verified
- Backend suite green (345 test cases, spotless clean); frontend suite green
  (25 files / 118 tests, lint 0 errors, `tsc -b` clean); `make test` green.
- Live E2E on production `65.21.108.250`: checkout synced to the milestone and
  the stack rebuilt (backend + frontend + openvpn images), all containers
  healthy; backend suite re-run green on the deployed checkout and frontend
  suite re-run green.
- Domain flow verified end-to-end: `POST /api/admin/rules` with
  `dstDomain=api.github.com` returns `destinationValid: true`; a request that
  sets both `dstCidr` and `dstDomain` is rejected with 400. The backend writes
  `address=/api.github.com/<ip>` + `server=/api.github.com/` into
  `dnsmasq.d/opnl-domains.conf`; the entrypoint watcher picks up the change,
  refreshes the `OPNL_DOMAINS` iptables chain (`RETURN` per pinned IP, final
  `DROP`) and restarts dnsmasq. dnsmasq answers an A query for `api.github.com`
  with exactly the pinned IP and returns NODATA for AAAA. daemon-0 pushes
  `dhcp-option DNS 10.8.0.1` (its dnsmasq address) ahead of the public servers,
  and dnsmasq listens on every daemon tun IP (10.8.0.1/10.9.0.1/10.10.0.1)
  plus loopback. Deleting the rules returns the conf to zero pins, the chain to
  `DROP`-only and `api.github.com` resolves via the upstream again; test rules
  purged afterwards.

### Live-verification fixes (found during E2E)
- [x] Rule changes only rewrote the pinning config; the entrypoint now watches
      `dnsmasq.d` too and refreshes the firewall + resolver without restarting
      the running VPN daemons
- [x] Clients were only pushed public DNS so pinning never reached them; the
      generator now prepends the per-daemon dnsmasq address (pool network + 1)
      and dnsmasq listens on every daemon tun IP
- [x] SIGHUP re-read config but kept the dnsmasq cache, so a rotated IP could
      split DNS from the firewall; pinning changes now restart dnsmasq (cache
      cleared) after refreshing the chain
- [x] Pinned domains answered AAAA from upstream (outside the IPv4-only rules);
      `server=/domain/` makes dnsmasq authoritative so AAAA returns NODATA

---

## v0.1.0-alpha.6 — 2026-08-13

Sixth tagged milestone (SemVer pre-release): post-auth Python script hook for
VPN connect-time automation. Includes everything from `v0.1.0-alpha.5` plus the
changes below. Tag: `v0.1.0-alpha.6`.

### Phase 2.4/3.6 — Post-auth hook
- [x] Server settings `post_auth_script` and `post_auth_timeout_seconds` —
      a bare filename is resolved inside the shared scripts directory, an
      absolute path is used as-is
- [x] `PostAuthHookService` — runs the configured script after a successful VPN
      login (env: `username`, `common_name`, `remote_ip`; timeout 1–120s,
      default 10), best-effort: a missing script, non-zero exit or timeout never
      drops the connection, and the outcome is recorded as a `VPN_POST_AUTH_HOOK`
      audit event (CAT_AUTH; script/username/exitCode/success/error detail)
- [x] Wired into `AuthService.verifyVpnLogin` + `AuthService.verifyVpnOtp`, so it
      fires exactly once per connect: auth-pending MFA runs it at the OTP phase,
      static-challenge MFA and password-only auth at phase 1; failed
      authentication never triggers it
- [x] `ScriptSync` now syncs `.py` scripts into the shared config volume;
      example `openvpn/scripts/post-auth-hook.py` appends one JSON line per
      login to `/var/log/opnl/post-auth.log`
- [x] Backend runtime image gains `python3`; Settings page adds typed editors for
      the two keys (script name + timeout)

### Verified
- Backend suite green (spotless clean); frontend suite green (25 files / 116
  tests, lint 0 errors, build passes); `make test` green.
- Live E2E on production `65.21.108.250`: checkout synced to the milestone
  commit (`5b9bd61`) and the stack rebuilt (backend + frontend images, openvpn
  healthy); backend suite re-run green on the deployed checkout
  (`./gradlew test spotlessCheck` → BUILD SUCCESSFUL) and frontend suite re-run
  green (25 files / 116 tests, lint 0 errors); hook flow verified end-to-end —
  `post_auth_script=post-auth-hook.py` with timeout 5 set via the admin API, a
  throwaway user authenticates through `/internal/auth/verify`
  (`{"allowed":true}`), the hook writes a JSON line to
  `/var/log/opnl/post-auth.log` (`event: vpn_login` with username/common_name/
  remote_ip) and a `VPN_POST_AUTH_HOOK` audit event is recorded
  (`exitCode: 0, success: true`); test user and hook settings purged afterwards.

---

## v0.1.0-alpha.5 — 2026-08-13

Fifth tagged milestone (SemVer pre-release): admin branding, configuration
report and backup/restore, plus API error-mapping hardening found during live
verification. Includes everything from `v0.1.0-alpha.4` plus the changes below.
Tag: `v0.1.0-alpha.5`.

### Phase 4 — Branding, configuration report & backup/restore
- [x] Brand settings — `brand_name`, `brand_primary_color`, `brand_footer` and
      `brand_logo_url` (`SettingKeys`), read/written via `BrandService` with a
      public `GET /api/public/brand` endpoint; frontend `useBrand` hook applies
      the name/logo/colors to the theme, login page and sidebar
- [x] Configuration report — `ConfigReportService` + `GET /api/admin/config-report`
      (ADMIN-only) producing a server-wide summary; Config Report page UI
- [x] Backup/restore — `BackupService` + `/api/admin/backups` (ADMIN-only):
      create, download as ZIP and restore with full DB + config volume
      replacement; `BACKUP_CREATE` / `BACKUP_RESTORE` audit events; restore
      purges stale SQLite WAL sidecars so the restored database opens cleanly
      (regression-covered in `BackupServiceTest`)
- [x] Backups page UI — create, download and restore with confirmation toasts
      (restore requires a backend restart)

### Error-mapping hardening
- [x] `MethodArgumentTypeMismatchException` (e.g. a `user-locked` value bound to
      a `ProfileType` path variable) now maps to `400 invalid_parameter` instead
      of a `500 internal_error`
- [x] Method-security denials (`AuthorizationDeniedException` /
      `AccessDeniedException`) now map to `403 forbidden` instead of `500`, so
      USER-role clients get a proper 403 on ADMIN-only endpoints

### Verified
- Backend suite green (spotless clean); frontend suite green (23 files / 107
  tests, lint 0 errors); `make test` green.
- Live E2E (production `65.21.108.250`): branding round-trip — name/colors
  applied to the login page and theme, reset to defaults and re-verified; config
  report renders the full server summary; backup create → download → restore →
  rollback verified, with stale WAL sidecars purged on restore; audit trail
  records `SETTING_SET`, `BACKUP_CREATE` and `BACKUP_RESTORE`. Portal profile
  downloads verified end-to-end (admin route, portal QR and share-token routes
  all return valid `.ovpn`); `/api/portal/profiles/user-locked/download` →
  `400 invalid_parameter` (was 500); `/api/admin/dashboard` and
  `/api/admin/users` as a USER → `403 forbidden` (was 500).

---

## v0.1.0-alpha.2 — 2026-08-12

Second tagged milestone (SemVer pre-release). Includes everything from
`v0.1.0-alpha.1` plus the OpenVPN Connect MFA fix below. Tag: `v0.1.0-alpha.2`.

### Phase 0 — Project scaffolding
- [x] Repo layout, `TODO.md`, `AGENTS.md`, `.gitignore`, `.env.example`
- [x] Gradle wrapper + build scripts (Java 25, Spring Boot 3.5, Kotlin DSL)
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
- OpenVPN Connect (mobile/3.x) MFA fix: `static-challenge` is not supported by
  OpenVPN Connect, so MFA-on-connect now uses the OpenVPN auth-pending flow —
  `verify-user-pass.sh` runs dual-mode (`user-pass-verify` + `client-crresponse`):
  phase 1 verifies the password (inline `password\nOTP` from static-challenge
  CLI clients still accepted) and, when the account requires MFA without a code,
  writes a `crtext` auth-pending file and exits 2; OpenVPN Connect then prompts
  for the TOTP and invokes phase 2, which decodes the response and validates it
  via the new `POST /internal/auth/verify-otp` (`AuthService.verifyVpnOtp`);
  auth-user-pass daemon configs now emit `client-crresponse` +
  `auth-gen-token 43200` after `auth-user-pass-verify`. Covered by
  `AuthServiceTest` verify-otp cases, `InternalControllerTest` and
  `ServerConfigGeneratorTest`. Verified live on production `65.21.108.250`:
  daemon configs regenerated via the admin API (daemon-0/3 carry the new
  directives, daemon-2 auto-login untouched), openvpn container healthy, both
  script phases exercised in the container (exit 2 + pending file;
  `auth_control_file` 1/0), MFA connect flow confirmed working on the user's
  OpenVPN Connect device.

### Not yet released
- Phase 3 — group subnet allocation, per-user full/split tunnel, inter-group
  connectivity rules, NAT-vs-routing mode, dnsmasq domain control, static IP/CCD
  editor UI
- Phase 4 — branding, backup, multi-node, installer polish, PostgreSQL
  profile validation

---

## v0.1.0-alpha.4 — 2026-08-13

Fourth tagged milestone (SemVer pre-release): interactive Swagger/OpenAPI documentation
with bearer-token testing support. Includes everything from `v0.1.0-alpha.3`
plus the changes below. Tag: `v0.1.0-alpha.4`.

### Phase 4 — API documentation
- [x] `OpenApiConfig` — global `bearerAuth` HTTP/Bearer-JWT security scheme +
      global security requirement, so Swagger UI shows an "Authorize" button for
      authenticated endpoint testing (token obtained from `/api/auth/login` or
      `/api/auth/mfa`)
- [x] Swagger UI `persist-authorization: true` — pasted token survives reloads
- [x] `@Tag` groupings and `@Operation` summaries across admin/portal/auth
      controllers; the Authentication login/MFA/refresh flow is documented end to end
- [x] `OpenApiConfigTest` — verifies the generated `/v3/api-docs` spec exposes
      `bearerAuth` and the global security requirement

### Verified
- Backend suite green (279 tests, spotless clean); frontend suite green (19 files
  / 95 tests, lint 0 errors); `make test` green.
- Live E2E (production `65.21.108.250`): checkout synced to the milestone commit
  and stack rebuilt — `/swagger-ui.html` reachable (302 → `/swagger-ui/index.html`,
  200), `/v3/api-docs` exposes the `bearerAuth` HTTP/Bearer-JWT scheme plus the
  global security requirement (title from brand, `OpenVPN Panel`), and
  `/v3/api-docs/swagger-config` reports `persistAuthorization: true` with
  `tryItOutEnabled: true` so the Authorize button survives reloads; Flyway schema
  validated at V9; both suites re-run green on the production checkout (279
  backend / 95 frontend).

---

## v0.1.0-alpha.3 — 2026-08-13

Third tagged milestone (SemVer pre-release): admin & auth audit trail with real
client-IP capture, syslog shipping and connection-log retention. Includes
everything from `v0.1.0-alpha.2` plus the changes below. Tag: `v0.1.0-alpha.3`
(back-tagged on `502a094` during the `v0.1.0-alpha.4` release).

### Phase 4 — Logging & audit
- [x] `audit_logs` entity + `AuditLogService` — every mutating admin/auth flow
      records actor, action, category, target, JSON detail and client IP
      (Flyway V8)
- [x] `GET /api/admin/audit-logs` — paginated, newest-first trail with `action`,
      `actor` and `from`/`to` (ISO instant or `yyyy-MM-dd`) filters, ADMIN-only
- [x] Audit wiring across services: user/group/access-rule/cert/daemon admin ops,
      server-settings changes, portal MFA + password change, login/logout
- [x] Frontend Audit Log page (`/audit-logs`, admin-only nav) — DataGrid with
      server-side pagination and filter bar; covered by component tests
- [x] Syslog shipping — `SyslogService` emits RFC3164 UDP messages for audit/auth
      events, configured via server settings (`syslog_enabled`, `syslog_host`,
      `syslog_port`, `syslog_facility`)
- [x] Retention — `audit_logs_retention_days` and `connection_logs_retention_days`
      server settings; the daily purge now targets only closed connection rows
      (V9 index on `disconnected_at`)
- [x] Client-IP capture — backend honors `X-Forwarded-For` from trusted reverse
      proxies only (Tomcat RemoteIpValve; `OPNL_TRUSTED_PROXIES`, default
      `172.16.0.0/12` covering Docker bridge subnets), so audit/auth entries
      record the real client IP instead of the proxy container IP
- [x] `GROUP_CREATE` audit coverage gap closed (`GroupAdminService.createGroup`),
      verified by the new `GroupAdminServiceTest`

### Verified
- Backend suite green (277 tests, spotless clean); frontend suite green (19 files
  / 95 tests, lint 0 errors).
- Live E2E (production `65.21.108.250`): setup wizard → admin login → user, group
  (create + membership) and certificate issue flows produce `LOGIN_SUCCESS`,
  `USER_CREATE`, `GROUP_CREATE`, `GROUP_MEMBERS_SET` and `CERT_ISSUE` entries in
  the Audit Log page; Action filter (e.g. `GROUP`) returns matching rows; fresh
  entries show the real client public IP while historical entries keep the proxy
  IP.

---

## Previous releases

- `v0.1.0-alpha.1` — first tagged milestone (2026-08-12): project scaffolding,
  core OpenVPN + Easy-RSA integration, users/groups/auth with MFA, access control
  & connection profiles, operations dashboard, real-time monitoring.

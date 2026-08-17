# Test Plan — PassageVPN

Living test strategy for the management panel (backend Java 25 + Spring Boot, frontend
React + TypeScript + MUI, OpenVPN Community 2.6). Companion to `TODO.md` and
`RELEASE_NOTES.md`. Update this file whenever the suite or the feature set changes.

Confirmed defects and their remediation live in `docs/test-findings.md`.

Legend: `[x]` covered, `[~]` partially covered, `[ ]` missing / to add.

---

## 1. Goals

1. Keep `make test` green before every commit (Definition of Done).
2. Cover every state-mutating backend service method with a unit test.
3. Cover every frontend page/flow with a component test where behavior matters.
4. Verify the OpenVPN integration contract (scripts ↔ `/internal/**`) at the HTTP
   contract level **and** live E2E level (real client handshake on a staging host).
5. Keep automated tests hermetic (no external services, temp SQLite, mocked subprocesses);
   run E2E scenarios against a live staging deployment.

## 2. Test pyramid

```
            E2E (live staging, scenario catalog)      ~54 scenarios
           Integration (MockMvc + temp-file SQLite)   some
          Unit: services / engines / DTOs             903 tests
        Component (frontend, RTL + Vitest)            169 tests
```

- **Backend unit**: JUnit 5 + Mockito + AssertJ. Repos mocked; `EasyRsaService` and
  `ProcessRunner` mocked so tests never shell out.
- **Backend integration**: MockMvc controller slices + `@SpringBootTest` smoke
  (SQLite + Flyway, temp-file DB).
- **Frontend**: Vitest + React Testing Library + `@testing-library/jest-dom`.
- **E2E**: live staging host (`65.21.108.250`), real OpenVPN client handshake,
  HTTP probes and browser-driven UI checks (see §6).

## 3. How to run

```bash
make test          # backend (gradle) + frontend (vitest)
make lint          # frontend eslint + backend spotless
cd backend  && ./gradlew test spotlessCheck
cd frontend && npm run lint && npm run test
```

Live E2E (§6) runs against the staging host:
`ssh root@65.21.108.250` — API at `http://127.0.0.1/api` (use `127.0.0.1`, not
`localhost`); `/internal/**` is only reachable from the `passage-openvpn` container
(`docker exec passage-openvpn curl http://backend:8080/internal/...`); UI at
`http://65.21.108.250`. API rate limit is 20 requests/60 s per IP (throttle probes).

## 4. Test inventory (current state — pulled live from staging `make test`, 2026-08-16)

### 4.1 Backend — 87 test classes, 903 tests, all green

Coverage (Gradle JaCoCo): **line 88.0 % · branch 73.3 % · method 88.3 % · class 96.9 %**.

| Package | Test classes (key) |
|---|---|
| `auth` / `security` | `AuthServiceTest`, `AuthControllerTest`, `PostAuthHookServiceTest`, `AuthProviderManagerTest`, `RateLimitFilterTest`, `ApiTokenAuthFilterTest`, `IpFailureTrackerTest`, `MfaEnrollEndpointSecurityTest`, `SecurityBootstrapCheckTest` |
| `api/admin` | `UserAdmin{Controller,Service}Test`, `GroupAdmin*`, `CertAdmin*`, `AccessRuleAdmin*`, `DnsRecordAdmin*`, `ProfileAdmin*`, `DaemonAdmin*`, `ApiTokenAdmin*`, `BackupAdmin*`, `ConfigReportAdmin*`, `StatusAdmin*`, `DashboardAdmin*`, `SystemInfoAdmin*`, `NodeAdmin*`, `DemoAdmin*`, `SettingsAdmin*`, `ConnectionAdmin*` |
| `api/portal` / `api` | `PortalAccountServiceTest`, `ShareControllerTest`, `PublicBrandControllerTest`, `SetupControllerSecurityTest` |
| `pki` | `CertServiceTest`, `EasyRsaServiceTest`, `IndexParserTest` |
| `network` / `ccd` | `ServerConfigGeneratorTest`, `DaemonServiceTest`, `ConfigWriterTest`, `CcdServiceTest`, `DnsmasqConfigServiceTest`, `ConnectionRegistryTest`, `NodeRegistryServiceTest`, `ScriptSyncTest` |
| `access` / `dns` | `RuleEngineTest`, `AccessRuleServiceTest`, `CidrUtilTest`, `DomainResolverTest`, `DnsOverrideServiceTest`, `DnsScopeConflictServiceTest` |
| `profile` / `token` | `ProfileServiceTest`, `OvpnGeneratorTest`, `ApiTokenServiceTest` |
| `monitor` | `MonitorServiceTest`, `MgmtClientTest`, `MgmtHandshakeTest`, `MgmtStatusTest`, `MgmtClientManagerTest`, `ConnectionLogServiceTest`, `TrafficAggregatorTest`, `WsAuthHandshakeInterceptorTest` |
| `internal` | `InternalControllerTest`, `InternalNodeControllerTest`, `ClientCertReaderTest`, `InternalTlsServiceTest`, `InternalTlsBootstrapTest`, `TomcatTlsConfigTest` |
| `node` | `AgentRegistrationServiceTest`, `AgentConfigSyncServiceTest`, `NodeConfigBundleServiceTest`, `AgentTlsTest` |
| other | `SetupServiceTest`, `SettingsServiceTest`, `SettingValidatorTest`, `AuditLogServiceTest`, `BackupServiceTest`, `BrandServiceTest`, `SyslogServiceTest`, `MaintenanceServiceTest`, `DemoSeedServiceTest`, `GroupScopeTest`, `MigrationParityTest`, `GlobalExceptionHandlerTest`, `OpenApiConfigTest`, `OpnlVpnApplicationTests` |

### 4.2 Frontend — 31 test files, 169 tests

Coverage (Vitest v8 `--coverage`): **line 95.13 % · function 67.06 % · branch 85.62 %**.

| Area | Files |
|---|---|
| Core | `lib/api.test.ts` (refresh-on-401, single-flight, error mapping, token purge), `hooks/useAuth.test.tsx`, `hooks/useLiveStatus.test.tsx`, `App.test.tsx`, `components/AppLayout.test.tsx`, `components/ProfileCard.test.tsx` |
| Pages | `LoginPage`, `MfaLoginPage`, `MfaEnrollPage`, `SetupWizardPage`, `DashboardPage`, `UsersPage`, `GroupsPage`, `CertsPage`, `AccessRulesPage`, `DnsOverridesPage`, `ProfilesPage`, `PortalPage`, `DaemonsPage`, `NodesPage`, `StatusPage`, `ConnectionLogsPage`, `SettingsPage`, `BrandingPage`, `ConfigReportPage`, `BackupsPage`, `MaintenancePage`, `AuditLogsPage`, `ApiTokensPage`, `AccountPage`, `PlaceholderPage` |

## 5. Feature → test matrix

| Feature | Backend unit/int | Frontend | E2E scenario(s) |
|---|---|---|---|
| Setup wizard | `SetupServiceTest`, `SetupControllerSecurityTest` | `SetupWizardPage` | E2E-01, E2E-02 |
| Auth (login, MFA, refresh, logout) | `AuthServiceTest`, `AuthControllerTest`, `RateLimitFilterTest` | `LoginPage`, `MfaLoginPage`, `useAuth` | E2E-03 … E2E-09 |
| API tokens | `ApiTokenServiceTest`, `ApiTokenAuthFilterTest`, `ApiTokenAdminControllerTest` | `ApiTokensPage` | E2E-10 … E2E-12 |
| RBAC (ADMIN / GROUP_ADMIN / USER) | `GroupScopeTest`, `UserAdminServiceTest` | `UsersPage`, `AppLayout` | E2E-13, E2E-14 |
| Users (CRUD, ban, reset, static IP) | `UserAdmin*Test`, `CcdServiceTest` | `UsersPage` | E2E-15 … E2E-20 |
| Groups | `GroupAdmin*Test`, `GroupScopeTest` | `GroupsPage` | E2E-21 … E2E-23 |
| PKI (issue, revoke, restore, rotate, reconcile) | `CertServiceTest`, `EasyRsaServiceTest`, `IndexParserTest` | `CertsPage` | E2E-24 … E2E-30 |
| Connection profiles + share links | `ProfileServiceTest`, `OvpnGeneratorTest`, `ShareControllerTest` | `ProfilesPage`, `PortalPage`, `ProfileCard` | E2E-31 … E2E-36 |
| Access rules / iptables | `RuleEngineTest`, `AccessRuleServiceTest`, `CidrUtilTest`, `DomainResolverTest` | `AccessRulesPage` | E2E-37 … E2E-39, E2E-53 |
| DNS overrides | `DnsOverrideServiceTest`, `DnsScopeConflictServiceTest` | `DnsOverridesPage` | E2E-40 … E2E-42 |
| Monitoring (status, dashboard, traffic, ws) | `MonitorServiceTest`, `Mgmt*Test`, `ConnectionLogServiceTest`, `TrafficAggregatorTest` | `DashboardPage`, `StatusPage`, `ConnectionLogsPage`, `useLiveStatus` | E2E-43 … E2E-46 |
| Ops (audit, backups, config report, daemons, maintenance, system) | `BackupServiceTest`, `ConfigReportServiceTest`, `StatusAdminServiceTest`, `DashboardAdminServiceTest`, `MaintenanceServiceTest`, `AuditLogServiceTest`, `DaemonServiceTest` | `BackupsPage`, `ConfigReportPage`, `DaemonsPage`, `MaintenancePage`, `AuditLogsPage`, `SettingsPage`, `BrandingPage` | E2E-47 … E2E-50 |
| Live VPN data-plane | `InternalControllerTest`, `ScriptSyncTest`, `ConnectionRegistryTest` | `StatusPage` | E2E-51 … E2E-53 |
| Node agent (mTLS) | `InternalNodeControllerTest`, `AgentRegistrationServiceTest`, `AgentConfigSyncServiceTest`, `NodeConfigBundleServiceTest`, `AgentTlsTest` | `NodesPage` | E2E-54 (needs agent deployment) |
| Backup/restore | `BackupServiceTest`, `BackupAdminControllerTest` | `BackupsPage` | E2E-49 |

## 6. E2E scenario catalog

Executed against the live staging stack (OpenVPN 2.6.20, backend 0.1.0-SNAPSHOT).
Status: `PASS` / `FAIL` (see findings) / `SKIP` (needs external setup) / `N/A` (release-gated).

Scenario fields: **ID · Purpose · Preconditions · Steps · Expected**.

### 6.1 Setup & wizard (fresh install)

**E2E-01 · Fresh install wizard** — `PASS` (verified on clean install, 2026-08-14, see
`test-findings.md` §M6)
- Pre: empty `PASSAGE_DATA_DIR`, wizard admin credentials in `.env`.
- Steps: reach `/setup` → create admin → configure server (admin host, port) → provision PKI → finish.
- Expected: setup state `COMPLETE`; CA, `server.crt`/`.key`, `ta.key`, CRL, `index.txt`
  present; login no longer redirects to `/setup`; re-running steps → 409.

**E2E-02 · PKI provisioning artifacts** — `PASS`
- Steps: inspect PKI dir and `GET /api/setup/state`.
- Expected: `pkiInitialized=true`, CA `subject` correct, daemon-0 config written.

### 6.2 Authentication & security

**E2E-03 · Password login + wrong password** — `PASS`
- Steps: `POST /api/auth/login` correct → token pair; wrong password → 401.
- Expected: 200 with `accessToken`/`refreshToken`; 401 `invalid_credentials`.

**E2E-04 · MFA full cycle (enroll → enable → login → redeem → disable)** — `PASS`
- Steps: enroll (`/api/auth/mfa/enroll`) → confirm → enable → logout → login (OTP required)
  → `/api/auth/mfa` redeem → disable.
- Expected: every step 200; `enforceMfa=true` gates login until OTP; disable returns to
  password-only.

**E2E-05 · Refresh token rotation** — `PASS`
- Steps: login → use refresh → new pair issued → replay old refresh → 401.
- Expected: rotation on every refresh; old token rejected after use.

**E2E-06 · Logout revokes session** — `PASS`
- Steps: `POST /api/auth/logout` with Authorization header → reuse access token.
- Expected: logout 200; reuse → 401. (Note: `/api/auth/logout` is *not* public — the
  Authorization header is required; without it 401.)

**E2E-07 · Rate limiting** — `PASS`
- Steps: burst >20 login requests per IP in 60 s.
- Expected: 429 with `Retry-After`; bucket refills after window.

**E2E-08 · Web-login lockout** — `FAIL` → **finding F16**
- Steps: 5+ wrong passwords for the same user via `/api/auth/login`.
- Expected: user locked (`locked_until`), further attempts rejected with
  `invalid_credentials`/locked, `LOGIN_FAILED` audit entries recorded.
- Actual: attempts never accumulate (`failed_attempts` stays 0, `LOGIN_FAILED` audit count
  0) — `@Transactional` rollback on the failed login. VPN path (F-16a) works.

**E2E-09 · VPN password/OTP verify (`/internal/auth/verify`)** — `PASS` (unit-covered)
- Steps: `verifyVpnLogin` with password-only, OTP-required, banned, locked users.
- Expected: correct outcomes incl. ban rejection and lockout persistence (this path is
  non-transactional and persists `failed_attempts`/`locked_until` correctly).

### 6.3 API tokens

**E2E-10 · Create + use API token** — `PASS`
- Steps: `POST /api/admin/api-tokens` (`label`, optional scope) → call an admin endpoint
  with `Authorization: Bearer <token>`.
- Expected: 200; admin endpoint callable with the token.

**E2E-11 · List + delete API token** — `PASS`
- Steps: list → delete → reuse deleted token.
- Expected: list shows the token (label/prefix/expiry); reuse → 401.

**E2E-12 · Token validation (no token / malformed)** — `PASS`
- Steps: call admin endpoint without token; with garbage token.
- Expected: 401 `unauthorized` in both cases.

### 6.4 RBAC

**E2E-13 · GROUP_ADMIN scoping** — `PASS`
- Steps: login as a GROUP_ADMIN (e.g. `qa_lead`, Marketing) → list users/groups, update a
  member of own group, update a member of another group, update another group.
- Expected: own group users visible and mutable; other-group users/groups → 403;
  `PATCH/PUT /api/admin/users` scoped to managed group.

**E2E-14 · Banned user access** — `PASS`
- Steps: ban a user → login as that user → refresh.
- Expected: login 403 `account_banned`; refresh 401.

### 6.5 Users & groups

**E2E-15 · Create user** — `PASS`
- Steps: `POST /api/admin/users` (username, fullName, email, password, role).
- Expected: 200, user appears in list; duplicate username → 409; blank username → 400.

**E2E-16 · Update user** — `PASS`
- Steps: change full name/email/role; reassign group.
- Expected: 200; changes reflected; role `ADMIN` grant respects last-admin guard.

**E2E-17 · Reset password** — `PASS`
- Steps: `POST /api/admin/users/{id}/reset-password` → login with new password.
- Expected: old password fails; new password works; hash re-encoded.

**E2E-18 · Ban / unban** — `PASS`
- Steps: ban → login fails (`account_banned`) → unban → login works.
- Expected: lifecycle works; last-admin guard on self-ban/delete → 400.

**E2E-19 · Static IP set/clear** — `PASS`
- Steps: `PUT /api/admin/users/{id}/static-ip` → connect → `DELETE .../static-ip`.
- Expected: invalid IP/out-of-range → 400; in-range valid IP is used via CCD (see E2E-52).

**E2E-20 · User delete** — `PASS`
- Steps: delete a disposable user → list.
- Expected: 200; gone from list; self-delete guard; last-admin guard.

**E2E-21 · Group CRUD** — `PASS`
- Steps: create group → list → update name → add members → delete empty group.
- Expected: lifecycle works; deleting a group with members → 409 (or members moved),
  matching implementation.

**E2E-22 · Group members endpoint** — `PASS`
- Steps: `PUT /api/admin/groups/{id}/members` with member IDs.
- Expected: members reflected in `GET /api/admin/groups` and user's group list.

**E2E-23 · Reserved/built-in groups** — `PASS`
- Steps: attempt to delete/create a reserved group name.
- Expected: guarded (409/400) per implementation.

### 6.6 PKI & certificates

**E2E-24 · Issue certificate** — `PASS`
- Steps: `POST /api/admin/certs` for a user without a cert.
- Expected: 200, status VALID, serial + issue/expiry dates from Easy-RSA index.

**E2E-25 · Re-issue idempotency** — `PASS`
- Steps: issue again for the same user.
- Expected: returns the existing VALID cert (no new serial).

**E2E-26 · Revoke certificate** — `PASS`
- Steps: `POST /api/admin/certs/{id}/revoke` → double revoke.
- Expected: status REVOKED, CRL regenerated, `revoked/certs_by_serial/` populated;
  double revoke → `already_revoked` 409.

**E2E-27 · Restore certificate** — `FAIL` (partially) → **finding F15**
- Steps: issue → revoke → restore.
- Expected: status back to VALID with artifacts usable for rotate/revoke.
- Actual: status flips to VALID, but on-disk artifacts are not restored; a subsequent
  `rotate` or `revoke` → 500 `pki_command` ("Unable to revoke as no certificate was found").

**E2E-28 · Rotate certificate** — `FAIL` (when preceded by restore) → **finding F15**
- Steps: rotate a normal cert → new serial issued, old one revoked.
- Expected: 200 with fresh cert; after a restore (E2E-27) rotate → 500.

**E2E-29 · Sync with PKI (reconcile)** — `PASS`
- Steps: `POST /api/admin/certs/sync` after an out-of-band index change.
- Expected: bookkeeping rows match `index.txt` (adds/marks revoked).

**E2E-30 · Revoked cert download blocked** — `PASS`
- Steps: download a profile for a revoked cert.
- Expected: blocked (404/409), never a `.ovpn`.

### 6.7 Connection profiles & share links

**E2E-31 · USER_LOCKED profile download (real cert)** — `PASS`
- Steps: `GET /api/admin/users/{id}/profiles/USER_LOCKED/download`.
- Expected: 200 JSON `{filename, content}`; `content` is a valid `.ovpn` with cert+key,
  `remote <adminHost> <port> <proto>`, `auth-user-pass`.

**E2E-32 · AUTO_LOGIN profile** — `PASS`
- Steps: download AUTO_LOGIN for a cert user.
- Expected: `.ovpn` embeds credentials (no interactive prompt), correct ciphers
  (`AES-256-GCM`), tls-crypt keys.

**E2E-33 · SERVER_LOCKED / GENERIC profiles** — `PASS` (resolution + unit), partial live
- Steps: `GET /api/admin/daemons/resolve/{SERVER_LOCKED|USER_LOCKED|AUTO_LOGIN|GENERIC}`;
  download GENERIC on the cert-less daemon.
- Expected: SERVER_LOCKED/USER_LOCKED resolve to the dedicated daemon (`test`),
  AUTO_LOGIN/GENERIC to `Primary`; GENERIC `.ovpn` contains no cert/key.

**E2E-34 · Profile token lifecycle** — `PASS`
- Steps: create token (type, expiresAt, usesLeft) → list → download via share URL → revoke.
- Expected: token usable until usesLeft exhausted (`token_exhausted` 409) / revoked / expired.

**E2E-35 · Share link (public, one-time)** — `PASS`
- Steps: `GET /share/{token}` (no auth, correct path) → consume twice with `usesLeft=1`.
- Expected: first 200 `.ovpn` attachment; second → 409 token exhausted; unknown token → 404.

**E2E-36 · QR code** — `PASS`
- Steps: `GET .../qr` for a profile/OTP.
- Expected: 200 PNG; ProfileCard renders countdown + expiry.

### 6.8 Access rules

**E2E-37 · Rule CRUD** — `PASS`
- Steps: create (GLOBAL/group/user, ALLOW/DENY, CIDR/domain, protocol/port) → disable →
  re-enable → delete.
- Expected: lifecycle works; disabled rules excluded from effective set; delete removes.

**E2E-38 · Rule validation** — `PASS`
- Steps: malformed `dstCidr`, unknown target, empty targetId for USER/GROUP.
- Expected: 400 `validation_failed` (or target-not-found 404) — never persisted.

**E2E-39 · Domain rule resolution** — `PASS` (unit)
- Steps: create `GLOBAL ALLOW git.internal` → effective chain includes resolved domain.
- Expected: `DomainResolver` resolves the A record; chain renders DNS ACCEPT.

### 6.9 DNS overrides

**E2E-40 · DNS override CRUD** — `PASS`
- Steps: `POST /api/admin/dns-overrides` with `hostname`, `ipv4` (or `ipv6`), `scope`,
  `enabled` → list → delete.
- Expected: 200; appears in list; delete 200. (Field is `ipv4`/`ipv6`, not `address`.)

**E2E-41 · DNS override validation** — `PASS`
- Steps: malformed hostname/IP, GLOBAL record with a scopeId, GROUP/USER without scopeId.
- Expected: 400; scope-target rule enforced (`isScopeValid`).

**E2E-42 · DNS override reachability through tunnel** — `PASS` (prior live pass §M6)
- Steps: connect a client, resolve `git.internal`/`docs.internal` through the tunnel.
- Expected: DNSMASQ serves override IPs; upstream DNS still resolves.

### 6.10 Monitoring & status

**E2E-43 · Status / monitor / dashboard** — `PASS`
- Steps: `GET /api/admin/status`, `/monitor`, `/dashboard`, `/system`.
- Expected: 200 with version, uptime, daemon health, active connection count,
  CPU/memory/disk metrics, traffic samples.

**E2E-44 · Connection logs** — `PASS`
- Steps: `GET /api/admin/connections` after a real connect (see E2E-51/52).
- Expected: rows with username, virtual IP, daemon, connected/disconnected timestamps,
  bytes; duration computed.

**E2E-45 · Daemon health + resolve** — `PASS`
- Steps: `GET /api/admin/daemons` + `/daemons/resolve/{type}`; UI Live Status page.
- Expected: 3/3 daemons running; per-type resolve mapping correct.

**E2E-46 · WebSocket live status** — `SKIP` (live)
- Steps: `ws://.../ws` client receives `>...` events.
- Expected: event stream; covered by unit tests (`WsAuthHandshakeInterceptorTest`,
  `Mgmt*Test`) — browser poll fallback verified on staging instead.

### 6.11 Ops

**E2E-47 · Audit log** — `PASS`
- Steps: perform actions (rule delete, user create…) → `GET /api/admin/audit-logs`.
- Expected: entries with actor, action, entity, timestamp; sample includes recent actions.

**E2E-48 · Config report** — `PASS`
- Steps: `GET /api/admin/config-report`.
- Expected: 200 with settings snapshot, PKI inventory, versions.

**E2E-49 · Backups (create, list, download)** — `PASS`
- Steps: `POST /api/admin/backups` → list → download archive → (optional restore on a
  scratch dir).
- Expected: archive created/listed/downloadable; restore round-trip covered by
  `BackupServiceTest` (restore on live staging is destructive → covered in CI/scratch).

**E2E-50 · Branding** — `PASS`
- Steps: `GET /api/public/brand`; `PUT /api/admin/settings/brand_name` → re-GET brand.
- Expected: brand settings editable via settings keys; public endpoint reflects them;
  login page shows the branded name.

### 6.12 Live VPN data-plane (real client)

**E2E-51 · Real OpenVPN client handshake** — `PASS`
- Pre: disposable user with a real cert + AUTO_LOGIN profile, client inside the
  `passage-openvpn` container (or separate client host/VM — see finding 2.14).
- Steps: connect → wait for "Initialization Sequence Completed" → check daemon status.
- Expected: TLS handshake + auth against live server; `activeConnections` increments;
  connection log row created; client gets `10.8.0.2` (pool); `AES-256-GCM` data channel.

**E2E-52 · Static IP via CCD + iptables enforcement** — `PASS`
- Steps: set static IP `10.8.0.199` → connect → while connected dump `iptables -L -n`.
- Expected: client interface configured with `10.8.0.199/24` (PUSH `ifconfig`);
  per-user chain `PASSAGE_<hash>` exists with the static-IP rule; `PASSAGE_DOMAINS` chain
  present; base default-deny in force.

**E2E-53 · Admin disconnect (management interface)** — `PASS`
- Steps: with the client connected, `POST /api/admin/connections/{cn}/disconnect`.
- Expected: 200; connection log row gets `disconnected_at`; client session terminated.

### 6.13 Node agent (remote node)

**E2E-54 · Agent register → heartbeat → config pull (mTLS)** — `SKIP` (live) / covered by tests
- Pre: an actual agent process with an mTLS client cert on the internal TLS connector.
- Steps: `POST /internal/node/register|heartbeat|config` from the agent.
- Expected: node appears in `/api/admin/nodes`; heartbeat refreshes `last_seen`;
  config bundle (daemon configs, PKI incl. CRL, CCD, scripts, dnsmasq) returned.
- Note: `requireMtls` enforces the internal connector + client cert; live staging has no
  agent deployed. Covered by `InternalNodeControllerTest`, `Agent*Test`, `NodeConfigBundleServiceTest`.

## 7. Release E2E process

Runs on the release candidate before tagging (`RELEASE_NOTES.md` update).

### 7.1 Smoke set (fast gate — ~10 min)
`E2E-01, E2E-05, E2E-10, E2E-19, E2E-24, E2E-31, E2E-48`
Must all be `PASS`; any `FAIL` blocks the release (findings must be fixed or explicitly
accepted with a severity note in `test-findings.md`).

### 7.2 Full set
All scenarios in §6.1–§6.13 marked `PASS` or `SKIP` (with reason). `FAIL`s must be
resolved or have an accepted-finding record. Expected runbook:

1. `make test` green on a clean checkout (903 backend + 169 frontend).
2. `install.sh --reset` on the staging host (or a fresh VM) → wizard E2E-01/02.
3. Load demo data → verify seed users/groups/rules/DNS (records of §M6).
4. Run the auth/RBAC/CRUD scenarios against the running stack.
5. Run the live VPN scenarios from a *separate* client host/VM (never the server host).
6. Smoke the UI: login, Dashboard, Users, Certificates, Live Status — no JS errors.
7. Update `docs/test-findings.md` with any new findings; mark release-pass in
   `RELEASE_NOTES.md`.

## 8. Fixtures & test data

- Backend: builders for `User`, `Group`, `AccessRule`, `Certificate`, `ProfileToken`,
  `DnsRecord`, `Daemon`; shared `ServerConfig.defaults()`.
- Frontend: MSW or mocked `api()` for `/api/**` in Vitest; React Query wrappers.
- Live staging: `.env` from `.env.example`; dedicated `PASSAGE_DATA_DIR` volume. Reusable
  identities (reset via DB bcrypt hash, argon-less): `admin` / `qa_lead`(GROUP_ADMIN,
  Marketing) / `devops_lead`(GROUP_ADMIN, DevOps) / demo users. Disposable users
  (`e2e_crud`, `e2e_live`, …) are created and deleted per scenario; leftover rows are
  cleaned at the end of a run.

## 9. Verified non-issues (recent E2E pass)

- **DNS override DTO** uses `ipv4`/`ipv6`, not `address`; GLOBAL forbids `scopeId` and
  GROUP/USER require it (`isScopeValid`).
- **Share links** are public at `/share/{token}` (also `/api/portal/share/**`); the
  profile download API returns JSON `{filename, content}`, not a raw file.
- **Branding** is stored as settings keys (`brand_name`, `brand_primary_color`,
  `brand_footer`, `brand_logo_url`) via `PUT /api/admin/settings/{key}`, exposed at
  `GET /api/public/brand`.
- **Group-admin scope** verified in the UI too: a GROUP_ADMIN session shows only its own
  group's users in the Users grid.
- **Rate limiter** returns 429 for bursts; UI poll 401s are avoided by proactive token
  refresh (finding 2.9 resolved).

## 10. Definition of Done (recap)

1. Feature behind a clean service + DTO boundary.
2. Unit tests for new logic (backend + frontend where applicable).
3. `make test` green.
4. TODO.md checkbox updated.
5. New/changed feature area updated in this file's matrix + E2E catalog.

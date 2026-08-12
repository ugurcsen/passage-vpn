# Test Plan — OpenVPN Management Panel

Living test strategy for the management panel (backend Java 21 + Spring Boot, frontend
React + TypeScript + MUI). Companion to `TODO.md` and `RELEASE_NOTES.md`. Update this file
whenever the suite or the feature set changes.

Confirmed defects and their remediation live in `docs/test-findings.md`.

Legend: `[x]` covered, `[~]` partially covered, `[ ]` missing / to add.

---

## 1. Goals

1. Keep `make test` green before every commit (Definition of Done).
2. Cover every state-mutating backend service method with a unit test.
3. Cover every frontend page/flow with a component test where behavior matters.
4. Verify the OpenVPN integration contract (scripts ↔ `/internal/**`) at the HTTP
   contract level and live E2E level.
5. Keep tests hermetic (no external services, temp SQLite, mocked subprocesses).

## 2. Test pyramid

```
            E2E (manual + scripted live)        few
           Integration (MockMvc + SQLite)       some
          Unit: services / engines / DTOs       many
        Component (frontend, RTL + Vitest)      many
```

- **Backend unit**: JUnit 5 + Mockito + AssertJ. Repos mocked; `EasyRsaService` and
  `ProcessRunner` mocked so tests never shell out.
- **Backend integration**: `@SpringBootTest` smoke (SQLite + Flyway) today; add
  MockMvc + temp-file SQLite slices for controller flows.
- **Frontend**: Vitest + React Testing Library + `@testing-library/jest-dom`.
- **E2E**: docker-compose up, live wizard + real OpenVPN client handshake.

## 3. How to run

```bash
make test          # backend (gradle) + frontend (vitest)
make lint          # frontend eslint + backend spotless
cd backend  && ./gradlew test spotlessCheck
cd frontend && npm run lint && npm run test
```

## 4. Test inventory (current state)

### 4.1 Backend — 15 classes, 213 tests, all green

| Class | Area | Status |
|---|---|---|
| `OpnlVpnApplicationTests` | Context smoke: SQLite + Flyway boot | [x] |
| `setup/SetupServiceTest` | Wizard state machine, PKI + config provisioning | [x] |
| `auth/AuthServiceTest` | Login, refresh rotation, MFA, lockout, VPN verify | [x] |
| `auth/spi/AuthProviderManagerTest` | Provider selection (local + stubs) | [x] |
| `security/RateLimitFilterTest` | 429 + Retry-After per-IP | [x] |
| `api/admin/UserAdminServiceTest` | User CRUD, ban/unban, bulk, reset, group assignment | [x] |
| `setting/SettingsServiceTest` | JSON settings, inheritance (user > group > default) | [x] |
| `pki/IndexParserTest` | `index.txt` → `CertIndexEntry` parsing | [x] |
| `pki/CertServiceTest` | Issue/reuse/revoke bookkeeping + Easy-RSA wiring | [x] |
| `network/ServerConfigGeneratorTest` | `server.conf` rendering, split/generic daemons, JSON round-trip | [x] |
| `access/RuleEngineTest` | Rule resolution + nested groups + iptables rendering/teardown | [x] |
| `access/AccessRuleServiceTest` | Rule CRUD validation, priority, target-name resolution | [x] |
| `profile/ProfileServiceTest` | 4 profile types + token lifecycle (uses/expiry/generic) | [x] |
| `api/portal/PortalAccountServiceTest` | Self-service MFA setup/enable/disable + password change | [x] |
| `internal/InternalControllerTest` | `/internal/connect|disconnect|auth/verify|seed-admin` contract + filter | [x] |

### 4.2 Frontend — 18 test files, 76 tests

| File | Area | Status |
|---|---|---|
| `lib/api.test.ts` | Token-refresh wrapper, ApiError mapping, 204 | [x] |
| `hooks/useAuth.test.tsx` | Session restore, login/MFA/logout, token store | [x] |
| `pages/LoginPage.test.tsx` | Sign-in form renders | [x] |
| `pages/MfaLoginPage.test.tsx` | Verification code control id/name (a11y) | [x] |
| `pages/DashboardPage.test.tsx` | Stat cards + phase placeholders render | [x] |
| `pages/UsersPage.test.tsx` | Grid render, create dialog, status filter, create POST, MFA manage dialog | [x] |
| `pages/CertsPage.test.tsx` | Grid render, issue dialog payload, revoke disabled | [x] |
| `pages/AccessRulesPage.test.tsx` | Rule render, global/user create payload, enable toggle | [x] |
| `pages/GroupsPage.test.tsx` | Grid render, create POST payload, members dialog PUT | [x] |
| `pages/ProfilesPage.test.tsx` | Share-link table, token create payload, copy-link clipboard | [x] |
| `pages/SharePage.test.tsx` | Token download (blob), error state | [x] |
| `pages/SetupWizardPage.test.tsx` | Full flow: admin POST, server config POST, PKI provision gate, finish | [x] |
| `components/ProfileCard.test.tsx` | Download blob, QR render | [x] |
| `pages/PortalPage.test.tsx` | Profile list render, download endpoint | [x] |
| `pages/SettingsPage.test.tsx` | Typed editors, boolean toggle, list/number save, structured network form | [x] |
| `pages/DaemonsPage.test.tsx` | Daemon rows, create payload, enable toggle, primary delete guard | [x] |
| `pages/StatusPage.test.tsx` | Panel info, daemon health, active connections, sessions | [x] |
| `pages/AccountPage.test.tsx` | Self-service MFA setup/disable flow + password change | [x] |

## 5. Backend test plan by feature area

### 5.1 Setup wizard (Phase 0/1)
- [x] State machine transitions and guards (`requireState`).
- [x] Admin creation, PKI provision, config write delegation.
- [x] Re-running completed steps returns 409; unknown step rejected.
- [x] `currentServerConfig()` falls back to defaults / reads stored setting.
- [x] Frontend drives all steps (admin → server → pki → complete); login requires
      `COMPLETE` state and `/login` redirects to `/setup` while incomplete.

### 5.2 Authentication & security (Phase 2)
- [x] Password login success/failure, MFA challenge + redeem, refresh rotation + logout.
- [x] Lockout (failed attempts + `locked_until`), VPN `verifyVpnLogin` (password/OTP/ban/lock).
- [x] Rate limiting filter (bucket exhaustion → 429 + `Retry-After`).
- [x] Auth provider selection.
- [x] Banned-user refresh rejection, expired refresh token, MFA `enforce_mfa` toggle
      (missing OTP → `mfa_required`, wrong OTP → `invalid_code`, valid OTP → allowed).

### 5.3 Users & groups (Phase 2)
- [x] Create/edit/delete user, ban/unban, admin grant, RESELLER restrictions.
- [x] Password reset (hash re-encode), group assignment, static-IP handoff to `CcdService`.
- [x] Bulk ban/unban/delete, empty-batch rejection, last-admin guard, self-delete guard,
      username trimming + uniqueness conflict, search filter (username/full name/email).

### 5.4 Settings (Phase 2)
- [x] Per-user / per-group / server default inheritance resolution.
- [x] JSON-string persistence with typed accessors.
- [ ] Add: invalid JSON fallback, override semantics (explicit `null` vs missing).

### 5.5 PKI & certificates (Phase 1/3)
- [x] `index.txt` parsing (VALID/REVOKED/EXPIRED rows).
- [x] `CertService`: ensure-once, reuse valid cert, revoke → CRL, double-revoke conflict.
- [ ] Add: restore/rotate, expiry-warning scheduler, index→DB metadata sync.

### 5.6 Server config engine (Phase 1)
- [x] Rendering: port/proto, DNS/route pushes, full-tunnel vs split, generic daemon
      (`client-cert-not-required` + `verify-client-cert none`), `authUserPass` toggle,
      management port per daemon index.
- [x] JSON round-trip of `ServerConfig`, invalid JSON → defaults, full placeholder
      substitution (no unreplaced `__X__` tokens).

### 5.7 Access rules / ZTNA (Phase 3) — `RuleEngine`
- [x] Effective rule resolution: global + group chain (child-first) + user, priority sort.
- [x] Disabled rules skipped.
- [x] iptables rendering: chain create, conntrack/DNS defaults, protocol/port/CIDR
      matches, default DROP, FORWARD jump, full teardown; no-rules → empty output.
- [x] `AccessRuleService` CRUD: target existence validation, priority bump, target-name
      resolution on create/update; `RuleEngine` nested group inheritance (child-first),
      ancestry-cycle termination, stable chain name.

### 5.8 Connection profiles (Phase 3) — `ProfileService`
- [x] USER_LOCKED/AUTO_LOGIN/SERVER_LOCKED embed cert+key; AUTO_LOGIN omits
      `auth-user-pass`; GENERIC has CA only.
- [x] Token: use-count decrement, revoked/expired rejection.
- [x] Token exhaustion (`usesLeft=0`), last-use decrement to zero, expiry boundary
      (strict `isAfter`), generic token creation/download with no non-admin user → error.

### 5.9 Internal VPN contract (Phase 3)
- [x] MockMvc tests for `/internal/connect` + `/internal/disconnect`: unknown user,
      banned/locked user, no-rule user (empty iptables), rule user (non-empty apply/remove),
      seed-admin (create/weak/conflict) + internal-token 401 serialization.
- [ ] `verify-user-pass.sh` / `client-connect.sh` / `client-disconnect.sh` contract tests
      (shell: feed env, assert curl payload shape + exit codes against a stubbed backend).

## 6. Frontend test plan

### 6.1 Priority order
1. `useAuth` / `useToast` hooks + `api()` token-refresh wrapper (pure-logic, high value). ✅
2. `UsersPage` — list renders, create/edit dialog validation, ban/delete confirm flows. ✅
3. `CertsPage` — issue dialog, revoke confirm, status chips. ✅
4. `AccessRulesPage` — create/edit dialog, target-type switching, enable toggle. ✅
5. `ProfilesPage` — download for user, create token dialog, copy-link. ⬜
6. `PortalPage` + `ProfileCard` — download + QR toggle. ✅
7. `SharePage` — token download + error state. ⬜
8. `GroupsPage`, `DashboardPage`, `SetupWizardPage`, `LoginPage` (already partial). ⬜

### 6.2 Coverage checklist
- [x] Hook tests: `useAuth` session restore/logout; `api()` refresh-on-401 retry, error
      mapping to `ApiError`.
- [x] `api()` refresh-on-401 single-flight retry, 401 retry skip on `/auth/**`, 204 →
      `undefined`, error mapping to `ApiError`, token purge on failed refresh.
- [x] Users: create user posts payload; status filter; delete confirm.
- [x] Certs: issue requires user selection; revoke disabled when not VALID.
- [x] Rules: GLOBAL hides target select; USER lists options; save payload shape; enable toggle.
- [ ] Profiles: token creation payload (expires/uses), copy-link clipboard.
- [x] Portal: fetches profile list, download + QR actions.
- [x] Share: valid token downloads, error message on failure.
- [x] SetupWizard: admin/server/pki POST payloads, PKI gate before Continue, Finish
      redirect; LoginPage redirects to `/setup` when state ≠ `COMPLETE`.

## 7. Integration & E2E plan

### 7.1 Backend integration (to add)
- [ ] `@SpringBootTest` + temp SQLite file: full REST flows through MockMvc with a
      seeded admin JWT (setup → admin → create user → assign group → set static IP →
      create rule → issue cert → create token → download profile).
- [ ] Assert CCD file written to temp `ccdDir` and iptables commands in `/internal/connect`.

### 7.2 Live E2E checklist (manual, `make up`)
- [ ] Setup wizard: admin user → PKI → network settings → server boots.
- [ ] OpenVPN client (OpenVPN 2.6) connects with USER_LOCKED profile (password + cert).
- [ ] Static-IP user: client gets the configured IP (verify `ifconfig` on client).
- [ ] Access rule user: `iptables -L OPNL_*` present on connect, removed on disconnect;
      blocked destination unreachable, allowed destination reachable.
- [ ] GENERIC profile on `client-cert-not-required` daemon connects with credentials only.
- [ ] Share-token URL downloads the profile; exhausted/revoked token returns 409/404.
- [ ] MFA user connects via `static-challenge` OTP challenge flow.
- [ ] Kill/ban a connected user → immediate disconnect (management interface).

## 8. Fixtures & test data

- Backend: builders for `User`, `Group`, `AccessRule`, `Certificate`, `ProfileToken`;
  shared `ServerConfig.defaults()`.
- Frontend: `@tanstack/react-query` mock + MSW for `/api/**` (to add).
- E2E: `.env` from `.env.example`; dedicated `data/e2e` dir for SQLite/PKI.

## 9. CI (target, Phase 4.7)

- [ ] Workflow: `make lint` → `make test` → `make build` (docker images) on PR.
- [ ] Hermetic: no docker-in-docker for unit/component layers.

## 10. Definition of Done (recap)

1. Feature behind a clean service + DTO boundary.
2. Unit tests for new logic (backend + frontend where applicable).
3. `make test` green.
4. TODO.md checkbox updated.

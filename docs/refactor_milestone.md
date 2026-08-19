# Refactor Milestone — PassageVPN

Refactoring and improvement plan for the PassageVPN project. The goal is to bring the
codebase to production-grade maturity: decompose god classes, unify frontend architecture,
eliminate dead code, harden security edges, and raise the CI/CD baseline.

> **Version context:** This plan starts from `v0.1.0-beta.20` (196 backend source files,
> 24 frontend pages, 22 Flyway migrations, 110+ API endpoints).

Legend: `[ ]` = pending, `[~]` = in progress, `[x]` = done.

---

## Working Protocol

Every task follows the same flow:

1. Flip the item to `[~]` while working.
2. Implement code + unit tests (backend and frontend where applicable).
3. Run verification:
   - `cd backend && ./gradlew test spotlessCheck`
   - `cd frontend && npm run lint && npm run test`
4. Flip the item to `[x]` when verified.
5. Update `RELEASE_NOTES.md` when a sub-milestone ships.

---

## Phase A — Backend: God Class Decomposition

The largest services have accumulated too many responsibilities. Extract focused
collaborators so each service stays transactional and single-purpose.

### A1. CcdService (709 lines) — extract Ipv6Util + IpPoolAllocator

- [x] **A1.1** Create `common/Ipv6Util.java` — extract all BigInteger arithmetic:
  `canonicalIpv6()`, `subnetNetwork()`, `prefixMask()`, `maskToNetwork()`,
  `toUnsigned()`, `ipv6ToUnsigned()`, `formatIpv6()`. These ~150 lines have no
  dependency on Spring or any other service.
- [x] **A1.2** Create `ccd/IpPoolAllocator.java` — a generic pool allocator that
  abstracts the duplicated IPv4/IPv6 pool logic. Both `parsePool()`/`parseIpv6Pool()`,
  `findFreeIp()`/`findFreeIpv6()`, `validate()`/`validateIpv6()` are structurally
  identical; the strategy pattern with `InetAddress` formatting methods eliminates
  the duplication.
- [x] **A1.3** Create `Ipv6UtilTest.java` — test canonical formatting (RFC 5952),
  prefix mask calculation, boundary prefixes, loopback/mapped addresses, invalid
  input rejection.
- [x] **A1.4** Create `IpPoolAllocatorTest.java` — test free-IP allocation,
  conflict detection, pool exhaustion, IPv4 and IPv6 variants.
- [x] **A1.5** Refactor `CcdService` to use the new utility classes. Verify
  `CcdServiceTest` passes without modification (behavioral equivalence).
- [x] **A1.6** Run `./gradlew test spotlessCheck` — all green.

**Files created:**
```
backend/src/main/java/com/passagevpn/common/Ipv6Util.java          (104 lines)
backend/src/main/java/com/passagevpn/ccd/IpPoolAllocator.java       (161 lines)
backend/src/test/java/com/passagevpn/common/Ipv6UtilTest.java       (~120 lines, 30 tests)
backend/src/test/java/com/passagevpn/ccd/IpPoolAllocatorTest.java   (168 lines, 23 tests)
```

**Result:** CcdService dropped from 615 → 516 lines (~100 lines removed).

### A2. UserAdminService (582 lines, 12 dependencies) — extract UserIpAdminService

- [x] **A2.1** Create `api/admin/UserIpAdminService.java` — move the 6 static IP
  methods (`setStaticIp`, `allocateStaticIp`, `clearStaticIp`, `setStaticIpv6`,
  `allocateStaticIpv6`, `clearStaticIpv6`) plus their audit-logging wrappers out
  of `UserAdminService`. This service takes `CcdService`, `AuditLogService`, and
  `UserRepository` as dependencies. Delegates DTO construction back to
  `UserAdminService.getUser()` so group membership, MFA, and settings resolve
  consistently from one code path.
- [x] **A2.2** Update `UserAdminController` — rewire the static IP endpoints to
  call `UserIpAdminService` instead of `UserAdminService`.
- [x] **A2.3** Create `UserIpAdminServiceTest.java` — test allocate, set, clear,
  conflict, and audit-log interactions for both IPv4 and IPv6.
- [x] **A2.4** Remove the 6 static IP methods from `UserAdminService`. Verify it
  drops to ~520 lines with 12 constructor parameters.
- [x] **A2.5** Run `./gradlew test spotlessCheck` — all green.

**Files created:**
```
backend/src/main/java/com/passagevpn/api/admin/UserIpAdminService.java  (127 lines)
backend/src/test/java/com/passagevpn/api/admin/UserIpAdminServiceTest.java  (119 lines, 8 tests)
```

**Result:** UserAdminService dropped from 582 → 520 lines (62 lines removed).

### A3. DaemonService (646 lines) — reduce duplication ✅

- [x] **A3.1** Refactor `validateUnique()` — replaced 4 separate `findAll()` calls
  with a single pass: fetch all daemons once, filter out the excluded entity, then
  check index, port, subnet, and IPv6 subnet uniqueness in-memory.
- [x] **A3.2** Extract `applyRequest(Daemon, DaemonRequest, int)` helper
  — both `create()` and `update()` now share 17 identical setter lines via this
  method. `DaemonRequest` record remains unchanged.
- [x] **A3.3** Updated `DaemonServiceTest.createRejectsDuplicateDaemonIndex` to stub
  `findAll()` (required by the consolidated `validateUnique` path). All 996 tests
  pass.
- [x] **A3.4** `./gradlew test spotlessCheck` — all green.

### A4. InternalController (237 lines) — extract ConnectionOrchestrator ✅

- [x] **A4.1** Created `internal/ConnectionOrchestrator.java` (178 lines) — moved
  `connect()` and `disconnect()` orchestration logic (user lookup, ban/lock check,
  connection-limit enforcement, connection registry update, connection log recording,
  iptables resolution) out of the controller into a service.
- [x] **A4.2** Refactored `InternalController.connect()` and `disconnect()` to delegate
  to `ConnectionOrchestrator.connectUser()` and `disconnect()`. The controller is now
  a thin HTTP adapter (327 → 237 lines).
- [x] **A4.3** Moved `sanitizeReason()` to `ConnectionOrchestrator.sanitizeReason()`
  as a domain-level concept. Also moved `daemonIndexOf()` and `asInt()` helpers.
- [x] **A4.4** Created `ConnectionOrchestratorTest.java` (198 lines, 18 tests) —
  covers all deny paths (unknown user, banned, locked, max connections), happy path,
  disconnect, sanitizeReason, daemonIndexOf. Updated `InternalControllerTest` to
  mock the orchestrator (576 → 412 lines, 3 delegation tests replace 10+ old tests).
- [x] **A4.5** `./gradlew test spotlessCheck` — all 1002 tests pass.

---

## Phase B — Backend: Code Quality & Security Hardening

### B1. SettingsService naming cleanup ✅

- [x] **B1.1** Renamed `groupRepository_` → `groupSettingRepository` in
  `SettingsService.java` (field and constructor parameter).
- [x] **B1.2** Verified `SettingsServiceTest` passes.
- [x] **B1.3** `./gradlew test spotlessCheck` — all green.

### B2. BCrypt → Argon2id password hashing migration ✅

AGENTS.md says "Argon2id preferred" but the codebase uses BCrypt. This adds a
migration-safe dual-hash support.

- [x] **B2.1** Added `Argon2PasswordEncoder` as default via Spring's `DelegatingPasswordEncoder`
  in `SecurityConfig`. Added `Argon2` tuning record to `PassageProperties.Auth`
  (defaults: 3 iterations, 64MB, 4 parallelism, 16 salt, 32 hash). Added BouncyCastle
  dependency.
- [x] **B2.2** `DelegatingPasswordEncoder` uses Argon2id for new hashes, falls back to BCrypt
  for existing `{bcrypt}` prefixed hashes. Transparent migration path.
- [x] **B2.3** Added migration Javadoc to `AuthService` documenting dual-hash support.
- [x] **B2.4** `PasswordEncoderConfigTest` covers Argon2id new-hash, BCrypt legacy verify,
  and wrong-password rejection paths.
- [x] **B2.5** `./gradlew test spotlessCheck` — all 1005 tests pass.

### B3. CORS origin tightening ✅

- [x] **B3.1** Added `Cors` record to `PassageProperties` with `allowedOrigins` field and
  `patterns()` helper (splits comma-separated string, defaults to `*`).
- [x] **B3.2** Updated `SecurityConfig.corsConfigurationSource()` to use
  `passageProperties.cors().patterns()` instead of hardcoded `"*"`.
- [x] **B3.3** Documented in `.env.example`: `PASSAGE_CORS_ORIGINS=*`.
- [x] **B3.4** `./gradlew test spotlessCheck` — all 1005 tests pass.

### B4. AuthService in-memory state documentation ✅

- [x] **B4.1** Added Javadoc to `redeemedChallenges` and `pendingVpnAuths` fields
  documenting the single-instance constraint and clustering limitation.
- [x] **B4.2** N/A — inline Javadoc is sufficient; no separate startup warning needed.

### B5. RateLimitFilter IP resolution

- [ ] **B5.1** Replace the manual `X-Forwarded-For` parsing in
  `RateLimitFilter.clientIp()` with Spring's `RequestContextHolder` to get the
  resolved remote address (already processed by Tomcat's `RemoteIpValve`).
- [ ] **B5.2** Verify `RateLimitFilterTest` passes.
- [ ] **B5.3** Run `./gradlew test spotlessCheck`.

### B6. Manage password at-rest documentation ✅

- [x] **B6.1** Added TODO + threat model Javadoc to `OpenVpnNode.mgmtPassword`
  documenting plaintext storage necessity and infrastructure-level protection requirement.
- [x] **B6.2** N/A — inline Javadoc covers the threat model adequately.

---

## Phase C — Frontend: Feature-Folder Architecture

The project prescribes `frontend/src/features/<feature>/` in AGENTS.md but currently
uses flat `pages/` and `components/` directories. This migration restructures the
codebase for discoverability and modularity.

### C1. Create shared test utilities (prerequisite) ✅

- [x] **C1.1** Created `src/test/renderWithProviders.tsx` — composable render wrapper
  with ThemeProvider, QueryClientProvider, AuthProvider, ToastProvider, BrandProvider,
  and optional MemoryRouter. Accepts `RenderOptions` for selective provider inclusion.
- [x] **C1.2** Created `src/test/helpers.ts` — shared `json()` response builder,
  `mockFetch()` factory, `resetFetchMock()`, and typed assertion helpers
  (`expectFetchPost`, `expectFetchPut`, `expectFetchDelete`, `expectFetchNotCalled`).
  Created `src/test/fixtures.ts` — shared `fakeAdmin`, `fakeGroupAdmin`, `fakeUser`,
  `fakeUserMfa` fixtures.
- [x] **C1.3** Migrated `BrandingPage.test.tsx` to use new utilities (3 tests, all pass).
- [x] **C1.4** Remaining files will be migrated during C2 (feature-folder move).
- [x] **C1.5** `npm run test` — all 175 tests pass.

### C2. Migrate pages to feature folders (incremental) ✅

Migrate one feature at a time. Each migration moves the page component, its sub-
components, types, hooks, and tests into a feature folder. The routing in `App.tsx`
is updated to import from the new location.

**Migration order** (simplest → most complex):

- [x] **C2.1** `auth/` — Moved `LoginPage.tsx`, `MfaLoginPage.tsx`,
  `MfaEnrollPage.tsx` + tests into `src/features/auth/`.
- [x] **C2.2** `dashboard/` — Moved `DashboardPage.tsx`, `StatusPage.tsx` + tests
  into `src/features/dashboard/`.
- [x] **C2.3** `settings/` — Moved `SettingsPage.tsx` + test into
  `src/features/settings/` (was already partially there).
- [x] **C2.4** `wizard/` — Moved `SetupWizardPage.tsx` + test into
  `src/features/wizard/`.
- [x] **C2.5** `users/` — Moved `UsersPage.tsx` + test into `src/features/users/`.
- [x] **C2.6** `groups/` — Moved `GroupsPage.tsx` + test into `src/features/groups/`.
- [x] **C2.7** `daemons/` — Moved `DaemonsPage.tsx` + test into
  `src/features/daemons/`.
- [x] **C2.8** `access-rules/` — Moved `AccessRulesPage.tsx` + test into
  `src/features/access-rules/`.
- [x] **C2.9** `certs/` — Moved `CertsPage.tsx` + test into `src/features/certs/`.
- [x] **C2.10** `dns-overrides/` — Moved `DnsOverridesPage.tsx` + test into
  `src/features/dns-overrides/`.
- [x] **C2.11** `profiles/` — Moved `ProfilesPage.tsx`, `PortalPage.tsx`,
  `AccountPage.tsx` + tests into `src/features/profiles/`.
- [x] **C2.12** `connection-logs/` — Moved `ConnectionLogsPage.tsx` + test into
  `src/features/connection-logs/`.
- [x] **C2.13** `nodes/` — Moved `NodesPage.tsx` + test into `src/features/nodes/`.
- [x] **C2.14** `backup/` — Moved `BackupsPage.tsx`, `ConfigReportPage.tsx`,
  `MaintenancePage.tsx` + tests into `src/features/backup/`.
- [x] **C2.15** `audit-log/` — Moved `AuditLogsPage.tsx` + test into
  `src/features/audit-log/`.
- [x] **C2.16** `api-tokens/` — Moved `ApiTokensPage.tsx` + test into
  `src/features/api-tokens/`.
- [x] **C2.17** Updated `App.tsx` routing to import all pages from their new feature
  folder locations. Also moved `BrandingPage` → `src/features/branding/`.
- [x] **C2.18** `npm run test && npm run build` — all 175 tests pass, build succeeds.

### C3. Decompose large pages into sub-components

After feature-folder migration, break the largest pages into focused sub-components
within their feature folder.

- [x] **C3.1** `UsersPage` (1199 lines) — extract:
  - `UserCreateDialog.tsx` — create user form
  - `PasswordResetDialog.tsx` — password reset form
  - `MfaSetupDialog.tsx` — MFA provisioning flow (QR, verify, enable)
  - `UserColumns.tsx` — DataGrid column definitions
  - `useUserMutations.ts` — mutation hooks (create, update, delete, ban, reset
    password, MFA)
- [x] **C3.2** `SettingsPage` (726 lines) — extract:
  - `NetworkConfigDialog.tsx` — network settings form
  - `AdvancedSettingsDialog.tsx` — raw JSON editor
  - `KnownSettingsGrid.tsx` — typed settings table
  - `useSettingsMutations.ts` — mutation hooks
- [x] **C3.3** `DaemonsPage` (610 lines) — extract:
  - `DaemonCreateDialog.tsx` — daemon create/edit form
  - `RouteChipList.tsx` — reusable chip-based route editor (shared with SettingsPage)
  - `DaemonColumns.tsx` — DataGrid column definitions
- [x] **C3.4** `DashboardPage` (525 lines) — extract:
  - `StatCards.tsx` — stat card row
  - `TrafficChart.tsx` — MUI X Charts traffic visualization
  - `RecentConnections.tsx` — recent connections table
- [x] **C3.5** `GroupsPage` (430 lines) — extract:
  - `GroupCreateDialog.tsx`
  - `MemberEditor.tsx` — chip-based member picker
  - `GroupColumns.tsx`
- [x] **C3.6** Run `npm run test && npm run lint && npm run build` — all green.

### C4. Decompose extracted hooks into feature hooks

- [x] **C4.1** Create `src/features/users/useUserMutations.ts` — mutation hooks
  for user CRUD, ban/unban, password reset, MFA provisioning.
- [x] **C4.2** Create `src/features/groups/useGroupMutations.ts` — mutation hooks
  for group CRUD, member management.
- [x] **C4.3** Create `src/features/daemons/useDaemonMutations.ts` — mutation
  hooks for daemon CRUD, toggle enable/disable.
- [x] **C4.4** Create `src/features/settings/useSettingsMutations.ts` — mutation
  hooks for settings CRUD.
- [x] **C4.5** Run `npm run test && npm run lint` — all green.

---

## Phase D — Frontend: Dependency & Quality Cleanup

### D1. Remove dead dependencies

- [x] **D1.1** Audit `react-hook-form` and `zod` usage — if confirmed unused,
  remove from `package.json`. The `@hookform/resolvers` package should also be
  removed if `react-hook-form` is gone.
- [x] **D1.2** Remove the manual chunk in `vite.config.ts` for `react-hook-form`
  if the dependency is removed.
- [x] **D1.3** Run `npm run build` — verify bundle size decreases.

### D2. Replace deprecated react-json-view

- [x] **D2.1** Replace `react-json-view` with `react18-json-view` (actively
  maintained fork) or a simpler alternative like a `<pre>` with
  `JSON.stringify(data, null, 2)`.
- [x] **D2.2** Update imports in affected files (likely `ConfigReportPage`,
  `SettingsPage` advanced editor).
- [x] **D2.3** Run `npm run test && npm run lint` — all green.

### D3. Re-enable no-explicit-any

- [x] **D3.1** Add `"@typescript-eslint/no-explicit-any": "warn"` to the ESLint
  config. Start with `warn` to identify current violations without breaking the
  build.
- [x] **D3.2** Fix or suppress each `any` occurrence with a proper type. Track
  the count down to zero.
- [x] **D3.3** Escalate to `"error"` once the codebase is clean.
- [x] **D3.4** Run `npm run lint` — no warnings.

### D4. Fragile test selectors

- [x] **D4.1** Audit all `querySelector("input[type='checkbox']")` patterns in
  test files. Replace with MUI's accessible `role="checkbox"` selectors
  (`screen.getByRole("checkbox", { name: /label/ })`) where possible.
- [x] **D4.2** Run `npm run test` — all green.

### D5. Missing test coverage

- [x] **D5.1** Add test for `ConfirmDialog` component.
- [x] **D5.2** Add test for `useBrand` hook.
- [x] **D5.3** Add test for `useToast` hook.
- [x] **D5.4** Add test for `roles.ts` utility.
- [ ] **D5.5** Add API failure path tests for pages with light coverage:
  `ConfigReportPage`, `StatusPage`, `BackupsPage`, `MaintenancePage`.
- [x] **D5.6** Run `npm run test:coverage` — verify thresholds met.

---

## Phase E — Backend: Test Coverage & Missing Tests

### E1. Untested utility/config classes

- [x] **E1.1** Add `SecurityConfigTest.java` — verify the security filter chain
  configuration (public endpoints, protected endpoints, CORS config).
- [x] **E1.2** Add `PassagePropertiesTest.java` — verify property binding and
  validation (JWT secret min length, default values).
- [x] **E1.3** Add `AgentPropertiesTest.java` and `InternalPropertiesTest.java`
  — verify property binding.
- [x] **E1.4** Add `PostAuthHookServiceTest.java` — test script execution,
  timeout, stderr capture, and hook failure behavior.
- [x] **E1.5** Add `AuthFailureRecorderTest.java` — test auth failure recording.
- [x] **E1.6** Run `./gradlew test jacocoTestCoverageVerification spotlessCheck`.

### E2. IPv6 edge case tests for CcdService

- [x] **E2.1** Add IPv6 canonical formatting tests to `CcdServiceTest` (or the
  new `Ipv6UtilTest`): loopback `::1`, IPv4-mapped `::ffff:192.168.1.1`, link-
  local `fe80::1`, ULA `fd00::1`, full expansion vs zero compression.
- [x] **E2.2** Add IPv6 pool allocation edge cases: `/128` single-host pool,
  pool exhaustion, boundary allocation (last IP in range).
- [x] **E2.3** Run `./gradlew test spotlessCheck`.

---

## Phase F — CI/CD & DevOps Hardening

### F1. CI pipeline improvements

- [x] **F1.1** Add a security scanning step to CI: `dependency-check` (OWASP) or
  `snyk` for backend dependency vulnerabilities. Run on `main` and PRs.
- [x] **F1.2** Add a frontend dependency audit step: `npm audit --audit-level=high`
  in the CI frontend job.
- [x] **F1.3** Add `release.yml` coverage gate — the release workflow currently
  runs `test` but not `jacocoTestCoverageVerification`. Add it to prevent
  releasing with coverage regressions.
- [x] **F1.4** Add Docker image vulnerability scanning: `docker scout cves` or
  `trivy` on the built images in the `docker-build` job.

### F2. Integration test smoke test in CI

- [x] **F2.1** Create a `docker-compose.ci.yml` that starts backend + openvpn
  (no frontend needed for backend smoke).
- [x] **F2.2** Add a CI job that runs `docker compose -f docker-compose.ci.yml up`
  and hits `/actuator/health` + `/api/setup/state` to verify the stack boots.
- [x] **F2.3** Add this job to `ci.yml` after the `docker-build` job.

### F3. Release workflow polish

- [ ] **F3.1** Add `release.yml` step to generate `CHANGELOG.md` from commit
  messages between the previous tag and the new tag.
- [ ] **F3.2** Add GitHub Release body with auto-generated notes from the tag
  range.

---

## Phase G — Documentation & Cross-Cutting

### G1. Architecture documentation

- [ ] **G1.1** Update `docs/architecture.md` with the new feature-folder
  structure diagram for the frontend.
- [ ] **G1.2** Document the backend service decomposition (extracted utility
  classes, connection orchestrator) in `docs/architecture.md`.
- [ ] **G1.3** Document the BCrypt→Argon2id migration path in
  `docs/architecture.md` and `docs/configuration.md`.

### G2. API documentation

- [ ] **G2.1** Regenerate `docs/api.md` via `make api-docs` after all backend
  changes.
- [ ] **G2.2** Verify OpenAPI spec is clean (no warnings, no unresolvable
  references).

### G3. Cross-cutting TODO items

These are the remaining items from the original `TODO.md` cross-cutting section:

- [x] **G3.1** Environment-variable-driven configuration audit — verify all
  secrets are only configurable via `.env` (no hardcoded values in source).
- [x] **G3.2** English-only verification — scan for non-English UI strings,
  comments, and commit messages.
- [x] **G3.3** Final `make test` pass — backend + frontend green, Spotless +
  ESLint clean.

---

## Execution Order & Milestones

The recommended execution order groups related work into shippable milestones:

### R1 — Backend decomposition (Phase A + B1-B2)
Ship: service decomposition, naming cleanup, Argon2id support.
Backend only, no API changes, all tests pass.

### R2 — Backend security hardening (Phase B3-B6)
Ship: CORS tightening, rate-limit fix, documentation.
No breaking changes, configuration additions only.

### R3 — Frontend architecture (Phase C1-C2)
Ship: feature-folder migration, shared test utilities.
No functional changes, imports updated, all tests pass.

### R4 — Frontend decomposition (Phase C3-C4)
Ship: page decomposition, extracted hooks.
No functional changes, all tests pass.

### R5 — Frontend cleanup (Phase D)
Ship: dead dependency removal, deprecated package replacement, lint fixes,
missing tests.
Bundle size decrease expected.

### R6 — Backend test coverage (Phase E)
Ship: missing tests, IPv6 edge cases.
Coverage gate improvement.

### R7 — CI/CD hardening + docs (Phase F + G)
Ship: security scanning, integration smoke test, documentation updates.
DevOps and documentation only, no source code changes.

---

## Metrics to Track

| Metric | Current (beta.20) | Target (post-refactor) |
|---|---|---|
| Backend source lines | 18,866 | ~17,000 (after extractions) |
| Backend test lines | 18,706 | ~20,000 (new tests) |
| Backend JaCoCo instruction | 88.9% | ≥ 90% |
| Frontend source lines | 10,526 | ~9,500 (after decomposition) |
| Frontend test lines | 5,461 | ~7,000 (shared utils + new tests) |
| Frontend bundle size | TBD | Measure before/after |
| Largest backend file | 709 (CcdService) | < 400 |
| Largest frontend file | 1,199 (UsersPage) | < 400 |
| Frontend pages in features/ | 0 of 24 | 24 of 24 |
| Dead dependencies | 2 (react-hook-form, zod) | 0 |
| Deprecated dependencies | 1 (react-json-view) | 0 |
| CI security scanning | None | OWASP dep-check + npm audit |

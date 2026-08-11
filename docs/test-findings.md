# Test Findings — OpenVPN Management Panel

Living log of issues found during live E2E testing of the management panel, together with
their root cause and remediation. Companion to `docs/test-plan.md` and `TODO.md`. Update
this file whenever a new issue is confirmed or one is fixed (mark it `[FIXED]` with a link
to the commit).

Environment tested: staging host `65.21.108.250`, `docker compose up` deployment, OpenVPN
Community **2.6.20** (`x86_64-alpine-linux-musl`), backend Java 21 + Spring Boot 3.5.
Tests executed as live HTTP/UI probes against the running stack.

Legend: `[CRIT]` breaks core functionality, `[HIGH]` degrades a feature, `[MED]` edge
case with wrong status code / masking, `[LOW]` polish / hardening.

---

## 1. Findings summary

| # | Severity | Title | Area |
|---|----------|-------|------|
| 1 | CRIT | `client-cert-not-required` is a removed OpenVPN 2.6 option | config generator |
| 2 | CRIT | Entrypoint config watcher dies on first failed daemon start | openvpn entrypoint |
| 3 | HIGH | OpenVPN container healthcheck masks multi-daemon failure | openvpn Dockerfile |
| 4 | MED | Profile-token create with `null` userId returns 500 | profile API |
| 5 | MED | Unknown `/api/**` path returns 500 instead of 404 | error handling |
| 6 | LOW | MFA pre-auth token is replayable | authentication |
| 7 | LOW | Access rule `dstCidr` accepts malformed CIDR | access rules |
| 8 | MED | Session rows stay "Active" after daemon restart (stale connection logs) | connection logs |
| 9 | LOW | 401 console noise from background polls before silent token refresh | frontend auth |
| 10 | LOW | Empty `#root` flash when loading `/login` after a hard reload | frontend router |
| 11 | LOW | A11y: form field without id/name attribute | frontend |
| 12 | LOW | Pre-fix invalid access rule remains in production data | data hygiene |

---

## 2. Findings detail

### 2.1 CRIT — `client-cert-not-required` is a removed OpenVPN 2.6 option

**Location**
- `backend/src/main/java/com/opnl/vpn/network/ServerConfigGenerator.java:53`
  ```java
  .replace("__CLIENT_CERT_NOT_REQUIRED__",
      config.clientCertNotRequired() ? "client-cert-not-required" : "")
  ```
- Template placeholder: `backend/src/main/resources/templates/daemon.conf:9`
  (`__CLIENT_CERT_NOT_REQUIRED__`). The correct option `__VERIFY_CLIENT_CERT__`
  (`verify-client-cert none`) is already emitted at line 22.

**Evidence** (OpenVPN 2.6.20 log):
```
2026-08-10 19:59:50 REMOVED OPTION: --client-cert-not-required,
  use '--verify-client-cert none' instead
2026-08-10 19:59:50 Exiting due to fatal error
```

**Root cause** OpenVPN 2.6 removed `client-cert-not-required` (replaced by
`verify-client-cert none`). The generator still writes the legacy option for GENERIC
(credentials-only) daemons, which aborts the daemon at startup.

**Impact** Any GENERIC daemon (or any daemon with `clientCertNotRequired=true`) exits with
a fatal error and never accepts connections.

**Fix**
1. Delete the `__CLIENT_CERT_NOT_REQUIRED__` placeholder from `daemon.conf` (line 9) and
   remove the `.replace(...)` block in `ServerConfigGenerator`.
2. Keep `verify-client-cert none` (already produced by `__VERIFY_CLIENT_CERT__`) as the
   single source of truth for cert-less daemons.
3. Update `ServerConfigGeneratorTest` so it asserts GENERIC daemons render
   `verify-client-cert none` and no longer contain `client-cert-not-required`.

---

### 2.2 CRIT — Entrypoint config watcher dies on first failed daemon start

**Location** `openvpn/entrypoint.sh`
- `set -e` at line 7.
- Watcher subshell (lines 84–93):
  ```bash
  (
    last_sig="$boot_sig"
    while :; do
      cur="$(conf_sig)"
      if [ -n "$cur" ] && [ "$cur" != "$last_sig" ]; then
        echo "[entrypoint] daemon config changed; restarting daemons"
        restart_all
        last_sig="$cur"
      fi
      sleep 2
    done
  ) &
  ```
- `restart_all` (lines 58–69) calls `start_daemon` (lines 36–47), which runs
  `openvpn --config ...` and lets its non-zero exit propagate.

**Evidence** After the 2.1 fatal error at 19:59:50, the container never again logged
`daemon config changed` even though new configs (`daemon-2.conf`, `daemon-3.conf`) were
written by the backend; only daemon-0 was running and only `daemon-0.pid` existed. The
watcher subshell was dead.

**Root cause** Inside the subshell, `restart_all` → `start_daemon` runs `openvpn` as a
plain command. Under `set -e` a non-zero exit from the first failing daemon aborts the
subshell immediately, killing the watcher loop. There is no `set +e`, `|| true`, or
`trap` around it.

**Impact** After any daemon fails to start once, config reloads stop permanently. The
backend has no restart/USR1 call, so the watcher is the only reload path (`trap
'restart_all' USR1` exists but nothing sends USR1). Result: edited/added daemons are never
picked up until the container is recreated.

**Fix**
1. Do not let a failing daemon kill the watcher. For example run each daemon in a
   conditional context, or disable errexit inside the loop:
   ```bash
   start_daemon() {
     ...
     openvpn --config "$conf" --daemon "$name" ... || {
       echo "[entrypoint] ERROR starting $name (config: $conf)" >&2
       return 1
     }
   }
   ```
   and in `restart_all` call `start_daemon "$conf" || true`.
2. Consider a bounded retry with backoff so a temporarily bad config recovers when the
   backend rewrites it.
3. Add a `set +e`/`set -e` guard around the watcher subshell body, or `trap - ERR EXIT`.
4. Regression check: start the stack with a bad daemon config, then fix the config via
   the API and assert the daemon comes up without a container restart.

---

### 2.3 HIGH — OpenVPN container healthcheck masks multi-daemon failure

**Location** `openvpn/Dockerfile:38`
```bash
HEALTHCHECK CMD bash -c 'if [ -n "$(ls "$OPNL_CONFIG_DIR"/daemon-*.conf 2>/dev/null)" ]; \
  then pgrep -f "openvpn --config.*daemon" >/dev/null || exit 1; fi; exit 0'
```

**Evidence** With findings 2.1 + 2.2 present (only daemon-0 running, daemon-2/3 down) the
container still reported `healthy`.

**Root cause** The check only asserts that *at least one* openvpn process matching
`--config.*daemon` exists. It does not compare expected configs to running processes or
pidfiles.

**Impact** Operators and `depends_on` conditions believe the VPN is fully operational
while some daemons (and therefore whole access modes, e.g. GENERIC) are down.

**Fix**
1. Compare every `daemon-*.conf` with a live pidfile:
   ```bash
   ok=1
   for conf in "$OPNL_CONFIG_DIR"/daemon-*.conf; do
     name="$(basename "$conf" .conf)"
     pidfile="$OPNL_LOG_DIR/$name.pid"
     if [ ! -f "$pidfile" ] || ! kill -0 "$(cat "$pidfile")" 2>/dev/null; then ok=0; fi
   done
   [ "$ok" -eq 1 ]
   ```
2. Guard against stale pidfiles (check `/proc/<pid>/cmdline` contains the daemon name).
3. Keep the "no configs yet" branch (pre-wizard) as healthy.

---

### 2.4 MED — Profile-token create with `null` userId returns 500

**Location**
- `backend/src/main/java/com/opnl/vpn/api/admin/ProfileAdminController.java:77`
  ```java
  public record CreateTokenRequest(
      String userId, @NotNull ProfileType profileType, Instant expiresAt, Integer usesLeft) {}
  ```
  → `userId` has no `@NotBlank`.
- `backend/src/main/java/com/opnl/vpn/profile/ProfileService.java` `createToken(...)`
  → `requireUser(userId)` → `userRepository.findById(null)`.

**Evidence** `POST /api/admin/profile-tokens` with `{"profileType":"USER_LOCKED"}`
returns `500 {"code":"internal_error"}`. A non-null bogus UUID correctly returns
`404 user_not_found`.

**Root cause** `findById(null)` throws Spring Data's
`InvalidDataAccessApiUsageException` ("The given id must not be null"), which is not
handled by `GlobalExceptionHandler` (only plain `IllegalArgumentException` is), so it
falls into the catch-all `Exception` handler → 500.

**Impact** Client-side bugs produce an unhelpful 500 instead of a 400/404.

**Fix**
1. Add `@NotBlank` to `CreateTokenRequest.userId` when the type is not GENERIC
   (GENERIC legitimately carries no user).
2. As defense-in-depth, handle `InvalidDataAccessApiUsageException` (and generally
   `DataAccessException`) in `GlobalExceptionHandler` as a 400/500-class mapping, or
   null-check `userId` in `requireUser`.
3. Add a service/controller test for missing `userId` → 400 (or 404) and bogus UUID → 404.

---

### 2.5 MED — Unknown `/api/**` path returns 500 instead of 404

**Location** `backend/src/main/java/com/opnl/vpn/common/GlobalExceptionHandler.java`
(no handler for `NoResourceFoundException`; falls through to `Exception` → 500).

**Evidence** `GET /api/admin/status` → `500 {"code":"internal_error"}` with
`NoResourceFoundException: No static resource api/admin/status.` in the backend log.

**Related dead endpoints** `frontend/src/lib/api.ts` defines `status: "/admin/status"`,
`settings: "/admin/settings"`, `dashboard: "/admin/dashboard"` but no backend controller
exists for any of them and no page calls them (the `/status` and `/settings` routes render
`PlaceholderPage`). They are either Phase 4 placeholders or dead config.

**Impact** Typos/unknown admin paths surface as 500s; monitoring may treat them as real
errors.

**Fix**
1. Add to `GlobalExceptionHandler`:
   ```java
   @ExceptionHandler(NoResourceFoundException.class)
   public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
     return ResponseEntity.status(HttpStatus.NOT_FOUND)
         .body(ApiError.of(404, "not_found", "Resource not found"));
   }
   ```
2. Decide on `/admin/status`, `/admin/settings`, `/admin/dashboard`: implement them or
   remove the entries from `api.ts`.

---

### 2.6 LOW — MFA pre-auth token is replayable

**Location** `backend/src/main/java/com/opnl/vpn/auth/AuthService.java` `mfa(...)`
- Parses `preAuthToken` (a JWT with an `mfa` claim type), checks `isMfaChallenge(claims)`
  and expiry only; there is no one-time-use revocation.

**Evidence** The same `preAuthToken` redeemed twice (each time with a freshly generated
valid TOTP code) returned two fresh token pairs, both `200`.

**Root cause** The challenge token is validated purely by signature + expiry; nothing marks
it as consumed.

**Impact** Limited in practice — each replay still requires a *new valid* TOTP code within
its time window — but challenge tokens are not single-use as the API contract implies.

**Fix**
1. Track redeemed challenge `jti`s (e.g. a `used_challenge` table or short-TTL cache) and
   reject `mfa()` calls for an already-consumed token.
2. Keep the challenge TTL short (seconds-to-minutes) as a second line of defense.
3. Add an `AuthServiceTest` case: successful redeem, then re-redeem with same
   preAuthToken + fresh code → rejected.

---

### 2.7 LOW — Access rule `dstCidr` accepts malformed CIDR

**Location**
- `backend/src/main/java/com/opnl/vpn/access/AccessRuleDto.java` — `dstCidr` has no
  format validation (only `@NotNull` on `targetType`/`action`).
- `backend/src/main/java/com/opnl/vpn/access/AccessRuleService.java` `apply(...)` — sets
  `dstCidr` blindly.

**Evidence** `POST /api/admin/rules` with `"dstCidr":"not-a-cidr"` returned `200` and
persisted the rule.

**Impact** A malformed rule is stored; `RuleEngine.iptablesFor` may emit broken `-s/-d`
matches or fail at connect time, affecting real traffic.

**Fix**
1. Validate `dstCidr` format in the DTO (e.g. `@Pattern(regexp =
   "^\\d{1,3}(\\.\\d{1,3}){3}/(3[0-2]|[0-2]?[0-9])$")`) or in `AccessRuleService.apply`.
2. Optionally reject `dstCidr` when `protocol` is null (port/CIDR semantics).
3. Add a service test for malformed CIDR → 400 `validation_failed`.

---

### 2.8 MED — Session rows stay "Active" after daemon restart (stale connection logs)

**Location**
- `backend/src/main/java/com/opnl/vpn/monitor/ConnectionLogService.java` `sessionEnded(...)`
  (and the `client-disconnect.sh` → `/internal/disconnect` callback path).
- `frontend/src/pages/StatusPage.tsx` "Recent sessions" table rendering.

**Evidence** The status page "Recent sessions" list showed an entry still marked
"Active" with 0s duration while no client was connected at that moment.

**Root cause** A session is only closed when the `client-disconnect` callback fires
(`/internal/disconnect`). When a daemon restarts (SIGUSR1 from the config watcher, crash,
or container restart) or the backend is briefly unreachable at disconnect time, the
callback is never delivered, so the `connection_logs` row keeps `disconnected_at = NULL`
and the UI renders it as "Active" forever.

**Impact** Misleading status page (an operator may believe a client is connected);
session-history statistics are wrong after any daemon restart.

**Fix**
1. Reconciliation: on each management-interface status poll (or on daemon startup), close
   any open `connection_logs` rows for sessions no longer present in the live `status 3`
   client list — treat "gone from the daemon view" as ended.
2. Alternatively/also: close open rows for a daemon when its config is (re)written or its
   process restarts (findings 2.2/2.3 touch this lifecycle).
3. Add a unit test: open session row + stale snapshot → row closed; and an integration
   test for disconnect-callback-vs-restart ordering.

---

### 2.9 LOW — 401 console noise from background polls before silent token refresh — [FIXED]

**Location**
- `frontend/src/lib/api.ts` (axios instance / auth interceptors, refresh logic).
- `frontend/src/hooks/useLiveStatus.ts` (polling every 10s).

**Evidence** 13× `401` responses (console `Failed to load resource: the server responded
with a status of 401`) clustered right after the 10-minute access-token expiry, each
paired with a status poll; the subsequent refresh-token call then succeeded and the UI
recovered. Functional impact nil — the refresh flow works — but the console is noisy and
real auth failures can get drowned out.

**Root cause** Background polls continue to use the expired access token; the refresh
only happens reactively on the first 401 (or when a page explicitly re-authenticates),
so every in-flight poll in the expiry window returns 401.

**Fix**
1. Proactive refresh: refresh the access token shortly before expiry. `api.ts` now decodes
   the JWT `exp` claim and schedules a silent refresh at 80% of the TTL via a module-level
   timer, re-arming itself after each rotation (`tokenStore.set` / `tokenStore.clear`
   schedule/cancel the timer). The 401 retry-once path stays as a safety net and shares a
   single in-flight refresh promise with the proactive timer.
2. Frontend test (`src/lib/api.test.ts`): fake timers + mocked refresh — the timer fires
   before expiry, rotates the token, and a subsequent poll uses the fresh bearer token
   without any 401.

---

### 2.10 LOW — Empty `#root` flash when loading `/login` after a hard reload — [FIXED]

**Location** `frontend/index.html` (empty `<div id="root">` between HTML paint and module
script execution).

**Evidence** After signing out and hard-reloading `/login`, the page rendered an empty
`#root` once (blank screen); a second manual reload rendered the login page correctly.
No console error accompanied it.

**Root cause** Suspected race in the router/lazy-route hydration on the first mount after
logout state change (likely a route chunk or query-client hydration ordering issue).

**Fix**
1. Reproduce deterministically (logout → reload `/login` several times), then inspect the
   router setup for a missing `Suspense`/splash fallback while lazy chunks load.
2. Ensure the query client / auth-state provider is mounted before routes render.
3. Add a test if reproducible in jsdom; otherwise a manual regression step in section 4.

**Resolution** Root cause is the render gap inherent to a `<div id="root">` that is empty
in the HTML: the browser paints the blank root before the deferred module bundle executes
(no lazy routes exist, so no Suspense gap — the flash is the pre-React paint). `index.html`
now embeds a static dark splash (spinner + label) inside `#root`, which React replaces on
mount. Manual regression step added in section 4.

---

### 2.11 LOW — A11y: form field without id/name attribute — [FIXED]

**Location** Frontend — one form control in the login flow flagged by an automated a11y
snapshot ("A form field element should have an id or name attribute").

**Evidence** Chrome DevTools a11y snapshot of the login page reported exactly one form
field missing `id`/`name`; no functional impact.

**Fix**
1. Add `id`/`name` (or `aria-label`) to the flagged control, and verify with a fresh a11y
   snapshot that the count drops to zero.
2. Frontend test: login form renders with all controls labelled.

**Resolution** All login-flow controls now carry explicit `id` + `name` attributes:
`LoginPage` (`username`, `password`) and `MfaLoginPage` (`code`). Regression tests in
`LoginPage.test.tsx` and `MfaLoginPage.test.tsx` assert `id`/`name` on every control.

---

### 2.12 LOW — Pre-fix invalid access rule remains in production data

**Location** `access_rules` table (production DB) / Access Rules grid (frontend).

**Evidence** The Access Rules grid still shows a rule with destination
`not-a-cidr:443`, created before finding 2.7's validation landed. Finding 2.7's DTO
validation now blocks *new* malformed CIDRs, but the legacy row persists, so the grid
surface is not clean.

**Root cause** Data created while finding 2.7 was open was never cleaned up.

**Impact** None functional (RuleEngine tolerates it), but it pollutes demo/screenshot
surfaces and keeps the invalid value visible to admins.

**Fix**
1. Delete the legacy rule via the admin API (`DELETE /api/admin/rules/{id}`) or a one-off
   SQL cleanup on the staging DB.
2. Add a regression step (section 4): re-create `not-a-cidr` → must be rejected 400
   (confirms finding 2.7 holds); the only way a bad CIDR exists is via pre-validation data.

---

## 3. Verified non-issues and API notes

Recorded during testing so they are not re-reported as bugs:

- **User enable/disable**: the admin UI uses `POST /api/admin/users/{id}/ban|unban`
  (column `banned`), not `/{id}/enabled`. There is no `/{id}/enabled` on
  `UserAdminController` — this is by design (`DaemonAdminController` and
  `AccessRuleAdminController` have `/{id}/enabled`).
- **Access rule priority**: `priority` in `AccessRuleDto` is ignored on create
  (`nextPriority()` auto-assigns max+1) and not changed on update. The frontend has no
  priority editor (read-only grid column) and documents "rules apply in priority order
  (lowest first)". Behavior is internally consistent, but the DTO field is misleading —
  either document it as read-only/auto, or honor it on create/update.
- **User static IP** must be inside the primary VPN subnet; out-of-range returns
  `invalid_static_ip` and cross-user duplicates return `static_ip_in_use`. Correct.
- **Cert re-issue** for a user with a VALID cert is idempotent (returns the existing
  cert); revoking twice returns `already_revoked`. Revocation is persisted to
  `index.txt`, `crl.pem`, and `revoked/certs_by_serial/`. Correct.
- **`DaemonService.writeAll`** appears only in `delete`/`setEnabled` in the source tree,
  yet the running deployment writes `daemon-*.conf` on create/update. Confirm the
  deployed jar matches the tree (see `git log -S "writeAll"`); reconcile if needed.
- **E2E data**: share-token `usesLeft` is enforced (`token_exhausted` 409), unknown token
  → `token_not_found` 404, MFA wrong code → `invalid_code` 401, unknown admin API path →
  see finding 2.5.

---

## 4. Verification commands

Findings were reproduced on the staging host:

```bash
docker logs --tail 200 opnl-backend                      # backend errors / stack traces
docker logs --tail 50  opnl-openvpn                      # openvpn fatal errors
ls -la /var/lib/docker/volumes/opnl_opnl-config/_data/   # daemon-*.conf vs running pids
docker exec opnl-openvpn bash -c 'pgrep -af openvpn'      # which daemons are actually up
ls /var/lib/opnl/pki ...                                 # cert issuance / CRL state
```

Suggested regression run after fixes:
1. Create a GENERIC daemon (finding 2.1) → assert it starts and `verify-client-cert none`
   is in its config.
2. Create/edit a daemon config while the stack is up → assert the watcher logs
   `daemon config changed` and the daemon restarts (finding 2.2).
3. Stop one daemon deliberately → assert the container healthcheck flips to
   `unhealthy` (finding 2.3).
4. `POST /api/admin/profile-tokens` without `userId` → 400/404, not 500 (finding 2.4).
5. `GET /api/admin/nonexistent` → 404, not 500 (finding 2.5).
6. Redeem an MFA preAuthToken twice → second call rejected (finding 2.6).
7. Create a rule with `dstCidr=not-a-cidr` → 400 (finding 2.7).
8. Kill a client's daemon connection mid-session (or restart a daemon) → the open
   session row must close / disappear from "Active" on the next poll (finding 2.8).
9. Cross the access-token expiry boundary while a status poll is running → no 401
   bursts in the console (finding 2.9).
10. Logout → hard reload `/login` repeatedly → always renders the login page (or the
    inline splash during load), never an empty `#root` (finding 2.10).
11. Run an a11y snapshot on the login page → zero "form field without id/name" issues
    (finding 2.11).
12. `GET /api/admin/rules` → no `not-a-cidr` rows remain; `POST` with `not-a-cidr` → 400
    (finding 2.12 / 2.7).

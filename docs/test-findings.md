# Test Findings — OpenVPN Management Panel

Living log of issues found during live E2E testing of the management panel, together with
their root cause and remediation. Companion to `docs/test-plan.md` and `TODO.md`. Update
this file whenever a new issue is confirmed or one is fixed (mark it `[FIXED]` with a link
to the commit).

Environment tested: staging host `65.21.108.250`, `docker compose up` deployment, OpenVPN
Community **2.6.20** (`x86_64-alpine-linux-musl`), backend Java 25 + Spring Boot 3.5.
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
| 13 | MED | Demo-seeded cert rows have no backing PKI files | demo mode |
| 14 | HIGH | Full-tunnel VPN client on the server host black-holes host routing | operational |
| 15 | HIGH | Cert restore flips the index to VALID without restoring on-disk artifacts | PKI / Easy-RSA | **FIXED** `9005aa2` |
| 16 | HIGH | Web-login lockout never triggers (`@Transactional` rollback) | authentication | **FIXED** `66d97c0` |

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

### 2.13 MED — Demo-seeded certificate rows have no backing PKI files

**Location** `system/DemoSeedService` (demo cert rows) + `profile/ProfileService`
(`downloadForUser` → cert lookup).

**Repro** Fresh install → load demo data → `GET /api/admin/users/{alice}/profiles/USER_LOCKED/download`
→ `404 pki_missing: issued/alice.crt`.

**Root cause** Demo mode creates `Certificate` rows (VALID/REVOKED) without issuing real
Easy-RSA artifacts, and profile download reads the physical `issued/<cn>.crt`. By design
(the demo dialog states "Real client certificates are not issued"), but the resulting
`pki_missing` error is misleading.

**Impact** Demo users cannot download connection profiles; a fresh real user downloads a
working profile (verified: real Easy-RSA cert issued, valid `.ovpn` with the configured
`remote` endpoint).

**Fix** `[FIXED]` — `CertService.ensureUserCert` now issues the real artifact on demand
when a VALID bookkeeping row has no backing file (refreshing serial/expiry/issued-at from
the Easy-RSA index), so demo users' profiles download with real certs. REVOKED demo rows
fall through to the normal "issue a fresh cert" path, matching real re-issue behavior.

### 2.14 HIGH — Full-tunnel VPN client on the server host black-holes host routing

**Location** OpenVPN client `redirect-gateway` (full-tunnel profile) + host deployment.

**Repro** Start the full-tunnel `.ovpn` client on the same host that runs the VPN server.
After connect, ALL of the host's outbound traffic (SSH responses, HTTP/panel, ICMP) is
routed into the `tun`, which does not carry it back → the host becomes unreachable from
the network (SSH, ping, ports 80/8080 all time out). Recovery required out-of-band access
(Hetzner console: reboot the host or `pkill -f "openvpn --config /tmp/e2e.ovpn"` — tunnel
routes are removed on process exit; the client is not persisted across reboot).

**Root cause** The full-tunnel profile installs default routes via the tunnel on the
client, and the server host's own egress is not exempted, so the host's replies to
inbound connections vanish into the tunnel.

**Impact** Self-hosting foot-gun: any admin running the client on the VPN server host
locks themselves out. Not a defect in tunnel functionality for normal clients on separate
hosts.

**Fix** Document explicitly: "do not run a VPN client on the same host as the VPN server."
For the E2E harness, run the connect test from a separate client host/VM. Consider, as
future hardening, pushing a `route-nopull`-style warning when the client's local address
equals the server host.

**Resolved (docs)** — `[FIXED]` README "Operational note" and `docs/architecture.md`
§8 now warn against running a full-tunnel client on the server host.

---

### 2.15 HIGH — Cert restore flips the index to VALID without restoring on-disk artifacts

**Location**
- `backend/src/main/java/com/opnl/vpn/pki/CertService.java` (`unrevokeCert` / `restore`)
- `backend/src/main/java/com/opnl/vpn/pki/EasyRsaService.java`

**Repro** (live staging, OpenVPN 2.6.20 stack):
```
issue → revoke → restore (200, status back to VALID)
then rotate  → HTTP 500 {"code":"pki_command", "message":"Unable to revoke as no
                certificate was found"}
also: restore → revoke → HTTP 500 pki_command (same message)
```

**Root cause** `restore()` flips the `index.txt` status byte V and regenerates the CRL,
but does not restore the physical artifacts (`issued/<cn>.crt`, `private/<cn>.key`) that
Easy-RSA needs for `revoke`/`renew`. After a revoke those files are gone (moved to
`revoked/certs_by_serial/`), so any subsequent `revoke`/`rotate` cannot find the cert and
fails with a `pki_command` 500.

**Impact** An admin who restores a revoked certificate to re-enable a user cannot then
revoke or rotate it; the certificate's lifecycle is stuck. The status reads VALID but the
artifact is unusable — a silent data/artifact mismatch.

**Fix**
1. On `restore()`, also copy the revoked artifact back from
   `revoked/certs_by_serial/<serial>/` (or regenerate from the index) into
   `issued/` + `private/` before flipping the index, and re-sign/restore the `ta.key` CRL
   state consistently.
2. Fail fast (4xx with a clear message) if the revoked artifact directory is missing
   instead of producing a half-VALID row.
3. Add a `CertServiceTest` regression: issue → revoke → restore → rotate must succeed,
   and restore → revoke must succeed.

**Resolved** `9005aa2` + `eec3949`. `EasyRsaService.unrevokeCert` now captures the restored
serial and CN from the matching `index.txt` row and restores the artifacts **before** writing the
index: the cert is copied back from `certs_by_serial/<serial>.pem` (fallback:
`revoked/certs_by_serial/<serial>/<cn>.crt`) to `issued/<cn>.crt` (mandatory) and the key
best-effort from `revoked/private_by_serial/<serial>.key` to `private/<cn>.key`. Missing
cert artifact now fails fast with `pki_missing` (404) and leaves the index untouched.
`eec3949` additionally removes the Easy-RSA 3.2.x revoked-archive leftovers
(`revoked/certs_by_serial/<serial>.crt`, `revoked/private_by_serial/<serial>.key`,
`revoked/reqs_by_serial/<serial>.req`) after restoring, otherwise a later revoke/rotate
aborts with "Cannot revoke this certificate, a conflicting file exists". Live-verified on
staging (M8): rotate and re-revoke after restore both return 200.
Regressions: `unrevokeCertRestoresIssuedCertAndPrivateKey`,
`unrevokeCertThrowsWhenRevokedArtifactMissing`, legacy-layout restore, and
`CertServiceTest` restore→rotate and restore→revoke.

---

### 2.16 HIGH — Web-login lockout never triggers (`@Transactional` rollback)

**Location** `backend/src/main/java/com/opnl/vpn/auth/AuthService.java` — `login(...)`
(e.g. `@Transactional` method that throws `ApiException` after `recordFailure(...)` and
the `LOGIN_FAILED` audit write).

**Repro** (live staging): 5+ wrong passwords for the same user via
`POST /api/auth/login`:
- `failed_attempts` in `users` stays 0, `locked_until` never set — login lockout never
  engages (`lockoutMaxAttempts: 5`).
- `audit_logs` contains 0 `LOGIN_FAILED` entries despite many failed attempts.

**Contrast (working path)** `/internal/auth/verify` (`verifyVpnLogin`) is
**non-transactional** and persists `failed_attempts`/`locked_until` correctly, so the
VPN auth path does lock accounts. Only the web login path is broken.

**Root cause** On a failed login the method calls `recordFailure(...)` and writes the
audit event, then throws `ApiException` (a `RuntimeException`). Spring rolls the whole
transaction back, discarding both the attempt counter and the audit row — so every failed
attempt starts from zero and no lockout is ever reached.

**Impact** Brute-force protection on the web login is effectively disabled; the
`LOGIN_FAILED` audit trail is missing, so security monitoring cannot see login
brute-force attempts.

**Fix**
1. Do not throw from the transactional method after recording the failure: split login
   into a non-transactional facade that commits the failure record (and audit) before
   returning the error to the controller, or
2. persist failure + audit in a separate `REQUIRES_NEW`/non-transactional component called
   before the exception path.
3. Add an `AuthServiceTest` regression: N failed web logins increment `failed_attempts`,
   write `LOGIN_FAILED` audit rows, and at the threshold set `locked_until`; the
   subsequent attempt is rejected with a locked reason.

**Resolved** `66d97c0`. New `AuthFailureRecorder` component persists the failure counter
and the `LOGIN_FAILED` audit row in its own `REQUIRES_NEW` transaction; `AuthService.login`
and the web MFA step call it **before** throwing the auth `ApiException`, so the record
survives the enclosing rollback. The web MFA invalid-code path now also counts toward
lockout, matching the VPN OTP path. Live-verified on staging (M8): 5 wrong web logins
persist 5 `LOGIN_FAILED` audit rows, set `locked_until`, and the next (correct-password)
attempt is rejected with `account_locked`. Regressions: `loginRejectsWrongPassword`,
`loginLocksAccountAfterMaxAttempts`, `mfaRejectsWrongCodeAndRecordsFailure`.

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

### M6 fresh-install E2E pass (2026-08-14, `install.sh --reset` on 65.21.108.250)

Verified on a clean install (images rebuilt, volumes wiped, `.env` preserved):

- **Wizard (setup state COMPLETE)**: admin step (credentials from `.env`), VPN server
  step (defaults; daemon created, `adminHost` = `OPNL_OPENVPN_ADMIN_HOST`), PKI step →
  "Certificate authority initialized." CA, `server.crt`/`server.key`, `ta.key`, CRL and
  `index.txt` all present in the PKI dir.
- **Login** with the wizard admin → Dashboard renders all nav pages + the demo-data
  button; daemon #0 (UDP 1194) listed.
- **Demo data (button)**: confirm dialog → POST → toast "Demo data loaded" → stats
  refresh (users 5, groups 2, active certs 2). API confirms users admin(ADMIN) /
  alice/bob/carol(USER) / dave(RESELLER), all 4 access rules (`/api/admin/rules`:
  GLOBAL ALLOW 10.8.0.0/24, GROUP DENY 10.0.0.0/8, USER alice ALLOW TCP
  10.8.0.5/32:22, GLOBAL ALLOW git.internal) and 2 DNS overrides
  (`git.internal→10.8.0.5` GLOBAL, `docs.internal→10.8.0.6` GROUP).
- **Profile generation**: `GET /api/admin/users/{id}/profiles/USER_LOCKED/download`
  issues a real Easy-RSA cert and returns a valid `.ovpn` (JSON `{filename, content}` —
  note the API returns a JSON body, not a raw file download). The `remote` line matches
  the daemon's corrected `adminHost`.
- **VPN connect flow (verified end-to-end from a *separate* client)**: fresh user `e2e`
  → real cert issued → client connects over UDP 1194 with TLS + user/pass auth (verify
  via backend `/internal/auth`) → virtual IP `10.8.0.2` assigned, `AES-256-GCM` data
  channel, PUSH_REPLY carries `dhcp-option DNS 10.8.0.1`, `redirect-gateway`, and
  `ping-restart 120`; session appears in `/api/admin/connections` (username / virtual IP /
  daemon / connectedAt) and in the daemon status file with byte counters (RX/TX rising).
  DNS overrides resolve through the tunnel (`git.internal→10.8.0.5`,
  `docs.internal→10.8.0.6`) and upstream resolution works (`google.com→216.58.x.x`).
  Firewall enforcement confirmed: because GLOBAL access rules exist, *every* user gets a
  default-deny chain — `wget http://ipv4.icanhazip.com` through the tunnel times out
  (blocked), while DNS (dport 53) and the allowed VPN-internal/git.internal flows are
  ACCEPTed. After an abrupt client kill, the server's inactivity timeout
  (`--ping-restart`; note the server doubles the client's 120 to **240s**) fires
  `client-disconnect`, the backend session clears (`/api/admin/connections` → 0 active),
  and the per-client iptables chain is removed (only the base `OPNL_DOMAINS` chain
  remains).
- **Connect-test caveat**: use a separate host/VM or a disposable `docker run
  --cap-add=NET_ADMIN --device /dev/net/tun` client container, never the server host
  itself (finding 2.14). The generated `.ovpn` `auth-user-pass` prompt requires an
  external `--auth-user-pass <file>` for headless use; `--daemon` must not be used as a
  container entrypoint (parent exits → container stops).
- **Test-harness caveats**: the wizard admin-host field got a *mangled* value
  (`vpn.example.com65.21.108.250`) because the automation `fill` appended to the MUI
  input; the daemon entity retained `vpn.example.com`. Fixed via
  `PUT /api/admin/daemons/{id}` (`adminHost: 65.21.108.250`) which regenerated
  `daemon-0.conf`. Profiles use the daemon entity, so the stale
  `server_settings` JSON value is harmless. The tunnel connect test must run from a
  **separate** client host (see finding 2.14).

### M7 comprehensive live E2E pass (2026-08-16, staging 65.21.108.250)

Ran the full §6 scenario catalog of `docs/test-plan.md` against the live stack. All
scenarios `PASS` except findings F15/F16 and E2E-54 (needs an mTLS agent deployment —
unit/integration covered). Highlights:

- **Auth**: login/wrong-password, full MFA cycle (enroll→enable→login→redeem→disable),
  refresh rotation (old refresh rejected 401), logout with Authorization header then token
  reuse → 401, rate limiting (20/60 s → 429, then blocked burst of 26 → 11×429).
- **API tokens**: create (`label`)/use/list/delete; deleted token → 401.
- **RBAC**: GROUP_ADMIN sees only its group's users (API *and* UI grid); cross-group
  user/group updates → 403; own-group updates → 200.
- **Users/groups CRUD**: create/update/reset-password/login/ban→`account_banned`
  403/unban/static-ip set+clear, group create/members/delete.
- **PKI**: issue/re-issue-idempotent/revoke(+CRL)/restore/reconcile. **F15**: rotate or
  revoke after restore → 500 `pki_command`.
- **Profiles/share**: USER_LOCKED + AUTO_LOGIN `.ovpn` download (JSON `{filename,
  content}`), share-link `GET /share/{token}` public and one-time (`usesLeft=1` →
  409 on second download), QR 200, token revoke.
- **Rules/DNS/branding**: rule create/disable/re-enable/delete; DNS override create/delete
  (`ipv4` field); branding via `PUT /api/admin/settings/brand_name` +
  `GET /api/public/brand`.
- **Ops/monitoring**: status/dashboard/monitor/system/config-report/audit-logs/
  connections/daemons (`resolve` mapping SERVER_LOCKED|USER_LOCKED→`test`,
  AUTO_LOGIN|GENERIC→`Primary`), backups create/list/download, system metrics live
  (CPU/mem/disk) on the Dashboard.
- **Live VPN data-plane (real client in the openvpn container, `remote 127.0.0.1 1194`)**:
  AUTO_LOGIN profile → "Initialization Sequence Completed", TLS handshake,
  `AES-256-GCM` data channel, virtual IP `10.8.0.2` from the pool; **static IP
  `10.8.0.199`** applied to the client interface via PUSH `ifconfig`; while connected,
  `iptables -L -n` shows the per-user chain `OPNL_<hash>` (rule keyed on the static IP)
  and the `OPNL_DOMAINS` chain; connection log rows record bytes + `disconnected_at`;
  `POST /api/admin/connections/e2e_live/disconnect` (management interface) terminates the
  session (200, row closed).
- **UI (browser)**: login page renders (fresh reload) with branded name; admin login →
  Dashboard (live stat cards, daemon list, traffic/sys charts); Users grid (RBAC-scoped
  for GROUP_ADMIN), Certificates grid (issue/rotate/revoke/restore actions, status chips,
  SYNC WITH PKI), Live Status page (daemon health table, Recent sessions with durations +
  byte counters). No JS console errors; one a11y warning (form field id/name — see
  finding 2.11, still flagged count 1–2 on some pages).

### M8 live re-verification of F15 + F16 fixes (2026-08-16, staging)

Fixes deployed to the staging stack (`66d97c0`, `9005aa2`, `eec3949`) and both findings
re-run against the live API:

- **F16 (web-login lockout)** — fresh throwaway user `f16_repro`:
  - 5× wrong password via `POST /api/auth/login` → each `401 invalid_credentials`.
  - DB after the burst: `users.failed_attempts = 0` (reset at threshold) and
    `users.locked_until` set (epoch millis).
  - `audit_logs` holds exactly **5 `LOGIN_FAILED`** rows for that user (`detail`
    contains `username` + `remoteIp`), committed despite the thrown exception.
  - 6th attempt with the **correct** password → `401 account_locked`. Lockout engages.
- **F15 (restore artifacts / re-revoke)** — fresh throwaway user `f15_repro`:
  - issue → `VALID`; revoke → `REVOKED`; restore → `VALID`;
  - **rotate → HTTP 200** `VALID` with a new serial (previously 500
    `pki_command` "no certificate was found");
  - restore → revoke again → **HTTP 200** `REVOKED` (previously 500
    "conflicting file exists", the Easy-RSA 3.2.x revoked archive leftover removed by
    `eec3949`).

**Minor note (data hygiene, not blocking):** the admin DELETE user endpoint
(`DeleteOptions.none()` by default, `UserAdminService.deleteUser`) removes the user but
leaves the `certificates` row orphaned (its `common_name` is UNIQUE), so re-creating a
user with the same username then fails cert issue with a SQLite unique-constraint 500
(`internal_error`). Workaround: pass the cleanup flag (UI checkbox) or clear the orphan
row. Out of scope for F15/F16; tracked as low-priority.

### M9 live re-verification of the orphan-cert fix (2026-08-16, staging)

Fix deployed to staging (`4df2588`) and re-run against the live API with a fresh user
`orphan_repro`:

- create user → issue cert (`POST /api/admin/certs`, `VALID`, serial `93084A…`)
- `DELETE /api/admin/users/{id}` with **no body** (i.e. no cleanup flags) → `200`
- `certificates` rows for that `user_id` removed from the DB
- re-create `orphan_repro` → re-issue cert → **HTTP 200** (previously 500
  `internal_error` unique-constraint). The on-disk PKI artifact is kept on the no-flag
  path and the existing cert is reused, so the serial matches — expected.

Root cause had two halves, both fixed in `4df2588`:

1. `UserAdminService.deleteUser` only purged certificate rows when
   `DeleteOptions.deleteCertificates()` was set; it now always calls
   `CertService.deleteRowsForUser(id)` (a bulk `deleteByUserId`) and the flag only
   controls the PKI artifact purge via `purgeForUser`.
2. `CertService.ensureUserCert` (via `purgeStaleForCommonName`) tried to delete the
   stale same-CN row with a deferred entity delete that Hibernate flushes *after* the
   new row's INSERT within the same transaction — tripping the UNIQUE constraint even
   when the row was deleted. Replaced with an immediate bulk delete
   `deleteByCommonNameAndUserIdNot`.

Unit tests: `UserAdminServiceTest.deleteUserWithoutOptionsStillRemovesCertificateRows`,
`CertServiceTest.ensureUserCertPurgesStaleCertFromDeletedAccountWithSameName` (now
verifies the bulk delete) and `CertServiceTest.deleteRowsForUserRemovesBookkeepingWithoutPkiPurge`.

### M10 UX simplification: user delete always purges the PKI (2026-08-16)

The leftover two-level behavior (DB rows always removed, PKI purge opt-in via the
`deleteCertificates` checkbox) was confusing: with the checkbox off, a deleted user's
certificate stayed **VALID** in the PKI index with its on-disk artifacts intact, so a
stolen profile could still authenticate against a cert-only (AUTO_LOGIN) daemon. Decision:
**deleting a user always revokes + purges the certificate**; the checkbox is removed.

- `UserAdminService.DeleteOptions` now has only `deleteAccessRules` + `clearCcd`;
  `deleteUser` always calls `certService.purgeForUser(id)`.
- `CertService.deleteRowsForUser` / `CertificateRepository.deleteByUserId` removed.
- Frontend delete dialog no longer offers a certificates checkbox and states the
  certificate is revoked and removed. "Deactivate but keep the cert" remains available
  via the separate ban/disable action (which does not purge).
- `docs/api.md` DeleteOptions updated; existing clients sending the removed
  `deleteCertificates` field are unaffected (unknown JSON fields are ignored and the
  behavior is now always-purge anyway).

Live-verified on staging (`d984b7f`): create user → issue cert (serial
`9A164E9D…`) → `DELETE /api/admin/users/{id}` with an empty body → the PKI index
entry flips `V → R` (REVOKED), `issued/<cn>.crt` and `private/<cn>.key` are
deleted from disk, the certificate row is removed, and a `CERT_PURGE` audit row
(`{"count":1}`) is written. Recreating the same username then issues a **new**
certificate (`B670411C…`, HTTP 200), proving no stale artifact is reused. UI:
the delete dialog now shows only "Delete access rules" and "Clear static IP"
checkboxes with the text "This cannot be undone. The user's certificate is
revoked and removed."

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
13. Issue → revoke → restore → rotate a cert; and issue → revoke → restore → revoke: both
    must succeed without a `pki_command` 500 (finding 2.15).
14. N failed web logins (`POST /api/auth/login` wrong password) → `failed_attempts`
    increments, `LOGIN_FAILED` audit rows appear, and at the threshold the account gets
    `locked_until` and further attempts are rejected (finding 2.16).

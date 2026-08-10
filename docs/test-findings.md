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

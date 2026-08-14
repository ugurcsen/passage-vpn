# API Reference — OpenVPN Panel

> Generated from the live OpenAPI document. Endpoints under `/api/admin/**`
> require an `ADMIN` (or scoped `RESELLER`) role; `/api/portal/**` endpoints are
> self-service. `docs/api.md` is regenerated with `make api-docs`.

- **Version**: v1
- **Base URL**: `http://localhost:8080`

## Authentication

- **bearerAuth** (`http`): `bearer` — Paste the access token returned by /api/auth/login (or /api/auth/mfa) as Bearer <token>.

Login via `POST /api/auth/login` (or `/api/auth/mfa`) and pass the returned
access token as `Authorization: Bearer <token>`. Automation can instead use an
API token as `X-API-Token: opnl_...` (see the Admin - API tokens endpoints).

## Endpoints

### `GET /api/admin/api-tokens`

**Tags**: Admin - API tokens

**Responses**: 200

---

### `POST /api/admin/api-tokens`

**Tags**: Admin - API tokens

**Request body**: `CreateRequest`

**Responses**: 200

---

### `DELETE /api/admin/api-tokens/{id}`

**Tags**: Admin - API tokens

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `GET /api/admin/audit-logs`

**Tags**: Admin - Audit Logs

| Parameter | In | Type | Required |
|---|---|---|---|
| `page` | query | integer | no |
| `size` | query | integer | no |
| `action` | query | string | no |
| `actor` | query | string | no |
| `from` | query | string | no |
| `to` | query | string | no |

**Responses**: 200

---

### `GET /api/admin/backups`

**Tags**: Admin - Backups

**Responses**: 200

---

### `POST /api/admin/backups`

**Tags**: Admin - Backups

**Responses**: 200

---

### `POST /api/admin/backups/import`

**Tags**: Admin - Backups

**Request body**: object

**Responses**: 200

---

### `GET /api/admin/backups/{name}/download`

**Tags**: Admin - Backups

| Parameter | In | Type | Required |
|---|---|---|---|
| `name` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/backups/{name}/restore`

**Tags**: Admin - Backups

| Parameter | In | Type | Required |
|---|---|---|---|
| `name` | path | string | yes |

**Responses**: 200

---

### `GET /api/admin/certs`

**Tags**: Admin - Certificates

| Parameter | In | Type | Required |
|---|---|---|---|
| `expiring` | query | boolean | no |

**Responses**: 200

---

### `POST /api/admin/certs`

**Tags**: Admin - Certificates

**Request body**: `IssueRequest`

**Responses**: 200

---

### `POST /api/admin/certs/reconcile`

**Tags**: Admin - Certificates

**Responses**: 200

---

### `GET /api/admin/certs/{id}`

**Tags**: Admin - Certificates

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/certs/{id}/restore`

**Tags**: Admin - Certificates

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/certs/{id}/revoke`

**Tags**: Admin - Certificates

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/certs/{id}/rotate`

**Tags**: Admin - Certificates

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `GET /api/admin/config-report`

**Tags**: Admin - Config Report

**Responses**: 200

---

### `GET /api/admin/connection-logs`

**Tags**: Admin - Connection Logs

| Parameter | In | Type | Required |
|---|---|---|---|
| `limit` | query | integer | no |

**Responses**: 200

---

### `GET /api/admin/connections`

**Tags**: Admin - Connections

**Responses**: 200

---

### `POST /api/admin/connections/{commonName}/disconnect`

**Tags**: Admin - Connections

| Parameter | In | Type | Required |
|---|---|---|---|
| `commonName` | path | string | yes |

**Responses**: 200

---

### `GET /api/admin/daemons`

**Tags**: Admin - Daemons

**Responses**: 200

---

### `POST /api/admin/daemons`

**Tags**: Admin - Daemons

**Request body**: `DaemonRequest`

**Responses**: 200

---

### `GET /api/admin/daemons/resolve/{profileType}`

**Tags**: Admin - Daemons

| Parameter | In | Type | Required |
|---|---|---|---|
| `profileType` | path | string | yes |

**Responses**: 200

---

### `PUT /api/admin/daemons/{id}`

**Tags**: Admin - Daemons

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `DaemonRequest`

**Responses**: 200

---

### `DELETE /api/admin/daemons/{id}`

**Tags**: Admin - Daemons

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/daemons/{id}/enabled`

**Tags**: Admin - Daemons

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |
| `enabled` | query | boolean | yes |

**Responses**: 200

---

### `GET /api/admin/dashboard`

**Tags**: Admin - Dashboard

**Responses**: 200

---

### `POST /api/admin/demo/seed`

Seeds sample users, groups, access rules, DNS overrides, certificate rows and connection history

**Tags**: Admin - Demo

**Request body**: `SeedDemoRequest`

**Responses**: 200

---

### `GET /api/admin/dns-overrides`

**Tags**: Admin - DNS Overrides

**Responses**: 200

---

### `POST /api/admin/dns-overrides`

**Tags**: Admin - DNS Overrides

**Request body**: `DnsRecordDto`

**Responses**: 200

---

### `PUT /api/admin/dns-overrides/{id}`

**Tags**: Admin - DNS Overrides

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `DnsRecordDto`

**Responses**: 200

---

### `DELETE /api/admin/dns-overrides/{id}`

**Tags**: Admin - DNS Overrides

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/dns-overrides/{id}/enabled`

**Tags**: Admin - DNS Overrides

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: boolean

**Responses**: 200

---

### `GET /api/admin/groups`

**Tags**: Admin - Groups

**Responses**: 200

---

### `POST /api/admin/groups`

**Tags**: Admin - Groups

**Request body**: `GroupCreateRequest`

**Responses**: 200

---

### `PUT /api/admin/groups/{id}`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `GroupUpdateRequest`

**Responses**: 200

---

### `DELETE /api/admin/groups/{id}`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `GET /api/admin/groups/{id}/members`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `PUT /api/admin/groups/{id}/members`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `GroupMembersRequest`

**Responses**: 200

---

### `GET /api/admin/groups/{id}/settings`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `PUT /api/admin/groups/{id}/settings/{key}`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |
| `key` | path | string | yes |

**Request body**: object

**Responses**: 200

---

### `DELETE /api/admin/groups/{id}/settings/{key}`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |
| `key` | path | string | yes |

**Responses**: 200

---

### `GET /api/admin/groups/{id}/static-ip-pool`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `PUT /api/admin/groups/{id}/static-ip-pool`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `StaticIpPoolRequest`

**Responses**: 200

---

### `GET /api/admin/groups/{id}/static-ipv6-pool`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `PUT /api/admin/groups/{id}/static-ipv6-pool`

**Tags**: Admin - Groups

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `StaticIpPoolRequest`

**Responses**: 200

---

### `GET /api/admin/monitor`

**Tags**: Admin - Monitor

**Responses**: 200

---

### `GET /api/admin/nodes`

**Tags**: Admin - Nodes

**Responses**: 200

---

### `POST /api/admin/nodes`

**Tags**: Admin - Nodes

**Request body**: `NodeRequest`

**Responses**: 200

---

### `PUT /api/admin/nodes/{id}`

**Tags**: Admin - Nodes

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `NodeRequest`

**Responses**: 200

---

### `DELETE /api/admin/nodes/{id}`

**Tags**: Admin - Nodes

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/nodes/{id}/enabled`

**Tags**: Admin - Nodes

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |
| `enabled` | query | boolean | yes |

**Responses**: 200

---

### `GET /api/admin/profile-tokens`

**Tags**: Admin - Profiles

**Responses**: 200

---

### `POST /api/admin/profile-tokens`

**Tags**: Admin - Profiles

**Request body**: `CreateTokenRequest`

**Responses**: 200

---

### `POST /api/admin/profile-tokens/{id}/revoke`

**Tags**: Admin - Profiles

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `GET /api/admin/rules`

**Tags**: Admin - Access Rules

**Responses**: 200

---

### `POST /api/admin/rules`

**Tags**: Admin - Access Rules

**Request body**: `AccessRuleDto`

**Responses**: 200

---

### `PUT /api/admin/rules/{id}`

**Tags**: Admin - Access Rules

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `AccessRuleDto`

**Responses**: 200

---

### `DELETE /api/admin/rules/{id}`

**Tags**: Admin - Access Rules

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/rules/{id}/enabled`

**Tags**: Admin - Access Rules

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |
| `enabled` | query | boolean | yes |

**Responses**: 200

---

### `GET /api/admin/settings`

**Tags**: Admin - Settings

**Responses**: 200

---

### `PUT /api/admin/settings/{key}`

**Tags**: Admin - Settings

| Parameter | In | Type | Required |
|---|---|---|---|
| `key` | path | string | yes |

**Request body**: `UpdateSettingRequest`

**Responses**: 200

---

### `DELETE /api/admin/settings/{key}`

**Tags**: Admin - Settings

| Parameter | In | Type | Required |
|---|---|---|---|
| `key` | path | string | yes |

**Responses**: 204

---

### `GET /api/admin/status`

**Tags**: Admin - Status

**Responses**: 200

---

### `GET /api/admin/system`

**Tags**: Admin - System

**Responses**: 200

---

### `POST /api/admin/system/preflight`

**Tags**: Admin - System

**Responses**: 200

---

### `POST /api/admin/system/reload-daemons`

**Tags**: Admin - System

**Responses**: 200

---

### `POST /api/admin/system/restart-backend`

**Tags**: Admin - System

**Responses**: 200

---

### `GET /api/admin/users`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `search` | query | string | no |

**Responses**: 200

---

### `POST /api/admin/users`

**Tags**: Admin - Users

**Request body**: `UserCreateRequest`

**Responses**: 200

---

### `POST /api/admin/users/bulk`

**Tags**: Admin - Users

**Request body**: `BulkRequest`

**Responses**: 200

---

### `GET /api/admin/users/{id}`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `PUT /api/admin/users/{id}`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `UserUpdateRequest`

**Responses**: 200

---

### `DELETE /api/admin/users/{id}`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `DeleteOptions`

**Responses**: 200

---

### `POST /api/admin/users/{id}/ban`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/users/{id}/mfa/disable`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/users/{id}/mfa/enable`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `MfaEnableRequest`

**Responses**: 200

---

### `POST /api/admin/users/{id}/mfa/setup`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/users/{id}/reset-password`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `PasswordRequest`

**Responses**: 200

---

### `GET /api/admin/users/{id}/settings`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `GET /api/admin/users/{id}/settings/effective`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `PUT /api/admin/users/{id}/settings/{key}`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |
| `key` | path | string | yes |

**Request body**: object

**Responses**: 200

---

### `DELETE /api/admin/users/{id}/settings/{key}`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |
| `key` | path | string | yes |

**Responses**: 200

---

### `PUT /api/admin/users/{id}/static-ip`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `StaticIpRequest`

**Responses**: 200

---

### `DELETE /api/admin/users/{id}/static-ip`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/users/{id}/static-ip/allocate`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `PUT /api/admin/users/{id}/static-ipv6`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Request body**: `StaticIpv6Request`

**Responses**: 200

---

### `DELETE /api/admin/users/{id}/static-ipv6`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/users/{id}/static-ipv6/allocate`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `POST /api/admin/users/{id}/unban`

**Tags**: Admin - Users

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | string | yes |

**Responses**: 200

---

### `GET /api/admin/users/{userId}/profiles/{type}/download`

**Tags**: Admin - Profiles

| Parameter | In | Type | Required |
|---|---|---|---|
| `userId` | path | string | yes |
| `type` | path | string | yes |

**Responses**: 200

---

### `POST /api/auth/login`

Validates username/password. When the account has TOTP enabled the response contains mfaRequired=true and a preAuthToken that must be redeemed at /mfa. Otherwise accessToken/refreshToken are returned directly.

**Tags**: Authentication

**Request body**: `LoginRequest`

**Responses**: 200

---

### `POST /api/auth/logout`

Revokes the given refreshToken, ending the session. The access token stays valid until it expires on its own.

**Tags**: Authentication

**Request body**: `LogoutRequest`

**Responses**: 200

---

### `GET /api/auth/me`

Returns the profile and roles of the authenticated user identified by the bearer access token.

**Tags**: Authentication

**Responses**: 200

---

### `POST /api/auth/mfa`

Redeems a preAuthToken from /login together with a TOTP code. Returns the final accessToken/refreshToken pair.

**Tags**: Authentication

**Request body**: `MfaRequest`

**Responses**: 200

---

### `POST /api/auth/mfa/enroll`

Begins TOTP provisioning for an account that must enable MFA before first sign-in. Accepts the preAuthToken returned by /login with mustEnrollMfa=true and returns the shared secret plus QR data URL; /mfa/enroll/confirm then activates MFA.

**Tags**: Authentication

**Request body**: `MfaEnrollRequest`

**Responses**: 200

---

### `POST /api/auth/mfa/enroll/confirm`

Activates MFA after the user scanned the QR code and returned a valid TOTP code, then issues the final accessToken/refreshToken pair.

**Tags**: Authentication

**Request body**: `MfaRequest`

**Responses**: 200

---

### `POST /api/auth/refresh`

Exchanges the current refreshToken for a fresh accessToken/refreshToken pair. A reused or revoked refresh token invalidates the whole session family.

**Tags**: Authentication

**Request body**: `RefreshRequest`

**Responses**: 200

---

### `POST /api/portal/account/mfa/disable`

**Tags**: Portal - Account

**Request body**: `MfaSetupRequest`

**Responses**: 200

---

### `POST /api/portal/account/mfa/enable`

**Tags**: Portal - Account

**Request body**: `MfaEnableRequest`

**Responses**: 200

---

### `POST /api/portal/account/mfa/setup`

**Tags**: Portal - Account

**Request body**: `MfaSetupRequest`

**Responses**: 200

---

### `POST /api/portal/account/password`

**Tags**: Portal - Account

**Request body**: `PasswordRequest`

**Responses**: 200

---

### `GET /api/portal/profiles`

**Tags**: Portal - Profiles

**Responses**: 200

---

### `GET /api/portal/profiles/{type}/download`

**Tags**: Portal - Profiles

| Parameter | In | Type | Required |
|---|---|---|---|
| `type` | path | string | yes |

**Responses**: 200

---

### `GET /api/portal/profiles/{type}/qr`

**Tags**: Portal - Profiles

| Parameter | In | Type | Required |
|---|---|---|---|
| `type` | path | string | yes |

**Responses**: 200

---

### `GET /api/portal/share/{token}`

**Tags**: Portal - Share

| Parameter | In | Type | Required |
|---|---|---|---|
| `token` | path | string | yes |

**Responses**: 200

---

### `GET /api/public/brand`

**Tags**: Public

**Responses**: 200

---

### `GET /api/setup/server-config`

**Tags**: Setup

**Responses**: 200

---

### `GET /api/setup/state`

**Tags**: Setup

**Responses**: 200

---

### `POST /api/setup/wizard`

**Tags**: Setup

**Request body**: `SetupWizardRequest`

**Responses**: 200

---

### `POST /internal/auth/verify`

**Tags**: Internal

**Request body**: `VerifyRequest`

**Responses**: 200

---

### `POST /internal/auth/verify-otp`

**Tags**: Internal

**Request body**: `VerifyOtpRequest`

**Responses**: 200

---

### `POST /internal/connect`

**Tags**: Internal

**Request body**: `ConnectRequest`

**Responses**: 200

---

### `POST /internal/disconnect`

**Tags**: Internal

**Request body**: `ConnectRequest`

**Responses**: 200

---

### `POST /internal/learn-address`

**Tags**: Internal

**Request body**: `LearnAddressRequest`

**Responses**: 200

---

### `POST /internal/node/heartbeat`

**Tags**: Node agent

**Request body**: `HeartbeatRequest`

**Responses**: 200

---

### `POST /internal/node/register`

**Tags**: Node agent

**Request body**: `RegisterRequest`

**Responses**: 200

---

### `POST /internal/seed-admin`

**Tags**: Internal

**Request body**: `SeedRequest`

**Responses**: 200

---

### `POST /internal/seed-demo`

**Tags**: Internal

**Request body**: `SeedDemoRequest`

**Responses**: 200

---

## Schemas

### AccessRuleDto

| Field | Type | Required |
|---|---|---|
| `action` | string | yes |
| `destinationValid` | boolean | no |
| `dstCidr` | string | no |
| `dstDomain` | string | no |
| `dstGroupId` | string | no |
| `dstGroupName` | string | no |
| `dstPort` | integer | no |
| `enabled` | boolean | no |
| `id` | string | no |
| `priority` | integer | no |
| `protocol` | string | no |
| `targetId` | string | no |
| `targetName` | string | no |
| `targetType` | string | yes |
| `warnings` | list[string] | no |

---

### ApiTokenCreatedDto

| Field | Type | Required |
|---|---|---|
| `rawToken` | string | no |
| `token` | ApiTokenDto | no |

---

### ApiTokenDto

| Field | Type | Required |
|---|---|---|
| `createdAt` | string | no |
| `createdBy` | string | no |
| `expiresAt` | string | no |
| `id` | string | no |
| `label` | string | no |
| `lastUsedAt` | string | no |
| `prefix` | string | no |
| `role` | string | no |

---

### AuditLogDto

| Field | Type | Required |
|---|---|---|
| `action` | string | no |
| `actorId` | string | no |
| `actorName` | string | no |
| `category` | string | no |
| `createdAt` | string | no |
| `detail` | string | no |
| `id` | string | no |
| `ip` | string | no |
| `targetId` | string | no |
| `targetType` | string | no |

---

### BackupInfo

| Field | Type | Required |
|---|---|---|
| `createdAt` | string | no |
| `name` | string | no |
| `sizeBytes` | integer | no |

---

### BrandDto

| Field | Type | Required |
|---|---|---|
| `footer` | string | no |
| `logoUrl` | string | no |
| `name` | string | no |
| `primaryColor` | string | no |

---

### BulkRequest

| Field | Type | Required |
|---|---|---|
| `action` | string | yes |
| `ids` | list[string] | yes |
| `options` | DeleteOptions | no |

---

### CertificateDto

| Field | Type | Required |
|---|---|---|
| `commonName` | string | no |
| `expiresAt` | string | no |
| `id` | string | no |
| `issuedAt` | string | no |
| `revokedAt` | string | no |
| `serial` | string | no |
| `status` | string | no |
| `userId` | string | no |
| `username` | string | no |

---

### ConfigReportDto

| Field | Type | Required |
|---|---|---|
| `brand` | string | no |
| `daemons` | list[DaemonSummary] | no |
| `dataDirs` | DataDirs | no |
| `dbType` | string | no |
| `generatedAt` | string | no |
| `groups` | integer | no |
| `pki` | PkiInventory | no |
| `serverSettings` | object | no |
| `users` | integer | no |
| `version` | string | no |

---

### ConnectRequest

| Field | Type | Required |
|---|---|---|
| `commonName` | string | no |
| `daemonName` | string | no |
| `nodeId` | string | no |
| `remoteIp` | string | no |
| `username` | string | no |
| `virtualIp` | string | no |
| `virtualIp6` | string | no |

---

### ConnectResult

| Field | Type | Required |
|---|---|---|
| `allowed` | boolean | no |
| `iptablesApply` | list[string] | no |
| `iptablesApply6` | list[string] | no |
| `iptablesRemove` | list[string] | no |
| `iptablesRemove6` | list[string] | no |
| `pushes` | list[string] | no |
| `reason` | string | no |

---

### ConnectionDto

| Field | Type | Required |
|---|---|---|
| `bytesIn` | integer | no |
| `bytesInPerSec` | integer | no |
| `bytesOut` | integer | no |
| `bytesOutPerSec` | integer | no |
| `commonName` | string | no |
| `connectedAt` | string | no |
| `daemonName` | string | no |
| `nodeId` | string | no |
| `remoteIp` | string | no |
| `username` | string | no |
| `virtualIp` | string | no |
| `virtualIpv6` | string | no |

---

### ConnectionLogDto

| Field | Type | Required |
|---|---|---|
| `bytesIn` | integer | no |
| `bytesOut` | integer | no |
| `commonName` | string | no |
| `connectedAt` | string | no |
| `daemonName` | string | no |
| `disconnectedAt` | string | no |
| `durationSeconds` | integer | no |
| `nodeId` | string | no |
| `remoteIp` | string | no |
| `username` | string | no |
| `virtualIp` | string | no |

---

### CreateRequest

| Field | Type | Required |
|---|---|---|
| `expiresAt` | string | no |
| `label` | string | yes |
| `role` | string | no |

---

### CreateTokenRequest

| Field | Type | Required |
|---|---|---|
| `expiresAt` | string | no |
| `profileType` | string | yes |
| `userId` | string | no |
| `userIdRequired` | boolean | no |
| `usesLeft` | integer | no |

---

### DaemonDto

| Field | Type | Required |
|---|---|---|
| `adminHost` | string | no |
| `authUserPass` | boolean | no |
| `clientCertNotRequired` | boolean | no |
| `createdAt` | string | no |
| `daemonIndex` | integer | no |
| `dco` | boolean | no |
| `dnsServers` | list[string] | no |
| `domain` | string | no |
| `enabled` | boolean | no |
| `extraRoutes` | list[string] | no |
| `fullTunnel` | boolean | no |
| `id` | string | no |
| `ipv6Enabled` | boolean | no |
| `ipv6Subnet` | string | no |
| `name` | string | no |
| `nodeId` | string | no |
| `port` | integer | no |
| `primary` | boolean | no |
| `proto` | string | no |
| `subnet` | string | no |
| `subnetMask` | string | no |

---

### DaemonRequest

| Field | Type | Required |
|---|---|---|
| `adminHost` | string | no |
| `authUserPass` | boolean | no |
| `clientCertNotRequired` | boolean | no |
| `daemonIndex` | integer | no |
| `dnsServers` | list[string] | no |
| `domain` | string | no |
| `enabled` | boolean | no |
| `extraRoutes` | list[string] | no |
| `fullTunnel` | boolean | no |
| `ipv6Enabled` | boolean | no |
| `ipv6Subnet` | string | no |
| `name` | string | no |
| `nodeId` | string | no |
| `port` | integer | no |
| `proto` | string | yes |
| `subnet` | string | yes |
| `subnetMask` | string | yes |

---

### DaemonStatus

| Field | Type | Required |
|---|---|---|
| `configPresent` | boolean | no |
| `dco` | boolean | no |
| `enabled` | boolean | no |
| `index` | integer | no |
| `mgmtReachable` | boolean | no |
| `name` | string | no |
| `nodeId` | string | no |
| `port` | integer | no |
| `proto` | string | no |

---

### DaemonSummary

| Field | Type | Required |
|---|---|---|
| `enabled` | boolean | no |
| `index` | integer | no |
| `name` | string | no |
| `port` | integer | no |
| `proto` | string | no |

---

### DashboardDto

| Field | Type | Required |
|---|---|---|
| `activeCertificates` | integer | no |
| `activeConnections` | integer | no |
| `groups` | integer | no |
| `recentConnections` | list[ConnectionDto] | no |
| `runningDaemons` | integer | no |
| `totalDaemons` | integer | no |
| `users` | integer | no |

---

### DataDirs

| Field | Type | Required |
|---|---|---|
| `ccd` | string | no |
| `config` | string | no |
| `logs` | string | no |
| `pki` | string | no |

---

### DeleteOptions

| Field | Type | Required |
|---|---|---|
| `clearCcd` | boolean | no |
| `deleteAccessRules` | boolean | no |
| `deleteCertificates` | boolean | no |

---

### DemoSeedResult

| Field | Type | Required |
|---|---|---|
| `users` | integer | no |

---

### DisconnectResult

| Field | Type | Required |
|---|---|---|
| `remove` | list[string] | no |
| `remove6` | list[string] | no |

---

### DnsRecordDto

| Field | Type | Required |
|---|---|---|
| `createdAt` | string | no |
| `enabled` | boolean | no |
| `hostname` | string | no |
| `id` | string | no |
| `ipv4` | string | no |
| `ipv6` | string | no |
| `scope` | string | yes |
| `scopeId` | string | no |
| `scopeName` | string | no |
| `scopeValid` | boolean | no |
| `warnings` | list[string] | no |

---

### GroupCreateRequest

| Field | Type | Required |
|---|---|---|
| `description` | string | no |
| `name` | string | yes |
| `parentId` | string | no |

---

### GroupDto

| Field | Type | Required |
|---|---|---|
| `createdAt` | string | no |
| `description` | string | no |
| `id` | string | no |
| `memberCount` | integer | no |
| `name` | string | no |
| `parentId` | string | no |

---

### GroupMembersRequest

| Field | Type | Required |
|---|---|---|
| `userIds` | list[string] | yes |

---

### GroupUpdateRequest

| Field | Type | Required |
|---|---|---|
| `description` | string | no |
| `name` | string | no |

---

### HeartbeatRequest

| Field | Type | Required |
|---|---|---|
| `nodeId` | string | no |

---

### IssueRequest

| Field | Type | Required |
|---|---|---|
| `userId` | string | yes |

---

### JsonNode

_No fields._

---

### LearnAddressRequest

| Field | Type | Required |
|---|---|---|
| `address` | string | no |
| `commonName` | string | no |
| `operation` | string | no |

---

### LoginRequest

| Field | Type | Required |
|---|---|---|
| `password` | string | yes |
| `username` | string | yes |

---

### LogoutRequest

| Field | Type | Required |
|---|---|---|
| `refreshToken` | string | no |

---

### MfaChallengeResponse

| Field | Type | Required |
|---|---|---|
| `accessToken` | string | no |
| `mfaRequired` | boolean | no |
| `mustEnrollMfa` | boolean | no |
| `preAuthToken` | string | no |
| `refreshToken` | string | no |

---

### MfaEnableRequest

| Field | Type | Required |
|---|---|---|
| `code` | string | yes |

---

### MfaEnrollRequest

| Field | Type | Required |
|---|---|---|
| `preAuthToken` | string | yes |

---

### MfaRequest

| Field | Type | Required |
|---|---|---|
| `code` | string | yes |
| `preAuthToken` | string | yes |

---

### MfaSetup

| Field | Type | Required |
|---|---|---|
| `otpAuthUrl` | string | no |
| `qrDataUrl` | string | no |
| `secret` | string | no |

---

### MfaSetupRequest

| Field | Type | Required |
|---|---|---|
| `currentPassword` | string | yes |

---

### MonitorSnapshotDto

| Field | Type | Required |
|---|---|---|
| `activeConnections` | integer | no |
| `at` | string | no |
| `bytesInPerSec` | integer | no |
| `bytesOutPerSec` | integer | no |
| `connections` | list[ConnectionDto] | no |
| `daemons` | list[DaemonStatus] | no |
| `history` | list[TrafficPointDto] | no |
| `system` | SystemInfoDto | no |

---

### NodeRequest

| Field | Type | Required |
|---|---|---|
| `adminIp` | string | no |
| `enabled` | boolean | no |
| `mgmtHost` | string | no |
| `mgmtPortBase` | integer | no |
| `name` | string | no |

---

### OpenVpnNodeDto

| Field | Type | Required |
|---|---|---|
| `adminIp` | string | no |
| `createdAt` | string | no |
| `enabled` | boolean | no |
| `id` | string | no |
| `lastSeenAt` | string | no |
| `mgmtHost` | string | no |
| `mgmtPortBase` | integer | no |
| `name` | string | no |
| `online` | boolean | no |

---

### OvpnFile

| Field | Type | Required |
|---|---|---|
| `content` | string | no |
| `filename` | string | no |

---

### PageDtoAuditLogDto

| Field | Type | Required |
|---|---|---|
| `content` | list[AuditLogDto] | no |
| `page` | integer | no |
| `size` | integer | no |
| `totalElements` | integer | no |
| `totalPages` | integer | no |

---

### PasswordRequest

| Field | Type | Required |
|---|---|---|
| `currentPassword` | string | yes |
| `newPassword` | string | yes |

---

### PkiInventory

| Field | Type | Required |
|---|---|---|
| `expired` | integer | no |
| `expiringSoon` | integer | no |
| `revoked` | integer | no |
| `total` | integer | no |
| `valid` | integer | no |

---

### PreflightCheck

| Field | Type | Required |
|---|---|---|
| `detail` | string | no |
| `name` | string | no |
| `status` | string | no |

---

### PreflightResult

| Field | Type | Required |
|---|---|---|
| `checks` | list[PreflightCheck] | no |
| `passed` | boolean | no |

---

### ProfileTokenDto

| Field | Type | Required |
|---|---|---|
| `createdAt` | string | no |
| `expiresAt` | string | no |
| `id` | string | no |
| `profileType` | string | no |
| `revoked` | boolean | no |
| `token` | string | no |
| `userId` | string | no |
| `username` | string | no |
| `usesLeft` | integer | no |

---

### ProfileTypeDto

| Field | Type | Required |
|---|---|---|
| `label` | string | no |
| `locked` | boolean | no |
| `type` | string | no |

---

### QrPayload

| Field | Type | Required |
|---|---|---|
| `expiresAt` | string | no |
| `token` | string | no |

---

### ReconcileResult

| Field | Type | Required |
|---|---|---|
| `created` | integer | no |
| `skipped` | integer | no |
| `updated` | integer | no |

---

### RefreshRequest

| Field | Type | Required |
|---|---|---|
| `refreshToken` | string | no |

---

### RegisterRequest

| Field | Type | Required |
|---|---|---|
| `adminIp` | string | no |
| `mgmtHost` | string | no |
| `mgmtPortBase` | integer | no |
| `name` | string | no |

---

### RegisterResult

| Field | Type | Required |
|---|---|---|
| `nodeId` | string | no |

---

### ReloadResult

| Field | Type | Required |
|---|---|---|
| `failed` | list[integer] | no |
| `signaled` | integer | no |
| `total` | integer | no |

---

### RestartResult

| Field | Type | Required |
|---|---|---|
| `message` | string | no |

---

### RestoreResult

| Field | Type | Required |
|---|---|---|
| `message` | string | no |
| `restartRequired` | boolean | no |

---

### SeedDemoRequest

| Field | Type | Required |
|---|---|---|
| `force` | boolean | no |

---

### SeedDemoResponse

| Field | Type | Required |
|---|---|---|
| `users` | integer | no |

---

### SeedRequest

| Field | Type | Required |
|---|---|---|
| `password` | string | no |
| `username` | string | no |

---

### SeedResult

| Field | Type | Required |
|---|---|---|
| `created` | boolean | no |
| `username` | string | no |

---

### ServerConfig

| Field | Type | Required |
|---|---|---|
| `adminHost` | string | no |
| `authUserPass` | boolean | no |
| `clientCertNotRequired` | boolean | no |
| `daemonIndex` | integer | no |
| `dnsServers` | list[string] | no |
| `domain` | string | no |
| `extraRoutes` | list[string] | no |
| `fullTunnel` | boolean | no |
| `ipv6Enabled` | boolean | no |
| `ipv6Subnet` | string | no |
| `port` | integer | no |
| `proto` | string | no |
| `subnet` | string | yes |
| `subnetMask` | string | yes |

---

### ServerStatusDto

| Field | Type | Required |
|---|---|---|
| `activeConnections` | integer | no |
| `brand` | string | no |
| `daemons` | list[DaemonStatus] | no |
| `uptimeSeconds` | integer | no |
| `version` | string | no |

---

### SetupStatus

| Field | Type | Required |
|---|---|---|
| `adminStepRequired` | boolean | no |
| `pkiInitialized` | boolean | no |
| `state` | string | no |

---

### SetupWizardRequest

| Field | Type | Required |
|---|---|---|
| `payload` | JsonNode | no |
| `step` | string | no |

---

### StaticIpPoolRequest

| Field | Type | Required |
|---|---|---|
| `pool` | string | no |

---

### StaticIpRequest

| Field | Type | Required |
|---|---|---|
| `staticIp` | string | no |

---

### StaticIpv6Request

| Field | Type | Required |
|---|---|---|
| `staticIpv6` | string | no |

---

### SystemInfoDto

| Field | Type | Required |
|---|---|---|
| `availableProcessors` | integer | no |
| `cpuLoadPercent` | number | no |
| `diskFree` | integer | no |
| `diskTotal` | integer | no |
| `freeMemory` | integer | no |
| `totalMemory` | integer | no |

---

### TokenResponse

| Field | Type | Required |
|---|---|---|
| `accessToken` | string | no |
| `refreshToken` | string | no |

---

### TrafficPointDto

| Field | Type | Required |
|---|---|---|
| `activeConnections` | integer | no |
| `at` | string | no |
| `bytesInPerSec` | integer | no |
| `bytesOutPerSec` | integer | no |

---

### UpdateSettingRequest

| Field | Type | Required |
|---|---|---|
| `value` | object | yes |

---

### UserCreateRequest

| Field | Type | Required |
|---|---|---|
| `email` | string | no |
| `fullName` | string | no |
| `groupIds` | list[string] | no |
| `password` | string | yes |
| `role` | string | no |
| `username` | string | yes |

---

### UserDto

| Field | Type | Required |
|---|---|---|
| `banned` | boolean | no |
| `createdAt` | string | no |
| `email` | string | no |
| `fullName` | string | no |
| `groups` | list[string] | no |
| `id` | string | no |
| `lastLoginAt` | string | no |
| `mfaEnabled` | boolean | no |
| `mfaRequired` | boolean | no |
| `mustChangePassword` | boolean | no |
| `role` | string | no |
| `staticIp` | string | no |
| `staticIpv6` | string | no |
| `username` | string | no |

---

### UserUpdateRequest

| Field | Type | Required |
|---|---|---|
| `banned` | boolean | no |
| `email` | string | no |
| `fullName` | string | no |
| `groupIds` | list[string] | no |
| `password` | string | no |
| `role` | string | no |

---

### VerifyOtpRequest

| Field | Type | Required |
|---|---|---|
| `otp` | string | no |
| `remoteIp` | string | no |
| `username` | string | no |

---

### VerifyRequest

| Field | Type | Required |
|---|---|---|
| `commonName` | string | no |
| `otp` | string | no |
| `password` | string | no |
| `remoteIp` | string | no |
| `username` | string | no |

---

### VerifyResult

| Field | Type | Required |
|---|---|---|
| `allowed` | boolean | no |
| `reason` | string | no |

---

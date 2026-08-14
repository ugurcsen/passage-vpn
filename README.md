# OpenVPN Management Panel

A production-ready OpenVPN management panel replicating OpenVPN Access Server
features on top of open source components:

- **Backend**: Java 21, Spring Boot 3.5, Gradle (Kotlin DSL), SQLite (validated against a PostgreSQL profile)
- **Frontend**: React 18 + TypeScript, Vite, MUI v6, TanStack Query
- **VPN core**: OpenVPN Community Edition 2.6, Easy-RSA 3.1, OpenVPN Management Interface
- **Deploy**: Docker Compose, Makefile, `install.sh`

See `AGENTS.md` for the agent/developer guide, `TODO.md` for the phased roadmap,
`RELEASE_NOTES.md` for release history, and `docs/` for detailed documentation:

| Doc | Contents |
|---|---|
| `docs/architecture.md` | Component topology, backend module map, VPN/management integration, security model |
| `docs/access-rules.md` | Firewall + DNS control: rule model, iptables rendering, domain pinning, dual-stack |
| `docs/api.md` | REST API reference (regenerated with `make api-docs`) |
| `docs/ROADMAP.md` | Milestone status for the current release cycle |

## Quick start

```bash
./install.sh                 # single-command install (docker compose)
# or manually:
cp .env.example .env         # edit secrets
make up                      # build + start all services
make logs                    # follow logs; open http://localhost:8080
```

On first boot complete the setup wizard (admin account → VPN server → PKI).
Alternatively bootstrap non-interactively with `make seed-admin` and try the
sample dataset with `make seed-demo` (or set `OPNL_DEMO_MODE=true` in `.env`
to auto-load it on first boot). Demo users use the password `demo-password-1`.

## Features

| Area | Capabilities |
|---|---|
| Users & groups | Accounts, groups with parent/subgroup inheritance, per-user/per-group settings, lock/ban |
| Auth | Local auth + TOTP MFA, brute-force lockout, JWT access/refresh tokens, RBAC (Admin/Reseller/User), API tokens for automation |
| PKI | Built-in CA via Easy-RSA; issue / revoke / restore / rotate certificates; CRL |
| Connection profiles | User-locked, auto-login, server-locked, generic; `.ovpn` download, token URLs, QR sharing, client self-service portal |
| Access control | Rules per user/group/global (IP/subnet, protocol/port, group subnet, domain), full/split tunnel, DNS overrides with scoped access, dual-stack iptables/ip6tables enforcement |
| Monitoring | Online users, traffic, session history, live dashboard, WebSocket push, multi-daemon status/kill |
| Nodes | `openvpn_nodes` registry + VPN Nodes page; node-aware status/kill/monitoring; backend `agent` profile registers remote gateways via `/internal/node/*` |
| Operations | Branding, config report, backup/restore, audit log, demo/seed mode, PostgreSQL profile (`OPNL_PROFILE=postgres`) |

## Documentation

- **Architecture** — `docs/architecture.md`
- **Access rules & DNS control** — `docs/access-rules.md`
- **API reference** — `docs/api.md` (Swagger UI at `/swagger-ui.html` on the backend)
- **Roadmap** — `docs/ROADMAP.md`

## Development

See `Makefile` and `AGENTS.md`. Typical loop:

```bash
make backend-dev     # Spring Boot on :8080 (expects env)
make frontend-dev    # Vite dev server on :5173 (proxies /api + /ws)
make test            # backend + frontend tests
```

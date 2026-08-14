# OpenVPN Management Panel

A production-ready OpenVPN management panel replicating OpenVPN Access Server
features on top of open source components:

- **Backend**: Java 21, Spring Boot 3.5, Gradle (Kotlin DSL), SQLite (validated against a PostgreSQL profile)
- **Frontend**: React 18 + TypeScript, Vite, MUI v6, TanStack Query
- **VPN core**: OpenVPN Community Edition 2.6, Easy-RSA 3.1, OpenVPN Management Interface
- **Deploy**: Docker Compose, Makefile, `install.sh`

See `AGENTS.md` for the agent/developer guide, `TODO.md` for the phased roadmap,
`RELEASE_NOTES.md` for release history, and `docs/` for architecture and API
documentation.

## Quick start

```bash
./install.sh                 # single-command install (docker compose)
# or manually:
cp .env.example .env         # edit secrets
make up                      # build + start all services
make logs                    # follow logs; open http://localhost:8080
```

## Features

- Users & groups with per-user/per-group settings and inheritance
- Local auth + TOTP MFA, brute-force lockout, JWT + RBAC (Admin/Reseller/User)
- Built-in CA via Easy-RSA; issue / revoke / restore / rotate certificates; CRL
- Connection profiles: user-locked, auto-login, server-locked, generic; .ovpn download,
  token URLs, QR sharing, client self-service portal
- Access control rules (IP/subnet, protocol/port, full/split tunnel) via iptables
- Live monitoring: online users, traffic, session history, dashboard, WebSocket push
- Branding, configuration report, backup/restore, multi-daemon
- Multi-node: `openvpn_nodes` registry + admin VPN Nodes page; node-aware
  status/kill/monitoring routing; backend `agent` Spring profile registers and
  heartbeats remote gateway nodes to the central backend (`/internal/node/*`)
- PostgreSQL profile (`OPNL_PROFILE=postgres`) with a dedicated
  `db/migration-postgresql` migration set + parity test

## Development

See `Makefile` and `AGENTS.md`. Typical loop:

```bash
make backend-dev     # Spring Boot on :8080 (expects env)
make frontend-dev    # Vite dev server on :5173 (proxies /api + /ws)
make test            # backend + frontend tests
```

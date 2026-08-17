# PassageVPN

[![CI](https://github.com/ugurcsen/passage-vpn/actions/workflows/ci.yml/badge.svg)](https://github.com/ugurcsen/passage-vpn/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/ugurcsen/passage-vpn?color=blue&label=release)](https://github.com/ugurcsen/passage-vpn/releases)
[![GHCR](https://img.shields.io/badge/images-ghcr.io/ugurcsen/passage-vpn-blue)](https://github.com/ugurcsen/passage-vpn/pkgs)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A production-ready OpenVPN management panel replicating OpenVPN Access Server
features on top of open source components. Source: **github.com/ugurcsen/passage-vpn**.

- **Backend**: Java 25, Spring Boot 3.5, Gradle (Kotlin DSL), SQLite (validated against a PostgreSQL profile)
- **Frontend**: React 18 + TypeScript, Vite, MUI v6, TanStack Query
- **VPN core**: OpenVPN Community Edition 2.7, Easy-RSA 3.1, OpenVPN Management Interface
- **Deploy**: Docker Compose, Makefile, `install.sh`

See `AGENTS.md` for the agent/developer guide, `TODO.md` for the phased roadmap,
`RELEASE_NOTES.md` for release history, and `docs/` for detailed documentation:

| Doc | Contents |
|---|---|
| `docs/architecture.md` | Component topology, backend module map, VPN/management integration, security model |
| `docs/access-rules.md` | Firewall + DNS control: rule model, iptables rendering, domain pinning, dual-stack |
| `docs/api.md` | REST API reference (regenerated with `make api-docs`) |
| `docs/configuration.md` | Environment variables reference (all `PASSAGE_*` settings) |
| `docs/installation.md` | Deployment: dev build vs. source-free release tarball (`--mode=release`) |
| `docs/ROADMAP.md` | Milestone status for the current release cycle |

## Quick start (development)

Build everything locally from source — needs the repo and a build toolchain:

```bash
git clone git@github.com:ugurcsen/passage-vpn.git && cd passage-vpn
./install.sh                 # single-command install (docker compose up -d --build)
# or manually:
cp .env.example .env         # edit secrets
make up                      # build + start all services
make logs                    # follow logs; open http://localhost:8080
```

## Production install (no source on the server)

Deploy from the **deploy-only release tarball** (`passage-vpn-<tag>.tar.gz`),
which contains just the compose files, env template and installer. Prebuilt
images are pulled from `ghcr.io/ugurcsen/passage-vpn`, so the server never needs
the source tree or a build toolchain:

```bash
curl -fsSL -o passage-vpn.tar.gz https://github.com/ugurcsen/passage-vpn/releases/download/<tag>/passage-vpn-<tag>.tar.gz
tar -xzf passage-vpn.tar.gz && cd passage-vpn-<tag>
./install.sh --mode=release --tag=<tag>     # pulls images, starts services
```

`<tag>` is a release version such as `v0.1.0-beta.6` (see
[Releases](https://github.com/ugurcsen/passage-vpn/releases)). You can also build
the same tarball locally from a tagged commit with `make release`. Full details
in `docs/installation.md`.

On first boot complete the setup wizard (admin account → VPN server → PKI).
Alternatively bootstrap non-interactively with `make seed-admin` and try the
sample dataset with `make seed-demo` (or set `PASSAGE_DEMO_MODE=true` in `.env`
to auto-load it on first boot). Demo users use the password `demo-password-1`.

> **Operational note:** never run a full-tunnel VPN client on the VPN server
> host itself — it redirects the host's own egress into the tunnel and locks
> the host out (SSH/panel unreachable until a console `pkill` or reboot).
> Always connect from a separate machine or VM.

## Features

| Area | Capabilities |
|---|---|
| Users & groups | Accounts, groups with parent/subgroup inheritance, per-user/per-group settings, lock/ban |
| Auth | Local auth + TOTP MFA, brute-force lockout, JWT access/refresh tokens, RBAC (Admin/Group Admin/User), API tokens for automation |
| PKI | Built-in CA via Easy-RSA; issue / revoke / restore / rotate certificates; CRL |
| Connection profiles | User-locked, auto-login, server-locked, generic; `.ovpn` download, token URLs, QR sharing, client self-service portal |
| Access control | Rules per user/group/global (IP/subnet, protocol/port, group subnet, domain), full/split tunnel, DNS overrides with scoped access, dual-stack iptables/ip6tables enforcement |
| Monitoring | Online users, traffic, session history, live dashboard, WebSocket push, multi-daemon status/kill |
| Nodes | `openvpn_nodes` registry + VPN Nodes page; node-aware status/kill/monitoring; backend `agent` profile registers remote gateways via `/internal/node/*` |
| Operations | Branding, config report, backup/restore, audit log, demo/seed mode, PostgreSQL profile (`PASSAGE_PROFILE=postgres`) |

## Documentation

- **Architecture** — `docs/architecture.md`
- **Access rules & DNS control** — `docs/access-rules.md`
- **API reference** — `docs/api.md` (Swagger UI at `/swagger-ui.html` on the backend)
- **Configuration & environment variables** — `docs/configuration.md`
- **Installation & deployment** — `docs/installation.md`
- **Roadmap** — `docs/ROADMAP.md`

## Development

See `Makefile` and `AGENTS.md`. Typical loop:

```bash
make backend-dev     # Spring Boot on :8080 (expects env)
make frontend-dev    # Vite dev server on :5173 (proxies /api + /ws)
make test            # backend + frontend tests
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, coding conventions, and the pull request process.

## GitHub resources

- **Repository** — https://github.com/ugurcsen/passage-vpn
- **Releases** (tarball + changelog) — https://github.com/ugurcsen/passage-vpn/releases
- **Issues / feature requests** — https://github.com/ugurcsen/passage-vpn/issues
- **Container images** (backend / frontend / openvpn) — https://github.com/ugurcsen/passage-vpn/pkgs/container

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

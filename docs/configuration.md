# Configuration

All runtime configuration is passed to the services via environment variables
prefixed with `OPNL_`. The backend binds them through Spring relaxed binding into
the `opnl.*` property tree (`config/OpnlProperties`, `config/AgentProperties`),
so each variable maps to a `application*.yml` entry like
`OPNL_DATA_DIR` → `opnl.data-dir`.

The canonical template is [`.env.example`](../.env.example): copy it to `.env`
and adjust. `docker-compose.yml` reads `.env` via `env_file` for the backend and
through `${VAR:-default}` substitutions for the rest.

> **Never commit `.env`.** Secrets such as `OPNL_JWT_SECRET`,
> `OPNL_ADMIN_PASSWORD`, `OPNL_INTERNAL_TOKEN` and the OpenVPN management
> passwords must be generated per install (see `install.sh`, which randomizes
> the first two).

## Profile

| Variable | Default | Values | Description |
|---|---|---|---|
| `OPNL_PROFILE` | `sqlite` | `sqlite` \| `postgres` \| `agent` | Active Spring profile. `sqlite` is the default portable backend; `postgres` runs against PostgreSQL (`docker-compose.postgres.yml`); `agent` turns the instance into a lightweight node agent that registers/heartbeats to a central backend. |

## Branding

| Variable | Default | Description |
|---|---|---|
| `OPNL_BRAND_NAME` | `OpenVPN Panel` | Product name shown in the UI. |

## Data & storage

| Variable | Default | Description |
|---|---|---|
| `OPNL_DATA_DIR` | `./data` (dev) / `/var/lib/opnl` (container) | Top-level runtime data directory. |
| `OPNL_DB_URL` | `jdbc:sqlite:./data/opnl.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on` | JDBC connection URL. The `postgres` profile defaults to `jdbc:postgresql://localhost:5432/opnl`. |
| `OPNL_DB_USER` | *(empty)* / `opnl` (postgres) | Database user (PostgreSQL profile). |
| `OPNL_DB_PASSWORD` | *(empty)* / `opnl` (postgres) | Database password (PostgreSQL profile). |
| `OPNL_PKI_DIR` | `./data/pki` / `/etc/opnl/pki/run` | Easy-RSA PKI directory (CA, certs, keys). Set per-environment in `docker-compose.yml`. |
| `OPNL_CCD_DIR` | `./data/ccd` / `/etc/opnl/ccd` | Per-client config directory (CCD files). |
| `OPNL_CONFIG_DIR` | `./data/config` / `/etc/opnl/config` | Generated OpenVPN daemon configs, management password files, dnsmasq pinning config. |
| `OPNL_SCRIPTS_DIR` | `./data/config/scripts` / `/etc/opnl/config/scripts` | Destination of the synchronized OpenVPN scripts (written by the backend). |
| `OPNL_SCRIPTS_SRC_DIR` | `./openvpn/scripts` (dev) / `/app/openvpn-scripts` (image) | Source tree the backend syncs scripts from (keeps secrets out of the repo). |
| `OPNL_LOG_DIR` | `./data/config/logs` / `/var/log/opnl` | OpenVPN daemon logs, status files, PIDs. |
| `OPNL_EASY_RSA_BIN` | `easyrsa` | Path to the Easy-RSA binary used by the PKI service. |
| `OPNL_PKI_CERT_EXPIRE` | `730` | Validity of newly issued client/server certificates in days. Shorter lifetimes mean a revocation window limited to the CRL is kept short; the CRL is always generated for at least this long. |
| `OPNL_INTERNAL_TLS_DIR` | `./data/internal-tls` / `/var/lib/opnl/internal-tls` | Internal control-plane CA + keystores (agents' mTLS), bootstrapped on first start. Keep inside the data volume. |

## Security & auth

| Variable | Default | Description |
|---|---|---|
| `OPNL_JWT_SECRET` | *(required)* | JWT signing secret, **minimum 32 bytes (256 bits)**. The backend refuses to start with a shorter value (`security/JwtService`). Generate with `openssl rand -base64 48`. |
| `OPNL_ADMIN_PASSWORD` | `change-me` | Bootstrap admin password used by the setup wizard and `make seed-admin`. |
| `OPNL_INTERNAL_TOKEN` | *(required)* | Shared secret guarding the `/internal/**` script-facing endpoints (openvpn scripts, seed endpoints). The backend **refuses to start** when unset or still equal to the placeholder `change-me-internal-token` (`security/SecurityBootstrapCheck`). Generate with `openssl rand -hex 32`; the openvpn container must use the same value. |
| `OPNL_BOOTSTRAP_TOKEN` | *(optional)* | Extra secret guarding the bootstrap-only seed endpoints (`/internal/seed-admin`, `/internal/seed-demo`). When set, requests must present `X-Bootstrap-Token`. Unlike `OPNL_INTERNAL_TOKEN` this value is never exposed to the OpenVPN container, so a compromised gateway cannot re-create an admin account. Generate with `openssl rand -hex 32`. |
| `OPNL_AUTH_PROVIDER` | `local` | Authentication provider. |
| `OPNL_AUTH_RATE_LIMIT_MAX` | `20` | Login attempts allowed per rate-limit window. |
| `OPNL_AUTH_RATE_LIMIT_WINDOW` | `60` | Rate-limit window in seconds. |
| `OPNL_DEMO_MODE` | `false` | Auto-seed the demo dataset (sample users/groups/rules) on first boot after setup. Manual seeding any time: `make seed-demo`. |

## OpenVPN integration

| Variable | Default | Description |
|---|---|---|
| `OPNL_OPENVPN_MGMT_HOST` | `openvpn` | Hostname/IP of the OpenVPN management interface. |
| `OPNL_OPENVPN_MGMT_PORT` | `7505` | Management interface TCP port (per-daemon; daemon 0 = 7505). |
| `OPNL_OPENVPN_MGMT_PASSWORD` | *(required)* | Password of the local OpenVPN management interface. The backend **refuses to start** when unset, and writes the value into every daemon's management password file — it must match what the gateway's management socket enforces. Generate with `openssl rand -base64 24`. |
| `OPNL_OPENVPN_ADMIN_HOST` | `localhost` | VPN server admin hostname/IP pushed to clients as the endpoint to connect to. |
| `OPNL_INTERNAL_BASE_URL` | `http://backend:8080` | Base URL the OpenVPN scripts use to call back into the backend (`/internal/**`). |
| `OPNL_INTERNAL_MTLS_PORT` | `9443` | Port of the internal control-plane mTLS listener (agents talk to this endpoint). |

## Server & networking

| Variable | Default | Description |
|---|---|---|
| `OPNL_SERVER_PORT` | `8080` | Backend HTTP port. In docker-compose the listener is **not published to the host** (all traffic enters via the frontend nginx on port 80); it is only used for local development (`make backend-dev`). |
| `OPNL_LOG_LEVEL` | `INFO` | Root log level. |
| `OPNL_TOMCAT_THREADS` | `50` | Max Tomcat request threads (bounded: the panel is a fleet admin tool, not a public API). |
| `OPNL_TRUSTED_PROXIES` | `172.16.0.0/12` | CIDR ranges allowed to set `X-Forwarded-For` (reverse-proxy IPs). Default covers Docker's default bridge subnets. |
| `OPNL_API_DOCS_ENABLED` | `true` | Swagger UI + OpenAPI docs (`/swagger-ui.html`, `/v3/api-docs`). Off by default in shipped `.env.example` so the API surface stays hidden in production; enable for development. |

## Frontend & compose ports

Compose publishes a **range** of host ports for each protocol, and the backend
auto-assigns new OpenVPN daemons from that range. A daemon's configured port is
the container listen port *and* the port advertised in `.ovpn`, so the container
port always equals the host-published port (identity mapping). Leave a daemon's
**Port** empty in the UI to auto-assign the next free port of its protocol
range; an explicit port must lie inside the published range
(`daemon_port_not_published`). To run more than one daemon, widen the `*_END`
variable of the protocol(s) you need and recreate the stack. Remote-node
gateways publish their own independent ranges (`OPNL_NODE_OPENVPN_*`); their
daemons must specify an explicit port. Full flow and error codes:
`docs/architecture.md` §5.1.

| Variable | Default | Description |
|---|---|---|
| `OPNL_FRONTEND_PORT` | `80` | Host port mapped to the frontend (nginx) container. |
| `OPNL_VITE_PROXY_TARGET` | `http://localhost:8080` | Dev-only: backend target of the Vite dev-server proxy (`/api`, `/share`, `/ws`) in `frontend/vite.config.ts`. Not used by the production nginx image. |
| `OPNL_OPENVPN_PORT` | `1194` | Start (base) of the host port range mapped to the OpenVPN UDP listeners. A daemon's configured port is both the container listen port and the externally advertised port, so daemons must use ports inside the published range. |
| `OPNL_OPENVPN_PORT_END` | `1194` | End of the host UDP range. Defaults to the base (single port); widen it (e.g. `1199`) to publish more UDP daemon ports. The backend auto-assigns a new daemon the next free port of its protocol range and rejects explicit ports outside it (`daemon_port_not_published`). |
| `OPNL_OPENVPN_TCP_PORT` | `1195` | Start (base) of the host port range mapped to the OpenVPN TCP listeners. |
| `OPNL_OPENVPN_TCP_PORT_END` | `1195` | End of the host TCP range (see `OPNL_OPENVPN_PORT_END`). |

## Deployment images (release mode)

When deploying from a release tarball (`install.sh --mode=release`), the images
are pulled from a container registry instead of being built from source, so the
server never needs the source tree or a build toolchain.

| Variable | Default | Description |
|---|---|---|
| `OPNL_IMAGE_REGISTRY` | `ghcr.io` | Container registry hosting the prebuilt images. |
| `OPNL_IMAGE_NAMESPACE` | `ugurcsen/opnl-vpn` | Registry path (GitHub owner/repo). Images live at `<registry>/<namespace>/{backend,frontend,openvpn}:<tag>`. |
| `OPNL_IMAGE_TAG` | `latest` | Image tag; `install.sh --tag=vX.Y.Z` sets this automatically. |

The CI `release.yml` workflow builds and pushes these images on version tags and
attaches the deploy tarball to the GitHub Release.

## Remote node gateway (`OPNL_PROFILE=agent`, compose profile `node`)

A gateway node runs two containers: `opnl-node-openvpn` (the local OpenVPN
daemons) and `opnl-agent` (the backend in `agent` profile, which registers the
node, heartbeats, and pulls its config bundle from the central backend). Start
both on the gateway host with

```bash
docker compose --profile node up -d opnl-node-openvpn opnl-agent
```

The gateway containers share the `opnl-node-pki`/`opnl-node-ccd`/
`opnl-node-config`/`opnl-node-logs` volumes. The agent provisions those volumes
from the central bundle; the openvpn container consumes them read-only, exactly
like the central stack, so the pulled daemon configs are valid verbatim. The
pulled bundle is applied atomically and only when its content hash changes; the
agent also reconciles stale managed files (previous daemon configs, revoked
client CCD entries, etc.).

| Variable | Default | Description |
|---|---|---|
| `OPNL_AGENT_CENTRAL_BASE_URL` | `https://backend:9443` | Central backend base URL (internal mTLS endpoint). |
| `OPNL_AGENT_NODE_NAME` | *(required)* | Unique node name the agent registers under. |
| `OPNL_AGENT_MGMT_HOST` | `openvpn` | Management interface host of the local OpenVPN gateway. |
| `OPNL_AGENT_MGMT_PORT_BASE` | `7505` | Management interface port base of the local gateway. |
| `OPNL_AGENT_MGMT_PASSWORD` | *(required)* | Password of the remote gateway's own management interface. |
| `OPNL_AGENT_ADMIN_IP` | *(empty)* | Admin IP reported for the node. |
| `OPNL_AGENT_HEARTBEAT_SECONDS` | `30` | Heartbeat interval to the central backend. |
| `OPNL_AGENT_SYNC_SECONDS` | `60` | Config-bundle pull interval. The agent downloads the node's bundle (daemon configs, PKI incl. the CRL, CCD, scripts, dnsmasq) from `POST /internal/node/config` and applies it atomically only when its content hash changed. |
| `OPNL_AGENT_TLS_CA` | `/etc/opnl/agent/tls/ca.crt` | mTLS client CA file (issued by the central backend via `POST /api/admin/nodes/{id}/agent-cert`). |
| `OPNL_AGENT_TLS_CERT` | `/etc/opnl/agent/tls/client.crt` | mTLS client certificate. |
| `OPNL_AGENT_TLS_KEY` | `/etc/opnl/agent/tls/client.key` | mTLS client private key. |
| `OPNL_AGENT_TLS_DIR` | `./data/agent-tls` | **Host** directory holding the agent TLS files; mounted read-only into the agent container at `/etc/opnl/agent/tls`. |
| `OPNL_NODE_INTERNAL_BASE_URL` | `http://backend:8080` | Base URL the remote gateway's OpenVPN scripts (`verify-user-pass`, `client-connect`, ...) use to call back into the **central** backend. The default only works when the gateway shares the central Docker network; a real remote gateway must point this at a reachable central address (e.g. `https://vpn.example.com:9443`). |
| `OPNL_NODE_OPENVPN_PORT` | `1196` | Start (base) of the host port range mapped to the remote gateway's UDP listeners. |
| `OPNL_NODE_OPENVPN_PORT_END` | `1196` | End of the remote gateway's UDP range (see `OPNL_OPENVPN_PORT_END`). |
| `OPNL_NODE_OPENVPN_TCP_PORT` | `1197` | Start (base) of the host port range mapped to the remote gateway's TCP listeners. |
| `OPNL_NODE_OPENVPN_TCP_PORT_END` | `1197` | End of the remote gateway's TCP range. |

## OpenVPN container firewall (runtime-injected)

These variables are **not read from `.env`**. The openvpn container entrypoint
extracts the values from the backend-generated daemon configs and injects them
into `apply-rules.sh`. They are listed for completeness.

| Variable | Default | Description |
|---|---|---|
| `OPNL_VPN_POOL` | *(derived)* | VPN client IPv4 subnet in CIDR form (required by `apply-rules.sh`). |
| `OPNL_VPN_POOL6` | *(derived)* | VPN client IPv6 subnet in CIDR form (optional; enables the dual-stack ip6tables rules). |
| `OPNL_FIREWALL_IFACE` | `eth0` | Uplink interface the VPN pool is routed out of. |
| `OPNL_NETWORK_MODE` | `nat` | `nat` (masquerade) or `routed` (no NAT; replies must be routed back). |

`OPNL_DOMAINS` / `OPNL_DOMAINS6` are iptables chain names, **not** environment
variables.

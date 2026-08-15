# Installation & Deployment

Two deployment paths are supported:

- **Development / self-build** — clone the repo and build the images on the host
  (needs Docker, the full source tree, and build toolchain).
- **Release (recommended for production)** — deploy a **deploy-only tarball**
  containing just the compose files, env template, and installer. Images are
  pulled prebuilt from GHCR, so the server never receives the source code.

## Requirements

- Docker Engine with the `docker compose` plugin
- `curl` (installer health check) and `openssl` (generates secrets on first run)
- Linux host; the OpenVPN container needs `/dev/net/tun` and `NET_ADMIN`
  (iptables-based access control)

## Production: install from the release tarball

1. **Create a release.** Tag a commit and push; the `release.yml` workflow builds
   the backend/frontend/openvpn images into GHCR and attaches
   `opnl-vpn-<tag>.tar.gz` to the GitHub Release. You can also build the same
   tarball locally from a tagged commit:

   ```bash
   make release     # requires a git tag on HEAD → release/opnl-vpn-<tag>.tar.gz
   ```

2. **On the server**, download the tarball and unpack it:

   ```bash
   curl -fsSL -o opnl-vpn.tar.gz https://github.com/ugurcsen/opnl-vpn/releases/download/<tag>/opnl-vpn-<tag>.tar.gz
   tar -xzf opnl-vpn.tar.gz
   cd opnl-vpn-<tag>
   ```

   The tarball contains only: `install.sh`, `docker-compose.yml`,
   `docker-compose.postgres.yml`, `.env.example`, and this README.

3. **Install** (pull prebuilt images, no build, no source):

   ```bash
   ./install.sh --mode=release --tag=<tag> [--profile=postgres]
   ```

   `--tag` defaults to `latest`; pin it to the released version for reproducibility.
   The installer creates `.env` from `.env.example` on first run and generates a
   JWT secret and admin password.

4. Complete the setup wizard at `http://<server>:` and log in with
   username `admin` and the password in `.env` (`OPNL_ADMIN_PASSWORD`).

### Upgrades

Repeat step 3 with the new tag — `docker compose pull` fetches the new images and
`docker compose up -d` recreates the containers. Runtime data (DB, PKI, configs,
logs) lives in Docker named volumes and is preserved.

## Development: build from source

```bash
git clone <repo> && cd opnl-vpn
cp .env.example .env        # then adjust (see docs/configuration.md)
./install.sh                # == docker compose up -d --build
```

All source is required for this path. `make up` / `make down` / `make logs` are
the day-to-day helpers; `make test` runs the full backend + frontend test suite.

## PostgreSQL (optional)

```bash
./install.sh --profile=postgres          # dev/build
./install.sh --mode=release --tag=<tag> --profile=postgres   # release
```

## Notes

- **Swagger/OpenAPI is off by default** in the shipped `.env.example`
  (`OPNL_API_DOCS_ENABLED=false`) so the API surface is not exposed in
  production. Enable it only for development.
- The backend refuses to start without `OPNL_INTERNAL_TOKEN` and
  `OPNL_OPENVPN_MGMT_PASSWORD` — replace the placeholders in `.env`.
- Backups: `make backup` archives the `data/` directory (config + PKI + DB dump).
- The release images are tagged `ghcr.io/ugurcsen/opnl-vpn/{backend,frontend,openvpn}:<tag>`
  — override with `OPNL_IMAGE_REGISTRY` / `OPNL_IMAGE_NAMESPACE` / `OPNL_IMAGE_TAG`
  if you host them elsewhere.

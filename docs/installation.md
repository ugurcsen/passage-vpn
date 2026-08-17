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
   `passage-vpn-<tag>.tar.gz` to the GitHub Release. You can also build the same
   tarball locally from a tagged commit:

   ```bash
   make release     # requires a git tag on HEAD → release/passage-vpn-<tag>.tar.gz
   ```

2. **On the server**, download the tarball and unpack it:

   ```bash
   curl -fsSL -o passage-vpn.tar.gz https://github.com/ugurcsen/passage-vpn/releases/download/<tag>/passage-vpn-<tag>.tar.gz
   tar -xzf passage-vpn.tar.gz
   cd passage-vpn-<tag>
   ```

   The tarball contains only: `install.sh`, `docker-compose.yml`,
   `docker-compose.postgres.yml`, `.env.example`, and this README.

3. **Install** (pull prebuilt images, no build, no source):

   ```bash
   ./install.sh --mode=release --tag=<tag> [--profile=postgres]
   ```

   Because the tarball contains no source directories, plain `./install.sh`
   (no flags) automatically detects the deploy-only tarball and falls back to
   release mode, prompting for the tag interactively when run on a terminal.
   `-y` skips all prompts and uses the defaults (`latest`). `--tag` defaults to
   `latest`; pin it to the released version for reproducibility.

   The installer creates `.env` from `.env.example` on first run and generates
   all secrets the backend requires: the JWT signing secret, the admin password,
   the internal token and the OpenVPN management-interface password. The admin
   password is printed at the end of the install (also in `.env` as
   `PASSAGE_ADMIN_PASSWORD`).

4. Complete the setup wizard at `http://<server>:` and log in with
   username `admin` and the password in `.env` (`PASSAGE_ADMIN_PASSWORD`).

### Upgrades

Repeat step 3 with the new tag — `docker compose pull` fetches the new images and
`docker compose up -d` recreates the containers. Runtime data (DB, PKI, configs,
logs) lives in Docker named volumes and is preserved.

## Development: build from source

```bash
git clone <repo> && cd passage-vpn
cp .env.example .env        # then adjust (see docs/configuration.md)
./install.sh                # interactive; ./install.sh -y for defaults
```

All source is required for this path. On a terminal the installer asks for the
install mode (build vs release), the database profile and whether to reset any
existing data; `./install.sh --mode=release --tag=<tag>` works here too.
`make up` / `make down` / `make logs` are the day-to-day helpers; `make test`
runs the full backend + frontend test suite.

## PostgreSQL (optional)

```bash
./install.sh --profile=postgres          # dev/build
./install.sh --mode=release --tag=<tag> --profile=postgres   # release
```

## Notes

- **Swagger/OpenAPI is off by default** in the shipped `.env.example`
  (`PASSAGE_API_DOCS_ENABLED=false`) so the API surface is not exposed in
  production. Enable it only for development.
- The backend refuses to start without `PASSAGE_INTERNAL_TOKEN` and
  `PASSAGE_OPENVPN_MGMT_PASSWORD` — replace the placeholders in `.env`.
- Backups: in a full checkout `make backup` archives the `data/` directory (agent TLS
  + DB dump). Release-tarball deployments have no Makefile — back up the Docker named
  volumes directly instead, e.g.
  `docker run --rm -v passage_data:/data -v "$PWD":/backup alpine tar czf /backup/passage-data.tar.gz -C /data .`
  and repeat for `passage-pki`, `passage-ccd`, `passage-config`, `passage-logs`.
- The release images are tagged `ghcr.io/ugurcsen/passage-vpn/{backend,frontend,openvpn}:<tag>`
  — override with `PASSAGE_IMAGE_REGISTRY` / `PASSAGE_IMAGE_NAMESPACE` / `PASSAGE_IMAGE_TAG`
  if you host them elsewhere.

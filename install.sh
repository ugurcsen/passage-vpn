#!/usr/bin/env bash
# =========================================================
# PassageVPN management panel — interactive installer
# Usage: ./install.sh [options]
#   Interactive: run without options on a terminal.
#   Non-interactive (safe defaults):  ./install.sh -y
#   Options:
#     --mode=build|release   build locally (needs full repo) or pull prebuilt
#     --tag=vX.Y.Z           image tag for release mode (default: latest)
#     --profile=sqlite|postgres
#     --reset                wipe volumes and runtime data before installing
#     -y, --yes              non-interactive: answer yes to all prompts
#     -h, --help             show this help
# =========================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[install]${NC} $*"; }
warn()  { echo -e "${YELLOW}[warn]${NC} $*"; }
fail()  { echo -e "${RED}[error]${NC} $*"; exit 1; }

# ---------- options ----------
RESET=0
MODE=""
TAG=""
PROFILE=""
YES=0

for arg in "$@"; do
  case "$arg" in
    --reset) RESET=1 ;;
    --mode=*) MODE="${arg#*=}" ;;
    --tag=*) TAG="${arg#*=}" ;;
    --profile=*) PROFILE="${arg#*=}" ;;
    -y|--yes) YES=1 ;;
    -h|--help)
      cat <<'EOF'
PassageVPN management panel — interactive installer.

Usage: ./install.sh [options]

  Run without options on a terminal for an interactive install; otherwise
  (piped, or with -y) safe defaults are used.

Options:
  --mode=build|release   build images locally (needs the full repo) or
                         pull prebuilt images from the registry
  --tag=vX.Y.Z           image tag for release mode (default: latest)
  --profile=sqlite|postgres
  --reset                wipe volumes and runtime data before installing
  -y, --yes              non-interactive: answer yes to all prompts
  -h, --help             show this help
EOF
      exit 0
      ;;
    *) fail "Unknown argument '$arg' (see ./install.sh --help)." ;;
  esac
done

# Interactive prompts only on a terminal; piped installs use defaults.
INTERACTIVE=0
if [ "$YES" = "0" ] && [ -t 0 ]; then
  INTERACTIVE=1
fi

ask() { # ask "prompt" default -> REPLY
  local prompt="$1" default="$2" answer
  REPLY="$default"
  if [ "$INTERACTIVE" = "1" ]; then
    printf "[install] %s [%s]: " "$prompt" "$default"
    read -r answer
    [ -n "$answer" ] && REPLY="$answer"
  fi
}

confirm() { # confirm "question" default(1=yes) -> 0/1
  local prompt="$1" default="$2" answer
  if [ "$INTERACTIVE" = "1" ]; then
    printf "[install] %s [%s]: " "$prompt" "$([ "$default" = "1" ] && echo "Y/n" || echo "y/N")"
    read -r answer
    answer="${answer:-}"
    if [ -z "$answer" ]; then
      return "$default"
    fi
    case "$answer" in
      y|Y|yes|YES) return 0 ;;
      *) return 1 ;;
    esac
  fi
  return "$default"
}

# ---------- preflight ----------
command -v docker >/dev/null 2>&1 || fail "docker is not installed."
docker compose version >/dev/null 2>&1 || fail "docker compose plugin is not installed."
HAVE_OPENSSL=0
command -v openssl >/dev/null 2>&1 && HAVE_OPENSSL=1
[ "$HAVE_OPENSSL" = "0" ] && warn "openssl not found (needed to generate secrets)."

# ---------- host kernel check ----------
FWD_OK=1
[ "$(sysctl -n net.ipv4.ip_forward 2>/dev/null)" = "1" ] || FWD_OK=0
if [ "$FWD_OK" = "0" ]; then
  warn "net.ipv4.ip_forward is not enabled on this host."
  warn "VPN clients will connect but cannot route traffic."
  warn "Fix: sudo sysctl -w net.ipv4.ip_forward=1"
  warn "Permanent: add 'net.ipv4.ip_forward=1' to /etc/sysctl.conf"
fi

# ---------- environment detection ----------
HAS_SRC=0
[ -d frontend ] && [ -d openvpn ] && [ -d backend ] && HAS_SRC=1

# ---------- mode ----------
if [ -z "$MODE" ]; then
  if [ "$HAS_SRC" = "0" ]; then
    warn "Source directories (frontend/, openvpn/, backend/) not found."
    info "Detected deploy-only release tarball — using release mode (pull prebuilt images)."
    MODE=release
  elif [ "$INTERACTIVE" = "1" ]; then
    echo
    info "How do you want to install?"
    echo "  1) Build from source (default) — requires the full repository"
    echo "  2) Pull prebuilt images (release) — no source needed"
    ask "Choice" "1"
    case "$REPLY" in
      2|release|Release) MODE=release ;;
      *) MODE=build ;;
    esac
  else
    MODE=build
  fi
fi

case "$MODE" in
  build|release) ;;
  *) fail "Unknown mode '$MODE' (expected build or release)." ;;
esac

if [ "$MODE" = "build" ] && [ "$HAS_SRC" = "0" ]; then
  fail "Build mode needs the full repository (frontend/, openvpn/, backend/) — use --mode=release here."
fi

# ---------- release tag ----------
if [ "$MODE" = "release" ] && [ -z "$TAG" ]; then
  ask "Image tag (the release you downloaded, e.g. v0.1.0)" "latest"
  TAG="$REPLY"
fi
[ "$MODE" = "release" ] && export PASSAGE_IMAGE_TAG="$TAG"

# ---------- profile ----------
if [ -z "$PROFILE" ]; then
  if [ "$INTERACTIVE" = "1" ]; then
    echo
    info "Database profile:"
    echo "  1) SQLite (default) — zero config, file-based"
    echo "  2) PostgreSQL — separate database container"
    ask "Choice" "1"
    case "$REPLY" in
      2|postgres|PostgreSQL) PROFILE=postgres ;;
      *) PROFILE=sqlite ;;
    esac
  else
    PROFILE=sqlite
  fi
fi

case "$PROFILE" in
  sqlite|postgres) ;;
  *) fail "Unknown profile '$PROFILE' (expected sqlite or postgres)." ;;
esac

# ---------- reset detection ----------
if [ "$RESET" = "0" ] && [ "$INTERACTIVE" = "1" ]; then
  if [ -d data ] || { docker compose ps -q 2>/dev/null | grep -q .; }; then
    echo
    if confirm "Existing installation detected. Reset volumes and runtime data?" "0"; then
      RESET=1
    fi
  fi
fi

# ---------- summary & confirmation ----------
echo
info "================= Installation summary =================="
printf "  %-10s : %s\n" "Mode" "$([ "$MODE" = "build" ] && echo "build from source" || echo "release (pull prebuilt images)")"
[ "$MODE" = "release" ] && printf "  %-10s : %s\n" "Image tag" "$TAG"
printf "  %-10s : %s\n" "Database" "$PROFILE"
printf "  %-10s : %s\n" "Reset" "$([ "$RESET" = "1" ] && echo "yes" || echo "no")"
info "========================================================="
if [ "$INTERACTIVE" = "1" ] && ! confirm "Proceed with the installation?" "1"; then
  info "Aborted by user."
  exit 0
fi

# ---------- environment ----------
if [ ! -f .env ]; then
  info "Creating .env from .env.example"
  cp .env.example .env
else
  info ".env already exists (leaving unchanged, filling only missing secrets)"
fi

ADMIN_PASSWORD=""
if [ "$HAVE_OPENSSL" = "1" ]; then
  for spec in \
    "PASSAGE_JWT_SECRET:openssl rand -base64 48 | tr -d '\n'" \
    "PASSAGE_ADMIN_PASSWORD:openssl rand -base64 18 | tr -d '/+='" \
    "PASSAGE_INTERNAL_TOKEN:openssl rand -hex 32" \
    "PASSAGE_OPENVPN_MGMT_PASSWORD:openssl rand -base64 24 | tr -d '/+='" ; do
    key="${spec%%:*}"
    gen="${spec#*:}"
    current="$(grep -E "^${key}=" .env 2>/dev/null | head -1 | cut -d= -f2- || true)"
    case "$current" in
      ""|"change-me"*) ;;
      *) continue ;;
    esac
    val="$(eval "$gen")"
    sed -i.bak "s|^${key}=.*|${key}=${val}|" .env
    rm -f .env.bak
    info "Generated $key"
    [ "$key" = "PASSAGE_ADMIN_PASSWORD" ] && ADMIN_PASSWORD="$val"
  done
else
  warn "openssl missing — cannot generate secrets; set PASSAGE_JWT_SECRET, PASSAGE_INTERNAL_TOKEN and PASSAGE_OPENVPN_MGMT_PASSWORD manually in .env (the backend refuses to start with placeholders)."
fi

# ---------- optional reset ----------
if [ "$RESET" = "1" ]; then
  warn "Resetting volumes and runtime data..."
  docker compose down -v
  rm -rf data
fi

# ---------- stale network patch ----------
# Old release tarballs pinned fixed subnets (172.18.0.0/16, fd00:2::/112) that collide
# with other stacks on the same host ("Pool overlaps"). Auto-detect and remove the ipam
# block so Docker auto-allocates. Deploy-only tarballs never contain the Makefile, so
# this must be fully self-contained.
patch_stale_network() {
  if ! grep -qE 'subnet: (172\.18\.0\.0/16|fd00:2::/112)' docker-compose.yml; then
    return 0
  fi
  warn "docker-compose.yml pins hardcoded network subnets (172.18.0.0/16, fd00:2::/112)."
  warn "These collide with other stacks on this host ('Pool overlaps') — removing them so Docker auto-allocates."
  if [ "$INTERACTIVE" = "1" ] && ! confirm "Patch docker-compose.yml (backup: docker-compose.yml.bak)?" "1"; then
    fail "Aborted: remove the ipam block under 'networks.passage-net' in docker-compose.yml manually, then re-run."
  fi
  tmp="$(mktemp)"
  awk '
    function indent(s, n,   i) {
      n = 0
      for (i = 1; i <= length(s) && substr(s, i, 1) == " "; i++) n++
      return n
    }
    {
      if (in_ipam) {
        if ($0 ~ /^[^ ]/ || indent($0) <= ipam_ind) {
          in_ipam = 0; in_passage = 0
          if (!ipam_drop) printf "%s", buf
          buf = ""
        } else {
          if ($0 ~ /172\.18\.0\.0\/16|fd00:2::\/112/) ipam_drop = 1
          buf = buf $0 "\n"
          next
        }
      }
      if ($0 ~ /^[a-zA-Z]/) { in_networks = 0; in_passage = 0 }
      if ($0 == "networks:") in_networks = 1
      if (in_networks && $0 ~ /^  passage-net:/) in_passage = 1
      if (in_passage && $0 ~ /^    ipam:[[:space:]]*$/) {
        in_ipam = 1; ipam_drop = 0; ipam_ind = 4; buf = $0 "\n"
        next
      }
      print
    }
  ' docker-compose.yml > "$tmp"
  if ! grep -qE 'subnet: (172\.18\.0\.0/16|fd00:2::/112)' "$tmp"; then
    cp docker-compose.yml docker-compose.yml.bak
    mv "$tmp" docker-compose.yml
    info "Removed hardcoded network subnets from docker-compose.yml (backup: docker-compose.yml.bak)."
  else
    rm -f "$tmp"
    fail "Could not patch docker-compose.yml automatically — remove the ipam block under 'networks.passage-net' manually, then re-run."
  fi
}
patch_stale_network

# ---------- build & start ----------
info "Starting services (mode=$MODE, profile=$PROFILE) ..."
COMPOSE_CMD="docker compose"
[ "$PROFILE" = "postgres" ] && export PASSAGE_PROFILE=postgres && COMPOSE_CMD="$COMPOSE_CMD -f docker-compose.yml -f docker-compose.postgres.yml"

# Helper hints adapt to the environment: a full checkout has the Makefile; the
# deploy-only release tarball (no Makefile) falls back to docker compose.
if [ -f Makefile ]; then
  HELPER_LOGS="make logs"
  HELPER_DOWN="make down"
  HELPER_UP="make up"
  HELPER_BACKUP="make backup"
else
  HELPER_LOGS="$COMPOSE_CMD logs -f"
  HELPER_DOWN="$COMPOSE_CMD down"
  HELPER_UP="$COMPOSE_CMD up -d"
  HELPER_BACKUP="see docs/installation.md"
fi

up_retry() {
  if $COMPOSE_CMD up -d "$@"; then
    return 0
  fi
  # A failed 'up' can leave the passage_passage-net network (or its pool) behind; if nothing
  # is attached, drop it and retry once before giving up.
  if docker network inspect passage_passage-net >/dev/null 2>&1; then
    if [ -n "$(docker network inspect -f '{{json .Containers}}' passage_passage-net 2>/dev/null | sed 's/[{}]//g; s/[[:space:]]//g')" ]; then
      fail "Failed to start containers: 'passage_passage-net' still has attached containers — resolve manually and re-run."
    fi
    warn "Failed to start; removing orphaned 'passage_passage-net' network and retrying once..."
    docker network rm passage_passage-net >/dev/null 2>&1 || true
    sleep 1
    $COMPOSE_CMD up -d "$@" || fail "Failed to start containers."
  else
    fail "Failed to start containers."
  fi
}

if [ "$MODE" = "release" ]; then
  info "Pulling images from the registry (tag=$TAG)..."
  $COMPOSE_CMD pull
  info "Starting containers (no build)..."
  up_retry --no-build
else
  info "Building images locally..."
  up_retry --build
fi

# ---------- wait for backend ----------
info "Waiting for backend to become healthy..."
backend_up=0
for i in $(seq 1 60); do
  status="$(docker inspect -f '{{.State.Health.Status}}' passage-backend 2>/dev/null || true)"
  if [ "$status" = "healthy" ]; then
    backend_up=1
    info "Backend is up."
    break
  fi
  sleep 2
done
[ "$backend_up" = "0" ] && warn "Backend not healthy yet — check '$HELPER_LOGS'."

FRONTEND_PORT="$(grep -E '^PASSAGE_FRONTEND_PORT=' .env 2>/dev/null | cut -d= -f2- || true)"
FRONTEND_PORT="${FRONTEND_PORT:-80}"

cat <<EOF

${GREEN}Installation complete.${NC}

  Web panel : http://localhost:${FRONTEND_PORT}

  Login     : username "admin"
  Password  : $([ -n "$ADMIN_PASSWORD" ] && echo "$ADMIN_PASSWORD" || echo "(see PASSAGE_ADMIN_PASSWORD in .env)")

First-run: open the panel and complete the setup wizard (/setup).
Useful: $HELPER_LOGS | $HELPER_DOWN | $HELPER_UP | $HELPER_BACKUP
EOF

#!/usr/bin/env bash
# =========================================================
# OpenVPN management panel — single-command installer
# Usage: ./install.sh [--mode=build|release] [--tag=vX.Y.Z] [--profile=sqlite|postgres] [--reset]
#
#   build   (default) build images locally from source (needs the full repo)
#   release           pull prebuilt images from the container registry (no source needed)
# =========================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[install]${NC} $*"; }
warn()  { echo -e "${YELLOW}[warn]${NC} $*"; }
fail()  { echo -e "${RED}[error]${NC} $*"; exit 1; }

RESET=0
MODE=build
TAG=latest
PROFILE=sqlite

for arg in "$@"; do
  case "$arg" in
    --reset) RESET=1 ;;
    --mode=*) MODE="${arg#*=}" ;;
    --tag=*) TAG="${arg#*=}" ;;
    --profile=*) PROFILE="${arg#*=}" ;;
  esac
done

case "$MODE" in
  build|release) ;;
  *) fail "Unknown mode '$MODE' (expected build or release)." ;;
esac

if [ "$MODE" = "release" ]; then
  # Deployed from the release tarball; the .env lives alongside install.sh.
  info "Release mode: pulling prebuilt images (tag=$TAG)."
  export OPNL_IMAGE_TAG="$TAG"
fi

# ---------- preflight ----------
command -v docker >/dev/null 2>&1 || fail "docker is not installed."
docker compose version >/dev/null 2>&1 || fail "docker compose plugin is not installed."
command -v openssl >/dev/null 2>&1 || warn "openssl not found (needed to generate secrets)."

# ---------- environment ----------
if [ ! -f .env ]; then
  info "Creating .env from .env.example"
  cp .env.example .env
  if command -v openssl >/dev/null 2>&1; then
    SECRET="$(openssl rand -base64 48)"
    ADMIN="$(openssl rand -base64 18 | tr -d '/+=' )"
    sed -i.bak "s|^OPNL_JWT_SECRET=.*|OPNL_JWT_SECRET=${SECRET}|" .env
    sed -i.bak "s|^OPNL_ADMIN_PASSWORD=.*|OPNL_ADMIN_PASSWORD=${ADMIN}|" .env
    rm -f .env.bak
    info "Generated JWT secret and admin password in .env"
  fi
else
  info ".env already exists (leaving unchanged)"
fi

# ---------- optional reset ----------
if [ "$RESET" = "1" ]; then
  warn "Resetting volumes and runtime data..."
  docker compose down -v
  rm -rf data
fi

# ---------- build & start ----------
info "Starting services (mode=$MODE, profile=$PROFILE) ..."
COMPOSE_CMD="docker compose"
[ "$PROFILE" = "postgres" ] && export OPNL_PROFILE=postgres && COMPOSE_CMD="$COMPOSE_CMD -f docker-compose.yml -f docker-compose.postgres.yml"

if [ "$MODE" = "release" ]; then
  info "Pulling images from registry..."
  $COMPOSE_CMD pull
  $COMPOSE_CMD up -d
else
  info "Building images locally..."
  $COMPOSE_CMD up -d --build
fi

# ---------- wait for backend ----------
info "Waiting for backend to become healthy..."
for i in $(seq 1 60); do
  if curl -sf -o /dev/null "http://localhost:${OPNL_SERVER_PORT:-8080}/actuator/health" 2>/dev/null; then
    info "Backend is up."
    break
  fi
  [ "$i" = "60" ] && warn "Backend not healthy yet — check 'make logs'."
  sleep 2
done

cat <<EOF

${GREEN}Installation complete.${NC}

  Web panel : http://localhost:${OPNL_FRONTEND_PORT:-80}
  Backend   : http://localhost:${OPNL_SERVER_PORT:-8080}

First-run: open the panel and complete the setup wizard (/setup).
Admin credentials: OPNL_ADMIN_PASSWORD in .env (username "admin").

Useful: make logs | make down | make up | make backup
EOF

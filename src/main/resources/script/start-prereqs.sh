#!/usr/bin/env bash
#
# start-prereqs.sh — bring up & verify everything the rag-agent backend needs
# before it boots:
#
#   1. Qdrant   (vector store, REST :6333 / gRPC :6334)  + the collection
#   2. Postgres (relational store, :5432)
#   3. Ollama   (:11434) with the chat + embedding models pulled
#
# Design: VERIFY-FIRST. Each dependency is probed on its expected port. If it's
# already reachable (native install, an existing container, whatever) we leave
# it alone. Only if it's DOWN do we try to start it — Postgres/Qdrant via the
# repo's docker-compose.yml, Ollama via `ollama serve`. This makes the script
# safe to run repeatedly and on machines with a mix of native/docker setups.
#
# Exit 0 = every dependency is up and verified. Non-zero = something couldn't be
# satisfied (with a message explaining what). Gradle's bootRun depends on this,
# so a non-zero exit stops the app from starting against a broken environment.
#
# Override anything via env vars (defaults shown):
#   CHAT_MODEL=llama3.2  EMBED_MODEL=nomic-embed-text
#   OLLAMA_HOST=http://localhost:11434
#   QDRANT_URL=http://localhost:6333  QDRANT_COLLECTION=my-rag-agent  VECTOR_SIZE=768
#   PG_HOST=localhost  PG_PORT=5432
#   WAIT_SECONDS=60     SKIP_DOCKER=false
#
set -euo pipefail

# ----- config -------------------------------------------------------------

CHAT_MODEL="${CHAT_MODEL:-llama3.2}"
EMBED_MODEL="${EMBED_MODEL:-nomic-embed-text}"
OLLAMA_HOST="${OLLAMA_HOST:-http://localhost:11434}"

QDRANT_URL="${QDRANT_URL:-http://localhost:6333}"
QDRANT_COLLECTION="${QDRANT_COLLECTION:-my-rag-agent}"
VECTOR_SIZE="${VECTOR_SIZE:-768}"
VECTOR_DISTANCE="${VECTOR_DISTANCE:-Cosine}"

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"

WAIT_SECONDS="${WAIT_SECONDS:-60}"
SKIP_DOCKER="${SKIP_DOCKER:-false}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.yml"

# ----- pretty output ------------------------------------------------------

if [ -t 1 ]; then
  C_GREEN='\033[0;32m'; C_RED='\033[0;31m'; C_YELLOW='\033[0;33m'; C_DIM='\033[2m'; C_RESET='\033[0m'
else
  C_GREEN=''; C_RED=''; C_YELLOW=''; C_DIM=''; C_RESET=''
fi
step() { printf '\n=== %s ===\n' "$1"; }
ok()   { printf "${C_GREEN}  OK${C_RESET}   %s\n" "$1"; }
info() { printf "${C_DIM}  ..${C_RESET}   %s\n" "$1"; }
warn() { printf "${C_YELLOW}  !!${C_RESET}   %s\n" "$1"; }
die()  { printf "${C_RED}  XX${C_RESET}   %s\n" "$1" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# HTTP 2xx/3xx within 3s?
http_ok() { curl -fsS -o /dev/null --max-time 3 "$1" 2>/dev/null; }

# TCP port open? Uses bash /dev/tcp (works in Git Bash too).
tcp_ok() { (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null; }

# Poll `check_fn args...` once a second until it succeeds or WAIT_SECONDS elapses.
wait_for() {
  local desc="$1" timeout="$2"; shift 2
  local i=0
  until "$@"; do
    i=$((i + 1))
    if [ "$i" -ge "$timeout" ]; then
      return 1
    fi
    sleep 1
  done
  info "$desc ready after ${i}s"
  return 0
}

# ----- docker compose detection ------------------------------------------

DC=""
detect_compose() {
  if [ "$SKIP_DOCKER" = "true" ]; then return; fi
  if docker compose version >/dev/null 2>&1; then
    DC="docker compose"
  elif have docker-compose; then
    DC="docker-compose"
  fi
}

compose_up() { # compose_up <service>
  local svc="$1"
  [ -n "$DC" ] || die "Docker Compose unavailable — cannot auto-start '$svc'. Start it manually or install Docker."
  [ -f "$COMPOSE_FILE" ] || die "Compose file not found at $COMPOSE_FILE"
  info "starting '$svc' via: $DC up -d $svc"
  ( cd "$PROJECT_DIR" && $DC -f "$COMPOSE_FILE" up -d "$svc" ) >/dev/null \
    || die "Failed to start '$svc' with Docker Compose."
}

# ----- 1. Qdrant ----------------------------------------------------------

ensure_qdrant() {
  step "Qdrant ($QDRANT_URL)"
  if http_ok "$QDRANT_URL/healthz" || http_ok "$QDRANT_URL/readyz" || http_ok "$QDRANT_URL/"; then
    ok "Qdrant is reachable"
  else
    warn "Qdrant is down — attempting to start it"
    compose_up qdrant
    wait_for "Qdrant" "$WAIT_SECONDS" http_ok "$QDRANT_URL/healthz" \
      || die "Qdrant did not become ready within ${WAIT_SECONDS}s."
    ok "Qdrant started"
  fi

  # Ensure the collection exists (app runs with initialize-schema: false).
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$QDRANT_URL/collections/$QDRANT_COLLECTION" || echo 000)"
  if [ "$code" = "200" ]; then
    ok "Collection '$QDRANT_COLLECTION' exists"
  else
    info "Creating collection '$QDRANT_COLLECTION' (size=$VECTOR_SIZE, distance=$VECTOR_DISTANCE)"
    curl -fsS -X PUT "$QDRANT_URL/collections/$QDRANT_COLLECTION" \
      -H 'Content-Type: application/json' \
      -d "{\"vectors\":{\"size\":$VECTOR_SIZE,\"distance\":\"$VECTOR_DISTANCE\"}}" >/dev/null \
      || die "Could not create collection '$QDRANT_COLLECTION'."
    ok "Collection '$QDRANT_COLLECTION' created"
  fi
}

# ----- 2. Postgres --------------------------------------------------------

ensure_postgres() {
  step "Postgres ($PG_HOST:$PG_PORT)"
  if tcp_ok "$PG_HOST" "$PG_PORT"; then
    ok "Postgres is accepting connections"
  else
    warn "Postgres is down — attempting to start it"
    compose_up postgres
    wait_for "Postgres" "$WAIT_SECONDS" tcp_ok "$PG_HOST" "$PG_PORT" \
      || die "Postgres did not open $PG_PORT within ${WAIT_SECONDS}s."
    ok "Postgres started"
  fi
}

# ----- 3. Ollama + models -------------------------------------------------

model_present() { # model_present <name>
  curl -fsS --max-time 5 "$OLLAMA_HOST/api/tags" 2>/dev/null | grep -q "\"$1"
}

ensure_model() { # ensure_model <name>
  local m="$1"
  if model_present "$m"; then
    ok "Model '$m' present"
    return
  fi
  warn "Model '$m' missing — pulling (first run can take a while)"
  if have ollama; then
    ollama pull "$m" || die "Failed to pull model '$m' via ollama CLI."
  else
    curl -fsS "$OLLAMA_HOST/api/pull" -H 'Content-Type: application/json' \
      -d "{\"name\":\"$m\"}" >/dev/null || die "Failed to pull '$m' via Ollama API."
  fi
  model_present "$m" || die "Model '$m' still not present after pull."
  ok "Model '$m' pulled"
}

ensure_ollama() {
  step "Ollama ($OLLAMA_HOST)"
  if http_ok "$OLLAMA_HOST/api/tags"; then
    ok "Ollama server is up"
  else
    warn "Ollama is down — attempting 'ollama serve'"
    have ollama || die "Ollama is not running and the 'ollama' binary isn't on PATH. Install/start Ollama."
    ( ollama serve >/dev/null 2>&1 & )
    wait_for "Ollama" "$WAIT_SECONDS" http_ok "$OLLAMA_HOST/api/tags" \
      || die "Ollama did not become ready within ${WAIT_SECONDS}s."
    ok "Ollama started"
  fi
  ensure_model "$CHAT_MODEL"
  ensure_model "$EMBED_MODEL"
}

# ----- main ---------------------------------------------------------------

printf 'Preparing rag-agent dependencies…\n'
have curl || die "'curl' is required but not found on PATH."
detect_compose
[ -n "$DC" ] || [ "$SKIP_DOCKER" = "true" ] || warn "Docker Compose not detected — will only verify, can't auto-start containers."

ensure_qdrant
ensure_postgres
ensure_ollama

printf "\n${C_GREEN}All prerequisites are up and verified.${C_RESET} Backend is clear to start.\n"

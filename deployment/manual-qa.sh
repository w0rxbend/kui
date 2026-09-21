#!/usr/bin/env bash
# One reproducible entry point for the repository's manual browser-QA stacks.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCAL_OVERRIDE=""

usage() {
  cat <<'EOF'
Usage: deployment/manual-qa.sh MODE COMMAND [--reuse-images]

Modes:
  quickstart       All-in-one KUI, Kafka, registry, Connect, ksqlDB and fixtures
  auth             Quickstart with form login (admin/viewer demo accounts)
  secured          SASL/SCRAM + TLS broker fixture
  demo             Three-cluster product demonstration (resource intensive)
  allinone         Two-container UI/API shell; no Kafka cluster is configured
  distributed      Distributed KUI services with Kafka and Schema Registry
  observability    Distributed stack plus OpenTelemetry Collector
  distributed-e2e  Distributed stack with deterministic E2E timing overrides
  storybook        Component catalogue only; no backend

Commands:
  up               Build current source, start the stack, then run readiness checks
  check            Check UI/API readiness and direct/proxied build identity
  test-messages    Run the real quickstart message-browser Playwright spec and logs
  logs             Print a bounded backend-oriented log snapshot (never follows)
  status           Show the stack's containers
  config           Render and validate the exact merged Compose configuration
  down             Remove this stack, its anonymous volumes and orphan containers
  help             Show this help

Options:
  --reuse-images   Skip source builds; accept a running image from another commit

All published ports are rebound to 127.0.0.1. Override numeric ports with the
variables already used by each Compose file. See deployment/MANUAL_QA.md for the
port matrix, credentials, coexistence constraints and manual browser checklist.
EOF
}

say() { printf '%s\n' "$*"; }
die() { printf 'manual-qa: %s\n' "$*" >&2; exit 1; }

cleanup() {
  if [[ -n "${LOCAL_OVERRIDE}" && -f "${LOCAL_OVERRIDE}" ]]; then
    rm -f -- "${LOCAL_OVERRIDE}"
  fi
}
trap cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

port() {
  local name="$1" default="$2" value
  value="${!name:-${default}}"
  [[ "${value}" =~ ^[0-9]+$ ]] || die "${name} must be a numeric TCP port, got: ${value}"
  (( 10#${value} >= 1 && 10#${value} <= 65535 )) || die "${name} must be between 1 and 65535"
  printf '%s' "${value}"
}

if [[ $# -eq 0 || "${1:-}" == "help" || "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

MODE="$1"
COMMAND="${2:-help}"
shift $(( $# >= 2 ? 2 : 1 ))

REUSE_IMAGES=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --reuse-images) REUSE_IMAGES=true ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
  shift
done

case "${COMMAND}" in
  up|check|test-messages|logs|status|config|down) ;;
  help) usage; exit 0 ;;
  *) die "unknown command: ${COMMAND}" ;;
esac

PROJECT_DIR=""
COMPOSE_FILES=()
LOG_SERVICES=()
FRONTEND_SERVICE=""
BACKEND_BUILD="none"
UI_PORT=""
API_PORT=""

configure_mode() {
  case "${MODE}" in
    quickstart|auth)
      PROJECT_DIR="${SCRIPT_DIR}/quickstart"
      COMPOSE_FILES=(
        "${SCRIPT_DIR}/quickstart/docker-compose.quickstart.yml"
        "${SCRIPT_DIR}/frontend/docker-compose.frontend.yml"
      )
      if [[ "${MODE}" == "auth" ]]; then
        COMPOSE_FILES+=("${SCRIPT_DIR}/quickstart/docker-compose.auth.yml")
      fi
      FRONTEND_SERVICE="frontend"
      BACKEND_BUILD="allinone"
      UI_PORT="$(port KUI_FRONTEND_PORT 8090)"
      API_PORT="$(port KUI_PORT 8080)"
      KAFKA_PORT="$(port KUI_QUICKSTART_KAFKA_PORT 9092)"
      REGISTRY_PORT="$(port KUI_QUICKSTART_REGISTRY_PORT 8081)"
      CONNECT_PORT="$(port KUI_QUICKSTART_CONNECT_PORT 8083)"
      KSQL_PORT="$(port KUI_QUICKSTART_KSQL_PORT 8088)"
      LOG_SERVICES=(kui frontend kafka schema-registry kafka-connect ksqldb-server avro-seed seed)
      ;;
    secured)
      PROJECT_DIR="${SCRIPT_DIR}/secured"
      COMPOSE_FILES=("${SCRIPT_DIR}/secured/docker-compose.secured.yml")
      FRONTEND_SERVICE="frontend"
      BACKEND_BUILD="allinone"
      UI_PORT="$(port KUI_SECURED_FRONTEND_PORT 18091)"
      API_PORT="$(port KUI_SECURED_PORT 18081)"
      LOG_SERVICES=(kui frontend kafka-secured provision consumer)
      ;;
    demo)
      PROJECT_DIR="${SCRIPT_DIR}/demo"
      COMPOSE_FILES=("${SCRIPT_DIR}/demo/docker-compose.demo.yml")
      FRONTEND_SERVICE="frontend"
      BACKEND_BUILD="allinone"
      UI_PORT="$(port KUI_DEMO_FRONTEND_PORT 18090)"
      API_PORT="$(port KUI_PORT 18080)"
      DEV_PORT="$(port KUI_DEMO_DEV_PORT 19092)"
      REGISTRY_PORT="$(port KUI_DEMO_REGISTRY_PORT 18081)"
      PROD_PORT_1="$(port KUI_DEMO_PROD_PORT_1 19093)"
      PROD_PORT_2="$(port KUI_DEMO_PROD_PORT_2 19094)"
      PROD_PORT_3="$(port KUI_DEMO_PROD_PORT_3 19095)"
      LOG_SERVICES=(kui frontend kafka-dev schema-registry-dev kafka-prod-1 kafka-prod-2 kafka-prod-3 kafka-secured seed-avro-dev)
      ;;
    allinone)
      PROJECT_DIR="${SCRIPT_DIR}/compose"
      COMPOSE_FILES=("${SCRIPT_DIR}/compose/docker-compose.allinone.yml")
      FRONTEND_SERVICE="kui-frontend"
      BACKEND_BUILD="allinone"
      UI_PORT="$(port KUI_FRONTEND_PORT 8090)"
      API_PORT="$(port KUI_PORT 8080)"
      LOG_SERVICES=(kui kui-frontend)
      ;;
    distributed|observability|distributed-e2e)
      PROJECT_DIR="${SCRIPT_DIR}/compose"
      COMPOSE_FILES=("${SCRIPT_DIR}/compose/docker-compose.yml")
      if [[ "${MODE}" == "observability" ]]; then
        COMPOSE_FILES+=("${SCRIPT_DIR}/compose/docker-compose.observability.yml")
      fi
      if [[ "${MODE}" == "distributed-e2e" ]]; then
        COMPOSE_FILES+=("${SCRIPT_DIR}/compose/docker-compose.e2e.yml")
      fi
      FRONTEND_SERVICE="kui-frontend"
      BACKEND_BUILD="distributed"
      UI_PORT="$(port KUI_FRONTEND_PORT 8090)"
      API_PORT="$(port KUI_PORT 8080)"
      LOG_SERVICES=(kui-gateway kui-message kui-consumer kui-schema kui-cluster kui-frontend kafka schema-registry)
      if [[ "${MODE}" == "observability" ]]; then
        LOG_SERVICES+=(otel-collector)
      fi
      ;;
    storybook)
      PROJECT_DIR="${SCRIPT_DIR}/storybook"
      COMPOSE_FILES=("${SCRIPT_DIR}/storybook/docker-compose.storybook.yml")
      FRONTEND_SERVICE="storybook"
      UI_PORT="$(port KUI_STORYBOOK_PORT 6006)"
      LOG_SERVICES=(storybook)
      ;;
    *) die "unknown mode: ${MODE}" ;;
  esac
}

write_port_override() {
  LOCAL_OVERRIDE="$(mktemp -t kui-manual-qa.XXXXXX.yml)"
  {
    printf 'services:\n'
    case "${MODE}" in
      quickstart|auth)
        printf '  kafka:\n    ports: !override\n      - "127.0.0.1:%s:29092"\n' "${KAFKA_PORT}"
        printf '  kafka-connect:\n    ports: !override\n      - "127.0.0.1:%s:8083"\n' "${CONNECT_PORT}"
        printf '  ksqldb-server:\n    ports: !override\n      - "127.0.0.1:%s:8088"\n' "${KSQL_PORT}"
        printf '  schema-registry:\n    ports: !override\n      - "127.0.0.1:%s:8080"\n' "${REGISTRY_PORT}"
        printf '  kui:\n    ports: !override\n      - "127.0.0.1:%s:8080"\n' "${API_PORT}"
        printf '  frontend:\n    ports: !override\n      - "127.0.0.1:%s:8082"\n' "${UI_PORT}"
        ;;
      secured)
        printf '  kui:\n    ports: !override\n      - "127.0.0.1:%s:8080"\n' "${API_PORT}"
        printf '  frontend:\n    ports: !override\n      - "127.0.0.1:%s:8082"\n' "${UI_PORT}"
        ;;
      demo)
        printf '  kafka-dev:\n    ports: !override\n      - "127.0.0.1:%s:29092"\n' "${DEV_PORT}"
        printf '  schema-registry-dev:\n    ports: !override\n      - "127.0.0.1:%s:8081"\n' "${REGISTRY_PORT}"
        printf '  kafka-prod-1:\n    ports: !override\n      - "127.0.0.1:%s:29092"\n' "${PROD_PORT_1}"
        printf '  kafka-prod-2:\n    ports: !override\n      - "127.0.0.1:%s:29092"\n' "${PROD_PORT_2}"
        printf '  kafka-prod-3:\n    ports: !override\n      - "127.0.0.1:%s:29092"\n' "${PROD_PORT_3}"
        printf '  kui:\n    ports: !override\n      - "127.0.0.1:%s:8080"\n' "${API_PORT}"
        printf '  frontend:\n    ports: !override\n      - "127.0.0.1:%s:8082"\n' "${UI_PORT}"
        ;;
      allinone)
        printf '  kui:\n    ports: !override\n      - "127.0.0.1:%s:8080"\n' "${API_PORT}"
        printf '  kui-frontend:\n    ports: !override\n      - "127.0.0.1:%s:8082"\n' "${UI_PORT}"
        ;;
      distributed|observability|distributed-e2e)
        printf '  kui-gateway:\n    ports: !override\n      - "127.0.0.1:%s:8080"\n' "${API_PORT}"
        printf '  kui-frontend:\n    ports: !override\n      - "127.0.0.1:%s:8082"\n' "${UI_PORT}"
        ;;
      storybook)
        printf '  storybook:\n    ports: !override\n      - "127.0.0.1:%s:8081"\n' "${UI_PORT}"
        ;;
    esac
  } >"${LOCAL_OVERRIDE}"
}

configure_mode
write_port_override

COMPOSE=(docker compose --project-directory "${PROJECT_DIR}")
for file in "${COMPOSE_FILES[@]}"; do COMPOSE+=(-f "${file}"); done
COMPOSE+=(-f "${LOCAL_OVERRIDE}")

require_docker() {
  require_command docker
  docker info >/dev/null 2>&1 || die "Docker is not running or is not accessible"
}

ensure_certs() {
  [[ "${MODE}" == "secured" || "${MODE}" == "demo" ]] || return 0
  local cert_dir="${SCRIPT_DIR}/secured/certs"
  if [[ ! -f "${cert_dir}/broker.keystore.p12" || ! -f "${cert_dir}/kafka-ca.p12" ]]; then
    say "Generating the throwaway secured-demo certificates..."
    "${SCRIPT_DIR}/secured/generate-certs.sh"
  fi
}

build_current_source() {
  ${REUSE_IMAGES} && { say "Reusing existing images by request."; return 0; }
  case "${BACKEND_BUILD}" in
    allinone)
      say "Building the current all-in-one backend image..."
      (cd "${REPO_ROOT}" && ./mill --no-server deployment.docker.allinone.docker.build)
      ;;
    distributed)
      say "Building all current distributed backend images..."
      (cd "${REPO_ROOT}" && ./mill --no-server deployment.docker.__.docker.build)
      ;;
  esac
  say "Building the current ${FRONTEND_SERVICE} image..."
  "${COMPOSE[@]}" build "${FRONTEND_SERVICE}"
}

wait_for_url() {
  local label="$1" url="$2" timeout="${KUI_QA_READY_TIMEOUT:-240}" deadline
  [[ "${timeout}" =~ ^[0-9]+$ ]] || die "KUI_QA_READY_TIMEOUT must be a whole number of seconds"
  deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if curl --fail --silent --show-error --output /dev/null \
      --connect-timeout 2 --max-time 5 "${url}" 2>/dev/null; then
      say "ready: ${label} (${url})"
      return 0
    fi
    sleep 2
  done
  die "timed out after ${timeout}s waiting for ${label}: ${url}"
}

check_stack() {
  require_command curl
  if [[ "${MODE}" == "storybook" ]]; then
    wait_for_url "Storybook" "http://127.0.0.1:${UI_PORT}/"
    return 0
  fi

  require_command jq
  wait_for_url "frontend" "http://127.0.0.1:${UI_PORT}/healthz"
  wait_for_url "gateway readiness" "http://127.0.0.1:${API_PORT}/api/v1/health/ready"
  wait_for_url "proxied gateway readiness" "http://127.0.0.1:${UI_PORT}/api/v1/health/ready"

  local direct_info proxied_info direct_build proxied_build running_commit source_commit dirty
  direct_info="$(curl --fail --silent --show-error --max-time 10 "http://127.0.0.1:${API_PORT}/api/v1/info")"
  proxied_info="$(curl --fail --silent --show-error --max-time 10 "http://127.0.0.1:${UI_PORT}/api/v1/info")"
  direct_build="$(jq -S -c '.build' <<<"${direct_info}")"
  proxied_build="$(jq -S -c '.build' <<<"${proxied_info}")"
  [[ "${direct_build}" == "${proxied_build}" ]] || die "frontend proxy and direct API report different builds"

  running_commit="$(jq -r '.build.gitCommit // empty' <<<"${direct_info}")"
  dirty="$(jq -r '.build.gitDirty // empty' <<<"${direct_info}")"
  source_commit="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
  if [[ "${running_commit}" != "${source_commit}" ]]; then
    ${REUSE_IMAGES} || die "running commit ${running_commit:-unknown} does not match source ${source_commit}; rebuild or pass --reuse-images intentionally"
    say "warning: reused image commit ${running_commit:-unknown} differs from source ${source_commit}"
  fi
  say "build identity: ${running_commit:-unknown} (gitDirty=${dirty:-unknown}); direct and proxied API agree"
  say "UI:  http://127.0.0.1:${UI_PORT}/ui/"
  say "API: http://127.0.0.1:${API_PORT}/api/v1/info"
}

log_tail() {
  local tail="${1:-${KUI_QA_LOG_TAIL:-300}}"
  [[ "${tail}" =~ ^[0-9]+$ ]] || die "log tail must be a non-negative integer"
  "${COMPOSE[@]}" logs --no-color --tail="${tail}" "${LOG_SERVICES[@]}"
}

test_messages() {
  [[ "${MODE}" == "quickstart" ]] || die "test-messages is supported only for quickstart; other modes need the manual checklist"
  check_stack
  local playwright="${REPO_ROOT}/frontend/node_modules/.bin/playwright" result
  [[ -x "${playwright}" ]] || die "Playwright is not installed locally; install the committed frontend dependencies first"
  set +e
  (cd "${REPO_ROOT}/frontend" && \
    KUI_E2E_UI="http://127.0.0.1:${UI_PORT}" \
    KUI_E2E_API="http://127.0.0.1:${API_PORT}" \
    "${playwright}" test e2e/messages.spec.ts --project=chromium)
  result=$?
  set -e
  say "Bounded backend log snapshot after Playwright:"
  log_tail 120
  return "${result}"
}

case "${COMMAND}" in
  config)
    require_command docker
    "${COMPOSE[@]}" config
    ;;
  status)
    require_docker
    "${COMPOSE[@]}" ps --all
    ;;
  logs)
    require_docker
    log_tail
    ;;
  check)
    require_docker
    check_stack
    ;;
  test-messages)
    require_docker
    test_messages
    ;;
  up)
    require_docker
    ensure_certs
    build_current_source
    "${COMPOSE[@]}" up --detach
    check_stack
    if [[ "${MODE}" == "auth" ]]; then
      say "Demo logins: admin / quickstart-admin; viewer / quickstart-viewer"
    fi
    ;;
  down)
    require_docker
    "${COMPOSE[@]}" down --volumes --remove-orphans
    ;;
esac

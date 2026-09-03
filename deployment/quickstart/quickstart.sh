#!/usr/bin/env bash
#
# The quickstart: one command that leaves you with KUI running against a Kafka broker that has data
# in it.
#
#   deployment/quickstart/quickstart.sh          start everything and print the URL
#   deployment/quickstart/quickstart.sh down     stop everything and remove it, volumes included
#   deployment/quickstart/quickstart.sh logs     follow the logs
#   deployment/quickstart/quickstart.sh status   what is running
#
# The only thing this needs installed is Docker (with the Compose plugin, which Docker Desktop and
# every current Docker Engine package include). No Java, no Mill, no Scala: if the KUI image is not
# on the machine, this script builds it inside a container.
#
# Ports, when 8080 or 9092 are already taken on your machine:
#
#   KUI_PORT=18080 KUI_QUICKSTART_KAFKA_PORT=19092 deployment/quickstart/quickstart.sh
#
# Pass the same variables to `down`, or not — teardown does not care which ports were used.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${HERE}/../.." && pwd)"
COMPOSE_FILE="${HERE}/docker-compose.quickstart.yml"

KUI_VERSION="${KUI_VERSION:-0.1.0-SNAPSHOT}"
KUI_IMAGE="kui-allinone:${KUI_VERSION}"
KUI_PORT="${KUI_PORT:-8080}"
KAFKA_PORT="${KUI_QUICKSTART_KAFKA_PORT:-9092}"

export KUI_VERSION KUI_PORT
export KUI_QUICKSTART_KAFKA_PORT="${KAFKA_PORT}"

compose() {
  docker compose --project-directory "${HERE}" -f "${COMPOSE_FILE}" "$@"
}

say()  { printf '%s\n' "$*"; }
die()  { printf '%s\n' "$*" >&2; exit 1; }

require_docker() {
  command -v docker >/dev/null 2>&1 \
    || die "Docker is not installed, or not on this shell's PATH. It is the only prerequisite: https://docs.docker.com/get-docker/"
  docker info >/dev/null 2>&1 \
    || die "Docker is installed but not running, or this user cannot talk to it. Start Docker and try again."
  docker compose version >/dev/null 2>&1 \
    || die "This Docker has no 'compose' plugin. Install docker-compose-plugin, or use Docker Desktop, which includes it."
}

# A port already in use produces a Compose error that names a container and a port and does not
# suggest a way out. Checking first lets the message say what to do instead.
port_in_use() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]${port}\$"
  elif command -v lsof >/dev/null 2>&1; then
    lsof -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
  else
    return 1
  fi
}

check_ports() {
  local blocked=()
  port_in_use "${KUI_PORT}"   && blocked+=("${KUI_PORT} (KUI, override with KUI_PORT)")
  port_in_use "${KAFKA_PORT}" && blocked+=("${KAFKA_PORT} (Kafka, override with KUI_QUICKSTART_KAFKA_PORT)")
  if [ "${#blocked[@]}" -gt 0 ]; then
    say "Something is already listening on:"
    printf '  %s\n' "${blocked[@]}"
    say ""
    say "Choose free ports and pass them in, for example:"
    say "  KUI_PORT=$((KUI_PORT + 10000)) KUI_QUICKSTART_KAFKA_PORT=$((KAFKA_PORT + 10000)) ${0}"
    exit 1
  fi
}

# The image is built only when it is missing, so a second run starts in seconds. Anyone with a JDK
# who would rather have the reproducible image build.mill produces can run
# `./mill deployment.docker.allinone.docker.build` first; this will then find it and skip the build.
ensure_image() {
  if docker image inspect "${KUI_IMAGE}" >/dev/null 2>&1; then
    return
  fi

  say "The KUI image ${KUI_IMAGE} is not on this machine, so it has to be built."
  say ""
  say "  This compiles KUI from source inside a container, so that a machine with only Docker"
  say "  installed is enough. It downloads a JDK image, the Mill build tool and every Scala"
  say "  dependency, then compiles the project."
  say ""
  say "  EXPECT SEVERAL MINUTES the first time — around two on a fast connection, longer on a"
  say "  slow one. It happens once: the image is kept, and later runs start immediately."
  say ""
  say "Building now. The build's own output follows, so you can see it is not stuck."
  say ""

  docker build \
    --file "${HERE}/Dockerfile" \
    --tag "${KUI_IMAGE}" \
    --progress plain \
    "${REPO_ROOT}" \
    || die "The KUI image did not build. The build output above says why."

  say ""
  say "Built ${KUI_IMAGE}."
  say ""
}

wait_for_kui() {
  local url="http://localhost:${KUI_PORT}/api/v1/health/ready"
  local deadline=$((SECONDS + 180))
  while [ "${SECONDS}" -lt "${deadline}" ]; do
    if curl -fsS -o /dev/null "${url}" 2>/dev/null; then
      return 0
    fi
    # No curl on this machine? Fall back to asking Docker what it thinks of the container's own
    # health check, which is the same check from the inside.
    if ! command -v curl >/dev/null 2>&1; then
      case "$(docker inspect -f '{{.State.Health.Status}}' kui-quickstart-kui 2>/dev/null || echo none)" in
        healthy) return 0 ;;
      esac
    fi
    sleep 2
  done
  return 1
}

up() {
  require_docker
  check_ports
  ensure_image

  say "Starting Kafka, seeding it, and starting KUI."
  say "  Kafka is waited for until it can actually serve metadata, not merely until it has started,"
  say "  so this pauses for around half a minute before the seed step runs."
  say ""

  compose up -d --wait --wait-timeout 300 \
    || die "Startup failed. 'deployment/quickstart/quickstart.sh logs' shows what happened."

  if ! wait_for_kui; then
    die "KUI started but never became ready. 'deployment/quickstart/quickstart.sh logs' shows why."
  fi

  say ""
  say "  KUI is running:  http://localhost:${KUI_PORT}/ui/"
  say ""
}

down() {
  require_docker
  # -v removes the named volumes, --remove-orphans removes containers left by an older version of
  # this file. Between them, nothing of this quickstart survives except the two images, which
  # `docker image rm kui-allinone:${KUI_VERSION} apache/kafka:4.3.1` removes if you want the disk
  # back.
  compose down -v --remove-orphans
  say "Removed. Nothing from the quickstart is left running or stored."
}

case "${1:-up}" in
  up|"")   up ;;
  down)    down ;;
  logs)    require_docker; compose logs -f ;;
  status)  require_docker; compose ps ;;
  *)
    die "Usage: ${0} [up|down|logs|status]"
    ;;
esac

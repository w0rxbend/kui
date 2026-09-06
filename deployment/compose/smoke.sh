#!/usr/bin/env bash
#
# The fault-isolation demo, as a script that fails loudly.
#
# It runs the exact sequence INFRA-002 specifies: bring the distributed stack up, check that every
# service is reachable through the gateway, stop one container, check that the gateway is still
# answering and now reports that one service as unavailable while the others are untouched,
# start it again, and check that it recovers on its own without anyone pressing anything.
#
# Since the interface became a container in this stack it also checks the claim the README makes
# either side of that sequence: that the page an operator needs in order to find out what is wrong
# is still being served while the service is down. That was a sentence nothing could fail on before.
#
# CI runs this in the end-to-end job, right after the images are built, so that a broken compose
# file is caught by the same run that builds the artefacts it describes. A developer can run it too,
# and should: it takes about a minute and it is the most convincing thing in the repository.
#
#   ./deployment/compose/smoke.sh
#
# Requires the images. Build the backend's six with `./mill deployment.docker.__.build`; the
# interface's is not a Mill target and `docker compose up` builds it from
# `deployment/frontend/Dockerfile` on the first run, which takes a few minutes once.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose=(docker compose -f "$here/docker-compose.yml")
base="http://localhost:${KUI_PORT:-8080}"
ui="http://localhost:${KUI_FRONTEND_PORT:-8090}"

# How long to wait for a state change before calling it a failure. The gateway polls each service
# every ten seconds (`kui.gateway.readinessIntervalMs`), so most transitions here take one full
# interval plus the time to notice.
#
# One of them does not, and forty seconds used to be too short for it. Recovery waits on a container
# that was just started, so it is waiting for a JVM to boot as well as for a poll, and the first
# readiness call against a cold one is slow enough that the gateway reports the service `degraded`
# on the p95 rule before it reports it `available`. Two of three runs of this script on a machine
# also building the rest of the repository failed there at forty seconds and the same sequence
# driven by hand recovered in twenty, which is a load measurement rather than a fault -- and a smoke
# test that fails when the machine is busy is one people learn to re-run. Ninety seconds is still
# short enough that a real hang is reported rather than waited out; every step but that one returns
# in about a second, so the ceiling costs nothing when nothing is wrong.
readonly SETTLE_TIMEOUT=90

log()  { printf '\n=== %s\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*" >&2; "${compose[@]}" ps >&2 || true; exit 1; }

cleanup() {
  log "tearing down"
  "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Waits until a curl command's output equals what is expected, or gives up.
#
# Polling rather than sleeping a fixed time, because the transitions here are driven by a poller
# with jitter: a fixed sleep would be either slow or flaky, and a flaky smoke test is worse than no
# smoke test because people learn to re-run it.
await() {
  local what="$1" expected="$2" command="$3" actual=""
  local deadline=$(( SECONDS + SETTLE_TIMEOUT ))

  while (( SECONDS < deadline )); do
    actual="$(eval "$command" 2>/dev/null || true)"
    if [[ "$actual" == "$expected" ]]; then
      printf '  %s: %s\n' "$what" "$actual"
      return 0
    fi
    sleep 1
  done

  fail "$what was '$actual' after ${SETTLE_TIMEOUT}s, expected '$expected'"
}

# The command that reads one service's capability status.
#
# Selected by service id and never by position in the array. The list grew from one service to four
# and an index that used to name the cluster service silently started naming a different one, which
# is the kind of test that keeps passing while checking nothing.
status_of() {
  printf "curl -sf %s/api/v1/capabilities | jq -r '.entries[] | select(.key.service == \"%s\") | .state.status'" \
    "$base" "$1"
}

# Every service the gateway is routing, read from the gateway itself.
#
# Derived rather than listed, because a literal is a list somebody has to remember: this loop named
# four services for a whole milestone while the gateway held a fifth contract, and nothing here
# could have said so. The next service is added by M8, and this line will not have to change.
routed_services() {
  curl -sf "$base/api/v1/capabilities" | jq -r '[.entries[].key.service] | unique | .[]'
}

# The same question asked of the compose file: every container that is a KUI service rather than the
# gateway or the interface, which are the two that publish a port and the two that are not routed.
service_containers() {
  "${compose[@]}" config --services |
    grep -Ev '^kui-(gateway|frontend)$' |
    sed 's/^kui-//' |
    LC_ALL=C sort
}

log "starting the distributed stack"
"${compose[@]}" up -d --wait --wait-timeout 120 || fail "the stack did not become healthy"

log "every service the gateway routes is up, and the interface serves"
services="$(routed_services)" || fail "the capability document could not be read"
[[ -n "$services" ]] || fail "the capability document named no service at all"
printf '  services the gateway routes: %s\n' "$(echo "$services" | tr '\n' ' ')"
# Both directions, because the two failures look nothing alike from here. A container the gateway
# has no address for never appears in the document at all -- which is how `kui-metrics` went a whole
# milestone unreachable while this script passed -- and an address with no container appears and
# never becomes available.
containers="$(service_containers)"
if [[ "$services" != "$containers" ]]; then
  fail "the gateway routes [$(echo "$services" | tr '\n' ' ')] \
but this stack runs [$(echo "$containers" | tr '\n' ' ')]"
fi
for service in $services; do
  await "$service capability" "available" "$(status_of "$service")"
done
# The interface, the one container a browser actually talks to, and the last one up. `/healthz`
# says nginx is up and nothing more; asking for the document as well is what distinguishes "the
# server is running" from "the server is running and the interface is in the image", which are
# different faults with the same symptom.
await "the interface's health endpoint" "ok" "curl -sf $ui/healthz"
await "the interface serves the application" "200" \
  "curl -s -o /dev/null -w '%{http_code}' $ui/ui/"
# Through the interface's own proxy rather than against the gateway's published port. The browser
# never uses that port -- it asks nginx for /api/ and nginx asks the gateway -- so this is the path
# that has to work, and the one nothing checked.
await "the API through the interface's proxy" "ok" \
  "curl -sf $ui/api/v1/clusters | jq -r .clusters.status"
# A read that really crosses the process boundary. `/api/v1/clusters` is answered by the gateway,
# but the answer is assembled from a call to the cluster service, and the section's status says
# whether that call succeeded: "ok" means the gateway reached the service and got a fresh answer.
# This replaces an earlier `GET /api/v1/ping`, which was an M0 scaffold endpoint and no longer
# exists.
await "proxied cluster list" "ok" \
  "curl -sf $base/api/v1/clusters | jq -r .clusters.status"

log "stopping kui-cluster: one real process dies"
"${compose[@]}" stop kui-cluster >/dev/null

log "the gateway survives it and says what is wrong"
await "cluster capability" "unavailable" "$(status_of cluster)"
# Every other service is a separate process and must be completely unaffected. This is the
# assertion the single-container all-in-one shape cannot make at all, and it is why these services
# grew `main`s and images of their own. The list is the derived one minus the container that was
# stopped, so a service added later is checked here too without anybody remembering to add it.
for service in $services; do
  # `if` and not `[[ ... ]] && continue`: under `set -e` a test that is false is a failed command,
  # and the script would exit on the first service that is not the one that was stopped.
  if [[ "$service" != "cluster" ]]; then
    await "$service capability" "available" "$(status_of "$service")"
  fi
done
# The gateway's own endpoints are the point of the whole exercise: the UI stays usable and can show
# an operator what happened, rather than going blank because one service went away.
await "the gateway's own endpoint" "disabled" \
  "curl -sf $base/api/v1/info | jq -r .authType"
# And the page itself. This is the sentence the README has always made -- "the UI still loads" --
# asserted while the process is actually dead rather than asserted in prose. An operator finds out
# what is wrong by looking at KUI, so KUI going blank with the service would defeat the design.
await "the interface while a service is down" "200" \
  "curl -s -o /dev/null -w '%{http_code}' $ui/ui/"

log "starting kui-cluster again: recovery needs no intervention"
"${compose[@]}" start kui-cluster >/dev/null
await "cluster capability" "available" "$(status_of cluster)"
await "proxied cluster list" "ok" \
  "curl -sf $base/api/v1/clusters | jq -r .clusters.status"

log "PASSED: the gateway survived the service, reported it, and recovered on its own,"
log "        and the interface stayed up throughout"

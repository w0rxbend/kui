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
# Requires the images. `./mill deployment.docker.__.build` builds the backend's eight: the gateway,
# the six services this file runs, and the all-in-one binary it does not. The interface's image is
# not a Mill target at all -- Mill never builds a browser bundle -- and `docker compose up` builds
# it from `deployment/frontend/Dockerfile` on the first run, which takes a few minutes once.
#
# Nothing here pulls. Every image this stack names is built locally and published to no registry, so
# a missing one used to surface as `pull access denied for kui-metrics, repository does not
# exist` -- a message about a registry, for a build step somebody skipped. The first thing this
# script does is therefore to check that each of them exists on this machine, and to name the Mill
# target that makes the missing one.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose=(docker compose -f "$here/docker-compose.yml")
base="http://localhost:${KUI_PORT:-8080}"
ui="http://localhost:${KUI_FRONTEND_PORT:-8090}"

# How long to wait for a state change before calling it a failure. The gateway polls each service
# every ten seconds (`kui.gateway.readinessIntervalMs`), so most transitions here take one full
# interval plus the time to notice.
#
# Recovery used to be the step that made this ceiling matter, and raising the ceiling was the wrong
# fix twice. A container that has just been started is waiting for a JVM to boot as well as for a
# poll, and that first readiness call is slow enough to land above `degradedP95Threshold` (2s). The
# gateway's latency window is the last fifty samples and its p95 is a nearest-rank percentile, so a
# single slow sample stays the p95 until the window holds twenty-one of them -- thirteen more polls,
# or a bit over two minutes at the ten-second interval configured in `kui.yaml`. Waiting for
# `available` was therefore waiting for a *latency window to drain*, which no ceiling short enough
# to report a real hang can cover: forty seconds failed two of three runs, ninety failed one.
#
# So that step no longer asks the question. Recovery is asserted as what it means -- a call crosses
# to the restarted process again, and the gateway has stopped calling it unavailable -- and both of
# those land within one poll, because ADR-039 §4 debounces the way *into* `Unavailable` and never
# the way out. Ninety seconds is now a backstop for every step rather than a budget for one of them.
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

# The same, for a state that has to be *left* rather than reached.
#
# Recovery is the one transition where naming the destination would over-specify it: a service that
# has just come back is `degraded` on the p95 rule for as long as one slow readiness call remains
# the p95 of the gateway's window, and that is a true report about a cold JVM rather than a
# failure to recover. What has to hold is that the gateway has stopped calling it unavailable --
# which no amount of latency can produce, because `Unavailable` comes from readiness, the circuit or
# the service's own verdict and never from a duration (ADR-039 §6).
await_not() {
  local what="$1" forbidden="$2" command="$3" actual=""
  local deadline=$(( SECONDS + SETTLE_TIMEOUT ))

  while (( SECONDS < deadline )); do
    actual="$(eval "$command" 2>/dev/null || true)"
    if [[ -n "$actual" && "$actual" != "$forbidden" ]]; then
      printf '  %s: %s\n' "$what" "$actual"
      return 0
    fi
    sleep 1
  done

  fail "$what was still '$actual' after ${SETTLE_TIMEOUT}s, expected anything but '$forbidden'"
}

# The command that reads one service's capability status.
#
# Selected by service id and never by position in the array. The list has grown from one service to
# six, and an index that used to name the cluster service silently started naming a different one,
# which is the kind of test that keeps passing while checking nothing.
status_of() {
  printf "curl -sf %s/api/v1/capabilities | jq -r '.entries[] | select(.key.service == \"%s\") | .state.status'" \
    "$base" "$1"
}

# THE THREE SETS, AND WHY THERE ARE THREE.
#
# A service is only reachable when three separate facts agree: the gateway holds a *contract* for
# it, this file gives that contract an *address*, and the address resolves to a *container*. Each
# of the three is written in a different place by a different person, and a service missing from any
# one of them is a screen that 404s against a stack that looks healthy.
#
# The check used to read two of the three, and that is exactly why it could not see the defect it
# was written for. `/api/v1/capabilities` reflects `kui.gateway.services` -- the addresses -- so a
# contract with no address is absent from *both* sides of an addresses-against-containers equality
# and the comparison passes. `services/schema` was in that position for a whole milestone, one
# service after `kui-metrics` was in it, and this script said the stack was fine.

# 1. Every service the gateway holds a contract for.
#
# Read from `ServiceContracts.byService`, which is the one place the association is declared, rather
# than from a list here that somebody would have to remember. It is a Scala source file and this is
# a shell script, which is not elegant -- but the alternative on offer is a literal, and a literal
# is what let the last two omissions through. The whitespace is stripped first so that a key
# Scalafmt has split across three lines is still one token.
gateway_contracts() {
  local contracts="src/kui/gateway/api/routing/ServiceContracts.scala"
  local declaration="$here/../../services/gateway/api/$contracts"

  [[ -f "$declaration" ]] || fail "the gateway's contract map is not at $declaration"
  sed -n '/val byService/,/^$/p' "$declaration" |
    tr -d ' \n' |
    grep -oE 'ServiceId\.unsafe\("[a-z0-9-]+"\)->' |
    sed -E 's/.*"([a-z0-9-]+)".*/\1/' |
    LC_ALL=C sort -u
}

# Contracts this stack deliberately does not route, with the reason, one per line.
#
# Empty, and the emptiness is the point: every contract the gateway holds has an address and a
# container here. Leaving one unrouted is a decision somebody may legitimately take -- but it has to
# be taken *here*, in writing, rather than by omission, because omission is invisible from both
# sides of every set below. Naming one costs a line; forgetting one now fails the stack.
readonly UNROUTED_CONTRACTS=""

# 2. Every service the gateway is routing, read from the gateway itself.
routed_services() {
  curl -sf "$base/api/v1/capabilities" | jq -r '[.entries[].key.service] | unique | .[]'
}

# 3. The same question asked of the compose file: every container that is a KUI service rather than
# the gateway or the interface, which are the two that publish a port and the two that are not
# routed.
service_containers() {
  "${compose[@]}" config --services |
    grep -Ev '^kui-(gateway|frontend)$' |
    sed 's/^kui-//' |
    LC_ALL=C sort
}

# Every image this stack expects to already exist, which is every one it does not build itself.
#
# `kui-frontend` carries a `build:` stanza and is therefore excluded: Compose makes it, and asking
# whether it is already on the machine would fail a perfectly good first run.
expected_images() {
  "${compose[@]}" config --format json |
    jq -r '.services | to_entries[] | select(.value.build == null) | .value.image'
}

# The Mill target that builds one of them. `kui-metrics` is `deployment.docker.metrics`.
target_for() { printf 'deployment.docker.%s' "${1#kui-}"; }

# Before anything is started, because a missing image is a build step somebody skipped and the
# message Compose gives for it is about a registry KUI does not publish to. `kui-metrics` had an
# `image:` and no `build:` and no CI job that made it, so the stack came up here and nowhere else,
# and `docker compose pull kui-metrics` said `pull access denied, repository does not exist` --
# which reads as a permissions problem and is not one.
log "every image this stack names exists on this machine"
missing=""
for image in $(expected_images); do
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    missing="$missing ${image%%:*}"
  fi
done
if [[ -n "$missing" ]]; then
  targets=""
  for name in $missing; do targets="$targets,$(target_for "$name")"; done
  fail "these images are not built:$missing
  build them with: ./mill '{${targets#,}}.docker.build'"
fi
printf '  images present: %s\n' "$(expected_images | tr '\n' ' ')"

# And the set the gateway holds a contract for, which is the third of the three and the one nothing
# used to read. It is checked before the stack starts because it is a property of the repository
# rather than of the run: a contract with no address here is wrong whether or not Docker is working.
log "every contract the gateway holds has an address and a container"
contracts="$(gateway_contracts)"
[[ -n "$contracts" ]] || fail "no service contract could be read out of ServiceContracts.byService"
declared="$(service_containers)"
unrouted="$(printf '%s\n' "$UNROUTED_CONTRACTS" | grep -v '^$' | LC_ALL=C sort -u || true)"
expected="$(comm -23 <(printf '%s\n' "$contracts") <(printf '%s\n' "$unrouted"))"
if [[ "$expected" != "$declared" ]]; then
  fail "the gateway holds contracts for [$(echo "$contracts" | tr '\n' ' ')] \
and this stack runs [$(echo "$declared" | tr '\n' ' ')].
  Give the missing one a container in docker-compose.yml and an address in kui.yaml, or name it in
  UNROUTED_CONTRACTS above with the reason it is deliberately not routed."
fi
printf '  contracts routed: %s\n' "$(echo "$contracts" | tr '\n' ' ')"

log "starting the distributed stack"
"${compose[@]}" up -d --wait --wait-timeout 120 || fail "the stack did not become healthy"

log "every service the gateway routes is up, and the interface serves"
services="$(routed_services)" || fail "the capability document could not be read"
[[ -n "$services" ]] || fail "the capability document named no service at all"
printf '  services the gateway routes: %s\n' "$(echo "$services" | tr '\n' ' ')"
# Both directions, because the two failures look nothing alike from here. A container the gateway
# has no address for never appears in the document at all -- which is how `kui-metrics` went a whole
# milestone unreachable while this script passed -- and an address with no container appears and
# never becomes available. Neither of those is the contract check above: this one is about what the
# running gateway actually loaded, and that one is about what the build says it can route.
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
# The read first, because it is what recovery means to anybody using the product and it is the one
# assertion here that cannot pass while the process is down: "ok" says the gateway called the
# cluster service and got a fresh answer back.
await "proxied cluster list" "ok" \
  "curl -sf $base/api/v1/clusters | jq -r .clusters.status"
# Then the registry, which has to stop saying `unavailable`. It is `await_not` and not
# `await ... available` for the reason given beside `SETTLE_TIMEOUT`: a cold JVM's first readiness
# call is slow enough to hold the gateway's p95 above its threshold for a couple of minutes, so
# demanding `available` here was demanding that a latency window drain, and it failed one run in
# three on a busy machine for a stack that had already fully recovered.
await_not "cluster capability" "unavailable" "$(status_of cluster)"

log "PASSED: the gateway survived the service, reported it, and recovered on its own,"
log "        and the interface stayed up throughout"

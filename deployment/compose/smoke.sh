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
# and should: it takes one to two minutes and it is the most convincing thing in the repository.
#
# "About a minute" is what stood here, and it was measured when this stack ran eight containers.
# Re-measured 2026-09-11 on the eleven-container stack, three consecutive runs against images that
# already existed, on a 16-core developer machine under concurrent load: 83s, 112s, 121s. The growth
# is in `up --wait`, not in the assertions -- three more containers to start and become healthy.
#
#   ./deployment/compose/smoke.sh
#
# Requires the images. `./mill deployment.docker.__.build` builds every backend image: the gateway,
# the nine services this file runs, and the all-in-one binary it does not. The number is not
# written down and does not need to be -- the preflight below derives the list from this stack's own
# compose file and names anything that is missing. The interface's image is
# not a Mill target at all -- Mill never builds a browser bundle -- and `docker compose up` builds
# it from `deployment/frontend/Dockerfile` on the first run, which takes a few minutes once.
#
# Every `kui-*` image this stack names is built locally and published to no registry, so a missing
# one used to surface as `pull access denied for kui-metrics, repository does not exist` -- a
# message about a registry, for a build step somebody skipped. The first thing this script does is
# therefore to check that each of them exists on this machine, and to name the Mill target that
# makes the missing one. The third-party images -- the Kafka broker, the JMX exporter beside it that
# makes this stack measurable at all, the Schema Registry, the Kafka Connect worker, which runs the
# broker's own image and so is a fifth container rather than a fifth image, and the ksqlDB server --
# are pulled by Compose like any other published image and are deliberately not in that check: there
# is no Mill target it could name.

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
# single slow sample stays the p95 until the window holds twenty of them -- twelve more polls, or
# two minutes at the ten-second interval configured in `kui.yaml`. (Twenty and not twenty-one: the
# slow sample is the largest, so it sits at index n-1, and `LatencyWindow.percentile` reads index
# `ceil(95/100 * n) - 1`. At n=19 that is 18, which is still the last element; at n=20 it is 18 and
# the last element is 19, so twenty is the first window in which the slow sample is no longer the
# p95. This comment said twenty-one and thirteen, which is the same arithmetic off by one.)
#
# Waiting for `available` was therefore waiting for a *latency window to drain*, which no ceiling
# short enough to report a real hang can cover: forty seconds failed two of three runs, ninety
# failed one.
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
# the service's own verdict and never from a duration. That is ADR-039 §1's four inputs and §2's
# precedence table, `NotConfigured > Unavailable > Degraded > Available`; the citation here used to
# read §6, which is the rule that business errors must not dim a capability and is about something
# else entirely.
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
#
# `.key.cluster == null` is the second half of the selection and it became load-bearing the moment
# this stack configured a cluster. The capability document carries one row per service *and* one
# row per (service, cluster) pair, so with two clusters the service filter alone returns three
# lines, and `await` compares "available\navailable\navailable" against "available" for ninety
# seconds before failing. The null row is also the right question for this file: it is the
# gateway's verdict on the *process*, which is what a fault-isolation test is about, while the
# per-cluster rows answer a different question -- whether that service can serve that cluster,
# which is how `metrics` is legitimately `available` on one and `not_configured` on the other here.
status_of() {
  printf "curl -sf %s/api/v1/capabilities | jq -r '.entries[] | select(.key.service == \"%s\" and .key.cluster == null) | .state.status'" \
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
    { grep -oE 'ServiceId\.unsafe\("[a-z0-9-]+"\)->' || true; } |
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

# The set arithmetic, pulled out so the self-test below and the real check are the same code.
#
# `comm -23` needs both sides sorted and needs the empty line that `printf '%s\n' ""` produces
# stripped, or an empty `UNROUTED_CONTRACTS` subtracts a blank from the contract list and takes the
# first entry with it.
routable_contracts() {
  local contracts="$1" declared="$2" unrouted
  unrouted="$(printf '%s\n' "$declared" | grep -v '^$' | LC_ALL=C sort -u || true)"
  comm -23 <(printf '%s\n' "$contracts") <(printf '%s\n' "$unrouted")
}

# THE SELF-TEST, AND WHY A SUBTRACTION NEEDS ONE.
#
# `UNROUTED_CONTRACTS` is empty and has always been empty, so `routable_contracts` has only ever
# been asked to subtract nothing from something -- and a subtraction that is only ever given the
# identity has not been tested. Every failure mode of it is invisible from here: returning
# `$contracts` unchanged, dropping the first line, or ignoring its second argument entirely would
# all produce today's exactly-correct output. The one run where it would matter is the one where
# somebody has just written a reason into that variable, which is the run nobody would re-check.
#
# So it is given the situation it exists for, on made-up input, before the real one. Three
# contracts, one of them deliberately unrouted, and the answer has to be the other two in order.
probe="$(routable_contracts "$(printf 'alpha\nbeta\ngamma')" "beta")"
[[ "$probe" == "$(printf 'alpha\ngamma')" ]] || fail "the unrouted-contract subtraction is broken:
  subtracting [beta] from [alpha beta gamma] gave [$(echo "$probe" | tr '\n' ' ')] and not
  [alpha gamma]. Every check below that uses it is now reporting the wrong set."
# And the identity, which is the case every real run takes: subtracting nothing changes nothing.
probe="$(routable_contracts "$(printf 'alpha\nbeta')" "")"
[[ "$probe" == "$(printf 'alpha\nbeta')" ]] || fail "the unrouted-contract subtraction dropped an
  entry when nothing was unrouted: [alpha beta] became [$(echo "$probe" | tr '\n' ' ')]."

# WHICH SIDE OF THE COMPARISON IS SHORT, because the two sides need opposite repairs.
#
# A contract with no container is a screen that 404s and the fix is a container plus an address. A
# container with no contract is a container and an address that may both be perfectly correct, and
# the fix is the row in `ServiceContracts.byService`. The failure message used to give the first
# advice for both, and in wave 6 it fired on the second -- naming the two facts that were already
# there while the missing one went unmentioned.
contracted_not_run() { comm -23 <(printf '%s\n' "$1") <(printf '%s\n' "$2"); }
run_not_contracted() { comm -13 <(printf '%s\n' "$1") <(printf '%s\n' "$2"); }

# Self-tested for the same reason `routable_contracts` is, and it is a stronger reason here: both
# sides of this comparison are equal on every passing run, so both functions answer empty every time
# anybody looks. A pair that had been swapped -- which is one character in each name -- would print
# exactly reversed advice on the one run that matters and nothing on every other, so the mistake
# could only ever be found by the person it was about to mislead.
probe="$(contracted_not_run "$(printf 'alpha\nbeta')" "$(printf 'alpha')")"
[[ "$probe" == "beta" ]] || fail "the contracted-but-not-run split is broken: contracts
  [alpha beta] against containers [alpha] gave [$(echo "$probe" | tr '\n' ' ')] and not [beta]."
probe="$(run_not_contracted "$(printf 'alpha')" "$(printf 'alpha\nbeta')")"
[[ "$probe" == "beta" ]] || fail "the run-but-not-contracted split is broken: contracts [alpha]
  against containers [alpha beta] gave [$(echo "$probe" | tr '\n' ' ')] and not [beta]."
# And both empty when the two sets agree, which is what every passing run sees. Without this line a
# pair of functions that answered everything would still satisfy the two probes above.
probe="$(contracted_not_run "$(printf 'alpha')" "$(printf 'alpha')")$(run_not_contracted "$(printf 'alpha')" "$(printf 'alpha')")"
[[ -z "$probe" ]] || fail "the contract/container split reported a difference between two identical
  sets: [alpha] against [alpha] gave [$(echo "$probe" | tr '\n' ' ')]."

# 2. Every service the gateway is routing, read from the gateway itself.
routed_services() {
  curl -sf "$base/api/v1/capabilities" | jq -r '[.entries[].key.service] | unique | .[]'
}

# 3. The same question asked of the compose file: every container that is a KUI service rather than
# the gateway or the interface, which are the two that publish a port and the two that are not
# routed.
#
# `^kui-` first, and it is not cosmetic. This stack runs a Kafka broker and a JMX exporter as well,
# and they are neither routed nor contracted: without the prefix filter `kafka` and `kafka-metrics`
# arrive in this set, the comparison below sees two container names the gateway holds no contract
# for, and a stack that is completely correct fails. The prefix is also the naming rule -- every KUI
# service container is `kui-<service id>` and the id is what the gateway's contract map is keyed by
# -- so nothing a service could be called escapes the check by being filtered out here.
service_containers() {
  "${compose[@]}" config --services |
    { grep -E '^kui-' || true; } |
    { grep -Ev '^kui-(gateway|frontend)$' || true; } |
    sed 's/^kui-//' |
    LC_ALL=C sort
}

# Every image this stack expects to already exist: every KUI image it does not build itself.
#
# Two exclusions and they are for opposite reasons. `kui-frontend` carries a `build:` stanza:
# Compose makes it, and asking whether it is already on the machine would fail a perfectly good
# first run. `apache/kafka`, `bitnamilegacy/jmx-exporter` and `apicurio/apicurio-registry` are
# third-party images published to a registry, so Compose pulls them and there is no Mill target
# that could make one -- naming them here would print "build them with
# ./mill deployment.docker.kafka.docker.build", which is advice for a task that does not exist.
#
# What is left is exactly the set this check is for: images built from this working tree and
# published nowhere.
expected_images() {
  "${compose[@]}" config --format json |
    jq -r '.services | to_entries[] | select(.value.build == null) | .value.image
           | select(startswith("kui-"))'
}

# The Mill target that builds one of them. `kui-metrics` is `deployment.docker.metrics`.
target_for() { printf 'deployment.docker.%s' "${1#kui-}"; }

# Before anything is started, because a missing image is a build step somebody skipped and the
# message Compose gives for it is about a registry KUI does not publish to. `kui-metrics` had an
# `image:` and no `build:` and no CI job that made it, so the stack came up here and nowhere else,
# and `docker compose pull kui-metrics` said `pull access denied, repository does not exist` --
# which reads as a permissions problem and is not one.
log "every image this stack names exists on this machine"
images="$(expected_images)"
# THE GUARD THIS STEP DID NOT HAVE, AND IT IS THE REASON THE STEP EXISTS.
#
# Everything below is a `for` over `$images`. A `for` over nothing runs zero times, leaves `missing`
# empty, and the step prints `images present:` followed by a space and passes -- over a stack whose
# images were never checked at all. That is not hypothetical: it was observed doing exactly that
# during wave 3's verification, when the `jq` filter was being edited, and the only thing that
# caught the run was the contract check twenty lines below, which happens to read a different file.
#
# One line, and it is the same `[[ -n ... ]] || fail` the contract check already has. A gate that
# cannot fail is not a gate, and a derivation that comes back empty is the way this one stops being
# one.
[[ -n "$images" ]] || fail "no kui-* image could be derived from docker-compose.yml.
  Every service in that file either carries a \`build:\` stanza or names a third-party image, which
  cannot be true of a stack that runs KUI. Check the \`expected_images\` filter above."
missing=""
for image in $images; do
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
printf '  images present: %s\n' "$(echo "$images" | tr '\n' ' ')"

# And the set the gateway holds a contract for, which is the third of the three and the one nothing
# used to read. It is checked before the stack starts because it is a property of the repository
# rather than of the run: a contract with no address here is wrong whether or not Docker is working.
log "every contract the gateway holds has an address and a container"
contracts="$(gateway_contracts)"
[[ -n "$contracts" ]] || fail "no service contract could be read out of ServiceContracts.byService"
declared="$(service_containers)"
unrouted="$(printf '%s\n' "$UNROUTED_CONTRACTS" | grep -v '^$' | LC_ALL=C sort -u || true)"
expected="$(routable_contracts "$contracts" "$UNROUTED_CONTRACTS")"
if [[ "$expected" != "$declared" ]]; then
  # THE ADVICE IS PER DIRECTION, BECAUSE THE TWO DIRECTIONS NEED OPPOSITE REPAIRS AND ONE OF THEM
  # WAS THE FAILURE THIS CHECK ACTUALLY PRINTED. It used to say "give the missing one a container in
  # docker-compose.yml and an address in kui.yaml" whichever way the sets disagreed. In wave 6 it
  # fired on `kui-alerts`, whose container was in this file and whose address was in kui.yaml, while
  # the *contract* was missing -- the gateway could not hold one, because `alerts.contract.jvm` was
  # absent from `services.gateway.api`'s moduleDeps. So the advice named the two facts that were
  # present and not the one that was absent, and the reader went looking in the file that was right.
  #
  # The two functions above, self-tested at the top of this file, rather than two `comm` calls
  # written here -- which is the arrangement `routable_contracts` already has and for the same
  # reason: on every passing run both answers are empty, so an inverted pair is invisible until the
  # run it is about to mislead. `comm` needs both sides sorted and both are: `gateway_contracts` and
  # `service_containers` each end in `sort`, and `routable_contracts` preserves that order.
  contracted_only="$(contracted_not_run "$expected" "$declared")"
  container_only="$(run_not_contracted "$expected" "$declared")"
  advice=""
  if [[ -n "$contracted_only" ]]; then
    advice="$advice
  CONTRACTED BUT NOT RUN: [$(echo "$contracted_only" | tr '\n' ' ')]. The gateway will publish these
  services' routes and have nowhere to send them, so every screen behind one 404s against a stack
  that looks healthy. Give each a container in docker-compose.yml and an address under
  kui.gateway.services in kui.yaml -- or, if it is deliberately not routed here, name it in
  UNROUTED_CONTRACTS above with the reason."
  fi
  if [[ -n "$container_only" ]]; then
    advice="$advice
  RUN BUT NOT CONTRACTED: [$(echo "$container_only" | tr '\n' ' ')]. The container and its address
  may both be perfectly correct and are not what is missing: the gateway holds no contract for the
  service, so it publishes none of its routes whatever this file says. Add the row to
  ServiceContracts.byService in services/gateway/api -- that is the third of the three facts, and
  the one an addresses-against-containers comparison cannot see. Or remove the container."
  fi
  fail "the gateway holds contracts for [$(echo "$contracts" | tr '\n' ' ')] \
and this stack runs [$(echo "$declared" | tr '\n' ' ')].$advice"
fi
# `$expected` and not `$contracts`. The two are the same list until `UNROUTED_CONTRACTS` names one,
# and printing the wrong one meant that the moment somebody deliberately left a service out, this
# line announced it as routed -- a report that is exactly wrong in the one case the variable exists
# for. The unrouted set is printed beside it rather than folded away, because a decision taken in
# writing should be visible in the output of the check that honours it.
printf '  contracts routed: %s\n' "$(echo "$expected" | tr '\n' ' ')"
if [[ -n "$unrouted" ]]; then
  printf '  contracts deliberately not routed: %s\n' "$(echo "$unrouted" | tr '\n' ' ')"
fi

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

# ==================================================================================================
# SOMETHING TO MEASURE, AND EXACTLY TWO ASSERTIONS DEPEND ON IT.
#
# WHAT THIS BLOCK USED TO SAY IS FALSE, AND IT IS WORTH SAYING WHAT REPLACED IT. It said that on an
# idle broker only the three broker-wide `BrokerTopicMetrics` meters are served and "nothing else
# the dashboard needs is there at all" -- no `RequestMetrics{request=Produce}` bean because nothing
# has produced, none for `FetchConsumer` because nothing has consumed. Measured on this stack, cold,
# up 25 seconds, with no client of any kind having touched the broker: the exporter serves ALL
# TWELVE of the line shapes listed below. Produce and FetchConsumer p99 at `0.0`, both purgatory
# sizes at `0.0`, `requesthandleravgidlepercent` at 0.958 and `networkprocessoravgidlepercent` at
# 0.399. Kafka creates `RequestMetrics` beans eagerly, one per ApiKey it can serve; what is lazy is
# the per-TOPIC slice of `BrokerTopicMetrics`, which needs that topic's first byte.
#
# So the traffic step earns its place here for exactly two assertions and not for the twelve:
#
#   1. the per-topic byte rate the Top producers card is drawn from, which really does not exist
#      until a topic has taken a byte, and
#   2. `bytesinpersec_total > 0` and `bytesoutpersec_total > 0` -- that the numbers this run reads
#      are numbers this run caused, rather than twelve honest zeroes.
#
# That is a smaller claim than the one it replaces and it is the one the run can support. Producing
# five hundred records and reading them back costs one topic and about fifteen seconds.
#
# Through the broker's own CLI rather than through KUI's produce API. The point is to put bytes
# through Kafka, and routing them through the message service would make this step fail for reasons
# that have nothing to do with measurement -- a plan token, a principal, a serde -- on a stack whose
# subject is fault isolation.
# ==================================================================================================
log "producing a little traffic, so that there is something to measure"

readonly TRAFFIC_TOPIC="smoke-traffic"
readonly TRAFFIC_RECORDS=500

kafka_cli() {
  local tool="$1"
  shift
  "${compose[@]}" exec -T kafka "/opt/kafka/bin/$tool" "$@"
}

kafka_cli kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic "$TRAFFIC_TOPIC" --partitions 1 --replication-factor 1 >/dev/null 2>&1 ||
  fail "could not create the topic $TRAFFIC_TOPIC on the broker this stack runs"

# Padded to a couple of hundred bytes each, so the byte counters move by something a person reading
# the exposition can recognise as this step rather than as noise.
seq 1 "$TRAFFIC_RECORDS" |
  awk '{ printf "%s %s\n", $1, "smoke-traffic-payload-padding-so-the-counters-move" }' |
  "${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic "$TRAFFIC_TOPIC" >/dev/null 2>&1 ||
  fail "could not produce to $TRAFFIC_TOPIC"

# And read them back. The sentence that used to sit here said this was "the only way to make the
# broker publish a `FetchConsumer` percentile" and that a produce alone leaves the bean uncreated.
# It is not: the block above records the exposition of an idle broker, and that percentile is in it
# at `0.0`. What the read-back is actually for is `BytesOutPerSec`, which nothing else on this stack
# moves -- a produce moves bytes in and leaves bytes out at zero for ever -- and the counter is
# asserted below.
#
# THE COUNT IS READ NOW, AND IT USED TO BE THROWN AWAY. The consumer's output went to /dev/null and
# only its exit status was looked at, so a run that read four records printed exactly the same
# "500 records produced to smoke-traffic and read back" as a run that read five hundred, and no
# assertion anywhere could tell the two apart. A line the run cannot detect is false is not a
# report; deleting this whole step left a full run PASSED and still printed it.
read_back="$(kafka_cli kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic "$TRAFFIC_TOPIC" --from-beginning --max-messages "$TRAFFIC_RECORDS" \
  --timeout-ms 30000 2>/dev/null | wc -l)" ||
  fail "could not read back the $TRAFFIC_RECORDS records just produced to $TRAFFIC_TOPIC"
[[ "$read_back" == "$TRAFFIC_RECORDS" ]] ||
  fail "$read_back of the $TRAFFIC_RECORDS records produced to $TRAFFIC_TOPIC came back.
  The consumer exited without an error and returned a different number of records than the producer
  wrote, which means either the produce above did not land what it reported or this broker is not
  the one it landed on. Every measurement below is about bytes this step was supposed to move."
printf '  %s records produced to %s, %s read back\n' \
  "$TRAFFIC_RECORDS" "$TRAFFIC_TOPIC" "$read_back"

# ==================================================================================================
# THE MEASUREMENT, WHICH IS THE HALF M7 SPENT TWO WAVES UNABLE TO PROVE.
#
# The throughput endpoint answers one of `ok | stale | unavailable | not_configured`, and with no
# exporter configured it answers `not_configured`. That was the whole of M7's old exit criterion,
# and a service containing no adapter -- no JMX client, no Prometheus parser, nothing -- satisfied
# every clause of it, because the only thing it asserted was a refusal and a refusal is what the
# absence of the code produces. A criterion whose positive case cannot be distinguished from the
# missing feature is a criterion that has never tested anything.
#
# So both halves are asserted here, on one deployment, and the first one is the one that matters:
# `measured` has an exporter named under `kui.metrics.sources` and must come back `ok` with a real
# number in it, and `unmeasured` -- the same broker, no entry -- must come back `not_configured`.
# ==================================================================================================

# ==================================================================================================
# THE METRIC NAMES ARE THE CONTRACT, AND UNTIL WAVE 5 TWO OF THEM WERE CHECKED.
#
# `../metrics/kafka-jmx-exporter.yml` calls its name list "a CONTRACT with services/metrics's
# Prometheus reader" and it is one: the reader looks a family up by name, and a family that has been
# renamed is byte-for-byte indistinguishable, to that reader, from a family the broker does not
# publish. It answers honestly that it cannot measure the thing, which is the correct rendering of
# the wrong situation.
#
# Nothing could see that. This step used to count the two byte-rate families and stop, and the two
# stacks' healthchecks grep one of those two. So `MessagesInPerSec` could be renamed here, or any of
# the `_total` rules, and every gate in this repository stayed green while `recordsPerSecond` was
# null on every bucket for ever -- which the bucket assertion below cannot see either, because it
# tests `bytesInPerSecond != null` and that is a different family.
#
# So: every name, by literal string, written out here rather than derived from the ruleset. A
# derivation would rename itself alongside the rule and assert nothing, which is the shape of gate
# this wave exists to stop shipping. Two lists that have to be edited together is the cost, and it
# is the point: this is the only place in the repository where a real exporter, a real broker and a
# real KUI process are in the same room, so it is the only place the contract can actually be read.
#
# It is asked from inside a container because `kafka-metrics` publishes no port to the host -- the
# exposition is for KUI, not for a person. And it is asked before any assertion about KUI's adapter,
# because the two failures need separating: if this passes and the next one does not, the sidecar is
# fine and KUI is the problem; if this fails, no assertion about KUI's adapter means anything.
# ==================================================================================================
# LINE SHAPES AND NOT FAMILY NAMES, because half the contract is in the labels.
#
# `PrometheusExposition` matches each family against an EXACT set of dimensions -- the broker-wide
# rates against no label at all, the p99 against `{request}`, the purgatory against
# `{delayedoperation}`, the per-topic rate against `{topic}`. So a rule that kept its name and lost
# its label, or kept its name and gained one, serves a line this script would have found and the
# reader will not. The trailing space in the unlabelled patterns is what says "no labels": in the
# exposition format the name is followed by `{` when there are labels and by a space when there are
# none.
#
# This was not a hypothetical. The first version of the widened ruleset spelled the request kind
# into the metric name -- `..._totaltimems_produce_99thpercentile` -- which is a perfectly good
# Prometheus name, is what the existing throughput rules do with `name=`, and is invisible to a
# reader testing `dimensionsOf(sample) == Set("request")`. It served, this script found the family,
# and the latency card would have said it could not measure a number that was on the wire.
readonly EXPORTER_LINES=(
  # Throughput, broker-wide. The Traffic tab's chart is drawn from the three rates; the three
  # counters are there for an adapter that would rather difference a monotonic total than trust a
  # moving average. No dimension on any of them: that is how the reader tells the broker's own
  # figure from a per-topic slice of it.
  '^kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate '
  '^kafka_server_brokertopicmetrics_bytesoutpersec_oneminuterate '
  '^kafka_server_brokertopicmetrics_messagesinpersec_oneminuterate '
  '^kafka_server_brokertopicmetrics_bytesinpersec_total '
  '^kafka_server_brokertopicmetrics_bytesoutpersec_total '
  '^kafka_server_brokertopicmetrics_messagesinpersec_total '
  # The p99 latency card, produce and fetch as two series on one chart. The label value keeps the
  # broker's capitalisation -- `lowercaseOutputLabelNames` lower-cases label names only -- and the
  # reader lower-cases before comparing, so `Produce` here and `produce` there is correct.
  '^kafka_network_requestmetrics_totaltimems_99thpercentile\{request="Produce"\} '
  '^kafka_network_requestmetrics_totaltimems_99thpercentile\{request="FetchConsumer"\} '
  # The ring gauges: two ratios in 0..1, undimensioned.
  '^kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent_oneminuterate '
  '^kafka_network_socketserver_networkprocessoravgidlepercent '
  # And the queue length that is not a ratio, one line per delayed operation.
  '^kafka_server_delayedoperationpurgatory_purgatorysize\{delayedoperation="Produce"\} '
  '^kafka_server_delayedoperationpurgatory_purgatorysize\{delayedoperation="Fetch"\} '
)

log "the broker beside this stack is measurable, and KUI measures it"

exposition="$("${compose[@]}" exec -T kui-gateway \
  curl -fsS http://kafka-metrics:5556/metrics 2>/dev/null || true)"
# A BETTER MESSAGE, AND NOT THE GUARD ITS OWN COMMENT CLAIMED. This used to say it was "the same
# guard the image preflight has, for the same reason: a loop over an empty body runs zero times and
# reports that every family was found". The hazard is real where it was copied from and cannot occur
# here: the loop below is over `EXPORTER_LINES`, twelve fixed patterns, not over the body. With an
# empty body every one of the twelve `grep -qE` calls fails and the step fails loudly naming all
# twelve. Checked by running it against an empty string.
#
# So this line is kept for what it does do -- turn twelve confusing "served nothing matching" lines
# into one sentence saying the exporter answered nothing at all, which is a different fault with a
# different fix -- and the claim that it is load bearing is gone.
[[ -n "$exposition" ]] || fail "the JMX exporter at kafka-metrics:5556 answered nothing at all.
  Every assertion below reads that body. This is a sidecar that is not serving, not a rule that was
  renamed: check that kafka-metrics is up and that it can reach the broker's JMX port."

# Every pattern is anchored at `^`, which is doing real work rather than being tidy: without it
# `..._bytesinpersec_oneminuterate` would be reported as present by a line for
# `..._replicationbytesinpersec_oneminuterate`, which is a different and much smaller number, and
# is the same trap `PrometheusExposition.isAttribute` guards against on the reading side.
missing=""
for line in "${EXPORTER_LINES[@]}"; do
  grep -qE "$line" <<<"$exposition" || missing="$missing
    $line"
done
[[ -z "$missing" ]] || fail "the JMX exporter served nothing matching:$missing
  Every shape in EXPORTER_LINES in this script is produced by ../metrics/kafka-jmx-exporter.yml and
  by nothing else in this repository, and is read by name and by label in services/metrics. One
  missing here is a card that will say it cannot measure something, on a broker that publishes it.
  Either a rule in that file was renamed or re-dimensioned without changing this list, or the broker
  stopped publishing the bean behind it -- \`docker compose -f $here/docker-compose.yml exec -T
  kui-gateway curl -s http://kafka-metrics:5556/metrics\` shows what it did serve."
printf '  the exporter serves all %s line shapes the reader reads\n' "${#EXPORTER_LINES[@]}"

# And the line that is not a family of its own. Top producers is drawn from the SAME family as the
# broker-wide byte rate, dimensioned by a `topic` label -- Kafka's own convention, and what
# `PrometheusExposition`'s aggregates-and-slices rule was written against. The list above cannot see
# it: the unlabelled aggregate satisfies that pattern on its own, so the per-topic rule could be
# deleted whole and every one of the twelve shapes above would still be found. (Twelve, which is
# what `${#EXPORTER_LINES[@]}` prints two lines up. This said thirteen, and so did
# ../compose/README.md, against an array nothing had counted.)
#
# It is asserted here and not at start-up because the traffic step above is what makes it exist:
# Kafka creates a per-topic `BrokerTopicMetrics` bean on that topic's first byte and not before, so
# on an idle broker the correct answer really is that this family has no labelled line at all.
per_topic='^kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate\{topic='
[[ "$(grep -cE "$per_topic" <<<"$exposition")" -gt 0 ]] ||
  fail "the exporter served no per-topic byte rate.
  Expected at least one kafka_server_brokertopicmetrics_bytesinpersec_oneminuterate{topic=\"...\"}
  line, which is the only figure a broker publishes that the Top producers card can be drawn from.
  It comes from the third rule in ../metrics/kafka-jmx-exporter.yml; the aggregate line above it
  satisfies the family check on its own, so nothing else in this repository would notice its loss."
printf '  the exporter serves the per-topic byte rate the producers card needs\n'

# And that the records really arrived, which is what makes every line above a measurement of
# something rather than a measurement of nothing. The monotonic counter and not the one-minute rate:
# the rate is a Yammer EWMA that ticks every five seconds, so a scrape taken immediately after a
# produce can honestly read `0.0` and asserting on it would be a flake. `_total` is exact from the
# first byte, and the traffic step above is the only thing on this stack that can move it.
# The brace group is not decoration, and it is the same defect `connect-seed.sh` carried (TD-053):
# an exporter that has not published this series yet makes `grep` exit 1, `pipefail` carries that
# out of the command substitution, and `set -e` kills this script ON THIS LINE -- two lines above
# the `fail` written to explain exactly that state, and with nothing printed. The smoke run would
# then report a missing series as a silent non-zero exit. `|| true` makes "matched nothing" an
# empty string, which is what the `[[ -n ... ]]` below is already written to catch.
bytes_in="$({ grep -E '^kafka_server_brokertopicmetrics_bytesinpersec_total ' <<<"$exposition" ||
  true; } | awk '{ print $2 }')"
[[ -n "$bytes_in" ]] && awk -v v="$bytes_in" 'BEGIN { exit !(v > 0) }' ||
  fail "the broker reports $bytes_in bytes in since it started, after $TRAFFIC_RECORDS records were
  produced to $TRAFFIC_TOPIC and read back. Either the produce above did not reach this broker, or
  the exporter is attached to a different JVM than the one KUI's cluster service is writing to."
printf '  the broker has taken %s bytes in since it started\n' "$bytes_in"

# And out, which is the counter the READ-BACK moves and the only thing on this stack that moves it.
# Without this line the consume step had no assertion of its own at all: it could be deleted whole,
# and the run stayed green with all twelve line shapes served, because the percentile its comment
# said it created is published from boot.
bytes_out="$({ grep -E '^kafka_server_brokertopicmetrics_bytesoutpersec_total ' <<<"$exposition" ||
  true; } | awk '{ print $2 }')"
[[ -n "$bytes_out" ]] && awk -v v="$bytes_out" 'BEGIN { exit !(v > 0) }' ||
  fail "the broker reports $bytes_out bytes out since it started, after $read_back records were read
  back from $TRAFFIC_TOPIC. A produce alone leaves this counter at zero, so either the consume above
  read from somewhere else or this exporter is attached to a different broker than that consumer."
printf '  the broker has served %s bytes out since it started\n' "$bytes_out"

# Then KUI. `await` and not a single call: the scrape loop runs at `kui.metrics.scrapeInterval`, so
# a stack that came up two seconds ago has correctly sampled nothing yet, and the honest answer at
# that instant is a series with no values in it. What is asserted is that it fills, not that it was
# full immediately.
throughput() {
  printf "curl -sf '%s/api/v1/clusters/%s/metrics/throughput?range=24h' | jq -r '%s'" \
    "$base" "$1" "$2"
}
await "the measured cluster's throughput" "ok" "$(throughput measured .throughput.status)"

# A status of `ok` over an empty series would still be a chart nobody can read, so the series itself
# is asserted: at least one bucket carrying a rate that is not null. A bucket that was never sampled
# is null and stays null -- it is a gap in the line and never a zero -- which is why this counts
# non-null buckets rather than checking the array's length.
#
# EVERY FIELD A BUCKET CARRIES, AND NOT ONLY THE FIRST OF THEM. This asked about `bytesInPerSecond`
# and nothing else, which is one of the three families the throughput reader parses. A KUI-side
# regression that stopped reading `messagesinpersec` -- the family behind `recordsPerSecond` -- left
# this job entirely green while the Traffic tab drew a records line made of nulls, and the exporter
# check above cannot see it either: that check asserts the family is on the wire, which is exactly
# the state a broken reader is in. Three `await`s and not one compound expression, so the failure
# names the field.
for field in bytesInPerSecond bytesOutPerSecond recordsPerSecond; do
  await "buckets carrying a measured $field" "yes" \
    "$(throughput measured \
      "if [.throughput.data.buckets[]? | select(.$field != null)] | length > 0
        then \"yes\" else \"no\" end")"
done

# And the other half, on the same deployment and the same broker: a cluster with no
# `kui.metrics.sources` entry says so. This is the assertion M7's criterion has been passing on
# alone for two milestones; it is correct and it is worth nothing without the two above it.
await "the unmeasured cluster's throughput" "not_configured" \
  "$(throughput unmeasured .throughput.status)"

# ==================================================================================================
# AND THE OTHER FOUR DOCUMENTS, BECAUSE THE THROUGHPUT ONE WAS NEVER THE WHOLE READER.
#
# `services/metrics` serves five documents from one exposition, and until now this script asserted
# one of them. The four below read the request-latency percentiles, the two idle ratios and the
# purgatory queues, the per-topic byte rates and the record-size mean -- families that are asserted
# to be ON THE WIRE by EXPORTER_LINES above and were asserted to be READ by nothing at all. That
# gap is the whole distance between "the sidecar publishes it" and "a card can draw it", and this
# stack is the only place in the repository where both halves are running.
#
# The latency document names its two fields because they are settled; the last three are asserted
# as "the document carries at least one number" and deliberately not field by field. Their payload
# shapes are being moved this wave (ADR-052, and the browser reads names that do not match the
# server's), so a second copy of those names here would be a contract written down in the one place
# whose owner is not the one changing it. What cannot move is that a document whose status is `ok`
# has to carry a figure: an `ok` over an empty object is the exact failure the Top producers card is
# in today, and it is a failure this shape does catch.
metric() {
  printf "curl -sf '%s/api/v1/clusters/measured/metrics/%s' | jq -r '%s'" "$base" "$1" "$2"
}

for field in produceP99Millis fetchP99Millis; do
  await "latency buckets carrying a measured $field" "yes" \
    "$(metric 'latency?range=24h' \
      "if [.latency.data.buckets[]? | select(.$field != null)] | length > 0
        then \"yes\" else \"no\" end")"
done

carries_a_number() {
  printf 'if [%s | .. | numbers] | length > 0 then "yes" else "no" end' "$1"
}

await "the request-handlers document carries a figure" "yes" \
  "$(metric request-handlers "$(carries_a_number .requestHandlers.data)")"
await "the top-producers document carries a figure" "yes" \
  "$(metric producers "$(carries_a_number .producers.data)")"
await "the record-size document carries a figure" "yes" \
  "$(metric record-size "$(carries_a_number .recordSize.data)")"

# ==================================================================================================
# THE NINTH SERVICE, AND THE ONE THING AN ALERTS FEED MUST NOT DO.
#
# This stack's broker is healthy, so the honest answer here is an empty feed: no offline partition,
# no under-replicated partition, no group stuck rebalancing. That is a measured zero and it is a
# perfectly good answer -- but it is also exactly what a service that ran no rules at all would
# answer, and what a service pointed at a broker it cannot reach would answer if it were willing to
# invent one. So the assertion is not about the events. It is about the evidence beside them:
#
#   * `evaluatedAt` is set, which says the rules have actually run against this broker;
#   * `rules` is non-empty, so the document names what was evaluated rather than only the result;
#   * at least one of those rules reports `ok`, which is a rule that read the facts it needed.
#
# ADR-053 puts a `Section` on each rule rather than one on the document for this reason: a rule
# whose facts could not be read says so and costs one row, and a feed of zeros from a service that
# could measure nothing is distinguishable from a feed of zeros from a healthy cluster. A zero that
# cannot be told apart from a refusal is the failure this whole product is written against.
log "the ninth service answers, and says what it evaluated"

alerts() { printf "curl -sf '%s/api/v1/clusters/measured/alerts/events' | jq -r '%s'" "$base" "$1"; }

await "the alerts feed" "ok" "$(alerts .events.status)"
await "the alerts feed says when the rules last ran" "yes" \
  "$(alerts 'if .events.data.evaluatedAt == null then "no" else "yes" end')"
await "the alerts feed names the rules it evaluated" "yes" \
  "$(alerts 'if [.events.data.rules[]?] | length > 0 then "yes" else "no" end')"
await "at least one alert rule read the facts it needs" "yes" \
  "$(alerts 'if [.events.data.rules[]? | select(.evaluation.status == "ok")] | length > 0
      then "yes" else "no" end')"

# ==================================================================================================
# THE TENTH SERVICE, AND THE THREE ANSWERS ITS SCREENS MUST NOT CONFUSE.
#
# A Connect screen has three honest states and they are told apart by the `Section` status alone:
#
#   * `not_configured` -- no cluster in `kui-service.yaml` names a `connect:` worker;
#   * an upstream refusal -- a worker is configured and will not answer;
#   * `ok` with an empty list -- a worker answered and is running no connectors.
#
# This stack is the third. `kafka-connect` is a real Kafka Connect worker started by
# `docker-compose.yml` and nothing registers a connector into it, so the honest answer here is an
# empty list, and asserting on its emptiness would assert nothing: a service that never called the
# worker at all answers the same shape. What separates them is the STATUS -- but it has to be the
# PER-WORKER status, and until this was measured it was the outer one.
#
# THE OUTER STATUS IS `ok` WITH NO WORKER REACHABLE AT ALL. Measured on this stack, with both
# `connect.url` entries in `kui-service.yaml` pointed at a host that does not resolve:
#
#   GET .../connect/connectors                    HTTP 200
#   to_entries[0].value.status                    "ok"
#   ...data.workers[0].connectors.status          "unavailable"
#   ...data.workers[0].connectors.reason          "UPSTREAM_UNAVAILABLE"
#   /api/v1/capabilities, connect, cluster null   "available"
#
# So the old assertion here -- `to_entries[0].value.status` == "ok" -- passed against a deployment
# with no worker it could reach, and so did the capability row above it. Wave 7's verification
# measured the same pass by stopping the container instead. The outer status separates
# `not_configured` from the other two states and nothing separated those two from each other, which
# is two of the three states enumerated above asserted by nothing, in the one script whose job is to
# notice that a service is unreachable.
#
# The per-worker assertion below fails on that arrangement: `every Connect worker the cluster names
# answered was 'unavailable' after 90s, expected 'ok'`.
#
# The per-worker section is where a refusal is reported, so that is what is asserted.
# ==================================================================================================
# EVERY CONTRACTED SERVICE ANSWERS A REAL READ, AND NOT ONLY A CAPABILITY PROBE.
#
# THE HOLE THIS CLOSES, MEASURED IN WAVE 9, BOTH DIRECTIONS. Change `KUI_PRINCIPAL_KEY` on
# `kui-topic` in `docker-compose.yml` so that it no longer matches the gateway's, and this script
# printed `PASSED` in 107 seconds with `topic capability: available` twice -- while
# `GET /api/v1/clusters/measured/topics` answered `HTTP 401 KUI-UNAUTHENTICATED` and
# `/consumer-groups`, whose service still had the right key, answered 200. The product was broken on
# the one screen most people open first and the smoke test was green.
#
# It is green because a capability row is not a read. `/api/v1/capabilities` reflects readiness --
# the gateway's own poll of a service's health endpoint, which carries no signed principal -- so it
# says the process is up and answering, and says nothing at all about whether the gateway is allowed
# to ask it a question. Six of the nine contracted services were covered by nothing else: `connect`
# had the read below, `metrics` and `alerts` had theirs, and `cluster`, `topic`, `consumer`,
# `schema`, `ksql` and `message` had a probe and a container name.
#
# So every contract gets a read, and the loop is over `$expected` -- the derived contract list --
# rather than over a roster written here.
#
# WRITING THE PATHS HERE WOULD BE A NINTH COPY OF SOMEBODY ELSE'S DECISION AND THE FIRST ONE NOTHING
# CHECKS. That reasoning is `connect_read_path`'s, which stood for one service and is now this
# function for all of them: the whole defect this file exists to catch is a service that is
# complete, imaged and unroutable, and a hard-coded `/api/v1/clusters/measured/topics` cannot see
# it, because a gateway publishing none of the topic contract answers 404 for the path this script
# invented -- indistinguishable from the path simply being wrong. Read off the running gateway
# instead and an empty derivation IS the failure, with a message that can say so.
#
# WHICH read is derived too, from the gateway's own merged document, by the service tag each
# operation already carries. Three filters, and each one is a fact the document states rather than a
# guess:
#
#   * the operation is tagged with this service;
#   * its 200 is not `text/event-stream` -- a stream does not end, and `curl -sf` on one would hang
#     until this script's own timeout rather than report a status. `ksql` is why this filter is not
#     optional: `/ksql/stream` is one character shorter than `/ksql/objects`, so "the shortest read"
#     picks the stream without it;
#   * its path templates nothing but `{clusterId}` -- a per-connector or per-subject route needs a
#     name this stack has none of.
#
# and of what survives, the shortest is the collection read.
service_read_path() {
  local service="$1"
  printf '%s' "$merged" |
    jq -r --arg service "$service" '
      .paths | to_entries[]
      | select(.value.get != null)
      | select(((.value.get.tags // []) | index($service)) != null)
      | select(((.value.get.responses["200"].content // {}) | keys
                | index("text/event-stream")) == null)
      | .key
      | select(test("^/api/v1/clusters/\\{clusterId\\}(/[A-Za-z0-9._-]+)*$"))' |
    awk '{ print length, $0 }' | LC_ALL=C sort -n | head -1 | cut -d' ' -f2-
}

# SELF-TESTED, for the reason every derivation in this file is: on a passing run every service has a
# path and the failure modes are all invisible. A tag filter that matched everything, a stream
# filter inverted, or a regexp that rejected the bare `/api/v1/clusters/{clusterId}` would each
# still answer *a* path for every service, and the loop below would still go green -- against the
# wrong endpoint. The fixture is a made-up document carrying exactly the three situations the three
# filters exist for.
merged="$(cat <<'FIXTURE'
{"paths":{
  "/api/v1/clusters/{clusterId}/probe/stream":
    {"get":{"tags":["probe"],"responses":{"200":{"content":{"text/event-stream":{}}}}}},
  "/api/v1/clusters/{clusterId}/probe/items/{itemId}":
    {"get":{"tags":["probe"],"responses":{"200":{"content":{"application/json":{}}}}}},
  "/api/v1/clusters/{clusterId}/probe/items":
    {"get":{"tags":["probe"],"responses":{"200":{"content":{"application/json":{}}}}}},
  "/api/v1/clusters/{clusterId}/other":
    {"get":{"tags":["other"],"responses":{"200":{"content":{"application/json":{}}}}}}
}}
FIXTURE
)"
probe="$(service_read_path probe)"
[[ "$probe" == "/api/v1/clusters/{clusterId}/probe/items" ]] || fail "the read-path derivation is
  broken: a document whose \`probe\` tag carries an event stream, a templated item route and a
  collection derived [$probe] and not /api/v1/clusters/{clusterId}/probe/items. Check the tag
  filter, the text/event-stream filter and the template regexp in service_read_path."
# And that the tag filter is a filter: asking for a service the document does not tag answers
# nothing, which is what makes an empty derivation below a real failure rather than a typo here.
probe="$(service_read_path absent)"
[[ -z "$probe" ]] || fail "the read-path derivation ignores its service tag: asking for a service
  the document does not mention answered [$probe] instead of nothing."

merged="$(curl -sf "$base/api/v1/openapi.json")" ||
  fail "the gateway's merged OpenAPI document could not be read at $base/api/v1/openapi.json"

log "the tenth service answers, on a path the gateway derives rather than one written here"

await "the connect capability" "available" "$(status_of connect)"

connect_path="$(service_read_path connect)"
[[ -n "$connect_path" ]] || fail "the gateway publishes no readable connect path at all.
  Every /api/v1/clusters/{clusterId}/connect... GET was expected in the merged document at
  $base/api/v1/openapi.json and none is there. The container is running and its capability row is
  above, so this is the third of the three facts: the contract is in ServiceContracts.byService and
  the routes are derived from it, or it is not and this service is reachable by nothing."
printf '  the gateway publishes the connect read at: %s\n' "$connect_path"

# `to_entries[0].value` and not a field name. Every KUI read answers one `Section` under one key
# (ADR-034), and which key this one is belongs to `services/connect`'s contract; naming it here
# would be a fifth copy of somebody else's decision, and one that goes stale silently -- a `jq`
# selecting a key that is not there answers `null`, and `await` would spend ninety seconds on it
# and then report `null` rather than reporting the rename. Below that key the field names ARE
# written out, because the per-worker status is the only place a refusal is reported and there is
# no way to reach it without naming the path to it.
#
# `unique | join(",")` and not a boolean: a failure then prints what the workers actually said --
# `unavailable`, or `ok,unavailable` on a stack where one of several is down -- instead of `false`.
# The empty case is named for the same reason: a document that carried no worker section at all
# would otherwise render as an empty string, which reads as the curl having failed.
await "every Connect worker the cluster names answered" "ok" \
  "curl -sf '$base${connect_path/\{clusterId\}/measured}' |
     jq -r '[to_entries[0].value.data.workers[]?.connectors.status]
            | unique | if length == 0 then \"no worker section\" else join(\",\") end'"

log "every contracted service answers a signed read, and not only a capability probe"

# `message` publishes exactly one GET and it is the browse stream, so it has no path the loop's
# filters can keep -- both the stream filter and the `{topicName}` template exclude it. It is
# covered here rather than skipped, because "the browse stream answers" is the assertion that
# separates a message service the gateway may talk to from one it may not, and that is the whole
# subject of this block. `limit=1` is what makes an endless stream end; the topic is the one the
# traffic step above created, so this is a read of bytes this run produced.
#
# A second reason to write it out: it is the only read here whose path carries a name, and a name is
# the thing a derivation cannot supply.
readonly STREAM_READS="message:\
/api/v1/clusters/{clusterId}/topics/$TRAFFIC_TOPIC/messages/stream?limit=1"

# The name of the first event an SSE response frames, or nothing if it framed none.
#
# THE STATUS CODE IS NOT THE ASSERTION FOR A STREAM, AND THAT IS WHY THIS EXISTS. An SSE endpoint
# answers `HTTP/1.1 200 OK` the moment the gateway decides to open the stream, which is before it
# has asked the service anything, so every refusal downstream arrives in the BODY at status 200.
# Measured by W10-05's verifier on this stack, with `kui-message`'s `KUI_PRINCIPAL_KEY` set to a
# wrong value: the loop below printed `message answers .../messages/stream?limit=1: 200` and the
# script PASSED, while the body it never read was
# `event: error` / `data: {"code":"KUI-UPSTREAM-UNAVAILABLE","message":"message could not be
# reached",...}`. The one service whose read had to be hand-written above was the one service this
# block could not fail on -- which is the wave-9 defect the block was added to close, surviving in
# the ninth of nine.
#
# `-N` disables curl's output buffering, so the first frame is readable without waiting for a
# stream that is designed not to end; `head -c` bounds what is read rather than what is sent, and
# the `-m` bounds the rest. `tr -d '\r'` because SSE frames are CRLF-terminated on the wire and a
# trailing carriage return would make every comparison below fail against a correct stream. The
# first `event:` line and not a grep for `error`: a stream that frames `phase` first and errors
# later is a different report from one that refuses outright, and only the second is this
# assertion's business.
#
# `awk` remembering rather than `sed ... | head -1`: this file runs under `pipefail`, and a stage
# that quits on the first match sends SIGPIPE to the stage above it, so the happy path would exit
# 141 and the whole derivation would be a coin toss on how the kernel scheduled two tiny processes.
# Reading all four hundred bounded bytes and printing once is the same answer without the race.
first_sse_event() {
  head -c 400 | tr -d '\r' | awk '/^event: / && !seen { print substr($0, 8); seen = 1 }'
}

# SELF-TESTED, for the reason every derivation in this file is: on a passing run the stream frames
# `phase` and the failure modes are invisible. A `sed` whose anchor had been lost would match the
# `event:` inside a `data:` payload; one whose `head -1` had been dropped would answer three names
# and compare as a mismatch against every one of them; and a filter that read the body without
# stripping CR would answer `phase\r`, which is not `phase`. The fixture is the shape this stack
# actually frames, CRLF and all.
frame="$(printf 'event: phase\r\ndata: {"event":"error","phase":"assigned"}\r\n\r\nevent: consumed\r\n' |
  first_sse_event)"
[[ "$frame" == "phase" ]] || fail "the first-frame derivation is broken: a stream framing \`phase\`
  and then \`consumed\`, with a \`data:\` payload that itself contains the word event, derived
  [$frame] and not [phase]. Check the anchor, the carriage-return strip and the head -1 in
  first_sse_event."
# And that it can say `error`, which is the whole point: a derivation that could only ever answer
# the happy name would pass this block on a refusal exactly as the status code did.
frame="$(printf 'event: error\r\ndata: {"code":"KUI-UPSTREAM-UNAVAILABLE"}\r\n' | first_sse_event)"
[[ "$frame" == "error" ]] || fail "the first-frame derivation cannot see a refusal: a stream whose
  first frame is \`event: error\` derived [$frame] and not [error], so the stream read below would
  pass over the exact failure it exists to catch."

for service in $expected; do
  path="$(service_read_path "$service")"
  streamed=""

  if [[ -z "$path" ]]; then
    # Every service whose only read is a stream is named above with its substitution. One that is
    # not named has no read at all in the gateway's document, which is the failure this block is
    # for: the contract exists, the container is up, and there is nothing a browser could ask it.
    written="$(printf '%s\n' "$STREAM_READS" | grep "^$service:" | cut -d: -f2- || true)"
    [[ -n "$written" ]] || fail "the gateway publishes no readable path for \`$service\` at all.
  Every GET tagged \`$service\` in $base/api/v1/openapi.json was either an event stream or needed a
  name this stack has none of. Its capability row is available and its container is up, so this is
  the third fact: the contract is in ServiceContracts.byService and its routes are derived from it,
  or it is not and this service is reachable by nothing."
    path="$written"
    streamed="yes"
  fi

  # `%{http_code}` rather than `curl -sf`, because the number is the assertion. A 401 and a 404 and
  # a 503 are three different repairs and `curl -sf` reports all three as "it failed"; the wave-9
  # defect this block exists for was a 401, and a message that says `401` sends a reader to the
  # principal key rather than to the routing table.
  #
  # `await` and not a bare curl: a service can be routable and still be finishing its first scrape,
  # and every other assertion in this file gives that the same ninety seconds.
  await "$service answers $path" "200" \
    "curl -s -o /dev/null -m 20 -w '%{http_code}' '$base${path/\{clusterId\}/measured}'"

  # AND FOR A STREAM, THE STATUS CODE IS NOT THE READ. The assertion above has just proved that the
  # gateway agreed to open the stream; what it cannot prove is that anything was on the other end
  # of it. A stream whose first frame is `error` is a refusal delivered at 200, and it is the exact
  # state a wrong signing key on `kui-message` produces.
  #
  # `phase` rather than "not error": the browse stream's contract (ADR-035) is that it announces
  # the phase it has reached before it sends a record, so the honest first frame is a fact about
  # this stream and not the absence of one bad word. A stream that started framing something else
  # would be a contract change, and a smoke test that shrugged at it would be checking nothing.
  if [[ -n "$streamed" ]]; then
    await "$service frames a stream rather than refusing at 200" "phase" \
      "curl -sN -m 20 '$base${path/\{clusterId\}/measured}' | first_sse_event"
  fi
done

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

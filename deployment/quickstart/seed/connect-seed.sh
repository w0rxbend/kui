#!/usr/bin/env bash
#
# Puts one connector onto the quickstart's Kafka Connect worker, and does not exit until that
# connector and its task are both RUNNING.
#
# ---------------------------------------------------------------------------------------------
# WHY THIS EXISTS, AND WHY THE COMPOSE FILE USED TO SAY THE OPPOSITE
# ---------------------------------------------------------------------------------------------
#
# `docker-compose.quickstart.yml` used to carry the sentence "NOTHING REGISTERS A CONNECTOR AND
# NOTHING SHOULD", on the argument that a worker running zero connectors is a state the Connect
# screens have to render honestly and that seeding one would erase it. Half of that is right and
# the conclusion was wrong, and the evidence is in the browser suite: `frontend/e2e/connect.spec.ts`
# has two positive cases -- a connector's rows are listed, and its tasks carry a state -- and they
# used to skip themselves on a stack with nothing to list, which was every stack that existed. The
# empty-worker state is still covered, by `deployment/compose/docker-compose.yml`, whose worker runs
# nothing and whose `smoke.sh` asserts exactly that shape. What was covered nowhere was a worker
# with something on it, which is every Connect deployment anybody actually runs.
#
# So the two stacks now hold the two states between them: compose is the empty worker, the
# quickstart is the busy one.
#
# AND THE SKIP IS GONE, WHICH MAKES THIS SEED LOAD-BEARING RATHER THAN DECORATIVE. Wave 9 rewrote
# that suite so that nothing in it skips: `grep -n 'test.skip' frontend/e2e/connect.spec.ts`
# answers only its own header describing the old shape, and the two cases now open with
# `expect(items.length, "no Connect worker on this stack is running a connector, …").
# toBeGreaterThan(0)`. A quickstart that comes up without this container therefore turns the
# browser suite RED rather than green-with-skips -- which is the right answer, and it is worth
# knowing before anybody removes the `connect-seed` service to make a bring-up faster.
#
# ---------------------------------------------------------------------------------------------
# WHY THIS CONNECTOR
# ---------------------------------------------------------------------------------------------
#
# `FileStreamSourceConnector` ships inside `apache/kafka:4.3.1` -- the image the worker already
# runs -- so the quickstart gains a connector and no download and no plugin mount. It is pointed
# at `/opt/kafka/LICENSE`, a file that is in the image, is a few hundred lines long and never
# changes, which gives the connector a task that starts, reads, commits offsets and then sits
# RUNNING with nothing further to do. That is the shape the screens need: a name, a class, a
# worker id, one task and a state, all of them read back out of the worker's own REST API rather
# than invented here.
#
# ---------------------------------------------------------------------------------------------
# THE CONTRACT WITH WHOEVER RUNS THIS
# ---------------------------------------------------------------------------------------------
#
#   Image        anything with bash and curl. The Compose file uses the registry's own image,
#                apicurio/apicurio-registry:3.0.6, for the reason `avro-seed.sh` gives at length:
#                `apache/kafka` is Alpine with busybox wget and no curl at all, and that image is
#                already being pulled by this same file.
#   Entrypoint   /bin/bash, with this script's path as the argument.
#   Environment  KUI_CONNECT_URL       -- required, e.g. http://kafka-connect:8083
#                KUI_CONNECT_NAME      -- optional, default quickstart-file-source
#                KUI_CONNECT_TOPIC     -- optional, default connect.file.lines
#                KUI_CONNECT_FILE      -- optional, default /opt/kafka/LICENSE (a path inside the
#                                         WORKER's filesystem, not this container's)
#                KUI_SEED_TIMEOUT_SECONDS -- optional, default 120
#   Exit code    0 when the connector and at least one task report RUNNING. Non-zero, with the
#                worker's own status document printed, otherwise.
#
# Re-running it is safe and is the point of `PUT .../config` rather than `POST /connectors`: the
# second form answers 409 when the connector exists, so a restarted quickstart would fail here on
# a stack that is in exactly the state this script wants.

set -euo pipefail

CONNECT_URL="${KUI_CONNECT_URL:?KUI_CONNECT_URL is required, e.g. http://kafka-connect:8083}"
NAME="${KUI_CONNECT_NAME:-quickstart-file-source}"
TOPIC="${KUI_CONNECT_TOPIC:-connect.file.lines}"
SOURCE_FILE="${KUI_CONNECT_FILE:-/opt/kafka/LICENSE}"
TIMEOUT_SECONDS="${KUI_SEED_TIMEOUT_SECONDS:-120}"

say() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }

# The worker is `depends_on: service_healthy` in the Compose file, so it has already answered a
# GET /connectors once before this container starts. This loop is for the case Compose cannot
# cover: somebody running the script by hand against a worker that is still coming up.
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
until curl -sf "${CONNECT_URL}/connectors" >/dev/null 2>&1; do
  [ "$(date +%s)" -lt "${deadline}" ] \
    || die "the worker at ${CONNECT_URL} did not answer GET /connectors within ${TIMEOUT_SECONDS}s"
  sleep 2
done
say "the Connect worker at ${CONNECT_URL} is answering"

# StringConverter on both halves, overriding the worker's JSON default. A FileStream source emits
# a null key and a String value; left on `JsonConverter` with `schemas.enable=true` the value
# arrives as `{"schema":{"type":"string",...},"payload":"..."}`, which is a wrapper nobody reading
# the messages screen asked for. The override is per-connector and changes nothing for anything
# else that may be registered later.
config="$(cat <<JSON
{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
  "tasks.max": "1",
  "file": "${SOURCE_FILE}",
  "topic": "${TOPIC}",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter"
}
JSON
)"

# The status code and the body, both. A Connect rejection is a 400 whose BODY names the offending
# configuration key, so `-f` -- which throws the body away and leaves a bare exit code 22 -- turns a
# precise message into a number. `--fail-with-body` would do exactly this in one flag and is NOT
# usable here: it arrived in curl 7.76 and the registry image this runs in carries an older one,
# which answers `option --fail-with-body: is unknown` and fails every run of this seed.
body="$(mktemp)"
code="$(curl -sS -o "${body}" -w '%{http_code}' -X PUT \
  -H 'Content-Type: application/json' \
  --data "${config}" \
  "${CONNECT_URL}/connectors/${NAME}/config" || true)"
case "${code}" in
  2*) ;;
  *) die "the worker answered HTTP ${code:-<nothing>} to the connector configuration:
$(cat "${body}")" ;;
esac
rm -f "${body}"
say "registered ${NAME} reading ${SOURCE_FILE} into ${TOPIC}"

# WAITING FOR RUNNING IS NOT PEDANTRY. `PUT .../config` answers 201 as soon as the configuration
# is written to the config topic; the connector is then still UNASSIGNED, and a task that fails to
# start -- a source file that is not there, a converter that cannot be loaded -- turns up as FAILED
# a second or two later with the 201 already returned. A seed that exited on the 201 would leave a
# quickstart whose Connect screen shows a connector nobody can explain, which is worse than no
# connector at all.
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
announced=""
while :; do
  status="$(curl -sS "${CONNECT_URL}/connectors/${NAME}/status" 2>/dev/null || true)"
  connector_state="$(printf '%s' "${status}" |
    sed -n 's/.*"connector":{"state":"\([A-Z]*\)".*/\1/p')"
  # `grep -o | wc -l` and not `grep -c`: the whole status document is one line, so `grep -c` would
  # answer 1 however many RUNNING states are in it. Two occurrences is the connector's own plus at
  # least one task's, and the connector's is already known from the line above -- so this is the
  # test for "a task is running too", written without depending on the order of the two keys.
  #
  # AND THE BRACE GROUP IS THE WHOLE POINT OF THIS LINE. `grep` exits 1 when it matches nothing,
  # which is the NORMAL first poll: `PUT .../config` has just answered 201 and the connector is
  # still UNASSIGNED with no tasks, so the document holds no RUNNING state at all. Under
  # `set -euo pipefail` that 1 becomes the pipeline's status, the assignment carries it out of the
  # command substitution, and `set -e` kills this script HERE -- between the poll and the `die`
  # below that exists to explain a failure. The container then exits 1 with its own log ending
  # cleanly on "registered ...", and Compose says only "connect-seed didn't complete successfully:
  # exit 1", which is the no-stderr shape this project has lost gates to before. It survived
  # because a warm machine's first poll is usually already RUNNING. `|| true` inside the group
  # turns "matched nothing" back into zero matches and leaves everything else -- a missing `wc`, a
  # broken pipe -- still able to fail the pipeline.
  running="$(printf '%s' "${status}" |
    { grep -o '"state":"RUNNING"' || true; } | wc -l | tr -d ' ')"

  if [ "${connector_state}" = "RUNNING" ] && [ "${running}" -ge 2 ]; then
    break
  fi

  # Say what is being waited for, once per change of state rather than once per poll. A seed that
  # waits in silence and a seed that has died are the same two minutes from outside the container,
  # and the first poll -- the one that used to kill this script -- is exactly the one a reader
  # needs to see reported rather than inferred.
  reported="connector ${connector_state:-<not registered yet>}, ${running} RUNNING state(s)"
  if [ "${reported}" != "${announced}" ]; then
    say "waiting for ${NAME}: ${reported}"
    announced="${reported}"
  fi
  if [ "${connector_state}" = "FAILED" ] ||
     printf '%s' "${status}" | grep -q '"state":"FAILED"'; then
    die "the connector or its task FAILED:
${status}"
  fi
  [ "$(date +%s)" -lt "${deadline}" ] || die "it did not reach RUNNING within ${TIMEOUT_SECONDS}s:
${status}"
  sleep 2
done

say "${NAME} and its task are RUNNING:"
say "${status}"

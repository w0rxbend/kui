#!/usr/bin/env bash
#
# Creates one ksqlDB stream in the quickstart's ksqlDB server, then exits.
#
# ---------------------------------------------------------------------------------------------
# WHY THERE IS A SEED AT ALL
# ---------------------------------------------------------------------------------------------
#
# A ksqlDB server that has been told about nothing answers `SHOW STREAMS` with an empty list. That
# is an honest answer and KUI renders it honestly -- but it would be the ONLY answer anything in
# this repository ever produced, which is the position the Connect screens were in for two waves:
# every positive browser case skipped, and nobody noticed until a milestone would not close on it.
#
# One stream is enough to make the positive half real: the object listing has a row, a statement
# can describe it, and a push query has something to select from.
#
# ---------------------------------------------------------------------------------------------
# WHY THIS STREAM
# ---------------------------------------------------------------------------------------------
#
# `orders.v1` is a topic `seed.sh` already creates and fills with JSON, so the stream is defined
# over data that is really there rather than over a topic ksqlDB would auto-create and leave empty.
# The columns are deliberately few and deliberately `VARCHAR`: this script asserts that KUI can
# list and describe a stream, not that ksqlDB can parse the quickstart's JSON, and a column type
# that disagreed with the data would make a push query fail for a reason that has nothing to do
# with KUI.
#
# ---------------------------------------------------------------------------------------------
# THE CONTRACT WITH WHOEVER RUNS THIS
# ---------------------------------------------------------------------------------------------
#
#   Image        anything with bash and curl. The Compose file uses the registry's own image for
#                the reason `avro-seed.sh` records: `apache/kafka` has no curl at all.
#   Entrypoint   /bin/bash, with this script's path as the argument.
#   Environment  KUI_KSQL_URL    -- required, e.g. http://ksqldb-server:8088
#                KUI_KSQL_STREAM -- optional, default QUICKSTART_ORDERS
#                KUI_KSQL_TOPIC  -- optional, default orders.v1
#                KUI_SEED_TIMEOUT_SECONDS -- optional, default 120
#   Exit code    0 when the stream exists. Non-zero, with ksqlDB's own message printed, otherwise.
#
# Re-running it is safe: `CREATE STREAM IF NOT EXISTS` is what ksqlDB offers for exactly this, and
# a plain `CREATE STREAM` against an existing one is an error rather than a no-op.

set -euo pipefail

KSQL_URL="${KUI_KSQL_URL:?KUI_KSQL_URL is required, e.g. http://ksqldb-server:8088}"
STREAM="${KUI_KSQL_STREAM:-QUICKSTART_ORDERS}"
TOPIC="${KUI_KSQL_TOPIC:-orders.v1}"
TIMEOUT_SECONDS="${KUI_SEED_TIMEOUT_SECONDS:-120}"

say() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }

# The server is `depends_on: service_healthy`, so it has already answered `/info` before this
# container starts. This loop is for somebody running the script by hand against a cold server.
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
until curl -sf "${KSQL_URL}/info" >/dev/null 2>&1; do
  [ "$(date +%s)" -lt "${deadline}" ] \
    || die "ksqlDB at ${KSQL_URL} did not answer GET /info within ${TIMEOUT_SECONDS}s"
  sleep 2
done
say "ksqlDB at ${KSQL_URL} is answering"

# `ksql.streams.auto.offset.reset=earliest` so that a push query started later sees the records
# `seed.sh` already wrote, rather than only whatever arrives after somebody opens the screen. On a
# demonstration whose data is all historic, `latest` would make every push query look broken.
statement="CREATE STREAM IF NOT EXISTS ${STREAM} (id VARCHAR, payload VARCHAR) \
WITH (KAFKA_TOPIC='${TOPIC}', VALUE_FORMAT='JSON');"

# The status code and the body, both: a ksqlDB rejection is a 400 whose body carries
# `error_code` and the statement text it could not accept, and `-f` throws all of that away.
# `--fail-with-body` would be the one-flag version and is not usable here -- it arrived in curl
# 7.76 and this image carries an older one, which answers `option --fail-with-body: is unknown`.
body="$(mktemp)"
code="$(curl -sS -o "${body}" -w '%{http_code}' -X POST \
  -H 'Content-Type: application/vnd.ksql.v1+json' \
  --data "{\"ksql\": \"${statement}\", \
           \"streamsProperties\": {\"ksql.streams.auto.offset.reset\": \"earliest\"}}" \
  "${KSQL_URL}/ksql" || true)"
case "${code}" in
  2*) ;;
  *) die "ksqlDB answered HTTP ${code:-<nothing>} to the CREATE STREAM:
$(cat "${body}")" ;;
esac
rm -f "${body}"

# And read it back, because a 200 from `/ksql` says the command was accepted onto the command
# topic rather than that the metastore now holds the stream. Asking `SHOW STREAMS` is the same
# question KUI's own object listing asks, so a seed that passed here and a screen that showed
# nothing would be a contradiction rather than two unrelated facts.
listed="$(curl -sS -X POST \
  -H 'Content-Type: application/vnd.ksql.v1+json' \
  --data '{"ksql": "SHOW STREAMS;"}' \
  "${KSQL_URL}/ksql" || true)"

printf '%s' "${listed}" | grep -q "\"${STREAM}\"" \
  || die "ksqlDB accepted the statement and SHOW STREAMS does not list ${STREAM}:
${listed}"

say "${STREAM} exists over ${TOPIC}, and SHOW STREAMS lists it"

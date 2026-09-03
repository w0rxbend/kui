#!/usr/bin/env bash
#
# The quickstart's live consumer group.
#
# ---------------------------------------------------------------------------------------------
# WHY THIS EXISTS SEPARATELY FROM seed.sh
# ---------------------------------------------------------------------------------------------
#
# seed.sh can manufacture a consumer group that has stopped: a stopped group is only a set of
# committed offsets sitting in Kafka, and those can be written directly. It cannot manufacture an
# ACTIVE one. A group is active exactly as long as a process is holding a session open with the
# broker; the moment that process exits, the group loses its members and Kafka reports it as
# empty. So the live group has to be a process that keeps running, which means a long-lived
# container, which is a different kind of thing from a job that finishes.
#
# Both states are worth showing. A consumer groups screen where every group is dead tells you
# nothing about what a healthy one looks like, and the difference between "EMPTY, lag 4 200" and
# "STABLE, one member, lag 12" is the difference an operator is usually trying to read.
#
# ---------------------------------------------------------------------------------------------
# THE CONTRACT WITH WHOEVER RUNS THIS
# ---------------------------------------------------------------------------------------------
#
#   Image        the same one seed.sh uses: apache/kafka:4.3.1, or anything with Kafka's shell
#                tools in /opt/kafka/bin.
#   Entrypoint   /bin/bash, with this script's path as the argument.
#   Lifetime     LONG-LIVED. It never exits on its own. Compose should give it
#                `restart: unless-stopped` and no healthcheck condition that anything waits on.
#   Order        start it after seed.sh has finished, so the topic it reads exists:
#                `depends_on: { kui-seed: { condition: service_completed_successfully } }`.
#   Environment  KAFKA_BOOTSTRAP_SERVERS — required. host:port of any broker.
#                KUI_SEED_GROUP          — optional, default analytics-indexer.
#                KUI_SEED_TOPIC          — optional, default analytics.pageviews.
#   Signals      exits promptly on SIGTERM, so `docker compose down` is not a ten-second wait.
#
# A Compose service that satisfies all of the above:
#
#   kui-consumer:
#     image: apache/kafka:4.3.1
#     entrypoint: ["/bin/bash", "/seed/consume.sh"]
#     environment:
#       KAFKA_BOOTSTRAP_SERVERS: kafka:9092
#     volumes:
#       - ./seed:/seed:ro
#     depends_on:
#       kui-seed:
#         condition: service_completed_successfully
#     restart: unless-stopped
#
# ---------------------------------------------------------------------------------------------
# WHAT IT ACTUALLY DOES
# ---------------------------------------------------------------------------------------------
#
# It joins the group, reads analytics.pageviews from the beginning, commits its offsets, and then
# sits there holding the session open with nothing left to read. Everything it reads is thrown
# away: this consumer exists to be looked at, not to compute anything.
#
# The group therefore settles at zero lag with one live member. That is the healthy state, and
# it is the one the screen needs an example of. The behind-by-a-lot example is order-fulfilment,
# which seed.sh leaves stopped part-way through orders.v1.

set -o errexit
set -o nounset
set -o pipefail

readonly BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-}"
readonly GROUP="${KUI_SEED_GROUP:-analytics-indexer}"
readonly TOPIC="${KUI_SEED_TOPIC:-analytics.pageviews}"
readonly BIN="${KAFKA_BIN_DIR:-/opt/kafka/bin}"

export KAFKA_LOG4J_OPTS="-Dlog4j2.rootLogger.level=${KUI_SEED_LOG_LEVEL:-WARN}"
export KAFKA_HEAP_OPTS="${KAFKA_HEAP_OPTS:--Xms64m -Xmx256m}"

[[ -n "${BOOTSTRAP}" ]] || { echo "[consumer] ERROR: KAFKA_BOOTSTRAP_SERVERS is not set." >&2; exit 1; }

# Forward SIGTERM to the JVM rather than letting Docker wait out its ten-second grace period and
# then kill it. A consumer that is killed rather than closed leaves its group session to time out,
# so the group would keep claiming a member that is gone for another few seconds — visible in KUI,
# and confusing when you have just stopped the stack yourself.
consumer_pid=""
shutdown() {
  [[ -n "${consumer_pid}" ]] && kill -TERM "${consumer_pid}" 2>/dev/null
  wait "${consumer_pid}" 2>/dev/null || true
  echo "[consumer] left group ${GROUP}"
  exit 0
}
trap shutdown TERM INT

echo "[consumer] joining ${GROUP} on ${TOPIC} at ${BOOTSTRAP}"

# --timeout-ms is deliberately absent: without it the consumer blocks for ever waiting for the
# next record, which is precisely the behaviour wanted here. Output goes to /dev/null because a
# container log filling with sample pageviews would bury anything worth reading.
#
# The commit interval is short so that KUI shows this group's lag falling to zero within a second
# or two of the stack starting, rather than after the five-second default.
"${BIN}/kafka-console-consumer.sh" \
  --bootstrap-server "${BOOTSTRAP}" \
  --topic "${TOPIC}" \
  --group "${GROUP}" \
  --from-beginning \
  --consumer-property enable.auto.commit=true \
  --consumer-property auto.commit.interval.ms=1000 \
  --consumer-property client.id=kui-quickstart-indexer \
  >/dev/null 2>&1 &
consumer_pid=$!

wait "${consumer_pid}"

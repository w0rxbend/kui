#!/usr/bin/env bash
#
# A consumer group of the KUI demo that is genuinely ALIVE.
#
# =============================================================================================
# WHY THIS EXISTS SEPARATELY FROM seed.sh
# =============================================================================================
#
# seed.sh can manufacture a consumer group that has stopped: a stopped group is only a set of
# committed offsets sitting in Kafka, and those can be written directly. It cannot manufacture an
# ACTIVE one. A group has members exactly as long as some process is holding a session open with
# the broker; the moment that process exits, the group loses its members and Kafka reports it as
# `Empty`. So a live group has to be a process that keeps running, which means a long-lived
# container — a different kind of thing from a job that finishes.
#
# All three states are worth showing, and the demo shows all three:
#
#   Stable            this script, holding a session open with one or more members.
#   Empty             seed.sh's groups: committed offsets, nobody reading them.
#   PreparingRebalance / CompletingRebalance
#                     this script with KUI_DEMO_CHURN_SECONDS set. See below.
#
# A consumer-groups screen where every group is dead tells you nothing about what a healthy one
# looks like, and the difference between "Empty, lag 8 400" and "Stable, two members, lag 12" is
# the difference an operator is usually trying to read.
#
# =============================================================================================
# THE CONTRACT WITH WHOEVER RUNS THIS
# =============================================================================================
#
#   Image        the same one seed.sh uses: apache/kafka:4.3.1, or anything with Kafka's shell
#                tools in /opt/kafka/bin.
#   Entrypoint   /bin/bash, with this script's path as the argument.
#   Lifetime     LONG-LIVED. It never exits on its own. Compose should give it
#                `restart: unless-stopped` and no healthcheck anything waits on.
#   Order        start it after seed.sh for the same cluster has finished, so the topic it reads
#                exists: `depends_on: { <seed>: { condition: service_completed_successfully } }`.
#   Signals      exits promptly on SIGTERM, so `docker compose down` is not a ten-second wait.
#
# Environment:
#
#   KAFKA_BOOTSTRAP_SERVERS   REQUIRED. host:port of any broker.
#   KUI_DEMO_GROUP            REQUIRED. The consumer group to join.
#   KUI_DEMO_TOPIC            REQUIRED. The topic to read. A comma-separated list subscribes to
#                             several, which is how a group ends up with an assignment spanning
#                             more than one topic — a case the assignment table should show.
#   KUI_DEMO_MEMBERS          Optional, default 1. How many consumer processes to run inside this
#                             one container, all in the same group. More than one gives the group
#                             a multi-member assignment: partitions divided between named members,
#                             which is what the members table exists to draw.
#   KUI_DEMO_CHURN_SECONDS    Optional. When set, one member leaves and rejoins every N seconds,
#                             which forces the group through a real rebalance each time. Without
#                             this, nobody ever sees a rebalancing group in the demo, because a
#                             healthy group rebalances once at startup and then never again.
#   KUI_SEED_COMMAND_CONFIG   Optional. Kafka client properties file, for a secured broker.
#   KUI_SEED_LOG_LEVEL        Optional, default WARN.
#   KAFKA_BIN_DIR             Optional, default /opt/kafka/bin.
#
# A Compose service that satisfies all of the above:
#
#   consumer-production:
#     image: apache/kafka:4.3.1
#     entrypoint: ["/bin/bash", "/seed/consume.sh"]
#     environment:
#       KAFKA_BOOTSTRAP_SERVERS: kafka-prod-1:9092
#       KUI_DEMO_GROUP: search-indexer
#       KUI_DEMO_TOPIC: search.index-updates
#       KUI_DEMO_MEMBERS: "3"
#     volumes:
#       - ./seed:/seed:ro
#     depends_on:
#       seed-production: { condition: service_completed_successfully }
#     restart: unless-stopped
#
# =============================================================================================
# WHAT IT ACTUALLY DOES
# =============================================================================================
#
# Joins the group, reads from the beginning, commits its offsets, and then sits there holding the
# session open with nothing left to read. Everything it reads is thrown away: these consumers
# exist to be looked at, not to compute anything.

set -o errexit
set -o nounset
set -o pipefail

readonly BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-}"
readonly GROUP="${KUI_DEMO_GROUP:-}"
readonly TOPICS="${KUI_DEMO_TOPIC:-}"
readonly MEMBERS="${KUI_DEMO_MEMBERS:-1}"
readonly CHURN_SECONDS="${KUI_DEMO_CHURN_SECONDS:-0}"
readonly COMMAND_CONFIG="${KUI_SEED_COMMAND_CONFIG:-}"
readonly BIN="${KAFKA_BIN_DIR:-/opt/kafka/bin}"

export KAFKA_LOG4J_OPTS="-Dlog4j2.rootLogger.level=${KUI_SEED_LOG_LEVEL:-WARN}"
export KAFKA_HEAP_OPTS="${KAFKA_HEAP_OPTS:--Xms64m -Xmx256m}"

say() { printf '[consumer:%s] %s\n' "${GROUP:-?}" "$*"; }
die() { printf '[consumer:%s] ERROR: %s\n' "${GROUP:-?}" "$*" >&2; exit 1; }

[[ -n "${BOOTSTRAP}" ]] || die "KAFKA_BOOTSTRAP_SERVERS is not set."
[[ -n "${GROUP}" ]]     || die "KUI_DEMO_GROUP is not set."
[[ -n "${TOPICS}" ]]    || die "KUI_DEMO_TOPIC is not set."

# The console consumer takes either one --topic or one --include regular expression. A
# comma-separated list becomes an anchored alternation, so `a,b` subscribes to exactly a and b and
# not to anything merely containing them.
subscription=(--topic "${TOPICS}")
if [[ "${TOPICS}" == *,* ]]; then
  subscription=(--include "^(${TOPICS//,/|})$")
fi

conn=(--bootstrap-server "${BOOTSTRAP}")
[[ -n "${COMMAND_CONFIG}" ]] && conn+=(--consumer.config "${COMMAND_CONFIG}")

pids=()

# Starts one consumer process and appends its process id to `pids`.
#
# It appends rather than printing the id for the caller to capture, and that is not a style
# choice. Writing `pids+=("$(start_member 1)")` runs the function inside a command substitution,
# which is a SUBSHELL: the JVM it starts is a child of that subshell, not of this script, so the
# `wait` at the bottom has nothing of its own to wait for, returns immediately, and the container
# exits zero one second after starting — taking the consumers with it and leaving a consumer group
# that never appears at all. That is exactly what happened the first time this was written, and it
# looked like a broker problem rather than a shell one.
#
# `member` is only used to give the consumer a client id, which is what shows up as the member's
# name in KUI's members table — "search-indexer-2" reads better there than a random uuid.
start_member() {
  local member="$1"
  local args=("${conn[@]}" "${subscription[@]}"
              --group "${GROUP}"
              --from-beginning
              --consumer-property enable.auto.commit=true
              # A short commit interval so the lag KUI shows follows what the consumer is really
              # doing within a second, rather than lurching every five seconds by default.
              --consumer-property auto.commit.interval.ms=1000
              # A short session timeout so that a member which goes away is noticed in a few
              # seconds. On a demo this is the difference between a rebalance you can watch and
              # one that happens between two page refreshes.
              --consumer-property session.timeout.ms=10000
              --consumer-property heartbeat.interval.ms=3000
              --consumer-property "client.id=${GROUP}-${member}")
  # --max-messages is deliberately absent, and so is --timeout-ms: without them the consumer blocks
  # for ever waiting for the next record, which is precisely the behaviour wanted here. Everything
  # it reads goes to /dev/null; these consumers exist to be looked at, not to compute anything.
  "${BIN}/kafka-console-consumer.sh" "${args[@]}" >/dev/null 2>&1 &
  pids+=("$!")
}

# Forward SIGTERM to the JVMs rather than letting Docker wait out its ten-second grace period and
# then kill them. A consumer that is killed rather than closed leaves its group session to time
# out, so the group keeps claiming a member that is gone for another few seconds — visible in KUI,
# and confusing when you have just stopped the stack yourself.
shutdown() {
  for pid in "${pids[@]:-}"; do
    [[ -n "${pid}" ]] && kill -TERM "${pid}" 2>/dev/null
  done
  wait 2>/dev/null || true
  say "left group ${GROUP}"
  exit 0
}
trap shutdown TERM INT

churn_note=""
(( CHURN_SECONDS > 0 )) && churn_note=", restarting one member every ${CHURN_SECONDS}s"
say "joining ${GROUP} on ${TOPICS} at ${BOOTSTRAP} with ${MEMBERS} member(s)${churn_note}"

for (( m = 1; m <= MEMBERS; m++ )); do
  start_member "${m}"
done

if [[ "${CHURN_SECONDS}" -le 0 ]]; then
  # The steady case: hold the session open for ever and let Compose stop us.
  wait
  exit 0
fi

# The churning case. Member 1 is stopped and started again on a timer. Each stop makes the group
# rebalance to divide its partitions among the survivors, and each start makes it rebalance back.
# Between the two, the group spends a second or two in PreparingRebalance/CompletingRebalance,
# which is a state the demo would otherwise never show.
#
# It has to be a real member leaving a real group. Faking the state is not possible from outside —
# a group's state is the coordinator's own view of its members — and would not be worth having if
# it were, since what this is demonstrating is that KUI reports what Kafka actually says.
churn_member=$(( MEMBERS + 1 ))
while :; do
  sleep "${CHURN_SECONDS}"
  kill -TERM "${pids[0]}" 2>/dev/null || true
  wait "${pids[0]}" 2>/dev/null || true
  sleep 2
  # start_member appends, so take the new id off the end and put it back in slot 0, which is the
  # slot the churn loop and the shutdown handler both know about.
  start_member "${churn_member}"
  pids[0]="${pids[-1]}"
  unset 'pids[-1]'
  churn_member=$(( churn_member + 1 ))
done

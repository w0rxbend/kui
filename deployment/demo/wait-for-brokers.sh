#!/usr/bin/env bash
#
# Waits until a Kafka cluster can genuinely do the job, and not merely until its containers are up.
#
# ---------------------------------------------------------------------------------------------
# WHY A THREE-BROKER CLUSTER NEEDS MORE THAN A HEALTH CHECK
# ---------------------------------------------------------------------------------------------
#
# The quickstart's single broker has one readiness question: can it answer a metadata request? Its
# Compose health check asks exactly that, by running `kafka-topics.sh --list`, and that is enough.
#
# A three-broker cluster has a second question, and it is the one that bites. Each broker answers
# `--list` as soon as it is up, whether or not the other two have joined. So all three health
# checks can be green while the cluster has, from the outside, one broker in it. Anything that
# creates a topic in that window gets a topic with fewer replicas than it asked for -- Kafka does
# not wait, it refuses a replication factor larger than the brokers currently registered -- and the
# demonstration ends up with single-replica topics on the cluster whose entire purpose is to show
# replication. Nothing errors. The numbers are just quietly wrong for the rest of the run.
#
# So this script waits for two things in order:
#
#   1. every broker registered -- `kafka-broker-api-versions.sh` prints one block per LIVE broker,
#      so counting those blocks counts the cluster, not the container it was asked;
#   2. a replica placement actually succeeding -- it creates a throwaway topic at the full
#      replication factor and deletes it again. That is the difference between "the brokers can be
#      counted" and "the brokers can be used", and it is the condition the seed step needs.
#
# ---------------------------------------------------------------------------------------------
# THE CONTRACT WITH WHOEVER RUNS THIS
# ---------------------------------------------------------------------------------------------
#
#   Image        apache/kafka:4.3.1, or anything with Kafka's shell tools in /opt/kafka/bin.
#   Entrypoint   /bin/bash, with this script's path as the argument.
#   Lifetime     one-shot. Exits 0 when the cluster is ready, non-zero when it never became so.
#   Environment  KAFKA_BOOTSTRAP_SERVERS   required. Any broker address, comma-separated for more.
#                KUI_EXPECTED_BROKERS      required. How many brokers this cluster has.
#                KUI_WAIT_TIMEOUT_SECONDS  optional, default 180.
#
# Put it between the brokers and whatever must not run early:
#
#   depends_on:
#     kafka-prod-ready:
#       condition: service_completed_successfully

set -o errexit
set -o nounset
set -o pipefail

readonly BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-}"
readonly EXPECTED="${KUI_EXPECTED_BROKERS:-}"
readonly TIMEOUT_SECONDS="${KUI_WAIT_TIMEOUT_SECONDS:-180}"
readonly BIN="${KAFKA_BIN_DIR:-/opt/kafka/bin}"
readonly PROBE_TOPIC="__kui_demo_readiness_probe"

# Kafka's tools print a wall of INFO lines otherwise, and the one useful line is lost in it.
export KAFKA_LOG4J_OPTS="-Dlog4j2.rootLogger.level=${KUI_SEED_LOG_LEVEL:-WARN}"
export KAFKA_HEAP_OPTS="${KAFKA_HEAP_OPTS:--Xms64m -Xmx256m}"

log() { printf '[wait] %s\n' "$*"; }
die() { printf '[wait] ERROR: %s\n' "$*" >&2; exit 1; }

[[ -n "${BOOTSTRAP}" ]] || die "KAFKA_BOOTSTRAP_SERVERS is not set."
[[ -n "${EXPECTED}" ]]  || die "KUI_EXPECTED_BROKERS is not set."

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
seen=0

log "waiting for ${EXPECTED} broker(s) at ${BOOTSTRAP} (up to ${TIMEOUT_SECONDS}s)"

while :; do
  if versions=$("${BIN}/kafka-broker-api-versions.sh" --bootstrap-server "${BOOTSTRAP}" 2>/dev/null); then
    seen=$(printf '%s\n' "${versions}" | grep -c ' (id: ' || true)
    if [[ "${seen}" -ge "${EXPECTED}" ]]; then
      break
    fi
  fi
  [[ $(date +%s) -lt ${deadline} ]] \
    || die "only ${seen} of ${EXPECTED} broker(s) had registered after ${TIMEOUT_SECONDS}s. 'demo.sh logs' shows what the brokers are saying."
  sleep 2
done

log "all ${seen} broker(s) registered; checking that a replica set can actually be placed"

# The second condition. `--if-not-exists` covers a re-run that found the probe left behind by a
# previous attempt that was killed between creating and deleting it.
while :; do
  if "${BIN}/kafka-topics.sh" --bootstrap-server "${BOOTSTRAP}" --create --if-not-exists \
       --topic "${PROBE_TOPIC}" --partitions 1 --replication-factor "${EXPECTED}" >/dev/null 2>&1; then
    break
  fi
  [[ $(date +%s) -lt ${deadline} ]] \
    || die "the cluster never accepted a topic with ${EXPECTED} replicas within ${TIMEOUT_SECONDS}s, although ${seen} broker(s) were registered."
  sleep 2
done

"${BIN}/kafka-topics.sh" --bootstrap-server "${BOOTSTRAP}" --delete --topic "${PROBE_TOPIC}" >/dev/null 2>&1 || true

log "cluster is ready: ${seen} broker(s), replication factor ${EXPECTED} accepted"

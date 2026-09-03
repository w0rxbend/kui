#!/usr/bin/env bash
#
# Fills a Kafka cluster with data that looks like a real system, so that the first screen a
# newcomer sees in KUI has something on it.
#
# ---------------------------------------------------------------------------------------------
# THE CONTRACT WITH WHOEVER RUNS THIS
# ---------------------------------------------------------------------------------------------
#
# Run it as a container that starts, does its work and exits. It is not a service; it has no port
# and nothing waits on it afterwards.
#
#   Image        apache/kafka:4.3.1 or any image with Kafka's shell tools in /opt/kafka/bin.
#                Nothing here needs a JDK of its own, a package install or network access beyond
#                the broker. The image already runs as a non-root user (uid 1000).
#   Entrypoint   /bin/bash, with this script's path as the argument.
#   Mount        this directory, read-only, anywhere. The script finds its own data files
#                relative to itself, so the mount path does not matter.
#   Environment  KAFKA_BOOTSTRAP_SERVERS  — required. host:port of any broker, comma-separated
#                                           for several. Example: kafka:9092
#                KUI_SEED_TIMEOUT_SECONDS — optional, default 120. How long to wait for the
#                                           broker to answer before giving up.
#                KUI_SEED_LOG_LEVEL       — optional, default WARN. Kafka's own tools are noisy;
#                                           set INFO or DEBUG when this script misbehaves.
#   Exit code    0 when the cluster ends up in the intended state, non-zero otherwise. A Compose
#                stack should treat a non-zero exit as a failed start, with
#                `depends_on: { kui-seed: { condition: service_completed_successfully } }`.
#
# A Compose service that satisfies all of the above:
#
#   kui-seed:
#     image: apache/kafka:4.3.1
#     entrypoint: ["/bin/bash", "/seed/seed.sh"]
#     environment:
#       KAFKA_BOOTSTRAP_SERVERS: kafka:9092
#     volumes:
#       - ./seed:/seed:ro
#     depends_on:
#       kafka:
#         condition: service_healthy
#     restart: "no"
#
# ---------------------------------------------------------------------------------------------
# WHAT IT GUARANTEES
# ---------------------------------------------------------------------------------------------
#
# Idempotent. Running it a second, fifth or hundredth time changes nothing and fails at nothing.
# Topics are created only when absent, messages are produced only into a topic that has none, and
# a consumer group is given offsets only when it does not already exist. That last rule matters
# more than it looks: if you have been clicking around in KUI, resetting a group's offsets under
# you because a container restarted would be a small betrayal.
#
# Fast, because it stands between a newcomer and their first look at the product. Each of Kafka's
# shell tools costs a JVM start of a second or two, so the script uses as few of them as it can
# and runs the unavoidably-repeated ones — one per topic — all at once rather than in a queue.
#
# ---------------------------------------------------------------------------------------------
# WHAT IT DOES NOT DO
# ---------------------------------------------------------------------------------------------
#
# It does not start an actively-consuming group. A group is only "active" while a process is
# holding it open, and this script exits. The consumer that does that lives beside this one in
# `consume.sh` and is run as a long-lived Compose service. This script prepares the topic it
# reads, and stops there.
#
# It does not talk to a secured cluster. The quickstart's broker has no authentication and no
# TLS, on purpose: it is a throwaway on a laptop. Pointing this at a real cluster would need a
# client properties file, which is deliberately out of scope for a first-run demo.

set -o errexit
set -o nounset
set -o pipefail

readonly SEED_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly DATA_DIR="${SEED_DIR}/data"
readonly TOPICS_FILE="${SEED_DIR}/topics.tsv"

readonly BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-}"
readonly TIMEOUT_SECONDS="${KUI_SEED_TIMEOUT_SECONDS:-120}"

# Kafka's shell tools read log4j settings from this variable. Without it every command prints a
# wall of INFO lines about consumer configuration, and the useful output is lost in it.
export KAFKA_LOG4J_OPTS="-Dlog4j2.rootLogger.level=${KUI_SEED_LOG_LEVEL:-WARN}"
# The tools do not need much heap and the default is a share of the host's RAM, which on a large
# machine means a slow start for nothing.
export KAFKA_HEAP_OPTS="${KAFKA_HEAP_OPTS:--Xms64m -Xmx256m}"

readonly BIN="${KAFKA_BIN_DIR:-/opt/kafka/bin}"

# The marker that means "this field is null, not empty". Used for the tombstone records in the
# compacted topics: a key with no value at all, which is how a delete is expressed in Kafka.
readonly NULL_MARKER='<NULL>'

# ---------------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------------

started_at=$(date +%s)

log()  { printf '[seed] %s\n' "$*"; }
step() { printf '[seed] %s\n' "$*"; }
die()  { printf '[seed] ERROR: %s\n' "$*" >&2; exit 1; }

elapsed() { echo $(( $(date +%s) - started_at )); }

# ---------------------------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------------------------

[[ -n "${BOOTSTRAP}" ]] || die "KAFKA_BOOTSTRAP_SERVERS is not set. It must hold a broker address such as kafka:9092."
[[ -x "${BIN}/kafka-topics.sh" ]] || die "Kafka's shell tools are not at ${BIN}. Run this in an image that has them, or set KAFKA_BIN_DIR."
[[ -r "${TOPICS_FILE}" ]] || die "Cannot read ${TOPICS_FILE}. Is this directory mounted?"

# ---------------------------------------------------------------------------------------------
# Step 1: wait for the broker, and find out how many there are
# ---------------------------------------------------------------------------------------------
#
# One command answers both questions. `kafka-broker-api-versions.sh` fails outright while no
# broker will talk to us, and when one does it prints a block per broker beginning with
# "host:port (id: 1 rack: null) -> (", so counting those blocks counts the brokers.
#
# The broker count is not trivia: Kafka refuses to create a topic whose replication factor is
# higher than the number of brokers, and topics.tsv asks for 3 in places because that is what the
# topic would be on a real cluster. On the quickstart's single broker every one of those has to
# come down to 1.

broker_count=0
step "waiting for a broker at ${BOOTSTRAP} (up to ${TIMEOUT_SECONDS}s)"
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while :; do
  if api_versions=$("${BIN}/kafka-broker-api-versions.sh" --bootstrap-server "${BOOTSTRAP}" 2>/dev/null); then
    broker_count=$(printf '%s\n' "${api_versions}" | grep -c ' (id: ' || true)
    [[ "${broker_count}" -gt 0 ]] && break
  fi
  [[ $(date +%s) -lt ${deadline} ]] || die "no broker answered at ${BOOTSTRAP} within ${TIMEOUT_SECONDS}s."
  sleep 2
done
log "broker is up: ${broker_count} broker(s), ${SECONDS}s in"

# ---------------------------------------------------------------------------------------------
# Step 2: read the topic table
# ---------------------------------------------------------------------------------------------

topic_names=()
topic_partitions=()
topic_replication=()
topic_configs=()

while IFS=$'\t' read -r name partitions replication configs; do
  [[ -z "${name}" || "${name}" == \#* ]] && continue
  # Ask for no more replicas than there are brokers to put them on.
  (( replication > broker_count )) && replication=${broker_count}
  topic_names+=("${name}")
  topic_partitions+=("${partitions}")
  topic_replication+=("${replication}")
  topic_configs+=("${configs}")
done < <(sed 's/[[:space:]]*$//' "${TOPICS_FILE}")

[[ ${#topic_names[@]} -gt 0 ]] || die "${TOPICS_FILE} defines no topics."

# ---------------------------------------------------------------------------------------------
# Step 3: create the topics that are missing
# ---------------------------------------------------------------------------------------------
#
# `--if-not-exists` would make the create calls safe on their own, but it would still cost a JVM
# start per topic on every run. Listing the topics once is one JVM start for all of them, and on
# a second run it means no create calls at all.

step "reading the topic list"
existing_topics=$("${BIN}/kafka-topics.sh" --bootstrap-server "${BOOTSTRAP}" --list 2>/dev/null || true)

create_pids=()
created=()
for i in "${!topic_names[@]}"; do
  name="${topic_names[$i]}"
  if grep -qxF "${name}" <<<"${existing_topics}"; then
    continue
  fi
  args=(--bootstrap-server "${BOOTSTRAP}" --create --if-not-exists
        --topic "${name}"
        --partitions "${topic_partitions[$i]}"
        --replication-factor "${topic_replication[$i]}")
  if [[ "${topic_configs[$i]}" != "-" && -n "${topic_configs[$i]}" ]]; then
    # One --config per key=value pair. Pairs are separated by semicolons, not commas, because a
    # Kafka config value can contain a comma of its own: `cleanup.policy=compact,delete`.
    IFS=';' read -r -a config_pairs <<<"${topic_configs[$i]}"
    for pair in "${config_pairs[@]}"; do
      # min.insync.replicas is the other setting a single-broker cluster cannot honour. Asking
      # for two in-sync replicas when there is one broker creates the topic happily and then
      # rejects every write to it with NOT_ENOUGH_REPLICAS — a failure that would surface here as
      # an empty topic and no explanation. Clamp it the same way as the replication factor.
      if [[ "${pair}" == min.insync.replicas=* && "${pair#min.insync.replicas=}" -gt "${broker_count}" ]]; then
        pair="min.insync.replicas=${broker_count}"
      fi
      args+=(--config "${pair}")
    done
  fi
  created+=("${name}")
  # All of them at once. Eight JVMs starting together take about as long as one.
  "${BIN}/kafka-topics.sh" "${args[@]}" >/dev/null &
  create_pids+=("$!")
done

create_failed=0
for pid in "${create_pids[@]:-}"; do
  [[ -n "${pid}" ]] || continue
  wait "${pid}" || create_failed=1
done
[[ ${create_failed} -eq 0 ]] || die "one or more topics could not be created. Re-run with KUI_SEED_LOG_LEVEL=INFO to see why."

if [[ ${#created[@]} -eq 0 ]]; then
  log "topics: all ${#topic_names[@]} already present, nothing to create"
else
  log "topics: created ${#created[@]} (${created[*]}), ${#topic_names[@]} in total"
fi

# ---------------------------------------------------------------------------------------------
# Step 4: find out which topics already hold messages
# ---------------------------------------------------------------------------------------------
#
# `kafka-get-offsets.sh` prints the end offset of every partition as `topic:partition:offset`. A
# topic whose offsets all read zero has never been written to, so it is one we still have to fill.
#
# It is called with no topic filter at all, which asks about every topic on the cluster in one
# JVM start. That is both the fastest form and the least breakable one: the tool's
# `--topic-partitions` syntax is fussy about what it accepts, and getting it subtly wrong here
# would be an expensive mistake — the check would quietly return nothing, every topic would look
# empty, and each run of this script would append another copy of the sample data.

step "checking which topics already hold messages"
offsets=$("${BIN}/kafka-get-offsets.sh" --bootstrap-server "${BOOTSTRAP}" 2>/dev/null || true)
[[ -n "${offsets}" ]] || die "could not read topic offsets, so there is no safe way to tell whether this cluster has already been seeded. Refusing to write anything."

topic_has_records() {
  local topic="$1" record_count
  record_count=$(printf '%s\n' "${offsets}" \
    | awk -F: -v t="${topic}" '$1 == t { total += $3 } END { print total + 0 }')
  [[ "${record_count}" -gt 0 ]]
}

# ---------------------------------------------------------------------------------------------
# Step 5: produce the messages
# ---------------------------------------------------------------------------------------------
#
# Each data file under data/ is named after its topic and starts with a `#mode:` line saying how
# its lines are laid out:
#
#   headers-key-value   h1:v1,h2:v2 <TAB> key <TAB> value
#   key-value           key <TAB> value
#   value-only          value
#
# The separator in the files is the two literal characters \t rather than a real tab, so that the
# files survive an editor that turns tabs into spaces; they are converted here. %TS-n% and %TS+n%
# become an ISO-8601 UTC timestamp n seconds before or after this run, which is what stops the
# sample data reading as a museum piece a week after it was written.

render() {
  local file="$1" now
  now=$(date -u +%s)
  # Two passes, both cheap: timestamps first, then the tab separators. Doing it in awk rather
  # than a shell loop keeps a forty-line file to a single process.
  awk -v now="${now}" '
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    {
      while (match($0, /%TS[-+][0-9]+%/)) {
        spec = substr($0, RSTART + 3, RLENGTH - 4)
        seconds = now + spec + 0
        if (!(seconds in stamps)) {
          cmd = "date -u -d @" seconds " +%Y-%m-%dT%H:%M:%SZ"
          cmd | getline stamps[seconds]
          close(cmd)
        }
        $0 = substr($0, 1, RSTART - 1) stamps[seconds] substr($0, RSTART + RLENGTH)
      }
      gsub(/\\t/, "\t")
      print
    }
  ' "${file}"
}

mode_of() {
  sed -n 's/^#mode:[[:space:]]*//p' "$1" | head -n 1
}

produce() {
  local topic="$1" file="${DATA_DIR}/$1" mode
  mode=$(mode_of "${file}")
  local args=(--bootstrap-server "${BOOTSTRAP}" --topic "${topic}"
              --property "null.marker=${NULL_MARKER}")
  case "${mode}" in
    headers-key-value) args+=(--property parse.headers=true --property parse.key=true) ;;
    key-value)         args+=(--property parse.key=true) ;;
    value-only)        ;;
    *) die "${file} does not declare a #mode: line, so its layout is unknown." ;;
  esac
  # stdout is dropped because the console producer greets every run with a deprecation notice
  # about `--property`, which is the only spelling that works on both Kafka 3.x and 4.x. Errors
  # go to stderr and are left alone.
  render "${file}" | "${BIN}/kafka-console-producer.sh" "${args[@]}" >/dev/null
}

produce_pids=()
produced=()
skipped=()
for name in "${topic_names[@]}"; do
  if [[ ! -r "${DATA_DIR}/${name}" ]]; then
    log "no data file for ${name}, leaving it empty"
    continue
  fi
  if topic_has_records "${name}"; then
    skipped+=("${name}")
    continue
  fi
  produced+=("${name}")
  produce "${name}" &
  produce_pids+=("$!")
done

produce_failed=0
for pid in "${produce_pids[@]:-}"; do
  [[ -n "${pid}" ]] || continue
  wait "${pid}" || produce_failed=1
done
[[ ${produce_failed} -eq 0 ]] || die "one or more topics could not be written to."

if [[ ${#produced[@]} -eq 0 ]]; then
  log "messages: every topic already held records, produced nothing"
else
  log "messages: wrote into ${#produced[@]} topic(s) (${produced[*]})${skipped:+; ${#skipped[@]} already had records}"
fi

# ---------------------------------------------------------------------------------------------
# Step 6: consumer groups
# ---------------------------------------------------------------------------------------------
#
# A consumer group is, from the outside, nothing but a set of committed offsets. `--reset-offsets
# --execute` writes those offsets directly, which creates the group in one command and without
# consuming anything, so a group with realistic lag costs one JVM start instead of a consumer
# session per partition.
#
# Two groups, deliberately different:
#
#   order-fulfilment       stopped part-way through orders.v1. Committed at offset 2 in every
#                          partition while the topic holds more than that, so KUI has genuine
#                          per-partition lag to draw. This is the interesting case, and the one
#                          an operator is usually looking at when something has gone wrong.
#   payments-ledger-sync   stopped, but caught up: committed at the end of every partition, so
#                          its lag is zero. Without it, "lag" looks like a colour every group
#                          has, rather than a state one group is in.
#
# The third group, analytics-indexer, is the live one, and it is not created here — see the note
# at the top of this file.
#
# Neither is touched if it already exists. Re-running must not rewind a group somebody is
# watching, and it must not fail against a group that currently has members, which is what
# `--reset-offsets` does by design.

step "reading the consumer group list"
existing_groups=$("${BIN}/kafka-consumer-groups.sh" --bootstrap-server "${BOOTSTRAP}" --list 2>/dev/null || true)

seed_group() {
  local group="$1" topic="$2"
  shift 2
  if grep -qxF "${group}" <<<"${existing_groups}"; then
    return 0
  fi
  "${BIN}/kafka-consumer-groups.sh" --bootstrap-server "${BOOTSTRAP}" \
    --group "${group}" --topic "${topic}" --reset-offsets "$@" --execute >/dev/null 2>&1
}

group_pids=()
seed_group order-fulfilment      orders.v1              --to-offset 2 & group_pids+=("$!")
seed_group payments-ledger-sync  payments.transactions  --to-latest   & group_pids+=("$!")

group_failed=0
for pid in "${group_pids[@]}"; do
  wait "${pid}" || group_failed=1
done
[[ ${group_failed} -eq 0 ]] || die "a consumer group could not be given offsets."

log "consumer groups: order-fulfilment (behind) and payments-ledger-sync (caught up) are in place"

# ---------------------------------------------------------------------------------------------

log "done in $(elapsed)s"

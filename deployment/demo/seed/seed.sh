#!/usr/bin/env bash
#
# Fills ONE Kafka cluster of the KUI demo with data, according to a named profile.
#
# The demo runs three clusters side by side, and the whole point of running three is that they do
# not look alike: switching between them in KUI has to show you something. So this script does not
# have one fixed idea of what to create. It is told which profile to apply, reads that profile's
# tables out of `profiles/<name>/`, and applies them.
#
# It is a direct descendant of `deployment/quickstart/seed/seed.sh` and keeps that script's file
# formats unchanged, so a data file can be moved between the two without editing. What is new is
# the profile directory, a declarative table of consumer groups instead of two hard-coded ones, a
# table of bulk-generated messages for the volume the production-shaped cluster needs, and support
# for a client properties file so it can talk to a secured broker.
#
# =============================================================================================
# THE CONTRACT WITH WHOEVER RUNS THIS
# =============================================================================================
#
# Run it as a container that starts, does its work and exits. It is not a service; it has no port
# and nothing waits on it afterwards.
#
#   Image        apache/kafka:4.3.1, or any image with Kafka's shell tools in /opt/kafka/bin.
#                Nothing here needs a JDK of its own, a package install, or network access beyond
#                the broker. The image already runs as a non-root user (uid 1000), and this
#                script never writes to its mount.
#   Entrypoint   /bin/bash, with this script's path as the argument.
#   Mount        this directory, read-only, anywhere. The script finds its profiles relative to
#                itself, so the mount path does not matter.
#   Exit code    0 when the cluster ends up in the intended state, non-zero otherwise. Compose
#                should treat a non-zero exit as a failed start, with
#                `depends_on: { <seed>: { condition: service_completed_successfully } }`.
#
# Environment:
#
#   KAFKA_BOOTSTRAP_SERVERS   REQUIRED. host:port of any broker on the cluster to seed,
#                             comma-separated for several. Example: kafka-prod-1:9092
#   KUI_SEED_PROFILE          REQUIRED. Which profile directory to apply. One of the directory
#                             names under profiles/ — today: development, production, secured.
#   KUI_SEED_COMMAND_CONFIG   Optional. Path to a Kafka client properties file, passed to every
#                             tool as `--command-config` / `--producer.config`. This is how the
#                             script reaches a broker that wants SASL or TLS. Unset means a
#                             plaintext, unauthenticated connection.
#   KUI_SEED_TIMEOUT_SECONDS  Optional, default 180. How long to wait for the cluster to have all
#                             the brokers this profile expects before giving up.
#   KUI_SEED_EXPECT_BROKERS   Optional. Wait until at least this many brokers are up before doing
#                             anything. Without it the script proceeds as soon as ONE broker
#                             answers — which on a three-broker cluster would mean creating every
#                             topic with a replication factor of 1, because that is all the
#                             cluster could offer at that instant. Set it to the broker count the
#                             profile is written for.
#   KUI_SEED_LOG_LEVEL        Optional, default WARN. Kafka's own tools are noisy; set INFO or
#                             DEBUG when this script misbehaves.
#   KAFKA_BIN_DIR             Optional, default /opt/kafka/bin.
#
# A Compose service that satisfies all of the above:
#
#   seed-production:
#     image: apache/kafka:4.3.1
#     entrypoint: ["/bin/bash", "/seed/seed.sh"]
#     environment:
#       KAFKA_BOOTSTRAP_SERVERS: kafka-prod-1:9092
#       KUI_SEED_PROFILE: production
#       KUI_SEED_EXPECT_BROKERS: "3"
#     volumes:
#       - ./seed:/seed:ro
#     depends_on:
#       kafka-prod-1: { condition: service_healthy }
#       kafka-prod-2: { condition: service_healthy }
#       kafka-prod-3: { condition: service_healthy }
#     restart: "no"
#
# =============================================================================================
# WHAT IT GUARANTEES
# =============================================================================================
#
# Idempotent. Running it a second, fifth or hundredth time changes nothing and fails at nothing.
# A topic is created only when absent, messages are produced only into a topic that holds none,
# and a consumer group is given offsets only when that group does not already exist. The last of
# those matters more than it looks: if you have been clicking around in KUI, having a container
# restart rewind your consumer group underneath you would be a small betrayal.
#
# Fast, because it stands between a reader and their first look at the product. Each of Kafka's
# shell tools costs a JVM start of a second or two, so the script spends as few of them as it can:
# one call to list every topic, one to read every topic's end offset, one to list every group, and
# the unavoidably-per-topic ones run all at once rather than in a queue.
#
# =============================================================================================
# WHAT IT DOES NOT DO
# =============================================================================================
#
# It does not start an actively-consuming group. A group has members only while some process is
# holding a session open with the broker, and this script exits. The consumers that do that live
# beside this one in `consume.sh` and run as long-lived Compose services. This script prepares the
# topics they read and stops there.
#
# It cannot manufacture an under-replicated partition. Under-replication is a fact about a
# cluster that is missing a broker, not a topic setting: Kafka's controller refuses a replica
# assignment naming a broker that does not exist, so there is no way to write one in from outside.
# What this script does instead is create `shipping.dispatches` with `min.insync.replicas` equal to
# its full replication factor, which is the topic that goes read-only the moment a single broker
# goes away. Stop one broker of the production cluster and that topic — and only that topic —
# starts refusing writes, while the rest carry on with a shrunken in-sync replica set. That is the
# demonstration, and it belongs to the Compose stack, not here.

set -o errexit
set -o nounset
set -o pipefail

readonly SEED_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

readonly BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-}"
readonly PROFILE="${KUI_SEED_PROFILE:-}"
readonly COMMAND_CONFIG="${KUI_SEED_COMMAND_CONFIG:-}"
readonly TIMEOUT_SECONDS="${KUI_SEED_TIMEOUT_SECONDS:-180}"
readonly EXPECT_BROKERS="${KUI_SEED_EXPECT_BROKERS:-1}"
readonly BIN="${KAFKA_BIN_DIR:-/opt/kafka/bin}"

# Kafka's shell tools read log4j settings from this variable. Without it every command prints a
# wall of INFO lines about client configuration and the useful output is lost in it.
export KAFKA_LOG4J_OPTS="-Dlog4j2.rootLogger.level=${KUI_SEED_LOG_LEVEL:-WARN}"
# These tools do not need much heap, and the default is a share of the host's RAM, which on a
# large machine means a slow start for nothing. Sixteen JVMs at 256 MB is a size a laptop has.
export KAFKA_HEAP_OPTS="${KAFKA_HEAP_OPTS:--Xms64m -Xmx256m}"
# Kafka's launcher scripts set garbage-collection and compiler flags tuned for a BROKER: a process
# that runs for months and is worth optimising hard at startup. Every process this script starts
# lives for two seconds, so those flags are all cost and no benefit. `UseSerialGC` skips building
# a parallel collector's thread pools and heap regions, and `TieredStopAtLevel=1` stops the JIT at
# the quick compiler instead of profiling code that is about to exit. Measured on one invocation
# of kafka-topics.sh: 4.6s down to 3.3s, and — the number that matters more here, because this
# script starts sixteen of them at once — 2.7s of CPU down to 1.6s.
export KAFKA_JVM_PERFORMANCE_OPTS="${KAFKA_JVM_PERFORMANCE_OPTS:--XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.awt.headless=true}"

# The marker that means "this field is null, not empty". Used for the tombstone records in the
# compacted topics: a key with no value at all, which is how a delete is expressed in Kafka.
readonly NULL_MARKER='<NULL>'

# ---------------------------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------------------------

started_at=$(date +%s)

log()  { printf '[seed:%s] %s\n' "${PROFILE:-?}" "$*"; }
# Every step announces how far into the run it is. Nearly all the cost of this script is JVM
# starts, and when somebody says it feels slow this is the line that says which of them.
step() { printf '[seed:%s] %4ss  %s\n' "${PROFILE:-?}" "$(elapsed)" "$*"; }
die()  { printf '[seed:%s] ERROR: %s\n' "${PROFILE:-?}" "$*" >&2; exit 1; }

elapsed() { echo $(( $(date +%s) - started_at )); }

# ---------------------------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------------------------

[[ -n "${BOOTSTRAP}" ]] || die "KAFKA_BOOTSTRAP_SERVERS is not set. It must hold a broker address such as kafka-prod-1:9092."
[[ -n "${PROFILE}" ]]   || die "KUI_SEED_PROFILE is not set. It must name a directory under ${SEED_DIR}/profiles."
[[ -x "${BIN}/kafka-topics.sh" ]] || die "Kafka's shell tools are not at ${BIN}. Run this in an image that has them, or set KAFKA_BIN_DIR."

readonly PROFILE_DIR="${SEED_DIR}/profiles/${PROFILE}"
readonly DATA_DIR="${PROFILE_DIR}/data"
readonly TOPICS_FILE="${PROFILE_DIR}/topics.tsv"
readonly GROUPS_FILE="${PROFILE_DIR}/groups.tsv"
readonly BULK_FILE="${PROFILE_DIR}/bulk.tsv"

if [[ ! -d "${PROFILE_DIR}" ]]; then
  available=$(cd "${SEED_DIR}/profiles" 2>/dev/null && echo * || echo "none — is this directory mounted?")
  die "there is no profile called '${PROFILE}'. Available: ${available}"
fi
[[ -r "${TOPICS_FILE}" ]] || die "${TOPICS_FILE} is missing. Every profile must have a topic table."

if [[ -n "${COMMAND_CONFIG}" ]]; then
  [[ -r "${COMMAND_CONFIG}" ]] || die "KUI_SEED_COMMAND_CONFIG points at ${COMMAND_CONFIG}, which cannot be read."
fi

# Every Kafka tool takes the connection the same way, but they disagree on what to call the
# properties file: the admin tools say --command-config, the console producer says --producer.config.
# These two arrays hide that difference from the rest of the script.
admin_conn=(--bootstrap-server "${BOOTSTRAP}")
producer_conn=(--bootstrap-server "${BOOTSTRAP}")
if [[ -n "${COMMAND_CONFIG}" ]]; then
  admin_conn+=(--command-config "${COMMAND_CONFIG}")
  producer_conn+=(--producer.config "${COMMAND_CONFIG}")
fi

# ---------------------------------------------------------------------------------------------
# Step 1: wait for the cluster, and find out how many brokers it has
# ---------------------------------------------------------------------------------------------
#
# One command answers both questions. `kafka-broker-api-versions.sh` fails outright while no
# broker will talk to us, and when one does it prints a block per broker beginning with
# "host:port (id: 1 rack: null) -> (", so counting those blocks counts the brokers.
#
# The broker count is not trivia. Kafka refuses to create a topic whose replication factor is
# higher than the number of brokers, and the production profile asks for 3 throughout. Worse than
# refusing, though, is succeeding too early: on a three-broker cluster where only the first broker
# has come up, every topic would be created with one replica and stay that way for the life of the
# stack, and the in-sync-replica column KUI is meant to show would be empty for ever. That is what
# KUI_SEED_EXPECT_BROKERS prevents.

broker_count=0
step "waiting for ${EXPECT_BROKERS} broker(s) at ${BOOTSTRAP} (up to ${TIMEOUT_SECONDS}s)"
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while :; do
  if api_versions=$("${BIN}/kafka-broker-api-versions.sh" "${admin_conn[@]}" 2>/dev/null); then
    broker_count=$(printf '%s\n' "${api_versions}" | grep -c ' (id: ' || true)
    [[ "${broker_count}" -ge "${EXPECT_BROKERS}" ]] && break
  fi
  if [[ $(date +%s) -ge ${deadline} ]]; then
    die "only ${broker_count} of ${EXPECT_BROKERS} expected broker(s) answered at ${BOOTSTRAP} within ${TIMEOUT_SECONDS}s."
  fi
  sleep 2
done
log "cluster is up: ${broker_count} broker(s) after $(elapsed)s"

# ---------------------------------------------------------------------------------------------
# Step 2: read the topic table
# ---------------------------------------------------------------------------------------------

topic_names=()
topic_partitions=()
topic_replication=()
topic_configs=()

while IFS=$'\t' read -r name partitions replication configs; do
  [[ -z "${name}" || "${name}" == \#* ]] && continue
  # Ask for no more replicas than there are brokers to put them on. The production profile runs
  # on three brokers and uses its numbers as written; the development and secured profiles run on
  # one broker, and everything they ask for comes down to 1 here.
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
# start per topic on every run. Listing the topics once is one JVM start for all of them, and on a
# second run it means no create calls at all.

step "reading the topic list"
existing_topics=$("${BIN}/kafka-topics.sh" "${admin_conn[@]}" --list 2>/dev/null || true)

create_pids=()
created=()
for i in "${!topic_names[@]}"; do
  name="${topic_names[$i]}"
  if grep -qxF "${name}" <<<"${existing_topics}"; then
    continue
  fi
  args=("${admin_conn[@]}" --create --if-not-exists
        --topic "${name}"
        --partitions "${topic_partitions[$i]}"
        --replication-factor "${topic_replication[$i]}")
  if [[ "${topic_configs[$i]}" != "-" && -n "${topic_configs[$i]}" ]]; then
    # One --config per key=value pair. Pairs are separated by semicolons, not commas, because a
    # Kafka config value can contain a comma of its own: `cleanup.policy=compact,delete`.
    IFS=';' read -r -a config_pairs <<<"${topic_configs[$i]}"
    for pair in "${config_pairs[@]}"; do
      # min.insync.replicas is the other setting a small cluster cannot honour. Asking for two
      # in-sync replicas on a one-broker cluster creates the topic happily and then rejects every
      # write to it with NOT_ENOUGH_REPLICAS — a failure that would surface here as an empty topic
      # and no explanation. Clamp it the same way as the replication factor.
      if [[ "${pair}" == min.insync.replicas=* && "${pair#min.insync.replicas=}" -gt "${topic_replication[$i]}" ]]; then
        pair="min.insync.replicas=${topic_replication[$i]}"
      fi
      args+=(--config "${pair}")
    done
  fi
  created+=("${name}")
  # All of them at once: sixteen JVMs starting together take about as long as one.
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
  log "topics: created ${#created[@]} of ${#topic_names[@]} (${created[*]})"
fi

# ---------------------------------------------------------------------------------------------
# Step 4: find out which topics already hold messages
# ---------------------------------------------------------------------------------------------
#
# `kafka-get-offsets.sh` prints the end offset of every partition as `topic:partition:offset`. A
# topic whose offsets all read zero has never been written to, so it is one we still have to fill.
#
# It is called with no topic filter at all, which asks about every topic on the cluster in one JVM
# start. That is both the fastest form and the least breakable one: the tool's `--topic-partitions`
# syntax is fussy about what it accepts, and getting it subtly wrong here would be an expensive
# mistake — the check would quietly return nothing, every topic would look empty, and each run
# would append another copy of the sample data.

step "checking which topics already hold messages"
offsets=$("${BIN}/kafka-get-offsets.sh" "${admin_conn[@]}" 2>/dev/null || true)
[[ -n "${offsets}" ]] || die "could not read topic offsets, so there is no safe way to tell whether this cluster has already been seeded. Refusing to write anything."

topic_has_records() {
  local topic="$1" record_count
  record_count=$(printf '%s\n' "${offsets}" \
    | awk -F: -v t="${topic}" '$1 == t { total += $3 } END { print total + 0 }')
  [[ "${record_count}" -gt 0 ]]
}

# ---------------------------------------------------------------------------------------------
# Step 5: the messages
# ---------------------------------------------------------------------------------------------
#
# There are two sources of messages and they answer two different needs.
#
# HAND-WRITTEN, in data/<topic>. Every line was written by a person to be read by a person: an
# order that was cancelled, a dead letter whose headers point at a real offset in another topic, a
# log line that is deliberately not JSON. These are what somebody actually opens the message
# browser to look at, and there are only ever a few dozen of them.
#
# GENERATED, listed in bulk.tsv. A virtualised table and an offset-range seek are not exercised by
# forty records; they need thousands, and nobody wants to read thousands or store them in git. So
# the bulk topics are produced by a generator here, from a seed value, which means the same run
# produces the same data and the repository stays small.
#
# Both end up in the same line format and go through the same producer, so a topic can move from
# one to the other by deleting a data file and adding a bulk.tsv row.
#
# The line format, unchanged from the quickstart's seed so files are interchangeable:
#
#   headers-key-value   h1:v1,h2:v2 <TAB> key <TAB> value
#   key-value           key <TAB> value
#   value-only          value
#
# The separator in the files is the two literal characters \t rather than a real tab, so the files
# survive an editor that turns tabs into spaces; they are converted here. %TS-n% and %TS+n% become
# an ISO-8601 UTC timestamp n seconds before or after this run, which is what stops the sample data
# reading as a museum piece a week after it was written.

render() {
  local file="$1" now
  now=$(date -u +%s)
  # Two passes, both cheap: timestamps first, then the tab separators. Doing it in awk rather than
  # a shell loop keeps a forty-line file to a single process.
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

# Sends lines from standard input to a topic. The caller says which of the three layouts the lines
# use; everything else about the producer is the same either way.
producer_for() {
  local topic="$1" mode="$2"
  local args=("${producer_conn[@]}" --topic "${topic}" --property "null.marker=${NULL_MARKER}")
  case "${mode}" in
    headers-key-value) args+=(--property parse.headers=true --property parse.key=true) ;;
    key-value)         args+=(--property parse.key=true) ;;
    value-only)        ;;
    *) die "unknown message layout '${mode}' for topic ${topic}." ;;
  esac
  # A batch of ten thousand records is thousands of round trips at the default batch size, and
  # that is most of the wall-clock cost of seeding the production cluster. Larger batches held
  # slightly longer turn it into tens of round trips. `acks=1` rather than `all` because this is
  # demonstration data on a throwaway cluster: losing a record to a broker restart mid-seed would
  # be caught by the idempotence check on the next run anyway.
  args+=(--producer-property batch.size=262144
         --producer-property linger.ms=50
         --producer-property compression.type=lz4
         --producer-property acks=1)
  # A RECORD WITH NO KEY DOES NOT GO ROUND-ROBIN BY DEFAULT, whatever everybody assumes. Kafka's
  # default partitioner is "sticky": it fills one partition's batch, then picks another. With the
  # large batches set above, three thousand unkeyed records landed on two of six partitions and
  # left four empty — which on a demonstration cluster is not a tuning detail, it is four empty
  # partitions in the partition table and a lag column with holes in it. RoundRobinPartitioner
  # spreads them one at a time, which is what an unkeyed topic is supposed to look like.
  #
  # It is set only for value-only topics. It ignores keys entirely, so using it on a keyed topic
  # would scatter one customer's records across every partition and destroy the ordering that is
  # the whole reason for having a key.
  if [[ "${mode}" == "value-only" ]]; then
    args+=(--producer-property partitioner.class=org.apache.kafka.clients.producer.RoundRobinPartitioner)
  fi
  # stdout is dropped because the console producer greets every run with a deprecation notice
  # about `--property`, which is the only spelling that works on both Kafka 3.x and 4.x. Errors go
  # to stderr and are left alone.
  "${BIN}/kafka-console-producer.sh" "${args[@]}" >/dev/null
}

produce_file() {
  local topic="$1" file="${DATA_DIR}/$1" mode
  mode=$(mode_of "${file}")
  [[ -n "${mode}" ]] || die "${file} does not declare a #mode: line, so its layout is unknown."
  render "${file}" | producer_for "${topic}" "${mode}"
}

# ---------------------------------------------------------------------------------------------
# The bulk generators
# ---------------------------------------------------------------------------------------------
#
# One awk program per shape. Each is handed a record count and a seed, and writes lines in the
# same `\t`-separated format the data files use, with real tabs already in place — they never go
# through render(), because rendering ten thousand lines twice would cost more than producing
# them. They stamp their own timestamps instead, spreading the records backwards from now over a
# realistic window so that a time-based seek in the message browser lands somewhere sensible.
#
# `srand(seed)` makes them deterministic: two runs of the same profile write the same messages, so
# a screenshot taken today still matches the data tomorrow.

#
# THE ONE PERFORMANCE TRAP IN THIS FILE, written down because it cost most of the seeding time
# before it was fixed. Every generated record needs a human-readable UTC timestamp, and the
# obvious way to get one in awk is to shell out to `date -u -d @<seconds>`. Twenty thousand
# records spread over a few hours have nearly twenty thousand DISTINCT second values, so caching
# does not help, and twenty thousand subprocesses is around thirty seconds of pure fork overhead —
# more than everything else this script does put together.
#
# `strftime(format, seconds, utc)` does it inside awk with no process at all. It is not in POSIX
# awk: BusyBox awk has it (which is what the apache/kafka image provides) and so does GNU awk, but
# mawk does not. So the prelude below tests it once and falls back to the slow-but-universal form
# if it is missing, rather than silently emitting the string "strftime" into every message.
readonly AWK_ISO_PRELUDE='
function iso(ts,   cmd) {
  if (have_strftime) return strftime("%Y-%m-%dT%H:%M:%SZ", ts, 1)
  if (ts in _stamp) return _stamp[ts]
  cmd = "date -u -d @" ts " +%Y-%m-%dT%H:%M:%SZ"
  cmd | getline _stamp[ts]
  close(cmd)
  return _stamp[ts]
}
BEGIN { have_strftime = (strftime("%Y", 0, 1) == "1970") }
'

generate() {
  local shape="$1" count="$2" seed="$3" now
  now=$(date -u +%s)
  case "${shape}" in

    # A web analytics firehose: no key, no headers, so records land round-robin and the topic
    # spreads evenly across all its partitions. About one event in five carries an experiment
    # block that the others do not, because that is what a real event stream looks like after a
    # year of shipping, and a JSON viewer has to survive fields appearing and disappearing.
    pageviews)
      awk -v n="${count}" -v now="${now}" -v seed="${seed}" "${AWK_ISO_PRELUDE}"'
        BEGIN {
          srand(seed)
          split("/,/pricing,/docs/quickstart,/blog/kafka-ui-comparison,/login,/account/billing,/search,/docs/api/topics", path, ",")
          split("desktop,mobile,tablet", device, ",")
          split("PL,GB,US,DE,FR,NL,ES,IN", country, ",")
          split("google,direct,twitter,newsletter,partner", referrer, ",")
          window = 21600            # spread the whole batch over the last six hours
          for (i = 0; i < n; i++) {
            at = iso(now - int(window * (n - i) / n))
            printf "{\"eventId\":\"pv-%08d\",\"at\":\"%s\",\"path\":\"%s\",\"sessionId\":\"sess-%06d\",\"device\":\"%s\",\"country\":\"%s\",\"referrer\":\"%s\",\"durationMs\":%d",
                   i, at, path[int(rand()*8)+1], int(rand()*40000), device[int(rand()*3)+1],
                   country[int(rand()*8)+1], referrer[int(rand()*5)+1], int(rand()*30000)
            if (rand() < 0.2)
              printf ",\"experiment\":{\"name\":\"pricing-layout\",\"variant\":\"%s\"}", (rand() < 0.5 ? "control" : "b")
            printf "}\n"
          }
        }' ;;

    # Raw clickstream, keyed by session so that all of one visitor's clicks land on one partition.
    # Keys matter here: this is the topic to open when you want to see what a keyed, high-volume
    # topic looks like, and to watch a partition-filtered browse return a coherent story.
    clickstream)
      awk -v n="${count}" -v now="${now}" -v seed="${seed}" "${AWK_ISO_PRELUDE}"'
        BEGIN {
          srand(seed)
          split("click,scroll,hover,input,submit,close", kind, ",")
          split("nav,hero,pricing-table,signup-form,footer,search-box", element, ",")
          window = 10800
          for (i = 0; i < n; i++) {
            at = iso(now - int(window * (n - i) / n))
            session = sprintf("sess-%06d", int(rand()*4000))
            printf "%s\t{\"sessionId\":\"%s\",\"at\":\"%s\",\"kind\":\"%s\",\"element\":\"%s\",\"x\":%d,\"y\":%d}\n",
                   session, session, at, kind[int(rand()*6)+1], element[int(rand()*6)+1],
                   int(rand()*1920), int(rand()*1080)
          }
        }' ;;

    # Search index updates: keyed by document id, with headers, and a small share of tombstones —
    # a key with no value, which is how a delete is expressed in a compacted topic. This is the
    # high-volume compacted topic, so browsing it shows repeated keys and deletions at scale
    # rather than the three or four a hand-written file can hold.
    index-updates)
      awk -v n="${count}" -v now="${now}" -v seed="${seed}" -v nullmarker="${NULL_MARKER}" "${AWK_ISO_PRELUDE}"'
        BEGIN {
          srand(seed)
          split("product,article,manual,review", kind, ",")
          split("en,pl,de,fr", lang, ",")
          window = 43200
          for (i = 0; i < n; i++) {
            at = iso(now - int(window * (n - i) / n))
            k = kind[int(rand()*4)+1]
            # Only 900 distinct ids for however many records: keys repeat, which is the whole
            # point of a compacted topic and gives the log cleaner something real to do.
            doc = sprintf("%s:%04d", k, int(rand()*900))
            if (rand() < 0.06) {
              printf "op:delete,indexed-at:%s\t%s\t%s\n", at, doc, nullmarker
            } else {
              printf "op:upsert,indexed-at:%s,lang:%s\t%s\t{\"id\":\"%s\",\"kind\":\"%s\",\"title\":\"%s %d\",\"revision\":%d,\"indexedAt\":\"%s\",\"score\":%.3f}\n",
                     at, lang[int(rand()*4)+1], doc, doc, k,
                     toupper(substr(k,1,1)) substr(k,2), int(rand()*9000), int(rand()*40)+1, at, rand()
            }
          }
        }' ;;

    # Per-minute service metrics: no key, small flat objects, and one value in forty deliberately
    # out of range. Something has to be findable with a message-browser filter, and "the request
    # that took nine seconds" is what an operator actually goes looking for.
    metrics)
      awk -v n="${count}" -v now="${now}" -v seed="${seed}" "${AWK_ISO_PRELUDE}"'
        BEGIN {
          srand(seed)
          split("gateway,topic-service,message-service,consumer-service,metadata-store", svc, ",")
          split("p50,p95,p99", quantile, ",")
          window = 86400
          for (i = 0; i < n; i++) {
            at = iso(now - int(window * (n - i) / n))
            latency = (rand() < 0.025) ? 4000 + int(rand()*5000) : 3 + int(rand()*180)
            printf "{\"at\":\"%s\",\"service\":\"%s\",\"quantile\":\"%s\",\"latencyMs\":%d,\"requests\":%d,\"errors\":%d}\n",
                   at, svc[int(rand()*5)+1], quantile[int(rand()*3)+1], latency,
                   int(rand()*5000), int(rand()*12)
          }
        }' ;;

    *) die "bulk.tsv asks for a generator called '${shape}', which does not exist. See the generate() function in seed.sh." ;;
  esac
}

# ---------------------------------------------------------------------------------------------
# Produce everything that needs producing
# ---------------------------------------------------------------------------------------------

produce_pids=()
produced=()
skipped=()

for name in "${topic_names[@]}"; do
  [[ -r "${DATA_DIR}/${name}" ]] || continue
  if topic_has_records "${name}"; then skipped+=("${name}"); continue; fi
  produced+=("${name}")
  produce_file "${name}" &
  produce_pids+=("$!")
done

bulk_total=0
if [[ -r "${BULK_FILE}" ]]; then
  # bulk.tsv: topic <TAB> generator <TAB> count <TAB> layout
  while IFS=$'\t' read -r name shape count mode; do
    [[ -z "${name}" || "${name}" == \#* ]] && continue
    if topic_has_records "${name}"; then skipped+=("${name}"); continue; fi
    produced+=("${name} x${count}")
    bulk_total=$(( bulk_total + count ))
    # The seed for the random number generator is derived from the topic name, so each topic gets
    # different data and the same topic gets the same data on every run.
    topic_seed=$(cksum <<<"${name}" | cut -d' ' -f1)
    generate "${shape}" "${count}" "${topic_seed}" | producer_for "${name}" "${mode}" &
    produce_pids+=("$!")
  done < <(sed 's/[[:space:]]*$//' "${BULK_FILE}")
fi

produce_failed=0
for pid in "${produce_pids[@]:-}"; do
  [[ -n "${pid}" ]] || continue
  wait "${pid}" || produce_failed=1
done
[[ ${produce_failed} -eq 0 ]] || die "one or more topics could not be written to."

if [[ ${#produced[@]} -eq 0 ]]; then
  log "messages: every topic already held records, produced nothing"
else
  generated_note=""
  (( bulk_total > 0 )) && generated_note=", including ${bulk_total} generated"
  log "messages: wrote into ${#produced[@]} topic(s)${generated_note}${skipped:+; ${#skipped[@]} already had records}"
fi

# ---------------------------------------------------------------------------------------------
# Step 6: consumer groups
# ---------------------------------------------------------------------------------------------
#
# A consumer group is, from the outside, nothing but a set of committed offsets. `--reset-offsets
# --execute` writes those offsets directly, which creates the group in one command without
# consuming anything, so a group with realistic lag costs one JVM start instead of a consumer
# session per partition.
#
# groups.tsv says which groups a profile has and where each one sits. Its columns are:
#
#   group <TAB> topic <TAB> position
#
# where `position` is passed through to kafka-consumer-groups.sh as it stands, so it is any of
# that tool's reset specifications: `--to-earliest`, `--to-latest`, `--to-offset 40`,
# `--shift-by -250`. A group can be given more than one row, one per topic, and it will then be
# subscribed to both.
#
# NOT `--to-datetime`, even though the tool offers it, and this is worth knowing before you reach
# for it. Kafka stamps every record with the moment it was PRODUCED, and everything here is
# produced within the same few seconds of the seed running. The timestamps inside the JSON say
# "six hours ago" because a person wrote them that way, but as far as the broker is concerned the
# whole topic happened at once. So `--to-datetime` an hour ago resolves to offset 0 on every
# partition — the same as `--to-earliest`, but by accident and with warnings printed. Use offsets.
#
# A group is skipped entirely if it already exists. Re-running must not rewind a group somebody is
# watching, and it must not fail against a group that currently has members, which is exactly what
# `--reset-offsets` does by design.
#
# The live groups are not here: a group has members only while a process holds a session open, and
# this script exits. Those are consume.sh, run as long-lived services.

if [[ -r "${GROUPS_FILE}" ]]; then
  step "reading the consumer group list"
  existing_groups=$("${BIN}/kafka-consumer-groups.sh" "${admin_conn[@]}" --list 2>/dev/null || true)

  seed_group() {
    local group="$1" topic="$2" position="$3"
    "${BIN}/kafka-consumer-groups.sh" "${admin_conn[@]}" \
      --group "${group}" --topic "${topic}" --reset-offsets ${position} --execute >/dev/null 2>&1
  }

  group_pids=()
  seeded_groups=()
  while IFS=$'\t' read -r group topic position; do
    [[ -z "${group}" || "${group}" == \#* ]] && continue
    if grep -qxF "${group}" <<<"${existing_groups}"; then continue; fi
    seeded_groups+=("${group}/${topic}")
    # `position` is deliberately unquoted inside seed_group: it is a fragment of command line
    # such as `--to-offset 40`, which has to split into two arguments.
    seed_group "${group}" "${topic}" "${position}" &
    group_pids+=("$!")
  done < <(sed 's/[[:space:]]*$//' "${GROUPS_FILE}")

  group_failed=0
  for pid in "${group_pids[@]:-}"; do
    [[ -n "${pid}" ]] || continue
    wait "${pid}" || group_failed=1
  done
  [[ ${group_failed} -eq 0 ]] || die "a consumer group could not be given offsets."

  if [[ ${#seeded_groups[@]} -eq 0 ]]; then
    log "consumer groups: all already present, left untouched"
  else
    log "consumer groups: positioned ${#seeded_groups[@]} (${seeded_groups[*]})"
  fi
fi

# ---------------------------------------------------------------------------------------------

log "done in $(elapsed)s"

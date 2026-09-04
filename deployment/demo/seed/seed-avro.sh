#!/usr/bin/env bash
#
# Writes Avro records into one topic of the KUI demo, through a real Confluent Schema Registry.
#
# WHY THIS IS A SEPARATE SCRIPT FROM seed.sh
#
# seed.sh produces text. Every line of every file under profiles/*/data is a string that a console
# producer writes to Kafka unchanged, and that is the right shape for eight of the nine topics in
# this demonstration. An Avro record is not text: it is a five-byte header — one zero byte, then the
# four-byte id of the schema in the registry — followed by Avro's binary encoding of the record. It
# cannot be produced by a tool that only knows how to write bytes it was handed, because the id in
# the header has to come from registering the schema first.
#
# So this script uses `kafka-avro-console-producer`, which ships in the Confluent Schema Registry
# image. That tool registers the schema under `<topic>-value` if it is not already there, learns the
# id the registry gave it, and writes the header in front of every record it encodes.
#
# WHY IT MATTERS THAT THE DEMONSTRATION HAS ONE
#
# A message browser that cannot decode Avro is a message browser that shows a wall of Base64 to the
# large fraction of real Kafka deployments that use a registry. Nothing else in this demonstration
# exercises that path end to end: the `_schemas` topic in the seed profiles contains records that
# LOOK like a registry's, but no registry ever wrote them and no producer ever used them. This topic
# is the one whose contents can only be read by going and fetching a schema.
#
# ENVIRONMENT
#
#   KAFKA_BOOTSTRAP_SERVERS   REQUIRED. The broker to produce to.
#   KUI_SCHEMA_REGISTRY_URL   REQUIRED. The registry to register the schema with.
#   KUI_AVRO_TOPIC            Optional, default orders.avro.v1.
#   KUI_AVRO_DIR              Optional, default the `avro` directory beside this script.
#
# It is idempotent: a topic that already holds records is left alone, so restarting the stack does
# not double the data.

set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:?KAFKA_BOOTSTRAP_SERVERS is required}"
REGISTRY="${KUI_SCHEMA_REGISTRY_URL:?KUI_SCHEMA_REGISTRY_URL is required}"
TOPIC="${KUI_AVRO_TOPIC:-orders.avro.v1}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AVRO_DIR="${KUI_AVRO_DIR:-${HERE}/avro}"

say() { echo "[avro] $*"; }

# ---------------------------------------------------------------------------------------------
# Wait for the registry. Compose can only say "the container started", and a Schema Registry
# spends its first few seconds reading the whole of `_schemas` before it will answer anything.
# ---------------------------------------------------------------------------------------------
say "waiting for the schema registry at ${REGISTRY}"
for _ in $(seq 1 60); do
  if curl -fsS "${REGISTRY}/subjects" >/dev/null 2>&1; then
    say "the registry is answering"
    break
  fi
  sleep 2
done
curl -fsS "${REGISTRY}/subjects" >/dev/null

# ---------------------------------------------------------------------------------------------
# Idempotence: produce only into a topic that holds nothing. Restarting the stack must not double
# the data.
#
# This is done by trying to CONSUME one record rather than by asking for the topic's end offsets,
# because the Schema Registry image has no `kafka-topics` and no `kafka-get-offsets` in it -- it
# ships the console tools that speak to a registry and nothing else.
#
# What is tested is the tool's OUTPUT, not its exit code, and only the lines of it that are a
# record. `kafka-avro-console-consumer` exits 0 whether it read a record or gave up waiting for
# one, so an exit status would report every empty topic as full and this script would never produce
# anything at all -- and it writes its own configuration dump to the same stdout as the records, so
# "any output" would do the same. A decoded Avro record is JSON and starts with a brace; every log
# line the tool writes starts with a bracketed timestamp.
#
# The topic itself is created by seed.sh from profiles/development/topics.tsv, in the image that
# does have Kafka's admin tools. Producing into a topic that does not exist yet would have the
# broker auto-create it with its own defaults instead of the ones that table asks for.
# ---------------------------------------------------------------------------------------------
existing="$(kafka-avro-console-consumer \
  --bootstrap-server "${BOOTSTRAP}" \
  --topic "${TOPIC}" \
  --from-beginning --max-messages 1 --timeout-ms 15000 \
  --property schema.registry.url="${REGISTRY}" 2>/dev/null | grep -m1 '^{' || true)"

if [ -n "${existing}" ]; then
  say "${TOPIC} already holds records; nothing to do"
  exit 0
fi

say "producing $(grep -c . "${AVRO_DIR}/orders.avro.v1.jsonl") Avro records into ${TOPIC}"
kafka-avro-console-producer \
  --bootstrap-server "${BOOTSTRAP}" \
  --topic "${TOPIC}" \
  --property schema.registry.url="${REGISTRY}" \
  --property value.schema="$(cat "${AVRO_DIR}/order.avsc")" \
  < "${AVRO_DIR}/orders.avro.v1.jsonl"

say "done; the subject is ${TOPIC}-value"

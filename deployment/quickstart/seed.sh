#!/usr/bin/env bash
#
# Puts a small, realistic-looking workload into the quickstart broker: a few topics with sensible
# names and shapes, JSON messages inside them, and a consumer group that is deliberately behind so
# there is lag to look at.
#
# It runs inside the `apache/kafka` image, which already carries Kafka's own command-line tools, so
# nothing has to be installed and no extra image has to be pulled.
#
# It is safe to run twice: creating a topic that exists is ignored, and the offsets it sets are
# absolute rather than relative.

set -euo pipefail

BOOTSTRAP="${BOOTSTRAP:-kafka:9092}"
BIN=/opt/kafka/bin

# Compose already waits for the broker's readiness health check before starting this container.
# This second wait is not redundant: it costs one call in the normal case, and it turns "the
# broker was restarted while this was starting" from a confusing stack trace into a short pause.
echo "seed: waiting for ${BOOTSTRAP} to serve metadata"
for attempt in $(seq 1 60); do
  if "${BIN}/kafka-topics.sh" --bootstrap-server "${BOOTSTRAP}" --list >/dev/null 2>&1; then
    break
  fi
  if [ "${attempt}" -eq 60 ]; then
    echo "seed: ${BOOTSTRAP} never became ready" >&2
    exit 1
  fi
  sleep 2
done

create_topic() {
  local name="$1" partitions="$2"
  shift 2
  echo "seed: topic ${name} (${partitions} partitions)"
  "${BIN}/kafka-topics.sh" --bootstrap-server "${BOOTSTRAP}" \
    --create --if-not-exists \
    --topic "${name}" \
    --partitions "${partitions}" \
    --replication-factor 1 \
    "$@" >/dev/null
}

# Shapes a person would recognise from a real system: a high-volume event stream spread over
# several partitions, a couple of ordinary domain topics, a compacted table-like topic, and the
# dead-letter queue every pipeline eventually grows.
create_topic orders 6
create_topic payments 3
create_topic customer-events 3
create_topic inventory-snapshots 1 --config cleanup.policy=compact --config min.cleanable.dirty.ratio=0.1
create_topic orders.DLQ 1

# ----------------------------------------------------------------------------------------------
# Messages. `--property parse.key=true` with a tab separator means each line is "key<TAB>value",
# so the records get real keys and therefore land on partitions by key rather than round-robin —
# which is what makes a partition view look like a real one.
# ----------------------------------------------------------------------------------------------
produce() {
  local topic="$1"
  "${BIN}/kafka-console-producer.sh" --bootstrap-server "${BOOTSTRAP}" \
    --topic "${topic}" \
    --property parse.key=true \
    --property key.separator=$'\t' >/dev/null
}

echo "seed: publishing orders"
{
  regions=(eu-west us-east ap-south)
  statuses=(PLACED PAID SHIPPED CANCELLED)
  items=(keyboard monitor laptop desk-lamp headset cable)
  for i in $(seq 1 120); do
    order=$(printf 'ORD-%05d' "${i}")
    region=${regions[$((i % 3))]}
    status=${statuses[$((i % 4))]}
    item=${items[$((i % 6))]}
    cents=$((1999 + (i * 737) % 48000))
    printf '%s\t{"orderId":"%s","customerId":"CUST-%04d","region":"%s","status":"%s","lines":[{"sku":"%s","quantity":%d,"unitPriceCents":%d}],"totalCents":%d,"placedAt":"2026-09-0%dT1%d:%02d:00Z"}\n' \
      "${order}" "${order}" "$((i % 40))" "${region}" "${status}" "${item}" "$((1 + i % 3))" \
      "${cents}" "${cents}" "$((1 + i % 9))" "$((i % 10))" "$((i % 60))"
  done
} | produce orders

echo "seed: publishing payments"
{
  methods=(card sepa paypal)
  for i in $(seq 1 60); do
    printf 'PAY-%05d\t{"paymentId":"PAY-%05d","orderId":"ORD-%05d","method":"%s","amountCents":%d,"currency":"EUR","captured":%s}\n' \
      "${i}" "${i}" "${i}" "${methods[$((i % 3))]}" "$((1999 + (i * 991) % 48000))" \
      "$([ $((i % 7)) -eq 0 ] && echo false || echo true)"
  done
} | produce payments

echo "seed: publishing customer-events"
{
  types=(SIGNED_UP EMAIL_VERIFIED ADDRESS_CHANGED CONSENT_GRANTED)
  for i in $(seq 1 45); do
    printf 'CUST-%04d\t{"customerId":"CUST-%04d","type":"%s","occurredAt":"2026-09-0%dT0%d:%02d:00Z","source":"web"}\n' \
      "$((i % 40))" "$((i % 40))" "${types[$((i % 4))]}" "$((1 + i % 9))" "$((i % 10))" "$((i % 60))"
  done
} | produce customer-events

echo "seed: publishing inventory-snapshots"
{
  # A compacted topic is a table: one current value per key. Writing each key twice makes that
  # visible — the older value is the one compaction is entitled to remove.
  for pass in 1 2; do
    for sku in keyboard monitor laptop desk-lamp headset cable; do
      printf '%s\t{"sku":"%s","onHand":%d,"reserved":%d,"warehouse":"WH-%d","snapshotPass":%d}\n' \
        "${sku}" "${sku}" "$((RANDOM % 500))" "$((RANDOM % 40))" "${pass}" "${pass}"
    done
  done
} | produce inventory-snapshots

echo "seed: publishing orders.DLQ"
{
  for i in 7 23 61 88; do
    printf 'ORD-%05d\t{"originalTopic":"orders","originalPartition":%d,"originalOffset":%d,"error":"DeserializationException: unexpected token at position 14","failedAt":"2026-09-03T09:%02d:00Z"}\n' \
      "${i}" "$((i % 6))" "${i}" "$((i % 60))"
  done
} | produce orders.DLQ

# ----------------------------------------------------------------------------------------------
# A consumer group with lag.
#
# `kafka-consumer-groups.sh --reset-offsets` on a group that has no live members creates the group
# and writes the offsets directly. That is used here instead of running a consumer for a while and
# stopping it, because it produces the same numbers on every machine: a consumer racing a timer
# gives a different lag on a fast laptop than on a loaded CI box, and a demonstration that shows a
# different number every run is one nobody trusts.
#
# `analytics-pipeline` is set to offset 12 on every partition of `orders`, which leaves it about 50
# messages behind in total. `dlq-alerter` is caught up, so there is a healthy group to compare with.
# ----------------------------------------------------------------------------------------------
echo "seed: creating consumer groups"
"${BIN}/kafka-consumer-groups.sh" --bootstrap-server "${BOOTSTRAP}" \
  --group analytics-pipeline --topic orders --reset-offsets --to-offset 12 --execute >/dev/null
"${BIN}/kafka-consumer-groups.sh" --bootstrap-server "${BOOTSTRAP}" \
  --group dlq-alerter --topic orders.DLQ --reset-offsets --to-latest --execute >/dev/null

echo "seed: done"
"${BIN}/kafka-topics.sh" --bootstrap-server "${BOOTSTRAP}" --list | sed 's/^/seed:   topic /'

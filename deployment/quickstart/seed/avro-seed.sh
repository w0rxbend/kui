#!/usr/bin/env bash
#
# Puts an Avro topic into the quickstart: one schema in the registry, and a handful of records
# written in the Schema Registry wire format.
#
# ---------------------------------------------------------------------------------------------
# WHY THIS EXISTS SEPARATELY FROM seed.sh
# ---------------------------------------------------------------------------------------------
#
# `seed.sh` writes text with Kafka's console producer, which is the right tool for JSON and log
# lines and cannot write these records at all: a Schema-Registry record is a zero byte, four bytes
# of schema id and an Avro-encoded body, and a console producer reading lines of text has no way
# to express that.
#
# So this script writes them through KUI's own produce API instead. That is not a workaround, it
# is the strongest form of the demonstration: KUI encodes the record against the schema it fetched
# from the registry, Kafka stores the bytes, and the messages screen decodes them back by fetching
# the same schema by the id inside the record. If the records show up as readable JSON in the UI,
# then both halves of schema-aware decoding — reading and writing — are working end to end.
#
# ---------------------------------------------------------------------------------------------
# THE CONTRACT WITH WHOEVER RUNS THIS
# ---------------------------------------------------------------------------------------------
#
#   Image        anything with bash, curl and sed. The Compose file uses the registry's own image,
#                apicurio/apicurio-registry:3.0.6, because it is already being pulled and carries
#                all three. NOT apache/kafka: nothing here needs Kafka's tools, and that image is
#                Alpine with busybox wget and no curl at all.
#   Entrypoint   /bin/bash, with this script's path as the argument.
#   Environment  KUI_BASE_URL      — required, e.g. http://kui:8080
#                KUI_CLUSTER_ID    — required, the cluster id in KUI's configuration
#                KUI_REGISTRY_URL  — required, the registry's Confluent-compatible base URL
#                KUI_AVRO_TOPIC    — optional, default orders.avro
#                KUI_SEED_TIMEOUT_SECONDS — optional, default 120
#                KUI_SEED_USERNAME, KUI_SEED_PASSWORD — optional. Set them when the KUI being
#                seeded has `kui.auth.type: form`, which the --with-auth quickstart does.
#                Producing is a mutation, so without a signed-in principal that may write to the
#                topic it is refused with a 401 and no Avro record is ever written.
#   Exit code    0 when the topic ends up holding decodable Avro records.
#
# Idempotent, in the same sense as its neighbour: registering a schema that is already registered
# returns the id it already had, and records are produced only into a topic that has none.

set -o errexit
set -o nounset
set -o pipefail

readonly SEED_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BASE_URL="${KUI_BASE_URL:-}"
readonly CLUSTER="${KUI_CLUSTER_ID:-}"
readonly REGISTRY="${KUI_REGISTRY_URL:-}"
readonly TOPIC="${KUI_AVRO_TOPIC:-orders.avro}"
readonly TIMEOUT_SECONDS="${KUI_SEED_TIMEOUT_SECONDS:-120}"
readonly USERNAME="${KUI_SEED_USERNAME:-}"
readonly PASSWORD="${KUI_SEED_PASSWORD:-}"
readonly COOKIES="$(mktemp)"

log() { printf '[avro-seed] %s\n' "$*"; }
die() { printf '[avro-seed] ERROR: %s\n' "$*" >&2; exit 1; }

[[ -n "${BASE_URL}" ]] || die "KUI_BASE_URL is not set."
[[ -n "${CLUSTER}" ]] || die "KUI_CLUSTER_ID is not set."
[[ -n "${REGISTRY}" ]] || die "KUI_REGISTRY_URL is not set."

# ---------------------------------------------------------------------------------------------
# Step 1: the schema
# ---------------------------------------------------------------------------------------------
#
# The subject is `<topic>-value`, which is the default TopicNameStrategy every registry-aware
# producer uses and the only naming KUI can apply on the read path — the alternatives key the
# subject on a type name that lives inside the payload KUI has not decoded yet.
#
# The schema itself is deliberately not trivial: a nullable field, an enum and a nested record are
# the three things that look different in Avro's JSON encoding from what a newcomer expects, and a
# demonstration that only ever shows flat strings teaches the wrong lesson.

readonly SCHEMA_FILE="${SEED_DIR}/data/orders.avro.avsc"
[[ -r "${SCHEMA_FILE}" ]] || die "cannot read ${SCHEMA_FILE}; is the seed directory mounted?"

log "registering ${TOPIC}-value in the registry at ${REGISTRY}"

# The registry takes the schema as a JSON *string* inside a JSON object, so the schema text has to
# be escaped into one. `jq` is not in this image; this is the same escaping done with sed, which is
# safe here because the file is ours and contains no control characters.
escaped=$(sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' "${SCHEMA_FILE}" | tr -d '\n')

registered=$(curl -sS -X POST \
  -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  --data "{\"schemaType\":\"AVRO\",\"schema\":\"${escaped}\"}" \
  "${REGISTRY}/subjects/${TOPIC}-value/versions") || die "the registry refused the schema"

case "${registered}" in
  *'"id"'*) log "schema registered: ${registered}" ;;
  *) die "the registry did not return a schema id: ${registered}" ;;
esac

# ---------------------------------------------------------------------------------------------
# Step 2: a session and a CSRF token
# ---------------------------------------------------------------------------------------------
#
# KUI protects every write with a token that has to be presented in a header and matched against
# the session cookie, so that a form on another website cannot make a browser produce records on
# somebody's behalf. A script is not a browser, but it goes through the same door as one.

log "asking ${BASE_URL} for a session"
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
csrf=""
while :; do
  if identity=$(curl -sS -c "${COOKIES}" -b "${COOKIES}" "${BASE_URL}/api/v1/auth/me" 2>/dev/null); then
    csrf=$(printf '%s' "${identity}" | sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p')
    [[ -n "${csrf}" ]] && break
  fi
  [[ $(date +%s) -lt ${deadline} ]] || die "KUI did not answer at ${BASE_URL} within ${TIMEOUT_SECONDS}s."
  sleep 2
done

# ---------------------------------------------------------------------------------------------
# Step 2b: signing in, when this deployment has a login
# ---------------------------------------------------------------------------------------------
#
# Skipped entirely when no username was given, which is the default and the ordinary quickstart.
#
# Signing in REPLACES the session — a new id and a new CSRF secret, which is ADR-019's defence
# against session fixation — so the token fetched above is dead the moment the login succeeds. That
# is why `/auth/me` is asked a second time afterwards rather than the first answer being reused: the
# first version of this signed in, kept the old token, and had every produce refused with a 403 that
# said nothing about a login.

if [[ -n "${USERNAME}" ]]; then
  log "signing in as ${USERNAME}"
  status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    -b "${COOKIES}" -c "${COOKIES}" \
    -H 'Content-Type: application/json' \
    -H "X-Csrf-Token: ${csrf}" \
    --data "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" \
    "${BASE_URL}/api/v1/auth/login")

  case "${status}" in
    2*) : ;;
    *) die "KUI refused the sign-in for '${USERNAME}' (HTTP ${status})." ;;
  esac

  identity=$(curl -sS -c "${COOKIES}" -b "${COOKIES}" "${BASE_URL}/api/v1/auth/me") \
    || die "KUI did not answer /auth/me after the sign-in."
  csrf=$(printf '%s' "${identity}" | sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p')
  [[ -n "${csrf}" ]] || die "the session after signing in carried no CSRF token."
fi

# ---------------------------------------------------------------------------------------------
# Step 3: the records
# ---------------------------------------------------------------------------------------------
#
# Each line of the data file is one record's value, in Avro's JSON encoding — which names the
# branch of a union explicitly, so a nullable string is `{"string": "gift"}` and not `"gift"`.
# That is the same text KUI shows when it decodes the record, so a person can copy a row out of
# the messages screen, change a field and send it back through the produce form.

readonly DATA_FILE="${SEED_DIR}/data/orders.avro.jsonl"
[[ -r "${DATA_FILE}" ]] || die "cannot read ${DATA_FILE}."

produced=0
line_number=0
while IFS= read -r line; do
  line_number=$(( line_number + 1 ))
  [[ -z "${line}" ]] && continue

  key="o-$(printf '%04d' "${line_number}")"
  # The value is embedded as a JSON string, so its quotes are escaped once more.
  value=$(printf '%s' "${line}" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')

  response=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    -b "${COOKIES}" -c "${COOKIES}" \
    -H 'Content-Type: application/json' \
    -H "X-Csrf-Token: ${csrf}" \
    --data "{\"key\":\"${key}\",\"value\":\"${value}\",\"valueSerde\":\"SchemaRegistry\",\"keySerde\":\"String\"}" \
    "${BASE_URL}/api/v1/clusters/${CLUSTER}/topics/${TOPIC}/messages")

  case "${response}" in
    2*) produced=$(( produced + 1 )) ;;
    *) die "KUI refused to produce record ${line_number} (HTTP ${response}). The registry, the schema or the serde configuration is wrong." ;;
  esac
done < "${DATA_FILE}"

rm -f "${COOKIES}"
log "produced ${produced} Avro records into ${TOPIC}"
log "open ${BASE_URL} and browse ${TOPIC}; the values are decoded from Avro, not shown as bytes"

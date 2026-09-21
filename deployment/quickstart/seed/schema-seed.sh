#!/usr/bin/env bash
#
# Registers and seeds one real topic for each Schema Registry wire format KUI can read:
# Avro, JSON Schema and Protobuf.
#
# Avro and JSON Schema records go through KUI's SchemaRegistry serializer. KUI deliberately does
# not write Protobuf yet, so the Protobuf fixture uses KUI's lossless Hex serializer with a frame
# assembled here: magic byte 00, a four-byte big-endian registry id, message index 00, then a body
# generated and checked with protoc. All three still travel through the same mutation endpoint and
# are subsequently decoded through the same SchemaRegistry reader in the message browser.
#
# The script is idempotent. Schema registration returns the existing id for an existing schema,
# and a topic that already has a record is never appended to. This matters when `compose up` is run
# again after somebody has browsed or produced messages of their own.
#
# Compose runs this in the already-pulled Apicurio Registry image: this job talks HTTP and needs
# bash, curl and sed, while the Apache Kafka image has Kafka's tools but no curl. No package is
# installed at startup and no extra image is introduced just for the fixture.

set -o errexit
set -o nounset
set -o pipefail

readonly SEED_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly DATA_DIR="${SEED_DIR}/data"
readonly BASE_URL="${KUI_BASE_URL:-}"
readonly CLUSTER="${KUI_CLUSTER_ID:-}"
readonly REGISTRY="${KUI_REGISTRY_URL:-}"
readonly TIMEOUT_SECONDS="${KUI_SEED_TIMEOUT_SECONDS:-120}"
readonly USERNAME="${KUI_SEED_USERNAME:-}"
readonly PASSWORD="${KUI_SEED_PASSWORD:-}"
readonly AVRO_TOPIC="${KUI_AVRO_TOPIC:-orders.avro}"
readonly JSON_SCHEMA_TOPIC="${KUI_JSON_SCHEMA_TOPIC:-orders.jsonschema}"
readonly PROTOBUF_TOPIC="${KUI_PROTOBUF_TOPIC:-orders.protobuf}"
readonly COOKIES="$(mktemp)"

trap 'rm -f "${COOKIES}"' EXIT

log() { printf '[schema-seed] %s\n' "$*"; }
die() { printf '[schema-seed] ERROR: %s\n' "$*" >&2; exit 1; }

[[ -n "${BASE_URL}" ]] || die "KUI_BASE_URL is not set."
[[ -n "${CLUSTER}" ]] || die "KUI_CLUSTER_ID is not set."
[[ -n "${REGISTRY}" ]] || die "KUI_REGISTRY_URL is not set."
[[ "${CLUSTER}" =~ ^[A-Za-z0-9._-]+$ ]] || die "KUI_CLUSTER_ID contains characters unsafe in a URL path."
for topic in "${AVRO_TOPIC}" "${JSON_SCHEMA_TOPIC}" "${PROTOBUF_TOPIC}"; do
  [[ "${topic}" =~ ^[A-Za-z0-9._-]+$ ]] || die "topic '${topic}' contains characters unsafe in a URL path."
done

json_escape() {
  sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# The registry expects the schema document as a JSON string. These files are checked in and contain
# no control characters; escaping backslashes and quotes and removing their formatting newlines is
# sufficient without adding jq to the already-pulled registry image.
REGISTERED_SCHEMA_ID=""
register_schema() {
  local topic="$1" schema_type="$2" schema_file="$3" escaped response schema_id normalized_id
  [[ -r "${schema_file}" ]] || die "cannot read ${schema_file}; is the seed directory mounted?"

  escaped=$(sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' "${schema_file}" | tr -d '\n')
  response=$(curl -sS -X POST \
    -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
    --data "{\"schemaType\":\"${schema_type}\",\"schema\":\"${escaped}\"}" \
    "${REGISTRY}/subjects/${topic}-value/versions") \
    || die "the registry request for ${topic}-value failed"

  schema_id=$(printf '%s' "${response}" \
    | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')
  [[ "${schema_id}" =~ ^[0-9]+$ ]] \
    || die "the registry did not return a numeric schema id for ${topic}-value: ${response}"
  normalized_id=$(printf '%s' "${schema_id}" | sed 's/^0*//')
  [[ -n "${normalized_id}" ]] \
    || die "the registry returned schema id ${schema_id}; the wire format requires 1..2147483647"
  if (( ${#normalized_id} > 10 )) \
    || { (( ${#normalized_id} == 10 )) && [[ "${normalized_id}" > "2147483647" ]]; }; then
    die "the registry returned schema id ${schema_id}; the wire format requires 1..2147483647"
  fi

  REGISTERED_SCHEMA_ID="${normalized_id}"
  log "registered ${topic}-value as ${schema_type}, schema id ${normalized_id}"
}

register_schema "${AVRO_TOPIC}" AVRO "${DATA_DIR}/orders.avro.avsc"
readonly AVRO_SCHEMA_ID="${REGISTERED_SCHEMA_ID}"
register_schema "${JSON_SCHEMA_TOPIC}" JSON "${DATA_DIR}/orders.jsonschema.schema.json"
readonly JSON_SCHEMA_ID="${REGISTERED_SCHEMA_ID}"
register_schema "${PROTOBUF_TOPIC}" PROTOBUF "${DATA_DIR}/orders.protobuf.proto"
readonly PROTOBUF_SCHEMA_ID="${REGISTERED_SCHEMA_ID}"

# KUI protects writes with a CSRF token tied to a session cookie. The auth overlay additionally
# signs that session in and then fetches the replacement token created during session rotation.
log "asking ${BASE_URL} for a session"
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
csrf=""
while :; do
  if identity=$(curl -sS -c "${COOKIES}" -b "${COOKIES}" "${BASE_URL}/api/v1/auth/me" 2>/dev/null); then
    csrf=$(printf '%s' "${identity}" | sed -n 's/.*"csrfToken":"\([^"]*\)".*/\1/p')
    [[ -n "${csrf}" ]] && break
  fi
  [[ $(date +%s) -lt ${deadline} ]] \
    || die "KUI did not answer at ${BASE_URL} within ${TIMEOUT_SECONDS}s."
  sleep 2
done

if [[ -n "${USERNAME}" ]]; then
  log "signing in as ${USERNAME}"
  encoded_username=$(printf '%s' "${USERNAME}" | json_escape)
  encoded_password=$(printf '%s' "${PASSWORD}" | json_escape)
  status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    -b "${COOKIES}" -c "${COOKIES}" \
    -H 'Content-Type: application/json' \
    -H "X-Csrf-Token: ${csrf}" \
    --data "{\"username\":\"${encoded_username}\",\"password\":\"${encoded_password}\"}" \
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

# Read one record through KUI rather than assuming that a completed one-shot container will never
# be recreated. A successful stream with no `message` event means the topic is empty. An error
# event is not treated as empty, because appending after a failed check would break idempotency.
topic_has_records() {
  local topic="$1" browse
  browse=$(curl -fsS --max-time 30 \
    -b "${COOKIES}" -c "${COOKIES}" \
    "${BASE_URL}/api/v1/clusters/${CLUSTER}/topics/${topic}/messages/stream?seekTo=beginning&limit=1&valueSerde=Hex") \
    || die "KUI could not check whether ${topic} already contains records"
  case "${browse}" in
    *'event: error'*) die "KUI returned an error while checking whether ${topic} contains records" ;;
    *'event: message'*) return 0 ;;
    *) return 1 ;;
  esac
}

produce_record() {
  local topic="$1" key="$2" value="$3" serde="$4" encoded_key encoded_value status
  encoded_key=$(printf '%s' "${key}" | json_escape)
  encoded_value=$(printf '%s' "${value}" | json_escape)
  status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    -b "${COOKIES}" -c "${COOKIES}" \
    -H 'Content-Type: application/json' \
    -H "X-Csrf-Token: ${csrf}" \
    --data "{\"key\":\"${encoded_key}\",\"value\":\"${encoded_value}\",\"valueSerde\":\"${serde}\",\"keySerde\":\"String\"}" \
    "${BASE_URL}/api/v1/clusters/${CLUSTER}/topics/${topic}/messages")
  case "${status}" in
    2*) : ;;
    *) die "KUI refused a record for ${topic} (HTTP ${status}); check the registry and serde configuration" ;;
  esac
}

seed_text_records() {
  local topic="$1" data_file="$2" serde="$3" key_prefix="$4" line line_number=0 produced=0
  [[ -r "${data_file}" ]] || die "cannot read ${data_file}."
  if topic_has_records "${topic}"; then
    log "${topic} already contains records; leaving it unchanged"
    return
  fi

  while IFS= read -r line; do
    line_number=$(( line_number + 1 ))
    [[ -z "${line}" || "${line}" == \#* ]] && continue
    produce_record "${topic}" "${key_prefix}$(printf '%04d' "${line_number}")" "${line}" "${serde}"
    produced=$(( produced + 1 ))
  done < "${data_file}"
  (( produced > 0 )) || die "${data_file} contains no records"
  log "produced ${produced} records into ${topic}"
}

seed_text_records "${AVRO_TOPIC}" "${DATA_DIR}/orders.avro.jsonl" SchemaRegistry "o-"
seed_text_records "${JSON_SCHEMA_TOPIC}" "${DATA_DIR}/orders.jsonschema.jsonl" SchemaRegistry "js-"

# `00` after the schema id is Confluent's compact representation of message-index path [0], the
# first top-level message in orders.protobuf.proto. The body itself is protoc output checked into
# orders.protobuf.hex. Schema ids are validated above before printf is allowed to format one.
printf -v protobuf_schema_hex '%08x' "$((10#${PROTOBUF_SCHEMA_ID}))"
if topic_has_records "${PROTOBUF_TOPIC}"; then
  log "${PROTOBUF_TOPIC} already contains records; leaving it unchanged"
else
  protobuf_line=0
  protobuf_record=0
  protobuf_produced=0
  while IFS= read -r body_hex; do
    protobuf_line=$(( protobuf_line + 1 ))
    [[ -z "${body_hex}" || "${body_hex}" == \#* ]] && continue
    protobuf_record=$(( protobuf_record + 1 ))
    [[ "${body_hex}" =~ ^([0-9a-fA-F][0-9a-fA-F])+$ ]] \
      || die "orders.protobuf.hex line ${protobuf_line} is not complete hexadecimal bytes"
    produce_record \
      "${PROTOBUF_TOPIC}" \
      "pb-$(printf '%04d' "${protobuf_record}")" \
      "00${protobuf_schema_hex}00${body_hex}" \
      Hex
    protobuf_produced=$(( protobuf_produced + 1 ))
  done < "${DATA_DIR}/orders.protobuf.hex"
  (( protobuf_produced > 0 )) || die "orders.protobuf.hex contains no records"
  log "produced ${protobuf_produced} records into ${PROTOBUF_TOPIC}"
fi

log "schema fixtures are ready: Avro=${AVRO_SCHEMA_ID}, JSON=${JSON_SCHEMA_ID}, Protobuf=${PROTOBUF_SCHEMA_ID}"

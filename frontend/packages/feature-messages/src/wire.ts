/**
 * The one place a record on the wire becomes a record on the screen.
 *
 * The kernel's `KafkaRecord` is what `RecordRow` draws; `MessageDto` is what the message service
 * sends. They are deliberately different types — the DTO carries sizes, serde names and a
 * per-serde property bag that the row has no use for, and the row carries a five-way value union
 * the DTO expresses as `kind` plus a separate `deserializeErrors` list. Mapping them here means the
 * row never has to know the wire's shape, and the wire never has to be shaped for the row.
 */

import type { KafkaRecord, RecordHeader, RecordValue } from "@kui/kernel";
import type { components } from "@kui/api";

export type MessageDto = components["schemas"]["MessageDto"];
type PayloadDto = components["schemas"]["DecodedPayloadDto"];
type DecodeErrorDto = components["schemas"]["DecodeErrorDto"];

/**
 * The `kind` values KUI's own serdes produce.
 *
 * A client may meet others — the field is a *hint* for rendering and the server says so explicitly,
 * so that adding a kind is not a decode failure for every deployed browser. Anything unrecognised
 * is drawn as plain text, which is always a safe reading of bytes somebody has already decoded.
 */
const PAYLOAD_KIND = {
  json: "json",
  text: "string",
  binary: "binary",
  absent: "null",
} as const;

/**
 * Above this many bytes the value is not previewed inline.
 *
 * The service sends the whole payload either way — this is the browser deciding what it is willing
 * to lay out, not what it is willing to receive. 256 kB of JSON in a `<pre>` is several seconds of
 * layout per row, on a list that can hold five hundred of them, and the operator scrolling past it
 * did not ask for any of it. `valueSize` is the *serialised* size, which is the honest number to
 * threshold on: it is what the record weighs in the log.
 */
export const LARGE_VALUE_BYTES = 256 * 1024;

/** One record, as the list draws it. */
export function toRecord(dto: MessageDto): KafkaRecord {
  return {
    /* The offset arrives as a JSON number because that is what the OpenAPI document says (`int64`,
     * which has no JavaScript representation), and it becomes a string here and stays one
     * everywhere after.
     *
     * This is a **lossy seam and the loss happens before this function is called**: by the time
     * `JSON.parse` has produced a double, an offset past 2^53 is already wrong and nothing here can
     * recover it. Converting immediately at least stops it being *re-rounded* and stops anything
     * downstream doing arithmetic on it. A busy topic reaches 2^53 in years rather than weeks, so
     * this is a real but distant defect; the fix is for the service to send offsets as strings, and
     * it is written up as a finding rather than worked around here with a second JSON parser. */
    offset: String(dto.offset),
    partition: dto.partition,
    key: keyOf(dto.key),
    timestamp: dto.timestamp,
    ...timestampTypeOf(dto.timestampType),
    headers: headersOf(dto.headers),
    value: valueOf(dto),
    ...schemaOf(dto.value),
  };
}

/**
 * A record's key, or `null` for a record that has none.
 *
 * `null` and the empty string are different facts and the row draws them differently: a null key in
 * a compacted topic *is* the deletion. The service is careful to distinguish them — an absent key
 * is `kind: "null"` with an empty `text`, never a missing field — so this reads the kind and not
 * the emptiness of the text.
 */
function keyOf(key: PayloadDto): string | null {
  return key.kind === PAYLOAD_KIND.absent ? null : key.text;
}

/**
 * The five situations a value can be in, from the two fields that describe it.
 *
 * The order matters. A failed decode is checked **first**, before the kind: the service delivers an
 * undecodable record anyway, through the fallback serde, so `kind` will read `string` and `text`
 * will hold something plausible-looking. Reading the kind first would draw a decode failure as an
 * ordinary text payload — the failure travels beside the record precisely so that it is not
 * silently absorbed, and absorbing it here is the one thing this function must not do.
 */
function valueOf(dto: MessageDto): RecordValue {
  const failure = valueFailure(dto.deserializeErrors);
  if (failure !== undefined) {
    return {
      kind: "undecodable",
      reason: failure.cause,
      // The bytes are the only thing left that is definitely true, so they are offered when the
      // fallback serde rendered them as hex. A binary fallback is exactly that rendering.
      ...(dto.value.kind === PAYLOAD_KIND.binary ? { hex: dto.value.text } : {}),
    };
  }

  if (dto.value.kind === PAYLOAD_KIND.absent) return { kind: "tombstone" };
  if (dto.valueSize > LARGE_VALUE_BYTES) return { kind: "large", bytes: dto.valueSize };
  if (dto.value.kind === PAYLOAD_KIND.json) return { kind: "json", text: dto.value.text };
  // Binary and every kind this build does not know: plain text. See PAYLOAD_KIND.
  return { kind: "text", text: dto.value.text };
}

/**
 * The failure that concerns the *value*, if there is one.
 *
 * A record can fail on its key and decode perfectly on its value, and the two are separate columns
 * on the screen. Filtering by target rather than taking the first error is what keeps a key failure
 * from painting the value red.
 */
function valueFailure(errors: readonly DecodeErrorDto[] | undefined): DecodeErrorDto | undefined {
  return errors?.find((error) => error.target.toLowerCase() === "value");
}

/**
 * The headers, as the row's list.
 *
 * The wire carries a map, so the order is whatever the JSON had; the row draws them in that order
 * rather than sorting, because a producer that writes `trace-id` before `content-type` is saying
 * something about its own conventions and re-ordering them makes two records that agree look
 * different.
 *
 * A value the service could not read as UTF-8 arrives as a `0x…` rendering, and that is marked
 * rather than silently drawn as text: a chip reading `0x48656c6c6f` with no marker is one an
 * operator reasonably reads as the literal characters.
 */
function headersOf(headers: Readonly<Record<string, string>> | undefined): readonly RecordHeader[] {
  if (headers === undefined) return [];
  return Object.entries(headers).map(([name, value]) => ({
    name,
    // The empty string is "present with an empty value", which the chip draws as `(empty)`. It is
    // not the same as absent, and an absent header is simply not in the map.
    value,
    ...(/^0x[0-9a-fA-F]*$/.test(value) ? { binary: true } : {}),
  }));
}

/**
 * Which clock the timestamp came from, when it is one of the two Kafka has.
 *
 * A value that is neither is dropped rather than shown: the box exists to tell an operator
 * debugging ordering which clock they are reading, and a third word in it answers no question.
 */
function timestampTypeOf(
  raw: string,
): { readonly timestampType: "CreateTime" | "LogAppendTime" } | Record<string, never> {
  return raw === "CreateTime" || raw === "LogAppendTime" ? { timestampType: raw } : {};
}

/**
 * The schema a registry-backed serde attached, when it attached one.
 *
 * It lives in the payload's free-form property bag, because the set of things a serde can report
 * differs per serde and hoisting them all onto the record would give every payload a dozen
 * permanently-null fields. Both parts are required here: a subject with no version names a moving
 * target, and a version with no subject names nothing at all.
 */
function schemaOf(
  value: PayloadDto,
): { readonly schema: { readonly subject: string; readonly version: number } } | Record<string, never> {
  const properties: Readonly<Record<string, string>> = value.properties;
  const subject = properties["schemaSubject"] ?? properties["subject"];
  const version = Number(properties["schemaVersion"] ?? properties["version"] ?? "");
  if (subject === undefined || subject === "" || !Number.isSafeInteger(version)) return {};
  return { schema: { subject, version } };
}

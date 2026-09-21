import { prettyValue, type KafkaRecord, type RecordHeader, type RecordValue } from "./record.js";

export function recordHeadersData(record: KafkaRecord): readonly RecordHeader[] {
  return record.headers.map((header) => ({ ...header }));
}

export function recordValueText(value: RecordValue): string {
  if (value.kind === "json") return value.text;
  if (value.kind === "large" && value.text !== undefined) return value.text;
  return prettyValue(value);
}

export function recordKeyText(record: KafkaRecord): string {
  return record.key ?? "null";
}

export function recordHeadersText(record: KafkaRecord): string {
  return JSON.stringify(recordHeadersData(record), null, 2);
}

export function recordMessageText(record: KafkaRecord): string {
  const headers = indentContinuation(JSON.stringify(recordHeadersData(record), null, 2), 2);
  return [
    "{",
    `  "headers": ${headers},`,
    `  "key": ${JSON.stringify(record.key)},`,
    `  "value": ${recordValueLiteral(record.value)}`,
    "}",
  ].join("\n");
}

function recordValueLiteral(value: RecordValue): string {
  if (value.kind === "json") return validJsonOrString(value.text);
  if (value.kind === "text") return JSON.stringify(value.text);
  if (value.kind === "tombstone") return "null";
  if (value.kind === "large" && value.text !== undefined) {
    return value.sourceKind === "json" ? validJsonOrString(value.text) : JSON.stringify(value.text);
  }
  return JSON.stringify(
    value.kind === "large"
      ? { unavailable: true, bytes: value.bytes }
      : {
          undecodable: true,
          reason: value.reason,
          ...(value.hex === undefined ? {} : { hex: value.hex }),
        },
  );
}

/** Validate only; never stringify the parsed value, because JSON numbers may exceed 2^53. */
function validJsonOrString(text: string): string {
  try {
    JSON.parse(text);
    return text;
  } catch {
    return JSON.stringify(text);
  }
}

function indentContinuation(text: string, spaces: number): string {
  const indentation = " ".repeat(spaces);
  return text.replaceAll("\n", `\n${indentation}`);
}

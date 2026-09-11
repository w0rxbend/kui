/**
 * What a Kafka record looks like to the list components, and the small amount of formatting they
 * need. No DOM, no reactivity — so every rule below is testable as a function.
 */

/** One record header. */
export interface RecordHeader {
  readonly name: string;
  /** `null` means present with an empty value. An absent header is simply not in the list. */
  readonly value: string | null;
  /** The value was not valid UTF-8; `value` holds its `0x…` rendering. */
  readonly binary?: boolean;
}

/**
 * A record's payload, as five situations rather than one string.
 *
 * The list has to draw all five and they are not variations on a theme — each says something
 * different about the cluster, and three of them have historically been drawn as an empty row.
 */
export type RecordValue =
  /** Parsed as JSON. `text` is the raw payload; the expansion pretty-prints it. */
  | { readonly kind: "json"; readonly text: string }
  /** Not JSON, and that is fine — plain text, CSV, a protobuf rendered as text. Shown as-is. */
  | { readonly kind: "text"; readonly text: string }
  /**
   * A null value: a tombstone. This is a Kafka concept the operator needs to see — it is how a
   * compacted topic records a deletion — and it is not an error.
   */
  | { readonly kind: "tombstone" }
  /** Too large to preview inline. The size is shown; the payload is fetched on demand. */
  | { readonly kind: "large"; readonly bytes: number }
  /**
   * The deserializer failed. The reason is shown in full, because "Avro schema 42 not found" is
   * the whole diagnosis, and the expansion offers the raw bytes as hex.
   */
  | { readonly kind: "undecodable"; readonly reason: string; readonly hex?: string };

export interface KafkaRecord {
  /**
   * The offset, as a **string**.
   *
   * Kafka offsets are signed 64-bit. A JavaScript number is a double and holds integers exactly
   * only up to 2^53 — about 9.007e15. A busy topic reaches that, and past it two different offsets
   * compare equal and the last digits of the one on screen are wrong. Nothing in the UI does
   * arithmetic on an offset, so carrying it as the string the API sent costs nothing and cannot be
   * silently wrong.
   */
  readonly offset: string;
  readonly partition: number;
  /** `null` is a null key — a tombstone's key, and a thing the operator needs to see spelled out. */
  readonly key: string | null;
  /** ISO 8601, as the API sends it. */
  readonly timestamp: string;
  /**
   * Which clock the timestamp came from.
   *
   * An operator debugging ordering has to know whether they are reading the producer's clock
   * (`CreateTime`) or the broker's (`LogAppendTime`), because those two disagree by however far
   * the producer's machine has drifted — and a topic configured for `LogAppendTime` will show
   * records in timestamp order that were not produced in that order.
   */
  readonly timestampType?: "CreateTime" | "LogAppendTime";
  readonly headers: readonly RecordHeader[];
  readonly value: RecordValue;
  /** Present when a schema registry resolved the payload. Adds a fifth box to the expansion. */
  readonly schema?: { readonly subject: string; readonly version: number };
}

/**
 * A stable identity for a record.
 *
 * Partition and offset, not offset alone: offsets restart at zero in every partition, so a list
 * merged across twelve partitions has twelve records numbered 0. Keying on the offset alone made
 * eleven of them share one element, and eleven rows drew the twelfth's contents.
 */
export function recordKey(record: KafkaRecord): string {
  return `${record.partition}:${record.offset}`;
}

/**
 * The offset with thousands separators, so a column of them can be compared at a glance.
 *
 * Grouped by hand rather than with `Intl.NumberFormat`, which takes a number and would undo the
 * whole reason the offset is a string.
 */
export function formatOffset(offset: string): string {
  const negative = offset.startsWith("-");
  const digits = negative ? offset.slice(1) : offset;
  if (!/^\d+$/.test(digits)) return offset;
  const grouped = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return negative ? `-${grouped}` : grouped;
}

/**
 * "2s ago", "4m ago", "3d ago".
 *
 * ## Why the clock is a parameter
 *
 * `now` is passed in rather than read from `Date.now()`. A function that reads the clock cannot be
 * tested without either freezing time globally or accepting a test that fails at midnight, and
 * this one has six branches.
 *
 * ## Why a future timestamp is not an error
 *
 * A producer whose clock is ahead of the broker's writes a record stamped in the future, and that
 * is common enough that treating it as a fault would cry wolf. It reads "in 3s", which is both
 * true and a visible hint that a clock is wrong somewhere.
 */
export function relativeTime(timestamp: string, now: number): string {
  const then = Date.parse(timestamp);
  // An unparseable timestamp is a bug on the wire, not something to render as "NaN ago".
  if (Number.isNaN(then)) return "unknown time";

  const seconds = Math.round((now - then) / 1000);
  const ahead = seconds < 0;
  const magnitude = Math.abs(seconds);

  const [amount, unit] =
    magnitude < 60
      ? [magnitude, "s"]
      : magnitude < 3600
        ? [Math.floor(magnitude / 60), "m"]
        : magnitude < 86400
          ? [Math.floor(magnitude / 3600), "h"]
          : [Math.floor(magnitude / 86400), "d"];

  return ahead ? `in ${amount}${unit}` : `${amount}${unit} ago`;
}

/**
 * Bytes at one decimal place, for "4.2 MB — open to view".
 *
 * ## Why the byte scale has three cases rather than one
 *
 * This was written for record sizes, which are whole numbers of bytes, and `147.0 B` for a
 * 147-byte record is a false claim of precision — so the byte scale printed its value unrounded.
 * Then rates started arriving through the same function, and a rate is a division: the cluster
 * dashboard's CONSUME card read `81.2359955010432 B/s` beside a PRODUCTION card reading `1.2 kB/s`,
 * and three rows of the throughput card's data table carried seventeen significant figures each.
 * Rounding at the call sites would have been two edits and fourteen places for the third one to be
 * forgotten, so the decision is here, where the product decides how a byte count is spelled:
 *
 * - a **whole** number of bytes prints as itself — `147 B`, never `147.0 B`;
 * - a **fraction** of a byte prints to one decimal, exactly as every larger unit does;
 * - a fraction **below `0.05`** prints `<0.1`, because `0.0 B/s` over a cluster that is moving
 *   something is the zero this dashboard is not allowed to show. A measured `0` is still `0 B`:
 *   that one is a fact about a quiet cluster and is the answer the card should give.
 *
 * ## Why the promotion threshold is not 1000
 *
 * `999.96` bytes is below the threshold and rounds to `1000.0` at one decimal, which prints a
 * four-digit figure under a unit that has three. The loop promotes at the value that *rounds* to
 * the next unit instead, so the printed figure is always in `[0, 1000)`.
 */
export function formatBytes(bytes: number): string {
  const units = ["B", "kB", "MB", "GB", "TB"] as const;
  let value = Math.max(0, bytes);
  let unit = 0;
  while (value >= 999.95 && unit < units.length - 1) {
    value /= 1000;
    unit += 1;
  }
  return `${formatMagnitude(value, unit)} ${units[unit] ?? "B"}`;
}

/** The figure `formatBytes` prints, without its unit. Its header has the three cases. */
function formatMagnitude(value: number, unit: number): string {
  // Only the byte scale can hold a whole number small enough to be worth printing whole: every
  // larger unit is reached by dividing, so `1.0 kB` is right and `1 kB` would hide 1,049 bytes.
  if (unit === 0 && Number.isInteger(value)) return `${value}`;
  if (value > 0 && value < 0.05) return "<0.1";
  return value.toFixed(1);
}

/**
 * The one-line preview of a record's value, for the collapsed row.
 *
 * Whitespace is collapsed so that a pretty-printed payload does not preview as a single `{`. The
 * row itself ellipsises with CSS rather than here, because CSS truncates at the width the row
 * actually has and a character count truncates at a guess about it.
 */
export function previewValue(value: RecordValue): string {
  switch (value.kind) {
    case "json":
    case "text":
      return value.text.replace(/\s+/g, " ").trim();
    case "tombstone":
      return "null";
    case "large":
      return `${formatBytes(value.bytes)} — open to view`;
    case "undecodable":
      return `could not deserialize (${value.reason})`;
  }
}

/**
 * The pretty-printed payload for the expansion.
 *
 * A payload that claimed to be JSON and then does not parse is shown exactly as it arrived rather
 * than replaced with an error. The bytes are what the operator is trying to look at, and a
 * component that hides them because it could not format them has removed the only evidence.
 */
export function prettyValue(value: RecordValue): string {
  if (value.kind !== "json") {
    return value.kind === "text"
      ? value.text
      : value.kind === "tombstone"
        ? "null"
        : value.kind === "large"
          ? `${formatBytes(value.bytes)} — not loaded`
          : (value.hex ?? value.reason);
  }
  try {
    return JSON.stringify(JSON.parse(value.text) as unknown, null, 2);
  } catch {
    return value.text;
  }
}

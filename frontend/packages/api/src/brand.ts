/**
 * Identifier types that a plain string cannot be mistaken for.
 *
 * ## What this is standing in for, and how far short it falls
 *
 * In Scala, `TopicName` is a type with a smart constructor: an invalid name cannot be built, so a
 * function taking a `TopicName` cannot be handed rubbish, and `topicName` and `clusterId` cannot be
 * swapped at a call site. TypeScript has no such thing — every identifier in the generated schema is
 * `string`, and `getTopic(topicName, clusterId)` compiles perfectly.
 *
 * Branding is the available approximation. `ClusterId` is `string & { __brand: "ClusterId" }`: it is
 * a `string` everywhere at runtime, but a plain string is not assignable to it, so the two arguments
 * above cannot be swapped and a raw literal cannot be passed by accident. **It is weaker than the
 * Scala it replaces, and ADR-048 says so rather than pretending otherwise**: the brand can be forged
 * with a cast, which is why casting to a branded type outside this file is a lint error, and why the
 * constructors below are the only supported way in.
 *
 * ## Where validation belongs
 *
 * At the browser's edge, in the constructor, and nowhere else. A value that reached a component
 * has been through one of these; a value that came off the wire is trusted, because the server
 * validated it and re-validating it here would only produce a browser that refuses to display data
 * the server considers valid.
 */

declare const brand: unique symbol;

/** A `string` that has been through a named constructor. */
export type Branded<Name extends string> = string & { readonly [brand]: Name };

/** The id of a configured cluster, as it appears in a URL and in every path parameter. */
export type ClusterId = Branded<"ClusterId">;
/** A Kafka topic name. */
export type TopicName = Branded<"TopicName">;
/** A consumer group id. */
export type GroupId = Branded<"GroupId">;
/** A schema-registry subject. */
export type SubjectName = Branded<"SubjectName">;
/** A connector name within a Kafka Connect cluster. */
export type ConnectorName = Branded<"ConnectorName">;

/** Why a value was refused, in a sentence a screen can show. */
export interface InvalidIdentifier {
  readonly kind: "invalid-identifier";
  /** Which identifier was being built — `"TopicName"`, and so on. */
  readonly type: string;
  /** What was wrong with it, phrased for a person. */
  readonly reason: string;
}

/** A value, or the reason it is not one. Deliberately the same shape of answer as an API call. */
export type Validated<T> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: InvalidIdentifier };

/**
 * Kafka's own rule for a topic name, and the same one for a group id: at least one character, at
 * most 249, and only letters, digits, dot, underscore and hyphen. `.` and `..` are refused because
 * Kafka stores a topic's data in a directory of that name.
 *
 * It is stated here rather than derived from the OpenAPI document because the document describes
 * these fields as plain strings — the constraint lives in Kafka, not in KUI's contract.
 */
const KafkaNamePattern = /^[a-zA-Z0-9._-]+$/;
const KafkaNameMaxLength = 249;

function validateKafkaName(type: string, raw: string): Validated<never> | undefined {
  if (raw.length === 0) return invalid(type, "it is empty");
  if (raw.length > KafkaNameMaxLength) {
    return invalid(type, `it is longer than ${KafkaNameMaxLength} characters`);
  }
  if (raw === "." || raw === "..") return invalid(type, "'.' and '..' are not usable names in Kafka");
  if (!KafkaNamePattern.test(raw)) {
    return invalid(type, "it may contain only letters, digits, '.', '_' and '-'");
  }
  return undefined;
}

function invalid(type: string, reason: string): Validated<never> {
  return { ok: false, error: { kind: "invalid-identifier", type, reason } };
}

function valid<T>(value: string): Validated<T> {
  // The one cast in the file, and the reason the lint rule forbids the cast anywhere else: this is
  // the point at which a checked string becomes a branded one.
  return { ok: true, value: value as T };
}

/** A cluster id, or why the text is not one. */
export function clusterId(raw: string): Validated<ClusterId> {
  if (raw.length === 0) return invalid("ClusterId", "it is empty");
  if (raw.includes("/")) return invalid("ClusterId", "it may not contain '/'");
  return valid<ClusterId>(raw);
}

/** A topic name, or why the text is not one. */
export function topicName(raw: string): Validated<TopicName> {
  return validateKafkaName("TopicName", raw) ?? valid<TopicName>(raw);
}

/** A consumer group id, or why the text is not one. */
export function groupId(raw: string): Validated<GroupId> {
  return validateKafkaName("GroupId", raw) ?? valid<GroupId>(raw);
}

/** A schema-registry subject, or why the text is not one. */
export function subjectName(raw: string): Validated<SubjectName> {
  if (raw.length === 0) return invalid("SubjectName", "it is empty");
  return valid<SubjectName>(raw);
}

/** A connector name, or why the text is not one. */
export function connectorName(raw: string): Validated<ConnectorName> {
  if (raw.length === 0) return invalid("ConnectorName", "it is empty");
  return valid<ConnectorName>(raw);
}

/**
 * Accepts a value that came off the wire without re-checking it.
 *
 * The server validated it, and a browser that refused to display a topic the server is perfectly
 * happy with would be a worse bug than the one branding is protecting against. Named so that it is
 * obvious in a diff which values were checked and which were trusted.
 */
export function trusted<T extends Branded<string>>(raw: string): T {
  return raw as T;
}

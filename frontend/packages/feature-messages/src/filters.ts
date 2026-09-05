/**
 * Smart filters: compiling one, and trying it against a single record first.
 *
 * ## Why registering and testing are one file and one workflow
 *
 * They are two endpoints and they are useless apart. Registering answers *does this expression
 * compile*, which is a question about syntax; testing answers *does this expression do what I mean*,
 * which is a question about the data. An operator who only had the first would write a filter that
 * compiles, start a browse over a million records, and get nothing back — and "no record matched"
 * and "my predicate is wrong" look exactly alike from the other side of that browse. The preview
 * exists so that the second question is answered against one record the operator can see, before
 * the browse is started rather than after it has finished telling them nothing.
 *
 * ## The three verdicts, and why collapsing them is the defect
 *
 * The service is explicit that a test has three outcomes, not two. An expression that *throws* on a
 * record — a field that is not there, a type that does not match — is neither a match nor a
 * non-match. It answers `matched: false` with a reason in `error`, and reading only `matched` turns
 * a filter that is broken on every record into a filter that matches nothing. Those two are the same
 * empty list on screen and completely different problems, so {@link FilterVerdict} keeps them apart
 * and the caller cannot accidentally merge them.
 *
 * Confirmed against a running gateway, `POST …/messages/filters/test`:
 *
 * ```
 * {"matched":true,"error":null}
 * {"matched":false,"error":null}
 * {"matched":false,"error":"evaluation error at <input>:12: key 'nosuch' is not present in map."}
 * {"matched":false,"error":"the filter returned Long rather than true or false"}
 * ```
 *
 * Note `error: null` rather than the field being absent. The generated type says `error?: string`,
 * so a `=== undefined` check reads a real failure as "no failure" — which is precisely the collapse
 * this file exists to prevent. {@link verdictOf} tests for a non-empty string instead.
 *
 * ## The last of those four is the argument for the preview, on its own
 *
 * `record.offset` **registers successfully**. It is a legal CEL expression and the compiler has no
 * opinion about its type, so the id comes back and nothing warns anybody. It is only when it is run
 * against a record that it says "the filter returned Long rather than true or false". Without a
 * preview that discovery happens partway through a browse over a production topic.
 *
 * ## Why the browse carries the source as well as the id
 *
 * The id is `sha256(source)` truncated, so it is not a lookup key that has to exist anywhere — any
 * replica can re-derive it. The browse sends `filterId` *and* `filterSource` together, and that pair
 * is deliberate: a replica that has never seen this id compiles the source instead of refusing a
 * filter the operator registered on a sibling replica a second earlier. So a registration that
 * failed to reach the browse's replica is not an error, and the id alone would make it one.
 * {@link RegisteredFilter} therefore keeps both and callers must pass both on.
 */

import type { ApiResult, KuiApiClient } from "@kui/api";
import type { MessageDto } from "./wire.js";

/**
 * A filter that compiled, as a browse must quote it.
 *
 * Both fields, always. See the header: the source travels beside the id because the id is derivable
 * rather than stored, and a browse carrying only the id can be refused by a replica that never saw
 * the registration.
 */
export interface RegisteredFilter {
  readonly id: string;
  readonly source: string;
  /** What the operator called it, when they named it. Never sent to the browse; it is for the UI. */
  readonly name?: string | undefined;
}

/**
 * What the filter did with one record.
 *
 * Three cases and not a boolean, for the reason in the header: `failed` is an expression that is
 * legal and threw, which is a different sentence from "did not match" and has to read as one.
 */
export type FilterVerdict =
  | { readonly kind: "matched" }
  | { readonly kind: "no-match" }
  | { readonly kind: "failed"; readonly reason: string };

/**
 * The fields a filter expression can name, as the help beside the editor lists them.
 *
 * Kept in the order the service's own documentation uses. This list is Kafbat's vocabulary
 * unchanged, deliberately: an operator migrating has filters written down in runbooks, and renaming
 * `keyAsText` would turn every one of them into a compile error for no gain.
 *
 * `key` and `value` are **absent** rather than null when the payload is not JSON, which is why the
 * two text forms are listed beside them. A filter reading `record.value.status` against a topic of
 * plain text gets a runtime failure — counted, shown, and recoverable — where a null would have
 * silently matched nothing and taught the operator their data was wrong rather than their filter.
 */
export const FILTER_VARIABLES: readonly {
  readonly name: string;
  readonly type: string;
  readonly describe: string;
}[] = [
  { name: "record.partition", type: "int", describe: "the partition the record was read from" },
  { name: "record.offset", type: "int", describe: "its offset within that partition" },
  { name: "record.timestampMs", type: "int", describe: "its timestamp, in milliseconds" },
  { name: "record.keyAsText", type: "string", describe: "the key, as the key serde decoded it" },
  { name: "record.valueAsText", type: "string", describe: "the value, as the value serde decoded it" },
  { name: "record.headers", type: "map(string, string)", describe: "header names to their values" },
  { name: "record.key", type: "dyn", describe: "the key parsed as JSON — absent when it is not JSON" },
  { name: "record.value", type: "dyn", describe: "the value parsed as JSON — absent when it is not JSON" },
];

/** Expressions worth starting from, and the shape of question each one answers. */
export const FILTER_EXAMPLES: readonly { readonly source: string; readonly describe: string }[] = [
  { source: 'record.value.status == "CAPTURED"', describe: "a field of the JSON value" },
  { source: 'record.keyAsText.startsWith("ord_")', describe: "a prefix of the key" },
  { source: 'record.headers["content-type"] == "application/json"', describe: "a header" },
  { source: "record.partition == 0 && record.offset > 1000", describe: "a position in the log" },
];

/**
 * The longest expression the service will compile, in bytes.
 *
 * The service's own limit, checked here so that an operator who pastes a program gets told by the
 * editor rather than by a round trip. Past this a CEL predicate is a program and not a filter.
 */
export const MAX_FILTER_SOURCE_BYTES = 8 * 1024;

/**
 * Why this expression cannot be sent yet, or `undefined`.
 *
 * Deliberately only the two things that are certain without a compiler. Everything about CEL's
 * grammar is the server's judgement, and guessing at it here would produce a second, worse compiler
 * whose disagreements with the real one an operator has no way to resolve.
 */
export function filterProblem(source: string): string | undefined {
  if (source.trim() === "") return "Write an expression to filter by.";
  const bytes = new TextEncoder().encode(source).length;
  if (bytes > MAX_FILTER_SOURCE_BYTES) {
    return `An expression may be at most ${String(MAX_FILTER_SOURCE_BYTES)} bytes; this one is ${String(bytes)}.`;
  }
  return undefined;
}

/**
 * Compile the expression and get back the handle a browse quotes it by.
 *
 * The same source always yields the same id, so calling this twice is free and there is no need for
 * the caller to remember whether it has registered this text before.
 */
export async function registerFilter(
  api: KuiApiClient,
  clusterId: string,
  source: string,
  name?: string | undefined,
): Promise<ApiResult<RegisteredFilter>> {
  const answer = await api.post("/api/v1/clusters/{clusterId}/messages/filters", {
    params: { path: { clusterId } },
    /* `name` omitted rather than sent as null when there is none — the encoding the contract asks
     * for, and the same rule `produce.ts` follows for every optional field it writes. */
    body: { source, ...(name === undefined || name === "" ? {} : { name }) },
  });
  if (!answer.ok) return answer;
  return {
    ok: true,
    value: { id: answer.value.id, source, ...(name === undefined || name === "" ? {} : { name }) },
  };
}

/**
 * Run the expression against one record the caller already has.
 *
 * No Kafka client is opened and no topic is read: the record travels in the request. That is what
 * makes this cheap enough to sit behind a button in an editor, and it is also why the record has to
 * be a full `MessageDto` — the service decodes the same document a browse would have delivered, so
 * that the preview's answer is the answer the browse will give.
 */
export async function testFilter(
  api: KuiApiClient,
  clusterId: string,
  source: string,
  record: MessageDto,
): Promise<ApiResult<FilterVerdict>> {
  const answer = await api.post("/api/v1/clusters/{clusterId}/messages/filters/test", {
    params: { path: { clusterId } },
    body: { source, record },
  });
  if (!answer.ok) return answer;
  return { ok: true, value: verdictOf(answer.value) };
}

/**
 * The wire's two fields as the three-way verdict.
 *
 * `error` is checked **first** and as a non-empty *string*, not against `undefined`. The server
 * sends `"error": null` explicitly, and the generated type declares the field optional, so
 * `error !== undefined` is true for every record and `error === undefined` is false for every
 * record — one of those readings turns every result into a failure and the other turns every
 * failure into a plain non-match. Neither is detectable by the type checker, which is why this is
 * one function with a recorded-document test rather than a condition at each call site.
 */
export function verdictOf(result: {
  readonly matched: boolean;
  /**
   * `null` is in this type deliberately, and the generated one does not have it.
   *
   * `schema.d.ts` says `error?: string`, because the OpenAPI document marks the field optional. The
   * server sends `"error": null`. Writing the truth here is what makes the recorded documents
   * type-check against this function instead of having to be cast at the test, and a cast would
   * have hidden the very mismatch the test is for.
   */
  readonly error?: string | null | undefined;
}): FilterVerdict {
  const reason = result.error;
  if (typeof reason === "string" && reason !== "") return { kind: "failed", reason };
  return result.matched ? { kind: "matched" } : { kind: "no-match" };
}

/**
 * The typed predicates the filter bar offers, and the one expression the server is actually asked.
 *
 * ## Why these compile to CEL rather than to query parameters
 *
 * `SCREENS-V4.md` §3.12 lists five controls on this bar with no server parameter behind them. They
 * are, in the design's own words, the time window, the offset *end* bound, the key/value match
 * modes, the status facet and the preset set — and this file holds **three** of them: the key and
 * value match modes (one item, not two), the end of the time window, and the end of the offset
 * range. The browse endpoint takes a *start* (`seekTo`) and one plain substring (`q`) that is
 * matched against the whole decoded record — so "the key starts with `ord_`" and "the value
 * contains `UAH`" are two different questions `q` cannot tell apart, and an upper bound is a
 * question it cannot ask at all.
 *
 * The other two are accounted for so nobody reads this file as the whole of row 2. The **preset
 * set** is `presets.ts`, a per-browser list with no server behind it. The **status facet** is not
 * built here or anywhere, and that is a decision rather than an omission: it filters on a `status`
 * field inside the payload, which is a property of one company's documents and of no Kafka record.
 * `SCREENS.md` §3.5 says of its badge "do not build it", `SCREENS-V4.md` §7.1 states the terms on
 * which it could be, and until those are met the bar has four controls in that row and not five.
 *
 * What the browse *does* take is a smart filter: an expression compiled by the message service and
 * evaluated per record, over the vocabulary in {@link FILTER_VARIABLES}. Every predicate here has an
 * exact expression in that vocabulary, so this module is a translation and not a second filter
 * engine — nothing below evaluates anything, and no record is ever excluded in the browser.
 *
 * ## One conjunct per bound field, in a fixed order
 *
 * {@link conjunctsOf} emits one term per field the operator filled in, each naming exactly the field
 * it is about. Two consequences, and both are the point:
 *
 *   - A predicate on the key and a predicate on the value reach the request **separately** — as two
 *     terms over two variables — rather than being merged into one substring that would match a key
 *     against text meant for the value.
 *   - The order is fixed, so the same predicates always produce the same source. The service's
 *     filter id is `sha256(source)`, so a stable spelling means a stable id, which means the second
 *     browse with the same predicates hits the compile cache instead of registering a new program.
 *
 * ## The upper bounds stop the read; they do not trim the list afterwards
 *
 * `record.offset <= n` and `record.timestampMs <= n` are evaluated by the service as it reads, and
 * the records they exclude are counted in the browse's `scanned` figure. That is what makes the
 * range honest: an operator who asks for offsets 100 to 200 is told how much of the log was examined
 * to answer, rather than being shown a shorter list with no account of what was skipped.
 */

/** How a text predicate is matched. The four the service's filter vocabulary expresses exactly. */
export type MatchMode = "contains" | "equals" | "starts" | "ends";

/** What the operator typed into one of the two text predicates. */
export interface FieldPredicate {
  readonly mode: MatchMode;
  readonly text: string;
}

/**
 * The quick time windows, as the design draws them (`5m | 15m | 1h | 24h`).
 *
 * They choose a *start*, not a span: a window whose end is "now" is a browse that begins fifteen
 * minutes ago and reads forward, which is also what makes `LIVE` and a window coexist — §3.12 is
 * explicit that a tail with a 15m window is a start-at-timestamp forward read that then follows,
 * and not an invalid request.
 */
export type TimeWindow = "5m" | "15m" | "1h" | "24h";

export const TIME_WINDOWS: readonly {
  readonly value: TimeWindow;
  readonly label: string;
  readonly millis: number;
}[] = [
  { value: "5m", label: "5m", millis: 5 * 60_000 },
  { value: "15m", label: "15m", millis: 15 * 60_000 },
  { value: "1h", label: "1h", millis: 60 * 60_000 },
  { value: "24h", label: "24h", millis: 24 * 60 * 60_000 },
];

export const MATCH_MODES: readonly { readonly value: MatchMode; readonly label: string }[] = [
  { value: "contains", label: "contains" },
  { value: "equals", label: "is" },
  { value: "starts", label: "starts with" },
  { value: "ends", label: "ends with" },
];

/**
 * Everything on the bar that the browse endpoint has no parameter for.
 *
 * Deliberately *not* part of `BrowseQuery`. That type is the server's own grammar, written in the
 * server's own parameter names, and putting a field in it that no endpoint accepts is how a browser
 * ends up sending a filter nothing applies — the exact failure `filterSource` without `filterId`
 * already has on this endpoint, where the server drops it in silence.
 */
export interface Predicates {
  readonly key?: FieldPredicate | undefined;
  readonly value?: FieldPredicate | undefined;
  /** The end of the offset range: no record beyond this offset. Digits, as offsets are elsewhere. */
  readonly untilOffset?: string | undefined;
  /** The end of the time window, in epoch milliseconds. */
  readonly untilTime?: number | undefined;
}

export const NO_PREDICATES: Predicates = {};

/** Whether anything here would narrow a browse. An empty predicate set compiles to no filter. */
export function isEmpty(predicates: Predicates): boolean {
  return conjunctsOf(predicates).length === 0;
}

/**
 * The instant a quick window starts at.
 *
 * `now` is a parameter rather than a call to `Date.now()` so that the arithmetic is a function a
 * test can pin. A window computed from an ambient clock is a window whose test asserts against
 * whatever second it happened to run in.
 */
export function windowStart(window: TimeWindow, now: number): number {
  const chosen = TIME_WINDOWS.find((candidate) => candidate.value === window);
  return now - (chosen?.millis ?? 0);
}

/**
 * Which quick window a start instant corresponds to, or `undefined` for one nobody's chip names.
 *
 * Matched within half a minute, because the chip sets the start from the clock at the moment it was
 * pressed and the page is read some seconds later — an exact comparison would leave the chip the
 * operator just pressed drawn as inactive, which reads as a control that does not work.
 */
export function windowOf(startMillis: number, now: number): TimeWindow | undefined {
  const elapsed = now - startMillis;
  return TIME_WINDOWS.find((candidate) => Math.abs(candidate.millis - elapsed) <= 30_000)?.value;
}

/**
 * The CEL terms these predicates become, one per field, in a fixed order.
 *
 * A field whose text is blank contributes nothing: an empty box is a box the operator has not
 * filled in, and compiling `record.keyAsText.contains("")` would be a term that matches every
 * record while looking, in the URL and in the chip, exactly like a filter that is doing something.
 */
export function conjunctsOf(predicates: Predicates): readonly string[] {
  const terms: string[] = [];
  const key = textTerm("record.keyAsText", predicates.key);
  if (key !== undefined) terms.push(key);
  const value = textTerm("record.valueAsText", predicates.value);
  if (value !== undefined) terms.push(value);
  if (predicates.untilOffset !== undefined && /^\d+$/.test(predicates.untilOffset)) {
    terms.push(`record.offset <= ${predicates.untilOffset}`);
  }
  if (predicates.untilTime !== undefined && Number.isSafeInteger(predicates.untilTime)) {
    terms.push(`record.timestampMs <= ${String(predicates.untilTime)}`);
  }
  return terms;
}

/**
 * The whole expression a browse should run: these predicates, and whatever the operator wrote.
 *
 * The hand-written expression is parenthesised and goes last. Parenthesised because a CEL source is
 * a paragraph somebody may have written with a top-level `||` in it, and `a && b || c` is not the
 * filter they meant; last because that is the order they were added to the screen, and a stable
 * spelling is what keeps the service's compile cache warm across browses.
 *
 * `undefined` when there is nothing to compile, which is what tells the screen to browse without a
 * filter rather than to register the empty string.
 */
export function celFor(
  predicates: Predicates,
  expression?: string | undefined,
): string | undefined {
  const written = expression?.trim() ?? "";
  const terms = [...conjunctsOf(predicates), ...(written === "" ? [] : [`(${written})`])];
  return terms.length === 0 ? undefined : terms.join(" && ");
}

// --- The URL ------------------------------------------------------------------------------------

/**
 * The parameter names these travel under, and why they are not in `BROWSE_PARAM`.
 *
 * That table is the *server's* grammar and every name in it is a parameter an endpoint accepts.
 * These four are the browser's own: they describe what the operator asked for, they are compiled
 * into `filterSource` before a request is made, and they are stripped from the request URL — see
 * `MessagesRoute`, which writes the address with them and starts the session without them.
 *
 * They are in the address for the same reason everything else on this bar is: a browse is a link,
 * and a colleague who is sent one must see the *predicates* in the controls rather than the
 * generated expression they compiled to.
 */
export const PREDICATE_PARAM = {
  key: "key",
  keyMode: "keyMode",
  value: "value",
  valueMode: "valueMode",
  untilOffset: "untilOffset",
  untilTime: "untilTime",
} as const;

/** The address-bar pairs these predicates become. Empty for an empty set. */
export function predicateParams(predicates: Predicates): readonly (readonly [string, string])[] {
  const pairs: [string, string][] = [];
  if (predicates.key !== undefined && predicates.key.text !== "") {
    pairs.push([PREDICATE_PARAM.key, predicates.key.text]);
    pairs.push([PREDICATE_PARAM.keyMode, predicates.key.mode]);
  }
  if (predicates.value !== undefined && predicates.value.text !== "") {
    pairs.push([PREDICATE_PARAM.value, predicates.value.text]);
    pairs.push([PREDICATE_PARAM.valueMode, predicates.value.mode]);
  }
  if (predicates.untilOffset !== undefined && predicates.untilOffset !== "") {
    pairs.push([PREDICATE_PARAM.untilOffset, predicates.untilOffset]);
  }
  if (predicates.untilTime !== undefined) {
    pairs.push([PREDICATE_PARAM.untilTime, String(predicates.untilTime)]);
  }
  return pairs;
}

/**
 * Reads them back out of an address.
 *
 * Lenient in exactly the way `fromParams` is, and for the same reason: these values come out of a
 * link somebody was sent, and a mode nobody recognises should cost the recipient that one setting
 * rather than the whole screen. An unknown mode falls back to `contains`, which is the mode the
 * control opens on.
 */
export function predicatesFrom(params: URLSearchParams): Predicates {
  const untilOffset = (params.get(PREDICATE_PARAM.untilOffset) ?? "").trim();
  const untilTime = Number(params.get(PREDICATE_PARAM.untilTime) ?? "");
  return {
    ...field(params, PREDICATE_PARAM.key, PREDICATE_PARAM.keyMode, "key"),
    ...field(params, PREDICATE_PARAM.value, PREDICATE_PARAM.valueMode, "value"),
    ...(/^\d+$/.test(untilOffset) ? { untilOffset } : {}),
    ...(Number.isSafeInteger(untilTime) && untilTime > 0 ? { untilTime } : {}),
  };
}

// --- The small pieces ---------------------------------------------------------------------------

function field<K extends "key" | "value">(
  params: URLSearchParams,
  textName: string,
  modeName: string,
  key: K,
): Record<K, FieldPredicate> | Record<string, never> {
  const text = params.get(textName) ?? "";
  if (text === "") return {};
  const raw = params.get(modeName) ?? "";
  const mode = MATCH_MODES.find((candidate) => candidate.value === raw)?.value ?? "contains";
  return { [key]: { mode, text } } as Record<K, FieldPredicate>;
}

function textTerm(variable: string, predicate: FieldPredicate | undefined): string | undefined {
  if (predicate === undefined || predicate.text === "") return undefined;
  const literal = celString(predicate.text);
  switch (predicate.mode) {
    case "contains":
      return `${variable}.contains(${literal})`;
    case "equals":
      return `${variable} == ${literal}`;
    case "starts":
      return `${variable}.startsWith(${literal})`;
    case "ends":
      return `${variable}.endsWith(${literal})`;
  }
}

/**
 * A CEL double-quoted string literal holding exactly this text.
 *
 * Written out rather than reached for through `JSON.stringify`, which is *nearly* right and wrong
 * in one place that matters here: it leaves U+2028 and U+2029 unescaped, and those two are line
 * terminators to some parsers. A key an operator pasted out of a payload is the most likely place
 * in this product for either to arrive, and a filter that fails to compile because of a character
 * nobody can see is a filter nobody can debug.
 */
export function celString(raw: string): string {
  let out = '"';
  for (const character of raw) {
    const code = character.codePointAt(0) ?? 0;
    if (character === "\\") out += "\\\\";
    else if (character === '"') out += '\\"';
    else if (character === "\n") out += "\\n";
    else if (character === "\r") out += "\\r";
    else if (character === "\t") out += "\\t";
    else if (code < 0x20 || code === 0x7f || code === 0x2028 || code === 0x2029) {
      out += `\\u${code.toString(16).padStart(4, "0")}`;
    } else out += character;
  }
  return `${out}"`;
}

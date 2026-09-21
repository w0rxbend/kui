/** A deliberately small JSONPath-shaped language that compiles to KUI's CEL record vocabulary. */

export type JsonPathFilterTarget = "key" | "value";
export type JsonPathComparisonOperator = "==" | "!=" | "<" | "<=" | ">" | ">=" | "contains";
export type JsonPathComparisonValue = string | number | boolean | null;

export interface JsonPathFilterInput {
  readonly target: JsonPathFilterTarget;
  readonly path: string;
  readonly operator: JsonPathComparisonOperator;
  readonly value: JsonPathComparisonValue;
}

export type JsonPathFilterResult =
  | { readonly ok: true; readonly source: string }
  | { readonly ok: false; readonly error: string };

type PathSegment =
  | { readonly kind: "field"; readonly value: string; readonly dot: boolean }
  | { readonly kind: "index"; readonly value: string };

type PathResult =
  | { readonly ok: true; readonly segments: readonly PathSegment[] }
  | { readonly ok: false; readonly error: string };

const TARGETS = new Set<JsonPathFilterTarget>(["key", "value"]);
const OPERATORS = new Set<JsonPathComparisonOperator>([
  "==",
  "!=",
  "<",
  "<=",
  ">",
  ">=",
  "contains",
]);

/** Words CEL tokenizes as literals or operators cannot safely follow a dot. */
const CEL_RESERVED_IDENTIFIERS = new Set(["true", "false", "null", "in"]);
const MAX_CEL_INDEX = "9223372036854775807";

/**
 * Compiles one field comparison without accepting any caller-provided CEL source.
 *
 * The path parser is intentionally not a general JSONPath parser. Unsupported syntax fails closed,
 * and every string that reaches the result is encoded as a CEL string literal before interpolation.
 */
export function compileJsonPathFilter(input: JsonPathFilterInput): JsonPathFilterResult {
  if (typeof input !== "object" || input === null) return failure("Filter input must be an object.");

  const candidate = input as unknown as Record<string, unknown>;
  const target = candidate["target"];
  if (typeof target !== "string" || !TARGETS.has(target as JsonPathFilterTarget)) {
    return failure("Filter target must be key or value.");
  }

  const operator = candidate["operator"];
  if (typeof operator !== "string" || !OPERATORS.has(operator as JsonPathComparisonOperator)) {
    return failure("Filter operator is not supported.");
  }

  const path = candidate["path"];
  if (typeof path !== "string") return failure("JSONPath must be a string beginning with $.");
  const parsed = parsePath(path);
  if (!parsed.ok) return parsed;

  const literal = comparisonLiteral(candidate["value"]);
  if (!literal.ok) return literal;
  if (operator === "contains" && typeof candidate["value"] !== "string") {
    return failure("The contains operator requires a string value.");
  }

  const root = `record.${target}`;
  const field = parsed.segments.reduce(appendSegment, root);
  const source =
    operator === "contains" ? `${field}.contains(${literal.source})` : `${field} ${operator} ${literal.source}`;
  return { ok: true, source };
}

function appendSegment(source: string, segment: PathSegment): string {
  if (segment.kind === "index") return `${source}[${segment.value}]`;
  if (segment.dot && !CEL_RESERVED_IDENTIFIERS.has(segment.value)) return `${source}.${segment.value}`;
  return `${source}[${celString(segment.value)}]`;
}

function parsePath(path: string): PathResult {
  if (path.charAt(0) !== "$") return pathFailure("must begin with $");

  const segments: PathSegment[] = [];
  let position = 1;
  while (position < path.length) {
    const current = path.charAt(position);
    if (current === ".") {
      const field = parseIdentifier(path, position + 1);
      if (!field.ok) return field;
      segments.push({ kind: "field", value: field.value, dot: true });
      position = field.next;
      continue;
    }
    if (current === "[") {
      const bracket = parseBracket(path, position + 1);
      if (!bracket.ok) return bracket;
      segments.push(bracket.segment);
      position = bracket.next;
      continue;
    }
    return pathFailure(`has unsupported trailing content at position ${String(position + 1)}`);
  }
  return { ok: true, segments };
}

type IdentifierResult =
  | { readonly ok: true; readonly value: string; readonly next: number }
  | { readonly ok: false; readonly error: string };

function parseIdentifier(path: string, start: number): IdentifierResult {
  if (!isIdentifierStart(path.charAt(start))) {
    return pathFailure(`needs an identifier after the dot at position ${String(start + 1)}`);
  }

  let next = start + 1;
  while (isIdentifierPart(path.charAt(next))) next += 1;
  return { ok: true, value: path.slice(start, next), next };
}

type BracketResult =
  | { readonly ok: true; readonly segment: PathSegment; readonly next: number }
  | { readonly ok: false; readonly error: string };

function parseBracket(path: string, start: number): BracketResult {
  const first = path.charAt(start);
  if (first === '"' || first === "'") {
    const quoted = parseQuotedKey(path, start, first);
    if (!quoted.ok) return quoted;
    if (path.charAt(quoted.next) !== "]") {
      return pathFailure(`needs ] after the quoted key at position ${String(quoted.next + 1)}`);
    }
    return {
      ok: true,
      segment: { kind: "field", value: quoted.value, dot: false },
      next: quoted.next + 1,
    };
  }

  if (!isDigit(first)) return pathFailure(`supports only numeric indexes or quoted keys inside []`);
  let next = start;
  while (isDigit(path.charAt(next))) next += 1;
  const raw = path.slice(start, next);
  if (raw.length > 1 && raw.charAt(0) === "0") {
    return pathFailure("does not allow a numeric index with a leading zero");
  }
  if (path.charAt(next) !== "]") {
    return pathFailure(`needs ] after the numeric index at position ${String(next + 1)}`);
  }
  if (raw.length > MAX_CEL_INDEX.length || (raw.length === MAX_CEL_INDEX.length && raw > MAX_CEL_INDEX)) {
    return pathFailure("contains a numeric index outside CEL's integer range");
  }
  return { ok: true, segment: { kind: "index", value: raw }, next: next + 1 };
}

type QuotedKeyResult =
  | { readonly ok: true; readonly value: string; readonly next: number }
  | { readonly ok: false; readonly error: string };

function parseQuotedKey(path: string, start: number, quote: string): QuotedKeyResult {
  let value = "";
  let position = start + 1;
  while (position < path.length) {
    const current = path.charAt(position);
    if (current === quote) return { ok: true, value, next: position + 1 };
    if (current === "\\") {
      const escaped = parseEscape(path, position);
      if (!escaped.ok) return escaped;
      value += escaped.value;
      position = escaped.next;
      continue;
    }
    if (current.charCodeAt(0) < 0x20) {
      return pathFailure(`contains an unescaped control character at position ${String(position + 1)}`);
    }
    value += current;
    position += 1;
  }
  return pathFailure("contains an unterminated quoted key");
}

type EscapeResult =
  | { readonly ok: true; readonly value: string; readonly next: number }
  | { readonly ok: false; readonly error: string };

function parseEscape(path: string, slash: number): EscapeResult {
  const escaped = path.charAt(slash + 1);
  const simple: Readonly<Record<string, string>> = {
    '"': '"',
    "'": "'",
    "\\": "\\",
    "/": "/",
    b: "\b",
    f: "\f",
    n: "\n",
    r: "\r",
    t: "\t",
  };
  const decoded = simple[escaped];
  if (decoded !== undefined) return { ok: true, value: decoded, next: slash + 2 };

  if (escaped !== "u") return pathFailure(`contains an unsupported escape at position ${String(slash + 1)}`);
  const hex = path.slice(slash + 2, slash + 6);
  if (hex.length !== 4 || ![...hex].every(isHexDigit)) {
    return pathFailure(`contains an invalid Unicode escape at position ${String(slash + 1)}`);
  }
  return { ok: true, value: String.fromCharCode(Number.parseInt(hex, 16)), next: slash + 6 };
}

type LiteralResult =
  | { readonly ok: true; readonly source: string }
  | { readonly ok: false; readonly error: string };

function comparisonLiteral(value: unknown): LiteralResult {
  if (typeof value === "string") return { ok: true, source: celString(value) };
  if (typeof value === "boolean") return { ok: true, source: String(value) };
  if (value === null) return { ok: true, source: "null" };
  if (typeof value === "number" && Number.isFinite(value)) {
    if (Number.isInteger(value) && !Number.isSafeInteger(value)) {
      return failure("Filter value is an integer JavaScript cannot represent exactly.");
    }
    return { ok: true, source: Object.is(value, -0) ? "0" : String(value) };
  }
  return failure("Filter value must be a finite string, number, boolean or null.");
}

/** JSON's quoted-string grammar is also valid CEL and closes both quote and backslash injection. */
function celString(value: string): string {
  return JSON.stringify(value).replace(/\u2028/g, "\\u2028").replace(/\u2029/g, "\\u2029");
}

function isIdentifierStart(value: string): boolean {
  return /^[A-Za-z_]$/.test(value);
}

function isIdentifierPart(value: string): boolean {
  return /^[A-Za-z0-9_]$/.test(value);
}

function isDigit(value: string): boolean {
  return value >= "0" && value <= "9";
}

function isHexDigit(value: string): boolean {
  return /^[0-9A-Fa-f]$/.test(value);
}

function failure(error: string): { readonly ok: false; readonly error: string } {
  return { ok: false, error };
}

function pathFailure(error: string): { readonly ok: false; readonly error: string } {
  return failure(`JSONPath ${error}.`);
}

/** The result of inspecting a value without changing the bytes the operator typed. */
export type JsonValueAnalysis =
  | { readonly kind: "empty" }
  | { readonly kind: "plain-text" }
  | { readonly kind: "valid-json" }
  | { readonly kind: "invalid-json"; readonly message: string };

export type JsonTokenKind = "key" | "string" | "number" | "boolean" | "null" | "punctuation" | "text";

export interface JsonToken {
  readonly kind: JsonTokenKind;
  readonly text: string;
}

/**
 * Detect structured JSON and check its grammar.
 *
 * A failed parse only becomes an error when the value starts like a JSON object or array. Kafka
 * string topics are allowed to carry arbitrary text, so `result=success` and `test` must not become
 * grammar failures merely because they are not JSON documents.
 */
export function analyzeJsonValue(value: string): JsonValueAnalysis {
  const trimmed = value.trim();
  if (trimmed === "") return { kind: "empty" };

  try {
    JSON.parse(trimmed);
    return { kind: "valid-json" };
  } catch (cause) {
    if (trimmed[0] !== "{" && trimmed[0] !== "[") return { kind: "plain-text" };
    return { kind: "invalid-json", message: describeJsonError(cause, value) };
  }
}

/**
 * Pretty-print or minify valid JSON by changing whitespace only.
 *
 * This intentionally does not use `JSON.stringify(JSON.parse(value))`. That familiar shortcut
 * rounds integer lexemes above JavaScript's safe range, turning Kafka identifiers such as
 * `9007199254740993` into different data before they reach the broker.
 */
export function formatJsonValue(value: string, style: "pretty" | "compact"): string {
  if (analyzeJsonValue(value).kind !== "valid-json") return value;
  const compact = compactJsonWhitespace(value.trim());
  return style === "compact" ? compact : prettyJsonWhitespace(compact);
}

/** Lex JSON-shaped text for the visual layer. Tokens are rendered as text nodes, never as HTML. */
export function tokenizeJsonValue(value: string): readonly JsonToken[] {
  const tokens: JsonToken[] = [];
  let at = 0;

  while (at < value.length) {
    const start = at;
    const char = value[at] as string;

    if (/\s/u.test(char)) {
      at += 1;
      while (at < value.length && /\s/u.test(value[at] as string)) at += 1;
      tokens.push({ kind: "text", text: value.slice(start, at) });
      continue;
    }

    if (char === '"') {
      at = stringEnd(value, at);
      let lookahead = at;
      while (lookahead < value.length && /\s/u.test(value[lookahead] as string)) lookahead += 1;
      tokens.push({
        kind: value[lookahead] === ":" ? "key" : "string",
        text: value.slice(start, at),
      });
      continue;
    }

    const rest = value.slice(at);
    const number = rest.match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/u)?.[0];
    if (number !== undefined) {
      tokens.push({ kind: "number", text: number });
      at += number.length;
      continue;
    }

    const literal = rest.match(/^(?:true|false|null)/u)?.[0];
    if (literal !== undefined) {
      tokens.push({ kind: literal === "null" ? "null" : "boolean", text: literal });
      at += literal.length;
      continue;
    }

    if ("{}[],:".includes(char)) {
      tokens.push({ kind: "punctuation", text: char });
      at += 1;
      continue;
    }

    at += 1;
    while (
      at < value.length &&
      !/[\s"{}\[\],:]/u.test(value[at] as string) &&
      !/-|\d/u.test(value[at] as string)
    ) at += 1;
    tokens.push({ kind: "text", text: value.slice(start, at) });
  }

  return tokens;
}

function stringEnd(value: string, quoteAt: number): number {
  let escaped = false;
  let at = quoteAt + 1;
  while (at < value.length) {
    const char = value[at] as string;
    at += 1;
    if (escaped) {
      escaped = false;
    } else if (char === "\\") {
      escaped = true;
    } else if (char === '"') {
      break;
    }
  }
  return at;
}

function compactJsonWhitespace(value: string): string {
  let compact = "";
  let inString = false;
  let escaped = false;

  for (const char of value) {
    if (inString) {
      compact += char;
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    if (char === '"') {
      inString = true;
      compact += char;
    } else if (!/\s/u.test(char)) {
      compact += char;
    }
  }
  return compact;
}

function prettyJsonWhitespace(value: string): string {
  let pretty = "";
  let depth = 0;
  let inString = false;
  let escaped = false;

  const indent = (): string => "  ".repeat(depth);

  for (let at = 0; at < value.length; at += 1) {
    const char = value[at] as string;
    if (inString) {
      pretty += char;
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    if (char === '"') {
      inString = true;
      pretty += char;
    } else if (char === "{" || char === "[") {
      pretty += char;
      if (value[at + 1] !== (char === "{" ? "}" : "]")) {
        depth += 1;
        pretty += `\n${indent()}`;
      }
    } else if (char === "}" || char === "]") {
      const opening = char === "}" ? "{" : "[";
      if (value[at - 1] !== opening) {
        depth = Math.max(0, depth - 1);
        pretty += `\n${indent()}`;
      }
      pretty += char;
    } else if (char === ",") {
      pretty += `,\n${indent()}`;
    } else if (char === ":") {
      pretty += ": ";
    } else {
      pretty += char;
    }
  }
  return pretty;
}

function describeJsonError(cause: unknown, value: string): string {
  const raw = cause instanceof Error ? cause.message : "The JSON grammar is invalid.";
  const givenLocation = raw.match(/line\s+(\d+)\s+column\s+(\d+)/iu);
  if (givenLocation !== null) {
    return `Invalid JSON at line ${givenLocation[1]}, column ${givenLocation[2]}.`;
  }

  const positionMatch = raw.match(/position\s+(\d+)/iu);
  const position =
    positionMatch === null
      ? (firstInvalidBareToken(value) ?? value.length)
      : Number(positionMatch[1]);
  const before = value.slice(0, Math.max(0, Math.min(position, value.length)));
  const line = before.split("\n").length;
  const lastBreak = before.lastIndexOf("\n");
  const column = before.length - lastBreak;
  return `Invalid JSON at line ${String(line)}, column ${String(column)}.`;
}

/** Finds misspelled JSON literals such as `tru` when an engine gives no source position. */
function firstInvalidBareToken(value: string): number | undefined {
  let inString = false;
  let escaped = false;
  for (let at = 0; at < value.length; at += 1) {
    const char = value[at] as string;
    if (inString) {
      if (escaped) escaped = false;
      else if (char === "\\") escaped = true;
      else if (char === '"') inString = false;
      continue;
    }
    if (char === '"') {
      inString = true;
      continue;
    }
    if (/[A-Za-z_]/u.test(char)) {
      const rest = value.slice(at);
      const word = rest.match(/^[A-Za-z_]+/u)?.[0] ?? char;
      if (word !== "true" && word !== "false" && word !== "null") return at;
      at += word.length - 1;
    }
  }
  return undefined;
}

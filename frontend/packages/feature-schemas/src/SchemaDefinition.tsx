import type { JSX } from "@solidjs/web";
import { For, Loading, Show, createMemo, lazy } from "solid-js";

const JsonTree = lazy(() => import("@kui/kernel/json-tree"));

export interface SchemaDefinitionProps {
  readonly definition: string;
  readonly schemaType: string;
}

type SchemaTokenKind = "keyword" | "string" | "number" | "comment" | "punctuation" | "text";

interface SchemaToken {
  readonly kind: SchemaTokenKind;
  readonly text: string;
}

const PROTOBUF_KEYWORDS = new Set([
  "syntax", "package", "import", "option", "message", "enum", "service", "rpc", "returns",
  "repeated", "optional", "required", "oneof", "map", "reserved", "extensions", "extend",
  "string", "bytes", "bool", "double", "float", "int32", "int64", "uint32", "uint64",
  "sint32", "sint64", "fixed32", "fixed64", "sfixed32", "sfixed64", "true", "false",
]);

/** A read-only schema preview: structured JSON where possible, highlighted source otherwise. */
export function SchemaDefinition(props: SchemaDefinitionProps): JSX.Element {
  const structured = createMemo(() => isJson(props.definition));
  const source = createMemo(() =>
    structured() || props.schemaType.toUpperCase() !== "PROTOBUF"
      ? props.definition
      : formatProtobufSource(props.definition),
  );
  const tokens = createMemo(() => tokenizeSchemaSource(source()));
  const lineCount = createMemo(() => source().split("\n").length);
  const sourceSummary = () => {
    const count = lineCount();
    return `${props.schemaType} source · ${String(count)} ${count === 1 ? "line" : "lines"}`;
  };

  return (
    <details class="kui-subject__definition-panel" open>
      <summary class="kui-subject__definition-summary">
        <span class="kui-subject__definition-title">Schema definition</span>
        <span class="kui-subject__definition-meta">
          {structured()
            ? "Structured JSON · expand fields as needed"
            : sourceSummary()}
        </span>
      </summary>
      <div class="kui-subject__definition-body">
        <Show
          when={structured()}
          fallback={
            <pre
              class="kui-subject__definition-source"
              tabindex={0}
              aria-label={`${props.schemaType} schema definition`}
            ><code><For each={tokens()}>{(token) => (
              <span class={`kui-schema-token kui-schema-token--${token.kind}`}>
                {token.text}
              </span>
            )}</For></code></pre>
          }
        >
          {/* `JsonTree` snapshots immutable text for performance. Keying on the definition remounts
              it when the operator switches versions without leaving the subject route. */}
          <Show when={props.definition} keyed>
            {(definition) => (
              <Loading
                fallback={
                  <div class="kui-subject__definition-loading" aria-busy="true">
                    Loading structured schema…
                  </div>
                }
              >
                <JsonTree
                  text={definition}
                  class="kui-subject__definition-tree"
                  ariaLabel={`${props.schemaType} schema definition`}
                />
              </Loading>
            )}
          </Show>
        </Show>
        <p class="kui-subject__definition-note">
          Formatted preview. The schema stored in the registry is unchanged.
        </p>
      </div>
    </details>
  );
}

function isJson(source: string): boolean {
  try {
    JSON.parse(source);
    return true;
  } catch {
    return false;
  }
}

function tokenizeSchemaSource(source: string): readonly SchemaToken[] {
  const tokens: SchemaToken[] = [];
  let at = 0;
  while (at < source.length) {
    const start = at;
    const char = source[at] as string;
    if (/\s/u.test(char)) {
      at += 1;
      while (at < source.length && /\s/u.test(source[at] as string)) at += 1;
      tokens.push({ kind: "text", text: source.slice(start, at) });
      continue;
    }
    if (source.startsWith("//", at)) {
      const end = source.indexOf("\n", at);
      at = end === -1 ? source.length : end;
      tokens.push({ kind: "comment", text: source.slice(start, at) });
      continue;
    }
    if (source.startsWith("/*", at)) {
      const end = source.indexOf("*/", at + 2);
      at = end === -1 ? source.length : end + 2;
      tokens.push({ kind: "comment", text: source.slice(start, at) });
      continue;
    }
    if (char === '"' || char === "'") {
      const quote = char;
      let escaped = false;
      at += 1;
      while (at < source.length) {
        const current = source[at] as string;
        at += 1;
        if (escaped) escaped = false;
        else if (current === "\\") escaped = true;
        else if (current === quote) break;
      }
      tokens.push({ kind: "string", text: source.slice(start, at) });
      continue;
    }
    const rest = source.slice(at);
    const number = rest.match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?/u)?.[0];
    if (number !== undefined) {
      tokens.push({ kind: "number", text: number });
      at += number.length;
      continue;
    }
    const word = rest.match(/^[A-Za-z_][A-Za-z0-9_]*/u)?.[0];
    if (word !== undefined) {
      tokens.push({ kind: PROTOBUF_KEYWORDS.has(word) ? "keyword" : "text", text: word });
      at += word.length;
      continue;
    }
    tokens.push({ kind: "punctuation", text: char });
    at += 1;
  }
  return tokens;
}

/** Formats compact Protobuf source for reading while preserving every non-whitespace token. */
function formatProtobufSource(source: string): string {
  const significant = tokenizeSchemaSource(source).filter((token) => !/^\s+$/u.test(token.text));
  let formatted = "";
  let depth = 0;
  let lineStart = true;

  const newline = (): void => {
    formatted = `${formatted.trimEnd()}\n`;
    lineStart = true;
  };
  const write = (text: string, spaceBefore = false): void => {
    if (lineStart) {
      formatted += "  ".repeat(depth);
      lineStart = false;
    } else if (spaceBefore && !formatted.endsWith(" ")) {
      formatted += " ";
    }
    formatted += text;
  };

  for (const token of significant) {
    if (token.kind === "comment") {
      write(token.text, true);
      newline();
    } else if (token.text === "{") {
      write("{", true);
      depth += 1;
      newline();
    } else if (token.text === "}") {
      if (!lineStart) newline();
      depth = Math.max(0, depth - 1);
      write("}");
      newline();
    } else if (token.text === ";") {
      write(";");
      newline();
    } else if (token.text === "=") {
      write("=", true);
      formatted += " ";
    } else if (token.text === ",") {
      write(",");
      formatted += " ";
    } else if (token.text === ".") {
      write(".");
    } else {
      const previous = formatted.at(-1);
      write(token.text, previous !== undefined && !/[\s.(=]/u.test(previous));
    }
  }
  return formatted.trim();
}

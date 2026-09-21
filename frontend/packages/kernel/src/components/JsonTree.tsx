import type { JSX } from "@solidjs/web";
import { For, Show, createMemo, createSignal, untrack } from "solid-js";

const DEFAULT_EXPANDED_DEPTH = 0;
const MAX_RENDER_DEPTH = 64;
const CHILD_BATCH_SIZE = 200;

export interface JsonTreeProps {
  readonly text: string;
  readonly ariaLabel?: string | undefined;
  readonly class?: string | undefined;
}

interface JsonNodeProps {
  readonly source: string;
  readonly start: number;
  readonly end: number;
  readonly path: string;
  readonly depth: number;
  readonly label?: string | number;
  readonly labelToken?: string;
  readonly trailing?: boolean;
}

interface JsonEntry {
  readonly label: string | number;
  readonly labelToken?: string;
  readonly start: number;
  readonly end: number;
  readonly path: string;
}

interface CollectionSlice {
  readonly count: number;
  readonly entries: readonly JsonEntry[];
}

interface ParseResult {
  readonly parsed: boolean;
  readonly start: number;
  readonly end: number;
}

function parseBounds(text: string): ParseResult {
  try {
    // Validation only. The parsed result is deliberately discarded: materialising JSON numbers as
    // doubles would change valid int64 values before they reached the screen.
    JSON.parse(text);
  } catch {
    return { parsed: false, start: 0, end: text.length };
  }
  let start = 0;
  let end = text.length;
  while (start < end && isWhitespace(text[start])) start += 1;
  while (end > start && isWhitespace(text[end - 1])) end -= 1;
  return { parsed: true, start, end };
}

function isWhitespace(character: string | undefined): boolean {
  return character === " " || character === "\n" || character === "\r" || character === "\t";
}

function skipWhitespace(source: string, index: number): number {
  let next = index;
  while (isWhitespace(source[next])) next += 1;
  return next;
}

function stringEnd(source: string, start: number): number {
  let escaped = false;
  for (let index = start + 1; index < source.length; index += 1) {
    const character = source[index];
    if (escaped) {
      escaped = false;
    } else if (character === "\\") {
      escaped = true;
    } else if (character === '"') {
      return index + 1;
    }
  }
  return source.length;
}

/** Finds one value without recursively materialising it. The source has already been validated. */
function valueEnd(source: string, start: number): number {
  const first = source[start];
  if (first === '"') return stringEnd(source, start);
  if (first !== "[" && first !== "{") {
    let index = start;
    while (
      index < source.length &&
      !isWhitespace(source[index]) &&
      source[index] !== "," &&
      source[index] !== "]" &&
      source[index] !== "}"
    ) {
      index += 1;
    }
    return index;
  }

  let depth = 0;
  for (let index = start; index < source.length; index += 1) {
    const character = source[index];
    if (character === '"') {
      index = stringEnd(source, index) - 1;
    } else if (character === "[" || character === "{") {
      depth += 1;
    } else if (character === "]" || character === "}") {
      depth -= 1;
      if (depth === 0) return index + 1;
    }
  }
  return source.length;
}

function childPath(parent: string, label: string | number): string {
  if (typeof label === "number") return `${parent}[${label}]`;
  return /^[A-Za-z_$][\w$]*$/.test(label)
    ? `${parent}.${label}`
    : `${parent}[${JSON.stringify(label)}]`;
}

function collectionSlice(
  source: string,
  start: number,
  end: number,
  parentPath: string,
  limit: number,
): CollectionSlice {
  const array = source[start] === "[";
  const entries: JsonEntry[] = [];
  let count = 0;
  let cursor = skipWhitespace(source, start + 1);

  while (cursor < end - 1) {
    let label: string | number;
    let labelToken: string | undefined;
    if (array) {
      label = count;
    } else {
      const keyStart = cursor;
      const keyEnd = stringEnd(source, keyStart);
      labelToken = source.slice(keyStart, keyEnd);
      label = JSON.parse(labelToken) as string;
      cursor = skipWhitespace(source, keyEnd);
      cursor = skipWhitespace(source, cursor + 1); // the validated colon
    }

    const childStart = cursor;
    const childEnd = valueEnd(source, childStart);
    if (entries.length < limit) {
      entries.push({
        label,
        ...(labelToken === undefined ? {} : { labelToken }),
        start: childStart,
        end: childEnd,
        path: childPath(parentPath, label),
      });
    }
    count += 1;
    cursor = skipWhitespace(source, childEnd);
    if (source[cursor] === ",") cursor = skipWhitespace(source, cursor + 1);
  }
  return { count, entries };
}

function nodeName(label: string | number | undefined, path: string): string {
  if (label === undefined) return "root";
  return typeof label === "number" ? `item ${label}` : `${label} at ${path}`;
}

function JsonKey(props: {
  readonly label?: string | number;
  readonly labelToken?: string;
}): JSX.Element {
  return (
    <Show when={props.label !== undefined}>
      <span class="kui-json-tree__key">
        {props.labelToken ??
          (typeof props.label === "number" ? props.label : JSON.stringify(props.label))}
      </span>
      <span class="kui-json-tree__punctuation">: </span>
    </Show>
  );
}

function primitiveKind(source: string, start: number): "string" | "number" | "boolean" | "null" {
  const first = source[start];
  if (first === '"') return "string";
  if (first === "t" || first === "f") return "boolean";
  if (first === "n") return "null";
  return "number";
}

function JsonPrimitive(props: {
  readonly source: string;
  readonly start: number;
  readonly end: number;
}): JSX.Element {
  const token = untrack(() => props.source.slice(props.start, props.end));
  const kind = untrack(() => primitiveKind(props.source, props.start));
  return <span class={`kui-json-tree__${kind}`}>{token}</span>;
}

function JsonNode(props: JsonNodeProps): JSX.Element {
  // Nodes describe immutable byte ranges in an immutable record. Snapshotting that contract also
  // prevents the renderer from creating thousands of subscriptions for static offsets.
  const node = untrack(() => ({
    source: props.source,
    start: props.start,
    end: props.end,
    path: props.path,
    depth: props.depth,
    label: props.label,
    labelToken: props.labelToken,
    trailing: props.trailing,
  }));
  const first = node.source[node.start];
  const collection = first === "[" || first === "{";
  if (!collection) {
    return (
      <div class="kui-json-tree__leaf" data-json-path={node.path}>
        <JsonKey
          {...(node.label === undefined ? {} : { label: node.label })}
          {...(node.labelToken === undefined ? {} : { labelToken: node.labelToken })}
        />
        <JsonPrimitive source={node.source} start={node.start} end={node.end} />
        <Show when={node.trailing === true}>
          <span class="kui-json-tree__punctuation">,</span>
        </Show>
      </div>
    );
  }

  const array = first === "[";
  const initiallyOpen = node.depth <= DEFAULT_EXPANDED_DEPTH;
  const [open, setOpen] = createSignal(initiallyOpen);
  const [visibleCount, setVisibleCount] = createSignal(CHILD_BATCH_SIZE);
  const data = createMemo(() =>
    collectionSlice(node.source, node.start, node.end, node.path, visibleCount()),
  );
  const opening = array ? "[" : "{";
  const closing = array ? "]" : "}";
  const kind = array ? "array" : "object";
  const unit = () =>
    array ? (data().count === 1 ? "item" : "items") : data().count === 1 ? "field" : "fields";

  return (
    <details
      class="kui-json-tree__branch"
      data-json-path={node.path}
      open={initiallyOpen}
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary
        class="kui-json-tree__summary"
        aria-label={`${open() ? "Collapse" : "Expand"} ${kind} ${nodeName(node.label, node.path)}, ${data().count} ${unit()}`}
      >
        <JsonKey
          {...(node.label === undefined ? {} : { label: node.label })}
          {...(node.labelToken === undefined ? {} : { labelToken: node.labelToken })}
        />
        <span class="kui-json-tree__punctuation">{opening}</span>
        <Show when={!open()}>
          <span class="kui-json-tree__preview"> … {closing}</span>
          <Show when={node.trailing === true}>
            <span class="kui-json-tree__punctuation">,</span>
          </Show>
        </Show>
        <span class="kui-json-tree__count">
          {data().count} {unit()}
        </span>
      </summary>

      <Show when={open()}>
        <div class="kui-json-tree__children">
          <Show
            when={node.depth < MAX_RENDER_DEPTH}
            fallback={<span class="kui-json-tree__limit">Maximum display depth reached</span>}
          >
            <For each={data().entries}>
              {(entry, index) => (
                <JsonNode
                  source={node.source}
                  start={entry.start}
                  end={entry.end}
                  path={entry.path}
                  depth={node.depth + 1}
                  label={entry.label}
                  {...(entry.labelToken === undefined ? {} : { labelToken: entry.labelToken })}
                  trailing={index() < data().count - 1}
                />
              )}
            </For>
            <Show when={data().entries.length < data().count}>
              <button
                type="button"
                class="kui-json-tree__more"
                onClick={() =>
                  setVisibleCount((count) => Math.min(data().count, count + CHILD_BATCH_SIZE))
                }
              >
                Show {Math.min(CHILD_BATCH_SIZE, data().count - data().entries.length)} more
              </button>
            </Show>
          </Show>
        </div>
        <div class="kui-json-tree__closing" aria-hidden="true">
          {closing}
          <Show when={node.trailing === true}>,</Show>
        </div>
      </Show>
    </details>
  );
}

export default function JsonTree(props: JsonTreeProps): JSX.Element {
  // A record row is keyed by partition+offset; its payload cannot change in place. Marking this
  // one-time validation explicitly avoids turning every token into a reactive subscription.
  const text = untrack(() => props.text);
  const result = parseBounds(text);
  if (!result.parsed) {
    return (
      <pre class={["kui-record__payload", props.class]} tabindex="0">
        {text}
      </pre>
    );
  }

  return (
    <div
      class={["kui-json-tree", props.class]}
      tabindex="0"
      aria-label={props.ariaLabel ?? "JSON value"}
    >
      <JsonNode source={text} start={result.start} end={result.end} path="$" depth={0} />
    </div>
  );
}

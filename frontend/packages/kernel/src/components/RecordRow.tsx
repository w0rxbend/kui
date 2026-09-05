import type { JSX } from "@solidjs/web";
import { createSignal, createUniqueId, For, Show } from "solid-js";
import { HeaderChip } from "./HeaderChip.jsx";
import { Icon } from "./Icon.jsx";
import {
  formatOffset,
  prettyValue,
  previewValue,
  relativeTime,
  type KafkaRecord,
} from "./record.js";

/**
 * One Kafka record in the message list, and its expansion.
 *
 * ## Why this is not a table row
 *
 * The design spec draws two list treatments and says explicitly not to merge them (§3.5).
 *
 *   - A **table** — consumer groups, brokers — is a grid of comparable values. Ruled rows, no
 *     gaps, aligned columns, and the eye travels *across* a row comparing it to its neighbours.
 *   - A **record list** is a stack of independent objects, each of which can open. Separated
 *     cards, a 6px gap of page ground between them, and the eye travels *down*.
 *
 * They look different because they are used differently. Nothing compares record 18,442,901 to
 * record 18,442,902 column by column; the reader is looking for one record and then opening it.
 * Building this on `DataTable` would have made an expanding row into a `<tr>` that grows, which is
 * where the layout fights start.
 *
 * ## The whole row is the control
 *
 * Not the chevron, and not the offset. The summary is a real `<button>` spanning the card, with
 * `aria-expanded` and `aria-controls`, so it is in the tab order, answers to Enter and Space, and
 * announces its state — none of which has to be written here because it is a button.
 *
 * A **visible chevron is required as well**. The two halves are separate requirements and this
 * project has shipped each without the other: a row that expands with no affordance is a row
 * nobody discovers, and a chevron that is the only hit target is a 16px target in a 36px row.
 *
 * ## Expanding pushes the rows below it down
 *
 * It does not overlay them and it does not open a modal. The expansion is a sibling inside the
 * same card, so the card grows and the list reflows. An overlay would hide the neighbouring
 * records, which is exactly what somebody comparing two payloads is trying not to lose; a modal
 * would take the whole page for one record out of five hundred.
 *
 * ## Five payloads, five renderings
 *
 * `RecordValue` (see `record.ts`) is a union rather than a string, because "the value" is five
 * different situations. Three of them — a tombstone, a payload too large to preview, and one that
 * would not deserialize — have all been drawn as an empty row at some point, and an empty row is
 * indistinguishable from a record that genuinely holds the empty string.
 */
export interface RecordRowProps {
  readonly record: KafkaRecord;
  /** The clock, passed in so relative times are testable. See `relativeTime`. */
  readonly now: number;
  /** Open on first render. The row owns its state after that. */
  readonly initiallyExpanded?: boolean;
  /**
   * The record arrived while live-tailing. Draws a one-shot wash of the success container that
   * fades out, suppressed by the stylesheet under `prefers-reduced-motion`.
   */
  readonly arrived?: boolean;
  readonly testId?: string;
}

export function RecordRow(props: RecordRowProps): JSX.Element {
  const [expanded, setExpanded] = createSignal(props.initiallyExpanded === true);
  const bodyId = createUniqueId();

  const isTombstone = () => props.record.value.kind === "tombstone" || props.record.key === null;
  const isJson = () => props.record.value.kind === "json";
  const failed = () => props.record.value.kind === "undecodable";

  return (
    <li
      class={[
        "kui-record",
        {
          "kui-record--open": expanded(),
          "kui-record--arrived": props.arrived === true,
          "kui-record--failed": failed(),
        },
      ]}
      data-testid={props.testId}
    >
      <button
        type="button"
        class="kui-record__summary"
        // The string, not the boolean: see the note in DataTable. `aria-expanded` absent means
        // "this is not an expandable thing at all", which is the opposite of what a closed row is.
        aria-expanded={expanded() ? "true" : "false"}
        aria-controls={bodyId}
        onClick={() => void setExpanded((open) => !open)}
      >
        <span class="kui-record__offset">
          <span class="kui-record__hash" aria-hidden="true">
            #
          </span>
          {/* The offset is the record's name, so it is the one thing announced in full: a screen
              reader reading "one eight four four two nine zero one" is reading a phone number. */}
          <span class="kui-record__offset-value">{formatOffset(props.record.offset)}</span>
        </span>

        <span class="kui-record__partition">
          <span class="kui-visually-hidden">partition </span>p&nbsp;{props.record.partition}
        </span>

        <span class="kui-record__key">
          <Icon name="key" class="kui-record__key-glyph" />
          <Show
            when={props.record.key}
            fallback={
              // Not a bare dash. A null key in a compacted topic *is* the deletion, and naming it
              // is the difference between "no key" and "this record deletes that key".
              <span class="kui-record__key-absent">— (tombstone)</span>
            }
          >
            {(key) => <span class="kui-record__key-value">{key()}</span>}
          </Show>
        </span>

        <span class="kui-record__value">
          <Show when={isJson()} fallback={<Icon name="menu" class="kui-record__value-glyph" />}>
            <Icon name="braces" class="kui-record__value-glyph" />
          </Show>
          <span
            class={[
              "kui-record__value-preview",
              { "kui-record__value-preview--failed": failed() },
            ]}
          >
            {previewValue(props.record.value)}
          </span>
        </span>

        <span class="kui-record__time">
          {/* The exact instant is on the element as a tooltip and in the expansion in full. The
              relative time is what is scanned; the absolute one is what is quoted in a ticket. */}
          <time datetime={props.record.timestamp} title={props.record.timestamp}>
            {relativeTime(props.record.timestamp, props.now)}
          </time>
        </span>

        {/* Decoration. The button above it already announces expanded or collapsed; a chevron that
            was also in the accessibility tree would say it twice, in pictures. */}
        <Icon name="chevron-down" class="kui-record__chevron" />
      </button>

      <Show when={expanded()}>
        <RecordExpansion id={bodyId} record={props.record} tombstone={isTombstone()} />
      </Show>
    </li>
  );
}

/**
 * What one record shows when it is open.
 *
 * Four labelled boxes, the headers, and the payload. The grid is
 * `repeat(auto-fit, minmax(220px, 1fr))` rather than four fixed columns, so a fifth box — the
 * schema, when one is attached — reflows onto a second line instead of squashing all five.
 */
function RecordExpansion(props: {
  readonly id: string;
  readonly record: KafkaRecord;
  readonly tombstone: boolean;
}): JSX.Element {
  return (
    <div id={props.id} class="kui-record__body">
      <dl class="kui-record__facts">
        <Fact label="OFFSET" mono>
          {formatOffset(props.record.offset)}
        </Fact>
        <Fact label="PARTITION">{String(props.record.partition)}</Fact>
        <Fact label="KEY" mono>
          {props.record.key ?? "— (tombstone)"}
        </Fact>
        <Fact label="TIMESTAMP">
          <time datetime={props.record.timestamp}>{props.record.timestamp}</time>
          <Show when={props.record.timestampType}>
            {/* Which clock this came from. Not a footnote: a topic on LogAppendTime shows records
                in an order they were not produced in, and an operator debugging ordering who does
                not know which clock they are reading will draw the wrong conclusion from it. */}
            {(type) => <span class="kui-record__fact-note">{type()}</span>}
          </Show>
        </Fact>
        <Show when={props.record.schema}>
          {(schema) => (
            <Fact label="SCHEMA" mono>
              {`${schema().subject} v${schema().version}`}
            </Fact>
          )}
        </Show>
      </dl>

      <section class="kui-record__section">
        {/* The label stays even when there are no headers. Dropping it entirely makes the reader
            wonder whether the product looked. */}
        <h4 class="kui-record__section-label">HEADERS</h4>
        <Show
          when={props.record.headers.length > 0}
          fallback={<p class="kui-record__none">— none</p>}
        >
          <div class="kui-record__headers">
            <For each={props.record.headers}>
              {(header) => (
                <HeaderChip
                  name={header.name}
                  value={header.value}
                  // `exactOptionalPropertyTypes` distinguishes "absent" from "present and
                  // undefined", so an optional flag is spread in only when it has a value.
                  {...(header.binary === undefined ? {} : { binary: header.binary })}
                />
              )}
            </For>
          </div>
        </Show>
      </section>

      <section class="kui-record__section">
        <h4 class="kui-record__section-label">VALUE</h4>
        <Show
          when={props.record.value.kind === "undecodable" ? props.record.value : null}
          fallback={
            // Scrolls vertically inside itself past its maximum height, and never horizontally:
            // long strings wrap. A horizontal scrollbar inside a vertical list means the reader
            // has to scroll two axes to read one payload.
            <pre class="kui-record__payload" tabindex="0">
              {prettyValue(props.record.value)}
            </pre>
          }
        >
          {(value) => (
            <div class="kui-record__decode-error">
              <p class="kui-record__decode-reason">{value().reason}</p>
              <Show when={value().hex}>
                {(hex) => (
                  <>
                    {/* The raw bytes are the only thing left that is definitely true, so they are
                        offered rather than hidden behind the error. */}
                    <p class="kui-record__section-label">RAW BYTES</p>
                    <pre class="kui-record__payload" tabindex="0">
                      {hex()}
                    </pre>
                  </>
                )}
              </Show>
            </div>
          )}
        </Show>
      </section>
    </div>
  );
}

/** One labelled box in the expansion's grid. A `<dt>`/`<dd>` pair, because that is what it is: a
 * name and its value, and a screen reader reading the list gets the pairing for free. */
function Fact(props: {
  readonly label: string;
  readonly mono?: boolean;
  readonly children: JSX.Element;
}): JSX.Element {
  return (
    <div class="kui-record__fact">
      {/* Written upper-case in the source rather than transformed from mixed case, so a screen
          reader is not handed a string it may choose to spell out letter by letter. */}
      <dt class="kui-record__fact-label">{props.label}</dt>
      <dd class={["kui-record__fact-value", { "kui-record__fact-value--mono": props.mono === true }]}>
        {props.children}
      </dd>
    </div>
  );
}

/**
 * The list the rows sit in.
 *
 * A real `<ul>`. A screen reader then says "list, five hundred items" and offers list navigation,
 * which on a message list is the difference between skimming and reading everything. The gaps
 * between cards come from the stylesheet, not from margins on the rows, so that a row does not
 * need to know whether it is the last one.
 */
export function RecordList(props: {
  readonly label: string;
  readonly children: JSX.Element;
}): JSX.Element {
  return (
    <ul class="kui-record-list" aria-label={props.label}>
      {props.children}
    </ul>
  );
}

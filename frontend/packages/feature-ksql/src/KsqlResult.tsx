/**
 * The result region — the one thing in `SCREENS-V4.md` §3.16 that no capture shows.
 *
 * §7.9 is the open finding this closes: *"Columns, streaming rows, the row cap and the error
 * rendering are all unspecified while `Run` is drawn as an enabled control."* ADR-056 is the
 * decision, `model.ts` is the state machine, and this file is what a reader sees.
 *
 * ## Four things that look alike and are drawn differently on purpose
 *
 * A push query that **has not produced a row yet**, a pull query that **ran and matched nothing**,
 * a query that **died before producing anything**, and a statement that **returns no rows at all**
 * put no rows on the screen. They are four completely different facts — wait, your predicate is
 * wrong, go and look at the server, and nothing was ever going to appear here — and `rowSentence`
 * and the `status` arm keep them apart. A region that drew "0 rows" for all four would be the
 * product refusing to say which.
 *
 * ## The rows survive the failure that ended them
 *
 * `interrupted` draws a banner **over** the rows rather than instead of them. They arrived and they
 * were true, and they are the only record the operator has of what the query was producing when the
 * connection died. An error panel that replaced them would delete evidence in order to show an
 * error message.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { Banner, DataTable, EmptyState, type Column } from "@kui/kernel";

import { cellText, rowSentence, type ResultRegion } from "./model.js";
import type { KsqlRow } from "./wire.js";

export interface KsqlResultProps {
  readonly region: ResultRegion;
}

/** One row, keyed by its arrival position: a result row has no identity of its own. */
interface NumberedRow {
  readonly index: number;
  readonly cells: KsqlRow;
}

export function KsqlResult(props: KsqlResultProps): JSX.Element {
  return (
    <div class="kui-ksql-result" data-testid="ksql-result" data-kind={props.region.kind}>
      <Show when={props.region.kind === "running"}>
        <p role="status" data-testid="ksql-result-running">
          Asking the ksqlDB server what this statement would do…
        </p>
      </Show>

      <Show when={props.region.kind === "confirming"}>
        <p role="status" data-testid="ksql-result-confirming">
          This statement needs confirming before KUI will run it.
        </p>
      </Show>

      <Show when={props.region.kind === "status" ? props.region : undefined}>
        {(state) => (
          <>
            {/* The server's own sentence. `Stream created and running` — never paraphrased, because
                the words are the whole of what a DDL statement answers. */}
            <p role="status" class="kui-ksql-result__status" data-testid="ksql-result-status">
              {state().message}
            </p>
            <Show when={state().entity}>
              {(entity) => (
                <p class="kui-ksql-result__entity">
                  It acted on <code>{entity()}</code>.
                </p>
              )}
            </Show>
          </>
        )}
      </Show>

      <Show when={props.region.kind === "failed" ? props.region : undefined}>
        {(state) => (
          <EmptyState
            kind="unavailable"
            title="The statement did not run."
            description={state().message}
            code={state().code}
            testId="ksql-result-failed"
          />
        )}
      </Show>

      {/* Over the rows, not instead of them. See the header. */}
      <Show when={props.region.kind === "interrupted" ? props.region : undefined}>
        {(state) => (
          <Banner
            tone="danger"
            message={`The query stopped: ${state().message}`}
            testId="ksql-result-interrupted"
          />
        )}
      </Show>

      <Show when={props.region.kind === "ended" ? props.region : undefined}>
        {(state) => (
          <Banner
            /* Info, not warning: a push query the reader cancelled, or one the server finished, is
               not a fault. The reason is the stream's own words for why it stopped. */
            tone="info"
            message={`The query is no longer running: ${state().reason}`}
            testId="ksql-result-ended"
          />
        )}
      </Show>

      <Show when={rowsOf(props.region)}>
        {(held) => (
          <>
            <p class="kui-ksql-result__count" role="status" data-testid="ksql-result-count">
              {rowSentence(props.region)}
            </p>
            {/* No table under a query that has produced nothing. The sentence above already says
                which of the kinds of nothing this is, and an empty grid under it reads as another
                one. */}
            <Show when={held().rows.length > 0}>
              <div class="kui-ksql-result__table">
                <DataTable<NumberedRow>
                  caption="Query result"
                  columns={columnsOf(held().columns, held().rows)}
                  rows={held().rows.map((cells, index) => ({ index, cells }))}
                  rowKey={(row) => String(row.index)}
                  testId="ksql-result-rows"
                />
              </div>
            </Show>
          </>
        )}
      </Show>
    </div>
  );
}

/** The four states that carry rows, as one shape. */
function rowsOf(
  region: ResultRegion,
): { readonly columns: readonly string[]; readonly rows: readonly KsqlRow[] } | undefined {
  switch (region.kind) {
    case "rows":
    case "streaming":
    case "ended":
    case "interrupted":
      return { columns: region.columns, rows: region.rows };
    default:
      return undefined;
  }
}

/**
 * The table's columns, from the column list the answer declared.
 *
 * Where a row is **wider** than that list — which a push query whose `phase` frame was dropped can
 * be, and which a service and a build that disagree certainly can be — the extra columns are drawn
 * with positional headings rather than cut off. Narrowing the table to the heading list would hide
 * a real disagreement by throwing data away, and this screen's whole job is to show what came back.
 */
function columnsOf(
  columns: readonly string[],
  rows: readonly KsqlRow[],
): readonly Column<NumberedRow>[] {
  const width = Math.max(columns.length, ...rows.map((row) => row.length), 0);
  return Array.from({ length: width }, (_unused, index) => ({
    id: `column-${index}`,
    header: columns[index] ?? `Column ${index + 1}`,
    render: (row: NumberedRow) => cellText(row.cells[index]),
  }));
}

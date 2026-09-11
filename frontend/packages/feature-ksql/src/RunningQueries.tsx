/**
 * What ksqlDB is running right now, under the workspace.
 *
 * `SCREENS-V4.md` §3.16 draws two panes and no query list; §4.15 names `M20`, `M21` and `M22` and
 * none of the three captures one. The list is here rather than in the objects pane because the two
 * are different things and the pane's heading says so: `STREAMS & TABLES` is what exists, and a
 * persistent query is what is *happening*. The service flattens both into one `items` list with a
 * `kind` — deliberately, so the browser does not re-order anything — and this component is the one
 * place the `query` rows are read out of it.
 *
 * It is a real table rather than a paragraph because the query id is what an operator copies into a
 * `TERMINATE`, and a copied id with a stray space in it fails in a way that reads as a KUI bug.
 *
 * **No throughput and no lag.** ksqlDB reports a query's own record rate in its metrics endpoints,
 * which `services/ksql` does not read, so nothing on this wire measures one. A `0 msg/s` beside a
 * running query would be a fabricated zero, which is the defect this product's central promise is
 * about — the row says what it knows and stops.
 */
import { Show } from "solid-js";
import type { JSX } from "@solidjs/web";
import { DataTable, type Column } from "@kui/kernel";

import type { KsqlObjectRow } from "./wire.js";

export interface RunningQueriesProps {
  /** The `query` rows of the listing, in the server's order. */
  readonly queries: readonly KsqlObjectRow[];
}

/** A transient push query writes nowhere, which is a fact rather than a missing value. */
const WRITES_NOWHERE = "nothing — this query is transient";

const COLUMNS: readonly Column<KsqlObjectRow>[] = [
  { id: "id", header: "Query", render: (query) => query.name, width: "18rem" },
  {
    id: "sinks",
    header: "Writes into",
    render: (query) => (query.sinks.length === 0 ? WRITES_NOWHERE : query.sinks.join(", ")),
    width: "16rem",
  },
  {
    id: "statement",
    header: "Statement",
    /* The only description of what a persistent query does. Absent is said in words: a blank cell
       here would read as a query that does nothing. */
    render: (query) => (
      <code class="kui-ksql-queries__sql">
        {query.statement ?? "The server did not report this query's statement."}
      </code>
    ),
  },
];

export function RunningQueries(props: RunningQueriesProps): JSX.Element {
  return (
    <Show when={props.queries.length > 0}>
      <section class="kui-ksql-queries" aria-label="Running queries" data-testid="ksql-queries">
        <h2 class="kui-ksql-queries__title">Running queries</h2>
        <DataTable<KsqlObjectRow>
          caption="Queries this ksqlDB server is running"
          columns={COLUMNS}
          rows={props.queries}
          rowKey={(query) => query.name}
          testId="ksql-queries-table"
        />
      </section>
    </Show>
  );
}

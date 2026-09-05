import type { JSX } from "@solidjs/web";
import { createSignal } from "solid-js";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, within } from "storybook/test";
import { ThresholdValue } from "./ThresholdValue.jsx";
import { Button } from "./Button.jsx";
import { DataTable, type Column, type DataTableProps, type Sort } from "./DataTable.jsx";
import { EmptyState, Missing } from "./EmptyState.jsx";
import { StatusPill } from "./StatusPill.jsx";
import {
  CONSUMER_GROUPS,
  EXTREME_GROUP,
  UNREADABLE_GROUP,
  type ConsumerGroup,
} from "./listFixtures.js";

/**
 * The consumer-group table from screenshot `04`, and every state it can be in.
 *
 * Six of the stories below are states a running product only reaches when something is wrong or
 * unusual — loading, four kinds of empty, a row nobody would type by hand. Those are the ones this
 * file exists for. Every defect this table has shipped was in a state that the happy path never
 * showed the person building it.
 */

/** The chip in the STATE column. Kafka's states mapped once, here, so six agents do not each
 * invent a mapping (design spec §4.17). */
function stateChip(state: ConsumerGroup["state"]) {
  switch (state) {
    case "Stable":
      return <StatusPill tone="success">Stable</StatusPill>;
    case "Rebalancing":
      return <StatusPill tone="warning">Rebalancing</StatusPill>;
    case "Empty":
      return <StatusPill tone="neutral">Empty</StatusPill>;
    case "Dead":
      return <StatusPill tone="danger">Dead</StatusPill>;
    case "Unknown":
      // Kafka *reports* Unknown. It is a state, not an absence.
      return <StatusPill tone="neutral">Unknown</StatusPill>;
    case "unreadable":
      // We could not ask. Drawing this as `Unknown` would hide an outage behind a state the broker
      // never reported (§4.17).
      return <StatusPill tone="neutral">—</StatusPill>;
  }
}

/**
 * Lag, at three levels and not five.
 *
 * An operator scanning a column has to sort each cell into "fine", "look at this" and "act on
 * this" in the time it takes to scroll past. A scale with more steps reads as a gradient, which is
 * to say as nothing. Zero recedes to the subtle colour — it is the *absence* of lag — and stays a
 * digit, so it is still distinguishable from the dash that means "we could not read it".
 */
function lagCell(lag: number | null) {
  if (lag === null) return <Missing />;
  /*
   * `ThresholdValue`, not an inline colour.
   *
   * This used to paint `var(--kui-color-warning)` onto a bare span, which looked identical and was
   * not the same thing: the table's refresh dim then took it to 3.99:1, because the exemption that
   * keeps state-carrying colours legible while a table reloads is keyed on the component's class.
   * A story that reimplements a component rather than using it is also a story that stops testing
   * the component — which is how a contrast rule can pass everywhere it is applied and still fail
   * on screen.
   */
  return (
    <ThresholdValue
      value={lag.toLocaleString("en-GB")}
      level={lag >= 1000 ? "warning" : "normal"}
      announcement={() => "above the lag threshold"}
    />
  );
}

const columns: readonly Column<ConsumerGroup>[] = [
  {
    id: "groupId",
    header: "Group id",
    sortable: true,
    render: (group) => <span class="kui-table__cell-strong">{group.groupId}</span>,
  },
  { id: "state", header: "State", render: (group) => stateChip(group.state) },
  { id: "members", header: "Members", align: "numeric", sortable: true, render: (g) => g.members },
  { id: "topics", header: "Topics", align: "numeric", render: (g) => g.topics },
  {
    id: "coordinator",
    header: "Coordinator",
    render: (g) =>
      g.coordinator === null ? (
        <Missing />
      ) : (
        <span style={{ "font-family": "var(--kui-font-family-mono)" }}>{g.coordinator}</span>
      ),
  },
  { id: "lag", header: "Lag", align: "numeric", sortable: true, render: (g) => lagCell(g.lag) },
];

/* `Meta` is parameterised by the *args*, not by `typeof DataTable`.
 *
 * `DataTable` is generic in its row type, and `satisfies Meta<typeof DataTable<ConsumerGroup>>`
 * collapses that parameter to `unknown` — every story's args then have to satisfy
 * `Column<unknown>[]`, which nothing real does. Naming the props type keeps `Row` bound to
 * `ConsumerGroup` all the way through `StoryObj<typeof meta>`, so a story with the wrong column
 * type is a type error rather than a runtime surprise. */
const meta: Meta<DataTableProps<ConsumerGroup>> = {
  title: "Kernel/DataTable",
  component: DataTable as (props: DataTableProps<ConsumerGroup>) => JSX.Element,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof meta>;

const base = {
  columns,
  rows: CONSUMER_GROUPS,
  rowKey: (group: ConsumerGroup) => group.groupId,
  caption: "Consumer groups on prod-kyiv-01",
};

/** The design: five groups, one rebalancing, one lag figure large enough to be worth colouring. */
export const AsDesigned: Story = { args: base };

/** Rows are controls. The whole row is the hit target, it takes a pointer cursor, and it is in the
 * tab order with a focus ring — not just a `click` handler that a keyboard cannot reach. */
export const ClickableRows: Story = {
  args: { ...base, onRowClick: () => {} },
};

/**
 * The row-as-control, proved from the keyboard.
 *
 * Tab to the first row and press Enter. A row that only answers to a mouse passes every visual
 * review and fails for everybody who does not use one.
 */
export const RowActivatesFromTheKeyboard: Story = {
  render: () => {
    const [lastActivated, setLastActivated] = createSignal("nothing yet");
    return (
      <>
        <DataTable
          {...base}
          onRowClick={(group) => setLastActivated(group.groupId)}
          testId="table"
        />
        <p data-testid="activated" style={{ "margin-top": "var(--kui-space-4)" }}>
          Activated: {lastActivated()}
        </p>
      </>
    );
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const rows = canvas.getAllByRole("row").slice(1);
    const first = rows[0];
    if (first === undefined) throw new Error("no rows rendered");
    first.focus();
    await userEvent.keyboard("{Enter}");
    await expect(canvas.getByTestId("activated")).toHaveTextContent("payments-processor");
  },
};

/**
 * Sorting, with the third state.
 *
 * Click the same header three times: ascending, descending, unsorted. Without the third click
 * there is no way back to the order the server returned, which for a list of brokers is broker id
 * and is not reachable by sorting on any column.
 */
export const Sortable: Story = {
  render: () => {
    const [sort, setSort] = createSignal<Sort | null>({ columnId: "lag", order: "desc" });
    return (
      <DataTable
        {...base}
        rows={[...CONSUMER_GROUPS].sort((a, b) => {
          const current = sort();
          if (current === null) return 0;
          const direction = current.order === "asc" ? 1 : -1;
          if (current.columnId === "lag") return ((a.lag ?? -1) - (b.lag ?? -1)) * direction;
          if (current.columnId === "members") return (a.members - b.members) * direction;
          return a.groupId.localeCompare(b.groupId) * direction;
        })}
        sort={sort()}
        onSortChange={setSort}
      />
    );
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const header = canvas.getByRole("columnheader", { name: /group id/i });
    await expect(header).toHaveAttribute("aria-sort", "none");
    await userEvent.click(within(header).getByRole("button"));
    await expect(header).toHaveAttribute("aria-sort", "ascending");
    await userEvent.click(within(header).getByRole("button"));
    await expect(header).toHaveAttribute("aria-sort", "descending");
    await userEvent.click(within(header).getByRole("button"));
    await expect(header).toHaveAttribute("aria-sort", "none");
  },
};

/**
 * Selection, with the product's own checkbox.
 *
 * Select one row and the header checkbox goes *mixed*, not unchecked. A header that showed
 * unchecked while three of twenty rows were selected would be lying about what is underneath it,
 * and clicking it would then select everything rather than clear the three.
 */
export const Selection: Story = {
  render: () => {
    const [selected, setSelected] = createSignal<ReadonlySet<string>>(new Set(["clickstream-etl"]));
    return (
      <DataTable
        {...base}
        selection={{
          selectedKeys: selected(),
          onChange: setSelected,
          rowLabel: (key) => `consumer group ${key}`,
        }}
      />
    );
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const all = canvas.getByTestId("select-all");
    await expect(all).toBePartiallyChecked();
    await userEvent.click(all);
    await expect(all).toBeChecked();
  },
};

/** Nothing selected, so the header checkbox is plainly unchecked and the drawn box is visible as
 * an empty box — the state this project once shipped as nothing at all. */
export const SelectionEmpty: Story = {
  args: {
    ...base,
    selection: { selectedKeys: new Set<string>(), onChange: () => {} },
  },
};

/** Everything selected: the header is checked, every row is filled with the selected colour — the
 * same colour as the selected navigation item, because it is the same idea. */
export const SelectionAll: Story = {
  args: {
    ...base,
    selection: {
      selectedKeys: new Set(CONSUMER_GROUPS.map((g) => g.groupId)),
      onChange: () => {},
    },
  },
};

/** The first fetch: six placeholder rows at the real row height, so nothing resizes when the data
 * lands. Never a spinner in the middle of the panel — that moves the layout twice. */
export const LoadingFirstFetch: Story = { args: { ...base, rows: [], loading: true } };

/** A refresh, with rows already on screen. The rows that are there stay and dim. Replacing them
 * would collapse the table, jump the page, and jump it back a moment later. */
export const LoadingRefresh: Story = { args: { ...base, loading: true } };

/** Nothing yet, and that is normal. The header stays, so the columns still say what the table
 * would have held. */
export const EmptyNothingYet: Story = {
  args: {
    ...base,
    rows: [],
    empty: (
      <EmptyState
        kind="empty"
        title="No consumer groups yet."
        description="A group appears here the first time something consumes from this cluster."
      />
    ),
  },
};

/** Filtered to nothing. Different words and an action, and never the copy above — that would tell
 * somebody their cluster is empty when their search box has eight characters in it. */
export const EmptyFilteredOut: Story = {
  args: {
    ...base,
    rows: [],
    empty: (
      <EmptyState
        kind="filtered"
        title="Nothing matched “payments”."
        description="Five groups exist on this cluster; none of their names contain that."
        action={<Button variant="secondary">Clear filter</Button>}
      />
    ),
  },
};

/** The request failed. The header stays, the code is on screen, and there is a Retry. */
export const Unavailable: Story = {
  args: {
    ...base,
    rows: [],
    empty: (
      <EmptyState
        kind="unavailable"
        title="Consumer group data is unavailable."
        description="The consumer service is not responding."
        code="UPSTREAM_UNAVAILABLE"
        action={<Button variant="secondary">Retry</Button>}
      />
    ),
  },
};

/** Refused. Same shape, a lock, and the panel is never hidden. */
export const Forbidden: Story = {
  args: {
    ...base,
    rows: [],
    empty: (
      <EmptyState
        kind="forbidden"
        title="You do not have permission to read consumer groups on this cluster."
        description="Ask a cluster administrator for the ConsumerGroup:Describe permission."
        code="FORBIDDEN"
      />
    ),
  },
};

/**
 * A group whose state and lag could not be read.
 *
 * A dash chip, not `Unknown`, and a dash in the lag column, not `0`. Kafka reports `Unknown` as a
 * state; conflating "the broker said unknown" with "we could not ask" hides an outage, and drawing
 * an unreadable lag as zero reports a broken consumer as a healthy one.
 */
export const RowWithUnreadableValues: Story = {
  args: { ...base, rows: [...CONSUMER_GROUPS, UNREADABLE_GROUP] },
};

/**
 * The extreme row: a 190-character group id, four-digit counts, a lag past two billion.
 *
 * What to look at is the *page*, not the table. The table's box grows a horizontal scrollbar and
 * the document does not: wide content scrolls inside its own box. This is the defect that once
 * dragged the whole shell sideways — the drawer slid off the left while the reader chased a column
 * off the right.
 */
export const ExtremeRow: Story = {
  args: { ...base, rows: [...CONSUMER_GROUPS, EXTREME_GROUP] },
};

/** The same content in a 360px column. The table scrolls; the story's frame does not. */
export const NarrowWindow: Story = {
  args: { ...base, rows: [...CONSUMER_GROUPS, EXTREME_GROUP] },
  decorators: [(Story) => <div style={{ width: "360px", overflow: "hidden" }}>{Story()}</div>],
};

/** One column, one row — the smallest table that is still a table. */
export const Minimal: Story = {
  args: {
    columns: [{ id: "groupId", header: "Group id", render: (g: ConsumerGroup) => g.groupId }],
    rows: [CONSUMER_GROUPS[0] as ConsumerGroup],
    rowKey: (g: ConsumerGroup) => g.groupId,
    caption: "One group",
  },
};

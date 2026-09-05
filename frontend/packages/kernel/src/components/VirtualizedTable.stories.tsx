import type { JSX } from "@solidjs/web";
import { createSignal } from "solid-js";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, waitFor, within } from "storybook/test";
import { Button } from "./Button.jsx";
import type { Column, Sort } from "./DataTable.jsx";
import { EmptyState } from "./EmptyState.jsx";
import { manyTopics } from "./listFixtures.js";
import { VirtualizedTable, type VirtualizedTableProps } from "./VirtualizedTable.jsx";

/**
 * The windowed table, in the situations that have broken it.
 *
 * Two stories here are the whole reason this file is long. `GrowsWithItsContainer` is the
 * ResizeObserver: a container that is laid out after mount must fill with rows, and measuring once
 * drew five rows for a twelve-partition topic. `ShortListInATallBox` is the twelve-partition topic
 * itself. Everything else is ordinary, and ordinary is not where this component fails.
 */

interface Topic {
  readonly name: string;
  readonly partitions: number;
  readonly size: number;
}

const columns: readonly Column<Topic>[] = [
  {
    id: "name",
    header: "Topic",
    sortable: true,
    render: (topic) => topic.name,
  },
  {
    id: "partitions",
    header: "Partitions",
    align: "numeric",
    width: "10rem",
    sortable: true,
    render: (topic) => topic.partitions,
  },
  {
    id: "size",
    header: "Size",
    align: "numeric",
    width: "10rem",
    render: (topic) => `${(topic.size / 1000).toFixed(1)} kB`,
  },
];

/* Parameterised by the args, not by `typeof VirtualizedTable` — see the note in
 * `DataTable.stories.tsx` on why a generic component loses its type parameter through `satisfies`. */
const meta: Meta<VirtualizedTableProps<Topic>> = {
  title: "Kernel/VirtualizedTable",
  component: VirtualizedTable as (props: VirtualizedTableProps<Topic>) => JSX.Element,
  parameters: { layout: "padded" },
  decorators: [
    // A windowed table takes its height from its parent's layout. Giving every story an explicit
    // box makes the height a property of the story rather than of the browser window, so two
    // stories can be compared and a screenshot means something.
    (Story) => <div style={{ height: "480px", display: "flex" }}>{Story()}</div>,
  ],
};

export default meta;
type Story = StoryObj<typeof meta>;

const base = {
  columns,
  rowKey: (topic: Topic) => topic.name,
  caption: "Topics on prod-kyiv-01",
};

/** Ten thousand topics. Scroll it: the scrollbar is the length of the whole list, the document
 * holds about twenty rows, and no frame drops. */
export const TenThousandRows: Story = { args: { ...base, rows: manyTopics(10_000) } };

/** A hundred thousand. The arithmetic is division, so this costs exactly what the last story
 * cost — which is the point of a fixed row height. */
export const HundredThousandRows: Story = { args: { ...base, rows: manyTopics(100_000) } };

/**
 * The twelve-partition topic: a short list in a tall box.
 *
 * All twelve rows are drawn, the list does not scroll, and there is no blank strip under them.
 * This story is here because the failure it guards against — five rows drawn, seven missing, a
 * scrollbar that does not scroll — looked exactly like a topic with five partitions, and nobody
 * could tell from the screen that anything was wrong.
 */
export const ShortListInATallBox: Story = { args: { ...base, rows: manyTopics(12) } };

/**
 * The container is laid out *after* the component mounts, and then grows again.
 *
 * Press the button and the box goes from 120px to 480px. The table has to fill with rows. If it
 * measured once at mount it will stay at the two or three rows that fitted the small box, under a
 * scrollbar that will not move, and the bottom three-quarters of the panel will be blank.
 */
export const GrowsWithItsContainer: StoryObj = {
  decorators: [(Story) => <>{Story()}</>],
  render: () => {
    const [tall, setTall] = createSignal(false);
    const rows = manyTopics(200);
    return (
      <div style={{ display: "flex", "flex-direction": "column", gap: "var(--kui-space-4)" }}>
        <Button onClick={() => setTall((value) => !value)}>
          {tall() ? "Shrink the container" : "Grow the container"}
        </Button>
        <div
          data-testid="box"
          style={{
            height: tall() ? "480px" : "120px",
            display: "flex",
            transition: "height 200ms ease",
          }}
        >
          <VirtualizedTable {...base} rows={rows} testId="vtable" />
        </div>
      </div>
    );
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const countRows = () =>
      canvasElement.querySelectorAll("tbody tr:not(.kui-vtable__spacer)").length;

    const small = countRows();
    await userEvent.click(canvas.getByRole("button", { name: /grow/i }));
    // The observer fires on the browser's own schedule, and Solid flushes on a microtask, so the
    // assertion waits rather than reading in the same tick — which is exactly the mistake the
    // component's own comment warns about.
    // `waitFor` rather than a read straight after the click: the observer fires on the browser's
    // own schedule and Solid flushes on a microtask, so reading in the same tick would see the
    // previous frame's window — exactly the mistake the component's own comment warns about.
    await waitFor(() => expect(countRows()).toBeGreaterThan(small), { timeout: 3000 });
  },
};

/**
 * Compact, forced.
 *
 * The rows are 36px rather than 48px, and — the part worth checking — *more of them* are drawn,
 * because the arithmetic moved with the stylesheet. If the two ever disagree, this story shows
 * short rows with a blank strip below them.
 */
export const Compact: Story = { args: { ...base, rows: manyTopics(500), compact: true } };

/** The comfortable default, for comparison with the story above. The screenshots that define this
 * design are compact; the product's default is this one. */
export const Comfortable: Story = { args: { ...base, rows: manyTopics(500), compact: false } };

/**
 * Keyboard navigation, and the roving tabindex.
 *
 * Tab once to enter the table, then Down, Page Down, End, Home. Exactly one row is in the tab
 * order at a time — Tab stepping through ten thousand rows is not navigation, it is a trap.
 */
export const KeyboardNavigation: Story = {
  args: { ...base, rows: manyTopics(500) },
  play: async ({ canvasElement }) => {
    const rows = () => canvasElement.querySelectorAll<HTMLElement>(".kui-vtable__row");
    const tabbable = () =>
      Array.from(rows()).filter((row) => row.getAttribute("tabindex") === "0");

    await expect(tabbable()).toHaveLength(1);

    const first = rows()[0];
    if (first === undefined) throw new Error("no rows rendered");
    first.focus();
    await userEvent.keyboard("{ArrowDown}{ArrowDown}");
    await waitFor(() =>
      expect(document.activeElement?.getAttribute("aria-rowindex")).toBe("3"),
    );

    await userEvent.keyboard("{End}");
    await waitFor(() =>
      expect(document.activeElement?.getAttribute("aria-rowindex")).toBe("500"),
    );
  },
};

/**
 * What a screen reader is told about the size of the list.
 *
 * `aria-rowcount` is the length of the whole list and each row's `aria-rowindex` is its position
 * in it. Without those, a reader on a list of ten thousand is told "row 3 of 12", because twelve
 * rows is all that is in the document — the single most misleading thing a windowed table can say.
 */
export const AnnouncesTheWholeList: Story = {
  args: { ...base, rows: manyTopics(10_000) },
  play: async ({ canvasElement }) => {
    const table = canvasElement.querySelector("table");
    await expect(table?.getAttribute("aria-rowcount")).toBe("10000");
    const firstRow = canvasElement.querySelector(".kui-vtable__row");
    await expect(firstRow?.getAttribute("aria-rowindex")).toBe("1");
    // The spacers must not be announced: two empty rows either side of every window would be
    // worse than useless.
    for (const spacer of canvasElement.querySelectorAll(".kui-vtable__spacer")) {
      await expect(spacer.getAttribute("aria-hidden")).toBe("true");
    }
  },
};

/** Sortable headers, in the windowed table. The rows are not re-sorted here: the screens that use
 * this component sort on the server, and a table that quietly re-sorted its own page would show
 * the right rows in an order no page boundary matches. */
export const Sortable: Story = {
  render: () => {
    const [sort, setSort] = createSignal<Sort | null>({ columnId: "name", order: "asc" });
    return (
      <VirtualizedTable {...base} rows={manyTopics(2000)} sort={sort()} onSortChange={setSort} />
    );
  },
};

/** Empty. The header stays and the columns still say what the table would have held. */
export const Empty: Story = { args: { ...base, rows: [] } };

/** Empty because the request failed. */
export const Unavailable: Story = {
  args: {
    ...base,
    rows: [],
    empty: (
      <EmptyState
        kind="unavailable"
        title="Topic data is unavailable."
        description="The topic service is not responding."
        code="UPSTREAM_UNAVAILABLE"
        action={<Button variant="secondary">Retry</Button>}
      />
    ),
  },
};

/** Exactly one row. The trailing spacer must be zero-height, not one row tall — an off-by-one
 * there shows as a blank row under a one-row list. */
export const SingleRow: Story = { args: { ...base, rows: manyTopics(1) } };

/**
 * A box 60px tall — shorter than two rows.
 *
 * The window is one row plus overscan and the table still scrolls. Page Down still moves, because
 * a page is a viewport's worth of rows *and at least one*; without that floor the key would appear
 * to do nothing in a container this short.
 */
export const SmallestWindow: Story = {
  args: { ...base, rows: manyTopics(500) },
  decorators: [(Story) => <div style={{ height: "60px", display: "flex" }}>{Story()}</div>],
};

/**
 * The widest content, in the narrowest column.
 *
 * `table-layout: fixed` keeps the columns from being re-measured as rows enter and leave the
 * window — with automatic layout, scrolling into a region of longer names silently re-flows every
 * column mid-scroll — and the long names truncate rather than wrapping, because a wrapped row is
 * taller than the arithmetic expects and every row below it is then drawn in the wrong place.
 */
export const LongNamesInANarrowColumn: Story = {
  args: { ...base, rows: manyTopics(500) },
  decorators: [
    (Story) => <div style={{ height: "480px", width: "420px", display: "flex" }}>{Story()}</div>,
  ],
};

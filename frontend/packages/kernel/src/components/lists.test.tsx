/**
 * Rendering, interaction and accessibility for the list surfaces: the plain table, the windowed
 * table, the record row and its expansion, and the header chip.
 *
 * Every case below is attached either to a statement in `.agent/design/SPEC.md` or to a defect this
 * project has already paid for. Nothing here asserts a colour, a size or a position: jsdom has no
 * layout engine, so a test that did would be asserting numbers jsdom invented. Those are judged by
 * looking at the stories against the design screenshots.
 *
 * The windowed table is driven through its `viewportHeight` prop rather than by measuring itself.
 * That prop exists for exactly this reason — jsdom reports every element as zero pixels tall, so a
 * component that could only measure itself would be untestable outside a real browser — and the
 * measuring half is covered by the `GrowsWithItsContainer` story, which runs in one.
 */

import userEvent from "@testing-library/user-event";
import { createSignal, flush } from "solid-js";
import { afterEach, describe, expect, it, vi } from "vitest";

import { DataTable, nextSort, type Column, type Sort } from "./DataTable.jsx";
import { EmptyState, Missing, Skeleton } from "./EmptyState.jsx";
import { HeaderChip } from "./HeaderChip.jsx";
import { RecordList, RecordRow } from "./RecordRow.jsx";
import { VirtualizedTable } from "./VirtualizedTable.jsx";
import {
  CONSUMER_GROUPS,
  EXTREME_GROUP,
  EXTREME_RECORD,
  NOW,
  RECORDS,
  TOMBSTONE,
  TOO_LARGE,
  UNDECODABLE,
  manyTopics,
  type ConsumerGroup,
} from "./listFixtures.js";
import { describeViolations, findViolations, mount } from "./testing.js";

const disposers: (() => void)[] = [];

function render(component: () => ReturnType<typeof DataTable>) {
  const mounted = mount(component);
  disposers.push(mounted.dispose);
  return mounted.container;
}

afterEach(() => {
  while (disposers.length > 0) disposers.pop()?.();
  document.documentElement.removeAttribute("data-density");
  vi.useRealTimers();
});

const groupColumns: readonly Column<ConsumerGroup>[] = [
  { id: "groupId", header: "Group id", sortable: true, render: (g) => g.groupId },
  { id: "members", header: "Members", align: "numeric", render: (g) => g.members },
  {
    id: "lag",
    header: "Lag",
    align: "numeric",
    sortable: true,
    render: (g) => (g.lag === null ? <Missing /> : g.lag),
  },
];

const tableBase = {
  columns: groupColumns,
  rowKey: (g: ConsumerGroup) => g.groupId,
  caption: "Consumer groups",
};

/* ------------------------------------------------------------------------------------------- */

describe("DataTable", () => {
  it("keeps its header, and its columns, in every empty state", () => {
    // A header with nothing under it and no sentence is the rendering that leaves a reader unable
    // to tell "no groups" from "the request failed". The header must survive; the sentence must
    // say which of the four situations this is.
    for (const empty of [
      <EmptyState kind="empty" title="No consumer groups yet." />,
      <EmptyState kind="filtered" title="Nothing matched “pay”." />,
      <EmptyState kind="unavailable" title="Unavailable." code="UPSTREAM_UNAVAILABLE" />,
      <EmptyState kind="forbidden" title="Not permitted." code="FORBIDDEN" />,
    ]) {
      const container = render(() => <DataTable {...tableBase} rows={[]} empty={empty} />);
      expect(container.querySelectorAll("th")).toHaveLength(3);
      expect(container.querySelector('[role="status"]')?.textContent ?? "").not.toBe("");
    }
  });

  it("announces its emptiness in words rather than by being empty", () => {
    const container = render(() => (
      <DataTable
        {...tableBase}
        rows={[]}
        empty={<EmptyState kind="filtered" title="Nothing matched “payments”." />}
      />
    ));
    // A live region, so a reader who filters a table down to nothing is told so rather than left
    // listening to silence (SPEC §7.9).
    expect(container.querySelector('[role="status"]')?.textContent).toContain("Nothing matched");
  });

  it("does not blank the rows it already has while refreshing", () => {
    // Replacing them with a spinner collapses the table to nothing, jumps the page, and jumps it
    // back a moment later.
    const container = render(() => (
      <DataTable {...tableBase} rows={CONSUMER_GROUPS} loading={true} />
    ));
    expect(container.querySelectorAll(".kui-table__body tr")).toHaveLength(CONSUMER_GROUPS.length);
    expect(container.querySelector("table")?.getAttribute("aria-busy")).toBe("true");
  });

  it("draws placeholder rows, not a spinner, on a first fetch", () => {
    const container = render(() => (
      <DataTable {...tableBase} rows={[]} loading={true} skeletonRows={6} />
    ));
    expect(container.querySelectorAll(".kui-table__row--skeleton")).toHaveLength(6);
    // And no empty state: "nothing here" and "not yet" are different statements, and a table that
    // said "no consumer groups" for the half second before the data landed would be lying.
    expect(container.querySelector('[role="status"]')).toBeNull();
  });

  it("cycles a sortable column through ascending, descending and back to unsorted", () => {
    // The third state is the one usually left out, and without it there is no way back to the
    // order the server returned.
    expect(nextSort(null, "lag")).toEqual({ columnId: "lag", order: "asc" });
    expect(nextSort({ columnId: "lag", order: "asc" }, "lag")).toEqual({
      columnId: "lag",
      order: "desc",
    });
    expect(nextSort({ columnId: "lag", order: "desc" }, "lag")).toBeNull();
    // Moving to a different column starts that column ascending rather than inheriting a direction.
    expect(nextSort({ columnId: "lag", order: "desc" }, "groupId")).toEqual({
      columnId: "groupId",
      order: "asc",
    });
  });

  it("puts the sort control in a button inside the header, and states the direction", async () => {
    const [sort, setSort] = createSignal<Sort | null>(null);
    const container = render(() => (
      <DataTable {...tableBase} rows={CONSUMER_GROUPS} sort={sort()} onSortChange={setSort} />
    ));
    const header = container.querySelectorAll("th")[0] as HTMLTableCellElement;
    expect(header.getAttribute("aria-sort")).toBe("none");

    // A clickable `<th>` is invisible to the keyboard, so the control is a real button inside it.
    const button = header.querySelector("button");
    expect(button).not.toBeNull();
    await userEvent.click(button as HTMLButtonElement);
    flush();
    expect(header.getAttribute("aria-sort")).toBe("ascending");
  });

  it("renders a sortable column as plain text when there is nowhere to send the sort", () => {
    // A control that looks live and is not is worse than no control.
    const container = render(() => <DataTable {...tableBase} rows={CONSUMER_GROUPS} />);
    expect(container.querySelectorAll("th button")).toHaveLength(0);
  });

  it("makes the whole row the hit target, and reachable from the keyboard", async () => {
    const activated: string[] = [];
    const container = render(() => (
      <DataTable
        {...tableBase}
        rows={CONSUMER_GROUPS}
        onRowClick={(group) => activated.push(group.groupId)}
      />
    ));
    const row = container.querySelector(".kui-table__row--clickable") as HTMLTableRowElement;
    expect(row.getAttribute("tabindex")).toBe("0");

    // A row that only answers to a mouse passes every visual review and fails for everybody who
    // does not use one.
    row.focus();
    await userEvent.keyboard("{Enter}");
    await userEvent.keyboard(" ");
    expect(activated).toEqual(["payments-processor", "payments-processor"]);
  });

  it("gives the header checkbox a mixed state rather than an unchecked one", async () => {
    const [selected, setSelected] = createSignal<ReadonlySet<string>>(new Set(["fraud-detector"]));
    const container = render(() => (
      <DataTable
        {...tableBase}
        rows={CONSUMER_GROUPS}
        selection={{ selectedKeys: selected(), onChange: setSelected }}
      />
    ));
    const all = container.querySelector<HTMLInputElement>('[data-testid="select-all"]');
    expect(all?.indeterminate).toBe(true);
    expect(all?.checked).toBe(false);

    // Clicking a mixed header selects everything, which is only the right behaviour because the
    // header was honest about being mixed in the first place.
    await userEvent.click(all as HTMLInputElement);
    flush();
    expect(selected().size).toBe(CONSUMER_GROUPS.length);
  });

  it("names every row's checkbox after the row, not after its position", () => {
    // "checkbox" and "checkbox" and "checkbox" tells a screen-reader user which control they are
    // on and nothing about what it selects.
    const container = render(() => (
      <DataTable
        {...tableBase}
        rows={CONSUMER_GROUPS}
        selection={{
          selectedKeys: new Set(),
          onChange: () => {},
          rowLabel: (key) => `consumer group ${key}`,
        }}
      />
    ));
    const labels = Array.from(container.querySelectorAll(".kui-table__cell--select")).map(
      (cell) => cell.textContent ?? "",
    );
    expect(labels[0]).toContain("consumer group payments-processor");
  });

  it("uses the product's own checkbox and keeps a real input underneath it", () => {
    // The drawn box is the visible half; the input is the half that has every native behaviour.
    // This project has shipped a checkbox that was perfect to a screen reader and drawn as
    // nothing, so both halves are asserted (SPEC §7.9).
    const container = render(() => (
      <DataTable
        {...tableBase}
        rows={CONSUMER_GROUPS}
        selection={{ selectedKeys: new Set(), onChange: () => {} }}
      />
    ));
    expect(container.querySelectorAll('input[type="checkbox"]').length).toBe(
      CONSUMER_GROUPS.length + 1,
    );
    expect(container.querySelectorAll(".kui-checkbox__box").length).toBe(
      CONSUMER_GROUPS.length + 1,
    );
  });

  it("scrolls inside its own box rather than making the page scroll sideways", () => {
    // The wrapper is what this component returns, and it is the thing that clips. Without it a
    // nine-column table pushes past its column and the whole shell slides sideways with it.
    const container = render(() => (
      <DataTable {...tableBase} rows={[...CONSUMER_GROUPS, EXTREME_GROUP]} />
    ));
    expect(container.firstElementChild?.className).toBe("kui-table-scroll");
  });

  it("has an accessible name, so four tables on a page are not four things called table", () => {
    const container = render(() => <DataTable {...tableBase} rows={CONSUMER_GROUPS} />);
    expect(container.querySelector("caption")?.textContent).toBe("Consumer groups");
  });

  it("draws a dash for an unreadable value and a digit for a zero", () => {
    // Zero lag is a caught-up consumer; unreadable lag is a consumer nobody knows anything about.
    // Drawing the second as the first reports an outage as a healthy cluster.
    const rows = [{ ...(CONSUMER_GROUPS[0] as ConsumerGroup), lag: null }];
    const container = render(() => <DataTable {...tableBase} rows={rows} />);
    expect(container.querySelector(".kui-missing")?.textContent).toBe("—");
    expect(container.textContent).not.toContain("null");
  });

  it("has no axe violations in any of its states", async () => {
    for (const props of [
      { rows: CONSUMER_GROUPS },
      { rows: CONSUMER_GROUPS, onRowClick: () => {} },
      { rows: CONSUMER_GROUPS, selection: { selectedKeys: new Set<string>(), onChange: () => {} } },
      { rows: [] as readonly ConsumerGroup[], loading: true },
      { rows: [] as readonly ConsumerGroup[] },
      { rows: [...CONSUMER_GROUPS, EXTREME_GROUP] },
    ]) {
      const container = render(() => <DataTable {...tableBase} {...props} />);
      const violations = await findViolations(container);
      expect(violations, describeViolations(violations)).toHaveLength(0);
    }
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("VirtualizedTable", () => {
  const topicColumns: readonly Column<{ name: string; partitions: number; size: number }>[] = [
    { id: "name", header: "Topic", sortable: true, render: (t) => t.name },
    { id: "partitions", header: "Partitions", align: "numeric", render: (t) => t.partitions },
  ];
  const vtableBase = {
    columns: topicColumns,
    rowKey: (t: { name: string }) => t.name,
    caption: "Topics",
  };

  it("keeps only the visible rows plus overscan in the document", () => {
    const container = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(10_000)} viewportHeight={480} />
    ));
    const rows = container.querySelectorAll(".kui-vtable__row");
    // Ten rows fit in 480px at 48px, plus three overscan below and none above at the top.
    expect(rows.length).toBeLessThan(30);
    expect(rows.length).toBeGreaterThan(9);
  });

  it("tells a screen reader the size of the whole list, not the size of the window", () => {
    // "row 3 of 12" on a list of ten thousand is the single most misleading thing a windowed table
    // can say.
    const container = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(10_000)} viewportHeight={480} />
    ));
    expect(container.querySelector("table")?.getAttribute("aria-rowcount")).toBe("10000");
    expect(container.querySelector(".kui-vtable__row")?.getAttribute("aria-rowindex")).toBe("1");
  });

  it("hides the spacers from assistive technology", () => {
    const container = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(1000)} viewportHeight={480} />
    ));
    const spacers = container.querySelectorAll(".kui-vtable__spacer");
    expect(spacers.length).toBe(2);
    for (const spacer of spacers) {
      expect(spacer.getAttribute("aria-hidden")).toBe("true");
      expect(spacer.getAttribute("role")).toBe("presentation");
    }
  });

  it("draws every row of a short list in a tall box", () => {
    // The twelve-partition topic. Five rows and a blank area under a scrollbar that will not
    // scroll is the defect this component was fixed for.
    const container = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(12)} viewportHeight={480} />
    ));
    expect(container.querySelectorAll(".kui-vtable__row")).toHaveLength(12);
  });

  it("publishes one row height to the stylesheet and uses the same one in its arithmetic", () => {
    // Two literals is the defect: the CSS draws 36px rows, the arithmetic believes 48, and the
    // bottom of the list is blank with nothing visibly wrong on either side taken alone.
    const comfortable = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(500)} viewportHeight={480} />
    ));
    const compact = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(500)} viewportHeight={480} compact />
    ));

    const heightOf = (container: HTMLElement) =>
      (container.querySelector(".kui-vtable") as HTMLElement).style.getPropertyValue(
        "--kui-vtable-row-height",
      );
    expect(heightOf(comfortable)).toBe("48px");
    expect(heightOf(compact)).toBe("36px");

    // And the compact table, from the same call, renders *more* rows into the same box.
    expect(compact.querySelectorAll(".kui-vtable__row").length).toBeGreaterThan(
      comfortable.querySelectorAll(".kui-vtable__row").length,
    );
  });

  it("follows the density attribute on the document rather than being told twice", () => {
    document.documentElement.setAttribute("data-density", "compact");
    const container = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(500)} viewportHeight={480} />
    ));
    expect(
      (container.querySelector(".kui-vtable") as HTMLElement).style.getPropertyValue(
        "--kui-vtable-row-height",
      ),
    ).toBe("36px");
  });

  it("keeps exactly one row in the tab order", async () => {
    // Tab stepping through ten thousand rows is not navigation, it is a trap.
    const container = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(500)} viewportHeight={480} />
    ));
    const tabbable = () =>
      Array.from(container.querySelectorAll(".kui-vtable__row")).filter(
        (row) => row.getAttribute("tabindex") === "0",
      );
    expect(tabbable()).toHaveLength(1);

    const first = container.querySelector(".kui-vtable__row") as HTMLElement;
    first.focus();
    await userEvent.keyboard("{ArrowDown}");
    flush();
    expect(tabbable()).toHaveLength(1);
    expect(tabbable()[0]?.getAttribute("aria-rowindex")).toBe("2");
  });

  it("moves a viewport's worth on Page Down, and at least one row in a very short box", async () => {
    // Without the floor of one, Page Down appears to ignore the key in a container shorter than a
    // row, which is a container the design does allow.
    const container = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(500)} viewportHeight={20} />
    ));
    const first = container.querySelector(".kui-vtable__row") as HTMLElement;
    first.focus();
    await userEvent.keyboard("{PageDown}");
    flush();
    const focused = Array.from(container.querySelectorAll(".kui-vtable__row")).find(
      (row) => row.getAttribute("tabindex") === "0",
    );
    expect(focused?.getAttribute("aria-rowindex")).toBe("2");
  });

  it("goes to the ends on Home and End", async () => {
    const container = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(500)} viewportHeight={480} />
    ));
    (container.querySelector(".kui-vtable__row") as HTMLElement).focus();
    await userEvent.keyboard("{End}");
    flush();
    const last = Array.from(container.querySelectorAll(".kui-vtable__row")).find(
      (row) => row.getAttribute("tabindex") === "0",
    );
    expect(last?.getAttribute("aria-rowindex")).toBe("500");
  });

  it("shows an empty state, with the header above it, when there is nothing", () => {
    const container = render(() => (
      <VirtualizedTable {...vtableBase} rows={[]} viewportHeight={480} />
    ));
    expect(container.querySelectorAll("th")).toHaveLength(2);
    expect(container.querySelector('[role="status"]')).not.toBeNull();
  });

  it("has no axe violations", async () => {
    const container = render(() => (
      <VirtualizedTable {...vtableBase} rows={manyTopics(1000)} viewportHeight={480} />
    ));
    const violations = await findViolations(container);
    expect(violations, describeViolations(violations)).toHaveLength(0);
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("RecordRow", () => {
  const first = RECORDS[0] as (typeof RECORDS)[number];

  it("makes the whole row the control, with a chevron as well", async () => {
    // Both halves. A row that expands with no affordance is a row nobody discovers; a chevron that
    // is the only hit target is a 16px target in a 36px row.
    const container = render(() => (
      <RecordList label="Records">
        <RecordRow record={first} now={NOW} />
      </RecordList>
    ));
    const summary = container.querySelector("button") as HTMLButtonElement;
    expect(summary.getAttribute("aria-expanded")).toBe("false");
    expect(container.querySelector(".kui-record__chevron")).not.toBeNull();

    await userEvent.click(summary);
    flush();
    expect(summary.getAttribute("aria-expanded")).toBe("true");
    // `aria-controls` points at the region that actually appeared, so a screen reader can move to
    // it rather than hunting for what the button just did.
    expect(container.querySelector(`#${CSS.escape(summary.getAttribute("aria-controls") ?? "")}`))
      .not.toBeNull();
  });

  it("opens from the keyboard, because it is a real button", async () => {
    const container = render(() => (
      <RecordList label="Records">
        <RecordRow record={first} now={NOW} />
      </RecordList>
    ));
    const summary = container.querySelector("button") as HTMLButtonElement;
    summary.focus();
    await userEvent.keyboard("{Enter}");
    flush();
    expect(summary.getAttribute("aria-expanded")).toBe("true");
  });

  it("expands in place, as a sibling, rather than as an overlay", () => {
    // Somebody comparing two payloads must not lose the neighbouring records to a layer on top.
    const container = render(() => (
      <RecordList label="Records">
        <RecordRow record={first} now={NOW} initiallyExpanded />
      </RecordList>
    ));
    const card = container.querySelector(".kui-record") as HTMLElement;
    expect(card.querySelector(".kui-record__body")?.parentElement).toBe(card);
  });

  it("names a null key rather than leaving a bare dash", () => {
    // A null key in a compacted topic *is* the deletion: "no key" and "this record deletes that
    // key" are different statements.
    const container = render(() => (
      <RecordList label="Records">
        <RecordRow record={TOMBSTONE} now={NOW} />
      </RecordList>
    ));
    expect(container.textContent).toContain("(tombstone)");
  });

  it("never draws an empty row for a payload it could not show", () => {
    // Three of these have shipped as blank rows, and a blank row is indistinguishable from a
    // record holding the empty string.
    for (const record of [TOMBSTONE, TOO_LARGE, UNDECODABLE]) {
      const container = render(() => (
        <RecordList label="Records">
          <RecordRow record={record} now={NOW} />
        </RecordList>
      ));
      const preview = container.querySelector(".kui-record__value-preview");
      expect(preview?.textContent ?? "").not.toBe("");
    }
  });

  it("says why a payload would not deserialize, and offers the bytes", () => {
    const container = render(() => (
      <RecordList label="Records">
        <RecordRow record={UNDECODABLE} now={NOW} initiallyExpanded />
      </RecordList>
    ));
    expect(container.textContent).toContain("Avro schema 42 not found");
    expect(container.textContent).toContain("RAW BYTES");
  });

  it("keeps the HEADERS label when there are none", () => {
    // Dropping the label entirely makes the reader wonder whether the product looked.
    const noHeaders = { ...first, headers: [] };
    const container = render(() => (
      <RecordList label="Records">
        <RecordRow record={noHeaders} now={NOW} initiallyExpanded />
      </RecordList>
    ));
    expect(container.textContent).toContain("HEADERS");
    expect(container.textContent).toContain("— none");
  });

  it("says which clock the timestamp came from", () => {
    // A topic on LogAppendTime shows records in an order they were not produced in, and an
    // operator debugging ordering who does not know which clock they are reading will draw the
    // wrong conclusion from it.
    const container = render(() => (
      <RecordList label="Records">
        <RecordRow record={{ ...first, timestampType: "LogAppendTime" }} now={NOW} initiallyExpanded />
      </RecordList>
    ));
    expect(container.textContent).toContain("LogAppendTime");
  });

  it("grows the fact grid to five boxes when a schema is attached", () => {
    const container = render(() => (
      <RecordList label="Records">
        <RecordRow record={EXTREME_RECORD} now={NOW} initiallyExpanded />
      </RecordList>
    ));
    expect(container.querySelectorAll(".kui-record__fact")).toHaveLength(5);
  });

  it("keeps every digit of an offset past 2^53", () => {
    const container = render(() => (
      <RecordList label="Records">
        <RecordRow record={EXTREME_RECORD} now={NOW} />
      </RecordList>
    ));
    expect(container.textContent).toContain("9,223,372,036,854,775,806");
  });

  it("is a real list, so a screen reader can say how long it is", () => {
    const container = render(() => (
      <RecordList label="Records in orders.payments.v2">
        <RecordRow record={first} now={NOW} />
      </RecordList>
    ));
    const list = container.querySelector("ul");
    expect(list?.getAttribute("aria-label")).toBe("Records in orders.payments.v2");
    expect(list?.querySelectorAll(":scope > li")).toHaveLength(1);
  });

  it("has no axe violations, open or closed, ordinary or broken", async () => {
    for (const record of [first, TOMBSTONE, UNDECODABLE, TOO_LARGE, EXTREME_RECORD]) {
      for (const open of [false, true]) {
        const container = render(() => (
          <RecordList label="Records">
            <RecordRow record={record} now={NOW} initiallyExpanded={open} />
          </RecordList>
        ));
        const violations = await findViolations(container);
        expect(violations, describeViolations(violations)).toHaveLength(0);
      }
    }
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("HeaderChip", () => {
  it("distinguishes a header sent empty from one that was not sent", () => {
    // A producer that sets correlation-id to the empty string has a bug; one that never sets it
    // has a different bug. Drawing the first as the second hides one of them.
    const container = render(() => <HeaderChip name="x-empty" value={null} />);
    expect(container.textContent).toContain("(empty)");
    expect(container.querySelector(".kui-header-chip--empty")).not.toBeNull();
  });

  it("marks binary bytes in words, not only in colour", () => {
    const container = render(() => (
      <HeaderChip name="x-signature" value="0x9f2a1c" binary={true} />
    ));
    expect(container.textContent).toContain("binary");
  });

  it("truncates the visible value but not the accessible one", () => {
    const long = "a".repeat(300);
    const container = render(() => <HeaderChip name="x-trace" value={long} />);
    const chip = container.querySelector("button") as HTMLButtonElement;
    expect(chip.querySelector(".kui-header-chip__value")?.textContent?.length).toBeLessThan(60);
    // The whole value survives in the tooltip and in the name, so nothing is actually lost.
    expect(chip.getAttribute("title")).toBe(long);
    expect(chip.getAttribute("aria-label")).toContain(long);
  });

  it("is a button, named after the action rather than the picture", () => {
    const container = render(() => <HeaderChip name="correlation-id" value="c_18442901" />);
    const chip = container.querySelector("button") as HTMLButtonElement;
    expect(chip.getAttribute("aria-label")).toBe("Copy header correlation-id: c_18442901");
  });

  it("has no axe violations", async () => {
    const container = render(() => (
      <div class="kui-record__headers">
        <HeaderChip name="content-type" value="application/json" />
        <HeaderChip name="x-empty" value={null} />
        <HeaderChip name="x-signature" value="0x9f" binary={true} />
      </div>
    ));
    const violations = await findViolations(container);
    expect(violations, describeViolations(violations)).toHaveLength(0);
  });
});

/* ------------------------------------------------------------------------------------------- */

describe("the three renderings of a value", () => {
  it("draws pending, absent and present differently", () => {
    // §4.0: a dash means "no value", a skeleton means "not yet", a number means the number. Draw
    // any two the same and the reader concludes a group has no coordinator when nobody has asked.
    const container = render(() => (
      <div>
        <Skeleton testId="pending" />
        <Missing />
        <span data-testid="present">0</span>
      </div>
    ));
    expect(container.querySelector('[data-testid="pending"]')?.className).toBe("kui-skeleton");
    expect(container.querySelector(".kui-missing")?.textContent).toBe("—");
    expect(container.querySelector('[data-testid="present"]')?.textContent).toBe("0");
    // The three are three different elements with three different classes; nothing here can be
    // satisfied by rendering the same thing three times.
    expect(container.querySelector(".kui-skeleton")).not.toBe(container.querySelector(".kui-missing"));
  });

  it("hides the placeholder from a screen reader, which is told by aria-busy instead", () => {
    const container = render(() => <Skeleton testId="pending" />);
    expect(container.querySelector('[data-testid="pending"]')?.getAttribute("aria-hidden")).toBe(
      "true",
    );
  });
});

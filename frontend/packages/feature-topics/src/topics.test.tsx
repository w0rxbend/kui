/**
 * The topic list and the topic page frame.
 *
 * The cases here are the ones that are easy to get wrong in a way nobody notices: a dash drawn as a
 * zero, a count that describes a filtered table as if it were the whole cluster, a destructive
 * action that looks like an ordinary one, and a forbidden action that has been hidden rather than
 * explained.
 */

import { describe, expect, test, vi } from "vitest";
import { createSignal, flush } from "solid-js";
import type { JSX } from "@solidjs/web";
import { mount } from "./testing.js";
import {
  DEFAULT_TOPIC_QUERY,
  TopicListPage,
  formatBytes,
  matchCount,
  type TopicListPageProps,
  type TopicListQuery,
} from "./TopicListPage.jsx";
import { TopicPage, healthChip } from "./TopicPage.jsx";
import type { TopicRow } from "./types.js";

const rows: readonly TopicRow[] = [
  {
    name: "orders.payments.v2",
    internal: false,
    partitions: 12,
    replicationFactor: 3,
    health: "in-sync",
    records: 18442901,
    bytes: 128_000_000_000,
    cleanupPolicy: "delete",
  },
  {
    name: "__consumer_offsets",
    internal: true,
    partitions: 50,
    replicationFactor: 3,
    health: "in-sync",
    records: 12,
    bytes: 4096,
  },
  {
    // The topic KUI could not describe. Its figures are genuinely unknown, which is not zero.
    name: "shipments.v1",
    internal: false,
    partitions: 6,
    replicationFactor: 2,
    health: "unknown",
  },
];

/**
 * A controlled list, with somewhere for its requests to go.
 *
 * The page no longer decides which topics exist: it draws the rows it is handed and *asks* for a
 * different set. So the tests below assert what it asks for, which is the behaviour that is now
 * true of a cluster of any size — the old ones asserted filtering that was correct for one page and
 * wrong for four thousand topics.
 */
function listing(overrides: Partial<TopicListPageProps> = {}): {
  readonly asked: TopicListQuery[];
  readonly node: JSX.Element;
} {
  const asked: TopicListQuery[] = [];
  const [query, setQuery] = createSignal<TopicListQuery>(DEFAULT_TOPIC_QUERY);
  return {
    asked,
    node: (
      <TopicListPage
        topics={rows}
        onOpen={() => undefined}
        viewportHeight={480}
        query={query()}
        onQueryChange={(next) => {
          asked.push(next);
          setQuery(next);
        }}
        {...overrides}
      />
    ),
  };
}

describe("the topic list", () => {
  test("asks the server for internal topics rather than filtering them out of a page", async () => {
    /*
     * The control used to filter rows the page already held — and the server excludes Kafka's
     * bookkeeping topics by default, so the data it filtered had never contained one and the
     * checkbox could not do anything at all. Now it changes the request.
     */
    const list = listing();
    const { container, dispose } = mount(() => list.node);
    await flush();

    const toggle = container.querySelector<HTMLInputElement>('input[type="checkbox"]');
    toggle?.click();
    await flush();

    expect(list.asked.at(-1)?.showInternal).toBe(true);
    dispose();
  });

  test("counts against the whole list, not against the page it can see", async () => {
    // "12 topics" over a table of twelve rows that is really a cluster of four thousand is the most
    // confidently wrong sentence this page could write.
    const list = listing({ totalItems: 4000 });
    const { container, dispose } = mount(() => list.node);
    await flush();
    expect(container.textContent).toContain("of 4,000 topics");
    dispose();
  });

  test("says how many are shown when the server did not count", async () => {
    // `undefined` is not zero. Printing the page's own length as a total would be a claim about the
    // cluster made from the size of one page.
    const list = listing({ totalItems: undefined });
    const { container, dispose } = mount(() => list.node);
    await flush();
    expect(container.textContent).toContain("topics shown");
    dispose();
  });

  test("draws a value KUI does not know as a dash with a word beside it, never as zero", async () => {
    const list = listing({ topics: [rows[2] as TopicRow] });
    const { container, dispose } = mount(() => list.node);
    await flush();
    // Every cell that has no value draws the dash, and *only* the dash: an assertion that merely
    // looked for one somewhere on the page would still pass if the records cell drew `0`, because
    // the cleanup-policy cell has a dash of its own.
    const absent = [...container.querySelectorAll(".kui-table__cell-muted [aria-hidden]")];
    expect(absent.length).toBeGreaterThan(0);
    for (const cell of absent) expect(cell.textContent).toBe("—");
    // A bare dash is announced as "dash" or as nothing at all depending on the reader; the fact is
    // that the value is not known, and that is what is said.
    expect(container.textContent).toContain("not known");
    // And nothing in this row is a drawn number, because none of its figures is known.
    expect(container.querySelectorAll(".kui-table__cell-number")).toHaveLength(2);
    dispose();
  });

  test("says how many topics are missing rather than quietly being short", async () => {
    const list = listing({ incomplete: 4 });
    const { container, dispose } = mount(() => list.node);
    await flush();
    expect(container.textContent).toContain("4 topics could not be described");
    dispose();
  });

  test("distinguishes an empty cluster from a search that matched nothing", async () => {
    const emptyList = listing({ topics: [] });
    const empty = mount(() => emptyList.node);
    await flush();
    expect(empty.container.textContent).toContain("No topics yet");
    empty.dispose();

    // A search the server has already applied, with nothing to show for it. The distinction is in
    // the query rather than in a local filter, because the search is not local any more.
    const searched = listing({
      topics: [],
      query: { ...DEFAULT_TOPIC_QUERY, search: "nothing-like-this" },
    });
    const listed = mount(() => searched.node);
    await flush();
    expect(listed.container.textContent).toContain("No topic matches that text");
    listed.dispose();
  });

  test("waits for a pause before asking, so a typed word is one request and not eight", async () => {
    vi.useFakeTimers();
    const list = listing();
    const { container, dispose } = mount(() => list.node);
    await flush();

    const search = container.querySelector<HTMLInputElement>('input[type="search"]');
    for (const text of ["p", "pa", "pay", "paym"]) {
      if (search !== null) {
        search.value = text;
        search.dispatchEvent(new Event("input", { bubbles: true }));
      }
      vi.advanceTimersByTime(50);
    }
    // Still nothing: every keystroke cancelled the one before it.
    expect(list.asked).toEqual([]);

    vi.advanceTimersByTime(400);
    expect(list.asked).toHaveLength(1);
    expect(list.asked[0]?.search).toBe("paym");
    vi.useRealTimers();
    dispose();
  });

  test("goes back to the first page whenever the view changes", async () => {
    // Page 7 of one filter is not page 7 of another, and landing on an empty page after typing
    // reads as "no matches" when the matches are on page 1.
    const list = listing({ query: { ...DEFAULT_TOPIC_QUERY, page: 7 } });
    const { container, dispose } = mount(() => list.node);
    await flush();
    container.querySelector<HTMLInputElement>('input[type="checkbox"]')?.click();
    await flush();
    expect(list.asked.at(-1)?.page).toBe(1);
    dispose();
  });

  test("the create action is disabled with a reason rather than hidden", async () => {
    const list = listing({
      onCreate: () => undefined,
      createDisabledReason: "This cluster is configured read-only.",
    });
    const { container, dispose } = mount(() => list.node);
    await flush();
    const button = [...container.querySelectorAll("button")].find((b) =>
      b.textContent?.includes("Create topic"),
    );
    expect(button?.getAttribute("aria-disabled")).toBe("true");
    dispose();
  });

  test("counts and sizes read as people write them", () => {
    expect(matchCount(3, 3)).toBe("3 topics");
    expect(matchCount(1, 1)).toBe("1 topic");
    expect(matchCount(2, 4000)).toBe("2 of 4,000 topics");
    expect(formatBytes(4096)).toBe("4.1 kB");
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(128_000_000_000)).toBe("128.0 GB");
  });
});

describe("the topic page frame", () => {
  test("names the topic in full and says how it is doing", async () => {
    const long = "orders.payments.v2.dead-letter.retry-5m.eu-central-1.reprocessing";
    const { container, dispose } = mount(() => <TopicPage name={long} health="in-sync" />);
    await flush();
    // Never shortened: a heading that ended in an ellipsis would name a different topic.
    expect(container.querySelector("h1")?.textContent).toBe(long);
    expect(container.textContent).toContain("in sync");
    dispose();
  });

  test("a topic KUI could not describe is not drawn as a broken one", () => {
    // "unknown" is a failure to describe, not a failure of the topic. Danger colours here would
    // tell an operator their topic is offline when what is offline is the broker that would say.
    expect(healthChip("unknown")).toEqual({ tone: "neutral", label: "not described" });
    expect(healthChip("offline")).toEqual({ tone: "danger", label: "offline" });
    expect(healthChip("under-replicated").tone).toBe("warning");
  });

  test("the destructive action does not share a shape with the constructive one", async () => {
    const purge = vi.fn();
    const { container, dispose } = mount(() => (
      <TopicPage
        name="orders.payments.v2"
        health="in-sync"
        onProduce={{ label: "Produce message", onClick: () => undefined }}
        onPurge={{ label: "Purge", onClick: purge }}
      />
    ));
    await flush();
    const buttons = [...container.querySelectorAll("button")];
    const produce = buttons.find((b) => b.textContent?.includes("Produce message"));
    const trash = buttons.find((b) => b.textContent?.includes("Purge"));
    // Different variants, which is what makes them different silhouettes rather than two buttons
    // that differ only in their words.
    expect(produce?.className).toContain("secondary");
    expect(trash?.className).toContain("danger");
    // And a glyph as well as the outline, because an outline alone is a colour-only distinction.
    expect(trash?.querySelector("svg")).not.toBeNull();
    trash?.click();
    expect(purge).toHaveBeenCalledOnce();
    dispose();
  });

  test("an action this principal may not take is disabled with the reason, not hidden", async () => {
    const { container, dispose } = mount(() => (
      <TopicPage
        name="t"
        health="in-sync"
        onPurge={{
          label: "Purge",
          onClick: () => undefined,
          disabledReason: "You do not hold a role that permits purging this topic.",
        }}
      />
    ));
    await flush();
    const trash = [...container.querySelectorAll("button")].find((b) =>
      b.textContent?.includes("Purge"),
    );
    expect(trash?.getAttribute("aria-disabled")).toBe("true");
    dispose();
  });

  test("renders the chrome it is handed and nothing when it is handed none", async () => {
    const bare = mount(() => <TopicPage name="t" health="in-sync" />);
    await flush();
    // A breadcrumb with a single item is a line that tells nobody anything; none is drawn.
    expect(bare.container.querySelector("nav")).toBeNull();
    bare.dispose();

    const dressed = mount(() => (
      <TopicPage name="t" health="in-sync" breadcrumb={<nav aria-label="Breadcrumb">Topics</nav>} />
    ));
    await flush();
    expect(dressed.container.querySelector("nav")).not.toBeNull();
    dressed.dispose();
  });
});

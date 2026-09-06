/**
 * The topic list and the topic page frame.
 *
 * The cases here are the ones that are easy to get wrong in a way nobody notices: a dash drawn as a
 * zero, a count that describes a filtered table as if it were the whole cluster, a destructive
 * action that looks like an ordinary one, and a forbidden action that has been hidden rather than
 * explained.
 */

import { afterEach, describe, expect, test, vi } from "vitest";
import { createSignal, flush } from "solid-js";
import type { JSX } from "@solidjs/web";
import { clearToasts, toasts } from "@kui/kernel";
import { mount } from "./testing.js";
import {
  DEFAULT_TOPIC_QUERY,
  TopicListPage,
  formatBytes,
  matchCount,
  rememberView,
  storedView,
  type TopicListPageProps,
  type TopicListQuery,
} from "./TopicListPage.jsx";
import { TopicCards } from "./TopicCards.jsx";
import { TopicConsumers } from "./TopicConsumers.jsx";
import { TopicPage, healthChip } from "./TopicPage.jsx";
import {
  forgetQueries,
  restoreMeasuredRows,
  settle,
  topicsHost,
  withMeasuredRows,
  type StubRequest,
} from "./harness.jsx";
import { topicsCsv, topicsVoice } from "./topicList.js";
import { bulkSentence, toTopicQuery } from "./TopicsRoute.jsx";
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
  test("the Internal chip changes the request rather than filtering a page", async () => {
    /*
     * The control used to filter rows the page already held — and the server excludes Kafka's
     * bookkeeping topics by default, so the data it filtered had never contained one and the
     * checkbox could not do anything at all. The chip that replaced it changes the *query*, which
     * `toTopicQuery` turns into `showInternal` (asserted in `write.test.ts`).
     */
    const list = listing();
    const { container, dispose } = mount(() => list.node);
    await flush();

    const internal = [...container.querySelectorAll("button")].find(
      (button) => button.textContent?.trim() === "Internal",
    );
    internal?.click();
    await flush();

    expect(list.asked.at(-1)?.facet).toBe("internal");
    dispose();
  });

  test("a chip the cluster cannot apply says that it narrowed the page", async () => {
    /*
     * The honest half of the four-chip bar. KUI's topic index has no column for compaction, so the
     * chip filters the rows the server sent — and a filter that narrows a page while looking like
     * it narrows a cluster is the exact defect the server-side search box was rebuilt to remove.
     */
    const list = listing({ query: { ...DEFAULT_TOPIC_QUERY, facet: "compacted" } });
    const { container, dispose } = mount(() => list.node);
    await flush();
    expect(container.textContent).toContain("narrows the 3 topics on this page");
    // And it really narrowed: only the row whose policy includes `compact` survives.
    expect(container.textContent).not.toContain("__consumer_offsets");
    dispose();
  });

  test("a row whose cleanupPolicy is absent renders nothing in that column", async () => {
    /*
     * Not `delete`, which is Kafka's default and would be the screen inventing a setting it was not
     * told; and not the em dash either, which everywhere else on this page means "a figure nobody
     * could measure". `shipments.v1` has no policy on it and `orders.payments.v2` has `delete`, so
     * this asserts one tag exists and the other cell is empty rather than asserting a global count.
     */
    const list = listing({ topics: [rows[0] as TopicRow, rows[2] as TopicRow] });
    const { container, dispose } = mount(() => list.node);
    await flush();

    /* `.kui-table__row` and not `tbody tr`: a windowed table pads its scroll height with two
       `role="presentation"` spacer rows, which are layout rather than topics. */
    const cells = [...container.querySelectorAll(".kui-table__row")].map((row) =>
      [...row.querySelectorAll("td")].at(-1)?.textContent?.trim(),
    );
    expect(cells).toEqual(["delete", ""]);
    dispose();
  });

  test("every order the Sort menu offers is one the server can produce", async () => {
    /*
     * The menu's options and the request's field names are the two ends of one vocabulary, and they
     * used to be two hand-written lists in two files. The pair had a hole with a direction to it:
     * *removing* a mapping was caught by a case in `write.test.ts`, and *adding* an option with no
     * mapping was not — which ships a Sort item that redraws the list in the server's own order
     * under an ascending arrow, and looks exactly like a sort.
     *
     * Driven through the rendered control rather than over `SORTABLE_COLUMNS`, so a menu that stops
     * being derived from that list fails here instead of passing against its own source.
     */
    const list = listing();
    const { container, dispose } = mount(() => list.node);
    await flush();

    const openSort = async (): Promise<HTMLElement[]> => {
      const trigger = [...container.querySelectorAll<HTMLElement>('[role="combobox"]')].find(
        (element) => element.textContent?.includes("Sort ·"),
      );
      // Only when it is shut: the trigger toggles, and a click on an open list closes it.
      if (trigger?.getAttribute("aria-expanded") !== "true") trigger?.click();
      await flush();
      return [...container.querySelectorAll<HTMLElement>('[role="option"]')];
    };

    // The first option is the server's own order and is not a column, so it is not one of these.
    const offered = (await openSort()).length;
    expect(offered).toBeGreaterThan(1);

    for (let index = 1; index < offered; index += 1) {
      const options = await openSort();
      const label = options[index]?.textContent?.trim() ?? "";
      options[index]?.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, cancelable: true }));
      await flush();

      const asked = list.asked.at(-1);
      expect(asked?.sort, `choosing "${label}" should have asked for an order`).toBeTruthy();
      expect(
        toTopicQuery(asked as TopicListQuery).sort,
        `the Sort menu offers "${label}", so the request has to carry a field for it`,
      ).toBeDefined();
    }
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
    [...container.querySelectorAll("button")]
      .find((button) => button.textContent?.trim() === "Internal")
      ?.click();
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

describe("the view toggle", () => {
  test("remembers the choice, because a control that forgets reads as broken", async () => {
    /*
     * `SCREENS.md` §2.12 is explicit: the choice persists per user, not per visit — "an operator who
     * prefers cards and gets a table on every navigation will conclude the control does not work".
     * It is a preference about how this person reads a list, not a property of the list, which is
     * why it is not in the query string: a link somebody sends should show the recipient their own
     * preferred view.
     */
    window.localStorage.removeItem("kui.topics.view");
    expect(storedView()).toBe("table");

    rememberView("cards");
    expect(storedView()).toBe("cards");

    rememberView("table");
    expect(storedView()).toBe("table");
  });

  test("falls back to the table when storage cannot be read at all", () => {
    // A private window, or a browser set to block site data. Both `getItem` and `setItem` can throw
    // rather than return null, and a preference that cannot be read is not an error — it is the
    // default. A screen that threw here would fail to render a topic list over a stored preference.
    const original = window.localStorage.getItem;
    Object.defineProperty(window.localStorage, "getItem", {
      configurable: true,
      value: () => {
        throw new Error("The operation is insecure.");
      },
    });
    expect(storedView()).toBe("table");
    Object.defineProperty(window.localStorage, "getItem", { configurable: true, value: original });
  });

  test("a card carries the topic's shape, its health and its measurements", async () => {
    /*
     * `SCREENS-V4.md` §4.7's composition — the three tags, the size and the rate — plus the health
     * pill, which the design leaves off cards and this keeps: an operator who switches to cards and
     * can no longer see that a topic is offline has been given decoration in exchange for
     * information. The deviation is argued at the top of `TopicCards.tsx`.
     */
    const { container, dispose } = mount(() => (
      <TopicCards topics={rows} onOpen={() => undefined} formatBytes={formatBytes} />
    ));
    await flush();
    expect(container.textContent).toContain("12 partitions");
    expect(container.textContent).toContain("RF 3");
    expect(container.textContent).toContain("delete");
    // The health the design omits, kept.
    expect(container.textContent).toContain("in sync");
    expect(container.textContent).toContain("not described");
    // `rows[2]` has no size and none of them has a rate: words, never a zero.
    expect(container.textContent).toContain("not measured");
    expect(container.textContent).not.toContain("0 B");
    dispose();
  });
});

/**
 * The seam, rather than the components.
 *
 * Everything below mounts the real route over the real router and a stubbed gateway, because every
 * rule here is about *which document reaches which component*. A case that handed `TopicListPage` a
 * `statistics` element would assert the arrangement the case itself made, and would keep passing if
 * `TopicsRoute` started computing those totals from the rows on screen — which is the one change
 * these cases exist to catch.
 */
describe("the topics screen, wired", () => {
  /* Toasts are a module-level stack and the view preference is `localStorage`: both outlive a case,
     and a case that ticked rows in a table would otherwise inherit `cards` from the one before it. */
  afterEach(() => {
    clearToasts();
    forgetQueries();
    restoreMeasuredRows();
    window.localStorage.removeItem("kui.topics.view");
  });

  /** One page of three topics, so a page count and a cluster count can differ. */
  const threeRows = {
    topics: {
      status: "ok",
      fetchedAt: "2026-09-06T00:00:00Z",
      data: {
        items: [
          {
            name: "orders.payments.v2",
            internal: false,
            partitionCount: 12,
            replicationFactor: 3,
            outOfSyncReplicas: 0,
            offlinePartitions: 0,
            messageCount: 18_442_901,
            sizeBytes: 128_000_000_000,
            cleanupPolicy: "delete",
          },
          {
            name: "orders.refunds.v1",
            internal: false,
            partitionCount: 6,
            replicationFactor: 3,
            outOfSyncReplicas: 0,
            offlinePartitions: 0,
            messageCount: 12,
            sizeBytes: 4096,
            cleanupPolicy: "compact",
          },
          {
            name: "orders.audit.v1",
            internal: false,
            partitionCount: 3,
            replicationFactor: 3,
            outOfSyncReplicas: 0,
            offlinePartitions: 0,
            messageCount: 4,
            sizeBytes: 512,
          },
        ],
        page: { page: 1, pageSize: 32, totalItems: 3 },
      },
    },
    incompleteTopics: 0,
  };

  test("the statistics region shows the cluster total and not the page's", async () => {
    /*
     * `SCREENS-V4.md` §4.6 calls this the load-bearing fact of the screen: the capture shows 128
     * under TOTAL TOPICS while the table below it holds three. The stub answers three rows and a
     * statistics document saying 128, so a region that folded the rows would print `3` and this
     * would fail — which is exactly what the mutation line asks for.
     */
    const host = topicsHost({
      at: "/clusters/stats-cluster/topics",
      answers: {
        "/api/v1/clusters/{clusterId}/topics": threeRows,
        "/api/v1/clusters/{clusterId}/topics/statistics": {
          statistics: {
            status: "ok",
            fetchedAt: "2026-09-06T00:00:00Z",
            data: { topicCount: 128, partitionCount: 1536, sizeBytes: 842_000_000_000, incompleteTopics: 0 },
          },
        },
      },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    const tile = container.querySelector('[data-testid="topic-stat-topics"]');
    expect(tile?.textContent).toContain("128");
    // And it is not the page's three, dressed as a cluster total.
    expect(tile?.textContent).not.toContain("3");
    /* And the page really did hold three, so the two figures genuinely disagree rather than the
       stub having answered the same number twice. The count beside the controls is the page's own
       — the windowed table draws no rows in a DOM with no layout engine, which is what
       `viewportHeight` exists for and is not what this case is about. */
    expect(container.querySelector(".kui-topic-list__count")?.textContent).toBe("3 topics");
    dispose();
  });

  test("a refused total renders the sentence and not 0", async () => {
    /*
     * `partitionCount` and `sizeBytes` are each `Option` on the wire and each refuses on its own: a
     * topic the scrape could not describe removes both sums and leaves the count. `0 B` under TOTAL
     * STORAGE is the most reassuring possible rendering of the least reassuring possible state.
     */
    const host = topicsHost({
      at: "/clusters/refused-cluster/topics",
      answers: {
        "/api/v1/clusters/{clusterId}/topics": threeRows,
        "/api/v1/clusters/{clusterId}/topics/statistics": {
          statistics: {
            status: "ok",
            fetchedAt: "2026-09-06T00:00:00Z",
            data: { topicCount: 10, partitionCount: null, sizeBytes: null, incompleteTopics: 2 },
          },
        },
      },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    const partitions = container.querySelector('[data-testid="topic-stat-partitions"]');
    const storage = container.querySelector('[data-testid="topic-stat-storage"]');
    expect(partitions?.textContent).toContain("not measured");
    expect(storage?.textContent).toContain("not measured");
    expect(partitions?.textContent).not.toContain("0");
    expect(storage?.textContent).not.toContain("0 B");
    // The count survives its own sums, and the screen says why they are gone.
    expect(container.querySelector('[data-testid="topic-stat-topics"]')?.textContent).toContain("10");
    expect(container.textContent).toContain("2 topics on this cluster could not be described");
    dispose();
  });

  test("the Overview tab renders a body", async () => {
    /*
     * The tab the strip opens by default, and until now the one that drew nothing at all: the route
     * declared `id: "overview"` and had no `<Show when={tab() === \"overview\"}>` anywhere in it.
     * Mounted at the topic's bare address, which is the address that tab lives at.
     */
    const host = topicsHost({
      at: "/clusters/overview-cluster/topics/orders.v1",
      answers: {
        "/api/v1/clusters/{clusterId}/topics/{topicName}/overview": {
          topic: {
            status: "ok",
            fetchedAt: "2026-09-06T00:00:00Z",
            data: {
              row: {
                name: "orders.v1",
                internal: false,
                partitionCount: 6,
                replicationFactor: 3,
                outOfSyncReplicas: 0,
                offlinePartitions: 0,
                messageCount: 16,
                sizeBytes: null,
                produceRate: null,
                cleanupPolicy: "delete",
              },
              partitions: [
                { partition: 0, leader: 1, replicas: [{ broker: 1, leader: true, inSync: true }], earliestOffset: 0, latestOffset: 1 },
              ],
            },
          },
          consumerGroups: { status: "ok", fetchedAt: "2026-09-06T00:00:00Z", data: [{}, {}] },
        },
      },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    // The four tiles the design names, and the partition table under them.
    expect(container.querySelector('[data-testid="topic-overview-partitions"]')?.textContent).toContain("6");
    expect(container.querySelector('[data-testid="topic-overview-groups"]')?.textContent).toContain("2");
    expect(container.querySelector('[data-testid="topic-partitions-table"]')).not.toBeNull();
    // The two figures this cluster does not report say so in words. Never `0 B` and never `0 /s`.
    expect(container.querySelector('[data-testid="topic-overview-size"]')?.textContent).toContain("not measured");
    expect(container.querySelector('[data-testid="topic-overview-rate"]')?.textContent).toContain("not measured");
    // And the trail the design draws inside the content.
    expect(container.querySelector("nav[aria-label='Breadcrumb']")?.textContent).toContain("Topics");
    dispose();
  });

  test("the consumers tab prints host:port", async () => {
    /*
     * `coordinatorHost` and `coordinatorPort` are on the wire together with `coordinatorId`, and the
     * screen printed `broker 1` — an id is not an address. Driven at `?tab=consumers`, so the tab's
     * own request is the one that produces the row.
     */
    const host = topicsHost({
      at: "/clusters/coord-cluster/topics/orders.v1?tab=consumers",
      answers: {
        "/api/v1/clusters/{clusterId}/topics/{topicName}/overview": {
          topic: {
            status: "ok",
            fetchedAt: "2026-09-06T00:00:00Z",
            data: { row: { name: "orders.v1", internal: false, partitionCount: 6, replicationFactor: 1, outOfSyncReplicas: 0, offlinePartitions: 0 }, partitions: [] },
          },
        },
        "/api/v1/clusters/{clusterId}/topics/{topic}/consumer-groups": {
          rows: [
            {
              group: {
                groupId: "order-fulfilment",
                state: "EMPTY",
                members: 0,
                topics: 1,
                coordinatorId: 1,
                coordinatorHost: "kafka",
                coordinatorPort: 9092,
                totalLag: 9,
              },
              topicLag: 9,
              partitions: 6,
              dormant: true,
            },
            {
              // The coordinator could not be described. No address, and no invented broker id.
              group: { groupId: "nightly-batch", state: "EMPTY", members: 0, topics: 1, totalLag: 0 },
              topicLag: 0,
              partitions: 1,
              dormant: true,
            },
          ],
        },
      },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    expect(container.textContent).toContain("kafka:9092");
    expect(container.textContent).not.toContain("broker 1");
    // The row with no coordinator says nothing rather than half an address.
    expect(container.textContent).toContain("no coordinator address");
    dispose();
  });

  test("the table and the cards the route renders share one selection set", async () => {
    /*
     * `SCREENS-V4.md` §3.7: the design's two ticks are on cards and the same set has to survive the
     * switch to the table. Nothing about that rule lives in either treatment. It lives in the fact
     * that `TopicsRoute` holds **one** signal and `TopicListPage` forwards it into whichever branch
     * is drawn — so it can only be asserted where the product makes the arrangement.
     *
     * The case that used to claim this built its own signal and handed it to a bare `TopicListPage`
     * and a bare `TopicCards` side by side, so it asserted the arrangement the case itself made:
     * giving the cards branch a private `createSignal` left it green, along with all 118 others.
     * This ticks a row in the table the *route* drew, works the *route's* own view control, and
     * reads the checkbox on the card the *route* drew.
     */
    withMeasuredRows();
    const host = topicsHost({
      at: "/clusters/one-set-cluster/topics",
      answers: { "/api/v1/clusters/{clusterId}/topics": threeRows },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    const tick = container.querySelector<HTMLInputElement>('tbody input[type="checkbox"]');
    expect(tick, "the route's table should have drawn rows to tick").not.toBeNull();
    tick?.click();
    await settle();

    // The route's own reading of the set, before the switch: one row, and the bar knows it.
    const bar = container.querySelector('[data-testid="topic-bulk-bar"]');
    expect(bar?.textContent).toContain("1 topic selected");

    const cards = [...container.querySelectorAll<HTMLInputElement>('input[type="radio"]')].find(
      (radio) => radio.value === "cards",
    );
    cards?.click();
    await settle();

    // The table is gone and the cards are drawn, so this really is the other treatment.
    expect(container.querySelector("tbody")).toBeNull();
    /* Scoped to the card grid: the `Show statistics` switch is a checked checkbox too, and a count
       over the whole page would be counting a control that has nothing to do with selection. */
    const ticked = [
      ...container.querySelectorAll<HTMLInputElement>('.kui-topic-cards input[type="checkbox"]'),
    ].filter((box) => box.checked);
    expect(ticked).toHaveLength(1);
    // And it is the same topic, not merely the same number of ticks.
    expect(ticked[0]?.getAttribute("aria-label") ?? ticked[0]?.closest("label")?.textContent).toContain(
      "orders.payments.v2",
    );
    // The bar is the route's, and it did not reset when the treatment changed.
    expect(container.querySelector('[data-testid="topic-bulk-bar"]')?.textContent).toContain(
      "1 topic selected",
    );

    dispose();
  });

  test("the overview says how short its partition table is, and only when it is short", async () => {
    /*
     * The notice used to fire on `partitions.length >= 500`, a hand-copy of the gateway's
     * `TopicDetailResponse.EmbeddedPartitionLimit` with nothing comparing the two — so a topic with
     * exactly 500 partitions and a complete table was told its table was short, and a topic whose
     * embedded list was short for any other reason was told nothing. It now subtracts what arrived
     * from what the topic has, which is the question the sentence claims to answer.
     */
    const overviewFor = (partitionCount: number, rows: number) => ({
      topic: {
        status: "ok",
        fetchedAt: "2026-09-06T00:00:00Z",
        data: {
          row: {
            name: "orders.v1",
            internal: false,
            partitionCount,
            replicationFactor: 1,
            outOfSyncReplicas: 0,
            offlinePartitions: 0,
          },
          partitions: Array.from({ length: rows }, (_unused, index) => ({
            partition: index,
            leader: 1,
            replicas: [{ broker: 1, leader: true, inSync: true }],
            earliestOffset: 0,
            latestOffset: 1,
          })),
        },
      },
    });

    const short = topicsHost({
      at: "/clusters/short-table-cluster/topics/orders.v1",
      answers: {
        "/api/v1/clusters/{clusterId}/topics/{topicName}/overview": overviewFor(9, 4),
      },
    });
    const shortMount = mount(short.view);
    await settle();
    expect(shortMount.container.textContent).toContain("This table shows 4 of 9 partitions");
    shortMount.dispose();
    forgetQueries();

    const whole = topicsHost({
      at: "/clusters/whole-table-cluster/topics/orders.v1",
      answers: {
        "/api/v1/clusters/{clusterId}/topics/{topicName}/overview": overviewFor(4, 4),
      },
    });
    const wholeMount = mount(whole.view);
    await settle();
    // Every partition arrived, so there is nothing to warn about and nothing is said.
    expect(wholeMount.container.textContent).not.toContain("The Partitions tab");
    wholeMount.dispose();
  });

  test("a refused consumer-group count renders the sentence and not 0", async () => {
    /*
     * The overview's five sections refuse independently: the consumer service can be down while the
     * topic service is not. `0` under CONSUMER GROUPS would say "nothing reads this topic", which
     * is
     * a real and completely different fact — and it is the fact an operator acts on.
     *
     * `data.ts` maps the section, `TopicOverviewTab` draws it, and until now no case fed the route
     * a
     * refused one: `consumerGroups: groupCount ?? 0` left the whole suite green.
     */
    const host = topicsHost({
      at: "/clusters/no-groups-cluster/topics/orders.v1",
      answers: {
        "/api/v1/clusters/{clusterId}/topics/{topicName}/overview": {
          topic: {
            status: "ok",
            fetchedAt: "2026-09-06T00:00:00Z",
            data: {
              row: {
                name: "orders.v1",
                internal: false,
                partitionCount: 1,
                replicationFactor: 1,
                outOfSyncReplicas: 0,
                offlinePartitions: 0,
                messageCount: 16,
                sizeBytes: 5114,
              },
              partitions: [
                { partition: 0, leader: 1, replicas: [{ broker: 1, leader: true, inSync: true }], earliestOffset: 0, latestOffset: 1 },
              ],
            },
          },
          // The consumer service did not answer. It said so; it did not say "none".
          consumerGroups: { status: "unavailable", reason: "circuit_open" },
        },
      },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    const tile = container.querySelector('[data-testid="topic-overview-groups"]');
    expect(tile?.textContent).toContain("not measured");
    // Not a zero anywhere in the tile, and not the em dash the table uses for a cell either.
    expect(tile?.textContent).not.toMatch(/\b0\b/);
    // The tiles that *were* answered still carry their figures: one refusal costs one tile.
    expect(container.querySelector('[data-testid="topic-overview-partitions"]')?.textContent).toContain("1");
    dispose();
  });

  test("a bulk action that partly refused raises a warning toast", async () => {
    /*
     * The tone is the whole content of this rendering. A green toast over a set that half refused
     * is
     * the reassuring rendering of the state that needs attention, and `tone: "success"` hard-coded
     * in place of the expression left every case in this package green — only the pure
     * `bulkSentence` helper was asserted, and it says nothing about colour.
     *
     * The refusal is a real one from `eachTopic`: the server plans the delete and withholds the
     * token, which is ADR-045's own way of saying no. `orders.payments.v2` gets a token and goes
     * through; `orders.refunds.v1` does not.
     */
    withMeasuredRows();
    const host = topicsHost({
      at: "/clusters/half-refused-cluster/topics",
      answers: {
        "/api/v1/clusters/{clusterId}/topics": threeRows,
        "/api/v1/clusters/{clusterId}/topics/{topicName}/deletion/plan": (request: StubRequest) => ({
          topic: request.params.path?.["topicName"] ?? "",
          partitions: 6,
          records: 16,
          autoCreateEnabled: false,
          warnings: [],
          ...(request.params.path?.["topicName"] === "orders.payments.v2"
            ? { token: "tok-1", expiresAt: "2026-09-06T00:05:00Z" }
            : {}),
        }),
        "/api/v1/clusters/{clusterId}/topics/{topicName}": {
          topic: "orders.payments.v2",
          partitions: 6,
          records: 16,
          warnings: [],
        },
      },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    /* Re-queried between the two clicks: ticking a row re-renders the windowed rows, so the second
       element of the first query is a node that is no longer in the document. */
    const tickRow = async (index: number): Promise<void> => {
      const boxes = [...container.querySelectorAll<HTMLInputElement>('tbody input[type="checkbox"]')];
      expect(boxes.length).toBeGreaterThan(index);
      boxes[index]?.click();
      await settle();
    };
    await tickRow(0);
    await tickRow(1);

    [...(container.querySelector('[data-testid="topic-bulk-bar"]')?.querySelectorAll("button") ?? [])]
      .find((button) => button.textContent?.trim() === "Delete")
      ?.click();
    await settle();

    const gate = document.querySelector<HTMLInputElement>(".kui-confirm__input");
    expect(gate).not.toBeNull();
    if (gate !== null) {
      gate.value = "delete";
      gate.dispatchEvent(new Event("input", { bubbles: true }));
    }
    await settle();

    [...document.querySelectorAll("button")]
      .find((button) => button.textContent?.trim() === "Delete topics")
      ?.click();
    await settle();

    const raised = toasts().at(-1);
    expect(raised?.title).toContain("1 topic deleted");
    expect(raised?.title).toContain("1 refused");
    // The rendering this case exists for.
    expect(raised?.tone).toBe("warning");

    dispose();
  });

  test("a bulk action that wholly succeeded raises a success toast", async () => {
    // The other half of the same expression: with both branches asserted, the tone cannot be a
    // constant of either value. Same arrangement, and both topics are issued a token.
    withMeasuredRows();
    const host = topicsHost({
      at: "/clusters/all-deleted-cluster/topics",
      answers: {
        "/api/v1/clusters/{clusterId}/topics": threeRows,
        "/api/v1/clusters/{clusterId}/topics/{topicName}/deletion/plan": (request: StubRequest) => ({
          topic: request.params.path?.["topicName"] ?? "",
          partitions: 6,
          records: 16,
          autoCreateEnabled: false,
          warnings: [],
          token: "tok-1",
          expiresAt: "2026-09-06T00:05:00Z",
        }),
        "/api/v1/clusters/{clusterId}/topics/{topicName}": {
          topic: "orders.payments.v2",
          partitions: 6,
          records: 16,
          warnings: [],
        },
      },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    const tickRow = async (index: number): Promise<void> => {
      const boxes = [...container.querySelectorAll<HTMLInputElement>('tbody input[type="checkbox"]')];
      boxes[index]?.click();
      await settle();
    };
    await tickRow(0);
    await tickRow(1);

    [...(container.querySelector('[data-testid="topic-bulk-bar"]')?.querySelectorAll("button") ?? [])]
      .find((button) => button.textContent?.trim() === "Delete")
      ?.click();
    await settle();

    const gate = document.querySelector<HTMLInputElement>(".kui-confirm__input");
    if (gate !== null) {
      gate.value = "delete";
      gate.dispatchEvent(new Event("input", { bubbles: true }));
    }
    await settle();

    [...document.querySelectorAll("button")]
      .find((button) => button.textContent?.trim() === "Delete topics")
      ?.click();
    await settle();

    const raised = toasts().at(-1);
    expect(raised?.title).toBe("2 topics deleted");
    expect(raised?.tone).toBe("success");

    dispose();
  });

  test("the bulk bar's Export hands over the ticked rows and not the page", async () => {
    /*
     * Found by mutation and gated afterwards: `onSelect: () => exportRows(result().topics)` — the
     * bulk bar exporting the whole page instead of the selection — left every case in this package
     * green. The header action above the table is the one that exports the page; the bar's is about
     * the ticks, and the two are one line apart in the same file.
     */
    let handed: Blob | undefined;
    const original = URL.createObjectURL;
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: (blob: Blob) => {
        handed = blob;
        return "blob:test";
      },
    });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: () => undefined });

    withMeasuredRows();
    const host = topicsHost({
      at: "/clusters/ticked-export-cluster/topics",
      answers: { "/api/v1/clusters/{clusterId}/topics": threeRows },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    container.querySelector<HTMLInputElement>('tbody input[type="checkbox"]')?.click();
    await settle();

    [...(container.querySelector('[data-testid="topic-bulk-bar"]')?.querySelectorAll("button") ?? [])]
      .find((button) => button.textContent?.trim() === "Export")
      ?.click();

    expect(handed).toBeDefined();
    const text = await (handed as Blob).text();
    expect(text).toContain('"orders.payments.v2"');
    // The two rows nobody ticked are not in the file, which is the whole difference.
    expect(text).not.toContain('"orders.refunds.v1"');
    expect(text).not.toContain('"orders.audit.v1"');

    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: original });
    dispose();
  });

  test("changing the query drops the ticks it can no longer show", async () => {
    /*
     * Also found by mutation: deleting `setSelected(new Set())` from `changeQuery` left 124 cases
     * green, under a three-sentence comment arguing for it. A bar reading "1 topic selected" over a
     * page holding no such row is a control whose subject the operator cannot see, and the first
     * thing they would do to find out what it means is press `Delete`.
     */
    withMeasuredRows();
    const host = topicsHost({
      at: "/clusters/requeried-cluster/topics",
      answers: { "/api/v1/clusters/{clusterId}/topics": threeRows },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    container.querySelector<HTMLInputElement>('tbody input[type="checkbox"]')?.click();
    await settle();
    expect(container.querySelector('[data-testid="topic-bulk-bar"]')?.textContent).toContain(
      "1 topic selected",
    );

    // Any control that changes the query will do; the chip is the one that needs no debounce.
    [...container.querySelectorAll("button")]
      .find((button) => button.textContent?.trim() === "Internal")
      ?.click();
    await settle();

    /* The bar is absent at zero selection rather than reading "0 selected", so the reading is
       taken defensively — and the ticks themselves are checked, not only the bar above them. */
    expect(container.querySelector('[data-testid="topic-bulk-bar"]')?.textContent ?? "").not.toContain(
      "selected",
    );
    expect(
      [...container.querySelectorAll<HTMLInputElement>('tbody input[type="checkbox"]')].filter(
        (box) => box.checked,
      ),
    ).toHaveLength(0);
    dispose();
  });

  test("a ?q= in the address filters the list", async () => {
    /*
     * The drawer's topic-prefix rows link at `…/topics?q=<prefix>`, and this screen used to seed
     * `DEFAULT_TOPIC_QUERY` and read the address only for `?tab=` — so the link was honest and the
     * destination listed the whole cluster. W4-06 owns the link; this is the reading.
     *
     * Asserted on the *request*, because that is where the filter is applied: a case that read the
     * search box would pass on a screen that filled the box and asked for everything.
     */
    const host = topicsHost({
      at: "/clusters/addressed-cluster/topics?q=orders.",
      answers: { "/api/v1/clusters/{clusterId}/topics": threeRows },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    const asked = host.stub.requests.find(
      (request) => request.path === "/api/v1/clusters/{clusterId}/topics",
    );
    expect(asked?.params.query?.["q"]).toBe("orders.");
    // And the box says what was asked for, so the screen and the server agree about the list.
    expect(container.querySelector<HTMLInputElement>(".kui-textfield__input")?.value).toBe("orders.");
    dispose();
  });

  test("a ?showInternal=true in the address lights the Internal chip and asks the server", async () => {
    // The one facet the wire has, so the one a link can carry. The chip and the parameter are the
    // same control (`isServerFacet`), which is why this is the facet the address is allowed to set.
    const host = topicsHost({
      at: "/clusters/internal-cluster/topics?showInternal=true",
      answers: { "/api/v1/clusters/{clusterId}/topics": threeRows },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    const asked = host.stub.requests.find(
      (request) => request.path === "/api/v1/clusters/{clusterId}/topics",
    );
    expect(asked?.params.query?.["showInternal"]).toBe(true);
    const lit = [...container.querySelectorAll("button")].find(
      (button) =>
        button.getAttribute("aria-pressed") === "true" ||
        button.getAttribute("aria-checked") === "true" ||
        button.getAttribute("aria-selected") === "true",
    );
    expect(lit?.textContent).toContain("Internal");
    dispose();
  });
});

describe("the consumers tab's columns", () => {
  test("no column heading is empty, and the dormant column's is Activity", async () => {
    /*
     * The a11y sweep is a whole-tree gate: it says *a* table somewhere has a blank `<th>`, and it
     * says it over 694 stories. This is the case that says which column — and it is the column that
     * shipped `header: \"\"` and produced all fourteen of the sweep's violations. See the long note
     * on the column itself for why the string is visible rather than visually hidden.
     */
    const { container, dispose } = mount(() => (
      <TopicConsumers
        rows={[
          {
            groupId: "order-fulfilment",
            state: "STABLE",
            members: 2,
            topicLag: 4,
            partitions: 6,
            dormant: true,
            totalLag: 4,
            topics: 1,
            coordinator: "kafka:9092",
          },
        ]}
        hrefFor={(groupId) => `/ui/consumer-groups/${groupId}`}
      />
    ));
    await flush();

    const headings = [...container.querySelectorAll("th")].map((cell) => cell.textContent?.trim());
    expect(headings).not.toContain("");
    expect(headings).toContain("Activity");
    expect(headings).toContain("Coordinator");
    dispose();
  });
});

describe("the export, and the sentence above the list", () => {
  afterEach(forgetQueries);

  test("a figure nobody measured is an empty cell and never a zero", () => {
    /*
     * A spreadsheet sums a column without asking. A `0` written for a topic whose size could not be
     * read becomes a cluster total that is quietly short — and unlike the screen, the file carries
     * no dash and no sentence to say so.
     */
    const csv = topicsCsv([rows[2] as TopicRow]);
    const cells = (csv.split("\r\n")[1] ?? "").split(",");
    // name, internal, partitions, RF, health, records, size, rate, policy.
    expect(cells).toEqual(['"shipments.v1"', '"no"', '"6"', '"2"', '"unknown"', '""', '""', '""', '""']);
  });

  test("a policy containing a comma does not shift every column after it", () => {
    // `compact,delete` is a real and common value of `cleanup.policy`, and an unquoted comma there
    // silently moves the rest of the row one column left. RFC 4180 quoting is what stops it.
    const csv = topicsCsv([
      { name: 'odd"name', internal: false, partitions: 1, replicationFactor: 1, health: "in-sync", cleanupPolicy: "compact,delete" },
    ]);
    expect(csv).toContain('"odd""name"');
    expect(csv).toContain('"compact,delete"');
    expect(csv.split("\r\n")[1]?.split('","')).toHaveLength(9);
  });

  test("the Export control hands the browser the rows it is showing", async () => {
    /*
     * The seam, rather than the formatter: the button is wired to the *page's* rows and to
     * `topicsCsv`, and a control that produced an empty file would look identical in a screenshot.
     * `createObjectURL` does not exist in a DOM with no layout engine, so it is supplied here and
     * the blob it is handed is read back.
     */
    let handed: Blob | undefined;
    const original = URL.createObjectURL;
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: (blob: Blob) => {
        handed = blob;
        return "blob:test";
      },
    });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: () => undefined });

    const host = topicsHost({
      at: "/clusters/export-cluster/topics",
      answers: {
        "/api/v1/clusters/{clusterId}/topics": {
          topics: {
            status: "ok",
            fetchedAt: "2026-09-06T00:00:00Z",
            data: {
              items: [
                {
                  name: "orders.v1",
                  internal: false,
                  partitionCount: 6,
                  replicationFactor: 1,
                  outOfSyncReplicas: 0,
                  offlinePartitions: 0,
                  messageCount: 16,
                  sizeBytes: 5114,
                  cleanupPolicy: "delete",
                },
              ],
              page: { page: 1, pageSize: 32, totalItems: 1 },
            },
          },
          incompleteTopics: 0,
        },
      },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    [...container.querySelectorAll("button")]
      .find((button) => button.textContent?.trim() === "Export")
      ?.click();

    expect(handed).toBeDefined();
    const text = await (handed as Blob).text();
    expect(text).toContain('"orders.v1"');
    expect(text).toContain('"delete"');
    // The header row, so a file with rows and no columns is not mistaken for a working export.
    expect(text.split("\r\n")[0]).toContain('"cleanup policy"');

    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: original });
    dispose();
  });

  test("the voice line drops a clause it cannot measure rather than filling it with zero", () => {
    // Three figures from three documents, and each can be missing. `0 partitions` on a cluster
    // whose sweep was incomplete would be the never-zero rule broken in the most readable place on
    // the screen.
    expect(topicsVoice(3, 128, 1536)).toBe("3 of 128 topics match · 1,536 partitions");
    expect(topicsVoice(128, 128, 1536)).toBe("128 topics · 1,536 partitions");
    expect(topicsVoice(3, 128, undefined)).toBe("3 of 128 topics match");
    expect(topicsVoice(undefined, undefined, undefined)).toBe("");
  });
});

describe("a destructive success says so", () => {
  afterEach(() => {
    clearToasts();
    forgetQueries();
  });

  test("deleting a topic raises a toast naming it", async () => {
    /*
     * The rule this package owes every destructive path: an action that worked says so. A screen
     * that navigates away in silence leaves the operator wondering whether the click registered,
     * and the answer they reach for is to do it again — which on a delete is the one repetition
     * that must never be encouraged.
     *
     * Driven through the real dialog: plan, type the name, confirm. A case that called `notify`
     * itself would assert that a toast library works.
     */
    const plan = {
      topic: "orders.v1",
      partitions: 6,
      records: 16,
      autoCreateEnabled: true,
      warnings: [],
      token: "tok-1",
      expiresAt: "2026-09-06T00:05:00Z",
    };
    const host = topicsHost({
      at: "/clusters/toast-cluster/topics/orders.v1",
      answers: {
        "/api/v1/clusters/{clusterId}/topics/{topicName}/overview": {
          topic: {
            status: "ok",
            fetchedAt: "2026-09-06T00:00:00Z",
            data: {
              row: { name: "orders.v1", internal: false, partitionCount: 6, replicationFactor: 1, outOfSyncReplicas: 0, offlinePartitions: 0 },
              partitions: [],
            },
          },
        },
        "/api/v1/clusters/{clusterId}/topics/{topicName}/deletion/plan": plan,
        "/api/v1/clusters/{clusterId}/topics/{topicName}": plan,
      },
    });
    const { container, dispose } = mount(host.view);
    await settle();

    [...container.querySelectorAll("button")]
      .find((button) => button.textContent?.includes("Delete topic"))
      ?.click();
    await settle();

    const confirmation = document.querySelector('[data-testid="planned-action-confirm"], [role="dialog"]');
    const field = confirmation?.querySelector<HTMLInputElement>('input[type="text"]');
    if (field !== null && field !== undefined) {
      field.value = "orders.v1";
      field.dispatchEvent(new Event("input", { bubbles: true }));
    }
    await flush();

    const confirm = [...(confirmation?.querySelectorAll("button") ?? [])].find(
      (button) => button.textContent?.trim() === "Delete topic",
    );
    confirm?.click();
    await settle();

    expect(toasts().map((toast) => toast.title)).toContain("orders.v1 deleted");
    // The sentence an operator is least likely to have thought of, carried into the confirmation.
    expect(toasts()[0]?.message).toContain("recreate");
    dispose();
  });

  test("a bulk outcome names both halves, because a set can fail in the middle", () => {
    // "3 topics deleted" over a selection of five leaves the operator to discover the other two.
    expect(bulkSentence("deleted", { done: ["a", "b"], failed: [] })).toBe("2 topics deleted");
    expect(
      bulkSentence("deleted", { done: ["a"], failed: [{ topic: "b", reason: "Not permitted." }] }),
    ).toBe("1 topic deleted. 1 refused: b — Not permitted.");
  });
});

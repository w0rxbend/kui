import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import {
  DEFAULT_TOPIC_QUERY,
  TopicListPage,
  type TopicListPageProps,
  type TopicListQuery,
} from "./TopicListPage.jsx";
import { TopicPage } from "./TopicPage.jsx";
import { TopicStatisticsRegion } from "./TopicStatisticsRegion.jsx";
import { TopicOverviewTab } from "./TopicOverviewTab.jsx";
import type { TopicRow } from "./types.js";

/**
 * The topic list and the topic page frame.
 *
 * Every state below is one the product reaches and none of them is reachable from a healthy
 * cluster: a topic KUI could not describe, a list that is quietly four topics short, a principal
 * who may look but not change, and the longest topic name this project has met.
 */

const TOPICS: readonly TopicRow[] = [
  {
    name: "orders.payments.v2",
    internal: false,
    partitions: 12,
    replicationFactor: 3,
    health: "in-sync",
    records: 18_442_901,
    bytes: 128_000_000_000,
    cleanupPolicy: "delete",
  },
  {
    name: "orders.payments.v2.dead-letter.retry-5m.eu-central-1.reprocessing",
    internal: false,
    partitions: 6,
    replicationFactor: 3,
    health: "under-replicated",
    records: 1_204,
    bytes: 12_400_000,
    cleanupPolicy: "delete",
  },
  {
    name: "shipments.v1",
    internal: false,
    partitions: 6,
    replicationFactor: 2,
    health: "offline",
    records: 0,
    bytes: 0,
    cleanupPolicy: "compact,delete",
  },
  {
    // No leader answered for its partitions, so its figures are unknown. They draw as dashes,
    // never as zeroes: a zero here is a fact this page does not have.
    name: "audit.events",
    internal: false,
    partitions: 3,
    replicationFactor: 3,
    health: "unknown",
  },
  {
    name: "__consumer_offsets",
    internal: true,
    partitions: 50,
    replicationFactor: 3,
    health: "in-sync",
    records: 9_007_199_254_740_991,
    bytes: 8_800_000_000,
    cleanupPolicy: "compact",
  },
];

/**
 * The list is controlled: it draws the rows it is handed and *asks* for a different set rather than
 * filtering what it holds, because a search that only looks at one page is a search that lies on a
 * cluster of four thousand topics.
 *
 * A story has no server to ask, so this plays one. It keeps the query and applies it to the whole
 * fixture — which is what makes the search box, the facet chips and the paginator all work in
 * Storybook without pretending `TopicListPage` does any of it itself.
 *
 * `showInternal` is `facet === "internal"` here for the same reason `toTopicQuery` computes it that
 * way: the chip bar is single-select, so `Internal` means Kafka's bookkeeping topics *instead of*
 * the user's rather than as well as. The two page-scoped chips are the page's own business and this
 * stub deliberately leaves them alone, so a story exercises the real narrowing.
 */
function ControlledList(
  props: Omit<TopicListPageProps, "query" | "onQueryChange" | "totalItems">,
): JSX.Element {
  const [query, setQuery] = createSignal<TopicListQuery>(DEFAULT_TOPIC_QUERY);
  const [selected, setSelected] = createSignal<ReadonlySet<string>>(new Set<string>());

  const matching = () =>
    props.topics.filter(
      (topic) =>
        (query().facet === "internal" ? topic.internal : !topic.internal) &&
        (query().search === "" || topic.name.toLowerCase().includes(query().search.toLowerCase())),
    );

  const page = () => {
    const start = (query().page - 1) * query().pageSize;
    return matching().slice(start, start + query().pageSize);
  };

  return (
    <TopicListPage
      {...props}
      topics={page()}
      query={query()}
      onQueryChange={(next) => {
        // The screen clears the ticks when the question changes, for the reason `TopicsRoute`
        // gives: a bulk bar acting on rows nobody can see is a control with a hidden subject.
        setSelected(new Set<string>());
        setQuery(next);
      }}
      totalItems={matching().length}
      selected={selected()}
      onSelectionChange={setSelected}
    />
  );
}

const listMeta = {
  title: "Topics/TopicListPage",
  component: ControlledList,
  parameters: { layout: "padded" },
} satisfies Meta<typeof ControlledList>;

export default listMeta;
type ListStory = StoryObj<typeof listMeta>;

export const Listed: ListStory = {
  args: { topics: TOPICS, onOpen: () => undefined, onCreate: () => undefined, viewportHeight: 420 },
};

/** An empty cluster. Not the same screen as a search that matched nothing. */
export const NoTopicsYet: ListStory = {
  args: { topics: [], onOpen: () => undefined, onCreate: () => undefined, viewportHeight: 420 },
};

/** Four topics the brokers would not describe. Named, rather than quietly missing. */
export const Incomplete: ListStory = {
  args: { topics: TOPICS, onOpen: () => undefined, incomplete: 4, viewportHeight: 420 },
};

/** A read-only cluster: the action stays, disabled, carrying the reason. */
export const CannotCreate: ListStory = {
  args: {
    topics: TOPICS,
    onOpen: () => undefined,
    onCreate: () => undefined,
    createDisabledReason: "This cluster is configured read-only.",
    viewportHeight: 420,
  },
};

/** Four thousand topics, to show the window doing its job. */
export const Thousands: ListStory = {
  args: {
    topics: Array.from({ length: 4000 }, (_, index) => ({
      name: `events.stream.${String(index).padStart(4, "0")}`,
      internal: false,
      partitions: 3,
      replicationFactor: 3,
      health: "in-sync" as const,
      records: index * 137,
      bytes: index * 900_000,
      cleanupPolicy: "delete",
    })),
    onOpen: () => undefined,
    viewportHeight: 420,
  },
};

// --- The page frame ------------------------------------------------------------------------------

type PageStory = StoryObj<typeof TopicPage>;

const crumbs = (
  <nav class="kui-page-tabs" aria-label="Breadcrumb">
    Topics › orders.payments.v2
  </nav>
);

/** What screenshot `02` draws above the tabs: the name, the chip, and the two actions. */
export const Page: PageStory = {
  render: () => (
    <TopicPage
      name="orders.payments.v2"
      health="in-sync"
      breadcrumb={crumbs}
      onProduce={{ label: "Produce message", onClick: () => undefined }}
      onPurge={{ label: "Purge", onClick: () => undefined }}
    />
  ),
};

/** The four health chips, which are four different facts rather than four shades of one. */
export const PageHealthStates: PageStory = {
  render: () => (
    <>
      <TopicPage name="orders.payments.v2" health="in-sync" />
      <TopicPage name="orders.payments.v2" health="under-replicated" />
      <TopicPage name="orders.payments.v2" health="offline" />
      <TopicPage name="audit.events" health="unknown" />
    </>
  ),
};

/** A principal who may read but not change. Both actions stay; both say why. */
export const PageForbidden: PageStory = {
  render: () => (
    <TopicPage
      name="orders.payments.v2"
      health="in-sync"
      onProduce={{
        label: "Produce message",
        onClick: () => undefined,
        disabledReason: "You do not hold a role that permits producing to this topic.",
      }}
      onPurge={{
        label: "Purge",
        onClick: () => undefined,
        disabledReason: "You do not hold a role that permits purging this topic.",
      }}
    />
  ),
};

/** The longest real topic name. The heading wraps; it never ellipsises. */
export const PageLongName: PageStory = {
  render: () => (
    <TopicPage
      name="orders.payments.v2.dead-letter.retry-5m.eu-central-1.reprocessing"
      health="under-replicated"
      onProduce={{ label: "Produce message", onClick: () => undefined }}
      onPurge={{ label: "Purge", onClick: () => undefined }}
    />
  ),
};

/**
 * The cards view.
 *
 * Not shown in any screenshot; `SCREENS.md` §3.3 fixes its composition rather than leaving it to be
 * invented. It is the *same page* with the table replaced — same controls, same query, same topics —
 * and each card carries what a column carries, because a view that drops the out-of-sync figure has
 * traded information for decoration.
 *
 * The toggle itself remembers the choice per user, so opening `Listed` after switching to cards here
 * will show cards. That is deliberate: a preference that resets on every navigation reads as a
 * control that does not work.
 */
export const Cards: ListStory = {
  args: { topics: TOPICS, onOpen: () => undefined, onCreate: () => undefined, viewportHeight: 420 },
  play: async ({ canvasElement }) => {
    const cards = canvasElement.querySelector<HTMLInputElement>('input[value="cards"]');
    cards?.click();
  },
};

// --- The statistics region -----------------------------------------------------------------------

/*
 * These render `TopicStatisticsRegion` directly rather than through `ControlledList`, and that is
 * the point rather than a shortcut: the region takes a *document* and cannot see the page's rows at
 * all, which is what makes "128 topics above a table of three" a state a story can draw and a
 * screen cannot fake. One `meta` per file is CSF's rule, so these are `render` stories under the
 * file's own meta, as `partitions.stories.tsx` does for the same reason.
 */
type StatsStory = StoryObj<typeof TopicStatisticsRegion>;

/** Every total measured. The design's `128 / 1,536 / 842 GB`, on a cluster nothing is wrong with. */
export const Statistics: StatsStory = {
  render: (args) => <TopicStatisticsRegion {...args} />,
  args: {
    statistics: { topics: 128, partitions: 1536, bytes: 842_000_000_000, incompleteTopics: 0 },
  },
};

/**
 * Three topics the scrape could not describe.
 *
 * The count survives — it comes from the listing — and both sums are withheld, because a sum over
 * the topics that *did* answer is a smaller number wearing the confidence of a complete one. Words,
 * never `0`: `0 B` under TOTAL STORAGE is the most reassuring possible rendering of the least
 * reassuring possible state.
 */
export const StatisticsRefused: StatsStory = {
  render: (args) => <TopicStatisticsRegion {...args} />,
  args: {
    statistics: { topics: 128, partitions: undefined, bytes: undefined, incompleteTopics: 3 },
  },
};

/** The whole document missing: the service that answers for it did not. Still not three zeroes. */
export const StatisticsUnavailable: StatsStory = {
  render: (args) => <TopicStatisticsRegion {...args} />,
  args: {
    unavailableReason: "KUI could not read this cluster's topic totals. The list below is still this cluster's.",
  },
};

/** The first paint, before anything has answered. Placeholders at the size the figures will be. */
export const StatisticsLoading: StatsStory = {
  render: (args) => <TopicStatisticsRegion {...args} />,
  args: { loading: true },
};

// --- Selection, and the bar it raises ------------------------------------------------------------

/**
 * Two topics ticked (`M13`).
 *
 * The set is the *page's*, not the table's: the same two ticks are on the cards in the design's own
 * capture, and switching treatment here keeps them. `Delete` is disabled with its reason rather
 * than hidden — if it disappeared for a principal without the grant, `Empty` would move into its
 * place and one gesture would do two different irreversible things to two different people.
 */
export const Selected: ListStory = {
  args: { topics: TOPICS, onOpen: () => undefined, onCreate: () => undefined, viewportHeight: 420 },
  play: async ({ canvasElement }) => {
    const ticks = [...canvasElement.querySelectorAll<HTMLInputElement>('tbody input[type="checkbox"]')];
    ticks[0]?.click();
    ticks[1]?.click();
  },
};

/**
 * A chip the cluster cannot apply.
 *
 * `Out of sync` and `Compacted` are derived from fields the topic index does not carry, so they
 * narrow the page — and the page says so. A filter that narrows a page while looking like it
 * narrows a cluster is the defect this list already fixed once, for the search box.
 */
export const PageScopedFacet: ListStory = {
  args: { topics: TOPICS, onOpen: () => undefined, viewportHeight: 420 },
  play: async ({ canvasElement }) => {
    [...canvasElement.querySelectorAll("button")]
      .find((button) => button.textContent?.trim() === "Compacted")
      ?.click();
  },
};

// --- The topic's Overview tab --------------------------------------------------------------------

type OverviewStory = StoryObj<typeof TopicOverviewTab>;

const OVERVIEW_PARTITIONS = Array.from({ length: 6 }, (_, partition) => ({
  partition,
  leader: 1,
  replicas: [1, 2, 3],
  inSync: [1, 2, 3],
  earliestOffset: 0,
  latestOffset: 1_204 + partition,
  messageCount: 1_204 + partition,
  sizeBytes: 48_200_000 + partition,
}));

/** The tab the strip opens by default, which drew nothing at all before this wave. */
export const TopicOverview: OverviewStory = {
  render: (args) => <TopicOverviewTab {...args} />,
  args: {
    partitionsHref: "/ui/clusters/quickstart/topics/orders.payments.v2?tab=partitions",
    overview: {
      topic: {
        name: "orders.payments.v2",
        internal: false,
        partitions: 12,
        replicationFactor: 3,
        health: "in-sync",
        records: 18_442_901,
        bytes: 48_200_000_000,
        messagesPerSecond: 1_204,
        cleanupPolicy: "delete",
      },
      partitions: OVERVIEW_PARTITIONS,
      consumerGroups: 2,
    },
  },
};

/**
 * The same tab on a cluster that measures less of itself.
 *
 * No log-directory size, no produce rate — both genuinely `null` on the quickstart's single broker
 * — and no answer from the consumer service. Three sentences, no zeroes: a topic with no bytes on
 * disk, a topic nobody produces to and a topic nothing reads are all real states and none of them
 * is what these tiles are showing.
 */
export const TopicOverviewNotMeasured: OverviewStory = {
  render: (args) => <TopicOverviewTab {...args} />,
  args: {
    partitionsHref: "/ui/clusters/quickstart/topics/orders.v1?tab=partitions",
    overview: {
      topic: {
        name: "orders.v1",
        internal: false,
        partitions: 6,
        replicationFactor: 1,
        health: "in-sync",
        records: 16,
      },
      partitions: OVERVIEW_PARTITIONS.slice(0, 3).map((row) => ({
        ...row,
        sizeBytes: null,
        messageCount: null,
      })),
      consumerGroups: undefined,
    },
  },
};

/** The first paint of the tab: placeholders where the four figures will be. */
export const TopicOverviewLoading: OverviewStory = {
  render: (args) => <TopicOverviewTab {...args} />,
  args: {
    partitionsHref: "/ui/clusters/quickstart/topics/orders.v1?tab=partitions",
    loading: true,
  },
};

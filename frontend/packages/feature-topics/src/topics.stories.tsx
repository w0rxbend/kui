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
 * fixture — which is what makes the search box, the internal-topics switch and the paginator all
 * work in Storybook without pretending `TopicListPage` does any of it itself.
 */
function ControlledList(
  props: Omit<TopicListPageProps, "query" | "onQueryChange" | "totalItems">,
): JSX.Element {
  const [query, setQuery] = createSignal<TopicListQuery>(DEFAULT_TOPIC_QUERY);

  const matching = () =>
    props.topics.filter(
      (topic) =>
        (query().showInternal || !topic.internal) &&
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
      onQueryChange={setQuery}
      totalItems={matching().length}
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

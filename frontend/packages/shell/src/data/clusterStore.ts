/**
 * Everything the frame knows about the selected cluster: its head, its storage meter, its badges.
 *
 * ## Why the frame gets its own store
 *
 * The drawer is not a screen. It is drawn on every screen, it outlives every navigation, and the
 * three things on it — the cluster block at the head, the four counts down the side, the storage
 * meter at the foot — come from six endpoints across four services. A screen that fetched them for
 * itself would refetch all six every time the user opened a topic, and two screens open on the same
 * cluster would each hold their own copy and disagree about it for thirty seconds at a time.
 *
 * So they are held here, over the kernel's `createQueryCache`, which already solves both halves:
 * several readers of one key share one request, and a failing refetch never throws away the last
 * good answer. What this module adds is the part the cache cannot know — which endpoint answers
 * which question, and what each answer *means* when it is missing.
 *
 * ## Nothing is fetched until something asks
 *
 * Each of the three members below is a getter, and each reads only the caches it needs. Reading
 * `summary` fetches the cluster detail and nothing else; reading `counts` fetches the four lists.
 * That is `QueryCache.watch`'s own contract — the request happens when the accessor is read
 * inside a reactive scope — carried through rather than defeated by an eager memo, so a deployment
 * that hides the drawer pays for none of this.
 *
 * A member read *outside* a reactive scope answers, and it also fetches: the subscription is made,
 * the loader runs, and the binding is released again with nothing left watching for the answer. So
 * a test against this file reads its members inside a memo — not because an untracked read would be
 * inert, but because it would issue the request and then throw the answer away.
 *
 * ## Six failures, not one
 *
 * Every member is a `Reading`, and every one of the six requests fails on its own. A cluster whose
 * schema registry is not configured still has a broker count; a cluster whose disks could not be
 * read still has a name at its head. Folding them into one "the cluster is unavailable" would make
 * the whole frame as available as its least available part, which on an operations tool is the
 * worst possible arrangement — the part most likely to be down is the one describing what broke.
 */

import { createMemo } from "solid-js";
import { userMessage, type components } from "@kui/api";
import { createQueryCache, useKui, type QueryCache, type QueryState } from "@kui/kernel";

import type { BrokerStorage } from "../chrome/StorageMeter.jsx";
import type { ClusterHealth, ClusterSummary, NavCount, NavCounts } from "../chrome/types.js";
import { readSection } from "../overview/load.js";
import type {
  Broker,
  ClusterSummary as ClusterSummaryDto,
  ConsumerGroup,
  LogDir,
} from "../overview/model.js";
import { pending, unknown, value, type Reading } from "../overview/reading.js";

/** What the frame is given. Four answers, each of which can be missing for its own reason. */
export interface ClusterFacts {
  readonly summary: Reading<ClusterSummary>;
  readonly storage: Reading<readonly BrokerStorage[]>;
  readonly counts: Reading<NavCounts>;
  /**
   * Every topic name on the cluster, for the drawer's tree.
   *
   * The names-only index and not a page of the topic list, which is the whole reason that endpoint
   * exists: the tree's group counts are counts of the *cluster*, and folding them out of whichever
   * twenty-five rows a list happened to return would produce a drawer whose figures changed when
   * somebody sorted a table. Unpaged is affordable because a name is a name — four thousand of them
   * is a few hundred kilobytes, once, shared by every screen through the same cache as the rest.
   */
  readonly topicNames: Reading<readonly string[]>;
}

/**
 * One page is enough to learn a total.
 *
 * The three count endpoints all answer with a page whose `page.totalItems` is the whole list's
 * size, so asking for one row rather than the default twenty-five is the same answer for a
 * twenty-fifth of the payload — on a cluster with four thousand topics that is the difference
 * between a drawer that costs nothing to draw and one that does not.
 *
 * The consumer groups are the exception and ask for more; see {@link consumerCount}.
 */
const COUNT_PAGE_SIZE = 1;

/**
 * How many consumer groups are read in order to count the ones that are rebalancing.
 *
 * A page, because there is no endpoint that counts them — and the size matters, because the count
 * is only *claimed* when the page holds the whole list. See {@link consumerCount}.
 */
const GROUP_PAGE_SIZE = 200;

/**
 * The cluster's facts, refetched as the selection changes.
 *
 * `clusterId` is a function rather than a string for the reason `useKui`'s `cluster()` is one: the
 * selection changes while the frame is mounted — the environment rail is one click away — and a
 * store handed a value would go on describing the cluster the user left, which is the most
 * convincing kind of wrong data there is.
 */
export function createClusterStore(clusterId: () => string | undefined): ClusterFacts {
  const { api } = useKui();

  /*
   * A cache per endpoint, keyed by the cluster id alone.
   *
   * The key needs no prefix because each cache holds exactly one endpoint's answers, so two of them
   * cannot collide however similar their keys look. `queryKey` is for the caches that hold several
   * kinds of thing; here it would only add a constant to every key.
   */
  const detail = createQueryCache({
    fetch: (id: string) =>
      api.get("/api/v1/clusters/{clusterId}", { params: { path: { clusterId: id } } }),
  });

  const brokers = createQueryCache({
    fetch: (id: string) =>
      api.get("/api/v1/clusters/{clusterId}/brokers", { params: { path: { clusterId: id } } }),
  });

  const logDirs = createQueryCache({
    fetch: (id: string) =>
      api.get("/api/v1/clusters/{clusterId}/log-dirs", { params: { path: { clusterId: id } } }),
  });

  const topics = createQueryCache({
    fetch: (id: string) =>
      api.get("/api/v1/clusters/{clusterId}/topics", {
        /* Internal topics included: the tree counts them under its own `internal` row, and a total
           that silently left them out would not match the sum of the rows beneath it. */
        params: {
          path: { clusterId: id },
          query: { pageSize: COUNT_PAGE_SIZE, showInternal: true },
        },
      }),
  });

  const groups = createQueryCache({
    fetch: (id: string) =>
      api.get("/api/v1/clusters/{clusterId}/consumer-groups", {
        params: { path: { clusterId: id }, query: { pageSize: GROUP_PAGE_SIZE } },
      }),
  });

  /* The tree's names. A seventh cache rather than a second reading off `topics` above: that one
     asks for a single row and reads only `page.totalItems`, and widening it to carry the names
     would make the badge — which every deployment draws — pay for the tree, which only a reader who
     expands it ever sees. Each getter below reads only the caches it needs, so an unexpanded drawer
     never asks for this at all. */
  const names = createQueryCache({
    fetch: (id: string) =>
      api.get("/api/v1/clusters/{clusterId}/topics/names", {
        params: { path: { clusterId: id } },
      }),
  });

  const subjects = createQueryCache({
    fetch: (id: string) =>
      api.get("/api/v1/clusters/{clusterId}/schemas/subjects", {
        params: { path: { clusterId: id }, query: { pageSize: COUNT_PAGE_SIZE } },
      }),
  });

  const detailState = watcher(detail, clusterId);
  const brokerState = watcher(brokers, clusterId);
  const logDirState = watcher(logDirs, clusterId);
  const topicState = watcher(topics, clusterId);
  const groupState = watcher(groups, clusterId);
  const subjectState = watcher(subjects, clusterId);
  const nameState = watcher(names, clusterId);

  /*
   * Getters rather than fields, so that reading `facts.summary` inside a component's JSX is a
   * tracked read of the caches behind it. A plain object of values would be computed once, when the
   * store was built, and the drawer would show the skeletons for ever.
   */
  return {
    get summary(): Reading<ClusterSummary> {
      return readingOf(detailState(), (body) => summaryOf(body.cluster));
    },
    get storage(): Reading<readonly BrokerStorage[]> {
      return readingOf(logDirState(), (body) => {
        const dirs = readSection<readonly LogDir[]>(body.logDirs, "the log directories");
        return dirs.kind === "value" ? value(storageOf(dirs.value)) : dirs;
      });
    },
    get counts(): Reading<NavCounts> {
      return countsOf({
        brokers: brokerState(),
        topics: topicState(),
        groups: groupState(),
        subjects: subjectState(),
      });
    },
    get topicNames(): Reading<readonly string[]> {
      return readingOf(nameState(), (body) =>
        readSection<readonly string[]>(body.names, "the topic names"),
      );
    },
  };
}

/* --- The reactive plumbing -------------------------------------------------------------------- */

/**
 * One cache, watched for whichever cluster is selected.
 *
 * The subscription is built in a memo that reads the *id* and not the state. Building it where the
 * state is read would tear the subscription down and set it up again on every answer, because the
 * answer is what the reading computation depends on — the watcher count would flap, and with it the
 * cache's eviction bookkeeping. Here the memo recomputes only when the selection changes, which is
 * exactly when the old subscription should be released.
 */
function watcher<A>(
  cache: QueryCache<A>,
  clusterId: () => string | undefined,
): () => QueryState<A> | undefined {
  const subscription = createMemo(() => {
    const id = clusterId();
    return id === undefined ? undefined : cache.watch(id);
  });
  return () => subscription()?.();
}

/**
 * A cache's state as a reading, keeping the last good answer through a failure.
 *
 * The precedence is ADR-032's: last-known-good data with a stale badge beats a blank panel, so a
 * refetch that fails leaves the numbers on screen rather than replacing them with a sentence. Only
 * a key that has *never* answered becomes `unknown`, which is the state a fallback panel is for.
 *
 * No cluster selected is `pending` rather than `unknown`. Nothing has been asked, so nothing has
 * failed; a skeleton is honest about that and a sentence saying the cluster could not be read is
 * not.
 */
function readingOf<A, B>(
  state: QueryState<A> | undefined,
  map: (value: A) => Reading<B>,
): Reading<B> {
  if (state === undefined) return pending();
  if (state.lastGood !== undefined) return map(state.lastGood);
  const outcome = state.outcome;
  if (outcome === undefined) return pending();
  return outcome.ok ? map(outcome.value) : unknown(userMessage(outcome.error));
}

/* --- The cluster block at the drawer's head --------------------------------------------------- */

/** One configured cluster's row, as far as the head reads it. */
interface ClusterRow {
  readonly id: string;
  readonly name: string;
  readonly summary: unknown;
}

/**
 * The head's three-part caption, assembled from the last scrape.
 *
 * Each optional field is omitted rather than set to `undefined`, because the components below treat
 * a present-but-undefined field and an absent one identically only by accident — and because the
 * caption drops the parts it does not have rather than writing a dash beside a word.
 *
 * ## Why the argument is `unknown` and not the row's own type
 *
 * The generated type says `ClusterDetailResponse.cluster` is required, and it is — of every answer
 * this endpoint produces. It is not required of every **200** the browser can receive: a reverse
 * proxy that answers an authentication challenge with its own JSON, a gateway rewritten to a
 * different route, or a deployment served an `index.html` under a `content-type` the client
 * believes, all reach this line with a body that decodes and is not the envelope. This function
 * used to read `row.summary` as its first statement, so `undefined` threw a `TypeError` inside a
 * memo — which Solid reports as a halted reactive graph, not as a failed request, and which takes
 * the **whole frame** away rather than the one panel that could not be read.
 *
 * So the identity is checked before it is used, exactly as the neighbouring readings check their
 * sections through `readSection`. A body that is not the envelope is a reading with no value, and
 * the drawer draws the cluster block's own "not known" rendering over it.
 */
function summaryOf(row: unknown): Reading<ClusterSummary> {
  if (typeof row !== "object" || row === null) {
    return unknown("The cluster's own record could not be read.");
  }
  const record = row as Partial<ClusterRow>;
  /* The id and the name are the head's *identity*, and the head cannot draw without them: a block
     titled with an empty string is a rendering fault on screen, where "this cluster could not be
     read" is a fact. The name falls back to the id — the same degradation `clusterSummaries` makes
     — and the id falls back to nothing at all. */
  if (typeof record.id !== "string" || record.id.length === 0) {
    return unknown("The cluster's own record could not be read.");
  }

  const section = readSection<ClusterSummaryDto>(record.summary, "the cluster summary");
  if (section.kind !== "value") {
    /* The identity is known even when the scrape is not — it came with the row — but the head is
     * still told this is a reading it does not have, so it can draw the cluster with an unknown dot
     * instead of a healthy one. `readSection` has already turned the four not-ok statuses into the
     * sentence that says which of them it was. */
    return section;
  }

  const scraped = section.value;
  const under = scraped.underReplicatedPartitionCount;
  return value({
    id: record.id,
    name: typeof record.name === "string" && record.name.length > 0 ? record.name : record.id,
    health: healthOf(scraped),
    ...(scraped.version === undefined ? {} : { version: scraped.version }),
    ...(scraped.brokerCount === undefined ? {} : { brokerCount: scraped.brokerCount }),
    ...(under === undefined ? {} : { defects: { underReplicatedPartitions: under } }),
  });
}

/**
 * The dot's colour, and the one thing it must not do is be green by default.
 *
 * A cluster that reported neither an offline nor an under-replicated count is `unknown`, not
 * `healthy`. Both counts come back `null` from a broker that does not report them and from a scrape
 * that has not completed, and `overview/load.ts` documents at length what happened the last time
 * this product read those nulls as zeros: it announced "Zero under-replicated partitions. You may
 * sip your coffee" over a cluster it had learned nothing about.
 *
 * `unreachable` is not decided here. It means the scrape itself failed, which is a reading that has
 * no value at all — see {@link summaryOf}.
 */
function healthOf(scraped: ClusterSummaryDto): ClusterHealth {
  const offline = scraped.offlinePartitionCount;
  const under = scraped.underReplicatedPartitionCount;
  if (offline === undefined && under === undefined) return "unknown";
  if ((offline ?? 0) > 0 || (under ?? 0) > 0) return "degraded";
  return "healthy";
}

/* --- The storage meter at the drawer's foot --------------------------------------------------- */

/**
 * One row per broker, from the directories that reported a size.
 *
 * A directory that reported no total is left out of *both* sums rather than counted as zero — the
 * same rule `diskPercentOf` applies in `overview/model.ts`, and for the same reason: a broker with
 * one Kafka 3.3 disk and one older one would otherwise show a percentage computed over half its
 * capacity, which is a plausible-looking number that is simply wrong. A failed disk is not a disk
 * of size zero.
 *
 * A broker none of whose directories reported a size produces **no row at all**. It is tempting to
 * emit one with `totalBytes: 0` so the meter has a segment per broker; `StorageMeter` would draw it
 * as an idle segment and the caption would count it in neither sum, so nothing would be *wrong* —
 * but the bar would then have as many segments as the cluster has brokers whether or not any of
 * them was measured, and the one thing the meter is for is showing which broker is hot.
 */
function storageOf(dirs: readonly LogDir[]): readonly BrokerStorage[] {
  const totals = new Map<number, { used: number; total: number }>();

  for (const dir of dirs) {
    if (dir.totalBytes === undefined || dir.usableBytes === undefined) continue;
    const held = totals.get(dir.brokerId) ?? { used: 0, total: 0 };
    totals.set(dir.brokerId, {
      used: held.used + (dir.totalBytes - dir.usableBytes),
      total: held.total + dir.totalBytes,
    });
  }

  return [...totals]
    .sort(([left], [right]) => left - right)
    /* `broker-3`, which is what the design's caption names ("broker-3 hot") and what the segment
       titles read. Deliberately not the broker's host: a host is a network address, several brokers
       can share one, and the meter is identifying a broker rather than telling anybody where to
       connect. */
    .map(([id, sums]) => ({ id: `broker-${id}`, usedBytes: sums.used, totalBytes: sums.total }));
}

/**
 * The rows the meter is actually passed, for a reading that may not have a value.
 *
 * An empty array is the meter's own honest "not known" rendering: it draws a single neutral track,
 * an em dash for the percentage, and says the disk usage could not be read. That is the right
 * picture for every one of the three ways this reading can have no value, and it is emphatically
 * not the same as a row of zeros, which draws an empty bar and reads as "your disks are empty".
 */
export function brokerStorageOf(
  reading: Reading<readonly BrokerStorage[]>,
): readonly BrokerStorage[] {
  return reading.kind === "value" ? reading.value : [];
}

/* --- The four badges down the drawer's side --------------------------------------------------- */

/** The four answers the counts are folded from, each as its own cache state. */
interface CountStates {
  readonly brokers: QueryState<{ readonly brokers: unknown }> | undefined;
  readonly topics: QueryState<{ readonly topics: unknown }> | undefined;
  readonly groups: QueryState<{ readonly groups: unknown }> | undefined;
  readonly subjects: QueryState<SubjectPage> | undefined;
}

/**
 * The subjects endpoint answers with the page directly: a subject list has nothing to be partial
 * about, so it is not wrapped in a section (see `fetchSubjects` in `feature-schemas`).
 *
 * The **generated** page, and not a hand-written mirror of it. There was one here — an
 * `interface SubjectPage { items?: { subject: string }[]; page?: { totalItems?: number } }` — and
 * it is the standing example of what not to write in this repository: the subjects endpoint was
 * widened from `string[]` to a summary row while that interface went on compiling, so the one thing
 * a wire-shape change is supposed to do here, break, is the one thing it could not. An alias onto
 * the generated schema costs nothing and cannot drift.
 */
type SubjectPage = components["schemas"]["PageDto_A"];

/**
 * The counts, with a member present only for a question that has been answered.
 *
 * Four independent sources cannot share one failure, so this is a value as soon as *any* of them
 * has answered, and the ones that have not are simply absent — which `navigationGroups` draws as a
 * row with no badge. That is the rule the whole vocabulary is built around: an unknown count
 * produces no badge, never a `0`.
 *
 * It is `pending` only while nothing has come back at all, which is the moment the drawer first
 * appears and the only moment at which a badge-shaped skeleton would be worth drawing.
 */
function countsOf(states: CountStates): Reading<NavCounts> {
  const counts: NavCounts = {
    ...maybe("clusters", brokerCountOf(states.brokers)),
    ...maybe("topics", pageTotalOf(states.topics, (body) => body.topics, "the topic list")),
    ...maybe("consumers", consumerCount(states.groups)),
    ...maybe("schemas", subjectCount(states.subjects)),
  };
  if (Object.keys(counts).length > 0) return value(counts);

  /*
   * No badge to draw, and the two ways of getting here are two different pictures.
   *
   * `reading.ts`'s headline rule decides which: `pending` means nothing has been answered,
   * `unknown` means the answer did not come. While any of the four is still out the drawer is
   * genuinely early and a badge-shaped skeleton is honest. When all four have come back with
   * nothing it is not, and this used to answer `pending` anyway — so on a cluster whose four
   * services are down, a drawer that draws skeletons on `pending` drew them for ever. The fold
   * cannot see the failure any other way: `valueOf` reads only `lastGood` and never looks at
   * `outcome`, so a request that failed and a request still in flight are the same absent number
   * to every line above this one.
   */
  const all = [states.brokers, states.topics, states.groups, states.subjects];
  return all.some((state) => state === undefined || state.pending)
    ? pending()
    : unknown(firstFailure(all) ?? "The cluster's counts could not be read.");
}

/**
 * The first failure among the four, as a sentence the drawer can show.
 *
 * The first and not all four: they are almost always one outage seen four times — a gateway that is
 * not answering answers none of them — and four copies of one sentence says less than one copy of
 * it. `undefined` when every request came back *successfully* and simply carried no number, which
 * is a real state (four sections all answering `not_configured`) and one no error message fits.
 */
function firstFailure(states: readonly (QueryState<unknown> | undefined)[]): string | undefined {
  for (const state of states) {
    const outcome = state?.outcome;
    if (outcome !== undefined && !outcome.ok) return userMessage(outcome.error);
  }
  return undefined;
}

/** One member of the table, or nothing at all — never a member holding `undefined`. */
function maybe(key: keyof NavCounts, count: NavCount | undefined): NavCounts {
  return count === undefined ? {} : { [key]: count };
}

/**
 * How many brokers answered — and deliberately not `3/3`.
 *
 * §2.2 draws a fraction, and `brokerCount` in `overview/model.ts` sets out at length why the
 * backend cannot honestly produce its denominator: Kafka has no notion of an expected broker count,
 * `describeCluster` reports the brokers that answered, so the fraction would be `n/n` always — 100%
 * by construction, and indistinguishable on screen from a fraction that could fall to `2/3` in an
 * outage. `NavCount` can express the fraction, for the day something can honestly supply one;
 * this source cannot, so it states the count and lets the head's caption carry the health.
 */
function brokerCountOf(
  state: QueryState<{ readonly brokers: unknown }> | undefined,
): NavCount | undefined {
  const list = valueOf(state, (body) =>
    readSection<readonly Broker[]>(body.brokers, "the broker list"),
  );
  return list === undefined ? undefined : { kind: "total", value: list.length, noun: "brokers" };
}

/**
 * A list's size, taken from the page's own total rather than from the rows on the page.
 *
 * The endpoints page, so the number of items in the answer is "how many fit on the first page" and
 * on any cluster worth the name that is 25. `overview/load.ts` shipped precisely that bug once; the
 * fallback when the server sends no total is no badge, because a page size drawn as a total is a
 * wrong number that looks exactly like a right one.
 */
function pageTotalOf<A>(
  state: QueryState<A> | undefined,
  section: (body: A) => unknown,
  noun: string,
): NavCount | undefined {
  const page = valueOf(state, (body) =>
    readSection<{ readonly page?: { readonly totalItems?: number } }>(section(body), noun),
  );
  const total = page?.page?.totalItems;
  return typeof total === "number" ? { kind: "total", value: total } : undefined;
}

/**
 * `1 rebalancing` when that is knowably true, and the group total otherwise.
 *
 * The design's amber `1 rebalancing` is the more useful of the two badges, and it is also the one
 * that is easy to get wrong: there is no endpoint that counts rebalancing groups, so the only way
 * to know is to look at the states on a page — and a page is not the list. A cluster with four
 * hundred groups would report the rebalancing ones among the first two hundred and quietly claim
 * that was all of them, which is a badge that says "1 rebalancing" while nine others are stuck.
 *
 * So the defect count is only claimed when the page demonstrably holds every group. Otherwise the
 * row falls back to the total, which the server counted and which is true at any size.
 */
function consumerCount(
  state: QueryState<{ readonly groups: unknown }> | undefined,
): NavCount | undefined {
  const page = valueOf(state, (body) =>
    readSection<{
      readonly items?: readonly ConsumerGroup[];
      readonly page?: { readonly totalItems?: number };
    }>(body.groups, "the consumer groups"),
  );
  if (page === undefined) return undefined;

  const items = page.items ?? [];
  const total = page.page?.totalItems;
  const whole = typeof total === "number" && total <= items.length;
  const rebalancing = items.filter((group) => isRebalancing(group.state)).length;

  if (whole && rebalancing > 0) {
    return { kind: "defect", value: rebalancing, noun: "rebalancing", severity: "warning" };
  }
  return typeof total === "number" ? { kind: "total", value: total } : undefined;
}

/**
 * The two states Kafka uses while a group is moving its partitions around.
 *
 * A prefix test rather than a pair of equalities, because the wire carries the enum's name and the
 * names have changed once already (`PREPARING_REBALANCE` was `PreparingRebalance` before KIP-848's
 * vocabulary settled). Matching the ending misses nothing a rename would add.
 */
function isRebalancing(state: string): boolean {
  return state.toUpperCase().endsWith("REBALANCE");
}

/** The registry's subject count, from the page total the endpoint already carries. */
function subjectCount(state: QueryState<SubjectPage> | undefined): NavCount | undefined {
  const total = valueOf(state, (body) => value(body))?.page?.totalItems;
  return typeof total === "number" ? { kind: "total", value: total } : undefined;
}

/**
 * The last good answer a cache holds, mapped through a section, or `undefined`.
 *
 * The counts want none of `Reading`'s distinctions — a badge is drawn or it is not — so this
 * flattens all four of the ways there might be no number into the one the drawer can act on. The
 * distinctions still matter for the head and the meter, which is why they keep their readings.
 */
function valueOf<A, B>(
  state: QueryState<A> | undefined,
  map: (value: A) => Reading<B>,
): B | undefined {
  if (state?.lastGood === undefined) return undefined;
  const reading = map(state.lastGood);
  return reading.kind === "value" ? reading.value : undefined;
}

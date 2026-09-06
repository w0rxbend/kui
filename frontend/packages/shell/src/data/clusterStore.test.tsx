/**
 * The frame's store, driven against a stub gateway.
 *
 * Every case here is a question about what the drawer says when it does *not* know something, which
 * is the state this file exists to get right: an unread disk must not draw as an empty one, a
 * cluster that reported no partition counts must not draw a green dot, and a count nobody could
 * fetch must not draw a zero.
 *
 * The store is created inside a real render because it reads the API client out of `useKui`, and
 * every member is read through a memo because that is the only kind of read the cache keeps alive.
 * An untracked read is not inert — it subscribes, fetches, and unbinds again — so a probe built out
 * of plain calls would issue every request and then discard the answers, and each assertion below
 * would be about a request whose reply nothing was left watching for.
 */
import { describe, expect, it } from "vitest";
import { render } from "@solidjs/web";
import { createMemo, flush } from "solid-js";
import { userMessage, type ApiError, type KuiApiClient } from "@kui/api";
import { KuiProvider, type KuiContextValue, type KuiPaths } from "@kui/kernel";

import type { BrokerStorage } from "../chrome/StorageMeter.jsx";
import type { ClusterSummary, NavCounts } from "../chrome/types.js";
import { readingValue, unknown, type Reading } from "../overview/reading.js";
import { brokerStorageOf, createClusterStore } from "./clusterStore.js";

/** A gateway that answers from a table, and answers "unreachable" for anything not in it. */
function stubApi(bodies: Readonly<Record<string, unknown>>): KuiApiClient {
  return {
    get: (path: string) => {
      const body = bodies[path];
      return Promise.resolve(
        body === undefined
          ? { ok: false, error: { kind: "unreachable", cause: "no stub for this path" } }
          : { ok: true, value: body },
      );
    },
  } as unknown as KuiApiClient;
}

const DETAIL = "/api/v1/clusters/{clusterId}";
const BROKERS = "/api/v1/clusters/{clusterId}/brokers";
const LOG_DIRS = "/api/v1/clusters/{clusterId}/log-dirs";
const TOPICS = "/api/v1/clusters/{clusterId}/topics";
const GROUPS = "/api/v1/clusters/{clusterId}/consumer-groups";
const SUBJECTS = "/api/v1/clusters/{clusterId}/schemas/subjects";
const NAMES = "/api/v1/clusters/{clusterId}/topics/names";

const ok = (data: unknown) => ({ status: "ok", data, fetchedAt: "2026-09-05T15:35:58.778Z" });

/**
 * Four brokers, and every shape of unmeasured directory the rule has to survive.
 *
 * The first three rows are the ordinary case. The last three are the fixture's whole reason for
 * existing, because the skip-the-unmeasured rule is a rule that *passes* when it is broken unless
 * the data discriminates: replacing the `continue` in `storageOf` with a zero-fill left this suite
 * green for the whole of wave 1, since the only unmeasured directory then present belonged to a
 * broker that also had a measured one, where a zero contributes nothing to either sum.
 *
 * Two rows discriminate and both are here. `/data/half` reports a size and no usable figure, which
 * a zero-fill turns into 400 GB of capacity that is 100% full — a plausible-looking number invented
 * out of a disk nobody measured. And broker 4 has nothing measured at all, which a zero-fill turns
 * into a row of `0 / 0`: a segment on the meter for a broker whose disks are entirely unknown,
 * which is the one thing the meter is for showing.
 */
const LOG_DIR_ROWS = [
  { brokerId: 1, path: "/data/a", totalBytes: 100, usableBytes: 40 },
  { brokerId: 1, path: "/data/b", totalBytes: 100, usableBytes: 60 },
  { brokerId: 2, path: "/data/a", totalBytes: 200, usableBytes: 50 },
  /* A size, and no usable figure. Kafka reports the pair or reports neither; a broker that has just
     answered the first half of the question has not answered it. */
  { brokerId: 2, path: "/data/half", totalBytes: 400 },
  { brokerId: 3, path: "/data/a", totalBytes: 100, usableBytes: 100 },
  /* Offline, and therefore reporting no size at all. Not a disk of size zero. */
  { brokerId: 3, path: "/data/broken", error: "KafkaStorageException" },
  /* Every directory this broker has, and not one of them measured. */
  { brokerId: 4, path: "/data/a", error: "KafkaStorageException" },
  { brokerId: 4, path: "/data/b", error: "KafkaStorageException" },
];

const WHOLE_CLUSTER: Readonly<Record<string, unknown>> = {
  [DETAIL]: {
    cluster: {
      id: "prod",
      name: "prod-kyiv-01",
      summary: ok({
        version: "3.7.0",
        brokerCount: 3,
        onlinePartitionCount: 128,
        offlinePartitionCount: 0,
        underReplicatedPartitionCount: 1,
      }),
    },
  },
  [BROKERS]: { brokers: ok([{ id: 1 }, { id: 2 }, { id: 3 }]) },
  [LOG_DIRS]: { logDirs: ok(LOG_DIR_ROWS) },
  [TOPICS]: { topics: ok({ items: [], page: { totalItems: 128 } }), incompleteTopics: 0 },
  [GROUPS]: {
    groups: ok({
      items: [
        { groupId: "billing", state: "STABLE" },
        { groupId: "search", state: "PREPARING_REBALANCE" },
      ],
      page: { totalItems: 2 },
    }),
    incompleteCoordinators: 0,
  },
  [SUBJECTS]: { items: [{ subject: "orders-value" }], page: { totalItems: 6 } },
  [NAMES]: { names: ok(["orders.v1", "orders.v2", "__consumer_offsets"]) },
};

interface Probe {
  readonly summary: () => Reading<ClusterSummary>;
  readonly storage: () => Reading<readonly BrokerStorage[]>;
  readonly counts: () => Reading<NavCounts>;
  readonly topicNames: () => Reading<readonly string[]>;
  readonly dispose: () => void;
}

/**
 * Mounts a store and hands back its three members as accessors.
 *
 * The memos are what make the reads tracked, and they are eager, so the requests are in flight
 * before {@link settle} is awaited.
 */
function probe(bodies: Readonly<Record<string, unknown>>): Probe {
  return probeWith(stubApi(bodies));
}

/** The same, over a client the case builds for itself: a stalled request, a counting fake. */
function probeWith(api: KuiApiClient): Probe {
  const context: KuiContextValue = {
    api,
    cluster: () => "prod",
    permits: () => true,
    paths: {} as KuiPaths,
    report: () => {},
  };

  let captured: Omit<Probe, "dispose"> | undefined;
  const host = document.createElement("div");
  document.body.appendChild(host);

  const Reader = () => {
    const facts = createClusterStore(() => "prod");
    captured = {
      summary: createMemo(() => facts.summary),
      storage: createMemo(() => facts.storage),
      counts: createMemo(() => facts.counts),
      topicNames: createMemo(() => facts.topicNames),
    };
    return null;
  };

  const dispose = render(
    (() => (
      <KuiProvider value={context}>
        <Reader />
      </KuiProvider>
    )) as never,
    host,
  );
  flush();

  if (captured === undefined) throw new Error("the probe never rendered");
  return {
    ...captured,
    dispose: () => {
      dispose();
      host.remove();
    },
  };
}

/** Lets the stubbed promises resolve and the reactive graph catch up. */
async function settle(): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) await Promise.resolve();
  flush();
}

describe("the cluster block at the drawer's head", () => {
  it("names the cluster and carries the two figures the caption is made of", async () => {
    const store = probe(WHOLE_CLUSTER);
    await settle();

    expect(readingValue(store.summary())).toEqual({
      id: "prod",
      name: "prod-kyiv-01",
      health: "degraded",
      version: "3.7.0",
      brokerCount: 3,
      defects: { underReplicatedPartitions: 1 },
    });
    store.dispose();
  });

  it("leaves out a figure the scrape did not report, rather than sending a zero", async () => {
    /* The caption drops the parts it does not have. A `brokerCount: 0` here would be printed as
     * "0 brokers" beside a cluster that is answering perfectly well. */
    const store = probe({
      ...WHOLE_CLUSTER,
      [DETAIL]: { cluster: { id: "prod", name: "prod-kyiv-01", summary: ok({}) } },
    });
    await settle();

    const summary = readingValue(store.summary());
    expect(summary).toEqual({ id: "prod", name: "prod-kyiv-01", health: "unknown" });
    expect(summary && "brokerCount" in summary).toBe(false);
    expect(summary && "defects" in summary).toBe(false);
    store.dispose();
  });

  it("does not call a cluster healthy on the strength of counts it never received", async () => {
    /* Both counts come back `null` from a broker that does not report them and from a scrape that
     * has not finished. Reading those as zeros is how this product once announced "Zero
     * under-replicated partitions. You may sip your coffee" about a cluster it knew nothing of. */
    const store = probe({
      ...WHOLE_CLUSTER,
      [DETAIL]: {
        cluster: {
          id: "prod",
          name: "prod-kyiv-01",
          summary: ok({
            brokerCount: 3,
            offlinePartitionCount: null,
            underReplicatedPartitionCount: null,
          }),
        },
      },
    });
    await settle();

    expect(readingValue(store.summary())?.health).toBe("unknown");
    store.dispose();
  });

  it("has no value at all when the scrape itself failed", async () => {
    const store = probe({
      ...WHOLE_CLUSTER,
      [DETAIL]: {
        cluster: {
          id: "prod",
          name: "prod-kyiv-01",
          summary: { status: "unavailable", reason: "upstream_unavailable", message: "no route" },
        },
      },
    });
    await settle();

    const summary = store.summary();
    expect(summary.kind).toBe("unknown");
    expect(summary.kind === "unknown" && summary.why).toBe("no route");
    store.dispose();
  });
});

describe("the storage meter at the drawer's foot", () => {
  it("sums each broker's directories into one row", async () => {
    const store = probe(WHOLE_CLUSTER);
    await settle();

    expect(readingValue(store.storage())).toEqual([
      { id: "broker-1", usedBytes: 100, totalBytes: 200 },
      { id: "broker-2", usedBytes: 150, totalBytes: 200 },
      /* Broker 3's offline directory is in neither sum: a failed disk is not a disk of size zero,
       * and counting it would compute a percentage over capacity the broker does not have. */
      { id: "broker-3", usedBytes: 0, totalBytes: 100 },
    ]);
    store.dispose();
  });

  it("leaves out a directory that reported a size and no usable figure", async () => {
    /* Half an answer is not an answer. Counting `/data/half` at its stated total with nothing used
       would put 400 GB of capacity on broker 2 and describe it as entirely full — a figure that
       looks measured, is in the alarming direction, and comes from a disk nobody read. */
    const store = probe(WHOLE_CLUSTER);
    await settle();

    const broker2 = readingValue(store.storage())?.find((row) => row.id === "broker-2");
    expect(broker2).toEqual({ id: "broker-2", usedBytes: 150, totalBytes: 200 });
    store.dispose();
  });

  it("emits no row at all for a broker none of whose directories were measured", async () => {
    /* The file's own doc comment says so, and it is the meter that makes it matter: a segment per
       broker whether or not any of them was measured takes away the one thing the bar is for, which
       is showing which broker is hot. */
    const store = probe(WHOLE_CLUSTER);
    await settle();

    const rows = readingValue(store.storage()) ?? [];
    expect(rows.map((row) => row.id)).toEqual(["broker-1", "broker-2", "broker-3"]);
    expect(rows.some((row) => row.totalBytes === 0)).toBe(false);
    store.dispose();
  });

  it("draws no bar rather than a bar of zeros when the directories could not be read", async () => {
    /* An empty array is the meter's own "not known" rendering — one neutral track, an em dash for
     * the percentage, and a caption saying so. A row of zeros would draw an empty bar, which reads
     * as "your disks are empty" and is the most reassuring possible misreading. */
    expect(brokerStorageOf(unknown("the cluster is not answering"))).toEqual([]);

    const store = probe({ ...WHOLE_CLUSTER, [LOG_DIRS]: undefined });
    await settle();

    expect(store.storage().kind).toBe("unknown");
    expect(brokerStorageOf(store.storage())).toEqual([]);
    store.dispose();
  });
});

describe("the four badges down the drawer's side", () => {
  it("counts the brokers, the topics, the groups and the subjects", async () => {
    const store = probe(WHOLE_CLUSTER);
    await settle();

    expect(readingValue(store.counts())).toEqual({
      clusters: { kind: "total", value: 3, noun: "brokers" },
      topics: { kind: "total", value: 128 },
      /* The whole list fitted on the page, so the rebalancing group can be claimed. */
      consumers: { kind: "defect", value: 1, noun: "rebalancing", severity: "warning" },
      schemas: { kind: "total", value: 6 },
    });
    store.dispose();
  });

  it("leaves out a count it could not fetch, and keeps the ones it could", async () => {
    /* The whole point of four independent requests: a registry that is not configured costs the
     * drawer one badge, not four. And the missing one is absent rather than zero, because
     * `navigationGroups` draws an absent count as no badge and a zero as a statement of fact. */
    const store = probe({ ...WHOLE_CLUSTER, [SUBJECTS]: undefined });
    await settle();

    const counts = readingValue(store.counts());
    expect(counts?.topics).toEqual({ kind: "total", value: 128 });
    expect(counts && "schemas" in counts).toBe(false);
    store.dispose();
  });

  it("will not claim a rebalancing count it read from one page of a longer list", async () => {
    /* There is no endpoint that counts rebalancing groups, so the only source is the states on a
     * page — and a page is not the list. "1 rebalancing" over a cluster with four hundred groups
     * would be a badge that says one while nine others are stuck. */
    const store = probe({
      ...WHOLE_CLUSTER,
      [GROUPS]: {
        groups: ok({
          items: [{ groupId: "search", state: "COMPLETING_REBALANCE" }],
          page: { totalItems: 400 },
        }),
        incompleteCoordinators: 0,
      },
    });
    await settle();

    expect(readingValue(store.counts())?.consumers).toEqual({ kind: "total", value: 400 });
    store.dispose();
  });

  it("says the counts could not be read once all four have failed, rather than staying pending", async () => {
    /* `pending` means nothing has been answered and `unknown` means the answer did not come, which
       is `reading.ts`'s headline rule and the rest of this file's behaviour. This fold used to
       answer `pending` in both cases, because `valueOf` reads only `lastGood` and never looks at
       `outcome` — so on a cluster whose four services are down, a drawer that draws a skeleton on
       `pending` drew one for ever. */
    const store = probe({
      ...WHOLE_CLUSTER,
      [BROKERS]: undefined,
      [TOPICS]: undefined,
      [GROUPS]: undefined,
      [SUBJECTS]: undefined,
    });
    await settle();

    const counts = store.counts();
    expect(counts.kind).toBe("unknown");
    /* The gateway's own sentence, once and not four times: the four are one outage seen four ways. */
    expect(counts.kind === "unknown" && counts.why).toBe(
      userMessage({ kind: "unreachable", cause: "no stub for this path" } as ApiError),
    );
    store.dispose();
  });

  it("stays pending while any of the four is still out", async () => {
    /* The other half of the same rule, and the one that keeps the repair honest: three failures and
       one request still in flight is a drawer that is early, not a drawer that has been told no. */
    const stalled = new Promise<never>(() => {});
    const api = {
      get: (path: string) =>
        path === TOPICS
          ? stalled
          : Promise.resolve({
              ok: false,
              error: { kind: "unreachable", cause: "no stub for this path" },
            }),
    } as unknown as KuiApiClient;

    const store = probeWith(api);
    await settle();

    expect(store.counts().kind).toBe("pending");
    store.dispose();
  });

  it("asks nothing at all until a cluster is chosen", async () => {
    /* Pending rather than unknown: nothing has been asked, so nothing has failed, and a sentence
     * saying the cluster could not be read would be about a request that was never made. */
    const context: KuiContextValue = {
      api: stubApi(WHOLE_CLUSTER),
      cluster: () => undefined,
      permits: () => true,
      paths: {} as KuiPaths,
      report: () => {},
    };

    let counts: (() => Reading<NavCounts>) | undefined;
    const host = document.createElement("div");
    document.body.appendChild(host);
    const Reader = () => {
      const facts = createClusterStore(() => undefined);
      counts = createMemo(() => facts.counts);
      return null;
    };
    const dispose = render(
      (() => (
        <KuiProvider value={context}>
          <Reader />
        </KuiProvider>
      )) as never,
      host,
    );
    flush();
    await settle();

    expect(counts?.().kind).toBe("pending");
    dispose();
    host.remove();
  });
});

/**
 * What the drawer's count requests actually ask the gateway for.
 *
 * Every other case in this file looks at the answer. These two look at the *request*, because the
 * cost of drawing a badge is not visible in the number that comes back: the schema service enriches
 * every subject row it returns with three further registry calls, so a badge that renders no rows
 * at all was paying for a row it discarded. `SchemaEndpoints.CountOnlyPageSize`'s own scaladoc
 * counts it: the subject list plus four more requests — the row's three and the registry-wide
 * compatibility level. W3-10 added the zero for exactly this and nothing changed the call; the
 * handoff is inside one packet this time, and this is the half that can go red.
 *
 * The topics request is asserted beside it and deliberately: zero is not a page size the rest of
 * KUI has. `PageSize` is 1..500 and the topic list refuses a zero at the edge, so a well-meant
 * sweep that made all three count calls agree would turn the drawer's topic badge into a 400.
 */
describe("what the drawer's count requests ask the gateway for", () => {
  /** A client that answers from the table above and records what it was asked. */
  function recording(calls: { path: string; query: Record<string, unknown> }[]): KuiApiClient {
    return {
      get: (path: string, init?: { params?: { query?: Record<string, unknown> } }) => {
        calls.push({ path, query: init?.params?.query ?? {} });
        const body = WHOLE_CLUSTER[path];
        return Promise.resolve(
          body === undefined
            ? { ok: false, error: { kind: "unreachable", cause: "no stub for this path" } }
            : { ok: true, value: body },
        );
      },
    } as unknown as KuiApiClient;
  }

  it("asks the registry for the subject total and for none of its rows", async () => {
    const calls: { path: string; query: Record<string, unknown> }[] = [];
    const store = probeWith(recording(calls));
    await settle();

    /* `SchemaEndpoints.CountOnlyPageSize`, which is 0 and means "count them, send none". A 1 here
       is a request for one enriched row nothing renders. */
    expect(calls.find((call) => call.path === SUBJECTS)?.query).toEqual({ pageSize: 0 });
    /* And the number still arrives, so the badge is not paid for with an absent count. */
    expect(readingValue(store.counts())?.schemas).toEqual({ kind: "total", value: 6 });
    store.dispose();
  });

  it("keeps the topic count at one row, because that endpoint has no zero", async () => {
    const calls: { path: string; query: Record<string, unknown> }[] = [];
    const store = probeWith(recording(calls));
    await settle();

    expect(calls.find((call) => call.path === TOPICS)?.query).toEqual({
      pageSize: 1,
      /* Internal topics included: the tree counts them under its own row, and a total that left
         them out would not match the sum of the rows beneath it. */
      showInternal: true,
    });
    store.dispose();
  });
});

/**
 * The topic names the drawer's tree is folded from.
 *
 * A seventh request, and a separate one on purpose: the badge above it asks for a single row and
 * reads only `page.totalItems`, so widening that call to carry four thousand names would make every
 * deployment pay for a tree only a reader who expands it ever sees.
 */
describe("the topic names behind the drawer's tree", () => {
  it("reads the names-only index, and not a page of the topic list", async () => {
    const store = probe(WHOLE_CLUSTER);
    await settle();

    expect(readingValue(store.topicNames())).toEqual([
      "orders.v1",
      "orders.v2",
      "__consumer_offsets",
    ]);
    store.dispose();
  });

  it("has no names rather than an empty tree when the section refuses", async () => {
    /* `unknown` and not `value([])`. An empty array is a cluster with no topics, which draws a
       branch that holds nothing; a refusal is a cluster whose topics could not be read, and the
       drawer draws no disclosure at all rather than a chevron that opens onto nothing. */
    const store = probe({
      ...WHOLE_CLUSTER,
      [NAMES]: { names: { status: "unavailable", reason: { code: "upstream_unavailable" } } },
    });
    await settle();

    expect(store.topicNames().kind).toBe("unknown");
    store.dispose();
  });
});

/**
 * A 200 that is not the answer this endpoint documents.
 *
 * The generated type says `ClusterDetailResponse.cluster` is required, and it is — of every answer
 * the *cluster service* produces. It is not required of every 200 a browser can receive: a reverse
 * proxy answering with its own JSON, or a gateway rewritten to a different route, both reach this
 * store with a body that decodes and is not the envelope.
 *
 * `summaryOf` read `row.summary` as its first statement, so `undefined` threw a `TypeError` inside
 * a memo — which Solid reports as a halted reactive graph rather than as a failed request, and which
 * takes the whole frame away rather than the one panel that could not be read. A blank page, from a
 * request that answered 200.
 */
describe("a cluster detail body that is not the envelope", () => {
  const bodies = (cluster: unknown) => ({ ...WHOLE_CLUSTER, [DETAIL]: { cluster } });

  it("answers a reading with no value rather than throwing inside the memo", async () => {
    for (const shape of [undefined, null, "not an object", 7, {}, { name: "prod" }]) {
      const store = probe(bodies(shape));
      await settle();

      expect(store.summary().kind).toBe("unknown");
      /* And the rest of the drawer is untouched: six failures are six failures, and a body the head
         could not read says nothing about the disks or the counts. */
      expect(readingValue(store.storage())).not.toBeUndefined();
      expect(readingValue(store.counts())?.topics).toEqual({ kind: "total", value: 128 });
      store.dispose();
    }
  });

  it("falls back to the identifier when the row carries no name", async () => {
    // The same degradation `clusterSummaries` makes: a blank where a name goes reads as a bug in
    // the drawer rather than as a cluster nobody named.
    const store = probe(
      bodies({ id: "prod", summary: ok({ version: "3.7.0", underReplicatedPartitionCount: 0 }) }),
    );
    await settle();

    expect(readingValue(store.summary())?.name).toBe("prod");
    store.dispose();
  });
});

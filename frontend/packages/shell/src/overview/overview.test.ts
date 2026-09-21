/**
 * Tests for the overview's judgements.
 *
 * Every case here is a sentence the dashboard might say about a cluster, and the question each one
 * asks is "would that sentence be true?". The rendering is tested separately; this file is about
 * whether the product is telling the truth, which is the part that can be wrong in a way that costs
 * somebody an afternoon.
 */

import { describe, expect, it } from "vitest";

import {
  DISK_WARN_PERCENT,
  LAG_JOKE_CEILING,
  type Broker,
  type ClusterSummary,
  type ConsumerGroup,
  type LogDir,
  brokerHealth,
  controllerNote,
  diskShare,
  inSyncPercent,
  lagPill,
  overviewLede,
  partitionHealth,
  partitionTotal,
  replicationPill,
  storageBreakdown,
  storageLede,
  topLag,
  totalLag,
} from "./model.js";
import {
  fetchOverview,
  readPagedSection,
  readSection,
  toOverviewModel,
  loadingData,
  withoutNulls,
} from "./load.js";
import type { KuiApiClient } from "@kui/api";
import { segmentTone } from "./StorageByBroker.jsx";
import { INTERNAL_GROUP, OTHER_GROUP } from "../nav/prefixes.js";
import { combineReadings, notCollected, pending, unknown, value } from "./reading.js";

const healthy: ClusterSummary = {
  version: "3.7.0",
  controllerId: 1,
  brokerCount: 3,
  onlinePartitionCount: 1536,
  offlinePartitionCount: 0,
  underReplicatedPartitionCount: 0,
};

describe("the voice is conditional on the cluster being healthy", () => {
  it("keeps the design's line when the cluster really is fine", () => {
    expect(overviewLede(value(healthy))).toBe(
      "All brokers vibing. Zero under-replicated partitions. You may sip your coffee.",
    );
  });

  it("does not tell somebody to sip their coffee over offline partitions", () => {
    const lede = overviewLede(value({ ...healthy, offlinePartitionCount: 12 }));
    expect(lede).not.toContain("coffee");
    expect(lede).toContain("12 partitions are offline");
  });

  it("does not tell somebody to sip their coffee over under-replicated partitions", () => {
    const lede = overviewLede(value({ ...healthy, underReplicatedPartitionCount: 4 }));
    expect(lede).not.toContain("coffee");
    expect(lede).toContain("under-replicated");
  });

  it("says the figures are incomplete rather than making a claim it cannot support", () => {
    expect(overviewLede(unknown("gateway down"))).toContain("has not answered");
    expect(overviewLede(pending())).toContain("Asking");
  });

  it("does not claim health when the counts are simply not reported", () => {
    // The bug this guards: `undefined > 0` is false, so a missing count sails into the healthy arm.
    const lede = overviewLede(value({ ...healthy, underReplicatedPartitionCount: undefined }));
    expect(lede).not.toContain("coffee");
    expect(lede).toContain("does not report");
  });
});

describe("the replication pill", () => {
  it("says all in sync only when both counts are known and zero", () => {
    expect(replicationPill(value(healthy))).toEqual({ text: "all in sync", tone: "success" });
  });

  it("reports offline partitions ahead of under-replicated ones, because they are worse", () => {
    const pill = replicationPill(value({ ...healthy, offlinePartitionCount: 2, underReplicatedPartitionCount: 12 }));
    expect(pill).toEqual({ text: "2 partitions offline", tone: "danger" });
  });

  it("shows nothing at all when the counts are unknown, rather than a reassuring pill", () => {
    expect(replicationPill(value({ ...healthy, offlinePartitionCount: undefined }))).toBeUndefined();
    expect(replicationPill(pending())).toBeUndefined();
  });
});

describe("partition totals and health", () => {
  it("adds the two counts", () => {
    expect(partitionTotal(value({ ...healthy, offlinePartitionCount: 4 }))).toEqual(value(1540));
  });

  it("refuses to add a count it does not have, rather than treating it as zero", () => {
    const total = partitionTotal(value({ ...healthy, offlinePartitionCount: undefined }));
    expect(total.kind).toBe("unknown");
  });

  it("carries pending through, so a waiting figure never renders as a dash", () => {
    expect(partitionTotal(pending()).kind).toBe("pending");
  });

  it("derives in-sync by subtraction, because online and under-replicated overlap", () => {
    const health = partitionHealth(
      value({ ...healthy, onlinePartitionCount: 1534, underReplicatedPartitionCount: 12, offlinePartitionCount: 2 }),
    );
    // 1534 online, of which 12 are under-replicated -> 1522 fully in sync. Adding the three raw
    // numbers would give 1548, which is more partitions than the cluster has.
    expect(health).toEqual(
      value({ inSync: 1522, underReplicated: 12, offline: 2, healthyPercent: (1522 / 1536) * 100 }),
    );
  });

  it("never produces a negative segment when the two scrapes disagree", () => {
    const health = partitionHealth(
      value({ ...healthy, onlinePartitionCount: 10, underReplicatedPartitionCount: 15, offlinePartitionCount: 0 }),
    );
    expect(health.kind === "value" && health.value.inSync).toBe(0);
  });

  it("reports 100% healthy for a cluster with no partitions rather than dividing by zero", () => {
    const health = partitionHealth(
      value({ ...healthy, onlinePartitionCount: 0, underReplicatedPartitionCount: 0, offlinePartitionCount: 0 }),
    );
    expect(health.kind === "value" && health.value.healthyPercent).toBe(100);
  });
});

describe("consumer lag", () => {
  const groups = (...lags: (number | undefined)[]): ConsumerGroup[] =>
    lags.map((totalLagValue, i) => ({ groupId: `group-${i}`, state: "STABLE", totalLag: totalLagValue }));

  it("sums the lags and counts the groups it could not", () => {
    expect(totalLag(value(groups(100, undefined, 33)))).toEqual(value({ total: 133, incomplete: 1 }));
  });

  it("says how many groups were left out rather than presenting a partial sum as a total", () => {
    const pill = lagPill(value({ total: 133, incomplete: 2 }));
    expect(pill).toEqual({ text: "2 groups not counted", tone: "warning" });
  });

  it("only makes the joke when the lag is small enough for one", () => {
    expect(lagPill(value({ total: 4212, incomplete: 0 }))?.text).toBe("fashionably late");
    expect(lagPill(value({ total: LAG_JOKE_CEILING, incomplete: 0 }))?.text).toBe("seriously behind");
  });

  it("distinguishes caught up from behind", () => {
    expect(lagPill(value({ total: 0, incomplete: 0 }))).toEqual({ text: "fully caught up", tone: "success" });
  });

  it("drops groups with no lag figure instead of sorting them as the healthiest", () => {
    const top = topLag(value(groups(5, undefined, 900)));
    expect(top.kind === "value" && top.value.map((e) => e.lag)).toEqual([900, 5]);
    expect(top.kind === "value" && top.value.length).toBe(2);
  });
});

describe("broker health", () => {
  const broker = (over: Partial<Broker> = {}): Broker => ({
    id: 1,
    host: "broker-1.kyiv",
    port: 9092,
    isController: true,
    leaderCount: 512,
    ...over,
  });

  const dir = (over: Partial<LogDir> = {}): LogDir => ({
    brokerId: 1,
    path: "/var/lib/kafka",
    totalBytes: 100,
    usableBytes: 39,
    ...over,
  });

  it("computes the disk percentage from used over total", () => {
    const bars = brokerHealth(value([broker()]), value([dir()]));
    expect(bars.kind === "value" && bars.value[0]?.diskPercent).toEqual(value(61));
  });

  it("crosses the amber threshold where the design says it does", () => {
    const bars = brokerHealth(value([broker()]), value([dir({ usableBytes: 17 })]));
    expect(bars.kind === "value" && bars.value[0]?.diskPercent).toEqual(value(83));
    expect(83).toBeGreaterThan(DISK_WARN_PERCENT);
  });

  it("ignores a directory that reports no capacity rather than counting it as empty", () => {
    // One 100-byte disk 61% full, plus an older disk that reports nothing. The answer must be 61%,
    // computed over the capacity we actually know, not 30.5% computed over a doubled denominator.
    const bars = brokerHealth(value([broker()]), value([dir(), dir({ totalBytes: undefined, usableBytes: undefined })]));
    expect(bars.kind === "value" && bars.value[0]?.diskPercent).toEqual(value(61));
  });

  it("reports an unmeasurable disk as unknown, never as zero", () => {
    const bars = brokerHealth(value([broker()]), value([dir({ totalBytes: undefined })]));
    const disk = bars.kind === "value" ? bars.value[0]?.diskPercent : undefined;
    expect(disk?.kind).toBe("unknown");
    // The specific thing being guarded: an empty bar must not be reachable as the number 0, because
    // a 0% disk bar is the most reassuring possible rendering of "we have no idea".
    expect(disk?.kind === "value").toBe(false);
  });

  it("leaves the bar blank while the log directories are still in flight", () => {
    const bars = brokerHealth(value([broker()]), pending());
    expect(bars.kind === "value" && bars.value[0]?.diskPercent.kind).toBe("pending");
  });

  it("omits the leader count from the detail line when the broker did not report one", () => {
    const bars = brokerHealth(value([broker({ leaderCount: undefined })]), value([dir()]));
    expect(bars.kind === "value" && bars.value[0]?.detail).toBe("id 1");
  });

  it("says nothing about the controller during an election rather than printing a dash", () => {
    expect(controllerNote(value([broker({ isController: false })]))).toBeUndefined();
    expect(controllerNote(value([broker()]))).toContain("broker 1");
  });
});

describe("the figures this model no longer decides", () => {
  it("carries none of the five metrics figures, because each of them is now a query", () => {
    // The model used to carry a constant `notCollected` reading for each of these and the cards
    // drew it. Deleting them is the shape of this wave's change; the other half is that the cards
    // ask. A constant here gives the screen one answer for three different situations — this build
    // does not measure it, this deployment configured no exporter, the exporter stopped answering —
    // and the whole argument of `reading.ts` is that those must not look the same. If one ever
    // comes back onto the model it will be a second answer to a question the query already
    // answers, and the two will disagree the first time either is edited.
    const keys = Object.keys(toOverviewModel(loadingData()));
    for (const gone of [
      "throughput",
      "productionRate",
      "latency",
      "topProducers",
      "requestHandlers",
      "messageSizes",
    ]) {
      expect(keys).not.toContain(gone);
    }
  });
});

describe("reading a section", () => {
  it("keeps stale data rather than blanking the panel", () => {
    const reading = readSection<number[]>(
      { status: "stale", data: [1, 2], fetchedAt: "2026-01-01T00:00:00Z", reason: "cache" },
      "the broker list",
    );
    expect(reading).toEqual(value([1, 2]));
  });

  it("turns every failing status into unknown, never into not-collected", () => {
    for (const status of ["unavailable", "forbidden", "not_configured"]) {
      const reading = readSection({ status }, "the broker list");
      expect(reading.kind).toBe("unknown");
    }
  });

  it("says which permission is missing rather than a bare failure", () => {
    const reading = readSection({ status: "forbidden" }, "the consumer groups");
    expect(reading.kind === "unknown" && reading.why).toContain("permission");
  });
});

describe("the assembled model", () => {
  it("starts with every figure pending, so nothing reads as absent before it is asked", () => {
    const model = toOverviewModel(loadingData());
    expect(model.brokerCount.kind).toBe("pending");
    expect(model.partitions.kind).toBe("pending");
    expect(model.brokerPill).toBeUndefined();
    expect(model.storage.kind).toBe("pending");
    expect(model.topLag.kind).toBe("pending");
  });
});

/**
 * The consumer-group section is a page, and reading it as a list froze the whole dashboard.
 *
 * `decodeSection<T>` takes `unknown` and returns `T`, so the type argument is an assertion nothing
 * can check, and the generated schema types this body as `unknown` and so could not contradict it.
 * The result was a `Reading` holding `{ items, pageInfo }` while claiming to hold an array; the
 * first `for…of` over it threw inside a computation, Solid 2 halted the reactive graph, and every
 * figure on the screen stayed a skeleton for ever with all five requests having returned 200.
 */
describe("reading a section whose payload is a page", () => {
  it("unwraps the page's items", () => {
    const reading = readPagedSection<{ groupId: string }>(
      { status: "ok", data: { items: [{ groupId: "a" }, { groupId: "b" }], pageInfo: { totalItems: 2 } } },
      "the consumer groups",
    );
    expect(reading).toEqual(value([{ groupId: "a" }, { groupId: "b" }]));
  });

  it("yields something iterable, because the callers iterate it", () => {
    const reading = readPagedSection<number>(
      { status: "ok", data: { items: [1, 2, 3] } },
      "the consumer groups",
    );
    // The assertion the original defect would have failed: not the shape of the reading, but the
    // fact that a consumer can walk it without throwing.
    expect(reading.kind === "value" && [...reading.value]).toEqual([1, 2, 3]);
  });

  it("reports a payload that is not a page instead of throwing later", () => {
    // The old code put this straight into the model and let `totalLag` throw. A panel that says it
    // could not read the data costs one panel; an exception in a computation costs the screen.
    const reading = readPagedSection({ status: "ok", data: [1, 2] }, "the consumer groups");
    expect(reading.kind).toBe("unknown");
  });

  it("passes a failing section through untouched", () => {
    const reading = readPagedSection({ status: "forbidden" }, "the consumer groups");
    expect(reading.kind === "unknown" && reading.why).toContain("permission");
  });
});

/**
 * `null` is what the wire actually sends where the model expects `undefined`.
 *
 * This is the defect with the worst consequences found in the whole review, because its symptom is
 * not a blank panel or a crash — it is a monitoring screen stating, in the product's cheerful
 * voice, that everything is fine about figures it never received.
 */
describe("normalising absent values off the wire", () => {
  it("turns null into an absent field", () => {
    expect(withoutNulls({ a: 1, b: null })).toEqual({ a: 1 });
    expect("b" in (withoutNulls({ a: 1, b: null }) as object)).toBe(false);
  });

  it("keeps zero, which is a value and not an absence", () => {
    expect(withoutNulls({ lag: 0 })).toEqual({ lag: 0 });
  });

  it("reaches into nested objects and arrays", () => {
    expect(withoutNulls({ outer: { inner: null }, list: [{ x: null }, { x: 2 }] })).toEqual({
      outer: {},
      list: [{}, { x: 2 }],
    });
  });

  it("applies to a decoded section, so the model's undefined checks hold", () => {
    const reading = readSection<{ offlinePartitionCount?: number }>(
      { status: "ok", data: { brokerCount: 1, offlinePartitionCount: null } },
      "the cluster summary",
    );
    expect(reading.kind === "value" && reading.value.offlinePartitionCount).toBeUndefined();
  });
});

describe("a cluster that reports no partition counts at all", () => {
  // Exactly what the demonstration environment's single-broker cluster sends: a real summary in
  // which all three partition counts are null.
  const silent = () =>
    readSection<ClusterSummary>(
      {
        status: "ok",
        data: {
          brokerCount: 1,
          version: "4.3",
          controllerId: 1,
          onlinePartitionCount: null,
          offlinePartitionCount: null,
          underReplicatedPartitionCount: null,
        },
      },
      "the cluster summary",
    );

  it("does not claim the partitions are all in sync", () => {
    // Before the fix this returned `{ text: "all in sync", tone: "success" }`, because the guard
    // written to catch exactly this case tested `=== undefined` and was handed `null`.
    expect(replicationPill(silent())).toBeUndefined();
  });

  it("does not add two absent counts into a confident zero", () => {
    expect(partitionTotal(silent()).kind).toBe("unknown");
  });

  it("does not tell the operator to sip their coffee", () => {
    expect(overviewLede(silent())).not.toContain("coffee");
  });
});

describe("the storage card's attribution", () => {
  const brokers: readonly Broker[] = [
    { id: 1, host: "broker-1", port: 9092, isController: true },
    { id: 2, host: "broker-2", port: 9092, isController: false },
  ];

  const dir = (over: Partial<LogDir> & { brokerId: number }): LogDir => ({
    path: "/var/lib/kafka",
    totalBytes: 1000,
    usableBytes: 400,
    ...over,
  });

  it("folds the names with the drawer's fold, so a group is written the drawer's way", () => {
    const reading = storageBreakdown(
      value(brokers),
      value([
        dir({
          brokerId: 1,
          replicas: [
            { topic: "orders.payments", sizeBytes: 300 },
            { topic: "orders.refunds", sizeBytes: 100 },
            { topic: "__consumer_offsets", sizeBytes: 20 },
          ],
        }),
        dir({ brokerId: 2, replicas: [{ topic: "orders.payments", sizeBytes: 200 }] }),
      ]),
    );

    expect(reading.kind).toBe("value");
    if (reading.kind !== "value") return;
    // `orders.*` and not `orders`: the group really continues past its segment. `internal` and not
    // `__consumer_offsets.*`: an underscore name is a group, not a prefix.
    expect(reading.value.groups.map((group) => group.prefix)).toEqual(["orders.*", "internal"]);
    expect(reading.value.rows[0]?.segments).toEqual([
      { prefix: "orders.*", bytes: 400 },
      { prefix: "internal", bytes: 20 },
    ]);
  });

  it("skips a directory that reported no size, in the capacity and in the attribution", () => {
    // The discriminating case. The unmeasured disk holds more `orders.*` than the measured one, so
    // counting it produces a share over 100% — which the bar would clamp and draw as ordinary.
    const reading = storageBreakdown(
      value([brokers[0] as Broker]),
      value([
        dir({ brokerId: 1, replicas: [{ topic: "orders.payments", sizeBytes: 300 }] }),
        dir({
          brokerId: 1,
          path: "/var/lib/kafka-2",
          totalBytes: undefined,
          usableBytes: undefined,
          replicas: [{ topic: "orders.payments", sizeBytes: 900 }],
        }),
      ]),
    );

    expect(reading.kind).toBe("value");
    if (reading.kind !== "value") return;
    expect(reading.value.rows[0]?.capacityBytes).toBe(1000);
    expect(reading.value.rows[0]?.usedBytes).toBe(600);
    expect(reading.value.rows[0]?.segments).toEqual([{ prefix: "orders.*", bytes: 300 }]);
  });

  it("gives a broker whose every directory is unmeasured no capacity and no segments", () => {
    const reading = storageBreakdown(
      value([brokers[0] as Broker]),
      value([
        dir({
          brokerId: 1,
          totalBytes: undefined,
          usableBytes: undefined,
          replicas: [{ topic: "orders.payments", sizeBytes: 300 }],
        }),
      ]),
    );

    expect(reading.kind).toBe("value");
    if (reading.kind !== "value") return;
    // Not a zero. A disk of unknown size drawn as a zero-byte disk is the most reassuring possible
    // rendering of "we have no idea how full this is".
    expect(reading.value.rows[0]?.capacityBytes).toBeUndefined();
    expect(reading.value.rows[0]?.usedBytes).toBeUndefined();
    expect(reading.value.rows[0]?.segments).toEqual([]);
  });

  it("waits rather than failing while the directories are still in flight", () => {
    // `combineReadings`' precedence, which matters here: the broker list landing first must not
    // turn the card into an error the moment it has half an answer.
    expect(storageBreakdown(value(brokers), pending()).kind).toBe("pending");
    expect(storageBreakdown(value(brokers), unknown("no")).kind).toBe("unknown");
  });

  it("says a figure it will never have rather than waiting for it forever", () => {
    /* Filed by W8-07's verification pass as V3-3. `combineReadings` has a three-step precedence
       spelled out in its own doc comment, and only the two *value*-paired steps were asserted —
       both by the case above, both of which survive moving `pending` above `notCollected`. That
       swap left all 536 shell cases green.

       The swapped order is not a subtly different answer, it is a card that never finishes. A
       deployment that collects no log-dir metrics reports `notCollected` forever and no request is
       outstanding, so `pending` means a skeleton that animates until the tab is closed. The doc
       comment says it in a sentence — *"if either half is something KUI never measures, the
       combination is never measurable, and waiting for it is pointless — say so immediately rather
       than spinning forever"* — and the sentence was the only thing enforcing it.

       Both argument positions, because the guard is two lines and swapping either one alone is the
       same defect for half the inputs; and the `pending`-over-`unknown` step too, whose direction
       is the opposite argument: a half-answer that may still turn out fine must not be called
       broken early. */
    expect(combineReadings(notCollected("no metrics source"), pending(), () => 1).kind).toBe(
      "notCollected",
    );
    expect(combineReadings(pending(), notCollected("no metrics source"), () => 1).kind).toBe(
      "notCollected",
    );

    // And the reason travels with it: a derived figure that reports its own vague "unavailable"
    // throws away the only sentence that would have explained the card.
    const combined = combineReadings(notCollected("no metrics source"), pending(), () => 1);
    expect(combined.kind === "notCollected" ? combined.why : undefined).toBe("no metrics source");

    expect(combineReadings(pending(), unknown("the broker did not answer"), () => 1).kind).toBe(
      "pending",
    );
    expect(combineReadings(unknown("the broker did not answer"), pending(), () => 1).kind).toBe(
      "pending",
    );
  });

  it("keeps the counts adding up when the cap drops a group", () => {
    // Seven prefixes and a cap of five: the two smallest have to survive as `other`, or the card's
    // legend would total less than the disks it is drawn over.
    const names = ["a", "b", "c", "d", "e", "f", "g"];
    const reading = storageBreakdown(
      value([brokers[0] as Broker]),
      value([
        dir({
          brokerId: 1,
          replicas: names.map((topic, index) => ({ topic: `${topic}.one`, sizeBytes: 100 - index })),
        }),
      ]),
    );

    expect(reading.kind).toBe("value");
    if (reading.kind !== "value") return;
    const labels = reading.value.groups.map((group) => group.prefix);
    expect(labels).toHaveLength(6);
    expect(labels.at(-1)).toBe("other");
    const attributed = reading.value.rows[0]?.segments.reduce((sum, s) => sum + s.bytes, 0);
    expect(attributed).toBe(names.reduce((sum, _, index) => sum + (100 - index), 0));
  });
});

describe("the Storage tab's voice", () => {
  it("promises an attribution only when there is one to see", () => {
    const disks = storageBreakdown(
      value([{ id: 1, host: "broker-1", port: 9092, isController: true }]),
      value([
        {
          brokerId: 1,
          path: "/var/lib/kafka",
          totalBytes: 1000,
          usableBytes: 400,
          replicas: [{ topic: "orders.payments", sizeBytes: 300 }],
        },
      ]),
    );
    expect(storageLede(disks)).toBe("Disk, retention and the topics eating your budget.");
  });

  it("says what is missing when no broker reported a disk size", () => {
    const noSizes = storageBreakdown(
      value([{ id: 1, host: "broker-1", port: 9092, isController: true }]),
      value([{ brokerId: 1, path: "/var/lib/kafka" }]),
    );
    expect(storageLede(noSizes)).toContain("no capacity to divide up");
    expect(storageLede(noSizes)).not.toContain("budget");
  });

  it("does not describe a picture that has not arrived", () => {
    expect(storageLede(pending())).toBe("Adding up what is on the disks.");
    expect(storageLede(unknown("the cluster service is not answering"))).not.toContain("budget");
  });
});

describe("the one division the whole screen shares", () => {
  it("refuses a disk whose size is not a size, and not only one whose size is zero", () => {
    /* Filed by W8-07's verification pass as V3-2. `diskShare` is the single arithmetic behind both
       the broker-health bar and the storage row — the two used to divide separately and disagreed
       about a zero-byte disk, which is why there is now one of them — and its guard is
       `total <= 0`. Narrowing it to `total === 0` left all 536 shell cases green, because every
       fixture that exercises the refusal reports exactly zero.

       The comment two lines above the guard is the rule, and it was the only thing holding it:
       *"`total <= 0` rather than `total === 0`, because a directory reporting a negative size is
       the same question and a worse answer: dividing by it would draw a bar pointing the other way
       instead of saying that the figure makes no sense."* Under `=== 0` a negative capacity makes
       it all the way through as a `value`, and a negative percentage on a `ProgressBar` is not a
       refusal an operator can see — it is a bar that renders as empty and a caption claiming a
       measured figure, on the card that answers "how full is this broker".

       A negative capacity is not hypothetical arithmetic: `usableBytes` and `totalBytes` arrive as
       longs off a `DescribeLogDirs` response, a directory KUI cannot stat contributes a sentinel,
       and the subtraction that produces `used` has underflowed in this codebase before. The
       refusal's *rendering* is already asserted where the screen draws it —
       `overview.render.test`'s "says so in the storage row, rather than printing a pair of
       zeroes" — so what is added here is the predicate, the half nothing measured. */
    expect(diskShare(10, -1)).toEqual({
      kind: "unknown",
      why: "this broker reported a zero-byte disk",
    });
    expect(diskShare(0, 0)).toEqual({
      kind: "unknown",
      why: "this broker reported a zero-byte disk",
    });
    // And the positive direction, so the guard declines nonsense rather than declining the feature.
    expect(diskShare(250, 1000)).toEqual({ kind: "value", value: 25 });
  });
});

describe("the in-sync share the gauge draws", () => {
  it("is the donut's own healthy percentage, not a second subtraction", () => {
    const summary = value({ ...healthy, onlinePartitionCount: 1000, underReplicatedPartitionCount: 10 });
    const health = partitionHealth(summary);
    expect(inSyncPercent(summary)).toEqual(
      health.kind === "value" ? value(health.value.healthyPercent) : health,
    );
  });

  it("is unknown, not 100, when the counts were not reported", () => {
    const share = inSyncPercent(value({ ...healthy, underReplicatedPartitionCount: undefined }));
    expect(share.kind).toBe("unknown");
  });
});

describe("the storage legend's inks", () => {
  it("keeps `internal` and `other` on fixed colours whatever position they land in", () => {
    // The two rows whose presence varies: a cluster with no internal topics has no `internal` row,
    // and one whose prefixes all fit has no `other` row. If either took the next ink in the ramp,
    // every other colour would shift when one of them appeared, and the key would have to be
    // re-read after a refresh that changed nothing an operator cares about.
    expect(segmentTone(INTERNAL_GROUP, 1)).toBe(segmentTone(INTERNAL_GROUP, 4));
    expect(segmentTone(OTHER_GROUP, 2)).toBe(segmentTone(OTHER_GROUP, 5));
    expect(segmentTone(INTERNAL_GROUP, 1)).not.toBe(segmentTone(OTHER_GROUP, 1));
  });

  it("gives the five prefix rows five different inks, because the legend has no other key", () => {
    const inks = [0, 1, 2, 3, 4].map((index) => segmentTone("orders.*", index));
    expect(new Set(inks).size).toBe(5);
    expect(inks).not.toContain(segmentTone(INTERNAL_GROUP, 0));
  });
});

describe("fetching the overview against a server that answers something else", () => {
  /**
   * A client whose every call succeeds and whose every body is `body`.
   *
   * The point of the case below is a **200**. A failure is already handled — `fetchOverview` folds
   * `ok: false` into a sentence on every one of the five readings — and a transport error would
   * therefore prove nothing. What was unguarded was a success whose body is not the envelope: a
   * reverse proxy's own 200, a gateway that matched a different route, a build whose envelope moved.
   */
  const answering = (body: unknown): KuiApiClient => {
    const get = async () => ({ ok: true, value: body });
    return { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;
  };

  /**
   * A gateway that answers each of the five endpoints separately, and refuses what it was not given.
   *
   * The reason this exists is the case below it. `answering` hands the *same* body to all five
   * calls, so under it every reading is `unknown` whatever `fetchOverview` does — and a
   * `fetchOverview` that collapsed the whole model into the summary's one failure would look
   * exactly like a `fetchOverview` that kept the five apart. A case whose four assertions are
   * satisfied by the correct code and by the defect it is named after has asserted nothing, which
   * is what it did for a wave. Answering per path is what makes the four readings able to be
   * `value`, and therefore able to stop being one.
   */
  const answeringByPath = (answers: Readonly<Record<string, unknown>>): KuiApiClient => {
    const get = async (path: string) =>
      Object.hasOwn(answers, path)
        ? { ok: true, value: answers[path] }
        : { ok: false, error: { kind: "unreachable", cause: `this case stubbed no answer for ${path}` } };
    return { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;
  };

  const section = (data: unknown): unknown => ({ status: "ok", data, fetchedAt: "2026-09-05T12:00:00Z" });

  /** The four endpoints that are *not* the cluster summary, all answering perfectly well. */
  const OTHER_FOUR: Readonly<Record<string, unknown>> = {
    "/api/v1/clusters/{clusterId}/brokers": {
      brokers: section([{ id: 1, host: "broker-1.kyiv", port: 9092, isController: true, leaderCount: 512 }]),
    },
    "/api/v1/clusters/{clusterId}/log-dirs": {
      logDirs: section([{ brokerId: 1, path: "/var/lib/kafka", totalBytes: 1000, usableBytes: 390 }]),
    },
    "/api/v1/clusters/{clusterId}/consumer-groups": {
      groups: section({ items: [{ groupId: "clickstream-etl", state: "STABLE", totalLag: 3861 }] }),
    },
    "/api/v1/clusters/{clusterId}/topics": { topics: section({ page: { totalItems: 128 } }) },
  };

  it("answers a sentence rather than throwing, when the 200 is not a cluster envelope", async () => {
    // `detail.value.cluster.summary` read two levels into this body. `.summary` off `undefined`
    // throws a `TypeError`, and it throws inside the memo that assembles the model — Solid 2
    // answers a throw in a computation by halting the graph, so the whole dashboard's skeletons
    // would never resolve. The rejection is what this case is really watching for.
    const data = await fetchOverview(answering({ detail: "not a cluster" }), "prod-kyiv-01");

    expect(data.summary.kind).toBe("unknown");
    expect(data.summary.kind === "unknown" ? data.summary.why : "").toContain(
      "something other than a cluster",
    );
  });

  it("still lands the other four readings, because they are separate requests", async () => {
    // ADR-039 from the other side: one malformed answer blanks its own panels and no others. A
    // guard that turned the whole model into one failure would be a different defect of the same
    // family as the one it replaced.
    //
    // The four assertions are on `value` and on the values, which is the whole point. The version
    // of this case that stood for a wave asserted that all four were `unknown` — true under the
    // correct implementation *and* true under the collapse it was named after, because its stub
    // answered the same non-envelope body to all five endpoints. Here the other four answer
    // properly, so the only way they can arrive as figures is if `fetchOverview` really did keep
    // five requests apart.
    const data = await fetchOverview(
      answeringByPath({ ...OTHER_FOUR, "/api/v1/clusters/{clusterId}": { detail: "not a cluster" } }),
      "prod-kyiv-01",
    );

    expect(data.summary.kind).toBe("unknown");
    expect(data.brokers).toEqual(value([{ id: 1, host: "broker-1.kyiv", port: 9092, isController: true, leaderCount: 512 }]));
    expect(data.logDirs).toEqual(
      value([{ brokerId: 1, path: "/var/lib/kafka", totalBytes: 1000, usableBytes: 390 }]),
    );
    expect(data.groups).toEqual(value([{ groupId: "clickstream-etl", state: "STABLE", totalLag: 3861 }]));
    expect(data.topicCount).toEqual(value(128));
  });

  it("draws the four panels those readings feed, on a screen whose summary failed", async () => {
    // The same rule one layer up, where an operator meets it: the panel that failed says so and the
    // other four carry figures. Asserted on the assembled model rather than on the four readings,
    // because a fold that dropped a reading on the way into the model would leave the case above
    // green and the screen blank.
    const model = toOverviewModel(
      await fetchOverview(
        answeringByPath({ ...OTHER_FOUR, "/api/v1/clusters/{clusterId}": { detail: "not a cluster" } }),
        "prod-kyiv-01",
      ),
    );

    expect(model.topicCount).toEqual(value(128));
    expect(model.brokers.kind).toBe("value");
    expect(model.storage.kind).toBe("value");
    expect(model.lag).toEqual(value({ total: 3861, incomplete: 0 }));
    // And the one that did fail is still saying so, so this is not a case that passes on a model
    // where nothing failed at all.
    expect(model.partitions.kind).toBe("unknown");
  });

  it("reads the summary out of the envelope when the server does send one", async () => {
    // The guard has to still let the good case through — a check that refuses everything is a
    // dashboard that never draws, and is the way a guard like this is usually got wrong.
    const data = await fetchOverview(
      answering({ cluster: { summary: { status: "ok", data: healthy, fetchedAt: "" } } }),
      "prod-kyiv-01",
    );

    expect(data.summary).toEqual(value(healthy));
  });
});

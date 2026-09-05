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
  lagPill,
  latencyPercentiles,
  overviewLede,
  partitionHealth,
  partitionTotal,
  productionRate,
  replicationPill,
  throughputSeries,
  topLag,
  totalLag,
} from "./model.js";
import { readPagedSection, readSection, toOverviewModel, loadingData, withoutNulls } from "./load.js";
import { pending, unknown, value } from "./reading.js";

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

describe("the figures this backend does not collect", () => {
  it("marks them not-collected, which is not the same as unavailable", () => {
    for (const reading of [throughputSeries(), productionRate(), latencyPercentiles()]) {
      expect(reading.kind).toBe("notCollected");
      // The distinction that matters: nothing here should read as a transient failure inviting a
      // retry, because no retry can ever succeed.
      expect(reading.kind).not.toBe("unknown");
    }
  });

  it("explains what is missing in terms of the thing that would have to exist", () => {
    const throughput = throughputSeries();
    const latency = latencyPercentiles();
    expect(throughput.kind === "notCollected" && throughput.why).toContain("no history");
    expect(latency.kind === "notCollected" && latency.why).toContain("JMX");
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
    // Except the three that are never coming, which are known to be absent from the start and
    // should not spend the page's life pretending to load.
    expect(model.throughput.kind).toBe("notCollected");
    expect(model.latency.kind).toBe("notCollected");
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

import { describe, expect, it, vi } from "vitest";
import type { KuiApiClient } from "@kui/api";
import { fetchBrokers, fetchClusters, healthOf } from "./data.js";

/**
 * The cluster feature's data layer.
 *
 * The case that matters most here is the one the fixtures used to hide: a cluster whose scrape
 * failed must still appear in the list, named, with its figures **absent** rather than zero. All
 * three of the obvious shortcuts are wrong in a way an operator cannot recover from —
 *
 *   - dropping the row makes a broken cluster look like a deleted one;
 *   - filling it with zeroes makes it look like a cluster with no brokers;
 *   - failing the whole request makes one broken cluster hide the three healthy ones.
 *
 * The section envelope exists to distinguish those, and this suite is what keeps the mapping
 * honest.
 */

/** A client that answers with whatever is given, without a server. */
function client(answer: unknown, ok = true): KuiApiClient {
  const get = vi.fn(async () => (ok ? { ok: true, value: answer } : { ok: false, error: answer }));
  return { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient;
}

const scrape = {
  version: "3.7.0",
  brokerCount: 3,
  onlinePartitionCount: 1536,
  offlinePartitionCount: 0,
  underReplicatedPartitionCount: 0,
  scrapedAt: "2026-09-05T12:00:00Z",
};

/**
 * One entry of the cluster list, in the shape the server really sends.
 *
 * The row is nested under `cluster`, and the counts come from a sibling `topics` section — not from
 * the scrape. Both were got wrong here first, and `recorded.test.ts` (which decodes a document
 * captured from a running gateway) is what found it. A hand-written fixture is only ever as right
 * as the author's memory of the contract, which is why that suite exists beside this one.
 */
function row(id: string, summary: unknown, topics?: unknown) {
  return {
    cluster: { id, name: id, readOnly: false, bootstrapServers: `${id}:9092`, summary },
    ...(topics === undefined ? {} : { topics }),
  };
}

describe("fetchClusters", () => {
  it("keeps a cluster whose scrape failed, with its figures absent rather than zero", async () => {
    const answer = await fetchClusters(
      client({
        clusters: {
          status: "ok",
          fetchedAt: "2026-09-05T12:00:00Z",
          data: [
            row("healthy-01", { status: "ok", fetchedAt: "2026-09-05T12:00:00Z", data: scrape }),
            row("silent-01", { status: "unavailable", reason: "UPSTREAM_UNAVAILABLE", message: "no answer" }),
          ],
        },
      }),
    );

    expect(answer.kind).toBe("ready");
    const clusters = answer.kind === "ready" ? answer.value : [];
    expect(clusters.map((c) => c.id)).toEqual(["healthy-01", "silent-01"]);

    const silent = clusters[1]!;
    expect(silent.name).toBe("silent-01");
    // The whole point: not zero.
    expect(silent.brokersOnline).toBeNull();
    expect(silent.partitions).toBeNull();
    expect(silent.version).toBeNull();
    expect(silent.health).toBe("unknown");

    const healthy = clusters[0]!;
    expect(healthy.brokersOnline).toBe(3);
    expect(healthy.health).toBe("healthy");
    // Absent because this fixture carries no `topics` section: the counts live there, not on the
    // scrape, so a cluster whose topic service did not answer has no topic count — and says so.
    expect(healthy.topics).toBeNull();
  });

  it("shows a stale list rather than hiding it, and says it is stale", async () => {
    const answer = await fetchClusters(
      client({
        clusters: {
          status: "stale",
          fetchedAt: "2026-09-05T11:00:00Z",
          data: [row("prod", { status: "ok", fetchedAt: "x", data: scrape })],
          // The wire shape, verified against `libs/contracts-core/src/kui/contracts/Section.scala`:
          // `reason` is a flat code string with `message` and `since` as its siblings, not a nested
          // object. Getting this wrong in a fixture is how a test passes against a shape the server
          // never sends.
          reason: "SCRAPE_FAILED",
          message: "The last scrape did not finish.",
        },
      }),
    );

    expect(answer.kind).toBe("stale");
    if (answer.kind === "stale") {
      expect(answer.value).toHaveLength(1);
      expect(answer.reason).toBe("The last scrape did not finish.");
    }
  });

  it("keeps forbidden and not-configured separate from a failure", async () => {
    // Three states that all render "no clusters here" and mean different things. Only one of them
    // has a retry that could ever work, which is why they must not be merged.
    const forbidden = await fetchClusters(client({ clusters: { status: "forbidden" } }));
    expect(forbidden.kind).toBe("forbidden");

    const absent = await fetchClusters(client({ clusters: { status: "not_configured" } }));
    expect(absent.kind).toBe("not-configured");

    const broken = await fetchClusters(
      client({ clusters: { status: "unavailable", reason: "UPSTREAM_UNAVAILABLE", message: "no" } }),
    );
    expect(broken.kind).toBe("failed");
  });

  it("reports a transport failure with a sentence rather than undefined", async () => {
    // Only the `envelope` error carries a `message`. Reaching for `.message` on an unreachable
    // gateway is how a screen renders "undefined" at exactly the wrong moment.
    const answer = await fetchClusters(client({ kind: "unreachable", cause: "ECONNREFUSED" }, false));
    expect(answer.kind).toBe("failed");
    if (answer.kind === "failed") {
      expect(answer.message).not.toContain("undefined");
      expect(answer.message.length).toBeGreaterThan(0);
      expect(answer.code).toBe("UNREACHABLE");
    }
  });

  it("treats a section it cannot read as unavailable rather than throwing", async () => {
    const answer = await fetchClusters(client({ clusters: { status: "something-new" } }));
    expect(answer.kind).toBe("failed");
  });
});

describe("fetchBrokers", () => {
  it("maps a broker whose disk could not be read to nulls, not zeroes", async () => {
    const answer = await fetchBrokers(
      client({
        brokers: {
          status: "ok",
          fetchedAt: "2026-09-05T12:00:00Z",
          data: [
            {
              id: 1,
              host: "broker-1",
              port: 9092,
              isController: true,
              // The server's names, verified against `src/recorded/brokers.json`. Every plausible
              // guess — `leaderPartitions`, `replicaPartitions`, `diskUsedBytes` — is wrong, and
              // wrong silently: the card renders as em dashes, which reads as a broker that did not
              // answer.
              leaderCount: 512,
              replicaCount: 1536,
              diskUsageBytes: 100,
            },
            { id: 2, host: "broker-2", port: 9092 },
          ],
        },
      }),
      "prod",
    );

    expect(answer.kind).toBe("ready");
    const brokers = answer.kind === "ready" ? answer.value : [];

    expect(brokers[0]).toMatchObject({ id: 1, isController: true, leaderPartitions: 512, health: "healthy" });
    expect(brokers[0]?.replicaPartitions).toBe(1536);
    expect(brokers[0]?.diskUsedBytes).toBe(100);
    // Neither is on the wire at all: the endpoint carries no per-broker out-of-sync count and no
    // disk total. `null` says so; a `0` would be a claim nobody made, and a total of `0` would make
    // every disk bar read as full.
    expect(brokers[0]?.outOfSyncReplicas).toBeNull();
    expect(brokers[0]?.diskTotalBytes).toBeNull();

    expect(brokers[1]?.diskUsedBytes).toBeNull();
    expect(brokers[1]?.leaderPartitions).toBeNull();
    expect(brokers[1]?.isController).toBe(false);
    expect(brokers[1]?.health).toBe("unknown");
  });
});

describe("healthOf", () => {
  it("ranks an offline partition above an under-replicated one", () => {
    // Offline is neither readable nor writable; under-replicated is readable and at risk. A cluster
    // with both is reported as the worse of the two.
    expect(healthOf({ offlinePartitionCount: 1, underReplicatedPartitionCount: 40 })).toBe("offline");
    expect(healthOf({ offlinePartitionCount: 0, underReplicatedPartitionCount: 40 })).toBe("degraded");
    expect(healthOf({ offlinePartitionCount: 0, underReplicatedPartitionCount: 0 })).toBe("healthy");
  });

  it("says unknown when there was no scrape, rather than guessing", () => {
    expect(healthOf(undefined)).toBe("unknown");
  });
});

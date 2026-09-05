/**
 * The cluster and broker screens' tests.
 *
 * The pure half exercises the rules that decide a colour, a threshold or a sentence; the rendered
 * half exercises what is only true once markup exists — that an unmeasurable disk draws no bar and
 * an em dash rather than a 0% bar, that a log directory which did not answer keeps its row, that a
 * sensitive setting is neither blank nor a dash, and that one failing tab leaves the other alone.
 */

import { describe, expect, it } from "vitest";
import { flush } from "solid-js";
import { describeViolations, findViolations, mount } from "./testing.js";
import {
  DISK_CRITICAL_PERCENT,
  DISK_WARN_PERCENT,
  brokerMeta,
  clusterVoice,
  configMatches,
  controllerCaption,
  diskPercent,
  healthLabel,
  partitionSkew,
  sortConfigs,
  totalLogDirBytes,
  voiceOf,
  type Broker,
} from "./model.js";
import { ClusterList } from "./ClusterList.jsx";
import { BrokerList } from "./BrokerList.jsx";
import { BrokerDetail } from "./BrokerDetail.jsx";
import { DEGRADED_BROKERS, RACKED_BROKERS, SAMPLE_BROKERS, SAMPLE_CLUSTERS, SAMPLE_CONFIGS, SAMPLE_LOG_DIRS } from "./fixtures.js";

const noop = (): void => {};

describe("disk", () => {
  it("returns undefined rather than zero when a disk cannot be measured", () => {
    // A 0%-full disk and an unmeasurable disk mean opposite things and must not draw the same bar.
    expect(diskPercent(null, 1_000)).toBeUndefined();
    expect(diskPercent(500, null)).toBeUndefined();
    expect(diskPercent(500, 0)).toBeUndefined();
    expect(diskPercent(0, 1_000)).toBe(0);
  });

  it("agrees with the design's own three brokers", () => {
    expect(Math.round(diskPercent(610, 1_000) ?? -1)).toBe(61);
    expect(Math.round(diskPercent(580, 1_000) ?? -1)).toBe(58);
    expect(Math.round(diskPercent(830, 1_000) ?? -1)).toBe(83);
  });

  it("puts the amber and the red where SPEC §4.20 puts them", () => {
    expect(DISK_WARN_PERCENT).toBe(75);
    expect(DISK_CRITICAL_PERCENT).toBe(90);
  });
});

describe("partition spread", () => {
  it("is undefined for a single broker, because there is no spread to measure", () => {
    expect(partitionSkew(SAMPLE_BROKERS.slice(0, 1))).toBeUndefined();
  });

  it("is zero when every broker holds the same number", () => {
    expect(partitionSkew(SAMPLE_BROKERS)).toBe(0);
  });

  it("ignores the brokers whose replica count could not be read", () => {
    const counted: Broker[] = [
      { ...SAMPLE_BROKERS[0]!, replicaPartitions: 1_000 },
      { ...SAMPLE_BROKERS[1]!, replicaPartitions: null },
      { ...SAMPLE_BROKERS[2]!, replicaPartitions: 2_000 },
    ];
    // Mean of the two that answered is 1,500; the gap is 1,000.
    expect(partitionSkew(counted)).toBeCloseTo(1_000 / 1_500);
  });

  it("does not count a broker that is down as a broker holding no partitions", () => {
    // A dead broker holds zero replicas, and counting it turns a perfectly even cluster into a
    // 200% skew — restating an outage that is already stated in words as a second, different
    // problem.
    const skew = partitionSkew(DEGRADED_BROKERS);
    expect(skew).toBeDefined();
    expect(skew).toBeLessThan(1);
  });
});

describe("the voice", () => {
  it("drops the aside the moment a broker is down", () => {
    const healthy = clusterVoice(voiceOf(SAMPLE_BROKERS, 0, "2s ago"));
    expect(healthy).toContain("sip your coffee");

    const failing = clusterVoice(voiceOf(DEGRADED_BROKERS, 47, "2s ago"));
    expect(failing).not.toContain("coffee");
    expect(failing).toContain("not accepting writes");
  });

  it("calls an under-replicated cluster degraded rather than healthy", () => {
    expect(voiceOf(SAMPLE_BROKERS, 47, null).kind).toBe("degraded");
  });

  it("says plainly that there is no controller, with no joke attached", () => {
    expect(controllerCaption(1)).toContain("fair and square");
    expect(controllerCaption(null)).toBe("No controller. The cluster has not elected one.");
  });

  it("leaves a leader count it could not read out of the metadata line, rather than printing zero", () => {
    const unreadable: Broker = { ...SAMPLE_BROKERS[0]!, leaderPartitions: null };
    expect(brokerMeta(unreadable)).toBe("id 1");
    expect(brokerMeta(SAMPLE_BROKERS[0]!)).toBe("id 1 · 512 leaders");
  });

  it("separates a broker that is down from one KUI could not reach", () => {
    expect(healthLabel("offline")).toBe("offline");
    expect(healthLabel("unknown")).toBe("unreachable");
  });
});

describe("the cluster list", () => {
  it("draws a broker fraction rather than a bare count when one is missing", async () => {
    const { container, dispose } = mount(() => <ClusterList clusters={SAMPLE_CLUSTERS} hrefFor={(id) => `/c/${id}`} />);
    await flush();
    expect(container.textContent).toContain("3/3");
    expect(container.textContent).toContain("2/3");
    dispose();
  });

  it("draws every unreadable figure as a dash with a reason, never as a zero", async () => {
    const { container, dispose } = mount(() => <ClusterList clusters={SAMPLE_CLUSTERS} hrefFor={(id) => `/c/${id}`} />);
    await flush();
    const row = [...container.querySelectorAll("tbody tr")].find((tr) => (tr.textContent ?? "").includes("archive-eu"));
    expect(row?.textContent).toContain("—");
    expect(row?.textContent).not.toContain("0");
    dispose();
  });

  it("has no axe violations", async () => {
    const { container, dispose } = mount(() => <ClusterList clusters={SAMPLE_CLUSTERS} hrefFor={(id) => `/c/${id}`} onOpen={noop} />);
    await flush();
    expect(describeViolations(await findViolations(container))).toBe("");
    dispose();
  });
});

describe("the broker list", () => {
  function list(brokers: readonly Broker[], underReplicated: number | null = 0) {
    return mount(() => (
      <BrokerList
        clusterName="prod-kyiv-01"
        brokers={brokers}
        underReplicatedPartitions={underReplicated}
        observedAgo="2s ago"
        clustersHref="/clusters"
        hrefFor={(id) => `/b/${id}`}
      />
    ));
  }

  it("draws one health row per broker, each naming its own metric for a screen reader", async () => {
    const { container, dispose } = list(SAMPLE_BROKERS);
    await flush();
    expect(container.querySelectorAll(".kui-brk-health__row")).toHaveLength(3);
    const bars = [...container.querySelectorAll('[role="progressbar"]')];
    expect(bars.map((bar) => bar.getAttribute("aria-label"))).toContain("broker-3.kyiv:9092 disk usage");
    dispose();
  });

  it("prints an em dash, not a percentage, for a disk it could not measure", async () => {
    const { container, dispose } = list(DEGRADED_BROKERS, 47);
    await flush();
    const offline = container.querySelector('[data-testid="broker-health-2"]');
    expect(offline?.textContent).toContain("—");
    expect(offline?.textContent).not.toContain("0%");
    dispose();
  });

  it("drops the rack column when the cluster is not rack-aware, and keeps it when it is", async () => {
    const plain = list(SAMPLE_BROKERS);
    await flush();
    const plainHeaders = headers(plain.container, "brokers-table");
    expect(plainHeaders).not.toContain("Rack");
    plain.dispose();

    const racked = list(RACKED_BROKERS);
    await flush();
    expect(headers(racked.container, "brokers-table")).toContain("Rack");
    racked.dispose();
  });

  it("says who the controller is under the panel", async () => {
    const { container, dispose } = list(SAMPLE_BROKERS);
    await flush();
    expect(container.textContent).toContain("Controller: broker 1");
    dispose();
  });

  it("has no axe violations, degraded or healthy", async () => {
    const healthy = list(SAMPLE_BROKERS);
    await flush();
    expect(describeViolations(await findViolations(healthy.container))).toBe("");
    healthy.dispose();

    const degraded = list(DEGRADED_BROKERS, 47);
    await flush();
    expect(describeViolations(await findViolations(degraded.container))).toBe("");
    degraded.dispose();
  });
});

describe("broker configuration", () => {
  it("puts what somebody changed above what Kafka defaulted to", () => {
    const sorted = sortConfigs(SAMPLE_CONFIGS);
    expect(sorted[0]?.source).toBe("DYNAMIC_BROKER");
    expect(sorted[sorted.length - 1]?.source).toBe("DEFAULT");
  });

  it("matches on the name and on the value, ignoring case", () => {
    const entry = SAMPLE_CONFIGS.find((one) => one.name === "compression.type")!;
    expect(configMatches(entry, "COMPRESSION")).toBe(true);
    expect(configMatches(entry, "producer")).toBe(true);
    expect(configMatches(entry, "retention")).toBe(false);
    expect(configMatches(entry, "  ")).toBe(true);
  });

  it("shows a sensitive value as hidden — neither a blank nor a dash", async () => {
    const { container, dispose } = detail({ configuration: { kind: "ready", value: SAMPLE_CONFIGS } }, "configuration");
    await flush();
    const row = [...container.querySelectorAll("tbody tr")].find((tr) => (tr.textContent ?? "").includes("ssl.keystore.password"));
    expect(row?.textContent).toContain("hidden");
    expect(row?.textContent).not.toContain("—");
    dispose();
  });

  it("offers a clear-filter way out when a filter matched nothing", async () => {
    const { container, dispose } = detail({ configuration: { kind: "ready", value: SAMPLE_CONFIGS } }, "configuration");
    await flush();
    const input = container.querySelector<HTMLInputElement>('[data-testid="broker-configuration"] input');
    input!.value = "nothing-like-this";
    input!.dispatchEvent(new Event("input", { bubbles: true }));
    await flush();
    expect(container.textContent).toContain("Nothing matched nothing-like-this.");
    expect(container.textContent).toContain("Clear filter");
    dispose();
  });

  it("keeps the same input element while somebody is typing in it", async () => {
    const { container, dispose } = detail({ configuration: { kind: "ready", value: SAMPLE_CONFIGS } }, "configuration");
    await flush();
    const before = container.querySelector('[data-testid="broker-configuration"] input');
    before!.dispatchEvent(new Event("input", { bubbles: true }));
    (before as HTMLInputElement).value = "log";
    before!.dispatchEvent(new Event("input", { bubbles: true }));
    await flush();
    // Node identity, not markup equality: a rebuilt field loses the caret and the composition.
    expect(container.querySelector('[data-testid="broker-configuration"] input')).toBe(before);
    dispose();
  });
});

describe("log directories", () => {
  it("keeps a row for a directory that did not answer, with the error on it", async () => {
    const { container, dispose } = detail({ logDirs: { kind: "ready", value: SAMPLE_LOG_DIRS } }, "logdirs");
    await flush();
    const rows = [...container.querySelectorAll("tbody tr")];
    expect(rows).toHaveLength(4);
    const broken = rows.find((tr) => (tr.textContent ?? "").includes("nvme2"));
    expect(broken?.textContent).toContain("unreadable");
    expect(broken?.textContent).toContain("KafkaStorageException");
    dispose();
  });

  it("sums only the directories that answered", () => {
    expect(totalLogDirBytes(SAMPLE_LOG_DIRS)).toBe(412_000_000_000 + 198_000_000_000 + 0);
    expect(totalLogDirBytes([{ path: "/x", sizeBytes: null, partitions: null, error: "gone" }])).toBeNull();
  });

  it("says the total is lower than the truth when a directory could not be read", async () => {
    const { container, dispose } = detail({ logDirs: { kind: "ready", value: SAMPLE_LOG_DIRS } }, "logdirs");
    await flush();
    expect(container.textContent).toContain("lower than the truth");
    dispose();
  });
});

describe("the broker detail page", () => {
  it("lets one tab fail without touching the other", async () => {
    const { container, dispose } = detail(
      {
        logDirs: { kind: "ready", value: SAMPLE_LOG_DIRS },
        configuration: { kind: "unavailable", message: "Broker configuration is unavailable.", code: "UPSTREAM_UNAVAILABLE", onRetry: noop },
      },
      "logdirs",
    );
    await flush();
    // The visible tab is unharmed, and the failing one has not blanked the page.
    expect(container.querySelector('[data-testid="broker-logdirs-table"]')).not.toBeNull();
    dispose();
  });

  it("keeps the frame, the code and a retry when a tab is unavailable", async () => {
    const { container, dispose } = detail(
      { configuration: { kind: "unavailable", message: "Broker configuration is unavailable.", code: "UPSTREAM_UNAVAILABLE", onRetry: noop } },
      "configuration",
    );
    await flush();
    expect(container.textContent).toContain("UPSTREAM_UNAVAILABLE");
    expect(container.textContent).toContain("Retry");
    expect(container.querySelector('[data-testid="broker-configuration"]')).not.toBeNull();
    dispose();
  });

  it("offers no edit control at all, not even a disabled one", async () => {
    const { container, dispose } = detail({ configuration: { kind: "ready", value: SAMPLE_CONFIGS } }, "configuration");
    await flush();
    const labels = [...container.querySelectorAll("button")].map((b) => (b.textContent ?? "").toLowerCase());
    expect(labels.some((label) => label.includes("edit"))).toBe(false);
    dispose();
  });

  it("has no axe violations on either tab", async () => {
    for (const tab of ["logdirs", "configuration"] as const) {
      const mounted = detail({ logDirs: { kind: "ready", value: SAMPLE_LOG_DIRS }, configuration: { kind: "ready", value: SAMPLE_CONFIGS } }, tab);
      await flush();
      expect(describeViolations(await findViolations(mounted.container))).toBe("");
      mounted.dispose();
    }
  });
});

type DetailOverrides = Partial<Pick<Parameters<typeof BrokerDetail>[0], "logDirs" | "configuration">>;

function detail(overrides: DetailOverrides, tab: "logdirs" | "configuration") {
  return mount(() => (
    <BrokerDetail
      broker={SAMPLE_BROKERS[0]!}
      clusterName="prod-kyiv-01"
      clustersHref="/clusters"
      brokersHref="/brokers"
      logDirs={overrides.logDirs ?? { kind: "loading" }}
      configuration={overrides.configuration ?? { kind: "loading" }}
      tab={tab}
      onTabChange={noop}
    />
  ));
}

function headers(root: ParentNode, testId: string): string[] {
  return [...root.querySelectorAll(`[data-testid="${testId}"] th`)].map((th) => th.textContent ?? "");
}

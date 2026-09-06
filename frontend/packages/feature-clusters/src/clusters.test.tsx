/**
 * The cluster and broker screens' tests.
 *
 * The pure half exercises the rules that decide a colour, a threshold or a sentence; the rendered
 * half exercises what is only true once markup exists — that an unmeasurable disk draws no bar and
 * an em dash rather than a 0% bar, that a log directory which did not answer keeps its row, that a
 * sensitive setting is neither blank nor a dash, and that one failing tab leaves the other alone.
 */

import { afterEach, describe, expect, it, vi } from "vitest";
import { flush } from "solid-js";
import { KuiProvider, clearToasts, toasts } from "@kui/kernel";
import type { KuiApiClient } from "@kui/api";
import { describeViolations, findViolations, mount, settle, testContext } from "./testing.js";
import {
  DISK_CRITICAL_PERCENT,
  DISK_WARN_PERCENT,
  brokerMeta,
  clusterVoice,
  configMatches,
  controllerCaption,
  diskNote,
  diskPercent,
  healthLabel,
  partitionSkew,
  sortConfigs,
  summariseDisk,
  totalLogDirBytes,
  versionTag,
  voiceOf,
  type Broker,
} from "./model.js";
import { ClusterList } from "./ClusterList.jsx";
import { BrokerList } from "./BrokerList.jsx";
import { BrokersScreen } from "./BrokersScreen.jsx";
import { BrokerDetail } from "./BrokerDetail.jsx";
import { ManageScreen } from "./ClustersRoute.jsx";
import { DEGRADED_BROKERS, RACKED_BROKERS, SAMPLE_BROKERS, SAMPLE_CLUSTERS, SAMPLE_CONFIGS, SAMPLE_LOG_DIRS, UNMEASURED_BROKERS } from "./fixtures.js";

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

  it("tells a measured zero from a count nobody read", () => {
    // `underReplicatedPartitionCount` is a number on the wire and is genuinely `0` on a healthy
    // cluster, so both branches are reached in production. Sharing one sentence between them is the
    // product announcing that everything is fine on evidence it does not have.
    const measured = clusterVoice(voiceOf(SAMPLE_BROKERS, 0, "2s ago"));
    expect(measured).toContain("Zero under-replicated partitions");

    const unmeasured = clusterVoice(voiceOf(SAMPLE_BROKERS, null, "2s ago"));
    expect(unmeasured).not.toContain("Zero");
    expect(unmeasured).not.toContain("coffee");
    expect(unmeasured).toContain("not claiming there are none");
  });

  it("does not tell an operator with a broker down that zero partitions are at risk", () => {
    const line = clusterVoice(voiceOf(DEGRADED_BROKERS, null, "2s ago"));
    expect(line).toContain("down");
    expect(line).not.toContain("0 partitions");
    expect(line).toContain("not known");
  });
});

describe("the disk", () => {
  it("says which of the two silences it is, and says nothing where the percentage speaks", () => {
    // Three states behind one empty bar: measured, no capacity to measure against, and nothing
    // measured at all. The first needs no sentence; the other two are not each other.
    expect(diskNote(SAMPLE_BROKERS[0]!)).toBeUndefined();
    expect(diskNote(UNMEASURED_BROKERS[0]!)).toContain("No disk capacity was reported");
    expect(diskNote({ ...UNMEASURED_BROKERS[0]!, heldBytes: null })).toContain("were not measured");
  });

  it("sums a capacity only over the brokers that reported one", () => {
    const summary = summariseDisk([SAMPLE_BROKERS[0]!, UNMEASURED_BROKERS[0]!]);
    // Half a cluster's capacity produces a percentage that looks perfectly plausible and is wrong,
    // so the pair counts only the broker that answered — and says how many did.
    expect(summary.measured).toBe(1);
    expect(summary.brokers).toBe(2);
    expect(summary.capacityBytes).toBe(1_000_000_000_000);
    // What Kafka holds is a different sum with a different fate, and both brokers report it.
    expect(summary.heldBytes).toBe(128_000_000_000 + 95_320);
  });

  it("has no capacity at all to report when nothing answered", () => {
    const summary = summariseDisk([{ ...UNMEASURED_BROKERS[0]!, heldBytes: null }]);
    expect(summary.usedBytes).toBeNull();
    expect(summary.capacityBytes).toBeNull();
    expect(summary.heldBytes).toBeNull();
  });

  it("tags a version the scrape reported and nothing where it did not", () => {
    expect(versionTag("4.3", "kraft")).toBe("v4.3 · KRaft");
    expect(versionTag("v3.7.0", null)).toBe("v3.7.0");
    expect(versionTag(null, "kraft")).toBeUndefined();
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
    /* `null` is a real argument here and not a default: it is what the wire answers before the
       first sweep lands, and the voice line has a third sentence for it. */
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

  it("draws one card per broker, each naming its own disk bar for a screen reader", async () => {
    // Was one health *row* per broker; the screen is cards now. The property is unchanged and is
    // the one that matters: every bar names the broker it belongs to, so a screen reader user hears
    // "broker-3.kyiv:9092 disk usage" rather than three bars called "disk".
    const { container, dispose } = list(SAMPLE_BROKERS);
    await flush();
    expect(container.querySelectorAll(".kui-brkcard")).toHaveLength(3);
    const bars = [...container.querySelectorAll('[role="progressbar"]')];
    expect(bars.map((bar) => bar.getAttribute("aria-label"))).toContain("broker-3.kyiv:9092 disk usage");
    dispose();
  });

  it("prints an em dash, not a percentage, for a disk it could not measure", async () => {
    const { container, dispose } = list(DEGRADED_BROKERS, 47);
    await flush();
    const offline = container.querySelector('[data-testid="broker-2"]');
    expect(offline?.textContent).toContain("—");
    expect(offline?.textContent).not.toContain("0%");
    dispose();
  });

  it("drops the rack figure when the cluster is not rack-aware, and keeps it when it is", async () => {
    // The table's rack *column* became the card's rack *figure*, and the rule survived the move: a
    // figure that is an em dash on every card is what teaches people to stop reading figures.
    const plain = list(SAMPLE_BROKERS);
    await flush();
    expect(plain.container.textContent).not.toContain("RACK");
    plain.dispose();

    const racked = list(RACKED_BROKERS);
    await flush();
    expect(racked.container.textContent).toContain("RACK");
    racked.dispose();
  });

  it("says who the controller is", async () => {
    const { container, dispose } = list(SAMPLE_BROKERS);
    await flush();
    const tile = container.querySelector(".kui-brk-tiles")!;
    expect(tile.textContent).toContain("ACTIVE CONTROLLER");
    expect(tile.textContent).toContain("broker 1");
    dispose();
  });

  it("opens a broker's configuration rather than showing it always", async () => {
    // A broker has around two hundred settings. Collapsed the card answers "is this all right?";
    // expanded it answers "why is it behaving like that?". Those are asked at different moments.
    const { container, dispose } = list(SAMPLE_BROKERS);
    await flush();
    expect(container.textContent).not.toContain("CONFIGURATION");
    const toggle = container.querySelector('[data-testid="broker-1"] .kui-brkcard__toggle') as HTMLButtonElement;
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    toggle.click();
    await flush();
    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(container.textContent).toContain("CONFIGURATION");
    dispose();
  });

  it("shows a sentence, and no percentage, for a disk that was not measured", async () => {
    // The quickstart's own shape: Kafka's usage is known and the disk beneath it is not, which is
    // the ordinary answer from a broker whose log directories KUI cannot read. An empty bar beside
    // a dash is indistinguishable from an empty disk, and the two mean opposite things.
    const { container, dispose } = list(UNMEASURED_BROKERS, null);
    await flush();
    const card = container.querySelector('[data-testid="broker-1"]')!;
    expect(card.textContent).toContain("No disk capacity was reported");
    expect(card.textContent).not.toContain("%");
    dispose();
  });

  it("draws a percentage where the log directories did report a capacity", async () => {
    const { container, dispose } = list(SAMPLE_BROKERS);
    await flush();
    expect(container.querySelector('[data-testid="broker-1"]')?.textContent).toContain("61%");
    expect(container.querySelector('[data-testid="broker-1"]')?.textContent).not.toContain(
      "No disk capacity",
    );
    dispose();
  });

  it("says it cannot show an uptime rather than inventing one", async () => {
    // The design's tag row carries `uptime 41d` and no endpoint in this product reports when a
    // broker started, which SCREENS-V4 §4.5 says in as many words.
    const { container, dispose } = list(SAMPLE_BROKERS);
    await flush();
    (container.querySelector('[data-testid="broker-1"] .kui-brkcard__toggle') as HTMLButtonElement).click();
    await flush();
    expect(container.textContent).toContain("Kafka reports no broker uptime");
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


/**
 * The brokers screen, fetching.
 *
 * These are the cases the last wave could not have had. `BrokerList` takes an
 * `underReplicatedPartitions` prop and every case above hands it in by hand, so all of them pass
 * over a route that never passes it — which is exactly what shipped: the voice line announced
 * "Zero under-replicated partitions" on every cluster in the product, including the ones nothing
 * had scraped. So these mount the screen, give it nothing but a gateway, and assert on what the
 * screen asks for and what it draws.
 *
 * Every case uses a cluster id of its own. The query registry is shared across the whole browser
 * tab by design, and two cases sharing a key would have the second one reading the first's answer
 * out of the cache and issuing no request at all — which would make the request counts below pass
 * for the wrong reason.
 */
describe("the brokers screen", () => {
  const fetchedAt = "2026-09-06T09:00:00.000Z";
  const ok = (data: unknown) => ({ status: "ok", data, fetchedAt });

  /** One broker, in the fields the gateway really sends. See `recorded/brokers.json`. */
  const BROKERS = {
    brokers: ok([
      {
        id: 1,
        host: "kafka",
        port: 9092,
        rack: null,
        isController: true,
        leaderCount: 86,
        replicaCount: 86,
        diskUsageBytes: 95_320,
      },
    ]),
  };

  /** The cluster's own document, whose summary carries the count the voice line reads. */
  const cluster = (underReplicated: number | null) => ({
    cluster: {
      id: "quickstart",
      name: "Quickstart (local)",
      readOnly: false,
      bootstrapServers: "kafka:9092",
      summary: ok({
        version: "4.3",
        controllerKind: "kraft",
        brokerCount: 1,
        underReplicatedPartitionCount: underReplicated,
        scrapedAt: fetchedAt,
      }),
    },
  });

  /** 1 TB of filesystem with 400 GB free: 60% used, which is the percentage the card must draw. */
  const LOG_DIRS = {
    logDirs: ok([
      {
        brokerId: 1,
        path: "/tmp/kafka-logs",
        error: null,
        totalBytes: 1_000_000_000_000,
        usableBytes: 400_000_000_000,
        partitionCount: 58,
        replicas: [{ sizeBytes: 95_320 }],
      },
    ]),
  };

  const CONFIGS = {
    configs: ok([
      { name: "log.retention.hours", value: "72", source: "dynamic-broker" },
      { name: "compression.type", value: "producer", source: "default" },
    ]),
  };

  /**
   * A gateway that answers by endpoint template and records what it was asked.
   *
   * Keyed by the path as the client names it — `/api/v1/clusters/{clusterId}/brokers` — because
   * that is the string the mapping passes, and asserting on it is asserting that the screen called
   * the endpoint it meant to.
   */
  function gateway(answers: Readonly<Record<string, unknown>>) {
    const asked: string[] = [];
    const get = vi.fn(async (path: string) => {
      asked.push(path);
      const answer = answers[path];
      return answer === undefined
        ? { ok: false, error: { kind: "unreachable", cause: `nothing stubbed for ${path}` } }
        : { ok: true, value: answer };
    });
    return {
      asked,
      api: { get, post: get, put: get, delete: get, patch: get, raw: {} } as unknown as KuiApiClient,
    };
  }

  const everything = (underReplicated: number | null) => ({
    "/api/v1/clusters/{clusterId}/brokers": BROKERS,
    "/api/v1/clusters/{clusterId}": cluster(underReplicated),
    "/api/v1/clusters/{clusterId}/log-dirs": LOG_DIRS,
    "/api/v1/clusters/{clusterId}/brokers/{brokerId}/configs": CONFIGS,
  });

  function open(api: KuiApiClient, clusterId: string) {
    return mount(() => (
      <KuiProvider value={testContext(api)}>
        <BrokersScreen
          clusterId={clusterId}
          clustersHref="/ui/clusters"
          hrefFor={(brokerId) => `/ui/clusters/${clusterId}/brokers/${brokerId}`}
          now={() => new Date(fetchedAt)}
        />
      </KuiProvider>
    ));
  }

  const configRequests = (asked: readonly string[]): number =>
    asked.filter((path) => path.endsWith("/configs")).length;

  /** Opens broker 1's card, which is what asks for its settings. */
  function expand(container: HTMLElement): void {
    const toggle = container.querySelector('[data-testid="broker-1"] .kui-brkcard__toggle');
    (toggle as HTMLButtonElement).click();
  }

  it("says zero under-replicated partitions only because the cluster said zero", async () => {
    const { api } = gateway(everything(0));
    const { container, dispose } = open(api, "urp-measured");
    await settle(container);
    const voice = container.querySelector('[data-testid="brokers-head"]')?.textContent ?? "";
    expect(voice).toContain("Zero under-replicated partitions");
    dispose();
  });

  it("does not claim zero over a cluster whose under-replication was never measured", async () => {
    /*
     * The mutation case. A freshly started stack answers `null` here until the first partition
     * sweep lands, and the screen used to print the cheerful line over it — the product telling an
     * operator everything is fine about the one number nobody had managed to read.
     */
    const { api } = gateway(everything(null));
    const { container, dispose } = open(api, "urp-unmeasured");
    await settle(container);
    const voice = container.querySelector('[data-testid="brokers-head"]')?.textContent ?? "";
    expect(voice).not.toContain("Zero");
    expect(voice).not.toContain("coffee");
    expect(voice).toContain("not claiming there are none");
    dispose();
  });

  it("asks for a broker's settings when its card is opened, and not before", async () => {
    // `describeConfigs` is three hundred and forty rows and sixty kilobytes per broker. A page
    // nobody expands must not pay for it, and expanding one broker must not fetch the others'.
    const { api, asked } = gateway(everything(0));
    const { container, dispose } = open(api, "lazy-configs");
    await settle(container);
    expect(configRequests(asked)).toBe(0);

    (container.querySelector('[data-testid="broker-1"] .kui-brkcard__toggle') as HTMLButtonElement).click();
    await settle(container);
    expect(configRequests(asked)).toBe(1);
    expect(container.textContent).toContain("log.retention.hours");
    dispose();
  });

  it("draws the disk percentage against the capacity the log directories reported", async () => {
    const { container, dispose } = open(gateway(everything(0)).api, "disk-measured");
    await settle(container);
    // 600 GB of a 1 TB filesystem. The brokers endpoint's `diskUsageBytes` is 95 kB of Kafka data
    // on that disk, and reading the second as the first would draw a broker at 0%.
    expect(container.querySelector('[data-testid="broker-1"]')?.textContent).toContain("60%");
    dispose();
  });

  it("keeps drawing every card when the log directories are refused", async () => {
    const answers = { ...everything(0) };
    delete (answers as Record<string, unknown>)["/api/v1/clusters/{clusterId}/log-dirs"];
    const { container, dispose } = open(gateway(answers).api, "disk-refused");
    await settle(container);
    const card = container.querySelector('[data-testid="broker-1"]');
    expect(card).not.toBeNull();
    expect(card?.textContent).toContain("No disk capacity was reported");
    dispose();
  });

  it("has no axe violations over a real answer", async () => {
    const { container, dispose } = open(gateway(everything(0)).api, "axe-brokers");
    await settle(container);
    expect(describeViolations(await findViolations(container))).toBe("");
    dispose();
  });

  /* -------------------------------------------------------------------------------------------- */
  /* What the screen draws while it is still asking, and the props nothing ever asserted           */
  /* -------------------------------------------------------------------------------------------- */

  /**
   * A gateway that has not answered yet.
   *
   * Every call returns a promise that never settles, which is what the first paint of this screen
   * actually looks like — and is the state the whole product's central rule is easiest to break in,
   * because every figure is absent and the tempting rendering of an absent figure is a zero.
   */
  function silence(): KuiApiClient {
    const get = vi.fn(() => new Promise<never>(() => {}));
    const client = { get, post: get, put: get, delete: get, patch: get, raw: {} };
    return client as unknown as KuiApiClient;
  }

  it("draws neither a zero nor a claim about the cluster while the brokers are in flight", async () => {
    /*
     * The live defect this packet was written for, at the seam. `BrokerList`'s `loading` prop was
     * declared in wave 2, fed from `BrokersScreen` in wave 3 and read by nothing, and with the
     * request delayed the screen stated four things it could not know: that the cluster was not
     * answering, that its last successful check was 24 seconds ago, that it led 0 partitions, and
     * that it had reported no brokers. Two of the four contradict each other, and the zero is a
     * bare figure where this milestone's rule demands a sentence.
     *
     * Asserted through the *screen*, over a gateway that has not answered, rather than by handing
     * `BrokerList` a `loading` prop by hand — which is the composition that let the wire look
     * connected for two waves.
     */
    const { container, dispose } = open(silence(), "brokers-in-flight");
    await settle(container);
    try {
      const page = container.textContent ?? "";

      // Not a claim about the cluster, and not the *scrape's* timestamp dressed up as a check.
      expect(page).toContain("Reading this cluster's brokers");
      expect(page).not.toContain("The cluster is not answering");
      expect(page).not.toContain("Last successful check");

      // Not the empty state: "it reported no brokers" is a claim about an answer that has not come.
      expect(page).not.toContain("No brokers.");
      expect(page).not.toContain("reported no brokers");
      expect(container.querySelector('[data-testid="brokers-pending"]')).not.toBeNull();

      // And no bare figure anywhere in the tiles. Every one of them is pending, which is a
      // skeleton — not a `0`, not an em dash, and not a chip explaining an absence nobody has
      // established yet.
      const tiles = [...container.querySelectorAll(".kui-tile")];
      expect(tiles.length).toBe(4);
      for (const tile of tiles) {
        expect(tile.getAttribute("aria-busy")).toBe("true");
        expect(tile.querySelector(".kui-tile__value")).toBeNull();
        expect(tile.querySelector(".kui-tile__absent")).toBeNull();
        expect(tile.querySelector(".kui-tile__chip")).toBeNull();
      }
    } finally {
      dispose();
    }
  });

  it("keeps the figures on screen while a refetch is out, rather than blanking them", async () => {
    // The other half of the same rule, and the reason `loading` alone is not the condition: rows
    // that have arrived stay. Blanking figures an operator is reading, to say they are being
    // fetched again, is the reference product's five-second full-page loader.
    const { container, dispose } = open(gateway(everything(0)).api, "brokers-refetching");
    await settle(container);
    try {
      expect(container.querySelector('[data-testid="brokers-pending"]')).toBeNull();
      expect(container.querySelector(".kui-tile__value")).not.toBeNull();
    } finally {
      dispose();
    }
  });

  it("tags an expanded card with the version the cluster's own summary reported", async () => {
    /*
     * `versionFor` and `controllerKind` together are the whole `v4.3 · KRaft` tag, and both could
     * be deleted with every unit, a11y and browser suite green — because the only case about the
     * tag called `versionTag` directly and the only case about the card handed it a string.
     */
    const { container, dispose } = open(gateway(everything(0)).api, "version-tag");
    await settle(container);
    try {
      expand(container);
      await settle(container);
      const card = container.querySelector('[data-testid="broker-1"]');
      expect(card?.textContent).toContain("v4.3 · KRaft");
    } finally {
      dispose();
    }
  });

  it("says how many settings a card is not showing, from the answer's own length", async () => {
    // `configsMoreFor` was deletable: the card draws eight chips and the sentence saying how many
    // more there are is what stops the chip row reading as the whole of a broker's configuration.
    const many = {
      configs: {
        status: "ok",
        data: Array.from({ length: 12 }, (_, index) => ({
          name: `setting.${index}`,
          value: `${index}`,
          source: "default",
        })),
        fetchedAt,
      },
    };
    const answers = { ...everything(0) };
    answers["/api/v1/clusters/{clusterId}/brokers/{brokerId}/configs"] = many;
    const { container, dispose } = open(gateway(answers).api, "configs-more");
    await settle(container);
    try {
      expand(container);
      await settle(container);
      const card = container.querySelector('[data-testid="broker-1"]');
      // Twelve reported, eight drawn.
      expect(card?.textContent).toContain("4 more settings");
    } finally {
      dispose();
    }
  });

  it("dates the picture from the scrape's own timestamp", async () => {
    // `observedAgo` was deletable with everything green. The freshness line is what makes a page
    // that never refreshes acceptable: the answer to "how old is this" is always on screen.
    const answers = { ...everything(0) };
    answers["/api/v1/clusters/{clusterId}"] = {
      cluster: {
        id: "quickstart",
        name: "Quickstart (local)",
        readOnly: false,
        bootstrapServers: "kafka:9092",
        summary: {
          status: "ok",
          data: {
            version: "4.3",
            controllerKind: "kraft",
            brokerCount: 1,
            underReplicatedPartitionCount: 0,
            scrapedAt: "2026-09-06T08:58:00.000Z",
          },
          fetchedAt,
        },
      },
    };
    const { container, dispose } = open(gateway(answers).api, "observed-ago");
    await settle(container);
    try {
      // The clock is fixed at 09:00:00 by `open`, and the scrape says 08:58:00.
      const line = container.querySelector('[data-testid="brokers-freshness"]')?.textContent ?? "";
      expect(line).toContain("2m ago");
      expect(line).toContain("Nothing on this page refreshes on its own");
    } finally {
      dispose();
    }
  });

  it("keeps the disk figure the brokers endpoint sent when no capacity was reported", async () => {
    /*
     * `diskFigure`'s held-bytes branch, which is deletable — and deleting it silently reverts the
     * DISK USED tile to `unknown` on a cluster that reported its usage perfectly, which is the
     * exact bug the comment above that branch says it exists to fix. So this asserts the figure is
     * *there*, not merely that the chip explains itself.
     */
    const answers = { ...everything(0) };
    delete (answers as Record<string, unknown>)["/api/v1/clusters/{clusterId}/log-dirs"];
    const { container, dispose } = open(gateway(answers).api, "disk-held-only");
    await settle(container);
    try {
      const tile = [...container.querySelectorAll(".kui-tile")].find((one) =>
        one.textContent?.includes("DISK USED"),
      );
      // 95,320 bytes of Kafka data, and no filesystem size to read it against.
      expect(tile?.querySelector(".kui-tile__value")?.textContent).toBe("95.3 kB");
      expect(tile?.querySelector(".kui-tile__absent")).toBeNull();
      expect(tile?.textContent).toContain("no disk capacity was reported");
    } finally {
      dispose();
    }
  });

  it("draws the failure panel through the screen when the brokers endpoint refuses", async () => {
    /*
     * The screen's `failure` prop was asserted only through hand-composed `BrokerList` props, which
     * is the composition blind spot `BrokersScreen`'s own header says the file exists to close: the
     * question is whether the *route* turns a refused request into that panel, not whether the
     * panel draws when handed one.
     */
    const { container, dispose } = open(gateway({}).api, "brokers-refused");
    await settle(container);
    try {
      const card = container.querySelector('[data-testid="brokers-card"]');
      expect(card).not.toBeNull();
      expect(card?.textContent).toContain("The cluster service is not responding");
      // The code is carried through rather than swallowed, and a retry is offered because this is
      // the one of the three refusals where pressing again can work.
      expect(card?.textContent).toContain("UNREACHABLE");
      const buttons = [...container.querySelectorAll("button")];
      expect(buttons.some((one) => one.textContent?.includes("Retry"))).toBe(true);
      // And the head says so rather than saying nothing about the cluster's health.
      expect(container.querySelector('[data-testid="brokers-head"]')?.textContent).toContain(
        "Broker data is unavailable",
      );
    } finally {
      dispose();
    }
  });

  it("says a broker's share of the leaderships, from the figure the cluster service sends", async () => {
    /*
     * `leaderSkewPercent` arrived on the wire in September and no screen drew it, which made it a
     * field no test could be wrong about. It is a *signed* deviation from an even share, so it is
     * drawn as a sentence: `-25%` beside the word LEADERS reads as a negative partition count.
     */
    const answers = { ...everything(0) };
    answers["/api/v1/clusters/{clusterId}/brokers"] = {
      brokers: ok([
        {
          id: 1,
          host: "kafka",
          port: 9092,
          rack: null,
          isController: true,
          leaderCount: 86,
          replicaCount: 86,
          diskUsageBytes: 95_320,
          leaderSkewPercent: -25.4,
        },
      ]),
    };
    const { container, dispose } = open(gateway(answers).api, "leader-skew");
    await settle(container);
    try {
      expand(container);
      await settle(container);
      expect(container.querySelector('[data-testid="broker-1-skew"]')?.textContent).toBe(
        "Leads 25% fewer partitions than an even share of this cluster's.",
      );
    } finally {
      dispose();
    }
  });

  it("says an even share is an even share, which is a measurement and not an absence", async () => {
    // Zero is the answer somebody came to this card for. Folding it into "leads more than" — or
    // into the sentence for a share that was never measured — throws away the reassurance.
    const answers = { ...everything(0) };
    answers["/api/v1/clusters/{clusterId}/brokers"] = {
      brokers: ok([
        { id: 1, host: "kafka", port: 9092, rack: null, isController: true, leaderCount: 86,
          replicaCount: 86, diskUsageBytes: 95_320, leaderSkewPercent: 0 },
      ]),
    };
    const { container, dispose } = open(gateway(answers).api, "leader-skew-even");
    await settle(container);
    try {
      expand(container);
      await settle(container);
      expect(container.querySelector('[data-testid="broker-1-skew"]')?.textContent).toBe(
        "Leads an even share of this cluster's partitions.",
      );
    } finally {
      dispose();
    }
  });

  it("says the share was not measured rather than drawing it as an even one", async () => {
    // The quickstart's own answer today: no partition census, so no share to measure against. A
    // zero here would say the broker is evenly balanced, which nobody established.
    const { container, dispose } = open(gateway(everything(0)).api, "leader-skew-absent");
    await settle(container);
    try {
      expand(container);
      await settle(container);
      const said = container.querySelector('[data-testid="broker-1-skew"]')?.textContent ?? "";
      expect(said).toContain("did not report this broker's share of the leaderships");
      expect(said).not.toContain("even share of this cluster's partitions");
    } finally {
      dispose();
    }
  });
});

/**
 * The registration screen's writes.
 *
 * A destructive success is the one outcome with nothing left on screen to confirm it: the row is
 * gone, and a row that is gone looks exactly like a row that was never there.
 */
describe("removing a cluster", () => {
  afterEach(() => clearToasts());

  const REGISTERED = {
    clusters: {
      status: "ok",
      fetchedAt: "2026-09-06T09:00:00.000Z",
      data: [
        {
          cluster: {
            id: "spare",
            name: "spare",
            readOnly: false,
            bootstrapServers: "spare:9092",
            origin: "stored",
            version: 4,
            summary: { status: "unavailable", reason: { code: "UPSTREAM_UNAVAILABLE" } },
          },
        },
      ],
    },
  };

  it("raises a toast the operator can read after the row has gone", async () => {
    const removed: string[] = [];
    const get = vi.fn(async () => ({ ok: true, value: REGISTERED }));
    const del = vi.fn(async (path: string) => {
      removed.push(path);
      return { ok: true, value: {} };
    });
    const api = { get, post: get, put: get, delete: del, patch: get, raw: {} } as unknown as KuiApiClient;

    const { container, dispose } = mount(() => (
      <KuiProvider value={testContext(api)}>
        <ManageScreen />
      </KuiProvider>
    ));
    await settle(container);

    const remove = [...container.querySelectorAll("button")].find(
      (button) => (button.textContent ?? "").trim() === "Remove",
    ) as HTMLButtonElement;
    remove.click();
    await flush();

    // The confirmation is type-to-confirm, so the click below is not enough on its own — which is
    // the point of the gate, and the reason this case drives it rather than calling the handler.
    // The dialog is portalled to the document, not nested in the screen — which is why this looks
    // outside the container the screen was mounted into.
    const gate = document.querySelector(".kui-confirm__input") as HTMLInputElement;
    gate.value = "spare";
    gate.dispatchEvent(new Event("input", { bubbles: true }));
    await flush();
    const confirm = [...document.querySelectorAll("button")].find((button) =>
      (button.textContent ?? "").includes("Remove cluster"),
    ) as HTMLButtonElement;
    confirm.click();
    /* Twice, deliberately: the toast is raised on the promise chain behind the mutation, and
       nothing in *this* screen re-renders when it lands — the toast region belongs to the shell —
       so the first settle returns as soon as the dialog has closed, which is before the answer has
       been handled. */
    await settle(container);
    await settle(container);

    expect(removed).toHaveLength(1);
    expect(toasts().map((toast) => toast.title)).toContain("Cluster removed");
    dispose();
  });
});

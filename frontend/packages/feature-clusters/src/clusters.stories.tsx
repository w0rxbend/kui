import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { BrokerDetail } from "./BrokerDetail.jsx";
import { BrokerList } from "./BrokerList.jsx";
import { ClusterList } from "./ClusterList.jsx";
import { DEGRADED_BROKERS, RACKED_BROKERS, SAMPLE_BROKERS, SAMPLE_CLUSTERS, SAMPLE_CONFIGS, SAMPLE_LOG_DIRS } from "./fixtures.js";

/**
 * The cluster and broker screens.
 *
 * None of these is drawn in the five screenshots, so the stories are how they get looked at at all.
 * `Healthy` is the one to put beside `01`'s broker-health panel: three brokers, 61%, 58% and 83%,
 * with the third bar amber. `Degraded` is the one that matters — a broker down, a broker
 * unreachable, a disk past 90% and every figure that could not be read drawn as an em dash rather
 * than as a zero.
 */
const meta: Meta = {
  title: "Screens/Clusters and brokers",
  parameters: { layout: "fullscreen" },
  decorators: [(Story) => <div style={{ padding: "24px" }}>{Story() as never}</div>],
};

export default meta;
type Story = StoryObj;

const noop = (): void => {};
const clusterHref = (id: string): string => `#/clusters/${id}`;
const brokerHref = (id: number): string => `#/brokers/${id}`;

/** Every cluster KUI is configured for: one healthy, one degraded, one it cannot reach. */
export const Clusters: Story = {
  render: () => <ClusterList clusters={SAMPLE_CLUSTERS} hrefFor={clusterHref} onOpen={noop} />,
};

export const ClustersLoading: Story = {
  render: () => <ClusterList clusters={[]} loading hrefFor={clusterHref} />,
};

export const ClustersEmpty: Story = {
  render: () => <ClusterList clusters={[]} hrefFor={clusterHref} />,
};

export const ClustersUnavailable: Story = {
  render: () => (
    <ClusterList
      clusters={[]}
      hrefFor={clusterHref}
      failure={{ message: "The cluster list is unavailable.", code: "UPSTREAM_UNAVAILABLE", onRetry: noop }}
    />
  ),
};

/** The design's three brokers. The third bar turns amber at 83%; the caption names the controller. */
export const Brokers: Story = {
  render: () => (
    <BrokerList clusterName="prod-kyiv-01" brokers={SAMPLE_BROKERS} underReplicatedPartitions={0} observedAgo="2s ago" clustersHref="#/clusters" hrefFor={brokerHref} onOpen={noop} />
  ),
};

/**
 * The screen an operator actually opens. One broker down, one unreachable, one disk over 90% with
 * a `disk critical` pill in words beside it, and the voice line with every trace of the joke gone.
 */
export const BrokersDegraded: Story = {
  render: () => (
    <BrokerList clusterName="prod-kyiv-01" brokers={DEGRADED_BROKERS} underReplicatedPartitions={47} observedAgo="4 minutes ago" clustersHref="#/clusters" hrefFor={brokerHref} onOpen={noop} />
  ),
};

/** A rack-aware cluster, which is the only case where the RACK column is drawn at all. */
export const BrokersRacked: Story = {
  render: () => (
    <BrokerList clusterName="prod-fra-02" brokers={RACKED_BROKERS} underReplicatedPartitions={0} observedAgo="2s ago" clustersHref="#/clusters" hrefFor={brokerHref} onOpen={noop} />
  ),
};

export const BrokersLoading: Story = {
  render: () => <BrokerList clusterName="prod-kyiv-01" brokers={[]} loading clustersHref="#/clusters" hrefFor={brokerHref} />,
};

export const BrokersUnavailable: Story = {
  render: () => (
    <BrokerList
      clusterName="prod-kyiv-01"
      brokers={[]}
      clustersHref="#/clusters"
      hrefFor={brokerHref}
      failure={{ message: "Broker data is unavailable.", code: "UPSTREAM_UNAVAILABLE", onRetry: noop }}
    />
  ),
};

/** One broker's disks. Look at the fourth row: it did not answer, and it keeps its row. */
export const BrokerLogDirs: Story = {
  render: () => (
    <BrokerDetail
      broker={SAMPLE_BROKERS[0]!}
      clusterName="prod-kyiv-01"
      clustersHref="#/clusters"
      brokersHref="#/brokers"
      tab="logdirs"
      onTabChange={noop}
      logDirs={{ kind: "ready", value: SAMPLE_LOG_DIRS }}
      configuration={{ kind: "loading" }}
    />
  ),
};

/**
 * One broker's settings, ordered by who set them. The overridden names are bright and the inherited
 * defaults are muted — the contrast between the two *is* the reading. The sensitive row says
 * `hidden`, which is neither a blank nor a dash.
 */
export const BrokerConfiguration: Story = {
  render: () => (
    <BrokerDetail
      broker={SAMPLE_BROKERS[0]!}
      clusterName="prod-kyiv-01"
      clustersHref="#/clusters"
      brokersHref="#/brokers"
      tab="configuration"
      onTabChange={noop}
      logDirs={{ kind: "ready", value: SAMPLE_LOG_DIRS }}
      configuration={{ kind: "ready", value: SAMPLE_CONFIGS }}
    />
  ),
};

/** One tab failed and the other did not. The page keeps working; the failing panel keeps its frame. */
export const BrokerTabFailedAlone: Story = {
  render: () => (
    <BrokerDetail
      broker={SAMPLE_BROKERS[2]!}
      clusterName="prod-kyiv-01"
      clustersHref="#/clusters"
      brokersHref="#/brokers"
      tab="configuration"
      onTabChange={noop}
      logDirs={{ kind: "ready", value: SAMPLE_LOG_DIRS }}
      configuration={{ kind: "unavailable", message: "Broker configuration is unavailable.", code: "UPSTREAM_UNAVAILABLE", onRetry: noop }}
    />
  ),
};

/** Refused rather than broken: the lock, the sentence, the code, and no retry that cannot help. */
export const BrokerConfigurationForbidden: Story = {
  render: () => (
    <BrokerDetail
      broker={SAMPLE_BROKERS[0]!}
      clusterName="prod-kyiv-01"
      clustersHref="#/clusters"
      brokersHref="#/brokers"
      tab="configuration"
      onTabChange={noop}
      logDirs={{ kind: "loading" }}
      configuration={{ kind: "forbidden", message: "You do not have permission to read broker configuration.", code: "FORBIDDEN" }}
    />
  ),
};

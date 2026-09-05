import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { StaleBadge } from "./StaleBadge.jsx";

/**
 * The badge that says the content under it is the last known value rather than the current one.
 *
 * The thing to check is that all three parts survive together: the registry's word for the state,
 * the sentence the operator can act on, and the code whoever they escalate to will search for.
 * Dropping the code turns a five-minute support conversation into an hour; dropping the sentence
 * leaves the person looking at the panel with nothing they can do.
 */
const meta: Meta<typeof StaleBadge> = {
  title: "Surfaces/StaleBadge",
  component: StaleBadge,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof StaleBadge>;

const ago = (ms: number) => new Date(Date.now() - ms);

export const Default: Story = {
  render: () => (
    <StaleBadge asOf={ago(4 * 60_000)} detail="the metrics service is not answering" code="UPSTREAM_UNAVAILABLE" />
  ),
};

/** Whole units, largest that fits — a badge that changes every second makes a live region shout. */
export const EveryAge: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", "align-items": "flex-start", gap: "12px" }}>
      <StaleBadge asOf={ago(2_000)} detail="revalidating" />
      <StaleBadge asOf={ago(45_000)} detail="the metrics service is not answering" />
      <StaleBadge asOf={ago(4 * 60_000)} detail="the metrics service is not answering" code="UPSTREAM_UNAVAILABLE" />
      <StaleBadge asOf={ago(3 * 3_600_000)} detail="the cluster has not been reachable since 09:00" code="CLUSTER_UNREACHABLE" />
      <StaleBadge asOf={ago(3 * 86_400_000)} state="Unavailable" detail="this cluster has been unreachable for three days" code="CLUSTER_UNREACHABLE" />
    </div>
  ),
};

/** The registry's own word, whatever it is, rendered verbatim. */
export const RegistryStates: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", "align-items": "flex-start", gap: "12px" }}>
      <StaleBadge state="Degraded" asOf={ago(90_000)} detail="one of three metrics endpoints answered" code="PARTIAL_UPSTREAM" />
      <StaleBadge state="Unavailable" asOf={ago(600_000)} detail="the metrics service is not answering" code="UPSTREAM_UNAVAILABLE" />
    </div>
  ),
};

/** The extreme: a sentence and a code longer than the panel. It wraps; nothing is cut. */
export const TheLongestOne: Story = {
  render: () => (
    <div style={{ width: "320px" }}>
      <StaleBadge
        state="Unavailable"
        asOf={ago(4 * 60_000)}
        detail="the consumer service did not answer within the gateway's upstream timeout, twice"
        code="UPSTREAM_UNAVAILABLE_AFTER_RETRY_BUDGET_EXHAUSTED"
      />
    </div>
  ),
};

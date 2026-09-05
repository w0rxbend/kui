import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { ConnectorCard } from "./ConnectorCard.jsx";

/**
 * Kafka Connect.
 *
 * **There is no connect service in KUI**, so these screens have no backend and no routes: they exist
 * here, against fixtures, so that the design is settled and reviewed before the service is written.
 * The point of building them now rather than later is that the rules the rest of the product follows
 * get built in rather than retrofitted — `NoTasks` and `AllTasksFailed` are two different pictures,
 * `StateNotReported` is not drawn as `RUNNING`, and `Forbidden` disables the actions with a reason
 * instead of hiding them.
 */
const meta: Meta<typeof ConnectorCard> = {
  title: "Connect/ConnectorCard",
  component: ConnectorCard,
  parameters: { layout: "padded" },
  decorators: [(Story) => <div style={{ "max-width": "22rem" }}>{Story() as never}</div>],
};

export default meta;
type Story = StoryObj<typeof meta>;

const base = {
  name: "orders-postgres-source",
  kind: "source · Debezium Postgres",
  throughput: 1204,
  topics: "orders.*",
  onPause: () => undefined,
  onRestart: () => undefined,
};

export const Running: Story = {
  args: { ...base, state: "RUNNING", tasks: ["running", "running", "running"] },
};

/** One task down. The bar is the point: three green and one red is visible without being read. */
export const OneTaskFailed: Story = {
  args: { ...base, state: "RUNNING", tasks: ["running", "running", "failed"] },
};

export const Failed: Story = {
  args: { ...base, state: "FAILED", tasks: ["failed", "failed", "failed"], throughput: 0 },
};

/** Paused deliberately. The action becomes Resume, because that is what it will do. */
export const Paused: Story = {
  args: { ...base, state: "PAUSED", tasks: ["paused", "paused"], throughput: 0 },
};

/**
 * No tasks at all: a single empty track.
 *
 * Deliberately not the same picture as `Failed`. "This connector has no tasks" and "every task has
 * failed" are different problems with different causes, and a bar that drew them alike would send
 * somebody to look at the wrong one.
 */
export const NoTasks: Story = {
  args: { ...base, state: "UNASSIGNED", tasks: [], throughput: undefined },
};

/**
 * The cluster did not report a state.
 *
 * Drawn as unknown, never as `RUNNING`. Guessing healthy is this product telling somebody their
 * pipeline is fine on no evidence, which is the worst thing a monitoring screen can do.
 */
export const StateNotReported: Story = {
  args: { ...base, state: "UNKNOWN", tasks: ["unknown", "unknown"], throughput: undefined },
};

/** Nothing measured the throughput. Not zero — a connector at 0 msg/s is idle, which is a fact. */
export const ThroughputNotMeasured: Story = {
  args: { ...base, state: "RUNNING", tasks: ["running"], throughput: undefined },
};

/** An operator who may look but not act. Disabled with the reason, never hidden. */
export const Forbidden: Story = {
  args: {
    ...base,
    state: "RUNNING",
    tasks: ["running", "running"],
    onPause: undefined,
    onRestart: undefined,
    actionsDisabledReason: "You do not have permission to operate connectors on this Connect cluster.",
  },
};

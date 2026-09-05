import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { GroupDetail } from "./GroupDetail.jsx";
import { ResetWizard } from "./ResetWizard.jsx";
import { NO_OP_PLAN, SAMPLE_GROUP_DETAIL, SAMPLE_PLAN } from "./fixtures.js";

/**
 * The group detail page and the offset-reset wizard.
 *
 * The wizard is the reason these stories exist. In the screen this replaces, pressing **Preview**
 * produced no feedback at all, and the only way to see that was to press it. Open `Wizard`, press
 * Preview, and watch the screen change; open `SlowPlan` and watch it change *immediately* even
 * though the answer takes two seconds; open `PlanRefused` and `Invalid` and see that a refusal is
 * always a sentence rather than a silence.
 */
const meta: Meta<typeof ResetWizard> = {
  title: "Screens/Consumer group detail",
  component: ResetWizard,
  parameters: { layout: "fullscreen" },
  decorators: [(Story) => <div style={{ padding: "24px" }}>{Story() as never}</div>],
};

export default meta;
type Story = StoryObj<typeof ResetWizard>;

const TOPICS = [
  { topic: "clickstream", partitions: [0, 1, 2, 3] },
  { topic: "sessions", partitions: [0] },
];

const after = <T,>(ms: number, value: T): Promise<T> => new Promise((resolve) => setTimeout(() => resolve(value), ms));
const at = (): string => "09:19";

/** The whole page: header, facts, members, assignments and the wizard at its foot. */
export const TheDetailPage: Story = {
  render: () => (
    <GroupDetail
      group={SAMPLE_GROUP_DETAIL}
      listHref="#/consumer-groups"
      onDelete={() => {}}
      reset={{
        plan: () => after(400, { ok: true, plan: SAMPLE_PLAN }),
        apply: () => after(400, { ok: true, receipt: SAMPLE_PLAN }),
        formatTime: at,
      }}
    />
  ),
};

/** A group KUI could only partly read: no state, no coordinator, no lag, no pace. Dashes, not zeroes. */
export const PartlyUnreadable: Story = {
  render: () => (
    <GroupDetail
      group={{ ...SAMPLE_GROUP_DETAIL, state: null, coordinator: null, totalLag: null, pace: null, excludedPartitions: 3 }}
      listHref="#/consumer-groups"
      reset={{ plan: () => after(0, { ok: false, problem: "The cluster is read-only." }), apply: () => after(0, { ok: false, problem: "The cluster is read-only." }) }}
    />
  ),
};

/** A stalled group: a commit rate of zero says `Stalled`, because `0` beside a large lag reads as nothing. */
export const Stalled: Story = {
  render: () => (
    <GroupDetail
      group={{ ...SAMPLE_GROUP_DETAIL, pace: 0, totalLag: 412_004 }}
      listHref="#/consumer-groups"
      reset={{ plan: () => after(0, { ok: true, plan: SAMPLE_PLAN }), apply: () => after(0, { ok: true, receipt: SAMPLE_PLAN }), formatTime: at }}
    />
  ),
};

/** The wizard on its own. Press **Preview the plan**. */
export const Wizard: Story = {
  render: () => (
    <ResetWizard topics={TOPICS} plan={() => after(300, { ok: true, plan: SAMPLE_PLAN })} apply={() => after(300, { ok: true, receipt: SAMPLE_PLAN })} formatTime={at} />
  ),
};

/**
 * A slow server. The planning step draws its own heading and a skeleton at the plan table's height,
 * so the screen answers the click within a frame however long the broker takes.
 */
export const SlowPlan: Story = {
  render: () => (
    <ResetWizard topics={TOPICS} plan={() => after(2_500, { ok: true, plan: SAMPLE_PLAN })} apply={() => after(1_500, { ok: true, receipt: SAMPLE_PLAN })} formatTime={at} />
  ),
};

/** A plan that changes nothing. It says so, and offers no Apply — there is nothing to confirm. */
export const NoOp: Story = {
  render: () => (
    <ResetWizard topics={TOPICS} plan={() => after(200, { ok: true, plan: NO_OP_PLAN })} apply={() => after(200, { ok: true, receipt: NO_OP_PLAN })} formatTime={at} />
  ),
};

/** The server refuses to plan. Back to the form, with the reason, never a spinner that never ends. */
export const PlanRefused: Story = {
  render: () => (
    <ResetWizard
      topics={TOPICS}
      plan={() => after(300, { ok: false, problem: "This cluster is read-only, so offsets cannot be reset from KUI." })}
      apply={() => after(0, { ok: false, problem: "This cluster is read-only." })}
    />
  ),
};

/** The token expired. The wizard stays on the plan the operator read rather than silently re-planning. */
export const TokenExpired: Story = {
  render: () => (
    <ResetWizard
      topics={TOPICS}
      plan={() => after(200, { ok: true, plan: SAMPLE_PLAN })}
      apply={() => after(400, { ok: false, problem: "That plan has expired. Ask for a new one — the offsets it named may no longer be the right ones." })}
      formatTime={at}
    />
  ),
};

/** A group holding no offsets. Pressing Preview says why rather than doing nothing. */
export const Invalid: Story = {
  render: () => <ResetWizard topics={[]} plan={() => after(0, { ok: true, plan: SAMPLE_PLAN })} apply={() => after(0, { ok: true, receipt: SAMPLE_PLAN })} />,
};

/** Refused before the way in, not at the last step. Three screens of work is not thrown away. */
export const NotPermitted: Story = {
  render: () => (
    <ResetWizard
      topics={TOPICS}
      permitted={false}
      refusal="You do not have permission to reset consumer offsets on this cluster."
      plan={() => after(0, { ok: true, plan: SAMPLE_PLAN })}
      apply={() => after(0, { ok: true, receipt: SAMPLE_PLAN })}
    />
  ),
};

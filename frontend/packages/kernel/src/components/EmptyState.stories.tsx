import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, within } from "storybook/test";
import { Button } from "./Button.jsx";
import { EmptyState, Missing, Skeleton } from "./EmptyState.jsx";

/**
 * The four kinds of nothing.
 *
 * These four stories are the point of this file. An empty region drawn as blank space is
 * ambiguous between them, and each one wants something different from the reader: wait, clear the
 * filter, retry, or ask an administrator. Put the four next to each other and the difference is
 * obvious; ship any of them as blank space and the operator cannot tell a healthy empty cluster
 * from a request that failed and said nothing.
 */
const meta = {
  title: "Kernel/EmptyState",
  component: EmptyState,
  parameters: { layout: "centered" },
  decorators: [
    (Story) => (
      <div
        style={{
          width: "520px",
          background: "var(--kui-color-surface-elevated)",
          "border-radius": "var(--kui-radius-lg)",
        }}
      >
        {Story()}
      </div>
    ),
  ],
} satisfies Meta<typeof EmptyState>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing yet, and that is normal. No colour, no alarm — a cluster nobody has produced to is not
 * a broken cluster. */
export const Empty: Story = {
  args: {
    kind: "empty",
    title: "No consumer groups yet.",
    description: "A group appears here the first time something consumes from this cluster.",
  },
};

/**
 * Filtered out. Different words *and* an action, because the reader caused this one and can undo
 * it. Substituting the `empty` copy here is the specific mistake §4.16 forbids: it tells somebody
 * their cluster has no topics when in fact their search box has three characters in it.
 */
export const FilteredOut: Story = {
  args: {
    kind: "filtered",
    title: "Nothing matched “payments”.",
    description: "Twelve groups exist on this cluster; none of their names contain that.",
    action: <Button variant="secondary">Clear filter</Button>,
  },
};

/**
 * The request did not come back. The code is on screen, in mono, because it means nothing to the
 * operator and everything to whoever they paste it to.
 */
export const Unavailable: Story = {
  args: {
    kind: "unavailable",
    title: "Consumer group data is unavailable.",
    description: "The consumer service is not responding.",
    code: "UPSTREAM_UNAVAILABLE",
    action: <Button variant="secondary">Retry</Button>,
  },
};

/**
 * Refused, not missing. The panel is never hidden: a reader who cannot see the panel at all
 * concludes the feature does not exist, opens a ticket, and finds out three days later that they
 * simply lacked a role.
 */
export const Forbidden: Story = {
  args: {
    kind: "forbidden",
    title: "You do not have permission to read consumer groups on this cluster.",
    description: "Ask a cluster administrator for the ConsumerGroup:Describe permission.",
    code: "FORBIDDEN",
  },
};

/** No description and no action — the minimum this component will draw. Still a sentence, still
 * announced, still not blank space. */
export const TitleOnly: Story = { args: { kind: "empty", title: "No topics yet." } };

/**
 * The longest strings that will ever appear here.
 *
 * A Kafka topic name may be 249 characters and a consumer group id 255, and both end up quoted in
 * these sentences. The block wraps and stays centred; if it ever stops doing so, this story is
 * where it will show.
 */
export const LongestPossibleText: Story = {
  args: {
    kind: "filtered",
    title:
      "Nothing matched “connect-s3-sink-eu-west-1-partitioned-by-hour-with-a-deliberately-and-unreasonably-long-name-that-somebody-really-did-configure-in-production”.",
    description:
      "Twelve thousand four hundred and eighteen groups exist on this cluster, and none of their names contain that string. Try a shorter fragment, or clear the filter and scroll.",
    code: "NO_MATCH_AFTER_FULL_SCAN_OF_12418_GROUPS",
    action: <Button variant="secondary">Clear filter</Button>,
  },
};

/** In a 320px column — the narrowest window the product supports. Nothing overflows; the sentence
 * simply takes more lines. */
export const NarrowWindow: Story = {
  args: Unavailable.args,
  decorators: [(Story) => <div style={{ width: "320px" }}>{Story()}</div>],
};

/**
 * The accessible half. The block is a live region, so a reader who filters a table down to nothing
 * is *told* so rather than left listening to silence — which is what §7.9 means by "every list
 * announces its emptiness in words".
 */
export const AnnouncesItself: Story = {
  args: Empty.args,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const region = canvas.getByRole("status");
    await expect(region).toHaveTextContent("No consumer groups yet.");
  },
};

/**
 * Loading, absent and present, side by side — the three renderings §4.0 says must never collapse
 * into one.
 *
 * A skeleton says "not yet". A dash says "there is nothing here". A number says the number. Draw
 * any two of them the same way and the reader draws a wrong conclusion about the cluster: a group
 * whose coordinator is still loading looks like a group that has no coordinator, which is an
 * alarming and untrue thing to say.
 */
export const PendingVersusAbsentVersusPresent: StoryObj = {
  render: () => (
    <table class="kui-table" style={{ margin: "var(--kui-space-4)" }}>
      <caption class="kui-visually-hidden">Three renderings of one column</caption>
      <thead>
        <tr>
          <th scope="col" class="kui-table__header-cell">
            Group
          </th>
          <th scope="col" class="kui-table__header-cell kui-table__header-cell--numeric">
            Lag
          </th>
        </tr>
      </thead>
      <tbody class="kui-table__body">
        <tr class="kui-table__row">
          <td class="kui-table__cell">still loading</td>
          <td class="kui-table__cell kui-table__cell--numeric">
            <Skeleton width="4ch" />
          </td>
        </tr>
        <tr class="kui-table__row">
          <td class="kui-table__cell">could not be read</td>
          <td class="kui-table__cell kui-table__cell--numeric">
            <Missing />
          </td>
        </tr>
        <tr class="kui-table__row">
          <td class="kui-table__cell">caught up</td>
          <td class="kui-table__cell kui-table__cell--numeric">0</td>
        </tr>
      </tbody>
    </table>
  ),
};

/** The placeholder on its own, at several sizes. It has to read as "a block where something will
 * be", not as a filled field. */
export const Skeletons: StoryObj = {
  render: () => (
    <div
      style={{
        display: "flex",
        "flex-direction": "column",
        gap: "var(--kui-space-3)",
        padding: "var(--kui-space-5)",
        width: "420px",
      }}
    >
      <Skeleton width="40%" height="2rem" />
      <Skeleton width="100%" />
      <Skeleton width="85%" />
      <Skeleton width="60%" />
    </div>
  ),
};

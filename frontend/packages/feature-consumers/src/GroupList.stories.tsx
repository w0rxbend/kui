import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { GroupList } from "./GroupList.jsx";
import { DEGRADED_GROUPS, SAMPLE_GROUPS } from "./fixtures.js";

/**
 * The consumer-group list, screenshot `04`.
 *
 * `TheScreenshot` is the one to put beside the PNG. Everything after it is a state nobody can
 * produce on a healthy cluster — an unreadable lag, a group with no coordinator, a name long enough
 * to break the layout, a filter that matched nothing, a service that is not answering — and those
 * are the states this project's defects have always been in.
 */
const meta: Meta<typeof GroupList> = {
  title: "Screens/Consumer groups",
  component: GroupList,
  parameters: { layout: "fullscreen" },
  decorators: [(Story) => <div style={{ padding: "24px" }}>{Story() as never}</div>],
};

export default meta;
type Story = StoryObj<typeof GroupList>;

const noop = (): void => {};
const href = (id: string): string => `#/consumer-groups/${id}`;

/** Row for row, the design. Six groups, one rebalancing, one lag in amber. */
export const TheScreenshot: Story = {
  render: () => <GroupList rows={SAMPLE_GROUPS} hrefFor={href} onOpen={noop} />,
};

/**
 * Every way a row can be incomplete, in one table.
 *
 * Look for four things: the em dash where a lag could not be computed (never a `0`), the em dash
 * where a member count could not be read, the `partial` chip beside a name, and the longest real
 * Kafka group id truncating rather than pushing the table sideways.
 */
export const EverythingMissing: Story = {
  render: () => <GroupList rows={DEGRADED_GROUPS} coordinatorsMissing={1} hrefFor={href} onOpen={noop} />,
};

/** Six skeleton rows at the real row height, so nothing resizes when the data lands. */
export const Loading: Story = {
  render: () => <GroupList rows={[]} loading hrefFor={href} />,
};

/** Nothing has ever consumed from this cluster. Gently warm, because nothing is wrong. */
export const Empty: Story = {
  render: () => <GroupList rows={[]} hrefFor={href} />,
};

/** A filter matched nothing. A different sentence, and a way out. Never substituted for `Empty`. */
export const FilteredOut: Story = {
  render: () => <GroupList rows={[]} hrefFor={href} failure={{ kind: "filtered", term: "payments", onClear: noop }} />,
};

/** The service is not answering. The frame stays, the code stays, and there is a retry. Plain voice. */
export const Unavailable: Story = {
  render: () => (
    <GroupList
      rows={[]}
      hrefFor={href}
      failure={{ kind: "unavailable", message: "Consumer group data is unavailable.", code: "UPSTREAM_UNAVAILABLE", onRetry: noop }}
    />
  ),
};

/** Refused rather than broken. Same shape, lock glyph, and the panel is never hidden. */
export const Forbidden: Story = {
  render: () => (
    <GroupList
      rows={[]}
      hrefFor={href}
      failure={{ kind: "forbidden", message: "You do not have permission to read consumer groups on this cluster.", code: "FORBIDDEN" }}
    />
  ),
};

/**
 * The narrow window. COORDINATOR goes first and TOPICS second; GROUP ID, STATE and LAG never go.
 * Resize the preview below 900px to see it — the columns are dropped, not hidden.
 */
export const Narrow: Story = {
  parameters: { viewport: { defaultViewport: "mobile2" } },
  render: () => (
    <div style={{ "max-width": "560px" }}>
      <GroupList rows={SAMPLE_GROUPS} hrefFor={href} onOpen={noop} />
    </div>
  ),
};

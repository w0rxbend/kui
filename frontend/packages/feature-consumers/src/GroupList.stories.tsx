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

/**
 * Row for row, the design. Six groups drawn, fourteen on the cluster, one lag in amber.
 *
 * `totalItems={14}` is the capture's own figure: `SCREENS-V4.md` §4.12 prints `14 groups` over six
 * rows, and that disagreement is the point of the sentence. Without it this story read "6 groups
 * on this page, of an unstated total" — the refusal a server that carried no total earns, drawn
 * here as the screen's ordinary voice, in the one story meant to be held beside the PNG.
 *
 * The aside is *not* the design's "One is rebalancing again". It cannot be: `clickstream-etl` is
 * 3,861 records behind, and `healthOf`'s ordering rule says a page that is both lagging and
 * rebalancing is described as lagging, because the lag is the one an operator has to act on. The
 * design's capture has the same row in amber and the cheerful line above it. `TotalUnstated` is
 * where the unstated-total sentence belongs, and it is the only story that draws it.
 */
export const TheScreenshot: Story = {
  render: () => <GroupList rows={SAMPLE_GROUPS} totalItems={14} hrefFor={href} onOpen={noop} />,
};

/**
 * One page of a cluster with more groups than fit on it.
 *
 * The sentence over the table reads `90 groups` and the table draws six. That is the state
 * screenshot `04` is in — `14 groups` over six rows — and it is what the screen got wrong until
 * this wave: the voice counted the array, so it agreed with the table and lied about the cluster.
 * The range line under the table is the other half of the answer: which six of the ninety.
 */
export const OnePageOfMany: Story = {
  render: () => (
    <GroupList
      rows={SAMPLE_GROUPS}
      totalItems={90}
      page={3}
      pageSize={6}
      onPage={noop}
      onPageSize={noop}
      hrefFor={href}
      onOpen={noop}
    />
  ),
};

/**
 * A server that did not say how many groups there are.
 *
 * The sentence says so rather than publishing the page's own length as the cluster's figure, and
 * the paginator drops its numbered buttons and its `last` step — a last page cannot be computed and
 * a guessed one sends the operator to an address that does not exist.
 */
export const TotalUnstated: Story = {
  render: () => (
    <GroupList
      rows={SAMPLE_GROUPS}
      totalItems={null}
      page={1}
      pageSize={6}
      onPage={noop}
      hrefFor={href}
      onOpen={noop}
    />
  ),
};

/**
 * Every way a row can be incomplete, in one table.
 *
 * Look for four things: the em dash where a lag could not be computed (never a `0`), the em dash
 * where a member count could not be read, the `partial` chip beside a name, and the longest real
 * Kafka group id truncating rather than pushing the table sideways.
 */
export const EverythingMissing: Story = {
  render: () => (
    <GroupList
      rows={DEGRADED_GROUPS}
      totalItems={6}
      coordinatorsMissing={1}
      hrefFor={href}
      onOpen={noop}
    />
  ),
};

/**
 * Six skeleton rows at the real row height, so nothing resizes when the data lands.
 *
 * The sentence over them is the one this state is actually in: the question is in flight. It used
 * to read "0 groups on this page, of an unstated total", which is a claim about a cluster nobody
 * had asked — and it was not only a story. `ConsumersRoute` starts its total at `null`, so that
 * was the first paint of every visit to this screen.
 */
export const Loading: Story = {
  render: () => <GroupList rows={[]} loading hrefFor={href} />,
};

/** Nothing has ever consumed from this cluster. A counted zero, so it is stated as one. */
export const Empty: Story = {
  render: () => <GroupList rows={[]} totalItems={0} hrefFor={href} />,
};

/** A filter matched nothing. A different sentence, and a way out. Never substituted for `Empty`. */
export const FilteredOut: Story = {
  render: () => (
    <GroupList
      rows={[]}
      totalItems={0}
      hrefFor={href}
      failure={{ kind: "filtered", term: "payments", onClear: noop }}
    />
  ),
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
      <GroupList rows={SAMPLE_GROUPS} totalItems={14} hrefFor={href} onOpen={noop} />
    </div>
  ),
};

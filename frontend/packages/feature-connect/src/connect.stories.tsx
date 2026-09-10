import type { Meta, StoryObj } from "storybook-solidjs-vite";
import type { Fetched } from "@kui/kernel";

import { ConnectorList } from "./ConnectorList.jsx";
import {
  decodeConnectorListing,
  Unreadable,
  type Connector,
  type ConnectorListing,
} from "./wire.js";
import { operateAction } from "./model.js";
import responseDocument from "./documents/connectors-response.json" with { type: "json" };
import partialDocument from "./documents/connectors-partial.json" with { type: "json" };
import emptyDocument from "./documents/connectors-empty.json" with { type: "json" };

/**
 * The Kafka Connect screen (`SCREENS-V4.md` §3.14, §4.14), in every state it has.
 *
 * `TheScreenshot` is the one to hold beside `M19`. Everything after it is a state a working Connect
 * cluster cannot be put into on purpose — a cluster mid-rebalance, a cluster that answered and
 * named nothing, a connector it would not describe, a document this build cannot read, a principal
 * who may look and may not touch — and those are the states this project's defects have always
 * lived in.
 *
 * Every story renders one of the **service's own** documents, copied from
 * `services/connect/contract/test/resources/golden/` and decoded by the same functions the screen
 * uses. A story built from a literal is a drawing of what its author believed the server sends,
 * which is how a screen comes to be reviewed, approved and wrong — this package's first draft read
 * `data.items` where the service renders `data.workers[].connectors.data.items`, and a story
 * written from a literal would have looked perfect.
 *
 * Two of these have no drawing in any capture and are the two the packet was asked for by name:
 * {@link WorkerNamedNoConnectors} and {@link ConnectorNotDescribed}. Neither renders a zero, and
 * that is the thing to check by eye as well as in the cases. And **no card anywhere draws a
 * throughput**: the Connect REST API measures none (ADR-054 §6), so §3.14's `1,204 msg/s` is a
 * figure this product cannot honestly print and every card says so in words instead.
 */
const meta: Meta<typeof ConnectorList> = {
  title: "Screens/Connect",
  component: ConnectorList,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => <div style={{ padding: "24px", "max-width": "1120px" }}>{Story() as never}</div>,
  ],
};

export default meta;
type Story = StoryObj<typeof ConnectorList>;

const noop = (): void => {};
const permitted = (): undefined => undefined;

/** A document, decoded exactly as `fetchConnectors` decodes it, as a screen state. */
function ready(document: unknown): Fetched<ConnectorListing> {
  const payload = (document as { connectors: { data: unknown } }).connectors.data;
  const listing = decodeConnectorListing(payload);
  if (listing === Unreadable) {
    throw new Error("a story fixture that does not decode — fix the fixture, not this line");
  }
  return { kind: "ready", value: listing };
}

/**
 * Three connectors on one Connect cluster, a second cluster rebalancing.
 *
 * The failed card is the one §7.7 is an open finding about. The design draws a state and a task
 * count and nothing else; the reason under the card is the worker's own line, verbatim, which is
 * what the notification for the same event has always carried. Note which card it is on:
 * `elastic-sink` reads **running** and is failed, because one of its tasks is.
 */
export const TheScreenshot: Story = {
  render: () => (
    <ConnectorList state={ready(responseDocument)} refusalFor={permitted} onCommand={noop} />
  ),
};

/**
 * A Connect cluster that answered and named no connectors.
 *
 * Not a zero anywhere on it. "0 connectors" is a count of nothing; *the workers answered and named
 * no connectors* is a fact about the workers, and it is the sentence that tells an operator KUI
 * reached them and there is genuinely nothing deployed.
 */
export const WorkerNamedNoConnectors: Story = {
  render: () => (
    <ConnectorList state={ready(emptyDocument)} refusalFor={permitted} onCommand={noop} />
  ),
};

/**
 * A connector the Connect cluster named and would not describe.
 *
 * The other state nobody draws. No task bar, because a bar with no segments is a picture of "no
 * tasks" and this connector may be running twenty; no state pill claiming a state, because nobody
 * reported one. The row exists at all because a connector missing from a list is indistinguishable
 * from one that was deleted — `ConnectorsDto.unreadable` is on the wire for exactly this.
 */
export const ConnectorNotDescribed: Story = {
  render: () => (
    <ConnectorList state={ready(partialDocument)} refusalFor={permitted} onCommand={noop} />
  ),
};

/**
 * A principal who may see every connector and may operate only the ones on `payments`.
 *
 * Every refused control is disabled **and says why, in words**, naming the Connect cluster —
 * because that is the grant to go and ask for. The tooltip alone is reachable by hover and by focus
 * and by nothing else, and on a page of cards with one disabled button that means hunting for which
 * one.
 */
export const PermittedOnOneClusterOnly: Story = {
  render: () => (
    <ConnectorList
      state={ready(responseDocument)}
      refusalFor={(connector: Connector) =>
        connector.connect === "payments"
          ? undefined
          : `You do not have permission to ${operateAction(connector)}.`
      }
      onCommand={noop}
    />
  ),
};

/** No Connect worker configured for this cluster. Nothing is broken, so no retry and no red. */
export const NoWorkerConfigured: Story = {
  render: () => <ConnectorList state={{ kind: "not-configured" }} refusalFor={permitted} />,
};

/** The principal may not read the connectors. No retry: a refusal does not change on re-asking. */
export const Forbidden: Story = {
  render: () => <ConnectorList state={{ kind: "forbidden" }} refusalFor={permitted} />,
};

/**
 * A document this build could not read.
 *
 * The state `fetchConnectors` produces rather than answering an empty list. The distinction is the
 * whole of wave 5's most expensive defect: an empty list is a sentence about the cluster, and an
 * operator acts on it.
 */
export const DocumentNotUnderstood: Story = {
  render: () => (
    <ConnectorList
      state={{
        kind: "failed",
        message:
          "KUI read the connectors section and did not recognise what was in it, so it cannot " +
          "say " +
          "which Connect workers this cluster has.",
        code: "UNREADABLE_BODY",
      }}
      refusalFor={permitted}
      onRetry={noop}
    />
  ),
};

/** The gateway did not answer at all. The code is shown verbatim, for whoever is escalated to. */
export const ReadFailed: Story = {
  render: () => (
    <ConnectorList
      state={{
        kind: "failed",
        message: "The connect service did not answer within 10 seconds.",
        code: "KUI-UPSTREAM-UNAVAILABLE",
      }}
      refusalFor={permitted}
      onRetry={noop}
    />
  ),
};

/** The last answer, with the reason it is old above it. Real data with a caveat, never hidden. */
export const Stale: Story = {
  render: () => {
    const fresh = ready(responseDocument);
    if (fresh.kind !== "ready") throw new Error("unreachable: the fixture decodes");
    return (
      <ConnectorList
        state={{
          kind: "stale",
          value: fresh.value,
          reason: "The connect service last answered 4 minutes ago.",
        }}
        refusalFor={permitted}
        onCommand={noop}
      />
    );
  },
};

/** A command in flight against one connector: its two controls are busy and cannot fire twice. */
export const CommandInFlight: Story = {
  render: () => (
    <ConnectorList
      state={ready(responseDocument)}
      refusalFor={permitted}
      onCommand={noop}
      pending={{ subject: "payments/orders-source", command: "restart" }}
    />
  ),
};

/** A command the cluster refused, in the words it used, beside the card it refused. */
export const CommandRefused: Story = {
  render: () => (
    <ConnectorList
      state={ready(responseDocument)}
      refusalFor={permitted}
      onCommand={noop}
      failure={{
        subject: "payments/orders-source",
        message: "The Kafka Connect cluster is rebalancing and cannot answer yet.",
      }}
    />
  ),
};

/** Before anything has answered. A named sentence rather than a blank region. */
export const Loading: Story = {
  render: () => <ConnectorList state={{ kind: "loading" }} refusalFor={permitted} />,
};

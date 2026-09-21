import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Monogram } from "./Monogram.jsx";

/**
 * The initials tile for an identifier.
 *
 * Two stories carry the whole point. `Deterministic` draws the same six ids three times over: a
 * tile whose colour depended on render order, or on which replica served the page, would show
 * three different rows here and the operator would never learn that plum means `checkout-svc`.
 * `NotAnAvatar` puts it beside the rule it is not — `Avatar` reads a person's name and takes the
 * last word, which on `orders.payments.v2` abbreviates by the version suffix.
 */
const meta = {
  title: "Primitives/Monogram",
  component: Monogram,
  args: { id: "checkout-svc" },
} satisfies Meta<typeof Monogram>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The six client ids the design's Top producers card lists. */
const CLIENT_IDS = [
  "checkout-svc",
  "orders.payments.v2",
  "analytics-ingest",
  "inventory-sync",
  "notifications",
  "users.profile.reader",
];

const Row = (props: { readonly ids: readonly string[] }) => (
  <div style={{ display: "flex", gap: "12px", "align-items": "center", "flex-wrap": "wrap" }}>
    {props.ids.map((id) => (
      <Monogram id={id} />
    )) as never}
  </div>
);

export const Default: Story = {};

/**
 * The design's row, as it is actually drawn: a tile, then the id in full beside it. This is why
 * the tile is `aria-hidden` by default — announcing it would read the id twice, once spelled out
 * two letters at a time.
 */
export const InARow: Story = {
  render: () => (
    <ul style={{ display: "grid", gap: "8px", "list-style": "none", margin: "0", padding: "0" }}>
      {CLIENT_IDS.map((id) => (
        <li style={{ display: "flex", gap: "12px", "align-items": "center" }}>
          <Monogram id={id} />
          <span style={{ "font-family": "var(--kui-font-family-mono)" }}>{id}</span>
        </li>
      )) as never}
    </ul>
  ),
};

/**
 * The same ids, three times. Every column must be identical — same letters, same hue. The colour
 * is a hash of the string and nothing else: not a counter, not the order the rows arrived in, not
 * a map built at render time. An operator learns a colour, and that only works if it holds across
 * a reload, across a re-sort, and across two replicas serving two tabs.
 */
export const Deterministic: Story = {
  render: () => (
    <div style={{ display: "grid", gap: "12px" }}>
      <Row ids={CLIENT_IDS} />
      <Row ids={CLIENT_IDS} />
      <Row ids={CLIENT_IDS} />
    </div>
  ),
};

/**
 * All five hues of the ramp, with the ink each one carries.
 *
 * The fifth is the one worth looking at. Its fill is dark enough that near-black initials fall
 * below the body contrast threshold, so it takes white letters while the other four take
 * near-black — the ink is chosen per entry, not once for the ramp. Nothing here changes between
 * light and dark: the tile is a fixed decorative colour, so the letters on it have to be fixed
 * too.
 */
export const TheWholeRamp: Story = {
  render: () => (
    // These five ids hash to entries 1..5 in order — checked, not guessed. The story is meant to
    // be a picture of the ramp, and six ids that happened to land on three of the five hues would
    // be a picture of the hash instead.
    <Row
      ids={[
        "kafka",
        "analytics-ingest",
        "orders.reconciliation.v2",
        "checkout-svc",
        "orders.payments.v2",
      ]}
    />
  ),
};

/**
 * Where `Avatar`'s rule would go wrong. `Avatar.initialsOf` takes the first letter of the first
 * word and of the *last*, which is right for "Olena Petrenko" and wrong for these: four `orders.*`
 * producers would all be abbreviated by their version suffix and come out looking alike.
 */
export const NotAnAvatar: Story = {
  render: () => (
    <Row
      ids={[
        "orders.payments.v2",
        "orders.refunds.v2",
        "orders.shipping.v2",
        "orders.reconciliation.v2",
      ]}
    />
  ),
};

/** The shapes an identifier actually arrives in, including the ones with no separator at all. */
export const IdentifierShapes: Story = {
  render: () => (
    <Row
      ids={[
        "checkout-svc",
        "orders.payments.reconciliation.v2.eu-central-1",
        "consumer_group_7",
        "kafka",
        "9",
        "connect-worker@eu-central-1",
      ]}
    />
  ),
};

/**
 * An identifier with nothing alphanumeric in it, which is a bug in whatever produced it. It draws
 * a question mark rather than a blank square: an empty tile reads as an image that failed to load,
 * and the operator goes looking for a network fault that is not there.
 */
export const NothingToAbbreviate: Story = { args: { id: "---" } };

/** The small size, for a tile that sits inside a table row rather than beside a card's heading. */
export const Small: Story = { args: { id: "checkout-svc", size: "sm" } };

/**
 * Announced, for the one case where the id is not written beside it. Tab and listen: the tile is
 * named by the whole identifier, never by its two letters — "C S" read aloud is not a client.
 */
export const Announced: Story = {
  args: { id: "checkout-svc", label: "Client checkout-svc" },
};

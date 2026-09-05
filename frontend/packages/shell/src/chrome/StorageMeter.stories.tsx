import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { StorageMeter, type BrokerStorage } from "./StorageMeter.jsx";

/**
 * The card at the drawer's foot.
 *
 * `OneHotBroker` is why this component exists at all. A cluster at 67% overall with one broker at
 * 83% is a cluster with a problem, and a single averaged bar is a reassuring picture of it. Compare
 * that story with `Balanced`: the overall figure is similar and the pictures are not.
 *
 * `Unknown` is the other one to look at. An empty track reads as 0% — "your disks are empty" —
 * which is both wrong and the most comforting available misreading.
 */
const meta: Meta<typeof StorageMeter> = {
  title: "Chrome/StorageMeter",
  component: StorageMeter,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof StorageMeter>;

const GB = 1024 ** 3;

/** The drawer's width, because that is the only width this component is ever drawn at. */
const InDrawer = (props: { readonly children: unknown }) => (
  <div style={{ width: "182px", background: "var(--kui-color-surface-raised)", padding: "4px 0" }}>
    {props.children as never}
  </div>
);

/** The design's own numbers: three brokers, 842 GB of 1.25 TB, and broker-3 running hot. */
export const OneHotBroker: Story = {
  render: () => {
    const brokers: readonly BrokerStorage[] = [
      { id: "broker-1.kyiv", usedBytes: 254 * GB, totalBytes: 416 * GB },
      { id: "broker-2.kyiv", usedBytes: 241 * GB, totalBytes: 416 * GB },
      { id: "broker-3.kyiv", usedBytes: 347 * GB, totalBytes: 418 * GB },
    ];
    return (
      <InDrawer>
        <StorageMeter brokers={brokers} />
      </InDrawer>
    );
  },
};

/**
 * The same total, spread evenly.
 *
 * The caption stops naming a broker, because on a balanced cluster the name is noise — and noise
 * trains the reader to stop looking at the line that will one day matter.
 */
export const Balanced: Story = {
  render: () => {
    const brokers: readonly BrokerStorage[] = [
      { id: "broker-1.kyiv", usedBytes: 281 * GB, totalBytes: 416 * GB },
      { id: "broker-2.kyiv", usedBytes: 280 * GB, totalBytes: 416 * GB },
      { id: "broker-3.kyiv", usedBytes: 281 * GB, totalBytes: 418 * GB },
    ];
    return (
      <InDrawer>
        <StorageMeter brokers={brokers} />
      </InDrawer>
    );
  },
};

/** A broker past the danger threshold. One red segment among green, and the caption names it. */
export const OneBrokerCritical: Story = {
  render: () => {
    const brokers: readonly BrokerStorage[] = [
      { id: "broker-1.kyiv", usedBytes: 120 * GB, totalBytes: 416 * GB },
      { id: "broker-2.kyiv", usedBytes: 130 * GB, totalBytes: 416 * GB },
      { id: "broker-3.kyiv", usedBytes: 400 * GB, totalBytes: 418 * GB },
    ];
    return (
      <InDrawer>
        <StorageMeter brokers={brokers} />
      </InDrawer>
    );
  },
};

/**
 * Storage could not be read.
 *
 * A neutral track, an em dash, and a sentence. Never an empty bar, which reads as 0%.
 */
export const Unknown: Story = {
  render: () => (
    <InDrawer>
      <StorageMeter brokers={[]} />
    </InDrawer>
  ),
};

/**
 * One broker's capacity is unknown — an unconfigured log directory reports zero.
 *
 * Its segment is neutral and it is excluded from the totals, rather than dividing by zero and
 * painting the whole bar red. The other two still report honestly.
 */
export const OneBrokerNotReporting: Story = {
  render: () => {
    const brokers: readonly BrokerStorage[] = [
      { id: "broker-1.kyiv", usedBytes: 254 * GB, totalBytes: 416 * GB },
      { id: "broker-2.kyiv", usedBytes: 0, totalBytes: 0 },
      { id: "broker-3.kyiv", usedBytes: 347 * GB, totalBytes: 418 * GB },
    ];
    return (
      <InDrawer>
        <StorageMeter brokers={brokers} />
      </InDrawer>
    );
  },
};

/** A brand new cluster: real capacity, almost nothing on it. Not to be confused with `Unknown`. */
export const NearlyEmpty: Story = {
  render: () => (
    <InDrawer>
      <StorageMeter
        brokers={[
          { id: "broker-1", usedBytes: 2 * GB, totalBytes: 416 * GB },
          { id: "broker-2", usedBytes: 2 * GB, totalBytes: 416 * GB },
        ]}
      />
    </InDrawer>
  ),
};

/**
 * The extremes: one broker, and thirty-six of them with long names.
 *
 * Thirty-six segments in a 182px drawer is about 3px each. If the gaps eat them, the bar has to
 * lose its gaps above some count rather than becoming a dashed line.
 */
export const TheExtremes: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", gap: "12px" }}>
      <InDrawer>
        {/* Two on one page is not a shape the product has — there is one drawer — but the story
            draws two, and two landmarks with one name are two indistinguishable entries in a
            screen reader's landmark list. Hence the names. */}
        <StorageMeter
          label="Cluster storage — one broker"
          brokers={[{ id: "the-only-broker", usedBytes: 900 * GB, totalBytes: 1000 * GB }]}
        />
      </InDrawer>
      <InDrawer>
        <StorageMeter
          label="Cluster storage — thirty-six brokers"
          brokers={Array.from({ length: 36 }, (_, index) => ({
            id: `broker-${index}.eu-central-1.payments-platform`,
            usedBytes: (100 + index * 9) * GB,
            totalBytes: 416 * GB,
          }))}
        />
      </InDrawer>
    </div>
  ),
};

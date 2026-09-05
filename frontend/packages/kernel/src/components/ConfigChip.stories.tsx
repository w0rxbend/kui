import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { For } from "solid-js";
import { ConfigChip, ConfigChips } from "./ConfigChip.jsx";

/**
 * A broker's configuration, drawn as chips.
 *
 * `TwoHundredSettings` is the story that decides whether this shape was the right call. A broker
 * really does have around two hundred settings; if the wrapping row becomes unreadable at that
 * count, the answer is a search field above it rather than a table, and it is better to find that
 * out here than on a live broker.
 */
const meta: Meta<typeof ConfigChip> = {
  title: "Data/ConfigChip",
  component: ConfigChip,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof ConfigChip>;

/** The eight the design puts on a broker card, exactly as it draws them. */
export const TheBrokerCardRow: Story = {
  render: () => (
    <ConfigChips label="broker-1.kyiv configuration">
      <ConfigChip name="num.network.threads" value="8" />
      <ConfigChip name="num.io.threads" value="16" />
      <ConfigChip name="log.retention.hours" value="168" />
      <ConfigChip name="log.segment.bytes" value="1 GB" />
      <ConfigChip name="num.partitions" value="12" />
      <ConfigChip name="default.replication.factor" value="3" />
      <ConfigChip name="min.insync.replicas" value="2" />
      <ConfigChip name="compression.type" value="producer" />
    </ConfigChips>
  ),
};

/**
 * The three states of a value.
 *
 * The middle one is the one to look at. `—` says the setting exists and has no value, which is a
 * real and different thing from `none` (a value that happens to be the word "none") and from a
 * setting we could not read at all — the last of which should not be drawn as a chip, because the
 * card around it carries that state for the whole set.
 */
export const ValuePresentEmptyAndLiteralNone: Story = {
  render: () => (
    <ConfigChips label="Value states">
      <ConfigChip name="compression.type" value="producer" />
      <ConfigChip name="compression.type" value={undefined} />
      <ConfigChip name="compression.type" value="none" />
    </ConfigChips>
  ),
};

/**
 * An overridden setting, beside an inherited one.
 *
 * The dot is the whole point of the variant: an override is the single thing an operator scans
 * this list for, and finding it by reading two hundred chips is not scanning. The title says it in
 * words, so the dot is never the only signal.
 */
export const Overridden: Story = {
  render: () => (
    <ConfigChips label="Overrides">
      <ConfigChip name="log.retention.hours" value="168" />
      <ConfigChip
        name="log.retention.hours"
        value="24"
        overridden
        description="How long a segment is kept before it is eligible for deletion"
      />
    </ConfigChips>
  ),
};

/** With descriptions. Hover any of them: Kafka's names are terse to the point of being cryptic. */
export const WithDescriptions: Story = {
  render: () => (
    <ConfigChips label="Documented settings">
      <ConfigChip
        name="min.insync.replicas"
        value="2"
        description="How many replicas must acknowledge a write before it is considered committed"
      />
      <ConfigChip
        name="unclean.leader.election.enable"
        value="false"
        description="Whether an out-of-sync replica may become leader, trading durability for availability"
      />
    </ConfigChips>
  ),
};

/**
 * Two hundred settings, which is what a real broker has.
 *
 * If this is unreadable, the shape is wrong and needs a filter above it. That judgement is the
 * reason this story exists; it cannot be made from a card with eight chips on it.
 */
export const TwoHundredSettings: Story = {
  parameters: { layout: "fullscreen" },
  render: () => {
    const settings = Array.from({ length: 200 }, (_, index) => ({
      name: `kafka.setting.number.${index.toString().padStart(3, "0")}`,
      value: index % 7 === 0 ? undefined : `${index * 13}`,
      overridden: index % 23 === 0,
    }));
    return (
      <div style={{ padding: "20px" }}>
        <ConfigChips label="Every setting">
          <For each={settings}>
            {(setting) => <ConfigChip name={setting.name} value={setting.value} overridden={setting.overridden} />}
          </For>
        </ConfigChips>
      </div>
    );
  },
};

/**
 * The extremes: a name longer than the card, and a value longer than the name.
 *
 * The name truncates and the value does not, which is the opposite of `HeaderChip` and deliberate:
 * a chip whose value is cut off still tells you which setting it is, and one whose name is cut off
 * tells you nothing.
 */
export const TheExtremes: Story = {
  render: () => (
    <div style={{ width: "320px" }}>
      <ConfigChips label="Extremes">
        <ConfigChip
          name="confluent.tier.local.hotset.bytes.per.broker.override.for.this.topic"
          value="107374182400"
        />
        <ConfigChip name="a" value="1" />
        <ConfigChip
          name="ssl.cipher.suites"
          value="TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"
        />
      </ConfigChips>
    </div>
  ),
};

/**
 * Stories for the range selector.
 *
 * The states worth looking at are not the happy one. A segmented control fails by being correct
 * and invisible: the radios work, the arrow keys work, and nothing on screen says which segment is
 * selected. So there is a story for the selected segment in each position, one for the disabled
 * range that must stay visible with its reason, and one for the whole control disabled.
 */
import { createSignal } from "solid-js";
import type { JSX } from "@solidjs/web";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { RangeSelector, type RangeOption } from "./RangeSelector.jsx";

const OPTIONS: readonly RangeOption[] = [
  { value: "24h", label: "24h" },
  { value: "7d", label: "7d" },
  { value: "30d", label: "30d" },
];

/** A live wrapper, because a control you cannot click is a control you have not looked at. */
function Live(props: { readonly options: readonly RangeOption[]; readonly initial: string; readonly disabled?: boolean }): JSX.Element {
  const [value, setValue] = createSignal(props.initial);
  return (
    <RangeSelector
      label="Throughput range"
      options={props.options}
      value={value()}
      onChange={setValue}
      disabled={props.disabled === true}
    />
  );
}

const meta: Meta<typeof RangeSelector> = {
  title: "Charts/RangeSelector",
  component: RangeSelector,
};
export default meta;

type Story = StoryObj<typeof RangeSelector>;

/** As the design draws it: three ranges, the first selected. */
export const Default: Story = {
  render: () => <Live options={OPTIONS} initial="24h" />,
};

/** The selection in the middle, where the segment has a rule on both sides. */
export const MiddleSelected: Story = {
  render: () => <Live options={OPTIONS} initial="7d" />,
};

/** The last segment, where the fill has to meet the container's rounded right end. */
export const LastSelected: Story = {
  render: () => <Live options={OPTIONS} initial="30d" />,
};

/**
 * A range the backend keeps no history for. It stays in the control and says why — omitting it
 * would make the retention limit invisible, and the operator would wonder why the product only
 * offers two ranges (SPEC §4.24).
 */
export const OneRangeUnavailable: Story = {
  render: () => (
    <Live
      initial="24h"
      options={[
        { value: "24h", label: "24h" },
        { value: "7d", label: "7d" },
        { value: "30d", label: "30d", disabled: true, disabledReason: "Metrics are retained for 7 days." },
      ]}
    />
  ),
};

/** The whole control disabled — the metrics service is not answering at all. */
export const Disabled: Story = {
  render: () => <Live options={OPTIONS} initial="24h" disabled />,
};

/** Two options, which is the narrowest the control ever gets. */
export const TwoOptions: Story = {
  render: () => (
    <Live
      initial="1h"
      options={[
        { value: "1h", label: "1h" },
        { value: "24h", label: "24h" },
      ]}
    />
  ),
};

/**
 * The extreme case: six ranges with the longest labels anyone would write. The control must not
 * wrap into two rows and must not push its card wider than the grid column.
 */
export const ManyLongOptions: Story = {
  render: () => (
    <div style={{ width: "320px", border: "1px dashed var(--kui-color-border)", padding: "8px" }}>
      <Live
        initial="90d"
        options={[
          { value: "15m", label: "15 min" },
          { value: "1h", label: "1 hour" },
          { value: "24h", label: "24 hours" },
          { value: "7d", label: "7 days" },
          { value: "30d", label: "30 days" },
          { value: "90d", label: "90 days" },
        ]}
      />
    </div>
  ),
};

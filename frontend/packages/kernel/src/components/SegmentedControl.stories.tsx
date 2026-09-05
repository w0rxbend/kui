import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import { SegmentedControl } from "./SegmentedControl.jsx";

/**
 * Every place the design uses a segmented control, in one page.
 *
 * Seeing them together is the point. The five uses have two, three and four segments, with and
 * without glyphs, at two sizes, and they have to look like one control rather than five — which is
 * only checkable side by side.
 *
 * The story to read with the keyboard is `Interactive`: Tab should enter the group once and stop,
 * and the arrow keys should move *and select*. If Tab walks through every segment, the radios have
 * been replaced by buttons and the group has lost its behaviour.
 */
const meta: Meta<typeof SegmentedControl> = {
  title: "Controls/SegmentedControl",
  component: SegmentedControl as never,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj;

const Row = (props: { readonly children: unknown }) => (
  <div style={{ display: "flex", "flex-direction": "column", gap: "20px", "align-items": "flex-start" }}>
    {props.children as never}
  </div>
);

/** The five uses the design has, in the order they appear in `SCREENS.md`. */
export const EveryUse: Story = {
  render: () => {
    const [view, setView] = createSignal<"table" | "cards">("table");
    const [format, setFormat] = createSignal<"json" | "table">("json");
    const [window, setWindow] = createSignal<"5m" | "15m" | "1h" | "24h">("15m");
    const [version, setVersion] = createSignal<"v1" | "v2" | "v3">("v3");
    const [compat, setCompat] = createSignal<"BACKWARD" | "FORWARD" | "FULL" | "NONE">("BACKWARD");

    return (
      <Row>
        <SegmentedControl
          label="View"
          value={view()}
          onChange={setView}
          segments={[
            { value: "table", label: "Table", icon: "table" },
            { value: "cards", label: "Cards", icon: "cards" },
          ]}
        />
        <SegmentedControl
          label="Record format"
          value={format()}
          onChange={setFormat}
          size="sm"
          segments={[
            { value: "json", label: "JSON", icon: "braces" },
            { value: "table", label: "Table", icon: "table" },
          ]}
        />
        <SegmentedControl
          label="Time window"
          value={window()}
          onChange={setWindow}
          size="sm"
          segments={[
            { value: "5m", label: "5m" },
            { value: "15m", label: "15m" },
            { value: "1h", label: "1h" },
            { value: "24h", label: "24h" },
          ]}
        />
        <SegmentedControl
          label="Schema version"
          value={version()}
          onChange={setVersion}
          size="sm"
          segments={[
            { value: "v1", label: "v1" },
            { value: "v2", label: "v2" },
            { value: "v3", label: "v3" },
          ]}
        />
        <SegmentedControl
          label="Compatibility mode"
          value={compat()}
          onChange={setCompat}
          segments={[
            { value: "BACKWARD", label: "BACKWARD" },
            { value: "FORWARD", label: "FORWARD" },
            { value: "FULL", label: "FULL" },
            { value: "NONE", label: "NONE" },
          ]}
        />
      </Row>
    );
  },
};

/** One control, live. Drive it with the arrow keys as well as the pointer. */
export const Interactive: Story = {
  render: () => {
    const [view, setView] = createSignal<"table" | "cards">("table");
    return (
      <Row>
        <SegmentedControl
          label="View"
          value={view()}
          onChange={setView}
          segments={[
            { value: "table", label: "Table", icon: "table" },
            { value: "cards", label: "Cards", icon: "cards" },
          ]}
        />
        <p style={{ color: "var(--kui-color-text-muted)", "font-size": "13px" }}>Showing the {view()} view.</p>
      </Row>
    );
  },
};

/**
 * One segment disabled.
 *
 * A disabled segment stays in the group rather than being dropped, because dropping it changes the
 * width of every other segment and moves the one the pointer was over.
 */
export const WithADisabledSegment: Story = {
  render: () => (
    <SegmentedControl
      label="View"
      value="table"
      onChange={() => undefined}
      segments={[
        { value: "table", label: "Table", icon: "table" },
        { value: "cards", label: "Cards", icon: "cards", disabled: true },
      ]}
    />
  ),
};

/**
 * Stretched to its container, which is what a filter bar does with it.
 *
 * The segments share the width equally rather than sizing to their labels, so a two-character
 * label and a nine-character one still make a symmetrical control.
 */
export const Stretched: Story = {
  render: () => (
    <div style={{ width: "420px" }}>
      <SegmentedControl
        label="Compatibility mode"
        value="FULL"
        onChange={() => undefined}
        stretch
        segments={[
          { value: "BACKWARD", label: "BACKWARD" },
          { value: "FORWARD", label: "FORWARD" },
          { value: "FULL", label: "FULL" },
          { value: "NONE", label: "NONE" },
        ]}
      />
    </div>
  ),
};

/**
 * The extremes, and the reason the component's documentation says five segments is too many.
 *
 * The first is a label nobody would write and somebody eventually will. The second is five
 * segments at the width a sidebar leaves: judge for yourself whether those labels are readable,
 * and reach for a `Select` when they are not.
 */
export const TheExtremes: Story = {
  render: () => (
    <Row>
      <div style={{ width: "360px" }}>
        <SegmentedControl
          label="View"
          value="a"
          onChange={() => undefined}
          segments={[
            { value: "a", label: "A view whose name did not fit" },
            { value: "b", label: "Cards" },
          ]}
        />
      </div>
      <div style={{ width: "300px" }}>
        <SegmentedControl
          label="Too many"
          value="c"
          onChange={() => undefined}
          stretch
          size="sm"
          segments={[
            { value: "a", label: "Latest" },
            { value: "b", label: "Earliest" },
            { value: "c", label: "Offset" },
            { value: "d", label: "Timestamp" },
            { value: "e", label: "Committed" },
          ]}
        />
      </div>
    </Row>
  ),
};

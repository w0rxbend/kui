import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import { FilterChip, FilterChipBar, SingleSelectChips } from "./FilterChips.jsx";
import { StatusPill } from "./StatusPill.jsx";
import { Tag } from "./Tag.jsx";

/**
 * The filter chip, and the two things it must not be mistaken for.
 *
 * `AgainstAPillAndATag` is the story that earns its place. The product has three pill-shaped
 * things — one reports, one labels, one acts — and only the third is clickable. If they converge,
 * operators start clicking the ones that do nothing, and conclude the interface is broken.
 */
const meta: Meta<typeof FilterChip> = {
  title: "Controls/FilterChip",
  component: FilterChip,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof FilterChip>;

/** The topic list's row, exactly as the design draws it. */
export const TheTopicListRow: Story = {
  render: () => {
    const [filter, setFilter] = createSignal<"all" | "internal" | "out-of-sync" | "compacted">("all");
    return (
      <SingleSelectChips
        label="Topic filters"
        value={filter()}
        onChange={setFilter}
        options={[
          { value: "all", label: "All" },
          { value: "internal", label: "Internal", icon: "lock" },
          { value: "out-of-sync", label: "Out of sync", icon: "warning" },
          { value: "compacted", label: "Compacted", icon: "sort" },
        ]}
      />
    );
  },
};

/** Active and inactive, side by side, which is the whole of the visual design. */
export const ActiveAndInactive: Story = {
  render: () => (
    <FilterChipBar label="States">
      <FilterChip label="Inactive" icon="lock" active={false} onToggle={() => undefined} />
      <FilterChip label="Active" active onToggle={() => undefined} />
      <FilterChip label="Disabled" icon="warning" active={false} disabled onToggle={() => undefined} />
    </FilterChipBar>
  ),
};

/**
 * With counts, including the case the component is careful about.
 *
 * `Out of sync 0` must be drawn. A chip that hides its count when the count is zero cannot say
 * "there are none of these", which is exactly the reassuring fact the operator came for.
 */
export const WithCounts: Story = {
  render: () => (
    <FilterChipBar label="Topic filters">
      <FilterChip label="All" count={128} active onToggle={() => undefined} />
      <FilterChip label="Internal" icon="lock" count={4} active={false} onToggle={() => undefined} />
      <FilterChip label="Out of sync" icon="warning" count={0} active={false} onToggle={() => undefined} />
      <FilterChip label="Unknown count" active={false} onToggle={() => undefined} />
    </FilterChipBar>
  ),
};

/**
 * Multi-select: several chips on at once.
 *
 * The component does not know whether it is single- or multi-select — that is a question about the
 * filters, not about the row — so this is the same component with a different handler.
 */
export const MultiSelect: Story = {
  render: () => {
    const [on, setOn] = createSignal<ReadonlySet<string>>(new Set(["internal", "compacted"]));
    const toggle = (key: string): void => {
      const next = new Set(on());
      if (next.has(key)) next.delete(key);
      else next.add(key);
      setOn(next);
    };
    return (
      <FilterChipBar label="Topic filters">
        <FilterChip label="Internal" icon="lock" active={on().has("internal")} onToggle={() => toggle("internal")} />
        <FilterChip
          label="Out of sync"
          icon="warning"
          active={on().has("out-of-sync")}
          onToggle={() => toggle("out-of-sync")}
        />
        <FilterChip label="Compacted" icon="sort" active={on().has("compacted")} onToggle={() => toggle("compacted")} />
      </FilterChipBar>
    );
  },
};

/**
 * The three pill-shaped things, together.
 *
 * From left: a chip (a control — it toggles), a status pill (a report — clicking does nothing), a
 * tag (a label in a table cell). Only the first is a button, and it has to look like one.
 */
export const AgainstAPillAndATag: Story = {
  render: () => (
    <div style={{ display: "flex", "align-items": "center", gap: "16px", "flex-wrap": "wrap" }}>
      <FilterChip label="Compacted" icon="sort" active={false} onToggle={() => undefined} />
      <StatusPill tone="success" icon="check">
        in sync
      </StatusPill>
      <Tag tone="neutral">compact</Tag>
    </div>
  ),
};

/**
 * The extremes: a chip whose label will not fit, a very large count, and a bar of more chips than
 * the row can hold. The bar wraps; it never scrolls sideways.
 */
export const TheExtremes: Story = {
  render: () => (
    <div style={{ width: "340px" }}>
      <FilterChipBar label="Too many">
        <FilterChip
          label="Topics whose replication factor is below the cluster default"
          count={18446744073709551615}
          active
          onToggle={() => undefined}
        />
        <FilterChip label="A" active={false} onToggle={() => undefined} />
        <FilterChip label="B" active={false} onToggle={() => undefined} />
        <FilterChip label="C" active={false} onToggle={() => undefined} />
        <FilterChip label="D" active={false} onToggle={() => undefined} />
        <FilterChip label="E" active={false} onToggle={() => undefined} />
      </FilterChipBar>
    </div>
  ),
};

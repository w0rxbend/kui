import { createSignal } from "solid-js";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, within } from "storybook/test";
import { SearchField, type SearchResultGroup } from "./SearchField.jsx";
import { LONG_TOPIC } from "./fixtures.js";

const RESULTS: readonly SearchResultGroup[] = [
  {
    heading: "TOPICS",
    items: [
      { id: "t1", label: "orders.payments.v2", href: "/topics/orders.payments.v2", detail: "12 partitions" },
      { id: "t2", label: "orders.payments.v1", href: "/topics/orders.payments.v1", detail: "6 partitions" },
    ],
  },
  {
    heading: "CONSUMER GROUPS",
    items: [{ id: "g1", label: "payments-processor", href: "/consumers/payments-processor", detail: "Stable" }],
  },
];

/**
 * The top bar's search field.
 *
 * The stories below cover the four things that can be true under the field — nothing asked for yet,
 * asking, nothing found, and the search service not answering — because an empty panel on its own
 * is ambiguous between all four, and each one wants a different next action from the operator.
 */
const meta = {
  title: "Shell/SearchField",
  component: SearchField,
  parameters: { layout: "padded" },
  decorators: [(Story) => <div style={{ width: "360px", padding: "8px 0 320px" }}>{Story()}</div>],
} satisfies Meta<typeof SearchField>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Resting, on a Linux or Windows machine: the hint reads `Ctrl K`. */
export const Idle: Story = {
  args: { value: "", onInput: () => {}, platform: "other" },
};

/** The same field on a Mac. Showing `⌘K` to a Linux operator teaches a shortcut that does not exist. */
export const IdleOnApple: Story = {
  args: { value: "", onInput: () => {}, platform: "apple" },
};

/** Focused: the ring is on the whole field, including the magnifier, and the hint has stepped out. */
export const Focused: Story = {
  args: { value: "", onInput: () => {}, platform: "other" },
  play: async ({ canvasElement }) => {
    const input = within(canvasElement).getByTestId("search-input");
    await userEvent.click(input);
    await expect(input).toHaveFocus();
  },
};

/** Results. The overlay floats above the page on the overlay surface with the only shadow in sight. */
export const WithResults: Story = {
  args: { value: "orders", onInput: () => {}, status: "ready", results: RESULTS, platform: "other" },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("search-input"));
  },
};

/** Asking. Skeleton rows at the size of the rows they stand in for, so "coming" cannot read as "empty". */
export const Searching: Story = {
  args: { value: "orders", onInput: () => {}, status: "searching", platform: "other" },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("search-input"));
  },
};

/** Asked, and there is nothing. Said in words, with the query quoted back. */
export const NoMatches: Story = {
  args: { value: "zzzz", onInput: () => {}, status: "empty", platform: "other" },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("search-input"));
  },
};

/**
 * The search service is not answering. The field stays enabled: disabling it teaches the operator
 * that the shortcut is broken and they stop reaching for it, whereas a box that explains itself is
 * one they will try again.
 */
export const Failed: Story = {
  args: { value: "orders", onInput: () => {}, status: "failed", platform: "other" },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("search-input"));
  },
};

/** The extreme case: a query and results made of the longest names this product meets. */
export const LongestResults: Story = {
  args: {
    value: LONG_TOPIC,
    onInput: () => {},
    status: "ready",
    platform: "other",
    results: [
      {
        heading: "TOPICS",
        items: [
          { id: "t1", label: LONG_TOPIC, href: "#", detail: "48 partitions · 1.2 TB · retention 7d" },
          { id: "t2", label: `${LONG_TOPIC}.v3`, href: "#", detail: "48 partitions" },
        ],
      },
    ],
  },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByTestId("search-input"));
  },
};

/**
 * Typing, for real, against a signal.
 *
 * This story is the guard on the defect that cost this product a bug report: the field must not be
 * rebuilt while somebody is typing in it. If the input is ever wrapped in a conditional, the
 * caret jumps to the end after each keystroke and the assertion below fails.
 */
export const TypingKeepsTheCaret: Story = {
  args: { value: "", onInput: () => {}, platform: "other" },
  render: () => {
    const [value, setValue] = createSignal("");
    return <SearchField value={value()} onInput={setValue} status="ready" results={RESULTS} platform="other" />;
  },
  play: async ({ canvasElement }) => {
    const input = within(canvasElement).getByTestId("search-input") as HTMLInputElement;
    await userEvent.click(input);
    await userEvent.keyboard("orders.payments");
    input.setSelectionRange(6, 6);
    await userEvent.keyboard("X");
    await expect(input.value).toBe("orderX.payments");
  },
};

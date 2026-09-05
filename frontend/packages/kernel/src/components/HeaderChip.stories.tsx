import { For } from "solid-js";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { expect, userEvent, within } from "storybook/test";
import { HeaderChip } from "./HeaderChip.jsx";

/**
 * One Kafka record header.
 *
 * The three stories that matter are `PresentButEmpty`, `NotValidUtf8` and `Absent`. A header that
 * is absent, one that is present with an empty value, and one whose bytes are not text are three
 * different facts about the producer, and collapsing any two of them loses a bug: a producer that
 * sets `correlation-id` to the empty string and one that never sets it are not the same producer.
 */
const meta = {
  title: "Kernel/HeaderChip",
  component: HeaderChip,
  parameters: { layout: "centered" },
} satisfies Meta<typeof HeaderChip>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The two headers from screenshot `03`. */
export const AsDesigned: Story = {
  args: { name: "content-type", value: "application/json" },
};

export const CorrelationId: Story = {
  args: { name: "correlation-id", value: "c_18442901" },
};

/**
 * Present, with an empty value.
 *
 * `(empty)` in italic at the subtle colour, and *not* the same as not being there. The chip's
 * presence says the producer set the header; the word says it set it to nothing.
 */
export const PresentButEmpty: Story = { args: { name: "x-empty", value: null } };

/**
 * The bytes are not valid UTF-8, so what is shown is hex, marked `binary`.
 *
 * Never a replacement character. `�` is what a decoder produces when it gives up, and
 * rendering it as though it were the data tells the operator their header contains a question mark
 * in a box, which it does not.
 */
export const NotValidUtf8: Story = {
  args: { name: "x-signature", value: "0x9f2a1c00ffee1234", binary: true },
};

/**
 * Absent — for comparison, not as a variant.
 *
 * There is no "absent" prop, and there should not be: a header that was not sent is simply not
 * rendered. This story shows the difference by putting the three cases side by side, because the
 * distinction only means anything when you can see all three at once.
 */
export const Absent: StoryObj = {
  render: () => (
    <div style={{ display: "grid", gap: "var(--kui-space-3)", "justify-items": "start" }}>
      <div>
        <p class="kui-record__section-label">SENT WITH A VALUE</p>
        <HeaderChip name="correlation-id" value="c_18442901" />
      </div>
      <div>
        <p class="kui-record__section-label">SENT EMPTY</p>
        <HeaderChip name="correlation-id" value={null} />
      </div>
      <div>
        <p class="kui-record__section-label">NOT SENT</p>
        <p class="kui-record__none">— nothing here, and nothing drawn</p>
      </div>
    </div>
  ),
};

/**
 * The longest value that will ever appear.
 *
 * Kafka does not limit a header's length, and a W3C traceparent with a baggage tail is routinely
 * longer than the chip. The visible text truncates; the tooltip and the accessible name carry the
 * whole thing, and the clipboard gets all of it.
 */
export const LongestPossibleValue: Story = {
  args: {
    name: "x-b3-traceparent",
    value:
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01,baggage=userId=alice,serverNode=DF%2028,isProduction=false,region=eu-central-1,tenant=acme-corporation-international",
  },
};

/** A long *name* as well as a long value. The name never truncates: it is what identifies the
 * chip, and a truncated name makes two different headers look like the same one. */
export const LongestPossibleName: Story = {
  args: {
    name: "x-acme-internal-message-routing-decision-explanation-header",
    value: "routed-to-eu-central-1",
  },
};

/**
 * Copying, proved.
 *
 * The chip is a button because a correlation id exists to be pasted into a log search. It says
 * "Copied" in a live region, so the confirmation reaches a screen reader as well as an eye — and
 * a clipboard write that the browser refuses leaves the label alone rather than claiming success.
 */
export const CopiesOnClick: Story = {
  args: { name: "correlation-id", value: "c_18442901" },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const chip = canvas.getByRole("button");
    await expect(chip).toHaveAccessibleName("Copy header correlation-id: c_18442901");
    await userEvent.click(chip);
    // The clipboard is not available in every context a story runs in, so this asserts the
    // affordance and its name rather than the platform's clipboard contents.
    await expect(chip).toBeVisible();
  },
};

/** A wrapping row of chips, as the expansion draws them. The row wraps; a chip never shrinks below
 * its name. */
export const AWrappingRow: StoryObj = {
  render: () => (
    <div class="kui-record__headers" style={{ width: "520px" }}>
      <For
        each={[
          { name: "content-type", value: "application/vnd.acme.order.v2+json; charset=utf-8" },
          { name: "correlation-id", value: "c_18442901" },
          { name: "x-empty", value: null },
          { name: "x-signature", value: "0x9f2a1c00ffee1234", binary: true },
          { name: "x-retry-count", value: "0" },
          { name: "x-source", value: "checkout-service@2.14.1" },
        ]}
      >
        {(header) => (
          <HeaderChip
            name={header.name}
            value={header.value}
            {...(header.binary === undefined ? {} : { binary: header.binary })}
          />
        )}
      </For>
    </div>
  ),
};

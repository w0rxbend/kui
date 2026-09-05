import { createSignal, onSettled } from "solid-js";
import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Button } from "./Button.jsx";
import { Drawer } from "./Drawer.jsx";

/**
 * The drawer, and the one story that is worth more than all the others: `SerdesArriveWhileTyping`.
 *
 * A drawer must not rebuild while somebody is typing in it. This project shipped the opposite —
 * the produce drawer threw away a composed payload the moment the serde list arrived — and the
 * only way to check the fix is to do the thing: start typing, wait, and see whether what you typed
 * is still there.
 */
const meta: Meta<typeof Drawer> = {
  title: "Surfaces/Drawer",
  component: Drawer,
  parameters: { layout: "fullscreen" },
};

export default meta;
type Story = StoryObj<typeof Drawer>;

function Stage(props: { readonly children: (open: () => boolean, setOpen: (v: boolean) => void) => unknown }) {
  const [open, setOpen] = createSignal(false, { ownedWrite: true });
  return (
    <div style={{ padding: "24px" }}>
      <p style={{ "max-width": "44ch", "margin-bottom": "16px" }}>
        A drawer ignores a click on the veil on purpose — it usually holds something somebody typed.
        <kbd>Escape</kbd> and the close button are the ways out, and both are deliberate acts.
      </p>
      <Button variant="secondary" icon="send" onClick={() => setOpen(true)}>
        Produce message
      </Button>
      {props.children(open, setOpen) as never}
    </div>
  );
}

const Field = (props: { readonly label: string; readonly children: unknown }) => (
  <label style={{ display: "flex", "flex-direction": "column", gap: "6px", "margin-bottom": "16px" }}>
    <span style={{ "font-size": "11px", "font-weight": 600, "letter-spacing": "0.06em", color: "var(--kui-color-text-muted)" }}>
      {props.label}
    </span>
    {props.children as never}
  </label>
);

const inputStyle = {
  height: "38px",
  padding: "0 12px",
  border: "1px solid var(--kui-color-border-strong)",
  "border-radius": "var(--kui-radius-md)",
  background: "var(--kui-color-surface-hover)",
  color: "var(--kui-color-text)",
  font: "inherit",
} as const;

export const ProduceMessage: Story = {
  render: () => (
    <Stage>
      {(open, setOpen) => (
        <Drawer
          open={open()}
          onClose={() => setOpen(false)}
          title="Produce to orders.payments.v2"
          description="The record is written as soon as you press Produce."
          footer={
            <>
              <Button variant="ghost" onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button variant="primary" icon="send" onClick={() => setOpen(false)}>
                Produce
              </Button>
            </>
          }
        >
          <Field label="PARTITION">
            <select style={inputStyle}>
              <option>Any (let Kafka choose)</option>
              <option>0</option>
              <option>1</option>
            </select>
          </Field>
          <Field label="KEY">
            <input style={inputStyle} placeholder="ord_9f21ac" />
          </Field>
          <Field label="VALUE">
            <textarea
              rows={8}
              style={{ ...inputStyle, height: "160px", padding: "12px", "font-family": "var(--kui-font-family-mono)" }}
            />
          </Field>
        </Drawer>
      )}
    </Stage>
  ),
};

/**
 * **The important one.** Open the drawer, start typing in the value box, and keep typing past three
 * seconds — the serde list arrives at that point, exactly as it does against a slow gateway.
 *
 * What must happen: the list appears, and not one character you typed is lost. If the editor
 * empties, the form has been put inside a boundary whose condition changed, which is the defect
 * this whole component is shaped around.
 */
export const SerdesArriveWhileTyping: Story = {
  render: () => (
    <Stage>
      {(open, setOpen) => {
        const [serdes, setSerdes] = createSignal<readonly string[]>([], { ownedWrite: true });
        onSettled(() => {
          const id = setTimeout(() => setSerdes(["String", "JSON", "Avro", "Protobuf"]), 3000);
          return () => clearTimeout(id);
        });
        return (
          <Drawer
            open={open()}
            onClose={() => setOpen(false)}
            title="Produce to orders.payments.v2"
            footer={<Button variant="primary" icon="send">Produce</Button>}
          >
            {/* The async region is around the select, and only around the select. */}
            <Field label="VALUE SERDE">
              <select style={inputStyle} disabled={serdes().length === 0}>
                {serdes().length === 0 ? (
                  <option>loading…</option>
                ) : (
                  serdes().map((serde) => <option>{serde}</option>)
                )}
              </select>
            </Field>
            <Field label="VALUE — type in here and wait three seconds">
              <textarea
                rows={8}
                style={{ ...inputStyle, height: "160px", padding: "12px", "font-family": "var(--kui-font-family-mono)" }}
              />
            </Field>
          </Drawer>
        );
      }}
    </Stage>
  ),
};

/**
 * The produce failed. The drawer stays open with the composed message intact and the error above
 * the footer. Throwing away a payload somebody typed because the broker was briefly busy is the
 * worst thing this surface can do.
 */
export const FailedAndStillOpen: Story = {
  render: () => (
    <Drawer
      open
      onClose={() => {}}
      title="Produce to orders.payments.v2"
      error={{ message: "The broker rejected the record: it is larger than max.message.bytes.", code: "RECORD_TOO_LARGE" }}
      footer={
        <>
          <Button variant="ghost">Cancel</Button>
          <Button variant="primary" icon="send">Produce</Button>
        </>
      }
    >
      <Field label="VALUE">
        <textarea
          rows={6}
          style={{ ...inputStyle, height: "160px", padding: "12px", "font-family": "var(--kui-font-family-mono)" }}
        >
          {'{ "orderId": "ord_9f21ac", "amount": 4212 }'}
        </textarea>
      </Field>
    </Drawer>
  ),
};

/**
 * A body longer than the window. The body scrolls inside its own box; the header and the footer
 * stay put. If the footer disappears off the bottom, `min-height: 0` has been removed from the
 * body — a flex child cannot shrink below its content unless it is told it may, and a scroller
 * that cannot shrink never scrolls.
 */
export const LongBody: Story = {
  render: () => (
    <Drawer open onClose={() => {}} title="Effective configuration" footer={<Button variant="primary">Done</Button>}>
      {Array.from({ length: 30 }, (_, index) => (
        <Field label={`SETTING ${index}`}>
          <input style={inputStyle} value="604800000" />
        </Field>
      ))}
    </Drawer>
  ),
};

/** From the left, for anything that is navigation rather than composition. */
export const FromTheLeft: Story = {
  render: () => (
    <Drawer open onClose={() => {}} title="Navigation" side="left" width="280px">
      <p>The same object, from the other edge.</p>
    </Drawer>
  ),
};

/** The extremes: the longest title, and the narrowest window the product supports. */
export const LongestTitleAndNarrowestWindow: Story = {
  render: () => (
    <div style={{ width: "360px", height: "480px", position: "relative", overflow: "hidden" }}>
      <Drawer
        open
        onClose={() => {}}
        title="Produce to orders.payments.reconciliation.v2.eu-central-1.high-throughput.retry.dead-letter"
        description="The record is written as soon as you press Produce, and cannot be recalled."
        footer={<Button variant="primary" icon="send">Produce</Button>}
      >
        <Field label="VALUE">
          <textarea rows={4} style={{ ...inputStyle, height: "120px", padding: "12px" }} />
        </Field>
      </Drawer>
    </div>
  ),
};

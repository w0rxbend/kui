import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Button } from "./Button.jsx";

/**
 * The stories below exist to make the *states* visible, not to demonstrate that a button renders.
 * Every defect this component is shaped by — three destructive actions that looked like ordinary
 * ones, a disabled action with no reason, a button that changed width mid-press — was in a state
 * nobody had a picture of.
 */
const meta = {
  title: "Primitives/Button",
  component: Button,
  argTypes: {
    variant: { control: "select", options: ["primary", "secondary", "danger", "ghost"] },
    size: { control: "radio", options: ["sm", "md"] },
  },
  args: { children: "Create topic", variant: "primary", size: "md" },
} satisfies Meta<typeof Button>;

export default meta;
type Story = StoryObj<typeof meta>;

const Row = (props: { children: unknown }) => (
  <div style={{ display: "flex", gap: "12px", "align-items": "center", "flex-wrap": "wrap" }}>
    {props.children as never}
  </div>
);

export const Primary: Story = { args: { icon: "plus", children: "Create topic" } };

export const Secondary: Story = { args: { variant: "secondary", icon: "send", children: "Produce message" } };

/**
 * The whole reason this variant exists. Put beside the other two it must be obvious at a glance
 * which one destroys something — with the labels covered up, not with them read.
 */
export const Danger: Story = { args: { variant: "danger", icon: "trash", children: "Purge" } };

export const Ghost: Story = { args: { variant: "ghost", icon: "settings", children: "Settings" } };

/** All four together, which is the comparison the defect was about. */
export const AllVariants: Story = {
  render: () => (
    <Row>
      <Button variant="primary" icon="plus">Create topic</Button>
      <Button variant="secondary" icon="send">Produce message</Button>
      <Button variant="danger" icon="trash">Purge</Button>
      <Button variant="ghost" icon="settings">Settings</Button>
    </Row>
  ),
};

/**
 * The topic page's action row, as the screenshots draw it — at the **small** size, which is the
 * stadium one. Measured from `02-topic-messages.png`: the "Produce message" fill is 26px of solid
 * colour and its corner arc reaches the edge at half the height. Compare with `Primary` above,
 * which is the 34px dashboard size and is not a stadium.
 */
export const TopicPageActions: Story = {
  render: () => (
    <Row>
      <Button size="sm" variant="secondary" icon="send">Produce message</Button>
      <Button size="sm" variant="danger" icon="trash">Purge</Button>
    </Row>
  ),
};

/** The two sizes beside each other, which is the comparison the measurement is about. */
export const BothSizes: Story = {
  render: () => (
    <div style={{ display: "grid", gap: "12px", "justify-items": "start" }}>
      <Button size="md" variant="primary" icon="plus">Create topic (34px, radius-md)</Button>
      <Button size="sm" variant="secondary" icon="send">Produce message (28px, stadium)</Button>
    </div>
  ),
};

export const Sizes: Story = {
  render: () => (
    <Row>
      <Button size="sm" icon="plus">Small</Button>
      <Button size="md" icon="plus">Medium</Button>
    </Row>
  ),
};

/**
 * Disabled, with the reason. Hover it or tab to it: the explanation is a tooltip, and the control
 * stays focusable so a keyboard user can reach the explanation at all.
 */
export const Disabled: Story = {
  render: () => (
    <Row>
      <Button
        variant="primary"
        icon="plus"
        disabled
        disabledReason="You do not have permission to create topics on prod-kyiv-01."
        disabledCode="RBAC_DENIED"
      >
        Create topic
      </Button>
      <Button
        variant="secondary"
        icon="send"
        disabled
        disabledReason="Producing is unavailable while the message service is down."
        disabledCode="UPSTREAM_UNAVAILABLE"
      >
        Produce message
      </Button>
      <Button
        variant="danger"
        icon="trash"
        disabled
        disabledReason="This topic is an internal compacted topic and cannot be purged."
      >
        Purge
      </Button>
    </Row>
  ),
};

/** Busy. The label stays and the width does not change, so nothing moves under the pointer. */
export const Busy: Story = {
  render: () => (
    <Row>
      <Button variant="primary" icon="plus" busy>Create topic</Button>
      <Button variant="secondary" icon="send" busy>Produce message</Button>
      <Button variant="danger" icon="trash" busy>Purge</Button>
    </Row>
  ),
};

/** Side by side with the resting state, to check that nothing shifts. */
export const BusyVersusResting: Story = {
  render: () => (
    <div style={{ display: "grid", gap: "8px", "justify-items": "start" }}>
      <Button variant="primary" icon="plus">Create topic</Button>
      <Button variant="primary" icon="plus" busy>Create topic</Button>
    </div>
  ),
};

/** Icon-only, as the top bar draws it. The name says the action, never the picture. */
export const IconOnly: Story = {
  render: () => (
    <Row>
      <Button variant="ghost" icon="bell" iconOnly>Notifications, 3 unread</Button>
      <Button variant="ghost" icon="sliders" iconOnly>Appearance</Button>
      <Button variant="ghost" icon="sun" iconOnly>Theme: follows system</Button>
      <Button variant="primary" icon="plus" iconOnly>Create topic</Button>
    </Row>
  ),
};

/** No icon at all. The danger variant is not offered here: the type forbids it. */
export const WithoutIcon: Story = {
  render: () => (
    <Row>
      <Button variant="primary">Save</Button>
      <Button variant="secondary">Cancel</Button>
      <Button variant="ghost">Reset</Button>
    </Row>
  ),
};

/**
 * The extreme case. A topic name is up to 249 characters and operators put whole sentences in
 * confirmation buttons. The button must not wrap, must not push its neighbour off screen, and
 * must not silently eat the label — so it is measured here against a narrow container.
 */
export const LongestLabel: Story = {
  render: () => (
    <div style={{ width: "320px", border: "1px dashed var(--kui-color-border)", padding: "12px" }}>
      <div style={{ display: "flex", gap: "8px", "flex-wrap": "wrap" }}>
        <Button variant="primary" icon="plus">
          Create topic orders.payments.v2.reprocessing.deadletter.eu-central-1
        </Button>
        <Button variant="danger" icon="trash">
          Purge orders.payments.v2.reprocessing.deadletter.eu-central-1
        </Button>
      </div>
    </div>
  ),
};

/** The smallest window. Nothing here may cause the page to scroll sideways. */
export const NarrowContainer: Story = {
  render: () => (
    <div style={{ width: "180px", border: "1px dashed var(--kui-color-border)", padding: "8px", overflow: "hidden" }}>
      <div style={{ display: "flex", gap: "8px", "flex-wrap": "wrap" }}>
        <Button variant="primary" icon="plus">Create topic</Button>
        <Button variant="danger" icon="trash">Purge</Button>
      </div>
    </div>
  ),
};

/** Every variant against every state, in one picture, for a visual sweep. */
export const Matrix: Story = {
  render: () => {
    const variants = ["primary", "secondary", "ghost"] as const;
    return (
      <table style={{ "border-collapse": "separate", "border-spacing": "12px" }}>
        <thead>
          <tr>
            <th />
            <th style={{ "font-size": "11px", color: "var(--kui-color-text-muted)" }}>default</th>
            <th style={{ "font-size": "11px", color: "var(--kui-color-text-muted)" }}>busy</th>
            <th style={{ "font-size": "11px", color: "var(--kui-color-text-muted)" }}>disabled</th>
          </tr>
        </thead>
        <tbody>
          {variants.map((v) => (
            <tr>
              <td style={{ "font-size": "11px", color: "var(--kui-color-text-muted)" }}>{v}</td>
              <td><Button variant={v} icon="plus">Action</Button></td>
              <td><Button variant={v} icon="plus" busy>Action</Button></td>
              <td><Button variant={v} icon="plus" disabled disabledReason="Not permitted here.">Action</Button></td>
            </tr>
          ))}
          <tr>
            <td style={{ "font-size": "11px", color: "var(--kui-color-text-muted)" }}>danger</td>
            <td><Button variant="danger" icon="trash">Action</Button></td>
            <td><Button variant="danger" icon="trash" busy>Action</Button></td>
            <td><Button variant="danger" icon="trash" disabled disabledReason="Not permitted here.">Action</Button></td>
          </tr>
        </tbody>
      </table>
    );
  },
};

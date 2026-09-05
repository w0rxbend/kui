import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Avatar } from "./Avatar.jsx";

const meta = {
  title: "Primitives/Avatar",
  component: Avatar,
  args: { name: "Olena Petrenko" },
} satisfies Meta<typeof Avatar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** As a button, which is how the top bar draws it: it opens the account menu. */
export const AsButton: Story = { args: { name: "Olena Petrenko", onClick: () => {} } };

/**
 * The identity service is unavailable. A neutral person glyph, never a guess — an operator who
 * sees the wrong initials concludes they are signed in as somebody else.
 */
export const NameUnknown: Story = { args: { name: undefined } };

/** The shapes a display name actually arrives in. */
export const NameShapes: Story = {
  render: () => (
    <div style={{ display: "flex", gap: "12px", "align-items": "center", "flex-wrap": "wrap" }}>
      <Avatar name="Olena Petrenko" />
      <Avatar name="admin" />
      <Avatar name="svc.connect-worker" />
      <Avatar name="o.petrenko@example.com" />
      <Avatar name="Maria de los Ángeles Fernández García" />
      <Avatar name="李" />
      <Avatar name="   " />
      <Avatar />
    </div>
  ),
};

/** The extreme case: a service account id with no word boundaries at all. */
export const LongestName: Story = {
  args: { name: "serviceaccountforthekafkaconnectclusterineucentral1" },
};

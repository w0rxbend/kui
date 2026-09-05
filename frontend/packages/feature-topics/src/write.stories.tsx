import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { CreateTopicDialog } from "./CreateTopicDialog.jsx";
import { TopicSettings } from "./TopicSettings.jsx";
import type { TopicConfig } from "./config.js";

/**
 * Creating a topic.
 *
 * The two sentences under the fields are the point of this dialog and are the reason it is not just
 * three boxes. Partitions can be added and never removed, and adding them changes which partition a
 * key hashes to; replication factor cannot be changed from KUI at all. Both are effectively
 * permanent decisions taken in ten seconds by somebody who came here to do something else.
 *
 * Neither number is pre-filled: an empty box reading "broker default" is honest, where a box
 * containing `1` looks like a recommendation this form is in no position to make.
 */
const meta: Meta<typeof CreateTopicDialog> = {
  title: "Screens/Create a topic",
  component: CreateTopicDialog,
  parameters: { layout: "fullscreen" },
};

export default meta;
type Story = StoryObj<typeof meta>;

const base = {
  open: true,
  onClose: () => undefined,
  onCreate: () => undefined,
  existingNames: ["orders.payments.v2", "analytics.pageviews"],
};

export const Empty: Story = {
  args: { ...base, state: { kind: "idle" } },
};

/** The request is out. The button is busy and Cancel refuses, so a second create cannot start. */
export const Creating: Story = {
  args: { ...base, state: { kind: "running" } },
};

/**
 * The cluster refused it. The dialog stays open with everything the operator typed still in it —
 * closing on failure loses the work and leaves them nothing to correct.
 */
export const Refused: Story = {
  args: {
    ...base,
    state: {
      kind: "failed",
      message: "A topic named orders.payments.v2 already exists on this cluster.",
      code: "KUI-TOPIC-EXISTS",
    },
  },
};

/** A 403. Not a failure with a retry: there is nothing to retry. */
export const Forbidden: Story = {
  args: {
    ...base,
    state: { kind: "forbidden", message: "You do not have permission to create topics on this cluster." },
  },
};

/* ------------------------------------------------------------------------------------------------
 * The settings tab
 * ---------------------------------------------------------------------------------------------- */

const CONFIG: TopicConfig = {
  overridden: 2,
  entries: [
    {
      name: "retention.ms",
      value: "604800000",
      defaultValue: null,
      source: "topic",
      sensitive: false,
      readOnly: false,
      documentation: "This configuration controls the maximum time we will retain a log before we discard old log segments.",
    },
    {
      name: "min.insync.replicas",
      value: "2",
      defaultValue: "1",
      source: "topic",
      sensitive: false,
      readOnly: false,
      documentation: "Specifies the minimum number of replicas that must acknowledge a write.",
    },
    {
      name: "cleanup.policy",
      value: "delete",
      defaultValue: "delete",
      source: "inherited",
      sensitive: false,
      readOnly: false,
      documentation: "The retention policy to use on log segments.",
    },
    {
      name: "compression.gzip.level",
      value: "-1",
      defaultValue: "-1",
      source: "inherited",
      sensitive: false,
      readOnly: false,
      documentation: null,
    },
    {
      // The broker sends no value at all for a key it marks sensitive. Never an editable box: an
      // empty one beside Save invites overwriting a secret with the empty string.
      name: "ssl.keystore.password",
      value: null,
      defaultValue: null,
      source: "inherited",
      sensitive: true,
      readOnly: false,
      documentation: null,
    },
  ],
};

type SettingsStory = StoryObj<typeof TopicSettings>;

/**
 * Two settings somebody chose, among the ones nobody did.
 *
 * A real topic has thirty-three keys and three of them hold a value that was set. Those three are
 * the entire reason anybody opens this tab — "why is this topic behaving differently" is answered by
 * them and by nothing else — so they come first and the rest are behind the switch.
 */
export const Settings: SettingsStory = {
  render: (args) => <TopicSettings {...args} />,
  args: { config: CONFIG, onChange: () => undefined, state: { kind: "idle" } },
};

/** Nothing set: a fact about the topic, and a good one. Better than an empty table. */
export const NothingOverridden: SettingsStory = {
  render: (args) => <TopicSettings {...args} />,
  args: {
    config: { overridden: 0, entries: CONFIG.entries.map((e) => ({ ...e, source: "inherited" as const })) },
    onChange: () => undefined,
    state: { kind: "idle" },
  },
};

/** A read-only cluster, or a principal without the permission. Said once, not on every row. */
export const CannotEdit: SettingsStory = {
  render: (args) => <TopicSettings {...args} />,
  args: {
    config: CONFIG,
    onChange: undefined,
    changeDisabledReason: "This cluster is configured read-only in KUI, so nothing here can be changed.",
    state: { kind: "idle" },
  },
};

/**
 * The configuration was withheld.
 *
 * ADR-039: a caller who may see the topic but not its settings gets a `not_permitted` view rather
 * than a 403, so the rest of the page keeps working. Drawn as an empty table it would read as "this
 * topic has no configuration", which is a very different and entirely false statement.
 */
export const Withheld: SettingsStory = {
  render: (args) => <TopicSettings {...args} />,
  args: { config: { entries: [], overridden: 0 }, onChange: () => undefined, state: { kind: "idle" } },
};

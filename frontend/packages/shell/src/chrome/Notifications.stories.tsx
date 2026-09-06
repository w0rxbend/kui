import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import {
  NotificationBell,
  NotificationPanel,
  type Notice,
  type NoticeCategory,
  type NoticeFeed,
} from "./Notifications.jsx";

/**
 * The bell and its panel.
 *
 * `Empty` is the story that earns its place. A bell that opens nothing when there is no news is
 * indistinguishable from a broken bell, and an operator who cannot tell those apart stops trusting
 * it — after which the one notification that mattered goes unread. The panel always opens, and when
 * there is nothing it says so.
 *
 * `Failed` is the same argument one step further: an empty list for a failed request is a lie, and
 * a quiet one.
 */
const meta: Meta<typeof NotificationPanel> = {
  title: "Chrome/Notifications",
  component: NotificationPanel,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof NotificationPanel>;

/** Fixed, so that the relative ages in these stories do not drift with the wall clock. */
const NOW = new Date("2026-09-05T12:00:00Z");
const ago = (minutes: number): Date => new Date(NOW.getTime() - minutes * 60_000);

/**
 * The four the design draws, in the order it draws them.
 *
 * Two of them are warnings and they carry different glyphs — the rebalance arrows and the disk —
 * which is the whole of `SCREENS-V4.md` §3.9's correction: severity chooses the tone, category
 * chooses the glyph. Before the category existed, this story drew two identical amber triangles and
 * looked correct, which is why it is worth reading the icons on it rather than the words.
 */
const NOTICES: readonly Notice[] = [
  {
    id: "rebalance",
    severity: "warning",
    category: "rebalance",
    title: "clickstream-etl is rebalancing",
    body: "12 members, lag climbing past 3.8k. Third time today.",
    at: ago(2),
    href: "#consumers",
  },
  {
    id: "connector",
    severity: "danger",
    category: "connector",
    title: "Connector elastic-audit-sink failed",
    body: "Task 0: connection refused to es-01:9200.",
    at: ago(14),
    href: "#connect",
  },
  {
    id: "disk",
    severity: "warning",
    category: "storage",
    title: "broker-3 disk at 83%",
    body: "Consider shortening retention on analytics.clickstream.",
    at: ago(60),
    href: "#brokers",
  },
  {
    id: "schema",
    severity: "success",
    category: "schema",
    title: "Schema v3 registered",
    body: "orders.payments.v2-value is BACKWARD compatible.",
    at: ago(180),
    read: true,
    href: "#schema",
  },
];

const Panel = (props: { readonly feed: NoticeFeed }) => (
  <NotificationPanel feed={props.feed} now={NOW} onMarkAllRead={() => undefined} onRetry={() => undefined} />
);

/** The panel as the design draws it: three unread, one read. */
export const Default: Story = {
  render: () => <Panel feed={{ kind: "ready", notices: NOTICES }} />,
};

/**
 * Nothing to report.
 *
 * Words, not a blank panel and not a panel that refuses to open. This is the case the component
 * exists to get right.
 */
export const Empty: Story = {
  render: () => <Panel feed={{ kind: "ready", notices: [] }} />,
};

/** Still fetching. A spinner and a sentence, never an empty list that will fill in later. */
export const Loading: Story = {
  render: () => <Panel feed={{ kind: "loading" }} />,
};

/** The request failed. It says so and offers a retry; it does not show an empty list. */
export const Failed: Story = {
  render: () => (
    <Panel feed={{ kind: "failed", reason: "Notifications could not be fetched — the gateway did not answer." }} />
  ),
};

/**
 * We have notices and they are out of date.
 *
 * Shown, with the reason above them. The alternative — hiding a stale list — leaves the operator
 * with nothing at exactly the moment something is wrong.
 */
export const Stale: Story = {
  render: () => (
    <Panel feed={{ kind: "stale", notices: NOTICES, reason: "Last updated 6 minutes ago; the stream dropped." }} />
  ),
};

/** Every severity, in one panel, so the four tones can be judged as a set. */
export const EverySeverity: Story = {
  render: () => (
    <Panel
      feed={{
        kind: "ready",
        notices: [
          { id: "1", severity: "info", title: "Rebalance finished", body: "orders-consumer is stable.", at: ago(1) },
          { id: "2", severity: "success", title: "Schema registered", body: "v4 is BACKWARD compatible.", at: ago(5) },
          { id: "3", severity: "warning", title: "broker-3 disk at 83%", body: "Retention is 7 days.", at: ago(30) },
          { id: "4", severity: "danger", title: "Connector failed", body: "Task 0: connection refused.", at: ago(90) },
        ],
      }}
    />
  ),
};

/** All read. They stay, dimmed, rather than being hidden — and "Mark all read" disappears. */
export const AllRead: Story = {
  render: () => <Panel feed={{ kind: "ready", notices: NOTICES.map((notice) => ({ ...notice, read: true })) }} />,
};

/** A notification with no body. The title centres against its tile rather than leaving a gap. */
export const NoBody: Story = {
  render: () => (
    <Panel
      feed={{
        kind: "ready",
        notices: [
          { id: "1", severity: "info", title: "Cluster reconnected", at: ago(1) },
          { id: "2", severity: "warning", title: "Rebalance started", at: ago(3) },
        ],
      }}
    />
  ),
};

/** The bell, in all four of its states. The dot is absent when nothing is unread, not grey. */
export const TheBell: Story = {
  render: () => {
    const [open, setOpen] = createSignal(false);
    return (
      <div style={{ display: "flex", gap: "24px", "align-items": "center" }}>
        <NotificationBell unreadCount={0} open={false} onToggle={() => undefined} />
        <NotificationBell unreadCount={3} open={false} onToggle={() => undefined} />
        <NotificationBell unreadCount={128} open={false} onToggle={() => undefined} />
        <NotificationBell unreadCount={3} open={open()} onToggle={() => setOpen(!open())} />
      </div>
    );
  },
};

/** The bell and the panel together, positioned as the top bar does it. Click to open. */
export const BellAndPanel: Story = {
  parameters: { layout: "fullscreen" },
  render: () => {
    const [open, setOpen] = createSignal(true);
    return (
      <div style={{ height: "420px", padding: "16px", display: "flex", "justify-content": "flex-end" }}>
        <div style={{ position: "relative" }}>
          <NotificationBell unreadCount={3} open={open()} onToggle={() => setOpen(!open())} />
          {open() ? (
            <div style={{ position: "absolute", top: "42px", right: "0" }}>
              <Panel feed={{ kind: "ready", notices: NOTICES }} />
            </div>
          ) : null}
        </div>
      </div>
    );
  },
};

/**
 * The extremes: a title and a body far longer than the panel, and more notices than it is tall.
 *
 * The body clamps to three lines — a notification is a summary and the page it links to is the
 * detail — and the list scrolls inside the panel's fixed height rather than growing off the screen.
 */
export const TheExtremes: Story = {
  render: () => (
    <Panel
      feed={{
        kind: "ready",
        notices: [
          {
            id: "long",
            severity: "danger",
            title:
              "Connector elastic-audit-sink-eu-central-1-reprocessing-dead-letter has failed for the fourth time",
            body:
              "Task 0: connection refused to es-01.eu-central-1.internal:9200. Task 1: connection refused to es-02.eu-central-1.internal:9200. The connector has been restarted automatically three times in the last hour and will not be restarted again without an operator.",
            at: ago(1),
          },
          ...Array.from({ length: 20 }, (_, index) => ({
            id: `n-${index}`,
            severity: "info" as const,
            title: `Routine notice ${index}`,
            body: "Something unremarkable happened.",
            at: ago(index * 7 + 5),
          })),
        ],
      }}
    />
  ),
};

/** One amber notification of a given category, so the list below reads as seven glyphs. */
const warning = (id: string, category: NoticeCategory, title: string, minutes: number): Notice => ({
  id,
  severity: "warning",
  category,
  title,
  at: ago(minutes),
});

/**
 * One severity, every category.
 *
 * Seven warnings that are seven different things. Read down the tiles: they are all amber, because
 * that is what "warning" means, and no two of them draw the same mark. This is the story that fails
 * if the glyph ever goes back to being derived from the severity.
 */
export const OneSeverityEveryCategory: Story = {
  render: () => (
    <Panel
      feed={{
        kind: "ready",
        notices: [
          warning("1", "rebalance", "orders-consumer is rebalancing", 1),
          warning("2", "storage", "broker-3 disk at 83%", 3),
          warning("3", "connector", "es-audit-sink task 0 retrying", 7),
          warning("4", "schema", "v4 is only FORWARD compatible", 11),
          warning("5", "cluster", "broker-2 rejoined the cluster", 19),
          warning("6", "topic", "analytics.raw has 1 URP", 31),
          warning("7", "security", "Sign-in refused for svc-etl", 47),
        ],
      }}
    />
  ),
};

/**
 * Notifications with no category at all, which is every notification the product raises today.
 *
 * The glyph falls back to the severity. It is honest and it is weak — four amber triangles is a
 * panel nobody scans — and that weakness is the argument for recording a category wherever one is
 * known, rather than for inventing one here.
 */
export const NoCategoryRecorded: Story = {
  render: () => (
    <Panel
      feed={{
        kind: "ready",
        notices: [
          { id: "1", severity: "info", title: "Cluster reconnected", at: ago(2) },
          { id: "2", severity: "warning", title: "Rebalance started", at: ago(6) },
          { id: "3", severity: "danger", title: "Connector failed", at: ago(9) },
          { id: "4", severity: "success", title: "Schema registered", at: ago(14) },
        ],
      }}
    />
  ),
};

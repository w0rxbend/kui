import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { createSignal } from "solid-js";
import { AppFrame } from "./AppFrame.jsx";
import { EnvRail, type RailDestination } from "./EnvRail.jsx";
import { NavDrawer } from "./NavDrawer.jsx";
import { TopBar } from "./TopBar.jsx";
import { CLUSTERS, HEALTHY_CLUSTER, NAV_GROUPS, NAV_GROUPS_DEGRADED } from "./fixtures.js";
import type { BrokerStorage } from "./StorageMeter.jsx";

/**
 * The whole frame, assembled from fixtures.
 *
 * This is the story to put beside `13-topics-list.png`. Everything it is responsible for is a
 * question about arrangement — the seam between the rail and the drawer, whether the top band reads
 * as page rather than as a bar, whether the drawer keeps its foot, what happens at 900px — and none
 * of those can be answered by a test in jsdom, because jsdom does not lay anything out.
 *
 * Three measurements to check against the design, all from `SCREENS.md` §1.1: the rail is 48px on
 * the page ground, the drawer is 182px and raised, and the content column starts 20px after the
 * drawer's right edge.
 */
const meta: Meta<typeof AppFrame> = {
  title: "Shell/AppFrame",
  component: AppFrame,
  parameters: { layout: "fullscreen" },
};

export default meta;
type Story = StoryObj<typeof AppFrame>;

const GB = 1024 ** 3;

const RAIL_DESTINATIONS: readonly RailDestination[] = [
  { id: "notifications", label: "Notifications", icon: "bell", href: "#notifications" },
  { id: "ksql", label: "ksqlDB", icon: "ksql", href: "#ksql" },
  { id: "security", label: "Security", icon: "shield", href: "#security" },
  { id: "settings", label: "Settings", icon: "settings", href: "#settings", atFoot: true },
];

const STORAGE: readonly BrokerStorage[] = [
  { id: "broker-1.kyiv", usedBytes: 254 * GB, totalBytes: 416 * GB },
  { id: "broker-2.kyiv", usedBytes: 241 * GB, totalBytes: 416 * GB },
  { id: "broker-3.kyiv", usedBytes: 347 * GB, totalBytes: 418 * GB },
];

/** Stand-in page content, so the content column has something to be judged against. */
const Page = (props: { readonly title: string; readonly voice?: string }) => (
  <div>
    <h1
      style={{
        "font-family": "var(--kui-font-family-display)",
        "font-size": "var(--kui-font-size-2xl)",
        "font-weight": "var(--kui-font-weight-display)",
        color: "var(--kui-color-text-strong)",
        margin: "24px 0 4px",
      }}
    >
      {props.title}
    </h1>
    {props.voice === undefined ? null : (
      <p style={{ margin: "0 0 20px", color: "var(--kui-color-text-muted)", "font-size": "14px" }}>{props.voice}</p>
    )}
    <div
      style={{
        display: "grid",
        "grid-template-columns": "repeat(4, minmax(0, 1fr))",
        gap: "20px",
      }}
    >
      {[0, 1, 2, 3].map(() => (
        <div
          style={{
            height: "104px",
            border: "var(--kui-card-border)",
            "border-radius": "var(--kui-radius-lg)",
            background: "var(--kui-color-surface-elevated)",
          }}
        />
      ))}
    </div>
  </div>
);

const Frame = (props: {
  readonly groups?: typeof NAV_GROUPS;
  readonly cluster?: typeof HEALTHY_CLUSTER | undefined;
  readonly storage?: readonly BrokerStorage[] | undefined;
  readonly currentId?: string;
  readonly crumbs?: readonly { readonly label: string; readonly href?: string }[];
  readonly children?: unknown;
}) => {
  const [selected, setSelected] = createSignal<string | undefined>("prod-kyiv-01");
  const [open, setOpen] = createSignal(false);
  return (
    <AppFrame
      rail={
        <EnvRail
          environments={CLUSTERS}
          currentId={selected()}
          onSelect={setSelected}
          destinations={RAIL_DESTINATIONS}
          onAdd={() => undefined}
          accountName="Olena Petrenko"
        />
      }
      drawer={
        <NavDrawer
          groups={props.groups ?? NAV_GROUPS}
          currentId={props.currentId ?? "topics"}
          cluster={props.cluster === undefined && props.storage === undefined ? undefined : HEALTHY_CLUSTER}
          storage={props.storage}
        />
      }
      topbar={
        <TopBar
          crumbs={props.crumbs ?? [{ label: "prod-kyiv-01", href: "#c" }, { label: "Topics" }]}
          search={{ value: "", onInput: () => undefined, platform: "other" }}
          theme="dark"
          unreadCount={3}
          notificationsOpen={open()}
          onToggleNotifications={() => setOpen(!open())}
          notifications={{ kind: "ready", notices: [] }}
        />
      }
    >
      {(props.children ?? <Page title="Topics" voice="24 of 128 topics match · 1,536 partitions" />) as never}
    </AppFrame>
  );
};

/** The design, as `13-topics-list.png` draws it. Hold the two up next to each other. */
export const AsDesigned: Story = {
  render: () => <Frame cluster={HEALTHY_CLUSTER} storage={STORAGE} />,
};

/**
 * A cluster that is not answering.
 *
 * The frame still draws — this is the rule the whole shell is built on. The drawer's foot swaps the
 * storage meter for the status card, because "you cannot reach this cluster, here is a retry" is
 * more use than an em dash where a percentage would be.
 */
export const ClusterUnreachable: Story = {
  render: () => (
    <Frame
      groups={NAV_GROUPS_DEGRADED}
      cluster={undefined}
      crumbs={[{ label: "prod-kyiv-01", href: "#c" }, { label: "Brokers" }]}
    >
      <Page title="Brokers" voice="The cluster is not answering. The figures below are the last we saw." />
    </Frame>
  ),
};

/** Nothing configured yet: no cluster, no storage. The frame is what gets the operator to settings. */
export const NothingConfigured: Story = {
  render: () => (
    <Frame groups={NAV_GROUPS} cluster={undefined} crumbs={[]}>
      <Page title="Welcome" voice="No cluster is configured yet." />
    </Frame>
  ),
};

/** The notifications panel open over the frame, which is where its anchoring is judged. */
export const NotificationsOpen: Story = {
  render: () => {
    const [selected, setSelected] = createSignal<string | undefined>("prod-kyiv-01");
    return (
      <AppFrame
        rail={
          <EnvRail
            environments={CLUSTERS}
            currentId={selected()}
            onSelect={setSelected}
            destinations={RAIL_DESTINATIONS}
            accountName="Olena Petrenko"
          />
        }
        drawer={<NavDrawer groups={NAV_GROUPS} currentId="topics" cluster={HEALTHY_CLUSTER} storage={STORAGE} />}
        topbar={
          <TopBar
            crumbs={[{ label: "prod-kyiv-01", href: "#c" }, { label: "Topics" }]}
            search={{ value: "", onInput: () => undefined, platform: "other" }}
            theme="dark"
            unreadCount={3}
            notificationsOpen
            onToggleNotifications={() => undefined}
            notifications={{
              kind: "ready",
              notices: [
                {
                  id: "a",
                  severity: "warning",
                  title: "clickstream-etl is rebalancing",
                  body: "12 members, lag climbing past 3.8k. Third time today.",
                  at: new Date(Date.now() - 120_000),
                },
                {
                  id: "b",
                  severity: "danger",
                  title: "Connector elastic-audit-sink failed",
                  body: "Task 0: connection refused to es-01:9200.",
                  at: new Date(Date.now() - 840_000),
                },
                {
                  id: "c",
                  severity: "success",
                  title: "Schema v3 registered",
                  body: "orders.payments.v2-value is BACKWARD compatible.",
                  at: new Date(Date.now() - 10_800_000),
                  read: true,
                },
              ],
            }}
          />
        }
      >
        <Page title="Topics" voice="24 of 128 topics match · 1,536 partitions" />
      </AppFrame>
    );
  },
};

/**
 * A page long enough to scroll.
 *
 * The rail, the drawer and the top band must all stay put; only the content moves. If the whole
 * frame scrolls, the navigation leaves the screen exactly when a long page makes it most useful.
 */
export const AScrollingPage: Story = {
  render: () => (
    <Frame cluster={HEALTHY_CLUSTER} storage={STORAGE}>
      <div>
        <Page title="Topics" voice="24 of 128 topics match · 1,536 partitions" />
        <div style={{ height: "1600px", display: "grid", "align-content": "end" }}>
          <p style={{ color: "var(--kui-color-text-muted)" }}>
            The bottom of a long page. The rail, the drawer and the band should all still be here.
          </p>
        </div>
      </div>
    </Frame>
  ),
};

/**
 * The narrow window, below 1000px.
 *
 * The rail keeps its column at every width — it is 48px, it is how the cluster is chosen, and a
 * narrow window is exactly when an operator is most likely to be checking which cluster they are
 * looking at. The drawer is what folds, into a strip above the content. That is a stopgap and is
 * visibly one: a proper disclosure needs a focus trap and a scrim, and half-building it would leave
 * the navigation unreachable.
 */
export const NarrowWindow: Story = {
  render: () => (
    <div style={{ width: "840px", height: "620px", overflow: "hidden", resize: "horizontal" }}>
      <Frame cluster={HEALTHY_CLUSTER} storage={STORAGE} />
    </div>
  ),
};

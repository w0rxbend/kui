import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { Banner } from "./Banner.jsx";
import { Button } from "./Button.jsx";

/**
 * The page-wide banner.
 *
 * It exists for one thing: telling the operator that everything below it is misleading. That is a
 * rare enough condition that the most useful story here is `OneBannerMaximum`, which shows what
 * the alternative looks like and why it is not allowed.
 */
const meta: Meta<typeof Banner> = {
  title: "Surfaces/Banner",
  component: Banner,
  parameters: { layout: "padded" },
};

export default meta;
type Story = StoryObj<typeof Banner>;

export const ClusterUnreachable: Story = {
  render: () => (
    <Banner
      tone="danger"
      message="The cluster is not answering. The last successful check was 4 minutes ago."
      code="CLUSTER_UNREACHABLE"
      action={<Button variant="secondary" icon="refresh">Retry</Button>}
    />
  ),
};

export const Degraded: Story = {
  render: () => (
    <Banner
      tone="warning"
      message="2 of 3 brokers are online. 47 partitions are under-replicated."
      action={<Button variant="ghost">View brokers</Button>}
    />
  ),
};

/**
 * The one case where dismissing is right: a condition the operator has chosen to live with. A
 * cluster that is not answering must **not** be dismissible, because dismissing it makes every
 * stale number on the page look current.
 */
export const ReadOnlyAndDismissible: Story = {
  render: () => (
    <Banner
      tone="info"
      message="KUI is in read-only mode for this cluster. Producing, deleting and configuration changes are disabled."
      onDismiss={() => {}}
    />
  ),
};

/**
 * What a stack of banners looks like, and why the component takes one banner rather than a list.
 * Read the third one. Nobody read the third one.
 */
export const OneBannerMaximum: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", gap: "8px" }}>
      <Banner tone="danger" message="The cluster is not answering." code="CLUSTER_UNREACHABLE" />
      <Banner tone="warning" message="2 of 3 brokers are online." />
      <Banner tone="info" message="KUI is in read-only mode for this cluster." />
    </div>
  ),
};

/** The extremes: the longest sentence, an action, a code and a dismiss, in a narrow window. */
export const TheExtremes: Story = {
  render: () => (
    <div style={{ display: "flex", "flex-direction": "column", gap: "16px" }}>
      <Banner
        tone="danger"
        message="The cluster is not answering, and the figures on this page are from the last successful check four minutes ago; the count of consumer groups needing attention has been withheld rather than shown as zero."
        code="CLUSTER_UNREACHABLE_AFTER_RETRY_BUDGET_EXHAUSTED"
        action={<Button variant="secondary" icon="refresh">Retry</Button>}
        onDismiss={() => {}}
      />
      <div style={{ width: "300px" }}>
        <Banner
          tone="warning"
          message="2 of 3 brokers are online. 47 partitions are under-replicated."
          action={<Button variant="ghost">View</Button>}
        />
      </div>
    </div>
  ),
};

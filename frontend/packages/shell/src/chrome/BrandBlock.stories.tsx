import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { BrandBlock } from "./BrandBlock.jsx";
import {
  DEFECTIVE_CLUSTER,
  HEALTHY_CLUSTER,
  LONG_NAME_CLUSTER,
  UNCOUNTED_CLUSTER,
  UNREACHABLE_CLUSTER,
  VERSIONLESS_CLUSTER,
} from "./fixtures.js";

/**
 * The drawer's head, which is the cluster and no longer the product (`SCREENS-V4.md` §2.1).
 *
 * The stories worth looking at are the last four. Every screenshot in this project is of a cluster
 * whose every figure arrived, and the rule this component exists to enforce is what happens when
 * one did not: each part of the caption is dropped on its own, so `NoBrokerCount` shows a shorter
 * line rather than `— brokers`, and `NothingKnownYet` shows no caption at all rather than a row of
 * dashes.
 *
 * The dot is decoration in every one of them. What it says is said in words by the caption's first
 * token — which is why the one state with no words, `NothingKnownYet`, is also the one where the
 * dot is deliberately the subtle colour rather than any of the three health colours.
 */
const meta = {
  title: "Shell/BrandBlock",
  component: BrandBlock,
  decorators: [
    (Story) => <div style={{ width: "182px", background: "var(--kui-color-surface-raised)" }}>{Story()}</div>,
  ],
} satisfies Meta<typeof BrandBlock>;

export default meta;
type Story = StoryObj<typeof meta>;

/** `M01`: the design, with all three parts of the caption present. */
export const Healthy: Story = {
  args: { cluster: HEALTHY_CLUSTER, manageHref: "/ui/clusters/manage" },
};

/** `M09`: the first token becomes the defect count, because "1 URP" says what the dot cannot. */
export const UnderReplicated: Story = {
  args: { cluster: DEFECTIVE_CLUSTER, manageHref: "/ui/clusters/manage" },
};

/** The cluster is not answering. The caption says so; the dot only agrees. */
export const Unreachable: Story = {
  args: { cluster: UNREACHABLE_CLUSTER, manageHref: "/ui/clusters/manage" },
};

/** The version could not be read. Two parts, one separator — never `healthy · · 3 brokers`. */
export const NoVersion: Story = {
  args: { cluster: VERSIONLESS_CLUSTER, manageHref: "/ui/clusters/manage" },
};

/**
 * Nobody has counted the brokers.
 *
 * The caption ends after the version. The failure this story exists to catch is a template that
 * prints `— brokers`, which reads as a missing dash rather than as a missing figure.
 */
export const NoBrokerCount: Story = {
  args: { cluster: UNCOUNTED_CLUSTER, manageHref: "/ui/clusters/manage" },
};

/**
 * Before the first scrape answers: a name, a subtle dot, and no caption at all.
 *
 * "health not known yet" is the right sentence for a tooltip and the wrong one for an 11px line
 * read at a glance, so at this size the honest rendering is to say nothing.
 */
export const NothingKnownYet: Story = {
  args: {
    cluster: { id: "prod-kyiv-01", name: "prod-kyiv-01", health: "unknown" },
    manageHref: "/ui/clusters/manage",
  },
};

/**
 * A fresh install with nothing configured. Not an error, and not drawn as one — the `+` beside it
 * is the whole point of the state.
 */
export const NoCluster: Story = { args: { manageHref: "/ui/clusters/manage" } };

/** A name longer than the drawer. It truncates; the caption underneath still fits and still wraps. */
export const LongName: Story = {
  args: { cluster: LONG_NAME_CLUSTER, manageHref: "/ui/clusters/manage" },
};

/**
 * A deployment where cluster registration is not reachable: no `+` at all.
 *
 * A control that goes nowhere is worse than an absent one, so the head simply loses it rather than
 * drawing a disabled glyph nobody can act on.
 */
export const NoRegistrationRoute: Story = { args: { cluster: HEALTHY_CLUSTER } };

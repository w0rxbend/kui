import type { Meta, StoryObj } from "storybook-solidjs-vite";
import { BrandBlock } from "./BrandBlock.jsx";

/**
 * The drawer's head. Fixed content — this is the product's name — with one variable: the health dot
 * on the corner of the tile, which mirrors the cluster card at the foot of the drawer.
 *
 * The dot is decoration and is hidden from assistive technology. That is deliberate and it is safe
 * precisely because it is a mirror: the same fact is stated in words forty pixels further down, so
 * hiding the coloured circle removes a redundant announcement rather than a piece of information.
 */
const meta = {
  title: "Shell/BrandBlock",
  component: BrandBlock,
  decorators: [
    (Story) => <div style={{ width: "196px", background: "var(--kui-color-surface-raised)" }}>{Story()}</div>,
  ],
} satisfies Meta<typeof BrandBlock>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Healthy: Story = { args: { health: "healthy" } };
export const Degraded: Story = { args: { health: "degraded" } };
export const Unreachable: Story = { args: { health: "unreachable" } };

/** Before a cluster has been chosen. The dot is subtle rather than absent: a missing dot would move
 * the tile's silhouette, and the drawer's head should not change shape while the page settles. */
export const Unknown: Story = { args: { health: "unknown" } };

import { Icon } from "@kui/kernel";
import type { ClusterHealth } from "./types.js";

/**
 * The head of the navigation drawer: the gradient tile, the wordmark and the tagline.
 *
 * ## What the dot on the tile is, and what it is not
 *
 * The tile carries a small dot in the corner that mirrors the selected cluster's health. It is
 * decoration, and it is marked as such: the authoritative statement about the cluster is the card at
 * the foot of the drawer, which says the same thing in words. Two things follow from that. The dot
 * is `aria-hidden`, because a screen reader hearing "green circle" learns nothing it will not hear
 * properly forty pixels further down. And the dot is never the only signal — if the corner dot were
 * the product's way of saying a cluster is unreachable, roughly one man in twelve could not read it
 * and nobody using a screen reader could read it at all.
 *
 * ## The tagline never changes
 *
 * "for humans, mostly" is part of the product's name block, not a status line. It is tempting to
 * reuse the space under the wordmark for something live; do not. A line that is usually a joke and
 * occasionally an alert is a line people stop reading.
 */
export type BrandBlockProps = {
  /** The selected cluster's health, or "unknown" before one has been chosen. */
  readonly health: ClusterHealth;
};

export function BrandBlock(props: BrandBlockProps) {
  return (
    <div class="kui-brand">
      <div class="kui-brand__tile">
        <Icon name="topology" size="20px" class="kui-brand__glyph" />
        <span class={["kui-brand__dot", `kui-brand__dot--${props.health}`]} aria-hidden="true" />
      </div>
      <div class="kui-brand__text">
        <span class="kui-brand__name">Kafka UI</span>
        <span class="kui-brand__tagline">for humans, mostly</span>
      </div>
    </div>
  );
}

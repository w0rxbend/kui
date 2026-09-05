/**
 * What the consumer screens are about, and every rule that is about *one value* rather than about
 * a layout.
 *
 * Everything in this file is a plain function over plain data. That is deliberate: the rules below
 * — which chip a group state gets, when a lag figure turns amber, what sentence sits under the page
 * title when two brokers are down — are the parts that are wrong in ways a screenshot cannot show,
 * so they are the parts that have to be testable without a DOM.
 *
 * The types are the shapes the screens draw, not the shapes the wire sends. `GroupSummary` below is
 * a near-copy of `GroupSummaryDto` from the generated OpenAPI client, and the near is the point: the
 * wire says `totalLag?: number`, which in TypeScript makes "absent" and "the key was there and held
 * `undefined`" the same thing, and this file needs them to be one explicit `number | null`. Mapping
 * happens at the edge, in the feature's data layer, so that a field the server renames breaks one
 * adapter rather than six components.
 */

import { MISSING, formatCount } from "@kui/kernel";
import type { PillTone, ThresholdLevel } from "@kui/kernel";

/* ------------------------------------------------------------------------------------------ */
/* Group state                                                                                  */
/* ------------------------------------------------------------------------------------------ */

/**
 * A consumer group's lifecycle state, as Kafka reports it.
 *
 * `Unreadable` is not one of Kafka's. It is KUI's word for "we asked and could not find out", and it
 * exists because conflating it with Kafka's own `UNKNOWN` hides an outage: `UNKNOWN` is a fact the
 * coordinator told us, and `Unreadable` is the coordinator not answering.
 */
export type GroupState =
  | "STABLE"
  | "EMPTY"
  | "DEAD"
  | "PREPARING_REBALANCE"
  | "COMPLETING_REBALANCE"
  | "UNKNOWN";

export interface StateChip {
  /** What the chip says. Both rebalancing states say `Rebalancing`; the distinction is not one an
   *  operator acts on, and two chips a letter apart in a scanned column read as noise. */
  readonly label: string;
  readonly tone: PillTone;
  /** Present when the word alone under-explains. Rendered as a `title`, never as the only source. */
  readonly title?: string;
}

/**
 * The state-to-chip mapping, written once (SPEC §4.17) so that six screens cannot each invent it.
 *
 * `Empty` is neutral rather than a warning on purpose: a group with no members is the normal resting
 * state of a batch job that runs nightly, and colouring it amber would train operators to ignore
 * amber on this column — which is the column where amber has to mean something.
 */
export function stateChip(state: GroupState): StateChip {
  switch (state) {
    case "STABLE":
      return { label: "Stable", tone: "success" };
    case "PREPARING_REBALANCE":
      return { label: "Rebalancing", tone: "warning", title: "Preparing rebalance" };
    case "COMPLETING_REBALANCE":
      return { label: "Rebalancing", tone: "warning", title: "Completing rebalance" };
    case "EMPTY":
      return {
        label: "Empty",
        tone: "neutral",
        title: "The group holds offsets but has no members. Normal for a job that runs on a schedule.",
      };
    case "DEAD":
      return { label: "Dead", tone: "danger", title: "The group has no offsets and no members." };
    case "UNKNOWN":
      return { label: "Unknown", tone: "neutral", title: "The coordinator reported an unknown state." };
  }
}

/**
 * The chip for a group whose state could not be read at all.
 *
 * An em dash, not the word `Unknown`. See the note on `GroupState`: the two look alike and mean
 * opposite things, and this is the one place the difference is drawn.
 */
export const UNREADABLE_STATE_CHIP: StateChip = {
  label: MISSING,
  tone: "neutral",
  title: "KUI could not read this group's state.",
};

/* ------------------------------------------------------------------------------------------ */
/* Lag                                                                                          */
/* ------------------------------------------------------------------------------------------ */

/**
 * Where a lag figure stops being ordinary, and where it starts being an incident.
 *
 * The numbers are fixed here rather than configured, and they are anchored to what screenshot `04`
 * draws: `333` is printed in the ordinary text colour and `3,861` is amber, so the boundary is
 * somewhere between them, and a round thousand is the only defensible place to put it.
 *
 * A hundred thousand for critical is a judgement, and it is the weaker of the two. It is documented
 * here rather than buried so that the first operator who disagrees can find it in one grep — and so
 * that when it becomes a per-topic setting, it becomes one in a single place.
 */
export const LAG_WARN_ABOVE = 1_000;
export const LAG_CRITICAL_ABOVE = 100_000;

/**
 * The three levels, and not five (SPEC §4.18).
 *
 * An operator scanning a column has to sort each cell into "fine", "look at this" and "act on this"
 * in the time it takes to scroll past. A scale with more steps is read as a gradient, which is to
 * say as nothing.
 */
export function lagLevel(lag: number): ThresholdLevel {
  if (lag > LAG_CRITICAL_ABOVE) return "critical";
  if (lag > LAG_WARN_ABOVE) return "warning";
  return "normal";
}

/** What a screen reader hears for a lag cell that is not ordinary. */
export function lagAnnouncement(level: ThresholdLevel): string {
  if (level === "critical") return "critical lag";
  if (level === "warning") return "high lag";
  return "";
}

/* ------------------------------------------------------------------------------------------ */
/* The rows themselves                                                                          */
/* ------------------------------------------------------------------------------------------ */

/**
 * Which parts of a group's row KUI could not read, and the sentence that says so.
 *
 * A group whose offsets were readable but whose member list was not still has a row worth showing;
 * what it must not have is a member count of `0` printed as if it were a fact.
 */
export interface Incomplete {
  readonly note: string;
  readonly offsetsKnown: boolean;
  readonly membersKnown: boolean;
  readonly endOffsetsKnown: boolean;
}

/** One consumer group, as the list draws it. */
export interface GroupSummary {
  readonly groupId: string;
  readonly state: GroupState | null;
  /** `null` when the member list could not be read. Never rendered as `0`. */
  readonly members: number | null;
  readonly topics: number;
  /** The coordinating broker, as `host:port`. `null` when no coordinator answered. */
  readonly coordinator: string | null;
  /** `null` when the lag could not be computed. The most expensive `0` on this screen. */
  readonly totalLag: number | null;
  /** Partitions left out of `totalLag` because their end offsets were unreadable. */
  readonly excludedPartitions: number;
  readonly incomplete: Incomplete | null;
}

/* ------------------------------------------------------------------------------------------ */
/* The voice                                                                                    */
/* ------------------------------------------------------------------------------------------ */

/**
 * What the list knows about itself, for the sentence under the title.
 *
 * A discriminated union rather than a template with an optional suffix, because SPEC §6.3 rule 3 is
 * that the aside is *dropped* the moment the state is not healthy, and a template with an optional
 * suffix is a shape in which somebody can leave the joke attached to the failure branch. Here they
 * cannot: each branch writes its whole sentence.
 */
export type GroupsHealth =
  | { readonly kind: "healthy"; readonly total: number; readonly rebalancing: number }
  | { readonly kind: "lagging"; readonly total: number; readonly behind: number }
  | { readonly kind: "incomplete"; readonly total: number; readonly coordinatorsMissing: number }
  | { readonly kind: "unavailable" };

/**
 * The line under "Consumer groups".
 *
 * The screenshot's line — *"14 groups. One is rebalancing again. We don't judge."* — is the healthy
 * branch and only the healthy branch. Delete the aside from any of these and the operator loses
 * nothing, which is SPEC §6.2's test for whether it was an aside at all.
 */
export function groupsVoice(health: GroupsHealth): string {
  switch (health.kind) {
    case "unavailable":
      return "Consumer group data is unavailable.";
    case "incomplete":
      return `${formatCount(health.total)} ${plural(health.total, "group", "groups")}. ${formatCount(
        health.coordinatorsMissing,
      )} ${plural(health.coordinatorsMissing, "coordinator", "coordinators")} did not answer, so some rows are incomplete.`;
    case "lagging":
      return `${formatCount(health.total)} ${plural(health.total, "group", "groups")}. ${formatCount(
        health.behind,
      )} ${health.behind === 1 ? "is" : "are"} more than ${formatCount(LAG_WARN_ABOVE)} records behind.`;
    case "healthy":
      if (health.rebalancing === 0) {
        return `${formatCount(health.total)} ${plural(health.total, "group", "groups")}. Nothing is rebalancing. Rare, and welcome.`;
      }
      if (health.rebalancing === 1) {
        return `${formatCount(health.total)} ${plural(health.total, "group", "groups")}. One is rebalancing again. We don't judge.`;
      }
      return `${formatCount(health.total)} ${plural(health.total, "group", "groups")}. ${formatCount(
        health.rebalancing,
      )} are rebalancing. We don't judge.`;
  }
}

/**
 * Reads the health of a page of rows off the rows themselves.
 *
 * Order matters and is the whole rule: a page that is both rebalancing and badly behind is
 * described as behind, because that is the one an operator has to do something about. The cheerful
 * branch is reachable only when nothing else is true.
 */
export function healthOf(rows: readonly GroupSummary[], coordinatorsMissing: number): GroupsHealth {
  if (coordinatorsMissing > 0) return { kind: "incomplete", total: rows.length, coordinatorsMissing };
  const behind = rows.filter((row) => row.totalLag !== null && row.totalLag > LAG_WARN_ABOVE).length;
  if (behind > 0) return { kind: "lagging", total: rows.length, behind };
  const rebalancing = rows.filter(
    (row) => row.state === "PREPARING_REBALANCE" || row.state === "COMPLETING_REBALANCE",
  ).length;
  return { kind: "healthy", total: rows.length, rebalancing };
}

export function plural(count: number, one: string, many: string): string {
  return count === 1 ? one : many;
}

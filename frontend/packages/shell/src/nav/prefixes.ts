/**
 * A topic name list as the drawer's tree: prefix groups, and one `internal` group at the foot.
 *
 * ## Why the drawer cannot just list the topics
 *
 * `SCREENS-V4.md` §2.2 draws the Topics row expanded, with two starred favourites by exact name and
 * then eight grouped rows — `orders.* 3`, `analytics.* 3`, `internal 4`. The captures were taken
 * against a cluster with 128 topics, which is small. A real one has thousands, and a drawer that
 * listed them would be a scroll region with the storage meter pushed off the bottom of it.
 *
 * So the tree summarises, and the summary is a *fold over names* rather than anything the server
 * computes. That is deliberate: it needs no endpoint, it works from the names-only index M5 adds,
 * and — because it is a pure function of a list of strings — the rules below can be argued about in
 * a test instead of in a running cluster.
 *
 * ## The two rules that are not obvious
 *
 * **The number of rows is capped, and the remainder is still counted.** A cluster whose four
 * thousand topics share no prefix would otherwise produce four thousand rows, which is the listing
 * this module exists to avoid, arrived at the long way round. The groups that survive are the
 * largest ones, because those are the ones a summary is for; everything else is folded into a
 * single `other` row that carries its own total. The row is kept rather than dropped so the figures
 * still add up — a tree whose children sum to less than the `128` on its parent is a tree somebody
 * will spend an afternoon reconciling.
 *
 * **`internal` is a group, not a prefix.** Kafka's own bookkeeping topics and KUI's metadata topics
 * are not interesting to an operator looking for their data, and they all begin with an underscore.
 * They come out as one padlocked row at the foot, whatever their prefixes are, so that
 * `__consumer_offsets` and `__transaction_state` do not each claim a row above `orders.*`.
 *
 * ## Shared, on purpose
 *
 * The storage card attributes disk to prefixes with exactly this fold (`SCREENS-V4.md` §4.3), so it
 * lives here and is exported rather than being written a second time against the same names. Two
 * implementations of "which group is this topic in" that disagree would put a topic in `orders.*`
 * in one place and in `other` in another, and neither screen would look wrong on its own.
 */

/** One row of the tree: the words on it, and how many topics it stands for. */
export interface PrefixGroup {
  /** `orders.*`, `internal`, `other` — already written the way the row shows it. */
  readonly prefix: string;
  readonly count: number;
}

/**
 * How many prefix rows the tree may hold, before `other` and `internal`.
 *
 * Eight because that is what §2.2 draws, and because the drawer's height is the real constraint:
 * the tree sits between the Topics row and the Consumers row, and the storage meter has to stay
 * visible at the foot on a laptop screen. A cap that is a product decision belongs beside the
 * fold it bounds, not at the four call sites that would each pick their own.
 */
export const MAX_PREFIX_GROUPS = 8;

/** The row every internal topic is counted under, whatever its own prefix is. */
export const INTERNAL_GROUP = "internal";

/** The row that carries the groups the cap left out, so the counts still add up. */
export const OTHER_GROUP = "other";

/**
 * Kafka's and KUI's own topics, which the tree keeps out of the way.
 *
 * The leading underscore is the whole test, and it is the convention rather than a list: Kafka's
 * `__consumer_offsets` and `__transaction_state`, Confluent's `_schemas`, and KUI's own metadata
 * topics all follow it, and a hard-coded list would silently promote the next one to a row of its
 * own. A user topic that begins with an underscore is filed here too — which is the right answer,
 * because it is a topic that has adopted the convention for being uninteresting.
 */
export function isInternalTopic(name: string): boolean {
  return name.startsWith("_");
}

/**
 * The tree's rows, largest group first, with `other` and then `internal` at the foot.
 *
 * Ties are broken alphabetically rather than by input order, so two clusters that hold the same
 * topics draw the same drawer and a refetch that returns them in a different order does not
 * reshuffle the rows under the reader's cursor — the same argument `navigation.ts` makes about
 * entries that move when a service goes down.
 */
export function prefixes(
  names: readonly string[],
  maxGroups: number = MAX_PREFIX_GROUPS,
): readonly PrefixGroup[] {
  let internal = 0;
  /** Each ordinary group's members, kept so the row's words can be decided once at the end. */
  const grouped = new Map<string, string[]>();

  for (const name of names) {
    if (name.length === 0) continue;
    if (isInternalTopic(name)) {
      internal += 1;
      continue;
    }
    const segment = firstSegment(name);
    const members = grouped.get(segment);
    if (members === undefined) grouped.set(segment, [name]);
    else members.push(name);
  }

  const ordered = [...grouped]
    .map(([segment, members]) => ({ prefix: labelFor(segment, members), count: members.length }))
    .sort((a, b) => b.count - a.count || a.prefix.localeCompare(b.prefix));

  const kept = maxGroups > 0 ? ordered.slice(0, maxGroups) : [];
  const dropped = ordered.slice(kept.length);
  const remainder = dropped.reduce((sum, group) => sum + group.count, 0);

  return [
    ...kept,
    ...(remainder > 0 ? [{ prefix: OTHER_GROUP, count: remainder }] : []),
    ...(internal > 0 ? [{ prefix: INTERNAL_GROUP, count: internal }] : []),
  ];
}

/**
 * Everything before the first separator — `orders` from `orders.payments.v2`.
 *
 * The dot only. Hyphens and underscores appear *inside* the segment names this product's users
 * actually have (`orders-eu.payments`, `click_stream.raw`), so splitting on them would file
 * `orders-eu.payments` and `orders-us.payments` under one `orders` row and claim a relationship
 * between two topics that share nothing but three letters.
 */
function firstSegment(name: string): string {
  const dot = name.indexOf(".");
  return dot === -1 ? name : name.slice(0, dot);
}

/**
 * What the row says: `orders.*` for a genuine prefix, the bare name for a topic that has none.
 *
 * `heartbeats.*` over a single topic called `heartbeats` claims children it does not have, and an
 * operator who expands it to find them has been sent somewhere for nothing. A group is only written
 * as a prefix when at least one of its members really continues past the segment.
 */
function labelFor(segment: string, members: readonly string[]): string {
  return members.some((name) => name.length > segment.length) ? `${segment}.*` : segment;
}

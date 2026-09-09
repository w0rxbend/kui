package kui.alerts.domain

/** How loud an event is. Two values, and the reason there are two rather than four is the whole of §3.9.
  *
  * `SCREENS-V4.md` §3.8 draws four severity dots — `primary`, `success`, `warning`, `danger`. Two of them are
  * not severities:
  *
  *   - the **success** dot is a row that has been resolved (`inventory.stock.events p7 back in sync` in
  *     `M01`). A resolved event is drawn in the success tone whatever it opened at, because the row is
  *     telling the reader it is over, so resolution chooses that tone and severity never does;
  *   - the **primary** dot is an informational row, and **no rule this service ships opens one**. A case
  *     nothing can open is a case a screen has to guess a meaning for, so it is not here. M9's connector-task
  *     rule is the first candidate for it and should add it with its first caller.
  *
  * What is left is the two severities the four shipped rules actually open, and each maps to exactly one
  * design tone. ADR-053 §3 is where this is argued; `AlertSeveritySuite` is where it is asserted.
  */
enum AlertSeverity(val wire: String, val tone: String) {

  /** Worth mentioning on the next working day. Drawn `--kui-color-warning`. */
  case Warning extends AlertSeverity("warning", "warning")

  /** Worth waking somebody. Drawn `--kui-color-danger`. */
  case Critical extends AlertSeverity("critical", "danger")
}

object AlertSeverity {

  val All: List[AlertSeverity] = values.toList

  def fromWire(raw: String): Option[AlertSeverity] = All.find(_.wire == raw.trim.toLowerCase)

  given CanEqual[AlertSeverity, AlertSeverity] = CanEqual.derived
}

/** What the event is *about*, which is a different question from how loud it is.
  *
  * `SCREENS-V4.md` §3.9 is a correction and this enum is the thing being corrected: the notifications panel
  * draws two `warning` items with **different** glyphs — a rebalance arrow and a disk — and the shipped
  * component picked the glyph from the severity, so it could not draw the capture it was drawn from. Severity
  * chooses the tone; category chooses the glyph; they are two fields.
  *
  * `glyph` is a name and not a character on purpose. Which shape is drawn is the browser's decision and the
  * icon set is the browser's; what the server owes is a stable word to key on, so that a new category cannot
  * silently fall back to whatever the icon lookup answers for an unknown key.
  */
enum AlertCategory(val wire: String, val glyph: String) {

  /** A partition has no leader. */
  case Partition extends AlertCategory("partition", "partition")

  /** A partition has fewer in-sync replicas than replicas. */
  case Replication extends AlertCategory("replication", "replication")

  /** A consumer group is not making progress because it is reassigning. */
  case Rebalance extends AlertCategory("rebalance", "rebalance")

  /** A log directory is filling up. */
  case Storage extends AlertCategory("storage", "storage")
}

object AlertCategory {

  val All: List[AlertCategory] = values.toList

  def fromWire(raw: String): Option[AlertCategory] = All.find(_.wire == raw.trim.toLowerCase)

  given CanEqual[AlertCategory, AlertCategory] = CanEqual.derived
}

/** The rules that ship, named, because a rules engine that half exists is indistinguishable from one that is
  * broken.
  *
  * Each entry is a rule that reads a fact **this product already measures** through an `AdminClient` call it
  * already makes somewhere. ADR-053 §2 lists what is deliberately *not* here and why:
  *
  *   - a failed connector task needs `services/connect`, which is M9's;
  *   - a schema registration needs the registry's own event feed, which the Confluent API does not publish —
  *     it would have to be polled, and a poll cannot tell a registration from a restart;
  *   - a broker that left the cluster is not a rule because the topology snapshot is already the screen for
  *     it, and an alert that repeats a tile is noise.
  *
  * The category and the severity a rule opens at are properties of the rule rather than of the event, so that
  * two events from one rule cannot disagree about which glyph they draw. The disk rule is the one exception
  * and it is a deliberate one: it opens at two severities, which is what its two thresholds mean.
  */
enum AlertRule(val wire: String, val category: AlertCategory) {

  case OfflinePartitions extends AlertRule("offline-partitions", AlertCategory.Partition)

  case UnderReplicatedPartitions extends AlertRule("under-replicated-partitions", AlertCategory.Replication)

  case StuckRebalance extends AlertRule("stuck-rebalance", AlertCategory.Rebalance)

  case DiskUsage extends AlertRule("disk-usage", AlertCategory.Storage)
}

object AlertRule {

  /** Every rule, in the order the feed reports them. It is contract: the browser draws one row per rule in
    * this order, so a rule appearing in the middle of the list is a row moving on a screen.
    */
  val All: List[AlertRule] = values.toList

  def fromWire(raw: String): Option[AlertRule] = All.find(_.wire == raw.trim.toLowerCase)

  given CanEqual[AlertRule, AlertRule] = CanEqual.derived
}

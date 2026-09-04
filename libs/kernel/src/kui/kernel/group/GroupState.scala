package kui.kernel.group

import kui.kernel.ValidationError

/** The lifecycle state a broker reports for a consumer group.
  *
  * `Unknown` is a member rather than an error: `ConsumerGroupListing.state()` is an `Optional`, and a broker
  * older than 2.6 — or one that could not be reached — simply does not say
  * (`research/kafka/admin-capabilities.md` §3). Rendering that as `DEAD` would be a lie about a group that
  * may be perfectly healthy.
  *
  * `description` is the operator-facing sentence the UI shows in the state chip's tooltip. It is a
  * constructor parameter for the same reason `ErrorCode.description` is: a new state cannot be added without
  * one.
  *
  * The `wire` string is the JSON value, the `state=` query-parameter value and the CSS modifier suffix — one
  * declaration, three uses, so that renaming a case is a deliberate contract change rather than a drift
  * between four modules that each spelled `STABLE` themselves (DEVPLAN §10 D1).
  */
enum GroupState(val wire: String, val description: String) {
  case Stable extends GroupState("STABLE", "Consumers are consuming and have assigned partitions.")
  case Empty extends GroupState("EMPTY", "The group exists but has no members.")
  case Dead
      extends GroupState(
        "DEAD",
        "The group has no members and no committed offsets, or it is being removed."
      )
  case PreparingRebalance
      extends GroupState(
        "PREPARING_REBALANCE",
        "Something changed and the partitions are about to be reassigned."
      )
  case CompletingRebalance
      extends GroupState("COMPLETING_REBALANCE", "Partition reassignment is in progress.")
  case Unknown extends GroupState("UNKNOWN", "The broker did not report a state for this group.")
}

object GroupState {

  /** Every state, in declaration order, which is also the order the UI lists its filter chips in. */
  val All: List[GroupState] = values.toList

  /** Reads a state back from a request or a stored document.
    *
    * A `ValidationError` rather than `Unknown` for an unrecognised name: `Unknown` means "the broker did not
    * say", and answering a typo in a `state=` filter with "the broker did not say" would silently return the
    * wrong page instead of a 400 naming the parameter.
    */
  def from(wire: String): Either[ValidationError, GroupState] =
    All.find(_.wire == wire) match {
      case Some(state) => Right(state)
      case None =>
        Left(ValidationError.Format("state", s"one of ${All.map(_.wire).mkString(", ")}", wire))
    }

  /** True when an offset operation is allowed for a group in this state.
    *
    * State alone is not sufficient — DEVPLAN §10 D4 requires the member list to be empty too, because a group
    * can report `Empty` while a member is joining — but a state outside this set is an immediate refusal, and
    * both the planner and the guard read the rule from here rather than restating it.
    */
  def permitsOffsetChange(state: GroupState): Boolean = state match {
    case Empty | Dead => true
    case Stable | PreparingRebalance | CompletingRebalance | Unknown => false
  }

  /** Declaration order: `Stable` first, `Unknown` last, which is the order an operator scans a list in. */
  given Ordering[GroupState] = Ordering.by(All.indexOf(_))

  given CanEqual[GroupState, GroupState] = CanEqual.derived
}

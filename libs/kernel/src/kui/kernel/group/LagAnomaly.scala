package kui.kernel.group

import kui.kernel.ValidationError

/** Why a partition's lag is not a plain number (DEVPLAN §10 D6).
  *
  * Lag is never fabricated: a partition KUI cannot compute a lag for carries one of these instead of a zero,
  * contributes nothing to the group's total, and renders as a dash. Substituting `0` — which the reference
  * product does — turns "we do not know" into "you are perfectly caught up", which is the one wrong answer a
  * capacity decision must not be made from.
  */
enum LagAnomaly(val wire: String, val description: String) {
  case NoCommit
      extends LagAnomaly(
        "NO_COMMIT",
        "This group has never committed an offset for this partition."
      )
  case CommittedBeyondEnd
      extends LagAnomaly(
        "COMMITTED_BEYOND_END",
        "The committed offset is past the end of the log; the lag cannot be computed."
      )
  case CommittedBeforeStart
      extends LagAnomaly(
        "COMMITTED_BEFORE_START",
        "The committed offset is older than the earliest record still retained; the consumer will resume from the earliest one."
      )
  case NoLeader
      extends LagAnomaly(
        "NO_LEADER",
        "This partition has no leader, so its end offset could not be read."
      )
}

object LagAnomaly {

  val All: List[LagAnomaly] = values.toList

  def from(wire: String): Either[ValidationError, LagAnomaly] =
    All.find(_.wire == wire) match {
      case Some(anomaly) => Right(anomaly)
      case None =>
        Left(ValidationError.Format("anomaly", s"one of ${All.map(_.wire).mkString(", ")}", wire))
    }

  given CanEqual[LagAnomaly, LagAnomaly] = CanEqual.derived
}

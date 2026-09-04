package kui.kernel.group

import kui.kernel.ValidationError

/** The *name* of a reset mode, as it appears in a request and on a radio button.
  *
  * The parameterised mode — the timestamp, the offset map, the shift — is `ResetSpec` in the consumer domain
  * (GRP-012). This enum is only the closed set of names, because that set is what the browser, the contract
  * and the planner must agree on. Splitting them this way is what lets `libs/kernel` stay free of
  * `Instant`-carrying request shapes, and lets the wizard render its six radios from `ResetTarget.values`
  * rather than from a hand-written list that can fall behind.
  */
enum ResetTarget(val wire: String, val label: String) {
  case Earliest extends ResetTarget("EARLIEST", "The beginning of each partition")
  case Latest extends ResetTarget("LATEST", "The end of each partition")
  case Timestamp extends ResetTarget("TIMESTAMP", "The first record at or after a point in time")
  case Offset extends ResetTarget("OFFSET", "An offset you give, per partition")
  case ShiftBy extends ResetTarget("SHIFT_BY", "A number of records forward or back from where it is now")
  case Duration extends ResetTarget("DURATION", "A period of time back from now")
}

object ResetTarget {

  val All: List[ResetTarget] = values.toList

  def from(wire: String): Either[ValidationError, ResetTarget] =
    All.find(_.wire == wire) match {
      case Some(target) => Right(target)
      case None =>
        Left(ValidationError.Format("target", s"one of ${All.map(_.wire).mkString(", ")}", wire))
    }

  given CanEqual[ResetTarget, ResetTarget] = CanEqual.derived
}

package kui.kernel.group

import kui.kernel.ValidationError

/** Which group protocol a member speaks.
  *
  * Classic members report their assignment through `assignment()`; KIP-848 consumer-protocol members report
  * it through `targetAssignment()`. A port that reads only one shows an empty member list for the other, so
  * the protocol is a field on the member and both are read (DEVPLAN risk R-10).
  */
enum GroupProtocol(val wire: String) {
  case Classic extends GroupProtocol("CLASSIC")
  case Consumer extends GroupProtocol("CONSUMER")
  case Unknown extends GroupProtocol("UNKNOWN")
}

object GroupProtocol {

  val All: List[GroupProtocol] = values.toList

  def from(wire: String): Either[ValidationError, GroupProtocol] =
    All.find(_.wire == wire) match {
      case Some(protocol) => Right(protocol)
      case None =>
        Left(ValidationError.Format("protocol", s"one of ${All.map(_.wire).mkString(", ")}", wire))
    }

  given CanEqual[GroupProtocol, GroupProtocol] = CanEqual.derived
}

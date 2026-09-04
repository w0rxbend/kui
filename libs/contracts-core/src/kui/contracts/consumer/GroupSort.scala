package kui.contracts.consumer

import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.{Codec as TapirCodec, DecodeResult, Schema}

import kui.contracts.KernelDecodeFailure
import kui.kernel.ValidationError

/** What the consumer-group list can be sorted by.
  *
  * An enum rather than a `String` because `libs/kernel`'s `Sort[Field]` is parameterised by the field type
  * exactly so that an unknown sort field is refused at the edge — a 400 naming the parameter — instead of
  * being silently ignored and answering with a list in some other order than the one the user asked for. A
  * quietly ignored sort parameter is the kind of defect nobody reports because it looks like a preference.
  *
  * It lives in `libs/contracts-core` and not in the consumer service's contract because it is a wire
  * vocabulary, and build rule A14 (ADR-041 Amendment 4) allows exactly two homes for one: `libs/kernel` and
  * this module. `libs/kernel` would be the wrong one — it has no idea this list exists — so it is this one.
  */
enum GroupSortField(val wire: String) {
  case Id extends GroupSortField("id")
  case Members extends GroupSortField("members")
  case Topics extends GroupSortField("topics")
  case Lag extends GroupSortField("lag")
  case State extends GroupSortField("state")
}

object GroupSortField {

  val All: List[GroupSortField] = values.toList

  /** The default, and the only one that is a total order over distinct groups: two groups can tie on lag or
    * on member count, and a list whose order changes between two identical requests makes paging skip rows.
    */
  val Default: GroupSortField = Id

  def from(wire: String): Either[ValidationError, GroupSortField] =
    All.find(_.wire == wire) match {
      case Some(field) => Right(field)
      case None =>
        Left(ValidationError.Format("sort", s"one of ${All.map(_.wire).mkString(", ")}", wire))
    }

  given TapirCodec[String, GroupSortField, TextPlain] =
    TapirCodec.string.mapDecode(raw =>
      from(raw) match {
        case Right(field) => DecodeResult.Value(field)
        case Left(error) => DecodeResult.Error(raw, KernelDecodeFailure(error))
      }
    )(_.wire)

  given Schema[GroupSortField] =
    Schema.string[GroupSortField].description(s"Sort by one of: ${All.map(_.wire).mkString(", ")}")

  given CanEqual[GroupSortField, GroupSortField] = CanEqual.derived
}

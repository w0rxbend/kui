package kui.ui.messages.browse

import sttp.tapir.DecodeResult

import kui.kernel.browse.SeekMode
import kui.kernel.serde.SerdeName
import kui.kernel.{Offset, PartitionId}
import kui.message.contract.{BrowseAddress, BrowseParams}

/** Everything one browse asks for, as one value.
  *
  * It is the browser's mirror of what the service reads, and it holds only what a *person* chooses on the
  * screen: where to start, which partitions, how many records, what text they must contain, how to decode the
  * two halves of a record, and whether to keep following.
  *
  * The isolation level is deliberately absent, and the serdes deliberately are not. The service already
  * chooses a serde per topic and says which it used on every record, and for the overwhelming majority of
  * topics that choice is right — which is why both are `None` by default and the controls read "Automatic".
  * They exist for the topic where it is wrong: a key written as a big-endian long that autodetection reads as
  * a four-character string, or a value the producer double-encoded. Without an override the only way to read
  * such a topic is to change the deployment's configuration, which an operator investigating an incident
  * cannot do.
  *
  * @param live
  *   tail mode. It is exclusive with a start position and the *server* refuses the combination, so this
  *   screen never sends both: pressing Follow sets the seek to the end, which is what following means.
  */
final case class BrowseQuery(
    seek: SeekMode,
    partitions: List[PartitionId],
    limit: Option[Int],
    contains: Option[String],
    keySerde: Option[SerdeName],
    valueSerde: Option[SerdeName],
    live: Boolean
)

object BrowseQuery {

  /** What the screen starts on: the newest records, read backwards.
    *
    * The end and not the beginning, because the overwhelmingly common question about a topic is "what is
    * happening now", and a topic with a million records answers "start at the beginning" with a million
    * records nobody wanted. It is also what every reference product does, and an operator's fingers already
    * expect it.
    */
  val Default: BrowseQuery =
    BrowseQuery(
      seek = SeekMode.Latest,
      partitions = Nil,
      limit = None,
      contains = None,
      keySerde = None,
      valueSerde = None,
      live = false
    )

  given CanEqual[BrowseQuery, BrowseQuery] = CanEqual.derived

  /** The query string this browse becomes, without the leading `?`.
    *
    * Every parameter name comes from `BrowseAddress` and every value from `BrowseParams`, both in the
    * contract module the service compiles too. Nothing here spells a name or a value itself: a `seekTo`
    * renamed on the server, or a per-partition seek respelled, stops this compiling instead of producing a
    * request the server answers with a 400 — which is what a browser that had typed the strings a second time
    * would do, and which nothing on either side would catch.
    */
  def queryString(query: BrowseQuery): String = {
    val seekValues = BrowseParams.seekModeCodec.encode(query.seek).map(BrowseAddress.SeekParam -> _)

    val partitionValues =
      query.partitions
        .sortBy(_.value)
        .map(partition => BrowseAddress.PartitionParam -> partition.value.toString)

    val rest =
      List(
        query.limit.map(BrowseAddress.LimitParam -> _.toString),
        query.contains.map(BrowseAddress.QueryParam -> _),
        // Sent only when the user overrode it. Absent means "let the service choose", which is what it
        // does anyway, and a URL that spells out every default is one a person cannot read.
        query.keySerde.map(BrowseAddress.KeySerdeParam -> _.value),
        query.valueSerde.map(BrowseAddress.ValueSerdeParam -> _.value),
        // Sent only when it is on. `live=false` and no `live` at all mean the same thing to the server, and
        // the shorter URL is the one a person can read.
        Option.when(query.live)(BrowseAddress.LiveParam -> "true")
      ).flatten

    (seekValues ++ partitionValues ++ rest)
      .map((name, value) => s"${encode(name)}=${encode(value)}")
      .mkString("&")
  }

  /** Reads a browse back out of a URL's parameters.
    *
    * Lenient throughout, and deliberately: these values come from a link somebody was sent, and a parameter
    * that no longer parses should cost the recipient that one setting rather than the whole screen. An
    * unreadable seek falls back to the default, which is a screen that works.
    */
  def fromParams(params: Map[String, List[String]]): BrowseQuery = {
    // A `DecodeResult` and not an `Option`, matched rather than flattened: everything that is not a value
    // — missing, malformed, several forms mixed — is the same answer here, which is "use the default and
    // show the user a screen that works".
    val seek =
      BrowseParams.seekModeCodec.decode(params.getOrElse(BrowseAddress.SeekParam, Nil)) match {
        case DecodeResult.Value(mode) => mode
        case _ => Default.seek
      }

    val partitions =
      params
        .getOrElse(BrowseAddress.PartitionParam, Nil)
        .flatMap(_.split(',').toList)
        .flatMap(raw => raw.trim.toIntOption)
        .filter(_ >= 0)
        .map(PartitionId.unsafe)
        .distinct

    BrowseQuery(
      seek = seek,
      partitions = partitions,
      limit = params.getOrElse(BrowseAddress.LimitParam, Nil).headOption.flatMap(_.toIntOption).filter(_ > 0),
      contains = params.getOrElse(BrowseAddress.QueryParam, Nil).headOption.map(_.trim).filter(_.nonEmpty),
      keySerde = serdeIn(params, BrowseAddress.KeySerdeParam),
      valueSerde = serdeIn(params, BrowseAddress.ValueSerdeParam),
      live = params.getOrElse(BrowseAddress.LiveParam, Nil).headOption.contains("true")
    )
  }

  /** A serde name from a URL, or none.
    *
    * A name that will not parse is dropped rather than fatal, exactly as an unreadable seek is: the value
    * came from a link somebody was sent, and it should cost the recipient that one setting rather than the
    * whole screen. Dropping it means the service chooses, which is the behaviour with no override at all.
    */
  private def serdeIn(params: Map[String, List[String]], name: String): Option[SerdeName] =
    params
      .getOrElse(name, Nil)
      .headOption
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(SerdeName.fromString(_).toOption)

  /** The four ways a person can say where to start, as the screen offers them.
    *
    * `AtOffsets` — a different offset per partition — is not offered as a control and is not lost either: it
    * arrives from a URL, round-trips through it, and the offset field shows the offset it names when they all
    * agree. A control for it would be a table of sixty inputs; a link carrying one is how a colleague hands
    * over exactly where they were.
    */
  def startKind(seek: SeekMode): String =
    seek match {
      case SeekMode.Beginning => "beginning"
      case SeekMode.Latest => "latest"
      case SeekMode.AtOffset(_) => "offset"
      case SeekMode.AtTimestamp(_) => "timestamp"
      case SeekMode.AtOffsets(_) => "offset"
    }

  /** The offset an offset-shaped seek names, for the input box to show. */
  def offsetOf(seek: SeekMode): Option[Long] =
    seek match {
      case SeekMode.AtOffset(offset) => Some(offset.value)
      // Every partition at the same offset is expressible in the box; a mixture is not, and showing one of
      // them would silently discard the others the moment the user pressed Read.
      case SeekMode.AtOffsets(perPartition) =>
        perPartition.values.map(_.value).toList.distinct match {
          case single :: Nil => Some(single)
          case _ => None
        }
      case _ => None
    }

  def timestampOf(seek: SeekMode): Option[Long] =
    seek match {
      case SeekMode.AtTimestamp(millis) => Some(millis)
      case _ => None
    }

  /** Builds a seek from what the controls hold. */
  def seekFor(kind: String, offset: Option[Long], timestamp: Option[Long]): SeekMode =
    kind match {
      case "beginning" => SeekMode.Beginning
      case "offset" => SeekMode.AtOffset(Offset.unsafe(offset.getOrElse(0L)))
      case "timestamp" => SeekMode.AtTimestamp(timestamp.getOrElse(0L))
      case _ => SeekMode.Latest
    }

  /** `encodeURIComponent`, spelled out rather than reached for through `js.URIUtils` so that this file — and
    * therefore the whole URL grammar — can be tested off a browser.
    */
  private def encode(raw: String): String =
    raw.flatMap {
      case c if c.isLetterOrDigit => c.toString
      case c @ ('-' | '_' | '.' | '~') => c.toString
      case c =>
        c.toString.getBytes("UTF-8").map(byte => f"%%${byte & 0xff}%02X").mkString
    }
}

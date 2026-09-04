package kui.topic.contract

import sttp.tapir.CodecFormat.TextPlain
import sttp.tapir.{query, Codec as TapirCodec, DecodeResult, EndpointInput}

import kui.contracts.KernelDecodeFailure
import kui.kernel.search.SearchMode
import kui.kernel.{PageRequest, PageSize, PositiveInt, Sort, SortOrder, ValidationError}

/** The fields a topic list may be sorted by.
  *
  * An enum and not a `String`, so an unknown `sort` is a decode failure at the edge rather than a parameter
  * the server quietly ignores. A silently ignored sort is how a user concludes that sorting is broken: the
  * rows come back, they are simply in the wrong order, and nothing anywhere says why.
  *
  * ==Why this is declared here and not imported from the domain==
  *
  * `services/topic/domain` declares the same six fields for its own `Ordering`s (TOP-011). It cannot be
  * imported here: rule A2 forbids a contract module any edge into a domain or an application module, because
  * a contract is cross-compiled to the browser and such an import would drag the domain into the browser
  * build. Rule A1 forbids the reverse edge for a related reason — the domain sees `libs/kernel` and cats and
  * nothing else.
  *
  * The two are therefore kept in step by the one module allowed to see both, `services/topic/api`, whose
  * mapping is an exhaustive match in each direction: a field added on either side fails to compile until it
  * is added on the other. That is a deliberate seam with a compiler standing on it, not a duplication nobody
  * noticed.
  */
enum TopicSortField {
  case Name, Partitions, ReplicationFactor, OutOfSyncReplicas, Size, MessageCount

  /** The spelling the query string uses.
    *
    * Written out rather than derived from the case name, so that renaming a case is a local edit rather than
    * a silent change to a URL people have bookmarked.
    */
  def wire: String = this match {
    case Name => "name"
    case Partitions => "partitions"
    case ReplicationFactor => "replicationFactor"
    case OutOfSyncReplicas => "outOfSyncReplicas"
    case Size => "size"
    case MessageCount => "messageCount"
  }
}

object TopicSortField {

  /** Every field, in the order the list screen offers them. `TopicSortFieldSuite` asserts it is exhaustive,
    * because a field missing here would be a field the query string silently refuses.
    */
  val all: List[TopicSortField] =
    List(Name, Partitions, ReplicationFactor, OutOfSyncReplicas, Size, MessageCount)

  /** `None` for anything else — never a fall back to a default field. */
  def fromWire(raw: String): Option[TopicSortField] = all.find(_.wire == raw)

  given CanEqual[TopicSortField, TopicSortField] = CanEqual.derived
}

/** The list query, validated at the edge.
  *
  * `sort` is an `Option` and not a defaulted value, because "no sort given" is meaningful: with `mode=fts`
  * the list is ordered by relevance when — and only when — the caller did not ask for an order (ADR-038,
  * DEVPLAN §10 D4). Defaulting here would make relevance ordering unreachable and would make an explicit
  * `sort=name:asc` indistinguishable from silence.
  */
final case class TopicListParams(
    q: Option[String],
    mode: SearchMode,
    showInternal: Boolean,
    sort: Option[Sort[TopicSortField]],
    page: PageRequest
)

object TopicListParams {

  /** What a request that names no parameter at all means: the first page of 25, substring matching, no search
    * term, no sort, and internal topics hidden.
    */
  val Default: TopicListParams =
    TopicListParams(None, SearchMode.Default, showInternal = false, sort = None, page = PageRequest.Default)

  given CanEqual[TopicListParams, TopicListParams] = CanEqual.derived
}

/** The query string of the topic list, read once.
  *
  * Every codec here refuses what it does not understand instead of substituting a default. That is the single
  * rule this object exists to enforce, and it is the opposite of the reference product's behaviour, which
  * resets an out-of-range `page` to 1 and an unrecognised `mode` to substring matching without saying so
  * (`research/kafbat/api-analysis.md` §3.3). A 400 naming the parameter is a message somebody can act on; a
  * page of results produced by a different rule from the one that was asked for is not — the user cannot even
  * tell it happened.
  */
object TopicQueryCodecs {

  val QParam: String = "q"
  val ModeParam: String = "mode"
  val ShowInternalParam: String = "showInternal"
  val SortParam: String = "sort"
  val PageParam: String = "page"
  val PageSizeParam: String = "pageSize"

  /** The separator of `sort=<field>:<direction>` (ADR-026).
    *
    * One parameter rather than two. Two would admit the state "direction given, field not", and a codec would
    * then have to invent a meaning for it.
    */
  private val SortSeparator: Char = ':'

  private def refuse[A](raw: String, error: ValidationError): DecodeResult[A] =
    DecodeResult.Error(raw, KernelDecodeFailure(error))

  given searchModeCodec: TapirCodec[String, SearchMode, TextPlain] =
    TapirCodec.string.mapDecode(raw =>
      SearchMode.fromWire(raw) match {
        case Some(mode) => DecodeResult.Value(mode)
        case None => refuse(raw, ValidationError.Format(ModeParam, "'plain' or 'fts'", raw))
      }
    )(_.wire)

  private val sortExpected: String =
    s"<field>${SortSeparator}<asc|desc>, with field one of ${TopicSortField.all.map(_.wire).mkString(", ")}"

  /** `sort=name:desc`.
    *
    * Both halves must be present and both must be understood. A bare field name is refused rather than read
    * as "ascending": somebody who wrote `sort=size` meaning "biggest first" would otherwise be shown the
    * smallest topics, with nothing anywhere saying that the request was not the one that ran.
    */
  given sortCodec: TapirCodec[String, Sort[TopicSortField], TextPlain] =
    TapirCodec.string.mapDecode { raw =>
      raw.split(SortSeparator).toList match {
        case field :: direction :: Nil =>
          (TopicSortField.fromWire(field), SortOrder.fromWire(direction)) match {
            case (Some(sortField), Some(order)) => DecodeResult.Value(Sort(sortField, order))
            case _ => refuse(raw, ValidationError.Format(SortParam, sortExpected, raw))
          }
        case _ => refuse(raw, ValidationError.Format(SortParam, sortExpected, raw))
      }
    }(sort => s"${sort.field.wire}$SortSeparator${sort.order.wire}")

  /** `page` and `pageSize` are decoded as plain integers and validated here rather than through
    * `KernelSchemas`' codec for `PositiveInt`, for one reason: the field name in the 400.
    *
    * `PositiveInt.from` names its field `positiveInt`, which is the right name for the type and the wrong one
    * for the message a caller reads — `PageRequest.from` in `libs/kernel` renames it for exactly the same
    * reason. A validation detail that names a type instead of a parameter cannot be acted on.
    */
  private def pageOf(raw: Int): DecodeResult[PositiveInt] =
    PositiveInt.from(raw) match {
      case Right(value) => DecodeResult.Value(value)
      case Left(ValidationError.Range(_, min, max, got)) =>
        refuse(raw.toString, ValidationError.Range(PageParam, min, max, got))
      case Left(other) => refuse(raw.toString, other)
    }

  private def pageSizeOf(raw: Int): DecodeResult[PageSize] =
    PageSize.from(raw) match {
      case Right(value) => DecodeResult.Value(value)
      case Left(error) => refuse(raw.toString, error)
    }

  /** Combines the six decoded parameters into the query the use case is stated in terms of.
    *
    * Separate from [[listParams]] and public, because it is the half worth testing: a Tapir `EndpointInput`
    * can only be exercised through a server or client interpreter, and a validation rule that is only
    * reachable through an HTTP round trip is a validation rule nobody writes a table for.
    *
    * An empty or blank `q` becomes `None`. "Present but blank" is not a case any later code should have to
    * think about, and a user who clears the search box sends exactly that.
    */
  def decodeParams(
      q: Option[String],
      mode: SearchMode,
      showInternal: Boolean,
      sort: Option[Sort[TopicSortField]],
      page: Int,
      pageSize: Int
  ): DecodeResult[TopicListParams] =
    for {
      validPage <- pageOf(page)
      validSize <- pageSizeOf(pageSize)
    } yield TopicListParams(
      q.map(_.trim).filter(_.nonEmpty),
      mode,
      showInternal,
      sort,
      PageRequest(validPage, validSize)
    )

  /** The whole query string as one input, so that no endpoint anywhere assembles a topic list query by hand.
    *
    * The six parameters are decoded independently and then combined, and the combination is where `page` and
    * `pageSize` become a `PageRequest` — the type whose `offset` arithmetic is written once, in
    * `libs/kernel`, and clamped there against the overflow a bookmark to page 100 000 000 would otherwise
    * cause.
    */
  val listParams: EndpointInput[TopicListParams] =
    query[Option[String]](QParam)
      .description("Match topic names containing this text (mode=plain) or resembling it (mode=fts)")
      .and(
        query[SearchMode](ModeParam)
          .description("plain for substring matching, fts for trigram matching (ADR-038)")
          .default(SearchMode.Default)
      )
      .and(
        query[Boolean](ShowInternalParam)
          .description("Include Kafka's internal topics and KUI's own metadata topics")
          .default(false)
      )
      .and(
        query[Option[Sort[TopicSortField]]](SortParam)
          .description(s"$sortExpected; omit for relevance order under mode=fts and name order otherwise")
      )
      .and(query[Int](PageParam).description("Which page, numbered from one").default(1))
      .and(
        query[Int](PageSizeParam)
          .description(s"How many topics per page, 1 to ${PageSize.Max.value}")
          .default(PageSize.Default.value)
      )
      .mapDecode { case (q, mode, showInternal, sort, page, pageSize) =>
        decodeParams(q, mode, showInternal, sort, page, pageSize)
      }(params =>
        (
          params.q,
          params.mode,
          params.showInternal,
          params.sort,
          params.page.page.value,
          params.page.pageSize.value
        )
      )
}

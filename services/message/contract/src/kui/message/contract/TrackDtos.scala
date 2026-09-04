package kui.message.contract

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, DecodingFailure, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.kernel.TopicName

/** What a track is looking for, and where on a record to look for it (ET-001, ET-002).
  *
  * ==Why `source` is mandatory==
  *
  * The reference implementation lets the field be omitted, and an omitted field silently means "search the
  * whole value". So a user who selects "header" and forgets to type the header's name gets a search of every
  * value instead of an error — and the results look plausible. An omitted parameter that changes the meaning
  * of another parameter is the shape that produces wrong answers quietly, so KUI spells it out: `value`,
  * `key` or `header`, always, and `header` requires a name (DEVPLAN §10 D12).
  *
  * @param source
  *   `value`, `key` or `header`
  * @param header
  *   the header's name, required when `source` is `header` and rejected otherwise. Rejected rather than
  *   ignored, because a request that names both a header and a value search is a request whose author
  *   believed something untrue about it
  * @param operator
  *   how `value` is compared: `equals`, `contains` or `matches` (a regular expression). Three, not a full
  *   expression language — the smart filter (ADR-017) is where an arbitrary predicate belongs, and a tracker
  *   that offered both would be two filter languages in one screen
  */
final case class TrackMatchDto(source: String, header: Option[String], operator: String, value: String)

object TrackMatchDto {

  /** Where on a record to look. */
  object Source {
    val Value: String = "value"
    val Key: String = "key"
    val Header: String = "header"

    val all: List[String] = List(Value, Key, Header)
  }

  /** How to compare. */
  object Operator {
    val Equals: String = "equals"
    val Contains: String = "contains"
    val Matches: String = "matches"

    val all: List[String] = List(Equals, Contains, Matches)
  }

  given Codec[TrackMatchDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        source <- cursor.get[String]("source")
        header <- cursor.get[Option[String]]("header")
        operator <- cursor.get[String]("operator")
        value <- cursor.get[String]("value")
        checked <- validated(TrackMatchDto(source, header, operator, value), cursor)
      } yield checked,
    (dto: TrackMatchDto) =>
      Json.obj(
        "source" -> dto.source.asJson,
        "header" -> dto.header.asJson,
        "operator" -> dto.operator.asJson,
        "value" -> dto.value.asJson
      )
  )

  /** The two rules that make a match unambiguous, checked where a failure is still a 400 naming a field.
    *
    * Both are stated as refusals rather than as corrections. "Header search with no header name" has an
    * obvious-looking repair — search the value instead — and that repair is precisely the reference product's
    * defect: the search runs, finds things, and finds the wrong things.
    */
  private def validated(dto: TrackMatchDto, cursor: HCursor): Either[DecodingFailure, TrackMatchDto] =
    if !Source.all.contains(dto.source) then
      Left(DecodingFailure(s"source must be one of ${Source.all.mkString(", ")}", cursor.history))
    else if !Operator.all.contains(dto.operator) then
      Left(DecodingFailure(s"operator must be one of ${Operator.all.mkString(", ")}", cursor.history))
    else if dto.source == Source.Header && dto.header.forall(_.isEmpty) then
      Left(DecodingFailure("source 'header' requires a header name", cursor.history))
    else if dto.source != Source.Header && dto.header.isDefined then
      Left(DecodingFailure(s"a header name is meaningless with source '${dto.source}'", cursor.history))
    else Right(dto)

  given Schema[TrackMatchDto] = Schema
    .derived[TrackMatchDto]
    .description("What to look for and where: value, key or a named header, compared three ways")

  given CanEqual[TrackMatchDto, TrackMatchDto] = CanEqual.derived
}

/** One event-tracking request: find this value across these topics inside this window (ADR-029).
  *
  * The window is mandatory and both ends are required. A track with no window is a scan of every topic from
  * the beginning of time, which on a real cluster is not a search — it is an outage with a progress bar.
  *
  * @param topics
  *   the topics to search, in the order given. Empty is rejected by the service; "all topics" is not offered,
  *   because a user who means it can list them and a user who does not mean it should not be able to trigger
  *   it with an empty field
  * @param limit
  *   how many hits to return before stopping. The service caps it; the stream ends with
  *   `done{reason:"limit"}` so that a truncated result never looks like a complete one
  */
final case class TrackQueryDto(
    topics: List[TopicName],
    `match`: TrackMatchDto,
    from: Instant,
    to: Instant,
    limit: Option[Int]
)

object TrackQueryDto {

  given Codec[TrackQueryDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topics <- cursor.get[List[TopicName]]("topics")
        matcher <- cursor.get[TrackMatchDto]("match")
        from <- cursor.get[Instant]("from")
        to <- cursor.get[Instant]("to")
        limit <- cursor.get[Option[Int]]("limit")
        checked <-
          if to.isBefore(from) then Left(DecodingFailure("'to' is before 'from'", cursor.history))
          else Right(TrackQueryDto(topics, matcher, from, to, limit))
      } yield checked,
    (dto: TrackQueryDto) =>
      Json.obj(
        "topics" -> dto.topics.asJson,
        "match" -> dto.`match`.asJson,
        "from" -> dto.from.asJson,
        "to" -> dto.to.asJson,
        "limit" -> dto.limit.asJson
      )
  )

  given Schema[TrackQueryDto] = Schema
    .derived[TrackQueryDto]
    .description("Find one value across several topics inside a closed time window")

  given CanEqual[TrackQueryDto, TrackQueryDto] = CanEqual.derived
}

/** One record a track found, with the topic it was found in.
  *
  * The topic is on the hit and not on the response, because a track's whole purpose is that its results come
  * from several topics at once: the answer to "where did this order go" is the list of topics, in time order,
  * and a response that grouped by topic would throw that order away.
  */
final case class TrackHitDto(topic: TopicName, record: MessageDto)

object TrackHitDto {

  given Codec[TrackHitDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topic <- cursor.get[TopicName]("topic")
        record <- cursor.get[MessageDto]("record")
      } yield TrackHitDto(topic, record),
    (dto: TrackHitDto) =>
      Json.obj(
        "topic" -> dto.topic.asJson,
        "record" -> dto.record.asJson
      )
  )

  given Schema[TrackHitDto] =
    Schema.derived[TrackHitDto].description("One record a track found, and which topic it was in")

  given CanEqual[TrackHitDto, TrackHitDto] = CanEqual.derived
}

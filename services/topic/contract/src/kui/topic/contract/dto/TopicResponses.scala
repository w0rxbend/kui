package kui.topic.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, DecodingFailure, HCursor, Json}
import sttp.tapir.Schema

// `ErrorEnvelope` is where the house `Codec[Instant]` lives: RFC 3339 with exactly three fractional
// digits, because `Instant.toString` drops trailing zeros and the same instant would otherwise be
// written two different ways depending on whether it landed on a whole second. Without this import
// circe's own encoder is used instead, silently, and the document differs from every other KUI document.
import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.contracts.Section
import kui.contracts.paging.PageDto
import kui.contracts.topic.{PartitionDto, TopicConfigEntryDto, TopicDetailDto, TopicRowDto}
import kui.kernel.{ClusterId, TopicName}

/** One page of the topic list.
  *
  * `topics` is a [[kui.contracts.Section]] rather than a bare page, so a cluster that could not be scraped is
  * a 200 carrying a stale or unavailable section — never a 5xx, and never an empty page. An empty page from a
  * cluster that has ten thousand topics is a lie that looks like data, and it is precisely the shape of M1's
  * second integration defect: a screen that said "nothing here" while nothing anywhere reported an error
  * (DEVPLAN §10 D11).
  *
  * @param incompleteTopics
  *   how many topics the scrape could not describe. It sits **outside** the section deliberately: it is a
  *   fact about the data that *is* being shown, so it is reported for a stale section too. It is what lets
  *   the screen say "9 998 of 10 000 topics; 2 could not be read" instead of quietly showing fewer
  */
final case class TopicsResponse(topics: Section[PageDto[TopicRowDto]], incompleteTopics: Int)

object TopicsResponse {

  given Codec[TopicsResponse] = Codec.from(
    (cursor: HCursor) =>
      for {
        topics <- cursor.get[Section[PageDto[TopicRowDto]]]("topics")
        // Absent means none, and none is the overwhelmingly common case. `topics` is required, so a
        // truncated document still fails to decode rather than arriving as an empty list.
        incompleteTopics <- cursor.getOrElse[Int]("incompleteTopics")(0)
      } yield TopicsResponse(topics, incompleteTopics),
    (response: TopicsResponse) =>
      Json.obj(
        "topics" -> response.topics.asJson,
        "incompleteTopics" -> response.incompleteTopics.asJson
      )
  )

  given Schema[TopicsResponse] = Schema
    .derived[TopicsResponse]
    .description("One page of the topic list, plus how many topics could not be described")

  given CanEqual[TopicsResponse, TopicsResponse] = CanEqual.derived
}

/** One topic, with as much of its partition table as belongs in a page-load.
  *
  * @param partitionsTruncated
  *   whether `topic.data.partitions` is only the first [[TopicDetailResponse.EmbeddedPartitionLimit]] of
  *   them. A flag rather than a comparison of `partitions.size` against the limit, because a topic with
  *   exactly that many partitions is not truncated and a reader deriving the flag would say it was. The full
  *   table comes from the partitions endpoint, which is also what the screen re-fetches on refresh
  */
final case class TopicDetailResponse(topic: Section[TopicDetailDto], partitionsTruncated: Boolean)

object TopicDetailResponse {

  /** How many partitions the detail document embeds (TOP-020 decision 2).
    *
    * A topic with two thousand partitions would otherwise make a page-load document tens of times larger than
    * the screen's first paint needs, for a table that is below the fold.
    */
  val EmbeddedPartitionLimit: Int = 500

  given Codec[TopicDetailResponse] = Codec.from(
    (cursor: HCursor) =>
      for {
        topic <- cursor.get[Section[TopicDetailDto]]("topic")
        partitionsTruncated <- cursor.getOrElse[Boolean]("partitionsTruncated")(false)
      } yield TopicDetailResponse(topic, partitionsTruncated),
    (response: TopicDetailResponse) =>
      Json.obj(
        "topic" -> response.topic.asJson,
        "partitionsTruncated" -> response.partitionsTruncated.asJson
      )
  )

  given Schema[TopicDetailResponse] = Schema
    .derived[TopicDetailResponse]
    .description("One topic; partitionsTruncated says whether the embedded partition list is the whole table")

  given CanEqual[TopicDetailResponse, TopicDetailResponse] = CanEqual.derived
}

/** What the Settings tab renders.
  *
  * The two cases exist because an empty table has two meanings a screen must not conflate: the topic has no
  * configuration the broker will report, or the caller may see the topic but not its configuration. Rendering
  * the second as the first tells an operator that a topic has no settings when the truth is that they are not
  * allowed to look — and the remedy for the second is an ACL change, which they will never think of.
  *
  * It is a case of the *result*, not an error, so the rest of the topic page keeps working: making it a 403
  * would take the partitions the user is allowed to see down with it (TOP-016 decision 1).
  *
  * ==Why the wire type is declared here==
  *
  * `services/topic/application` declares the same two cases over its own entry type (TOP-016). Rule A2 keeps
  * that type out of this module and rule A3 keeps this one out of that module; `services/topic/api` maps
  * between them, which is where ADR-033 says a domain-to-wire mapping belongs.
  */
enum TopicConfigViewDto {

  /** The topic's configuration keys, sorted by name. Possibly empty, which the screen renders as "no
    * overrides" — a statement about the topic, not about the caller.
    */
  case Entries(values: List[TopicConfigEntryDto])

  /** The topic exists and the caller may not read its configuration. */
  case NotPermitted(detail: String)

  /** The `status` discriminator, contract like [[kui.contracts.Section]]'s. */
  def status: String = this match {
    case Entries(_) => "entries"
    case NotPermitted(_) => "not_permitted"
  }
}

object TopicConfigViewDto {

  given Codec[TopicConfigViewDto] = Codec.from(
    (cursor: HCursor) =>
      cursor.get[String]("status").flatMap {
        case "entries" =>
          cursor.getOrElse[List[TopicConfigEntryDto]]("values")(Nil).map(Entries(_))
        case "not_permitted" =>
          cursor.get[String]("detail").map(NotPermitted(_))
        case other =>
          Left(DecodingFailure(s"'$other' is not a topic config view status", cursor.history))
      },
    (view: TopicConfigViewDto) =>
      view match {
        case Entries(values) =>
          Json.obj("status" -> Json.fromString(view.status), "values" -> values.asJson)
        case NotPermitted(detail) =>
          Json.obj("status" -> Json.fromString(view.status), "detail" -> detail.asJson)
      }
  )

  /** Tapir cannot see inside a union of two shapes, and a schema that lies is worse than a vague one, so the
    * documented schema is an open object with its discriminator described — the same choice `Section` made.
    */
  given Schema[TopicConfigViewDto] = Schema
    .any[TopicConfigViewDto]
    .description(
      "A topic's settings: status is entries (with values) or not_permitted (with detail)"
    )

  given CanEqual[TopicConfigViewDto, TopicConfigViewDto] = CanEqual.derived
}

/** One topic's settings. */
final case class TopicConfigResponse(config: Section[TopicConfigViewDto])

object TopicConfigResponse {

  given Codec[TopicConfigResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[Section[TopicConfigViewDto]]("config").map(TopicConfigResponse(_)),
    (response: TopicConfigResponse) => Json.obj("config" -> response.config.asJson)
  )

  given Schema[TopicConfigResponse] =
    Schema.derived[TopicConfigResponse].description("One topic's configuration keys, read-only in M2")

  given CanEqual[TopicConfigResponse, TopicConfigResponse] = CanEqual.derived
}

/** Every partition of one topic — the whole table, however many there are.
  *
  * This exists separately from the detail endpoint even though the detail document embeds partitions, because
  * the two answer different questions: the detail is a page-load and the partition table is what a screen
  * re-fetches when the user presses refresh, or pages through when the topic has two thousand of them.
  */
final case class PartitionsResponse(partitions: Section[List[PartitionDto]])

object PartitionsResponse {

  given Codec[PartitionsResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[Section[List[PartitionDto]]]("partitions").map(PartitionsResponse(_)),
    (response: PartitionsResponse) => Json.obj("partitions" -> response.partitions.asJson)
  )

  given Schema[PartitionsResponse] =
    Schema.derived[PartitionsResponse].description("Every partition of one topic, with leaders and replicas")

  given CanEqual[PartitionsResponse, PartitionsResponse] = CanEqual.derived
}

/** The cluster-wide totals the topics list draws above its table (`SCREENS-V4.md` §4.6).
  *
  * They are **not** a footer of the page below them. The capture's own load-bearing fact is that the
  * statistics keep reading 128 topics while the filtered table shows three, so this is a separate document
  * over the whole snapshot; a total computed from the rows on screen would track the search box, and the
  * number an operator opens this region for is precisely the one that does not move when they type.
  *
  * @param topicCount
  *   every topic the scrape learned of, including the ones it could not describe. Not an `Option`: the count
  *   comes from the listing, and a scrape with no listing has no document at all — the section is
  *   `unavailable` instead
  * @param partitionCount
  *   partitions summed over every topic, or `None` when the scrape could not describe one of them. A sum over
  *   the topics that did answer is a number that looks measured and is wrong in the direction that reassures
  * @param sizeBytes
  *   bytes summed over every topic, or `None`. It refuses for the reason above *and* for its own, because
  *   `describeLogDirs` can fail on a cluster whose describe worked — so a partition total beside an absent
  *   size total is a state this document is meant to be able to express
  * @param incompleteTopics
  *   how many topics could not be described. It is what lets the screen say why a total is absent instead of
  *   showing an unexplained em dash under a count of 128
  */
final case class TopicStatisticsDto(
    topicCount: Int,
    partitionCount: Option[Long],
    sizeBytes: Option[Long],
    incompleteTopics: Int
)

object TopicStatisticsDto {

  given Codec[TopicStatisticsDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topicCount <- cursor.get[Int]("topicCount")
        partitionCount <- cursor.get[Option[Long]]("partitionCount")
        sizeBytes <- cursor.get[Option[Long]]("sizeBytes")
        incompleteTopics <- cursor.getOrElse[Int]("incompleteTopics")(0)
      } yield TopicStatisticsDto(topicCount, partitionCount, sizeBytes, incompleteTopics),
    (dto: TopicStatisticsDto) =>
      Json.obj(
        "topicCount" -> dto.topicCount.asJson,
        "partitionCount" -> dto.partitionCount.asJson,
        "sizeBytes" -> dto.sizeBytes.asJson,
        "incompleteTopics" -> dto.incompleteTopics.asJson
      )
  )

  given Schema[TopicStatisticsDto] = Schema
    .derived[TopicStatisticsDto]
    .description("Cluster-wide topic totals; each sum is absent rather than partial")

  given CanEqual[TopicStatisticsDto, TopicStatisticsDto] = CanEqual.derived
}

/** One cluster's topic statistics, `Section`-wrapped like every other read this service serves. */
final case class TopicStatisticsResponse(statistics: Section[TopicStatisticsDto])

object TopicStatisticsResponse {

  given Codec[TopicStatisticsResponse] = Codec.from(
    (cursor: HCursor) =>
      cursor.get[Section[TopicStatisticsDto]]("statistics").map(TopicStatisticsResponse(_)),
    (response: TopicStatisticsResponse) => Json.obj("statistics" -> response.statistics.asJson)
  )

  given Schema[TopicStatisticsResponse] =
    Schema.derived[TopicStatisticsResponse].description("The topics list's cluster-wide statistics region")

  given CanEqual[TopicStatisticsResponse, TopicStatisticsResponse] = CanEqual.derived
}

/** Every topic name on a cluster, and nothing else.
  *
  * The drawer's topic tree is a fold over names — favourites, prefix groups, one `internal` row — and the
  * fold is `frontend/packages/shell/src/nav/prefixes.ts`, a pure function of a list of strings. Paging the
  * full topic list to feed it would fetch ten thousand rows of counts, sizes and replica figures to learn ten
  * thousand strings, on every cluster switch. So this endpoint sends the strings.
  *
  * It is deliberately **not** paged. A page of names would make the drawer's totals depend on how far the
  * browser had scrolled a list it never shows, and the whole point of the tree is that its counts add up. Ten
  * thousand topic names is a few hundred kilobytes, which is smaller than one page of the list document.
  *
  * The names include the topics the scrape could not describe: those have no list row, they do exist, and a
  * tree that omitted them would tell an operator a topic is gone.
  */
final case class TopicNamesResponse(names: Section[List[TopicName]])

object TopicNamesResponse {

  given Codec[TopicNamesResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[Section[List[TopicName]]]("names").map(TopicNamesResponse(_)),
    (response: TopicNamesResponse) => Json.obj("names" -> response.names.asJson)
  )

  given Schema[TopicNamesResponse] = Schema
    .derived[TopicNamesResponse]
    .description("Every topic name on the cluster, for the drawer's tree; no counts and no paging")

  given CanEqual[TopicNamesResponse, TopicNamesResponse] = CanEqual.derived
}

/** What a forced refresh of a cluster's topic snapshot answers with: that it was accepted, not that it has
  * finished.
  *
  * 202 and not 200. The snapshot is taken asynchronously, so a 200 would claim data that does not exist yet;
  * `requestedAt` is the time the request was taken, which is what the button then has something truthful to
  * say about.
  *
  * The refresh is per **cluster** and not per topic. The snapshot is per cluster, so a per-topic refresh
  * would either refresh everything under a misleading name or invent a second level of caching to be wrong
  * about.
  *
  * It repeats the two fields of `kui.cluster.contract.dto.RefreshAcceptedDto` rather than importing it: this
  * module would otherwise take an edge on the whole cluster contract — every cluster, broker and profile DTO
  * — to reuse a pair of fields, and that edge would follow it into the browser bundle.
  */
final case class RefreshAcceptedDto(clusterId: ClusterId, requestedAt: Instant)

object RefreshAcceptedDto {

  given Codec[RefreshAcceptedDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        clusterId <- cursor.get[ClusterId]("clusterId")
        requestedAt <- cursor.get[Instant]("requestedAt")
      } yield RefreshAcceptedDto(clusterId, requestedAt),
    (dto: RefreshAcceptedDto) =>
      Json.obj("clusterId" -> dto.clusterId.asJson, "requestedAt" -> dto.requestedAt.asJson)
  )

  given Schema[RefreshAcceptedDto] = Schema
    .derived[RefreshAcceptedDto]
    .description("A topic-snapshot refresh was accepted; the snapshot is not new yet")

  given CanEqual[RefreshAcceptedDto, RefreshAcceptedDto] = CanEqual.derived
}

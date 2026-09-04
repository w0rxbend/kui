package kui.gateway.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.Section
import kui.contracts.topic.TopicDetailDto

/** Everything the topic page shows, in one document, with five independent levels of failure.
  *
  * The topic page is a shell with tabs, and each tab's data belongs to a different service: the topic itself
  * to `kui-topic`, the consumers to `kui-consumer` (M4), the connectors to `kui-connect` (M7), the ACLs to
  * `kui-security` (M7) and the schemas to `kui-schema` (M7). One request returns all five, and each may be
  * missing on its own, so the page renders the topic even when four other services do not exist — and gains
  * the Consumers tab in M4 by registration rather than by redesign.
  *
  * ==Why absent services are `NotConfigured` and not `Unavailable`==
  *
  * `Unavailable` means "this deployment has this thing and cannot reach it", and a screen shows it with its
  * reason so somebody can go and fix it. `NotConfigured` means "this deployment has no such thing", and a
  * screen hides it (ADR-032). In M2 the consumer, connect, security and schema services do not exist in any
  * build, so four red panels reading "unavailable" would appear on every topic page of every installation —
  * and an operator who is shown four permanent errors stops reading errors, including the one that matters.
  * The roadmap's wording said `Unavailable`; DEVPLAN §10 D10 corrects it and this is where the correction
  * lives.
  *
  * ==Why the four future sections carry `Json`==
  *
  * Their shape is the owning service's contract and it does not exist yet. A placeholder record invented here
  * would be a type M4 has to delete from a shared module in the milestone it is trying to add a tab in.
  * `Json` is honest about what is known, and in M2 the sections are `NotConfigured` in every deployment, so
  * nothing ever encodes or decodes one.
  *
  * @param generatedAt
  *   when this document was assembled, which is not when any section was fetched. `Section.Ok` and
  *   `Section.Stale` each carry their own `fetchedAt`, and the difference between the two is what a staleness
  *   overlay renders
  */
final case class TopicOverviewDto(
    topic: Section[TopicDetailDto],
    consumerGroups: Section[List[Json]],
    connectors: Section[List[Json]],
    acls: Section[List[Json]],
    schemas: Section[Json],
    generatedAt: Instant
)

object TopicOverviewDto {

  /** The section names, in the order the page's tabs appear.
    *
    * Named here rather than spelled out at each call site because three different things key on them: the
    * aggregation's `fillable` set, the span attribute per section, and the `section` label on the counter
    * that says how often a topic page is degraded and which part of it degrades.
    */
  val TopicSection: String = "topic"
  val ConsumerGroupsSection: String = "consumerGroups"
  val ConnectorsSection: String = "connectors"
  val AclsSection: String = "acls"
  val SchemasSection: String = "schemas"

  val sections: List[String] =
    List(TopicSection, ConsumerGroupsSection, ConnectorsSection, AclsSection, SchemasSection)

  /** Each section's status, by name, for the span attributes and the counter.
    *
    * Derived from the document rather than tracked alongside it while it is assembled: a separately
    * maintained list of statuses is a list that can disagree with the document it describes.
    */
  def statuses(dto: TopicOverviewDto): Map[String, String] =
    Map(
      TopicSection -> dto.topic.status,
      ConsumerGroupsSection -> dto.consumerGroups.status,
      ConnectorsSection -> dto.connectors.status,
      AclsSection -> dto.acls.status,
      SchemasSection -> dto.schemas.status
    )

  given Codec[TopicOverviewDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        topic <- cursor.get[Section[TopicDetailDto]](TopicSection)
        consumerGroups <- cursor.get[Section[List[Json]]](ConsumerGroupsSection)
        connectors <- cursor.get[Section[List[Json]]](ConnectorsSection)
        acls <- cursor.get[Section[List[Json]]](AclsSection)
        schemas <- cursor.get[Section[Json]](SchemasSection)
        generatedAt <- cursor.get[Instant]("generatedAt")
      } yield TopicOverviewDto(topic, consumerGroups, connectors, acls, schemas, generatedAt),
    (dto: TopicOverviewDto) =>
      Json.obj(
        TopicSection -> dto.topic.asJson,
        ConsumerGroupsSection -> dto.consumerGroups.asJson,
        ConnectorsSection -> dto.connectors.asJson,
        AclsSection -> dto.acls.asJson,
        SchemasSection -> dto.schemas.asJson,
        "generatedAt" -> dto.generatedAt.asJson
      )
  )

  given Schema[TopicOverviewDto] = Schema
    .derived[TopicOverviewDto]
    .description("Everything the topic page shows, with each part able to be missing on its own")

  given CanEqual[TopicOverviewDto, TopicOverviewDto] = CanEqual.derived
}

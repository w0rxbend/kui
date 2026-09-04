package kui.gateway.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.Section
import kui.contracts.capability.CapabilityState
import kui.contracts.cluster.ClusterRowDto

/** One cluster on the dashboard: what the cluster service knows about it, what the gateway's capability
  * registry says about KUI's ability to serve it, and the two totals the dashboard draws that come from other
  * services entirely.
  *
  * The registry only *decorates* the row. A cluster the registry has an entry for but the cluster service did
  * not return is not invented here: the list is the cluster service's answer, and a gateway that added rows
  * of its own would be holding cluster state, which is the one thing ADR-004 says it must not do.
  *
  * ==Three independent statuses on one row==
  *
  * `cluster.summary`, `topics` and `consumerGroups` fail separately, and the dashboard is the screen where
  * that matters most: it is the product's argument in one picture. A cluster whose brokers are answering but
  * whose consumer service is down shows its broker count and its topic totals beside a consumer panel that
  * says why it is empty. Folding the three into one status would make one dead service blank the row.
  *
  * Both new sections are `NotConfigured` in a deployment with no topic or no consumer service — hidden, not
  * shown as an error (ADR-032) — and default to that when an older gateway's document is decoded, so a
  * browser from a newer build still reads an older answer.
  *
  * @param topics
  *   the cluster's topic and partition totals, from the topic service
  * @param consumerGroups
  *   the cluster's consumer groups by state, from the consumer service
  */
final case class ClusterOverviewRow(
    cluster: ClusterRowDto,
    capability: CapabilityState,
    topics: Section[TopicTotalsDto] = Section.NotConfigured,
    consumerGroups: Section[GroupTotalsDto] = Section.NotConfigured
)

object ClusterOverviewRow {

  given Codec[ClusterOverviewRow] = Codec.from(
    (cursor: HCursor) =>
      for {
        cluster <- cursor.get[ClusterRowDto]("cluster")
        capability <- cursor.get[CapabilityState]("capability")
        topics <- cursor.getOrElse[Section[TopicTotalsDto]]("topics")(Section.NotConfigured)
        groups <- cursor.getOrElse[Section[GroupTotalsDto]]("consumerGroups")(Section.NotConfigured)
      } yield ClusterOverviewRow(cluster, capability, topics, groups),
    (row: ClusterOverviewRow) =>
      Json.obj(
        "cluster" -> row.cluster.asJson,
        "capability" -> row.capability.asJson,
        "topics" -> row.topics.asJson,
        "consumerGroups" -> row.consumerGroups.asJson
      )
  )

  given Schema[ClusterOverviewRow] =
    Schema.derived[ClusterOverviewRow].description("One dashboard row and what KUI can currently do with it")

  given CanEqual[ClusterOverviewRow, ClusterOverviewRow] = CanEqual.derived
}

/** The dashboard, in one document, with two independent levels of failure.
  *
  *   - `clusters` is the **outer** section: can the gateway see the list of clusters at all? `Ok` when the
  *     cluster service answered, `Stale` when it did not and the gateway is serving the last rows it saw,
  *     `Unavailable` when it did not and there are none to serve.
  *   - each row's `cluster.summary` is the **inner** section: can the cluster service see *that* cluster's
  *     brokers?
  *
  * The two are different events with different fixes — "KUI's cluster service is down" and "this Kafka
  * cluster is unreachable" — and a screen has to be able to say which one happened. A single flat status
  * could express one of them, and the milestone's exit criteria name both: an unreachable cluster leaves the
  * other rows populated, and a stopped cluster service leaves every row greyed and timestamped but still
  * readable.
  */
final case class ClusterOverviewDto(clusters: Section[List[ClusterOverviewRow]], generatedAt: Instant)

object ClusterOverviewDto {

  /** The number of rows whose own summary could not be produced, for the span attribute and for a caller that
    * wants to say "2 of 3 clusters are reachable" without walking the list twice.
    */
  def unavailableRows(dto: ClusterOverviewDto): Int =
    dto.clusters.toOption.toList.flatten.count(row => row.cluster.summary.toOption.isEmpty)

  def totalRows(dto: ClusterOverviewDto): Int = dto.clusters.toOption.toList.flatten.size

  given Codec[ClusterOverviewDto] = Codec.from(
    (cursor: HCursor) =>
      for {
        clusters <- cursor.get[Section[List[ClusterOverviewRow]]]("clusters")
        generatedAt <- cursor.get[Instant]("generatedAt")
      } yield ClusterOverviewDto(clusters, generatedAt),
    (dto: ClusterOverviewDto) =>
      Json.obj("clusters" -> dto.clusters.asJson, "generatedAt" -> dto.generatedAt.asJson)
  )

  given Schema[ClusterOverviewDto] = Schema
    .derived[ClusterOverviewDto]
    .description("Every configured cluster, with a status for the list and a status for each row")

  given CanEqual[ClusterOverviewDto, ClusterOverviewDto] = CanEqual.derived
}

package kui.cluster.contract.dto

import java.time.Instant

import io.circe.syntax.*
import io.circe.{Codec, HCursor, Json}
import sttp.tapir.Schema

import kui.contracts.ErrorEnvelope.given
import kui.contracts.KernelCodecs.given
import kui.contracts.KernelSchemas.given
import kui.contracts.cluster.ClusterRowDto
import kui.kernel.ClusterId

/** Every configured cluster, each with whatever the last scrape of it produced.
  *
  * `items` is a plain list rather than a `Section`, and that is deliberate. The *list* of configured clusters
  * comes from the registry — local configuration overlaid by the replayed metadata store — so it is available
  * whenever the service is. What can fail is each cluster's own summary, and that is where the `Section`
  * sits, one per row. A dead cluster therefore costs one row's data, never the page.
  *
  * @param generatedAt
  *   when the response was assembled, which is not when any cluster was scraped. Each row carries its own
  *   `fetchedAt`, and the difference between the two is exactly what a staleness overlay renders
  */
final case class ClustersResponse(items: List[ClusterRowDto], generatedAt: Instant)

object ClustersResponse {

  given Codec[ClustersResponse] = Codec.from(
    (cursor: HCursor) =>
      for {
        items <- cursor.getOrElse[List[ClusterRowDto]]("items")(Nil)
        generatedAt <- cursor.get[Instant]("generatedAt")
      } yield ClustersResponse(items, generatedAt),
    (response: ClustersResponse) =>
      Json.obj("items" -> response.items.asJson, "generatedAt" -> response.generatedAt.asJson)
  )

  given Schema[ClustersResponse] =
    Schema.derived[ClustersResponse].description("Every configured cluster and its last known state")

  given CanEqual[ClustersResponse, ClustersResponse] = CanEqual.derived
}

/** One cluster, for a deep link or a broker page that must not fetch forty rows to draw one header. */
final case class ClusterDetailResponse(cluster: ClusterRowDto)

object ClusterDetailResponse {

  given Codec[ClusterDetailResponse] = Codec.from(
    (cursor: HCursor) => cursor.get[ClusterRowDto]("cluster").map(ClusterDetailResponse(_)),
    (response: ClusterDetailResponse) => Json.obj("cluster" -> response.cluster.asJson)
  )

  given Schema[ClusterDetailResponse] =
    Schema.derived[ClusterDetailResponse].description("One configured cluster and its last known state")

  given CanEqual[ClusterDetailResponse, ClusterDetailResponse] = CanEqual.derived
}

/** What a forced refresh answers with: that it was accepted, not that it has finished.
  *
  * The snapshot loop owns when a cluster is next read (DEVPLAN D10: 30 seconds server-side, and the browser
  * never polls). A forced refresh asks that loop to run now, and the honest answer is 202 with the time the
  * request was taken — which is what the button then shows — rather than a 200 claiming data that does not
  * exist yet.
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

  given Schema[RefreshAcceptedDto] =
    Schema.derived[RefreshAcceptedDto].description("A refresh was accepted; the snapshot is not new yet")

  given CanEqual[RefreshAcceptedDto, RefreshAcceptedDto] = CanEqual.derived
}

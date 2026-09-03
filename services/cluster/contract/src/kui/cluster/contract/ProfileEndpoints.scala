package kui.cluster.contract

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody

import kui.cluster.contract.dto.{ClusterProfileDto, ProfileResult}
import kui.contracts.KernelSchemas.given
import kui.contracts.{ErrorEnvelope, KuiEndpoint}
import kui.kernel.ClusterId
import kui.security.SignedPrincipal

/** How another KUI service learns a cluster's connection settings, and how it is told they changed.
  *
  * This is the server half of ADR-036's distribution sentence: resolved profiles at
  * `GET /internal/v1/clusters/{id}/profile` with an `ETag`, change notifications on an SSE stream, and
  * consumers that keep the last profile they saw, poll as a fallback, and rebuild their clients when the
  * version moves. It is built now, in M1, while there is exactly one producer and no consumer — the cheapest
  * possible moment to get a distribution mechanism wrong and fix it.
  *
  * The stream endpoint is not here but in the `api` module (`ClusterStreamEndpoint`), for the same reason
  * `CapabilityRoutes.streamEndpoint` is: describing an event stream needs `fs2`, which has no business in a
  * module that must link for the browser.
  */
object ProfileEndpoints {

  val ProfileSegment: String = "profile"
  val StreamSegment: String = "stream"

  /** The conditional-request header a consumer sends back the ETag in. */
  val IfNoneMatchHeader: String = "If-None-Match"

  val ETagHeader: String = "ETag"

  /** The wildcard `If-None-Match` value. A client sending it is asking for the profile whatever it holds. */
  val AnyEtag: String = "*"

  /** `GET /internal/v1/clusters/{clusterId}/profile`.
    *
    * Two outcomes, modelled as a `oneOf` rather than an optional body: 200 with the profile and its `ETag`,
    * or 304 with the `ETag` and nothing else. A generated client and the generated document therefore both
    * know that 304 is a normal answer rather than an error, which is the difference between a consumer that
    * polls cheaply and one that logs a failure every minute.
    */
  val profile: Endpoint[SignedPrincipal, (ClusterId, Option[String]), ErrorEnvelope, ProfileResult, Any] =
    KuiEndpoint.internal.get
      .in(
        "internal" / "v1" / ClusterEndpoints.ClustersSegment /
          path[ClusterId](ClusterEndpoints.ClusterIdParam)
            .description("The configured cluster's slug id") / ProfileSegment
      )
      .in(
        header[Option[String]](IfNoneMatchHeader)
          .description("The ETag the caller already holds; '*' always fetches")
      )
      .out(
        oneOf[ProfileResult](
          oneOfVariant(
            statusCode(StatusCode.Ok)
              .and(header[String](ETagHeader))
              .and(jsonBody[ClusterProfileDto])
              .mapTo[ProfileResult.Current]
          ),
          oneOfVariant(
            statusCode(StatusCode.NotModified)
              .and(header[String](ETagHeader))
              .mapTo[ProfileResult.NotModified]
          )
        )
      )
      .name("cluster.profile")
      .summary("A cluster's resolved connection settings, for another KUI service")
      .description(
        "The ETag is the profile's store version. A caller keeps the last profile it saw and re-fetches " +
          "with If-None-Match; an unchanged profile answers 304 with no body. Every credential is " +
          "removed: M1 has no consumer that builds a Kafka client from this."
      )
      .tag("cluster")

  /** Every endpoint declared in this file. */
  val all: List[AnyEndpoint] = List(profile)
}
